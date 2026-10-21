package com.simats.sehatsethu.roles.patient

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.util.Log
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
import com.simats.sehatsethu.BuildConfig
import com.simats.sehatsethu.LanguageChangeActivity
import com.simats.sehatsethu.R
import com.simats.sehatsethu.RoleSelectActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class PatientProfileFragment : Fragment() {

    private val TAG = "PatientProfile"

    private val sp by lazy {
        requireContext().getSharedPreferences("patient_profile_cache", 0)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_patient_profile, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val tvTitle = view.findViewById<TextView?>(R.id.tvTitle)
        val tvNamePrimary = view.findViewById<TextView?>(R.id.tvName)
        val tvNameAlt = view.findViewById<TextView?>(R.id.tvUserName)
        val tvEmail = view.findViewById<TextView?>(R.id.tvUserEmail)
        val tvPhonePrimary = view.findViewById<TextView?>(R.id.tvPhone)
        val tvPhoneAlt = view.findViewById<TextView?>(R.id.tvUserPhone)
        val tvLangValue = view.findViewById<TextView?>(R.id.tvLanguageValue)

        val defaultName = getString(R.string.default_user_name)
        val defaultEmail = getString(R.string.default_user_email)
        val defaultPhone = getString(R.string.default_user_phone) // should be "—"

        tvTitle?.text = getString(R.string.my_profile)
        tvLangValue?.text = currentLanguageLabel(AppPrefs.getLang(requireContext()))

        val token = AppPrefs.getToken(requireContext()).orEmpty()
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "BASE_URL=$BASE_URL")
            Log.d(TAG, "token_present=${token.isNotBlank()}")
        }

        // quick fill from cache (but never show old dummy number if it exists)
        val cachedName = sp.getString("name", null)?.trim().takeIf { !it.isNullOrBlank() && it != defaultName }
        val cachedEmail = sp.getString("email", null)?.trim().takeIf { !it.isNullOrBlank() && it != defaultEmail }
        val cachedPhone = sp.getString("phone", null)?.trim()
            ?.takeIf { it.isNotBlank() && it != defaultPhone && it != "9876543210" }

        tvNamePrimary?.text = cachedName ?: defaultName
        tvNameAlt?.text = cachedName ?: defaultName
        tvEmail?.text = cachedEmail ?: defaultEmail
        tvPhonePrimary?.text = cachedPhone ?: defaultPhone
        tvPhoneAlt?.text = cachedPhone ?: defaultPhone

        // fetch server truth
        if (token.isNotBlank()) {
            viewLifecycleOwner.lifecycleScope.launch {
                val (user, err, httpCode, raw) = withContext(Dispatchers.IO) { apiGetPatientProfile(token) }

                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "patient_get http=$httpCode raw=$raw")
                }

                if (user == null) {
                    Toast.makeText(requireContext(), err ?: "Failed to load profile", Toast.LENGTH_LONG).show()
                    return@launch
                }

                val name = pickFirstNonBlank(
                    user.optString("full_name"),
                    user.optString("fullName"),
                    user.optString("name")
                )

                val email = pickFirstNonBlank(
                    user.optString("email")
                )

                val phone = pickFirstNonBlank(
                    user.optString("phone"),
                    user.optString("mobile")
                )?.takeIf { !it.equals("null", true) && it != "9876543210" }

                if (!name.isNullOrBlank()) {
                    sp.edit().putString("name", name).apply()
                    tvNamePrimary?.text = name
                    tvNameAlt?.text = name
                }

                if (!email.isNullOrBlank()) {
                    sp.edit().putString("email", email).apply()
                    tvEmail?.text = email
                }

                if (!phone.isNullOrBlank()) {
                    sp.edit().putString("phone", phone).apply()
                    tvPhonePrimary?.text = phone
                    tvPhoneAlt?.text = phone
                } else {
                    // phone missing/empty on server => show placeholder, not dummy
                    sp.edit().remove("phone").apply()
                    tvPhonePrimary?.text = defaultPhone
                    tvPhoneAlt?.text = defaultPhone
                }
            }
        }

        // edit profile
        view.findViewById<View?>(R.id.btnEditProfile)?.setOnClickListener {
            val curName = tvNamePrimary?.text?.toString().orEmpty().ifBlank { "" }
            val curPhone = tvPhonePrimary?.text?.toString().orEmpty().takeIf { it != defaultPhone }.orEmpty()

            showEditProfileDialog(curName, curPhone) { newName, newPhoneDigits ->
                val t = AppPrefs.getToken(requireContext()).orEmpty()
                if (t.isBlank()) {
                    Toast.makeText(requireContext(), getString(R.string.please_login_again), Toast.LENGTH_SHORT).show()
                    return@showEditProfileDialog
                }

                viewLifecycleOwner.lifecycleScope.launch {
                    val (ok, msg, phoneFromServer) = withContext(Dispatchers.IO) {
                        apiUpdatePatientBasic(t, newName, newPhoneDigits)
                    }
                    if (!ok) {
                        Toast.makeText(requireContext(), msg ?: getString(R.string.failed), Toast.LENGTH_LONG).show()
                        return@launch
                    }

                    val finalPhone = (phoneFromServer ?: newPhoneDigits).trim().ifBlank { defaultPhone }

                    sp.edit().putString("name", newName).apply()
                    if (finalPhone == defaultPhone) sp.edit().remove("phone").apply()
                    else sp.edit().putString("phone", finalPhone).apply()

                    tvNamePrimary?.text = newName
                    tvNameAlt?.text = newName
                    tvPhonePrimary?.text = finalPhone
                    tvPhoneAlt?.text = finalPhone

                    Toast.makeText(requireContext(), getString(R.string.profile_updated), Toast.LENGTH_SHORT).show()
                }
            }
        }

        // share
        view.findViewById<View?>(R.id.btnShareApp)?.setOnClickListener {
            val pkg = requireContext().packageName
            val text = getString(R.string.share_app_text, pkg)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, getString(R.string.app_name))
                putExtra(Intent.EXTRA_TEXT, text)
            }
            runCatching { startActivity(Intent.createChooser(intent, getString(R.string.share_app))) }
                .onFailure { Toast.makeText(requireContext(), getString(R.string.unable_to_share), Toast.LENGTH_SHORT).show() }
        }

        view.findViewById<View?>(R.id.vLang)?.setOnClickListener {
            startActivity(Intent(requireContext(), LanguageChangeActivity::class.java))
        }

        view.findViewById<View?>(R.id.vNotify)?.setOnClickListener { openAppNotificationSettings() }

        view.findViewById<View?>(R.id.vPrivacy)?.setOnClickListener {
            showInfo(getString(R.string.privacy_security), getString(R.string.privacy_info_text))
        }

        view.findViewById<View?>(R.id.vHelp)?.setOnClickListener { contactSupport() }

        view.findViewById<View?>(R.id.vLogout)?.setOnClickListener { showLogoutDialog() }
    }

    override fun onResume() {
        super.onResume()
        view?.findViewById<TextView?>(R.id.tvLanguageValue)
            ?.text = currentLanguageLabel(AppPrefs.getLang(requireContext()))
    }

    private fun base(): String = if (BASE_URL.endsWith("/")) BASE_URL else "$BASE_URL/"

    private fun apiGetPatientProfile(token: String): Quad<JSONObject?, String?, Int, String> {
        return try {
            val urlStr = base() + "profile/patient_get.php"
            if (BuildConfig.DEBUG) Log.d(TAG, "GET $urlStr")

            val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 12000
                readTimeout = 12000
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("Accept", "application/json")
            }

            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.readText().orEmpty()

            val json = runCatching { JSONObject(body) }.getOrNull()
                ?: return Quad(null, "Invalid JSON (HTTP $code)", code, body)

            if (json.optBoolean("ok", false) != true) {
                val err = json.optString("error").ifBlank { "Request failed (HTTP $code)" }
                return Quad(null, err, code, body)
            }

            // accept user OR data (some backends use different key)
            val user = json.optJSONObject("user") ?: json.optJSONObject("data")
            if (user == null) return Quad(null, "API ok but user missing", code, body)

            Quad(user, null, code, body)
        } catch (t: Throwable) {
            Quad(null, t.message ?: "Network error", -1, "")
        }
    }

    private fun apiUpdatePatientBasic(token: String, fullName: String, phoneDigits: String): Triple<Boolean, String?, String?> {
        return try {
            val payload = JSONObject().apply {
                put("full_name", fullName)
                put("phone", phoneDigits)
            }

            val urlStr = base() + "profile/patient_update_basic.php"
            if (BuildConfig.DEBUG) Log.d(TAG, "POST $urlStr body=$payload")

            val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
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

            val json = runCatching { JSONObject(body) }.getOrNull()
                ?: return Triple(false, "Invalid JSON (HTTP $code)", null)

            val ok = json.optBoolean("ok", false) == true
            if (!ok) return Triple(false, json.optString("error").ifBlank { "Update failed (HTTP $code)" }, null)

            val phone = (json.optJSONObject("user") ?: json.optJSONObject("data"))
                ?.optString("phone")?.trim()

            Triple(true, null, phone)
        } catch (t: Throwable) {
            Triple(false, t.message, null)
        }
    }

    private fun showLogoutDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_logout_confirm, null, false)
        val dlg = AlertDialog.Builder(requireContext()).setView(dialogView).create()
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

    private fun showEditProfileDialog(
        currentName: String,
        currentPhone: String,
        onSave: (name: String, phone: String) -> Unit
    ) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_patient_edit_profile, null, false)
        val tilName = dialogView.findViewById<TextInputLayout>(R.id.tilName)
        val tilPhone = dialogView.findViewById<TextInputLayout>(R.id.tilPhone)
        val etName = dialogView.findViewById<TextInputEditText>(R.id.etName)
        val etPhone = dialogView.findViewById<TextInputEditText>(R.id.etPhone)

        etName.setText(currentName)
        etPhone.setText(currentPhone)

        val dlg = AlertDialog.Builder(requireContext()).setView(dialogView).create()
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

    private fun pickFirstNonBlank(vararg xs: String?): String? {
        for (x in xs) {
            val v = x?.trim()
            if (!v.isNullOrBlank()) return v
        }
        return null
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
        } catch (_: Throwable) { null }
    }

    private fun fixBase64Url(s: String): String {
        var out = s.replace('-', '+').replace('_', '/')
        while (out.length % 4 != 0) out += "="
        return out
    }

    // simple holder
    data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)
}
