package com.simats.sehatsethu.roles.pharmacist

import android.content.Context
import android.util.Base64
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.simats.sehatsethu.AppPrefs
import org.json.JSONObject

data class PharmacistMe(
    val id: Long?,
    val pharmacyName: String,
    val email: String,
    val role: String,
    val phone: String?
)

sealed class PharmacistMeState {
    object Loading : PharmacistMeState()
    data class Ready(val me: PharmacistMe) : PharmacistMeState()
    data class Empty(val message: String) : PharmacistMeState()
    data class Error(val message: String) : PharmacistMeState()
}

object PharmacistUserStore {

    private val _state = MutableLiveData<PharmacistMeState>()
    val state: LiveData<PharmacistMeState> = _state

    private var cached: PharmacistMe? = null
    fun getCached(): PharmacistMe? = cached

    suspend fun refresh(ctx: Context) {
        _state.postValue(PharmacistMeState.Loading)

        val token = AppPrefs.getToken(ctx).orEmpty()
        if (token.isBlank()) {
            cached = null
            _state.postValue(PharmacistMeState.Empty("You are signed out. Please login again."))
            return
        }

        val claims = decodeJwtPayload(token)
        if (claims == null) {
            cached = null
            _state.postValue(PharmacistMeState.Empty("Profile details are not available yet."))
            return
        }

        val pharmacyName =
            claims.optString("pharmacy_name").trim()
                .ifBlank { claims.optString("full_name").trim() }
                .ifBlank { claims.optString("name").trim() }
                .ifBlank { "Pharmacy" }

        val email = claims.optString("email").trim().ifBlank { "Not available" }

        val role =
            claims.optString("role").trim()
                .ifBlank { AppPrefs.getRole(ctx).orEmpty() }
                .ifBlank { "PHARMACIST" }

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

        val me = PharmacistMe(
            id = id,
            pharmacyName = pharmacyName,
            email = email,
            role = role,
            phone = phone
        )

        cached = me
        _state.postValue(PharmacistMeState.Ready(me))
    }

    fun clear() {
        cached = null
        _state.postValue(PharmacistMeState.Empty("Signed out."))
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
