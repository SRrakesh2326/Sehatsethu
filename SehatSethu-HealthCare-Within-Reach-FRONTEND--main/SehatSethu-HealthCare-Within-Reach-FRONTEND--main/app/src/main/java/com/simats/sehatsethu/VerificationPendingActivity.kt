package com.simats.sehatsethu

import android.content.Intent
import android.graphics.drawable.Animatable
import android.os.Bundle
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.appcompat.content.res.AppCompatResources
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class VerificationPendingActivity : BaseActivity() {

    private lateinit var role: Role
    private lateinit var ivBack: ImageView
    private lateinit var btnCheck: MaterialButton

    private var isRefreshing = false
    private var runningAnim: Animatable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_verification_pending)
        supportActionBar?.hide()

        role = RoleResolver.resolve(this, savedInstanceState)
        RoleResolver.persist(this, role)

        ivBack = findViewById(R.id.ivBack)
        btnCheck = findViewById(R.id.btnCheckStatus)

        ivBack.setOnClickListener { logoutAndExit() }
        onBackPressedDispatcher.addCallback(this) { logoutAndExit() }

        // Ensure default icon (non-rotating) initially
        setRefreshing(false)

        btnCheck.setOnClickListener { checkStatus(force = true) }
    }

    override fun onDestroy() {
        setRefreshing(false)
        super.onDestroy()
    }

    private fun logoutAndExit() {
        runCatching { AppPrefs.setToken(this, "") }
        runCatching { AppPrefs.setProfileCompleted(this, false) }
        runCatching { AppPrefs.setAdminVerificationStatus(this, "") }
        runCatching { AppPrefs.setAdminVerificationReason(this, null) }
        runCatching { AppPrefs.setDoctorApplicationNo(this, "") }

        RoleResolver.persist(this, role)

        val i = Intent(this, RoleSelectActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(i)
        finish()
        overridePendingTransition(0, 0)
    }

    private fun checkStatus(force: Boolean) {
        val token = AppPrefs.getToken(this).orEmpty()
        if (token.isBlank()) {
            Toast.makeText(this, getString(R.string.please_login_again), Toast.LENGTH_SHORT).show()
            logoutAndExit()
            return
        }

        // One click -> one rotation (always visible)
        spinRefreshOnce(300L)

        btnCheck.isEnabled = false

        lifecycleScope.launch {
            val res = withContext(Dispatchers.IO) {
                AuthApi.checkVerificationStatus(token = token)
            }

            btnCheck.isEnabled = true

            val j = res.json
            val ok = res.ok && (j?.optBoolean("ok", false) == true)
            if (!ok) {
                Toast.makeText(
                    this@VerificationPendingActivity,
                    j?.optString("error") ?: res.errorMessage ?: getString(R.string.failed),
                    Toast.LENGTH_SHORT
                ).show()
                return@launch
            }

            val status = (j?.optString("admin_verification_status") ?: "UNVERIFIED").uppercase()
            val reason = j?.optString("admin_verification_reason")?.trim().orEmpty()

            AppPrefs.setAdminVerificationStatus(this@VerificationPendingActivity, status)
            AppPrefs.setAdminVerificationReason(this@VerificationPendingActivity, reason.ifBlank { null })

            when (status) {
                "VERIFIED" -> {
                    val i = Intent(this@VerificationPendingActivity, VerificationApprovedActivity::class.java)
                    RoleResolver.putRole(i, role)
                    goNext(i, finishThis = true)
                }
                "REJECTED" -> {
                    val i = Intent(this@VerificationPendingActivity, VerificationRejectedActivity::class.java).apply {
                        putExtra(VerificationRejectedActivity.EXTRA_REASON, reason)
                    }
                    RoleResolver.putRole(i, role)
                    goNext(i, finishThis = true)
                }
                else -> {
                    Toast.makeText(this@VerificationPendingActivity, getString(R.string.under_review), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }


    /**
     * Rotates ONLY the refresh icon in the button (like Google/YouTube pull-to-refresh).
     * No text ellipsize/changes, just icon animation.
     */
    private fun setRefreshing(refreshing: Boolean) {
        if (refreshing) {
            val d = androidx.appcompat.content.res.AppCompatResources
                .getDrawable(this, R.drawable.anim_refresh_rotate)
                ?.mutate()

            btnCheck.icon = d
            btnCheck.isEnabled = false

            // Start the animation after the icon is attached to the view
            btnCheck.post {
                (btnCheck.icon as? android.graphics.drawable.Animatable)?.start()
                // If Animatable is null, your drawable isn't animated-rotate / not loaded correctly.
            }
        } else {
            (btnCheck.icon as? android.graphics.drawable.Animatable)?.stop()

            btnCheck.icon = androidx.appcompat.content.res.AppCompatResources
                .getDrawable(this, R.drawable.ic_refresh)
                ?.mutate()

            btnCheck.isEnabled = true
        }
    }
    private fun spinRefreshOnce(durationMs: Long = 300L) {
        // swap icon to animated rotate drawable (configured for ONE cycle)
        val anim = androidx.appcompat.content.res.AppCompatResources
            .getDrawable(this, R.drawable.anim_refresh_rotate)
            ?.mutate()

        btnCheck.icon = anim
        btnCheck.post {
            (btnCheck.icon as? android.graphics.drawable.Animatable)?.start()
        }

        // Restore back to normal refresh icon after ONE rotation duration
        btnCheck.postDelayed({
            btnCheck.icon = androidx.appcompat.content.res.AppCompatResources
                .getDrawable(this, R.drawable.ic_refresh)
                ?.mutate()
        }, durationMs + 30L) // tiny buffer so it completes cleanly
    }


    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(NavKeys.EXTRA_ROLE, role.id)
        super.onSaveInstanceState(outState)
    }
}
