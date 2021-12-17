package org.readium.r2.navigator.media2

import androidx.media2.common.SessionPlayer.*
import org.readium.r2.navigator.ExperimentalAudiobook
import kotlin.time.ExperimentalTime

@ExperimentalAudiobook
enum class PlayerState {
    Paused,
    Playing,
    Error;

    companion object {
        internal fun from(sessionPlayerState: Int) = when (sessionPlayerState) {
            PLAYER_STATE_IDLE -> null
            PLAYER_STATE_PAUSED -> Paused
            PLAYER_STATE_PLAYING -> Playing
            else -> Error
        }
    }
}