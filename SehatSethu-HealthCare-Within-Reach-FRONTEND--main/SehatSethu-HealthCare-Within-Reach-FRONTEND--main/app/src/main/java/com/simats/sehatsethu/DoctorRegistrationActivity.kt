package com.simats.sehatsethu

import android.content.ContentResolver
import android.content.Intent
import android.database.Cursor
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.Editable
import android.text.TextWatcher
import android.util.Base64
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import android.widget.PopupWindow
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

class DoctorRegistrationActivity : BaseActivity() {

    private lateinit var role: Role

    private lateinit var ivBack: ImageView
    private lateinit var progressBar: ProgressBar
    private lateinit var btnContinue: MaterialButton

    private lateinit var etFullName: TextInputEditText
    private lateinit var etSpecialization: TextInputEditText
    private lateinit var etRegNo: TextInputEditText
    private lateinit var etHospital: TextInputEditText
    private lateinit var etExperience: TextInputEditText
    private var etPhone: TextInputEditText? = null

    private var tvDoc1Status: TextView? = null
    private var tvDoc2Status: TextView? = null
    private var tvDoc3Status: TextView? = null

    private var btnUploadMedical: MaterialButton? = null
    private var btnUploadAadhaar: MaterialButton? = null
    private var btnUploadMbbs: MaterialButton? = null

    private var uriMedical: Uri? = null
    private var uriAadhaar: Uri? = null
    private var uriMbbs: Uri? = null

    private enum class DocType(val api: String) {
        MEDICAL("MEDICAL_LICENSE"),
        AADHAAR("AADHAAR"),
        MBBS("MBBS_CERT")
    }

    private data class Spec(val key: String, val label: String)

    /**
     * MUST match Patient SelectSpeciality stable keys.
     * We show LABEL to user, but we store/send KEY to backend/DB.
     */
    private fun specs(): List<Spec> = listOf(
        Spec("GENERAL_PHYSICIAN", getString(R.string.speciality_general)),
        Spec("CARDIOLOGY", getString(R.string.speciality_heart)),
        Spec("NEUROLOGY", getString(R.string.speciality_brain)),
        Spec("ORTHOPEDICS", getString(R.string.speciality_bones)),
        Spec("OPHTHALMOLOGY", getString(R.string.speciality_eyes)),
        Spec("PEDIATRICS", getString(R.string.speciality_child)),
        Spec("DERMATOLOGY", getString(R.string.speciality_skin)),
        Spec("PULMONOLOGY", getString(R.string.speciality_lungs)),
        Spec("DIABETOLOGY", getString(R.string.speciality_diabetes)),
        Spec("FEVER_CLINIC", getString(R.string.speciality_fever)),
        Spec("GENERAL_MEDICINE", getString(R.string.speciality_medicine)),
        Spec("EMERGENCY", getString(R.string.speciality_emergency)),
    )

    // Selected specialization MUST come only from clicked suggestion
    private var selectedSpecKey: String? = null
    private var selectedSpecLabel: String? = null

    // Custom dropdown
    private lateinit var specDropdown: SpecDropdownPopup
    private var settingSpecByClick = false

