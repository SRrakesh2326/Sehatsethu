package com.simats.sehatsethu.roles.admin

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Bundle
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.widget.ImageViewCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import com.simats.sehatsethu.AppPrefs
import com.simats.sehatsethu.BaseActivity
import com.simats.sehatsethu.R
import com.simats.sehatsethu.RoleSelectActivity
import com.simats.sehatsethu.SessionProfile

class AdminActivity : BaseActivity() {

    private enum class Tab { HOME, USERS, PROFILE }
    private var currentTab: Tab = Tab.HOME

    private lateinit var navHome: LinearLayout
    private lateinit var navUsers: LinearLayout
    private lateinit var navProfile: LinearLayout

    private lateinit var ivHome: ImageView
    private lateinit var ivUsers: ImageView
    private lateinit var ivProfile: ImageView

    private lateinit var tvHome: TextView
    private lateinit var tvUsers: TextView
    private lateinit var tvProfile: TextView

    private lateinit var homeF: Fragment
    private lateinit var usersF: Fragment
    private lateinit var profileF: Fragment

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // If token missing -> RoleSelect
        val token = AppPrefs.getToken(this)
        if (token.isNullOrBlank()) {
            goBack(Intent(this, RoleSelectActivity::class.java))
            return
        }

        setContentView(R.layout.activity_admin)
        supportActionBar?.hide()

        navHome = findViewById(R.id.navHome)
        navUsers = findViewById(R.id.navUsers)
        navProfile = findViewById(R.id.navProfile)

        ivHome = findViewById(R.id.ivHome)
        ivUsers = findViewById(R.id.ivUsers)
        ivProfile = findViewById(R.id.ivProfile)

        tvHome = findViewById(R.id.tvHome)
        tvUsers = findViewById(R.id.tvUsers)
        tvProfile = findViewById(R.id.tvProfile)

        if (savedInstanceState == null) {
            homeF = AdminHomeFragment()
            usersF = AdminUsersFragment()
            profileF = AdminProfileFragment()

            supportFragmentManager.beginTransaction()
                .setReorderingAllowed(true)
                .add(R.id.fragment_container, homeF, TAG_HOME)
                .add(R.id.fragment_container, usersF, TAG_USERS).hide(usersF)
                .add(R.id.fragment_container, profileF, TAG_PROFILE).hide(profileF)
                .setMaxLifecycle(homeF, Lifecycle.State.RESUMED)
                .setMaxLifecycle(usersF, Lifecycle.State.STARTED)
                .setMaxLifecycle(profileF, Lifecycle.State.STARTED)
                .commit()

            currentTab = Tab.HOME
            renderTabUI(currentTab)
        } else {
            homeF = supportFragmentManager.findFragmentByTag(TAG_HOME) ?: AdminHomeFragment()
            usersF = supportFragmentManager.findFragmentByTag(TAG_USERS) ?: AdminUsersFragment()
            profileF = supportFragmentManager.findFragmentByTag(TAG_PROFILE) ?: AdminProfileFragment()

            val tx = supportFragmentManager.beginTransaction().setReorderingAllowed(true)
            if (!homeF.isAdded) tx.add(R.id.fragment_container, homeF, TAG_HOME)
            if (!usersF.isAdded) tx.add(R.id.fragment_container, usersF, TAG_USERS)
            if (!profileF.isAdded) tx.add(R.id.fragment_container, profileF, TAG_PROFILE)

            tx.hide(homeF).hide(usersF).hide(profileF)

            currentTab = when {
                profileF.isVisible -> Tab.PROFILE
                usersF.isVisible -> Tab.USERS
                else -> Tab.HOME
            }

            val visible = frag(currentTab)
            tx.show(visible)
                .setMaxLifecycle(homeF, if (currentTab == Tab.HOME) Lifecycle.State.RESUMED else Lifecycle.State.STARTED)
                .setMaxLifecycle(usersF, if (currentTab == Tab.USERS) Lifecycle.State.RESUMED else Lifecycle.State.STARTED)
                .setMaxLifecycle(profileF, if (currentTab == Tab.PROFILE) Lifecycle.State.RESUMED else Lifecycle.State.STARTED)
                .commit()

            renderTabUI(currentTab)
        }

        navHome.setOnClickListener { switchTo(Tab.HOME) }
        navUsers.setOnClickListener { switchTo(Tab.USERS) }
        navProfile.setOnClickListener { switchTo(Tab.PROFILE) }

        //  IMPORTANT: DO NOT add onBackPressed callback here.
        // BaseActivity already does:
        // - logged in + root -> moveTaskToBack(true)
        // - not logged in + root -> RoleSelect

        SessionProfile.get(this)
    }

    private fun switchTo(tab: Tab) {
        if (tab == currentTab) return

        val from = frag(currentTab)
        val to = frag(tab)

        supportFragmentManager.beginTransaction()
            .setReorderingAllowed(true)
            .hide(from)
            .show(to)
            .setMaxLifecycle(from, Lifecycle.State.STARTED)
            .setMaxLifecycle(to, Lifecycle.State.RESUMED)
            .commit()

        currentTab = tab
        renderTabUI(tab)
    }

    private fun renderTabUI(tab: Tab) {
        val selectedColor = ContextCompat.getColor(this, R.color.emerald_600_fallback)
        val unselectedColor = ContextCompat.getColor(this, R.color.slate_500_fallback)

        navHome.setBackgroundResource(if (tab == Tab.HOME) R.drawable.bg_nav_selected else R.drawable.bg_nav_unselected)
        navUsers.setBackgroundResource(if (tab == Tab.USERS) R.drawable.bg_nav_selected else R.drawable.bg_nav_unselected)
        navProfile.setBackgroundResource(if (tab == Tab.PROFILE) R.drawable.bg_nav_selected else R.drawable.bg_nav_unselected)

        tint(ivHome, if (tab == Tab.HOME) selectedColor else unselectedColor)
        tint(ivUsers, if (tab == Tab.USERS) selectedColor else unselectedColor)
        tint(ivProfile, if (tab == Tab.PROFILE) selectedColor else unselectedColor)

        styleText(tvHome, tab == Tab.HOME, selectedColor, unselectedColor)
        styleText(tvUsers, tab == Tab.USERS, selectedColor, unselectedColor)
        styleText(tvProfile, tab == Tab.PROFILE, selectedColor, unselectedColor)
    }

    private fun tint(iv: ImageView, color: Int) {
        ImageViewCompat.setImageTintList(iv, ColorStateList.valueOf(color))
    }

    private fun styleText(tv: TextView, selected: Boolean, selectedColor: Int, unselectedColor: Int) {
        tv.setTextColor(if (selected) selectedColor else unselectedColor)
        tv.setTypeface(tv.typeface, if (selected) Typeface.BOLD else Typeface.NORMAL)
    }

    private fun frag(tab: Tab): Fragment = when (tab) {
        Tab.HOME -> homeF
        Tab.USERS -> usersF
        Tab.PROFILE -> profileF
    }

    companion object {
        private const val TAG_HOME = "ad_home"
        private const val TAG_USERS = "ad_users"
        private const val TAG_PROFILE = "ad_profile"
    }
}
