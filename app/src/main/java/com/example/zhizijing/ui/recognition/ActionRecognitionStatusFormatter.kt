package com.example.zhizijing.ui.recognition

import com.example.zhizijing.domain.model.TrainingSummary
import com.example.zhizijing.domain.model.ActionType
import com.example.zhizijing.report.TrainingReportFormatter

object ActionRecognitionStatusFormatter {
    fun format(
        actionRecognitionThreshold: Float,
        latest: TrainingSummary?,
    ): String {
        val latestText = latest?.let {
            "最近识别：${it.actionType.displayName}，${TrainingReportFormatter.primaryMetricText(it.actionType, it.totalCount, it.durationMs)}，评分：${TrainingReportFormatter.averageScoreText(it)}"
        } ?: "最近识别：暂无训练记录"
        return """
            识别模式：自动识别${ActionType.trainingActionNamesText()}
            姿态来源：摄像头实时画面
            判断依据：连续动作中的关节角度、躯干倾角、手腕高度、脚踝开合、膝盖交替和小幅纵跳变化
            识别阈值：${"%.2f".format(actionRecognitionThreshold)}
            $latestText
            识别结果：稳定识别后进入对应计数与姿势评估；结束训练时会采用训练过程中最高置信度的动作进入复盘。
        """.trimIndent()
    }
}
