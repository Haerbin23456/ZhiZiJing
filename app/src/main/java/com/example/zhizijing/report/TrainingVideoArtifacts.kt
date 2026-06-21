package com.example.zhizijing.report

import java.io.File

object TrainingVideoArtifacts {
    fun listForSession(rootDir: File, sessionId: Long): List<File> {
        if (sessionId <= 0L) return emptyList()
        val videoDir = TrainingFileLayout.videosDir(rootDir, sessionId)
        return videoDir
            .listFiles { file ->
                file.isFile && file.extension.equals("mp4", ignoreCase = true)
            }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()
    }

    fun formatSummary(videoFiles: List<File>): String {
        if (videoFiles.isEmpty()) return "视频素材：暂无。"
        return videoFiles.joinToString(separator = "\n", prefix = "视频素材：${videoFiles.size} 个\n") { file ->
            "${file.name} / ${formatFileSize(file.length())} / ${file.absolutePath}"
        }
    }

    fun formatFileSize(sizeBytes: Long): String =
        when {
            sizeBytes >= 1024L * 1024L -> "%.1f MB".format(sizeBytes / 1024f / 1024f)
            sizeBytes >= 1024L -> "%.1f KB".format(sizeBytes / 1024f)
            else -> "$sizeBytes B"
        }
}
