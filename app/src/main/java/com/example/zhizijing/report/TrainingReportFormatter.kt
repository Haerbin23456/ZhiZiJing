package com.example.zhizijing.report

import com.example.zhizijing.data.entity.ActionResultEntity
import com.example.zhizijing.data.entity.DeviceNodeEntity
import com.example.zhizijing.domain.model.ActionType
import com.example.zhizijing.domain.model.DeviceRole
import com.example.zhizijing.domain.model.ProblemType
import com.example.zhizijing.domain.model.TrainingSummary
import java.io.File
import java.util.Locale

object TrainingReportFormatter {
    fun title(summary: TrainingSummary): String =
        "智姿镜训练报告 - ${summary.actionType.displayName}"

    fun plainText(
        summary: TrainingSummary,
        actionResults: List<ActionResultEntity>,
        deviceNodes: List<DeviceNodeEntity> = emptyList(),
        videoFiles: List<File> = emptyList(),
        includeActionDetails: Boolean = true,
    ): String {
        val stats = TrainingReportStatsCalculator.from(actionResults)
        val actionDetails = if (includeActionDetails && actionResults.isNotEmpty()) {
            actionDetailsText(actionResults)
        } else {
            ""
        }
        return """
        ${title(summary)}
        记录 ID：${summary.sessionId}
        动作类型：${summary.actionType.displayName}
        ${primaryMetricText(summary.actionType, summary.totalCount, summary.durationMs)}
        合格次数：${stats.qualifiedCount}
        需复盘次数：${stats.reviewCount}
        训练时长：${summary.durationMs / 1000}s
        平均节奏：${averageTempoText(summary.actionType, summary.totalCount, summary.durationMs)}
        平均评分：${averageScoreText(summary)}
        平均姿态识别置信度：${recognitionConfidenceText(summary.averagePoseConfidence)}
        主要问题：${summary.mainProblem.displayName}
        建议：${summary.suggestion.orEmpty()}
        参与设备：${1 + deviceNodes.size} 台（含本机）
        ${formatDeviceNodes(deviceNodes)}
        ${TrainingReportStatsCalculator.qualificationRuleText()}
        动作明细：${actionResults.size} 条
        $actionDetails
        关键帧图片：${stats.keyFramePaths.size} 张
        正面关键帧：${stats.frontKeyFramePath ?: "暂无"}
        侧面关键帧：${stats.sideKeyFramePath ?: "暂无"}
        ${TrainingVideoArtifacts.formatSummary(videoFiles)}
        """.trimIndent()
    }

    fun actionDetailsText(
        actionResults: List<ActionResultEntity>,
        maxRows: Int = Int.MAX_VALUE,
    ): String {
        if (actionResults.isEmpty()) return "动作明细：暂无。"
        val shownRows = actionResults.take(maxRows.coerceAtLeast(1))
        val lines = shownRows.map { result ->
            val actionType = ActionType.fromNameOrUnknown(result.actionType)
            val scoreText = result.score?.let { "${it.toInt()} 分" } ?: if (
                actionType.supportsDetailedScore &&
                result.problemType == ProblemType.LOW_CONFIDENCE.name
            ) {
                "暂不评分"
            } else if (actionType.isHoldBased) {
                "保持计时"
            } else {
                "仅计数"
            }
            val kneeText = result.kneeAngle?.let { "，膝角 ${it.toInt()} 度" }.orEmpty()
            val trunkText = result.trunkAngle?.let { "，躯干角 ${it.toInt()} 度" }.orEmpty()
            "#${result.actionIndex} ${actionDisplayName(result.actionType)}：评分 $scoreText$kneeText$trunkText，问题 ${problemDisplayName(result.problemType)}，建议 ${result.suggestion.orEmpty().ifBlank { "保持稳定节奏。" }}"
        }.toMutableList()
        val remaining = actionResults.size - shownRows.size
        if (remaining > 0) {
            lines += "还有 $remaining 条动作明细未展示。"
        }
        return lines.joinToString(separator = "\n")
    }

