package com.example.zhizijing.pose.overlay

import com.example.zhizijing.pose.model.LandmarkPoint
import com.example.zhizijing.pose.model.PoseFrame
import kotlin.math.max

data class OverlayPoint(
    val x: Float,
    val y: Float,
)

object PoseOverlayCoordinateMapper {
    fun mapPoint(
        point: LandmarkPoint,
        frame: PoseFrame,
        viewWidth: Int,
        viewHeight: Int,
    ): OverlayPoint {
        val sourceWidth = frame.imageWidth.coerceAtLeast(1)
        val sourceHeight = frame.imageHeight.coerceAtLeast(1)
        val safeViewWidth = viewWidth.coerceAtLeast(1)
        val safeViewHeight = viewHeight.coerceAtLeast(1)
        val scale = max(
            safeViewWidth.toFloat() / sourceWidth.toFloat(),
            safeViewHeight.toFloat() / sourceHeight.toFloat(),
        )
        val scaledWidth = sourceWidth * scale
        val scaledHeight = sourceHeight * scale
        val offsetX = (safeViewWidth - scaledWidth) / 2f
        val offsetY = (safeViewHeight - scaledHeight) / 2f
        return OverlayPoint(
            offsetX + point.x * sourceWidth * scale,
            offsetY + point.y * sourceHeight * scale,
        )
    }
}
