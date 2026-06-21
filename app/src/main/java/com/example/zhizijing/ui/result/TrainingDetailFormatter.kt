package com.example.zhizijing.ui.result

import com.example.zhizijing.domain.model.ActionType
import com.example.zhizijing.domain.model.ProblemType
import com.example.zhizijing.domain.model.TrainingSummary
import com.example.zhizijing.report.TrainingReportFormatter
import com.example.zhizijing.report.TrainingReportStats

data class TrainingDetailUiState(
    val actionTitle: String,
    val primaryMetric: String,
    val scoreText: String,
    val suggestionText: String,
    val qualifiedText: String,
    val reviewText: String,
    val durationText: String,
    val confidenceText: String,
    val reviewSummaryText: String,
    val keyFrameText: String,
)

object TrainingDetailFormatter {
    fun fromSummary(
        summary: TrainingSummary,
        stats: TrainingReportStats,
        videoCount: Int,
    ): TrainingDetailUiState {
        val suggestion = summary.suggestion.orEmpty()
            .ifBlank { "保持全身入镜，按稳定节奏完成动作。" }
        return TrainingDetailUiState(
            actionTitle = "${summary.actionType.displayName}训练",
            primaryMetric = primaryMetricValue(summary.actionType, summary.totalCount, summary.durationMs),
            scoreText = "平均评分：${averageScoreDisplay(summary)}",
            suggestionText = "建议：$suggestion",
            qualifiedText = "合格\n${stats.qualifiedCount} 次",
            reviewText = "需复盘\n${stats.reviewCount} 次",
            durationText = "时长\n${summary.durationMs / 1000} 秒",
            confidenceText = "姿态置信度\n${TrainingReportFormatter.recognitionConfidenceText(summary.averagePoseConfidence)}",
            reviewSummaryText = "主要问题：${summary.mainProblem.displayName}",
            keyFrameText = keyFrameText(stats),
        )
    }

    fun fromFallback(
        actionType: ActionType,
        count: Int,
        score: Int,
        problemText: String,
        durationMs: Long,
        suggestion: String,
    ): TrainingDetailUiState {
        val fallbackSummary = TrainingSummary(
            actionType = actionType,
            totalCount = count,
            averageScore = null,
            durationMs = durationMs,
            mainProblem = ProblemType.NONE,
            suggestion = null,
        )
        val problemDisplay = runCatching { ProblemType.valueOf(problemText).displayName }
            .getOrDefault(problemText.ifBlank { ProblemType.NONE.displayName })
        return TrainingDetailUiState(
            actionTitle = "${actionType.displayName}训练",
            primaryMetric = primaryMetricValue(actionType, count, durationMs),
            scoreText = "平均评分：${if (score > 0) "$score 分" else TrainingReportFormatter.averageScoreText(fallbackSummary)}",
            suggestionText = "建议：${suggestion.ifBlank { "保持稳定节奏，继续训练。" }}",
            qualifiedText = "合格\n${ResultFallbackFormatter.qualifiedCountText(actionType, count)}",
            reviewText = "需复盘\n暂无",
            durationText = "时长\n${durationMs / 1000} 秒",
            confidenceText = "姿态置信度\n暂无",
            reviewSummaryText = "主要问题：$problemDisplay",
            keyFrameText = keyFrameText(EmptyStats.value),
        )
    }

    fun unavailable(message: String): TrainingDetailUiState =
        TrainingDetailUiState(
            actionTitle = "训练详情",
            primaryMetric = "--",
            scoreText = "平均评分：暂无",
            suggestionText = "建议：$message",
            qualifiedText = "合格\n暂无",
            reviewText = "需复盘\n暂无",
            durationText = "时长\n暂无",
            confidenceText = "姿态置信度\n暂无",
            reviewSummaryText = message,
            keyFrameText = keyFrameText(EmptyStats.value),
        )

    fun keyFrameText(stats: TrainingReportStats): String =
        "运动过程中的部分关键帧记录如下"

    private fun primaryMetricValue(actionType: ActionType, totalCount: Int, durationMs: Long): String =
        if (actionType.isHoldBased) {
            "${durationMs / 1000} 秒"
        } else {
            "$totalCount 次"
        }

    private fun averageScoreDisplay(summary: TrainingSummary): String {
        val scoreText = TrainingReportFormatter.averageScoreText(summary)
        return if (summary.averageScore != null) "$scoreText 分" else scoreText
    }

    private object EmptyStats {
        val value = TrainingReportStats(
            actionCount = 0,
            qualifiedCount = 0,
            reviewCount = 0,
            keyFramePaths = emptyList(),
        )
    }
}
