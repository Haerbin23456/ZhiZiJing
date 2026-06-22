package com.example.zhizijing.data.repository

import com.example.zhizijing.data.entity.ActionResultEntity
import com.example.zhizijing.data.entity.PoseFrameEntity
import com.example.zhizijing.domain.model.ActionType
import com.example.zhizijing.domain.model.DeviceRole
import com.example.zhizijing.domain.model.ProblemType
import com.example.zhizijing.domain.rule.PoseAnalysisConfig
import com.example.zhizijing.domain.rule.SquatScorePolicy
import com.example.zhizijing.pose.feature.PoseMath
import com.example.zhizijing.pose.model.LandmarkPoint
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlin.math.max

data class TrainingPoseMetrics(
    val kneeAngle: Float?,
    val trunkAngle: Float?,
    val depthLevel: String?,
    val problemType: ProblemType,
    val score: Float?,
    val suggestion: String,
    val detectedProblems: Set<ProblemType>,
)

object TrainingPoseMetricsExtractor {
    fun applyTo(
        result: ActionResultEntity,
        frame: PoseFrameEntity,
        config: PoseAnalysisConfig = PoseAnalysisConfig(),
        overwriteEvaluation: Boolean = false,
    ) {
        val metrics = extract(result.actionType, frame, config) ?: return
        applyMetrics(result, metrics, overwriteEvaluation)
    }

    fun applyMultiViewTo(
        result: ActionResultEntity,
        fallbackFrame: PoseFrameEntity,
        frontFrame: PoseFrameEntity?,
        sideFrame: PoseFrameEntity?,
        config: PoseAnalysisConfig = PoseAnalysisConfig(),
    ) {
        val metrics = fuse(
            rawActionType = result.actionType,
            fallbackFrame = fallbackFrame,
            frontFrame = frontFrame,
            sideFrame = sideFrame,
            config = config,
        ) ?: return
        applyMetrics(result, metrics, overwriteEvaluation = true)
    }

    fun fuse(
        rawActionType: String?,
        fallbackFrame: PoseFrameEntity,
        frontFrame: PoseFrameEntity?,
        sideFrame: PoseFrameEntity?,
        config: PoseAnalysisConfig = PoseAnalysisConfig(),
    ): TrainingPoseMetrics? {
        val fallbackMetrics = extract(rawActionType, fallbackFrame, config) ?: return null
        if (ActionType.fromNameOrUnknown(rawActionType) != ActionType.SQUAT) return fallbackMetrics
        val frontMetrics = frontFrame?.let { frame -> extract(rawActionType, frame, config) }
        val sideMetrics = sideFrame?.let { frame -> extract(rawActionType, frame, config) }
        if (frontMetrics == null || sideMetrics == null) {
            return frontMetrics ?: sideMetrics ?: fallbackMetrics
        }

        val detectedProblems = frontMetrics.detectedProblems
            .filterTo(mutableSetOf()) { problem -> problem in FRONT_CAMERA_PROBLEMS }
            .plus(sideMetrics.detectedProblems.filter { problem -> problem in SIDE_CAMERA_PROBLEMS })
        val problemType = primaryProblem(detectedProblems)
        return TrainingPoseMetrics(
            kneeAngle = sideMetrics.kneeAngle ?: frontMetrics.kneeAngle,
            trunkAngle = sideMetrics.trunkAngle ?: frontMetrics.trunkAngle,
            depthLevel = sideMetrics.depthLevel ?: frontMetrics.depthLevel,
            problemType = problemType,
            score = SquatScorePolicy.reportableScore(problemType, scoreFor(problemType)),
            suggestion = TrainingRecordMapper.suggestionFor(problemType),
            detectedProblems = detectedProblems,
        )
    }

