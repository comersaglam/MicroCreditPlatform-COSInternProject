package com.example.app_mobile.ui.reports

import androidx.fragment.app.Fragment
import com.example.app_mobile.R
import dagger.hilt.android.AndroidEntryPoint

/**
 * "Detaylı Bilgiler" tab (seller only) — a placeholder for now, mirroring app-pos.
 *
 * Passing the layout id to Fragment's constructor is the short form: the base class
 * inflates it, so there is no onCreateView to write. It grows into the full form
 * (date-range search, debt/collection totals) later.
 */
@AndroidEntryPoint
class ReportsFragment : Fragment(R.layout.fragment_reports)
