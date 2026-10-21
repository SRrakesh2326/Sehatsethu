package com.simats.sehatsethu.roles.pharmacist

import android.os.Bundle
import android.text.InputType
import android.widget.ImageView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText
import com.simats.sehatsethu.ApiConfig.BASE_URL
import com.simats.sehatsethu.AppPrefs
import com.simats.sehatsethu.BaseActivity
import com.simats.sehatsethu.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class AddMedicineActivity : BaseActivity() {

    private val API_BASE = BASE_URL

    private lateinit var ivBack: ImageView

    private lateinit var etMedicineName: MaterialAutoCompleteTextView
    private lateinit var etStrength: TextInputEditText
    private lateinit var etQty: TextInputEditText
    private lateinit var etReorder: TextInputEditText

    //  optional: if your XML has a price field, we read it safely without hard-referencing an id
    private var etPriceMaybe: TextInputEditText? = null

    private lateinit var btnSaveAddAnother: MaterialButton
    private lateinit var btnSaveClose: MaterialButton

    private var loading = false

    private val catalog = mutableListOf<String>()
    private var selectedMedicine: String? = null

    private lateinit var adapter: android.widget.ArrayAdapter<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_add_medicine)
        supportActionBar?.hide()

        ivBack = findViewById(R.id.ivBack)

        etMedicineName = findViewById(R.id.etMedicineName)
        etStrength = findViewById(R.id.etStrength)
        etQty = findViewById(R.id.etQuantity)
        etReorder = findViewById(R.id.etReorderLevel)

        //  Try to find a price input if you already added it in XML (doesn't break build if not present)
        etPriceMaybe = findOptionalPriceField()

        btnSaveAddAnother = findViewById(R.id.btnSaveAddAnother)
        btnSaveClose = findViewById(R.id.btnSaveClose)

        ivBack.setOnClickListener { onBackPressedDispatcher.onBackPressed() }

        setupMedicineDropdownStrict()

        btnSaveAddAnother.setOnClickListener { submit(closeAfter = false) }
        btnSaveClose.setOnClickListener { submit(closeAfter = true) }
    }

    private fun findOptionalPriceField(): TextInputEditText? {
        val names = listOf("etPrice", "etPriceAmount", "etUnitPrice", "etMrp")
        for (n in names) {
            val id = resources.getIdentifier(n, "id", packageName)
            if (id != 0) {
                return runCatching { findViewById<TextInputEditText>(id) }.getOrNull()
            }
        }
        return null
    }

    private fun setupMedicineDropdownStrict() {
        catalog.clear()
        catalog.addAll(
            listOf(
                getString(R.string.med_paracetamol),
                getString(R.string.med_cetirizine),
                getString(R.string.med_azithromycin),
                getString(R.string.med_omeprazole),
                getString(R.string.med_vitamin_c),
                getString(R.string.med_cough_syrup)
            ).distinct()
        )

        adapter = android.widget.ArrayAdapter(this, R.layout.item_dropdown_medicine, catalog)
        etMedicineName.setAdapter(adapter)
        etMedicineName.threshold = 0

        //  WHITE dropdown background + subtle border
        ContextCompat.getDrawable(this, R.drawable.bg_dropdown_popup)?.let {
            etMedicineName.setDropDownBackgroundDrawable(it)
        }

        //  HARD BLOCK typing: must pick from dropdown
        etMedicineName.inputType = InputType.TYPE_NULL
        etMedicineName.keyListener = null
        etMedicineName.isCursorVisible = false
        etMedicineName.setTextIsSelectable(false)

        etMedicineName.setOnClickListener { if (!loading) etMedicineName.showDropDown() }
        etMedicineName.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus && !loading) etMedicineName.showDropDown()
        }

        etMedicineName.setOnItemClickListener { _, _, pos, _ ->
            selectedMedicine = adapter.getItem(pos)
            etMedicineName.setText(selectedMedicine ?: "", false)
        }

        etMedicineName.setOnDismissListener {
            val now = etMedicineName.text?.toString()?.trim().orEmpty()
            if (now.isBlank()) selectedMedicine = null
        }
    }

    private fun submit(closeAfter: Boolean) {
        if (loading) return

        val token = AppPrefs.getToken(this).orEmpty()
        if (token.isBlank()) {
            toast(getString(R.string.please_login_again))
            finish()
            return
        }

        val picked = selectedMedicine?.trim().orEmpty()
        if (picked.isBlank()) {
            toast(getString(R.string.select_medicine_from_dropdown))
            etMedicineName.requestFocus()
            etMedicineName.showDropDown()
            return
        }

        val strength = etStrength.text?.toString()?.trim().orEmpty()
        val qty = etQty.text?.toString()?.trim()?.toIntOrNull()
        val reorder = etReorder.text?.toString()?.trim()?.toIntOrNull()

        //  price (optional UI). If you didn't add price field, it stays 0.
        val priceAmount = etPriceMaybe?.text?.toString()?.trim()?.toDoubleOrNull() ?: 0.0
        if (priceAmount < 0) {
            toast(getString(R.string.invalid_price))
            return
        }

        if (qty == null || reorder == null) {
            toast(getString(R.string.err_fill_all_fields))
            return
        }
        if (qty < 0 || reorder < 0) {
            toast(getString(R.string.invalid_quantity))
            return
        }

        setLoading(true)

        lifecycleScope.launch {
            val res = withContext(Dispatchers.IO) {
                postJsonAuth(
                    url = API_BASE + "pharmacist/inventory_add_item.php",
                    token = token,
                    body = JSONObject().apply {
                        put("medicine_name", picked)
                        put("strength", strength)
                        put("quantity", qty)
                        put("reorder_level", reorder)
                        put("price_amount", priceAmount) //  SAVES NOW
                    }
                )
            }

            setLoading(false)

            if (res.optBoolean("ok", false)) {
                if (closeAfter) {
                    toast(getString(R.string.medicine_saved))
                    setResult(RESULT_OK)
                    finish()
                } else {
                    toast(getString(R.string.medicine_saved_add_another))
                    selectedMedicine = null
                    etMedicineName.setText("", false)
                    etStrength.setText("")
                    etQty.setText("")
                    etReorder.setText("")
                    etPriceMaybe?.setText("")
                    etMedicineName.requestFocus()
                    etMedicineName.showDropDown()
                }
            } else {
                toast(res.optString("error", getString(R.string.failed)))
            }
        }
    }

    private fun setLoading(isLoading: Boolean) {
        loading = isLoading
        btnSaveAddAnother.isEnabled = !isLoading
        btnSaveClose.isEnabled = !isLoading
        btnSaveAddAnother.alpha = if (isLoading) 0.7f else 1f
        btnSaveClose.alpha = if (isLoading) 0.7f else 1f

        etMedicineName.isEnabled = !isLoading
        etStrength.isEnabled = !isLoading
        etQty.isEnabled = !isLoading
        etReorder.isEnabled = !isLoading
        etPriceMaybe?.isEnabled = !isLoading
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
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