    fun pdfOverviewText(
        summary: TrainingSummary,
        actionResults: List<ActionResultEntity>,
        deviceNodes: List<DeviceNodeEntity> = emptyList(),
        videoFiles: List<File> = emptyList(),
    ): String {
        val stats = TrainingReportStatsCalculator.from(actionResults)
        val lines = mutableListOf(
            "动作：${summary.actionType.displayName}",
            pdfPrimaryMetricText(summary.actionType, summary.totalCount, summary.durationMs),
            "训练时长：${durationText(summary.durationMs)}",
            "平均评分：${pdfAverageScoreText(summary)}",
        )
        if (summary.actionType.isCountBased) {
            lines += "完成情况：合格 ${stats.qualifiedCount} 次，需复盘 ${stats.reviewCount} 次"
        } else if (summary.actionType.isHoldBased) {
            lines += "完成情况：按保持时长记录"
        }
        recognitionConfidenceText(summary.averagePoseConfidence)
            .takeIf { it != "暂无" }
            ?.let { confidence -> lines += "姿态识别稳定度：$confidence" }
        lines += "主要问题：${summary.mainProblem.displayName}"
        summary.suggestion
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { suggestion -> lines += "建议：$suggestion" }
        userFacingDeviceSummary(deviceNodes)
            ?.let { text -> lines += text }
        if (videoFiles.isNotEmpty()) {
            lines += "训练视频：${videoFiles.size} 段，已保存在本次训练详情中。"
        }
        return lines.joinToString(separator = "\n")
    }

    fun pdfActionReviewText(
        actionResults: List<ActionResultEntity>,
        maxRows: Int = 8,
    ): String {
        if (actionResults.isEmpty()) return "暂无可复盘的单次动作。"
        val shownRows = actionResults.take(maxRows.coerceAtLeast(1))
        val lines = shownRows.map { result ->
            val actionType = ActionType.fromNameOrUnknown(result.actionType)
            val scoreText = result.score?.let { "${it.toInt()} 分" } ?: when {
                actionType.isHoldBased -> "保持计时"
                actionType.isCountBased && !actionType.supportsDetailedScore -> "已完成"
                else -> "暂不评分"
            }
            val problemText = problemDisplayName(result.problemType)
            val suggestionText = result.suggestion
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: if (problemText == ProblemType.NONE.displayName) "保持当前节奏。" else "按主要问题继续调整。"
            val metricsText = listOfNotNull(
                result.kneeAngle?.let { "膝角 ${it.toInt()} 度" },
                result.trunkAngle?.let { "躯干角 ${it.toInt()} 度" },
            )
                .takeIf { it.isNotEmpty() }
                ?.joinToString(prefix = "，", separator = "，")
                .orEmpty()
            "第 ${result.actionIndex} 次：$scoreText$metricsText，问题：$problemText。建议：$suggestionText"
        }.toMutableList()
        val remaining = actionResults.size - shownRows.size
        if (remaining > 0) {
            lines += "其余 $remaining 次动作已省略，可在应用详情页继续查看。"
        }
        return lines.joinToString(separator = "\n")
    }

    fun formatDeviceNodes(deviceNodes: List<DeviceNodeEntity>): String {
        if (deviceNodes.isEmpty()) return "参与设备：暂无其它设备记录。"
        return deviceNodes.joinToString(separator = "\n") { node ->
            "参与设备：${node.deviceName} / ${roleDisplayText(node.role)} / ${if (node.isOnline) "在线" else "离线"} / 电量 ${node.batteryLevel?.let { "$it%" } ?: "未知"} / 延迟 ${node.networkDelayMs?.let { "$it ms" } ?: "待测"}"
        }
    }

