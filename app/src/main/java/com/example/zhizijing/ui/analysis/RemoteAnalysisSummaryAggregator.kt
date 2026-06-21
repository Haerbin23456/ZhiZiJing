package com.example.zhizijing.ui.analysis

import com.example.zhizijing.data.repository.TrainingRecordMapper
import com.example.zhizijing.domain.model.ActionType
import com.example.zhizijing.domain.model.DeviceRole
import com.example.zhizijing.domain.model.ProblemType
import com.example.zhizijing.domain.rule.SquatScorePolicy
import com.example.zhizijing.nearby.connection.NearbyEndpoint
import com.example.zhizijing.nearby.message.NearbyMessage

data class RemoteAnalysisAggregate(
    val actionType: ActionType = ActionType.UNKNOWN,
    val actionConfidence: Float? = null,
    val totalCount: Int = 0,
    val holdDurationMs: Long = 0L,
    val score: Float? = null,
    val kneeAngle: Float? = null,
    val trunkAngle: Float? = null,
    val postureLevel: String? = null,
    val problemType: ProblemType = ProblemType.NONE,
    val suggestion: String? = null,
    val activeSummaryCount: Int = 0,
    val isDegraded: Boolean = true,
    val statusText: String = "多设备连接：暂无节点分析回传。",
)

class RemoteAnalysisSummaryAggregator {
    private val summaries = linkedMapOf<String, NearbyMessage>()

    fun record(
        endpointId: String,
        message: NearbyMessage,
        endpoints: List<NearbyEndpoint>,
    ): RemoteAnalysisAggregate {
        summaries[endpointId] = message
        return calculate(endpoints)
    }

    fun calculate(endpoints: List<NearbyEndpoint>): RemoteAnalysisAggregate {
        val endpointById = endpoints.associateBy { endpoint -> endpoint.endpointId }
        val onlineSummaries = summaries.mapNotNull { (endpointId, summary) ->
            val endpoint = endpointById[endpointId]
            if (endpoint?.isOnline != true) return@mapNotNull null
            NodeSummary(endpointId, endpoint.role.takeUnless { it == DeviceRole.UNKNOWN } ?: summary.role, summary)
        }
        val bestActionSummary = onlineSummaries
            .filter { node -> node.message.actionType.isTrainingAction }
            .maxWithOrNull(
                compareBy<NodeSummary> { node -> node.message.actionConfidence ?: 0f }
                    .thenBy { node -> node.message.timestampMs }
            )
        val bestKnownAction = bestActionSummary?.message?.actionType ?: ActionType.UNKNOWN
        val actionSummaries = if (bestKnownAction == ActionType.UNKNOWN) {
            emptyList()
        } else {
            onlineSummaries.filter { node -> node.message.actionType == bestKnownAction }
        }
        val acceptedProblems = actionSummaries.mapNotNull { node ->
            node.message.problemType.takeIf { problem -> acceptsProblem(node.role, problem) }
        }
        val problemType = PROBLEM_PRIORITY.firstOrNull { problem -> problem in acceptedProblems } ?: ProblemType.NONE
        val score = actionSummaries.mapNotNull { node ->
            SquatScorePolicy.reportableScore(node.message.problemType, node.message.score)
        }
            .takeIf { scores -> scores.isNotEmpty() }
            ?.average()
            ?.toFloat()
        val kneeAngle = preferredSideMetric(actionSummaries) { message -> message.kneeAngle }
        val trunkAngle = preferredSideMetric(actionSummaries) { message -> message.trunkAngle }
        val postureLevel = actionSummaries
            .firstOrNull { node -> node.role == DeviceRole.SIDE_CAMERA && !node.message.postureLevel.isNullOrBlank() }
            ?.message
            ?.postureLevel
            ?: actionSummaries.firstNotNullOfOrNull { node -> node.message.postureLevel?.takeIf { it.isNotBlank() } }
        val onlineRoles = endpoints.filter { endpoint -> endpoint.isOnline }.map { endpoint -> endpoint.role }.toSet()
        val hasFront = DeviceRole.FRONT_CAMERA in onlineRoles
        val hasSide = DeviceRole.SIDE_CAMERA in onlineRoles
        val isDegraded = !hasFront || !hasSide
        val suggestion = actionSummaries
            .firstOrNull { node -> node.message.problemType == problemType && !node.message.suggestion.isNullOrBlank() }
            ?.message
            ?.suggestion
            ?: TrainingRecordMapper.suggestionFor(problemType)
        return RemoteAnalysisAggregate(
            actionType = bestKnownAction,
            actionConfidence = bestActionSummary?.message?.actionConfidence,
            totalCount = actionSummaries.maxOfOrNull { node -> node.message.totalCount ?: 0 } ?: 0,
            holdDurationMs = actionSummaries.maxOfOrNull { node -> node.message.holdDurationMs ?: 0L } ?: 0L,
            score = score,
            kneeAngle = kneeAngle,
            trunkAngle = trunkAngle,
            postureLevel = postureLevel,
            problemType = problemType,
            suggestion = suggestion,
            activeSummaryCount = actionSummaries.size,
            isDegraded = isDegraded,
            statusText = formatStatus(endpoints, onlineSummaries, isDegraded, hasFront, hasSide),
        )
    }

