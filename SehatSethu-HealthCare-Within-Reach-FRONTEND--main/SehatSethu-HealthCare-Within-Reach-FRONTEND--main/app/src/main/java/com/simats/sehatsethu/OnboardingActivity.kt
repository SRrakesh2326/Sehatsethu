package com.simats.sehatsethu

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.viewpager2.widget.ViewPager2

class OnboardingActivity : BaseActivity() {

    private lateinit var vp: ViewPager2
    private lateinit var dots: Array<View>

    private var pendingFinishAfterPerms = false

    //  Only these 4 categories:
    // Camera, Location, Microphone, Phone Call
    private val permsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            val allGranted = requiredRuntimePermissions().all { isGranted(it) }

            if (pendingFinishAfterPerms && allGranted) {
                pendingFinishAfterPerms = false
                doCompleteOnboarding()
                return@registerForActivityResult
            }

            if (!allGranted) {
                showPermissionsDeniedDialog()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()

        setContentView(R.layout.activity_onboarding)

        val tvSkip = findViewById<View>(R.id.tv_skip)
        val btnBack = findViewById<View>(R.id.btn_back)
        val btnNext = findViewById<View>(R.id.btn_next)

        vp = findViewById(R.id.vp_onboarding)

        dots = arrayOf(
            findViewById(R.id.dot_0),
            findViewById(R.id.dot_1),
            findViewById(R.id.dot_2)
        )

        vp.adapter = OnboardingPagerAdapter(this)
        vp.isUserInputEnabled = true

        fun applyUi(position: Int) {
            updateDots(position)
            tvSkip.visibility = if (position < 2) View.VISIBLE else View.INVISIBLE
            btnBack.visibility = View.VISIBLE

            val nextButton = btnNext as androidx.appcompat.widget.AppCompatButton
            nextButton.text = if (position < 2) getString(R.string.next) else getString(R.string.get_started)
        }

        applyUi(0)

        vp.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                applyUi(position)
            }
        })

        tvSkip.setOnClickListener { completeOnboarding() }

        btnBack.setOnClickListener {
            val p = vp.currentItem
            if (p == 0) {
                goBack(Intent(this, LanguageSelectActivity::class.java))
            } else {
                vp.currentItem = p - 1
            }
        }

        btnNext.setOnClickListener {
            val p = vp.currentItem
            if (p < 2) {
                vp.currentItem = p + 1
            } else {
                completeOnboarding()
            }
        }

        //  Ask only these 4 permissions during onboarding
        requestAllIfMissing()
    }

    private fun completeOnboarding() {
        if (!hasAllRequiredPermissions()) {
            pendingFinishAfterPerms = true
            requestAllIfMissing(force = true)
            return
        }
        doCompleteOnboarding()
    }

    private fun doCompleteOnboarding() {
        AppPrefs.setOnboardingDone(this, true)

        startActivity(Intent(this, RoleSelectActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        })
        finish()
        overridePendingTransition(0, 0)
    }

    private fun requestAllIfMissing(force: Boolean = false) {
        val base = requiredRuntimePermissions()
        val missing = base.filter { force || !isGranted(it) }
        if (missing.isEmpty()) return
        permsLauncher.launch(missing.toTypedArray())
    }

    private fun hasAllRequiredPermissions(): Boolean {
        return requiredRuntimePermissions().all { isGranted(it) }
    }

    private fun requiredRuntimePermissions(): List<String> {
        return listOf(
            // Camera
            Manifest.permission.CAMERA,

            // Microphone
            Manifest.permission.RECORD_AUDIO,

            // Location (foreground)
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,

            // Phone call
            Manifest.permission.CALL_PHONE
        ).distinct()
    }

    private fun isGranted(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun showPermissionsDeniedDialog() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.permissions_required_title))
            .setMessage(getString(R.string.permissions_required_message))
            .setCancelable(true)
            .setPositiveButton(getString(R.string.open_settings)) { _, _ ->
                openAppSettings()
            }
            .setNegativeButton(getString(R.string.retry)) { _, _ ->
                requestAllIfMissing(force = true)
            }
            .show()
    }

    private fun openAppSettings() {
        val i = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", packageName, null)
        }
        startActivity(i)
    }

    private fun updateDots(active: Int) {
        for (i in dots.indices) {
            dots[i].background = getDrawable(
                if (i == active) R.drawable.bg_dot_active else R.drawable.bg_dot_inactive
            )
            val lp = dots[i].layoutParams
            lp.width = if (i == active) dp(28) else dp(8)
            dots[i].layoutParams = lp
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
