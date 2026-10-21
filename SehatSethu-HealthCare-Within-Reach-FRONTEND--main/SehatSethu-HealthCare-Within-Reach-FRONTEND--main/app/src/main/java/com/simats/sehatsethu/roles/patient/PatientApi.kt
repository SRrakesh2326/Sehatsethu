package com.simats.sehatsethu.roles.patient

import android.content.Context
import com.simats.sehatsethu.ApiClient
import com.simats.sehatsethu.ApiConfig
import com.simats.sehatsethu.AppPrefs
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale

object PatientApi {
    private const val TIMEOUT = 60_000

    @Volatile var lastError: String? = null
        private set
    @Volatile var lastHttpCode: Int? = null
        private set
    @Volatile var lastRaw: String? = null
        private set

    private fun token(ctx: Context): String {
        return try {
            AppPrefs.getAuthToken(ctx).orEmpty()
        } catch (_: Throwable) {
            try {
                AppPrefs.getToken(ctx).orEmpty()
            } catch (_: Throwable) {
                ""
            }
        }
    }

    private fun buildUrl(path: String): String {
        val base = ApiConfig.BASE_URL.trim()
        val b = if (base.endsWith("/")) base else "$base/"
        val p = path.trimStart('/')
        return b + p
    }

    // ------------------------------
    // Doctor detail
    // ------------------------------
    fun getDoctorDetail(ctx: Context, doctorId: Int): JSONObject? {
        lastError = null
        lastHttpCode = null
        lastRaw = null

        val t = token(ctx)
        if (t.isBlank()) {
            lastError = "Missing auth token"
            return null
        }

        val body = JSONObject().apply {
            put("doctorId", doctorId)
            put("doctor_id", doctorId)
        }

        val res = ApiClient.postJsonWithAuth("patient/doctor_detail.php", body, t, TIMEOUT)
        if (!res.ok) {
            lastError = res.errorMessage ?: res.json?.optString("error") ?: "Failed"
            lastHttpCode = res.httpCode
            lastRaw = res.json?.toString()
            return null
        }

        val root = res.json ?: return null
        val doctor = root.optJSONObject("data")?.optJSONObject("doctor")
            ?: root.optJSONObject("doctor") // fallback
        if (doctor == null) lastError = "Invalid response"
        return doctor
    }

    // ------------------------------
    // Slots (date-wise)
    // ------------------------------
    fun getSlots(ctx: Context, doctorId: Int, daysAhead: Int = 7): JSONArray? {
        lastError = null
        lastHttpCode = null
        lastRaw = null

        val t = token(ctx)
        if (t.isBlank()) {
            lastError = "Missing auth token"
            return null
        }

        val body = JSONObject().apply {
            put("doctorId", doctorId)
            put("doctor_id", doctorId)
            put("daysAhead", daysAhead)
            put("days", daysAhead)
        }

        val res = ApiClient.postJsonWithAuth("patient/doctor_slots.php", body, t, TIMEOUT)
        if (res.ok) {
            val root = res.json ?: return null
            return root.optJSONObject("data")?.optJSONArray("days")
                ?: root.optJSONArray("days")
        }

        val fallback = ApiClient.postJsonWithAuth("doctor/available_slots.php", body, t, TIMEOUT)
        if (!fallback.ok) {
            lastError = fallback.errorMessage ?: fallback.json?.optString("error") ?: "Failed"
            lastHttpCode = fallback.httpCode
            lastRaw = fallback.json?.toString()
            return null
        }

        val root2 = fallback.json ?: return null
        return root2.optJSONObject("data")?.optJSONArray("days")
            ?: root2.optJSONArray("days")
    }

