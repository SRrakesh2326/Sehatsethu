package com.simats.sehatsethu.roles.admin

import android.content.Context
import com.simats.sehatsethu.ApiConfig.BASE_URL
import com.simats.sehatsethu.AppPrefs
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class ApiRes(
    val ok: Boolean,
    val code: Int,
    val url: String,
    val json: JSONObject? = null,
    val error: String? = null
)

object AdminApi {

    private fun buildUrl(path: String): String {
        return if (BASE_URL.endsWith("/")) BASE_URL + path else "$BASE_URL/$path"
    }

    private fun attachAuth(ctx: Context, conn: HttpURLConnection) {
        val token = AppPrefs.getToken(ctx).orEmpty()
        if (token.isNotBlank()) {
            conn.setRequestProperty("Authorization", "Bearer $token")
        }
    }

    suspend fun get(ctx: Context, path: String): ApiRes {
        val fullUrl = buildUrl(path)

        return try {
            val conn = (URL(fullUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 12000
                readTimeout = 12000
                setRequestProperty("Accept", "application/json")
                attachAuth(ctx, this)
            }

            val code = conn.responseCode
            val body = runCatching {
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                stream?.bufferedReader()?.readText().orEmpty()
            }.getOrDefault("")

            val j = runCatching { if (body.isNotBlank()) JSONObject(body) else null }.getOrNull()
            val ok = (code in 200..299) && (j?.optBoolean("ok", false) == true)

            ApiRes(ok = ok, code = code, url = fullUrl, json = j, error = if (ok) null else body)
        } catch (t: Throwable) {
            ApiRes(ok = false, code = -1, url = fullUrl, json = null, error = t.message)
        }
    }

    /**
     *  NEW: POST JSON (for approve/reject, etc.)
     * Does NOT change your existing get() behavior.
     */
    suspend fun postJson(ctx: Context, path: String, bodyJson: JSONObject): ApiRes {
        val fullUrl = buildUrl(path)

        return try {
            val conn = (URL(fullUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 12000
                readTimeout = 12000
                doOutput = true

                setRequestProperty("Accept", "application/json")
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                attachAuth(ctx, this)
            }

            runCatching {
                conn.outputStream.use { os ->
                    val bytes = bodyJson.toString().toByteArray(Charsets.UTF_8)
                    os.write(bytes)
                    os.flush()
                }
            }

            val code = conn.responseCode
            val body = runCatching {
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                stream?.bufferedReader()?.readText().orEmpty()
            }.getOrDefault("")

            val j = runCatching { if (body.isNotBlank()) JSONObject(body) else null }.getOrNull()
            val ok = (code in 200..299) && (j?.optBoolean("ok", false) == true)

            ApiRes(ok = ok, code = code, url = fullUrl, json = j, error = if (ok) null else body)
        } catch (t: Throwable) {
            ApiRes(ok = false, code = -1, url = fullUrl, json = null, error = t.message)
        }
    }
}
