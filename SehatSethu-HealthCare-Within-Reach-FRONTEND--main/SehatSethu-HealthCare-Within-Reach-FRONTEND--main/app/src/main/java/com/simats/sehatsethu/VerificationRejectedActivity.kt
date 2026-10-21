package com.simats.sehatsethu

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.widget.AppCompatButton

class VerificationRejectedActivity : BaseActivity() {

    companion object {
        const val EXTRA_REASON = "extra_reason"
    }

    private lateinit var role: Role

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_verification_failed) // <-- ensure matches your xml filename
        supportActionBar?.hide()

        role = RoleResolver.resolve(this, savedInstanceState)
        RoleResolver.persist(this, role)

        findViewById<ImageView>(R.id.ivBack).setOnClickListener { goBack() }

        val reason = intent.getStringExtra(EXTRA_REASON).orEmpty().ifBlank {
            AppPrefs.getAdminVerificationReason(this).orEmpty()
        }

        // if your layout has these ids, update reason text safely
        runCatching {
            findViewById<TextView>(R.id.tvReason).text =
                if (reason.isBlank()) "Reason:\n—" else "Reason:\n$reason"
        }

        findViewById<AppCompatButton>(R.id.btnReupload).setOnClickListener {
            // go back to the appropriate registration screen
            val target = when (role) {
                Role.DOCTOR -> DoctorRegistrationActivity::class.java
                Role.PHARMACIST -> PharmacistRegistrationActivity::class.java
                else -> RoleSelectActivity::class.java
            }
            val i = Intent(this, target)
            RoleResolver.putRole(i, role)
            goNext(i, finishThis = true)
        }

        findViewById<AppCompatButton>(R.id.btnSupport).setOnClickListener {
            // simple mail intent (doesn't affect backend)
            val mail = Intent(Intent.ACTION_SENDTO).apply {
                data = Uri.parse("mailto:support@sehatsethu.com")
                putExtra(Intent.EXTRA_SUBJECT, "SehatSethu Verification Help")
                putExtra(Intent.EXTRA_TEXT, "Hi Support,\n\nMy verification was rejected.\nRole: ${role.id}\nReason: $reason\n\nPlease help.\n")
            }
            startActivity(Intent.createChooser(mail, "Contact Support"))
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(NavKeys.EXTRA_ROLE, role.id)
        super.onSaveInstanceState(outState)
    }
}
