package com.example.zhizijing.ui.main

import com.example.zhizijing.domain.model.ActionType
import com.example.zhizijing.domain.model.TrainingSummary
import com.example.zhizijing.report.TrainingReportFormatter

data class MainDashboardStats(
    val latestRecord: TrainingSummary?,
    val recordCount: Int,
    val totalCount: Int,
    val scoredRecordCount: Int,
    val averageScore: Float?,
    val recognizedActions: Set<ActionType>,
)

object MainDashboardFormatter {
    fun calculate(records: List<TrainingSummary>): MainDashboardStats {
        val scoredRecords = records.mapNotNull { it.averageScore }
        return MainDashboardStats(
            latestRecord = records.maxByOrNull { it.timestampMs },
            recordCount = records.size,
            totalCount = records.sumOf { it.totalCount },
            scoredRecordCount = scoredRecords.size,
            averageScore = scoredRecords.takeIf { it.isNotEmpty() }?.average()?.toFloat(),
            recognizedActions = records.map { it.actionType }.toSet(),
        )
    }

    fun latestTrainingText(stats: MainDashboardStats): String {
        val latest = stats.latestRecord ?: return "最近训练：暂无训练记录。完成一次训练后会自动显示。"
        return "最近训练：${latest.actionType.displayName}，${latest.totalCount} 次，评分：${TrainingReportFormatter.averageScoreText(latest)}。"
    }

    fun summaryText(stats: MainDashboardStats): String =
        """
            综合评分：${scoreText(stats.averageScore)}
            累计次数：${stats.totalCount} 次
            训练记录：${stats.recordCount} 条
            动作识别状态：${recognitionStatusText(stats)}
        """.trimIndent()

    private fun recognitionStatusText(stats: MainDashboardStats): String {
        if (stats.recordCount == 0) {
            return "默认自动识别${ActionType.trainingActionNamesText()}，暂无历史识别记录"
        }
        val actions = stats.recognizedActions
            .filter { it.isTrainingAction }
            .joinToString("、") { it.displayName }
            .ifBlank { "暂无有效训练动作记录" }
        return "默认自动识别${ActionType.trainingActionNamesText()}，已记录：$actions"
    }

    private fun scoreText(score: Float?): String =
        score?.let { "${it.toInt()} 分" } ?: "仅计数/暂无评分"
}
