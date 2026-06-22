package com.example.zhizijing.ui.history

import android.app.DatePickerDialog
import android.content.Intent
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.example.zhizijing.R
import com.example.zhizijing.data.repository.TrainingRepository
import com.example.zhizijing.databinding.ViewHistoryListBinding
import com.example.zhizijing.domain.model.ActionType
import com.example.zhizijing.domain.model.TrainingSummary
import com.example.zhizijing.utils.AppExecutors
import com.google.android.material.button.MaterialButton
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class HistoryListController(
    private val activity: ComponentActivity,
    private val binding: ViewHistoryListBinding,
    private val returnTarget: String,
    private val finishHostOnDetail: Boolean = false,
) {
    private var currentFilter: ActionType? = null
    private var currentDateMode: HistoryDateMode = HistoryDateMode.ALL
    private var customDateFilter: String? = null
    private val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).apply {
        isLenient = false
    }

    fun bind() {
        binding.actionChipGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            val checkedId = checkedIds.firstOrNull() ?: return@setOnCheckedStateChangeListener
            currentFilter = when (checkedId) {
                R.id.chipActionSquat -> ActionType.SQUAT
                R.id.chipActionJumpingJack -> ActionType.JUMPING_JACK
                else -> null
            }
            refresh()
        }

        binding.dateChipGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            val checkedId = checkedIds.firstOrNull() ?: return@setOnCheckedStateChangeListener
            if (checkedId != R.id.chipDateCustom) {
                binding.chipDateCustom.text = "指定日期"
                customDateFilter = null
            }
            currentDateMode = when (checkedId) {
                R.id.chipDateToday -> HistoryDateMode.TODAY
                R.id.chipDateRecent -> HistoryDateMode.RECENT_7_DAYS
                R.id.chipDateCustom -> {
                    showDatePickerDialog()
                    HistoryDateMode.CUSTOM
                }
                else -> HistoryDateMode.ALL
            }
            if (currentDateMode != HistoryDateMode.CUSTOM) {
                refresh()
            }
        }

        binding.chipDateCustom.setOnClickListener {
            if (currentDateMode == HistoryDateMode.CUSTOM) {
                showDatePickerDialog()
            }
        }
    }

    fun refresh() {
        binding.historyStatusText.text = "正在读取历史记录..."
        binding.historyListContainer.removeAllViews()
        AppExecutors.io.execute {
            val records = filterByDateMode(TrainingRepository.listForCurrentUser(activity, currentFilter))
            activity.runOnUiThread {
                val filterText = currentFilter?.displayName ?: "全部"
                binding.historyStatusText.text = HistoryFormatter.summaryText(filterText, records, dateFilterLabel())
                renderHistoryRows(records)
            }
        }
    }

    private fun showDatePickerDialog() {
        val calendar = Calendar.getInstance()
        customDateFilter?.let { dateText ->
            runCatching {
                dayFormat.parse(dateText)?.let { date -> calendar.time = date }
            }
        }

        DatePickerDialog(
            activity,
            { _, year, month, dayOfMonth ->
                val selectedCalendar = Calendar.getInstance().apply {
                    set(Calendar.YEAR, year)
                    set(Calendar.MONTH, month)
                    set(Calendar.DAY_OF_MONTH, dayOfMonth)
                }
                customDateFilter = dayFormat.format(selectedCalendar.time)
                binding.chipDateCustom.text = customDateFilter
                refresh()
            },
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH),
            calendar.get(Calendar.DAY_OF_MONTH),
        ).apply {
            setOnCancelListener {
                if (customDateFilter == null) {
                    binding.dateChipGroup.check(R.id.chipDateAll)
                }
            }
            show()
        }
    }

    private fun renderHistoryRows(records: List<TrainingSummary>) {
        binding.historyListContainer.removeAllViews()
        if (records.isEmpty()) {
            binding.historyListContainer.addView(emptyStateView())
            return
        }
        HistoryFormatter.groupByDay(records).forEach { group ->
            binding.historyListContainer.addView(dayHeader(group.dateText))
            group.records.forEach { record ->
                binding.historyListContainer.addView(recordView(record))
            }
        }
    }

    private fun dayHeader(dateText: String): TextView =
        TextView(activity).apply {
            text = dateText
            setTextColor(color(R.color.zzj_text_primary))
            textSize = 18f
            setPadding(0, dp(18), 0, dp(8))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }

    private fun recordView(record: TrainingSummary): View {
        val cardView = activity.layoutInflater.inflate(R.layout.item_history_card, binding.historyListContainer, false)

        cardView.findViewById<TextView>(R.id.actionNameText).text = record.actionType.displayName
        cardView.findViewById<TextView>(R.id.metricValueText).text = HistoryFormatter.keyMetricText(record)
        val scoreBadgeContainer = cardView.findViewById<View>(R.id.scoreBadgeContainer)
        val scoreText = HistoryFormatter.scoreBadgeText(record)
        if (scoreText != null) {
            scoreBadgeContainer.visibility = View.VISIBLE
            cardView.findViewById<TextView>(R.id.scoreValueText).text = scoreText
        } else {
            scoreBadgeContainer.visibility = View.GONE
        }
        cardView.findViewById<TextView>(R.id.supportingText).text = HistoryFormatter.supportingText(record)
        cardView.findViewById<MaterialButton>(R.id.detailButton).setOnClickListener { openDetail(record.sessionId) }
        cardView.findViewById<MaterialButton>(R.id.deleteButton).setOnClickListener { deleteRecord(record.sessionId) }

        return cardView
    }

    private fun emptyStateView(): View =
        TextView(activity).apply {
            text = "暂无训练记录"
            gravity = Gravity.CENTER
            setTextColor(color(R.color.zzj_text_secondary))
            textSize = 15f
            setBackgroundResource(R.drawable.bg_zzj_card_soft)
            setPadding(dp(16), dp(32), dp(16), dp(32))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }

    private fun openDetail(sessionId: Long) {
        if (sessionId <= 0L) {
            showHistoryMessage("无法打开详情：记录编号无效（$sessionId）。")
            return
        }
        showHistoryMessage("正在打开训练详情：记录 $sessionId。")
        AppExecutors.io.execute {
            runCatching { TrainingRepository.findSummary(activity, sessionId) }
                .onSuccess { summary ->
                    activity.runOnUiThread {
                        if (summary == null) {
                            showHistoryMessage("无法打开详情：记录 $sessionId 已不存在，请刷新历史记录。")
                            return@runOnUiThread
                        }
                        runCatching {
                            activity.startActivity(
                                Intent(activity, HistoryDetailActivity::class.java)
                                    .putExtra(HistoryDetailActivity.EXTRA_SESSION_ID, sessionId)
                                    .putExtra(HistoryDetailActivity.EXTRA_RETURN_TARGET, returnTarget)
                            )
                        }.onSuccess {
                            if (finishHostOnDetail) activity.finish()
                        }.onFailure { error ->
                            showHistoryMessage("打开详情页面失败：${error.message ?: error.javaClass.simpleName}")
                        }
                    }
                }
                .onFailure { error ->
                    activity.runOnUiThread {
                        showHistoryMessage("打开详情前读取记录失败：${error.message ?: error.javaClass.simpleName}")
                    }
                }
        }
    }

    private fun deleteRecord(sessionId: Long) {
        if (sessionId <= 0L) return
        AppExecutors.io.execute {
            runCatching { TrainingRepository.deleteSession(activity, sessionId) }
                .onSuccess {
                    activity.runOnUiThread {
                        Toast.makeText(activity, "已删除记录 $sessionId", Toast.LENGTH_SHORT).show()
                        refresh()
                    }
                }
                .onFailure { error ->
                    activity.runOnUiThread {
                        Toast.makeText(activity, "删除失败：${error.message}", Toast.LENGTH_SHORT).show()
                    }
                }
        }
    }

    private fun showHistoryMessage(message: String) {
        Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
    }

    private fun filterByDateMode(records: List<TrainingSummary>): List<TrainingSummary> =
        when (currentDateMode) {
            HistoryDateMode.ALL -> records
            HistoryDateMode.TODAY -> {
                val today = formatDay(System.currentTimeMillis())
                records.filter { record -> formatDay(record.timestampMs) == today }
            }
            HistoryDateMode.RECENT_7_DAYS -> {
                val startMs = Calendar.getInstance().apply {
                    add(Calendar.DAY_OF_YEAR, -6)
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }.timeInMillis
                records.filter { record -> record.timestampMs >= startMs }
            }
            HistoryDateMode.CUSTOM -> {
                val date = customDateFilter
                if (date.isNullOrBlank()) records else records.filter { record -> formatDay(record.timestampMs) == date }
            }
        }

    private fun dateFilterLabel(): String? =
        when (currentDateMode) {
            HistoryDateMode.ALL -> null
            HistoryDateMode.TODAY -> "今天"
            HistoryDateMode.RECENT_7_DAYS -> "近7天"
            HistoryDateMode.CUSTOM -> customDateFilter?.let { "日期 $it" } ?: "指定日期"
        }

    private fun formatDay(timestampMs: Long): String =
        synchronized(dayFormat) { dayFormat.format(Date(timestampMs)) }

    private fun dp(value: Int): Int =
        (value * activity.resources.displayMetrics.density).toInt()

    private fun color(resId: Int): Int =
        activity.getColor(resId)

    private enum class HistoryDateMode {
        ALL,
        TODAY,
        RECENT_7_DAYS,
        CUSTOM,
    }
}