    fun getAvailableDays(ctx: Context, doctorId: Int): JSONArray? {
        lastError = null
        lastHttpCode = null
        lastRaw = null

        val t = token(ctx)
        if (t.isBlank()) {
            lastError = "Missing auth token"
            return null
        }

        val body = JSONObject().apply {
            put("doctorId", doctorId)
            put("doctor_id", doctorId)
            put("daysAhead", 7)
            put("days", 7)
        }

        val postRes = ApiClient.postJsonWithAuth("patient/doctor_slots.php", body, t, TIMEOUT)
        if (postRes.ok) {
            return postRes.json?.optJSONObject("data")?.optJSONArray("days")
                ?: postRes.json?.optJSONArray("days")
        }

        lastError = postRes.errorMessage ?: postRes.json?.optString("error") ?: "Failed"
        lastHttpCode = postRes.httpCode
        lastRaw = postRes.json?.toString()

        val qs = "doctor_id=$doctorId&doctorId=$doctorId&daysAhead=7&days=7"
        val getRoot = getJsonWithQuery(ctx, "patient/doctor_slots.php", qs) ?: return null
        return getRoot.optJSONObject("data")?.optJSONArray("days")
            ?: getRoot.optJSONArray("days")
    }

    private fun getJsonWithQuery(ctx: Context, path: String, query: String): JSONObject? {
        val base = ApiConfig.BASE_URL.trim()
        val b = if (base.endsWith("/")) base else "$base/"
        val p = path.trimStart('/')

        val url = if (query.isBlank()) (b + p) else (b + p + "?" + query)
        return http(ctx, "GET", url, null)
    }

    // ------------------------------
    // Booking status gate (dashboard-based)
    // ------------------------------
    fun getDoctorBookingStatus(ctx: Context, doctorId: Int): JSONObject? {
        lastError = null
        lastHttpCode = null
        lastRaw = null

        //  1) authoritative: POST to server
        runCatching {
            val token = token(ctx)
            val body = JSONObject().apply {
                put("doctorId", doctorId)
                put("doctor_id", doctorId)
            }

            val res = ApiClient.postJsonWithAuth(
                "patient/doctor_booking_status.php",
                body,
                token,
                TIMEOUT
            )

            if (res.ok && res.json != null) {
                val root = res.json!!
                val ok = root.optBoolean("ok", false) || root.optBoolean("success", false)
                if (ok) {
                    val data = root.optJSONObject("data")
                    if (data != null && data.has("hasActiveBooking")) return data
                }
            }
        }

        //  2) fallback: your existing dashboard scan (unchanged)
        // ... keep your existing fallback code here ...
        return JSONObject().apply {
            put("hasActiveBooking", false)
            put("appointmentId", "")
            put("public_code", "")
        }
    }

    // PatientApi.kt (add this)
    fun listAppointments(ctx: Context, view: String, limit: Int = 50, offset: Int = 0): JSONArray? {
        lastError = null
        lastHttpCode = null
        lastRaw = null

        val t = token(ctx)
        if (t.isBlank()) {
            lastError = "Missing auth token"
            return null
        }

        val body = JSONObject().apply {
            put("view", view) // UPCOMING | PAST | ALL
            put("limit", limit)
            put("offset", offset)
        }

        val res = ApiClient.postJsonWithAuth("patient/appointments_list.php", body, t, TIMEOUT)
        if (!res.ok) {
            lastError = res.errorMessage ?: res.json?.optString("error") ?: "Failed"
            lastHttpCode = res.httpCode
            lastRaw = res.json?.toString()
            return null
        }

        val root = res.json ?: return null
        return root.optJSONObject("data")?.optJSONArray("items")
            ?: root.optJSONArray("items")
    }



    // ------------------------------
    // Dashboard & Specialities
    // ------------------------------
    fun getDashboard(ctx: Context): JSONObject? {
        return getJson(ctx, "patient/dashboard.php")
    }

    fun getSpecialities(ctx: Context): JSONArray? {
        val r = getJson(ctx, "patient/specialities.php") ?: return null
        return r.optJSONObject("data")?.optJSONArray("specialities")
            ?: r.optJSONArray("specialities")
    }

