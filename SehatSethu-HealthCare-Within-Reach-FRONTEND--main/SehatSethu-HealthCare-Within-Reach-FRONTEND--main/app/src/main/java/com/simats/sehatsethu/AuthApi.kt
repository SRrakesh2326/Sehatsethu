package com.simats.sehatsethu

import org.json.JSONArray
import org.json.JSONObject

object AuthApi {

    private const val OTP_TIMEOUT_MS = 60_000

    fun registerSendOtp(fullName: String, email: String, password: String, role: Role): ApiClient.ApiResult {
        val body = JSONObject().apply {
            put("full_name", fullName)
            put("email", email)
            put("password", password)
            put("role", role.id)
        }
        return ApiClient.postJson("auth/register_send_otp.php", body, OTP_TIMEOUT_MS)
    }

    fun verifyEmailOtp(email: String, role: Role, otp: String): ApiClient.ApiResult {
        val body = JSONObject().apply {
            put("email", email)
            put("otp", otp)
            put("role", role.id)
        }
        return ApiClient.postJson("auth/verify_email_otp.php", body)
    }

    // keep (if used anywhere)
    fun verifyEmailOtp(email: String, otp: String): ApiClient.ApiResult {
        val body = JSONObject().apply {
            put("email", email)
            put("otp", otp)
        }
        return ApiClient.postJson("auth/verify_email_otp.php", body)
    }

    fun registerCreateAccount(
        fullName: String,
        email: String,
        password: String,
        role: Role,
        signupToken: String
    ): ApiClient.ApiResult {
        val body = JSONObject().apply {
            put("full_name", fullName)
            put("email", email)
            put("password", password)
            put("role", role.id)
            put("signup_token", signupToken)
        }
        return ApiClient.postJson("auth/register_create_account.php", body)
    }

    fun forgotSendOtp(email: String): ApiClient.ApiResult {
        val body = JSONObject().apply { put("email", email) }
        return ApiClient.postJson("auth/forgot_send_otp.php", body, OTP_TIMEOUT_MS)
    }

    fun verifyResetOtp(email: String, otp: String): ApiClient.ApiResult {
        val body = JSONObject().apply {
            put("email", email)
            put("otp", otp)
        }
        return ApiClient.postJson("auth/verify_reset_otp.php", body)
    }

    fun resetPasswordOtp(email: String, otp: String, newPassword: String): ApiClient.ApiResult {
        val body = JSONObject().apply {
            put("email", email)
            put("otp", otp)
            put("new_password", newPassword)
        }
        return ApiClient.postJson("auth/reset_password_otp.php", body)
    }

    fun login(email: String, password: String, role: Role): ApiClient.ApiResult {
        val body = JSONObject().apply {
            put("email", email)
            put("password", password)
            put("role", role.id)
        }
        return ApiClient.postJson("auth/login.php", body)
    }

    fun resendEmailOtp(email: String): ApiClient.ApiResult {
        val body = JSONObject().apply { put("email", email) }
        return ApiClient.postJson("auth/resend_email_otp.php", body, OTP_TIMEOUT_MS)
    }

    // =========================================================
    //  Admin verification status check
    // =========================================================
    fun checkVerificationStatus(token: String = ""): ApiClient.ApiResult {
        val body = JSONObject().apply { put("ping", 1) }
        return ApiClient.postJsonWithAuth("auth/check_verification_status.php", body, token)
    }

    // =========================================================
    //  Doctor submit
    // =========================================================
    fun submitDoctorProfile(
        fullName: String,
        specialization: String,
        regNo: String,
        hospital: String,
        experienceYears: Int,
        token: String,
        phone: String? = null,
        documents: JSONArray? = null
    ): ApiClient.ApiResult {
        val body = JSONObject().apply {
            put("full_name", fullName)
            put("specialization", specialization)
            put("registration_no", regNo)
            put("practice_place", hospital)
            put("experience_years", experienceYears)
            if (!phone.isNullOrBlank()) put("phone", phone)
            if (documents != null) put("documents", documents)
        }
        return ApiClient.postJsonAuth("profile/doctor_submit.php", body, token)
    }

    // =========================================================
    //  Pharmacist submit (your existing one)
    // =========================================================
    suspend fun submitPharmacistProfileWithDocs(
        fullName: String,
        phone: String = "",
        pharmacyName: String,
        drugLicenseNo: String,
        villageTown: String,
        fullAddress: String?,
        token: String,
        documents: JSONArray
    ): ApiClient.ApiResult {
        val payload = JSONObject().apply {
            put("full_name", fullName)
            if (phone.isNotBlank()) put("phone", phone)
            put("pharmacy_name", pharmacyName)
            put("drug_license_no", drugLicenseNo)
            put("village_town", villageTown)
            if (!fullAddress.isNullOrBlank()) put("full_address", fullAddress)
            put("documents", documents)
        }
        return ApiClient.postJsonAuth("profile/pharmacist_submit.php", payload, token)
    }

    // =========================================================
    //  Patient profile submit
    // =========================================================


    fun completePatientProfile(
        fullName: String,
        gender: String,
        age: Int,
        villageTown: String,
        district: String,
        phone: String,
        token: String
    ): ApiClient.ApiResult {

        val body = JSONObject().apply {
            put("full_name", fullName)
            put("gender", gender)
            put("age", age)
            put("village_town", villageTown)
            put("district", district)
            put("phone", phone) //  NEW
        }

        val headers = mapOf(
            "Authorization" to "Bearer $token",
            "Accept" to "application/json",
            "Content-Type" to "application/json; charset=utf-8"
        )

        // pick your timeout (match what you use elsewhere)
        return ApiClient.postJson(
            path = "profile/patient_submit.php",
            body = body,
            timeoutMs = 12000,
            headers = headers
        )
    }

    // =========================================================
    //  Patient medical history upload (token-based)
    // =========================================================
    fun uploadPatientMedicalHistory(
        token: String,
        documents: JSONArray
    ): ApiClient.ApiResult {
        val body = JSONObject().apply {
            put("documents", documents)
        }
        return ApiClient.postJsonAuth("patient/medical_history_upload.php", body, token)
    }

}
