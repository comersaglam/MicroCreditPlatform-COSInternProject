package com.example.app_pos.ui.dashboard

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import com.example.app_pos.R
import com.example.app_pos.databinding.FragmentDashboardBinding

/**
 * The merchant's management section: a shell that hosts the three dashboard
 * tabs and nothing else.
 *
 * This fragment draws no content of its own. It owns a *second* NavHost — the
 * app therefore runs two nested navigation levels at once:
 *
 *   MainActivity's host  → saleFlow ↔ dashboardFragment   (outer)
 *   this fragment's host → customers / reports / profile   (inner)
 *
 * Keeping the tabs in an inner graph means switching tabs never touches the
 * outer back stack: pressing back from any tab leaves the dashboard as a whole
 * and returns to the sale flow, which is what a cashier expects.
 */
class DashboardFragment : Fragment() {

    private var _binding: FragmentDashboardBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDashboardBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // The inner host is a child fragment of this one, so it must be looked
        // up through childFragmentManager — the Activity's manager knows
        // nothing about it.
        val innerNavHost = childFragmentManager
            .findFragmentById(R.id.dashboardNavHost) as NavHostFragment

        // Wires each menu item to the destination sharing its id. That id match
        // (bottom_nav_menu.xml ↔ dashboard_graph.xml) is the whole contract —
        // no click listeners, no manual navigate() calls.
        binding.bottomNav.setupWithNavController(innerNavHost.navController)

        keepTabSelectedOnSubScreens(innerNavHost.navController)
        setupInnerBack(innerNavHost.navController)
    }

    /**
     * Handles going back from an inner screen (e.g. customer detail -> list) for
     * BOTH the system back button and the action-bar up arrow.
     *
     * The outer AppBarConfiguration treats the whole dashboard as one top-level
     * destination, so it never shows an up arrow for inner screens and never pops
     * them. We drive both ourselves, enabled only while the inner stack has
     * somewhere to go up to:
     *  - system back: an OnBackPressedCallback pops the inner stack;
     *  - up arrow: shown/hidden here, routed through MainActivity.onSupportNavigateUp
     *    (which already tries the inner controller first).
     */
    /**
     * Keeps a tab highlighted while the merchant is on one of its sub-screens.
     *
     * setupWithNavController only highlights a destination that IS a tab, so opening a
     * detail screen left the whole bar unselected — and it stayed that way after coming
     * back, because returning to a tab it had already "left" produced no selection change.
     */
    private fun keepTabSelectedOnSubScreens(innerNav: NavController) {
        innerNav.addOnDestinationChangedListener { _, destination, _ ->
            val tabId = when (destination.id) {
                R.id.customerDetailFragment -> R.id.customersFragment
                R.id.pairingFragment -> R.id.profileFragment
                else -> destination.id
            }
            // The label declared on the INNER destination. The action bar is wired to the
            // outer graph, whose only destination here is the dashboard itself -- so every
            // tab and every detail screen showed that one outer label and nothing ever
            // changed it.
            destination.label?.let {
                (activity as? AppCompatActivity)?.supportActionBar?.title = it
            }

            val item = binding.bottomNav.menu.findItem(tabId) ?: return@addOnDestinationChangedListener
            // isChecked, NOT selectedItemId: assigning the id makes the view act as though
            // the tab were tapped, which navigates away from the screen just opened.
            item.isChecked = true
        }
    }

    private fun setupInnerBack(inner: NavController) {
        val callback = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                inner.navigateUp()
            }
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, callback)
        inner.addOnDestinationChangedListener { _, _, _ ->
            val canGoUp = inner.previousBackStackEntry != null
            callback.isEnabled = canGoUp
            // Show the action-bar up arrow only on an inner sub-screen.
            (activity as? AppCompatActivity)?.supportActionBar
                ?.setDisplayHomeAsUpEnabled(canGoUp)
        }
    }

    /**
     * The inner nav controller, so the Activity can route the action-bar up arrow
     * into it (customer detail -> list). Null if the view isn't ready.
     */
    fun innerNavControllerOrNull(): NavController? {
        val host = childFragmentManager.findFragmentById(R.id.dashboardNavHost)
            as? NavHostFragment ?: return null
        return host.navController
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
