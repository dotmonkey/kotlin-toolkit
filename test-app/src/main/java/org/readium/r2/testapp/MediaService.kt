package org.readium.r2.testapp

import androidx.media2.session.MediaSession
import androidx.media2.session.MediaSessionService
import org.readium.r2.navigator.media2.PublicationPlayerFactory
import org.readium.r2.shared.extensions.getPublicationOrNull
import timber.log.Timber
import kotlin.time.ExperimentalTime

@ExperimentalTime
class MediaService : MediaSessionService() {

    private val playerFactory = PublicationPlayerFactory()

    private var mediaSession: MediaSession? = null

    private var publicationId: String? = null

    override fun onCreate() {
        super.onCreate()
        Timber.d("MediaService created.")
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        Timber.d("onGetSession called with hints ${controllerInfo.connectionHints}.")

        val publication = controllerInfo.connectionHints.getPublicationOrNull()
            ?: run {
                Timber.d("No publication passed to onGetSession. Returning current session if any.")
                return mediaSession
            }

        if (publicationId != null && publicationId == publication.metadata.identifier) {
            Timber.d("Publication $publicationId is being played. Returning current session.")
            return mediaSession
        }

        val player = playerFactory.open(applicationContext, publication)
            ?: run {
                Timber.e("Publication ${publication.metadata.identifier} not supported by any engine.")
                return null
            }

        return when (val currentSession = mediaSession) {
            null -> {
                Timber.d("Creating MediaSession for publication ${publication.metadata.identifier}.")
                val session = MediaSession.Builder(applicationContext, player).build()
                mediaSession = session
                publicationId = publication.metadata.identifier
                session
            }
            else -> {
                Timber.d("Updating MediaSession for publication ${publication.metadata.identifier}.")
                currentSession.player.close()
                currentSession.updatePlayer(player)
                publicationId = publication.metadata.identifier
                currentSession
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Timber.d("MediaService destroyed.")
    }
}