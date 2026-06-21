package com.example.zhizijing.report

import java.io.File

object ReportOutputDirectory {
    private const val DEFAULT_BASE_DIR = "training/reports"
    private const val SESSION_PLACEHOLDER = "{sessionId}"

    fun resolve(
        rootDir: File,
        sessionId: Long,
        configuredDir: String,
    ): File {
        val normalized = safeRelativeBaseDir(configuredDir)
        val relativePath = if (SESSION_PLACEHOLDER in normalized) {
            normalized.replace(SESSION_PLACEHOLDER, sessionId.toString())
        } else {
            "$normalized/$sessionId"
        }
        return File(rootDir, relativePath)
    }

    private fun safeRelativeBaseDir(configuredDir: String): String {
        val value = configuredDir.trim()
        return if (isSafeRelativePath(value)) value else DEFAULT_BASE_DIR
    }

    private fun isSafeRelativePath(value: String): Boolean =
        value.isNotBlank() &&
            value.length <= 80 &&
            !value.startsWith("/") &&
            !value.startsWith("\\") &&
            ":" !in value &&
            "\\" !in value &&
            value.none { char -> char.code < 32 } &&
            value.split('/').none { segment -> segment.isBlank() || segment == "." || segment == ".." }
}
