package com.example.zhizijing.domain.rule

import com.example.zhizijing.domain.model.ProblemType

object SquatScorePolicy {
    fun reportableScore(
        problemType: ProblemType,
        score: Float?,
    ): Float? =
        score?.takeUnless { problemType == ProblemType.LOW_CONFIDENCE }

    fun displayText(
        problemType: ProblemType,
        score: Float?,
    ): String =
        reportableScore(problemType, score)?.toInt()?.toString() ?: "暂不评分"
}