    private fun applyMetrics(
        result: ActionResultEntity,
        metrics: TrainingPoseMetrics,
        overwriteEvaluation: Boolean,
    ) {
        result.kneeAngle = metrics.kneeAngle
        result.trunkAngle = metrics.trunkAngle
        result.depthLevel = metrics.depthLevel
        result.postureLevel = metrics.depthLevel
        if (overwriteEvaluation) {
            val existingProblems = TrainingRecordMapper.problemsFrom(result.problemType)
                .filter { problem -> problem.isPersistentPoseProblem() }
            val mergedProblems = (metrics.detectedProblems + existingProblems).toSet()
            val primaryProblem = primaryProblem(mergedProblems)
            result.problemType = problemTypeStorageText(mergedProblems, primaryProblem)
            result.score = SquatScorePolicy.reportableScore(primaryProblem, scoreFor(primaryProblem))
            result.suggestion = suggestionText(mergedProblems, primaryProblem)
        }
    }

    fun extract(
        rawActionType: String?,
        frame: PoseFrameEntity,
        config: PoseAnalysisConfig = PoseAnalysisConfig(),
    ): TrainingPoseMetrics? {
        val actionType = ActionType.fromNameOrUnknown(rawActionType)
        if (actionType != ActionType.SQUAT) return null
        val landmarks = parseLandmarks(frame.landmarksJson)
        val leftShoulder = landmarks["LEFT_SHOULDER"] ?: return null
        val rightShoulder = landmarks["RIGHT_SHOULDER"] ?: return null
        val leftHip = landmarks["LEFT_HIP"] ?: return null
        val rightHip = landmarks["RIGHT_HIP"] ?: return null
        val leftKnee = landmarks["LEFT_KNEE"] ?: return null
        val rightKnee = landmarks["RIGHT_KNEE"] ?: return null
        val leftAnkle = landmarks["LEFT_ANKLE"] ?: return null
        val rightAnkle = landmarks["RIGHT_ANKLE"] ?: return null

        val leftKneeAngle = PoseMath.angle(leftHip, leftKnee, leftAnkle)
        val rightKneeAngle = PoseMath.angle(rightHip, rightKnee, rightAnkle)
        val kneeAngle = (leftKneeAngle + rightKneeAngle) / 2f
        val hipMid = PoseMath.midpoint("HIP_MID", leftHip, rightHip)
        val kneeMid = PoseMath.midpoint("KNEE_MID", leftKnee, rightKnee)
        val shoulderMid = PoseMath.midpoint("SHOULDER_MID", leftShoulder, rightShoulder)
        val trunkAngle = PoseMath.trunkLeanAngleFromVertical(shoulderMid, hipMid)
        val ankleMid = PoseMath.midpoint("ANKLE_MID", leftAnkle, rightAnkle)
        val bodyScale = maxOf(
            PoseMath.distance(shoulderMid, hipMid),
            (
                PoseMath.distance(hipMid, kneeMid) +
                    PoseMath.distance(kneeMid, ankleMid)
                ) * 0.5f,
            0.001f,
        )
        val hipKneeGapRatio = (kneeMid.y - hipMid.y) / bodyScale
        val cameraRole = parseCameraRole(frame.cameraRole)
        val frontCorrection = usesFrontCorrection(cameraRole)
        val sideCorrection = usesSideCorrection(cameraRole)
        val isSquatAttempt = hipKneeGapRatio <= SQUAT_ENTER_GAP_RATIO ||
            kneeAngle <= SQUAT_ENTER_KNEE_ANGLE
        val deepEnough = hipKneeGapRatio <= GOOD_DEPTH_GAP_RATIO
        val depthLevel = when {
            !sideCorrection -> "COUNT_ONLY"
            !isSquatAttempt -> null
            deepEnough -> "GOOD"
            else -> "SHALLOW"
        }
        val required = listOf(leftShoulder, rightShoulder, leftHip, rightHip, leftKnee, rightKnee, leftAnkle, rightAnkle)
        val detectedProblems = buildSet {
            if (
                frame.confidence < config.minPoseConfidence ||
                !PoseMath.hasMinimumConfidence(required, config.minPoseConfidence)
            ) {
                add(ProblemType.LOW_CONFIDENCE)
            }
            if (
                frontCorrection &&
                kneeInwardRatio(leftKnee, rightKnee, leftAnkle, rightAnkle) > KNEE_INWARD_RATIO
            ) {
                add(ProblemType.KNEE_INWARD)
            }
            if (sideCorrection && trunkAngle > config.backLeanAngleThreshold) {
                add(ProblemType.BACK_LEAN_TOO_MUCH)
            }
            if (sideCorrection && depthLevel == "SHALLOW") {
                add(ProblemType.SQUAT_DEPTH_NOT_ENOUGH)
            }
        }
        val problemType = primaryProblem(detectedProblems)

        return TrainingPoseMetrics(
            kneeAngle = kneeAngle,
            trunkAngle = trunkAngle,
            depthLevel = depthLevel,
            problemType = problemType,
            score = SquatScorePolicy.reportableScore(problemType, scoreFor(problemType)),
            suggestion = TrainingRecordMapper.suggestionFor(problemType),
            detectedProblems = detectedProblems,
        )
    }

