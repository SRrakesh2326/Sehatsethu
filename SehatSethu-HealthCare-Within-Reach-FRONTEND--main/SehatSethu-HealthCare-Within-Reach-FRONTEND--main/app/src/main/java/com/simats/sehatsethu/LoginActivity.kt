package com.simats.sehatsethu

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.PorterDuff
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.activity.addCallback
import androidx.lifecycle.lifecycleScope
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class LoginActivity : BaseActivity() {

    companion object {
        const val EXTRA_EMAIL_PREFILL = "extra_email_prefill"
    }

    private lateinit var role: Role

    private lateinit var etEmail: EditText
    private lateinit var etPassword: EditText
    private lateinit var btnSignIn: Button
    private lateinit var tvForgot: TextView
    private lateinit var tvSignup: TextView
    private lateinit var btnBack: ImageView

    private lateinit var loadingOverlay: View
    private lateinit var tilPassword: TextInputLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)
        supportActionBar?.hide()

        role = RoleResolver.resolve(this, savedInstanceState)
        RoleResolver.persist(this, role)

        etEmail = findViewById(R.id.et_email)
        etPassword = findViewById(R.id.et_password)
        btnSignIn = findViewById(R.id.btn_sign_in)
        tvForgot = findViewById(R.id.tv_forgot)
        tvSignup = findViewById(R.id.tv_signup)
        btnBack = findViewById(R.id.btn_back)
        loadingOverlay = findViewById(R.id.loading_overlay)
        tilPassword = findViewById(R.id.til_password)

        makeReadable(etEmail)
        makeReadable(etPassword)

        tilPassword.setEndIconTintList(ColorStateList.valueOf(0xFF000000.toInt()))
        tilPassword.setEndIconTintMode(PorterDuff.Mode.SRC_IN)

        intent.getStringExtra(EXTRA_EMAIL_PREFILL)?.let { pre ->
            if (pre.isNotBlank()) etEmail.setText(pre)
        }

        btnBack.setOnClickListener { handleBack() }
        onBackPressedDispatcher.addCallback(this) { handleBack() }

        btnSignIn.setOnClickListener { doLogin() }

        tvSignup.setOnClickListener {
            val i = RoleResolver.putRole(Intent(this, CreateAccountActivity::class.java), role)
            goNext(i, finishThis = false)
        }

        tvForgot.setOnClickListener {
            val i = Intent(this, ForgotPasswordActivity::class.java).apply {
                putExtra(
                    ForgotPasswordActivity.EXTRA_EMAIL_PREFILL,
                    etEmail.text?.toString()?.trim().orEmpty()
                )
            }
            RoleResolver.putRole(i, role)
            goNext(i, finishThis = false)
        }
    }

    private fun doLogin() {
        val email = etEmail.text?.toString()?.trim().orEmpty()
        val pass = etPassword.text?.toString()?.trim().orEmpty()

        if (email.isBlank() || pass.isBlank()) {
            toast(getString(R.string.err_email_password_required))
            return
        }

        setLoading(true)

        lifecycleScope.launch {
            val res = withContext(Dispatchers.IO) { AuthApi.login(email, pass, role) }
            setLoading(false)

            if (!res.ok) {
                val code = res.json?.optString("code")?.trim().orEmpty()
                val errText = (res.json?.optString("error") ?: res.errorMessage ?: "").lowercase()

                val msg = when {
                    code == "ROLE_MISMATCH" || errText.contains("switch role") || errText.contains("registered as") -> {
                        val expected = res.json?.optString("expected_role")?.trim().orEmpty()
                        if (expected.isNotBlank()) {
                            getString(R.string.err_role_mismatch_expected, expected)
                        } else {
                            getString(R.string.err_role_mismatch_generic)
                        }
                    }

                    code == "USER_NOT_FOUND" || errText.contains("user not found") || errText.contains("no user found") || errText.contains("no account") ->
                        getString(R.string.err_user_not_found)

                    code == "INVALID_CREDENTIALS" || errText.contains("invalid credentials") || errText.contains("wrong password") ->
                        getString(R.string.err_invalid_credentials)

                    code == "EMAIL_NOT_VERIFIED" || errText.contains("verify your email") ->
                        getString(R.string.err_email_not_verified)

                    else ->
                        (res.errorMessage ?: "${getString(R.string.err_login_failed)}\n(${res.url})")
                }

                showDialog(getString(R.string.error), msg)
                return@launch
            }

            val token = res.json?.optString("token").orEmpty()
            if (token.isBlank()) {
                showDialog(getString(R.string.error), getString(R.string.err_login_missing_token, res.url))
                return@launch
            }

            // Save token + role
            AppPrefs.setToken(this@LoginActivity, token)
            RoleResolver.persist(this@LoginActivity, role)

            // Parse gate and persist
            val gate = parseGateFromLoginJson(res.json)
            persistGateToPrefs(gate)

            runCatching {
                SessionProfile.saveFromLogin(
                    context = this@LoginActivity,
                    loginJson = res.json,
                    token = token,
                    roleFallback = role
                )
            }

            //  CRITICAL FIX: Launch next as FRESH TASK so back will NOT return to Login
            val next = routeByGate(role, gate)
            launchFreshAfterLogin(next)
        }
    }

    //  This is the key: CLEAR_TASK + NEW_TASK
    private fun launchFreshAfterLogin(next: Intent) {
        next.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(next)
        overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left)
        finish()
    }

    private fun persistGateToPrefs(gate: GateState) {
        gate.profileCompleted?.let { completed ->
            runCatching { AppPrefs.setProfileCompleted(this@LoginActivity, completed) }
        }

        gate.adminVerificationStatus?.let { st ->
            runCatching { AppPrefs.setAdminVerificationStatus(this@LoginActivity, st.trim().uppercase()) }
        }
        runCatching { AppPrefs.setAdminVerificationReason(this@LoginActivity, gate.adminVerificationReason?.ifBlank { null }) }
    }

    private fun parseGateFromLoginJson(json: JSONObject?): GateState {
        if (json == null) return GateState()

        val src = json.optJSONObject("user") ?: json

        val profileCompleted: Boolean? =
            if (src.has("profile_completed")) {
                when (val v = src.opt("profile_completed")) {
                    is Int -> v == 1
                    is Boolean -> v
                    else -> src.optString("profile_completed", "0") == "1"
                }
            } else null

        val adminStatus = (src.optString("admin_verification_status", "").trim().ifBlank { null })
        val reason =
            src.optString("admin_verification_reason", "").trim().ifBlank { null }
                ?: src.optString("admin_rejection_reason", "").trim().ifBlank { null }

        return GateState(
            profileCompleted = profileCompleted,
            adminVerificationStatus = adminStatus,
            adminVerificationReason = reason
        )
    }

    private fun routeByGate(role: Role, gate: GateState): Intent {
        if (role == Role.ADMIN) return buildRoleHomeIntent(role)

        if (role == Role.PATIENT) {
            val st = gate.adminVerificationStatus?.trim()?.uppercase()
            if (gate.profileCompleted != true || st != "VERIFIED") {
                return RoleResolver.putRole(Intent(this, PatientRegistrationActivity::class.java), role)
            }
            return buildRoleHomeIntent(role)
        }

        if (role == Role.DOCTOR || role == Role.PHARMACIST) {
            val st = gate.adminVerificationStatus?.trim()?.uppercase().orEmpty()

            if (st == "PENDING" || gate.profileCompleted != true) {
                return if (role == Role.DOCTOR) {
                    RoleResolver.putRole(Intent(this, DoctorRegistrationActivity::class.java), role)
                } else {
                    RoleResolver.putRole(Intent(this, PharmacistRegistrationActivity::class.java), role)
                }
            }

            if (st == "VERIFIED") return buildRoleHomeIntent(role)

            if (st == "REJECTED") {
                return Intent(this, VerificationRejectedActivity::class.java).apply {
                    putExtra(NavKeys.EXTRA_ROLE, role.id)
                    putExtra(VerificationRejectedActivity.EXTRA_REASON, gate.adminVerificationReason ?: "")
                }
            }

            return Intent(this, VerificationPendingActivity::class.java).apply {
                putExtra(NavKeys.EXTRA_ROLE, role.id)
            }
        }

        return buildRoleHomeIntent(role)
    }

    private fun buildRoleHomeIntent(role: Role): Intent {
        val className = when (role) {
            Role.ADMIN -> "com.simats.sehatsethu.roles.admin.AdminActivity"
            Role.DOCTOR -> "com.simats.sehatsethu.roles.doctor.DoctorActivity"
            Role.PATIENT -> "com.simats.sehatsethu.roles.patient.PatientActivity"
            Role.PHARMACIST -> "com.simats.sehatsethu.roles.pharmacist.PharmacistActivity"
        }

        val intent = try {
            Intent(this, Class.forName(className))
        } catch (_: Throwable) {
            Intent(this, MainActivity::class.java)
        }

        RoleResolver.putRole(intent, role)
        return intent
    }

    private fun handleBack() {
        if (isTaskRoot) goBack(Intent(this, RoleSelectActivity::class.java)) else goBack()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(NavKeys.EXTRA_ROLE, role.id)
        super.onSaveInstanceState(outState)
    }

    private fun setLoading(loading: Boolean) {
        loadingOverlay.visibility = if (loading) View.VISIBLE else View.GONE
        btnSignIn.isEnabled = !loading
        tvForgot.isEnabled = !loading
        tvSignup.isEnabled = !loading
        etEmail.isEnabled = !loading
        etPassword.isEnabled = !loading
        btnBack.isEnabled = !loading
        tilPassword.isEnabled = !loading
    }

    private fun makeReadable(et: EditText) {
        et.setTextColor(0xFF0F172A.toInt())
        et.setHintTextColor(0xFF94A3B8.toInt())
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

data class GateState(
    val profileCompleted: Boolean? = null,
    val adminVerificationStatus: String? = null,
    val adminVerificationReason: String? = null
)
