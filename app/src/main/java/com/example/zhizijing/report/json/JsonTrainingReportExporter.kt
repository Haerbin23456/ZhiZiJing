package com.example.zhizijing.report.json

import com.example.zhizijing.data.entity.ActionResultEntity
import com.example.zhizijing.data.entity.DeviceNodeEntity
import com.example.zhizijing.domain.model.TrainingSummary
import com.example.zhizijing.report.ReportExporter
import com.example.zhizijing.report.TrainingReportFormatter
import com.example.zhizijing.report.TrainingReportStatsCalculator
import com.google.gson.GsonBuilder
import java.io.File

class JsonTrainingReportExporter : ReportExporter {
    private val gson = GsonBuilder().serializeNulls().setPrettyPrinting().create()

    override fun export(
        summary: TrainingSummary,
        actionResults: List<ActionResultEntity>,
        deviceNodes: List<DeviceNodeEntity>,
        outputDir: File,
        videoFiles: List<File>,
    ): File {
        outputDir.mkdirs()
        val output = File(outputDir, "training_${summary.sessionId}.json")
        val stats = TrainingReportStatsCalculator.from(actionResults)
        val json = mapOf(
            "sessionId" to summary.sessionId,
            "userId" to summary.userId,
            "actionType" to summary.actionType.name,
            "actionDisplayName" to summary.actionType.displayName,
            "totalCount" to summary.totalCount,
            "averageScore" to summary.averageScore,
            "averageTempoPerMinute" to TrainingReportFormatter.averageTempoPerMinute(
                summary.actionType,
                summary.totalCount,
                summary.durationMs,
            ),
            "averagePoseConfidence" to summary.averagePoseConfidence,
            "durationMs" to summary.durationMs,
            "mainProblem" to summary.mainProblem.name,
            "mainProblemDisplayName" to summary.mainProblem.displayName,
            "suggestion" to summary.suggestion,
            "timestampMs" to summary.timestampMs,
            "deviceCount" to 1 + deviceNodes.size,
            "deviceNodes" to deviceNodes.map { it.toJson() },
            "actionResults" to actionResults.map { it.toJson() },
            "roleKeyFrames" to mapOf(
                "front" to stats.frontKeyFramePath,
                "side" to stats.sideKeyFramePath,
            ),
            "videoFiles" to videoFiles.map { it.toJson() },
        )
        output.writeText(gson.toJson(json), Charsets.UTF_8)
        return output
    }

    private fun ActionResultEntity.toJson(): Map<String, Any?> =
        mapOf(
            "resultId" to resultId,
            "sessionId" to sessionId,
            "actionIndex" to actionIndex,
            "actionType" to actionType,
            "startTimeMs" to startTimeMs,
            "endTimeMs" to endTimeMs,
            "score" to score,
            "kneeAngle" to kneeAngle,
            "trunkAngle" to trunkAngle,
            "depthLevel" to depthLevel,
            "postureLevel" to postureLevel,
            "problemType" to problemType,
            "suggestion" to suggestion,
            "keyFramePath" to keyFramePath,
        )

    private fun DeviceNodeEntity.toJson(): Map<String, Any?> =
        mapOf(
            "nodeId" to nodeId,
            "sessionId" to sessionId,
            "deviceName" to deviceName,
            "endpointId" to endpointId,
            "role" to role,
            "batteryLevel" to batteryLevel,
            "networkDelayMs" to networkDelayMs,
            "isOnline" to isOnline,
            "lastHeartbeatAt" to lastHeartbeatAt,
        )

    private fun File.toJson(): Map<String, Any?> =
        mapOf(
            "fileName" to name,
            "absolutePath" to absolutePath,
            "sizeBytes" to length(),
            "lastModified" to lastModified(),
        )
}