    fun representativeScore(
        rawActionType: String?,
        frame: PoseFrameEntity,
    ): Float? {
        val landmarks = parseLandmarks(frame.landmarksJson)
        return when (ActionType.fromNameOrUnknown(rawActionType)) {
            ActionType.SQUAT -> {
                val leftHip = landmarks["LEFT_HIP"] ?: return null
                val rightHip = landmarks["RIGHT_HIP"] ?: return null
                val leftKnee = landmarks["LEFT_KNEE"] ?: return null
                val rightKnee = landmarks["RIGHT_KNEE"] ?: return null
                val hipMid = PoseMath.midpoint("HIP_MID", leftHip, rightHip)
                val kneeMid = PoseMath.midpoint("KNEE_MID", leftKnee, rightKnee)
                hipMid.y - kneeMid.y
            }
            ActionType.JUMPING_JACK -> {
                val leftShoulder = landmarks["LEFT_SHOULDER"] ?: return null
                val rightShoulder = landmarks["RIGHT_SHOULDER"] ?: return null
                val leftWrist = landmarks["LEFT_WRIST"] ?: return null
                val rightWrist = landmarks["RIGHT_WRIST"] ?: return null
                val leftAnkle = landmarks["LEFT_ANKLE"] ?: return null
                val rightAnkle = landmarks["RIGHT_ANKLE"] ?: return null
                val shoulderWidth = PoseMath.horizontalDistance(leftShoulder, rightShoulder).coerceAtLeast(0.001f)
                val ankleRatio = PoseMath.horizontalDistance(leftAnkle, rightAnkle) / shoulderWidth
                val shoulderY = PoseMath.midpoint("SHOULDER_MID", leftShoulder, rightShoulder).y
                val averageWristY = (leftWrist.y + rightWrist.y) / 2f
                ankleRatio + max(0f, shoulderY - averageWristY)
            }
            else -> null
        }
    }

    private fun parseLandmarks(json: String?): Map<String, LandmarkPoint> {
        if (json.isNullOrBlank()) return emptyMap()
        val root = runCatching { JsonParser.parseString(json).asJsonObject }.getOrNull() ?: return emptyMap()
        return root.entrySet().mapNotNull { (name, element) ->
            val point = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            point.toLandmarkPoint(name)?.let { name to it }
        }.toMap()
    }

    private fun JsonObject.toLandmarkPoint(name: String): LandmarkPoint? {
        val x = getFloat("x") ?: return null
        val y = getFloat("y") ?: return null
        val z = getFloat("z")
        val confidence = getFloat("confidence") ?: 0f
        return LandmarkPoint(name, x, y, z, confidence)
    }

    private fun JsonObject.getFloat(name: String): Float? =
        get(name)
            ?.takeIf { !it.isJsonNull }
            ?.let { runCatching { it.asFloat }.getOrNull() }

    private fun parseCameraRole(raw: String?): DeviceRole =
        runCatching { DeviceRole.valueOf(raw.orEmpty()) }.getOrDefault(DeviceRole.UNKNOWN)

