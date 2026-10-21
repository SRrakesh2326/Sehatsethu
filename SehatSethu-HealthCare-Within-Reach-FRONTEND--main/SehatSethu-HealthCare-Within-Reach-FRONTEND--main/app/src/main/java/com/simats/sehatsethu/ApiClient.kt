package com.simats.sehatsethu

import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.ProtocolException
import java.net.URL

object ApiClient {

    data class ApiResult(
        val ok: Boolean,
        val httpCode: Int,
        val json: JSONObject?,
        val errorMessage: String?,
        val url: String
    )

    private const val BASE_URL = ApiConfig.BASE_URL
    private const val DEFAULT_TIMEOUT_MS = 15_000

    fun postJson(path: String, body: JSONObject): ApiResult {
        return postJsonInternal(path, body, DEFAULT_TIMEOUT_MS, retryOnEof = true, headers = emptyMap())
    }

    fun postJson(path: String, body: JSONObject, timeoutMs: Int): ApiResult {
        return postJsonInternal(path, body, timeoutMs, retryOnEof = true, headers = emptyMap())
    }

    fun postJson(path: String, body: JSONObject, timeoutMs: Int, headers: Map<String, String>): ApiResult {
        return postJsonInternal(path, body, timeoutMs, retryOnEof = true, headers = headers)
    }

    fun postJsonAuth(path: String, body: JSONObject, token: String, timeoutMs: Int = DEFAULT_TIMEOUT_MS): ApiResult {
        val h = if (token.isBlank()) emptyMap() else mapOf("Authorization" to "Bearer $token")
        return postJsonInternal(path, body, timeoutMs, retryOnEof = true, headers = h)
    }

    fun postJsonWithAuth(path: String, body: JSONObject, token: String, timeoutMs: Int = DEFAULT_TIMEOUT_MS): ApiResult {
        return if (token.isBlank()) {
            postJson(path, body, timeoutMs)
        } else {
            postJsonAuth(path, body, token, timeoutMs)
        }
    }

    // NEW (optional): automatic session-aware auth (does NOT affect existing calls)
    fun postJsonWithSession(context: android.content.Context, path: String, body: JSONObject, timeoutMs: Int = DEFAULT_TIMEOUT_MS): ApiResult {
        val token = AppPrefs.getToken(context) ?: ""
        val expiredLocal = JwtUtils.isExpired(token)
        if (token.isBlank() || expiredLocal || AppPrefs.isTokenExpired(context)) {
            AppPrefs.setTokenExpired(context, true)
            return ApiResult(
                ok = false,
                httpCode = 0,
                json = null,
                errorMessage = "OFFLINE_SESSION",
                url = BASE_URL + path
            )
        }

        val res = postJsonAuth(path, body, token, timeoutMs)
        if (res.httpCode == 401) {
            // mark expired but do NOT wipe token (offline read-only friendly)
            AppPrefs.setTokenExpired(context, true)
        }
        return res
    }

    private fun postJsonInternal(
        path: String,
        body: JSONObject,
        timeoutMs: Int,
        retryOnEof: Boolean,
        headers: Map<String, String>
    ): ApiResult {
        val fullUrl = BASE_URL + path

        // fixes XAMPP intermittent EOF
        System.setProperty("http.keepAlive", "false")

        var conn: HttpURLConnection? = null

        fun readAll(stream: InputStream?): String {
            if (stream == null) return ""
            return stream.bufferedReader().use(BufferedReader::readText)
        }

        try {
            val url = URL(fullUrl)
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                instanceFollowRedirects = false
                useCaches = false
                doInput = true
                doOutput = true

                connectTimeout = timeoutMs
                readTimeout = timeoutMs

                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Accept-Encoding", "identity")
                setRequestProperty("Connection", "close")

                for ((k, v) in headers) setRequestProperty(k, v)
            }

            val bytes = body.toString().toByteArray(Charsets.UTF_8)
            conn.setFixedLengthStreamingMode(bytes.size)

            conn.outputStream.use { os ->
                os.write(bytes)
                os.flush()
            }

            val code = conn.responseCode
            val text = if (code in 200..299) readAll(conn.inputStream) else readAll(conn.errorStream)
            val json = runCatching { JSONObject(text) }.getOrNull()

            val ok = (code in 200..299) && (json?.optBoolean("ok", false) == true)

            // normalize 401 message so UI can handle consistently
            val serverErr =
                json?.optString("error")?.takeIf { it.isNotBlank() }
                    ?: json?.optString("message")?.takeIf { !ok && it.isNotBlank() }
                    ?: if (!ok) text.takeIf { it.isNotBlank() } else null

            val normalizedErr = when {
                code == 401 -> "Invalid/expired token"
                else -> serverErr
            }

            return ApiResult(
                ok = ok,
                httpCode = code,
                json = json,
                errorMessage = normalizedErr,
                url = fullUrl
            )
        } catch (e: ProtocolException) {
            if (retryOnEof) {
                return postJsonInternal(path, body, timeoutMs, retryOnEof = false, headers = headers)
            }
            return ApiResult(false, 0, null, "ProtocolException: ${e.message}", fullUrl)
        } catch (e: Exception) {
            return ApiResult(false, 0, null, "${e.javaClass.simpleName}: ${e.message}", fullUrl)
        } finally {
            conn?.disconnect()
        }
    }
}
