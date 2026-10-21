package com.simats.sehatsethu.roles.doctor

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.simats.sehatsethu.ApiConfig.BASE_URL
import com.simats.sehatsethu.AppPrefs
import com.simats.sehatsethu.LanguageChangeActivity
import com.simats.sehatsethu.R
import com.simats.sehatsethu.RoleSelectActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class DoctorProfileFragment : Fragment() {

    private val sp by lazy {
        requireContext().getSharedPreferences("doctor_profile_cache", 0)
    }

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        val v = i.inflate(R.layout.fragment_doctor_profile, c, false)

        v.findViewById<TextView>(R.id.tvTitle).text = getString(R.string.my_profile)

        val tvName = v.findViewById<TextView>(R.id.tvUserName)
        val tvEmail = v.findViewById<TextView>(R.id.tvUserEmail)
        val tvPhone = v.findViewById<TextView>(R.id.tvUserPhone)
        val tvLangValue = v.findViewById<TextView>(R.id.tvLanguageValue)

        val defaultName = getString(R.string.default_user_name)
        val defaultEmail = getString(R.string.default_user_email)
        val defaultPhone = getString(R.string.default_user_phone)

        tvName.text = defaultName
        tvEmail.text = defaultEmail
        tvPhone.text = defaultPhone
        tvLangValue.text = currentLanguageLabel(AppPrefs.getLang(requireContext()))

        val token = AppPrefs.getToken(requireContext()).orEmpty()
        val claims = if (token.isBlank()) null else decodeJwtPayload(token)

        val jwtName = claims?.optString("full_name")?.takeIf { it.trim().isNotEmpty() }
            ?: claims?.optString("name")?.takeIf { it.trim().isNotEmpty() }

        val jwtEmail = claims?.optString("email")?.takeIf { it.trim().isNotEmpty() }

        val cachedName = sp.getString("name", null)?.trim()?.takeIf { it.isNotEmpty() && it != defaultName }
        val cachedEmail = sp.getString("email", null)?.trim()?.takeIf { it.isNotEmpty() && it != defaultEmail }

        tvName.text = cachedName ?: jwtName ?: defaultName
        tvEmail.text = cachedEmail ?: jwtEmail ?: defaultEmail

        //  Fetch profile from server (full_name + email + phone)
        if (token.isNotBlank()) {
            lifecycleScope.launch {
                val (fullName, email, phone) = withContext(Dispatchers.IO) { fetchDoctorProfile(token) }

                val nameClean = (fullName ?: "").trim()
                if (nameClean.isNotEmpty()) {
                    sp.edit().putString("name", nameClean).apply()
                    tvName.text = nameClean
                }

                val emailClean = (email ?: "").trim()
                if (emailClean.isNotEmpty()) {
                    sp.edit().putString("email", emailClean).apply()
                    tvEmail.text = emailClean
                }

                val phoneClean = (phone ?: "").trim()
                if (phoneClean.isNotEmpty() && !phoneClean.equals("null", true)) {
                    sp.edit().putString("phone", phoneClean).apply()
                    tvPhone.visibility = View.VISIBLE
                    tvPhone.text = phoneClean
                } else {
                    sp.edit().remove("phone").apply()
                    tvPhone.visibility = View.GONE
                }
            }
        } else {
            tvPhone.visibility = View.GONE
        }

        // ---- Actions ----

        //  Same professional edit dialog as PatientProfile (dialog_patient_edit_profile)
        v.findViewById<View>(R.id.btnEditProfile).setOnClickListener {
            val curName = tvName.text?.toString().orEmpty()
            val curPhone = if (tvPhone.visibility == View.VISIBLE) tvPhone.text?.toString().orEmpty() else ""

            showEditProfileDialog(curName, curPhone) { newName, newPhoneDigits ->
                val t = AppPrefs.getToken(requireContext()).orEmpty()
                if (t.isBlank()) {
                    Toast.makeText(requireContext(), getString(R.string.please_login_again), Toast.LENGTH_SHORT).show()
                    return@showEditProfileDialog
                }

                viewLifecycleOwner.lifecycleScope.launch {
                    val (ok, err, phoneFromServer) = withContext(Dispatchers.IO) {
                        apiUpdateDoctorBasic(t, newName, newPhoneDigits)
                    }

                    if (!ok) {
                        Toast.makeText(requireContext(), err ?: getString(R.string.failed), Toast.LENGTH_SHORT).show()
                        return@launch
                    }

                    val finalPhone = (phoneFromServer ?: newPhoneDigits).trim()

                    sp.edit().putString("name", newName).apply()
                    tvName.text = newName.ifBlank { defaultName }

                    if (finalPhone.isNotEmpty() && !finalPhone.equals("null", true)) {
                        sp.edit().putString("phone", finalPhone).apply()
                        tvPhone.visibility = View.VISIBLE
                        tvPhone.text = finalPhone
                    } else {
                        sp.edit().remove("phone").apply()
                        tvPhone.visibility = View.GONE
                    }

                    Toast.makeText(requireContext(), getString(R.string.profile_updated), Toast.LENGTH_SHORT).show()
                }
            }
        }

        v.findViewById<View>(R.id.btnShareApp).setOnClickListener {
            val pkg = requireContext().packageName
            val text = getString(R.string.share_app_text, pkg)

            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, getString(R.string.app_name))
                putExtra(Intent.EXTRA_TEXT, text)
            }

            runCatching {
                startActivity(Intent.createChooser(intent, getString(R.string.share_app)))
            }.onFailure {
                Toast.makeText(requireContext(), getString(R.string.unable_to_share), Toast.LENGTH_SHORT).show()
            }
        }

        v.findViewById<View>(R.id.rowChangeLanguage).setOnClickListener {
            startActivity(Intent(requireContext(), LanguageChangeActivity::class.java))
        }

        v.findViewById<View>(R.id.rowNotifications).setOnClickListener {
            openAppNotificationSettings()
        }

        v.findViewById<View>(R.id.rowAvailability).setOnClickListener {
            startActivity(Intent(requireContext(), DoctorsAvailabilityActivity::class.java))
        }

        v.findViewById<View>(R.id.rowPrivacy).setOnClickListener {
            showInfo(
                title = getString(R.string.privacy_security),
                msg = getString(R.string.privacy_info_text)
            )
        }

        v.findViewById<View>(R.id.rowHelp).setOnClickListener {
            contactSupport()
        }

        //  Same custom logout dialog as PatientProfile (dialog_logout_confirm)
        v.findViewById<View>(R.id.rowLogout).setOnClickListener {
            showLogoutDialog()
        }

        return v
    }

    override fun onResume() {
        super.onResume()
        view?.findViewById<TextView?>(R.id.tvLanguageValue)
            ?.text = currentLanguageLabel(AppPrefs.getLang(requireContext()))
    }

    //  GET: expects { ok:true, profile:{ full_name,email,phone } }
    private fun fetchDoctorProfile(token: String): Triple<String?, String?, String?> {
        return try {
            val url = URL(BASE_URL + "profile/doctor_get.php")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 12000
                readTimeout = 12000
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("Accept", "application/json")
            }

            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.readText().orEmpty()

            val json = runCatching { JSONObject(body) }.getOrNull() ?: return Triple(null, null, null)
            if (json.optBoolean("ok", false) != true) return Triple(null, null, null)

            val p = json.optJSONObject("profile") ?: return Triple(null, null, null)

            val fullName = p.optString("full_name", "").trim().let { if (it.isNotEmpty()) it else null }
            val email = p.optString("email", "").trim().let { if (it.isNotEmpty()) it else null }
            val phoneClean = p.optString("phone", "").trim()
            val phone = if (phoneClean.isNotEmpty() && !phoneClean.equals("null", true)) phoneClean else null

            Triple(fullName, email, phone)
        } catch (_: Throwable) {
            Triple(null, null, null)
        }
    }

    //  POST: same style as patient_update_basic, but for doctor
    // If your backend file name differs, change only the URL path.
    private fun apiUpdateDoctorBasic(
        token: String,
        fullName: String,
        phoneDigits: String
    ): Triple<Boolean, String?, String?> {
        return try {
            val payload = JSONObject().apply {
                put("full_name", fullName)
                put("phone", phoneDigits)
            }

            val url = URL(BASE_URL + "profile/doctor_update_basic.php")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 12000
                readTimeout = 12000
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
            }

            conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.readText().orEmpty()

            val json = runCatching { JSONObject(body) }.getOrNull() ?: return Triple(false, "Invalid response", null)
            val ok = json.optBoolean("ok", false) == true
            if (!ok) return Triple(false, json.optString("error").ifBlank { "Failed (HTTP $code)" }, null)

            // accept either "profile" or "user" to be resilient
            val p = json.optJSONObject("profile") ?: json.optJSONObject("user")
            val phone = p?.optString("phone")?.trim()

            Triple(true, null, phone)
        } catch (t: Throwable) {
            Triple(false, t.message, null)
        }
    }

    //  Custom Logout Dialog (same as PatientProfile)
    private fun showLogoutDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_logout_confirm, null, false)

        val dlg = AlertDialog.Builder(requireContext())
            .setView(dialogView)
            .create()

        dlg.window?.setBackgroundDrawableResource(android.R.color.transparent)

        dialogView.findViewById<View>(R.id.btnCancel).setOnClickListener { dlg.dismiss() }
        dialogView.findViewById<View>(R.id.btnLogout).setOnClickListener {
            dlg.dismiss()
            AppPrefs.setToken(requireContext(), "")
            startActivity(Intent(requireContext(), RoleSelectActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            })
        }

        dlg.show()
    }

    //  Custom Edit Dialog (same as PatientProfile) using dialog_patient_edit_profile
    private fun showEditProfileDialog(
        currentName: String,
        currentPhone: String,
        onSave: (name: String, phoneDigits: String) -> Unit
    ) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_patient_edit_profile, null, false)

        val tilName = dialogView.findViewById<TextInputLayout>(R.id.tilName)
        val tilPhone = dialogView.findViewById<TextInputLayout>(R.id.tilPhone)
        val etName = dialogView.findViewById<TextInputEditText>(R.id.etName)
        val etPhone = dialogView.findViewById<TextInputEditText>(R.id.etPhone)

        etName.setText(currentName)
        etPhone.setText(currentPhone)

        val dlg = AlertDialog.Builder(requireContext())
            .setView(dialogView)
            .create()

        dlg.window?.setBackgroundDrawableResource(android.R.color.transparent)

        dialogView.findViewById<View>(R.id.btnCancel).setOnClickListener { dlg.dismiss() }
        dialogView.findViewById<View>(R.id.btnSave).setOnClickListener {
            tilName.error = null
            tilPhone.error = null

            val name = etName.text?.toString()?.trim().orEmpty()
            val phone = etPhone.text?.toString()?.trim().orEmpty()

            if (name.isBlank()) {
                tilName.error = getString(R.string.err_fullname_required)
                return@setOnClickListener
            }

            val digits = phone.replace(Regex("\\D+"), "")
            if (digits.length !in 10..15) {
                tilPhone.error = getString(R.string.invalid_phone)
                return@setOnClickListener
            }

            dlg.dismiss()
            onSave(name, digits)
        }

        dlg.show()
    }

    private fun openAppNotificationSettings() {
        val ctx = requireContext()
        val intent = Intent().apply {
            action = "android.settings.APP_NOTIFICATION_SETTINGS"
            putExtra("android.provider.extra.APP_PACKAGE", ctx.packageName)
        }
        runCatching { startActivity(intent) }.onFailure {
            showInfo(getString(R.string.notifications), getString(R.string.coming_soon))
        }
    }

    private fun contactSupport() {
        val supportEmail = getString(R.string.support_email)
        val subject = getString(R.string.support_email_subject)

        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("mailto:$supportEmail")
            putExtra(Intent.EXTRA_SUBJECT, subject)
        }

        runCatching { startActivity(intent) }.onFailure {
            Toast.makeText(requireContext(), getString(R.string.no_email_app), Toast.LENGTH_SHORT).show()
        }
    }

    private fun showInfo(title: String, msg: String) {
        AlertDialog.Builder(requireContext())
            .setTitle(title)
            .setMessage(msg)
            .setPositiveButton(getString(R.string.ok), null)
            .show()
    }

    private fun currentLanguageLabel(lang: String?): String {
        return when (lang) {
            "hi" -> getString(R.string.lang_hindi)
            "ta" -> getString(R.string.lang_tamil)
            "te" -> getString(R.string.lang_telugu)
            "pa" -> getString(R.string.lang_punjabi)
            else -> getString(R.string.lang_english)
        }
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
