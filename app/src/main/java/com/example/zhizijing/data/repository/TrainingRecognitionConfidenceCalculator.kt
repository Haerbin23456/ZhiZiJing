package com.example.zhizijing.data.repository

import com.example.zhizijing.data.entity.PoseFrameEntity

object TrainingRecognitionConfidenceCalculator {
    fun averageFrom(frames: List<PoseFrameEntity>): Float? =
        frames
            .mapNotNull { frame ->
                frame.confidence?.takeIf { confidence ->
                    confidence.isFinite() && confidence in 0f..1f
                }
            }
            .takeIf { confidenceValues -> confidenceValues.isNotEmpty() }
            ?.average()
            ?.toFloat()
}
