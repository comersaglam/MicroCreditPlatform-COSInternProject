package com.example.app_pos

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.core.os.bundleOf
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.navigateUp
import androidx.navigation.ui.setupActionBarWithNavController
import com.example.app_pos.data.OrderBodyParser
import com.example.app_pos.databinding.ActivityMainBinding
import com.example.app_pos.model.Repository
import com.example.app_pos.pgw.PgwJobRunner
import com.example.app_pos.ui.dashboard.DashboardFragment
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The app's only Activity. Every screen is a Fragment inside the nav host.
 *
 * app-pos has two entry points, told apart by the launching Intent:
 *  - Launcher icon → lands on the dashboard (the nav graph's start destination):
 *    the merchant can browse customers/logs without any payment.
 *  - CREDIT Intent from the payment app (mock-pos) → jumps straight into the
 *    sale flow with the handed-over amount, and finishes back to the payment app
 *    once the veresiye entry is done (handoff mode).
 */
@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var appBarConfiguration: AppBarConfiguration

    /**
     * Injected rather than fetched from a static holder, so the session it reports comes
     * from the persisted store. Hilt fills this during super.onCreate(), which is before
     * the start-destination read below and before any onNewIntent — so both handoff paths
     * see a ready repository.
     */
    @Inject lateinit var repo: Repository

    /**
     * Delivers server-queued work to the payment gateway. Injected here because a job can
     * arrive while any screen is open — it belongs to the terminal, not to a screen.
     */
    @Inject lateinit var pgwJobRunner: PgwJobRunner

    /** True when opened from the payment app; drives the finish-back behaviour. */
    private var isCreditHandoff = false

    /**
     * A CREDIT orderBody that arrived while the merchant was not signed in. Held
     * until login succeeds, then the sale flow resumes with it (see
     * onLoginSucceeded). We keep the raw JSON (not just the amount) so the basket's
     * items survive the login detour for later product-level use. Saved into the
     * instance state so it survives rotation / process death on the login screen.
     * null = nothing pending.
     */
    private var pendingHandoffOrderBody: String? = null

    private val navController: NavController
        get() = (supportFragmentManager
            .findFragmentById(R.id.navHostFragment) as NavHostFragment).navController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Pick the start destination from the session BEFORE the graph is used, so a
        // valid session opens straight on the dashboard with no login flash, and no
        // session shows the gate. Only on a fresh start: on recreation the controller
        // restores its own back stack, so re-setting the graph would wipe it.
        if (savedInstanceState == null) {
            val graph = navController.navInflater.inflate(R.navigation.nav_graph)
            graph.setStartDestination(
                if (repo.isSessionValid()) R.id.dashboardFragment else R.id.loginFragment
            )
            navController.graph = graph
        } else {
            pendingHandoffOrderBody = savedInstanceState.getString(KEY_PENDING_ORDER_BODY)
        }

        // Shows each destination's label in the action bar and adds the up
        // arrow on every screen except the start destination.
        appBarConfiguration = AppBarConfiguration(navController.graph)
        setupActionBarWithNavController(navController, appBarConfiguration)

        // The T-Fides mark in the bar, on every screen. On the activity rather than in a
        // layout, because the bar belongs to the activity: in one screen's layout it would
        // be lost everywhere else.
        //
        // A custom view, NOT setLogo(): that places the mark inside the title block, where
        // it flows with the text and drifts as the title changes length. This one is
        // positioned by its own gravity and stays at the right end.
        supportActionBar?.apply {
            setDisplayShowCustomEnabled(true)
            setCustomView(R.layout.actionbar_logo)
        }

        // onPrepareOptionsMenu only runs when the menu is about to be shown, so
        // the action bar has to be told to rebuild it whenever the screen —
        // and with it the dashboard icon's relevance — changes.
        navController.addOnDestinationChangedListener { _, _, _ ->
            invalidateOptionsMenu()
        }

        // Only route the launching Intent on a fresh start: on recreation the
        // nav controller already restored its back stack, and re-navigating
        // would push a duplicate sale-flow screen.
        if (savedInstanceState == null) {
            handleIntent(intent)
        }

        collectGatewayJobs()
    }

    /**
     * Collects work the server left for this terminal and fires it at the payment gateway.
     *
     * Lives on the Activity rather than on any one screen because a job can arrive at any
     * moment — the sale that produced it happened on somebody's PHONE (paths 3, 4 and 5),
     * and which tab the merchant happens to be looking at is irrelevant to it.
     *
     * Scoped to STARTED, so polling stops when the terminal is backgrounded. That is
     * deliberate: launching a payment screen out of a pocketed device would be alarming,
     * and nothing is lost by waiting — an undelivered job stays PENDING on the server.
     */
    private fun collectGatewayJobs() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                pgwJobRunner.poll(this@MainActivity)
            }
        }
    }

    /**
     * singleTask means a launch while already running comes here instead of a new
     * onCreate. Re-read the intent so the handoff flag reflects THIS launch (a
     * launcher tap clears it, a CREDIT intent sets it) and route accordingly.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    /**
     * Decides, from the launching intent, whether this is a payment-app handoff
     * (CREDIT) or a standalone launch, and acts on it.
     *
     * The flag is set on EVERY intent — set for CREDIT, cleared otherwise — so a
     * later standalone launch (e.g. tapping the launcher) never inherits a stale
     * "handoff" state from an earlier sale. That stale flag was what wrongly sent
     * the payment flow back to the payment app.
     */
    private fun handleIntent(intent: Intent) {
        isCreditHandoff = intent.action == ACTION_CREDIT
        if (isCreditHandoff) {
            // The extra is the PGW's orderBody JSON. Parsed here for the total, and passed
            // on UNPARSED as well: the sale flow needs the items at the write step, and
            // for a long time only the total made it past this line -- so every basket the
            // gateway handed over was dropped here (docs/deferred.md J).
            val orderBody = intent.getStringExtra(EXTRA_ORDER_BODY)
            val amountMinor = OrderBodyParser.parse(orderBody)?.totalMinor() ?: 0L
            if (repo.isSessionValid()) {
                // Signed in: straight to the sale flow, as before.
                // Empty rather than null for a missing body: the nav argument is a
                // non-null String (it has a default), and a null in the bundle would
                // throw when the fragment reads it.
                navController.navigate(
                    R.id.saleFlow,
                    bundleOf(
                        "amountMinor" to amountMinor,
                        "orderBody" to orderBody.orEmpty()
                    )
                )
            } else {
                // Not signed in: hold the orderBody and require login first. The gate
                // is already the start destination on a cold start; if a CREDIT
                // arrives while on the dashboard with an expired session, go to login.
                pendingHandoffOrderBody = orderBody
                if (navController.currentDestination?.id != R.id.loginFragment) {
                    navController.navigate(R.id.action_global_login)
                }
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.sale_menu, menu)
        return true
    }

    /**
     * The dashboard icon only makes sense inside the sale flow — once the
     * dashboard is open, offering to open it again would be noise.
     */
    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val inSaleFlow = navController.currentDestination?.parent?.id == R.id.saleFlow
        menu.findItem(R.id.action_dashboard)?.isVisible = inSaleFlow
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean =
        if (item.itemId == R.id.action_dashboard) {
            // A global action, so the same tap works from any sale screen.
            navController.navigate(R.id.action_global_dashboard)
            true
        } else {
            super.onOptionsItemSelected(item)
        }

    /**
     * Routes the action bar's up arrow.
     *
     * The dashboard hosts its own inner nav controller (customers/reports/profile
     * + customer detail), which the outer controller knows nothing about. So try
     * the inner one first — that pops customer detail back to the list — and fall
     * back to the outer controller for everything else.
     */
    override fun onSupportNavigateUp(): Boolean =
        innerNavController()?.navigateUp() == true ||
            navController.navigateUp(appBarConfiguration) ||
            super.onSupportNavigateUp()

    /** The dashboard's inner nav controller, if the dashboard is on screen and
     *  its inner back stack has somewhere to go up to (e.g. customer detail). */
    private fun innerNavController(): NavController? {
        val dashboard = supportFragmentManager
            .findFragmentById(R.id.navHostFragment)
            ?.childFragmentManager
            ?.primaryNavigationFragment as? DashboardFragment ?: return null
        val inner = dashboard.innerNavControllerOrNull() ?: return null
        return if (inner.previousBackStackEntry != null) inner else null
    }

    /**
     * Ends a payment-app handoff and returns there, after the sale-flow screen is
     * done. Returns true if it closed; false means "stay in app-pos" and the
     * caller decides where to go (usually the dashboard).
     *
     * Only closes when BOTH: opened from the payment app (isCreditHandoff) AND the
     * caller says this flow may hand back (isHandoffFlow). A PAYMENT flow always
     * starts inside app-pos, so it passes false and never finishes — that stale
     * flag was what wrongly sent payments back to the payment app (and crashed
     * when it wasn't running).
     */
    fun finishCreditHandoff(isHandoffFlow: Boolean, success: Boolean = true): Boolean {
        if (isCreditHandoff && isHandoffFlow) {
            // What the gateway is actually waiting for. It launched us to find out whether
            // the veresiye was agreed, and prints its slip on the answer — so a refused
            // approval must come back as CANCELED, not as a silent close that the gateway
            // would have no way to distinguish from success.
            setResult(if (success) RESULT_OK else RESULT_CANCELED)
            finish()
            return true
        }
        return false
    }

    /**
     * Returns to the login gate, clearing the dashboard behind it (see
     * action_global_login). Called from the profile's logout button. Uses the
     * OUTER controller: login lives in the outer graph, so the dashboard's inner
     * controller (which the profile screen would otherwise reach) cannot navigate
     * there. Mirrors how OtpFragment reaches finishCreditHandoff via the activity.
     */
    fun navigateToLogin() {
        navController.navigate(R.id.action_global_login)
    }

    /**
     * Called by LoginFragment on a successful sign-in. If a CREDIT amount was waiting
     * (handoff arrived while logged out), resume the sale flow with it and report
     * true so the fragment does not also go to the dashboard. Otherwise report false
     * and the fragment routes to the dashboard as usual.
     *
     * isCreditHandoff is still true here, so once the sale flow finishes OTP,
     * finishCreditHandoff(true) returns to the payment app exactly as a direct
     * handoff would.
     */
    fun onLoginSucceeded(): Boolean {
        val orderBody = pendingHandoffOrderBody ?: return false
        pendingHandoffOrderBody = null
        val amount = OrderBodyParser.parse(orderBody)?.totalMinor() ?: 0L
        // The basket travels through the login detour too. Carrying it in only the direct
        // branch above would lose the items for exactly the sales that had to sign in
        // first -- a difference nothing on screen would show.
        navController.navigate(
            R.id.action_global_saleflow_after_login,
            bundleOf("amountMinor" to amount, "orderBody" to orderBody)
        )
        return true
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        pendingHandoffOrderBody?.let { outState.putString(KEY_PENDING_ORDER_BODY, it) }
    }

    companion object {
        // The handoff contract with the payment app (mock-pos). The matching
        // action is declared in AndroidManifest and mock-pos sends the same
        // action + extra. Duplicated across the two apps on purpose: they share
        // no code (separate APKs); could move to shared-contracts later.
        const val ACTION_CREDIT = "com.example.app_pos.action.CREDIT"

        // The extra is the PGW's orderBody JSON (basketID + items), mirroring the
        // real Token payment gateway payload; mock-pos puts the same key.
        const val EXTRA_ORDER_BODY = "orderBody"

        // Instance-state key for a CREDIT orderBody pending login (survives rotation).
        private const val KEY_PENDING_ORDER_BODY = "pending_handoff_order_body"
    }
}
