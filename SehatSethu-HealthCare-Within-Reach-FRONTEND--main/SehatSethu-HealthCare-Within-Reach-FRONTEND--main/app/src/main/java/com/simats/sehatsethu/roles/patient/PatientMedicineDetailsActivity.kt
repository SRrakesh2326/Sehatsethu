package com.simats.sehatsethu.roles.patient

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.simats.sehatsethu.ApiConfig.BASE_URL
import com.simats.sehatsethu.AppPrefs
import com.simats.sehatsethu.BaseActivity
import com.simats.sehatsethu.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

class PatientMedicineDetailsActivity : BaseActivity() {

    companion object {
        const val EXTRA_PHARMACIST_USER_ID = "pharmacist_user_id"
        const val EXTRA_PATIENT_LAT = "patient_lat"
        const val EXTRA_PATIENT_LNG = "patient_lng"
    }

    private val API_BASE = BASE_URL

    private lateinit var ivBack: ImageView
    private lateinit var tvStoreName: TextView
    private lateinit var tvOwnerName: TextView
    private lateinit var tvRating: TextView
    private lateinit var tvReviews: TextView

    private lateinit var tvTotal: TextView
    private lateinit var tvAvailable: TextView
    private lateinit var tvLow: TextView

    private lateinit var tvAddress: TextView
    private lateinit var tvDistance: TextView
    private lateinit var tvHours: TextView
    private lateinit var tvOpenNow: TextView
    private lateinit var tvContact: TextView

    private lateinit var btnCall: MaterialButton
    private lateinit var rv: RecyclerView

    private val items = ArrayList<JSONObject>()
    private lateinit var adapter: MedAdapter

    private var pharmacistUserId: Int = 0
    private var phone: String = ""

    // remembers last selected medicine (so Call Pharmacy can auto-pick it)
    private var lastSelectedMed: JSONObject? = null

