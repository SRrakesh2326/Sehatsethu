package com.simats.sehatsethu.roles.pharmacist

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.lifecycleScope
import com.simats.sehatsethu.BaseActivity
import com.simats.sehatsethu.R
import kotlinx.coroutines.launch
import java.util.Locale

class PharmacistActivity : BaseActivity() {

    private enum class Tab { HOME, STOCK, REQUESTS, PROFILE }
    private var currentTab: Tab = Tab.HOME

    private lateinit var homeI: ImageView
    private lateinit var stockI: ImageView
    private lateinit var reqI: ImageView
    private lateinit var profI: ImageView

    private lateinit var homeT: TextView
    private lateinit var stockT: TextView
    private lateinit var reqT: TextView
    private lateinit var profT: TextView

    private var vHomePill: View? = null
    private var vStockPill: View? = null
    private var vRequestsPill: View? = null
    private var vProfilePill: View? = null

    private lateinit var homeF: Fragment
    private lateinit var stockF: Fragment
    private lateinit var requestsF: Fragment
    private lateinit var profileF: Fragment

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pharmacist)
        supportActionBar?.hide()

        bindViews()

        if (savedInstanceState == null) {
            homeF = PharmacistHomeFragment()
            stockF = PharmacistStockFragment()
            requestsF = PharmacistRequestsFragment()
            profileF = PharmacistProfileFragment()

            supportFragmentManager.beginTransaction()
                .setReorderingAllowed(true)
                .add(R.id.fragment_container, homeF, TAG_HOME)
                .add(R.id.fragment_container, stockF, TAG_STOCK).hide(stockF)
                .add(R.id.fragment_container, requestsF, TAG_REQUESTS).hide(requestsF)
                .add(R.id.fragment_container, profileF, TAG_PROFILE).hide(profileF)
                .setMaxLifecycle(homeF, androidx.lifecycle.Lifecycle.State.RESUMED)
                .setMaxLifecycle(stockF, androidx.lifecycle.Lifecycle.State.STARTED)
                .setMaxLifecycle(requestsF, androidx.lifecycle.Lifecycle.State.STARTED)
                .setMaxLifecycle(profileF, androidx.lifecycle.Lifecycle.State.STARTED)
                .commit()

            currentTab = Tab.HOME
            setSelected(Tab.HOME)

            supportFragmentManager.executePendingTransactions()
        } else {
            homeF = supportFragmentManager.findFragmentByTag(TAG_HOME) ?: PharmacistHomeFragment()
            stockF = supportFragmentManager.findFragmentByTag(TAG_STOCK) ?: PharmacistStockFragment()
            requestsF = supportFragmentManager.findFragmentByTag(TAG_REQUESTS) ?: PharmacistRequestsFragment()
            profileF = supportFragmentManager.findFragmentByTag(TAG_PROFILE) ?: PharmacistProfileFragment()

            currentTab = when {
                profileF.isVisible -> Tab.PROFILE
                requestsF.isVisible -> Tab.REQUESTS
                stockF.isVisible -> Tab.STOCK
                else -> Tab.HOME
            }
            setSelected(currentTab)
        }

        bindClicks()

        //  Open correct tab if launched from Notifications
        handleOpenTabFromIntent(intent)

        lifecycleScope.launch {
            PharmacistUserStore.refresh(this@PharmacistActivity)
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent == null) return
        setIntent(intent)
        handleOpenTabFromIntent(intent)
    }

    //  Public API for fragments (Home -> Requests)
    fun openRequestsTab() {
        if (!this::requestsF.isInitialized) return
        if (currentTab != Tab.REQUESTS) switchTo(Tab.REQUESTS)
    }

    fun openHomeTab() {
        if (!this::homeF.isInitialized) return
        if (currentTab != Tab.HOME) switchTo(Tab.HOME)
    }

    private fun handleOpenTabFromIntent(i: Intent?) {
        val tab = intentToTab(i) ?: return
        if (tab != currentTab) {
            lifecycleScope.launch {
                runCatching { supportFragmentManager.executePendingTransactions() }
                switchTo(tab)
            }
        }
    }

    private fun intentToTab(i: Intent?): Tab? {
        if (i == null) return null

        val t1 = i.getStringExtra(EXTRA_OPEN_TAB)?.trim()?.uppercase(Locale.US)
        val t2 = i.getStringExtra(EXTRA_TAB)?.trim()?.uppercase(Locale.US)

        if (t1 == "REQUESTS" || t2 == "REQUESTS") return Tab.REQUESTS
        if (t1 == "STOCK" || t2 == "STOCK") return Tab.STOCK
        if (t1 == "HOME" || t2 == "HOME") return Tab.HOME
        if (t1 == "PROFILE" || t2 == "PROFILE") return Tab.PROFILE

        if (i.getBooleanExtra(EXTRA_OPEN_REQUESTS, false)) return Tab.REQUESTS

        return null
    }

    private fun bindViews() {
        homeI = findViewById(R.id.ivNavHome)
        stockI = findViewById(R.id.ivNavStock)
        reqI = findViewById(R.id.ivNavRequests)
        profI = findViewById(R.id.ivNavProfile)

        homeT = findViewById(R.id.tvNavHome)
        stockT = findViewById(R.id.tvNavStock)
        reqT = findViewById(R.id.tvNavRequests)
        profT = findViewById(R.id.tvNavProfile)

        vHomePill = findViewById<View?>(R.id.vHomePill)
        vStockPill = findViewById<View?>(R.id.vStockPill)
        vRequestsPill = findViewById<View?>(R.id.vRequestsPill)
        vProfilePill = findViewById<View?>(R.id.vProfilePill)
    }

    private fun bindClicks() {
        homeI.setOnClickListener { switchTo(Tab.HOME) }
        homeT.setOnClickListener { switchTo(Tab.HOME) }

        stockI.setOnClickListener { switchTo(Tab.STOCK) }
        stockT.setOnClickListener { switchTo(Tab.STOCK) }

        reqI.setOnClickListener { switchTo(Tab.REQUESTS) }
        reqT.setOnClickListener { switchTo(Tab.REQUESTS) }

        profI.setOnClickListener { switchTo(Tab.PROFILE) }
        profT.setOnClickListener { switchTo(Tab.PROFILE) }

        findViewById<View?>(R.id.navHome)?.setOnClickListener { switchTo(Tab.HOME) }
        findViewById<View?>(R.id.navStock)?.setOnClickListener { switchTo(Tab.STOCK) }
        findViewById<View?>(R.id.navRequests)?.setOnClickListener { switchTo(Tab.REQUESTS) }
        findViewById<View?>(R.id.navProfile)?.setOnClickListener { switchTo(Tab.PROFILE) }
    }

    private fun switchTo(tab: Tab) {
        if (tab == currentTab) return

        val fm: FragmentManager = supportFragmentManager
        val from = fragmentFor(currentTab)
        val to = fragmentFor(tab)

        fm.beginTransaction()
            .setReorderingAllowed(true)
            .hide(from)
            .show(to)
            .setMaxLifecycle(from, androidx.lifecycle.Lifecycle.State.STARTED)
            .setMaxLifecycle(to, androidx.lifecycle.Lifecycle.State.RESUMED)
            .commit()

        currentTab = tab
        setSelected(tab)
    }

    private fun fragmentFor(tab: Tab): Fragment = when (tab) {
        Tab.HOME -> homeF
        Tab.STOCK -> stockF
        Tab.REQUESTS -> requestsF
        Tab.PROFILE -> profileF
    }

    private fun setSelected(tab: Tab) {
        val active = 0xFF10B981.toInt()
        val inactive = 0xFF64748B.toInt()

        vHomePill?.visibility = if (tab == Tab.HOME) View.VISIBLE else View.GONE
        vStockPill?.visibility = if (tab == Tab.STOCK) View.VISIBLE else View.GONE
        vRequestsPill?.visibility = if (tab == Tab.REQUESTS) View.VISIBLE else View.GONE
        vProfilePill?.visibility = if (tab == Tab.PROFILE) View.VISIBLE else View.GONE

        homeI.setColorFilter(if (tab == Tab.HOME) active else inactive)
        stockI.setColorFilter(if (tab == Tab.STOCK) active else inactive)
        reqI.setColorFilter(if (tab == Tab.REQUESTS) active else inactive)
        profI.setColorFilter(if (tab == Tab.PROFILE) active else inactive)

        homeT.setTextColor(if (tab == Tab.HOME) active else inactive)
        stockT.setTextColor(if (tab == Tab.STOCK) active else inactive)
        reqT.setTextColor(if (tab == Tab.REQUESTS) active else inactive)
        profT.setTextColor(if (tab == Tab.PROFILE) active else inactive)
    }

    companion object {
        private const val TAG_HOME = "ph_home"
        private const val TAG_STOCK = "ph_stock"
        private const val TAG_REQUESTS = "ph_requests"
        private const val TAG_PROFILE = "ph_profile"

        const val EXTRA_OPEN_TAB = "open_tab"
        const val EXTRA_TAB = "tab"
        const val EXTRA_OPEN_REQUESTS = "open_requests"
    }
}
