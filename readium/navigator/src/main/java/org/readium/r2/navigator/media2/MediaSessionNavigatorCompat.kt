package org.readium.r2.navigator.media2

import android.content.Context
import android.os.Bundle
import androidx.media2.common.MediaMetadata
import androidx.media2.session.SessionToken
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.readium.r2.navigator.ExperimentalAudiobook
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import timber.log.Timber
import java.util.concurrent.Executors
import kotlin.time.Duration
import kotlin.time.ExperimentalTime

@ExperimentalAudiobook
@OptIn(ExperimentalTime::class)
class MediaSessionNavigatorCompat(
    context: Context,
    override val publication: Publication,
    sessionToken: SessionToken,
    connectionHints: Bundle,
    private val configuration: MediaSessionNavigator.Configuration = MediaSessionNavigator.Configuration(),
) : MediaNavigator {

    /**
     * This must be a single thread executor in order for commands to be executed in the right order
     * once the navigator is initialized.
     */

    private val executor =
        Executors.newSingleThreadExecutor()

    private val coroutineScope =
        CoroutineScope(executor.asCoroutineDispatcher())

    private val stateCompat: StateCompat

    private val navigator: Deferred<MediaSessionNavigator>

    init {
        Timber.d("Going to create components")
        val components = MediaSessionNavigator.createComponents(
            context, sessionToken, connectionHints, configuration
        )

        stateCompat = StateCompat.fromState(coroutineScope, components.navigatorState)

        Timber.d("Going to create navigator")
        navigator = coroutineScope.async {
            MediaSessionNavigator(
                components.coroutineScope,
                components.controllerMutex,
                components.controllerFacade,
                components.navigatorState,
                configuration,
            ).apply {
                Timber.d("MediaSessionNavigator available")
                components.controllerState.connectedState.first { it }
                Timber.d("connected")
                components.controllerFacade.prepare()
                Timber.d("prepared")
                /*components.controllerState.playerState.first()
                Timber.d("playerState available")
                // Then, we ensure all other states are available.
                components.controllerState.currentItem.first()
                components.controllerState.currentPosition.first()
                components.controllerState.bufferedPosition.first()*/
            }
        }
    }

    override val currentLocator: StateFlow<Locator>
        get() = stateCompat.currentLocator

    override val currentItem: StateFlow<MediaMetadata?>
        get() = stateCompat.currentItem

    override val currentPosition: StateFlow<Duration?>
        get() = stateCompat.currentPosition

    override val bufferedPosition: StateFlow<Duration?>
        get() = stateCompat.bufferedPosition

    override val playerState: StateFlow<PlayerState?>
        get() = stateCompat.playerState

    override fun setPlaybackRate(rate: Double) {
        launch { navigator.await().setPlaybackRate(rate) }
    }

    override fun play() {
        launch {
            val result = navigator.await().play()
            Timber.d("play finished with result code ${result.resultCode}")
        }
    }

    override fun pause() {
        launch { navigator.await().pause() }
    }

    override fun close() {
        launch { navigator.await().close() }
        navigator.cancel()
        executor.shutdown()
    }

    override fun seekTo(position: Duration) {
        launch { navigator.await().seekTo(position) }
    }

    override fun seekRelative(offset: Duration) {
        launch { navigator.await().seekBy(offset) }
    }

    override fun go(locator: Locator, animated: Boolean, completion: () -> Unit): Boolean {
        launchAndRun({ navigator.await().go(locator) }, completion)
        return true
    }

    override fun go(link: Link, animated: Boolean, completion: () -> Unit): Boolean {
        launchAndRun({ navigator.await().go(link) }, completion)
        return true
    }

    override fun goForward(animated: Boolean, completion: () -> Unit): Boolean {
        launchAndRun({ navigator.await().goForward() }, completion)
        return true
    }

    override fun goBackward(animated: Boolean, completion: () -> Unit): Boolean {
        launchAndRun({ navigator.await().goBackward() }, completion)
        return true
    }

    private fun launch(runnable: suspend () -> Unit) =
        coroutineScope.launch{ runnable() }

    private fun launchAndRun(runnable: suspend () -> Unit, callback: () -> Unit) =
        coroutineScope.launch { runnable() }.invokeOnCompletion { callback() }

    private data class StateCompat(
        val playerState: StateFlow<PlayerState?>,
        val currentItem: StateFlow<MediaMetadata?>,
        val currentPosition: StateFlow<Duration?>,
        val bufferedPosition: StateFlow<Duration?>,
        val currentLocator: StateFlow<Locator>
    ) {
        companion object {

            fun fromState(
                coroutineScope: CoroutineScope,
                state: MediaSessionNavigator.State
            ): StateCompat {

                fun<T> Flow<T>.startWithNull(coroutineScope: CoroutineScope) =
                    stateIn(coroutineScope, SharingStarted.Lazily, null)

                val dummyLocator = Locator(href = "#", type = "")

                return StateCompat(
                    state.playerState.startWithNull(coroutineScope),
                    state.currentItem.startWithNull(coroutineScope),
                    state.currentPosition.startWithNull(coroutineScope),
                    state.bufferedPosition.startWithNull(coroutineScope),
                    state.currentLocator.stateIn(coroutineScope, SharingStarted.Lazily, dummyLocator)
                )
            }
        }
    }
}