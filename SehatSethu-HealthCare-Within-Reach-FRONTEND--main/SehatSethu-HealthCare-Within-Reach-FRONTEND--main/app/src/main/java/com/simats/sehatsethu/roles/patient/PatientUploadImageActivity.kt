package com.simats.sehatsethu.roles.patient

import android.content.ContentResolver
import android.content.Intent
import android.database.Cursor
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.simats.sehatsethu.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

class PatientUploadImageActivity : BaseActivity() {

    private lateinit var role: Role

    private lateinit var btnBack: ImageView
    private lateinit var btnSkipTop: TextView

    private lateinit var boxUpload: View
    private lateinit var btnTakePhoto: MaterialCardView
    private lateinit var btnGallery: MaterialCardView

    private lateinit var tvNoImagesTitle: TextView
    private lateinit var tvNoImagesSub: TextView

    private lateinit var loadingOverlay: FrameLayout

    // dynamic UI (programmatically added under boxUpload)
    private var selectedListHost: LinearLayout? = null
    private var btnUploadContinue: MaterialButton? = null

    private data class Item(val uri: Uri, val mime: String, val fileName: String)

    private val items = mutableListOf<Item>()
    private val cameraBytesMap = mutableMapOf<String, ByteArray>()

    private val MAX_FILES = 5
    private val MAX_BYTES = 5 * 1024 * 1024
    private val ALLOWED = setOf("application/pdf", "image/jpeg", "image/jpg", "image/png")

    private var isUploading = false

