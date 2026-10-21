package com.simats.sehatsethu.roles.admin

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.simats.sehatsethu.R
import kotlinx.coroutines.launch
import java.util.Locale

class AdminHomeFragment : Fragment() {

    private enum class RoleChip { ALL, DOCTORS, PHARMACISTS }
    private var selectedChip: RoleChip = RoleChip.ALL

    //  will be set inside onCreateView
    private var refreshFn: (() -> Unit)? = null

    //  launcher to refresh after approve/reject
    private val reviewLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            if (res.resultCode == Activity.RESULT_OK) {
                refreshFn?.invoke()
            }
        }

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        val v = i.inflate(R.layout.fragment_admin_dashboard, c, false)

        val tvPending = v.findViewById<TextView>(R.id.tvStatPending)
        val tvApproved = v.findViewById<TextView>(R.id.tvStatApproved)
        val tvRejected = v.findViewById<TextView>(R.id.tvStatRejected)
        val tvTotal = v.findViewById<TextView>(R.id.tvStatTotal)

        val chipAll = v.findViewById<TextView>(R.id.chipAll)
        val chipDoctors = v.findViewById<TextView>(R.id.chipDoctors)
        val chipPharmacists = v.findViewById<TextView>(R.id.chipPharmacists)
        val chipRow = v.findViewById<View>(R.id.chipRowRole)

        val pb = v.findViewById<ProgressBar>(R.id.pbUsersLoading)
        val emptyState = v.findViewById<View>(R.id.emptyState)
        val tvEmptyTitle = v.findViewById<TextView>(R.id.tvEmptyTitle)
        val tvEmptySubtitle = v.findViewById<TextView>(R.id.tvEmptySubtitle)

        val swipe = v.findViewById<SwipeRefreshLayout>(R.id.swipeRefresh)
        val scroll = v.findViewById<View>(R.id.admin_dashboard_scroll)

        val rv = v.findViewById<RecyclerView>(R.id.rvUsers)
        rv.layoutManager = LinearLayoutManager(requireContext())

        val adapter = AdminUsersAdapter { row ->
            val intent = Intent(requireContext(), ReviewApplicationActivity::class.java).apply {
                putExtra(ReviewApplicationActivity.EXTRA_USER_ID, row.id)
                putExtra(ReviewApplicationActivity.EXTRA_STATUS, row.status)
                putExtra(ReviewApplicationActivity.EXTRA_ROLE, row.role)
                putExtra(ReviewApplicationActivity.EXTRA_NAME, row.fullName)
                putExtra(ReviewApplicationActivity.EXTRA_SUBTITLE, row.subtitle.orEmpty())
                putExtra(ReviewApplicationActivity.EXTRA_APPLIED_AT, row.appliedAt.orEmpty())
                putExtra(ReviewApplicationActivity.EXTRA_DOCS_COUNT, row.docsCount)
            }
            reviewLauncher.launch(intent) //  IMPORTANT
        }
        rv.adapter = adapter

        fun applyChipUI(selected: RoleChip) {
            fun setChip(tv: TextView, active: Boolean) {
                tv.setBackgroundResource(if (active) R.drawable.bg_chip_active else R.drawable.bg_chip)
                tv.setTextColor(if (active) 0xFFFFFFFF.toInt() else 0xFF475569.toInt())
            }
            setChip(chipAll, selected == RoleChip.ALL)
            setChip(chipDoctors, selected == RoleChip.DOCTORS)
            setChip(chipPharmacists, selected == RoleChip.PHARMACISTS)
        }

        fun roleParam(): String = when (selectedChip) {
            RoleChip.ALL -> "all"
            RoleChip.DOCTORS -> "doctor"
            RoleChip.PHARMACISTS -> "pharmacist"
        }

        fun showEmpty(title: String, subtitle: String) {
            emptyState.visibility = View.VISIBLE
            rv.visibility = View.GONE
            tvEmptyTitle.text = title
            tvEmptySubtitle.text = subtitle
        }

        fun hideEmpty() {
            emptyState.visibility = View.GONE
            rv.visibility = View.VISIBLE
        }

        fun refresh(fromPull: Boolean) {
            viewLifecycleOwner.lifecycleScope.launch {
                if (!fromPull) pb.visibility = View.VISIBLE
                hideEmpty()
                try {
                    AdminDashboardStore.refresh(requireContext(), roleParam())
                } finally {
                    pb.visibility = View.GONE
                    swipe.isRefreshing = false
                }
            }
        }

        //  for launcher callback
        refreshFn = { refresh(fromPull = false) }

        swipe.setOnChildScrollUpCallback { _, _ ->
            val scrollY = scroll.scrollY
            val triggerY = chipRow.top
            scrollY > triggerY
        }

        applyChipUI(selectedChip)

        chipAll.setOnClickListener {
            selectedChip = RoleChip.ALL
            applyChipUI(selectedChip)
            refresh(fromPull = false)
        }
        chipDoctors.setOnClickListener {
            selectedChip = RoleChip.DOCTORS
            applyChipUI(selectedChip)
            refresh(fromPull = false)
        }
        chipPharmacists.setOnClickListener {
            selectedChip = RoleChip.PHARMACISTS
            applyChipUI(selectedChip)
            refresh(fromPull = false)
        }

        swipe.setOnRefreshListener { refresh(fromPull = true) }

        AdminDashboardStore.state.observe(viewLifecycleOwner) { st ->
            when (st) {
                is AdminDashboardState.Loading -> {
                    adapter.submit(emptyList())
                    showEmpty(getString(R.string.loading), getString(R.string.please_try_again))
                }
                is AdminDashboardState.Ready -> {
                    tvPending.text = st.stats.pending.toString()
                    tvApproved.text = st.stats.approved.toString()
                    tvRejected.text = st.stats.rejected.toString()
                    tvTotal.text = st.stats.total.toString()

                    //  show ONLY UNDER_REVIEW
                    val underReview = st.recent.filter {
                        it.status.trim().uppercase(Locale.US) == "UNDER_REVIEW"
                    }

                    adapter.submit(underReview)

                    if (underReview.isEmpty()) {
                        showEmpty(
                            getString(R.string.no_applications_title),
                            getString(R.string.no_applications_subtitle)
                        )
                    } else {
                        hideEmpty()
                    }
                }
                is AdminDashboardState.Error -> {
                    adapter.submit(emptyList())
                    showEmpty(getString(R.string.couldnt_load_data), getString(R.string.please_try_again))
                }
            }
        }

        refresh(fromPull = false)
        return v
    }
}
