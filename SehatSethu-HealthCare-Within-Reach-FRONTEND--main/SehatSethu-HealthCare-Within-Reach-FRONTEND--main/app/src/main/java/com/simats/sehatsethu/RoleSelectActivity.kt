package com.simats.sehatsethu

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.AppCompatButton

class RoleSelectActivity : BaseActivity() {

    private var selectedRole: Role? = null

    private lateinit var rolesContainer: ViewGroup
    private lateinit var patientCard: View
    private lateinit var doctorCard: View
    private lateinit var pharmacistCard: View
    private lateinit var adminCard: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_role_select)
        supportActionBar?.hide()

        findViewById<TextView>(R.id.tv_title).text = getString(R.string.choose_role_title)
        findViewById<TextView>(R.id.tv_subtitle).text = getString(R.string.choose_role_subtitle)

        findViewById<ImageView>(R.id.btn_back).setOnClickListener {
            goBack(Intent(this, LanguageSelectActivity::class.java))
        }

        rolesContainer = findViewById(R.id.roles_container)

        if (rolesContainer.childCount >= 4) {
            patientCard = rolesContainer.getChildAt(0)
            doctorCard = rolesContainer.getChildAt(1)
            pharmacistCard = rolesContainer.getChildAt(2)
            adminCard = rolesContainer.getChildAt(3)
        } else {
            Toast.makeText(this, "Role cards missing in layout", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        setCardTexts(patientCard, R.string.role_patient, R.string.role_patient_desc)
        setCardTexts(doctorCard, R.string.role_doctor, R.string.role_doctor_desc)
        setCardTexts(pharmacistCard, R.string.role_pharmacist, R.string.role_pharmacist_desc)

        // Admin cannot self-register — credentials are added directly in the database.
        adminCard.visibility = android.view.View.GONE

        selectedRole = Role.fromId(AppPrefs.getRole(this)) ?: Role.PATIENT
        if (selectedRole == Role.ADMIN) selectedRole = Role.PATIENT // reset if somehow admin was pre-selected

        patientCard.setOnClickListener { select(Role.PATIENT) }
        doctorCard.setOnClickListener { select(Role.DOCTOR) }
        pharmacistCard.setOnClickListener { select(Role.PHARMACIST) }
        // adminCard has no click listener — it is hidden

        renderSelection()

        val continueBtn = findViewById<AppCompatButton>(R.id.btn_continue)
        continueBtn.text = getString(R.string.continue_btn)

        continueBtn.setOnClickListener {
            val role = selectedRole ?: run {
                Toast.makeText(this, getString(R.string.select_role_toast), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            // Block admin from being selected through any path
            if (role == Role.ADMIN) {
                Toast.makeText(this, "Admin registration is not allowed.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            RoleResolver.persist(this, role)

            val i = RoleResolver.putRole(Intent(this, LoginActivity::class.java), role)
            goNext(i, finishThis = false)
        }
    }

    private fun select(role: Role) {
        selectedRole = role
        renderSelection()
    }

    private fun renderSelection() {
        val selected = selectedRole
        applyCardState(patientCard, selected == Role.PATIENT, Role.PATIENT)
        applyCardState(doctorCard, selected == Role.DOCTOR, Role.DOCTOR)
        applyCardState(pharmacistCard, selected == Role.PHARMACIST, Role.PHARMACIST)
        applyCardState(adminCard, selected == Role.ADMIN, Role.ADMIN)
    }

    private fun applyCardState(card: View, isSelected: Boolean, role: Role) {
        if (isSelected) {
            val accent = roleAccentColor(role)
            card.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpF(16f)
                setColor(withAlpha(accent, 0.08f))
                setStroke(dpI(2f), withAlpha(accent, 0.85f))
            }
            card.alpha = 1.0f
            card.scaleX = 1.01f
            card.scaleY = 1.01f
            card.elevation = dpF(2f)
        } else {
            card.setBackgroundResource(R.drawable.bg_role_card)
            card.alpha = 0.96f
            card.scaleX = 1.0f
            card.scaleY = 1.0f
            card.elevation = dpF(0.5f)
        }
    }

    private fun roleAccentColor(role: Role): Int = when (role) {
        Role.PATIENT -> Color.parseColor("#059669")
        Role.DOCTOR -> Color.parseColor("#2563EB")
        Role.PHARMACIST -> Color.parseColor("#9333EA")
        Role.ADMIN -> Color.parseColor("#F59E0B")
    }

    private fun withAlpha(color: Int, alpha: Float): Int {
        val a = (255 * alpha).toInt().coerceIn(0, 255)
        return (color and 0x00FFFFFF) or (a shl 24)
    }

    private fun dpF(v: Float): Float = v * resources.displayMetrics.density
    private fun dpI(v: Float): Int = (v * resources.displayMetrics.density).toInt()

    private fun setCardTexts(card: View, titleRes: Int, descRes: Int) {
        val tvs = ViewTools.allTextViews(card)
        if (tvs.size >= 2) {
            tvs[0].text = getString(titleRes)
            tvs[1].text = getString(descRes)
        }
    }
}
