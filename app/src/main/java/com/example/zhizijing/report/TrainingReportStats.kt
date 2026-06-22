package com.example.zhizijing.report

import com.example.zhizijing.data.entity.ActionResultEntity
import com.example.zhizijing.data.repository.TrainingRecordMapper
import com.example.zhizijing.domain.model.ActionType
import com.example.zhizijing.domain.model.DeviceRole
import com.example.zhizijing.domain.model.ProblemType
import com.example.zhizijing.report.frame.KeyFrameStore
import java.io.File

data class TrainingReportStats(
    val actionCount: Int,
    val qualifiedCount: Int,
    val reviewCount: Int,
    val keyFramePaths: List<String>,
    val frontKeyFramePath: String? = null,
    val sideKeyFramePath: String? = null,
    val problemCounts: Map<ProblemType, Int> = emptyMap(),
) {
    val firstKeyFramePath: String? = frontKeyFramePath ?: keyFramePaths.getOrNull(0)
    val secondKeyFramePath: String? = sideKeyFramePath
        ?: keyFramePaths.firstOrNull { path -> path != firstKeyFramePath }
}

object TrainingReportStatsCalculator {
    private const val QUALIFIED_SCORE = 85f

    fun from(actionResults: List<ActionResultEntity>): TrainingReportStats {
        val qualifiedCount = actionResults.count { result -> result.isQualified() }
        val actionKeyFramePaths = actionResults
            .mapNotNull { result -> result.keyFramePath?.takeIf { it.isNotBlank() } }
            .distinct()
        val keyFrameDir = actionKeyFramePaths.firstOrNull()?.let { path -> File(path).parentFile }
        val frontKeyFramePath = keyFrameDir
            ?.resolve(KeyFrameStore.rolePreviewFileName(DeviceRole.FRONT_CAMERA))
            ?.takeIf { file -> file.exists() }
            ?.absolutePath
        val sideKeyFramePath = keyFrameDir
            ?.resolve(KeyFrameStore.rolePreviewFileName(DeviceRole.SIDE_CAMERA))
            ?.takeIf { file -> file.exists() }
            ?.absolutePath
        val keyFramePaths = listOfNotNull(frontKeyFramePath, sideKeyFramePath)
            .plus(actionKeyFramePaths)
            .distinct()
        val problemCounts = actionResults
            .flatMap { result -> TrainingRecordMapper.problemsFrom(result.problemType) }
            .filter { problem -> problem != ProblemType.NONE }
            .groupingBy { problem -> problem }
            .eachCount()
        return TrainingReportStats(
            actionCount = actionResults.size,
            qualifiedCount = qualifiedCount,
            reviewCount = (actionResults.size - qualifiedCount).coerceAtLeast(0),
            keyFramePaths = keyFramePaths,
            frontKeyFramePath = frontKeyFramePath,
            sideKeyFramePath = sideKeyFramePath,
            problemCounts = problemCounts,
        )
    }

    fun qualificationRuleText(): String =
        "合格规则：深蹲等评分动作必须有单次评分且 >= ${QUALIFIED_SCORE.toInt()}、非低置信度才计入；开合跳、俯卧撑等计数型动作按完成次数计入；平板支撑按保持时长展示。"

    private fun ActionResultEntity.isQualified(): Boolean {
        val problems = TrainingRecordMapper.problemsFrom(problemType)
        if (ProblemType.LOW_CONFIDENCE in problems) return false
        score?.let { return it >= QUALIFIED_SCORE }
        val actionType = ActionType.fromNameOrUnknown(actionType)
        return actionType.isCountBased && !actionType.supportsDetailedScore
    }
}
