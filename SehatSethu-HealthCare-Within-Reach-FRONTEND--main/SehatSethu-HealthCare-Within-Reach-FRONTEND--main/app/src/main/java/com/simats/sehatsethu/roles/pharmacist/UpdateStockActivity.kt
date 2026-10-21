package com.simats.sehatsethu.roles.pharmacist

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
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.simats.sehatsethu.AppPrefs
import com.simats.sehatsethu.BaseActivity
import com.simats.sehatsethu.R
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import com.simats.sehatsethu.ApiConfig.BASE_URL
import java.util.Locale

class UpdateStockActivity : BaseActivity() {

    private val API_BASE = BASE_URL;
    private val uiScope = MainScope()

    private lateinit var ivBack: ImageView
    private lateinit var etSearch: EditText
    private lateinit var rv: RecyclerView

    private lateinit var emptyWrap: View
    private lateinit var btnEmptyAdd: View

    private val all = ArrayList<StockRow>()
    private val visible = ArrayList<StockRow>()

    private lateinit var adapter: StockAdapter

    private var searchJob: Job? = null
    private val updateJobs = HashMap<Long, Job>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pharmacist_update_stock)
        supportActionBar?.hide()

        ivBack = findViewById(R.id.ivBack)
        etSearch = findViewById(R.id.etSearch)
        rv = findViewById(R.id.rvStock)

        emptyWrap = findViewById(R.id.emptyWrap)
        btnEmptyAdd = findViewById(R.id.btnEmptyAdd)

        ivBack.setOnClickListener { onBackPressedDispatcher.onBackPressed() }

        adapter = StockAdapter(
            items = visible,
            onMinusBig = { r -> changeQty(r, -5) },
            onMinusOne = { r -> changeQty(r, -1) },
            onPlusOne = { r -> changeQty(r, +1) },
            onPlusBig = { r -> changeQty(r, +5) },
            onQtyClick = { r -> openSetQuantityDialog(r) }
        )

        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = adapter

        btnEmptyAdd.setOnClickListener {
            // go to AddMedicineActivity
            startActivity(android.content.Intent(this, AddMedicineActivity::class.java))
        }

        setupSearch()
        loadInventory()
    }

    override fun onResume() {
        super.onResume()
        // refresh after adding new item
        loadInventory()
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
                    applyFilter(q)
                }
            }
        })
    }

    private fun loadInventory() {
        val token = AppPrefs.getToken(this).orEmpty()
        if (token.isBlank()) {
            toast(getString(R.string.please_login_again))
            finish()
            return
        }

        uiScope.launch {
            val res = withContext(Dispatchers.IO) {
                postJsonAuth(
                    url = API_BASE + "pharmacist/inventory_list.php",
                    token = token,
                    body = JSONObject()
                )
            }

            val ok = res.optBoolean("ok", false)
            if (!ok) {
                toast(res.optString("error", getString(R.string.failed)))
                return@launch
            }

            val items = res.optJSONObject("data")?.optJSONArray("items") ?: JSONArray()

            all.clear()
            visible.clear()

            for (i in 0 until items.length()) {
                val o = items.optJSONObject(i) ?: continue
                all.add(
                    StockRow(
                        id = o.optLong("id", 0L),
                        medicineName = o.optString("medicine_name", ""),
                        strength = o.optString("strength", ""),
                        quantity = o.optInt("quantity", 0),
                        reorderLevel = o.optInt("reorder_level", 5)
                    )
                )
            }

            applyFilter(etSearch.text?.toString().orEmpty())
        }
    }

    private fun applyFilter(queryRaw: String) {
        val q = queryRaw.trim().lowercase(Locale.getDefault())

        val filtered = if (q.isBlank()) {
            all.toList()
        } else {
            all.filter {
                val hay = "${it.medicineName} ${it.strength}".lowercase(Locale.getDefault())
                hay.contains(q)
            }
        }

        visible.clear()
        visible.addAll(filtered)
        adapter.notifyDataSetChanged()

        // empty state
        if (all.isEmpty()) {
            emptyWrap.visibility = View.VISIBLE
            rv.visibility = View.GONE
        } else {
            emptyWrap.visibility = View.GONE
            rv.visibility = View.VISIBLE
        }
    }

    private fun changeQty(row: StockRow, delta: Int) {
        val newQty = (row.quantity + delta).coerceAtLeast(0)
        if (newQty == row.quantity) return
        setQty(row, newQty)
    }

    private fun setQty(row: StockRow, qty: Int) {
        row.quantity = qty
        val idx = visible.indexOfFirst { it.id == row.id }
        if (idx >= 0) adapter.notifyItemChanged(idx)

        updateJobs[row.id]?.cancel()
        updateJobs[row.id] = uiScope.launch {
            delay(260)
            pushQtyToServer(row)
        }
    }

    private suspend fun pushQtyToServer(row: StockRow) {
        val token = AppPrefs.getToken(this).orEmpty()
        if (token.isBlank()) return

        val res = withContext(Dispatchers.IO) {
            postJsonAuth(
                url = API_BASE + "pharmacist/inventory_set_quantity.php",
                token = token,
                body = JSONObject().apply {
                    put("item_id", row.id)
                    put("quantity", row.quantity)
                }
            )
        }

        val ok = res.optBoolean("ok", false)
        if (!ok) {
            withContext(Dispatchers.Main) {
                toast(res.optString("error", getString(R.string.failed)))
                loadInventory()
            }
        }
    }

    private fun openSetQuantityDialog(row: StockRow) {
        val view = layoutInflater.inflate(R.layout.dialog_set_quantity, null, false)
        val et = view.findViewById<TextInputEditText>(R.id.etQty)
        et.setText(row.quantity.toString())

        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.set_quantity))
            .setView(view)
            .setNegativeButton(getString(R.string.cancel), null)
            .setPositiveButton(getString(R.string.save)) { _, _ ->
                val txt = et.text?.toString()?.trim().orEmpty()
                val q = txt.toIntOrNull()
                if (q == null || q < 0) {
                    toast(getString(R.string.invalid_quantity))
                    return@setPositiveButton
                }
                setQty(row, q)
            }
            .show()
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        searchJob?.cancel()
        updateJobs.values.forEach { it.cancel() }
        uiScope.cancel()
        super.onDestroy()
    }

    private fun postJsonAuth(url: String, token: String, body: JSONObject): JSONObject {
        val conn = (URL(url).openConnection() as HttpURLConnection)
        return try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 60000
            conn.readTimeout = 60000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setRequestProperty("Authorization", "Bearer $token")

            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val txt = stream.bufferedReader().use { it.readText() }
            JSONObject(txt)
        } catch (_: Throwable) {
            JSONObject().put("ok", false).put("error", "Network error")
        } finally {
            conn.disconnect()
        }
    }
}

