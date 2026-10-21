package com.simats.sehatsethu.roles.doctor

import android.content.Context
import com.simats.sehatsethu.ApiConfig.BASE_URL
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object DoctorHomeRepo {

    //  Change this to your backend base URL (emulator default)

    private const val ENDPOINT = "doctor/home_dashboard.php" // implement on backend

    suspend fun fetchDoctorHome(context: Context): DoctorHomeData {
        val raw = httpGet(BASE_URL + ENDPOINT)
        if (raw.isBlank()) return DoctorHomeData()

        return parseDoctorHome(raw)
    }

    private fun parseDoctorHome(raw: String): DoctorHomeData {
        return try {
            val j = JSONObject(raw)

            // allow { success: true, data: {...} } OR direct fields
            val dataObj = if (j.has("data") && j.opt("data") is JSONObject) j.optJSONObject("data")!! else j

            val nextObj = dataObj.optJSONObject("next_appointment")

            DoctorHomeData(
                doctorName = dataObj.optString("doctor_name").nullIfBlank(),
                speciality = dataObj.optString("speciality").nullIfBlank(),
                notifications = dataObj.optIntSafe("notifications", 0),
                todayPatients = dataObj.optIntSafe("today_patients", 0),
                todayCompleted = dataObj.optIntSafe("today_completed", 0),
                totalPatients = dataObj.optIntSafe("total_patients", 0),
                rating = dataObj.optDoubleSafe("rating", 0.0),
                nextAppointment = nextObj?.let {
                    NextAppointment(
                        patientName = it.optString("patient_name").orEmpty(),
                        meta = it.optString("meta").orEmpty(),
                        issue = it.optString("issue").orEmpty(),
                        time = it.optString("time").orEmpty(),
                        mode = it.optString("mode").orEmpty()
                    )
                }
            )
        } catch (_: Exception) {
            // never crash
            DoctorHomeData()
        }
    }

    private fun httpGet(urlStr: String): String {
        var conn: HttpURLConnection? = null
        return try {
            val url = URL(urlStr)
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 12_000
                readTimeout = 12_000
                setRequestProperty("Accept", "application/json")
            }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            if (stream == null) return ""

            stream.bufferedReader().use { it.readText() }
        } catch (_: Exception) {
            ""
        } finally {
            conn?.disconnect()
        }
    }

    private fun String?.nullIfBlank(): String? = if (this == null || this.isBlank()) null else this

    private fun JSONObject.optIntSafe(key: String, fallback: Int): Int {
        return try {
            if (!has(key)) fallback
            else when (val v = opt(key)) {
                is Number -> v.toInt()
                is String -> v.toIntOrNull() ?: fallback
                else -> fallback
            }
        } catch (_: Exception) {
            fallback
        }
    }

    private fun JSONObject.optDoubleSafe(key: String, fallback: Double): Double {
        return try {
            if (!has(key)) fallback
            else when (val v = opt(key)) {
                is Number -> v.toDouble()
                is String -> v.toDoubleOrNull() ?: fallback
                else -> fallback
            }
        } catch (_: Exception) {
            fallback
        }
    }
}
