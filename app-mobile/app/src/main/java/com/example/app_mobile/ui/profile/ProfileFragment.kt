package com.example.app_mobile.ui.profile

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.example.app_mobile.MainActivity
import com.example.app_mobile.R
import com.example.app_mobile.databinding.FragmentProfileBinding
import com.example.app_mobile.ui.kvkk.requireKvkkConsent
import com.example.app_pos.data.consent.ConsentStore
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * "Profil" tab: the signed-in user's account. Name/email editable inline. If not a
 * seller, a "Satıcı ol" button (shop name → become a seller). Once a seller, the shop
 * name row + a POS pairing card (NotPaired → pair → Ready) appear. Logout → gate.
 */
@AndroidEntryPoint
class ProfileFragment : Fragment() {

    private var _binding: FragmentProfileBinding? = null
    private val binding get() = _binding!!

    private val viewModel: ProfileViewModel by viewModels()

    private var currentName: String = ""
    private var currentEmail: String = ""
    private var currentShop: String = ""

    // Dialogs held so onDestroyView can dismiss them; a dialog outliving its fragment is
    // a leaked window.
    private var becomeSellerDialog: AlertDialog? = null
    private var mockDialog: AlertDialog? = null
    private var kvkkDialog: AlertDialog? = null

    @Inject
    lateinit var consentStore: ConsentStore

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentProfileBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.btnEditName.setOnClickListener {
            showEditDialog(R.string.profile_edit_name_title, currentName) { viewModel.updateDisplayName(it) }
        }
        binding.btnEditEmail.setOnClickListener {
            showEditDialog(R.string.profile_edit_email_title, currentEmail) { viewModel.updateEmail(it) }
        }
        binding.btnEditShop.setOnClickListener {
            showEditDialog(R.string.profile_update_shop, currentShop) { viewModel.updateShopName(it) }
        }
        binding.btnBecomeSeller.setOnClickListener { showBecomeSellerDialog() }

        // The mock surfaces (Turn 47, deferred.md §L.3 / §L.16). The KYC rows above take no
        // input at all — their values are string constants in the layout — so the only
        // things wired here are the ones that have somewhere to go: an explanation.
        binding.btnAddAddress.setOnClickListener { showMockSoonDialog() }
        binding.btnIdPhoto.setOnClickListener { showMockSoonDialog() }
        binding.btnConnectTokenflex.setOnClickListener { showMockSoonDialog() }
        binding.btnConnectOdero.setOnClickListener { showMockSoonDialog() }
        binding.btnConnectYapikredi.setOnClickListener { showMockSoonDialog() }

        binding.btnPair.setOnClickListener {
            findNavController().navigate(R.id.action_profile_to_pairing)
        }
        binding.btnLogout.setOnClickListener {
            viewModel.logout()
            (activity as? MainActivity)?.navigateToLogin()
        }
        observeState()
    }

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state -> if (state != null) render(state) }
            }
        }
    }

    private fun render(state: ProfileUiState) {
        val user = state.user
        currentName = user.displayName
        currentEmail = user.email.orEmpty()
        currentShop = user.sellerInfo?.shopName.orEmpty()

        binding.displayNameText.text =
            user.displayName.ifEmpty { getString(R.string.profile_not_set) }
        binding.phoneText.text = user.phone
        binding.emailText.text = currentEmail.ifEmpty { getString(R.string.profile_not_set) }
        binding.rolesText.text = rolesLabel(user)

        // Seller-only pieces: the shop-name row, the pairing card / paired text, and
        // hiding the "become a seller" button (already one).
        val seller = user.isSeller
        binding.shopRow.visibility = if (seller) View.VISIBLE else View.GONE
        binding.shopNameText.text = currentShop.ifEmpty { getString(R.string.profile_not_set) }
        binding.btnBecomeSeller.visibility = if (seller) View.GONE else View.VISIBLE

        binding.pairCard.visibility = if (seller && !state.isPaired) View.VISIBLE else View.GONE
        binding.pairedText.visibility = if (seller && state.isPaired) View.VISIBLE else View.GONE
    }

    private fun showBecomeSellerDialog() {
        val input = TextInputEditText(requireContext()).apply { hint = getString(R.string.become_seller_hint) }
        // Held in a field and dismissed in onDestroyView, like both LoginFragments already
        // do with theirs. It was neither before, so rotating the screen with it open leaked
        // a window; Turn 47 stacks a second dialog on top of it, which would have doubled
        // the leak rather than introducing it.
        becomeSellerDialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.become_seller_title)
            .setMessage(R.string.become_seller_message)
            .setView(input)
            .setPositiveButton(R.string.become_seller_positive) { _, _ ->
                // Read BEFORE the gate: onGranted runs after this dialog is gone, and the
                // TextInputEditText goes with it.
                val shopName = input.text?.toString()?.trim().orEmpty()
                requireKvkkConsent(consentStore, onShown = { kvkkDialog = it }) {
                    viewModel.becomeSeller(shopName)
                }
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    /**
     * What every mock surface on this screen says (deferred.md §L.3 / §L.16).
     *
     * A dialog, not a Toast. On a screen someone is presenting from, a Toast is gone before
     * the sentence explaining it ends — and this text carries the reason the feature is a
     * picture rather than a feature, which is the part worth reading.
     */
    private fun showMockSoonDialog() {
        mockDialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.mock_soon_title)
            .setMessage(R.string.mock_soon_message)
            .setPositiveButton(R.string.mock_soon_dismiss, null)
            .show()
    }

    /** One-field edit dialog, prefilled. */
    private fun showEditDialog(titleRes: Int, current: String, onSave: (String) -> Unit) {
        val input = TextInputEditText(requireContext()).apply {
            setText(current)
            setSelection(text?.length ?: 0)
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(titleRes)
            .setView(input)
            .setPositiveButton(R.string.dialog_save) { _, _ ->
                onSave(input.text?.toString()?.trim().orEmpty())
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    /** Both roles can be active, so join whichever are set. */
    private fun rolesLabel(user: com.example.app_pos.model.User): String {
        val roles = buildList {
            if (user.isBuyer) add(getString(R.string.profile_role_buyer))
            if (user.isSeller) add(getString(R.string.profile_role_seller))
        }
        return roles.joinToString(", ")
    }

    override fun onDestroyView() {
        super.onDestroyView()
        becomeSellerDialog?.dismiss()
        becomeSellerDialog = null
        mockDialog?.dismiss()
        mockDialog = null
        kvkkDialog?.dismiss()
        kvkkDialog = null
        _binding = null
    }
}
