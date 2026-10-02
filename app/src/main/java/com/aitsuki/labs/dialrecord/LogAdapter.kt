package com.aitsuki.labs.dialrecord

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.util.Log
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.aitsuki.labs.dialrecord.databinding.ItemLogBinding

internal class LogAdapter(private val onListCommitted: (Int) -> Unit) :
    ListAdapter<AppLog.Entry, LogAdapter.ViewHolder>(EntryDiff) {

    init {
        setHasStableIds(true)
        stateRestorationPolicy = StateRestorationPolicy.PREVENT_WHEN_EMPTY
    }

    override fun getItemId(position: Int) = getItem(position).id

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ViewHolder(
        ItemLogBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    )

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    override fun onCurrentListChanged(
        previousList: MutableList<AppLog.Entry>,
        currentList: MutableList<AppLog.Entry>
    ) {
        onListCommitted(currentList.size)
    }

    class ViewHolder(private val binding: ItemLogBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(entry: AppLog.Entry) {
            val (level, color) = when (entry.priority) {
                Log.DEBUG -> "DEBUG" to R.color.log_debug
                Log.INFO -> "INFO" to R.color.log_info
                Log.WARN -> "WARN" to R.color.log_warn
                else -> "ERROR" to R.color.log_error
            }
            val context = binding.root.context
            binding.logEntryText.text = SpannableStringBuilder().apply {
                append(entry.time)
                setSpan(ForegroundColorSpan(ContextCompat.getColor(context, R.color.log_time)),
                    0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(RelativeSizeSpan(0.9f), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                append("  ")
                val levelStart = length
                append(level)
                setSpan(ForegroundColorSpan(ContextCompat.getColor(context, color)),
                    levelStart, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(StyleSpan(Typeface.BOLD), levelStart, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                append('\n')
                append(entry.message)
            }
        }
    }

    private object EntryDiff : DiffUtil.ItemCallback<AppLog.Entry>() {
        override fun areItemsTheSame(oldItem: AppLog.Entry, newItem: AppLog.Entry) =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: AppLog.Entry, newItem: AppLog.Entry) =
            oldItem == newItem
    }
}
