package com.aitsuki.labs.dialrecord.data

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File

object RecordingStore {
    fun directory(context: Context): File = File(context.filesDir, "recordings").apply {
        check(isDirectory || mkdirs()) { "无法创建录音目录" }
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences("recording_entries", Context.MODE_PRIVATE)

    @Synchronized
    fun saveMetadata(context: Context, entry: RecordingEntry) {
        check(prefs(context).edit().putString(entry.recordingStartedAtMs.toString(), encode(entry)).commit()) {
            "无法保存录音信息"
        }
    }

    @Synchronized
    fun removeMetadata(context: Context, recordingStartedAtMs: Long) {
        check(prefs(context).edit().remove(recordingStartedAtMs.toString()).commit())
    }

    @Synchronized
    fun loadEntries(context: Context): List<RecordingEntry> = prefs(context).all.values.mapNotNull { raw ->
        try {
            decode(raw as String)
        } catch (e: Exception) {
            Log.e("RecordingStore", "录音元数据损坏", e)
            null
        }
    }.sortedByDescending { it.recordingStartedAtMs }

    internal fun encode(entry: RecordingEntry): String = JSONObject().apply {
        put("phoneNumber", entry.phoneNumber)
        put("recordingStartedAtMs", entry.recordingStartedAtMs)
        entry.callLog?.let { call ->
            put("callLog", JSONObject().apply {
                put("id", call.id)
                put("durationSeconds", call.durationSeconds)
            })
        }
    }.toString()

    internal fun decode(raw: String): RecordingEntry {
        val json = JSONObject(raw)
        return RecordingEntry(
            phoneNumber = json.getString("phoneNumber"),
            recordingStartedAtMs = json.getLong("recordingStartedAtMs"),
            callLog = json.optJSONObject("callLog")?.let {
                CallLogInfo(it.getLong("id"), it.getLong("durationSeconds"))
            },
        )
    }
}
