package com.example.zhizijing.pose.classifier

import com.example.zhizijing.domain.model.DeviceRole
import com.example.zhizijing.domain.rule.PoseAnalysisConfig
import com.example.zhizijing.pose.model.LandmarkPoint
import com.example.zhizijing.pose.model.PoseFrame
import kotlin.math.abs

data class FrontViewPoseQualityResult(
    val canClassify: Boolean,
    val reason: String,
    val frontFrameCount: Int,
    val reliableFrameCount: Int,
)

object FrontViewPoseQualityGate {
    fun evaluate(
        frames: List<PoseFrame>,
        config: PoseAnalysisConfig,
        minReliableFrames: Int = DEFAULT_MIN_RELIABLE_FRAMES,
    ): FrontViewPoseQualityResult {
        val frontFrames = frames.filter { frame -> frame.cameraRole == DeviceRole.FRONT_CAMERA }
        if (frontFrames.isEmpty()) {
            return FrontViewPoseQualityResult(
                canClassify = false,
                reason = "等待正面机位关键点",
                frontFrameCount = 0,
                reliableFrameCount = 0,
            )
        }
        val reliableFrameCount = frontFrames.count { frame -> isReliableFrontFrame(frame, config) }
        val requiredReliableFrames = minOf(minReliableFrames, frontFrames.size).coerceAtLeast(1)
        val hasEnoughReliableFrames = reliableFrameCount >= requiredReliableFrames
        val reliableRatioOk = reliableFrameCount * 2 >= frontFrames.size
        return FrontViewPoseQualityResult(
            canClassify = hasEnoughReliableFrames && reliableRatioOk,
            reason = when {
                hasEnoughReliableFrames && reliableRatioOk -> "正面关键点稳定"
                reliableFrameCount == 0 -> "正面人体关键点不足，请让头肩、髋、膝、踝尽量入镜"
                !reliableRatioOk -> "正面关键点波动较大，请保持站位稳定"
                else -> "正在等待更多稳定正面关键点"
            },
            frontFrameCount = frontFrames.size,
            reliableFrameCount = reliableFrameCount,
        )
    }

    fun isReliableFrontFrame(frame: PoseFrame, config: PoseAnalysisConfig): Boolean {
        if (frame.cameraRole != DeviceRole.FRONT_CAMERA) return false
        if (frame.overallConfidence < config.minPoseConfidence) return false
        val confidentCoreCount = CORE_LANDMARK_NAMES.count { name ->
            frame.landmarks[name]?.isConfident(config) == true
        }
        if (confidentCoreCount < MIN_CONFIDENT_CORE_LANDMARKS) return false
        val shoulders = frame.horizontalSpread("LEFT_SHOULDER", "RIGHT_SHOULDER")
        val hips = frame.horizontalSpread("LEFT_HIP", "RIGHT_HIP")
        val ankles = frame.horizontalSpread("LEFT_ANKLE", "RIGHT_ANKLE")
        val hasVisibleWidth = listOfNotNull(shoulders, hips, ankles).any { spread -> spread >= MIN_BODY_SPREAD }
        if (!hasVisibleWidth) return false
        val shoulderSkew = frame.verticalSkew("LEFT_SHOULDER", "RIGHT_SHOULDER")
        val hipSkew = frame.verticalSkew("LEFT_HIP", "RIGHT_HIP")
        return listOfNotNull(shoulderSkew, hipSkew).all { skew -> skew <= MAX_PAIR_VERTICAL_SKEW }
    }

    private fun LandmarkPoint.isConfident(config: PoseAnalysisConfig): Boolean =
        confidence >= config.minPoseConfidence

    private fun PoseFrame.horizontalSpread(leftName: String, rightName: String): Float? {
        val left = landmarks[leftName] ?: return null
        val right = landmarks[rightName] ?: return null
        return abs(left.x - right.x)
    }

    private fun PoseFrame.verticalSkew(leftName: String, rightName: String): Float? {
        val left = landmarks[leftName] ?: return null
        val right = landmarks[rightName] ?: return null
        return abs(left.y - right.y)
    }

    private const val DEFAULT_MIN_RELIABLE_FRAMES = 3
    private const val MIN_CONFIDENT_CORE_LANDMARKS = 6
    private const val MIN_BODY_SPREAD = 0.025f
    private const val MAX_PAIR_VERTICAL_SKEW = 0.18f
    private val CORE_LANDMARK_NAMES = listOf(
        "LEFT_SHOULDER",
        "RIGHT_SHOULDER",
        "LEFT_WRIST",
        "RIGHT_WRIST",
        "LEFT_HIP",
        "RIGHT_HIP",
        "LEFT_KNEE",
        "RIGHT_KNEE",
        "LEFT_ANKLE",
        "RIGHT_ANKLE",
    )
}
