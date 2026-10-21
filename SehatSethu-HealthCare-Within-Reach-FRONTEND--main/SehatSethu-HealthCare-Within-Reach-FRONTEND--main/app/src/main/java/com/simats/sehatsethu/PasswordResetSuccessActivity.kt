package com.simats.sehatsethu

import android.content.Intent
import android.os.Bundle
import android.widget.Button

class PasswordResetSuccessActivity : BaseActivity() {

    companion object {
        const val EXTRA_EMAIL = "extra_email"
    }

    private lateinit var role: Role
    private var email: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.password_reset_successful)
        supportActionBar?.hide()

        role = RoleResolver.resolve(this, savedInstanceState)
        RoleResolver.persist(this, role)

        email = intent.getStringExtra(EXTRA_EMAIL).orEmpty()

        findViewById<Button>(R.id.btn_sign_in).setOnClickListener {
            goLogin()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(NavKeys.EXTRA_ROLE, role.id)
        super.onSaveInstanceState(outState)
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
}
