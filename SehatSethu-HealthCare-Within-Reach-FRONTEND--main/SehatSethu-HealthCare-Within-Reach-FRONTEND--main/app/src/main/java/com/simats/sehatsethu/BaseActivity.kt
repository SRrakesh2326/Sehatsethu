package com.simats.sehatsethu

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

open class BaseActivity : AppCompatActivity() {

    // Apply selected language to every Activity automatically
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleManager.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Let our layout handle the status bar area (no automatic system bar padding)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        setupRootBackBehavior()
    }

    /**
     * Called after setContentView — applies the real status bar height as
     * top padding on the root view of every Activity automatically.
     * Existing top padding in the layout is preserved (padding is additive).
     */
    override fun setContentView(layoutResID: Int) {
        super.setContentView(layoutResID)
        applyStatusBarTopPadding()
    }

    override fun setContentView(view: View) {
        super.setContentView(view)
        applyStatusBarTopPadding()
    }

    private fun applyStatusBarTopPadding() {
        val root = window.decorView.findViewById<View>(android.R.id.content) ?: return
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val statusBarHeight = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            // Only set if not already handled (avoid double padding on re-attach)
            if (v.tag != statusBarHeight) {
                v.tag = statusBarHeight
                v.setPadding(
                    v.paddingLeft,
                    statusBarHeight,
                    v.paddingRight,
                    v.paddingBottom
                )
            }
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }

    /**
     * Root-back behavior:
     * - If this Activity is task root AND user is logged in -> close app (move to background)
     * - If task root AND not logged in -> go to RoleSelectActivity
     * - Otherwise -> normal back
     */
    private fun setupRootBackBehavior() {
        onBackPressedDispatcher.addCallback(this) {
            // Not root => normal back
            if (!isTaskRoot) {
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
                return@addCallback
            }

            // Root screen
            val token = (AppPrefs.getToken(this@BaseActivity) ?: "").trim()

            if (token.isNotEmpty()) {
                // Logged in root (dashboard): close/minimize app instead of navigating to RoleSelect
                moveTaskToBack(true)
            } else {
                // Not logged in root: go RoleSelect (your strict rule)
                startActivity(Intent(this@BaseActivity, RoleSelectActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                })
                finish()
                overridePendingTransition(0, 0)
            }
        }
    }

    /** Forward navigation (Next) */
    protected fun goNext(intent: Intent, finishThis: Boolean = false) {
        startActivity(intent)
        overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left)
        if (finishThis) finish()
    }

    /** Back navigation */
    protected fun goBack(intent: Intent? = null) {
        if (intent != null) {
            startActivity(intent)
            overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right)
            finish()
        } else {
            finish()
            overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right)
        }
    }

    override fun finish() {
        super.finish()
        // If caller didn't use goBack(), still keep it smooth:
        overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right)
    }
}
