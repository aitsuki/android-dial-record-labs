package com.aitsuki.labs.dialrecord.ui

import android.annotation.SuppressLint
import android.content.Context
import android.media.MediaPlayer
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import com.aitsuki.labs.dialrecord.data.RecordingEntry
import com.aitsuki.labs.dialrecord.data.CallSource
import com.aitsuki.labs.dialrecord.data.RecordingStore
import com.aitsuki.labs.dialrecord.databinding.ItemRecordingBinding
import com.aitsuki.labs.dialrecord.recording.RecordingService
import java.io.File
import java.text.DateFormat
import java.util.Date

class RecordingAdapter(private val onEditDuration: (RecordingEntry) -> Unit) : RecyclerView.Adapter<RecordingAdapter.Holder>() {
    private var entries = emptyList<RecordingEntry>()
    private var player: MediaPlayer? = null

    class Holder(val binding: ItemRecordingBinding) : RecyclerView.ViewHolder(binding.root)

    @SuppressLint("NotifyDataSetChanged")
    fun update(newEntries: List<RecordingEntry>) {
        if (entries == newEntries) return
        entries = newEntries
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(ItemRecordingBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount() = entries.size

    @SuppressLint("SetTextI18n")
    override fun onBindViewHolder(holder: Holder, position: Int) {
        val entry = entries[position]
        holder.binding.apply {
            fileName.text = entry.fileName
            callLogId.text = when (entry.source) {
                CallSource.SYSTEM -> entry.callLogId?.let { "系统电话 · 通话记录 ID：$it" } ?: "系统电话 · 尚未关联通话记录"
                CallSource.SDK -> "SDK 通话"
            }
            phoneNumber.text = "电话号码：${entry.phoneNumber}"
            recordingStartTime.text = "录音开始：${DateFormat.getDateTimeInstance().format(Date(entry.recordingStartedAtMs))}"
            callDuration.text = "通话时长：${entry.durationSeconds?.let { "$it 秒" } ?: "未知"} · 点击播放"
            editDuration.setOnClickListener { onEditDuration(entry) }
            root.setOnClickListener {
                runCatching { RecordingStore.audioFile(it.context, entry.recordingId) }
                    .onSuccess { file -> play(root.context.applicationContext, file) }
                    .onFailure { error -> showToast(root.context, error.message.orEmpty()) }
            }
        }
    }

    private fun play(context: Context, file: File) {
        // 录音尚未封装完成时不能播放，也避免播放音频干扰通话录音。
        if (RecordingService.hasSession) {
            showToast(context, "请在通话结束后播放录音")
            return
        }
        releasePlayer()
        runCatching {
            val media = MediaPlayer()
            player = media
            media.setDataSource(file.absolutePath)
            media.setOnPreparedListener { prepared ->
                if (player !== prepared) return@setOnPreparedListener
                if (RecordingService.hasSession) {
                    releasePlayer()
                    showToast(context, "请在通话结束后播放录音")
                    return@setOnPreparedListener
                }
                runCatching { prepared.start() }.onFailure {
                    releasePlayer()
                    showToast(context, "无法播放录音：${it.message}")
                }
            }
            media.setOnCompletionListener { completed ->
                if (player === completed) releasePlayer()
            }
            media.setOnErrorListener { failed, _, _ ->
                if (player === failed) {
                    releasePlayer()
                    showToast(context, "录音无法播放，可能未正常完成")
                }
                true
            }
            media.prepareAsync()
        }.onFailure {
            releasePlayer()
            showToast(context, "无法播放录音：${it.message}")
        }
    }

    /** 页面退出、发起拨号或 Adapter 移除时释放播放器，不影响录音服务。 */
    fun releasePlayer() {
        val media = player ?: return
        player = null
        media.setOnPreparedListener(null)
        media.setOnCompletionListener(null)
        media.setOnErrorListener(null)
        media.release()
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        releasePlayer()
        super.onDetachedFromRecyclerView(recyclerView)
    }

    private fun showToast(context: Context, text: String) =
        Toast.makeText(context, text, Toast.LENGTH_LONG).show()
}
