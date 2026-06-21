package com.example.zhizijing.domain.rule

data class PoseAnalysisConfig(
    val minPoseConfidence: Float = 0.38f,
    val squatDepthThreshold: Float = 0.04f,
    val backLeanAngleThreshold: Float = 22f,
    val actionRecognitionThreshold: Float = 0.62f,
    val jumpingJackOpenAnkleShoulderRatio: Float = 1.35f,
    val jumpingJackClosedAnkleShoulderRatio: Float = 1.12f,
    val jumpingJackWristUpMargin: Float = 0.025f,
    val jumpingJackWristDownMargin: Float = 0.06f,
    val minSquatRepDurationMs: Long = 800L,
    val maxSquatRepDurationMs: Long = 6_000L,
)
