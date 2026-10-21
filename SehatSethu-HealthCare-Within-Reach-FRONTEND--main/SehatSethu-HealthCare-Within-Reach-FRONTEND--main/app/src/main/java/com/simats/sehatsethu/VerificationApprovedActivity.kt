package com.simats.sehatsethu

import android.content.Intent
import android.os.Bundle
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.addCallback
import com.google.android.material.button.MaterialButton

class VerificationApprovedActivity : BaseActivity() {

    private lateinit var role: Role

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_verification_approved)
        supportActionBar?.hide()

        role = RoleResolver.resolve(this, savedInstanceState)
        RoleResolver.persist(this, role)

        val btnBack = findViewById<ImageView>(R.id.btnBack)
        val tvTitle = findViewById<TextView>(R.id.tvTitle)
        val tvSubtitle = findViewById<TextView>(R.id.tvSubtitle)
        val tvPillText = findViewById<TextView>(R.id.tvPillText)
        val btnContinue = findViewById<MaterialButton>(R.id.btnContinue)

        // Texts (role-based)
        tvTitle.text = getString(R.string.verification_approved_title)
        tvSubtitle.text = getString(
            if (role == Role.DOCTOR) R.string.verification_approved_subtitle_doctor
            else R.string.verification_approved_subtitle_pharmacist
        )
        tvPillText.text = getString(
            if (role == Role.DOCTOR) R.string.verified_doctor
            else R.string.verified_pharmacist
        )

        fun goDashboard() {
            val fqcn = when (role) {
                Role.DOCTOR -> "com.simats.sehatsethu.roles.doctor.DoctorActivity"
                Role.PHARMACIST -> "com.simats.sehatsethu.roles.pharmacist.PharmacistActivity"
                else -> null
            }

            val i = if (fqcn != null) intentFor(fqcn) else Intent(this, MainActivity::class.java)
            RoleResolver.putRole(i, role)
            goNext(i, finishThis = true)
        }

        btnContinue.setOnClickListener { goDashboard() }
        btnBack.setOnClickListener { goDashboard() }

        onBackPressedDispatcher.addCallback(this) { goDashboard() }
    }

    private fun intentFor(fqcn: String): Intent {
        return try {
            Intent(this, Class.forName(fqcn))
        } catch (_: Throwable) {
            Intent(this, MainActivity::class.java)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(NavKeys.EXTRA_ROLE, role.id)
        super.onSaveInstanceState(outState)
    }
}
