package com.example.zhizijing.ui.result

import com.example.zhizijing.domain.model.ActionType

object ResultFallbackFormatter {
    fun qualifiedCountText(
        actionType: ActionType,
        totalCount: Int,
    ): String =
        when {
            totalCount <= 0 -> "0"
            actionType.isCountBased && !actionType.supportsDetailedScore -> totalCount.toString()
            else -> "暂无（兜底数据无逐次评分）"
        }
}