    //  FIXED: DOCTORS FETCH (auth + multiple shapes + multiple param names)
    fun getDoctors(ctx: Context, speciality: String): JSONArray? {
        lastError = null
        lastHttpCode = null
        lastRaw = null

        val t = token(ctx)
        if (t.isBlank()) {
            lastError = "Missing auth token"
            return null
        }

        val body = JSONObject().apply {
            // send all common keys (backend changes won’t break app)
            put("speciality", speciality)
            put("speciality_key", speciality)
            put("specialty", speciality)
            put("specialization", speciality)
        }

        val paths = listOf(
            "patient/doctors_by_speciality.php",
            "patient/doctors.php"
        )

        var lastMsg = "Failed"
        var lastCode: Int? = null
        var lastRawLocal: String? = null

        for (p in paths) {
            val res = ApiClient.postJsonWithAuth(p, body, t, TIMEOUT)
            if (res.ok) {
                val root = res.json
                val arr = extractDoctorsArray(root)
                if (arr != null) return arr

                // ok=true but unexpected format
                lastMsg = "Invalid response"
                lastCode = res.httpCode
                lastRawLocal = root?.toString()
            } else {
                lastMsg = res.errorMessage ?: res.json?.optString("error") ?: "Failed"
                lastCode = res.httpCode
                lastRawLocal = res.json?.toString()
            }
        }

        lastError = lastMsg
        lastHttpCode = lastCode
        lastRaw = lastRawLocal
        return null
    }

    private fun extractDoctorsArray(root: JSONObject?): JSONArray? {
        if (root == null) return null

        // sometimes array is directly at root
        root.optJSONArray("doctors")?.let { return it }
        root.optJSONArray("items")?.let { return it }
        root.optJSONArray("list")?.let { return it }

        val dataAny = root.opt("data")
        if (dataAny is JSONArray) return dataAny
        if (dataAny is JSONObject) {
            dataAny.optJSONArray("doctors")?.let { return it }
            dataAny.optJSONArray("items")?.let { return it }
            dataAny.optJSONArray("list")?.let { return it }
            // sometimes nested again
            dataAny.optJSONObject("data")?.optJSONArray("doctors")?.let { return it }
        }

        return null
    }

    // ------------------------------
    // Booking
    // ------------------------------
    fun book(
        ctx: Context,
        doctorId: Int,
        specialityKey: String,
        consultType: String,
        date: String,
        time: String,
        symptoms: String
    ): JSONObject? {
        lastError = null
        lastHttpCode = null
        lastRaw = null

        val t = token(ctx)
        if (t.isBlank()) {
            lastError = "Missing auth token"
            return null
        }

        val cType = consultType.trim().uppercase(Locale.US)
        val sym = symptoms.trim()

        val body = JSONObject().apply {
            // doctor
            put("doctorId", doctorId)
            put("doctor_id", doctorId)
            put("doctor_id_str", doctorId.toString())

            // speciality (stable key)
            put("speciality", specialityKey)
            put("speciality_key", specialityKey)
            put("specialty", specialityKey)
            put("specialization", specialityKey)

            // consult type
            put("consult_type", cType)
            put("consultType", cType)

            // date/time (send common variants)
            put("date", date)
            put("appointment_date", date)
            put("time", time)
            put("slot_time", time)
            put("appointment_time", time)

            //  symptoms (send multiple keys for compatibility)
            put("symptoms", sym)
            put("symptoms_text", sym)
            put("complaint", sym)
            put("notes", sym)

            // fee: backend should ignore client; keep safe default
            put("fee_amount", 0)
        }

        val paths = listOf("patient/book_appointment.php")

        var lastFailMsg = "Booking failed"
        var lastFailCode: Int? = null
        var lastFailRaw: String? = null

        for (p in paths) {
            val res = ApiClient.postJsonWithAuth(p, body, t, TIMEOUT)
            if (res.ok) {
                val root = res.json ?: return null

                // keep raw for debugging (doesn't affect logic)
                lastRaw = root.toString()
                lastHttpCode = res.httpCode

                // tolerate {ok:true,data:{...}} OR direct payload
                val data = root.optJSONObject("data") ?: root
                return data
            } else {
                lastFailMsg = res.errorMessage ?: res.json?.optString("error") ?: "Booking failed"
                lastFailCode = res.httpCode
                lastFailRaw = res.json?.toString()
            }
        }

        lastError = lastFailMsg
        lastHttpCode = lastFailCode
        lastRaw = lastFailRaw
        return null
    }


