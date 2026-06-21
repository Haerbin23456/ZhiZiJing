package com.example.zhizijing.diagnostics

import com.example.zhizijing.pose.model.PoseFrame
import java.util.ArrayDeque
import java.util.Locale

data class CameraNodeDiagnosticsSnapshot(
    val totalImageFrames: Int,
    val poseFrameCount: Int,
    val emptyPoseFrameCount: Int,
    val errorCount: Int,
    val savedPoseFrameCount: Int,
    val recentFps: Float,
    val poseSuccessRate: Float,
    val latestConfidence: Float?,
    val latestLatencyMs: Long?,
    val recentEvents: List<String>,
) {
    fun formatText(): String {
        val confidenceText = latestConfidence?.let { "%.2f".format(Locale.US, it) } ?: "暂无"
        val latencyText = latestLatencyMs?.let { "${it}ms" } ?: "暂无"
        return listOf(
            "诊断信息",
            "已分析画面：$totalImageFrames 帧",
            "人体识别成功/未检出/错误：$poseFrameCount / $emptyPoseFrameCount / $errorCount",
            "人体识别成功率：${"%.0f".format(Locale.US, poseSuccessRate * 100f)}%",
            "近 5 秒帧率：${"%.1f".format(Locale.US, recentFps)} 帧/秒",
            "最新置信度：$confidenceText",
            "最新处理延迟：$latencyText",
            "已采样保存：$savedPoseFrameCount 帧",
        ).joinToString(separator = "\n")
    }
}

class CameraNodeDiagnostics(
    private val fpsWindowMs: Long = 5_000L,
    private val maxEvents: Int = 6,
) {
    private val frameTimestamps = ArrayDeque<Long>()
    private val recentEvents = ArrayDeque<String>()
    private var totalImageFrames = 0
    private var poseFrameCount = 0
    private var emptyPoseFrameCount = 0
    private var errorCount = 0
    private var savedPoseFrameCount = 0
    private var latestConfidence: Float? = null
    private var latestLatencyMs: Long? = null

    @Synchronized
    fun onImageFrame(nowMs: Long) {
        totalImageFrames += 1
        frameTimestamps.addLast(nowMs)
        trimFrameWindow(nowMs)
    }

    @Synchronized
    fun onPoseResult(frame: PoseFrame?, nowMs: Long) {
        if (frame == null) {
            emptyPoseFrameCount += 1
            latestConfidence = null
            latestLatencyMs = null
        } else {
            poseFrameCount += 1
            latestConfidence = frame.overallConfidence
            latestLatencyMs = (nowMs - frame.timestampMs).coerceAtLeast(0L)
        }
    }

    @Synchronized
    fun onError(message: String?, nowMs: Long) {
        errorCount += 1
        recordEvent("识别错误：${message.orEmpty().ifBlank { "未知错误" }}", nowMs)
    }

    @Synchronized
    fun onSessionFrameSaved(savedCount: Int, nowMs: Long) {
        savedPoseFrameCount = savedCount
        recordEvent("采样保存关键点：$savedCount 帧", nowMs)
    }

    @Synchronized
    fun recordEvent(message: String, nowMs: Long) {
        val seconds = (nowMs / 1000L) % 100_000L
        recentEvents.addFirst("[$seconds] $message")
        while (recentEvents.size > maxEvents) {
            recentEvents.removeLast()
        }
    }

    @Synchronized
    fun snapshot(nowMs: Long): CameraNodeDiagnosticsSnapshot {
        trimFrameWindow(nowMs)
        val observedMs = if (frameTimestamps.size >= 2) {
            (frameTimestamps.last - frameTimestamps.first).coerceAtLeast(1L)
        } else {
            fpsWindowMs
        }
        val fps = if (frameTimestamps.isEmpty()) {
            0f
        } else {
            frameTimestamps.size * 1000f / observedMs
        }
        val poseAttempts = poseFrameCount + emptyPoseFrameCount + errorCount
        val successRate = if (poseAttempts == 0) 0f else poseFrameCount.toFloat() / poseAttempts
        return CameraNodeDiagnosticsSnapshot(
            totalImageFrames = totalImageFrames,
            poseFrameCount = poseFrameCount,
            emptyPoseFrameCount = emptyPoseFrameCount,
            errorCount = errorCount,
            savedPoseFrameCount = savedPoseFrameCount,
            recentFps = fps,
            poseSuccessRate = successRate,
            latestConfidence = latestConfidence,
            latestLatencyMs = latestLatencyMs,
            recentEvents = recentEvents.toList(),
        )
    }

    private fun trimFrameWindow(nowMs: Long) {
        while (frameTimestamps.isNotEmpty() && nowMs - frameTimestamps.first > fpsWindowMs) {
            frameTimestamps.removeFirst()
        }
    }
}
