package com.example.app_pos.ui.dashboard.detail

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.app_pos.R
import com.example.app_pos.databinding.FragmentTransactionDetailBinding
import com.example.app_pos.model.TransactionDetail
import com.example.app_pos.model.TransactionType
import com.example.app_pos.util.toDisplayDateTime
import com.example.app_pos.util.toTlString
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

/**
 * What one ledger entry was made of.
 *
 * The question §J spent three turns making answerable and could still only answer in SQL:
 * the basket travelled from the gateway to the server and back, and no screen showed it.
 *
 * The nav argument reaches the ViewModel through SavedStateHandle rather than a
 * hand-written factory, like every other detail screen in this app.
 */
@AndroidEntryPoint
class TransactionDetailFragment : Fragment() {

    private var _binding: FragmentTransactionDetailBinding? = null
    private val binding get() = _binding!!

    private val viewModel: TransactionDetailViewModel by viewModels()

    private val adapter = BasketItemAdapter()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentTransactionDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.basketList.layoutManager = LinearLayoutManager(requireContext())
        binding.basketList.adapter = adapter

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect(::render)
            }
        }
    }

    private fun render(state: TransactionDetailUiState) = when (state) {
        // Nothing to show yet. Deliberately blank rather than a spinner: the read is a
        // single local query and a spinner that appears for one frame is a flicker, not
        // feedback.
        TransactionDetailUiState.Loading -> Unit

        // The entry is not on this device. Reuses the "no basket" wording rather than
        // introducing an error state: from where the reader stands the two are the same
        // sentence, and neither is anybody's fault.
        TransactionDetailUiState.Missing -> {
            binding.basketGroup.visibility = View.GONE
            binding.basketEmpty.visibility = View.VISIBLE
        }

        is TransactionDetailUiState.Content -> showDetail(state.detail)
    }

    private fun showDetail(detail: TransactionDetail) {
        val transaction = detail.transaction

        binding.txDetailDescription.text = transaction.description
        // Stored as ISO-8601 UTC; shown in the device's own time zone.
        binding.txDetailDate.text = transaction.createdAt.toDisplayDateTime()

        // Sign and colour by entry type, exactly as the row that was tapped renders them.
        // The same amount changing appearance between the list and the screen it opens
        // would read as two different figures.
        val prefix = when (transaction.type) {
            TransactionType.PAYMENT -> "-"
            else -> "+"
        }
        binding.txDetailAmount.text = prefix + transaction.amountMinor.toTlString()
        binding.txDetailAmount.setTextColor(
            requireContext().getColor(
                when (transaction.type) {
                    TransactionType.DEBT -> R.color.balance_due
                    TransactionType.INDEXATION -> R.color.balance_indexation
                    TransactionType.PAYMENT -> R.color.payment_received
                }
            )
        )

        val basket = detail.basket
        if (basket == null) {
            // Ordinary, not exceptional: a money-only handoff, a payment, or one of the
            // server's indexation rows. Between them that is most of a normal history.
            binding.basketGroup.visibility = View.GONE
            binding.basketEmpty.visibility = View.VISIBLE
            return
        }

        binding.basketGroup.visibility = View.VISIBLE
        binding.basketEmpty.visibility = View.GONE
        adapter.submitList(basket.items)

        // The lines' own sum, printed under them so it can be compared with the amount in
        // the header. They agree; showing it twice is what makes that checkable rather
        // than asserted.
        binding.basketTotal.text = basket.totalMinor().toTlString()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding.basketList.adapter = null
        _binding = null
    }
}
