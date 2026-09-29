package com.aitsuki.labs.dialrecord.recording

import java.io.File
import java.util.UUID

/** 目录就是队列。staging 中的音频无论是否封装完成，都不能被上传器读取。 */
class RecordingFiles(root: File) {
    val staging = File(root, "staging")
    val pending = File(root, "pending")

    /** 为本次录音分配 staging 下的 .part 路径；只创建目录，文件由录音器写入。 */
    fun newStagingFile(): File {
        check(staging.isDirectory || staging.mkdirs()) { "Unable to create the recording staging directory" }
        return File(staging, "${UUID.randomUUID()}.part")
    }

    /** 短通话删除音频并返回 null；其余同一文件系统内重命名发布，失败保留原文件。 */
    fun publish(audio: File, userId: String, number: String, dateMs: Long, duration: Long): File? {
        val name = uploadName(userId, number, dateMs, duration)
        check(
            audio.parentFile?.canonicalFile == staging.canonicalFile &&
                    audio.extension == "m4a" && audio.isFile && audio.length() > 0
        ) { "The recording has not been finalized correctly" }
        if (duration < MIN_UPLOAD_DURATION_SECONDS) {
            check(audio.delete()) { "Short recording could not be deleted" }
            return null
        }
        check(pending.isDirectory || pending.mkdirs()) { "Unable to create the pending upload directory" }
        val target = File(pending, name)
        // 不能添加 UUID 改变协议，也不能覆盖另一次通话的文件。
        check(!target.exists()) { "Pending upload filename conflict: $name" }
        check(audio.renameTo(target)) { "Unable to publish the recording for upload" }
        return target
    }

    companion object {
        const val MIN_UPLOAD_DURATION_SECONDS = 5L

        fun validUserId(value: String) = Regex("[A-Za-z0-9-]+").matches(value)

        /** 与回调使用同一份号码、date 和 duration；date 已明确为秒精度的毫秒值。 */
        fun uploadName(userId: String, number: String, dateMs: Long, duration: Long): String {
            require(validUserId(userId)) { "userId must not be empty or contain underscores or path characters" }
            require(Regex("\\+?[0-9]{1,32}").matches(number)) { "Invalid correlation phone number" }
            require(dateMs > 0 && dateMs % 1_000 == 0L) { "The correlation time must be a millisecond timestamp with second precision" }
            require(duration >= 0) { "The correlation duration must be known and non-negative" }
            return "${userId}_${number}_${dateMs / 1_000}_${duration}.m4a"
        }

        fun isUploadFile(file: File) = file.isFile && file.length() > 0 &&
                Regex("[A-Za-z0-9-]+_\\+?[0-9]{1,32}_[0-9]+_[0-9]+\\.m4a").matches(file.name)
    }
}
