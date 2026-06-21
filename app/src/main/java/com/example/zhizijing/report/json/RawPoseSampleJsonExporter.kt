package com.example.zhizijing.report.json

import com.example.zhizijing.data.entity.PoseFrameEntity
import com.example.zhizijing.domain.model.TrainingSummary
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File

class RawPoseSampleJsonExporter {
    private val gson = GsonBuilder().setPrettyPrinting().create()

    fun export(
        summary: TrainingSummary,
        poseFrames: List<PoseFrameEntity>,
        outputDir: File,
    ): File {
        outputDir.mkdirs()
        val output = File(outputDir, "training_${summary.sessionId}_raw_pose.json")
        val json = mapOf(
            "schemaVersion" to 1,
            "sessionId" to summary.sessionId,
            "actionType" to summary.actionType.name,
            "label" to summary.actionType.name,
            "totalCount" to summary.totalCount,
            "durationMs" to summary.durationMs,
            "frameCount" to poseFrames.size,
            "frames" to poseFrames.map { it.toJson() },
        )
        output.writeText(gson.toJson(json), Charsets.UTF_8)
        return output
    }

    private fun PoseFrameEntity.toJson(): Map<String, Any?> =
        mapOf(
            "frameId" to frameId,
            "sessionId" to sessionId,
            "nodeId" to nodeId,
            "timestampMs" to timestampMs,
            "cameraRole" to cameraRole,
            "confidence" to confidence,
            "frameType" to frameType,
            "frameImagePath" to frameImagePath,
            "landmarks" to parseLandmarksJson(landmarksJson),
        )

    private fun parseLandmarksJson(raw: String?): JsonObject =
        raw?.takeIf { it.isNotBlank() }
            ?.let { json ->
                runCatching {
                    JsonParser.parseString(json)
                        .takeIf { element -> element.isJsonObject }
                        ?.asJsonObject
                }.getOrNull()
            }
            ?: JsonObject()
}
