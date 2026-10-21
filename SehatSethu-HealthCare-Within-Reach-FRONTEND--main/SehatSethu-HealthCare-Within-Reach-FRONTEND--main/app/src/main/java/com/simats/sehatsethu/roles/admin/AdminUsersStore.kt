package com.simats.sehatsethu.roles.admin

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

//  Row model used by AdminUsersFragment + Adapter
data class AdminUserRow(
    val id: Long,
    val fullName: String,
    val role: String,
    val status: String,
    val subtitle: String? = null,     // doctor: specialization, pharmacist: village/pharmacy
    val appliedAt: String? = null,
    val docsCount: Int = 0            //  "No. of uploaded docs"
)

sealed class AdminUsersState {
    object Loading : AdminUsersState()
    data class Ready(val users: List<AdminUserRow>) : AdminUsersState()
    data class Error(val message: String) : AdminUsersState()
}

object AdminUsersStore {

    private val _state = MutableLiveData<AdminUsersState>()
    val state: LiveData<AdminUsersState> = _state

    suspend fun load(ctx: Context, filter: String) {
        _state.postValue(AdminUsersState.Loading)

        val res = withContext(Dispatchers.IO) {
            AdminApi.get(ctx, "admin/users.php?filter=$filter&role=all&limit=500")
        }

        val j = res.json
        val ok = j?.optBoolean("ok", false) == true

        if (!ok) {
            val msgFromJson = j?.optString("error").orEmpty()

            // If response isn't JSON (404/HTML/fatal), show a helpful message
            val snippet = (res.error ?: "").replace("\n", " ").take(180)

            val msg = when {
                msgFromJson.isNotBlank() -> msgFromJson
                res.code == 404 -> "Endpoint not found (404). Check BASE_URL and path."
                res.code == 401 -> "Unauthorized (401). Token missing/invalid."
                res.code == 403 -> "Forbidden (403). Admin only."
                res.code in 500..599 -> "Server error (${res.code}). $snippet"
                else -> "Couldn’t load data (${res.code}). $snippet\n${res.url}"
            }

            _state.postValue(AdminUsersState.Error(msg))
            return
        }

        val arr = j?.optJSONArray("users") ?: run {
            _state.postValue(AdminUsersState.Ready(emptyList()))
            return
        }

        val out = ArrayList<AdminUserRow>(arr.length())
        for (k in 0 until arr.length()) {
            val u = arr.optJSONObject(k) ?: continue

            out.add(
                AdminUserRow(
                    id = u.optLong("id", 0L),
                    fullName = u.optString("full_name", "User"),
                    role = u.optString("role", ""),
                    status = u.optString("status", "PENDING"),
                    appliedAt = u.optString("applied_at", ""),
                    subtitle = u.optString("subtitle", ""),      //  backend must send this
                    docsCount = u.optInt("docs_count", 0)        //  backend must send this
                )
            )
        }

        _state.postValue(AdminUsersState.Ready(out))
    }
}
