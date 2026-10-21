package com.simats.sehatsethu.roles.pharmacist

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.simats.sehatsethu.AppPrefs
import com.simats.sehatsethu.ApiConfig
import com.simats.sehatsethu.LanguageChangeActivity
import com.simats.sehatsethu.R
import com.simats.sehatsethu.RoleSelectActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class PharmacistProfileFragment : Fragment() {

    private val sp by lazy {
        requireContext().getSharedPreferences("pharmacist_profile_cache", 0)
    }

    private fun buildUrl(path: String): String {
        val base = ApiConfig.BASE_URL.trim()
        val b = if (base.endsWith("/")) base else "$base/"
        return b + path.trimStart('/')
    }

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        val v = i.inflate(R.layout.fragment_pharmacist_profile, c, false)

        // Title
        v.findViewById<TextView>(R.id.tvTitle).text = getString(R.string.my_profile)

        // Header card views
        val tvNameOrPharmacy = v.findViewById<TextView>(R.id.tvPharmacyName)
        val tvEmail = v.findViewById<TextView>(R.id.tvUserEmail)
        val tvPhone = v.findViewById<TextView>(R.id.tvPhone)

        // Settings value
        val tvLangValue = v.findViewById<TextView>(R.id.tvLanguageValue)

        // Defaults (never show dummy phone as real value)
        tvNameOrPharmacy.text = getString(R.string.default_pharmacy_name)
        tvEmail.text = getString(R.string.default_user_email)
        tvPhone.text = getString(R.string.default_user_phone)

        // Language label
        tvLangValue.text = currentLanguageLabel(AppPrefs.getLang(requireContext()))

        // Apply cached override first (fast UI), then refresh from DB
        applyCacheIfAny(tvNameOrPharmacy, tvPhone)
        fetchProfileFromDb(tvNameOrPharmacy, tvEmail, tvPhone)

        // --- ACTIONS ---

        // Edit Profile (custom dialog)
        v.findViewById<View>(R.id.btnEditProfile).setOnClickListener {
            showEditProfileDialog(
                currentName = tvNameOrPharmacy.text?.toString().orEmpty(),
                currentPhone = tvPhone.text?.toString().orEmpty()
            ) { newName, newPhone ->
                updateProfileToDb(
                    newName = newName,
                    newPhone = newPhone,
                    onSuccess = {
                        cacheNamePhone(newName, newPhone)
                        tvNameOrPharmacy.text = newName.ifBlank { getString(R.string.default_pharmacy_name) }
                        tvPhone.text = newPhone.ifBlank { getString(R.string.default_user_phone) }
                        Toast.makeText(requireContext(), getString(R.string.profile_updated), Toast.LENGTH_SHORT).show()
                    }
                )
            }
        }

        // Share App (NO BuildConfig)
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

        // Change Language
        v.findViewById<View>(R.id.rowChangeLanguage).setOnClickListener {
            startActivity(Intent(requireContext(), LanguageChangeActivity::class.java))
        }

        // Notifications
        v.findViewById<View>(R.id.rowNotifications).setOnClickListener {
            openAppNotificationSettings()
        }

        // Privacy
        v.findViewById<View>(R.id.rowPrivacy).setOnClickListener {
            showInfoDialog(
                title = getString(R.string.privacy_security),
                msg = getString(R.string.privacy_info_text)
            )
        }

        // Help
        v.findViewById<View>(R.id.rowHelp).setOnClickListener {
            contactSupport()
        }

        // Logout (custom card dialog)
        v.findViewById<View>(R.id.rowLogout).setOnClickListener {
            confirmLogout()
        }

        return v
    }

    // ---------------------------
    // DB Fetch / Update
    // ---------------------------

    private fun fetchProfileFromDb(tvName: TextView, tvEmail: TextView, tvPhone: TextView) {
        val token = AppPrefs.getToken(requireContext()).orEmpty()
        if (token.isBlank()) return

        viewLifecycleOwner.lifecycleScope.launch {
            val res = withContext(Dispatchers.IO) {
                httpGetJson(
                    urlStr = buildUrl("profile/pharmacist_get.php"),
                    token = token
                )
            }

            val ok = res?.optBoolean("ok", false) == true
            if (!ok) {
                // keep cached/default UI (silent fail)
                return@launch
            }

            val data = res.optJSONObject("data") ?: return@launch
            val name = data.optString("full_name").trim()
            val email = data.optString("email").trim()
            val phone = data.optString("phone").trim()

            // Update UI
            tvName.text = name.ifBlank { getString(R.string.default_pharmacy_name) }
            tvEmail.text = email.ifBlank { getString(R.string.default_user_email) }
            tvPhone.text = phone.ifBlank { getString(R.string.default_user_phone) }

            // Cache (avoid caching placeholder/default phone)
            cacheNamePhone(name, phone)
        }
    }

    private fun updateProfileToDb(newName: String, newPhone: String, onSuccess: () -> Unit) {
        val token = AppPrefs.getToken(requireContext()).orEmpty()
        if (token.isBlank()) {
            Toast.makeText(requireContext(), getString(R.string.please_login_again), Toast.LENGTH_SHORT).show()
            return
        }

        val name = newName.trim()
        val phone = newPhone.trim()

        // light validation
        if (name.isBlank()) {
            Toast.makeText(requireContext(), getString(R.string.err_fill_all_fields), Toast.LENGTH_SHORT).show()
            return
        }

        viewLifecycleOwner.lifecycleScope.launch {
            val body = JSONObject().apply {
                put("full_name", name)
                put("phone", phone)
            }

            val res = withContext(Dispatchers.IO) {
                httpPostJson(
                    urlStr = buildUrl("profile/pharmacist_update_basic.php"),
                    token = token,
                    body = body
                )
            }

            val ok = res?.optBoolean("ok", false) == true
            if (!ok) {
                val msg = res?.optString("error")?.takeIf { it.isNotBlank() } ?: getString(R.string.failed)
                Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
                return@launch
            }

            onSuccess()
        }
    }

    // ---------------------------
    // Cache helpers (no dummy phone)
    // ---------------------------

    private fun applyCacheIfAny(tvName: TextView, tvPhone: TextView) {
        val localName = sp.getString("name", null)?.takeIf { it.isNotBlank() }
        val localPhone = sp.getString("phone", null)?.takeIf { it.isNotBlank() }

        if (!localName.isNullOrBlank()) tvName.text = localName

        // do not show/cache placeholder default phone
        if (!localPhone.isNullOrBlank() && !isPlaceholderPhone(localPhone)) {
            tvPhone.text = localPhone
        }
    }

    private fun cacheNamePhone(name: String, phone: String) {
        val n = name.trim()
        val p = phone.trim()

        sp.edit().apply {
            if (n.isNotBlank()) putString("name", n) else remove("name")
            if (p.isNotBlank() && !isPlaceholderPhone(p)) putString("phone", p) else remove("phone")
        }.apply()
    }

    private fun isPlaceholderPhone(p: String): Boolean {
        val s = p.trim()
        val def = getString(R.string.default_user_phone).trim()
        return s.equals(def, ignoreCase = true) ||
                s == "9876543210" || s == "9999999999" || s == "0000000000"
    }

    // ---------------------------
    // Custom Dialogs
    // ---------------------------

    /**
     *  IMPORTANT FIX:
     * Your crash was because btnSave/btnCancel were NULL (IDs not found in dialog_edit_profile_simple.xml).
     * This makes it resilient: tries standard IDs first, then fallback IDs by name, and never crashes.
     */
    private fun findViewByIdName(root: View, vararg names: String): View? {
        val res = requireContext().resources
        val pkg = requireContext().packageName
        for (n in names) {
            val id = res.getIdentifier(n, "id", pkg)
            if (id != 0) {
                val v = root.findViewById<View>(id)
                if (v != null) return v
            }
        }
        return null
    }

    private fun showEditProfileDialog(
        currentName: String,
        currentPhone: String,
        onSave: (String, String) -> Unit
    ) {
        val view = layoutInflater.inflate(R.layout.dialog_edit_profile_simple, null, false)

        val etName = view.findViewById<EditText?>(R.id.etName)
        val etPhone = view.findViewById<EditText?>(R.id.etPhone)

        etName?.setText(if (currentName == getString(R.string.default_pharmacy_name)) "" else currentName)
        etPhone?.setText(if (isPlaceholderPhone(currentPhone)) "" else currentPhone)

        val d = androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setView(view)
            .create()

        // Try common IDs first, then fallback by name
        val btnCancel =
            view.findViewById<View?>(R.id.btnCancel)
                ?: findViewByIdName(view, "btnCancel", "tvCancel", "btnClose", "ivClose", "close", "cancel")

        val btnSave =
            view.findViewById<View?>(R.id.btnSave)
                ?: findViewByIdName(view, "btnSave", "btnUpdate", "btnOk", "btnSubmit", "save", "update")

        btnCancel?.setOnClickListener { d.dismiss() }

        btnSave?.setOnClickListener {
            val name = etName?.text?.toString()?.trim().orEmpty()
            val phone = etPhone?.text?.toString()?.trim().orEmpty()
            d.dismiss()
            onSave(name, phone)
        }

        d.show()
    }

    private fun confirmLogout() {
        val v = layoutInflater.inflate(R.layout.dialog_logout_confirm, null, false)

        val dialog = android.app.Dialog(requireContext())
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        dialog.setContentView(v)
        dialog.setCancelable(true)
        dialog.setCanceledOnTouchOutside(true)

        dialog.window?.apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
            decorView.setPadding(0, 0, 0, 0)
            setLayout(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setGravity(android.view.Gravity.CENTER)
            addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.45f)
        }

        v.findViewById<View>(R.id.btnCancel).setOnClickListener { dialog.dismiss() }
        v.findViewById<View>(R.id.btnLogout).setOnClickListener {
            dialog.dismiss()
            AppPrefs.setToken(requireContext(), "")
            startActivity(Intent(requireContext(), RoleSelectActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            })
        }

        dialog.show()
    }

    private fun showInfoDialog(title: String, msg: String) {
        val view = layoutInflater.inflate(R.layout.dialog_info_card, null, false)
        view.findViewById<TextView>(R.id.tvTitle).text = title
        view.findViewById<TextView>(R.id.tvMsg).text = msg

        val d = androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setView(view)
            .create()

        view.findViewById<View>(R.id.btnOk).setOnClickListener { d.dismiss() }
        d.show()
    }

    // ---------------------------
    // System actions
    // ---------------------------

    private fun openAppNotificationSettings() {
        val ctx = requireContext()
        val intent = Intent().apply {
            action = "android.settings.APP_NOTIFICATION_SETTINGS"
            putExtra("android.provider.extra.APP_PACKAGE", ctx.packageName)
        }
        runCatching { startActivity(intent) }.onFailure {
            showInfoDialog(getString(R.string.notifications), getString(R.string.coming_soon))
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

    private fun currentLanguageLabel(lang: String?): String {
        return when (lang) {
            "hi" -> getString(R.string.lang_hindi)
            "ta" -> getString(R.string.lang_tamil)
            "te" -> getString(R.string.lang_telugu)
            "pa" -> getString(R.string.lang_punjabi)
            else -> getString(R.string.lang_english)
        }
    }

    // ---------------------------
    // Networking (simple, reliable)
    // ---------------------------

    private fun httpGetJson(urlStr: String, token: String): JSONObject? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 25_000
                readTimeout = 25_000
                useCaches = false
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Authorization", "Bearer $token")
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val raw = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (raw.isBlank()) return null
            JSONObject(raw.trim().removePrefix("\uFEFF"))
        } catch (_: Throwable) {
            null
        } finally {
            try { conn?.disconnect() } catch (_: Throwable) {}
        }
    }

    private fun httpPostJson(urlStr: String, token: String, body: JSONObject): JSONObject? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 25_000
                readTimeout = 25_000
                useCaches = false
                doOutput = true
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Authorization", "Bearer $token")
            }
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val raw = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (raw.isBlank()) return null
            JSONObject(raw.trim().removePrefix("\uFEFF"))
        } catch (_: Throwable) {
            null
        } finally {
            try { conn?.disconnect() } catch (_: Throwable) {}
        }
    }
}
