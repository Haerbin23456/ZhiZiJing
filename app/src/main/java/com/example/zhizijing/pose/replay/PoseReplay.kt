package com.example.zhizijing.pose.replay

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import com.example.zhizijing.data.entity.PoseFrameEntity
import com.example.zhizijing.domain.model.ActionProgressTracker
import com.example.zhizijing.domain.model.ActionType
import com.example.zhizijing.domain.model.DeviceRole
import com.example.zhizijing.domain.model.ProblemType
import com.example.zhizijing.domain.rule.PoseAnalysisConfig
import com.example.zhizijing.domain.rule.SimpleJumpingJackAnalyzer
import com.example.zhizijing.domain.rule.SimpleSquatAnalyzer
import com.example.zhizijing.domain.rule.SquatScorePolicy
import com.example.zhizijing.pose.model.LandmarkPoint
import com.example.zhizijing.pose.model.PoseFrame
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import java.io.File

data class PoseReplaySample(
    val schemaVersion: Int = 1,
    val actionType: ActionType = ActionType.UNKNOWN,
    val expectedCount: Int? = null,
    val capturedAtMs: Long = System.currentTimeMillis(),
    val frameCount: Int = 0,
    val frames: List<PoseFrame> = emptyList(),
)

data class PoseReplayResult(
    val actionType: ActionType,
    val totalCount: Int,
    val score: Float?,
    val problemType: ProblemType,
    val suggestion: String?,
    val frameCount: Int,
)

data class PoseReplayExportResult(
    val uri: Uri,
    val displayName: String,
    val displayPath: String,
)

object PoseReplayJson {
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val landmarkMapType = object : TypeToken<Map<String, LandmarkPoint>>() {}.type

    fun export(
        actionType: ActionType,
        frames: List<PoseFrame>,
        outputDir: File,
        expectedCount: Int? = null,
        capturedAtMs: Long = System.currentTimeMillis(),
    ): File {
        outputDir.mkdirs()
        val output = File(outputDir, fileName(actionType, capturedAtMs))
        output.writeText(toJson(actionType, frames, expectedCount, capturedAtMs), Charsets.UTF_8)
        return output
    }

    fun exportEntities(
        actionType: ActionType,
        poseFrames: List<PoseFrameEntity>,
        outputDir: File,
        expectedCount: Int? = null,
        capturedAtMs: Long = System.currentTimeMillis(),
    ): File =
        export(
            actionType = actionType,
            frames = poseFrames.mapNotNull { entity -> entity.toPoseFrame() },
            outputDir = outputDir,
            expectedCount = expectedCount,
            capturedAtMs = capturedAtMs,
        )

