package com.simats.sehatsethu.roles.doctor

import android.content.Context
import android.util.Base64
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.simats.sehatsethu.AppPrefs
import org.json.JSONObject

data class DoctorMe(
    val id: Long?,
    val fullName: String,
    val email: String,
    val role: String,
    val phone: String?
)

sealed class DoctorMeState {
    object Loading : DoctorMeState()
    data class Ready(val me: DoctorMe) : DoctorMeState()
    data class Empty(val message: String) : DoctorMeState()
    data class Error(val message: String) : DoctorMeState()
}

object DoctorUserStore {

    private val _state = MutableLiveData<DoctorMeState>()
    val state: LiveData<DoctorMeState> = _state

    private var cached: DoctorMe? = null
    fun getCached(): DoctorMe? = cached

    suspend fun refresh(ctx: Context) {
        _state.postValue(DoctorMeState.Loading)

        val token = AppPrefs.getToken(ctx).orEmpty()
        if (token.isBlank()) {
            cached = null
            _state.postValue(DoctorMeState.Empty("You are signed out. Please login again."))
            return
        }

        val claims = decodeJwtPayload(token)
        if (claims == null) {
            cached = null
            _state.postValue(DoctorMeState.Empty("Profile details are not available yet."))
            return
        }

        val fullName =
            claims.optString("full_name").trim()
                .ifBlank { claims.optString("name").trim() }
                .ifBlank { "Doctor" }

        val email =
            claims.optString("email").trim().ifBlank { "Not available" }

        val role =
            claims.optString("role").trim().ifBlank { AppPrefs.getRole(ctx).orEmpty() }
                .ifBlank { "DOCTOR" }

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

        val me = DoctorMe(
            id = id,
            fullName = fullName,
            email = email,
            role = role,
            phone = phone
        )

        cached = me
        _state.postValue(DoctorMeState.Ready(me))
    }

    fun clear() {
        cached = null
        _state.postValue(DoctorMeState.Empty("Signed out."))
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
