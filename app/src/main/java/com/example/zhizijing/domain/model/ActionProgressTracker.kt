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
        val best = when {
            previous == null -> next
            actionType.isHoldBased && next.holdDurationMs >= previous.holdDurationMs -> next
            actionType.isCountBased && next.totalCount >= previous.totalCount -> next
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
}
