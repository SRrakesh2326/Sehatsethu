package com.simats.sehatsethu

import android.content.Context
import android.content.Intent
import android.os.Bundle

object RoleResolver {

    fun putRole(intent: Intent, role: Role): Intent {
        intent.putExtra(NavKeys.EXTRA_ROLE, role.id)
        return intent
    }

    /** Resolve role priority: savedInstanceState -> intent extra -> prefs -> default(PATIENT) */
    fun resolve(context: Context, savedInstanceState: Bundle?): Role {
        val fromState = Role.fromId(savedInstanceState?.getString(NavKeys.EXTRA_ROLE))
        if (fromState != null) return fromState

        val fromIntent = if (context is android.app.Activity) {
            Role.fromId(context.intent?.getStringExtra(NavKeys.EXTRA_ROLE))
        } else null
        if (fromIntent != null) return fromIntent

        val fromPrefs = Role.fromId(AppPrefs.getRole(context))
        return fromPrefs ?: Role.PATIENT
    }

    fun persist(context: Context, role: Role) {
        AppPrefs.setRole(context, role.id)
    }
}
