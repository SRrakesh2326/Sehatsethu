package com.simats.sehatsethu

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.CountDownTimer
import android.text.Editable
import android.text.InputFilter
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.AppCompatButton
import androidx.core.view.setPadding
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class VerifyOtpActivity : BaseActivity() {

    companion object {
        const val EXTRA_FLOW = "extra_flow"
        const val EXTRA_EMAIL = "extra_email"
        const val FLOW_RESET = "reset"

        const val EXTRA_ROLE_FALLBACK = NavKeys.EXTRA_ROLE
    }

    private lateinit var role: Role
    private lateinit var email: String
    private lateinit var flow: String

    private lateinit var btnBack: ImageView
    private lateinit var tvSubtitle: TextView
    private lateinit var btnVerify: AppCompatButton
    private lateinit var tvResend: TextView
    private lateinit var loadingOverlay: View

    private lateinit var otpEdits: List<EditText>
    private var timer: CountDownTimer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_verify_otp)
        supportActionBar?.hide()

        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)

        role = RoleResolver.resolve(this, savedInstanceState)
        RoleResolver.persist(this, role)

        email = intent.getStringExtra(EXTRA_EMAIL).orEmpty()
        flow = intent.getStringExtra(EXTRA_FLOW).orEmpty()

        btnBack = findViewById(R.id.btn_back)
        tvSubtitle = findViewById(R.id.tv_subtitle)
        btnVerify = findViewById(R.id.btn_verify)
        tvResend = findViewById(R.id.tv_resend)
        loadingOverlay = findViewById(R.id.loading_overlay)

        otpEdits = listOf(
            findViewById(R.id.et_otp_1),
            findViewById(R.id.et_otp_2),
            findViewById(R.id.et_otp_3),
            findViewById(R.id.et_otp_4),
            findViewById(R.id.et_otp_5),
            findViewById(R.id.et_otp_6),
        )

        otpEdits.forEach {
            makeReadable(it)
            it.isFocusable = true
            it.isFocusableInTouchMode = true
            it.filters = arrayOf(InputFilter.LengthFilter(1))
        }
        setupOtpAutoMove()

        tvSubtitle.text = getString(R.string.otp_sent_to, email)

        btnBack.setOnClickListener { goBack() }
        btnVerify.setOnClickListener { verifyOtp() }
        tvResend.setOnClickListener { resendOtp() }

        startResendCountdown()

        val debugOtp = intent.getStringExtra("extra_debug_otp")?.trim().orEmpty()
        if (debugOtp.length == 6) {
            debugOtp.forEachIndexed { i, c ->
                if (i in otpEdits.indices) otpEdits[i].setText(c.toString())
            }
            toastBL("Verification code: $debugOtp")
        } else {
            focusFirstOtpAndShowKeyboard()
        }
    }

    override fun onDestroy() {
        timer?.cancel()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(NavKeys.EXTRA_ROLE, role.id)
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        if (otpEdits.all { it.text.isNullOrEmpty() }) {
            focusFirstOtpAndShowKeyboard()
        }
    }

    private fun setupOtpAutoMove() {
        otpEdits.forEachIndexed { idx, et ->
            et.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    if (s?.length == 1 && idx < otpEdits.lastIndex) {
                        otpEdits[idx + 1].requestFocus()
                    }
                }
            })

            et.setOnKeyListener { v, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_DEL && event.action == KeyEvent.ACTION_DOWN) {
                    val cur = v as EditText
                    if (cur.text.isNullOrEmpty() && idx > 0) {
                        otpEdits[idx - 1].requestFocus()
                        otpEdits[idx - 1].setSelection(otpEdits[idx - 1].text?.length ?: 0)
                        return@setOnKeyListener true
                    }
                }
                false
            }
        }
    }

    private fun verifyOtp() {
        val otp = otpEdits.joinToString("") { it.text?.toString()?.trim().orEmpty() }
        if (otp.length != 6) {
            toastBL(getString(R.string.otp_invalid), isError = true)
            return
        }

        setLoading(true)

        lifecycleScope.launch {
            val res = withContext(Dispatchers.IO) {
                when (flow) {
                    FLOW_RESET -> AuthApi.verifyResetOtp(email, otp)
                    else -> AuthApi.verifyResetOtp(email, otp) // keep existing logic
                }
            }

            setLoading(false)

            val j = res.json
            val ok = res.ok && (j?.optBoolean("ok", true) == true)

            if (ok) {
                val i = Intent(this@VerifyOtpActivity, ResetPasswordActivity::class.java).apply {
                    putExtra(ResetPasswordActivity.EXTRA_EMAIL, email)
                    putExtra(ResetPasswordActivity.EXTRA_OTP, otp)
                    RoleResolver.putRole(this, role)
                }
                goNext(i, finishThis = true)
                return@launch
            }

            //  Friendly error mapping
            val code = (j?.optString("code") ?: "").trim().uppercase()
            val serverErr = (j?.optString("error") ?: "").trim()
            val serverMsg = (j?.optString("message") ?: "").trim()

            val friendly = when (code) {
                "OTP_INVALID" -> "Incorrect code. Please try again."
                "OTP_EXPIRED" -> "This code has expired. Tap Resend to get a new one."
                "TOO_MANY_ATTEMPTS" -> "Too many attempts. Please resend a new code."
                "USER_NOT_FOUND" -> "No account found for this email."
                else -> {
                    // Network / stream errors often come as httpCode=0 with errorMessage
                    val net = (res.httpCode == 0)
                    if (net) "Network issue. Please try again."
                    else serverErr.ifBlank { serverMsg }.ifBlank { getString(R.string.otp_invalid) }
                }
            }

            toastBL(friendly, isError = true)

            //  UX: clear OTP and focus first box for quick retry on invalid
            if (code == "OTP_INVALID" || code.isBlank()) {
                otpEdits.forEach { it.setText("") }
                focusFirstOtpAndShowKeyboard()
            }
        }
    }

    private fun resendOtp() {
        if (!tvResend.isEnabled) return

        setLoading(true)
        lifecycleScope.launch {
            val res = withContext(Dispatchers.IO) { AuthApi.forgotSendOtp(email) }
            setLoading(false)

            val j = res.json
            val ok = res.ok && (j?.optBoolean("ok", true) == true)

            if (ok) {
                val newOtp = j?.optString("otp")?.trim().orEmpty()
                otpEdits.forEach { it.setText("") }
                if (newOtp.length == 6) {
                    newOtp.forEachIndexed { idx, c ->
                        if (idx in otpEdits.indices) otpEdits[idx].setText(c.toString())
                    }
                    toastBL("New code: $newOtp")
                } else {
                    toastBL(getString(R.string.otp_resent))
                    focusFirstOtpAndShowKeyboard()
                }
                startResendCountdown()
                return@launch
            }

            val code = (j?.optString("code") ?: "").trim().uppercase()
            val serverErr = (j?.optString("error") ?: "").trim()
            val serverMsg = (j?.optString("message") ?: "").trim()

            val friendly = when (code) {
                "RATE_LIMIT" -> serverErr.ifBlank { "Please wait a moment before resending." }
                "USER_NOT_FOUND" -> "No account found for this email."
                else -> {
                    val net = (res.httpCode == 0)
                    if (net) "Network issue. Please try again."
                    else serverErr.ifBlank { serverMsg }.ifBlank { getString(R.string.otp_resend_failed) }
                }
            }

            toastBL(friendly, isError = true)
        }
    }

    private fun focusFirstOtpAndShowKeyboard() {
        val first = otpEdits.firstOrNull() ?: return
        if (!first.isEnabled) return

        first.post {
            first.requestFocus()
            first.setSelection(first.text?.length ?: 0)
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(first, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun startResendCountdown() {
        tvResend.isEnabled = false
        timer?.cancel()

        timer = object : CountDownTimer(60_000, 1_000) {
            override fun onTick(ms: Long) {
                val sec = (ms / 1000).toInt()
                tvResend.text = getString(R.string.otp_resend_in, sec)
            }

            override fun onFinish() {
                tvResend.text = getString(R.string.otp_resend_now)
                tvResend.isEnabled = true
            }
        }.start()
    }

    private fun setLoading(loading: Boolean) {
        loadingOverlay.visibility = if (loading) View.VISIBLE else View.GONE
        btnVerify.isEnabled = !loading
        btnBack.isEnabled = !loading
        otpEdits.forEach { it.isEnabled = !loading }
        if (loading) tvResend.isEnabled = false
    }

    private fun makeReadable(et: EditText) {
        et.setTextColor(0xFF0F172A.toInt())
        et.setHintTextColor(0xFF94A3B8.toInt())
    }

    //  Same premium toast style as CreateAccountActivity
    private fun toastBL(message: String, isError: Boolean = false) {
        val bg = GradientDrawable().apply {
            cornerRadius = dp(14).toFloat()
            setColor(if (isError) 0xFF111827.toInt() else 0xFF0F172A.toInt())
        }

        val tv = TextView(this).apply {
            text = message
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = bg
            maxLines = 3
            gravity = Gravity.CENTER
        }

        Toast(this).apply {
            duration = Toast.LENGTH_SHORT
            view = tv
            setGravity(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, 0, dp(90))
        }.show()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}