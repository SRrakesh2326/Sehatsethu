package com.simats.sehatsethu

import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowInsetsControllerCompat

class SplashActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        supportActionBar?.hide()

        window.statusBarColor = 0xFF059669.toInt()
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = false

        applyLocalizedSplashText()

        window.decorView.postDelayed({

            val lang = AppPrefs.getLang(this)
            if (lang.isNullOrBlank()) {
                startFresh(LanguageSelectActivity::class.java)
                return@postDelayed
            }

            if (!AppPrefs.isOnboardingDone(this)) {
                startFresh(OnboardingActivity::class.java)
                return@postDelayed
            }

            val token = AppPrefs.getToken(this)

            if (!token.isNullOrBlank()) {
                val expired = JwtUtils.isExpired(token)
                AppPrefs.setTokenExpired(this, expired)
                startFresh(MainActivity::class.java)
                return@postDelayed
            }

            startFresh(RoleSelectActivity::class.java)

        }, 650)
    }

    private fun startFresh(cls: Class<*>) {
        startActivity(Intent(this, cls).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        })
        finish()
        overridePendingTransition(0, 0)
    }

    private fun applyLocalizedSplashText() {
        val root = findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
        val tvs = ViewTools.allTextViews(root)
        if (tvs.size >= 3) {
            tvs[0].text = getString(R.string.app_name)
            tvs[1].text = getString(R.string.splash_tagline)
            tvs[tvs.size - 1].text = getString(R.string.splash_bottom)
        }
    }
}
