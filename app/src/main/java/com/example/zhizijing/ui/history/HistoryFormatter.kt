package com.example.zhizijing.ui.history

import com.example.zhizijing.domain.model.TrainingSummary
import com.example.zhizijing.domain.model.ProblemType
import com.example.zhizijing.report.TrainingReportFormatter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class HistoryDayGroup(
    val dateText: String,
    val records: List<TrainingSummary>,
)

object HistoryFormatter {
    private val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).apply {
        isLenient = false
    }
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.CHINA)

    fun groupByDay(records: List<TrainingSummary>): List<HistoryDayGroup> =
        records
            .sortedByDescending { it.timestampMs }
            .groupBy { formatDay(it.timestampMs) }
            .map { (dateText, items) -> HistoryDayGroup(dateText, items) }

    fun filterByDate(
        records: List<TrainingSummary>,
        dateFilter: String?,
    ): List<TrainingSummary> =
        dateFilter
            ?.takeIf { it.isNotBlank() }
            ?.let { expectedDate -> records.filter { record -> formatDay(record.timestampMs) == expectedDate } }
            ?: records

    fun dateFilterError(rawDate: String): String? {
        val value = rawDate.trim()
        if (value.isBlank()) return null
        if (!DATE_PATTERN.matches(value)) return "日期格式应为 yyyy-MM-dd，例如 2026-05-31。"
        val parsed = synchronized(dayFormat) { runCatching { dayFormat.parse(value) }.getOrNull() }
        return if (parsed == null) "请输入有效日期，例如 2026-05-31。" else null
    }

    fun recordLine(record: TrainingSummary): String =
        """
            ${record.actionType.displayName}  ${keyMetricText(record)}
            ${supportingText(record)}
        """.trimIndent()

    fun keyMetricText(record: TrainingSummary): String =
        TrainingReportFormatter.primaryMetricText(record.actionType, record.totalCount, record.durationMs)

    fun scoreBadgeText(record: TrainingSummary): String? =
        record.averageScore?.toInt()?.let { score -> "$score 分" }

    fun supportingText(record: TrainingSummary): String {
        val parts = mutableListOf(
            timeFormat.format(Date(record.timestampMs)),
            "时长 ${record.durationMs / 1000}s",
        )
        val tempo = TrainingReportFormatter.averageTempoText(record.actionType, record.totalCount, record.durationMs)
        if (tempo != "不适用") parts += "节奏 $tempo"
        parts += if (record.mainProblem == ProblemType.NONE) {
            "表现稳定"
        } else {
            "重点 ${record.mainProblem.displayName}"
        }
        return parts.joinToString(" · ")
    }

    fun summaryText(
        filterText: String,
        records: List<TrainingSummary>,
        dateFilterLabel: String? = null,
    ): String {
        val filterDescription = buildString {
            append(filterText)
            dateFilterLabel?.takeIf { it.isNotBlank() }?.let { append(" · $it") }
        }
        return if (records.isEmpty()) {
            "$filterDescription · 暂无训练记录"
        } else {
            "$filterDescription · 共 ${records.size} 条"
        }
    }

    private fun formatDay(timestampMs: Long): String =
        synchronized(dayFormat) { dayFormat.format(Date(timestampMs)) }

    private val DATE_PATTERN = Regex("""\d{4}-\d{2}-\d{2}""")
}
