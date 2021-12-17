package org.readium.r2.navigator.media2

import androidx.media2.common.MediaItem
import androidx.media2.common.MediaMetadata
import androidx.media2.common.SessionPlayer
import androidx.media2.session.MediaController
import androidx.media2.session.SessionResult
import com.google.common.util.concurrent.ListenableFuture
import org.readium.r2.navigator.ExperimentalAudiobook
import timber.log.Timber
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.time.Duration
import kotlin.time.ExperimentalTime

@ExperimentalAudiobook
@OptIn(ExperimentalTime::class)
internal class MediaControllerFacade(
    private val mediaController: MediaController,
    private val controllerExecutor: Executor,
) {
    val playerState: PlayerState?
        get() = PlayerState.from(mediaController.playerState)

    val currentPosition: Duration?
        get() = mediaController.currentPositionDuration

    val currentItem: MediaItem?
        get() = mediaController.currentMediaItem

    val playlist: List<MediaItem>?
        get() = mediaController.playlist

    val playbackSpeed: Double?
        get() = mediaController.playbackSpeedNullable

    private fun<T> ListenableFuture<T>.addListener(listener: Runnable): Unit =
        addListener(listener, controllerExecutor)

    private suspend fun<T> ListenableFuture<T>.suspendUntilCompletion(): T =
        suspendCoroutine { continuation -> addListener { continuation.resume(get()) } }

    //FIXME: This is not race-free. Implement a custom command instead?
    suspend fun<T> onPause(block: suspend () -> T ) {
        val wasPlaying = playerState == PlayerState.Playing
        pause()
        block()
        if (wasPlaying) play()
    }

    suspend fun prepare(): SessionResult {
        Timber.d("prepare called")
        val result = mediaController.prepare().suspendUntilCompletion()
        Timber.d("prepare finished with result ${result.resultCode}")
        return result
    }

    suspend fun play(): SessionResult =
        mediaController.play().suspendUntilCompletion()

    suspend fun pause(): SessionResult =
        mediaController.pause().suspendUntilCompletion()

    suspend fun setPlaybackSpeed(speed: Double): SessionResult =
        mediaController.setPlaybackSpeed(speed.toFloat()).suspendUntilCompletion()

    suspend fun seekTo(position: Long): SessionResult =
        mediaController.seekTo(position).suspendUntilCompletion()

    suspend fun seekBy(offset: Long): SessionResult = when (val positionNow = currentPosition) {
        null ->
            SessionResult(SessionResult.RESULT_ERROR_INVALID_STATE, null)
        else ->
            mediaController.seekTo((positionNow.inWholeMilliseconds + offset).coerceAtLeast(0))
                .suspendUntilCompletion()
    }

    suspend fun skipToPlaylistItem(index: Int): SessionResult =
        mediaController.skipToPlaylistItem(index).suspendUntilCompletion()

    fun close() {
        mediaController.close()
    }
}