    fun exportEntitiesToDownloads(
        context: Context,
        actionType: ActionType,
        poseFrames: List<PoseFrameEntity>,
        expectedCount: Int? = null,
        capturedAtMs: Long = System.currentTimeMillis(),
    ): PoseReplayExportResult {
        val displayName = fileName(actionType, capturedAtMs)
        val displayPath = "${Environment.DIRECTORY_DOWNLOADS}/ZhiZiJing/$displayName"
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, displayName)
            put(MediaStore.Downloads.MIME_TYPE, "application/json")
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/ZhiZiJing")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("无法创建下载目录文件。")
        runCatching {
            resolver.openOutputStream(uri)?.use { output ->
                output.write(
                    toJson(
                        actionType = actionType,
                        frames = poseFrames.mapNotNull { entity -> entity.toPoseFrame() },
                        expectedCount = expectedCount,
                        capturedAtMs = capturedAtMs,
                    ).toByteArray(Charsets.UTF_8)
                )
            } ?: error("无法写入关键点样本。")
            resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) },
                null,
                null,
            )
        }.onFailure { error ->
            resolver.delete(uri, null, null)
            throw error
        }
        return PoseReplayExportResult(uri, displayName, displayPath)
    }

    fun toJson(
        actionType: ActionType,
        frames: List<PoseFrame>,
        expectedCount: Int? = null,
        capturedAtMs: Long = System.currentTimeMillis(),
    ): String =
        gson.toJson(
            PoseReplaySample(
                actionType = actionType,
                expectedCount = expectedCount,
                capturedAtMs = capturedAtMs,
                frameCount = frames.size,
                frames = frames.map { frame ->
                    frame.copy(rgbImageBase64 = null)
                },
            )
        )

    fun read(input: File): PoseReplaySample =
        decode(input.readText(Charsets.UTF_8))

    fun decode(json: String): PoseReplaySample =
        gson.fromJson(json, PoseReplaySample::class.java)

    private fun fileName(actionType: ActionType, capturedAtMs: Long): String =
        "${capturedAtMs}_${actionType.name.lowercase()}_pose_replay.json"

    private fun PoseFrameEntity.toPoseFrame(): PoseFrame? {
        val landmarks = runCatching {
            gson.fromJson<Map<String, LandmarkPoint>>(landmarksJson, landmarkMapType)
        }.getOrNull() ?: return null
        if (landmarks.isEmpty()) return null
        val role = runCatching { DeviceRole.valueOf(cameraRole) }.getOrDefault(DeviceRole.UNKNOWN)
        return PoseFrame(
            sessionId = sessionId,
            nodeId = nodeId,
            timestampMs = timestampMs,
            cameraRole = role,
            landmarks = landmarks,
            overallConfidence = confidence ?: landmarks.values.map { it.confidence }.average().toFloat(),
            imageWidth = 0,
            imageHeight = 0,
            rgbImagePath = frameImagePath,
        )
    }
}

object PoseReplayRunner {
    fun replay(
        sample: PoseReplaySample,
        config: PoseAnalysisConfig = PoseAnalysisConfig(),
    ): PoseReplayResult =
        replay(
            actionType = sample.actionType,
            frames = sample.frames,
            config = config,
        )

    fun replay(
        actionType: ActionType,
        frames: List<PoseFrame>,
        config: PoseAnalysisConfig = PoseAnalysisConfig(),
    ): PoseReplayResult {
        var totalCount = 0
        var score: Float? = null
        var problemType = ProblemType.NONE
        var suggestion: String? = null

        when (actionType) {
            ActionType.SQUAT -> {
                val analyzer = SimpleSquatAnalyzer(config)
                val progressTracker = ActionProgressTracker()
                frames.sortedBy { frame -> frame.timestampMs }.forEach { frame ->
                    val result = analyzer.analyze(frame)
                    val progress = progressTracker.record(
                        actionType = ActionType.SQUAT,
                        totalCount = result.totalCount,
                        holdDurationMs = 0L,
                        score = SquatScorePolicy.reportableScore(result.problemType, result.score),
                        problemType = result.problemType,
                        suggestion = result.suggestion,
                    )
                    totalCount = progress.totalCount
                    score = progress.score
                    problemType = progress.problemType
                    suggestion = progress.suggestion
                }
            }
            ActionType.JUMPING_JACK -> {
                val analyzer = SimpleJumpingJackAnalyzer(config)
                frames.sortedBy { frame -> frame.timestampMs }.forEach { frame ->
                    val result = analyzer.analyze(frame)
                    totalCount = result.totalCount
                    problemType = if (result.lostFrameCount > 0) {
                        ProblemType.LOW_CONFIDENCE
                    } else {
                        ProblemType.NONE
                    }
                    suggestion = if (result.lostFrameCount > 0) {
                        "请保持全身入镜，避免丢失关键点。"
                    } else {
                        "保持稳定节奏。"
                    }
                }
            }
            else -> Unit
        }

        return PoseReplayResult(
            actionType = actionType,
            totalCount = totalCount,
            score = score,
            problemType = problemType,
            suggestion = suggestion,
            frameCount = frames.size,
        )
    }
}
