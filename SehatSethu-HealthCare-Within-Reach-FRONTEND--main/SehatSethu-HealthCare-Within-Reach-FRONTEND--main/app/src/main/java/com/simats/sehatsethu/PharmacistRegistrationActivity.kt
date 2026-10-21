package com.simats.sehatsethu

import android.Manifest
import android.content.ContentResolver
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.graphics.Bitmap
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Base64
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.simats.sehatsethu.ApiConfig.BASE_URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

class PharmacistRegistrationActivity : BaseActivity() {

    private lateinit var role: Role

    private lateinit var ivBack: ImageView
    private lateinit var btnVerifyContinue: MaterialButton

    private lateinit var etFullName: EditText
    private lateinit var etPhone: EditText
    private lateinit var etPharmacyName: EditText
    private lateinit var etDrugLicense: EditText
    private lateinit var etVillage: EditText
    private lateinit var etAddress: EditText

    private lateinit var tvDocsCountPill: TextView
    private lateinit var tvDocsError: TextView
    private lateinit var tvDocsEmpty: TextView
    private lateinit var docsContainer: LinearLayout
    private lateinit var cardUploadZone: LinearLayout
    private lateinit var btnAddDocument: MaterialButton
    private lateinit var btnTakePhoto: MaterialButton

    private data class DocItem(val uri: Uri, val mime: String, val fileName: String)
    private val docs = mutableListOf<DocItem>()

    private val MIN_DOCS = 1
    private val MAX_DOCS = 3

    private val MAX_BYTES = 5 * 1024 * 1024
    private val ALLOWED_MIMES = setOf("application/pdf", "image/jpeg", "image/jpg", "image/png")

    // GPS captured during registration
    private var lat: Double? = null
    private var lng: Double? = null
    private var locationRequestedOnce = false