    private fun extractIsoDate(o: JSONObject): String {
        val direct = o.optString("date", "").ifBlank { o.optString("appointment_date", "") }.trim()
        if (direct.matches(Regex("""\d{4}-\d{2}-\d{2}"""))) return direct

        val dt = o.optString("scheduled_at", "").ifBlank {
            o.optString("scheduledAt", "").ifBlank {
                o.optString("scheduled_at_str", "").ifBlank {
                    o.optString("scheduledAtStr", "")
                }
            }
        }.trim()

        if (dt.length >= 10 && dt.substring(0, 10).matches(Regex("""\d{4}-\d{2}-\d{2}"""))) {
            return dt.substring(0, 10)
        }
        return ""
    }

    // ------------------------------
    // Notifications
    // ------------------------------
    // ------------------------------
// Notifications (SAFE: tries patient/* first, then falls back to old notifications/*)
// ------------------------------
    fun listNotifications(ctx: Context, unreadOnly: Boolean = false): JSONArray? {
        lastError = null
        lastHttpCode = null
        lastRaw = null

        val t = token(ctx)
        if (t.isBlank()) {
            lastError = "Missing auth token"
            return null
        }

        //  1) NEW (recommended): GET patient/notifications_list.php?unread=0/1
        runCatching {
            val qs = "unread=${if (unreadOnly) 1 else 0}&limit=200"
            val root = getJsonWithQuery(ctx, "patient/notifications_list.php", qs)
            if (root != null) {
                val ok = root.optBoolean("ok", false) || root.optBoolean("success", false)
                if (ok) {
                    val data = root.optJSONArray("data")
                    if (data != null) return data
                } else {
                    // keep error but allow fallback
                    lastError = root.optString("error").ifBlank { "Failed" }
                    lastRaw = root.toString()
                }
            }
        }

        //  2) FALLBACK: your existing endpoint notifications/list.php (POST)
        val body = JSONObject().apply {
            put("unread_only", if (unreadOnly) 1 else 0)
            put("limit", 100)
        }

        val res = ApiClient.postJsonWithAuth("notifications/list.php", body, t, TIMEOUT)
        if (!res.ok) {
            lastError = res.errorMessage ?: res.json?.optString("error") ?: "Failed"
            lastHttpCode = res.httpCode
            lastRaw = res.json?.toString()
            return null
        }

        val root = res.json ?: return null
        return root.optJSONObject("data")?.optJSONArray("items")
            ?: root.optJSONArray("items")
            ?: root.optJSONArray("data") // extra fallback if server returns array at data
    }

    /**  Mark ALL as read */
    fun markAllNotificationsRead(ctx: Context): Boolean {
        lastError = null
        lastHttpCode = null
        lastRaw = null

        val t = token(ctx)
        if (t.isBlank()) {
            lastError = "Missing auth token"
            return false
        }

        //  1) NEW: patient/notifications_mark_all_read.php
        runCatching {
            val res = ApiClient.postJsonWithAuth("patient/notifications_mark_all_read.php", JSONObject(), t, TIMEOUT)
            if (res.ok) {
                val root = res.json
                val ok = root?.optBoolean("ok", true) ?: true
                if (ok) return true
            } else {
                // keep error but allow fallback
                lastError = res.errorMessage ?: res.json?.optString("error") ?: "Failed"
                lastHttpCode = res.httpCode
                lastRaw = res.json?.toString()
            }
        }

        //  2) FALLBACK: your old notifications/mark_read.php (mark_all=1)
        val body = JSONObject().apply { put("mark_all", 1) }
        val res2 = ApiClient.postJsonWithAuth("notifications/mark_read.php", body, t, TIMEOUT)
        if (!res2.ok) {
            lastError = res2.errorMessage ?: res2.json?.optString("error") ?: "Failed"
            lastHttpCode = res2.httpCode
            lastRaw = res2.json?.toString()
            return false
        }
        return true
    }

