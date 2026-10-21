package com.simats.sehatsethu

enum class Role(val id: String) {
    PATIENT("PATIENT"),
    DOCTOR("DOCTOR"),
    PHARMACIST("PHARMACIST"),
    ADMIN("ADMIN");

    companion object {
        fun fromId(id: String?): Role? {
            if (id.isNullOrBlank()) return null
            return entries.firstOrNull { it.id.equals(id, ignoreCase = true) }
        }
    }
}
