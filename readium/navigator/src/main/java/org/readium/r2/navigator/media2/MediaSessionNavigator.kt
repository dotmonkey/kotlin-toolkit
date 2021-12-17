package org.readium.r2.navigator.media2

import android.content.Context
import android.os.Bundle
import androidx.media2.common.MediaMetadata
import androidx.media2.session.MediaController
import androidx.media2.session.SessionToken
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.readium.r2.navigator.ExperimentalAudiobook
import org.readium.r2.navigator.extensions.sum
import org.readium.r2.navigator.extensions.time
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.toLocator
import timber.log.Timber
import java.util.concurrent.Executors
import kotlin.time.Duration
import kotlin.time.ExperimentalTime

@ExperimentalAudiobook
@OptIn(ExperimentalTime::class)
class MediaSessionNavigator internal constructor(
  private val coroutineScope: CoroutineScope,
  private val controllerMutex: Mutex,
  private val controllerFacade: MediaControllerFacade,
  private val navigatorState: State,
  val configuration: Configuration,
) {
  val playerState: Flow<PlayerState>
    get() = navigatorState.playerState

  val currentItem: Flow<MediaMetadata>
    get() = navigatorState.currentItem

  val currentPosition: Flow<Duration>
    get() = navigatorState.currentPosition

  val bufferedPosition: Flow<Duration>
    get() = navigatorState.bufferedPosition

  val currentLocator: Flow<Locator>
    get() = navigatorState.currentLocator

  private fun<T> Flow<T>.stateInFirst(): StateFlow<T> =
  stateIn(coroutineScope, SharingStarted.Lazily, runBlocking { first() })

  val playbackRate: Double
    get() = checkNotNull(controllerFacade.playbackSpeed)

  val playlist: List<MediaMetadata>
    get() = checkNotNull(controllerFacade.playlist).map { it.metadata!! }

  val totalDuration: Duration
    get() = playlist.map { it.duration }.sum()

  suspend fun prepare() {
    navigatorState.playerState.first()
    controllerFacade.prepare()
  }

  suspend fun setPlaybackRate(rate: Double) {
    controllerFacade.setPlaybackSpeed(rate)
  }

  suspend fun play() = controllerMutex.withLock {
    controllerFacade.play()
  }

  suspend fun pause() = controllerMutex.withLock {
    controllerFacade.pause()
  }

  suspend fun go(link: Link) =
    go(link.toLocator())

  suspend fun go(locator: Locator) =  controllerMutex.withLock {
    controllerFacade.onPause {
      Timber.d("Go to locator $locator")
      val itemIndex = checkNotNull(controllerFacade.playlist).indexOfFirstWithHref(locator.href)
      if (itemIndex == controllerFacade.currentItem!!.metadata!!.index) {
        val position = locator.locations.time ?: Duration.ZERO
        controllerFacade.seekTo(position.inWholeMilliseconds)
      } else {
        controllerFacade.skipToPlaylistItem(itemIndex)
        locator.locations.time?.let { controllerFacade.seekTo(it.inWholeMilliseconds) }
      }
    }
  }

  suspend fun goForward() = smartSeek(configuration.skipForwardInterval)

  suspend fun goBackward() = smartSeek(-configuration.skipBackwardInterval)

  private suspend fun smartSeek(offset: Duration) =  controllerMutex.withLock {
    controllerFacade.onPause {
      val(newIndex, newPosition) = SmartSeeker.dispatchSeek(
        offset,
        controllerFacade.currentPosition!!,
        controllerFacade.currentItem!!.metadata!!.index,
        playlist.map { it.duration }
      )
      Timber.d("Smart seeking by $offset resolved to item $newIndex position $newPosition")
      controllerFacade.skipToPlaylistItem(newIndex)
      controllerFacade.seekTo(newPosition.inWholeMilliseconds)
    }
  }

  suspend fun seekTo(position: Duration) = controllerMutex.withLock{
    require(!position.isNegative())
    controllerFacade.seekTo(position.inWholeMilliseconds)
  }

  suspend fun seekBy(offset: Duration) = controllerMutex.withLock{
    controllerFacade.onPause {
      controllerFacade.seekBy(offset.inWholeMilliseconds)
    }
  }

  fun close() {
    controllerFacade.close()
    coroutineScope.cancel()
  }

  data class Configuration(
    val positionRefreshRate: Double = 2.0,  // Hz
    val skipForwardInterval: Duration = Duration.seconds(30),
    val skipBackwardInterval: Duration = Duration.seconds(30),
  )

  internal data class State(
    val playerState: Flow<PlayerState>,
    val currentItem: Flow<MediaMetadata>,
    val currentPosition: Flow<Duration>,
    val bufferedPosition: Flow<Duration>,
    val currentLocator: Flow<Locator>,
  )

  companion object {

    private fun MediaControllerFacade.locatorNow(item: MediaMetadata, position: Duration): Locator {
      Timber.d("locatorNow ${this.currentItem} $item ${this.currentPosition} $position ${this.playlist}")
      val playlist = checkNotNull(this.playlist)

      fun itemStartPosition(index: Int) =
        playlist.slice(0 until index).map { it.metadata!!.duration }.sum()

      fun totalProgression(item: MediaMetadata, position: Duration) =
        (itemStartPosition(item.index) + position) / playlist.map { it.metadata!!.duration }.sum()

      return Locator(
        href = item.href,
        title = item.title,
        type = item.type ?: "",
        locations = Locator.Locations(
          fragments = listOf("t=${position.inWholeSeconds}"),
          progression = position / item.duration,
          position = item.index,
          totalProgression = totalProgression(item, position)
        )
      )
    }

    internal data class Components(
      val coroutineScope: CoroutineScope,
      val controllerMutex: Mutex,
      val controllerFacade: MediaControllerFacade,
      val controllerState: MediaControllerState,
      val navigatorState: State
    )

    suspend fun create(
      context: Context,
      sessionToken: SessionToken,
      connectionHints: Bundle,
      configuration: Configuration = Configuration()
    ): MediaSessionNavigator {
      val components = createComponents(context, sessionToken, connectionHints, configuration)

      // Wait for the MediaController being connected and the playlist being ready.

      components.controllerState.connectedState.first { it }
      components.controllerState.playerState.first()
      components.controllerFacade.prepare()

      return MediaSessionNavigator(
        components.coroutineScope,
        components.controllerMutex,
        components.controllerFacade,
        components.navigatorState,
        configuration,
      )
    }

    internal fun createComponents(
      context: Context,
      sessionToken: SessionToken,
      connectionHints: Bundle,
      configuration: Configuration = Configuration()
    ): Components {
      val coroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
      val controllerExecutor = Executors.newSingleThreadExecutor()

      val positionRefreshDelay = Duration.seconds((1.0 / configuration.positionRefreshRate))
      val controllerState = MediaControllerState(coroutineScope, positionRefreshDelay)

      val mediaController = MediaController.Builder(context)
        .setConnectionHints(connectionHints)
        .setSessionToken(sessionToken)
        .setControllerCallback(controllerExecutor, controllerState)
        .build()

      val controllerMutex = Mutex()
      val controllerFacade = MediaControllerFacade(mediaController, controllerExecutor)

      val currentLocator: Flow<Locator> =
        combine(controllerState.currentItem, controllerState.currentPosition) { currentItem, currentPosition ->
          controllerFacade.locatorNow(currentItem, currentPosition)
        }

      val navigatorState = State(
        controllerState.playerState,
        controllerState.currentItem,
        controllerState.currentPosition,
        controllerState.bufferedPosition,
        currentLocator
      )

      return Components(coroutineScope, controllerMutex, controllerFacade, controllerState, navigatorState)
    }


    internal fun <T> StateFlow<T?>.asNonNullable(coroutineScope: CoroutineScope): StateFlow<T> =
      filterNotNull().stateIn(coroutineScope, SharingStarted.Lazily, value!!)
  }
}