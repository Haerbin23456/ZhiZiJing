package com.example.zhizijing.report.frame

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import com.example.zhizijing.data.entity.PoseFrameEntity
import com.example.zhizijing.domain.model.DeviceRole
import com.example.zhizijing.report.TrainingFileLayout
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

object KeyFrameStore {
    private const val WIDTH = 720
    private const val HEIGHT = 1280
    private val gson = Gson()

    // 关键帧渲染并写入文件
    fun savePoseKeyFrame(
        rootDir: File,
        sessionId: Long,
        actionIndex: Int,
        frame: PoseFrameEntity,
    ): File {
        val outputDir = TrainingFileLayout.framesDir(rootDir, sessionId)
        outputDir.mkdirs()
        val output = File(outputDir, "action_${actionIndex}_keyframe.png")
        output.outputStream().use { stream ->
            render(frame).compress(Bitmap.CompressFormat.PNG, 100, stream)
        }
        return output
    }

    fun saveRolePoseKeyFrame(
        rootDir: File,
        sessionId: Long,
        role: DeviceRole,
        frame: PoseFrameEntity,
    ): File {
        val outputDir = TrainingFileLayout.framesDir(rootDir, sessionId)
        outputDir.mkdirs()
        val output = File(outputDir, rolePreviewFileName(role))
        output.outputStream().use { stream ->
            render(frame).compress(Bitmap.CompressFormat.PNG, 100, stream)
        }
        return output
    }

    fun rolePreviewFileName(role: DeviceRole): String =
        "${role.name.lowercase()}_keyframe.png"

    fun chooseKeyFrameIndex(actionIndex: Int, frameCountPerAction: Int): Int =
        ((actionIndex - 1).coerceAtLeast(0) * frameCountPerAction + 1).coerceAtLeast(0)

    private fun render(frame: PoseFrameEntity): Bitmap {
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val landmarks = parseLandmarks(frame.landmarksJson)
        val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(247, 250, 249) }
        val panelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(0, 106, 106)
            strokeWidth = 9f
            strokeCap = Paint.Cap.ROUND
        }
        val jointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 183, 77) }
        val scrimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(52, 0, 0, 0) }
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(36, 53, 51)
            textSize = 32f
        }

        val rgbBitmap = frame.frameImagePath
            ?.takeIf { path -> path.isNotBlank() && File(path).exists() }
            ?.let { path -> BitmapFactory.decodeFile(path) }
        if (rgbBitmap != null) {
            canvas.drawBitmap(
                rgbBitmap,
                null,
                RectF(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat()),
                null,
            )
            canvas.drawRect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), scrimPaint)
        } else {
            canvas.drawRect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), backgroundPaint)
            canvas.drawRoundRect(RectF(40f, 80f, WIDTH - 40f, HEIGHT - 80f), 20f, 20f, panelPaint)
        }
        canvas.drawText("智姿镜关键帧", 72f, 136f, textPaint)
        canvas.drawText("confidence=${"%.2f".format(frame.confidence ?: 0f)}", 72f, HEIGHT - 112f, textPaint)

        skeletonPairs.forEach { (start, end) ->
            val first = landmarks[start]
            val second = landmarks[end]
            if (first != null && second != null) {
                canvas.drawLine(
                    first.x * WIDTH,
                    first.y * HEIGHT,
                    second.x * WIDTH,
                    second.y * HEIGHT,
                    linePaint,
                )
            }
        }
        landmarks.values.forEach { point ->
            canvas.drawCircle(point.x * WIDTH, point.y * HEIGHT, 13f, jointPaint)
        }
        return bitmap
    }

    private fun parseLandmarks(raw: String?): Map<String, KeyFramePoint> {
        if (raw.isNullOrBlank()) return emptyMap()
        val type = object : TypeToken<Map<String, KeyFramePoint>>() {}.type
        return runCatching { gson.fromJson<Map<String, KeyFramePoint>>(raw, type) }
            .getOrDefault(emptyMap())
    }

    private data class KeyFramePoint(
        val x: Float,
        val y: Float,
        val z: Float = 0f,
        val confidence: Float = 1f,
    )

    private val skeletonPairs = listOf(
        "LEFT_SHOULDER" to "RIGHT_SHOULDER",
        "LEFT_SHOULDER" to "LEFT_HIP",
        "RIGHT_SHOULDER" to "RIGHT_HIP",
        "LEFT_HIP" to "RIGHT_HIP",
        "LEFT_SHOULDER" to "LEFT_WRIST",
        "RIGHT_SHOULDER" to "RIGHT_WRIST",
        "LEFT_HIP" to "LEFT_KNEE",
        "LEFT_KNEE" to "LEFT_ANKLE",
        "RIGHT_HIP" to "RIGHT_KNEE",
        "RIGHT_KNEE" to "RIGHT_ANKLE",
    )
}