    private fun formatStatus(
        endpoints: List<NearbyEndpoint>,
        onlineSummaries: List<NodeSummary>,
        isDegraded: Boolean,
        hasFront: Boolean,
        hasSide: Boolean,
    ): String {
        val modeText = if (!isDegraded) {
            "多机位状态：正面 / 侧面在线，已启用融合。"
        } else {
            val missingRoles = buildList {
                if (!hasFront) add("正面机位")
                if (!hasSide) add("侧面机位")
            }.joinToString("、")
            "多机位状态：单机位降级，缺少$missingRoles。"
        }
        val endpointLines = endpoints.ifEmpty {
            return "$modeText\n节点状态：暂无在线节点。"
        }.joinToString(separator = "\n") { endpoint ->
            val summary = onlineSummaries.firstOrNull { node -> node.endpointId == endpoint.endpointId }?.message
            val roleText = endpoint.role.roleText()
            val onlineText = if (endpoint.isOnline) "在线" else "离线"
            val summaryText = if (summary == null) {
                "等待分析摘要"
            } else {
                val scoreText = if (summary.actionType.isHoldBased) {
                    "保持计时"
                } else if (summary.actionType.isCountBased && !summary.actionType.supportsDetailedScore) {
                    "仅计数"
                } else {
                    SquatScorePolicy.reportableScore(summary.problemType, summary.score)?.toInt()?.toString()
                        ?: "暂不评分"
                }
                val metricText = if (summary.actionType.isHoldBased) {
                    "保持中"
                } else {
                    "${summary.totalCount ?: 0} 次"
                }
                "${summary.actionType.displayName} $metricText，评分 $scoreText，" +
                    "${RealtimeAnalysisFormatter.coreAngleText(summary.kneeAngle, summary.trunkAngle)}，" +
                    "姿态 ${RealtimeAnalysisFormatter.postureLevelText(summary.postureLevel)}，问题 ${summary.problemType.displayName}"
            }
            "$roleText：$onlineText，$summaryText"
        }
        return "$modeText\n$endpointLines"
    }

    private fun acceptsProblem(role: DeviceRole, problemType: ProblemType): Boolean =
        when (role) {
            DeviceRole.FRONT_CAMERA -> problemType in FRONT_CAMERA_PROBLEMS
            DeviceRole.SIDE_CAMERA -> problemType in SIDE_CAMERA_PROBLEMS
            else -> true
        }

    private fun preferredSideMetric(
        actionSummaries: List<NodeSummary>,
        selector: (NearbyMessage) -> Float?,
    ): Float? =
        actionSummaries
            .firstOrNull { node -> node.role == DeviceRole.SIDE_CAMERA && selector(node.message) != null }
            ?.let { node -> selector(node.message) }
            ?: actionSummaries.firstNotNullOfOrNull { node -> selector(node.message) }

    private fun DeviceRole.roleText(): String =
        when (this) {
            DeviceRole.FRONT_CAMERA -> "正面机位"
            DeviceRole.SIDE_CAMERA -> "侧面机位"
            DeviceRole.BACKUP_CAMERA -> "备用机位"
            DeviceRole.HOST -> "主控端"
            DeviceRole.UNKNOWN -> "未分配机位"
        }

    private data class NodeSummary(
        val endpointId: String,
        val role: DeviceRole,
        val message: NearbyMessage,
    )

    companion object {
        private val FRONT_CAMERA_PROBLEMS = setOf(
            ProblemType.LOW_CONFIDENCE,
            ProblemType.KNEE_INWARD,
            ProblemType.ASYMMETRY,
        )
        private val SIDE_CAMERA_PROBLEMS = setOf(
            ProblemType.LOW_CONFIDENCE,
            ProblemType.BACK_LEAN_TOO_MUCH,
            ProblemType.SQUAT_DEPTH_NOT_ENOUGH,
        )
        private val PROBLEM_PRIORITY = listOf(
            ProblemType.LOW_CONFIDENCE,
            ProblemType.KNEE_INWARD,
            ProblemType.BACK_LEAN_TOO_MUCH,
            ProblemType.ASYMMETRY,
            ProblemType.SQUAT_DEPTH_NOT_ENOUGH,
            ProblemType.RHYTHM_ABNORMAL,
            ProblemType.NONE,
        )
    }
}