    private val pickMedical = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            uriMedical = uri
            tvDoc1Status?.text = getString(R.string.doc_selected_format, displayName(uri) ?: getString(R.string.selected))
            updateContinueEnabled()
        }
    }

    private val pickAadhaar = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            uriAadhaar = uri
            tvDoc2Status?.text = getString(R.string.doc_selected_format, displayName(uri) ?: getString(R.string.selected))
            updateContinueEnabled()
        }
    }

    private val pickMbbs = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            uriMbbs = uri
            tvDoc3Status?.text = getString(R.string.doc_selected_format, displayName(uri) ?: getString(R.string.selected))
            updateContinueEnabled()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_doctor_registration)
        supportActionBar?.hide()

        role = RoleResolver.resolve(this, savedInstanceState)
        RoleResolver.persist(this, role)

        ivBack = findViewById(R.id.ivBack)
        progressBar = findViewById(R.id.progressBar)
        btnContinue = findViewById(R.id.btnContinue)

        etFullName = findViewById(R.id.etFullName)
        etSpecialization = findViewById(R.id.etSpecialization)
        etRegNo = findViewById(R.id.etRegNo)
        etHospital = findViewById(R.id.etHospital)
        etExperience = findViewById(R.id.etExperience)
        etPhone = runCatching { findViewById<TextInputEditText>(R.id.etPhone) }.getOrNull()

        tvDoc1Status = runCatching { findViewById<TextView>(R.id.tvDoc1Status) }.getOrNull()
        tvDoc2Status = runCatching { findViewById<TextView>(R.id.tvDoc2Status) }.getOrNull()
        tvDoc3Status = runCatching { findViewById<TextView>(R.id.tvDoc3Status) }.getOrNull()

        btnUploadMedical = runCatching { findViewById<MaterialButton>(R.id.btnUploadMedicalLicense) }.getOrNull()
        btnUploadAadhaar = runCatching { findViewById<MaterialButton>(R.id.btnUploadAadhaar) }.getOrNull()
        btnUploadMbbs = runCatching { findViewById<MaterialButton>(R.id.btnUploadMbbs) }.getOrNull()

        progressBar.progress = 50

        tvDoc1Status?.text = getString(R.string.doc_not_uploaded)
        tvDoc2Status?.text = getString(R.string.doc_not_uploaded)
        tvDoc3Status?.text = getString(R.string.doc_not_uploaded)

        btnUploadMedical?.setOnClickListener { pickMedical.launch(arrayOf("application/pdf", "image/*")) }
        btnUploadAadhaar?.setOnClickListener { pickAadhaar.launch(arrayOf("application/pdf", "image/*")) }
        btnUploadMbbs?.setOnClickListener { pickMbbs.launch(arrayOf("application/pdf", "image/*")) }

        // Back behavior (your rule)
        ivBack.setOnClickListener { handleBack() }
        onBackPressedDispatcher.addCallback(this) { handleBack() }

        // Dropdown init (shows LABELS)
        specDropdown = SpecDropdownPopup(
            anchor = etSpecialization,
            onPick = { pickedLabel ->
                val picked = specs().firstOrNull { it.label == pickedLabel }
                if (picked != null) {
                    settingSpecByClick = true
                    selectedSpecKey = picked.key
                    selectedSpecLabel = picked.label
                    etSpecialization.setText(picked.label)
                    etSpecialization.setSelection(picked.label.length)
                    settingSpecByClick = false
                }
                specDropdown.dismiss()
                updateContinueEnabled()
            }
        )

        setupSpecializationPicker()

        btnContinue.isEnabled = false
        updateContinueEnabled()

        btnContinue.setOnClickListener {
            val msg = validateBeforeSubmit()
            if (msg != null) {
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            submit()
        }

        val w = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { updateContinueEnabled() }
        }
        etFullName.addTextChangedListener(w)
        etRegNo.addTextChangedListener(w)
        etHospital.addTextChangedListener(w)
        etExperience.addTextChangedListener(w)
    }

    /**
     * Specialization:
     * - user can type
     * - dropdown shows filtered LABEL list
     * - ONLY clicked suggestion becomes valid (stores KEY)
     * - if user edits text after selecting, selection resets (KEY cleared)
     */
    private fun setupSpecializationPicker() {
        etSpecialization.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (settingSpecByClick) return

                val text = s?.toString().orEmpty()

                // User edited => invalidate selected KEY unless exact label matches
                if (selectedSpecLabel != null && text != selectedSpecLabel) {
                    selectedSpecKey = null
                    selectedSpecLabel = null
                }

                showSpecSuggestions(text)
                updateContinueEnabled()
            }
        })

        etSpecialization.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) specDropdown.dismiss()
            else showSpecSuggestions(etSpecialization.text?.toString().orEmpty())
        }

        etSpecialization.setOnClickListener {
            showSpecSuggestions(etSpecialization.text?.toString().orEmpty())
        }
    }

    private fun showSpecSuggestions(query: String) {
        val q = query.trim().lowercase(Locale.getDefault())
        val all = specs().map { it.label }

        val filtered = if (q.isBlank()) {
            all
        } else {
            all.filter { it.lowercase(Locale.getDefault()).contains(q) }
                .sortedBy {
                    val s = it.lowercase(Locale.getDefault())
                    when {
                        s == q -> 0
                        s.startsWith(q) -> 1
                        else -> 2
                    }
                }
        }.take(8)

        if (filtered.isEmpty()) {
            specDropdown.dismiss()
            return
        }
        specDropdown.show(filtered)
    }

    private fun updateContinueEnabled() {
        val fullName = etFullName.text?.toString()?.trim().orEmpty()
        val reg = etRegNo.text?.toString()?.trim().orEmpty()
        val hosp = etHospital.text?.toString()?.trim().orEmpty()
        val expText = etExperience.text?.toString()?.trim().orEmpty()
        val expOk = expText.isNotBlank() && (expText.toIntOrNull() ?: -1) >= 0

        val specOk = !selectedSpecKey.isNullOrBlank() // MUST be clicked
        val medicalOk = uriMedical != null // REQUIRED

        btnContinue.isEnabled =
            fullName.isNotBlank() &&
                    reg.isNotBlank() &&
                    hosp.isNotBlank() &&
                    expOk &&
                    specOk &&
                    medicalOk
    }

    private fun validateBeforeSubmit(): String? {
        val fullName = etFullName.text?.toString()?.trim().orEmpty()
        val reg = etRegNo.text?.toString()?.trim().orEmpty()
        val hosp = etHospital.text?.toString()?.trim().orEmpty()
        val expText = etExperience.text?.toString()?.trim().orEmpty()

        if (fullName.isBlank()) return getString(R.string.err_enter_full_name)
        if (!FieldValidator.isValidName(fullName)) return "Name must contain only alphabets."
        val phone = etPhone?.text?.toString()?.trim().orEmpty()
        if (phone.isNotBlank() && !FieldValidator.isValidIndianPhone(phone)) return FieldValidator.PHONE_HINT
        if (etSpecialization.text?.toString()?.trim().isNullOrBlank()) return getString(R.string.err_enter_specialization)
        if (selectedSpecKey.isNullOrBlank()) return getString(R.string.err_pick_specialization_from_list)
        if (reg.isBlank()) return getString(R.string.err_enter_reg_no)
        if (hosp.isBlank()) return getString(R.string.err_enter_hospital)
        if (expText.isBlank()) return getString(R.string.err_enter_experience)
        if ((expText.toIntOrNull() ?: -1) < 0) return getString(R.string.err_enter_valid_experience)
        if (uriMedical == null) return getString(R.string.err_upload_medical_license_required)

        return null
    }

    private fun handleBack() {
        if (!isTaskRoot) {
            goBack()
            return
        }

        runCatching { AppPrefs.setToken(this, "") }
        runCatching { AppPrefs.setDoctorApplicationNo(this, "") }

        RoleResolver.persist(this, role)

        val i = RoleResolver.putRole(Intent(this, LoginActivity::class.java), role).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(i)
        finish()
        overridePendingTransition(0, 0)
    }

    private fun submit() {
        val token = AppPrefs.getToken(this).orEmpty()
        if (token.isBlank()) {
            Toast.makeText(this, getString(R.string.please_login_again), Toast.LENGTH_SHORT).show()
            handleBack()
            return
        }

        val fullName = etFullName.text?.toString()?.trim().orEmpty()
        val reg = etRegNo.text?.toString()?.trim().orEmpty()
        val hosp = etHospital.text?.toString()?.trim().orEmpty()
        val exp = etExperience.text?.toString()?.trim().orEmpty().toIntOrNull() ?: 0
        val phone = etPhone?.text?.toString()?.trim().orEmpty().ifBlank { null }

        // IMPORTANT: send KEY to backend (not label)
        val specKey = selectedSpecKey?.trim().orEmpty()

        btnContinue.isEnabled = false

        lifecycleScope.launch {
            val docsJson = withContext(Dispatchers.IO) { buildDocumentsJsonOrNull() }
            if (docsJson == null) {
                btnContinue.isEnabled = true
                updateContinueEnabled()
                return@launch
            }

            val res = withContext(Dispatchers.IO) {
                AuthApi.submitDoctorProfile(
                    fullName = fullName,
                    specialization = specKey,
                    regNo = reg,
                    hospital = hosp,
                    experienceYears = exp,
                    token = token,
                    phone = phone,
                    documents = docsJson
                )
            }

            btnContinue.isEnabled = true
            updateContinueEnabled()

            val ok = res.ok && (res.json?.optBoolean("ok", false) == true)
            if (!ok) {
                Toast.makeText(
                    this@DoctorRegistrationActivity,
                    res.json?.optString("error") ?: res.errorMessage ?: "Failed",
                    Toast.LENGTH_SHORT
                ).show()
                return@launch
            }

            val appNo = res.json?.optString("application_no").orEmpty()
            if (appNo.isNotBlank()) AppPrefs.setDoctorApplicationNo(this@DoctorRegistrationActivity, appNo)

            runCatching { AppPrefs.setProfileCompleted(this@DoctorRegistrationActivity, true) }
            AppPrefs.setAdminVerificationStatus(this@DoctorRegistrationActivity, "UNDER_REVIEW")
            AppPrefs.setAdminVerificationReason(this@DoctorRegistrationActivity, null)

            val i = Intent(this@DoctorRegistrationActivity, VerificationPendingActivity::class.java)
            RoleResolver.putRole(i, Role.DOCTOR)
            goNext(i, finishThis = true)
        }
    }

    private fun buildDocumentsJsonOrNull(): JSONArray? {
        val out = JSONArray()
        val items = listOf(
            DocType.MEDICAL to uriMedical, // REQUIRED by validation
            DocType.AADHAAR to uriAadhaar,
            DocType.MBBS to uriMbbs
        )

        for ((type, uri) in items) {
            if (uri == null) continue

            val mime = (contentResolver.getType(uri) ?: "").lowercase().trim()
            val okType = mime == "application/pdf" ||
                    mime == "image/jpeg" || mime == "image/jpg" || mime == "image/png"

            if (mime.isBlank() || !okType) {
                runOnUiThread {
                    Toast.makeText(this, getString(R.string.err_doc_invalid_type), Toast.LENGTH_SHORT).show()
                }
                return null
            }

            val name = displayName(uri) ?: "${type.api}.file"
            val bytes = readAllBytes(uri) ?: run {
                runOnUiThread { Toast.makeText(this, getString(R.string.err_pick_doc), Toast.LENGTH_SHORT).show() }
                return null
            }

            if (bytes.isEmpty() || bytes.size > 5 * 1024 * 1024) {
                runOnUiThread { Toast.makeText(this, getString(R.string.err_doc_too_large), Toast.LENGTH_SHORT).show() }
                return null
            }

            val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)

            val o = JSONObject()
            o.put("doc_type", type.api)
            o.put("file_name", name)
            o.put("mime_type", mime)
            o.put("file_base64", b64)
            out.put(o)
        }

        return out
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

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(NavKeys.EXTRA_ROLE, role.id)
        super.onSaveInstanceState(outState)
    }

    /**
     * Custom dropdown popup (keyboard-safe)
     * Uses your existing layouts:
     *  - R.layout.popup_speciality_dropdown (must contain rvDropdown)
     *  - R.layout.item_speciality_dropdown_row (must contain tvRow)
     */
    private class SpecDropdownPopup(
        private val anchor: View,
        private val onPick: (String) -> Unit
    ) {
        private val ctx = anchor.context
        private val data = ArrayList<String>()

        private val content: View =
            LayoutInflater.from(ctx).inflate(R.layout.popup_speciality_dropdown, null, false)
        private val rv: RecyclerView = content.findViewById(R.id.rvDropdown)

        private val popup: PopupWindow = PopupWindow(
            content,
            anchor.width.coerceAtLeast(1),
            ViewGroup.LayoutParams.WRAP_CONTENT,
            false
        ).apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            isOutsideTouchable = true
            isTouchable = true
            elevation = 8f
            inputMethodMode = PopupWindow.INPUT_METHOD_NEEDED
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }

        private val adapter = DropdownAdapter(data) { picked -> onPick(picked) }

        init {
            rv.layoutManager = LinearLayoutManager(ctx)
            rv.adapter = adapter
        }

        fun show(items: List<String>) {
            data.clear()
            data.addAll(items)
            adapter.notifyDataSetChanged()

            popup.width = anchor.width

            if (!popup.isShowing) {
                popup.showAsDropDown(anchor, 0, dp(6))
            } else {
                popup.update(anchor, 0, dp(6), anchor.width, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
        }

        fun dismiss() {
            if (popup.isShowing) popup.dismiss()
        }

        private fun dp(v: Int): Int =
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), ctx.resources.displayMetrics).toInt()

        private class DropdownAdapter(
            private val items: List<String>,
            private val onClick: (String) -> Unit
        ) : RecyclerView.Adapter<DropdownVH>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DropdownVH {
                val v = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_speciality_dropdown_row, parent, false)
                return DropdownVH(v, onClick)
            }
            override fun getItemCount(): Int = items.size
            override fun onBindViewHolder(holder: DropdownVH, position: Int) = holder.bind(items[position])
        }

        private class DropdownVH(v: View, private val onClick: (String) -> Unit) : RecyclerView.ViewHolder(v) {
            private val tv: TextView = v.findViewById(R.id.tvRow)
            fun bind(text: String) {
                tv.text = text
                itemView.setOnClickListener { onClick(text) }
            }
        }
    }
}
