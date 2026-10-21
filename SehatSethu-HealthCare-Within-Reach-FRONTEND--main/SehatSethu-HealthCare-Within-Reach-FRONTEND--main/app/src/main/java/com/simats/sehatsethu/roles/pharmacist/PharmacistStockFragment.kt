package com.simats.sehatsethu.roles.pharmacist

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.button.MaterialButton
import com.simats.sehatsethu.ApiConfig.BASE_URL
import com.simats.sehatsethu.AppPrefs
import com.simats.sehatsethu.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class PharmacistStockFragment : Fragment() {



    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        val v = i.inflate(R.layout.fragment_pharmacist_stock, c, false)

        val swipe = v.findViewById<SwipeRefreshLayout>(R.id.swipeRefresh)

        val tvTitle = v.findViewById<TextView>(R.id.tvTitle)
        val tvSubtitle = v.findViewById<TextView>(R.id.tvSubtitle)
        val tvSwipeHint = v.findViewById<TextView>(R.id.tvSwipeHint)

        val tvTotal = v.findViewById<TextView>(R.id.tvTotalCount)
        val tvLow = v.findViewById<TextView>(R.id.tvLowCount)

        val btnAdd = v.findViewById<MaterialButton>(R.id.btnAddMedicine)
        val btnUpdate = v.findViewById<MaterialButton>(R.id.btnUpdateStock)

        tvTitle.text = getString(R.string.stock)
        tvSubtitle.text = getString(R.string.manage_stock_subtitle)
        tvSwipeHint.text = getString(R.string.swipe_to_refresh_hint)

        // default values until API loads
        tvTotal.text = getString(R.string.total_items_fmt, 0)
        tvLow.text = getString(R.string.low_stock_items_fmt, 0)

        btnAdd.setOnClickListener {
            startActivity(Intent(requireContext(), AddMedicineActivity::class.java))
        }

        btnUpdate.setOnClickListener {
            startActivity(Intent(requireContext(), UpdateStockActivity::class.java))
        }

        swipe.setOnRefreshListener {
            loadStats(tvTotal, tvLow, swipe, showToast = true)
        }

        // initial load (also shows user swipe is available via hint)
        loadStats(tvTotal, tvLow, swipe, showToast = false)

        return v
    }

    private fun loadStats(
        tvTotal: TextView,
        tvLow: TextView,
        swipe: SwipeRefreshLayout,
        showToast: Boolean
    ) {
        val token = AppPrefs.getToken(requireContext()).orEmpty()
        if (token.isBlank()) {
            swipe.isRefreshing = false
            return
        }

        swipe.isRefreshing = true

        lifecycleScope.launch {
            val res = withContext(Dispatchers.IO) {
                getJsonAuth(
                    url = BASE_URL + "pharmacist/inventory_stats.php",
                    token = token
                )
            }

            swipe.isRefreshing = false

            if (res.optBoolean("ok", false)) {
                val total = res.optInt("total_items", 0)
                val low = res.optInt("low_stock_items", 0)

                tvTotal.text = getString(R.string.total_items_fmt, total)
                tvLow.text = getString(R.string.low_stock_items_fmt, low)

                if (showToast) {
                    Toast.makeText(requireContext(), getString(R.string.refreshed), Toast.LENGTH_SHORT).show()
                }
            } else {
                if (showToast) {
                    Toast.makeText(
                        requireContext(),
                        res.optString("error", getString(R.string.failed)),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    private fun getJsonAuth(url: String, token: String): JSONObject {
        val conn = (URL(url).openConnection() as HttpURLConnection)
        return try {
            conn.requestMethod = "GET"
            conn.connectTimeout = 15000
            conn.readTimeout = 15000
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("Authorization", "Bearer $token")

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
