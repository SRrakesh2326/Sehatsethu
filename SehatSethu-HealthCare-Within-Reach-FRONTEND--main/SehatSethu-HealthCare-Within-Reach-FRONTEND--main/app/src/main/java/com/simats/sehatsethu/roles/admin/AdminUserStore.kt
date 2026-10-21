package com.simats.sehatsethu.roles.admin

import android.content.Context
import android.util.Base64
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.simats.sehatsethu.AppPrefs
import org.json.JSONObject

data class AdminMe(
    val id: Long?,
    val fullName: String,
    val email: String,
    val role: String,
    val phone: String?
)

sealed class AdminMeState {
    object Loading : AdminMeState()
    data class Ready(val me: AdminMe) : AdminMeState()
    data class Empty(val message: String) : AdminMeState()
    data class Error(val message: String) : AdminMeState()
}

object AdminUserStore {

    private val _state = MutableLiveData<AdminMeState>()
    val state: LiveData<AdminMeState> = _state

    // cache
    private var cached: AdminMe? = null
    fun getCached(): AdminMe? = cached

    /**
     * No network calls here.
     * We read the token you already store and decode JWT payload for user fields.
     * This avoids EOF/timeout problems and avoids touching PHP.
     */
    suspend fun refresh(ctx: Context) {
        _state.postValue(AdminMeState.Loading)

        val token = AppPrefs.getToken(ctx).orEmpty()
        if (token.isBlank()) {
            cached = null
            _state.postValue(AdminMeState.Empty("You are signed out. Please login again."))
            return
        }

        val claims = decodeJwtPayload(token)
        if (claims == null) {
            cached = null
            _state.postValue(AdminMeState.Empty("Profile details are not available yet."))
            return
        }

        val fullName =
            claims.optString("full_name").trim()
                .ifBlank { claims.optString("name").trim() }

        val email = claims.optString("email").trim()

        // role may exist in token or fallback to your saved role in prefs
        val role =
            claims.optString("role").trim().ifBlank { AppPrefs.getRole(ctx).orEmpty() }

        val phone =
            claims.optString("phone").trim()
                .ifBlank { claims.optString("mobile").trim() }
                .ifBlank { null }

        val id = try {
            val v = claims.optLong("id", 0L)
            if (v > 0L) v else null
        } catch (_: Throwable) {
            null
        }

        if (fullName.isBlank() && email.isBlank()) {
            cached = null
            _state.postValue(AdminMeState.Empty("Profile info is not available yet."))
            return
        }

        val me = AdminMe(
            id = id,
            fullName = fullName.ifBlank { "Admin" },
            email = email.ifBlank { "Not available" },
            role = role.ifBlank { "ADMIN" },
            phone = phone
        )

        cached = me
        _state.postValue(AdminMeState.Ready(me))
    }

    fun clear() {
        cached = null
        _state.postValue(AdminMeState.Empty("Profile cleared."))
    }

    private fun decodeJwtPayload(token: String): JSONObject? {
        return try {
            val parts = token.split(".")
            if (parts.size < 2) return null
            val payload = parts[1]
            val decoded = String(Base64.decode(fixBase64Url(payload), Base64.DEFAULT))
            JSONObject(decoded)
        } catch (_: Throwable) {
            null
        }
    }

    private fun fixBase64Url(s: String): String {
        var out = s.replace('-', '+').replace('_', '/')
        while (out.length % 4 != 0) out += "="
        return out
    }
}
