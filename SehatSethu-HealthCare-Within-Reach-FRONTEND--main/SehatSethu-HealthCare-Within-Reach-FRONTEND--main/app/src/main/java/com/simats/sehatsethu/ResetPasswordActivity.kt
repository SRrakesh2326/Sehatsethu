// ResetPasswordActivity.kt  (uses your existing ids from activity_reset_password.xml)
package com.simats.sehatsethu

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.AppCompatButton
import androidx.lifecycle.lifecycleScope
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ResetPasswordActivity : BaseActivity() {

    companion object {
        const val EXTRA_EMAIL = "extra_email"
        const val EXTRA_OTP = "extra_otp"
    }

    private enum class ToastKind { SUCCESS, ERROR }

    private lateinit var role: Role
    private lateinit var email: String
    private lateinit var otp: String

    private lateinit var btnBack: ImageView
    private lateinit var etNewPass: TextInputEditText
    private lateinit var etConfirm: TextInputEditText
    private lateinit var btnReset: AppCompatButton
    private lateinit var tvBackToSignIn: TextView
    private lateinit var loadingOverlay: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_reset_password)
        supportActionBar?.hide()

        role = RoleResolver.resolve(this, savedInstanceState)
        RoleResolver.persist(this, role)

        email = intent.getStringExtra(EXTRA_EMAIL).orEmpty()
        otp = intent.getStringExtra(EXTRA_OTP).orEmpty()

        btnBack = findViewById(R.id.btn_back)
        etNewPass = findViewById(R.id.et_new_password)
        etConfirm = findViewById(R.id.et_confirm_password)
        btnReset = findViewById(R.id.btn_reset_password)
        tvBackToSignIn = findViewById(R.id.tv_back_to_signin)
        loadingOverlay = findViewById(R.id.loading_overlay)

        btnBack.setOnClickListener { goBack() }
        tvBackToSignIn.setOnClickListener { goLogin() }

        btnReset.setOnClickListener { submit() }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(NavKeys.EXTRA_ROLE, role.id)
        super.onSaveInstanceState(outState)
    }

    private fun submit() {
        val p1 = etNewPass.text?.toString()?.trim().orEmpty()
        val p2 = etConfirm.text?.toString()?.trim().orEmpty()

        if (p1.length < 6) {
            showCustomToast(getString(R.string.err_password_min), ToastKind.ERROR)
            return
        }
        if (p1 != p2) {
            showCustomToast(getString(R.string.err_password_mismatch), ToastKind.ERROR)
            return
        }

        setLoading(true)

        lifecycleScope.launch {
            val res = withContext(Dispatchers.IO) { AuthApi.resetPasswordOtp(email, otp, p1) }
            setLoading(false)

            if (res.ok && res.json?.optBoolean("ok", true) == true) {
                val i = Intent(this@ResetPasswordActivity, PasswordResetSuccessActivity::class.java).apply {
                    putExtra(PasswordResetSuccessActivity.EXTRA_EMAIL, email)
                    RoleResolver.putRole(this, role)
                }
                startActivity(i)
                finish()
            } else {
                val msg =
                    res.json?.optString("error")?.takeIf { it.isNotBlank() }
                        ?: res.json?.optString("message")?.takeIf { it.isNotBlank() }
                        ?: res.errorMessage
                        ?: getString(R.string.reset_failed_generic)

                showCustomToast(msg, ToastKind.ERROR)
                showDialog(getString(R.string.error), "$msg\n(${res.url})")
            }
        }
    }


    private fun goLogin() {
        val i = Intent(this, LoginActivity::class.java).apply {
            putExtra(LoginActivity.EXTRA_EMAIL_PREFILL, email)
            RoleResolver.putRole(this, role)
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        startActivity(i)
        finish()
    }

    private fun setLoading(loading: Boolean) {
        loadingOverlay.visibility = if (loading) View.VISIBLE else View.GONE
        btnReset.isEnabled = !loading
        etNewPass.isEnabled = !loading
        etConfirm.isEnabled = !loading
        btnBack.isEnabled = !loading
        tvBackToSignIn.isEnabled = !loading
    }

    //  Custom Toast (professional, mobile-friendly)
    private fun showCustomToast(message: String, kind: ToastKind) {
        val v = LayoutInflater.from(this).inflate(R.layout.toast_custom, null)
        val icon = v.findViewById<ImageView>(R.id.toast_icon)
        val text = v.findViewById<TextView>(R.id.toast_text)

        text.text = message

        when (kind) {
            ToastKind.SUCCESS -> {
                v.setBackgroundResource(R.drawable.toast_bg_success)
                icon.setImageResource(android.R.drawable.checkbox_on_background)
            }
            ToastKind.ERROR -> {
                v.setBackgroundResource(R.drawable.toast_bg_error)
                icon.setImageResource(android.R.drawable.ic_dialog_alert)
            }
        }

        Toast(this).apply {
            view = v
            duration = Toast.LENGTH_SHORT
            setGravity(Gravity.TOP or Gravity.FILL_HORIZONTAL, 0, dp(16))
            show()
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun showDialog(title: String, msg: String) {
        android.app.AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(msg)
            .setPositiveButton(getString(R.string.ok), null)
            .show()
    }
}
