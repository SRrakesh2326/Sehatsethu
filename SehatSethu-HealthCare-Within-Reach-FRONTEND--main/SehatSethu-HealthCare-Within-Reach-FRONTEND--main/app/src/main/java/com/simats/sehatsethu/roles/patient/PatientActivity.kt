package com.simats.sehatsethu.roles.patient

import android.os.Bundle
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.simats.sehatsethu.BaseActivity
import com.simats.sehatsethu.R
import kotlinx.coroutines.launch

class PatientActivity : BaseActivity(), PatientHomeFragment.HomeNav {

    private enum class Tab { HOME, BOOKINGS, MEDICINE, RECORDS, PROFILE }
    private var currentTab: Tab = Tab.HOME

    private val active = 0xFF10B981.toInt()
    private val inactive = 0xFF64748B.toInt()

    private lateinit var navHome: LinearLayout
    private lateinit var navBookings: LinearLayout
    private lateinit var navMedicine: LinearLayout
    private lateinit var navRecords: LinearLayout
    private lateinit var navProfile: LinearLayout

    private lateinit var ivHome: ImageView
    private lateinit var ivBookings: ImageView
    private lateinit var ivMedicine: ImageView
    private lateinit var ivRecords: ImageView
    private lateinit var ivProfile: ImageView

    private lateinit var tvHome: TextView
    private lateinit var tvBookings: TextView
    private lateinit var tvMedicine: TextView
    private lateinit var tvRecords: TextView
    private lateinit var tvProfile: TextView

