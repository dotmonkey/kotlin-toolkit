package org.readium.r2.testapp.reader

import android.media.AudioManager
import android.os.Bundle
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.readium.r2.navigator.ExperimentalAudiobook
import org.readium.r2.navigator.media2.MediaNavigator
import org.readium.r2.navigator.media2.PlayerState
import org.readium.r2.navigator.media2.duration
import org.readium.r2.shared.publication.services.cover
import org.readium.r2.testapp.R
import org.readium.r2.testapp.databinding.FragmentAudiobookBinding
import kotlin.time.Duration
import kotlin.time.DurationUnit
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalAudiobook::class, ExperimentalTime::class)
class AudioReaderFragment2 : BaseReaderFragment() {

    override val model: ReaderViewModel by activityViewModels()
    private val audioModel: AudioReaderFragmentViewModel by viewModels(factoryProducer = {
        AudioReaderFragmentViewModel.Factory(requireActivity().application, model.publication)
    })

    override val navigator: MediaNavigator get() = audioModel.navigator

    private var binding: FragmentAudiobookBinding? = null
    private var isSeeking = false
    private var isPlaying = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        binding = FragmentAudiobookBinding.inflate(inflater, container, false)
        return binding?.root
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val binding = requireNotNull(binding)

        binding.publicationTitle.text = model.publication.metadata.title

        viewLifecycleOwner.lifecycleScope.launch {
            model.publication.cover()?.let {
                binding.coverView.setImageBitmap(it)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            navigator.playerState.collectLatest { state ->
                isPlaying = state != PlayerState.Paused
                when (state) {
                    PlayerState.Paused, PlayerState.Playing -> {
                        binding.playPause.isEnabled = true
                        binding.playPause.setImageResource(
                            if (isPlaying) R.drawable.ic_baseline_pause_24
                            else R.drawable.ic_baseline_play_arrow_24
                        )
                    }
                    PlayerState.Error, null -> {
                        binding.playPause.isEnabled = false
                    }
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            navigator.currentItem.collectLatest { metadata ->
                binding.timelineBar.max = metadata?.duration?.inWholeSeconds?.toInt() ?: 0
                binding.timelineDuration.text = metadata?.duration?.formatElapsedTime() ?: ""
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            navigator.currentPosition.collectLatest { position ->
                if (!isSeeking) {
                    binding.timelineBar.progress = position?.inWholeSeconds?.toInt() ?: 0
                    binding.timelinePosition.text = position?.formatElapsedTime() ?: ""
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            navigator.bufferedPosition.collectLatest { buffered ->
                binding.timelineBar.secondaryProgress = buffered?.inWholeSeconds?.toInt() ?: 0
            }
        }
    }

    override fun onResume() {
        super.onResume()
        activity?.volumeControlStream = AudioManager.STREAM_MUSIC
    }

    override fun onStart() {
        super.onStart()
        val binding = checkNotNull(binding)

        binding.timelineBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {

            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {}

            override fun onStartTrackingTouch(seekBar: SeekBar) {
                isSeeking = true
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                isSeeking = false
                this@AudioReaderFragment2.navigator.seekTo(Duration.seconds(seekBar.progress))
            }
        })

        binding.playPause.setOnClickListener { if (isPlaying) navigator.pause() else navigator.play() }
        binding.skipForward.setOnClickListener { navigator.goForward() }
        binding.skipBackward.setOnClickListener { navigator.goBackward() }
    }

    private fun Duration.formatElapsedTime(): String =
        DateUtils.formatElapsedTime(toLong(DurationUnit.SECONDS))

}
