package com.example.zhizijing.domain.rule

import com.example.zhizijing.domain.model.JumpingJackStage
import com.example.zhizijing.domain.model.JumpingJackResult
import com.example.zhizijing.domain.model.ActionType
import com.example.zhizijing.domain.model.BasicActionAnalysisResult
import com.example.zhizijing.domain.model.DeviceRole
import com.example.zhizijing.domain.model.ProblemType
import com.example.zhizijing.domain.model.SquatAnalysisResult
import com.example.zhizijing.domain.model.SquatStage
import com.example.zhizijing.pose.feature.PoseActionRules
import com.example.zhizijing.pose.feature.PoseActionFeatures
import com.example.zhizijing.pose.feature.PoseMath
import com.example.zhizijing.pose.model.LandmarkPoint
import com.example.zhizijing.pose.model.PoseFrame

interface SquatAnalyzer {
    fun analyze(frame: PoseFrame): SquatAnalysisResult
}

interface JumpingJackAnalyzer {
    fun analyze(frame: PoseFrame): JumpingJackResult
}

interface BasicActionAnalyzer {
    fun analyze(actionType: ActionType, frame: PoseFrame): BasicActionAnalysisResult
}

class SimpleSquatAnalyzer(
    private val config: PoseAnalysisConfig = PoseAnalysisConfig(),
) : SquatAnalyzer {
    private var count = 0
    private var stage = SquatStage.STANDING
    private var currentRepStartMs: Long? = null
    private var pendingRhythmProblem = false
    private var pendingDepthProblem = false
    private var repMinKneeAngle: Float? = null
    private var repMinHipKneeGapRatio: Float? = null
    private var repMaxTrunkAngle: Float? = null
    private var repReachedBottom = false
    private var repKneeInward = false
    private var repBackLean = false
    private var pendingKneeInwardProblem = false
    private var pendingBackLeanProblem = false
    private var pendingProblemTrunkAngle: Float? = null
    private var frontStandingHipY: Float? = null
    private var frontStandingBodyScale: Float? = null
    private var repMaxFrontHipDropRatio: Float? = null

    override fun analyze(frame: PoseFrame): SquatAnalysisResult {
        val points = SquatPoints.from(frame) ?: return output(
            problem = ProblemType.LOW_CONFIDENCE,
            problems = listOf(ProblemType.LOW_CONFIDENCE),
            kneeAngle = null,
            trunkAngle = null,
            depthLevel = null,
        )
        if (
            frame.overallConfidence < config.minPoseConfidence ||
            !PoseMath.hasMinimumConfidence(points.required, config.minPoseConfidence)
        ) {
            return output(
                problem = ProblemType.LOW_CONFIDENCE,
                problems = listOf(ProblemType.LOW_CONFIDENCE),
                kneeAngle = null,
                trunkAngle = null,
                depthLevel = null,
            )
        }

        // 深蹲角度与阶段评估
        val leftKneeAngle = PoseMath.angle(points.leftHip, points.leftKnee, points.leftAnkle)
        val rightKneeAngle = PoseMath.angle(points.rightHip, points.rightKnee, points.rightAnkle)
        val kneeAngle = (leftKneeAngle + rightKneeAngle) / 2f
        val hipMid = PoseMath.midpoint("HIP_MID", points.leftHip, points.rightHip)
        val kneeMid = PoseMath.midpoint("KNEE_MID", points.leftKnee, points.rightKnee)
        val shoulderMid = PoseMath.midpoint("SHOULDER_MID", points.leftShoulder, points.rightShoulder)
        val trunkAngle = PoseMath.trunkLeanAngleFromVertical(shoulderMid, hipMid)
        val frontCorrection = usesFrontCorrection(frame.cameraRole)
        val sideCorrection = usesSideCorrection(frame.cameraRole)
        val bodyScaleValue = bodyScale(points, useLegPrimaryScale = sideCorrection).coerceAtLeast(0.001f)
        val hipKneeGapRatio = (kneeMid.y - hipMid.y) / bodyScaleValue
        val ankleDistance = PoseMath.horizontalDistance(points.leftAnkle, points.rightAnkle).coerceAtLeast(0.001f)
        val kneeInward = (points.leftKnee.x - points.leftAnkle.x) / ankleDistance > KNEE_INWARD_RATIO &&
            (points.rightAnkle.x - points.rightKnee.x) / ankleDistance > KNEE_INWARD_RATIO
        val backLean = trunkAngle > config.backLeanAngleThreshold

        updateStage(
            frame = frame,
            kneeAngle = kneeAngle,
            hipKneeGapRatio = hipKneeGapRatio,
            hipY = hipMid.y,
            bodyScaleValue = bodyScaleValue,
            trunkAngle = trunkAngle,
            kneeInward = frontCorrection && kneeInward,
            backLean = sideCorrection && backLean,
            frontCountMode = frontCorrection,
            evaluateDepth = sideCorrection,
        )
        val outputTrunkAngle = pendingProblemTrunkAngle ?: trunkAngle
        val problems = consumePendingProblems()
        val problem = primaryProblem(problems)
        val depthLevel = when {
            !sideCorrection -> "COUNT_ONLY"
            stage == SquatStage.SQUATTING && !isDeepEnough(hipKneeGapRatio, kneeAngle) -> "SHALLOW"
            else -> "GOOD"
        }
        return output(
            problem = problem,
            problems = problems,
            kneeAngle = kneeAngle,
            trunkAngle = outputTrunkAngle,
            depthLevel = depthLevel,
        )
    }

    private fun updateStage(
        frame: PoseFrame,
        kneeAngle: Float,
        hipKneeGapRatio: Float,
        hipY: Float,
        bodyScaleValue: Float,
        trunkAngle: Float,
        kneeInward: Boolean,
        backLean: Boolean,
        frontCountMode: Boolean,
        evaluateDepth: Boolean,
    ) {
        if (frontCountMode) {
            updateFrontStage(
                frame = frame,
                kneeAngle = kneeAngle,
                hipKneeGapRatio = hipKneeGapRatio,
                hipY = hipY,
                bodyScaleValue = bodyScaleValue,
                trunkAngle = trunkAngle,
                kneeInward = kneeInward,
            )
            return
        }

        val previousStage = stage
        val lowSignal = hipKneeGapRatio <= SQUAT_ENTER_GAP_RATIO || kneeAngle <= SQUAT_ENTER_KNEE_ANGLE
        val highSignal = hipKneeGapRatio >= STANDING_RETURN_GAP_RATIO && kneeAngle >= STANDING_RETURN_KNEE_ANGLE

        if (previousStage == SquatStage.STANDING && lowSignal) {
            stage = SquatStage.SQUATTING
            currentRepStartMs = frame.timestampMs
            resetRepMetrics(keepStartTime = true)
            updateRepMetrics(hipKneeGapRatio, kneeAngle, trunkAngle, kneeInward, backLean)
            return
        }

        if (previousStage == SquatStage.SQUATTING) {
            updateRepMetrics(hipKneeGapRatio, kneeAngle, trunkAngle, kneeInward, backLean)
        }

        if (previousStage == SquatStage.SQUATTING && highSignal) {
            if (isCountableRep()) {
                count += 1
                if (repKneeInward) {
                    pendingKneeInwardProblem = true
                }
                if (repBackLean) {
                    pendingBackLeanProblem = true
                    pendingProblemTrunkAngle = repMaxTrunkAngle
                }
                if (evaluateDepth && !repDeepEnough()) {
                    pendingDepthProblem = true
                }
                val repDurationMs = currentRepStartMs?.let { frame.timestampMs - it }
                if (repDurationMs != null &&
                    (repDurationMs < config.minSquatRepDurationMs || repDurationMs > config.maxSquatRepDurationMs)
                ) {
                    pendingRhythmProblem = true
                }
            }
            stage = SquatStage.STANDING
            resetRepMetrics()
        }
    }

    private fun updateFrontStage(
        frame: PoseFrame,
        kneeAngle: Float,
        hipKneeGapRatio: Float,
        hipY: Float,
        bodyScaleValue: Float,
        trunkAngle: Float,
        kneeInward: Boolean,
    ) {
        if (frontStandingHipY == null || frontStandingBodyScale == null) {
            frontStandingHipY = hipY
            frontStandingBodyScale = bodyScaleValue
        }

        val standingHipY = frontStandingHipY ?: hipY
        val standingScale = (frontStandingBodyScale ?: bodyScaleValue).coerceAtLeast(0.001f)
        val hipDropRatio = (hipY - standingHipY) / standingScale
        val lowSignal = hipDropRatio >= FRONT_SQUAT_ENTER_HIP_DROP_RATIO
        val highSignal = hipDropRatio <= FRONT_STANDING_RETURN_HIP_DROP_RATIO

        if (stage == SquatStage.STANDING && !lowSignal) {
            updateFrontStandingBaseline(hipY, bodyScaleValue)
            return
        }

        if (stage == SquatStage.STANDING && lowSignal) {
            stage = SquatStage.SQUATTING
            currentRepStartMs = frame.timestampMs
            resetRepMetrics(keepStartTime = true)
            updateRepMetrics(hipKneeGapRatio, kneeAngle, trunkAngle, kneeInward, backLean = false)
            updateFrontRepMetrics(hipDropRatio)
            return
        }

        updateRepMetrics(hipKneeGapRatio, kneeAngle, trunkAngle, kneeInward, backLean = false)
        updateFrontRepMetrics(hipDropRatio)

        if (stage == SquatStage.SQUATTING && highSignal) {
            if (isFrontCountableRep()) {
                count += 1
                if (repKneeInward) {
                    pendingKneeInwardProblem = true
                }
                val repDurationMs = currentRepStartMs?.let { frame.timestampMs - it }
                if (repDurationMs != null &&
                    (repDurationMs < config.minSquatRepDurationMs || repDurationMs > config.maxSquatRepDurationMs)
                ) {
                    pendingRhythmProblem = true
                }
            }
            stage = SquatStage.STANDING
            resetRepMetrics()
            updateFrontStandingBaseline(hipY, bodyScaleValue)
        }
    }

    private fun updateRepMetrics(
        hipKneeGapRatio: Float,
        kneeAngle: Float,
        trunkAngle: Float,
        kneeInward: Boolean,
        backLean: Boolean,
    ) {
        repMinHipKneeGapRatio = minOf(repMinHipKneeGapRatio ?: hipKneeGapRatio, hipKneeGapRatio)
        repMinKneeAngle = minOf(repMinKneeAngle ?: kneeAngle, kneeAngle)
        repMaxTrunkAngle = maxOf(repMaxTrunkAngle ?: trunkAngle, trunkAngle)
        repKneeInward = repKneeInward || kneeInward
        repBackLean = repBackLean || backLean
        if (isDeepEnough(hipKneeGapRatio, kneeAngle)) {
            repReachedBottom = true
        }
    }

    private fun updateFrontRepMetrics(hipDropRatio: Float) {
        repMaxFrontHipDropRatio = maxOf(repMaxFrontHipDropRatio ?: hipDropRatio, hipDropRatio)
    }

    private fun isCountableRep(): Boolean =
        repMinHipKneeGapRatio != null || repMinKneeAngle != null

    private fun isFrontCountableRep(): Boolean =
        (repMaxFrontHipDropRatio ?: 0f) >= FRONT_SQUAT_MIN_HIP_DROP_RATIO

    private fun repDeepEnough(): Boolean =
        isDeepEnough(repMinHipKneeGapRatio ?: Float.MAX_VALUE, repMinKneeAngle ?: 180f)

    private fun resetRepMetrics(keepStartTime: Boolean = false) {
        if (!keepStartTime) {
            currentRepStartMs = null
        }
        repMinKneeAngle = null
        repMinHipKneeGapRatio = null
        repMaxTrunkAngle = null
        repReachedBottom = false
        repKneeInward = false
        repBackLean = false
        repMaxFrontHipDropRatio = null
    }

    private fun isDeepEnough(hipKneeGapRatio: Float, @Suppress("UNUSED_PARAMETER") kneeAngle: Float): Boolean =
        hipKneeGapRatio <= GOOD_DEPTH_GAP_RATIO

    private fun bodyScale(points: SquatPoints, useLegPrimaryScale: Boolean): Float {
        val shoulderMid = PoseMath.midpoint("SHOULDER_MID", points.leftShoulder, points.rightShoulder)
        val hipMid = PoseMath.midpoint("HIP_MID", points.leftHip, points.rightHip)
        val kneeMid = PoseMath.midpoint("KNEE_MID", points.leftKnee, points.rightKnee)
        val ankleMid = PoseMath.midpoint("ANKLE_MID", points.leftAnkle, points.rightAnkle)
        val torsoLength = PoseMath.distance(shoulderMid, hipMid)
        val legLength = PoseMath.distance(hipMid, kneeMid) + PoseMath.distance(kneeMid, ankleMid)
        return if (useLegPrimaryScale) {
            maxOf(legLength * 0.5f, torsoLength * SIDE_TORSO_SCALE_FALLBACK_WEIGHT, 0.001f)
        } else {
            maxOf(torsoLength, legLength * 0.5f, 0.001f)
        }
    }

    private fun usesFrontCorrection(role: DeviceRole): Boolean =
        role == DeviceRole.FRONT_CAMERA

    private fun usesSideCorrection(role: DeviceRole): Boolean =
        role != DeviceRole.FRONT_CAMERA

    private fun updateFrontStandingBaseline(hipY: Float, bodyScaleValue: Float) {
        val currentHipY = frontStandingHipY
        if (currentHipY == null) {
            frontStandingHipY = hipY
            frontStandingBodyScale = bodyScaleValue
            return
        }
        if (hipY <= currentHipY + FRONT_STANDING_BASELINE_MARGIN) {
            frontStandingHipY = minOf(currentHipY, hipY)
            val currentScale = frontStandingBodyScale ?: bodyScaleValue
            frontStandingBodyScale = currentScale * FRONT_BASELINE_SCALE_KEEP_WEIGHT +
                bodyScaleValue * (1f - FRONT_BASELINE_SCALE_KEEP_WEIGHT)
        }
    }

    private fun consumePendingProblems(): List<ProblemType> {
        val kneeInward = pendingKneeInwardProblem
        val backLean = pendingBackLeanProblem
        val shallow = pendingDepthProblem
        val rhythmAbnormal = pendingRhythmProblem
        pendingKneeInwardProblem = false
        pendingBackLeanProblem = false
        pendingProblemTrunkAngle = null
        pendingRhythmProblem = false
        pendingDepthProblem = false
        return buildList {
            if (kneeInward) add(ProblemType.KNEE_INWARD)
            if (backLean) add(ProblemType.BACK_LEAN_TOO_MUCH)
            if (shallow) add(ProblemType.SQUAT_DEPTH_NOT_ENOUGH)
            if (rhythmAbnormal) add(ProblemType.RHYTHM_ABNORMAL)
        }
    }

    private fun primaryProblem(problems: List<ProblemType>): ProblemType =
        listOf(
            ProblemType.KNEE_INWARD,
            ProblemType.BACK_LEAN_TOO_MUCH,
            ProblemType.SQUAT_DEPTH_NOT_ENOUGH,
            ProblemType.RHYTHM_ABNORMAL,
        ).firstOrNull { problem -> problem in problems } ?: ProblemType.NONE

    // 问题类型映射分数建议
    private fun output(
        problem: ProblemType,
        problems: List<ProblemType>,
        kneeAngle: Float?,
        trunkAngle: Float?,
        depthLevel: String?,
    ): SquatAnalysisResult {
        val score = when (problem) {
            ProblemType.SQUAT_DEPTH_NOT_ENOUGH -> 85f
            ProblemType.KNEE_INWARD -> 80f
            ProblemType.BACK_LEAN_TOO_MUCH -> 85f
            ProblemType.ASYMMETRY -> 90f
            ProblemType.LOW_CONFIDENCE -> 90f
            ProblemType.RHYTHM_ABNORMAL -> 95f
            else -> 100f
        }
        val suggestion = when (problem) {
            ProblemType.SQUAT_DEPTH_NOT_ENOUGH -> "适当增加下蹲幅度，让髋部接近膝盖高度。"
            ProblemType.KNEE_INWARD -> "下蹲时保持膝盖朝向脚尖，避免向身体中线内扣。"
            ProblemType.BACK_LEAN_TOO_MUCH -> "收紧核心，保持胸部打开，减少躯干过度前倾。"
            ProblemType.ASYMMETRY -> "注意左右脚均匀发力，保持身体中心稳定。"
            ProblemType.LOW_CONFIDENCE -> "请保持全身入镜，等待关键点稳定后再继续。"
            ProblemType.RHYTHM_ABNORMAL -> "放慢节奏，确保每次动作完整打开和收回。"
            else -> "动作表现稳定，继续保持。"
        }
        return SquatAnalysisResult(
            totalCount = count,
            currentStage = stage,
            score = score,
            kneeAngle = kneeAngle,
            trunkAngle = trunkAngle,
            depthLevel = depthLevel,
            problemType = problem,
            suggestion = suggestion,
            problemTypes = problems,
        )
    }

    private data class SquatPoints(
        val leftShoulder: LandmarkPoint,
        val rightShoulder: LandmarkPoint,
        val leftHip: LandmarkPoint,
        val rightHip: LandmarkPoint,
        val leftKnee: LandmarkPoint,
        val rightKnee: LandmarkPoint,
        val leftAnkle: LandmarkPoint,
        val rightAnkle: LandmarkPoint,
    ) {
        val required: List<LandmarkPoint> =
            listOf(leftShoulder, rightShoulder, leftHip, rightHip, leftKnee, rightKnee, leftAnkle, rightAnkle)

        companion object {
            fun from(frame: PoseFrame): SquatPoints? {
                val landmarks = frame.landmarks
                return SquatPoints(
                    leftShoulder = landmarks["LEFT_SHOULDER"] ?: return null,
                    rightShoulder = landmarks["RIGHT_SHOULDER"] ?: return null,
                    leftHip = landmarks["LEFT_HIP"] ?: return null,
                    rightHip = landmarks["RIGHT_HIP"] ?: return null,
                    leftKnee = landmarks["LEFT_KNEE"] ?: return null,
                    rightKnee = landmarks["RIGHT_KNEE"] ?: return null,
                    leftAnkle = landmarks["LEFT_ANKLE"] ?: return null,
                    rightAnkle = landmarks["RIGHT_ANKLE"] ?: return null,
                )
            }
        }
    }

    companion object {
        private const val SQUAT_ENTER_GAP_RATIO = 0.75f
        private const val SQUAT_ENTER_KNEE_ANGLE = 145f
        private const val STANDING_RETURN_GAP_RATIO = 0.95f
        private const val STANDING_RETURN_KNEE_ANGLE = 160f
        private const val GOOD_DEPTH_GAP_RATIO = 0.55f
        private const val SIDE_TORSO_SCALE_FALLBACK_WEIGHT = 0.55f
        private const val KNEE_INWARD_RATIO = 0.12f
        private const val FRONT_SQUAT_ENTER_HIP_DROP_RATIO = 0.28f
        private const val FRONT_STANDING_RETURN_HIP_DROP_RATIO = 0.18f
        private const val FRONT_SQUAT_MIN_HIP_DROP_RATIO = 0.28f
        private const val FRONT_STANDING_BASELINE_MARGIN = 0.03f
        private const val FRONT_BASELINE_SCALE_KEEP_WEIGHT = 0.85f
    }
}

