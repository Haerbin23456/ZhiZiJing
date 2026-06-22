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
            suggestionText = "建议：${cleanSuggestionText(suggestion)}",
            qualifiedText = "合格\n${stats.qualifiedCount} 次",
            reviewText = "需复盘\n${stats.reviewCount} 次",
            durationText = "时长\n${summary.durationMs / 1000} 秒",
            confidenceText = TrainingReportFormatter.recognitionConfidenceText(summary.averagePoseConfidence),
            reviewSummaryText = reviewSummaryText(summary, stats),
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
        val problemDisplay = runCatching { ProblemType.valueOf(problemText).getOrNullReviewLine(1) }
            .getOrDefault(problemText.ifBlank { ProblemType.NONE.displayName })
        return TrainingDetailUiState(
            actionTitle = "${actionType.displayName}训练",
            primaryMetric = primaryMetricValue(actionType, count, durationMs),
            scoreText = "平均评分：${if (score > 0) "$score 分" else TrainingReportFormatter.averageScoreText(fallbackSummary)}",
            suggestionText = "建议：${cleanSuggestionText(suggestion.ifBlank { "保持稳定节奏，继续训练。" })}",
            qualifiedText = "合格\n${ResultFallbackFormatter.qualifiedCountText(actionType, count)}",
            reviewText = "需复盘\n暂无",
            durationText = "时长\n${durationMs / 1000} 秒",
            confidenceText = "暂无",
            reviewSummaryText = problemDisplay,
            keyFrameText = keyFrameText(EmptyStats.value),
        )
    }

    fun unavailable(message: String): TrainingDetailUiState =
        TrainingDetailUiState(
            actionTitle = "训练详情",
            primaryMetric = "--",
            scoreText = "平均评分：暂无",
            suggestionText = "建议：${cleanSuggestionText(message)}",
            qualifiedText = "合格\n暂无",
            reviewText = "需复盘\n暂无",
            durationText = "时长\n暂无",
            confidenceText = "暂无",
            reviewSummaryText = message,
            keyFrameText = keyFrameText(EmptyStats.value),
        )

    fun keyFrameText(stats: TrainingReportStats): String =
        "运动过程中的部分关键帧记录如下"

    private fun reviewSummaryText(summary: TrainingSummary, stats: TrainingReportStats): String {
        val problemLines = stats.problemCounts
            .entries
            .filter { (problem, count) -> problem != ProblemType.NONE && count > 0 }
            .sortedWith(
                compareByDescending<Map.Entry<ProblemType, Int>> { entry -> entry.value }
                    .thenBy { entry -> problemPriority(entry.key) }
            )
            .joinToString(separator = "\n") { (problem, count) ->
                problem.reviewLine(count)
            }
        if (problemLines.isNotBlank()) return problemLines
        return if (summary.mainProblem == ProblemType.NONE) {
            "未发现明显问题"
        } else {
            summary.mainProblem.reviewLine(1)
        }
    }

    private fun ProblemType.reviewLine(count: Int): String =
        "${displayName}：$count 次"

    private fun ProblemType.getOrNullReviewLine(count: Int): String =
        if (this == ProblemType.NONE) "未发现明显问题" else reviewLine(count)

    private fun problemPriority(problemType: ProblemType): Int =
        when (problemType) {
            ProblemType.KNEE_INWARD -> 0
            ProblemType.BACK_LEAN_TOO_MUCH -> 1
            ProblemType.SQUAT_DEPTH_NOT_ENOUGH -> 2
            ProblemType.RHYTHM_ABNORMAL -> 4
            ProblemType.ASYMMETRY -> 5
            ProblemType.LOW_CONFIDENCE -> 6
            ProblemType.NONE -> 6
        }

    private fun primaryMetricValue(actionType: ActionType, totalCount: Int, durationMs: Long): String =
        if (actionType.isHoldBased) {
            "${durationMs / 1000} 秒"
        } else {
            "$totalCount 次"
        }

    private fun cleanSuggestionText(text: String): String =
        text.trim().trimStart('·', '•', '-', ' ', '\n', '\t')

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
