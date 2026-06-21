package com.example.zhizijing.pose.classifier

import com.example.zhizijing.domain.model.ActionClassificationResult
import com.example.zhizijing.domain.model.ActionType

data class BestActionRecognition(
    val actionType: ActionType = ActionType.UNKNOWN,
    val confidence: Float = 0f,
    val observedAtMs: Long = 0L,
    val isConfirmed: Boolean = false,
) {
    val hasTrainingAction: Boolean
        get() = actionType.isTrainingAction
}

class BestActionRecognitionTracker {
    private var best = BestActionRecognition()

    fun observe(
        classification: ActionClassificationResult,
        isConfirmed: Boolean,
    ) {
        val actionType = classification.actionType
        if (!actionType.isTrainingAction) return
        val confidence = classification.confidence.takeIf { it.isFinite() } ?: return
        val candidate = BestActionRecognition(
            actionType = actionType,
            confidence = confidence.coerceIn(0f, 1f),
            observedAtMs = classification.windowEndMs,
            isConfirmed = isConfirmed,
        )
        if (candidate.isBetterThan(best)) {
            best = candidate
        }
    }

    fun best(): BestActionRecognition = best

    fun reset() {
        best = BestActionRecognition()
    }

    private fun BestActionRecognition.isBetterThan(current: BestActionRecognition): Boolean =
        when {
            !current.hasTrainingAction -> true
            isConfirmed != current.isConfirmed -> isConfirmed
            confidence != current.confidence -> confidence > current.confidence
            else -> observedAtMs >= current.observedAtMs
        }
}
