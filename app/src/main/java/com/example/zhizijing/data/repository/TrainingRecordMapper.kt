package com.example.zhizijing.data.repository

import com.example.zhizijing.data.entity.ActionResultEntity
import com.example.zhizijing.data.entity.DeviceNodeEntity
import com.example.zhizijing.data.entity.PoseFrameEntity
import com.example.zhizijing.data.entity.TrainingSessionEntity
import com.example.zhizijing.domain.model.ActionType
import com.example.zhizijing.domain.model.DeviceRole
import com.example.zhizijing.domain.model.ProblemType
import com.example.zhizijing.domain.model.TrainingState
import com.example.zhizijing.domain.model.TrainingSummary
import com.example.zhizijing.pose.model.PoseFrame
import com.google.gson.Gson

object TrainingRecordMapper {
    private val gson = Gson()

    fun toSessionEntity(
        userId: Long,
        summary: TrainingSummary,
        deviceCount: Int = 1,
    ): TrainingSessionEntity {
        val endTime = summary.timestampMs
        val startTime = (endTime - summary.durationMs).coerceAtLeast(0L)
        return TrainingSessionEntity(
            userId,
            summary.actionType.name,
            startTime,
            endTime,
            deviceCount.coerceAtLeast(1),
            summary.totalCount,
            summary.averageScore,
            summary.mainProblem.name,
            null,
            TrainingState.FINISHED.name,
        )
    }

    fun toDeviceNodeEntity(
        sessionId: Long,
        snapshot: TrainingDeviceSnapshot,
    ): DeviceNodeEntity =
        DeviceNodeEntity(
            sessionId,
            snapshot.deviceName.ifBlank { "未知设备" },
            snapshot.endpointId,
            snapshot.role.name,
            snapshot.batteryLevel,
            snapshot.networkDelayMs,
            snapshot.isOnline,
            snapshot.lastHeartbeatAt,
        )

    fun toActionResults(sessionId: Long, summary: TrainingSummary): List<ActionResultEntity> {
        if (summary.actionType.isHoldBased && summary.durationMs > 0L) {
            return listOf(
                ActionResultEntity(
                    sessionId,
                    1,
                    summary.actionType.name,
                    (summary.timestampMs - summary.durationMs).coerceAtLeast(0L),
                    summary.timestampMs,
                    null,
                    null,
                    null,
                    "HOLDING",
                    summary.mainProblem.name,
                    summary.suggestion,
                    null,
                )
            )
        }
        if (summary.totalCount <= 0) return emptyList()
        val durationPerAction = if (summary.totalCount > 0) {
            summary.durationMs / summary.totalCount
        } else {
            summary.durationMs
        }.coerceAtLeast(1L)
        return (1..summary.totalCount).map { index ->
            val start = summary.timestampMs - summary.durationMs + (index - 1) * durationPerAction
            ActionResultEntity(
                sessionId,
                index,
                summary.actionType.name,
                start,
                start + durationPerAction,
                summary.averageScore,
                null,
                null,
                if (summary.mainProblem == ProblemType.SQUAT_DEPTH_NOT_ENOUGH) "SHALLOW" else "GOOD",
                summary.mainProblem.name,
                summary.suggestion,
                null,
            )
        }
    }

    fun toPoseFrameSamples(sessionId: Long, summary: TrainingSummary): List<PoseFrameEntity> {
        if (summary.totalCount <= 0) return emptyList()
        val frameCountPerAction = 4
        val totalFrames = summary.totalCount * frameCountPerAction
        val frameDuration = if (totalFrames > 0) {
            summary.durationMs / totalFrames
        } else {
            summary.durationMs
        }.coerceAtLeast(1L)
        val startTime = (summary.timestampMs - summary.durationMs).coerceAtLeast(0L)
        return (0 until totalFrames).map { frameIndex ->
            val phase = frameIndex % frameCountPerAction
            PoseFrameEntity(
                sessionId,
                1L,
                startTime + frameIndex * frameDuration,
                DeviceRole.FRONT_CAMERA.name,
                landmarksJsonFor(summary.actionType, summary.mainProblem, phase),
                0.95f,
                null,
                "RAW_POSE_SAMPLE",
            )
        }
    }

