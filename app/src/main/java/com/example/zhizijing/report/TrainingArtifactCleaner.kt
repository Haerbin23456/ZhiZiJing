package com.example.zhizijing.report

import com.example.zhizijing.data.entity.ExportRecordEntity
import java.io.File

object TrainingArtifactCleaner {
    fun cleanup(
        rootDir: File,
        sessionId: Long,
        exportRecords: List<ExportRecordEntity>,
    ): Int {
        if (sessionId <= 0L) return 0
        val root = rootDir.canonicalFile
        val deletedPaths = linkedSetOf<String>()
        var deletedCount = 0

        exportRecords.forEach { record ->
            val path = record.filePath?.trim().orEmpty()
            if (path.isNotEmpty()) {
                val file = File(path)
                if (deleteSessionArtifact(root, sessionId, file, deletedPaths)) {
                    deletedCount += 1
                    pruneEmptyParents(root, file.parentFile)
                }
            }
        }

        if (deleteInsideRoot(root, TrainingFileLayout.sessionDir(rootDir, sessionId), deletedPaths)) {
            deletedCount += 1
        }

        return deletedCount
    }

    private fun deleteSessionArtifact(
        root: File,
        sessionId: Long,
        target: File,
        deletedPaths: MutableSet<String>,
    ): Boolean {
        val canonicalTarget = target.canonicalFile
        if (!isCurrentSessionArtifact(root, sessionId, canonicalTarget)) return false
        return deleteInsideRoot(root, canonicalTarget, deletedPaths)
    }

    private fun deleteInsideRoot(
        root: File,
        target: File,
        deletedPaths: MutableSet<String>,
    ): Boolean {
        val canonicalTarget = target.canonicalFile
        if (canonicalTarget == root || !isInside(root, canonicalTarget) || !canonicalTarget.exists()) {
            return false
        }
        if (!deletedPaths.add(canonicalTarget.absolutePath)) {
            return false
        }
        return if (canonicalTarget.isDirectory) {
            canonicalTarget.deleteRecursively()
        } else {
            canonicalTarget.delete()
        }
    }

    private fun pruneEmptyParents(root: File, start: File?) {
        var current = start?.canonicalFile
        while (current != null && current != root && isInside(root, current)) {
            val children = current.list()
            if (children == null || children.isNotEmpty()) break
            if (!current.delete()) break
            current = current.parentFile?.canonicalFile
        }
    }

    private fun isCurrentSessionArtifact(
        root: File,
        sessionId: Long,
        target: File,
    ): Boolean {
        if (!isInside(root, target)) return false
        val sessionDir = TrainingFileLayout.sessionDir(root, sessionId).canonicalFile
        if (target == sessionDir || isInside(sessionDir, target)) return true

        val sessionSegment = sessionId.toString()
        val hasSessionDirectory = generateSequence(target.parentFile) { it.parentFile }
            .takeWhile { isInside(root, it) }
            .any { parent -> parent.name == sessionSegment }
        val allowedExportExtension = target.extension.lowercase() in setOf("json", "pdf")
        return hasSessionDirectory &&
            target.nameWithoutExtension.startsWith("training_$sessionId") &&
            allowedExportExtension
    }

    private fun isInside(root: File, target: File): Boolean =
        generateSequence(target) { it.parentFile }.any { it == root }
}
