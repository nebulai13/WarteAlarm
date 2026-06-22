package com.example.wartealarm

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import com.example.wartealarm.databinding.FragmentFirstBinding
import com.example.wartealarm.domain.LinkParser
import com.example.wartealarm.domain.WatchParams
import com.example.wartealarm.service.WatchService
import com.example.wartealarm.ui.MainViewModel

/**
 * Entry screen: paste the wartenummer.at link and enter your number to start watching.
 *
 * On "Start watching" it validates the inputs (see [validatedParams]), asks for the notification
 * permission on Android 13+ (the foreground watcher needs it to surface its status/alarm), then starts
 * [WatchService] and navigates to the live-status screen.
 *
 * Validation is intentionally explicit about the short `/t/…` link: it can't be resolved to a room/queue
 * without logging in (`API-research.md` §2/§5), so we tell the user to paste the long link instead.
 */
class FirstFragment : Fragment() {

    private var _binding: FragmentFirstBinding? = null

    // Valid only between onCreateView and onDestroyView.
    private val binding get() = _binding!!

    private val viewModel: MainViewModel by activityViewModels()

    /**
     * The watch params to start once a permission decision comes back. Held across the async permission
     * prompt; we proceed regardless of the result, since the watch itself doesn't require the permission
     * (only the user-visible alarm notification does).
     */
    private var pendingParams: WatchParams? = null

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            // Start watching either way: without the permission the alarm still fires, just without a
            // posted notification on Android 13+.
            pendingParams?.let { startWatching(it) }
            pendingParams = null
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentFirstBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Restore any in-progress entry text and keep the ViewModel in sync as the user types.
        binding.linkInput.setText(viewModel.linkInput)
        binding.numberInput.setText(viewModel.numberInput)
        binding.linkInput.doAfterTextChanged { text ->
            viewModel.linkInput = text?.toString().orEmpty()
            binding.linkInputLayout.error = null
        }
        binding.numberInput.doAfterTextChanged { text ->
            viewModel.numberInput = text?.toString().orEmpty()
            binding.numberInputLayout.error = null
        }

        binding.startWatchingButton.setOnClickListener { onStartClicked() }
    }

    /** Validates the fields; on success requests the notification permission and then starts watching. */
    private fun onStartClicked() {
        val params = validatedParams() ?: return

        if (needsNotificationPermission()) {
            pendingParams = params
            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            startWatching(params)
        }
    }

    /**
     * Validates the link and number fields, surfacing a clear inline error and returning `null` on any
     * problem. Returns the [WatchParams] to watch when everything is valid.
     */
    private fun validatedParams(): WatchParams? {
        val rawLink = binding.linkInput.text?.toString().orEmpty().trim()
        val rawNumber = binding.numberInput.text?.toString().orEmpty().trim()

        if (rawLink.isEmpty()) {
            binding.linkInputLayout.error = getString(R.string.entry_error_link_empty)
            return null
        }

        val link = LinkParser.parse(rawLink)
        if (link == null) {
            binding.linkInputLayout.error = getString(R.string.entry_error_link_unrecognised)
            return null
        }
        if (link.isShortLink) {
            binding.linkInputLayout.error = getString(R.string.entry_error_short_link)
            return null
        }
        val room = link.room
        val queue = link.queue
        if (room.isNullOrBlank() || queue.isNullOrBlank()) {
            binding.linkInputLayout.error = getString(R.string.entry_error_link_missing_queue)
            return null
        }

        if (rawNumber.isEmpty()) {
            binding.numberInputLayout.error = getString(R.string.entry_error_number_empty)
            return null
        }
        val number = rawNumber.toIntOrNull()
        if (number == null) {
            binding.numberInputLayout.error = getString(R.string.entry_error_number_invalid)
            return null
        }

        return WatchParams(room = room, queue = queue, myNumber = number)
    }

    /** Starts the foreground watcher and moves to the live-status screen. */
    private fun startWatching(params: WatchParams) {
        WatchService.start(requireContext(), params)
        findNavController().navigate(R.id.action_FirstFragment_to_SecondFragment)
    }

    /** True if we should request [Manifest.permission.POST_NOTIFICATIONS] (Android 13+, not yet granted). */
    private fun needsNotificationPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        return ContextCompat.checkSelfPermission(
            requireContext(),
            Manifest.permission.POST_NOTIFICATIONS,
        ) != PackageManager.PERMISSION_GRANTED
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
