package com.example.zhizijing.report

import com.example.zhizijing.data.entity.ExportRecordEntity
import java.io.File

object ReportExportSelector {
    fun latestExistingExportFile(exports: List<ExportRecordEntity>): File? =
        exports.asSequence()
            .sortedWith(compareByDescending<ExportRecordEntity> { it.createdAt }.thenByDescending { it.exportId })
            .map { record -> File(record.filePath) }
            .firstOrNull { file -> file.exists() && file.isFile && ReportShareHelper.hasShareableReportExtension(file) }
}
