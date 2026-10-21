package com.simats.sehatsethu.roles.patient

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.simats.sehatsethu.ApiConfig.BASE_URL
import com.simats.sehatsethu.AppPrefs
import com.simats.sehatsethu.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import kotlin.coroutines.resume

class PatientMedicineFragment : Fragment(R.layout.fragment_patient_medicine) {

    private val API_BASE = BASE_URL

    private val rows = ArrayList<JSONObject>()
    private lateinit var rv: RecyclerView
    private lateinit var tvEmpty: TextView
    private lateinit var tvNearValue: TextView
    private lateinit var adapter: MedicineSearchAdapter

    private var searchJob: Job? = null

    private var lastLat: Double? = null
    private var lastLng: Double? = null
    private var locationRequestedOnce = false

    private val reqPerms = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        val ok = (granted[Manifest.permission.ACCESS_FINE_LOCATION] == true) ||
                (granted[Manifest.permission.ACCESS_COARSE_LOCATION] == true)

        if (ok) {
            tvNearValue.text = getString(R.string.fetching_location)
            fetchLocationOnce()
        } else {
            tvNearValue.text = getString(R.string.near_you)
        }
    }

    override fun onViewCreated(v: View, s: Bundle?) {
        val et = v.findViewById<EditText>(R.id.etSearchMedicine)
        val btn = v.findViewById<MaterialButton>(R.id.btnSearch)

        rv = v.findViewById(R.id.rvMedicines)
        tvEmpty = v.findViewById(R.id.tvEmpty)
        tvNearValue = v.findViewById(R.id.tvNearValue)

        adapter = MedicineSearchAdapter(rows) { item ->
            val pharmacistId = item.optInt("pharmacist_user_id", 0)
            if (pharmacistId <= 0) return@MedicineSearchAdapter

            startActivity(
                Intent(requireContext(), PatientMedicineDetailsActivity::class.java).apply {
                    putExtra(PatientMedicineDetailsActivity.EXTRA_PHARMACIST_USER_ID, pharmacistId)
                    lastLat?.let { putExtra(PatientMedicineDetailsActivity.EXTRA_PATIENT_LAT, it) }
                    lastLng?.let { putExtra(PatientMedicineDetailsActivity.EXTRA_PATIENT_LNG, it) }
                }
            )
        }

        rv.layoutManager = LinearLayoutManager(requireContext())
        rv.adapter = adapter

        fun triggerSearch(q: String) {
            searchJob?.cancel()
            searchJob = lifecycleScope.launch {
                delay(220)
                loadFromApi(q)
            }
        }

        v.findViewById<View>(R.id.chipRecent1).setOnClickListener { et.setText(getString(R.string.med_paracetamol)) }
        v.findViewById<View>(R.id.chipRecent2).setOnClickListener { et.setText(getString(R.string.med_cetirizine)) }
        v.findViewById<View>(R.id.chipRecent3).setOnClickListener { et.setText(getString(R.string.med_vitamin_c)) }
        v.findViewById<View>(R.id.chipRecent4).setOnClickListener { et.setText(getString(R.string.med_cough_syrup)) }

        btn.setOnClickListener { triggerSearch(et.text?.toString().orEmpty()) }

        et.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun afterTextChanged(s: Editable?) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                triggerSearch(s?.toString().orEmpty())
            }
        })

        tvNearValue.text = getString(R.string.fetching_location)
        ensureLocation()

        triggerSearch("")
    }

    private fun hasLocationPermission(ctx: Context): Boolean {
        val fine = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    private fun ensureLocation() {
        if (locationRequestedOnce) return
        locationRequestedOnce = true

        val c = requireContext()
        if (hasLocationPermission(c)) {
            tvNearValue.text = getString(R.string.fetching_location)
            fetchLocationOnce()
        } else {
            reqPerms.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    //  Lint-safe: we check permission at top + still suppress MissingPermission warning
    @SuppressLint("MissingPermission")
    private fun fetchLocationOnce() {
        val ctx = requireContext()
        if (!hasLocationPermission(ctx)) {
            tvNearValue.text = getString(R.string.near_you)
            return
        }

        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager

        fun useLocation(loc: Location?) {
            if (loc == null) return
            lastLat = loc.latitude
            lastLng = loc.longitude

            lifecycleScope.launch {
                val place = withContext(Dispatchers.IO) { bestPlaceLabelSuspend(ctx, loc.latitude, loc.longitude) }
                tvNearValue.text = when {
                    !place.isNullOrBlank() -> place
                    else -> String.format(Locale.getDefault(), "%.4f, %.4f", loc.latitude, loc.longitude)
                }

                val q = view?.findViewById<EditText>(R.id.etSearchMedicine)?.text?.toString().orEmpty()
                loadFromApi(q)
            }
        }

        // 1) best last known
        try {
            val providers = lm.getProviders(true)
            var best: Location? = null
            for (p in providers) {
                if (!hasLocationPermission(ctx)) break
                val l = try { lm.getLastKnownLocation(p) } catch (_: SecurityException) { null }
                if (l != null) {
                    val bestAcc = best?.accuracy ?: Float.MAX_VALUE
                    if (best == null || l.accuracy < bestAcc) best = l
                }
            }
            if (best != null) {
                useLocation(best)
            }
        } catch (_: Throwable) {
            // ignore
        }

        // 2) current/fresh location
        try {
            if (!hasLocationPermission(ctx)) return

            val provider = when {
                lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
                lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
                else -> null
            }

            if (provider == null) {
                tvNearValue.text = getString(R.string.near_you)
                return
            }

            // Android 11+ best API
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try {
                    lm.getCurrentLocation(provider, null, ContextCompat.getMainExecutor(ctx)) { loc ->
                        if (loc != null) useLocation(loc)
                    }
                    return
                } catch (_: Throwable) {
                    // fallback below
                }
            }

            // fallback: single update
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    useLocation(location)
                    runCatching { lm.removeUpdates(this) }
                }
                override fun onProviderDisabled(provider: String) {}
                override fun onProviderEnabled(provider: String) {}
            }

            try {
                lm.requestSingleUpdate(provider, listener, null)
            } catch (_: SecurityException) {
                tvNearValue.text = getString(R.string.near_you)
            }
        } catch (_: Throwable) {
            tvNearValue.text = getString(R.string.near_you)
        }
    }

    //  FIX: make it suspend so we can call reverseGeocode33 safely
    private suspend fun bestPlaceLabelSuspend(ctx: Context, lat: Double, lng: Double): String? {
        return try {
            if (Build.VERSION.SDK_INT >= 33) {
                reverseGeocode33(ctx, lat, lng)
            } else {
                val geocoder = Geocoder(ctx, Locale.getDefault())
                val list = geocoder.getFromLocation(lat, lng, 1)
                val a = list?.firstOrNull() ?: return null
                when {
                    !a.subLocality.isNullOrBlank() -> a.subLocality
                    !a.locality.isNullOrBlank() -> a.locality
                    !a.adminArea.isNullOrBlank() -> a.adminArea
                    else -> null
                }
            }
        } catch (_: Throwable) {
            null
        }
    }

    //  FIX: API 33 callback geocoder guarded + annotated
    @RequiresApi(33)
    private suspend fun reverseGeocode33(ctx: Context, lat: Double, lng: Double): String? {
        return suspendCancellableCoroutine { cont ->
            try {
                val geocoder = Geocoder(ctx, Locale.getDefault())
                geocoder.getFromLocation(lat, lng, 1) { list ->
                    val a = list?.firstOrNull()
                    val place = when {
                        a == null -> null
                        !a.subLocality.isNullOrBlank() -> a.subLocality
                        !a.locality.isNullOrBlank() -> a.locality
                        !a.adminArea.isNullOrBlank() -> a.adminArea
                        else -> null
                    }
                    if (cont.isActive) cont.resume(place)
                }
            } catch (_: Throwable) {
                if (cont.isActive) cont.resume(null)
            }
        }
    }

    private suspend fun loadFromApi(qRaw: String) {
        val token = AppPrefs.getToken(requireContext()).orEmpty()
        if (token.isBlank()) {
            Toast.makeText(requireContext(), getString(R.string.please_login_again), Toast.LENGTH_SHORT).show()
            return
        }

        val q = qRaw.trim()

        val url = buildString {
            append(API_BASE)
            append("patient/medicine_search.php?q=")
            append(URLEncoder.encode(q, "UTF-8"))
            append("&limit=50")
            if (lastLat != null && lastLng != null) {
                append("&lat="); append(lastLat)
                append("&lng="); append(lastLng)
            }
        }

        val res = withContext(Dispatchers.IO) { getJson(url, token) }

        if (res == null) {
            rows.clear()
            adapter.notifyDataSetChanged()
            showEmpty(getString(R.string.failed), withIcon = true)
            return
        }

        if (res.optBoolean("ok", false) != true) {
            rows.clear()
            adapter.notifyDataSetChanged()
            val msg = res.optString("error").takeIf { it.isNotBlank() } ?: getString(R.string.failed)
            showEmpty(msg, withIcon = true)
            return
        }

        val arr: JSONArray = res.optJSONArray("items") ?: JSONArray()
        rows.clear()
        for (i in 0 until arr.length()) {
            arr.optJSONObject(i)?.let { rows.add(it) }
        }
        adapter.notifyDataSetChanged()

        if (rows.isEmpty()) showEmpty(getString(R.string.no_results), withIcon = true)
        else showEmpty("", withIcon = false)
    }

    private fun showEmpty(message: String, withIcon: Boolean) {
        tvEmpty.isVisible = message.isNotBlank()
        tvEmpty.text = message

        if (withIcon && message.isNotBlank()) {
            tvEmpty.setCompoundDrawablesWithIntrinsicBounds(0, R.drawable.ic_empty_medicine, 0, 0)
            tvEmpty.compoundDrawablePadding = (10 * resources.displayMetrics.density).toInt()
        } else {
            tvEmpty.setCompoundDrawablesWithIntrinsicBounds(0, 0, 0, 0)
        }
    }

    private fun getJson(url: String, token: String): JSONObject? {
        return try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 12000
                readTimeout = 12000
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Authorization", "Bearer $token")
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream.bufferedReader().use { it.readText() }
            conn.disconnect()

            runCatching { JSONObject(text) }.getOrNull()
        } catch (_: Throwable) {
            null
        }
    }
}

