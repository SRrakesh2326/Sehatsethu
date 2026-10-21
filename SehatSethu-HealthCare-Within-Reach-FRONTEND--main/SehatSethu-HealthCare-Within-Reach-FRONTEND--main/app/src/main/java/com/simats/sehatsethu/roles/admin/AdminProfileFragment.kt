package com.simats.sehatsethu.roles.admin

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Base64
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import com.simats.sehatsethu.AppPrefs
import com.simats.sehatsethu.HelpSupportActivity
import com.simats.sehatsethu.LanguageChangeActivity
import com.simats.sehatsethu.R
import com.simats.sehatsethu.RoleSelectActivity
import org.json.JSONObject

class AdminProfileFragment : Fragment() {

    private val sp by lazy {
        requireContext().getSharedPreferences("admin_profile_cache", 0)
    }

    private var tvLangValue: TextView? = null
    private var tvName: TextView? = null
    private var tvEmail: TextView? = null
    private var tvPhone: TextView? = null

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        val v = i.inflate(R.layout.fragment_admin_profile, c, false)

        v.findViewById<TextView>(R.id.tvProfileTitle).text = getString(R.string.my_profile)

        tvName = v.findViewById(R.id.tvUserName)
        tvEmail = v.findViewById(R.id.tvUserEmail)
        tvPhone = v.findViewById(R.id.tvUserPhone)
        tvLangValue = v.findViewById(R.id.tvLanguageValue)

        tvName?.text = getString(R.string.default_user_name)
        tvEmail?.text = getString(R.string.default_user_email)
        tvPhone?.text = getString(R.string.default_user_phone)

        bindProfile()

        tvLangValue?.text = currentLanguageLabel(AppPrefs.getLang(requireContext()))

        v.findViewById<View>(R.id.btnEditProfile).setOnClickListener {
            showEditProfileDialog(
                currentName = tvName?.text?.toString().orEmpty(),
                currentPhone = tvPhone?.text?.toString().orEmpty()
            )
        }

        v.findViewById<View>(R.id.btnShareApp).setOnClickListener { shareApp() }

        v.findViewById<View>(R.id.itemLanguage).setOnClickListener {
            startActivity(Intent(requireContext(), LanguageChangeActivity::class.java))
        }

        v.findViewById<View>(R.id.itemNotifications).setOnClickListener {
            openAppNotificationSettings()
        }

        v.findViewById<View>(R.id.itemPrivacy).setOnClickListener {
            showInfo(
                title = getString(R.string.privacy_security),
                msg = getString(R.string.privacy_info_text)
            )
        }

        //  Help -> open Help & Support screen
        v.findViewById<View>(R.id.itemHelp).setOnClickListener {
            startActivity(Intent(requireContext(), HelpSupportActivity::class.java))
        }

        v.findViewById<View>(R.id.rowLogout).setOnClickListener {
            confirmLogout()
        }

        return v
    }

    override fun onResume() {
        super.onResume()
        tvLangValue?.text = currentLanguageLabel(AppPrefs.getLang(requireContext()))
    }

    private fun bindProfile() {
        val ctx = requireContext()

        val token = AppPrefs.getToken(ctx).orEmpty()
        val claims = if (token.isBlank()) null else decodeJwtPayload(token)

        val jwtName =
            claims?.optString("full_name")?.takeIf { it.isNotBlank() }
                ?: claims?.optString("name")?.takeIf { it.isNotBlank() }

        val jwtEmail = claims?.optString("email")?.takeIf { it.isNotBlank() }
        val jwtPhone = claims?.optString("phone")?.takeIf { it.isNotBlank() }

        val localName = sp.getString("name", null)?.trim().takeIf { !it.isNullOrBlank() }
        val localPhone = sp.getString("phone", null)?.trim().takeIf { !it.isNullOrBlank() }

        tvName?.text = localName ?: jwtName ?: getString(R.string.default_user_name)
        tvEmail?.text = jwtEmail ?: getString(R.string.default_user_email)
        tvPhone?.text = localPhone ?: jwtPhone ?: getString(R.string.default_user_phone)
    }

    private fun showEditProfileDialog(currentName: String, currentPhone: String) {
        val view = layoutInflater.inflate(R.layout.dialog_edit_profile_simple, null, false)
        val etName = view.findViewById<EditText>(R.id.etName)
        val etPhone = view.findViewById<EditText>(R.id.etPhone)

        etName.setText(currentName)
        etPhone.setText(currentPhone)

        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.edit_profile))
            .setView(view)
            .setNegativeButton(getString(R.string.cancel), null)
            .setPositiveButton(getString(R.string.save), null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = etName.text?.toString()?.trim().orEmpty()
                val phone = etPhone.text?.toString()?.trim().orEmpty()

                if (name.isNotBlank() && !com.simats.sehatsethu.FieldValidator.isValidName(name)) {
                    Toast.makeText(requireContext(), "Name must contain only alphabets.", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                if (phone.isNotBlank() && !com.simats.sehatsethu.FieldValidator.isValidIndianPhone(phone)) {
                    Toast.makeText(requireContext(), com.simats.sehatsethu.FieldValidator.PHONE_HINT, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                sp.edit()
                    .putString("name", name)
                    .putString("phone", phone)
                    .apply()

                tvName?.text = name.ifBlank { getString(R.string.default_user_name) }
                tvPhone?.text = phone.ifBlank { getString(R.string.default_user_phone) }

                Toast.makeText(requireContext(), getString(R.string.profile_updated), Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun shareApp() {
        val appId = requireContext().packageName
        val text = getString(R.string.share_app_text, appId)

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

    private fun openAppNotificationSettings() {
        val ctx = requireContext()

        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
        }

        val fallback = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${ctx.packageName}")
        }

        runCatching { startActivity(intent) }
            .onFailure { runCatching { startActivity(fallback) } }
    }

    private fun confirmLogout() {
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.logout_confirm_title))
            .setMessage(getString(R.string.logout_confirm_msg))
            .setNegativeButton(getString(R.string.cancel), null)
            .setPositiveButton(getString(R.string.logout)) { _, _ ->
                AppPrefs.setToken(requireContext(), "")
                startActivity(Intent(requireContext(), RoleSelectActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                })
            }
            .show()
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
