package com.aitsuki.labs.dialrecord.data

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** 每条录音一个目录、一份原子写入的元数据和一个音频文件。所有文件变更在这里串行执行。 */
object RecordingStore {
    private const val METADATA = "entry.json"
    private const val PENDING_AUDIO = "audio.part"

    private fun root(context: Context) = File(context.filesDir, "recordings").apply {
        check(isDirectory || mkdirs()) { "无法创建录音目录" }
    }

    private fun folder(context: Context, id: String): File {
        require(UUID.fromString(id).toString() == id) { "录音 ID 无效" }
        return File(root(context), id)
    }

    @Synchronized
    fun prepareAudio(context: Context, id: String): File {
        val folder = folder(context, id)
        check(!folder.exists() && folder.mkdir()) { "录音 ID 已存在或目录无法创建" }
        return File(folder, PENDING_AUDIO)
    }

    /** 仅在录音器成功 stop/release 后调用；发布元数据后，该录音才会出现在列表。 */
    @Synchronized
    fun complete(context: Context, entry: RecordingEntry): RecordingEntry {
        val folder = folder(context, entry.recordingId)
        check(File(folder, PENDING_AUDIO).isFile) { "录音文件不存在" }
        write(folder, entry)
        reconcile(folder, entry)
        return entry
    }

    @Synchronized
    fun discardPending(context: Context, id: String) {
        val folder = folder(context, id)
        // 已发布的音频不能被服务异常清理删除。
        if (File(folder, METADATA).exists() || File(folder, "$METADATA.bak").exists()) return
        File(folder, PENDING_AUDIO).delete()
        folder.delete()
    }

    @Synchronized
    fun loadEntries(context: Context): List<RecordingEntry> = root(context).listFiles()
        .orEmpty().filter { it.isDirectory }.mapNotNull { folder ->
            runCatching { read(folder) }.onFailure {
                Log.e("RecordingStore", "无法读取录音 ${folder.name}", it)
            }.getOrNull()
        }.sortedByDescending { it.recordingStartedAtMs }

    @Synchronized
    fun get(context: Context, id: String): RecordingEntry? = read(folder(context, id))

    @Synchronized
    fun audioFile(context: Context, id: String): File {
        val entry = requireNotNull(get(context, id)) { "录音不存在或尚未结束" }
        return File(folder(context, id), entry.fileName)
    }

    /** 可在会话结束后任意时间调用；相同时长的重复提交不会重复重命名。 */
    @Synchronized
    fun updateDuration(context: Context, id: String, seconds: Long): RecordingEntry {
        require(seconds >= 0) { "时长不能小于零" }
        val current = requireNotNull(get(context, id)) { "录音不存在或尚未结束" }
        return update(context, current, current.copy(durationSeconds = seconds))
    }

    /** 自动匹配只填充未知时长，绝不覆盖调用方已经提供的时长。 */
    @Synchronized
    fun attachCallLog(context: Context, id: String, call: CallLogInfo): RecordingEntry {
        val current = requireNotNull(get(context, id)) { "录音不存在" }
        check(current.source == CallSource.SYSTEM)
        check(loadEntries(context).none { it.recordingId != id && it.callLogId == call.id }) {
            "系统通话记录已被其他录音关联"
        }
        return update(context, current, current.copy(
            callLogId = call.id,
            durationSeconds = current.durationSeconds ?: call.durationSeconds,
        ))
    }

    private fun update(context: Context, old: RecordingEntry, updated: RecordingEntry): RecordingEntry {
        if (old == updated) return old
        val folder = folder(context, old.recordingId)
        write(folder, updated)
        try {
            reconcile(folder, updated)
        } catch (error: Exception) {
            // 普通失败恢复旧元数据；进程被杀时由 read() 根据已提交元数据恢复命名。
            runCatching { write(folder, old) }.onFailure { error.addSuppressed(it) }
            throw error
        }
        return updated
    }

    private fun read(folder: File): RecordingEntry? {
        val metadata = AtomicFile(File(folder, METADATA))
        if (!metadata.baseFile.exists() && !File(folder, "$METADATA.bak").exists()) return null
        val entry = decode(metadata.openRead().bufferedReader().use { it.readText() })
        check(entry.recordingId == folder.name) { "录音目录与 ID 不一致" }
        reconcile(folder, entry)
        return entry
    }

    /** 元数据是命名依据。原子提交和 rename 之间被杀进程，下次读取会完成 rename。 */
    private fun reconcile(folder: File, entry: RecordingEntry) {
        val target = File(folder, entry.fileName)
        if (target.isFile) return
        val source = folder.listFiles().orEmpty().filter {
            it.isFile && (it.extension == "m4a" || it.name == PENDING_AUDIO)
        }.singleOrNull()
        check(source != null && source.renameTo(target)) { "录音文件重命名失败" }
    }

    private fun write(folder: File, entry: RecordingEntry) {
        val file = AtomicFile(File(folder, METADATA))
        val stream = file.startWrite()
        try {
            stream.write(encode(entry).toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (error: Exception) {
            file.failWrite(stream)
            throw error
        }
    }

    internal fun encode(entry: RecordingEntry): String = JSONObject().apply {
        put("recordingId", entry.recordingId)
        put("source", entry.source.name)
        put("phoneNumber", entry.phoneNumber)
        put("recordingStartedAtMs", entry.recordingStartedAtMs)
        entry.durationSeconds?.let { put("durationSeconds", it) }
        entry.callLogId?.let { put("callLogId", it) }
    }.toString()

    internal fun decode(raw: String): RecordingEntry = JSONObject(raw).let {
        RecordingEntry(
            recordingId = it.getString("recordingId"),
            source = CallSource.valueOf(it.getString("source")),
            phoneNumber = it.getString("phoneNumber"),
            recordingStartedAtMs = it.getLong("recordingStartedAtMs"),
            durationSeconds = if (it.has("durationSeconds")) it.getLong("durationSeconds") else null,
            callLogId = if (it.has("callLogId")) it.getLong("callLogId") else null,
        )
    }
}
