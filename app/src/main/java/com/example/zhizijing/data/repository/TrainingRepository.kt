package com.example.zhizijing.data.repository

import android.content.Context
import com.example.zhizijing.data.entity.ActionResultEntity
import com.example.zhizijing.data.entity.DeviceNodeEntity
import com.example.zhizijing.data.entity.ExportRecordEntity
import com.example.zhizijing.data.entity.PoseFrameEntity
import com.example.zhizijing.data.local.AppDatabaseProvider
import com.example.zhizijing.domain.model.ActionType
import com.example.zhizijing.domain.model.DeviceRole
import com.example.zhizijing.domain.model.TrainingSummary
import com.example.zhizijing.pose.model.PoseFrame
import com.example.zhizijing.report.TrainingArtifactCleaner
import com.example.zhizijing.report.TrainingFileLayout
import com.example.zhizijing.report.frame.KeyFrameStore
import java.io.File
import java.util.Base64

object TrainingRepository {
    fun saveFakeTraining(
        context: Context,
        summary: TrainingSummary,
        deviceSnapshots: List<TrainingDeviceSnapshot> = emptyList(),
    ): Long {
        val poseFramesFactory = { sessionId: Long ->
            TrainingRecordMapper.toPoseFrameSamples(sessionId, summary)
        }
        return saveTraining(context, summary, deviceSnapshots, poseFramesFactory = poseFramesFactory)
    }

    fun saveRecognizedTraining(
        context: Context,
        summary: TrainingSummary,
        poseFrames: List<PoseFrame>,
        deviceSnapshots: List<TrainingDeviceSnapshot> = emptyList(),
    ): Long =
        saveTraining(context, summary, deviceSnapshots, overwritePoseEvaluation = true) { sessionId ->
            poseFrames.mapIndexed { index, frame ->
                val persistedRgbPath = persistPoseFrameRgbImage(context.filesDir, sessionId, index, frame)
                TrainingRecordMapper.toPoseFrameEntity(
                    sessionId = sessionId,
                    frame = frame.copy(rgbImagePath = persistedRgbPath ?: frame.rgbImagePath),
                    frameType = "REAL_POSE",
                )
            }
        }

    // 训练结果统一落库流程
    private fun saveTraining(
        context: Context,
        summary: TrainingSummary,
        deviceSnapshots: List<TrainingDeviceSnapshot>,
        overwritePoseEvaluation: Boolean = false,
        poseFramesFactory: (Long) -> List<PoseFrameEntity>,
    ): Long {
        val database = AppDatabaseProvider.get(context)
        val userId = AuthRepository.currentUser(context)?.userId ?: 0L
        val deviceCount = 1 + deviceSnapshots.size
        val session = TrainingRecordMapper.toSessionEntity(userId, summary, deviceCount)
        val sessionId = database.trainingSessionDao().insert(session)
        session.sessionId = sessionId
        deviceSnapshots
            .map { snapshot -> TrainingRecordMapper.toDeviceNodeEntity(sessionId, snapshot) }
            .forEach { node -> database.deviceNodeDao().insert(node) }
        val poseFrames = poseFramesFactory(sessionId)
        val actionResults = TrainingRecordMapper.toActionResults(sessionId, summary)
        actionResults.forEach { result ->
            val keyFrame = selectActionKeyFrame(result, summary.totalCount, poseFrames)
            if (keyFrame != null) {
                if (overwritePoseEvaluation) {
                    TrainingPoseMetricsExtractor.applyMultiViewTo(
                        result = result,
                        fallbackFrame = keyFrame,
                        frontFrame = selectActionKeyFrame(
                            result,
                            summary.totalCount,
                            poseFrames.filter { frame -> frame.cameraRole == DeviceRole.FRONT_CAMERA.name },
                        ),
                        sideFrame = selectActionKeyFrame(
                            result,
                            summary.totalCount,
                            poseFrames.filter { frame -> frame.cameraRole == DeviceRole.SIDE_CAMERA.name },
                        ),
                    )
                } else {
                    TrainingPoseMetricsExtractor.applyTo(result = result, frame = keyFrame)
                }
                val keyFrameFile = KeyFrameStore.savePoseKeyFrame(
                    rootDir = context.filesDir,
                    sessionId = sessionId,
                    actionIndex = result.actionIndex,
                    frame = keyFrame,
                )
                result.keyFramePath = keyFrameFile.absolutePath
            }
            database.actionResultDao().insert(result)
        }
        if (overwritePoseEvaluation) {
            TrainingRecordMapper.applyRecognizedActionOverview(session, actionResults)
            database.trainingSessionDao().update(session)
        }
        saveRoleKeyFramePreviews(context, sessionId, summary.actionType, poseFrames)
        poseFrames.forEach { frame -> database.poseFrameDao().insert(frame) }
        return sessionId
    }

    fun listForCurrentUser(
        context: Context,
        actionType: ActionType? = null,
    ): List<TrainingSummary> {
        val database = AppDatabaseProvider.get(context)
        val userId = AuthRepository.currentUser(context)?.userId ?: 0L
        val sessions = if (userId > 0L) {
            database.trainingSessionDao().findByUser(userId)
        } else {
            database.trainingSessionDao().findAll()
        }
        return sessions
            .map { TrainingRecordMapper.toSummary(it) }
            .filter { actionType == null || it.actionType == actionType }
    }