    fun averageTempoPerMinute(
        actionType: ActionType,
        totalCount: Int,
        durationMs: Long,
    ): Float? =
        if (actionType.isCountBased && !actionType.supportsDetailedScore && totalCount > 0 && durationMs > 0L) {
            totalCount * 60_000f / durationMs
        } else {
            null
        }

    fun averageScoreText(summary: TrainingSummary): String =
        summary.averageScore?.toInt()?.toString()
            ?: when {
                summary.actionType.isHoldBased -> "按保持时长统计"
                summary.actionType.isCountBased && !summary.actionType.supportsDetailedScore -> "仅计数"
                summary.mainProblem == ProblemType.LOW_CONFIDENCE -> "暂不评分"
                else -> "暂无"
            }

    fun averageTempoText(
        actionType: ActionType,
        totalCount: Int,
        durationMs: Long,
    ): String =
        averageTempoPerMinute(actionType, totalCount, durationMs)
            ?.let { tempo -> "%.1f 次/分钟".format(Locale.CHINA, tempo) }
            ?: "不适用"

    fun recognitionConfidenceText(confidence: Float?): String =
        confidence
            ?.takeIf { value -> value.isFinite() && value in 0f..1f }
            ?.let { value -> "%.1f%%".format(Locale.CHINA, value * 100f) }
            ?: "暂无"

    fun primaryMetricText(
        actionType: ActionType,
        totalCount: Int,
        durationMs: Long,
    ): String =
        if (actionType.isHoldBased) {
            "保持时长：${durationMs / 1000}s"
        } else {
            "总次数：$totalCount"
        }

    private fun durationText(durationMs: Long): String {
        val totalSeconds = (durationMs / 1000).coerceAtLeast(0L)
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return if (minutes > 0L) {
            "${minutes}分${seconds}秒"
        } else {
            "${seconds}秒"
        }
    }

    private fun pdfPrimaryMetricText(
        actionType: ActionType,
        totalCount: Int,
        durationMs: Long,
    ): String =
        if (actionType.isHoldBased) {
            "保持时长：${durationText(durationMs)}"
        } else {
            "总次数：$totalCount"
        }

    private fun pdfAverageScoreText(summary: TrainingSummary): String =
        averageScoreText(summary).let { scoreText ->
            if (summary.averageScore != null) "$scoreText 分" else scoreText
        }

    private fun userFacingDeviceSummary(deviceNodes: List<DeviceNodeEntity>): String? {
        if (deviceNodes.isEmpty()) return null
        val roles = deviceNodes
            .map { node -> roleDisplayText(node.role) }
            .filter { role -> role != "未分配" }
            .distinct()
        return if (roles.isEmpty()) {
            "采集设备：本机 + ${deviceNodes.size} 台辅助设备"
        } else {
            "采集机位：本机、${roles.joinToString("、")}"
        }
    }

    private fun actionDisplayName(raw: String?): String {
        val actionType = ActionType.fromNameOrUnknown(raw)
        return if (actionType == ActionType.UNKNOWN) raw.orEmpty().ifBlank { actionType.displayName } else actionType.displayName
    }

    private fun problemDisplayName(raw: String?): String =
        raw.orEmpty()
            .takeIf { it.isNotBlank() }
            ?.let { value ->
                runCatching { ProblemType.valueOf(value).displayName }.getOrDefault(value)
            }
            ?: ProblemType.NONE.displayName

    private fun roleDisplayText(raw: String?): String =
        when (runCatching { DeviceRole.valueOf(raw.orEmpty()) }.getOrDefault(DeviceRole.UNKNOWN)) {
            DeviceRole.HOST -> "主控端"
            DeviceRole.FRONT_CAMERA -> "正面机位"
            DeviceRole.SIDE_CAMERA -> "侧面机位"
            DeviceRole.BACKUP_CAMERA -> "备用机位"
            DeviceRole.UNKNOWN -> "未分配"
        }
}
