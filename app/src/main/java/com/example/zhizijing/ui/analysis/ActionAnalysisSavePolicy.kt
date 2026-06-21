package com.example.zhizijing.ui.analysis

object ActionAnalysisSavePolicy {
    fun errorFor(
        remoteSummaryCount: Int,
        remotePoseFrameCount: Int,
    ): String? =
        null

    fun useRecognizedPoseFrames(remotePoseFrameCount: Int): Boolean =
        remotePoseFrameCount > 0
}
