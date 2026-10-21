package com.simats.sehatsethu

import android.util.Base64
import org.json.JSONObject

object JwtUtils {

    fun decodePayload(jwt: String?): JSONObject? {
        if (jwt.isNullOrBlank()) return null
        val parts = jwt.split(".")
        if (parts.size < 2) return null

        val payloadB64 = parts[1]
        val jsonStr = runCatching { String(base64UrlDecode(payloadB64), Charsets.UTF_8) }.getOrNull()
            ?: return null

        return runCatching { JSONObject(jsonStr) }.getOrNull()
    }

    fun expEpochSeconds(jwt: String?): Long {
        val p = decodePayload(jwt) ?: return 0L
        return runCatching { p.optLong("exp", 0L) }.getOrDefault(0L)
    }

    fun isExpired(jwt: String?, leewaySeconds: Long = 30L): Boolean {
        if (jwt.isNullOrBlank()) return true
        val exp = expEpochSeconds(jwt)
        if (exp <= 0L) return true
        val now = System.currentTimeMillis() / 1000L
        return now >= (exp - leewaySeconds)
    }

    fun iatEpochSeconds(jwt: String?): Long {
        val p = decodePayload(jwt) ?: return 0L
        return runCatching { p.optLong("iat", 0L) }.getOrDefault(0L)
    }

    private fun base64UrlDecode(str: String): ByteArray {
        var s = str.replace('-', '+').replace('_', '/')
        val pad = (4 - (s.length % 4)) % 4
        s += "=".repeat(pad)
        return Base64.decode(s, Base64.DEFAULT)
    }
}
