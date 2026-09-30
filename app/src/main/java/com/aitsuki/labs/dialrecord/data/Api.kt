package com.aitsuki.labs.dialrecord.data

import com.aitsuki.labs.dialrecord.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.time.Duration.Companion.seconds

object Api {

    data class Response(
        val code: Int = 200,
        val success: Boolean = false
    )

    suspend fun uploadCallAudio(file: File): Response =
        withContext(Dispatchers.IO) {
            AppLog.info("正在上传录音: ${file.name}")
            delay(3.seconds)
            Response(code = 200, success = true)
        }
}