package com.example.zhizijing.report

import java.io.File

object TrainingFileLayout {
    private const val TRAINING_DIR = "training"
    private const val FRAMES_DIR = "frames"
    private const val REPORTS_DIR = "reports"
    private const val RAW_POSE_DIR = "raw_pose"
    private const val VIDEOS_DIR = "videos"
    private const val VIDEO_DRAFTS_DIR = "training/video_drafts"

    fun sessionDir(rootDir: File, sessionId: Long): File =
        File(rootDir, "$TRAINING_DIR/$sessionId")

    fun framesDir(rootDir: File, sessionId: Long): File =
        File(sessionDir(rootDir, sessionId), FRAMES_DIR)

    fun reportsDir(rootDir: File, sessionId: Long): File =
        File(sessionDir(rootDir, sessionId), REPORTS_DIR)

    fun rawPoseDir(rootDir: File, sessionId: Long): File =
        File(sessionDir(rootDir, sessionId), RAW_POSE_DIR)

    fun videosDir(rootDir: File, sessionId: Long): File =
        File(sessionDir(rootDir, sessionId), VIDEOS_DIR)

    fun videoDraftsDir(rootDir: File): File =
        File(rootDir, VIDEO_DRAFTS_DIR)
}
