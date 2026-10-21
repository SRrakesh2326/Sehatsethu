// ForgotPasswordActivity.kt   (no ViewTools / UiTuning)
package com.simats.sehatsethu

import android.content.Intent
import android.os.Bundle
import android.util.Patterns
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.widget.AppCompatButton
import androidx.core.view.setPadding
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ForgotPasswordActivity : BaseActivity() {

    companion object {
        const val EXTRA_EMAIL_PREFILL = "extra_email_prefill"
    }

    private lateinit var role: Role
    private lateinit var progressOverlay: android.widget.FrameLayout

    private lateinit var btnBack: ImageView
    private lateinit var etEmail: EditText
    private lateinit var btnSend: AppCompatButton
    private lateinit var tvBackToSignIn: android.widget.TextView
    private lateinit var loadingOverlay: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_forgot_password)
        supportActionBar?.hide()

        role = RoleResolver.resolve(this, savedInstanceState)
        RoleResolver.persist(this, role)

        btnBack = findViewById(R.id.btn_back)
        etEmail = findViewById(R.id.et_email)
        btnSend = findViewById(R.id.btn_send_code)
        tvBackToSignIn = findViewById(R.id.tv_back_to_signin)
        loadingOverlay = findViewById(R.id.loading_overlay)
        setupLoadingOverlayNoDim()

        intent.getStringExtra(EXTRA_EMAIL_PREFILL)?.let { pre ->
            if (pre.isNotBlank()) etEmail.setText(pre)
        }

        btnBack.setOnClickListener { goBack() }
        tvBackToSignIn.setOnClickListener { goBack() }

        btnSend.setOnClickListener { sendOtp() }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(NavKeys.EXTRA_ROLE, role.id)
        super.onSaveInstanceState(outState)
    }

    private fun sendOtp() {
        val email = etEmail.text?.toString()?.trim().orEmpty()

        if (email.isBlank()) {
            toast(getString(R.string.err_email_required))
            return
        }
        if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            toast(getString(R.string.err_invalid_email))
            return
        }

        setLoading(true)

        lifecycleScope.launch {
            val res = withContext(Dispatchers.IO) { AuthApi.forgotSendOtp(email) }
            setLoading(false)

            if (res.ok && res.json?.optBoolean("ok", true) == true) {
                val otpCode = res.json?.optString("otp")?.trim().orEmpty()
                val i = Intent(this@ForgotPasswordActivity, VerifyOtpActivity::class.java).apply {
                    putExtra(VerifyOtpActivity.EXTRA_FLOW, VerifyOtpActivity.FLOW_RESET)
                    putExtra(VerifyOtpActivity.EXTRA_EMAIL, email)
                    if (otpCode.isNotBlank()) putExtra("extra_debug_otp", otpCode)
                    RoleResolver.putRole(this, role) // keep role across flow
                }
                goNext(i, finishThis = true)
            } else {
                val msg =
                    res.json?.optString("error")?.takeIf { it.isNotBlank() }
                        ?: res.errorMessage
                        ?: getString(R.string.forgot_failed)
                showDialog(getString(R.string.error), "$msg\n(${res.url})")
            }
        }
    }
    private fun setupLoadingOverlayNoDim() {
        val root = findViewById<View>(android.R.id.content) as ViewGroup

        progressOverlay = android.widget.FrameLayout(this).apply {
            visibility = View.GONE
            alpha = 0f
            setBackgroundColor(android.graphics.Color.TRANSPARENT) //  no page dim
            isClickable = true
            isFocusable = true

            val card = android.widget.LinearLayout(this@ForgotPasswordActivity).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(dp(16))
                background = getDrawable(R.drawable.bg_input)
                elevation = dp(10).toFloat()
            }

            val spinner = com.google.android.material.progressindicator.CircularProgressIndicator(this@ForgotPasswordActivity).apply {
                isIndeterminate = true
                indicatorSize = dp(28)
                trackThickness = dp(3)
                setIndicatorColor(android.graphics.Color.parseColor("#059669"))
                trackColor = 0xFFE2E8F0.toInt()
            }

            val tv = android.widget.TextView(this@ForgotPasswordActivity).apply {
                text = getString(R.string.loading)
                textSize = 14f
                setPadding(dp(12), 0, 0, 0)
                setTextColor(0xFF0F172A.toInt())
            }

            card.addView(spinner)
            card.addView(tv)

            val lp = android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = android.view.Gravity.CENTER }

            addView(card, lp)
        }

        root.addView(
            progressOverlay,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun setLoading(loading: Boolean) {
        if (loading) {
            progressOverlay.visibility = View.VISIBLE
            progressOverlay.animate().cancel()
            progressOverlay.animate().alpha(1f).setDuration(120).start()
        } else {
            progressOverlay.animate().cancel()
            progressOverlay.animate().alpha(0f).setDuration(120).withEndAction {
                progressOverlay.visibility = View.GONE
            }.start()
        }

        btnSend.isEnabled = !loading
        etEmail.isEnabled = !loading
        btnBack.isEnabled = !loading
        tvBackToSignIn.isEnabled = !loading
    }


    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun showDialog(title: String, msg: String) {
        android.app.AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(msg)
            .setPositiveButton(getString(R.string.ok), null)
            .show()
    }
}
