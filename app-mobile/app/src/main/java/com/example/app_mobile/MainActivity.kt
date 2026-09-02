package com.example.app_mobile

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.navigateUp
import androidx.navigation.ui.setupActionBarWithNavController
import com.example.app_mobile.databinding.ActivityMainBinding
import com.example.app_mobile.ui.dashboard.DashboardFragment
import com.example.app_pos.model.Repository
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * The app's only Activity. Every screen is a Fragment inside the nav host.
 *
 * Unlike app-pos, app-mobile has a single entry point (the launcher) — there is
 * no payment-app handoff, so none of app-pos's CREDIT-intent machinery exists
 * here. What is kept is the session gate (start on login vs dashboard) and the
 * inner-nav up routing for the dashboard's bottom-nav sub-screens.
 */
@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var appBarConfiguration: AppBarConfiguration

    // Field injection because Hilt constructs Activities itself; ready by super.onCreate().
    @Inject lateinit var repo: Repository

    private val navController: NavController
        get() = (supportFragmentManager
            .findFragmentById(R.id.navHostFragment) as NavHostFragment).navController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Pick the start destination from the session BEFORE the graph is used, so a
        // valid session opens straight on the dashboard with no login flash. Only on
        // a fresh start: on recreation the controller restores its own back stack.
        if (savedInstanceState == null) {
            val graph = navController.navInflater.inflate(R.navigation.nav_graph)
            graph.setStartDestination(
                if (repo.isSessionValid()) R.id.dashboardFragment else R.id.loginFragment
            )
            navController.graph = graph
        }

        appBarConfiguration = AppBarConfiguration(navController.graph)
        setupActionBarWithNavController(navController, appBarConfiguration)

        // The T-Fides mark in the bar, on every screen. On the activity rather than in a
        // layout because the bar belongs to the activity: in the dashboard's layout it
        // would vanish on the detail screens, which is where someone is looking at money
        // and most wants to know whose app they are in.
        //
        // A custom view, NOT setLogo(): that places the mark inside the title block, where
        // it flows with the text and drifts as the title changes length. This one is
        // positioned by its own gravity and stays at the right end.
        supportActionBar?.apply {
            setDisplayShowCustomEnabled(true)
            setCustomView(R.layout.actionbar_logo)
            // The custom view fills the bar so its own gravity can push the mark to the
            // right end; without explicit params it is measured as wrap_content and lands
            // wherever the title leaves room, which is the drift this replaced.
            customView.layoutParams = androidx.appcompat.app.ActionBar.LayoutParams(
                androidx.appcompat.app.ActionBar.LayoutParams.MATCH_PARENT,
                androidx.appcompat.app.ActionBar.LayoutParams.MATCH_PARENT,
            )
        }
    }

    /**
     * Routes the action bar's up arrow. The dashboard hosts its own inner nav
     * controller (debts / approvals / profile + seller detail), which the outer
     * controller knows nothing about, so try the inner one first (that pops seller
     * detail back to the list) and fall back to the outer controller.
     */
    override fun onSupportNavigateUp(): Boolean =
        innerNavController()?.navigateUp() == true ||
            navController.navigateUp(appBarConfiguration) ||
            super.onSupportNavigateUp()

    /** The dashboard's inner nav controller, if it is on screen and its inner back
     *  stack has somewhere to go up to (e.g. seller detail). */
    private fun innerNavController(): NavController? {
        val dashboard = supportFragmentManager
            .findFragmentById(R.id.navHostFragment)
            ?.childFragmentManager
            ?.primaryNavigationFragment as? DashboardFragment ?: return null
        val inner = dashboard.innerNavControllerOrNull() ?: return null
        return if (inner.previousBackStackEntry != null) inner else null
    }

    /** Called by LoginFragment after sign-in: go to the dashboard, dropping the gate. */
    fun onLoginSucceeded() {
        navController.navigate(R.id.action_global_dashboard_after_login)
    }

    /** Returns to the login gate, clearing the dashboard behind it (profile logout).
     *  Uses the OUTER controller: login lives in the outer graph. */
    fun navigateToLogin() {
        navController.navigate(R.id.action_global_login)
    }
}
