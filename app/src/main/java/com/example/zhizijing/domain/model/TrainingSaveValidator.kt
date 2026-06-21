package com.example.zhizijing.domain.model

object TrainingSaveValidator {
    fun errorFor(
        actionType: ActionType,
        totalCount: Int,
        holdDurationMs: Long = 0L,
    ): String? =
        when {
            !actionType.isTrainingAction ->
                "尚未稳定识别出可训练动作，请先完成 ${ActionType.trainingActionNamesText()} 中的一种动作。"
            actionType.isHoldBased && holdDurationMs < MIN_HOLD_DURATION_MS ->
                "还没有完成有效${actionType.displayName}保持，请至少稳定保持 ${MIN_HOLD_DURATION_MS / 1000} 秒后再保存训练记录。"
            actionType.isCountBased && totalCount <= 0 ->
                "还没有完成一次${actionType.displayName}，请完成至少 1 次后再保存训练记录。"
            else -> null
        }

    private const val MIN_HOLD_DURATION_MS = 1_000L
}
