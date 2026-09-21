package com.aitsuki.labs.dialrecord.data

data class RecordingEntry(
    val phoneNumber: String,
    /** OFFHOOK 时的 Unix 毫秒时间戳，用于文件名。 */
    val recordingStartedAtMs: Long,
    /** null 表示尚未关联系统通话记录。 */
    val callLog: CallLogInfo? = null,
) {
    val fileName: String
        get() = buildString {
            append(phoneNumber).append('_').append(recordingStartedAtMs)
            callLog?.let { append('_').append(it.durationSeconds) }
            append(".m4a")
        }
}

data class CallLogInfo(val id: Long, val durationSeconds: Long) {
    init {
        require(id >= 0 && durationSeconds >= 0)
    }
}
