package com.simats.sehatsethu

import android.graphics.Color
import android.widget.EditText

object UiTuning {

    // Always readable on your current white layouts
    fun makeEditTextReadable(et: EditText) {
        et.setTextColor(Color.parseColor("#0F172A"))      // slate-900
        et.setHintTextColor(Color.parseColor("#94A3B8"))  // slate-400
    }
}
