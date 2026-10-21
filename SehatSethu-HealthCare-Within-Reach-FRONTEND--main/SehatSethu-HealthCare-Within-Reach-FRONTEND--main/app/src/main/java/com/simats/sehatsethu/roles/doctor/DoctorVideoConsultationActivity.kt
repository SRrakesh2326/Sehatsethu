package com.simats.sehatsethu.roles.doctor

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.addCallback
import androidx.lifecycle.lifecycleScope
import com.simats.sehatsethu.ApiConfig.BASE_URL
import com.simats.sehatsethu.ApiConfig.HOST_URL
import com.simats.sehatsethu.ApiConfig.JITSI_BASE_URL
import com.simats.sehatsethu.AppPrefs
import com.simats.sehatsethu.BaseActivity
import com.simats.sehatsethu.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class DoctorVideoConsultationActivity : BaseActivity() {

    private lateinit var web: WebView

    private var room: String = ""
    private var displayName: String = "Doctor"

    private var apptKey: String = ""
    private var patientName: String = ""
    private var patientMeta: String = ""

    private var patientId: Long = 0L
    private var appointmentId: Long = 0L

    private var finishedToPrescription = false
    private var completionTriggered = false

    //  your Jitsi Docker server (LAN)
    private val jitsiServer = JITSI_BASE_URL;
    private val REQ_AV = 991
    private fun ensureAvPermissions() {
        val need = arrayListOf<String>()
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            need.add(android.Manifest.permission.RECORD_AUDIO)
        if (checkSelfPermission(android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            need.add(android.Manifest.permission.CAMERA)

        if (need.isNotEmpty()) requestPermissions(need.toTypedArray(), REQ_AV)
    }


    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_video_consultation)
        supportActionBar?.hide()
        ensureAvPermissions()

        room = sanitizeRoom(intent.getStringExtra("room").orEmpty())
        displayName = intent.getStringExtra("name").orEmpty().ifBlank { getString(R.string.doctor) }

        apptKey = intent.getStringExtra("appointmentKey").orEmpty()
        patientName = intent.getStringExtra("patientName").orEmpty()
        patientMeta = intent.getStringExtra("patientMeta").orEmpty()

        patientId = readLongAny("patient_id", "patientId", "pid")
        appointmentId = readLongAny("appointment_id", "appointmentId", "id", "apptId")

        if (room.isBlank()) {
            Toast.makeText(this, getString(R.string.failed), Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        onBackPressedDispatcher.addCallback(this) {
            runCatching { web.evaluateJavascript("hangupAndClose();", null) }
                .onFailure { safeFinishToPrescription("BACK_FALLBACK") }
        }

        web = findViewById(R.id.webMeet)

        // Clear old cookies/sessions
        runCatching {
            CookieManager.getInstance().apply {
                setAcceptCookie(true)
                removeAllCookies(null)
                flush()
            }
            WebStorage.getInstance().deleteAllData()
        }

        web.webViewClient = object : WebViewClient() {

            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                //  DEV ONLY: allow self-signed cert for your LAN IP
                val host = error.url?.let { runCatching { Uri.parse(it).host }.getOrNull() }.orEmpty()
                if (host == HOST_URL) {
                    handler.proceed()
                } else {
                    handler.cancel()
                    safeFinishToPrescription("SSL_BLOCKED")
                }
            }

            private fun isGoogleOAuth(url: String): Boolean {
                val u = url.lowercase()
                return u.contains("accounts.google.com") || u.contains("oauth") || u.contains("signin")
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url.toString()

                // Block Google login inside WebView
                if (isGoogleOAuth(url)) return true

                if (url.startsWith("intent:", true)) return true
                return false
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                runOnUiThread { runCatching { request.grant(request.resources) } }
            }
        }

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            allowFileAccess = true
            allowContentAccess = true

            userAgentString =
                "Mozilla/5.0 (Linux; Android 13; Pixel 6) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            cacheMode = WebSettings.LOAD_DEFAULT
        }

        web.addJavascriptInterface(AndroidBridge(), "Android")

        val url =
            "file:///android_asset/jitsi_embed.html?room=${enc(room)}&name=${enc(displayName)}&server=${enc(jitsiServer)}"
        web.loadUrl(url)
    }

    private fun sanitizeRoom(raw: String): String {
        val r = raw.trim()
        if (r.isBlank()) return ""
        return r.replace(Regex("[^a-zA-Z0-9_]"), "_")
    }

    private fun enc(s: String): String = try { URLEncoder.encode(s, "UTF-8") } catch (_: Throwable) { s }

    private fun safeFinishToPrescription(trigger: String) {
        if (finishedToPrescription) return
        finishedToPrescription = true

        triggerCompletionOnce(trigger)
        ensureIdsThenOpenPrescription(trigger)
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_AV) {
            // if denied, show toast
            val denied = grantResults.any { it != android.content.pm.PackageManager.PERMISSION_GRANTED }
            if (denied) Toast.makeText(this, "Mic/Camera permission required", Toast.LENGTH_SHORT).show()
            // reload once permissions granted
            if (!denied) runCatching { web.reload() }
        }
    }

    private fun ensureIdsThenOpenPrescription(trigger: String) {
        if (patientId > 0L) {
            openCreatePrescription()
            return
        }

        val token = AppPrefs.getToken(this).orEmpty()
        if (token.isBlank()) {
            Toast.makeText(this, getString(R.string.please_login_again), Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val key = apptKey.trim()
        val aid = appointmentId
        if (key.isBlank() && aid <= 0L) {
            Toast.makeText(this, getString(R.string.missing_patient_id), Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        lifecycleScope.launch {
            val resolved = withContext(Dispatchers.IO) {
                resolvePatientFromAppointment(token, key, aid)
            }
            if (isFinishing || isDestroyed) return@launch

            if (resolved.first > 0L) {
                patientId = resolved.first
                if (resolved.second > 0L) appointmentId = resolved.second
                openCreatePrescription()
            } else {
                Toast.makeText(this@DoctorVideoConsultationActivity, getString(R.string.missing_patient_id), Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    private fun resolvePatientFromAppointment(token: String, appointmentKey: String, appointmentId: Long): Pair<Long, Long> {
        val body = JSONObject().apply {
            if (appointmentKey.isNotBlank()) put("appointment_key", appointmentKey)
            if (appointmentId > 0L) put("appointment_id", appointmentId)
        }
        val res = postJsonAuth(BASE_URL + "doctor/appointment_resolve.php", token, body)
        if (!res.optBoolean("ok", false)) return 0L to 0L
        val data = res.optJSONObject("data") ?: return 0L to 0L

        val pid = (data.opt("patient_id") as? Number)?.toLong()
            ?: data.optString("patient_id", "").trim().toLongOrNull()
            ?: 0L

        val aid = (data.opt("appointment_id") as? Number)?.toLong()
            ?: data.optString("appointment_id", "").trim().toLongOrNull()
            ?: 0L

        return pid to aid
    }

    private fun triggerCompletionOnce(trigger: String) {
        if (completionTriggered) return
        completionTriggered = true

        val token = AppPrefs.getToken(this).orEmpty()
        if (token.isBlank()) return
        if (apptKey.isBlank()) return

        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                markAppointmentCompleted(token = token, appointmentKey = apptKey, trigger = trigger)
            }
        }
    }

    private fun openCreatePrescription() {
        if (patientId <= 0L) {
            Toast.makeText(this, getString(R.string.missing_patient_id), Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val itn = Intent(this, CreatePrescriptionActivity::class.java).apply {
            putExtra("patient_id", patientId)
            putExtra("patientId", patientId)
            putExtra("pid", patientId)

            if (appointmentId > 0L) {
                putExtra("appointment_id", appointmentId)
                putExtra("appointmentId", appointmentId)
                putExtra("id", appointmentId)
                putExtra("apptId", appointmentId)
            }

            if (apptKey.isNotBlank()) putExtra("appointmentKey", apptKey)
            if (patientName.isNotBlank()) putExtra("patientName", patientName)
            if (patientMeta.isNotBlank()) putExtra("patientMeta", patientMeta)
        }

        startActivity(itn)
        overridePendingTransition(R.anim.ai_enter, R.anim.ai_exit)
        finish()
    }

    override fun onDestroy() {
        runCatching {
            web.loadUrl("about:blank")
            web.removeAllViews()
            web.destroy()
        }
        super.onDestroy()
    }

    inner class AndroidBridge {
        @JavascriptInterface
        fun onHangup() {
            runOnUiThread { safeFinishToPrescription("JS_HANGUP") }
        }
    }

    private fun markAppointmentCompleted(token: String, appointmentKey: String, trigger: String): Boolean {
        val urlStr = BASE_URL + "doctor/appointment_complete.php"
        var conn: HttpURLConnection? = null
        return try {
            val body = JSONObject().apply {
                put("appointment_key", appointmentKey)
                put("trigger", trigger)
                put("client_ended_at_ms", System.currentTimeMillis())
            }

            conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 20000
                readTimeout = 20000
                doInput = true
                doOutput = true
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Authorization", "Bearer $token")
            }

            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            val res = runCatching { JSONObject(text) }.getOrElse { JSONObject().put("ok", false) }
            res.optBoolean("ok", false) == true
        } catch (_: Throwable) {
            false
        } finally {
            try { conn?.disconnect() } catch (_: Throwable) {}
        }
    }

    private fun postJsonAuth(urlStr: String, token: String, body: JSONObject): JSONObject {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 20000
                readTimeout = 20000
                doInput = true
                doOutput = true
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Authorization", "Bearer $token")
            }
            conn.outputStream.use { os ->
                os.write(body.toString().toByteArray(Charsets.UTF_8))
                os.flush()
            }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            runCatching { JSONObject(text) }.getOrElse { JSONObject().put("ok", false) }
        } catch (_: Throwable) {
            JSONObject().put("ok", false)
        } finally {
            try { conn?.disconnect() } catch (_: Throwable) {}
        }
    }

    private fun readLongAny(vararg keys: String): Long {
        for (k in keys) {
            val any = intent.extras?.get(k)
            when (any) {
                is Long -> if (any > 0L) return any
                is Int -> if (any > 0) return any.toLong()
                is String -> any.trim().toLongOrNull()?.let { if (it > 0L) return it }
            }
        }
        for (k in keys) {
            val v = intent.getLongExtra(k, 0L)
            if (v > 0L) return v
        }
        return 0L
    }
}
