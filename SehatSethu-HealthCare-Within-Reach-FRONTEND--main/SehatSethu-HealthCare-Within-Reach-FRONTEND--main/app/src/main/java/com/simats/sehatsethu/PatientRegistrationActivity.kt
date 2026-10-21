package com.simats.sehatsethu

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PatientRegistrationActivity : BaseActivity() {

    private lateinit var role: Role

    private lateinit var ivBack: ImageView
    private lateinit var btnComplete: MaterialButton

    private lateinit var etFullName: TextInputEditText
    private lateinit var etPhone: TextInputEditText
    private lateinit var etAge: TextInputEditText
    private lateinit var etVillage: TextInputEditText
    private lateinit var etDistrict: TextInputEditText

    private var tilFullName: TextInputLayout? = null
    private var tilPhone: TextInputLayout? = null
    private var tilAge: TextInputLayout? = null
    private var tilVillage: TextInputLayout? = null
    private var tilDistrict: TextInputLayout? = null

    private lateinit var cardMale: MaterialCardView
    private lateinit var cardFemale: MaterialCardView
    private lateinit var cardOther: MaterialCardView

    private var gender: String? = null
    private var isSubmitting = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_patient_registration)
        supportActionBar?.hide()

        role = RoleResolver.resolve(this, savedInstanceState)
        RoleResolver.persist(this, role)

        ivBack = findViewById(R.id.ivBack)
        btnComplete = findViewById(R.id.btnComplete)

        etFullName = findViewById(R.id.etFullName)
        etPhone = findViewById(R.id.etPhone)
        etAge = findViewById(R.id.etAge)
        etVillage = findViewById(R.id.etVillage)
        etDistrict = findViewById(R.id.etDistrict)

        tilFullName = runCatching { findViewById<TextInputLayout>(R.id.tilFullName) }.getOrNull()
        tilPhone = runCatching { findViewById<TextInputLayout>(R.id.tilPhone) }.getOrNull()
        tilAge = runCatching { findViewById<TextInputLayout>(R.id.tilAge) }.getOrNull()
        tilVillage = runCatching { findViewById<TextInputLayout>(R.id.tilVillage) }.getOrNull()
        tilDistrict = runCatching { findViewById<TextInputLayout>(R.id.tilDistrict) }.getOrNull()

        cardMale = findViewById(R.id.cardMale)
        cardFemale = findViewById(R.id.cardFemale)
        cardOther = findViewById(R.id.cardOther)

        ivBack.setOnClickListener { goBack() }

        cardMale.setOnClickListener { selectGender("MALE") }
        cardFemale.setOnClickListener { selectGender("FEMALE") }
        cardOther.setOnClickListener { selectGender("OTHER") }

        attachClearErrorOnType(etFullName, tilFullName)
        attachClearErrorOnType(etPhone, tilPhone)
        attachClearErrorOnType(etAge, tilAge)
        attachClearErrorOnType(etVillage, tilVillage)
        attachClearErrorOnType(etDistrict, tilDistrict)

        btnComplete.setOnClickListener { submit() }
    }

    private fun attachClearErrorOnType(et: TextInputEditText, til: TextInputLayout?) {
        et.addTextChangedListener(SimpleTextWatcher { til?.error = null })
        et.setOnFocusChangeListener { _, hasFocus -> if (hasFocus) til?.error = null }
    }

    private fun selectGender(g: String) {
        gender = g
        fun setSel(card: MaterialCardView, sel: Boolean) {
            card.strokeWidth = if (sel) dp(2) else dp(1)
            card.setStrokeColor(if (sel) 0xFF059669.toInt() else 0xFFE2E8F0.toInt())
            card.cardElevation = if (sel) dp(2).toFloat() else 0f
        }
        setSel(cardMale, g == "MALE")
        setSel(cardFemale, g == "FEMALE")
        setSel(cardOther, g == "OTHER")
    }

    private fun normalizePhone(input: String): String {
        // keep digits and optional leading +
        val trimmed = input.trim()
        var cleaned = trimmed.replace(Regex("[^0-9+]"), "")
        // avoid "+" in middle
        if (cleaned.count { it == '+' } > 1) cleaned = cleaned.replace("+", "")
        if (cleaned.contains("+") && !cleaned.startsWith("+")) cleaned = cleaned.replace("+", "")
        return cleaned
    }

    private fun isValidPhone(normalized: String): Boolean {
        // Accept:
        // - 10 digits (India)
        // - or +<countrycode> with total digits 10..15
        val digitsOnly = normalized.replace("+", "")
        if (!digitsOnly.matches(Regex("^\\d{10,15}$"))) return false
        // if no + then must be 10 digits
        if (!normalized.startsWith("+") && digitsOnly.length != 10) return false
        return true
    }

    private fun submit() {
        if (isSubmitting) return

        val token = AppPrefs.getToken(this).orEmpty()
        if (token.isBlank()) {
            toast(getString(R.string.please_login_again))
            return
        }

        tilFullName?.error = null
        tilPhone?.error = null
        tilAge?.error = null
        tilVillage?.error = null
        tilDistrict?.error = null

        val fullName = etFullName.text?.toString()?.trim().orEmpty()
        val phoneRaw = etPhone.text?.toString().orEmpty()
        val phone = normalizePhone(phoneRaw)

        val ageStr = etAge.text?.toString()?.trim().orEmpty()
        val villageTown = etVillage.text?.toString()?.trim().orEmpty()
        val district = etDistrict.text?.toString()?.trim().orEmpty()
        val gen = gender?.trim()

        if (fullName.isBlank()) {
            tilFullName?.error = getString(R.string.err_fullname_required)
            etFullName.requestFocus()
            return
        }

        if (!FieldValidator.isValidName(fullName)) {
            tilFullName?.error = "Name must contain only alphabets."
            etFullName.requestFocus()
            return
        }

        if (phone.isBlank()) {
            tilPhone?.error = getString(R.string.err_phone_required)
            etPhone.requestFocus()
            return
        }

        if (!FieldValidator.isValidIndianPhone(phone)) {
            tilPhone?.error = FieldValidator.PHONE_HINT
            etPhone.requestFocus()
            return
        }

        if (gen.isNullOrBlank()) {
            toast(getString(R.string.err_gender_required))
            return
        }

        val age = ageStr.toIntOrNull()
        if (age == null || age !in 1..120) {
            tilAge?.error = getString(R.string.err_age_invalid)
            etAge.requestFocus()
            return
        }

        if (villageTown.isBlank()) {
            tilVillage?.error = getString(R.string.err_village_required)
            etVillage.requestFocus()
            return
        }

        if (district.isBlank()) {
            tilDistrict?.error = getString(R.string.err_district_required)
            etDistrict.requestFocus()
            return
        }

        setLoading(true)

        lifecycleScope.launch {
            val res = withContext(Dispatchers.IO) {
                AuthApi.completePatientProfile(
                    fullName = fullName,
                    gender = gen,
                    age = age,
                    villageTown = villageTown,
                    district = district,
                    phone = phone, //  NEW
                    token = token
                )
            }

            setLoading(false)

            val ok = res.ok && (res.json?.optBoolean("ok", false) == true)
            if (!ok) {
                toast(res.json?.optString("error") ?: res.errorMessage ?: getString(R.string.failed))
                return@launch
            }

            val profileCompleted = (res.json?.optInt("profile_completed", 0) == 1)
            val st = res.json?.optString("admin_verification_status")?.trim()?.uppercase()

            AppPrefs.setProfileCompleted(this@PatientRegistrationActivity, profileCompleted)
            AppPrefs.setAdminVerificationStatus(this@PatientRegistrationActivity, st ?: "PENDING")
            AppPrefs.setAdminVerificationReason(this@PatientRegistrationActivity, null)

            val next = Intent(
                this@PatientRegistrationActivity,
                Class.forName("com.simats.sehatsethu.roles.patient.PatientUploadImageActivity")
            )
            RoleResolver.putRole(next, Role.PATIENT)
            goNext(next, finishThis = true)
        }
    }

    private fun setLoading(loading: Boolean) {
        isSubmitting = loading
        btnComplete.isEnabled = !loading
        btnComplete.alpha = if (loading) 0.7f else 1f
        findViewById<View>(R.id.root)?.isEnabled = !loading
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()
}