    fun toPoseFrameEntity(
        sessionId: Long,
        frame: PoseFrame,
        frameType: String,
    ): PoseFrameEntity =
        PoseFrameEntity(
            sessionId,
            frame.nodeId,
            frame.timestampMs,
            frame.cameraRole.name,
            gson.toJson(frame.landmarks),
            frame.overallConfidence,
            frame.rgbImagePath,
            frameType,
        )

    fun toSummary(
        session: TrainingSessionEntity,
        actionResults: List<ActionResultEntity> = emptyList(),
    ): TrainingSummary {
        val problem = problemFrom(session.mainProblems)
        val suggestion = actionResults.firstOrNull { result ->
            problemFrom(result.problemType) == problem && !result.suggestion.isNullOrBlank()
        }?.suggestion
            ?: actionResults.firstOrNull { !it.suggestion.isNullOrBlank() }?.suggestion
            ?: suggestionFor(problem)
        val endTime = session.endTime ?: session.startTime
        return TrainingSummary(
            sessionId = session.sessionId,
            userId = session.userId,
            actionType = ActionType.fromName(session.actionType),
            totalCount = session.totalCount,
            averageScore = session.averageScore,
            durationMs = (endTime - session.startTime).coerceAtLeast(0L),
            mainProblem = problem,
            suggestion = suggestion,
            timestampMs = endTime,
            reportPath = session.reportPath,
        )
    }

    fun problemFrom(raw: String?): ProblemType =
        raw.orEmpty()
            .split(',', '|')
            .firstOrNull { it.isNotBlank() }
            ?.let { runCatching { ProblemType.valueOf(it) }.getOrNull() }
            ?: ProblemType.NONE

    fun applyRecognizedActionOverview(
        session: TrainingSessionEntity,
        actionResults: List<ActionResultEntity>,
    ) {
        val actionScores = actionResults.mapNotNull { result -> result.score }
        session.averageScore = actionScores.takeIf { it.isNotEmpty() }?.average()?.toFloat()
        val detectedProblem = actionResults
            .asSequence()
            .map { result -> problemFrom(result.problemType) }
            .filter { problem -> problem != ProblemType.NONE }
            .groupingBy { problem -> problem }
            .eachCount()
            .entries
            .maxWithOrNull(
                compareBy<Map.Entry<ProblemType, Int>> { entry -> entry.value }
                    .thenBy { entry -> overviewPriority(entry.key) }
            )
            ?.key
        if (detectedProblem != null || problemFrom(session.mainProblems) == ProblemType.NONE) {
            session.mainProblems = (detectedProblem ?: ProblemType.NONE).name
        }
    }

    fun suggestionFor(problemType: ProblemType): String =
        when (problemType) {
            ProblemType.SQUAT_DEPTH_NOT_ENOUGH -> "适当增加下蹲幅度，让髋部接近膝盖高度。"
            ProblemType.KNEE_INWARD -> "下蹲时保持膝盖朝向脚尖，避免向身体中线内扣。"
            ProblemType.BACK_LEAN_TOO_MUCH -> "收紧核心，保持胸部打开，减少躯干过度前倾。"
            ProblemType.ASYMMETRY -> "注意左右脚均匀发力，保持身体中心稳定。"
            ProblemType.LOW_CONFIDENCE -> "请保持全身入镜，等待关键点稳定后再继续。"
            ProblemType.RHYTHM_ABNORMAL -> "放慢节奏，确保每次动作完整打开和收回。"
            ProblemType.NONE -> "保持全身入镜，按稳定节奏完成动作。"
        }

