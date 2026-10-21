package com.simats.sehatsethu

import org.json.JSONObject

object SessionProfile {

    /**
     * Call this AFTER login success (token received).
     * It will:
     * 1) read profile from login JSON if present
     * 2) else decode JWT payload if possible
     * 3) store to UserCachePrefs
     */
    fun saveFromLogin(context: android.content.Context, loginJson: JSONObject?, token: String, roleFallback: Role) {
        val p1 = extractFromLoginJson(loginJson, roleFallback)
        val p2 = extractFromJwt(token, roleFallback)

        val merged = UserProfile(
            id = p1.id ?: p2.id,
            fullName = p1.fullName ?: p2.fullName,
            email = p1.email ?: p2.email,
            role = p1.role ?: p2.role ?: roleFallback.id,
            phone = p1.phone ?: p2.phone
        )
        UserCachePrefs.save(context, merged)
    }

    fun get(context: android.content.Context): UserProfile {
        return UserCachePrefs.get(context)
    }

    private fun extractFromLoginJson(j: JSONObject?, roleFallback: Role): UserProfile {
        if (j == null) return UserProfile(role = roleFallback.id)

        // common patterns: { user:{...} } OR { data:{user:{...}} } OR flat keys
        val userObj =
            j.optJSONObject("user")
                ?: j.optJSONObject("data")?.optJSONObject("user")
                ?: j.optJSONObject("result")?.optJSONObject("user")

        val src = userObj ?: j

        val id = src.optLong("id", -1L).takeIf { it > 0 }
            ?: src.optLong("user_id", -1L).takeIf { it > 0 }

        val fullName = src.optString("full_name").takeIf { it.isNotBlank() }
            ?: src.optString("name").takeIf { it.isNotBlank() }

        val email = src.optString("email").takeIf { it.isNotBlank() }
        val role = src.optString("role").takeIf { it.isNotBlank() } ?: roleFallback.id

        val phone = src.optString("phone").takeIf { it.isNotBlank() }
            ?: src.optString("mobile").takeIf { it.isNotBlank() }

        return UserProfile(id = id, fullName = fullName, email = email, role = role, phone = phone)
    }

    private fun extractFromJwt(token: String, roleFallback: Role): UserProfile {
        val payload = JwtUtils.decodePayload(token) ?: return UserProfile(role = roleFallback.id)

        val id = payload.optLong("id", -1L).takeIf { it > 0 }
            ?: payload.optLong("user_id", -1L).takeIf { it > 0 }
            ?: payload.optLong("sub", -1L).takeIf { it > 0 }

        val fullName = payload.optString("full_name").takeIf { it.isNotBlank() }
            ?: payload.optString("name").takeIf { it.isNotBlank() }

        val email = payload.optString("email").takeIf { it.isNotBlank() }
        val role = payload.optString("role").takeIf { it.isNotBlank() } ?: roleFallback.id

        val phone = payload.optString("phone").takeIf { it.isNotBlank() }
            ?: payload.optString("mobile").takeIf { it.isNotBlank() }

        return UserProfile(id = id, fullName = fullName, email = email, role = role, phone = phone)
    }
}
