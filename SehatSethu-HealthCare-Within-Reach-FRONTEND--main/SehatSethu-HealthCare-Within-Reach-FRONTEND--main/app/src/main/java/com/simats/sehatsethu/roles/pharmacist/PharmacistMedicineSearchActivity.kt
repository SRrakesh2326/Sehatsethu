package com.simats.sehatsethu.roles.pharmacist

import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.core.view.ViewCompat
import androidx.core.widget.TextViewCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.simats.sehatsethu.ApiConfig.BASE_URL
import com.simats.sehatsethu.AppPrefs
import com.simats.sehatsethu.BaseActivity
import com.simats.sehatsethu.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs

class PharmacistMedicineSearchActivity : BaseActivity() {

    private val uiScope = MainScope()
    private var searchJob: Job? = null
    private var loading = false

    private lateinit var ivBack: ImageView
    private lateinit var tvTitle: TextView
    private lateinit var tvCount: TextView

    private lateinit var etSearch: EditText

    private lateinit var chipAll: TextView
    private lateinit var chipLow: TextView
    private lateinit var chipOut: TextView

    private lateinit var rv: RecyclerView
    private lateinit var emptyWrap: View
    private lateinit var tvEmptyTitle: TextView
    private lateinit var tvEmptySub: TextView
    private lateinit var btnGoUpdate: View

    private val all = ArrayList<MedRow>()
    private val visible = ArrayList<MedRow>()
    private lateinit var adapter: MedAdapter

    private enum class Filter { ALL, LOW, OUT }
    private var currentFilter: Filter = Filter.ALL

