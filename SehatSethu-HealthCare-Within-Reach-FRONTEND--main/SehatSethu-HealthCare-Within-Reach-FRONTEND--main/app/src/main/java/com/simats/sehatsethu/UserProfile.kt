package com.simats.sehatsethu

data class UserProfile(
    val id: Long? = null,
    val fullName: String? = null,
    val email: String? = null,
    val role: String? = null,
    val phone: String? = null
) {
    fun displayName(): String =
        fullName?.trim().takeUnless { it.isNullOrBlank() }
            ?: email?.trim().takeUnless { it.isNullOrBlank() }
            ?: "User"

    fun displayEmail(): String =
        email?.trim().takeUnless { it.isNullOrBlank() } ?: "Email not available"

    fun displayPhone(): String =
        phone?.trim().takeUnless { it.isNullOrBlank() } ?: "Phone not added"
}