private class MedicineSearchAdapter(
    private val items: List<JSONObject>,
    private val onClick: (JSONObject) -> Unit
) : RecyclerView.Adapter<MedicineSearchVH>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MedicineSearchVH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_patient_medicine_search, parent, false)
        return MedicineSearchVH(v, onClick)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: MedicineSearchVH, position: Int) {
        holder.bind(items[position])
    }
}

private class MedicineSearchVH(
    v: View,
    private val onClick: (JSONObject) -> Unit
) : RecyclerView.ViewHolder(v) {

    private val tvName = v.findViewById<TextView>(R.id.tvMedName)
    private val tvSub = v.findViewById<TextView>(R.id.tvMedSub)

    private val chip = v.findViewById<MaterialCardView>(R.id.chipStatus)
    private val iv = v.findViewById<ImageView>(R.id.ivChipIcon)
    private val tvChip = v.findViewById<TextView>(R.id.tvChipText)

    fun bind(o: JSONObject) {
        val display = o.optString("display_name", o.optString("medicine_name", ""))
        val pharmacy = o.optString("pharmacy_name", "")
        val qty = o.optInt("quantity", 0)
        val status = o.optString("status", "IN_STOCK")

        val dist = if (o.isNull("distance_km")) null else o.optDouble("distance_km", 0.0)
        tvName.text = display
        tvSub.text = if (dist == null) pharmacy
        else pharmacy + " • " + itemView.context.getString(R.string.distance_km_short, dist)

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
