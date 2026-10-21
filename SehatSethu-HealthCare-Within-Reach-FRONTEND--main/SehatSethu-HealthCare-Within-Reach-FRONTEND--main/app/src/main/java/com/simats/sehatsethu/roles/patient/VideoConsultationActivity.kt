package com.simats.sehatsethu.roles.patient

import android.annotation.SuppressLint
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.webkit.*
import android.widget.Toast
import com.simats.sehatsethu.ApiConfig.BASE_URL
import com.simats.sehatsethu.ApiConfig.HOST_URL
import com.simats.sehatsethu.ApiConfig.JITSI_BASE_URL
import com.simats.sehatsethu.BaseActivity
import com.simats.sehatsethu.R
import java.net.URLEncoder

class VideoConsultationActivity : BaseActivity() {

    private lateinit var web: WebView

    private var room: String = ""
    private var displayName: String = "Patient"
    private var server: String = JITSI_BASE_URL
    private val REQ_AV = 991
    private fun ensureAvPermissions() {
        val need = arrayListOf<String>()
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            need.add(android.Manifest.permission.RECORD_AUDIO)
        if (checkSelfPermission(android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            need.add(android.Manifest.permission.CAMERA)

        if (need.isNotEmpty()) requestPermissions(need.toTypedArray(), REQ_AV)
    }

    //  force your server by default

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_video_consultation)
        supportActionBar?.hide()
        ensureAvPermissions()

        room = sanitizeRoom(intent.getStringExtra("room").orEmpty().trim())
        displayName = intent.getStringExtra("name").orEmpty().ifBlank { getString(R.string.patient) }

        val incomingServer = intent.getStringExtra("server").orEmpty().trim()
        server = if (incomingServer.isBlank()) JITSI_BASE_URL else incomingServer.trimEnd('/')


        if (room.isBlank()) {
            Toast.makeText(this, getString(R.string.failed), Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        web = findViewById(R.id.webMeet)

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(web, true)
        }

        web.webViewClient = object : WebViewClient() {

            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                val host = runCatching { Uri.parse(error.url).host }.getOrNull().orEmpty()
                if (host == HOST_URL) handler.proceed() else handler.cancel()
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                // show real reason
                if (request.isForMainFrame) {
                    Toast.makeText(this@VideoConsultationActivity, error.description, Toast.LENGTH_SHORT).show()
                }
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
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)

            userAgentString =
                "Mozilla/5.0 (Linux; Android 13; Pixel 6) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
        }

        web.addJavascriptInterface(AndroidBridge(), "Android")

        val url = "file:///android_asset/jitsi_embed.html" +
                "?room=${enc(room)}&name=${enc(displayName)}&server=${enc(server)}"

        web.loadUrl(url)
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

    private fun normalizeServer(raw: String): String {
        var s = raw.trim().trimEnd('/')
        if (s.isBlank()) return JITSI_BASE_URL

        // if someone passes http://192.168.1.7:8000 convert to https 8443
        if (s.startsWith("http://") && s.contains(":8000")) {
            s = s.replace("http://", "https://").replace(":8000", ":8443")
        }
        // if http but no port => switch to https
        if (s.startsWith("http://")) s = s.replace("http://", "https://")

        return s.trimEnd('/')
    }

    private fun sanitizeRoom(raw: String): String {
        val r = raw.trim()
        if (r.isBlank()) return ""
        return r.replace(Regex("[^a-zA-Z0-9_]"), "_")
    }

    private fun enc(s: String): String = try { URLEncoder.encode(s, "UTF-8") } catch (_: Throwable) { s }

    override fun onDestroy() {
        runCatching {
            web.loadUrl("about:blank")
            web.removeAllViews()
            web.destroy()
        }
        super.onDestroy()
    }

    inner class AndroidBridge {
        @JavascriptInterface fun onHangup() { runOnUiThread { finish() } }
        @JavascriptInterface fun onLog(msg: String) {
            android.util.Log.d("JITSI_WEB", msg)
        }
    }
}
