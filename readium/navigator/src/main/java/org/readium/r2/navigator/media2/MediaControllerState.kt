package org.readium.r2.navigator.media2

import androidx.media2.common.MediaItem
import androidx.media2.common.MediaMetadata
import androidx.media2.common.SessionPlayer
import androidx.media2.session.MediaController
import androidx.media2.session.SessionCommandGroup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import kotlin.time.Duration
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalTime::class)
internal class MediaControllerState(
    private val coroutineScope: CoroutineScope,
    private val positionRefreshDelay: Duration,
) : MediaController.ControllerCallback() {

    val connectedState: StateFlow<Boolean>
        get() = _connectedState

    val playerState: Flow<PlayerState>
        get() = _playerState.filterNotNull().distinctUntilChanged()

    val currentItem: Flow<MediaMetadata>
        get() = _currentItem.filterNotNull().distinctUntilChanged()

    val currentPosition: Flow<Duration>
        get() = _currentPosition.filterNotNull().distinctUntilChanged()

    val bufferedPosition: Flow<Duration>
        get() = _bufferedPosition.filterNotNull().distinctUntilChanged()

    private val _connectedState = MutableStateFlow(false)

    private val _playerState = MutableSharedFlow<PlayerState?>(
        replay = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    private val _currentItem = MutableSharedFlow<MediaMetadata?>(
        replay = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    private val _currentPosition = MutableSharedFlow<Duration?>(
        replay = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    private val _bufferedPosition = MutableSharedFlow<Duration?>(
        replay = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    private lateinit var controller: MediaController

    override fun onConnected(controller: MediaController, allowedCommands: SessionCommandGroup) {
        Timber.d("MediaController connected to the session")
        this.controller = controller
        this._connectedState.value = true

        // These two callbacks might not have been called if the player had been set up before
        // the controller was connected to the session.
        onPlayerStateChanged(controller, controller.playerState)
        onCurrentMediaItemChanged(controller, controller.currentMediaItem)

        coroutineScope.launch {
            while (isActive) {
                Timber.d("Check position")
                _currentPosition.tryEmit(controller.currentPositionDuration)
                _bufferedPosition.tryEmit(controller.bufferedPositionDuration)
                delay(positionRefreshDelay)
            }
        }
    }

    override fun onDisconnected(controller: MediaController) {
        Timber.d("MediaController disconnected from the session")
        this._connectedState.value = false
    }

    override fun onPlayerStateChanged(controller: MediaController, state: Int) {
        Timber.d("onPlayerStateChanged $state")
        _playerState.tryEmit(PlayerState.from(state))
    }

    override fun onCurrentMediaItemChanged(controller: MediaController, item: MediaItem?) {
        Timber.d("onCurrentMediaItemChanged $item")
        _currentItem.tryEmit(item?.metadata)
    }
}