class SimpleJumpingJackAnalyzer(
    private val config: PoseAnalysisConfig = PoseAnalysisConfig(),
) : JumpingJackAnalyzer {
    private var count = 0
    private var stage = JumpingJackStage.CLOSED
    private var startedAtMs = 0L
    private var lostFrames = 0

    override fun analyze(frame: PoseFrame): JumpingJackResult {
        if (startedAtMs == 0L) startedAtMs = frame.timestampMs
        val points = JumpingJackPoints.from(frame)
        if (
            frame.overallConfidence < config.minPoseConfidence ||
            points == null ||
            !PoseMath.hasMinimumConfidence(points.required, config.minPoseConfidence)
        ) {
            lostFrames += 1
            return output(frame.timestampMs)
        }

        val shoulderWidth = PoseMath.horizontalDistance(points.leftShoulder, points.rightShoulder)
        val ankleSpreadRatio = PoseMath.horizontalDistance(points.leftAnkle, points.rightAnkle) / shoulderWidth.coerceAtLeast(0.001f)
        val shoulderY = PoseMath.midpoint("SHOULDER_MID", points.leftShoulder, points.rightShoulder).y
        val leftWristDown = points.leftWrist.y > shoulderY + config.jumpingJackWristDownMargin
        val rightWristDown = points.rightWrist.y > shoulderY + config.jumpingJackWristDownMargin
        val wristsDown = leftWristDown || rightWristDown
        val open = ankleSpreadRatio >= config.jumpingJackOpenAnkleShoulderRatio
        val closed = ankleSpreadRatio <= config.jumpingJackClosedAnkleShoulderRatio

        val previousStage = stage
        stage = when {
            open && previousStage == JumpingJackStage.CLOSED -> JumpingJackStage.OPENING
            open && previousStage == JumpingJackStage.OPENING -> JumpingJackStage.OPEN
            open && previousStage == JumpingJackStage.CLOSING -> JumpingJackStage.OPEN
            open -> JumpingJackStage.OPEN
            closed && previousStage == JumpingJackStage.OPEN -> JumpingJackStage.CLOSING
            closed && previousStage == JumpingJackStage.CLOSING -> JumpingJackStage.CLOSED
            closed && previousStage == JumpingJackStage.OPENING -> JumpingJackStage.CLOSED
            closed -> JumpingJackStage.CLOSED
            else -> previousStage
        }
        if (
            closed &&
            wristsDown &&
            previousStage in setOf(JumpingJackStage.OPENING, JumpingJackStage.OPEN, JumpingJackStage.CLOSING) &&
            stage == JumpingJackStage.CLOSED
        ) {
            count += 1
        }
        return output(frame.timestampMs)
    }

    private fun output(nowMs: Long): JumpingJackResult {
        val duration = (nowMs - startedAtMs).coerceAtLeast(0L)
        val tempo = if (duration > 0) count * 60000f / duration else 0f
        return JumpingJackResult(
            totalCount = count,
            durationMs = duration,
            averageTempo = tempo,
            lostFrameCount = lostFrames,
            currentStage = stage,
        )
    }

    private data class JumpingJackPoints(
        val leftShoulder: LandmarkPoint,
        val rightShoulder: LandmarkPoint,
        val leftWrist: LandmarkPoint,
        val rightWrist: LandmarkPoint,
        val leftAnkle: LandmarkPoint,
        val rightAnkle: LandmarkPoint,
    ) {
        val required: List<LandmarkPoint> =
            listOf(leftShoulder, rightShoulder, leftWrist, rightWrist, leftAnkle, rightAnkle)

        companion object {
            fun from(frame: PoseFrame): JumpingJackPoints? {
                val landmarks = frame.landmarks
                return JumpingJackPoints(
                    leftShoulder = landmarks["LEFT_SHOULDER"] ?: return null,
                    rightShoulder = landmarks["RIGHT_SHOULDER"] ?: return null,
                    leftWrist = landmarks["LEFT_WRIST"] ?: return null,
                    rightWrist = landmarks["RIGHT_WRIST"] ?: return null,
                    leftAnkle = landmarks["LEFT_ANKLE"] ?: return null,
                    rightAnkle = landmarks["RIGHT_ANKLE"] ?: return null,
                )
            }
        }
    }

}