    fun latestForCurrentUser(context: Context): TrainingSummary? {
        val database = AppDatabaseProvider.get(context)
        val userId = AuthRepository.currentUser(context)?.userId ?: 0L
        val session = if (userId > 0L) {
            database.trainingSessionDao().findLatestByUser(userId)
        } else {
            database.trainingSessionDao().findLatest()
        } ?: return null
        return TrainingRecordMapper.toSummary(session, database.actionResultDao().findBySession(session.sessionId))
            .withRecognitionConfidence(database.poseFrameDao().findBySession(session.sessionId))
    }

    fun findSummary(context: Context, sessionId: Long): TrainingSummary? {
        if (sessionId <= 0L) return null
        val database = AppDatabaseProvider.get(context)
        val session = database.trainingSessionDao().findById(sessionId) ?: return null
        val results = database.actionResultDao().findBySession(sessionId)
        return TrainingRecordMapper.toSummary(session, results)
            .withRecognitionConfidence(database.poseFrameDao().findBySession(sessionId))
    }

    fun findActionResults(context: Context, sessionId: Long): List<ActionResultEntity> {
        if (sessionId <= 0L) return emptyList()
        return AppDatabaseProvider.get(context).actionResultDao().findBySession(sessionId)
    }

    fun findPoseFrames(context: Context, sessionId: Long): List<PoseFrameEntity> {
        if (sessionId <= 0L) return emptyList()
        return AppDatabaseProvider.get(context).poseFrameDao().findBySession(sessionId)
    }

    fun findDeviceNodes(context: Context, sessionId: Long): List<DeviceNodeEntity> {
        if (sessionId <= 0L) return emptyList()
        return AppDatabaseProvider.get(context).deviceNodeDao().findBySession(sessionId)
    }

    fun findExportRecords(context: Context, sessionId: Long): List<ExportRecordEntity> {
        if (sessionId <= 0L) return emptyList()
        return AppDatabaseProvider.get(context).exportRecordDao().findBySession(sessionId)
    }

    fun addExportRecord(context: Context, record: ExportRecordEntity): Long =
        AppDatabaseProvider.get(context).exportRecordDao().insert(record)

    fun updateReportPath(context: Context, sessionId: Long, reportPath: String) {
        if (sessionId <= 0L || reportPath.isBlank()) return
        val database = AppDatabaseProvider.get(context)
        val session = database.trainingSessionDao().findById(sessionId) ?: return
        session.reportPath = reportPath
        database.trainingSessionDao().update(session)
    }

    fun deleteSession(context: Context, sessionId: Long) {
        if (sessionId <= 0L) return
        val database = AppDatabaseProvider.get(context)
        val exportRecords = database.exportRecordDao().findBySession(sessionId)
        TrainingArtifactCleaner.cleanup(context.filesDir, sessionId, exportRecords)
        database.actionResultDao().deleteBySession(sessionId)
        database.poseFrameDao().deleteBySession(sessionId)
        database.deviceNodeDao().deleteBySession(sessionId)
        database.exportRecordDao().deleteBySession(sessionId)
        database.trainingSessionDao().deleteById(sessionId)
    }

    private fun saveRoleKeyFramePreviews(
        context: Context,
        sessionId: Long,
        actionType: ActionType,
        poseFrames: List<PoseFrameEntity>,
    ) {
        listOf(DeviceRole.FRONT_CAMERA, DeviceRole.SIDE_CAMERA).forEach { role ->
            val roleFrames = poseFrames.filter { frame -> frame.cameraRole == role.name }
            val keyFrame = TrainingKeyFrameSelector.select(
                rawActionType = actionType.name,
                actionIndex = 1,
                totalCount = 1,
                frames = roleFrames,
            ) ?: return@forEach
            KeyFrameStore.saveRolePoseKeyFrame(
                rootDir = context.filesDir,
                sessionId = sessionId,
                role = role,
                frame = keyFrame,
            )
        }
    }

    private fun selectActionKeyFrame(
        result: ActionResultEntity,
        totalCount: Int,
        frames: List<PoseFrameEntity>,
    ): PoseFrameEntity? =
        TrainingKeyFrameSelector.selectInTimeRange(
            rawActionType = result.actionType,
            startTimeMs = result.startTimeMs,
            endTimeMs = result.endTimeMs,
            frames = frames,
        ) ?: TrainingKeyFrameSelector.select(
            rawActionType = result.actionType,
            actionIndex = result.actionIndex,
            totalCount = totalCount,
            frames = frames,
        )

    private fun persistPoseFrameRgbImage(
        rootDir: File,
        sessionId: Long,
        index: Int,
        frame: PoseFrame,
    ): String? {
        val outputDir = TrainingFileLayout.framesDir(rootDir, sessionId).apply { mkdirs() }
        val output = File(outputDir, "rgb_${index + 1}_${frame.timestampMs}.jpg")
        val base64 = frame.rgbImageBase64
        if (!base64.isNullOrBlank()) {
            val decodedPath = runCatching {
                output.writeBytes(Base64.getDecoder().decode(base64))
                output.absolutePath
            }.getOrNull()
            if (decodedPath != null) return decodedPath
        }
        val source = frame.rgbImagePath
            ?.takeIf { it.isNotBlank() }
            ?.let { File(it) }
            ?.takeIf { it.exists() && it.isFile }
            ?: return null
        return runCatching {
            if (source.canonicalPath != output.canonicalPath) {
                source.copyTo(output, overwrite = true)
            }
            output.absolutePath
        }.getOrNull()
    }

    private fun TrainingSummary.withRecognitionConfidence(
        frames: List<PoseFrameEntity>,
    ): TrainingSummary =
        copy(averagePoseConfidence = TrainingRecognitionConfidenceCalculator.averageFrom(frames))
}
