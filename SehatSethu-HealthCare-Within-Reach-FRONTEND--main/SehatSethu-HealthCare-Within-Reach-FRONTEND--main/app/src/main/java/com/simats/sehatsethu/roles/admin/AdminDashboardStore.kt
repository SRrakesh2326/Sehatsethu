package com.simats.sehatsethu.roles.admin

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class AdminStats(
    val pending: Int,
    val approved: Int,
    val rejected: Int,
    val total: Int
)

sealed class AdminDashboardState {
    object Loading : AdminDashboardState()
    data class Ready(val stats: AdminStats, val recent: List<AdminUserRow>) : AdminDashboardState()
    data class Error(val message: String) : AdminDashboardState()
}

object AdminDashboardStore {

    private val _state = MutableLiveData<AdminDashboardState>()
    val state: LiveData<AdminDashboardState> = _state

    suspend fun refresh(ctx: Context, roleParam: String) {
        _state.postValue(AdminDashboardState.Loading)

        val statsRes = withContext(Dispatchers.IO) {
            AdminApi.get(ctx, "admin/stats.php?role=$roleParam")
        }

        val usersRes = withContext(Dispatchers.IO) {
            AdminApi.get(ctx, "admin/users.php?filter=under_review&role=$roleParam&limit=10")
        }

        val statsJson = statsRes.json
        val usersJson = usersRes.json

        val okStats = statsJson?.optBoolean("ok", false) == true
        val okUsers = usersJson?.optBoolean("ok", false) == true

        if (!okStats || !okUsers) {
            val msg =
                statsJson?.optString("error")
                    ?: usersJson?.optString("error")
                    ?: "Couldn’t load data"
            _state.postValue(AdminDashboardState.Error(msg))
            return
        }

        val stats = parseStats(statsJson)
        val recent = parseUsers(usersJson)

        _state.postValue(AdminDashboardState.Ready(stats, recent))
    }

    private fun parseStats(j: JSONObject?): AdminStats {
        if (j == null) return AdminStats(0, 0, 0, 0)
        return AdminStats(
            pending = j.optInt("pending", 0),
            approved = j.optInt("approved", 0),
            rejected = j.optInt("rejected", 0),
            total = j.optInt("total", 0)
        )
    }

    private fun parseUsers(j: JSONObject?): List<AdminUserRow> {
        if (j == null) return emptyList()
        val arr = j.optJSONArray("users") ?: return emptyList()

        val out = ArrayList<AdminUserRow>(arr.length())
        for (k in 0 until arr.length()) {
            val u = arr.optJSONObject(k) ?: continue
            out.add(
                AdminUserRow(
                    id = u.optLong("id", 0L),
                    fullName = u.optString("full_name", "User"),
                    role = u.optString("role", ""),
                    status = u.optString("status", "PENDING"),
                    subtitle = u.optString("subtitle", ""),
                    appliedAt = u.optString("applied_at", ""),
                    docsCount = u.optInt("docs_count", 0)
                )
            )
        }
        return out
    }
}
