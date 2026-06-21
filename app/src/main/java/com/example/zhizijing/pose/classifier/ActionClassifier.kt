package com.example.zhizijing.pose.classifier

import com.example.zhizijing.domain.model.ActionClassificationResult
import com.example.zhizijing.domain.model.ActionType
import com.example.zhizijing.domain.model.ClassifierSource
import com.example.zhizijing.domain.model.DeviceRole
import com.example.zhizijing.domain.rule.PoseAnalysisConfig
import com.example.zhizijing.pose.feature.PoseActionRules
import com.example.zhizijing.pose.model.PoseFrame

interface ActionClassifier {
    fun classify(frames: List<PoseFrame>): ActionClassificationResult
}

class RuleBasedActionClassifier(
    private val config: PoseAnalysisConfig = PoseAnalysisConfig(),
) : ActionClassifier {
    override fun classify(frames: List<PoseFrame>): ActionClassificationResult {
        if (frames.isEmpty()) {
            return result(ActionType.UNKNOWN, 0f, 0L, 0L)
        }

        val frontFrames = frames.filter { frame -> frame.cameraRole == DeviceRole.FRONT_CAMERA }
        val classificationFrames = if (
            frontFrames.isNotEmpty() &&
            !PoseActionRules.allLowConfidence(frontFrames, config)
        ) {
            frontFrames
        } else {
            frames
        }
        if (PoseActionRules.allLowConfidence(classificationFrames, config)) {
            return result(
                ActionType.UNKNOWN,
                0f,
                classificationFrames.first().timestampMs,
                classificationFrames.last().timestampMs,
            )
        }

        // 连续帧转规则特征
        val features = classificationFrames.map { frame -> PoseActionRules.features(frame, config) }
        val mountainClimberMotion = PoseActionRules.hasMountainClimberMotion(features)
        val staticProneHold = !mountainClimberMotion && PoseActionRules.hasStaticProneHold(features)
        val plankSupport = if (mountainClimberMotion) {
            0
        } else if (staticProneHold) {
            features.size
        } else {
            features.count { it.isPlankSignal() }
        }
        val mountainClimberSupport = if (mountainClimberMotion) {
            features.size
        } else {
            features.count { it.isMountainClimberSignal() }
        }
        val lungeSupport = features.count { it.isLungeSignal() }
        val jumpRopeCycle = PoseActionRules.hasJumpRopeCycle(features)
        val lateralRaiseSupport = if (jumpRopeCycle) {
            0
        } else if (PoseActionRules.hasLateralRaiseWindow(features)) {
            features.size
        } else {
            features.count { it.isLateralRaiseSignal() }
        }
        val nonLungeFeatures = features.filterNot { it.isLungeSignal() }
        val squatFrames = if (staticProneHold) {
            emptyList()
        } else {
            nonLungeFeatures.filterNot { it.isPlankSignal() || it.isProneHoldCandidate() }
        }
        val pushUpSupport = if (staticProneHold) {
            0
        } else {
            features.count { it.isPushUpSignal() }
        }
        val squatSupport = if (PoseActionRules.hasSquatMotionSequence(squatFrames)) {
            squatFrames.size
        } else {
            squatFrames.count { it.isSquatSignal() }
        }
        val sitUpSupport = if (squatSupport > 0) 0 else features.count { it.isSitUpSignal() }
        val candidates = listOf(
            ActionCandidate(ActionType.JUMPING_JACK, features.count { it.isJumpingJackOpen() }, 0.78f, 13),
            ActionCandidate(ActionType.LATERAL_RAISE, lateralRaiseSupport, 0.73f, 12),
            ActionCandidate(
                ActionType.MOUNTAIN_CLIMBER,
                mountainClimberSupport,
                if (mountainClimberMotion) 0.78f else 0.73f,
                13,
            ),
            ActionCandidate(ActionType.PUSH_UP, pushUpSupport, 0.73f, 10),
            ActionCandidate(ActionType.LUNGE, lungeSupport, 0.74f, 8),
            ActionCandidate(ActionType.HIGH_KNEES, features.count { it.isHighKneesSignal() }, 0.74f, 9),
            ActionCandidate(ActionType.JUMP_ROPE, if (jumpRopeCycle) features.size else 0, 0.74f, 12),
            ActionCandidate(ActionType.PLANK, plankSupport, if (staticProneHold) 0.78f else 0.73f, if (staticProneHold) 12 else 9),
            ActionCandidate(ActionType.SQUAT, squatSupport, 0.72f, 7),
            ActionCandidate(ActionType.STANDING_FORWARD_BEND, features.count { it.isStandingForwardBendSignal() }, 0.72f, 6),
            ActionCandidate(ActionType.SIT_UP, sitUpSupport, 0.71f, 4),
            ActionCandidate(ActionType.STANDING, features.count { it.isStandingSignal() }, 0.68f, 1),
        )
        val requiredSupport = requiredSupport(features.size)
        val trainingBest = candidates
            .filter { candidate -> candidate.actionType != ActionType.STANDING }
            .filter { candidate -> candidate.support >= requiredSupport }
            .maxWithOrNull(
                compareBy<ActionCandidate> { candidate -> candidate.support }
                    .thenBy { candidate -> candidate.priority }
            )
        val standingBest = candidates
            .filter { candidate -> candidate.actionType == ActionType.STANDING }
            .filter { candidate -> candidate.support >= requiredSupport }
            .maxWithOrNull(
                compareBy<ActionCandidate> { candidate -> candidate.support }
                    .thenBy { candidate -> candidate.priority }
            )
        val best = trainingBest ?: standingBest

        return if (best == null) {
            result(ActionType.UNKNOWN, 0.35f, classificationFrames.first().timestampMs, classificationFrames.last().timestampMs)
        } else {
            acceptedResult(best.actionType, best.confidence, classificationFrames.first().timestampMs, classificationFrames.last().timestampMs)
        }
    }

    private fun acceptedResult(
        actionType: ActionType,
        confidence: Float,
        startMs: Long,
        endMs: Long,
    ): ActionClassificationResult =
        if (confidence >= config.actionRecognitionThreshold) {
            result(actionType, confidence, startMs, endMs)
        } else {
            result(ActionType.UNKNOWN, confidence, startMs, endMs)
        }

    private fun result(
        actionType: ActionType,
        confidence: Float,
        startMs: Long,
        endMs: Long,
    ): ActionClassificationResult =
        ActionClassificationResult(
            actionType = actionType,
            confidence = confidence,
            windowStartMs = startMs,
            windowEndMs = endMs,
            source = ClassifierSource.RULE_BASED,
        )

    private fun requiredSupport(frameCount: Int): Int =
        when {
            frameCount <= 3 -> 1
            frameCount <= 8 -> 2
            else -> 3
        }

    private data class ActionCandidate(
        val actionType: ActionType,
        val support: Int,
        val confidence: Float,
        val priority: Int,
    )
}
