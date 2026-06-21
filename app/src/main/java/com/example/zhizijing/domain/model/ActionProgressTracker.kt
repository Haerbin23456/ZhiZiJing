package com.example.zhizijing.domain.model

data class ActionProgressSnapshot(
    val actionType: ActionType,
    val totalCount: Int,
    val holdDurationMs: Long,
    val score: Float?,
    val problemType: ProblemType,
    val suggestion: String?,
)

class ActionProgressTracker {
    private val progressByAction = linkedMapOf<ActionType, ActionProgressSnapshot>()

    fun record(
        actionType: ActionType,
        totalCount: Int,
        holdDurationMs: Long,
        score: Float?,
        problemType: ProblemType,
        suggestion: String?,
    ): ActionProgressSnapshot {
        if (!actionType.isTrainingAction) {
            return ActionProgressSnapshot(actionType, totalCount, holdDurationMs, score, problemType, suggestion)
        }
        val previous = progressByAction[actionType]
        val normalizedTotalCount = totalCount.coerceAtLeast(0)
        val next = ActionProgressSnapshot(
            actionType = actionType,
            totalCount = normalizedTotalCount,
            holdDurationMs = holdDurationMs.coerceAtLeast(0L),
            score = score,
            problemType = problemType,
            suggestion = suggestion,
        )
        val nextWithStableProblem = if (
            previous != null &&
            actionType.isCountBased &&
            next.problemType == ProblemType.NONE &&
            previous.problemType.isPersistentPostureProblem()
        ) {
            next.copy(
                score = previous.score ?: next.score,
                problemType = previous.problemType,
                suggestion = previous.suggestion ?: next.suggestion,
            )
        } else {
            next
        }
        val best = when {
            previous == null -> nextWithStableProblem
            actionType.isHoldBased && nextWithStableProblem.holdDurationMs >= previous.holdDurationMs -> nextWithStableProblem
            actionType.isCountBased && nextWithStableProblem.totalCount >= previous.totalCount -> nextWithStableProblem
            else -> previous
        }
        progressByAction[actionType] = best
        return best
    }

    fun bestFor(actionType: ActionType): ActionProgressSnapshot? =
        progressByAction[actionType]

    fun reset() {
        progressByAction.clear()
    }

    private fun ProblemType.isPersistentPostureProblem(): Boolean =
        this == ProblemType.SQUAT_DEPTH_NOT_ENOUGH ||
            this == ProblemType.KNEE_INWARD ||
            this == ProblemType.BACK_LEAN_TOO_MUCH
}