    private val pickMultiple =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isNullOrEmpty()) return@registerForActivityResult
            handlePickedUris(uris)
        }

    private val takePhoto =
        registerForActivityResult(ActivityResultContracts.TakePicturePreview()) { bmp ->
            if (bmp == null) return@registerForActivityResult
            lifecycleScope.launch { handleCameraBitmap(bmp) }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_upload_images)
        supportActionBar?.hide()

        role = RoleResolver.resolve(this, savedInstanceState)
        RoleResolver.persist(this, role)

        btnBack = findViewById(R.id.btnBack)
        btnSkipTop = findViewById(R.id.btnSkipTop)

        boxUpload = findViewById(R.id.boxUpload)
        btnTakePhoto = findViewById(R.id.btnTakePhoto)
        btnGallery = findViewById(R.id.btnGallery)

        tvNoImagesTitle = findViewById(R.id.tvNoImagesTitle)
        tvNoImagesSub = findViewById(R.id.tvNoImagesSub)

        setupLoadingOverlay()
        ensureDynamicArea()

        //  Back must return to previous activity in backstack (PatientRegistrationActivity)
        btnBack.setOnClickListener { goBack() }
        onBackPressedDispatcher.addCallback(this) { goBack() }

        // Skip -> home
        btnSkipTop.setOnClickListener { goPatientHome() }

        boxUpload.setOnClickListener { openGallery() }
        btnGallery.setOnClickListener { openGallery() }
        btnTakePhoto.setOnClickListener { takePhoto.launch(null) }

        refreshUi()
        renderSelectedItems()
        updateUploadButtonState()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(NavKeys.EXTRA_ROLE, role.id)
        super.onSaveInstanceState(outState)
    }

    private fun openGallery() {
        if (isUploading) return
        if (items.size >= MAX_FILES) {
            toast(getString(R.string.err_max_docs_reached, MAX_FILES))
            return
        }
        pickMultiple.launch(arrayOf("application/pdf", "image/*"))
    }

    private fun handlePickedUris(uris: List<Uri>) {
        if (isUploading) return

        val remaining = (MAX_FILES - items.size).coerceAtLeast(0)
        val take = uris.take(remaining)

        if (take.isEmpty()) {
            toast(getString(R.string.err_max_docs_reached, MAX_FILES))
            return
        }

        take.forEach { uri ->
            runCatching {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }

            val mime = (contentResolver.getType(uri) ?: "").lowercase().trim()
            if (mime.isBlank() || !ALLOWED.contains(mime)) {
                toast(getString(R.string.err_doc_invalid_type))
                return@forEach
            }

            val name = displayName(uri) ?: "file_${System.currentTimeMillis()}.${safeExt(mime)}"
            if (items.any { it.uri.toString() == uri.toString() }) return@forEach

            items.add(Item(uri, mime, name))
        }

        refreshUi()
        renderSelectedItems()
        updateUploadButtonState()
        //  NO AUTO UPLOAD here (user can add more files)
    }

    private suspend fun handleCameraBitmap(bmp: Bitmap) {
        if (isUploading) return
        if (items.size >= MAX_FILES) {
            toast(getString(R.string.err_max_docs_reached, MAX_FILES))
            return
        }

        val bytes = withContext(Dispatchers.IO) {
            ByteArrayOutputStream().use { bos ->
                bmp.compress(Bitmap.CompressFormat.JPEG, 92, bos)
                bos.toByteArray()
            }
        }

        if (bytes.isEmpty() || bytes.size > MAX_BYTES) {
            toast(getString(R.string.err_doc_too_large))
            return
        }

        val tmpUri = Uri.parse("camera://local/${System.currentTimeMillis()}")
        val fileName = "camera_${System.currentTimeMillis()}.jpg"

        items.add(Item(tmpUri, "image/jpeg", fileName))
        cameraBytesMap[tmpUri.toString()] = bytes

        refreshUi()
        renderSelectedItems()
        updateUploadButtonState()
        //  NO AUTO UPLOAD here
    }

    private fun refreshUi() {
        if (items.isEmpty()) {
            tvNoImagesTitle.text = getString(R.string.no_images_uploaded)
            tvNoImagesSub.text = getString(R.string.tap_buttons_to_add)
        } else {
            tvNoImagesTitle.text = "Selected: ${items.size} / $MAX_FILES"
            tvNoImagesSub.text = "You can add more files, then tap Upload & Continue."
        }
    }

    /**
     * Creates:
     * - selected list host below boxUpload
     * - Upload & Continue button below the list
     * No XML changes required.
     */
    private fun ensureDynamicArea() {
        if (selectedListHost != null && btnUploadContinue != null) return

        val scroll = runCatching { findViewById<View>(R.id.scroll) }.getOrNull()
        val content = (scroll as? ViewGroup)?.getChildAt(0) as? LinearLayout ?: return

        val boxIdx = content.indexOfChild(boxUpload).takeIf { it >= 0 } ?: return

        // list host
        if (selectedListHost == null) {
            selectedListHost = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                visibility = View.GONE
                setPadding(0, dp(12), 0, 0)
            }
            content.addView(selectedListHost, (boxIdx + 1).coerceAtMost(content.childCount))
        }

        // upload button
        if (btnUploadContinue == null) {
            btnUploadContinue = MaterialButton(this).apply {
                text = "Upload & Continue"
                isAllCaps = false
                cornerRadius = dp(14)
                setPadding(dp(16), dp(14), dp(16), dp(14))
                iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
                visibility = View.GONE
                setOnClickListener { uploadMedicalHistoryAndFinish() }
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(14)
            }
            content.addView(btnUploadContinue, (boxIdx + 2).coerceAtMost(content.childCount), lp)
        }
    }

    private fun renderSelectedItems() {
        ensureDynamicArea()
        val host = selectedListHost ?: return

        host.removeAllViews()
        host.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE

        items.forEachIndexed { index, item ->
            host.addView(makeFileCard(item, index))
        }
    }

    private fun makeFileCard(item: Item, index: Int): View {
        val card = MaterialCardView(this).apply {
            radius = dp(14).toFloat()
            cardElevation = 0f
            strokeWidth = dp(1)
            setStrokeColor(0xFFE2E8F0.toInt())
            setCardBackgroundColor(0xFFFFFFFF.toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
        }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(12), dp(12))
        }

        val badge = TextView(this).apply {
            text = when {
                item.mime == "application/pdf" -> "PDF"
                item.mime.startsWith("image/") -> "IMG"
                else -> "FILE"
            }
            setTextColor(0xFF0F172A.toInt())
            textSize = 12f
            setPadding(dp(10), dp(6), dp(10), dp(6))
            setBackgroundColor(0xFFF1F5F9.toInt())
        }

        val mid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginStart = dp(12) }
        }

        val title = TextView(this).apply {
            text = item.fileName
            setTextColor(0xFF0F172A.toInt())
            textSize = 14f
        }

        val sub = TextView(this).apply {
            text = "${item.mime} • ${getSizeText(item)}"
            setTextColor(0xFF64748B.toInt())
            textSize = 12f
        }

        mid.addView(title)
        mid.addView(sub)

        val btnRemove = TextView(this).apply {
            text = "✕"
            textSize = 16f
            setTextColor(0xFFEF4444.toInt())
            setPadding(dp(10), dp(6), dp(10), dp(6))
            isClickable = true
            isFocusable = true
            setOnClickListener {
                if (isUploading) return@setOnClickListener
                removeItemAt(index)
            }
        }

        row.addView(badge)
        row.addView(mid)
        row.addView(btnRemove)

        card.addView(row)
        return card
    }

    private fun removeItemAt(i: Int) {
        if (i !in 0 until items.size) return
        val removed = items.removeAt(i)
        cameraBytesMap.remove(removed.uri.toString())
        refreshUi()
        renderSelectedItems()
        updateUploadButtonState()
    }

    private fun updateUploadButtonState() {
        ensureDynamicArea()
        val btn = btnUploadContinue ?: return
        val show = items.isNotEmpty()
        btn.visibility = if (show) View.VISIBLE else View.GONE
        btn.isEnabled = show && !isUploading
        btn.alpha = if (btn.isEnabled) 1f else 0.7f
    }

    private fun uploadMedicalHistoryAndFinish() {
        if (isUploading) return
        if (items.isEmpty()) {
            toast("Please select at least one file.")
            return
        }

        val token = AppPrefs.getToken(this).orEmpty()
        if (token.isBlank()) {
            toast(getString(R.string.please_login_again))
            goNext(Intent(this, RoleSelectActivity::class.java), finishThis = true)
            return
        }

        isUploading = true
        setLoading(true)
        updateUploadButtonState()

        lifecycleScope.launch {
            val payload = withContext(Dispatchers.IO) { buildItemsJsonOrNull() }
            if (payload == null) {
                isUploading = false
                setLoading(false)
                updateUploadButtonState()
                return@launch
            }

            val res = withContext(Dispatchers.IO) {
                AuthApi.uploadPatientMedicalHistory(token = token, documents = payload)
            }

            isUploading = false
            setLoading(false)
            updateUploadButtonState()

            val ok = res.ok && (res.json?.optBoolean("ok", false) == true)
            if (!ok) {
                toast(res.json?.optString("error") ?: res.errorMessage ?: getString(R.string.failed))
                return@launch
            }

            goPatientHome()
        }
    }

    private fun buildItemsJsonOrNull(): JSONArray? {
        val arr = JSONArray()

        for ((i, it) in items.withIndex()) {
            val bytes: ByteArray? = if (it.uri.scheme == "camera") {
                cameraBytesMap[it.uri.toString()]
            } else {
                readAllBytes(it.uri)
            }

            if (bytes == null) {
                runOnUiThread { toast(getString(R.string.err_pick_doc)) }
                return null
            }

            if (bytes.isEmpty() || bytes.size > MAX_BYTES) {
                runOnUiThread { toast(getString(R.string.err_doc_too_large)) }
                return null
            }

            val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)

            arr.put(JSONObject().apply {
                put("file_name", it.fileName)
                put("mime_type", it.mime)
                put("file_base64", b64)
                put("doc_type", "MED_${i + 1}")
            })
        }

        return arr
    }

    private fun readAllBytes(uri: Uri): ByteArray? {
        return try {
            contentResolver.openInputStream(uri)?.use { it.readBytes() }
        } catch (_: Throwable) {
            null
        }
    }

    private fun displayName(uri: Uri): String? {
        val cr: ContentResolver = contentResolver
        var cursor: Cursor? = null
        return try {
            cursor = cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            if (cursor != null && cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) cursor.getString(idx) else null
            } else null
        } catch (_: Throwable) {
            null
        } finally {
            cursor?.close()
        }
    }

    private fun getSizeText(item: Item): String {
        val bytes: Long? = try {
            if (item.uri.scheme == "camera") {
                cameraBytesMap[item.uri.toString()]?.size?.toLong()
            } else {
                querySizeBytes(item.uri)
            }
        } catch (_: Throwable) {
            null
        }
        if (bytes == null) return "—"
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        return if (mb >= 1) String.format("%.2f MB", mb) else String.format("%.0f KB", kb)
    }

    private fun querySizeBytes(uri: Uri): Long? {
        var cursor: Cursor? = null
        return try {
            cursor = contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
            if (cursor != null && cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (idx >= 0) cursor.getLong(idx) else null
            } else null
        } finally {
            cursor?.close()
        }
    }

    private fun safeExt(mime: String): String {
        return when (mime.lowercase()) {
            "application/pdf" -> "pdf"
            "image/png" -> "png"
            else -> "jpg"
        }
    }

    private fun goPatientHome() {
        val home = try {
            Intent(this, Class.forName("com.simats.sehatsethu.roles.patient.PatientActivity"))
        } catch (_: Throwable) {
            Intent(this, MainActivity::class.java)
        }
        RoleResolver.putRole(home, Role.PATIENT)
        goNext(home, finishThis = true)
    }

    private fun setupLoadingOverlay() {
        val root = findViewById<View>(android.R.id.content) as ViewGroup
        loadingOverlay = FrameLayout(this).apply {
            visibility = View.GONE
            alpha = 0f
            isClickable = true
            isFocusable = true
            setBackgroundColor(0x22000000)

            val spinner = CircularProgressIndicator(this@PatientUploadImageActivity).apply {
                isIndeterminate = true
            }
            addView(
                spinner,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                ).apply { gravity = Gravity.CENTER }
            )
        }
        root.addView(
            loadingOverlay,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
    }

    private fun setLoading(loading: Boolean) {
        loadingOverlay.visibility = if (loading) View.VISIBLE else View.GONE
        loadingOverlay.alpha = if (loading) 1f else 0f

        btnBack.isEnabled = !loading
        btnSkipTop.isEnabled = !loading
        btnTakePhoto.isEnabled = !loading
        btnGallery.isEnabled = !loading
        boxUpload.isEnabled = !loading
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()
}
