package com.simats.sehatsethu.roles.doctor

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData

object DoctorHomeStore {

    private val _state = MutableLiveData<DoctorHomeState>(DoctorHomeState.Loading)
    val state: LiveData<DoctorHomeState> = _state

    suspend fun refresh(context: Context) {
        _state.postValue(DoctorHomeState.Loading)
        try {
            val data = DoctorHomeRepo.fetchDoctorHome(context)
            _state.postValue(DoctorHomeState.Ready(data))
        } catch (_: Exception) {
            // never crash; keep safe defaults
            _state.postValue(DoctorHomeState.Error)
        }
    }
}
