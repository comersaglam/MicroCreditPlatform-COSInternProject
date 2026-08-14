package com.example.app_mobile.ui.profile

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
import com.example.app_mobile.MainActivity
import com.example.app_mobile.R
import com.example.app_mobile.databinding.FragmentProfileBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

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
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.become_seller_title)
            .setMessage(R.string.become_seller_message)
            .setView(input)
            .setPositiveButton(R.string.become_seller_positive) { _, _ ->
                viewModel.becomeSeller(input.text?.toString()?.trim().orEmpty())
            }
            .setNegativeButton(R.string.dialog_cancel, null)
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
        _binding = null
    }
}
