package org.readium.r2.testapp.reader

import android.app.Application
import androidx.core.os.bundleOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import org.readium.r2.navigator.ExperimentalAudiobook
import org.readium.r2.navigator.media2.MediaNavigator
import org.readium.r2.navigator.media2.MediaSessionNavigatorCompat
import org.readium.r2.shared.extensions.putPublication
import org.readium.r2.shared.publication.Publication
import org.readium.r2.testapp.R2App
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalAudiobook::class, ExperimentalTime::class)
class AudioReaderFragmentViewModel(
    application: Application,
    publication: Publication
) : ViewModel() {

    var navigator: MediaNavigator = MediaSessionNavigatorCompat(
        context = application,
        publication = publication,
        sessionToken = (application as R2App).sessionToken,
        connectionHints = bundleOf().apply {  putPublication(publication) }
    ).apply {
        play()
    }

    class Factory(private val application: Application, private val publication: Publication)
        : ViewModelProvider.Factory {

        override fun <T : ViewModel?> create(modelClass: Class<T>): T =
            if (modelClass.isAssignableFrom(AudioReaderFragmentViewModel::class.java))
                AudioReaderFragmentViewModel(application, publication) as T
            else
                throw IllegalStateException("Cannot instantiate ${modelClass::class.java.name}")
    }
}

