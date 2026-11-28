package com.zcshou.gogogo.run.ui.home

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.zcshou.gogogo.R
import com.zcshou.gogogo.run.data.db.RunRecordEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 战报中心历史列表：全量记录（不再截断到 30 条），由筛选逻辑在上游裁剪。
 */
class HistoryRecordAdapter(
    private val onClick: (RunRecordEntity) -> Unit
) : RecyclerView.Adapter<HistoryRecordAdapter.Holder>() {

    private val items = ArrayList<RunRecordEntity>()
    private val dateFormat = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)

    fun submit(list: List<RunRecordEntity>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_history_record, parent, false)
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val record = items[position]
        holder.name.text = record.routeName
        holder.meta.text = String.format(
            Locale.US,
            "%s · 用时 %s · %s",
            dateFormat.format(Date(record.startedAt)),
            formatDuration(record.durationSeconds),
            if (record.averagePaceSec > 0) formatPace(record.averagePaceSec) + "/km" else "配速 --"
        )
        holder.km.text = String.format(Locale.US, "%.2f km", record.actualDistanceMeters / 1000.0)
        holder.status.text = if (record.status == "COMPLETED") "完成" else "中止"
        holder.itemView.setOnClickListener { onClick(record) }
    }

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.recordName)
        val meta: TextView = view.findViewById(R.id.recordMeta)
        val km: TextView = view.findViewById(R.id.recordKm)
        val status: TextView = view.findViewById(R.id.recordStatus)
    }

    private fun formatDuration(seconds: Int): String {
        val safe = seconds.coerceAtLeast(0)
        val hour = safe / 3600
        val minute = (safe % 3600) / 60
        val second = safe % 60
        return if (hour > 0) {
            String.format(Locale.US, "%d:%02d:%02d", hour, minute, second)
        } else {
            String.format(Locale.US, "%02d:%02d", minute, second)
        }
    }

    private fun formatPace(secPerKm: Int): String =
        String.format(Locale.US, "%d'%02d\"", secPerKm / 60, secPerKm % 60)
}
