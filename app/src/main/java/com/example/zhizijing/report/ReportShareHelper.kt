package com.example.zhizijing.report

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

object ReportShareHelper {
    // 构建系统分享 Intent
    fun shareReport(context: Context, file: File) {
        require(canShareReportFile(context.filesDir, file)) {
            "只能分享应用私有 files 目录内的 PDF/JSON 训练报告。"
        }
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType(file)
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "智姿镜训练报告")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(shareIntent, "分享训练报告"))
    }

    fun mimeType(file: File): String =
        when (file.extension.lowercase()) {
            "pdf" -> "application/pdf"
            "json" -> "application/json"
            else -> "application/octet-stream"
        }

    fun canShareReportFile(rootDir: File, file: File): Boolean =
        isInsideDirectory(rootDir, file) && file.isFile && hasShareableReportExtension(file)

    fun hasShareableReportExtension(file: File): Boolean =
        file.extension.lowercase() in setOf("pdf", "json")

    fun isInsideDirectory(rootDir: File, file: File): Boolean {
        val root = rootDir.canonicalFile
        val target = file.canonicalFile
        return target == root || generateSequence(target) { it.parentFile }.any { it == root }
    }
}
