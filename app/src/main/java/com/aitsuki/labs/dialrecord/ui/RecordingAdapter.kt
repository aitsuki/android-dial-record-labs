package com.aitsuki.labs.dialrecord.ui

import android.annotation.SuppressLint
import android.content.Context
import android.media.MediaPlayer
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import com.aitsuki.labs.dialrecord.data.RecordingEntry
import com.aitsuki.labs.dialrecord.databinding.ItemRecordingBinding
import com.aitsuki.labs.dialrecord.recording.CallSessionService
import java.io.File
import java.text.DateFormat
import java.util.Date

class RecordingAdapter : RecyclerView.Adapter<RecordingAdapter.Holder>() {
    private var files = emptyList<File>()
    private var entries = emptyList<RecordingEntry>()
    private var player: MediaPlayer? = null

    class Holder(val binding: ItemRecordingBinding) : RecyclerView.ViewHolder(binding.root)

    @SuppressLint("NotifyDataSetChanged")
    fun update(newFiles: List<File>, newEntries: List<RecordingEntry>) {
        if (files == newFiles && entries == newEntries) return
        files = newFiles
        entries = newEntries
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(ItemRecordingBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount() = files.size

    @SuppressLint("SetTextI18n")
    override fun onBindViewHolder(holder: Holder, position: Int) {
        val file = files[position]
        val entry = entries.find { it.fileName == file.name }
        val callLog = entry?.callLog
        holder.binding.apply {
            fileName.text = file.name
            callLogId.text =
                if (callLog != null) "通话记录 ID：${callLog.id}" else "未关联通话记录"
            phoneNumber.text = "号码：${entry?.phoneNumber ?: file.name.substringBefore('_')}"
            recordingStartTime.text = entry?.let {
                "录音开始：${DateFormat.getDateTimeInstance().format(Date(it.recordingStartedAtMs))}"
            }.orEmpty()
            callDuration.text =
                if (callLog != null) "通话时长：${callLog.durationSeconds} 秒 · 点击播放" else "通话时长：未关联 · 点击播放"
            root.setOnClickListener { play(it.context.applicationContext, file) }
        }
    }

    private fun play(context: Context, file: File) {
        // 录音尚未封装完成时不能播放，也避免播放音频干扰通话录音。
        if (CallSessionService.hasSession) {
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
                if (CallSessionService.hasSession) {
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