    private val reqPerms = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        val ok = (granted[Manifest.permission.ACCESS_FINE_LOCATION] == true) ||
                (granted[Manifest.permission.ACCESS_COARSE_LOCATION] == true)
        if (ok) fetchLocationOnce()
    }

    private val pickDoc = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult

        if (docs.size >= MAX_DOCS) {
            toast(getString(R.string.err_max_docs_reached, MAX_DOCS))
            refreshDocsUi()
            return@registerForActivityResult
        }

        runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        addDocFromUri(uri)
    }

    private val takePhoto = registerForActivityResult(ActivityResultContracts.TakePicturePreview()) { bmp ->
        if (bmp == null) return@registerForActivityResult

        if (docs.size >= MAX_DOCS) {
            toast(getString(R.string.err_max_docs_reached, MAX_DOCS))
            refreshDocsUi()
            return@registerForActivityResult
        }

        lifecycleScope.launch {
            val uri = withContext(Dispatchers.IO) { saveBitmapToCacheAndGetUri(bmp) }
            if (uri != null) addDocFromUri(uri) else toast(getString(R.string.failed))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pharmacist_registration)
        supportActionBar?.hide()

        role = RoleResolver.resolve(this, savedInstanceState)
        RoleResolver.persist(this, role)

        ivBack = findViewById(R.id.ivBack)
        btnVerifyContinue = findViewById(R.id.btnVerifyContinue)

        etFullName = findViewById(R.id.etFullName)
        etPhone = findViewById(R.id.etPhone)
        etPharmacyName = findViewById(R.id.etPharmacyName)
        etDrugLicense = findViewById(R.id.etDrugLicense)
        etVillage = findViewById(R.id.etVillage)
        etAddress = findViewById(R.id.etAddress)

        tvDocsCountPill = findViewById(R.id.tvDocsCountPill)
        tvDocsError = findViewById(R.id.tvDocsError)
        tvDocsEmpty = findViewById(R.id.tvDocsEmpty)
        docsContainer = findViewById(R.id.docsContainer)
        cardUploadZone = findViewById(R.id.cardUploadZone)
        btnAddDocument = findViewById(R.id.btnAddDocument)
        btnTakePhoto = findViewById(R.id.btnTakePhoto)

        ivBack.setOnClickListener { handleBack() }
        onBackPressedDispatcher.addCallback(this) { handleBack() }

        cardUploadZone.setOnClickListener { openPicker() }
        btnAddDocument.setOnClickListener { openPicker() }
        btnTakePhoto.setOnClickListener { takePhoto.launch(null) }

        btnVerifyContinue.setOnClickListener { submit() }

        refreshDocsUi()
        ensureLocation()
    }

    private fun ensureLocation() {
        if (locationRequestedOnce) return
        locationRequestedOnce = true

        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

        if (fine || coarse) fetchLocationOnce()
        else reqPerms.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }

    private fun fetchLocationOnce() {
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager

        fun accept(loc: Location?) {
            if (loc == null) return
            val la = loc.latitude
            val lo = loc.longitude
            if (la in -90.0..90.0 && lo in -180.0..180.0) {
                lat = la
                lng = lo
            }
        }

        // last known first
        runCatching {
            val providers = lm.getProviders(true)
            var best: Location? = null
            for (p in providers) {
                val l = lm.getLastKnownLocation(p) ?: continue
                if (best == null || l.accuracy < best!!.accuracy) best = l
            }
            if (best != null) {
                accept(best)
                return
            }
        }

        // single update
        runCatching {
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    accept(location)
                    runCatching { lm.removeUpdates(this) }
                }
                override fun onProviderDisabled(provider: String) {}
                override fun onProviderEnabled(provider: String) {}
            }

            val provider = when {
                lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
                lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
                else -> null
            } ?: return

            lm.requestSingleUpdate(provider, listener, null)
        }
    }

    private fun openPicker() {
        if (docs.size >= MAX_DOCS) {
            toast(getString(R.string.err_max_docs_reached, MAX_DOCS))
            refreshDocsUi()
            return
        }
        pickDoc.launch(arrayOf("application/pdf", "image/*"))
    }

    private fun handleBack() {
        if (!isTaskRoot) {
            goBack()
            return
        }

        runCatching { AppPrefs.setToken(this, "") }
        runCatching { AppPrefs.setProfileCompleted(this, false) }
        runCatching { AppPrefs.setAdminVerificationStatus(this, "") }
        runCatching { AppPrefs.setAdminVerificationReason(this, null) }

        RoleResolver.persist(this, role)

        val i = RoleResolver.putRole(Intent(this, LoginActivity::class.java), role).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(i)
        finish()
        overridePendingTransition(0, 0)
    }

    private fun addDocFromUri(uri: Uri) {
        if (docs.size >= MAX_DOCS) {
            toast(getString(R.string.err_max_docs_reached, MAX_DOCS))
            refreshDocsUi()
            return
        }

        val mime = (contentResolver.getType(uri) ?: "").lowercase().trim()
        if (mime.isBlank() || !ALLOWED_MIMES.contains(mime)) {
            toast(getString(R.string.err_doc_invalid_type))
            return
        }

        val name = displayName(uri) ?: "document.${safeExt(mime)}"

        if (docs.any { it.uri.toString() == uri.toString() || it.fileName.equals(name, true) }) {
            toast(getString(R.string.already_added))
            return
        }

        docs.add(DocItem(uri = uri, mime = mime, fileName = name))
        refreshDocsUi()
    }

    private fun refreshDocsUi() {
        tvDocsCountPill.text = getString(R.string.docs_count_pill, docs.size, MIN_DOCS)

        tvDocsEmpty.visibility = if (docs.isEmpty()) View.VISIBLE else View.GONE

        tvDocsError.text = getString(R.string.docs_min_error, MIN_DOCS)
        tvDocsError.visibility = if (docs.size < MIN_DOCS) View.VISIBLE else View.GONE

        docsContainer.removeAllViews()
        docs.forEachIndexed { index, doc ->
            docsContainer.addView(buildDocRow(index, doc))
        }

        val canAdd = docs.size < MAX_DOCS
        btnAddDocument.isEnabled = canAdd
        btnTakePhoto.isEnabled = canAdd
        cardUploadZone.isEnabled = canAdd
    }

    private fun buildDocRow(index: Int, item: DocItem): View {
        val row = layoutInflater.inflate(R.layout.item_uploaded_doc_row, docsContainer, false)

        val tvName = row.findViewById<TextView>(R.id.tvDocName)
        val tvMeta = row.findViewById<TextView>(R.id.tvDocMeta)
        val ivRemove = row.findViewById<ImageView>(R.id.ivRemove)

        tvName.text = item.fileName
        tvMeta.text = item.mime.uppercase()

        ivRemove.setOnClickListener {
            if (index in docs.indices) {
                docs.removeAt(index)
                refreshDocsUi()
            }
        }

        row.setOnClickListener {
            runCatching {
                val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(item.uri, item.mime)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(viewIntent)
            }
        }

        return row
    }

    private fun submit() {
        val token = AppPrefs.getToken(this).orEmpty()
        if (token.isBlank()) {
            toast(getString(R.string.please_login_again))
            handleBack()
            return
        }

        val name = etFullName.text?.toString()?.trim().orEmpty()
        val phoneRaw = etPhone.text?.toString()?.trim().orEmpty()
        val pharmacy = etPharmacyName.text?.toString()?.trim().orEmpty()
        val license = etDrugLicense.text?.toString()?.trim().orEmpty()
        val villageTown = etVillage.text?.toString()?.trim().orEmpty()
        val address: String? = etAddress.text?.toString()?.trim()?.ifBlank { null }

        val phone = phoneRaw.replace(Regex("[^0-9+]"), "")
        val digitsOnly = phone.replace(Regex("[^0-9]"), "")

        if (name.isBlank() || phoneRaw.isBlank() || pharmacy.isBlank() || license.isBlank() || villageTown.isBlank()) {
            toast(getString(R.string.err_fill_all_fields))
            return
        }
        if (!FieldValidator.isValidName(name)) {
            toast("Name must contain only alphabets.")
            return
        }
        if (!FieldValidator.isValidIndianPhone(phoneRaw)) {
            toast(FieldValidator.PHONE_HINT)
            return
        }
        if (docs.size < MIN_DOCS) {
            val msg = getString(R.string.please_upload_min_docs, MIN_DOCS)
            tvDocsError.text = msg
            tvDocsError.visibility = View.VISIBLE
            toast(msg)
            return
        } else {
            tvDocsError.visibility = View.GONE
        }

        setLoading(true)

        lifecycleScope.launch {
            val docsJson = withContext(Dispatchers.IO) { buildDocumentsJsonOrNull() }
            if (docsJson == null) {
                setLoading(false)
                return@launch
            }

            val body = JSONObject().apply {
                put("full_name", name)
                put("phone", phone)
                put("pharmacy_name", pharmacy)
                put("drug_license_no", license)
                put("village_town", villageTown)
                if (address != null) put("full_address", address)
                //  send GPS if we have it
                lat?.let { put("latitude", it) }
                lng?.let { put("longitude", it) }
                put("documents", docsJson)
            }

            val res = withContext(Dispatchers.IO) {
                postJsonAuth(
                    url = BASE_URL + "profile/pharmacist_submit.php",
                    token = token,
                    body = body
                )
            }

            setLoading(false)

            if (res.optBoolean("ok", false)) {
                runCatching { AppPrefs.setProfileCompleted(this@PharmacistRegistrationActivity, true) }
                AppPrefs.setAdminVerificationStatus(this@PharmacistRegistrationActivity, "UNDER_REVIEW")
                AppPrefs.setAdminVerificationReason(this@PharmacistRegistrationActivity, null)

                val i = Intent(this@PharmacistRegistrationActivity, VerificationPendingActivity::class.java).apply {
                    putExtra(NavKeys.EXTRA_ROLE, Role.PHARMACIST.id)
                }
                goNext(i, finishThis = true)
            } else {
                toast(res.optString("error", getString(R.string.failed)))
            }
        }
    }

    private fun buildDocumentsJsonOrNull(): JSONArray? {
        val out = JSONArray()

        for ((i, d) in docs.withIndex()) {
            val bytes = readAllBytes(d.uri) ?: run {
                runOnUiThread { toast(getString(R.string.err_pick_doc)) }
                return null
            }

            if (bytes.isEmpty() || bytes.size > MAX_BYTES) {
                runOnUiThread { toast(getString(R.string.err_doc_too_large)) }
                return null
            }

            val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)

            out.put(JSONObject().apply {
                put("doc_type", "DOC_${i + 1}")
                put("file_name", d.fileName)
                put("mime_type", d.mime)
                put("file_base64", b64)
            })
        }

        return out
    }

    private fun postJsonAuth(url: String, token: String, body: JSONObject): JSONObject {
        val conn = (URL(url).openConnection() as HttpURLConnection)
        return try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 60000
            conn.readTimeout = 60000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val txt = stream.bufferedReader().use { it.readText() }
            JSONObject(txt)
        } catch (_: Throwable) {
            JSONObject().put("ok", false).put("error", getString(R.string.network_error))
        } finally {
            conn.disconnect()
        }
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

    private fun safeExt(mime: String): String {
        return when (mime.lowercase()) {
            "application/pdf" -> "pdf"
            "image/png" -> "png"
            else -> "jpg"
        }
    }

    private fun saveBitmapToCacheAndGetUri(bmp: Bitmap): Uri? {
        return try {
            val fileName = "photo_${System.currentTimeMillis()}.jpg"
            val file = File(cacheDir, fileName)
            FileOutputStream(file).use { fos ->
                bmp.compress(Bitmap.CompressFormat.JPEG, 92, fos)
            }
            androidx.core.content.FileProvider.getUriForFile(
                this,
                "${packageName}.fileprovider",
                file
            )
        } catch (_: Throwable) {
            null
        }
    }

    private fun setLoading(loading: Boolean) {
        btnVerifyContinue.isEnabled = !loading
        btnVerifyContinue.alpha = if (loading) 0.7f else 1f

        val canAdd = !loading && docs.size < MAX_DOCS
        btnAddDocument.isEnabled = canAdd
        btnTakePhoto.isEnabled = canAdd
        cardUploadZone.isEnabled = canAdd
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(NavKeys.EXTRA_ROLE, role.id)
        super.onSaveInstanceState(outState)
    }
}