class SimpleBasicActionAnalyzer(
    private val config: PoseAnalysisConfig = PoseAnalysisConfig(),
) : BasicActionAnalyzer {
    private val states = mutableMapOf<ActionType, ActionState>()

    override fun analyze(actionType: ActionType, frame: PoseFrame): BasicActionAnalysisResult {
        val state = states.getOrPut(actionType) { ActionState() }
        if (!actionType.isTrainingAction) {
            return output(
                actionType = actionType,
                state = state,
                holdDurationMs = 0L,
                stageText = "非训练动作",
                problemType = ProblemType.NONE,
                suggestion = "请选择需要训练的动作。",
            )
        }
        val features = PoseActionRules.features(frame, config)
        if (frame.overallConfidence < config.minPoseConfidence) {
            state.active = false
            state.holdStartMs = null
            state.lateralRaiseWasRaised = false
            state.standingForwardBendWasBent = false
            return output(
                actionType = actionType,
                state = state,
                holdDurationMs = state.holdDurationMs,
                stageText = "低置信度暂停",
                problemType = ProblemType.LOW_CONFIDENCE,
                suggestion = "请保持全身入镜，等待关键点稳定后再继续。",
            )
        }

        if (actionType.isHoldBased) {
            val holding = features.isActiveFor(actionType)
            if (holding) {
                if (state.holdStartMs == null) {
                    state.holdStartMs = frame.timestampMs
                }
                state.holdDurationMs = (frame.timestampMs - (state.holdStartMs ?: frame.timestampMs)).coerceAtLeast(0L)
            } else {
                state.holdStartMs = null
            }
            state.active = holding
            return output(
                actionType = actionType,
                state = state,
                totalCount = state.count,
                holdDurationMs = state.holdDurationMs,
                stageText = if (holding) "保持中" else "等待进入保持姿态",
                problemType = ProblemType.NONE,
                suggestion = if (holding) {
                    "保持身体接近一条直线，稳定呼吸。"
                } else {
                    "身体保持水平，肩、髋、踝尽量在同一直线。"
                },
            )
        }

        if (actionType == ActionType.JUMP_ROPE) {
            return analyzeJumpRope(state, features, frame)
        }

        if (actionType == ActionType.LATERAL_RAISE) {
            return analyzeLateralRaise(state, features, frame)
        }

        if (actionType == ActionType.STANDING_FORWARD_BEND) {
            return analyzeStandingForwardBend(state, features, frame)
        }

        val active = features.isActiveFor(actionType)
        val side = features.activeSideFor(actionType)
        val canCountBySide = actionType in ALTERNATING_ACTIONS && side != null
        if (active && canCountBySide) {
            if (side != state.lastSide && frame.timestampMs - state.lastCountAtMs >= MIN_REP_GAP_MS) {
                state.count += 1
                state.lastSide = side
                state.lastCountAtMs = frame.timestampMs
            }
        } else if (active && !state.active && frame.timestampMs - state.lastCountAtMs >= MIN_REP_GAP_MS) {
            state.count += 1
            state.lastCountAtMs = frame.timestampMs
        }
        state.active = active
        if (!active && actionType !in ALTERNATING_ACTIONS) {
            state.lastSide = null
        }
        return output(
            actionType = actionType,
            state = state,
            holdDurationMs = 0L,
            stageText = when {
                side != null -> "动作侧：$side"
                active -> "动作进行中"
                else -> "等待完整动作"
            },
            problemType = ProblemType.NONE,
            suggestion = firstVersionSuggestion(actionType),
        )
    }

    private fun analyzeStandingForwardBend(
        state: ActionState,
        features: PoseActionFeatures,
        frame: PoseFrame,
    ): BasicActionAnalysisResult {
        val bent = features.isStandingForwardBendSignal()
        val upright = features.isStandingForwardBendRestSignal()
        if (bent) {
            state.standingForwardBendWasBent = true
            state.active = true
        } else if (upright) {
            if (
                state.standingForwardBendWasBent &&
                frame.timestampMs - state.lastCountAtMs >= MIN_REP_GAP_MS
            ) {
                state.count += 1
                state.lastCountAtMs = frame.timestampMs
            }
            state.standingForwardBendWasBent = false
            state.active = false
        } else {
            state.active = false
        }

        return output(
            actionType = ActionType.STANDING_FORWARD_BEND,
            state = state,
            holdDurationMs = 0L,
            stageText = when {
                bent -> "已俯身下探，站直后计 1 次"
                state.standingForwardBendWasBent -> "正在回正，站直后计数"
                else -> "等待俯身下探"
            },
            problemType = ProblemType.NONE,
            suggestion = firstVersionSuggestion(ActionType.STANDING_FORWARD_BEND),
        )
    }

    private fun analyzeLateralRaise(
        state: ActionState,
        features: PoseActionFeatures,
        frame: PoseFrame,
    ): BasicActionAnalysisResult {
        val raised = features.isLateralRaiseSignal()
        val resting = features.isLateralRaiseRestSignal()
        if (raised) {
            state.lateralRaiseWasRaised = true
            state.active = true
        } else if (resting) {
            if (
                state.lateralRaiseWasRaised &&
                frame.timestampMs - state.lastCountAtMs >= MIN_REP_GAP_MS
            ) {
                state.count += 1
                state.lastCountAtMs = frame.timestampMs
            }
            state.lateralRaiseWasRaised = false
            state.active = false
        } else {
            state.active = false
        }

        return output(
            actionType = ActionType.LATERAL_RAISE,
            state = state,
            holdDurationMs = 0L,
            stageText = when {
                raised -> "双臂已抬起，放回身体两侧后计 1 次"
                state.lateralRaiseWasRaised -> "正在放下，回到身体两侧后计数"
                else -> "等待抬起双臂"
            },
            problemType = ProblemType.NONE,
            suggestion = firstVersionSuggestion(ActionType.LATERAL_RAISE),
        )
    }

    private fun analyzeJumpRope(
        state: ActionState,
        features: PoseActionFeatures,
        frame: PoseFrame,
    ): BasicActionAnalysisResult {
        val active = features.isJumpRopePose()
        val currentY = features.ankleMid?.y ?: features.hipMid?.y ?: features.kneeMid?.y
        if (!active || currentY == null) {
            state.active = false
            state.jumpRopeWasLifted = false
            state.jumpRopeLastY = null
            return output(
                actionType = ActionType.JUMP_ROPE,
                state = state,
                holdDurationMs = 0L,
                stageText = "等待跳绳手位和小幅弹跳",
                problemType = ProblemType.NONE,
                suggestion = firstVersionSuggestion(ActionType.JUMP_ROPE),
            )
        }

        val previousY = state.jumpRopeLastY
        if (previousY != null) {
            val deltaY = currentY - previousY
            if (deltaY <= -JUMP_ROPE_COUNT_EPSILON) {
                state.jumpRopeWasLifted = true
            }
            if (
                state.jumpRopeWasLifted &&
                deltaY >= JUMP_ROPE_COUNT_EPSILON &&
                frame.timestampMs - state.lastCountAtMs >= MIN_JUMP_ROPE_REP_GAP_MS
            ) {
                state.count += 1
                state.lastCountAtMs = frame.timestampMs
                state.jumpRopeWasLifted = false
            }
        }
        state.jumpRopeLastY = currentY
        state.active = true
        return output(
            actionType = ActionType.JUMP_ROPE,
            state = state,
            holdDurationMs = 0L,
            stageText = if (state.jumpRopeWasLifted) "弹跳中" else "等待下一次弹跳",
            problemType = ProblemType.NONE,
            suggestion = firstVersionSuggestion(ActionType.JUMP_ROPE),
        )
    }

    private fun output(
        actionType: ActionType,
        state: ActionState,
        totalCount: Int = state.count,
        holdDurationMs: Long,
        stageText: String,
        problemType: ProblemType,
        suggestion: String,
    ): BasicActionAnalysisResult =
        BasicActionAnalysisResult(
            actionType = actionType,
            totalCount = totalCount,
            holdDurationMs = holdDurationMs,
            stageText = stageText,
            problemType = problemType,
            suggestion = suggestion,
        )

    private fun firstVersionSuggestion(actionType: ActionType): String =
        when (actionType) {
            ActionType.PUSH_UP -> "保持身体成一条直线，后续需真机调优肘角阈值。"
            ActionType.SIT_UP -> "保持髋膝弯曲，后续需真机调优躯干抬起幅度。"
            ActionType.LUNGE -> "可左右交替，也可单侧连续；每次回到站立后再下蹲更容易计数。"
            ActionType.HIGH_KNEES -> "膝盖抬到髋部附近，保持左右交替节奏。"
            ActionType.LATERAL_RAISE -> "双臂从身体两侧抬到肩部附近，再稳定放回身体两侧。"
            ActionType.MOUNTAIN_CLIMBER -> "俯撑时保持核心稳定，左右膝交替靠近胸部。"
            ActionType.JUMP_ROPE -> "手腕保持身体两侧，做出小幅甩绳动作并连续纵跳。"
            ActionType.STANDING_FORWARD_BEND -> "双脚站稳，膝盖尽量伸直，俯身下探到腿部附近后再站直。"
            else -> "保持稳定节奏，完成标准动作。"
        }

    private data class ActionState(
        var count: Int = 0,
        var active: Boolean = false,
        var lastCountAtMs: Long = Long.MIN_VALUE / 4,
        var lastSide: String? = null,
        var holdStartMs: Long? = null,
        var holdDurationMs: Long = 0L,
        var jumpRopeLastY: Float? = null,
        var jumpRopeWasLifted: Boolean = false,
        var lateralRaiseWasRaised: Boolean = false,
        var standingForwardBendWasBent: Boolean = false,
    )

    companion object {
        private const val MIN_REP_GAP_MS = 250L
        private const val MIN_JUMP_ROPE_REP_GAP_MS = 180L
        private const val JUMP_ROPE_COUNT_EPSILON = 0.0035f
        private val ALTERNATING_ACTIONS = setOf(
            ActionType.HIGH_KNEES,
            ActionType.MOUNTAIN_CLIMBER,
        )
    }
}