private data class StockRow(
    val id: Long,
    val medicineName: String,
    val strength: String,
    var quantity: Int,
    val reorderLevel: Int
)

private class StockAdapter(
    private val items: List<StockRow>,
    private val onMinusBig: (StockRow) -> Unit,
    private val onMinusOne: (StockRow) -> Unit,
    private val onPlusOne: (StockRow) -> Unit,
    private val onPlusBig: (StockRow) -> Unit,
    private val onQtyClick: (StockRow) -> Unit
) : RecyclerView.Adapter<StockVH>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): StockVH {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_stock_row, parent, false)
        return StockVH(view, onMinusBig, onMinusOne, onPlusOne, onPlusBig, onQtyClick)
    }

    override fun onBindViewHolder(holder: StockVH, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size
}

private class StockVH(
    itemView: View,
    private val onMinusBig: (StockRow) -> Unit,
    private val onMinusOne: (StockRow) -> Unit,
    private val onPlusOne: (StockRow) -> Unit,
    private val onPlusBig: (StockRow) -> Unit,
    private val onQtyClick: (StockRow) -> Unit
) : RecyclerView.ViewHolder(itemView) {

    private val tvName: TextView = itemView.findViewById(R.id.tvMedName)
    private val chip: com.google.android.material.card.MaterialCardView = itemView.findViewById(R.id.chipStatus)
    private val ivChip: ImageView = itemView.findViewById(R.id.ivChipIcon)
    private val tvChip: TextView = itemView.findViewById(R.id.tvChipText)

    private val btnMinusBig: View = itemView.findViewById(R.id.btnMinusBig)
    private val btnMinusOne: View = itemView.findViewById(R.id.btnMinusOne)
    private val boxQty: View = itemView.findViewById(R.id.boxQty)
    private val tvQty: TextView = itemView.findViewById(R.id.tvQty)
    private val btnPlusOne: View = itemView.findViewById(R.id.btnPlusOne)
    private val btnPlusBig: View = itemView.findViewById(R.id.btnPlusBig)

    fun bind(row: StockRow) {
        val ctx = itemView.context

        val title = if (row.strength.isBlank()) row.medicineName else "${row.medicineName} ${row.strength}"
        tvName.text = title
        tvQty.text = row.quantity.toString()

        val isOut = row.quantity <= 0
        val isLow = !isOut && row.quantity <= row.reorderLevel

        when {
            isOut -> {
                chip.setCardBackgroundColor(0xFFFFE4E6.toInt())
                ivChip.setImageResource(R.drawable.ic_stock_out)
                ivChip.setColorFilter(0xFFDC2626.toInt())
                tvChip.setTextColor(0xFFDC2626.toInt())
                tvChip.text = ctx.getString(R.string.stock_out_fmt, row.quantity)
            }
            isLow -> {
                chip.setCardBackgroundColor(0xFFFFF3C4.toInt())
                ivChip.setImageResource(R.drawable.ic_stock_low)
                ivChip.setColorFilter(0xFFB45309.toInt())
                tvChip.setTextColor(0xFFB45309.toInt())
                tvChip.text = ctx.getString(R.string.stock_low_fmt, row.quantity)
            }
            else -> {
                chip.setCardBackgroundColor(0xFFDFF7EA.toInt())
                ivChip.setImageResource(R.drawable.ic_stock_in)
                ivChip.setColorFilter(0xFF059669.toInt())
                tvChip.setTextColor(0xFF059669.toInt())
                tvChip.text = ctx.getString(R.string.stock_in_fmt, row.quantity)
            }
        }

        btnMinusBig.setOnClickListener { onMinusBig(row) }
        btnMinusOne.setOnClickListener { onMinusOne(row) }
        btnPlusOne.setOnClickListener { onPlusOne(row) }
        btnPlusBig.setOnClickListener { onPlusBig(row) }
        boxQty.setOnClickListener { onQtyClick(row) }
        tvQty.setOnClickListener { onQtyClick(row) }
    }
}
