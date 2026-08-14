package com.example.app_mobile.ui.login

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.app_mobile.MainActivity
import com.example.app_mobile.R
import com.example.app_mobile.databinding.FragmentLoginBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

/**
 * The login gate — the app's start destination. The customer cannot reach the dashboard
 * until signed in (see nav_graph startDestination).
 *
 * One screen, two steps: the phone field asks the server for a code, then the same screen
 * swaps in the code field. A separate destination would put the gate on the back stack and
 * let the system back button land between the steps.
 */
@AndroidEntryPoint
class LoginFragment : Fragment() {

    private var _binding: FragmentLoginBinding? = null
    private val binding get() = _binding!!

    private val viewModel: LoginViewModel by viewModels()

    private var registerDialog: androidx.appcompat.app.AlertDialog? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentLoginBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // One button, two meanings — which step it acts on is the ViewModel's state, not a
        // flag kept here, so the two can never disagree.
        binding.btnLogin.setOnClickListener {
            if (viewModel.state.value == LoginState.CODE_SENT) {
                viewModel.submitCode(binding.codeInput.text?.toString()?.trim().orEmpty())
            } else {
                val phone = binding.phoneInput.text?.toString()?.trim().orEmpty()
                viewModel.requestCode(phone.ifEmpty { null })
            }
        }
        binding.btnEditPhone.setOnClickListener { viewModel.editPhone() }
        observeState()
    }

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { renderState(it) }
            }
        }
    }

    private fun renderState(state: LoginState) {
        // Which step is on screen. SUBMITTING keeps whatever step it was started from, so
        // the fields do not jump while a request is in flight.
        val onCodeStep = state == LoginState.CODE_SENT || state == LoginState.NEEDS_REGISTER
        if (state != LoginState.SUBMITTING) {
            // The phone field STAYS, just goes read-only, and the code field opens BELOW it
            // (app-pos does the same). Hiding the phone made one screen look like two: the
            // number the user just typed vanished, so the step read as a page change rather
            // than as the next field in the same form.
            binding.phoneLayout.isEnabled = !onCodeStep
            binding.codeLayout.visibility = if (onCodeStep) View.VISIBLE else View.GONE
            binding.btnEditPhone.visibility = if (onCodeStep) View.VISIBLE else View.GONE
            binding.btnLogin.setText(
                if (onCodeStep) R.string.login_verify_button else R.string.login_button
            )
        }

        when (state) {
            LoginState.IDLE -> {
                binding.btnLogin.isEnabled = true
                binding.statusText.visibility = View.GONE
                binding.codeInput.text = null
            }
            LoginState.SUBMITTING -> {
                binding.btnLogin.isEnabled = false
                binding.statusText.visibility = View.GONE
            }
            LoginState.CODE_SENT -> {
                binding.btnLogin.isEnabled = true
                // Reached either fresh (code just sent) or after a wrong code — the two look
                // identical on screen, so the message has to distinguish them.
                binding.statusText.setText(
                    if (viewModel.codeRejected.value) R.string.msg_login_wrong_code
                    else R.string.msg_login_code_sent
                )
                binding.statusText.visibility = View.VISIBLE
            }
            LoginState.SUCCESS -> {
                (activity as? MainActivity)?.onLoginSucceeded()
            }
            LoginState.NEEDS_REGISTER -> {
                binding.btnLogin.isEnabled = true
                binding.statusText.visibility = View.GONE
                showRegisterDialog()
            }
            LoginState.ERROR -> {
                binding.btnLogin.isEnabled = true
                // Prefer what the server said; fall back to the generic line.
                val message = viewModel.errorMessage.value
                if (message != null) binding.statusText.text = message
                else binding.statusText.setText(R.string.msg_login_wrong_number)
                binding.statusText.visibility = View.VISIBLE
            }
        }
    }

    private fun showRegisterDialog() {
        if (registerDialog?.isShowing == true) return
        val phone = viewModel.pendingPhoneDisplay ?: return
        registerDialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.register_confirm_title)
            .setMessage(getString(R.string.register_confirm_message, phone))
            // Reuses the code already on screen: the server keeps it valid for the whole
            // exchange, so registering does not need a second SMS.
            .setPositiveButton(R.string.register_confirm_positive) { _, _ ->
                viewModel.register(binding.codeInput.text?.toString()?.trim().orEmpty())
            }
            .setNegativeButton(R.string.register_confirm_negative) { _, _ -> viewModel.cancelRegister() }
            .setOnCancelListener { viewModel.cancelRegister() }
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        registerDialog?.dismiss()
        registerDialog = null
        _binding = null
    }
}
