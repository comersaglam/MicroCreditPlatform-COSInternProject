package com.example.app_pos.ui.login

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.example.app_pos.MainActivity
import com.example.app_pos.R
import com.example.app_pos.data.consent.ConsentStore
import com.example.app_pos.ui.kvkk.requireKvkkConsent
import com.example.app_pos.databinding.FragmentLoginBinding
import kotlinx.coroutines.launch
import javax.inject.Inject
import dagger.hilt.android.AndroidEntryPoint

/**
 * The login gate — the app's start destination. The merchant cannot reach the
 * dashboard until signed in (see nav_graph startDestination).
 *
 * One screen, two steps: the phone field asks the server for a code, then the code field
 * appears and the same button verifies it. Which step is showing is derived entirely from
 * [LoginViewModel.state], so a rotation or a process death cannot leave the screen in a
 * different half than the ViewModel thinks it is in.
 */
@AndroidEntryPoint
class LoginFragment : Fragment() {

    private var _binding: FragmentLoginBinding? = null
    private val binding get() = _binding!!

    private val viewModel: LoginViewModel by viewModels()

    // Guards against re-showing the register dialog on every re-emit / config change.
    private var registerDialog: androidx.appcompat.app.AlertDialog? = null

    // The KVKK dialog stacks on top of the register one, so it gets the same treatment:
    // held here, dismissed in onDestroyView.
    private var kvkkDialog: androidx.appcompat.app.AlertDialog? = null

    @Inject
    lateinit var consentStore: ConsentStore

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
        binding.btnLogin.setOnClickListener {
            // One button, two jobs, chosen by which field is on screen. The state decides,
            // not the button's own text, so the two can never disagree.
            if (binding.codeLayout.visibility == View.VISIBLE) {
                viewModel.verify(binding.codeInput.text?.toString())
            } else {
                val phone = binding.phoneInput.text?.toString()?.trim().orEmpty()
                viewModel.sendCode(phone.ifEmpty { null })
            }
        }
        binding.btnEditPhone.setOnClickListener {
            binding.codeInput.text = null
            viewModel.editPhone()
        }
        observeState()
    }

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { state ->
                    renderState(state)
                }
            }
        }
    }

    private fun renderState(state: LoginState) {
        // Which half of the flow is on screen. Derived from the state rather than tracked
        // separately, so there is nothing to fall out of sync after a rotation.
        //
        // SUBMITTING says nothing about which half we are in — it happens on both — so it
        // leaves the fields exactly as they were. Recomputing on it would collapse the code
        // field for the length of every verify request.
        if (state != LoginState.SUBMITTING) {
            val onCodeStep = state in CODE_STEP_STATES
            binding.codeLayout.visibility = if (onCodeStep) View.VISIBLE else View.GONE
            binding.btnEditPhone.visibility = if (onCodeStep) View.VISIBLE else View.GONE
            binding.phoneLayout.isEnabled = !onCodeStep
            binding.btnLogin.setText(
                if (onCodeStep) R.string.login_button else R.string.login_send_code
            )
        }

        binding.btnLogin.isEnabled = state != LoginState.SUBMITTING

        when (state) {
            LoginState.IDLE, LoginState.SUBMITTING -> hideStatus()

            LoginState.CODE_SENT -> showStatus(R.string.msg_login_code_sent, isError = false)

            LoginState.SUCCESS -> {
                // If a CREDIT handoff was waiting, the activity resumes the sale flow
                // and handles navigation; otherwise go to the dashboard (dropping the
                // gate from the back stack so Back exits the app).
                val handled = (activity as? MainActivity)?.onLoginSucceeded() == true
                if (!handled) {
                    findNavController().navigate(R.id.action_global_dashboard_after_login)
                }
            }

            LoginState.NEEDS_REGISTER -> {
                hideStatus()
                showRegisterDialog()
            }

            LoginState.INVALID_CODE -> showStatus(R.string.msg_login_invalid_code)
            LoginState.OFFLINE -> showStatus(R.string.msg_login_offline)
            LoginState.ERROR -> showStatus(R.string.msg_login_wrong_number)
        }
    }

    private fun showStatus(messageRes: Int, isError: Boolean = true) {
        binding.statusText.setText(messageRes)
        binding.statusText.setTextColor(
            com.google.android.material.color.MaterialColors.getColor(
                binding.statusText,
                if (isError) com.google.android.material.R.attr.colorError
                else com.google.android.material.R.attr.colorOnSurfaceVariant
            )
        )
        binding.statusText.visibility = View.VISIBLE
    }

    private fun hideStatus() {
        binding.statusText.visibility = View.GONE
    }

    /** Confirmation for registering an unknown number; already-showing = no-op. */
    private fun showRegisterDialog() {
        if (registerDialog?.isShowing == true) return
        val phone = viewModel.pendingPhoneDisplay ?: return
        registerDialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.register_confirm_title)
            .setMessage(getString(R.string.register_confirm_message, phone))
            .setPositiveButton(R.string.register_confirm_positive) { _, _ ->
                requireKvkkConsent(consentStore, onShown = { kvkkDialog = it }) {
                    viewModel.register()
                }
            }
            .setNegativeButton(R.string.register_confirm_negative) { _, _ -> viewModel.cancelRegister() }
            .setOnCancelListener { viewModel.cancelRegister() }
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        registerDialog?.dismiss()
        registerDialog = null
        kvkkDialog?.dismiss()
        kvkkDialog = null
        _binding = null
    }

    private companion object {
        /**
         * States that mean "a code is outstanding", so the code field belongs on screen.
         *
         * SUBMITTING is deliberately absent: it occurs in BOTH halves, and treating it as
         * either one would make the fields flicker to the other step while a request is in
         * flight. Leaving it out keeps whatever is already showing in place, which is what
         * a spinner should do.
         */
        val CODE_STEP_STATES = setOf(
            LoginState.CODE_SENT,
            LoginState.INVALID_CODE,
            LoginState.NEEDS_REGISTER
        )
    }
}
