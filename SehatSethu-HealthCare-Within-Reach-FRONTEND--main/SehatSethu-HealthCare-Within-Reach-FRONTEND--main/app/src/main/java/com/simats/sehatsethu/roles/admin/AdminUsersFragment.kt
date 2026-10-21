package com.simats.sehatsethu.roles.admin

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.simats.sehatsethu.R
import kotlinx.coroutines.launch

class AdminUsersFragment : Fragment() {

    private lateinit var chipAll: TextView
    private lateinit var chipVerified: TextView
    private lateinit var chipUnverified: TextView
    private lateinit var chipRejected: TextView

    private lateinit var tvSubtitle: TextView
    private lateinit var rv: RecyclerView
    private lateinit var emptyState: View
    private lateinit var tvEmptyTitle: TextView
    private lateinit var tvEmptySubtitle: TextView

    private var swipe: SwipeRefreshLayout? = null

    private var current = "all"

    //  throttle to avoid double calls (tab switch + onResume)
    private var lastRefreshAtMs: Long = 0L
    private val refreshCooldownMs = 500L

    private val adapter = AdminUsersAdapter { row ->
        val i = Intent(requireContext(), ReviewApplicationActivity::class.java).apply {
            putExtra(ReviewApplicationActivity.EXTRA_USER_ID, row.id)
            putExtra(ReviewApplicationActivity.EXTRA_STATUS, row.status)
            putExtra(ReviewApplicationActivity.EXTRA_ROLE, row.role)

            putExtra(ReviewApplicationActivity.EXTRA_NAME, row.fullName)
            putExtra(ReviewApplicationActivity.EXTRA_SUBTITLE, row.subtitle.orEmpty())
            putExtra(ReviewApplicationActivity.EXTRA_APPLIED_AT, row.appliedAt.orEmpty())
            putExtra(ReviewApplicationActivity.EXTRA_DOCS_COUNT, row.docsCount)
        }
        reviewLauncher.launch(i)
    }

    //  still keep launcher, but we refresh anyway via onResume + onFragmentStarted
    private val reviewLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            // no condition. event-driven refresh will happen onResume.
        }

    //  detect show/hide tab switches (AdminActivity uses hide/show)
    private val fmCallback = object : FragmentManager.FragmentLifecycleCallbacks() {
        override fun onFragmentStarted(fm: FragmentManager, f: Fragment) {
            if (f === this@AdminUsersFragment) {
                // fragment became visible (shown)
                refresh(fromPull = false, force = false)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        parentFragmentManager.registerFragmentLifecycleCallbacks(fmCallback, false)
    }

    override fun onDestroy() {
        parentFragmentManager.unregisterFragmentLifecycleCallbacks(fmCallback)
        super.onDestroy()
    }

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        val v = i.inflate(R.layout.fragment_admin_users, c, false)

        chipAll = v.findViewById(R.id.chipAll)
        chipVerified = v.findViewById(R.id.chipVerified)
        chipUnverified = v.findViewById(R.id.chipUnverified)
        chipRejected = v.findViewById(R.id.chipRejected)

        tvSubtitle = v.findViewById(R.id.tvSubtitle)

        rv = v.findViewById(R.id.rvUsers)
        rv.layoutManager = LinearLayoutManager(requireContext())
        rv.adapter = adapter

        emptyState = v.findViewById(R.id.emptyState)
        tvEmptyTitle = v.findViewById(R.id.tvEmptyTitle)
        tvEmptySubtitle = v.findViewById(R.id.tvEmptySubtitle)

        swipe = v.findViewById<SwipeRefreshLayout?>(R.id.swipeRefresh)

        setChipUi(current)

        chipAll.setOnClickListener { switchTo("all") }
        chipVerified.setOnClickListener { switchTo("verified") }
        chipUnverified.setOnClickListener { switchTo("unverified") }
        chipRejected.setOnClickListener { switchTo("rejected") }

        swipe?.setOnRefreshListener { refresh(fromPull = true, force = true) }

        AdminUsersStore.state.observe(viewLifecycleOwner) { st ->
            when (st) {
                is AdminUsersState.Loading -> {
                    tvSubtitle.text = getString(R.string.loading)
                    adapter.submit(emptyList())
                    showEmpty(false, "", "")
                }

                is AdminUsersState.Ready -> {
                    val size = st.users.size
                    tvSubtitle.text = if (size == 0)
                        getString(R.string.no_results)
                    else
                        getString(R.string.users_count, size)

                    adapter.submit(st.users)

                    if (size == 0) {
                        showEmpty(
                            true,
                            getString(R.string.no_users_title),
                            getString(R.string.no_users_subtitle)
                        )
                    } else {
                        showEmpty(false, "", "")
                    }
                }

                is AdminUsersState.Error -> {
                    tvSubtitle.text = st.message
                    adapter.submit(emptyList())
                    showEmpty(
                        true,
                        getString(R.string.couldnt_load_data),
                        getString(R.string.please_try_again)
                    )
                }
            }
        }

        // initial load
        refresh(fromPull = false, force = true)

        return v
    }

    override fun onResume() {
        super.onResume()
        //  Always refresh when returning to this fragment
        if (isVisible) {
            refresh(fromPull = false, force = false)
        }
    }

    private fun switchTo(filter: String) {
        if (filter == current) return
        current = filter
        setChipUi(filter)
        refresh(fromPull = false, force = true)
    }

    private fun refresh(fromPull: Boolean, force: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (!force && (now - lastRefreshAtMs) < refreshCooldownMs) return
        lastRefreshAtMs = now

        viewLifecycleOwner.lifecycleScope.launch {
            if (!fromPull) tvSubtitle.text = getString(R.string.loading)
            try {
                AdminUsersStore.load(requireContext(), current)
            } finally {
                swipe?.isRefreshing = false
            }
        }
    }

    private fun showEmpty(show: Boolean, title: String, subtitle: String) {
        emptyState.visibility = if (show) View.VISIBLE else View.GONE
        rv.visibility = if (show) View.GONE else View.VISIBLE
        if (show) {
            tvEmptyTitle.text = title
            tvEmptySubtitle.text = subtitle
        }
    }

    private fun setChipUi(filter: String) {
        fun active(tv: TextView, yes: Boolean) {
            tv.setTextColor(if (yes) 0xFFFFFFFF.toInt() else 0xFF475569.toInt())
            tv.setBackgroundResource(if (yes) R.drawable.bg_chip_active else R.drawable.bg_chip)
        }
        active(chipAll, filter == "all")
        active(chipVerified, filter == "verified")
        active(chipUnverified, filter == "unverified")
        active(chipRejected, filter == "rejected")
    }
}
