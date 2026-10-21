package com.simats.sehatsethu

import android.content.Context
import android.content.Intent

enum class AdminVerificationStatus {
    UNVERIFIED, UNDER_REVIEW, VERIFIED, REJECTED;

    companion object {
        fun fromRaw(v: String?): AdminVerificationStatus? {
            val s = (v ?: "").trim().uppercase()
            return when (s) {
                "UNVERIFIED" -> UNVERIFIED
                "UNDER_REVIEW" -> UNDER_REVIEW
                "VERIFIED" -> VERIFIED
                "REJECTED" -> REJECTED
                "PENDING" -> UNDER_REVIEW
                "" -> null
                else -> null
            }
        }
    }
}

data class UserStatus(
    val role: Role,
    val isEmailVerified: Boolean,
    val profileCompleted: Boolean,
    val adminStatus: AdminVerificationStatus?,
    val rejectionReason: String? = null,
    val hasSubmittedProfessionalDetails: Boolean = false
)

object Gatekeeper {

    private const val EXTRA_OFFLINE = "extra_offline"

    // Existing signature preserved
    fun nextIntent(context: Context, status: UserStatus): Intent {
        return nextIntent(context, status, offline = false)
    }

    // NEW overload (optional; won’t break existing callers)
    fun nextIntent(context: Context, status: UserStatus, offline: Boolean): Intent {
        return when (status.role) {

            Role.PATIENT -> {
                val isVerified = (status.adminStatus == AdminVerificationStatus.VERIFIED)

                if (!status.profileCompleted || !isVerified) {
                    intentFor(
                        context,
                        "com.simats.sehatsethu.PatientRegistrationActivity",
                        fallback = MainActivity::class.java
                    ).also {
                        RoleResolver.putRole(it, Role.PATIENT)
                        it.putExtra(EXTRA_OFFLINE, offline)
                    }
                } else {
                    intentFor(
                        context,
                        "com.simats.sehatsethu.roles.patient.PatientActivity",
                        fallback = MainActivity::class.java
                    ).also {
                        RoleResolver.putRole(it, Role.PATIENT)
                        it.putExtra(EXTRA_OFFLINE, offline)
                    }
                }
            }

            Role.DOCTOR -> doctorFlow(context, status, offline)
            Role.PHARMACIST -> pharmacistFlow(context, status, offline)

            Role.ADMIN -> intentFor(
                context,
                "com.simats.sehatsethu.roles.admin.AdminActivity",
                fallback = MainActivity::class.java
            ).also {
                RoleResolver.putRole(it, Role.ADMIN)
                it.putExtra(EXTRA_OFFLINE, offline)
            }
        }
    }

    private fun doctorFlow(context: Context, status: UserStatus, offline: Boolean): Intent {
        if (!status.profileCompleted) {
            return intentFor(
                context,
                "com.simats.sehatsethu.DoctorRegistrationActivity",
                fallback = MainActivity::class.java
            ).also {
                RoleResolver.putRole(it, Role.DOCTOR)
                it.putExtra(EXTRA_OFFLINE, offline)
            }
        }

        return when (status.adminStatus ?: AdminVerificationStatus.UNVERIFIED) {

            AdminVerificationStatus.VERIFIED ->
                intentFor(
                    context,
                    "com.simats.sehatsethu.roles.doctor.DoctorActivity",
                    fallback = MainActivity::class.java
                ).also {
                    RoleResolver.putRole(it, Role.DOCTOR)
                    it.putExtra(EXTRA_OFFLINE, offline)
                }

            AdminVerificationStatus.REJECTED ->
                Intent(context, VerificationRejectedActivity::class.java).apply {
                    putExtra(NavKeys.EXTRA_ROLE, Role.DOCTOR.id)
                    putExtra(VerificationRejectedActivity.EXTRA_REASON, status.rejectionReason ?: "")
                    putExtra(EXTRA_OFFLINE, offline)
                }

            AdminVerificationStatus.UNDER_REVIEW,
            AdminVerificationStatus.UNVERIFIED ->
                Intent(context, VerificationPendingActivity::class.java).apply {
                    putExtra(NavKeys.EXTRA_ROLE, Role.DOCTOR.id)
                    putExtra(EXTRA_OFFLINE, offline)
                }
        }
    }

    private fun pharmacistFlow(context: Context, status: UserStatus, offline: Boolean): Intent {
        if (!status.profileCompleted) {
            return intentFor(
                context,
                "com.simats.sehatsethu.PharmacistRegistrationActivity",
                fallback = MainActivity::class.java
            ).also {
                RoleResolver.putRole(it, Role.PHARMACIST)
                it.putExtra(EXTRA_OFFLINE, offline)
            }
        }

        return when (status.adminStatus ?: AdminVerificationStatus.UNVERIFIED) {

            AdminVerificationStatus.VERIFIED ->
                intentFor(
                    context,
                    "com.simats.sehatsethu.roles.pharmacist.PharmacistActivity",
                    fallback = MainActivity::class.java
                ).also {
                    RoleResolver.putRole(it, Role.PHARMACIST)
                    it.putExtra(EXTRA_OFFLINE, offline)
                }

            AdminVerificationStatus.REJECTED ->
                Intent(context, VerificationRejectedActivity::class.java).apply {
                    putExtra(NavKeys.EXTRA_ROLE, Role.PHARMACIST.id)
                    putExtra(VerificationRejectedActivity.EXTRA_REASON, status.rejectionReason ?: "")
                    putExtra(EXTRA_OFFLINE, offline)
                }

            AdminVerificationStatus.UNDER_REVIEW,
            AdminVerificationStatus.UNVERIFIED ->
                Intent(context, VerificationPendingActivity::class.java).apply {
                    putExtra(NavKeys.EXTRA_ROLE, Role.PHARMACIST.id)
                    putExtra(EXTRA_OFFLINE, offline)
                }
        }
    }

    private fun intentFor(context: Context, fqcn: String, fallback: Class<*>): Intent {
        return try {
            val cls = Class.forName(fqcn)
            Intent(context, cls)
        } catch (_: Throwable) {
            Intent(context, fallback)
        }
    }
}
