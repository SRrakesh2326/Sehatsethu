package com.simats.sehatsethu

import android.content.Context

object UserCachePrefs {
    private const val PREF = "sehatsethu_user_cache"

    private const val K_ID = "id"
    private const val K_FULL_NAME = "full_name"
    private const val K_EMAIL = "email"
    private const val K_ROLE = "role"
    private const val K_PHONE = "phone"

    fun save(context: Context, profile: UserProfile) {
        val sp = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        sp.edit().apply {
            putLong(K_ID, profile.id ?: -1L)
            putString(K_FULL_NAME, profile.fullName ?: "")
            putString(K_EMAIL, profile.email ?: "")
            putString(K_ROLE, profile.role ?: "")
            putString(K_PHONE, profile.phone ?: "")
        }.apply()
    }

    fun get(context: Context): UserProfile {
        val sp = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val idRaw = sp.getLong(K_ID, -1L)
        return UserProfile(
            id = if (idRaw > 0) idRaw else null,
            fullName = sp.getString(K_FULL_NAME, "")?.takeIf { it.isNotBlank() },
            email = sp.getString(K_EMAIL, "")?.takeIf { it.isNotBlank() },
            role = sp.getString(K_ROLE, "")?.takeIf { it.isNotBlank() },
            phone = sp.getString(K_PHONE, "")?.takeIf { it.isNotBlank() }
        )
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().clear().apply()
    }
}