    /**  Mark ONE notification read (used by PatientNotificationsActivity) */
    fun markNotificationRead(ctx: Context, notificationId: Long): Boolean {
        lastError = null
        lastHttpCode = null
        lastRaw = null

        val t = token(ctx)
        if (t.isBlank()) {
            lastError = "Missing auth token"
            return false
        }
        if (notificationId <= 0L) {
            lastError = "Invalid notification id"
            return false
        }

        val body = JSONObject().apply { put("notification_id", notificationId) }

        //  1) NEW: patient/notifications_mark_read.php
        runCatching {
            val res = ApiClient.postJsonWithAuth("patient/notifications_mark_read.php", body, t, TIMEOUT)
            if (res.ok) {
                val root = res.json
                val ok = root?.optBoolean("ok", true) ?: true
                if (ok) return true
            } else {
                lastError = res.errorMessage ?: res.json?.optString("error") ?: "Failed"
                lastHttpCode = res.httpCode
                lastRaw = res.json?.toString()
            }
        }

        //  2) FALLBACK: notifications/mark_read.php (id)
        val body2 = JSONObject().apply {
            put("notification_id", notificationId)
            put("id", notificationId)
            put("mark_all", 0)
        }
        val res2 = ApiClient.postJsonWithAuth("notifications/mark_read.php", body2, t, TIMEOUT)
        if (!res2.ok) {
            lastError = res2.errorMessage ?: res2.json?.optString("error") ?: "Failed"
            lastHttpCode = res2.httpCode
            lastRaw = res2.json?.toString()
            return false
        }
        return true
    }

    /**  Dismiss ONE notification (used by PatientNotificationsActivity) */
    fun dismissNotification(ctx: Context, notificationId: Long): Boolean {
        lastError = null
        lastHttpCode = null
        lastRaw = null

        val t = token(ctx)
        if (t.isBlank()) {
            lastError = "Missing auth token"
            return false
        }
        if (notificationId <= 0L) {
            lastError = "Invalid notification id"
            return false
        }

        val body = JSONObject().apply { put("notification_id", notificationId) }

        //  1) NEW: patient/notifications_dismiss.php
        runCatching {
            val res = ApiClient.postJsonWithAuth("patient/notifications_dismiss.php", body, t, TIMEOUT)
            if (res.ok) {
                val root = res.json
                val ok = root?.optBoolean("ok", true) ?: true
                if (ok) return true
            } else {
                lastError = res.errorMessage ?: res.json?.optString("error") ?: "Failed"
                lastHttpCode = res.httpCode
                lastRaw = res.json?.toString()
            }
        }

        //  2) FALLBACK: notifications/dismiss.php (if you have it)
        runCatching {
            val res2 = ApiClient.postJsonWithAuth("notifications/dismiss.php", body, t, TIMEOUT)
            if (res2.ok) return true
        }

        // if fallback doesn't exist, return failure
        if (lastError.isNullOrBlank()) lastError = "Dismiss failed"
        return false
    }