    private lateinit var homeF: Fragment
    private lateinit var bookingsF: Fragment
    private lateinit var medicineF: Fragment
    private lateinit var recordsF: Fragment
    private lateinit var profileF: Fragment

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_patient)
        supportActionBar?.hide()

        bindViews()

        if (savedInstanceState == null) {
            homeF = PatientHomeFragment()
            bookingsF = PatientBookingsFragment()

            //  Prefer PatientMedicineAvailabilityFragment if present
            medicineF = createFragmentOrNull("com.simats.sehatsethu.roles.patient.PatientMedicineAvailabilityFragment")
                ?: PatientMedicineFragment()

            recordsF = PatientRecordsFragment()
            profileF = PatientProfileFragment()

            supportFragmentManager.beginTransaction()
                .setReorderingAllowed(true)
                .add(R.id.fragment_container, homeF, TAG_HOME)
                .add(R.id.fragment_container, bookingsF, TAG_BOOKINGS).hide(bookingsF)
                .add(R.id.fragment_container, medicineF, TAG_MEDICINE).hide(medicineF)
                .add(R.id.fragment_container, recordsF, TAG_RECORDS).hide(recordsF)
                .add(R.id.fragment_container, profileF, TAG_PROFILE).hide(profileF)
                .setMaxLifecycle(homeF, androidx.lifecycle.Lifecycle.State.RESUMED)
                .setMaxLifecycle(bookingsF, androidx.lifecycle.Lifecycle.State.STARTED)
                .setMaxLifecycle(medicineF, androidx.lifecycle.Lifecycle.State.STARTED)
                .setMaxLifecycle(recordsF, androidx.lifecycle.Lifecycle.State.STARTED)
                .setMaxLifecycle(profileF, androidx.lifecycle.Lifecycle.State.STARTED)
                .commit()

            currentTab = Tab.HOME
            setSelected(Tab.HOME)
        } else {
            homeF = supportFragmentManager.findFragmentByTag(TAG_HOME) ?: PatientHomeFragment()
            bookingsF = supportFragmentManager.findFragmentByTag(TAG_BOOKINGS) ?: PatientBookingsFragment()

            medicineF = supportFragmentManager.findFragmentByTag(TAG_MEDICINE)
                ?: (createFragmentOrNull("com.simats.sehatsethu.roles.patient.PatientMedicineAvailabilityFragment")
                    ?: PatientMedicineFragment())

            recordsF = supportFragmentManager.findFragmentByTag(TAG_RECORDS) ?: PatientRecordsFragment()
            profileF = supportFragmentManager.findFragmentByTag(TAG_PROFILE) ?: PatientProfileFragment()

            currentTab = when {
                profileF.isVisible -> Tab.PROFILE
                recordsF.isVisible -> Tab.RECORDS
                medicineF.isVisible -> Tab.MEDICINE
                bookingsF.isVisible -> Tab.BOOKINGS
                else -> Tab.HOME
            }
            setSelected(currentTab)
        }

        navHome.setOnClickListener { switchTo(Tab.HOME) }
        navBookings.setOnClickListener { switchTo(Tab.BOOKINGS) }
        navMedicine.setOnClickListener { switchTo(Tab.MEDICINE) }
        navRecords.setOnClickListener { switchTo(Tab.RECORDS) }
        navProfile.setOnClickListener { switchTo(Tab.PROFILE) }

        lifecycleScope.launch {
            PatientUserStore.refresh(this@PatientActivity)
        }
    }

    //  HomeNav implementation (used by PatientHomeFragment)
    override fun openBookingsTab() = switchTo(Tab.BOOKINGS)
    override fun openMedicineTab() = switchTo(Tab.MEDICINE)
    override fun openRecordsTab() = switchTo(Tab.RECORDS)

    private fun bindViews() {
        navHome = findViewById(R.id.navHome)
        navBookings = findViewById(R.id.navBookings)
        navMedicine = findViewById(R.id.navMedicine)
        navRecords = findViewById(R.id.navRecords)
        navProfile = findViewById(R.id.navProfile)

        ivHome = findViewById(R.id.ivHome)
        ivBookings = findViewById(R.id.ivBookings)
        ivMedicine = findViewById(R.id.ivMedicine)
        ivRecords = findViewById(R.id.ivRecords)
        ivProfile = findViewById(R.id.ivProfile)

        tvHome = findViewById(R.id.tvHome)
        tvBookings = findViewById(R.id.tvBookings)
        tvMedicine = findViewById(R.id.tvMedicine)
        tvRecords = findViewById(R.id.tvRecords)
        tvProfile = findViewById(R.id.tvProfile)
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
        Tab.BOOKINGS -> bookingsF
        Tab.MEDICINE -> medicineF
        Tab.RECORDS -> recordsF
        Tab.PROFILE -> profileF
    }

    private fun setSelected(tab: Tab) {
        resetItem(navHome, ivHome, tvHome)
        resetItem(navBookings, ivBookings, tvBookings)
        resetItem(navMedicine, ivMedicine, tvMedicine)
        resetItem(navRecords, ivRecords, tvRecords)
        resetItem(navProfile, ivProfile, tvProfile)

        when (tab) {
            Tab.HOME -> selectItem(navHome, ivHome, tvHome)
            Tab.BOOKINGS -> selectItem(navBookings, ivBookings, tvBookings)
            Tab.MEDICINE -> selectItem(navMedicine, ivMedicine, tvMedicine)
            Tab.RECORDS -> selectItem(navRecords, ivRecords, tvRecords)
            Tab.PROFILE -> selectItem(navProfile, ivProfile, tvProfile)
        }
    }

    private fun resetItem(container: LinearLayout, icon: ImageView, label: TextView) {
        container.background = null
        icon.setColorFilter(inactive)
        label.setTextColor(inactive)
        label.setTypeface(label.typeface, android.graphics.Typeface.NORMAL)
    }

    private fun selectItem(container: LinearLayout, icon: ImageView, label: TextView) {
        container.setBackgroundResource(R.drawable.bg_patient_nav_selected)
        icon.setColorFilter(active)
        label.setTextColor(active)
        label.setTypeface(label.typeface, android.graphics.Typeface.BOLD)
    }

    private fun createFragmentOrNull(fqcn: String): Fragment? {
        return try {
            val clz = Class.forName(fqcn)
            val obj = clz.getDeclaredConstructor().newInstance()
            obj as? Fragment
        } catch (_: Throwable) {
            null
        }
    }

    companion object {
        private const val TAG_HOME = "pt_home"
        private const val TAG_BOOKINGS = "pt_bookings"
        private const val TAG_MEDICINE = "pt_medicine"
        private const val TAG_RECORDS = "pt_records"
        private const val TAG_PROFILE = "pt_profile"
    }
}
