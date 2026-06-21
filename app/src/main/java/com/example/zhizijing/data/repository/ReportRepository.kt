package com.example.zhizijing.data.repository

import android.content.Context
import com.example.zhizijing.data.datastore.AppSettingsDataStore
import com.example.zhizijing.data.entity.ExportRecordEntity
import com.example.zhizijing.report.ReportExporter
import com.example.zhizijing.report.ReportOutputDirectory
import com.example.zhizijing.report.TrainingFileLayout
import com.example.zhizijing.report.TrainingVideoArtifacts
import com.example.zhizijing.report.json.JsonTrainingReportExporter
import com.example.zhizijing.report.json.RawPoseSampleJsonExporter
import com.example.zhizijing.report.pdf.PdfTrainingReportExporter
import java.io.File

enum class ExportType(val fileLabel: String) {
    JSON("JSON"),
    PDF("PDF"),
    RAW_POSE_JSON("关键点 JSON"),
}

fun ExportType.persistsAsSessionReportPath(): Boolean =
    this == ExportType.JSON || this == ExportType.PDF

data class ExportResult(
    val file: File,
    val recordId: Long,
)

object ReportRepository {
    // 报告导出按类型分流
    fun export(
        context: Context,
        sessionId: Long,
        type: ExportType,
    ): ExportResult {
        val summary = TrainingRepository.findSummary(context, sessionId)
            ?: throw IllegalArgumentException("找不到训练记录：$sessionId")
        val actions = TrainingRepository.findActionResults(context, sessionId)
        val deviceNodes = TrainingRepository.findDeviceNodes(context, sessionId)
        val rootDir = context.filesDir
        val videoFiles = TrainingVideoArtifacts.listForSession(rootDir, sessionId)
        val settings = AppSettingsDataStore.load(context)
        val file = when (type) {
            ExportType.JSON,
            ExportType.PDF -> {
                val outputDir = ReportOutputDirectory.resolve(
                    rootDir = rootDir,
                    sessionId = sessionId,
                    configuredDir = settings.defaultExportDir,
                )
                val exporter: ReportExporter = when (type) {
                    ExportType.JSON -> JsonTrainingReportExporter()
                    ExportType.PDF -> PdfTrainingReportExporter()
                    ExportType.RAW_POSE_JSON -> error("unreachable")
                }
                exporter.export(summary, actions, deviceNodes, outputDir, videoFiles)
            }
            ExportType.RAW_POSE_JSON -> {
                val poseFrames = TrainingRepository.findPoseFrames(context, sessionId)
                val outputDir = TrainingFileLayout.rawPoseDir(rootDir, sessionId)
                RawPoseSampleJsonExporter().export(summary, poseFrames, outputDir)
            }
        }
        val recordId = TrainingRepository.addExportRecord(
            context,
            ExportRecordEntity(
                sessionId,
                type.name,
                file.absolutePath,
                System.currentTimeMillis(),
                file.length(),
            )
        )
        if (type.persistsAsSessionReportPath()) {
            TrainingRepository.updateReportPath(context, sessionId, file.absolutePath)
        }
        return ExportResult(file, recordId)
    }
}
