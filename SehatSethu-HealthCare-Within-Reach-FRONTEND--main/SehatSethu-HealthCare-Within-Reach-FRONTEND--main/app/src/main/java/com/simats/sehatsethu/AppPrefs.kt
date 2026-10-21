package com.simats.sehatsethu

import android.content.Context

object AppPrefs {
    private const val PREF = "app_prefs"
    private const val KEY_LANG = "selected_language"
    private const val KEY_ROLE = "selected_role"
    private const val KEY_TOKEN = "auth_token"
    private const val KEY_ONBOARDING_DONE = "onboarding_done"

    private const val KEY_PROFILE_COMPLETED = "profile_completed"
    private const val KEY_ADMIN_VERIFICATION_STATUS = "admin_verification_status"
    private const val KEY_ADMIN_VERIFICATION_REASON = "admin_verification_reason"

    private const val KEY_DOCTOR_APPLICATION_NO = "doctor_application_no"
    private const val KEY_PHARMACIST_APPLICATION_NO = "pharmacist_application_no"

    // NEW (offline-friendly)
    private const val KEY_TOKEN_EXPIRED = "token_expired_flag"
    private const val KEY_LAST_ROLE = "last_active_role"
    private const val KEY_LAST_UID = "last_uid"
    private const val KEY_LAST_USER_NAME = "last_user_name"

    private fun prefs(c: Context) = c.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun getLang(c: Context): String? = prefs(c).getString(KEY_LANG, null)
    fun setLang(c: Context, lang: String) = prefs(c).edit().putString(KEY_LANG, lang).apply()

    fun getRole(c: Context): String? = prefs(c).getString(KEY_ROLE, null)
    fun setRole(c: Context, role: String) = prefs(c).edit().putString(KEY_ROLE, role).apply()
    fun clearRole(c: Context) = prefs(c).edit().remove(KEY_ROLE).apply()

    fun getToken(c: Context): String? = prefs(c).getString(KEY_TOKEN, null)
    fun setToken(c: Context, token: String?) = prefs(c).edit().putString(KEY_TOKEN, token).apply()

    fun getAuthToken(c: Context): String? = getToken(c)
    fun setAuthToken(c: Context, token: String?) = setToken(c, token)

    fun clearToken(c: Context) {
        prefs(c).edit()
            .remove(KEY_TOKEN)
            .putBoolean(KEY_TOKEN_EXPIRED, false)
            .apply()
    }

    fun isOnboardingDone(c: Context): Boolean = prefs(c).getBoolean(KEY_ONBOARDING_DONE, false)
    fun setOnboardingDone(c: Context, done: Boolean) =
        prefs(c).edit().putBoolean(KEY_ONBOARDING_DONE, done).apply()

    fun getProfileCompleted(c: Context): Boolean? {
        val v = prefs(c).getInt(KEY_PROFILE_COMPLETED, -1)
        return when (v) {
            1 -> true
            0 -> false
            else -> null
        }
    }

    fun setProfileCompleted(c: Context, value: Boolean?) {
        val v = when (value) {
            true -> 1
            false -> 0
            null -> -1
        }
        prefs(c).edit().putInt(KEY_PROFILE_COMPLETED, v).apply()
    }

    fun getAdminVerificationStatus(c: Context): String? =
        prefs(c).getString(KEY_ADMIN_VERIFICATION_STATUS, null)

    fun setAdminVerificationStatus(c: Context, value: String?) =
        prefs(c).edit().putString(KEY_ADMIN_VERIFICATION_STATUS, value).apply()

    fun getAdminVerificationReason(c: Context): String? =
        prefs(c).getString(KEY_ADMIN_VERIFICATION_REASON, null)

    fun setAdminVerificationReason(c: Context, value: String?) =
        prefs(c).edit().putString(KEY_ADMIN_VERIFICATION_REASON, value).apply()

    fun getDoctorApplicationNo(c: Context): String? =
        prefs(c).getString(KEY_DOCTOR_APPLICATION_NO, null)

    fun setDoctorApplicationNo(c: Context, value: String?) =
        prefs(c).edit().putString(KEY_DOCTOR_APPLICATION_NO, value).apply()

    fun clearDoctorApplicationNo(c: Context) =
        prefs(c).edit().remove(KEY_DOCTOR_APPLICATION_NO).apply()

    fun getPharmacistApplicationNo(c: Context): String? =
        prefs(c).getString(KEY_PHARMACIST_APPLICATION_NO, null)

    fun setPharmacistApplicationNo(c: Context, value: String?) =
        prefs(c).edit().putString(KEY_PHARMACIST_APPLICATION_NO, value).apply()

    fun clearPharmacistApplicationNo(c: Context) =
        prefs(c).edit().remove(KEY_PHARMACIST_APPLICATION_NO).apply()

    // =========================
    // Offline/session helpers
    // =========================
    fun isTokenExpired(c: Context): Boolean = prefs(c).getBoolean(KEY_TOKEN_EXPIRED, false)

    fun setTokenExpired(c: Context, expired: Boolean) {
        prefs(c).edit().putBoolean(KEY_TOKEN_EXPIRED, expired).apply()
    }

    fun setLastSession(c: Context, roleId: String?, uid: Long? = null, userName: String? = null) {
        val ed = prefs(c).edit()
        if (roleId != null) ed.putString(KEY_LAST_ROLE, roleId)
        if (uid != null) ed.putLong(KEY_LAST_UID, uid)
        if (userName != null) ed.putString(KEY_LAST_USER_NAME, userName)
        ed.apply()
    }

    fun getLastRole(c: Context): String? = prefs(c).getString(KEY_LAST_ROLE, null)
    fun getLastUid(c: Context): Long = prefs(c).getLong(KEY_LAST_UID, 0L)
    fun getLastUserName(c: Context): String? = prefs(c).getString(KEY_LAST_USER_NAME, null)
}