    private fun overviewPriority(problemType: ProblemType): Int =
        when (problemType) {
            ProblemType.LOW_CONFIDENCE -> 6
            ProblemType.KNEE_INWARD -> 5
            ProblemType.BACK_LEAN_TOO_MUCH -> 4
            ProblemType.ASYMMETRY -> 3
            ProblemType.SQUAT_DEPTH_NOT_ENOUGH -> 2
            ProblemType.RHYTHM_ABNORMAL -> 1
            ProblemType.NONE -> 0
        }

    private fun landmarksJsonFor(
        actionType: ActionType,
        problemType: ProblemType,
        phase: Int,
    ): String =
        when (actionType) {
            ActionType.JUMPING_JACK -> jumpingJackLandmarks(phase)
            ActionType.SQUAT -> squatLandmarks(problemType, phase)
            ActionType.PUSH_UP,
            ActionType.SIT_UP,
            ActionType.LUNGE,
            ActionType.HIGH_KNEES,
            ActionType.PLANK,
            ActionType.LATERAL_RAISE,
            ActionType.MOUNTAIN_CLIMBER,
            ActionType.JUMP_ROPE,
            ActionType.STANDING_FORWARD_BEND -> basicActionLandmarks(actionType, phase)
            else -> squatLandmarks(problemType, phase)
        }.let { landmarks ->
            gson.toJson(landmarks)
        }

    private fun squatLandmarks(
        problemType: ProblemType,
        phase: Int,
    ): Map<String, SamplePoint> {
        val isBottom = phase == 1 || phase == 2
        val hipY = when {
            !isBottom -> 0.54f
            problemType == ProblemType.SQUAT_DEPTH_NOT_ENOUGH -> 0.64f
            else -> 0.72f
        }
        val kneeY = if (isBottom) 0.74f else 0.72f
        val kneeOffset = if (problemType == ProblemType.KNEE_INWARD && isBottom) 0.03f else 0f
        val shoulderShift = if (problemType == ProblemType.BACK_LEAN_TOO_MUCH && isBottom) 0.09f else 0f
        val leftKneeX = 0.42f + kneeOffset
        val rightKneeX = 0.58f - kneeOffset
        return mapOf(
            "LEFT_SHOULDER" to SamplePoint(0.38f + shoulderShift, 0.32f),
            "RIGHT_SHOULDER" to SamplePoint(0.62f + shoulderShift, 0.32f),
            "LEFT_HIP" to SamplePoint(0.42f, hipY),
            "RIGHT_HIP" to SamplePoint(0.58f, hipY),
            "LEFT_KNEE" to SamplePoint(leftKneeX, kneeY),
            "RIGHT_KNEE" to SamplePoint(rightKneeX, kneeY),
            "LEFT_ANKLE" to SamplePoint(0.39f, 0.91f),
            "RIGHT_ANKLE" to SamplePoint(0.61f, 0.91f),
            "LEFT_WRIST" to SamplePoint(0.32f, 0.48f),
            "RIGHT_WRIST" to SamplePoint(0.68f, 0.48f),
        )
    }

    private fun jumpingJackLandmarks(phase: Int): Map<String, SamplePoint> {
        val open = phase == 1 || phase == 2
        val ankleSpread = if (open) 0.32f else 0.16f
        val wristY = if (open) 0.18f else 0.62f
        return mapOf(
            "LEFT_SHOULDER" to SamplePoint(0.39f, 0.34f),
            "RIGHT_SHOULDER" to SamplePoint(0.61f, 0.34f),
            "LEFT_HIP" to SamplePoint(0.43f, 0.56f),
            "RIGHT_HIP" to SamplePoint(0.57f, 0.56f),
            "LEFT_KNEE" to SamplePoint(0.46f - ankleSpread / 3f, 0.74f),
            "RIGHT_KNEE" to SamplePoint(0.54f + ankleSpread / 3f, 0.74f),
            "LEFT_ANKLE" to SamplePoint(0.5f - ankleSpread, 0.91f),
            "RIGHT_ANKLE" to SamplePoint(0.5f + ankleSpread, 0.91f),
            "LEFT_WRIST" to SamplePoint(0.29f, wristY),
            "RIGHT_WRIST" to SamplePoint(0.71f, wristY),
        )
    }

