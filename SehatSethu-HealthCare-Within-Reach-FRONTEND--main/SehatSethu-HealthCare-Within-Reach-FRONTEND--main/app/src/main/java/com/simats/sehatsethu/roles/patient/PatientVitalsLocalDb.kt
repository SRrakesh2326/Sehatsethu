package com.simats.sehatsethu.roles.patient

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class PatientVitalsLocalDb(ctx: Context) : SQLiteOpenHelper(ctx, DB_NAME, null, DB_VER) {

    data class Row(
        val localId: Long,
        val serverId: Long,
        val patientId: Long,
        val recordedAtMs: Long,
        val systolic: Int?,
        val diastolic: Int?,
        val sugar: Int?,
        val sugarContext: String,
        val temperatureF: Double?,
        val weightKg: Double?,
        val notes: String,
        val synced: Int
    )

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS vitals_local (
              local_id INTEGER PRIMARY KEY AUTOINCREMENT,
              server_id INTEGER NOT NULL DEFAULT 0,
              patient_id INTEGER NOT NULL DEFAULT 0,
              recorded_at_ms INTEGER NOT NULL,
              systolic INTEGER,
              diastolic INTEGER,
              sugar INTEGER,
              sugar_context TEXT NOT NULL DEFAULT 'FASTING',
              temperature_f REAL,
              weight_kg REAL,
              notes TEXT NOT NULL DEFAULT '',
              synced INTEGER NOT NULL DEFAULT 0
            );
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_vitals_local_patient ON vitals_local(patient_id, recorded_at_ms DESC);")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_vitals_local_synced ON vitals_local(synced, recorded_at_ms);")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 1) onCreate(db)
    }

    fun insertLocal(
        patientId: Long,
        recordedAtMs: Long,
        systolic: Int?,
        diastolic: Int?,
        sugar: Int?,
        sugarContext: String,
        temperatureF: Double?,
        weightKg: Double?,
        notes: String
    ): Long {
        val cv = ContentValues().apply {
            put("server_id", 0)
            put("patient_id", if (patientId > 0) patientId else 0)
            put("recorded_at_ms", recordedAtMs)
            if (systolic != null) put("systolic", systolic) else putNull("systolic")
            if (diastolic != null) put("diastolic", diastolic) else putNull("diastolic")
            if (sugar != null) put("sugar", sugar) else putNull("sugar")
            put("sugar_context", sugarContext)
            if (temperatureF != null) put("temperature_f", temperatureF) else putNull("temperature_f")
            if (weightKg != null) put("weight_kg", weightKg) else putNull("weight_kg")
            put("notes", notes)
            put("synced", 0)
        }
        return writableDatabase.insert("vitals_local", null, cv)
    }

    fun markSynced(localId: Long, serverId: Long) {
        val cv = ContentValues().apply {
            put("server_id", serverId)
            put("synced", 1)
        }
        writableDatabase.update("vitals_local", cv, "local_id=?", arrayOf(localId.toString()))
    }

    /**  Use this when you DO NOT know patient_id (your case). */
    fun listPendingAny(limit: Int = 25): List<Row> {
        val out = ArrayList<Row>()
        val c = readableDatabase.rawQuery(
            "SELECT * FROM vitals_local WHERE synced=0 ORDER BY recorded_at_ms ASC LIMIT ?",
            arrayOf(limit.toString())
        )
        c.use {
            while (it.moveToNext()) out.add(readRow(it))
        }
        return out
    }

    /** If later you store patientId in prefs, you can use this safely too. */
    fun listPendingByPatient(patientId: Long, limit: Int = 25): List<Row> {
        val out = ArrayList<Row>()
        val c = readableDatabase.rawQuery(
            "SELECT * FROM vitals_local WHERE patient_id=? AND synced=0 ORDER BY recorded_at_ms ASC LIMIT ?",
            arrayOf(patientId.toString(), limit.toString())
        )
        c.use {
            while (it.moveToNext()) out.add(readRow(it))
        }
        return out
    }

    private fun readRow(c: android.database.Cursor): Row {
        fun i(name: String) = c.getColumnIndexOrThrow(name)
        return Row(
            localId = c.getLong(i("local_id")),
            serverId = c.getLong(i("server_id")),
            patientId = c.getLong(i("patient_id")),
            recordedAtMs = c.getLong(i("recorded_at_ms")),
            systolic = if (c.isNull(i("systolic"))) null else c.getInt(i("systolic")),
            diastolic = if (c.isNull(i("diastolic"))) null else c.getInt(i("diastolic")),
            sugar = if (c.isNull(i("sugar"))) null else c.getInt(i("sugar")),
            sugarContext = c.getString(i("sugar_context")) ?: "FASTING",
            temperatureF = if (c.isNull(i("temperature_f"))) null else c.getDouble(i("temperature_f")),
            weightKg = if (c.isNull(i("weight_kg"))) null else c.getDouble(i("weight_kg")),
            notes = c.getString(i("notes")) ?: "",
            synced = c.getInt(i("synced"))
        )
    }

    companion object {
        private const val DB_NAME = "sehatsethu_local.db"
        private const val DB_VER = 1
    }
}
