package com.simats.sehatsethu.roles.doctor

data class DoctorHomeData(
    val doctorName: String? = null,
    val speciality: String? = null,
    val notifications: Int? = 0,
    val todayPatients: Int? = 0,
    val todayCompleted: Int? = 0,
    val totalPatients: Int? = 0,
    val rating: Double = 0.0,
    val nextAppointment: NextAppointment? = null
)

data class NextAppointment(
    val patientName: String = "",
    val meta: String = "",
    val issue: String = "",
    val time: String = "",
    val mode: String = "" // VIDEO/AUDIO etc (optional)
)