    private fun basicActionLandmarks(actionType: ActionType, phase: Int): Map<String, SamplePoint> =
        when (actionType) {
            ActionType.PUSH_UP -> horizontalBody(
                elbowY = if (phase == 1 || phase == 2) 0.58f else 0.50f,
                leftKneeY = 0.57f,
                rightKneeY = 0.57f,
            )
            ActionType.PLANK -> horizontalBody(
                elbowY = 0.50f,
                leftKneeY = 0.57f,
                rightKneeY = 0.57f,
            )
            ActionType.MOUNTAIN_CLIMBER -> horizontalBody(
                elbowY = 0.50f,
                leftKneeY = if (phase % 2 == 0) 0.45f else 0.58f,
                rightKneeY = if (phase % 2 == 0) 0.58f else 0.45f,
            )
            ActionType.SIT_UP -> uprightBody(
                hipY = 0.66f,
                leftKneeY = 0.76f,
                rightKneeY = 0.76f,
                leftKneeX = 0.42f,
                rightKneeX = 0.58f,
                shoulderY = if (phase == 1 || phase == 2) 0.48f else 0.62f,
                wristY = 0.52f,
            )
            ActionType.LUNGE -> uprightBody(
                hipY = 0.63f,
                leftKneeY = if (phase % 2 == 0) 0.70f else 0.60f,
                rightKneeY = if (phase % 2 == 0) 0.60f else 0.70f,
                leftKneeX = if (phase % 2 == 0) 0.36f else 0.44f,
                rightKneeX = if (phase % 2 == 0) 0.56f else 0.64f,
                shoulderY = 0.32f,
                wristY = 0.50f,
            )
            ActionType.HIGH_KNEES -> uprightBody(
                hipY = 0.56f,
                leftKneeY = if (phase % 2 == 0) 0.54f else 0.74f,
                rightKneeY = if (phase % 2 == 0) 0.74f else 0.54f,
                leftKneeX = 0.44f,
                rightKneeX = 0.56f,
                shoulderY = 0.32f,
                wristY = 0.46f,
            )
            ActionType.STANDING_FORWARD_BEND -> standingForwardBendLandmarks(phase)
            ActionType.JUMP_ROPE -> uprightBody(
                hipY = 0.56f,
                leftKneeY = 0.73f,
                rightKneeY = 0.73f,
                leftKneeX = 0.45f,
                rightKneeX = 0.55f,
                shoulderY = 0.32f,
                wristY = 0.54f,
                ankleY = if (phase == 1 || phase == 2) 0.87f else 0.92f,
                ankleSpread = 0.08f,
            )
            ActionType.LATERAL_RAISE -> lateralRaiseLandmarks(phase)
            else -> squatLandmarks(ProblemType.NONE, phase)
        }

    private fun standingForwardBendLandmarks(phase: Int): Map<String, SamplePoint> {
        val bent = phase == 1 || phase == 2
        return uprightBody(
            hipY = 0.56f,
            leftKneeY = 0.74f,
            rightKneeY = 0.74f,
            leftKneeX = 0.43f,
            rightKneeX = 0.57f,
            shoulderY = if (bent) 0.54f else 0.32f,
            wristY = if (bent) 0.76f else 0.58f,
            ankleSpread = 0.08f,
        )
    }

