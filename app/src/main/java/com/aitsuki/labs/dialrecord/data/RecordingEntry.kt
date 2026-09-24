package com.aitsuki.labs.dialrecord.data

import java.util.UUID

enum class CallSource { SYSTEM, SDK }

internal val PHONE_NUMBER_PATTERN = Regex("\\+?[0-9]{1,32}")

/** 已封装完成的录音。通话时长与音频长度无关，null 表示未知，0 表示已确认零秒。 */
data class RecordingEntry(
    val recordingId: String,
    val source: CallSource,
    val phoneNumber: String,
    val recordingStartedAtMs: Long,
    val durationSeconds: Long? = null,
    val callLogId: Long? = null,
) {
    init {
        require(UUID.fromString(recordingId).toString() == recordingId) { "录音 ID 无效" }
        require(PHONE_NUMBER_PATTERN.matches(phoneNumber)) { "电话号码无效" }
        require(recordingStartedAtMs >= 0)
        require(durationSeconds == null || durationSeconds >= 0) { "时长不能小于零" }
        require(callLogId == null || callLogId >= 0)
    }

    // 每条录音有独立 ID 目录；同一号码、时间戳也不会覆盖另一条录音。
    // 文件名仅由 Store 落盘，调用方使用 Store.audioFile() 取得实际文件。
    val fileName: String
        get() {
            val duration = durationSeconds?.let { "_$it" }.orEmpty()
            return "${phoneNumber}_${recordingStartedAtMs}$duration.m4a"
        }
}

data class CallLogInfo(val id: Long, val durationSeconds: Long) {
    init { require(id >= 0 && durationSeconds >= 0) }
}
