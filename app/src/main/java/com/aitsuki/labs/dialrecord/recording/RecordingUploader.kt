package com.aitsuki.labs.dialrecord.recording

import android.os.SystemClock
import com.aitsuki.labs.dialrecord.AppLog
import com.aitsuki.labs.dialrecord.data.Api
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import kotlin.time.Duration.Companion.seconds

/** 应用进程级轮询，不持有 Activity。upload 只有收到后端业务成功确认才返回 true。 */
class RecordingUploader(private val directory: File) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var job: Job? = null

    /** 每个实例只启动一次；进程重启时创建新实例，重新扫描磁盘。 */
    @Synchronized
    fun start(): Job {
        job?.let { return it }
        return scope.launch {
            while (isActive) {
                uploadPending()
                delay(20.seconds)
            }
        }.also { job = it }
    }

    /** 仅供单轮测试；运行时由唯一的轮询协程串行调用。失败文件不阻塞其他文件。 */
    internal suspend fun uploadPending() {
        val files = try {
            if (!directory.exists()) return
            check(directory.isDirectory) { "The pending upload path is not a directory" }
            checkNotNull(directory.listFiles()) { "Unable to scan the pending upload directory" }
                .filter(RecordingFiles::isUploadFile).sortedBy { it.name }
        } catch (e: Exception) {
            AppLog.debug("RecordingUploader - 上传保留待重试", e)
            return
        }
        for (file in files) {
            currentCoroutineContext().ensureActive()
            val startedAt = SystemClock.elapsedRealtime()
            val size = file.length()
            try {
                val result = Api.uploadCallAudio(file)
                val elapsedMs = SystemClock.elapsedRealtime() - startedAt
                if (result.success) {
                    AppLog.info(
                        "录音上传成功：文件=%s，大小=%s 字节，耗时=%s 毫秒",
                        file.name, size, elapsedMs
                    )
                    currentCoroutineContext().ensureActive()
                    check(file.delete()) { "Upload succeeded, but the local file could not be deleted" }
                    AppLog.info("上传后已删除本地录音：文件=%s", file.name)
                } else {
                    AppLog.warn(
                        "录音上传未获后端成功确认，保留待重试：文件=%s，业务返回码=%s，耗时=%s 毫秒",
                        file.name, result.code, elapsedMs
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 轮询重试失败只记日志，避免每 20 秒重复生成错误事件。
                AppLog.warn(
                    "录音上传或上传后清理失败，保留待重试：文件=%s，原因=%s，耗时=%s 毫秒",
                    file.name, e.message, SystemClock.elapsedRealtime() - startedAt
                )
            }
        }
    }
}
