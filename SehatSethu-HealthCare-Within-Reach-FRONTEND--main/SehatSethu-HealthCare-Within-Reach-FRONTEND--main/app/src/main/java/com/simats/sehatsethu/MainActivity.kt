package com.simats.sehatsethu

import android.content.Intent
import android.os.Bundle

class MainActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()

        val token = (AppPrefs.getToken(this) ?: "").trim()
        if (token.isEmpty()) {
            startFresh(RoleSelectActivity::class.java)
            return
        }

        val role = Role.fromId(AppPrefs.getRole(this)) ?: Role.PATIENT

        val profileCompleted = runCatching { AppPrefs.getProfileCompleted(this) }.getOrNull() ?: false
        val rawStatus = runCatching { AppPrefs.getAdminVerificationStatus(this) }.getOrNull()
        val status = (rawStatus ?: "").trim().uppercase()
        val reason = runCatching { AppPrefs.getAdminVerificationReason(this) }.getOrNull().orEmpty()

        val next = when (role) {

            Role.ADMIN -> buildRoleHomeIntent(role)

            Role.PATIENT -> {
                // STRICT: Must have BOTH profile completed AND VERIFIED
                if (!profileCompleted || status != "VERIFIED") {
                    RoleResolver.putRole(Intent(this, PatientRegistrationActivity::class.java), role)
                } else {
                    buildRoleHomeIntent(role)
                }
            }

            Role.DOCTOR -> {
                when (status) {
                    "UNDER_REVIEW" -> RoleResolver.putRole(Intent(this, VerificationPendingActivity::class.java), role)
                    "REJECTED" -> Intent(this, VerificationRejectedActivity::class.java).apply {
                        putExtra(NavKeys.EXTRA_ROLE, role.id)
                        putExtra(VerificationRejectedActivity.EXTRA_REASON, reason)
                    }
                    "VERIFIED" -> buildRoleHomeIntent(role)
                    "PENDING", "" -> RoleResolver.putRole(Intent(this, DoctorRegistrationActivity::class.java), role)
                    else -> {
                        // Unknown status but token exists -> safest is pending (prevents re-registration loop)
                        RoleResolver.putRole(Intent(this, VerificationPendingActivity::class.java), role)
                    }
                }
            }

            Role.PHARMACIST -> {
                when (status) {
                    "UNDER_REVIEW" -> RoleResolver.putRole(Intent(this, VerificationPendingActivity::class.java), role)
                    "REJECTED" -> Intent(this, VerificationRejectedActivity::class.java).apply {
                        putExtra(NavKeys.EXTRA_ROLE, role.id)
                        putExtra(VerificationRejectedActivity.EXTRA_REASON, reason)
                    }
                    "VERIFIED" -> buildRoleHomeIntent(role)
                    "PENDING", "" -> RoleResolver.putRole(Intent(this, PharmacistRegistrationActivity::class.java), role)
                    else -> RoleResolver.putRole(Intent(this, VerificationPendingActivity::class.java), role)
                }
            }
        }

        startFresh(next)
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
            Intent(this, RoleSelectActivity::class.java)
        }

        RoleResolver.putRole(intent, role)
        return intent
    }

    private fun startFresh(cls: Class<*>) {
        startActivity(Intent(this, cls).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        })
        finish()
        overridePendingTransition(0, 0)
    }

    private fun startFresh(i: Intent) {
        i.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(i)
        finish()
        overridePendingTransition(0, 0)
    }
}
