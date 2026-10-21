package com.simats.sehatsethu.roles.doctor

import android.os.Bundle
import com.simats.sehatsethu.BaseActivity
import com.simats.sehatsethu.R

class DoctorConsultationsActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_doctor_consultations)
        supportActionBar?.hide()

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragmentContainer, DoctorConsultFragment())
                .commit()
        }
    }
}
