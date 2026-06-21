package com.example.zhizijing.ui.analysis

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import android.widget.Toast
import com.example.zhizijing.data.repository.TrainingDeviceSnapshot
import com.example.zhizijing.data.repository.TrainingRecordMapper
import com.example.zhizijing.data.repository.TrainingRepository
import com.example.zhizijing.databinding.ActivityActionAnalysisBinding
import com.example.zhizijing.domain.model.ActionProgressSnapshot
import com.example.zhizijing.domain.model.ActionProgressTracker
import com.example.zhizijing.domain.model.ActionType
import com.example.zhizijing.domain.model.DeviceRole
import com.example.zhizijing.domain.model.ProblemType
import com.example.zhizijing.domain.model.SquatStage
import com.example.zhizijing.domain.model.TrainingSaveValidator
import com.example.zhizijing.domain.model.TrainingSummary
import com.example.zhizijing.domain.rule.SquatScorePolicy
import com.example.zhizijing.nearby.connection.NearbyConnectionListener
import com.example.zhizijing.nearby.connection.NearbyConnectionState
import com.example.zhizijing.nearby.connection.NearbyMessageDiagnostics
import com.example.zhizijing.nearby.connection.NearbyRoomSession
import com.example.zhizijing.nearby.message.NearbyMessage
import com.example.zhizijing.nearby.message.NearbyMessageType
import com.example.zhizijing.nearby.message.NearbyPoseFrameCodec
import com.example.zhizijing.ui.history.HistoryDetailActivity
import com.example.zhizijing.utils.AppExecutors
import java.util.Locale

