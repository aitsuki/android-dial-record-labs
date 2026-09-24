package com.aitsuki.labs.dialrecord.recording

import java.io.File
import java.util.UUID

/** 目录就是队列。staging 中的音频无论是否封装完成，都不能被上传器读取。 */
class RecordingFiles(root: File) {
    val staging = File(root, "staging")
    val pending = File(root, "pending")

    fun create(): File {
        check(staging.isDirectory || staging.mkdirs()) { "无法创建录音临时目录" }
        return File(staging, "${UUID.randomUUID()}.part")
    }

    /** 同一文件系统内重命名发布；只在拿到完整关联字段后调用，失败保留原文件。 */
    fun publish(audio: File, userId: String, number: String, dateMs: Long, duration: Long): File {
        val name = uploadName(userId, number, dateMs, duration)
        check(audio.parentFile?.canonicalFile == staging.canonicalFile &&
            audio.extension == "m4a" && audio.isFile && audio.length() > 0) { "录音尚未正确封装" }
        check(pending.isDirectory || pending.mkdirs()) { "无法创建待上传目录" }
        val target = File(pending, name)
        // 不能添加 UUID 改变协议，也不能覆盖另一次通话的文件。
        check(!target.exists()) { "待上传文件名冲突：$name" }
        check(audio.renameTo(target)) { "无法发布待上传录音" }
        return target
    }

    companion object {
        fun validUserId(value: String) = Regex("[A-Za-z0-9-]+").matches(value)

        /** 与回调使用同一份号码、date 和 duration；date 已明确为秒精度的毫秒值。 */
        fun uploadName(userId: String, number: String, dateMs: Long, duration: Long): String {
            require(validUserId(userId)) { "userId 不能为空，也不能包含下划线或路径字符" }
            require(Regex("\\+?[0-9]{1,32}").matches(number)) { "关联号码无效" }
            require(dateMs > 0 && dateMs % 1_000 == 0L) { "关联时间必须为秒精度的毫秒时间戳" }
            require(duration >= 0) { "关联时长必须已知且非负" }
            return "${userId}_${number}_${dateMs / 1_000}_${duration}.m4a"
        }

        fun isUploadFile(file: File) = file.isFile && file.length() > 0 &&
            Regex("[A-Za-z0-9-]+_\\+?[0-9]{1,32}_[0-9]+_[0-9]+\\.m4a").matches(file.name)
    }
}
