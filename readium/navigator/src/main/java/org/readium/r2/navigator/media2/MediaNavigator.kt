package org.readium.r2.navigator.media2

import androidx.media2.common.MediaMetadata
import kotlinx.coroutines.flow.StateFlow
import org.readium.r2.navigator.ExperimentalAudiobook
import org.readium.r2.navigator.Navigator
import kotlin.time.Duration
import kotlin.time.ExperimentalTime

/**
 * A navigator rendering an audio or video publication.
 */
@ExperimentalAudiobook
@OptIn(ExperimentalTime::class)
interface MediaNavigator : Navigator {

    val currentItem: StateFlow<MediaMetadata?>

    val currentPosition: StateFlow<Duration?>

    val bufferedPosition: StateFlow<Duration?>

    /**
     * Indicates the navigator current state.
     */
    val playerState: StateFlow<PlayerState?>

    /**
     * Sets the speed of the media playback.
     *
     * Normal speed is 1.0 and 0.0 is incorrect.
     */
    fun setPlaybackRate(rate: Double)

    /**
     * Resumes or start the playback at the current location.
     */
    fun play()

    /**
     * Pauses the playback.
     */
    fun pause()

    /**
     * Stops the playback.
     *
     * Compared to [pause], the navigator may clear its state in whatever way is appropriate. For
     * example, recovering a player's resources.
     */
    fun close()

    /**
     * Seeks to the given time in the current resource.
     */
    fun seekTo(position: Duration)

    /**
     * Seeks relatively from the current position in the current resource.
     */
    fun seekRelative(offset: Duration)
}