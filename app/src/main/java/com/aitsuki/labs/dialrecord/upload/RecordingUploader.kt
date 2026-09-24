package com.aitsuki.labs.dialrecord.upload

import com.aitsuki.labs.dialrecord.recording.RecordingFiles
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** 应用进程级轮询，不持有 Activity。upload 只有收到后端业务成功确认才返回 true。 */
class RecordingUploader(
    private val directory: File,
    private val upload: suspend (File) -> Boolean,
    private val onError: (File?, Exception) -> Unit,
) {
    private var job: Job? = null

    /** 每个实例只启动一次；进程重启时创建新实例，重新扫描磁盘。 */
    @Synchronized
    fun start(scope: CoroutineScope, intervalMs: Long = 60_000): Job {
        require(intervalMs > 0)
        job?.let { return it }
        return scope.launch {
            while (isActive) {
                uploadPending()
                delay(intervalMs)
            }
        }.also { job = it }
    }

    /** 仅供单轮测试；运行时由唯一的轮询协程串行调用。失败文件不阻塞其他文件。 */
    internal suspend fun uploadPending() {
        val files = try {
            if (!directory.exists()) return
            check(directory.isDirectory) { "待上传路径不是目录" }
            checkNotNull(directory.listFiles()) { "无法扫描待上传目录" }
                .filter(RecordingFiles::isUploadFile).sortedBy { it.name }
        } catch (e: Exception) {
            onError(null, e)
            return
        }
        for (file in files) {
            currentCoroutineContext().ensureActive()
            try {
                if (upload(file)) {
                    // 上传已成功但在响应期间取消：保留文件，下次用同名文件重试。
                    currentCoroutineContext().ensureActive()
                    check(file.delete()) { "上传成功，但无法删除本地文件" }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onError(file, e)
            }
        }
    }
}