    private val reqCallPerm = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        // Permission result used only after request is saved.
        // We'll call/dial using preferDirectCall flag in tryStartCall()
        tryStartCall(preferDirectCall = granted)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_patient_medicine_details)
        supportActionBar?.hide()

        pharmacistUserId = intent.getIntExtra(EXTRA_PHARMACIST_USER_ID, 0)
        if (pharmacistUserId <= 0) {
            Toast.makeText(this, getString(R.string.failed), Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        ivBack = findViewById(R.id.ivBack)
        tvStoreName = findViewById(R.id.tvStoreName)
        tvOwnerName = findViewById(R.id.tvOwnerName)
        tvRating = findViewById(R.id.tvRating)
        tvReviews = findViewById(R.id.tvReviews)

        tvTotal = findViewById(R.id.tvTotalStock)
        tvAvailable = findViewById(R.id.tvAvailableStock)
        tvLow = findViewById(R.id.tvLowStock)

        tvAddress = findViewById(R.id.tvAddressValue)
        tvDistance = findViewById(R.id.tvDistanceValue)
        tvHours = findViewById(R.id.tvHoursValue)
        tvOpenNow = findViewById(R.id.tvOpenNowValue)
        tvContact = findViewById(R.id.tvContactValue)

        btnCall = findViewById(R.id.btnCallPharmacy)
        rv = findViewById(R.id.rvPopularMedicines)

        ivBack.setOnClickListener { onBackPressedDispatcher.onBackPressed() }

        //  Tap on medicine row: request + call (also sets lastSelectedMed)
        adapter = MedAdapter(items) { medObj ->
            lastSelectedMed = medObj
            openRequestDialog(medObj)
        }
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = adapter

        //  UPDATED: Call Pharmacy must ALSO save request first
        btnCall.setOnClickListener {
            if (phone.trim().isBlank()) {
                Toast.makeText(this, getString(R.string.failed), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            openCallPharmacyRequestFlow()
        }

        loadDetails()
    }

    // ============================================
    //  NEW: Call Pharmacy → Select Med → Qty → Save → Call
    // ============================================

    private fun openCallPharmacyRequestFlow() {
        // If user already tapped a medicine, we can reuse it, but still ask qty
        val pre = lastSelectedMed
        if (pre != null && getMedicineName(pre).isNotBlank()) {
            openQuantityDialog(pre)
            return
        }

        if (items.isEmpty()) {
            Toast.makeText(this, getString(R.string.failed), Toast.LENGTH_SHORT).show()
            return
        }

        val labels = items.map { it.optString("display_name", it.optString("medicine_name", "")) }
            .map { it.ifBlank { getString(R.string.failed) } }
            .toTypedArray()

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.select_medicine_from_dropdown))
            .setItems(labels) { _, which ->
                val med = items.getOrNull(which)
                if (med != null) {
                    lastSelectedMed = med
                    openQuantityDialog(med)
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun openQuantityDialog(med: JSONObject) {
        val medName = med.optString("medicine_name", "").trim()
        if (medName.isBlank()) {
            Toast.makeText(this, getString(R.string.failed), Toast.LENGTH_SHORT).show()
            return
        }

        val strength = med.optString("strength", "").trim()
        val display = med.optString(
            "display_name",
            (if (strength.isBlank()) medName else "$medName $strength")
        ).trim()

        val dlgView = layoutInflater.inflate(R.layout.dialog_medicine_request, null, false)

        val tvMed = dlgView.findViewById<TextView>(R.id.tvDlgMed)
        val etQty = dlgView.findViewById<EditText>(R.id.etQty)
        val btnMinus = dlgView.findViewById<View>(R.id.btnMinus)
        val btnPlus = dlgView.findViewById<View>(R.id.btnPlus)
        val btnCancel = dlgView.findViewById<View>(R.id.btnCancel)
        val btnCall = dlgView.findViewById<View>(R.id.btnCall)

        tvMed.text = display
        etQty.setText("1")

        fun readQty(): Int = etQty.text?.toString()?.trim()?.toIntOrNull() ?: 0

        fun setQty(v: Int) {
            val vv = v.coerceIn(1, 9999)
            etQty.setText(vv.toString())
            etQty.setSelection(etQty.text?.length ?: 0)
        }

        btnMinus.setOnClickListener { setQty(readQty() - 1) }
        btnPlus.setOnClickListener { setQty(readQty() + 1) }

        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setView(dlgView)
            .create()

        //  remove the system white box & default padding around your custom layout
        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
            dialog.window?.decorView?.setPadding(0, 0, 0, 0)
        }

        btnCancel.setOnClickListener { dialog.dismiss() }

        btnCall.setOnClickListener {
            val qty = readQty()
            if (qty <= 0) {
                Toast.makeText(this, getString(R.string.invalid_quantity), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            dialog.dismiss()
            createRequestThenCall(medName, strength, qty)
        }

        dialog.show()
    }



    // existing row-tap flow (still works)
    private fun openRequestDialog(med: JSONObject) {
        openQuantityDialog(med)
    }

    private fun getMedicineName(o: JSONObject): String {
        return o.optString("medicine_name", "").trim()
    }

    // ============================================
    //  SAVE REQUEST → THEN CALL/DIAL
    // ============================================

    private fun createRequestThenCall(medicineName: String, strength: String, qty: Int) {
        val token = AppPrefs.getToken(this).orEmpty()
        if (token.isBlank()) {
            Toast.makeText(this, getString(R.string.please_login_again), Toast.LENGTH_SHORT).show()
            return
        }

        val url = API_BASE + "patient/medicine_request_create.php"

        lifecycleScope.launch {
            val res = withContext(Dispatchers.IO) {
                postJson(url, token, JSONObject().apply {
                    put("pharmacist_user_id", pharmacistUserId)
                    put("medicine_name", medicineName)
                    put("strength", strength)
                    put("quantity", qty)
                })
            }

            if (res?.optBoolean("ok", false) != true) {
                val msg = res?.optString("error")?.ifBlank { getString(R.string.failed) }
                    ?: getString(R.string.failed)
                Toast.makeText(this@PatientMedicineDetailsActivity, msg, Toast.LENGTH_LONG).show()
                // still allow dialer so user can proceed
            }

            val granted = ContextCompat.checkSelfPermission(
                this@PatientMedicineDetailsActivity,
                Manifest.permission.CALL_PHONE
            ) == PackageManager.PERMISSION_GRANTED

            if (granted) {
                tryStartCall(preferDirectCall = true)
            } else {
                // request permission -> callback will call/dial safely
                reqCallPerm.launch(Manifest.permission.CALL_PHONE)
            }
        }
    }

    private fun tryStartCall(preferDirectCall: Boolean) {
        val p = phone.trim()
        if (p.isBlank()) return

        val uri = Uri.parse("tel:$p")
        val intent = if (preferDirectCall) {
            Intent(Intent.ACTION_CALL).apply { data = uri }
        } else {
            Intent(Intent.ACTION_DIAL).apply { data = uri }
        }

        try {
            startActivity(intent)
        } catch (_: SecurityException) {
            runCatching {
                startActivity(Intent(Intent.ACTION_DIAL).apply { data = uri })
            }.onFailure {
                Toast.makeText(this, getString(R.string.failed), Toast.LENGTH_SHORT).show()
            }
        } catch (_: Throwable) {
            Toast.makeText(this, getString(R.string.failed), Toast.LENGTH_SHORT).show()
        }
    }

    private fun postJson(urlStr: String, token: String, body: JSONObject): JSONObject? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15000
                readTimeout = 15000
                doOutput = true
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Authorization", "Bearer $token")
            }
            conn.outputStream.use { os ->
                os.write(body.toString().toByteArray(Charsets.UTF_8))
            }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()

            runCatching { JSONObject(text) }.getOrElse {
                JSONObject().apply {
                    put("ok", false)
                    put("error", "HTTP $code (non-JSON)")
                    put("raw", text.take(800))
                }
            }
        } catch (_: Throwable) {
            null
        } finally {
            try { conn?.disconnect() } catch (_: Throwable) {}
        }
    }

    // ============================================
    // Existing loadDetails()
    // ============================================

    private fun loadDetails() {
        val token = AppPrefs.getToken(this).orEmpty()
        if (token.isBlank()) {
            Toast.makeText(this, getString(R.string.please_login_again), Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val pLat: Double? = if (intent.hasExtra(EXTRA_PATIENT_LAT)) intent.getDoubleExtra(EXTRA_PATIENT_LAT, 0.0) else null
        val pLng: Double? = if (intent.hasExtra(EXTRA_PATIENT_LNG)) intent.getDoubleExtra(EXTRA_PATIENT_LNG, 0.0) else null

        val url = buildString {
            append(API_BASE)
            append("patient/pharmacy_detail.php?pharmacist_user_id=")
            append(pharmacistUserId)
            append("&debug=1")
            if (pLat != null && pLng != null) {
                append("&lat="); append(pLat)
                append("&lng="); append(pLng)
            }
        }

        lifecycleScope.launch {
            val res = withContext(Dispatchers.IO) { getJson(url, token) }

            if (res == null) {
                Toast.makeText(this@PatientMedicineDetailsActivity, "Failed (no response)", Toast.LENGTH_SHORT).show()
                return@launch
            }

            if (!res.optBoolean("ok", false)) {
                val msg = when {
                    res.optString("detail").isNotBlank() -> res.optString("detail")
                    res.optString("raw").isNotBlank() -> res.optString("raw")
                    res.optString("error").isNotBlank() -> res.optString("error")
                    else -> getString(R.string.failed)
                }
                Toast.makeText(this@PatientMedicineDetailsActivity, msg, Toast.LENGTH_LONG).show()
                items.clear()
                adapter.notifyDataSetChanged()
                return@launch
            }

            val data = res.optJSONObject("data") ?: JSONObject()

            tvStoreName.text = data.optString("pharmacy_name", "")
            tvOwnerName.text = data.optString("owner_name", "")

            val rating = data.optDouble("rating", 0.0)
            val reviews = data.optInt("reviews_count", 0)
            tvRating.text = String.format(Locale.getDefault(), "%.1f", rating)
            tvReviews.text = getString(R.string.reviews_count, reviews)

            val stats = data.optJSONObject("stats") ?: JSONObject()
            tvTotal.text = stats.optInt("total_stock", 0).toString()
            tvAvailable.text = stats.optInt("available_stock", 0).toString()
            tvLow.text = stats.optInt("low_stock_items", 0).toString()

            tvAddress.text = data.optString("address", "")

            tvDistance.text = if (data.isNull("distance_km")) "—"
            else {
                val d = data.optDouble("distance_km", 0.0)
                getString(R.string.distance_km, d)
            }

            val hours = data.optString("hours", "").trim()
            tvHours.text = if (hours.isBlank()) "—" else hours

            val openNow = data.optBoolean("open_now", false)
            tvOpenNow.text = if (openNow) getString(R.string.open_now) else getString(R.string.closed_now)

            phone = data.optString("phone", "")
            tvContact.text = phone

            items.clear()
            val arr: JSONArray = data.optJSONArray("medicines") ?: JSONArray()
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.let { items.add(it) }
            }
            adapter.notifyDataSetChanged()
        }
    }

    private fun getJson(url: String, token: String): JSONObject? {
        return try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15000
                readTimeout = 15000
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Authorization", "Bearer $token")
            }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream.bufferedReader().use { it.readText() }
            conn.disconnect()

            runCatching { JSONObject(text) }.getOrElse {
                JSONObject().apply {
                    put("ok", false)
                    put("error", "HTTP $code (non-JSON)")
                    put("raw", text.take(800))
                }
            }
        } catch (_: Throwable) {
            null
        }
    }

    // ============================================
    // Adapter
    // ============================================

    private class MedAdapter(
        private val items: List<JSONObject>,
        private val onClick: (JSONObject) -> Unit
    ) : RecyclerView.Adapter<MedVH>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MedVH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_patient_pharmacy_medicine, parent, false)
            return MedVH(v, onClick)
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: MedVH, position: Int) {
            holder.bind(items[position])
        }
    }

    private class MedVH(v: View, private val onClick: (JSONObject) -> Unit) : RecyclerView.ViewHolder(v) {
        private val tvName = v.findViewById<TextView>(R.id.tvMedName)
        private val tvPrice = v.findViewById<TextView>(R.id.tvPrice)
        private val chip = v.findViewById<MaterialCardView>(R.id.chipStatus)
        private val iv = v.findViewById<ImageView>(R.id.ivChipIcon)
        private val tvChip = v.findViewById<TextView>(R.id.tvChipText)

        fun bind(o: JSONObject) {
            val name = o.optString("display_name", o.optString("medicine_name", ""))
            val qty = o.optInt("quantity", 0)
            val status = o.optString("status", "IN_STOCK")
            val price = if (o.isNull("price")) null else o.optDouble("price", 0.0)

            tvName.text = name
            tvPrice.text = if (price == null) "₹—" else "₹" + price.toInt().toString()

            when (status) {
                "OUT_OF_STOCK" -> {
                    chip.setCardBackgroundColor(0xFFFEE2E2.toInt())
                    iv.setImageResource(R.drawable.ic_stock_out)
                    iv.setColorFilter(0xFFDC2626.toInt())
                    tvChip.setTextColor(0xFFDC2626.toInt())
                    tvChip.text = itemView.context.getString(R.string.out_of_stock)
                }
                "LOW_STOCK" -> {
                    chip.setCardBackgroundColor(0xFFFFF3C4.toInt())
                    iv.setImageResource(R.drawable.ic_stock_low)
                    iv.setColorFilter(0xFFB45309.toInt())
                    tvChip.setTextColor(0xFFB45309.toInt())
                    tvChip.text = itemView.context.getString(R.string.low_stock_qty, qty)
                }
                else -> {
                    chip.setCardBackgroundColor(0xFFDCFCE7.toInt())
                    iv.setImageResource(R.drawable.ic_stock_in)
                    iv.setColorFilter(0xFF059669.toInt())
                    tvChip.setTextColor(0xFF059669.toInt())
                    tvChip.text = itemView.context.getString(R.string.in_stock_qty, qty)
                }
            }

            itemView.setOnClickListener { onClick(o) }
        }
    }
}
