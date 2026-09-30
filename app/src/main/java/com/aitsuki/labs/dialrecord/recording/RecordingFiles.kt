package com.aitsuki.labs.dialrecord.recording

import com.aitsuki.labs.dialrecord.AppLog
import java.io.File
import java.util.UUID

/** 目录就是队列。staging 中的音频无论是否封装完成，都不能被上传器读取。 */
class RecordingFiles(root: File) {
    val staging = File(root, "staging")
    val pending = File(root, "pending")

    fun newStagingFile(): File {
        check(staging.isDirectory || staging.mkdirs()) { "Unable to create the recording staging directory" }
        return File(staging, "${UUID.randomUUID()}.part")
    }

    /** 同一文件系统内重命名发布；只在拿到完整关联字段后调用，失败保留原文件。 */
    fun publish(audio: File, userId: String, number: String, dateMs: Long, duration: Long): File? {
        val name = uploadName(userId, number, dateMs, duration)
        check(
            audio.parentFile?.canonicalFile == staging.canonicalFile &&
                    audio.extension == "m4a" && audio.isFile && audio.length() > 0
        ) { "The recording has not been finalized correctly" }
        if (duration < MIN_UPLOAD_DURATION_SECONDS) {
            check(audio.delete()) { "Short recording could not be deleted" }
            AppLog.info(
                "通话不足 5 秒，已删除录音，不上传：号码=%s，时长=%s 秒，文件=%s",
                number, duration, audio.name
            )
            return null
        }
        check(pending.isDirectory || pending.mkdirs()) { "Unable to create the pending upload directory" }
        val target = File(pending, name)
        // 不能添加 UUID 改变协议，也不能覆盖另一次通话的文件。
        check(!target.exists()) { "Pending upload filename conflict: $name" }
        check(audio.renameTo(target)) { "Unable to publish the recording for upload" }
        AppLog.info(
            "录音已加入待上传目录：原文件=%s，待上传文件=%s，大小=%s 字节",
            audio.name, target.name, target.length()
        )
        return target
    }

    companion object {
        const val MIN_UPLOAD_DURATION_SECONDS = 5L

        fun isUploadFile(file: File) = file.isFile && file.length() > 0 &&
                Regex("[A-Za-z0-9-]+_\\+?[0-9]{1,32}_[0-9]+_[0-9]+\\.m4a").matches(file.name)

        /** 与回调使用同一份号码、date 和 duration；date 已明确为秒精度的毫秒值。 */
        private fun uploadName(
            userId: String,
            number: String,
            dateMs: Long,
            duration: Long
        ): String {
            require(Regex("\\+?[0-9]{1,32}").matches(number)) { "Invalid correlation phone number" }
            require(dateMs > 0) { "Invalid correlation date" }
            require(duration >= 0) { "The correlation duration must be known and non-negative" }
            return "${userId}_${number}_${dateMs / 1_000}_${duration}.m4a"
        }
    }
}