    fun getAppointmentDetail(ctx: Context, appointmentIdOrCode: String): JSONObject? {
        lastError = null
        lastHttpCode = null
        lastRaw = null

        val t = token(ctx)
        if (t.isBlank()) {
            lastError = "Missing auth token"
            return null
        }

        val key = appointmentIdOrCode.trim()
        if (key.isBlank()) {
            lastError = "Missing appointment id"
            return null
        }

        //  keep backward compatibility: send all keys
        val body = JSONObject().apply {
            put("appointment_id", key)   // primary
            put("appointmentId", key)
            put("id", key)
            put("public_code", key)
            put("publicCode", key)
        }

        val res = ApiClient.postJsonWithAuth("patient/appointment_detail.php", body, t, TIMEOUT)
        if (!res.ok) {
            lastError = res.errorMessage ?: res.json?.optString("error") ?: "Failed"
            lastHttpCode = res.httpCode
            lastRaw = res.json?.toString()
            return null
        }

        val root = res.json ?: return null

        //  tolerate both {ok:true,data:{...}} and direct object payloads
        val ok = root.optBoolean("ok", false) || root.optBoolean("success", false)
        if (!ok && root.has("error")) {
            lastError = root.optString("error", "Failed")
            lastRaw = root.toString()
            return null
        }

        return root.optJSONObject("data") ?: root
    }

    fun appointmentsList(ctx: Context, view: String, limit: Int = 5, offset: Int = 0): JSONObject? {
        lastError = null
        lastHttpCode = null
        lastRaw = null

        val urlStr = ApiConfig.BASE_URL + "patient/appointments_list.php"
        val body = JSONObject().apply {
            put("view", view)        // UPCOMING | PAST | ALL
            put("limit", limit)
            put("offset", offset)
        }

        return try {
            val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = TIMEOUT
                readTimeout = TIMEOUT
                doInput = true
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                val t = token(ctx)
                if (t.isNotBlank()) setRequestProperty("Authorization", "Bearer $t")
            }

            conn.outputStream.use { os ->
                os.write(body.toString().toByteArray(Charsets.UTF_8))
                os.flush()
            }

            lastHttpCode = conn.responseCode
            val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
            val raw = stream.bufferedReader().use { it.readText() }
            lastRaw = raw

            val json = JSONObject(raw)
            if (!json.optBoolean("ok", false)) lastError = json.optString("error", "Request failed")
            json
        } catch (t: Throwable) {
            lastError = t.message ?: "Network error"
            null
        }
    }


    // ------------------------------
    // HTTP helpers (kept for GET endpoints)
    // ------------------------------
    private fun getJson(ctx: Context, path: String): JSONObject? {
        val urlStr = buildUrl(path)
        return http(ctx, "GET", urlStr, null)
    }

    private fun postJson(ctx: Context, path: String, body: JSONObject): JSONObject? {
        val urlStr = buildUrl(path)
        return http(ctx, "POST", urlStr, body)
    }

    private fun http(ctx: Context, method: String, urlStr: String, body: JSONObject?): JSONObject? {
        lastError = null
        lastHttpCode = null
        lastRaw = null

        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                connectTimeout = 25_000
                readTimeout = 25_000
                requestMethod = method
                useCaches = false
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Content-Type", "application/json; charset=utf-8")

                val t = token(ctx)
                if (t.isNotBlank()) setRequestProperty("Authorization", "Bearer $t")
                if (method == "POST") doOutput = true
            }

            if (method == "POST" && body != null) {
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }

            val code = conn.responseCode
            lastHttpCode = code

            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val raw = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            lastRaw = raw

            if (raw.isBlank()) {
                lastError = "Empty response"
                return null
            }

            var txt = raw.trimStart().removePrefix("\uFEFF")
            if (txt.startsWith("<!--")) {
                val end = txt.indexOf("-->")
                if (end >= 0) txt = txt.substring(end + 3).trimStart()
            }

            val json = runCatching { JSONObject(txt) }.getOrNull()
            if (json == null) {
                lastError = "Non-JSON response (HTTP $code): " + txt.take(120)
                return null
            }

            val ok = json.optBoolean("ok", false) || json.optBoolean("success", false)
            if (!ok) lastError = json.optString("error").ifBlank { "Request failed" }

            json
        } catch (e: Exception) {
            lastError = e.message ?: "Network error"
            null
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }
}
