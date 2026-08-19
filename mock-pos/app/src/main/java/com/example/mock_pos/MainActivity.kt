package com.example.mock_pos

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.mock_pos.databinding.ActivityMainBinding
import com.example.mock_pos.util.toTlString
import kotlinx.coroutines.launch

/**
 * Mock POS payment screen: key in an amount, pick a payment method.
 *
 * This stands in for Token's real POS payment app. Card / Meal Card / Cash are
 * mocked (Toast only). VERESİYE hands the amount off to the app-pos app over an
 * Intent — payment is a separate concern from the veresiye (credit) ledger, so
 * it lives in a separate app.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val viewModel: PaymentViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        bindKeypad()
        bindPaymentMethods()
        observeAmount()
        showIncomingRequest(intent)
    }

    /**
     * Launched with a request from app-pos rather than by a person tapping the icon.
     *
     * This app cannot print anything, so it reports what the REAL gateway would have done
     * with what it received. That is the whole value of the mock at this stage: the intent
     * either arrived in the right shape or it did not, and this makes that visible without
     * a terminal.
     */
    private fun showIncomingRequest(intent: Intent) {
        val orderBody = intent.getStringExtra(EXTRA_ORDER_BODY) ?: return

        // paymentItems[].type is what separates the two requests: 17 is a credit slip,
        // anything else is an ordinary card payment. Read as text rather than parsed —
        // the point here is to show what arrived, not to interpret it.
        val action =
            if (orderBody.contains("\"type\":$ITEM_TYPE_CREDIT")) R.string.pgw_would_print_receipt
            else R.string.pgw_would_collect_payment

        AlertDialog.Builder(this)
            .setTitle(R.string.pgw_request_title)
            .setMessage(getString(R.string.pgw_request_body, getString(action), orderBody))
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    /** A second gateway request arriving while this screen is already open. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        showIncomingRequest(intent)
    }

    /**
     * Each time the screen returns to the foreground — including coming back from
     * the app-pos handoff — start a fresh sale so a previous amount never lingers.
     */
    override fun onResume() {
        super.onResume()
        viewModel.onClear()
    }

    private fun bindKeypad() = with(binding) {
        key0.setOnClickListener { viewModel.onDigit(0) }
        key1.setOnClickListener { viewModel.onDigit(1) }
        key2.setOnClickListener { viewModel.onDigit(2) }
        key3.setOnClickListener { viewModel.onDigit(3) }
        key4.setOnClickListener { viewModel.onDigit(4) }
        key5.setOnClickListener { viewModel.onDigit(5) }
        key6.setOnClickListener { viewModel.onDigit(6) }
        key7.setOnClickListener { viewModel.onDigit(7) }
        key8.setOnClickListener { viewModel.onDigit(8) }
        key9.setOnClickListener { viewModel.onDigit(9) }
        keyClear.setOnClickListener { viewModel.onClear() }
        keyBackspace.setOnClickListener { viewModel.onBackspace() }
    }

    private fun bindPaymentMethods() = with(binding) {
        btnCard.setOnClickListener { onMockMethod(getString(R.string.method_card)) }
        btnMealCard.setOnClickListener { onMockMethod(getString(R.string.method_meal_card)) }
        btnCash.setOnClickListener { onMockMethod(getString(R.string.method_cash)) }
        // Default (tap): money-only handoff from the keyed-in amount.
        btnCredit.setOnClickListener { onCreditSelected() }
        // Optional (long-press): pick a canned item-level basket, so the
        // product-based path can be demoed without a real basket app.
        btnCredit.setOnLongClickListener {
            showBasketPicker()
            true
        }
    }

    private fun observeAmount() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.amountMinor.collect { amount ->
                    binding.amountText.text = amount.toTlString()
                }
            }
        }
    }

    private fun onMockMethod(methodName: String) {
        if (!requireAmount()) return
        toast(getString(R.string.msg_method_mocked, methodName))
    }

    /**
     * VERESİYE is the one method that leaves this app: hand off to app-pos. The
     * default is a MONEY-ONLY basket built from the keyed-in amount, matching the
     * previous single-amount handoff — the item data just travels in the orderBody
     * shape now so app-pos can support product-level credit later.
     */
    private fun onCreditSelected() {
        if (!requireAmount()) return
        handOff(MockBasket.moneyOnly(viewModel.amountMinor.value))
    }

    /** Lets the merchant send a canned item-level basket instead of a plain amount. */
    private fun showBasketPicker() {
        val samples = MockBasket.SAMPLES
        val labels = samples.map { it.label }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.basket_picker_title)
            .setItems(labels) { _, which -> handOff(MockBasket.fromSample(samples[which])) }
            .show()
    }

    /**
     * Sends the orderBody JSON to app-pos over the CREDIT intent, and WAITS for its answer.
     *
     * The result is the point. A real gateway launches the veresiye app to find out whether
     * the customer agreed, and prints its slip on that answer — so it cannot fire and
     * forget. app-pos reports RESULT_OK once the customer approves and RESULT_CANCELED when
     * they refuse; without listening, a refused veresiye would be indistinguishable from an
     * accepted one and the slip would print either way.
     *
     * NEW_TASK is gone for the same reason: a launch into its own task returns its result
     * immediately as CANCELED, so the two are mutually exclusive. app-pos still declares
     * its own task affinity, which is what kept it out of this app's back stack.
     */
    private fun handOff(orderBody: String) {
        val intent = Intent(ACTION_CREDIT).apply {
            // Same-device app; targeting the package keeps the handoff explicit.
            setPackage(APP_POS_PACKAGE)
            putExtra(EXTRA_ORDER_BODY, orderBody)
        }
        try {
            creditResult.launch(intent)
        } catch (e: ActivityNotFoundException) {
            toast(getString(R.string.msg_credit_app_missing))
        }
    }

    /**
     * What app-pos answered about the veresiye — the moment a real gateway would decide
     * whether to print.
     *
     * Registered as a field rather than created per handoff because the Activity result API
     * requires registration before STARTED, and a launcher made inside the click handler
     * would crash on the first tap.
     */
    private val creditResult =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val message =
                if (result.resultCode == RESULT_OK) R.string.msg_credit_approved
                else R.string.msg_credit_declined
            toast(getString(message))
        }

    /** Guards every method: nothing proceeds before an amount is entered. */
    private fun requireAmount(): Boolean {
        if (!viewModel.hasAmount()) {
            toast(getString(R.string.msg_enter_amount))
            return false
        }
        return true
    }

    private fun toast(message: String) =
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    companion object {
        // The app-pos handoff contract. app-pos declares the matching intent-filter
        // and reads the same extra key. The two apps do not share code, so these
        // constants are duplicated on the app-pos side; they could move to
        // shared-contracts later.
        //
        // The extra is now the PGW's orderBody JSON (basketID + items), not a bare
        // amount — this mirrors the real Token payment gateway payload.
        private const val APP_POS_PACKAGE = "com.example.app_pos"
        private const val ACTION_CREDIT = "com.example.app_pos.action.CREDIT"
        private const val EXTRA_ORDER_BODY = "orderBody"

        // The gateway's payment-item type for a credit slip -- THEIR number. Used here only
        // to say which of the two things the real gateway would have done.
        private const val ITEM_TYPE_CREDIT = 17
    }
}