class ActionAnalysisActivity : ComponentActivity() {
    private lateinit var binding: ActivityActionAnalysisBinding
    private var actionType = ActionType.UNKNOWN
    private var count = 0
    private var holdDurationMs = 0L
    private var score = 100
    private var stage = SquatStage.STANDING
    private var problem = ProblemType.NONE
    private var startedAtMs = 0L
    private var isAutoSimulating = false
    private var isRemoteTrainingPaused = false
    private var remoteStatus = "多设备连接：暂无节点分析回传。"
    private var remotePoseStatus = "节点关键点样本：暂无回传。"
    private var remoteSuggestion: String? = null
    private var bestActionConfidence: Float? = null
    private var remoteSummaryCount = 0
    private var remotePoseFrameCount = 0
    private var lastRemoteSummaryAtMs = 0L
    private var lastRemotePoseFrameAtMs = 0L
    private var lastRemoteLatencyMs: Long? = null
    private var lastRemotePoseLatencyMs: Long? = null
    private var lastRemoteEndpointId: String? = null
    private var lastRemotePoseEndpointId: String? = null
    private var lastHostStatusSentAtMs = 0L
    private var lastHostStatusSignature = ""
    private val actionProgressTracker = ActionProgressTracker()
    // 远端姿态帧与摘要缓存
    private val remotePoseFrameBuffer = RemotePoseFrameBuffer()
    private val remoteSummaryAggregator = RemoteAnalysisSummaryAggregator()
    private val nearbyListener = object : NearbyConnectionListener {
        override fun onNearbyStateChanged(state: NearbyConnectionState) {
            val aggregate = remoteSummaryAggregator.calculate(state.endpoints)
            applyRemoteAggregate(aggregate)
            remoteStatus = """
                多设备连接：${state.statusText}
                在线节点：${state.endpoints.count { it.isOnline }} / ${state.endpoints.size}
                ${aggregate.statusText}
            """.trimIndent()
            runOnUiThread { renderState() }
        }

        override fun onNearbyMessageReceived(endpointId: String, message: NearbyMessage) {
            when (message.type) {
                NearbyMessageType.ANALYSIS_SUMMARY -> handleAnalysisSummary(endpointId, message)
                NearbyMessageType.POSE_FRAME -> handlePoseFrameSnapshot(endpointId, message)
                else -> Unit
            }
        }
    }
    private val autoSimulationHandler = Handler(Looper.getMainLooper())
    private val autoSimulationRunnable = object : Runnable {
        override fun run() {
            if (!isAutoSimulating) return
            if (actionType == ActionType.JUMPING_JACK) {
                simulateJumpingJack()
            } else if (actionType.isHoldBased) {
                simulateHoldAction()
            } else if (actionType.isTrainingAction && actionType != ActionType.SQUAT) {
                simulateBasicCountAction()
            } else {
                simulateSquat()
            }
            if (isAutoTargetReached()) {
                stopAutoSimulation("自动模拟已完成，可结束训练查看结果。")
            } else {
                autoSimulationHandler.postDelayed(this, AUTO_SIMULATION_INTERVAL_MS)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityActionAnalysisBinding.inflate(layoutInflater)
        setContentView(binding.root)

        actionType = ActionType.fromNameOrUnknown(intent.getStringExtra(EXTRA_ACTION_TYPE))
        startedAtMs = System.currentTimeMillis()
        renderState()
        binding.autoSimulateButton.setOnClickListener { toggleAutoSimulation() }
        binding.simulateSquatButton.setOnClickListener { simulateSquat() }
        binding.simulateJumpingJackButton.setOnClickListener { simulateJumpingJack() }
        binding.pauseRemoteTrainingButton.setOnClickListener { toggleRemoteTrainingPause() }
        binding.finishTrainingButton.setOnClickListener { openResult() }
        binding.backButton.setOnClickListener {
            stopAutoSimulation()
            finish()
        }
    }

    override fun onStart() {
        super.onStart()
        NearbyRoomSession.manager(this).addListener(nearbyListener)
    }

    override fun onStop() {
        NearbyRoomSession.manager(this).removeListener(nearbyListener)
        super.onStop()
    }

    private fun simulateSquat() {
        actionType = ActionType.SQUAT
        count += 1
        holdDurationMs = 0L
        stage = when (count % 4) {
            1 -> SquatStage.DESCENDING
            2 -> SquatStage.SQUATTING
            3 -> SquatStage.RISING
            else -> SquatStage.STANDING
        }
        problem = when (count % 4) {
            1 -> ProblemType.NONE
            2 -> ProblemType.SQUAT_DEPTH_NOT_ENOUGH
            3 -> ProblemType.BACK_LEAN_TOO_MUCH
            else -> ProblemType.KNEE_INWARD
        }
        score = (100 - count * 3).coerceAtLeast(70)
        recordProgress(ActionType.SQUAT, count, holdDurationMs, score.toFloat(), problem, suggestionFor(problem))
        renderState()
    }

    private fun simulateJumpingJack() {
        actionType = ActionType.JUMPING_JACK
        count += 1
        holdDurationMs = 0L
        stage = SquatStage.STANDING
        problem = ProblemType.NONE
        score = 0
        recordProgress(ActionType.JUMPING_JACK, count, holdDurationMs, null, problem, "保持稳定节奏。")
        renderState()
    }

    private fun simulateBasicCountAction() {
        if (!actionType.isTrainingAction || actionType.isHoldBased) {
            actionType = ActionType.PUSH_UP
        }
        count += 1
        holdDurationMs = 0L
        stage = SquatStage.STANDING
        problem = ProblemType.NONE
        score = 0
        recordProgress(actionType, count, holdDurationMs, null, problem, suggestionFor(problem))
        renderState()
    }

    private fun simulateHoldAction() {
        if (!actionType.isTrainingAction || !actionType.isHoldBased) {
            actionType = ActionType.PLANK
        }
        holdDurationMs += AUTO_SIMULATION_INTERVAL_MS
        count = 0
        stage = SquatStage.STANDING
        problem = ProblemType.NONE
        score = 0
        val suggestion = "保持身体稳定，完成有效保持。"
        recordProgress(actionType, count, holdDurationMs, null, problem, suggestion)
        renderState()
    }

    private fun renderState() {
        val scoreText = if (actionType.supportsDetailedScore) {
            SquatScorePolicy.displayText(problem, score.toFloat())
        } else if (actionType.isHoldBased) {
            "按保持时长统计"
        } else {
            "仅计数"
        }
        val progressText = "当前次数：$count"
        val autoText = if (isAutoSimulating) "自动模拟中，目标 ${targetText()}" else "手动或自动模拟均可"
        val remotePoseFrameSavedCount = remotePoseFrameBuffer.size()
        binding.simulateSquatButton.text = if (actionType.isTrainingAction && actionType != ActionType.SQUAT) {
            "模拟一次${actionType.displayName}"
        } else {
            "模拟一次深蹲"
        }
        binding.simulateJumpingJackButton.text = "模拟一次开合跳"
        binding.pauseRemoteTrainingButton.text = if (isRemoteTrainingPaused) "继续节点训练" else "暂停节点训练"
        binding.analysisStatusText.text = """
            当前训练模式：${trainingModeText(actionType)}（单机模拟 / 节点回传）
            $progressText
            深蹲阶段：$stage
            当前评分：$scoreText
            主要问题：${problem.displayName}
            模拟状态：$autoText
            $remoteStatus
            $remotePoseStatus
            节点摘要数：$remoteSummaryCount
            最近节点：${lastRemoteEndpointId ?: "暂无"}
            最近摘要延迟：${lastRemoteLatencyMs?.let { "${it}ms" } ?: "暂无"}
            最近摘要时间：${if (lastRemoteSummaryAtMs > 0L) "${lastRemoteSummaryAtMs / 1000L}s" else "暂无"}
            关键点样本数：$remotePoseFrameCount
            最近样本节点：${lastRemotePoseEndpointId ?: "暂无"}
            最近样本延迟：${lastRemotePoseLatencyMs?.let { "${it}ms" } ?: "暂无"}
            最近样本时间：${if (lastRemotePoseFrameAtMs > 0L) "${lastRemotePoseFrameAtMs / 1000L}s" else "暂无"}
            远程关键点样本：已缓存 $remotePoseFrameSavedCount 帧
            远程训练控制：开始、暂停和结束均由主控端统一发起，节点手机无需手动操作。
            提示：本页可使用单机模拟，也可接收节点回传的自动识别结果；结束保存时优先使用远程关键点样本，缺少样本时也会按最高置信度动作先保存摘要结果。
        """.trimIndent()
        maybeSendHostAnalysisStatus(scoreText, progressText)
    }

    private fun openResult() {
        stopAutoSimulation()
        val saveActionType = actionType
        val saveProgress = saveableProgressFor(saveActionType)
        val validationError = TrainingSaveValidator.errorFor(
            actionType = saveActionType,
            totalCount = saveProgress.totalCount,
            holdDurationMs = saveProgress.holdDurationMs,
        )
        if (validationError != null) {
            Toast.makeText(this, validationError, Toast.LENGTH_SHORT).show()
            renderState()
            return
        }
        val remotePoseFrames = remotePoseFrameBuffer.snapshot()
        val savePolicyError = ActionAnalysisSavePolicy.errorFor(
            remoteSummaryCount = remoteSummaryCount,
            remotePoseFrameCount = remotePoseFrames.size,
        )
        if (savePolicyError != null) {
            Toast.makeText(this, savePolicyError, Toast.LENGTH_LONG).show()
            renderState()
            return
        }
        NearbyRoomSession.manager(this).sendEndTraining(saveActionType)
        val durationMs = System.currentTimeMillis() - startedAtMs
        val saveScore = if (saveProgress.problemType == ProblemType.RHYTHM_ABNORMAL && saveProgress.totalCount == 1) {
            95f
        } else {
            score.toFloat()
        }
        val summary = TrainingSummary(
            actionType = saveActionType,
            totalCount = saveProgress.totalCount,
            averageScore = if (saveActionType.supportsDetailedScore) {
                SquatScorePolicy.reportableScore(saveProgress.problemType, saveScore)
            } else {
                null
            },
            durationMs = durationMs,
            mainProblem = saveProgress.problemType,
            suggestion = saveProgress.suggestion,
        )
        val deviceSnapshots = nearbyDeviceSnapshots()
        binding.finishTrainingButton.isEnabled = false
        AppExecutors.io.execute {
            val result = runCatching {
                if (ActionAnalysisSavePolicy.useRecognizedPoseFrames(remotePoseFrames.size)) {
                    TrainingRepository.saveRecognizedTraining(this, summary, remotePoseFrames, deviceSnapshots)
                } else {
                    TrainingRepository.saveFakeTraining(this, summary, deviceSnapshots)
                }
            }
            runOnUiThread {
                result.onSuccess { sessionId ->
                    binding.finishTrainingButton.isEnabled = false
                    binding.finishTrainingButton.text = "已保存，正在打开详情..."
                    openSavedTrainingDetail(sessionId)
                }.onFailure {
                    binding.finishTrainingButton.isEnabled = true
                    Toast.makeText(this, "训练记录保存失败：${it.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun openSavedTrainingDetail(sessionId: Long) {
        runCatching {
            startActivity(
                Intent(this, HistoryDetailActivity::class.java)
                    .putExtra(HistoryDetailActivity.EXTRA_SESSION_ID, sessionId)
                    .putExtra(
                        HistoryDetailActivity.EXTRA_RETURN_TARGET,
                        HistoryDetailActivity.RETURN_TARGET_HOME,
                    )
            )
        }.onSuccess {
            finish()
        }.onFailure { error ->
            binding.finishTrainingButton.isEnabled = true
            binding.finishTrainingButton.text = "结束训练并查看结果"
            Toast.makeText(
                this,
                "训练已保存，但打开详情失败：${error.message ?: error.javaClass.simpleName}",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    private fun saveableProgressFor(actionType: ActionType): SaveProgress {
        val confidenceText = bestActionConfidence
            ?.let { confidence -> "${(confidence * 100f).toInt().coerceIn(0, 100)}%" }
            ?: "当前最高"
        val bestProgress = actionProgressTracker.bestFor(actionType)
        val defaultSuggestion = remoteSuggestion ?: suggestionFor(problem)
        if (!actionType.isTrainingAction) {
            return SaveProgress(count, holdDurationMs, problem, defaultSuggestion)
        }
        if (actionType.isHoldBased) {
            val progressHoldDurationMs = maxOf(bestProgress?.holdDurationMs ?: 0L, holdDurationMs)
            val safeHoldDurationMs = maxOf(progressHoldDurationMs, MIN_SAVE_HOLD_DURATION_MS)
            val fixedCount = 0
            val suggestion = if (progressHoldDurationMs < MIN_SAVE_HOLD_DURATION_MS) {
                "已根据节点回传的最高置信度动作${actionType.displayName}保存（置信度约 $confidenceText）；本次按有效保持记录进入复盘。"
            } else {
                (bestProgress?.suggestion ?: defaultSuggestion).withBestActionConfidenceText(actionType)
            }
            return SaveProgress(fixedCount, safeHoldDurationMs, bestProgress?.problemType ?: problem, suggestion)
        }
        val bestCount = maxOf(bestProgress?.totalCount ?: 0, count)
        if (bestCount > 0) {
            return SaveProgress(
                bestCount,
                bestProgress?.holdDurationMs ?: holdDurationMs,
                bestProgress?.problemType ?: problem,
                (bestProgress?.suggestion ?: defaultSuggestion).withBestActionConfidenceText(actionType),
            )
        }
        if (actionType == ActionType.LATERAL_RAISE) {
            return SaveProgress(
                totalCount = 0,
                holdDurationMs = 0L,
                problemType = ProblemType.RHYTHM_ABNORMAL,
                suggestion = "已识别到侧平举抬臂，但没有捕捉到放回身体两侧；本次不计次数，建议完整抬起并放下后结束。",
            )
        }
        if (actionType == ActionType.STANDING_FORWARD_BEND) {
            return SaveProgress(
                totalCount = 0,
                holdDurationMs = 0L,
                problemType = ProblemType.RHYTHM_ABNORMAL,
                suggestion = "已识别到站姿体前屈下探，但没有捕捉到站直回正；本次不计次数，建议完整俯身并站直后结束。",
            )
        }
        return SaveProgress(
            totalCount = 1,
            holdDurationMs = 0L,
            problemType = ProblemType.RHYTHM_ABNORMAL,
            suggestion = "已根据节点回传的最高置信度动作${actionType.displayName}保存（置信度约 $confidenceText）；本次按 1 次待复盘记录保存，建议下次完整完成动作后结束。",
        )
    }

    private fun recordProgress(
        actionType: ActionType,
        totalCount: Int,
        holdDurationMs: Long,
        score: Float?,
        problemType: ProblemType,
        suggestion: String?,
    ): ActionProgressSnapshot =
        actionProgressTracker.record(
            actionType = actionType,
            totalCount = totalCount,
            holdDurationMs = holdDurationMs,
            score = score,
            problemType = problemType,
            suggestion = suggestion,
        ).also { progress ->
            applyProgress(progress)
        }

    private fun applyProgress(progress: ActionProgressSnapshot) {
        count = progress.totalCount
        holdDurationMs = progress.holdDurationMs
        problem = progress.problemType
        progress.score?.let { score = it.toInt() }
        remoteSuggestion = progress.suggestion ?: remoteSuggestion
    }

    private fun suggestionFor(problemType: ProblemType): String =
        TrainingRecordMapper.suggestionFor(problemType)

    private fun String.withBestActionConfidenceText(actionType: ActionType): String {
        if (contains("最高置信度")) return this
        val confidenceText = bestActionConfidence
            ?.let { confidence -> "${(confidence * 100f).toInt().coerceIn(0, 100)}%" }
            ?: return this
        return "识别动作：${actionType.displayName}（节点回传最高动作置信度约 $confidenceText）。$this"
    }

    private fun toggleAutoSimulation() {
        if (isAutoSimulating) {
            stopAutoSimulation("自动模拟已暂停。")
        } else {
            startAutoSimulation()
        }
    }

    private fun startAutoSimulation() {
        isAutoSimulating = true
        binding.autoSimulateButton.text = "暂停自动模拟"
        binding.simulateSquatButton.isEnabled = false
        binding.simulateJumpingJackButton.isEnabled = false
        renderState()
        autoSimulationHandler.removeCallbacks(autoSimulationRunnable)
        autoSimulationHandler.postDelayed(autoSimulationRunnable, AUTO_SIMULATION_INTERVAL_MS)
    }

    private fun toggleRemoteTrainingPause() {
        isRemoteTrainingPaused = !isRemoteTrainingPaused
        if (isRemoteTrainingPaused) {
            NearbyRoomSession.manager(this).sendPauseAnalysis(actionType)
            Toast.makeText(this, "已通知节点暂停训练识别", Toast.LENGTH_SHORT).show()
        } else {
            NearbyRoomSession.manager(this).sendResumeAnalysis(actionType)
            Toast.makeText(this, "已通知节点继续训练识别", Toast.LENGTH_SHORT).show()
        }
        renderState()
    }

    // 远端分析摘要聚合
    private fun handleAnalysisSummary(endpointId: String, message: NearbyMessage) {
        val receivedAtMs = System.currentTimeMillis()
        val aggregate = remoteSummaryAggregator.record(
            endpointId = endpointId,
            message = message,
            endpoints = NearbyRoomSession.manager(this).currentState().endpoints,
        )
        applyRemoteAggregate(aggregate)
        remoteSummaryCount += 1
        lastRemoteSummaryAtMs = receivedAtMs
        lastRemoteLatencyMs = NearbyMessageDiagnostics.estimateDelayMs(message, receivedAtMs)?.toLong()
        lastRemoteEndpointId = endpointId
        remoteStatus = """
            多设备连接：已收到节点分析结果，在线结果 ${aggregate.activeSummaryCount} 个。
            融合动作：${aggregate.actionType.displayName}
            融合动作置信度：${aggregate.actionConfidence?.let { "%.0f%%".format(Locale.US, it * 100f) } ?: "暂无"}
            融合核心角度：${RealtimeAnalysisFormatter.coreAngleText(aggregate.kneeAngle, aggregate.trunkAngle)}
            融合姿态等级：${RealtimeAnalysisFormatter.postureLevelText(aggregate.postureLevel)}
            融合建议：${aggregate.suggestion ?: "暂无"}
            ${aggregate.statusText}
        """.trimIndent()
        runOnUiThread { renderState() }
    }

    private fun applyRemoteAggregate(aggregate: RemoteAnalysisAggregate) {
        if (aggregate.actionType != ActionType.UNKNOWN) {
            actionType = aggregate.actionType
            bestActionConfidence = aggregate.actionConfidence
        }
        if (aggregate.activeSummaryCount > 0 && aggregate.actionType.isTrainingAction) {
            recordProgress(
                actionType = aggregate.actionType,
                totalCount = aggregate.totalCount,
                holdDurationMs = aggregate.holdDurationMs,
                score = aggregate.score,
                problemType = aggregate.problemType,
                suggestion = aggregate.suggestion,
            )
        }
    }

    private fun maybeSendHostAnalysisStatus(scoreText: String, progressText: String) {
        val manager = NearbyRoomSession.manager(this)
        val state = manager.currentState()
        if (!state.isHostSession || state.endpoints.none { endpoint -> endpoint.isOnline }) return
        val now = System.currentTimeMillis()
        val suggestion = remoteSuggestion ?: suggestionFor(problem)
        val statusMessage = listOf(
            "主控实时结果：${trainingModeText(actionType)}",
            progressText,
            "评分：$scoreText",
            "问题：${problem.displayName}",
            "建议：$suggestion",
        ).joinToString("；")
        val signature = listOf(
            actionType.name,
            count.toString(),
            holdDurationMs.toString(),
            score.toString(),
            problem.name,
            suggestion,
            isRemoteTrainingPaused.toString(),
        ).joinToString("|")
        if (signature == lastHostStatusSignature && now - lastHostStatusSentAtMs < HOST_STATUS_SEND_INTERVAL_MS) {
            return
        }
        lastHostStatusSignature = signature
        lastHostStatusSentAtMs = now
        manager.sendHostAnalysisStatus(
            actionType = actionType,
            totalCount = count,
            holdDurationMs = null,
            score = if (actionType.supportsDetailedScore) {
                SquatScorePolicy.reportableScore(problem, score.toFloat())
            } else {
                null
            },
            kneeAngle = null,
            trunkAngle = null,
            postureLevel = if (problem == ProblemType.NONE) "NORMAL" else problem.name,
            problemType = problem,
            suggestion = suggestion,
            message = statusMessage,
        )
    }

    private fun handlePoseFrameSnapshot(endpointId: String, message: NearbyMessage) {
        val receivedAtMs = System.currentTimeMillis()
        val frame = NearbyPoseFrameCodec.decode(message.poseFrameJson)
        remotePoseFrameCount += 1
        lastRemotePoseFrameAtMs = receivedAtMs
        lastRemotePoseLatencyMs = NearbyMessageDiagnostics.estimateDelayMs(message, receivedAtMs)?.toLong()
        lastRemotePoseEndpointId = endpointId
        if (message.actionType != ActionType.UNKNOWN && message.role == DeviceRole.FRONT_CAMERA) {
            actionType = message.actionType
        }
        remotePoseStatus = if (frame == null) {
            "节点关键点样本：收到样本，但内容为空或解析失败。"
        } else {
            remotePoseFrameBuffer.add(frame)
            val humanStatus = if (frame.overallConfidence >= 0.5f) "人体已入镜" else "低置信度，请调整机位"
            """
                节点关键点样本：$humanStatus
                节点机位：${frame.cameraRole.displayText()}
                关键点数量：${frame.landmarks.size}
                平均置信度：${"%.2f".format(Locale.US, frame.overallConfidence)}
                画面尺寸：${frame.imageWidth} x ${frame.imageHeight}
            """.trimIndent()
        }
        runOnUiThread { renderState() }
    }

    private fun stopAutoSimulation(message: String? = null) {
        isAutoSimulating = false
        autoSimulationHandler.removeCallbacks(autoSimulationRunnable)
        if (::binding.isInitialized) {
            binding.autoSimulateButton.text = "开始自动模拟训练"
            binding.simulateSquatButton.isEnabled = true
            binding.simulateJumpingJackButton.isEnabled = true
            renderState()
        }
        if (message != null) {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun targetCount(): Int =
        if (actionType == ActionType.JUMPING_JACK) 20 else 8

    private fun targetText(): String =
        if (actionType.isHoldBased) "${TARGET_HOLD_DURATION_MS / 1000} 秒" else "${targetCount()} 次"

    private fun isAutoTargetReached(): Boolean =
        if (actionType.isHoldBased) holdDurationMs >= TARGET_HOLD_DURATION_MS else count >= targetCount()

    private fun trainingModeText(actionType: ActionType): String =
        if (actionType == ActionType.UNKNOWN) "自动识别${ActionType.trainingActionNamesText()}" else actionType.displayName

    private fun DeviceRole.displayText(): String =
        when (this) {
            DeviceRole.HOST -> "主控端"
            DeviceRole.FRONT_CAMERA -> "正面机位"
            DeviceRole.SIDE_CAMERA -> "侧面机位"
            DeviceRole.BACKUP_CAMERA -> "备用机位"
            DeviceRole.UNKNOWN -> "未分配"
        }

    private fun nearbyDeviceSnapshots(): List<TrainingDeviceSnapshot> =
        NearbyRoomSession.manager(this).currentState().endpoints.map { endpoint ->
            TrainingDeviceSnapshot(
                deviceName = endpoint.deviceName,
                endpointId = endpoint.endpointId,
                role = endpoint.role,
                batteryLevel = endpoint.batteryLevel,
                networkDelayMs = endpoint.networkDelayMs,
                isOnline = endpoint.isOnline,
                lastHeartbeatAt = endpoint.lastHeartbeatAt.takeIf { it > 0L },
            )
        }

    override fun onDestroy() {
        stopAutoSimulation()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_ACTION_TYPE = "extra_action_type"
        private const val MIN_SAVE_HOLD_DURATION_MS = 1_000L
        private const val AUTO_SIMULATION_INTERVAL_MS = 700L
        private const val TARGET_HOLD_DURATION_MS = 5_000L
        private const val HOST_STATUS_SEND_INTERVAL_MS = 500L
    }

    private data class SaveProgress(
        val totalCount: Int,
        val holdDurationMs: Long,
        val problemType: ProblemType,
        val suggestion: String,
    )
}
