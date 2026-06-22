package com.example.wartealarm

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.example.wartealarm.databinding.FragmentSecondBinding
import com.example.wartealarm.data.ConnectionState
import com.example.wartealarm.domain.AlarmSettings
import com.example.wartealarm.domain.MyStatus
import com.example.wartealarm.domain.WatchParams
import com.example.wartealarm.service.WatchService
import com.example.wartealarm.ui.MainViewModel
import com.google.android.material.materialswitch.MaterialSwitch
import kotlinx.coroutines.launch

/**
 * Live status + settings screen.
 *
 * Renders the watcher's live state observed from the [com.example.wartealarm.core.WatchBus] (via the
 * [MainViewModel]) — connection, a coarse-mode banner, and the current [MyStatus] — and exposes the
 * persisted [AlarmSettings] controls, writing every change straight back through the ViewModel.
 *
 * "Stop watching" stops [WatchService] and navigates back to the entry screen.
 */
class SecondFragment : Fragment() {

    private var _binding: FragmentSecondBinding? = null

    // Valid only between onCreateView and onDestroyView.
    private val binding get() = _binding!!

    private val viewModel: MainViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentSecondBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        bindSettingsControls()

        binding.stopWatchingButton.setOnClickListener {
            WatchService.stop(requireContext())
            findNavController().navigate(R.id.action_SecondFragment_to_FirstFragment)
        }

        observeState()
    }

    /** Collects the live status and persisted settings flows, only while the view is at least STARTED. */
    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.connection.collect(::renderConnection) }
                launch { viewModel.coarseMode.collect(::renderCoarseBanner) }
                launch { viewModel.status.collect(::renderStatus) }
                launch { viewModel.params.collect(::renderWatchingSummary) }
                launch { viewModel.settings.collect(::renderSettings) }
            }
        }
    }

    // --- Rendering ------------------------------------------------------------------------------

    private fun renderConnection(state: ConnectionState) {
        binding.connectionText.text = getString(
            when (state) {
                ConnectionState.DISCONNECTED -> R.string.connection_disconnected
                ConnectionState.CONNECTING -> R.string.connection_connecting
                ConnectionState.CONNECTED -> R.string.connection_connected
                ConnectionState.ERROR -> R.string.connection_error
            },
        )
    }

    private fun renderCoarseBanner(coarse: Boolean) {
        binding.coarseBanner.visibility = if (coarse) View.VISIBLE else View.GONE
    }

    private fun renderStatus(status: MyStatus) {
        // The headline line is the primary status; the detail line carries the extra coarse context.
        val detail: String?
        binding.statusText.text = when (status) {
            is MyStatus.Waiting -> {
                detail = null
                getString(R.string.status_waiting, status.ahead, status.position)
            }
            is MyStatus.Called -> {
                detail = null
                status.desk?.let { getString(R.string.status_called_desk, it) }
                    ?: getString(R.string.status_called_no_desk)
            }
            MyStatus.Skipped -> {
                detail = null
                getString(R.string.status_skipped)
            }
            is MyStatus.Coarse -> {
                detail = if (status.reached) getString(R.string.status_coarse_reached) else null
                status.lastDrawn
                    ?.let { getString(R.string.status_coarse_last_called, it.toString()) }
                    ?: getString(R.string.status_coarse_unknown)
            }
            MyStatus.Unknown -> {
                detail = null
                getString(R.string.status_unknown)
            }
        }

        binding.statusDetailText.text = detail.orEmpty()
        binding.statusDetailText.visibility = if (detail == null) View.GONE else View.VISIBLE
    }

    private fun renderWatchingSummary(params: WatchParams?) {
        if (params == null) {
            binding.watchingSummaryText.visibility = View.GONE
        } else {
            binding.watchingSummaryText.visibility = View.VISIBLE
            binding.watchingSummaryText.text =
                getString(R.string.status_watching_summary, params.myNumber, params.queue)
        }
    }

    private fun renderSettings(settings: AlarmSettings) {
        binding.preAlarmValueText.text =
            getString(R.string.settings_pre_alarm_value, settings.preAlarmThreshold)

        // setCheckedSilently avoids the listener re-firing (and re-persisting) on every flow emission.
        binding.soundSwitch.setCheckedSilently(settings.sound) { checked ->
            viewModel.updateSettings { it.copy(sound = checked) }
        }
        binding.headphonesOnlySwitch.setCheckedSilently(settings.soundHeadphonesOnly) { checked ->
            viewModel.updateSettings { it.copy(soundHeadphonesOnly = checked) }
        }
        binding.vibrateSwitch.setCheckedSilently(settings.vibrate) { checked ->
            viewModel.updateSettings { it.copy(vibrate = checked) }
        }
        binding.visualBlinkSwitch.setCheckedSilently(settings.visualBlink) { checked ->
            viewModel.updateSettings { it.copy(visualBlink = checked) }
        }
        binding.flashlightBlinkSwitch.setCheckedSilently(settings.flashlightBlink) { checked ->
            viewModel.updateSettings { it.copy(flashlightBlink = checked) }
        }
        binding.fullSystemAlarmSwitch.setCheckedSilently(settings.fullSystemAlarm) { checked ->
            viewModel.updateSettings { it.copy(fullSystemAlarm = checked) }
        }
    }

    // --- Setup ----------------------------------------------------------------------------------

    /** Wires the pre-alarm stepper buttons; the switches are wired per-emission in [renderSettings]. */
    private fun bindSettingsControls() {
        binding.preAlarmDecreaseButton.setOnClickListener {
            viewModel.updateSettings {
                it.copy(preAlarmThreshold = (it.preAlarmThreshold - 1).coerceAtLeast(MIN_PRE_ALARM))
            }
        }
        binding.preAlarmIncreaseButton.setOnClickListener {
            viewModel.updateSettings {
                it.copy(preAlarmThreshold = (it.preAlarmThreshold + 1).coerceAtMost(MAX_PRE_ALARM))
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private companion object {
        const val MIN_PRE_ALARM = 0
        const val MAX_PRE_ALARM = 20
    }
}

/**
 * Sets the checked state without triggering [onChange], then (re)installs [onChange] for user taps.
 * Detaching the listener first prevents the persisted-settings flow from echoing back into a write loop.
 */
private inline fun MaterialSwitch.setCheckedSilently(checked: Boolean, crossinline onChange: (Boolean) -> Unit) {
    setOnCheckedChangeListener(null)
    isChecked = checked
    setOnCheckedChangeListener { _, isChecked -> onChange(isChecked) }
}
