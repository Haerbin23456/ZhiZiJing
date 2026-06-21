package com.example.zhizijing.pose.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import com.example.zhizijing.pose.model.LandmarkPoint
import com.example.zhizijing.pose.model.PoseFrame

class PoseOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {
    private var poseFrame: PoseFrame? = null

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(0, 106, 106)
        strokeWidth = 6f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val pointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 179, 0)
        style = Paint.Style.FILL
    }

    fun updatePose(frame: PoseFrame?) {
        poseFrame = frame
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val frame = poseFrame ?: return
        skeletonLines.forEach { (startName, endName) ->
            val start = frame.landmarks[startName]
            val end = frame.landmarks[endName]
            if (start.isDrawable() && end.isDrawable()) {
                val mappedStart = PoseOverlayCoordinateMapper.mapPoint(start!!, frame, width, height)
                val mappedEnd = PoseOverlayCoordinateMapper.mapPoint(end!!, frame, width, height)
                canvas.drawLine(
                    mappedStart.x,
                    mappedStart.y,
                    mappedEnd.x,
                    mappedEnd.y,
                    linePaint,
                )
            }
        }
        frame.landmarks.values
            .filter { it.isDrawable() }
            .forEach { point ->
                val mappedPoint = PoseOverlayCoordinateMapper.mapPoint(point, frame, width, height)
                canvas.drawCircle(mappedPoint.x, mappedPoint.y, 7f, pointPaint)
            }
    }

    private fun LandmarkPoint?.isDrawable(): Boolean =
        this != null &&
            confidence >= MIN_CONFIDENCE &&
            x in 0f..1f &&
            y in 0f..1f

    companion object {
        private const val MIN_CONFIDENCE = 0.35f

        private val skeletonLines = listOf(
            "LEFT_SHOULDER" to "RIGHT_SHOULDER",
            "LEFT_SHOULDER" to "LEFT_ELBOW",
            "LEFT_ELBOW" to "LEFT_WRIST",
            "RIGHT_SHOULDER" to "RIGHT_ELBOW",
            "RIGHT_ELBOW" to "RIGHT_WRIST",
            "LEFT_SHOULDER" to "LEFT_HIP",
            "RIGHT_SHOULDER" to "RIGHT_HIP",
            "LEFT_HIP" to "RIGHT_HIP",
            "LEFT_HIP" to "LEFT_KNEE",
            "LEFT_KNEE" to "LEFT_ANKLE",
            "RIGHT_HIP" to "RIGHT_KNEE",
            "RIGHT_KNEE" to "RIGHT_ANKLE",
            "LEFT_ANKLE" to "LEFT_FOOT_INDEX",
            "RIGHT_ANKLE" to "RIGHT_FOOT_INDEX",
        )
    }
}
