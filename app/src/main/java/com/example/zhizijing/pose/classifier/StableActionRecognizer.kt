package com.example.zhizijing.pose.classifier

import com.example.zhizijing.domain.model.ActionClassificationResult
import com.example.zhizijing.domain.model.ActionType

data class StableActionRecognition(
    val result: ActionClassificationResult,
    val candidateActionType: ActionType,
    val stableWindowCount: Int,
    val isConfirmed: Boolean,
)

class StableActionRecognizer(
    private val requiredStableWindows: Int = DEFAULT_REQUIRED_STABLE_WINDOWS,
    private val unknownResetWindows: Int = DEFAULT_UNKNOWN_RESET_WINDOWS,
) {
    private var candidateActionType = ActionType.UNKNOWN
    private var candidateWindowCount = 0
    private var confirmedActionType = ActionType.UNKNOWN
    private var confirmedConfidence = 0f
    private var unknownWindowCount = 0

    fun observe(classification: ActionClassificationResult): StableActionRecognition {
        if (classification.actionType == ActionType.UNKNOWN) {
            unknownWindowCount += 1
            if (unknownWindowCount >= unknownResetWindows) {
                candidateActionType = ActionType.UNKNOWN
                candidateWindowCount = 0
                confirmedActionType = ActionType.UNKNOWN
                confirmedConfidence = 0f
            }
            val outputActionType = if (unknownWindowCount < unknownResetWindows) {
                confirmedActionType
            } else {
                ActionType.UNKNOWN
            }
            return StableActionRecognition(
                result = classification.copy(
                    actionType = outputActionType,
                    confidence = if (outputActionType == ActionType.UNKNOWN) 0f else confirmedConfidence,
                ),
                candidateActionType = candidateActionType,
                stableWindowCount = candidateWindowCount,
                isConfirmed = outputActionType != ActionType.UNKNOWN,
            )
        }

        unknownWindowCount = 0
        if (classification.actionType == candidateActionType) {
            candidateWindowCount += 1
        } else {
            candidateActionType = classification.actionType
            candidateWindowCount = 1
        }
        if (candidateWindowCount >= requiredStableWindows) {
            confirmedActionType = classification.actionType
            confirmedConfidence = classification.confidence
        }
        val outputActionType = confirmedActionType
        return StableActionRecognition(
            result = classification.copy(
                actionType = outputActionType,
                confidence = if (outputActionType == classification.actionType) {
                    classification.confidence
                } else {
                    confirmedConfidence
                },
            ),
            candidateActionType = candidateActionType,
            stableWindowCount = candidateWindowCount,
            isConfirmed = outputActionType != ActionType.UNKNOWN,
        )
    }

    fun reset() {
        candidateActionType = ActionType.UNKNOWN
        candidateWindowCount = 0
        confirmedActionType = ActionType.UNKNOWN
        confirmedConfidence = 0f
        unknownWindowCount = 0
    }

    companion object {
        private const val DEFAULT_REQUIRED_STABLE_WINDOWS = 2
        private const val DEFAULT_UNKNOWN_RESET_WINDOWS = 6
    }
}