    private fun usesFrontCorrection(role: DeviceRole): Boolean =
        role == DeviceRole.FRONT_CAMERA

    private fun usesSideCorrection(role: DeviceRole): Boolean =
        role != DeviceRole.FRONT_CAMERA

    private fun kneeInwardRatio(
        leftKnee: LandmarkPoint,
        rightKnee: LandmarkPoint,
        leftAnkle: LandmarkPoint,
        rightAnkle: LandmarkPoint,
    ): Float {
        val ankleDistance = PoseMath.horizontalDistance(leftAnkle, rightAnkle).coerceAtLeast(0.001f)
        val leftInward = (leftKnee.x - leftAnkle.x) / ankleDistance
        val rightInward = (rightAnkle.x - rightKnee.x) / ankleDistance
        return minOf(leftInward, rightInward)
    }

    private fun scoreFor(problemType: ProblemType): Float =
        when (problemType) {
            ProblemType.SQUAT_DEPTH_NOT_ENOUGH -> 85f
            ProblemType.KNEE_INWARD -> 80f
            ProblemType.BACK_LEAN_TOO_MUCH -> 85f
            ProblemType.ASYMMETRY -> 90f
            ProblemType.LOW_CONFIDENCE -> 90f
            ProblemType.RHYTHM_ABNORMAL -> 95f
            ProblemType.NONE -> 100f
        }

    private fun primaryProblem(problems: Set<ProblemType>): ProblemType =
        PROBLEM_PRIORITY.firstOrNull { problem -> problem in problems } ?: ProblemType.NONE

    private fun problemTypeStorageText(problems: Set<ProblemType>, fallback: ProblemType): String {
        val ordered = orderedProblems(problems, fallback)
        return ordered.joinToString(separator = "|") { problem -> problem.name }
    }

    private fun suggestionText(problems: Set<ProblemType>, fallback: ProblemType): String {
        val ordered = orderedProblems(problems, fallback)
        return ordered
            .filter { problem -> problem != ProblemType.NONE }
            .joinToString(separator = "\n") { problem -> "• ${TrainingRecordMapper.suggestionFor(problem)}" }
            .ifBlank { TrainingRecordMapper.suggestionFor(fallback) }
    }

    private fun orderedProblems(problems: Set<ProblemType>, fallback: ProblemType): List<ProblemType> {
        val normalized = problems.takeIf { it.isNotEmpty() } ?: setOf(fallback)
        return PROBLEM_PRIORITY.filter { problem -> problem in normalized }
            .ifEmpty { listOf(fallback) }
    }

    private fun ProblemType.isPersistentPoseProblem(): Boolean =
        this == ProblemType.SQUAT_DEPTH_NOT_ENOUGH ||
            this == ProblemType.KNEE_INWARD ||
            this == ProblemType.BACK_LEAN_TOO_MUCH

    private val FRONT_CAMERA_PROBLEMS = setOf(
        ProblemType.LOW_CONFIDENCE,
        ProblemType.KNEE_INWARD,
    )
    private val SIDE_CAMERA_PROBLEMS = setOf(
        ProblemType.LOW_CONFIDENCE,
        ProblemType.BACK_LEAN_TOO_MUCH,
        ProblemType.SQUAT_DEPTH_NOT_ENOUGH,
    )
    private val PROBLEM_PRIORITY = listOf(
        ProblemType.KNEE_INWARD,
        ProblemType.BACK_LEAN_TOO_MUCH,
        ProblemType.SQUAT_DEPTH_NOT_ENOUGH,
        ProblemType.LOW_CONFIDENCE,
    )
    private const val SQUAT_ENTER_GAP_RATIO = 0.5f
    private const val SQUAT_ENTER_KNEE_ANGLE = 145f
    private const val GOOD_DEPTH_GAP_RATIO = 0.45f
    private const val KNEE_INWARD_RATIO = 0.12f
}
