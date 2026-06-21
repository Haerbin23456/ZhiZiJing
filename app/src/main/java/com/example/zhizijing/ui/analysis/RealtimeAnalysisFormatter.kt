package com.example.zhizijing.ui.analysis

object RealtimeAnalysisFormatter {
    fun postureLevelText(rawLevel: String?): String =
        when (rawLevel) {
            "GOOD" -> "良好"
            "SHALLOW" -> "下蹲深度不足"
            "LOW_CONFIDENCE" -> "低置信度，暂不评估"
            "COUNT_ONLY" -> "仅计数"
            else -> "暂无"
        }

    fun coreAngleText(
        kneeAngle: Float?,
        trunkAngle: Float?,
    ): String =
        "膝角 ${angleText(kneeAngle)}，躯干角 ${angleText(trunkAngle)}"

    private fun angleText(angle: Float?): String =
        angle?.takeIf { value -> value.isFinite() }?.let { value -> "${value.toInt()} 度" } ?: "暂无"
}