    private val tzIst = TimeZone.getTimeZone("Asia/Kolkata")
    private val dfServer = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).apply { timeZone = tzIst }

    private val handler by lazy { Handler(Looper.getMainLooper()) }
    private val ticker = object : Runnable {
        override fun run() {
            if (!isFinishing && visible.isNotEmpty()) {
                adapter.notifyItemRangeChanged(0, visible.size)
            }
            handler.postDelayed(this, 30_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pharmacist_medicine_search)
        supportActionBar?.hide()

        ivBack = findViewById(R.id.ivBack)
        tvTitle = findViewById(R.id.tvTitle)
        tvCount = findViewById(R.id.tvCount)

        etSearch = findViewById(R.id.etSearch)

        chipAll = findViewById(R.id.chipAll)
        chipLow = findViewById(R.id.chipLow)
        chipOut = findViewById(R.id.chipOut)

        rv = findViewById(R.id.rvMeds)
        emptyWrap = findViewById(R.id.emptyWrap)
        tvEmptyTitle = findViewById(R.id.tvEmptyTitle)
        tvEmptySub = findViewById(R.id.tvEmptySub)
        btnGoUpdate = findViewById(R.id.btnGoUpdate)

        tvTitle.text = getString(R.string.medicine_search_title)
        tvCount.text = getString(R.string.medicine_count_fmt, 0)

        ivBack.setOnClickListener { onBackPressedDispatcher.onBackPressed() }

        adapter = MedAdapter(
            rows = visible,
            onRowClick = { /* optional later */ }
        )
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = adapter

        btnGoUpdate.setOnClickListener {
            // keep safe
            runCatching {
                startActivity(android.content.Intent(this, AddMedicineActivity::class.java))
            }.onFailure {
                toast(getString(R.string.coming_soon))
            }
        }

        setupSearch()
        setupChips()

        showEmpty(state = false)
        loadInventory()
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        searchJob?.cancel()
        uiScope.cancel()
        super.onDestroy()
    }

    private fun setupSearch() {
        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun afterTextChanged(s: Editable?) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                searchJob?.cancel()
                val q = s?.toString().orEmpty()
                searchJob = uiScope.launch {
                    delay(180)
                    applyFilterAndSearch(q)
                }
            }
        })
    }

    private fun setupChips() {
        chipAll.setOnClickListener {
            if (currentFilter != Filter.ALL) {
                currentFilter = Filter.ALL
                updateChipUi()
                applyFilterAndSearch(etSearch.text?.toString().orEmpty())
            }
        }
        chipLow.setOnClickListener {
            if (currentFilter != Filter.LOW) {
                currentFilter = Filter.LOW
                updateChipUi()
                applyFilterAndSearch(etSearch.text?.toString().orEmpty())
            }
        }
        chipOut.setOnClickListener {
            if (currentFilter != Filter.OUT) {
                currentFilter = Filter.OUT
                updateChipUi()
                applyFilterAndSearch(etSearch.text?.toString().orEmpty())
            }
        }
        updateChipUi()
    }

    private fun updateChipUi() {
        fun setChip(tv: TextView, selected: Boolean) {
            tv.setBackgroundResource(if (selected) R.drawable.bg_chip_selected_blue else R.drawable.bg_chip_unselected_gray)
            tv.setTextColor(
                androidx.core.content.ContextCompat.getColor(
                    this,
                    if (selected) android.R.color.white else R.color.black
                )
            )
        }
        setChip(chipAll, currentFilter == Filter.ALL)
        setChip(chipLow, currentFilter == Filter.LOW)
        setChip(chipOut, currentFilter == Filter.OUT)
    }

    private fun loadInventory() {
        if (loading) return
        loading = true

        val token = AppPrefs.getToken(this).orEmpty()
        if (token.isBlank()) {
            toast(getString(R.string.please_login_again))
            finish()
            return
        }

        uiScope.launch {
            try {
                val res = withContext(Dispatchers.IO) {
                    postJsonAuth(
                        url = BASE_URL + "pharmacist/inventory_list.php",
                        token = token,
                        body = JSONObject()
                    )
                }

                if (!res.optBoolean("ok", false)) {
                    toast(res.optString("error", getString(R.string.failed)))
                    all.clear()
                    visible.clear()
                    adapter.notifyDataSetChanged()
                    tvCount.text = getString(R.string.medicine_count_fmt, 0)
                    showEmpty(state = true)
                    tvEmptyTitle.text = getString(R.string.no_inventory_title)
                    tvEmptySub.text = getString(R.string.no_inventory_sub)
                    btnGoUpdate.visibility = View.VISIBLE
                    ensureEmptyCentered()
                    return@launch
                }

                val arr = res.optJSONObject("data")?.optJSONArray("items") ?: JSONArray()

                all.clear()
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    all.add(
                        MedRow(
                            id = o.optLong("id", 0L),
                            medicineName = o.optString("medicine_name", "").trim(),
                            strength = o.optString("strength", "").trim(),
                            quantity = o.optInt("quantity", 0),
                            reorderLevel = o.optInt("reorder_level", 5),
                            priceAmount = o.optString("price_amount", "").trim(),
                            updatedAt = o.optString("updated_at", "").trim()
                        )
                    )
                }

                applyFilterAndSearch(etSearch.text?.toString().orEmpty())
            } catch (_: Throwable) {
                toast(getString(R.string.failed))
            } finally {
                loading = false
            }
        }
    }

    private fun applyFilterAndSearch(queryRaw: String) {
        val q = queryRaw.trim().lowercase(Locale.getDefault())

        val base: List<MedRow> = when (currentFilter) {
            Filter.ALL -> all
            Filter.LOW -> all.filter { it.quantity > 0 && it.quantity <= it.reorderLevel }
            Filter.OUT -> all.filter { it.quantity <= 0 }
        }

        val filtered = if (q.isBlank()) {
            base
        } else {
            base.filter {
                val hay = "${it.medicineName} ${it.strength}".lowercase(Locale.getDefault())
                hay.contains(q)
            }
        }

        visible.clear()
        visible.addAll(filtered)
        adapter.notifyDataSetChanged()

        tvCount.text = getString(R.string.medicine_count_fmt, visible.size)

        val show = all.isEmpty() || visible.isEmpty()
        showEmpty(state = show)

        if (all.isEmpty()) {
            tvEmptyTitle.text = getString(R.string.no_inventory_title)
            tvEmptySub.text = getString(R.string.no_inventory_sub)
            btnGoUpdate.visibility = View.VISIBLE
        } else if (visible.isEmpty()) {
            //  No results centered
            tvEmptyTitle.text = getString(R.string.no_results_title)
            tvEmptySub.text = getString(R.string.no_results_sub)
            btnGoUpdate.visibility = View.GONE
        }
        if (show) ensureEmptyCentered()
    }

    //  replace your existing showEmpty()
    private fun showEmpty(state: Boolean) {
        emptyWrap.visibility = if (state) View.VISIBLE else View.GONE
        rv.visibility = if (state) View.GONE else View.VISIBLE

        // reset when hiding
        if (!state) {
            emptyWrap.translationY = 0f
            return
        }

        // center after layout
        centerEmptyWrap()
    }

    //  replace your existing ensureEmptyCentered() with this
    private fun centerEmptyWrap() {
        emptyWrap.post {
            val content = findViewById<View>(android.R.id.content) ?: return@post
            val viewportH = content.height
            val eh = emptyWrap.height
            if (viewportH <= 0 || eh <= 0) return@post

            val locEmpty = IntArray(2)
            val locContent = IntArray(2)
            emptyWrap.getLocationInWindow(locEmpty)
            content.getLocationInWindow(locContent)

            val contentTop = locContent[1]
            val desiredTop = contentTop + ((viewportH - eh) / 2)
            val currentTop = locEmpty[1]

            emptyWrap.translationY = (desiredTop - currentTop).toFloat()
        }
    }



    /**  centers emptyWrap vertically if parent is ConstraintLayout */
    private fun ensureEmptyCentered() {
        val parent = emptyWrap.parent as? ConstraintLayout ?: return
        val cs = ConstraintSet()
        cs.clone(parent)
        cs.clear(emptyWrap.id)
        cs.connect(emptyWrap.id, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START)
        cs.connect(emptyWrap.id, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END)
        cs.connect(emptyWrap.id, ConstraintSet.TOP, ConstraintSet.PARENT_ID, ConstraintSet.TOP)
        cs.connect(emptyWrap.id, ConstraintSet.BOTTOM, ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM)
        cs.setVerticalBias(emptyWrap.id, 0.5f)
        cs.applyTo(parent)
    }

    private fun relativeUpdated(updatedAt: String): String {
        val ms = parseServerMs(updatedAt) ?: return getString(R.string.updated_unknown)
        val now = System.currentTimeMillis()
        val diff = now - ms
        val mins = abs(diff) / 60000L
        val hrs = mins / 60L
        val days = hrs / 24L

        val ago = when {
            mins < 1 -> getString(R.string.just_now)
            mins == 1L -> getString(R.string.minute_ago)
            mins < 60L -> getString(R.string.minutes_ago, mins.toInt())
            hrs == 1L -> getString(R.string.hour_ago)
            hrs < 24L -> getString(R.string.hours_ago, hrs.toInt())
            days == 1L -> getString(R.string.day_ago)
            else -> getString(R.string.days_ago, days.toInt())
        }
        return getString(R.string.updated_ago_fmt, ago)
    }

    private fun parseServerMs(s: String): Long? {
        val t = s.trim()
        if (t.isBlank()) return null
        return try { dfServer.parse(t)?.time } catch (_: Throwable) { null }
    }

    private fun moneyOrDash(raw: String): String {
        val v = raw.trim()
        val d = v.toDoubleOrNull() ?: return "—"
        val nf = NumberFormat.getNumberInstance(Locale.getDefault()).apply {
            maximumFractionDigits = 2
            minimumFractionDigits = 0
        }
        return nf.format(d)
    }

    private fun toast(s: String) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    }

    private fun postJsonAuth(url: String, token: String, body: JSONObject): JSONObject {
        val conn = (URL(url).openConnection() as HttpURLConnection)
        return try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 20000
            conn.readTimeout = 20000
            conn.doOutput = true
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setRequestProperty("Authorization", "Bearer $token")

            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val txt = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            runCatching { JSONObject(txt) }
                .getOrElse { JSONObject().put("ok", false).put("error", "Non-JSON response") }
        } catch (_: Throwable) {
            JSONObject().put("ok", false).put("error", "Network error")
        } finally {
            try { conn.disconnect() } catch (_: Throwable) {}
        }
    }

    // -------------------- Model + Adapter --------------------

    private data class MedRow(
        val id: Long,
        val medicineName: String,
        val strength: String,
        val quantity: Int,
        val reorderLevel: Int,
        val priceAmount: String,
        val updatedAt: String
    )

    private inner class MedAdapter(
        private val rows: List<MedRow>,
        private val onRowClick: (MedRow) -> Unit
    ) : RecyclerView.Adapter<MedAdapter.MedVH>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MedVH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_pharmacist_medicine, parent, false)
            return MedVH(v)
        }

        override fun onBindViewHolder(holder: MedVH, position: Int) {
            holder.bind(rows[position])
        }

        override fun getItemCount(): Int = rows.size

        inner class MedVH(v: View) : RecyclerView.ViewHolder(v) {
            private val tvName: TextView = v.findViewById(R.id.tvName)
            private val tvStrength: TextView = v.findViewById(R.id.tvStrength)
            private val tvMeta: TextView = v.findViewById(R.id.tvMeta)

            //  item_pharmacist_medicine (image style) uses boxQty as chip and tvQty as chip text
            private val boxQty: View = v.findViewById(R.id.boxQty)
            private val tvQty: TextView = v.findViewById(R.id.tvQty)

            // keep old ids (may be gone/hidden in layout) – we won't rely on them
            // private val tvStatus: TextView = v.findViewById(R.id.tvStatus)
            // private val vChip: View = v.findViewById(R.id.vStatusChip)

            fun bind(row: MedRow) {
                val nameLine = if (row.strength.isBlank()) {
                    row.medicineName.ifBlank { "—" }
                } else {
                    "${row.medicineName.ifBlank { "—" }} ${row.strength}"
                }

                tvName.text = nameLine

                // keep for compatibility; layout may hide it
                tvStrength.text = row.strength

                // price only (purple like image). Updated time can be used later if you want.
                val price = moneyOrDash(row.priceAmount)
                tvMeta.text = if (price == "—") "—" else "₹$price"

                val isOut = row.quantity <= 0
                val isLow = !isOut && row.quantity <= row.reorderLevel

                //  Chip text + icon + colors (exact “In Stock (50)” style)
                when {
                    isOut -> applyStatusChip(
                        bg = Color.parseColor("#FEE2E2"),
                        fg = Color.parseColor("#DC2626"),
                        iconRes = R.drawable.ic_stock_out,
                        label = "${getString(R.string.out_of_stock)} (${row.quantity.coerceAtLeast(0)})"
                    )
                    isLow -> applyStatusChip(
                        bg = Color.parseColor("#FEF3C7"),
                        fg = Color.parseColor("#B45309"),
                        iconRes = R.drawable.ic_stock_low,
                        label = "${getString(R.string.low_stock)} (${row.quantity})"
                    )
                    else -> applyStatusChip(
                        bg = Color.parseColor("#DCFCE7"),
                        fg = Color.parseColor("#059669"),
                        iconRes = R.drawable.ic_stock_in,
                        label = "${getString(R.string.in_stock)} (${row.quantity})"
                    )
                }

                // keep “Updated x ago” tick effect available if you want (not shown in image)
                // If you want it, replace tvMeta above with:
                // tvMeta.text = getString(R.string.price_updated_fmt, "₹$price", relativeUpdated(row.updatedAt))

                itemView.setOnClickListener { onRowClick(row) }
            }

            private fun applyStatusChip(bg: Int, fg: Int, iconRes: Int, label: String) {
                ViewCompat.setBackgroundTintList(boxQty, ColorStateList.valueOf(bg))

                tvQty.text = label
                tvQty.setTextColor(fg)

                tvQty.setCompoundDrawablesWithIntrinsicBounds(iconRes, 0, 0, 0)
                TextViewCompat.setCompoundDrawableTintList(tvQty, ColorStateList.valueOf(fg))
            }
        }
    }
}
