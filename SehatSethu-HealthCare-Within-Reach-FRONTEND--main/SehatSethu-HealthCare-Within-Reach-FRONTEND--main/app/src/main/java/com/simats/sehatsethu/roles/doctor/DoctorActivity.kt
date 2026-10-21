package com.simats.sehatsethu.roles.doctor

import android.os.Bundle
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

import androidx.core.widget.ImageViewCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.simats.sehatsethu.BaseActivity
import com.simats.sehatsethu.R
import kotlinx.coroutines.launch

class DoctorActivity : BaseActivity() {

    private enum class Tab { HOME, PATIENTS, CONSULT, SCHEDULE, PROFILE }
    private var currentTab: Tab = Tab.HOME

    private val active = 0xFF059669.toInt()
    private val inactive = 0xFF64748B.toInt()

    private lateinit var navHome: LinearLayout
    private lateinit var navPatients: LinearLayout
    private lateinit var navConsult: LinearLayout
    private lateinit var navSchedule: LinearLayout
    private lateinit var navProfile: LinearLayout

    private lateinit var tvHome: TextView
    private lateinit var tvPatients: TextView
    private lateinit var tvConsult: TextView
    private lateinit var tvSchedule: TextView
    private lateinit var tvProfile: TextView

    private lateinit var homeF: Fragment
    private lateinit var patientsF: Fragment
    private lateinit var consultF: Fragment
    private lateinit var scheduleF: Fragment
    private lateinit var profileF: Fragment

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_doctor)
        supportActionBar?.hide()

        navHome = findViewById(R.id.navHome)
        navPatients = findViewById(R.id.navPatients)
        navConsult = findViewById(R.id.navConsult)
        navSchedule = findViewById(R.id.navSchedule)
        navProfile = findViewById(R.id.navProfile)

        tvHome = findViewById(R.id.tvHome)
        tvPatients = findViewById(R.id.tvPatients)
        tvConsult = findViewById(R.id.tvConsult)
        tvSchedule = findViewById(R.id.tvSchedule)
        tvProfile = findViewById(R.id.tvProfile)

        if (savedInstanceState == null) {
            homeF = DoctorHomeFragment()
            patientsF = DoctorPatientsFragment()
            consultF = DoctorConsultFragment()
            scheduleF = DoctorScheduleFragment()
            profileF = DoctorProfileFragment()

            supportFragmentManager.beginTransaction()
                .setReorderingAllowed(true)
                .add(R.id.fragment_container, homeF, TAG_HOME)
                .add(R.id.fragment_container, patientsF, TAG_PATIENTS).hide(patientsF)
                .add(R.id.fragment_container, consultF, TAG_CONSULT).hide(consultF)
                .add(R.id.fragment_container, scheduleF, TAG_SCHEDULE).hide(scheduleF)
                .add(R.id.fragment_container, profileF, TAG_PROFILE).hide(profileF)
                .setMaxLifecycle(homeF, androidx.lifecycle.Lifecycle.State.RESUMED)
                .setMaxLifecycle(patientsF, androidx.lifecycle.Lifecycle.State.STARTED)
                .setMaxLifecycle(consultF, androidx.lifecycle.Lifecycle.State.STARTED)
                .setMaxLifecycle(scheduleF, androidx.lifecycle.Lifecycle.State.STARTED)
                .setMaxLifecycle(profileF, androidx.lifecycle.Lifecycle.State.STARTED)
                .commit()

            currentTab = Tab.HOME
        } else {
            homeF = supportFragmentManager.findFragmentByTag(TAG_HOME) ?: DoctorHomeFragment()
            patientsF = supportFragmentManager.findFragmentByTag(TAG_PATIENTS) ?: DoctorPatientsFragment()
            consultF = supportFragmentManager.findFragmentByTag(TAG_CONSULT) ?: DoctorConsultFragment()
            scheduleF = supportFragmentManager.findFragmentByTag(TAG_SCHEDULE) ?: DoctorScheduleFragment()
            profileF = supportFragmentManager.findFragmentByTag(TAG_PROFILE) ?: DoctorProfileFragment()

            currentTab = when {
                profileF.isVisible -> Tab.PROFILE
                scheduleF.isVisible -> Tab.SCHEDULE
                consultF.isVisible -> Tab.CONSULT
                patientsF.isVisible -> Tab.PATIENTS
                else -> Tab.HOME
            }
        }

        //  Ensure visuals always match the restored tab
        setSelected(currentTab)

        navHome.setOnClickListener { switchTo(Tab.HOME) }
        navPatients.setOnClickListener { switchTo(Tab.PATIENTS) }
        navConsult.setOnClickListener { switchTo(Tab.CONSULT) }
        navSchedule.setOnClickListener { switchTo(Tab.SCHEDULE) }
        navProfile.setOnClickListener { switchTo(Tab.PROFILE) }

        lifecycleScope.launch {
            DoctorUserStore.refresh(this@DoctorActivity)
        }
    }

    private fun switchTo(tab: Tab) {
        if (tab == currentTab) return

        val from = frag(currentTab)
        val to = frag(tab)

        supportFragmentManager.beginTransaction()
            .setReorderingAllowed(true)
            .hide(from)
            .show(to)
            .setMaxLifecycle(from, androidx.lifecycle.Lifecycle.State.STARTED)
            .setMaxLifecycle(to, androidx.lifecycle.Lifecycle.State.RESUMED)
            .commit()

        currentTab = tab
        setSelected(tab)
    }

    private fun frag(tab: Tab): Fragment = when (tab) {
        Tab.HOME -> homeF
        Tab.PATIENTS -> patientsF
        Tab.CONSULT -> consultF
        Tab.SCHEDULE -> scheduleF
        Tab.PROFILE -> profileF
    }

    private fun setSelected(tab: Tab) {
        // Text
        tvHome.setTextColor(if (tab == Tab.HOME) active else inactive)
        tvPatients.setTextColor(if (tab == Tab.PATIENTS) active else inactive)
        tvConsult.setTextColor(if (tab == Tab.CONSULT) active else inactive)
        tvSchedule.setTextColor(if (tab == Tab.SCHEDULE) active else inactive)
        tvProfile.setTextColor(if (tab == Tab.PROFILE) active else inactive)

        // Background pill (selected) + transparent (others)
        applyNavStyle(navHome, selected = tab == Tab.HOME)
        applyNavStyle(navPatients, selected = tab == Tab.PATIENTS)
        applyNavStyle(navConsult, selected = tab == Tab.CONSULT)
        applyNavStyle(navSchedule, selected = tab == Tab.SCHEDULE)
        applyNavStyle(navProfile, selected = tab == Tab.PROFILE)

        // Disable selected like big apps (no repeated taps)
        navHome.isEnabled = tab != Tab.HOME
        navPatients.isEnabled = tab != Tab.PATIENTS
        navConsult.isEnabled = tab != Tab.CONSULT
        navSchedule.isEnabled = tab != Tab.SCHEDULE
        navProfile.isEnabled = tab != Tab.PROFILE

        // Icon tint (ImageView is first child in each nav item)
        tintIcon(navHome, selected = tab == Tab.HOME)
        tintIcon(navPatients, selected = tab == Tab.PATIENTS)
        tintIcon(navConsult, selected = tab == Tab.CONSULT)
        tintIcon(navSchedule, selected = tab == Tab.SCHEDULE)
        tintIcon(navProfile, selected = tab == Tab.PROFILE)
    }

    private fun applyNavStyle(nav: LinearLayout, selected: Boolean) {
        if (selected) {
            nav.setBackgroundResource(R.drawable.bg_nav_selected)
        } else {
            nav.background = null // important (removes pill)
        }
    }

    private fun tintIcon(nav: LinearLayout, selected: Boolean) {
        val icon = nav.getChildAt(0) as? ImageView ?: return
        val color = if (selected) active else inactive
        ImageViewCompat.setImageTintList(icon, android.content.res.ColorStateList.valueOf(color))
    }

    //  PUBLIC HELPERS (used by DoctorHomeFragment quick actions)
    fun openPatientsTab() = switchTo(Tab.PATIENTS)
    fun openScheduleTab() = switchTo(Tab.SCHEDULE)
    fun openConsultTab() = switchTo(Tab.CONSULT)

    companion object {
        private const val TAG_HOME = "dr_home"
        private const val TAG_PATIENTS = "dr_patients"
        private const val TAG_CONSULT = "dr_consult"
        private const val TAG_SCHEDULE = "dr_schedule"
        private const val TAG_PROFILE = "dr_profile"
    }
}