    private fun lateralRaiseLandmarks(phase: Int): Map<String, SamplePoint> {
        val raised = phase == 1 || phase == 2
        val leftElbow = if (raised) SamplePoint(0.30f, 0.35f) else SamplePoint(0.35f, 0.50f)
        val rightElbow = if (raised) SamplePoint(0.70f, 0.35f) else SamplePoint(0.65f, 0.50f)
        val leftWrist = if (raised) SamplePoint(0.20f, 0.36f) else SamplePoint(0.31f, 0.58f)
        val rightWrist = if (raised) SamplePoint(0.80f, 0.36f) else SamplePoint(0.69f, 0.58f)
        return mapOf(
            "LEFT_SHOULDER" to SamplePoint(0.39f, 0.34f),
            "RIGHT_SHOULDER" to SamplePoint(0.61f, 0.34f),
            "LEFT_ELBOW" to leftElbow,
            "RIGHT_ELBOW" to rightElbow,
            "LEFT_WRIST" to leftWrist,
            "RIGHT_WRIST" to rightWrist,
            "LEFT_HIP" to SamplePoint(0.43f, 0.56f),
            "RIGHT_HIP" to SamplePoint(0.57f, 0.56f),
            "LEFT_KNEE" to SamplePoint(0.43f, 0.74f),
            "RIGHT_KNEE" to SamplePoint(0.57f, 0.74f),
            "LEFT_ANKLE" to SamplePoint(0.42f, 0.92f),
            "RIGHT_ANKLE" to SamplePoint(0.58f, 0.92f),
        )
    }

    private fun uprightBody(
        hipY: Float,
        leftKneeY: Float,
        rightKneeY: Float,
        leftKneeX: Float,
        rightKneeX: Float,
        shoulderY: Float,
        wristY: Float,
        ankleY: Float = 0.92f,
        ankleSpread: Float = 0.16f,
    ): Map<String, SamplePoint> =
        mapOf(
            "LEFT_SHOULDER" to SamplePoint(0.39f, shoulderY),
            "RIGHT_SHOULDER" to SamplePoint(0.61f, shoulderY),
            "LEFT_ELBOW" to SamplePoint(0.34f, wristY - 0.02f),
            "RIGHT_ELBOW" to SamplePoint(0.66f, wristY - 0.02f),
            "LEFT_WRIST" to SamplePoint(0.31f, wristY),
            "RIGHT_WRIST" to SamplePoint(0.69f, wristY),
            "LEFT_HIP" to SamplePoint(0.43f, hipY),
            "RIGHT_HIP" to SamplePoint(0.57f, hipY),
            "LEFT_KNEE" to SamplePoint(leftKneeX, leftKneeY),
            "RIGHT_KNEE" to SamplePoint(rightKneeX, rightKneeY),
            "LEFT_ANKLE" to SamplePoint(0.5f - ankleSpread, ankleY),
            "RIGHT_ANKLE" to SamplePoint(0.5f + ankleSpread, ankleY),
        )

    private fun horizontalBody(
        elbowY: Float,
        leftKneeY: Float,
        rightKneeY: Float,
    ): Map<String, SamplePoint> =
        mapOf(
            "LEFT_SHOULDER" to SamplePoint(0.26f, 0.50f),
            "RIGHT_SHOULDER" to SamplePoint(0.30f, 0.50f),
            "LEFT_ELBOW" to SamplePoint(0.34f, elbowY),
            "RIGHT_ELBOW" to SamplePoint(0.36f, elbowY),
            "LEFT_WRIST" to SamplePoint(0.42f, 0.50f),
            "RIGHT_WRIST" to SamplePoint(0.44f, 0.50f),
            "LEFT_HIP" to SamplePoint(0.56f, 0.54f),
            "RIGHT_HIP" to SamplePoint(0.59f, 0.54f),
            "LEFT_KNEE" to SamplePoint(0.68f, leftKneeY),
            "RIGHT_KNEE" to SamplePoint(0.70f, rightKneeY),
            "LEFT_ANKLE" to SamplePoint(0.86f, 0.58f),
            "RIGHT_ANKLE" to SamplePoint(0.88f, 0.58f),
        )

    private data class SamplePoint(
        val x: Float,
        val y: Float,
        val z: Float = 0f,
        val confidence: Float = 0.95f,
    )
}
