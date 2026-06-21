package com.example.zhizijing.pose.model

import com.example.zhizijing.domain.model.DeviceRole

data class LandmarkPoint(
    val name: String,
    val x: Float,
    val y: Float,
    val z: Float? = null,
    val confidence: Float,
)

data class PoseFrame(
    val sessionId: Long,
    val nodeId: Long,
    val timestampMs: Long,
    val cameraRole: DeviceRole,
    val landmarks: Map<String, LandmarkPoint>,
    val overallConfidence: Float,
    val imageWidth: Int,
    val imageHeight: Int,
    val rgbImagePath: String? = null,
    val rgbImageBase64: String? = null,
)
