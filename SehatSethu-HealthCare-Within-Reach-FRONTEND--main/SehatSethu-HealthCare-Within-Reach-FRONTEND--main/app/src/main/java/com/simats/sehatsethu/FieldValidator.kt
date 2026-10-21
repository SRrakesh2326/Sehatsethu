package com.simats.sehatsethu

/**
 * Centralised field validation rules for SehatSethu.
 * Used across CreateAccountActivity, PatientRegistrationActivity,
 * DoctorRegistrationActivity, and PharmacistRegistrationActivity.
 */
object FieldValidator {

    // ── Name ────────────────────────────────────────────────────────────
    /** Allows alphabets and single spaces between words. No digits or special chars. */
    fun isValidName(name: String): Boolean {
        if (name.isBlank()) return false
        return name.matches(Regex("^[A-Za-z]+(\\s[A-Za-z]+)*$"))
    }

    // ── Email ───────────────────────────────────────────────────────────
    private val ALLOWED_DOMAINS = setOf(
        "gmail.com", "yahoo.com", "yahoo.in", "yahoo.co.in",
        "saveetha.com", "saveetha.ac.in",
        "outlook.com", "hotmail.com", "live.com",
        "icloud.com", "protonmail.com",
        "rediffmail.com", "ymail.com"
    )

    fun isValidEmail(email: String): Boolean {
        val lower = email.trim().lowercase()
        if (!android.util.Patterns.EMAIL_ADDRESS.matcher(lower).matches()) return false
        val domain = lower.substringAfterLast("@")
        return ALLOWED_DOMAINS.contains(domain)
    }

    fun emailDomainError(): String =
        "Email must end with @gmail.com, @yahoo.com, @saveetha.com, @outlook.com, etc."

    // ── Password ────────────────────────────────────────────────────────
    /**
     * Min 8 chars, at least:
     *  - one uppercase letter
     *  - one lowercase letter
     *  - one digit
     *  - one special character
     */
    fun isValidPassword(password: String): Boolean {
        if (password.length < 8) return false
        val hasUpper = password.any { it.isUpperCase() }
        val hasLower = password.any { it.isLowerCase() }
        val hasDigit = password.any { it.isDigit() }
        val hasSpecial = password.any { !it.isLetterOrDigit() }
        return hasUpper && hasLower && hasDigit && hasSpecial
    }

    const val PASSWORD_HINT =
        "Password must be at least 8 characters with uppercase, lowercase, number and special character."

    // ── Phone ───────────────────────────────────────────────────────────
    /**
     * Indian mobile: exactly 10 digits, first digit 6-9.
     */
    fun isValidIndianPhone(phone: String): Boolean {
        val digits = phone.trim().replace(Regex("[^0-9]"), "")
        return digits.length == 10 && digits[0] in '6'..'9'
    }

    const val PHONE_HINT = "Enter a 10-digit mobile number starting with 6, 7, 8 or 9."
}
