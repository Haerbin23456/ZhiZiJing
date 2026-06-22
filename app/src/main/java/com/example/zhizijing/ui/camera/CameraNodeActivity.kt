package com.example.zhizijing.ui.camera

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.os.Bundle
import android.os.CountDownTimer
import android.util.Base64
import android.util.TypedValue
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.View
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import com.example.zhizijing.R
import com.example.zhizijing.data.datastore.AppSettingsDataStore
import com.example.zhizijing.data.datastore.toPoseAnalysisConfig
import com.example.zhizijing.data.repository.TrainingDeviceSnapshot
import com.example.zhizijing.data.repository.TrainingRepository
import com.example.zhizijing.databinding.ActivityCameraNodeBinding
import com.example.zhizijing.databinding.SheetCameraSettingsBinding
import com.example.zhizijing.diagnostics.CameraNodeDiagnostics
import com.example.zhizijing.domain.model.ActionType
import com.example.zhizijing.domain.model.ActionClassificationResult
import com.example.zhizijing.domain.model.ActionProgressSnapshot
import com.example.zhizijing.domain.model.ActionProgressTracker
import com.example.zhizijing.domain.model.DeviceRole
import com.example.zhizijing.domain.model.JumpingJackStage
import com.example.zhizijing.domain.model.ProblemType
import com.example.zhizijing.domain.model.SquatStage
import com.example.zhizijing.domain.model.TrainingState
import com.example.zhizijing.domain.model.TrainingSaveValidator
import com.example.zhizijing.domain.model.TrainingSummary
import com.example.zhizijing.domain.rule.PoseAnalysisConfig
import com.example.zhizijing.domain.rule.SimpleBasicActionAnalyzer
import com.example.zhizijing.domain.rule.SimpleJumpingJackAnalyzer
import com.example.zhizijing.domain.rule.SimpleSquatAnalyzer
import com.example.zhizijing.domain.rule.SquatScorePolicy
import com.example.zhizijing.nearby.connection.NearbyConnectionListener
import com.example.zhizijing.nearby.connection.NearbyConnectionMode
import com.example.zhizijing.nearby.connection.NearbyConnectionState
import com.example.zhizijing.nearby.connection.NearbyRoomSession
import com.example.zhizijing.nearby.message.NearbyMessage
import com.example.zhizijing.nearby.message.NearbyMessageType
import com.example.zhizijing.nearby.message.NearbyPoseFrameCodec
import com.example.zhizijing.pose.classifier.BestActionRecognition
import com.example.zhizijing.pose.classifier.BestActionRecognitionTracker
import com.example.zhizijing.pose.classifier.RuleBasedActionClassifier
import com.example.zhizijing.pose.classifier.StableActionRecognizer
import com.example.zhizijing.pose.detector.MlKitPoseDetectorAdapter
import com.example.zhizijing.pose.feature.PoseActionRules
import com.example.zhizijing.pose.model.PoseFrame
import com.example.zhizijing.report.TrainingFileLayout
import com.example.zhizijing.ui.analysis.ActionAnalysisActivity
import com.example.zhizijing.ui.analysis.RemoteAnalysisAggregate
import com.example.zhizijing.ui.analysis.RemoteAnalysisSummaryAggregator
import com.example.zhizijing.ui.history.HistoryDetailActivity
import com.example.zhizijing.utils.AppExecutors
import com.google.android.material.bottomsheet.BottomSheetDialog
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.ArrayDeque
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class CameraNodeActivity : ComponentActivity() {
    private lateinit var binding: ActivityCameraNodeBinding
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var poseDetectorAdapter: MlKitPoseDetectorAdapter
    private lateinit var analysisConfig: PoseAnalysisConfig
    private lateinit var ruleBasedActionClassifier: RuleBasedActionClassifier
    private lateinit var stableActionRecognizer: StableActionRecognizer
    private val bestActionRecognitionTracker = BestActionRecognitionTracker()
    private val actionProgressTracker = ActionProgressTracker()
    private lateinit var squatAnalyzer: SimpleSquatAnalyzer
    private lateinit var jumpingJackAnalyzer: SimpleJumpingJackAnalyzer
    private lateinit var basicActionAnalyzer: SimpleBasicActionAnalyzer
    private lateinit var scaleGestureDetector: ScaleGestureDetector
    private var boundCamera: Camera? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null
    private var latestVideoFile: File? = null
    private var pendingVideoSessionId: Long? = null
    private val pendingVideoSaveCoordinator = PendingVideoSaveCoordinator()
    private var activeCameraSettingsBinding: SheetCameraSettingsBinding? = null
    private var showSkeletonOverlay = true
    private var saveVideoEnabled = false
    @Volatile
    private var currentCameraRole = DeviceRole.UNKNOWN
    private val recognitionLock = Any()
    private val recentFrames = ArrayDeque<PoseFrame>()
    private val sessionFrames = mutableListOf<PoseFrame>()
    private val remoteSessionFrames = ArrayDeque<PoseFrame>()
    private val localRepResults = mutableListOf<RepResultEvent>()
    private val remoteRepResults = mutableListOf<RepResultEvent>()
    private val remoteSummaryAggregator = RemoteAnalysisSummaryAggregator()
    private var analyzedFrameCount = 0
    private var lastSummarySentAtMs = 0L
    private var lastPoseFrameSentAtMs = 0L
    private var lastFrameSavedAtMs = 0L
    private var lastLiveReplayFrameCount = 0
    private var startedAtMs = 0L
    private var expectedActionType = ActionType.UNKNOWN
    private var latestActionType = ActionType.UNKNOWN
    private var latestCount = 0
    private var latestHoldDurationMs = 0L
    private var latestScore: Float? = null
    private var latestProblem = ProblemType.NONE
    private var latestSuggestion: String? = null
    private var latestPoseStageText: String? = null
    private var latestPoseDetected = false
    private var lastHostAnalysisStatusAtMs = 0L
    private var lastHostDashboardStatusSentAtMs = 0L
    private var lastHostDashboardStatusSignature = ""
    private var isSavingTraining = false
    @Volatile
    private var trainingStarted = false
    @Volatile
    private var isRecognitionPaused = false
    private var isRemoteControlledNode = false
    private var remoteAutoStartRequested = false
    private var remoteEndBroadcastForSession = false
    private var lastCaptureReadySignature = ""
    private var trainingCountdownTimer: CountDownTimer? = null
    private var nearbyStatusText = "单机模式"
    private var latestNearbyState = NearbyConnectionState()
    private var latestRemoteAggregate: RemoteAnalysisAggregate? = null
    private val diagnostics = CameraNodeDiagnostics()
    private val nearbyListener = object : NearbyConnectionListener {
        override fun onNearbyStateChanged(state: NearbyConnectionState) {
            updateCameraRole(state.localRole)
            updateControlAuthority(state)
            latestNearbyState = state
            nearbyStatusText = nearbyStatusLine(state)
            maybeSendCaptureReadyStatus()
            diagnostics.recordEvent("多设备状态：${state.statusText}", System.currentTimeMillis())
            runOnUiThread {
                renderTrainingControls()
                renderTrainingDashboard()
                renderDiagnostics()
            }
        }

        override fun onNearbyMessageReceived(endpointId: String, message: NearbyMessage) {
            diagnostics.recordEvent("收到多设备消息", System.currentTimeMillis())
            handleNearbyMessage(endpointId, message)
        }
    }
    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            diagnostics.recordEvent("相机权限已允许", System.currentTimeMillis())
            startCamera()
        } else {
            diagnostics.recordEvent("相机权限被拒绝", System.currentTimeMillis())
            Toast.makeText(this, "相机权限被拒绝，无法启动预览", Toast.LENGTH_SHORT).show()
            showCameraStatus("相机权限被拒绝。请在系统设置中允许相机权限后重试。")
            renderDiagnostics()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCameraNodeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 相机页运行参数初始化
        val appSettings = AppSettingsDataStore.load(this)
        analysisConfig = appSettings.toPoseAnalysisConfig()
        showSkeletonOverlay = appSettings.showSkeletonOverlay
        saveVideoEnabled = appSettings.saveVideoEnabled
        ruleBasedActionClassifier = RuleBasedActionClassifier(analysisConfig)
        stableActionRecognizer = StableActionRecognizer()
        squatAnalyzer = SimpleSquatAnalyzer(analysisConfig)
        jumpingJackAnalyzer = SimpleJumpingJackAnalyzer(analysisConfig)
        basicActionAnalyzer = SimpleBasicActionAnalyzer(analysisConfig)
        renderPoseOverlayVisibility()
        expectedActionType = intent.getStringExtra(ActionAnalysisActivity.EXTRA_ACTION_TYPE)
            ?.let { ActionType.fromNameOrUnknown(it) }
            ?: ActionType.UNKNOWN
        latestActionType = expectedActionType
        remoteAutoStartRequested = intent.getBooleanExtra(EXTRA_REMOTE_AUTO_START, false)
        cameraExecutor = Executors.newSingleThreadExecutor()
        poseDetectorAdapter = MlKitPoseDetectorAdapter { currentCameraRole }
        setupPreviewZoomGesture()
        val initialNearbyState = NearbyRoomSession.manager(this).currentState()
        latestNearbyState = initialNearbyState
        updateControlAuthority(initialNearbyState)
        binding.startTrainingButton.setOnClickListener {
            if (isRemoteControlledNode) {
                showRemoteControlledNodeToast()
            } else {
                startTrainingCountdown(DEFAULT_COUNTDOWN_SECONDS, expectedActionType, triggeredByRemote = false)
            }
        }
        binding.settingsButton.setOnClickListener { showCameraSettingsSheet() }
        binding.pauseRecognitionButton.setOnClickListener {
            if (isRemoteControlledNode) showRemoteControlledNodeToast() else toggleRecognitionPause()
        }
        binding.saveTrainingButton.setOnClickListener {
            if (isRemoteControlledNode) showRemoteControlledNodeToast() else saveRecognizedTraining(triggeredByRemote = false)
        }
        binding.backButton.setOnClickListener { finish() }
        binding.saveTrainingButton.isEnabled = false
        renderTrainingControls()
        renderVideoButton()
        renderPauseButton()
        renderTrainingDashboard()
        renderDiagnostics()
        requestOrStartCamera()
        maybeStartRemoteTrainingFromIntent()
    }

    override fun onStart() {
        super.onStart()
        NearbyRoomSession.manager(this).addListener(nearbyListener)
    }

    override fun onStop() {
        if (lastCaptureReadySignature.isNotBlank() && !trainingStarted) {
            NearbyRoomSession.manager(this).sendNodeCaptureIdle()
            lastCaptureReadySignature = ""
        }
        NearbyRoomSession.manager(this).removeListener(nearbyListener)
        super.onStop()
    }

    private fun requestOrStartCamera() {
        if (hasCameraPermission()) {
            startCamera()
        } else {
            showCameraStatus("需要相机权限才能启动摄像头预览。")
            diagnostics.recordEvent("请求相机权限", System.currentTimeMillis())
            renderDiagnostics()
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("ClickableViewAccessibility")
    private fun setupPreviewZoomGesture() {
        scaleGestureDetector = ScaleGestureDetector(
            this,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    updateCameraZoom(detector.scaleFactor)
                    return true
                }
            },
        )
        binding.previewView.setOnTouchListener { _, event ->
            scaleGestureDetector.onTouchEvent(event)
            true
        }
    }

    private fun updateCameraZoom(scaleFactor: Float) {
        val camera = boundCamera ?: return
        val zoomState = camera.cameraInfo.zoomState.value ?: return
        val nextRatio = (zoomState.zoomRatio * scaleFactor)
            .coerceIn(zoomState.minZoomRatio, zoomState.maxZoomRatio)
        camera.cameraControl.setZoomRatio(nextRatio)
    }

    // CameraX 预览与分析管线
    private fun startCamera() {
        showCameraStatus("正在启动摄像头预览...")
        diagnostics.recordEvent("启动摄像头预览", System.currentTimeMillis())
        renderDiagnostics()
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val targetRotation = binding.previewView.display?.rotation ?: Surface.ROTATION_0
            val preview = Preview.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                .setTargetRotation(targetRotation)
                .build()
                .also { it.setSurfaceProvider(binding.previewView.surfaceProvider) }
            val imageAnalysis = ImageAnalysis.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                .setTargetRotation(targetRotation)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { analysis ->
                    analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                        val receivedAtMs = System.currentTimeMillis()
                        analyzedFrameCount += 1
                        diagnostics.onImageFrame(receivedAtMs)
                        poseDetectorAdapter.detect(
                            inputFrame = imageProxy,
                            sessionId = 0L,
                            nodeId = 1L,
                            onResult = { poseFrame ->
                                val resultAtMs = System.currentTimeMillis()
                                diagnostics.onPoseResult(poseFrame, resultAtMs)
                                if (analyzedFrameCount % 10 == 0 || poseFrame != null) {
                                    val isPaused = isRecognitionPaused
                                    val shouldAnalyze = shouldRunActionRecognition(isPaused)
                                    if (shouldAnalyze && poseFrame != null) {
                                        analyzePoseFrame(poseFrame, imageProxy)
                                    }
                                    runOnUiThread {
                                        if (showSkeletonOverlay || isRemoteControlledNode) {
                                            binding.poseOverlayView.updatePose(poseFrame)
                                        }
                                        renderTrainingDashboard(poseDetected = poseFrame != null, paused = isPaused)
                                        renderDiagnostics(resultAtMs)
                                        when {
                                            !trainingStarted -> {
                                                if (isRemoteControlledNode) {
                                                    showCameraStatus("等待主控端开始训练。\n预览已开启，人体入镜后会显示结构点。")
                                                } else {
                                                    hideCameraStatus()
                                                }
                                            }
                                            isPaused -> showCameraStatus(
                                                if (isRemoteControlledNode) {
                                                    "主控端已暂停训练。\n摄像头预览保持开启。"
                                                } else {
                                                    "动作识别已暂停。\n点击继续识别后恢复计数。"
                                                }
                                            )
                                            poseFrame == null -> showCameraStatus(
                                                if (isRemoteControlledNode) {
                                                    "主控端已开始训练。\n暂未识别到人体，请保持全身入镜。"
                                                } else {
                                                    "动作识别已启动。\n暂未识别到人体，请保持全身入镜。"
                                                }
                                            )
                                            else -> showCameraStatus(
                                                if (isRemoteControlledNode) {
                                                    "人体已入镜。\n本机仅显示结构点。"
                                                } else {
                                                    "人体已入镜。\n正在识别动作。"
                                                }
                                            )
                                        }
                                    }
                                }
                            },
                            onError = { error ->
                                val errorAtMs = System.currentTimeMillis()
                                diagnostics.onError(error.message, errorAtMs)
                                runOnUiThread {
                                    showCameraStatus("姿态识别失败：${error.message}")
                                    renderDiagnostics(errorAtMs)
                                }
                            },
                        )
                    }
                }
            val recorder = Recorder.Builder()
                .setQualitySelector(QualitySelector.from(Quality.SD))
                .build()
            videoCapture = if (saveVideoEnabled) {
                VideoCapture.withOutput(recorder)
            } else {
                null
            }

            runCatching {
                cameraProvider.unbindAll()
                val capture = videoCapture
                boundCamera = if (capture == null) {
                    cameraProvider.bindToLifecycle(
                        this,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        imageAnalysis,
                    )
                } else {
                    cameraProvider.bindToLifecycle(
                        this,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        imageAnalysis,
                        capture,
                    )
                }
            }.recoverCatching { error ->
                val capture = videoCapture
                if (capture == null) throw error
                diagnostics.recordEvent("视频用例绑定失败，回退预览分析：${error.message}", System.currentTimeMillis())
                videoCapture = null
                cameraProvider.unbindAll()
                boundCamera = cameraProvider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageAnalysis,
                )
            }.onSuccess {
                diagnostics.recordEvent("摄像头预览启动成功", System.currentTimeMillis())
                renderDiagnostics()
                hideCameraStatus()
                maybeSendCaptureReadyStatus()
                maybeStartVideoRecordingForTraining()
                renderVideoButton()
            }.onFailure {
                boundCamera = null
                diagnostics.recordEvent("摄像头启动失败：${it.message}", System.currentTimeMillis())
                showCameraStatus("摄像头启动失败：${it.message}")
                renderDiagnostics()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    // 连续姿态帧进入规则识别
    private fun analyzePoseFrame(frame: PoseFrame, imageProxy: ImageProxy) = synchronized(recognitionLock) {
        val analysisFrame = rememberSessionFrame(frame, imageProxy)
        recentFrames.addLast(analysisFrame)
        while (recentFrames.size > CLASSIFIER_WINDOW_SIZE) {
            recentFrames.removeFirst()
        }
        val frameWindow = recentFrames.toList()
        val canDetermineActionType = currentCameraRole != DeviceRole.UNKNOWN
        val rawClassification = if (canDetermineActionType) {
            ruleBasedActionClassifier.classify(frameWindow)
        } else {
            null
        }
        val stableRecognition = rawClassification?.let { classification ->
            stableActionRecognizer.observe(classification)
        }
        val classification = stableRecognition?.result
        rawClassification?.let { result ->
            bestActionRecognitionTracker.observe(result, isConfirmed = false)
        }
        stableRecognition?.let { recognition ->
            bestActionRecognitionTracker.observe(recognition.result, isConfirmed = recognition.isConfirmed)
        }
        val actionType = if (expectedActionType != ActionType.UNKNOWN) {
            expectedActionType
        } else if (canDetermineActionType) {
            classification?.actionType ?: ActionType.UNKNOWN
        } else {
            ActionType.UNKNOWN
        }
        latestActionType = if (actionType == ActionType.UNKNOWN) latestActionType else actionType
        val actionConfidence = actionConfidenceFor(actionType, classification, rawClassification)
        maybeSendPoseFrameSnapshot(actionType, analysisFrame)
        when (actionType) {
            ActionType.SQUAT -> {
                val previousCount = actionProgressTracker.bestFor(ActionType.SQUAT)?.totalCount ?: 0
                val result = squatAnalyzer.analyze(analysisFrame)
                latestPoseStageText = squatStageText(result.currentStage)
                val reportableScore = SquatScorePolicy.reportableScore(result.problemType, result.score)
                val progress = actionProgressTracker.record(
                    actionType = ActionType.SQUAT,
                    totalCount = result.totalCount,
                    holdDurationMs = 0L,
                    score = reportableScore,
                    problemType = result.problemType,
                    suggestion = result.suggestion,
                )
                if (result.totalCount > previousCount) {
                    recordLocalRepResult(
                        actionType = ActionType.SQUAT,
                        repIndex = result.totalCount,
                        score = reportableScore,
                        kneeAngle = result.kneeAngle,
                        trunkAngle = result.trunkAngle,
                        postureLevel = result.depthLevel,
                        problemTypes = result.problemTypes,
                        suggestion = result.suggestion,
                    )
                }
                applyLatestProgress(progress)
            }
            ActionType.JUMPING_JACK -> {
                val result = jumpingJackAnalyzer.analyze(analysisFrame)
                latestPoseStageText = jumpingJackStageText(result.currentStage)
                latestProblem = if (result.lostFrameCount > 0) ProblemType.LOW_CONFIDENCE else ProblemType.NONE
                latestSuggestion = if (result.lostFrameCount > 0) "请保持全身入镜，避免丢失关键点。" else "保持稳定节奏。"
                val progress = actionProgressTracker.record(
                    actionType = ActionType.JUMPING_JACK,
                    totalCount = result.totalCount,
                    holdDurationMs = 0L,
                    score = null,
                    problemType = latestProblem,
                    suggestion = latestSuggestion,
                )
                applyLatestProgress(progress)
                maybeSendAnalysisSummary(
                    actionType = ActionType.JUMPING_JACK,
                    actionConfidence = actionConfidence,
                    totalCount = progress.totalCount,
                    score = null,
                    kneeAngle = null,
                    trunkAngle = null,
                    postureLevel = if (progress.problemType == ProblemType.LOW_CONFIDENCE) "LOW_CONFIDENCE" else "COUNT_ONLY",
                    problemType = progress.problemType,
                    suggestion = progress.suggestion,
                )
            }
            in ActionType.trainingActions -> {
                val result = basicActionAnalyzer.analyze(actionType, analysisFrame)
                latestPoseStageText = result.stageText
                val progress = actionProgressTracker.record(
                    actionType = actionType,
                    totalCount = result.totalCount,
                    holdDurationMs = result.holdDurationMs,
                    score = null,
                    problemType = result.problemType,
                    suggestion = result.suggestion,
                )
                applyLatestProgress(progress)
                maybeSendAnalysisSummary(
                    actionType = actionType,
                    actionConfidence = actionConfidence,
                    totalCount = progress.totalCount,
                    holdDurationMs = progress.holdDurationMs,
                    score = null,
                    kneeAngle = null,
                    trunkAngle = null,
                    postureLevel = if (actionType.isHoldBased) "HOLDING" else "COUNT_ONLY",
                    problemType = progress.problemType,
                    suggestion = progress.suggestion,
                )
            }
            else -> {
                latestPoseStageText = null
                latestProblem = ProblemType.NONE
                latestSuggestion = unknownActionHintText()
            }
        }
        syncLiveProgressFromSessionFrames(actionType)
    }

    private fun unknownActionHintText(): String =
        when (currentCameraRole) {
            DeviceRole.UNKNOWN -> "请先设置机位，再完成 ${ActionType.trainingActionNamesText()} 中的一种动作。"
            else -> "请保持全身入镜，当前${currentCameraRole.displayText()}会尝试识别 ${ActionType.trainingActionNamesText()} 中的一种动作。"
        }

    private fun actionConfidenceFor(
        actionType: ActionType,
        classification: ActionClassificationResult?,
        rawClassification: ActionClassificationResult?,
    ): Float? {
        if (!actionType.isTrainingAction) return null
        val directConfidence = listOfNotNull(classification, rawClassification)
            .firstOrNull { result -> result.actionType == actionType }
            ?.confidence
        return directConfidence
            ?: bestActionRecognitionTracker.best()
                .takeIf { best -> best.actionType == actionType }
                ?.confidence
    }

    private fun applyLatestProgress(progress: ActionProgressSnapshot) {
        latestCount = progress.totalCount
        latestHoldDurationMs = progress.holdDurationMs
        latestScore = progress.score
        latestProblem = progress.problemType
        latestSuggestion = progress.suggestion
    }

    private fun syncLiveProgressFromSessionFrames(actionType: ActionType) {
        if (!actionType.isTrainingAction) return
        val frameCount = sessionFrames.size
        if (frameCount == 0 || frameCount == lastLiveReplayFrameCount) return
        val frames = sessionFrames.toList()
        val replayState = runCatching {
            replaySaveStateFromFrames(
                SaveState(
                    expectedActionType = expectedActionType,
                    actionType = actionType,
                    bestRecognition = bestActionRecognitionTracker.best(),
                    totalCount = latestCount,
                    holdDurationMs = latestHoldDurationMs,
                    score = latestScore,
                    problemType = latestProblem,
                    suggestion = latestSuggestion,
                    durationMs = System.currentTimeMillis() - startedAtMs,
                    frames = frames,
                    actionProblemTypes = emptyList(),
                )
            )
        }.getOrElse { error ->
            lastLiveReplayFrameCount = frameCount
            diagnostics.recordEvent(
                "实时关键点回放失败：${error.message ?: error.javaClass.simpleName}",
                System.currentTimeMillis(),
            )
            return
        }
        lastLiveReplayFrameCount = frameCount
        val selectedActionType = replayState.actionType.takeIf { it != ActionType.UNKNOWN } ?: actionType
        if (!selectedActionType.isTrainingAction) return
        if (selectedActionType.isCountBased && replayState.totalCount < latestCount) return
        if (selectedActionType.isHoldBased && replayState.holdDurationMs < latestHoldDurationMs) return
        val progress = actionProgressTracker.record(
            actionType = selectedActionType,
            totalCount = replayState.totalCount,
            holdDurationMs = replayState.holdDurationMs,
            score = replayState.score,
            problemType = replayState.problemType,
            suggestion = replayState.suggestion,
        )
        latestActionType = progress.actionType
        applyLatestProgress(progress)
    }

private fun renderTrainingDashboard(
        poseDetected: Boolean? = null,
        paused: Boolean = isRecognitionPaused,
    ) {
        if (!::binding.isInitialized) return
        poseDetected?.let { latestPoseDetected = it }
        val state = synchronized(recognitionLock) {
            DashboardState(
                actionType = when {
                    latestActionType != ActionType.UNKNOWN -> latestActionType
                    expectedActionType != ActionType.UNKNOWN -> expectedActionType
                    else -> ActionType.UNKNOWN
                },
                totalCount = latestCount,
                holdDurationMs = latestHoldDurationMs,
                score = latestScore,
                problemType = latestProblem,
                suggestion = latestSuggestion,
                poseStageText = latestPoseStageText,
            )
        }
        binding.actionValueText.text = dashboardActionText(state.actionType)
        binding.countLabelText.text = if (isRemoteControlledNode) "本机状态" else "次数"
        binding.countValueText.setTextSize(
            TypedValue.COMPLEX_UNIT_SP,
            if (isRemoteControlledNode) 30f else 42f,
        )
        binding.countValueText.text = if (isRemoteControlledNode) {
            remoteCapturePrimaryText(paused)
        } else {
            "${state.totalCount} 次"
        }
        binding.problemValueText.text = when {
            isRemoteControlledNode && !trainingStarted -> remoteHostResultText()
            isRemoteControlledNode && paused -> "等待主控"
            !trainingStarted -> "待开始"
            paused -> "已暂停"
            isRemoteControlledNode && lastHostAnalysisStatusAtMs > 0L && state.actionType != ActionType.UNKNOWN ->
                state.poseStageText ?: "主控识别中"
            !latestPoseDetected -> "未入镜"
            isRemoteControlledNode -> state.poseStageText ?: "已入镜"
            !state.poseStageText.isNullOrBlank() -> state.poseStageText
            state.actionType == ActionType.UNKNOWN -> "识别中"
            else -> "已入镜"
        }
        binding.trainingControlHintText.text = dashboardModeHintText(paused)
        
        // 渲染网络/多机位文本
        binding.connectionStatusText.text = dashboardConnectionStatusText()
        // 【新增渲染控制】实时调整多机位 HUD 网络状态指示灯颜色
        renderConnectionStatusDot()
        
        maybeBroadcastHostDashboardState(state, paused)
    }

    // 【新增核心辅助函数】动态控制指示灯，增加答辩时的视觉表现力
    private fun renderConnectionStatusDot() {
        if (!::binding.isInitialized) return
        val state = latestNearbyState
        val color = when {
            // 1. 未开启局域网同步时：灰色指示灯
            state.mode == NearbyConnectionMode.IDLE -> 0xFF9E9E9E.toInt()
            
            // 2. 本机是副机（被控端）时
            isRemoteControlledNode -> {
                if (lastHostAnalysisStatusAtMs > 0L) {
                    // 主控已有心跳同步过来：荧光绿
                    0xFF00E676.toInt()
                } else {
                    // 等待配对同步：亮黄
                    0xFFFFD54F.toInt()
                }
            }
            
            // 3. 本机是主机（主控端）时
            else -> {
                val online = state.endpoints.filter { it.isOnline }
                if (online.isEmpty()) {
                    // 没有发现副机加入：亮黄
                    0xFFFFD54F.toInt()
                } else {
                    // 只要有 1 台及以上副机就绪：荧光绿
                    0xFF00E676.toInt()
                }
            }
        }
        // 利用动态 Tint 改变卡片背景色
        binding.connectionStatusDot.setCardBackgroundColor(
            android.content.res.ColorStateList.valueOf(color)
        )
    }

    private fun remoteCapturePrimaryText(paused: Boolean): String =
        when {
            !trainingStarted -> "待命"
            paused -> "已暂停"
            else -> "采集中"
        }

    private fun remoteHostResultText(): String =
        if (lastHostAnalysisStatusAtMs > 0L) "主控结果" else "待开始"

    private fun dashboardModeHintText(paused: Boolean): String =
        when {
            isRemoteControlledNode && !trainingStarted && lastHostAnalysisStatusAtMs > 0L ->
                "${currentCameraRole.displayText()} · 主控结果"
            isRemoteControlledNode && trainingStarted ->
                "${currentCameraRole.displayText()} · ${if (paused) "暂停" else "上传中"}"
            isRemoteControlledNode -> "本机：${currentCameraRole.displayText()}"
            latestNearbyState.isHostSession && latestNearbyState.endpoints.any { it.isOnline } ->
                "${currentCameraRole.displayText()} · 主控端"
            trainingStarted -> "${currentCameraRole.displayText()} · 训练中"
            else -> "${currentCameraRole.displayText()} · 单机调试"
        }

    private fun dashboardConnectionStatusText(): String {
        val state = latestNearbyState
        if (state.mode == NearbyConnectionMode.IDLE) return "单机模式"
        return if (isRemoteControlledNode) {
            val syncText = if (lastHostAnalysisStatusAtMs > 0L) {
                "主控同步 ${elapsedSecondsAgoText(lastHostAnalysisStatusAtMs)}"
            } else {
                "等待主控同步"
            }
            syncText
        } else {
            val online = state.endpoints.filter { it.isOnline }
            if (!state.isHostSession || online.isEmpty()) {
                "等待副机连接"
            } else {
                val readyCount = online.count { it.isCaptureReady }
                val trainingCount = online.count { it.trainingState == TrainingState.ANALYZING }
                val finishedCount = online.count { it.trainingState == TrainingState.FINISHED }
                when {
                    readyCount == online.size -> "副机 ${readyCount} 台就绪"
                    trainingCount == online.size -> "副机 ${trainingCount} 台训练中"
                    finishedCount == online.size -> "副机 ${finishedCount} 台已结束"
                    readyCount > 0 -> "副机 ${online.size} 台在线 · ${readyCount} 台就绪"
                    trainingCount > 0 -> "副机 ${online.size} 台在线 · ${trainingCount} 台训练中"
                    else -> "副机 ${online.size} 台在线 · 等待就绪"
                }
            }
        }
    }

    private fun elapsedSecondsAgoText(timestampMs: Long): String {
        val elapsedSeconds = ((System.currentTimeMillis() - timestampMs).coerceAtLeast(0L) / 1000L)
        return if (elapsedSeconds <= 0L) "刚刚" else "${elapsedSeconds}秒前"
    }

    private fun squatStageText(stage: SquatStage): String =
        when (stage) {
            SquatStage.STANDING -> "站立"
            SquatStage.DESCENDING,
            SquatStage.SQUATTING,
            SquatStage.RISING -> "蹲姿"
        }

    private fun jumpingJackStageText(stage: JumpingJackStage): String =
        when (stage) {
            JumpingJackStage.CLOSED -> "合拢"
            JumpingJackStage.OPENING -> "打开中"
            JumpingJackStage.OPEN -> "打开"
            JumpingJackStage.CLOSING -> "合拢中"
        }

    private fun maybeBroadcastHostDashboardState(
        state: DashboardState,
        paused: Boolean,
    ) {
        if (!trainingStarted || isRemoteControlledNode || !canControlRemoteNodes()) return
        val now = System.currentTimeMillis()
        val suggestion = state.suggestion
            ?.takeIf { it.isNotBlank() }
            ?: if (state.problemType == ProblemType.NONE) {
                "保持全身入镜，按稳定节奏完成动作。"
            } else {
                state.problemType.displayName
            }
        val signature = listOf(
            state.actionType.name,
            state.totalCount.toString(),
            state.score?.toString().orEmpty(),
            state.problemType.name,
            suggestion,
            paused.toString(),
        ).joinToString("|")
        if (
            signature == lastHostDashboardStatusSignature &&
            now - lastHostDashboardStatusSentAtMs < HOST_DASHBOARD_STATUS_SEND_INTERVAL_MS
        ) {
            return
        }
        lastHostDashboardStatusSignature = signature
        lastHostDashboardStatusSentAtMs = now
        NearbyRoomSession.manager(this).sendHostAnalysisStatus(
            actionType = state.actionType,
            totalCount = state.totalCount,
            holdDurationMs = null,
            score = state.score,
            kneeAngle = null,
            trunkAngle = null,
            postureLevel = if (paused) {
                "PAUSED"
            } else if (state.problemType == ProblemType.NONE) {
                "NORMAL"
            } else {
                state.problemType.name
            },
            problemType = if (paused) ProblemType.NONE else state.problemType,
            suggestion = suggestion,
            message = listOf(
                "主控实时结果：${dashboardActionText(state.actionType)}",
                "当前次数：${state.totalCount}",
                "姿态：${if (paused) "已暂停" else hostPostureText(state.problemType)}",
            ).joinToString("；"),
        )
    }

    private fun dashboardActionText(actionType: ActionType): String =
        if (actionType == ActionType.UNKNOWN) "自动识别" else actionType.displayName

    private fun hostPostureText(problemType: ProblemType): String =
        if (problemType == ProblemType.NONE) "正常" else problemType.displayName

    private fun nearbyStatusLine(state: NearbyConnectionState): String {
        val roleText = currentCameraRole.displayText()
        return if (state.mode == NearbyConnectionMode.IDLE) {
            "单机模式 · $roleText"
        } else {
            "${state.statusText} · $roleText"
        }
    }

    private fun compactStatusText(message: CharSequence): String =
        (message.toString()
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .firstOrNull()
            ?.take(18)
            ?: "")
            .ifBlank { "请保持全身入镜。" }

    private fun showCameraStatus(message: CharSequence) {
        val cleanText = compactStatusText(message)
        binding.cameraStatusText.visibility = View.GONE
        binding.cameraStatusText.text = cleanText
    }

    private fun hideCameraStatus() {
        showCameraStatus("")
    }

    private fun topTrainingStatusText(actionType: ActionType = expectedActionType, status: String): String {
        val actionText = if (actionType == ActionType.UNKNOWN) "训练" else actionType.displayName
        return "$actionText · $status"
    }

    private fun showCameraSettingsSheet() {
        val sheetBinding = SheetCameraSettingsBinding.inflate(layoutInflater)
        val dialog = BottomSheetDialog(this)
        activeCameraSettingsBinding = sheetBinding
        dialog.setContentView(sheetBinding.root)
        dialog.setOnDismissListener {
            if (activeCameraSettingsBinding === sheetBinding) {
                activeCameraSettingsBinding = null
            }
        }
        updateCameraSettingsSheet(sheetBinding)
        sheetBinding.settingsDoneButton.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun setVideoSavingEnabled(enabled: Boolean) {
        if (trainingStarted || isSavingTraining) {
            Toast.makeText(this, "训练开始后不能切换视频保存。", Toast.LENGTH_SHORT).show()
            renderVideoButton()
            return
        }
        saveVideoEnabled = enabled
        val currentSettings = AppSettingsDataStore.load(this)
        AppSettingsDataStore.saveOutputSettings(
            context = this,
            saveVideoEnabled = enabled,
            defaultExportDir = currentSettings.defaultExportDir,
        )
        diagnostics.recordEvent(
            if (enabled) "视频保存已开启" else "视频保存已关闭",
            System.currentTimeMillis(),
        )
        Toast.makeText(this, if (enabled) "视频保存已开启" else "视频保存已关闭", Toast.LENGTH_SHORT).show()
        renderVideoButton()
        if (hasCameraPermission()) {
            startCamera()
        } else {
            requestOrStartCamera()
        }
    }

    private fun maybeStartVideoRecordingForTraining() {
        if (
            !trainingStarted ||
            isRemoteControlledNode ||
            !saveVideoEnabled ||
            activeRecording != null ||
            latestVideoFile?.exists() == true
        ) {
            return
        }
        if (videoCapture == null) {
            diagnostics.recordEvent("视频保存已开启，等待摄像头视频用例就绪", System.currentTimeMillis())
            renderVideoButton()
            return
        }
        startVideoRecording(showNotReadyToast = false)
    }

    private fun startVideoRecording(showNotReadyToast: Boolean = true) {
        val capture = videoCapture
        if (capture == null) {
            if (showNotReadyToast) {
                Toast.makeText(this, "视频录制尚未就绪，请稍后重试。", Toast.LENGTH_SHORT).show()
            }
            diagnostics.recordEvent("视频录制尚未就绪", System.currentTimeMillis())
            renderVideoButton()
            return
        }
        val outputDir = TrainingFileLayout.videoDraftsDir(filesDir).apply { mkdirs() }
        val outputFile = File(outputDir, "camera_node_${System.currentTimeMillis()}.mp4")
        val outputOptions = FileOutputOptions.Builder(outputFile).build()
        latestVideoFile = null
        pendingVideoSessionId = null
        activeRecording = capture.output
            .prepareRecording(this, outputOptions)
            .start(ContextCompat.getMainExecutor(this)) { event ->
                when (event) {
                    is VideoRecordEvent.Start -> {
                        diagnostics.recordEvent("开始录制视频素材", System.currentTimeMillis())
                        showCameraStatus("视频录制中。\n完成训练后会保存视频素材。")
                        renderVideoButton()
                        renderDiagnostics()
                    }
                    is VideoRecordEvent.Finalize -> {
                        activeRecording = null
                        if (event.error == VideoRecordEvent.Finalize.ERROR_NONE && outputFile.exists()) {
                            latestVideoFile = outputFile
                            diagnostics.recordEvent("视频素材已保存：${outputFile.name}", System.currentTimeMillis())
                            pendingVideoSessionId?.let { sessionId -> archiveLatestVideoForSession(sessionId) }
                            Toast.makeText(this, "视频素材已保存：${outputFile.name}", Toast.LENGTH_SHORT).show()
                        } else {
                            diagnostics.recordEvent("视频录制失败：${event.cause?.message ?: event.error}", System.currentTimeMillis())
                            Toast.makeText(this, "视频录制失败：${event.cause?.message ?: event.error}", Toast.LENGTH_SHORT).show()
                        }
                        renderVideoButton()
                        renderDiagnostics()
                        pendingVideoSaveCoordinator.consumeTriggeredByRemote()?.let { triggeredByRemote ->
                            showCameraStatus("视频录制已结束，正在保存训练记录...")
                            saveRecognizedTraining(triggeredByRemote)
                        }
                    }
                }
            }
    }

    private fun stopVideoRecording() {
        activeRecording?.stop()
        diagnostics.recordEvent("结束视频录制请求", System.currentTimeMillis())
        renderVideoButton()
        renderDiagnostics()
    }

    private fun renderVideoButton() {
        if (!::binding.isInitialized) return
        updateCameraSettingsSheet()
        renderTrainingDashboard()
    }

    private fun updateCameraSettingsSheet(sheetBinding: SheetCameraSettingsBinding? = activeCameraSettingsBinding) {
        val binding = sheetBinding ?: return
        binding.showSkeletonSwitch.setOnCheckedChangeListener(null)
        binding.saveVideoSwitch.setOnCheckedChangeListener(null)
        binding.showSkeletonSwitch.isChecked = showSkeletonOverlay
        binding.saveVideoSwitch.isChecked = saveVideoEnabled
        val canChangeVideoSaving = !trainingStarted && !isSavingTraining
        binding.showSkeletonSwitch.isEnabled = !isSavingTraining
        binding.saveVideoSwitch.isEnabled = canChangeVideoSaving
        binding.settingsSheetHintText.text = if (canChangeVideoSaving) {
            "骨架线可随时切换；视频保存需在训练开始前设置。"
        } else {
            "骨架线可随时切换；训练进行中视频保存已锁定。"
        }
        binding.videoStateText.text = when {
            !saveVideoEnabled -> "视频保存未开启"
            activeRecording != null -> "视频正在录制，结束训练后会自动归档。"
            videoCapture == null -> "视频保存已开启，等待摄像头视频用例就绪。"
            latestVideoFile?.exists() == true -> "视频保存已开启，本轮素材已录制。"
            trainingStarted -> "视频保存已开启，训练中会自动录制。"
            else -> "视频保存已开启，开始训练后会自动录制。"
        }
        binding.showSkeletonSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isSavingTraining) {
                Toast.makeText(this, "正在保存训练，暂不能切换骨架线。", Toast.LENGTH_SHORT).show()
                updateCameraSettingsSheet(binding)
                return@setOnCheckedChangeListener
            }
            showSkeletonOverlay = isChecked
            AppSettingsDataStore.saveDisplaySettings(this, isChecked)
            renderPoseOverlayVisibility()
            updateCameraSettingsSheet(binding)
        }
        binding.saveVideoSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (trainingStarted || isSavingTraining) {
                Toast.makeText(this, "训练开始后不能切换视频保存。", Toast.LENGTH_SHORT).show()
                updateCameraSettingsSheet(binding)
                return@setOnCheckedChangeListener
            }
            setVideoSavingEnabled(isChecked)
            updateCameraSettingsSheet(binding)
        }
    }

    private fun updateControlAuthority(state: NearbyConnectionState) {
        isRemoteControlledNode = state.mode != NearbyConnectionMode.IDLE && !state.isHostSession
    }

private fun renderTrainingControls() {
        if (!::binding.isInitialized) return
        renderPoseOverlayVisibility()
        
        if (isRemoteControlledNode) {
            binding.startTrainingButton.visibility = View.GONE
            binding.activeControlRow.visibility = View.GONE
            binding.countdownOverlay.visibility = View.GONE
        } else if (trainingStarted) {
            binding.startTrainingButton.visibility = View.GONE
            binding.activeControlRow.visibility = View.VISIBLE
            binding.countdownOverlay.visibility = View.GONE
        } else {
            binding.startTrainingButton.visibility = if (trainingCountdownTimer == null) View.VISIBLE else View.GONE
            binding.activeControlRow.visibility = View.GONE
            binding.startTrainingButton.text = "开始训练"
            binding.startTrainingButton.isEnabled = !isSavingTraining
        }
        
        binding.trainingControlHintText.text = dashboardModeHintText(isRecognitionPaused)
        binding.connectionStatusText.text = dashboardConnectionStatusText()
        // 刷新指示灯
        renderConnectionStatusDot()
    }

    private fun renderPrimaryControlState() {
        if (!::binding.isInitialized) return
        if (isRemoteControlledNode) {
            binding.startTrainingButton.visibility = View.GONE
            binding.activeControlRow.visibility = View.GONE
        } else if (trainingStarted) {
            binding.startTrainingButton.visibility = View.GONE
            binding.activeControlRow.visibility = View.VISIBLE
        } else {
            binding.startTrainingButton.visibility = if (trainingCountdownTimer == null) View.VISIBLE else View.GONE
            binding.activeControlRow.visibility = View.GONE
        }
    }

    private fun renderPoseOverlayVisibility() {
        if (!::binding.isInitialized) return
        binding.poseOverlayView.visibility = if (showSkeletonOverlay || isRemoteControlledNode) {
            View.VISIBLE
        } else {
            View.GONE
        }
    }

    private fun shouldRunActionRecognition(isPaused: Boolean): Boolean =
        trainingStarted && !isPaused

    private fun showRemoteControlledNodeToast() {
        Toast.makeText(this, "本机为加入节点，训练开始、暂停和结束请在主控端操作。", Toast.LENGTH_SHORT).show()
    }

    private fun toggleRecognitionPause() {
        if (!trainingStarted) {
            Toast.makeText(this, "训练开始后才能暂停识别", Toast.LENGTH_SHORT).show()
            return
        }
        val nextPaused = !isRecognitionPaused
        broadcastPauseOrResumeIfNeeded(nextPaused)
        setRecognitionPaused(paused = nextPaused, triggeredByRemote = false)
    }

    private fun setRecognitionPaused(paused: Boolean, triggeredByRemote: Boolean) {
        if (!trainingStarted && triggeredByRemote) {
            showCameraStatus("收到主控端暂停/继续指令，但本机训练尚未开始。")
            renderDiagnostics()
            return
        }
        isRecognitionPaused = paused
        binding.trainingCountdownText.text = topTrainingStatusText(status = if (paused) "已暂停" else "训练中")
        diagnostics.recordEvent(
            if (isRecognitionPaused) "暂停动作识别" else "继续动作识别",
            System.currentTimeMillis(),
        )
        showCameraStatus(
            when {
                isRecognitionPaused && triggeredByRemote -> "主控端已暂停训练识别，摄像头预览仍保持开启。"
                isRecognitionPaused -> "动作识别已暂停，摄像头预览仍保持开启。"
                triggeredByRemote && isRemoteControlledNode -> "主控端已继续训练，请保持全身入镜。本机只显示人体结构点，不参与动作识别。"
                triggeredByRemote -> "主控端已继续训练识别，请保持全身入镜。"
                else -> "动作识别已继续，请保持全身入镜。"
            }
        )
        renderPauseButton()
        renderTrainingDashboard()
        renderDiagnostics()
    }

    private fun renderPauseButton() {
        if (!::binding.isInitialized) return
        binding.pauseRecognitionButton.isEnabled = trainingStarted && !isSavingTraining
        binding.pauseRecognitionButton.contentDescription = if (isRecognitionPaused) "继续识别" else "暂停识别"
        binding.pauseRecognitionButton.setImageResource(if (isRecognitionPaused) R.drawable.ic_play_24 else R.drawable.ic_pause_24)
        renderPrimaryControlState()
    }

    private fun rememberSessionFrame(frame: PoseFrame, imageProxy: ImageProxy): PoseFrame {
        val now = frame.timestampMs
        if (sessionFrames.size >= MAX_SESSION_FRAMES) return frame
        if (now - lastFrameSavedAtMs < SESSION_FRAME_INTERVAL_MS) return frame
        val frameWithRgb = saveRgbFrameDraft(frame, imageProxy) ?: frame
        sessionFrames += frameWithRgb
        lastFrameSavedAtMs = now
        diagnostics.onSessionFrameSaved(sessionFrames.size, now)
        return frameWithRgb
    }

    private fun saveRgbFrameDraft(frame: PoseFrame, imageProxy: ImageProxy): PoseFrame? =
        runCatching {
            val jpegBytes = imageProxyToJpegBytes(imageProxy) ?: return null
            val outputDir = File(filesDir, "training/frame_drafts").apply { mkdirs() }
            val output = File(
                outputDir,
                "rgb_${frame.cameraRole.name.lowercase()}_${frame.timestampMs}.jpg",
            )
            output.writeBytes(jpegBytes)
            frame.copy(
                rgbImagePath = output.absolutePath,
                rgbImageBase64 = Base64.encodeToString(jpegBytes, Base64.NO_WRAP),
            )
        }.getOrNull()

    private fun imageProxyToJpegBytes(imageProxy: ImageProxy): ByteArray? {
        if (imageProxy.format != ImageFormat.YUV_420_888) return null
        val nv21 = yuv420ToNv21(imageProxy)
        val rawJpeg = ByteArrayOutputStream().use { output ->
            YuvImage(nv21, ImageFormat.NV21, imageProxy.width, imageProxy.height, null)
                .compressToJpeg(
                    Rect(0, 0, imageProxy.width, imageProxy.height),
                    RGB_FRAME_JPEG_QUALITY,
                    output,
                )
            output.toByteArray()
        }
        return rotateJpegIfNeeded(rawJpeg, imageProxy.imageInfo.rotationDegrees)
    }

    private fun yuv420ToNv21(imageProxy: ImageProxy): ByteArray {
        val width = imageProxy.width
        val height = imageProxy.height
        val output = ByteArray(width * height * 3 / 2)
        val yPlane = imageProxy.planes[0]
        val uPlane = imageProxy.planes[1]
        val vPlane = imageProxy.planes[2]
        val yBuffer = yPlane.buffer.duplicate()
        val uBuffer = uPlane.buffer.duplicate()
        val vBuffer = vPlane.buffer.duplicate()

        var outputIndex = 0
        for (row in 0 until height) {
            val rowStart = row * yPlane.rowStride
            for (col in 0 until width) {
                output[outputIndex++] = yBuffer.get(rowStart + col * yPlane.pixelStride)
            }
        }

        val chromaWidth = width / 2
        val chromaHeight = height / 2
        for (row in 0 until chromaHeight) {
            val uRowStart = row * uPlane.rowStride
            val vRowStart = row * vPlane.rowStride
            for (col in 0 until chromaWidth) {
                output[outputIndex++] = vBuffer.get(vRowStart + col * vPlane.pixelStride)
                output[outputIndex++] = uBuffer.get(uRowStart + col * uPlane.pixelStride)
            }
        }
        return output
    }

    private fun rotateJpegIfNeeded(jpegBytes: ByteArray, rotationDegrees: Int): ByteArray {
        val normalizedRotation = ((rotationDegrees % 360) + 360) % 360
        if (normalizedRotation == 0) return jpegBytes
        val source = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size) ?: return jpegBytes
        val rotated = Bitmap.createBitmap(
            source,
            0,
            0,
            source.width,
            source.height,
            Matrix().apply { postRotate(normalizedRotation.toFloat()) },
            true,
        )
        val output = ByteArrayOutputStream()
        rotated.compress(Bitmap.CompressFormat.JPEG, RGB_FRAME_JPEG_QUALITY, output)
        if (rotated != source) source.recycle()
        rotated.recycle()
        return output.toByteArray()
    }

    // 节点定时上报分析摘要
    private fun maybeSendAnalysisSummary(
        actionType: ActionType,
        actionConfidence: Float?,
        totalCount: Int,
        holdDurationMs: Long? = null,
        score: Float?,
        kneeAngle: Float?,
        trunkAngle: Float?,
        postureLevel: String?,
        problemType: ProblemType,
        suggestion: String?,
    ) {
        val now = System.currentTimeMillis()
        if (now - lastSummarySentAtMs < SUMMARY_SEND_INTERVAL_MS) return
        lastSummarySentAtMs = now
        diagnostics.recordEvent("发送分析摘要：${actionType.displayName} $totalCount 次", now)
        NearbyRoomSession.manager(this).sendAnalysisSummary(
            actionType = actionType,
            actionConfidence = actionConfidence,
            totalCount = totalCount,
            holdDurationMs = holdDurationMs,
            score = score,
            kneeAngle = kneeAngle,
            trunkAngle = trunkAngle,
            postureLevel = postureLevel,
            problemType = problemType,
            suggestion = suggestion,
        )
    }

    private fun maybeSendPoseFrameSnapshot(actionType: ActionType, frame: PoseFrame) {
        val now = System.currentTimeMillis()
        if (now - lastPoseFrameSentAtMs < POSE_FRAME_SEND_INTERVAL_MS) return
        lastPoseFrameSentAtMs = now
        NearbyRoomSession.manager(this).sendPoseFrameSnapshot(actionType, frame)
    }

    private fun handleNearbyMessage(endpointId: String, message: NearbyMessage) {
        when (message.type) {
            NearbyMessageType.START_COUNTDOWN -> {
                diagnostics.recordEvent("主控倒计时指令：${message.countdownSeconds ?: 3}s", System.currentTimeMillis())
                runOnUiThread {
                    startTrainingCountdown(
                        seconds = message.countdownSeconds ?: DEFAULT_COUNTDOWN_SECONDS,
                        actionType = message.actionType,
                        triggeredByRemote = true,
                    )
                }
            }
            NearbyMessageType.START_ANALYSIS -> {
                diagnostics.recordEvent("主控开始分析：${message.actionType.displayName}", System.currentTimeMillis())
                runOnUiThread {
                    beginTrainingSession(message.actionType, triggeredByRemote = true)
                }
            }
            NearbyMessageType.PAUSE_ANALYSIS -> {
                diagnostics.recordEvent("主控暂停训练", System.currentTimeMillis())
                runOnUiThread {
                    setRecognitionPaused(paused = true, triggeredByRemote = true)
                }
            }
            NearbyMessageType.RESUME_ANALYSIS -> {
                diagnostics.recordEvent("主控继续训练", System.currentTimeMillis())
                runOnUiThread {
                    setRecognitionPaused(paused = false, triggeredByRemote = true)
                }
            }
            NearbyMessageType.HOST_ANALYSIS_STATUS -> {
                diagnostics.recordEvent("主控实时状态更新", System.currentTimeMillis())
                runOnUiThread {
                    applyHostAnalysisStatus(message)
                }
            }
            NearbyMessageType.ANALYSIS_SUMMARY -> {
                handleRemoteAnalysisSummary(endpointId, message)
            }
            NearbyMessageType.REP_RESULT -> {
                handleRemoteRepResult(endpointId, message)
            }
            NearbyMessageType.POSE_FRAME -> {
                handleRemotePoseFrameSnapshot(endpointId, message)
            }
            NearbyMessageType.ASSIGN_ROLE -> {
                updateCameraRole(message.role)
                updateControlAuthority(NearbyRoomSession.manager(this).currentState())
                diagnostics.recordEvent("分配机位：$currentCameraRole", System.currentTimeMillis())
                runOnUiThread {
                    showCameraStatus("已接收机位分配：${message.role.displayText()}，当前采集机位：${currentCameraRole.displayText()}")
                    renderTrainingControls()
                    renderDiagnostics()
                }
            }
            NearbyMessageType.END_TRAINING -> {
                diagnostics.recordEvent("主控结束训练", System.currentTimeMillis())
                runOnUiThread {
                    if (isRemoteControlledNode) {
                        stopRemoteControlledTraining(message)
                    } else {
                        showCameraStatus("收到主控端结束训练指令，正在保存本机识别记录。")
                        renderDiagnostics()
                        saveRecognizedTraining(triggeredByRemote = true)
                    }
                }
            }
            else -> Unit
        }
    }

    private fun applyHostAnalysisStatus(message: NearbyMessage) {
        if (!isRemoteControlledNode) return
        synchronized(recognitionLock) {
            if (message.actionType != ActionType.UNKNOWN) {
                latestActionType = message.actionType
            }
            latestCount = message.totalCount ?: latestCount
            latestHoldDurationMs = message.holdDurationMs ?: latestHoldDurationMs
            latestScore = message.score
            latestProblem = message.problemType
            latestSuggestion = message.suggestion
            lastHostAnalysisStatusAtMs = System.currentTimeMillis()
        }
        showCameraStatus(hostAnalysisStatusText(message))
        renderTrainingDashboard()
    }

    private fun handleRemoteAnalysisSummary(endpointId: String, message: NearbyMessage) {
        if (isRemoteControlledNode) return
        val aggregate = remoteSummaryAggregator.record(
            endpointId = endpointId,
            message = message,
            endpoints = NearbyRoomSession.manager(this).currentState().endpoints,
            expectedActionType = expectedActionType,
        )
        diagnostics.recordEvent(
            "收到副机分析摘要：${message.role.displayText()} ${message.actionType.displayName} ${message.problemType.displayName}",
            System.currentTimeMillis(),
        )
        if (aggregate.activeSummaryCount <= 0 || !aggregate.actionType.isTrainingAction) return
        latestRemoteAggregate = aggregate
        synchronized(recognitionLock) {
            if (expectedActionType == ActionType.UNKNOWN) {
                latestActionType = aggregate.actionType
            }
            if (aggregate.problemType != ProblemType.NONE) {
                latestProblem = aggregate.problemType
                latestSuggestion = aggregate.suggestion ?: latestSuggestion
            }
        }
    }

    private fun handleRemoteRepResult(endpointId: String, message: NearbyMessage) {
        if (isRemoteControlledNode) return
        val repIndex = message.repIndex ?: message.totalCount ?: return
        val problems = normalizedProblems(message.problemTypes, message.problemType)
        synchronized(recognitionLock) {
            remoteRepResults.removeAll { event ->
                event.endpointId == endpointId &&
                    event.actionType == message.actionType &&
                    event.repIndex == repIndex &&
                    event.role == message.role
            }
            remoteRepResults += RepResultEvent(
                endpointId = endpointId,
                role = message.role,
                actionType = message.actionType,
                repIndex = repIndex,
                problemTypes = problems,
                score = message.score,
                suggestion = message.suggestion,
            )
            if (message.actionType != ActionType.UNKNOWN) {
                latestActionType = message.actionType
            }
            if (problems.isNotEmpty()) {
                latestProblem = primaryRepProblem(problems)
                latestSuggestion = message.suggestion ?: latestSuggestion
            }
        }
        diagnostics.recordEvent(
            "收到副机单次结果：${message.role.displayText()} 第 $repIndex 次 ${problems.joinToString { it.displayName }.ifBlank { "正常" }}",
            System.currentTimeMillis(),
        )
    }

    private fun handleRemotePoseFrameSnapshot(endpointId: String, message: NearbyMessage) {
        if (isRemoteControlledNode) return
        val frame = NearbyPoseFrameCodec.decode(message.poseFrameJson)
        if (frame == null) {
            diagnostics.recordEvent("收到副机关键点样本但解析失败：$endpointId", System.currentTimeMillis())
            return
        }
        synchronized(recognitionLock) {
            remoteSessionFrames.addLast(frame)
            while (remoteSessionFrames.size > MAX_REMOTE_SESSION_FRAMES) {
                remoteSessionFrames.removeFirst()
            }
        }
        diagnostics.recordEvent(
            "缓存副机关键点样本：${frame.cameraRole.displayText()} ${frame.landmarks.size} 点",
            System.currentTimeMillis(),
        )
    }

    private fun recordLocalRepResult(
        actionType: ActionType,
        repIndex: Int,
        score: Float?,
        kneeAngle: Float?,
        trunkAngle: Float?,
        postureLevel: String?,
        problemTypes: List<ProblemType>,
        suggestion: String?,
    ) {
        val normalizedProblems = problemTypes.filter { problem -> problem != ProblemType.NONE }.distinct()
        synchronized(recognitionLock) {
            localRepResults.removeAll { event ->
                event.actionType == actionType &&
                    event.repIndex == repIndex &&
                    event.role == currentCameraRole
            }
            localRepResults += RepResultEvent(
                endpointId = "local",
                role = currentCameraRole,
                actionType = actionType,
                repIndex = repIndex,
                problemTypes = normalizedProblems,
                score = score,
                suggestion = suggestion,
            )
        }
        NearbyRoomSession.manager(this).sendRepResult(
            actionType = actionType,
            repIndex = repIndex,
            score = score,
            kneeAngle = kneeAngle,
            trunkAngle = trunkAngle,
            postureLevel = postureLevel,
            problemTypes = normalizedProblems,
            suggestion = suggestion,
        )
        diagnostics.recordEvent(
            "发送单次动作结果：${actionType.displayName} 第 $repIndex 次 ${normalizedProblems.joinToString { it.displayName }.ifBlank { "正常" }}",
            System.currentTimeMillis(),
        )
    }

    private fun hostAnalysisStatusText(message: NearbyMessage): String {
        val actionText = if (message.actionType == ActionType.UNKNOWN) "自动识别" else message.actionType.displayName
        val progressText = "${message.totalCount ?: 0} 次"
        val problemText = if (message.problemType == ProblemType.NONE) "姿态正常" else message.problemType.displayName
        val suggestionText = message.suggestion?.takeIf { it.isNotBlank() } ?: "保持全身入镜。"
        return "主控：$actionText，$progressText\n$problemText：$suggestionText"
    }

    private fun startTrainingCountdown(
        seconds: Int,
        actionType: ActionType,
        triggeredByRemote: Boolean,
    ) {
        if (trainingStarted) {
            val message = "训练已经开始，请结束并保存本轮记录后再重新开始。"
            if (!triggeredByRemote) {
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
            }
            showCameraStatus(message)
            return
        }
        if (!triggeredByRemote) {
            val blockReason = NearbyRoomSession.manager(this).startTrainingBlockReason()
            if (blockReason != null) {
                Toast.makeText(this, blockReason, Toast.LENGTH_LONG).show()
                showCameraStatus(blockReason)
                renderDiagnostics()
                return
            }
        }
        unsupportedTrainingReason(actionType)?.let { reason ->
            Toast.makeText(this, reason, Toast.LENGTH_LONG).show()
            showCameraStatus(reason)
            renderDiagnostics()
            return
        }
        val safeSeconds = seconds.coerceIn(1, 10)
        synchronized(recognitionLock) {
            expectedActionType = actionType
            latestActionType = if (actionType == ActionType.UNKNOWN) latestActionType else actionType
        }
        if (!triggeredByRemote) {
            broadcastStartCountdownIfNeeded(safeSeconds, actionType)
        }
        trainingCountdownTimer?.cancel()
        binding.startTrainingButton.isEnabled = false
        binding.startTrainingButton.visibility = View.GONE
        binding.activeControlRow.visibility = View.GONE
        binding.saveTrainingButton.isEnabled = false
        binding.trainingCountdownText.text = topTrainingStatusText(actionType, "准备开始")
        binding.countdownOverlay.visibility = View.VISIBLE
        binding.countdownNumberText.text = safeSeconds.toString()
        showCameraStatus(
            if (triggeredByRemote) {
                if (isRemoteControlledNode) {
                    "收到主控端同步倒计时：$safeSeconds 秒，倒计时后开始采集本机结果。"
                } else {
                    "收到主控端同步倒计时：$safeSeconds 秒，将识别${trainingModeText(actionType)}。"
                }
            } else {
                "训练准备开始：$safeSeconds 秒后识别${trainingModeText(actionType)}。"
            }
        )
        renderVideoButton()
        renderDiagnostics()
        trainingCountdownTimer = object : CountDownTimer(safeSeconds * 1000L, 1000L) {
            override fun onTick(millisUntilFinished: Long) {
                val left = ((millisUntilFinished / 1000L) + 1L).toInt()
                binding.trainingCountdownText.text = topTrainingStatusText(actionType, "倒计时")
                binding.countdownOverlay.visibility = View.VISIBLE
                binding.countdownNumberText.text = left.toString()
                showCameraStatus(
                    if (isRemoteControlledNode) {
                        "请保持全身入镜，$left 秒后开始采集本机结果。"
                    } else {
                        "请保持全身入镜，$left 秒后开始识别${trainingModeText(actionType)}。"
                    }
                )
            }

            override fun onFinish() {
                trainingCountdownTimer = null
                binding.countdownOverlay.visibility = View.GONE
                if (!triggeredByRemote) {
                    broadcastStartAnalysisIfNeeded(actionType)
                }
                beginTrainingSession(actionType, triggeredByRemote)
            }
        }.start()
    }

    private fun beginTrainingSession(actionType: ActionType, triggeredByRemote: Boolean) {
        if (trainingStarted) {
            binding.trainingCountdownText.text = topTrainingStatusText(actionType, "训练中")
            showCameraStatus(
                if (triggeredByRemote) {
                    "已收到主控端开始训练指令，当前训练已经在进行中。"
                } else {
                    "训练已经开始，请结束并保存本轮记录后再重新开始。"
                }
            )
            renderDiagnostics()
            return
        }
        trainingCountdownTimer?.cancel()
        trainingCountdownTimer = null
        binding.countdownOverlay.visibility = View.GONE
        resetRecognitionSession(actionType)
        trainingStarted = true
        isRecognitionPaused = false
        binding.startTrainingButton.isEnabled = false
        binding.saveTrainingButton.isEnabled = true
        binding.saveTrainingButton.contentDescription = "结束训练"
        binding.trainingCountdownText.text = topTrainingStatusText(actionType, "训练中")
        showCameraStatus(
            if (isRemoteControlledNode) {
                """
                主控端已开始训练。
                本机将按主控选择的${trainingModeText(actionType)}采集关键点并上传，结果以主控端为准。
                请保持全身入镜。
                $nearbyStatusText
                """.trimIndent()
            } else {
                """
                训练已开始。
                请完成${trainingModeText(actionType)}，本轮不会自动切换到其他动作。
                $nearbyStatusText
                """.trimIndent()
            }
        )
        diagnostics.recordEvent(
            if (triggeredByRemote) "主控触发训练开始" else "本机触发训练开始",
            System.currentTimeMillis(),
        )
        renderVideoButton()
        renderTrainingControls()
        renderPauseButton()
        renderDiagnostics()
        maybeStartVideoRecordingForTraining()
    }

    private fun maybeStartRemoteTrainingFromIntent() {
        if (!remoteAutoStartRequested) return
        remoteAutoStartRequested = false
        beginTrainingSession(expectedActionType, triggeredByRemote = true)
    }

    private fun resetRecognitionSession(actionType: ActionType) {
        val now = System.currentTimeMillis()
        isRecognitionPaused = false
        latestVideoFile = null
        pendingVideoSessionId = null
        synchronized(recognitionLock) {
            expectedActionType = actionType
            latestActionType = if (actionType == ActionType.UNKNOWN) ActionType.UNKNOWN else actionType
            remoteEndBroadcastForSession = false
            latestCount = 0
            latestHoldDurationMs = 0L
            latestScore = null
            latestProblem = ProblemType.NONE
            latestSuggestion = null
            latestPoseStageText = null
            latestPoseDetected = false
            lastHostAnalysisStatusAtMs = 0L
            lastHostDashboardStatusSentAtMs = 0L
            lastHostDashboardStatusSignature = ""
            bestActionRecognitionTracker.reset()
            actionProgressTracker.reset()
            remoteSummaryAggregator.reset()
            latestRemoteAggregate = null
            recentFrames.clear()
            sessionFrames.clear()
            remoteSessionFrames.clear()
            localRepResults.clear()
            remoteRepResults.clear()
            lastSummarySentAtMs = 0L
            lastPoseFrameSentAtMs = 0L
            lastFrameSavedAtMs = 0L
            lastLiveReplayFrameCount = 0
            startedAtMs = now
            ruleBasedActionClassifier = RuleBasedActionClassifier(analysisConfig)
            stableActionRecognizer.reset()
            squatAnalyzer = SimpleSquatAnalyzer(analysisConfig)
            jumpingJackAnalyzer = SimpleJumpingJackAnalyzer(analysisConfig)
            basicActionAnalyzer = SimpleBasicActionAnalyzer(analysisConfig)
        }
        diagnostics.onSessionFrameSaved(0, now)
        diagnostics.recordEvent("重置识别状态：${actionType.displayName}", now)
    }

    private fun updateCameraRole(localRole: DeviceRole) {
        val nextRole = CameraNodeRoleResolver.captureRole(localRole)
        if (nextRole != currentCameraRole) {
            currentCameraRole = nextRole
        }
    }

    private fun saveRecognizedTraining(triggeredByRemote: Boolean) {
        if (isSavingTraining) {
            diagnostics.recordEvent("忽略重复保存请求", System.currentTimeMillis())
            renderDiagnostics()
            return
        }
        if (!trainingStarted) {
            if (!triggeredByRemote) {
                Toast.makeText(this, "请先点击开始训练，倒计时结束后再保存训练结果", Toast.LENGTH_SHORT).show()
            }
            diagnostics.recordEvent("保存失败：训练尚未开始", System.currentTimeMillis())
            showCameraStatus("训练尚未开始，请先点击开始训练并完成动作。")
            renderDiagnostics()
            return
        }
        if (!triggeredByRemote) {
            broadcastEndTrainingIfNeeded(currentControlActionType())
        }
        if (pendingVideoSaveCoordinator.isPending()) {
            diagnostics.recordEvent("已等待视频结束后保存，忽略重复请求", System.currentTimeMillis())
            renderDiagnostics()
            return
        }
        if (activeRecording != null) {
            pendingVideoSaveCoordinator.request(triggeredByRemote)
            binding.saveTrainingButton.isEnabled = false
            showCameraStatus("正在结束视频录制，完成后自动保存训练记录...")
            diagnostics.recordEvent("等待视频结束后保存训练", System.currentTimeMillis())
            renderVideoButton()
            renderPauseButton()
            renderDiagnostics()
            stopVideoRecording()
            return
        }
        val liveState = synchronized(recognitionLock) {
            val bestRecognition = bestActionRecognitionTracker.best()
            val actionType = when {
                expectedActionType != ActionType.UNKNOWN -> expectedActionType
                bestRecognition.isConfirmed -> bestRecognition.actionType
                latestActionType != ActionType.UNKNOWN -> latestActionType
                bestRecognition.hasTrainingAction -> bestRecognition.actionType
                else -> ActionType.UNKNOWN
            }
            val actionProgress = actionProgressTracker.bestFor(actionType)
            SaveState(
                expectedActionType = expectedActionType,
                actionType = actionType,
                bestRecognition = bestRecognition,
                totalCount = actionProgress?.totalCount ?: latestCount,
                holdDurationMs = actionProgress?.holdDurationMs ?: latestHoldDurationMs,
                score = actionProgress?.score ?: latestScore,
                problemType = actionProgress?.problemType ?: latestProblem,
                suggestion = actionProgress?.suggestion ?: latestSuggestion,
                durationMs = System.currentTimeMillis() - startedAtMs,
                frames = sessionFrames.toList() + remoteSessionFrames.toList(),
                actionProblemTypes = buildActionProblemTypes(actionType, actionProgress?.totalCount ?: latestCount),
            )
        }
        val state = mergeRemoteAggregateForSave(resolveSaveState(liveState))
        if (state.frames.isEmpty()) {
            blockTrainingSave("本轮还没有采集到人体关键点样本，请保持全身入镜后再保存训练记录。")
            return
        }
        val validationError = TrainingSaveValidator.errorFor(
            actionType = state.actionType,
            totalCount = state.totalCount,
            holdDurationMs = state.holdDurationMs,
        )
        if (validationError != null) {
            blockTrainingSave(validationError)
            return
        }
        isSavingTraining = true
        binding.saveTrainingButton.isEnabled = false
        renderVideoButton()
        renderPauseButton()
        val summary = TrainingSummary(
            actionType = state.actionType,
            totalCount = state.totalCount,
            averageScore = if (state.actionType.supportsDetailedScore) state.score else null,
            durationMs = state.durationMs,
            mainProblem = state.problemType,
            suggestion = state.suggestion.withBestRecognitionText(state.actionType, state.bestRecognition),
            actionProblemTypes = state.actionProblemTypes,
        )
        val deviceSnapshots = nearbyDeviceSnapshots()
        AppExecutors.io.execute {
            val result = runCatching {
                TrainingRepository.saveRecognizedTraining(this, summary, state.frames, deviceSnapshots)
            }
            runOnUiThread {
                isSavingTraining = false
                renderVideoButton()
                renderPauseButton()
                result.onSuccess { sessionId ->
                    isSavingTraining = true
                    binding.saveTrainingButton.isEnabled = false
                    binding.saveTrainingButton.contentDescription = "已保存"
                    pendingVideoSessionId = sessionId
                    archiveLatestVideoForSession(sessionId)
                    diagnostics.recordEvent("训练记录保存成功：$sessionId", System.currentTimeMillis())
                    renderDiagnostics()
                    val toastText = if (triggeredByRemote) {
                        "已按主控结束指令保存本机识别记录"
                    } else {
                        "已保存本机识别训练记录"
                    }
                    Toast.makeText(this, toastText, Toast.LENGTH_SHORT).show()
                    openSavedTrainingDetail(sessionId)
                }.onFailure { error ->
                    binding.saveTrainingButton.isEnabled = true
                    diagnostics.recordEvent("训练记录保存失败：${error.message}", System.currentTimeMillis())
                    renderDiagnostics()
                    Toast.makeText(this, "真实识别记录保存失败：${error.message}", Toast.LENGTH_SHORT).show()
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
            isSavingTraining = false
            binding.saveTrainingButton.isEnabled = true
            binding.saveTrainingButton.contentDescription = "结束训练"
            renderVideoButton()
            renderPauseButton()
            diagnostics.recordEvent("打开训练详情失败：${error.message}", System.currentTimeMillis())
            renderDiagnostics()
            Toast.makeText(
                this,
                "训练已保存，但打开详情失败：${error.message ?: error.javaClass.simpleName}",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    private fun broadcastStartCountdownIfNeeded(seconds: Int, actionType: ActionType) {
        if (!canControlRemoteNodes()) return
        NearbyRoomSession.manager(this).sendStartCountdown(seconds, actionType)
        diagnostics.recordEvent("已通知加入手机同步倒计时", System.currentTimeMillis())
    }

    private fun broadcastStartAnalysisIfNeeded(actionType: ActionType) {
        if (!canControlRemoteNodes()) return
        NearbyRoomSession.manager(this).sendStartAnalysis(actionType)
        diagnostics.recordEvent("已通知加入手机开始训练", System.currentTimeMillis())
    }

    private fun broadcastPauseOrResumeIfNeeded(paused: Boolean) {
        if (!canControlRemoteNodes()) return
        val actionType = currentControlActionType()
        if (paused) {
            NearbyRoomSession.manager(this).sendPauseAnalysis(actionType)
        } else {
            NearbyRoomSession.manager(this).sendResumeAnalysis(actionType)
        }
        diagnostics.recordEvent(
            if (paused) "已通知加入手机暂停训练" else "已通知加入手机继续训练",
            System.currentTimeMillis(),
        )
    }

    private fun broadcastEndTrainingIfNeeded(actionType: ActionType) {
        if (remoteEndBroadcastForSession || !canControlRemoteNodes()) return
        remoteEndBroadcastForSession = true
        val progress = synchronized(recognitionLock) {
            actionProgressTracker.bestFor(actionType)
        }
        NearbyRoomSession.manager(this).sendEndTraining(
            actionType = actionType,
            totalCount = progress?.totalCount ?: latestCount,
            holdDurationMs = progress?.holdDurationMs ?: latestHoldDurationMs,
            score = progress?.score ?: latestScore,
            problemType = progress?.problemType ?: latestProblem,
            suggestion = progress?.suggestion ?: latestSuggestion,
        )
        diagnostics.recordEvent("已通知加入手机结束训练", System.currentTimeMillis())
    }

    private fun canControlRemoteNodes(): Boolean {
        val state = NearbyRoomSession.manager(this).currentState()
        return state.isHostSession && state.endpoints.any { endpoint -> endpoint.isOnline }
    }

    private fun maybeSendCaptureReadyStatus() {
        if (trainingStarted || boundCamera == null || !isRemoteControlledNode) return
        val state = NearbyRoomSession.manager(this).currentState()
        if (state.mode == NearbyConnectionMode.IDLE || state.isHostSession) return
        val actionType = expectedActionType
        val signature = "${state.localRole.name}:${actionType.name}"
        if (signature == lastCaptureReadySignature) return
        lastCaptureReadySignature = signature
        NearbyRoomSession.manager(this).sendNodeCaptureReady(actionType)
        diagnostics.recordEvent("已上报副机采集就绪", System.currentTimeMillis())
    }

    private fun currentControlActionType(): ActionType =
        synchronized(recognitionLock) {
            val bestRecognition = bestActionRecognitionTracker.best()
            when {
                expectedActionType != ActionType.UNKNOWN -> expectedActionType
                bestRecognition.isConfirmed -> bestRecognition.actionType
                latestActionType != ActionType.UNKNOWN -> latestActionType
                bestRecognition.hasTrainingAction -> bestRecognition.actionType
                else -> ActionType.UNKNOWN
            }
        }

    private fun stopRemoteControlledTraining(message: NearbyMessage) {
        val actionType = message.actionType
        val now = System.currentTimeMillis()
        trainingCountdownTimer?.cancel()
        trainingCountdownTimer = null
        trainingStarted = false
        isRecognitionPaused = false
        binding.startTrainingButton.isEnabled = false
        binding.saveTrainingButton.isEnabled = false
        if (activeRecording != null) {
            stopVideoRecording()
        }
        synchronized(recognitionLock) {
            if (message.actionType != ActionType.UNKNOWN) {
                latestActionType = message.actionType
            }
            latestCount = message.totalCount ?: latestCount
            latestHoldDurationMs = message.holdDurationMs ?: latestHoldDurationMs
            latestScore = message.score ?: latestScore
            latestProblem = message.problemType
            latestSuggestion = message.suggestion ?: latestSuggestion
            lastHostAnalysisStatusAtMs = now
        }
        binding.trainingCountdownText.text = topTrainingStatusText(actionType, "已结束")
        showCameraStatus(
            """
            主控端已结束本轮训练：${trainingModeText(actionType)}。
            主控结果已同步：${message.totalCount ?: latestCount} 次。
            """.trimIndent()
        )
        diagnostics.recordEvent("主控结束远程节点训练，副机停止采集并显示主控结果", now)
        renderVideoButton()
        renderPauseButton()
        renderTrainingControls()
        renderTrainingDashboard()
        renderDiagnostics()
    }

    private fun mergeRemoteAggregateForSave(state: SaveState): SaveState {
        val nearbyState = latestNearbyState
        if (!nearbyState.isHostSession || nearbyState.endpoints.none { endpoint -> endpoint.isOnline }) {
            return state
        }
        val freshAggregate = remoteSummaryAggregator.calculate(
            endpoints = nearbyState.endpoints,
            expectedActionType = state.actionType.takeIf { it != ActionType.UNKNOWN } ?: expectedActionType,
        )
        val aggregate = freshAggregate.takeIf { it.activeSummaryCount > 0 }
            ?: latestRemoteAggregate
            ?: return state
        if (aggregate.activeSummaryCount <= 0 || !aggregate.actionType.isTrainingAction) return state
        val actionType = when {
            state.actionType != ActionType.UNKNOWN -> state.actionType
            else -> aggregate.actionType
        }
        if (aggregate.actionType != ActionType.UNKNOWN && actionType != aggregate.actionType) return state
        val remoteHasProblem = aggregate.problemType != ProblemType.NONE
        val merged = state.copy(
            actionType = actionType,
            totalCount = if (actionType.isCountBased) {
                maxOf(state.totalCount, aggregate.totalCount)
            } else {
                state.totalCount
            },
            holdDurationMs = if (actionType.isHoldBased) {
                maxOf(state.holdDurationMs, aggregate.holdDurationMs)
            } else {
                state.holdDurationMs
            },
            score = if (remoteHasProblem) aggregate.score ?: state.score else state.score,
            problemType = if (remoteHasProblem) aggregate.problemType else state.problemType,
            suggestion = if (remoteHasProblem) aggregate.suggestion ?: state.suggestion else state.suggestion,
            actionProblemTypes = buildActionProblemTypes(actionType, maxOf(state.totalCount, aggregate.totalCount)),
        )
        if (remoteHasProblem) {
            diagnostics.recordEvent(
                "保存采用副机融合问题：${aggregate.problemType.displayName}，摘要 ${aggregate.activeSummaryCount} 个",
                System.currentTimeMillis(),
            )
        }
        return merged
    }

    private fun buildActionProblemTypes(actionType: ActionType, totalCount: Int): List<String> {
        if (totalCount <= 0 || actionType != ActionType.SQUAT) return emptyList()
        val events = (localRepResults + remoteRepResults)
            .filter { event -> event.actionType == actionType }
        if (events.isEmpty()) return emptyList()
        return (1..totalCount).map { repIndex ->
            val problems = events
                .filter { event -> event.repIndex == repIndex }
                .flatMap { event -> event.problemTypes }
                .filter { problem -> problem != ProblemType.NONE }
                .distinctBy { problem -> problem.name }
            problems.joinToString(separator = "|") { problem -> problem.name }
        }
    }

    private fun normalizedProblems(
        problemTypes: List<ProblemType>,
        fallback: ProblemType,
    ): List<ProblemType> =
        (problemTypes.takeIf { problems -> problems.isNotEmpty() } ?: listOf(fallback))
            .filter { problem -> problem != ProblemType.NONE }
            .distinctBy { problem -> problem.name }

    private fun primaryRepProblem(problems: List<ProblemType>): ProblemType =
        listOf(
            ProblemType.KNEE_INWARD,
            ProblemType.BACK_LEAN_TOO_MUCH,
            ProblemType.SQUAT_DEPTH_NOT_ENOUGH,
            ProblemType.RHYTHM_ABNORMAL,
            ProblemType.LOW_CONFIDENCE,
        ).firstOrNull { problem -> problem in problems } ?: ProblemType.NONE

    private fun resolveSaveState(liveState: SaveState): SaveState {
        if (liveState.frames.isEmpty()) return liveState
        val liveError = liveState.validationError()
        val replayState = replaySaveStateFromFrames(liveState)
        val replayError = replayState.validationError()
        if (replayError == null && (liveError != null || replayState.isAtLeastAsCompleteAs(liveState))) {
            diagnostics.recordEvent(
                "保存前基于关键点样本重算成功：${replayState.actionType.displayName}，${replayState.totalCount} 次",
                System.currentTimeMillis(),
            )
            return replayState
        }
        if (liveError == null) return liveState
        val attemptState = promoteRecognizedSquatAttempt(replayState, liveState)
        if (attemptState.validationError() == null) {
            diagnostics.recordEvent(
                "保存前识别到深蹲尝试，按 1 次待复盘记录保存",
                System.currentTimeMillis(),
            )
            return attemptState
        }
        val bestActionState = promoteBestRecognizedActionAttempt(attemptState)
        if (bestActionState.validationError() == null) {
            diagnostics.recordEvent(
                "保存前采用最高置信度动作：${bestActionState.actionType.displayName}",
                System.currentTimeMillis(),
            )
            return bestActionState
        }
        return if (liveState.actionType == ActionType.UNKNOWN && replayState.actionType != ActionType.UNKNOWN) {
            replayState
        } else {
            liveState
        }
    }

    private fun SaveState.isAtLeastAsCompleteAs(other: SaveState): Boolean {
        if (actionType != other.actionType) return true
        return when {
            actionType.isCountBased -> totalCount >= other.totalCount
            actionType.isHoldBased -> holdDurationMs >= other.holdDurationMs
            else -> true
        }
    }

    private fun promoteBestRecognizedActionAttempt(state: SaveState): SaveState {
        val bestRecognition = state.bestRecognition
        val actionType = when {
            state.actionType.isTrainingAction -> state.actionType
            bestRecognition.hasTrainingAction -> bestRecognition.actionType
            else -> return state
        }
        if (state.frames.isEmpty()) return state
        if (actionType.isHoldBased) {
            val safeHoldDurationMs = maxOf(state.holdDurationMs, MIN_SAVE_HOLD_DURATION_MS)
            val fixedCount = 0
            return state.copy(
                actionType = actionType,
                totalCount = fixedCount,
                holdDurationMs = safeHoldDurationMs,
                score = null,
                problemType = state.problemType,
                suggestion = bestRecognitionSaveSuggestion(actionType, bestRecognition.confidence, isHoldBased = true),
            )
        }
        if (state.totalCount > 0) {
            return state.copy(actionType = actionType)
        }
        if (actionType == ActionType.LATERAL_RAISE) {
            return state.copy(
                actionType = actionType,
                totalCount = 0,
                holdDurationMs = 0L,
                score = null,
                problemType = ProblemType.RHYTHM_ABNORMAL,
                suggestion = "已识别到侧平举抬臂，但没有捕捉到放回身体两侧；本次不计次数，建议完整抬起并放下后结束。",
            )
        }
        if (actionType == ActionType.STANDING_FORWARD_BEND) {
            return state.copy(
                actionType = actionType,
                totalCount = 0,
                holdDurationMs = 0L,
                score = null,
                problemType = ProblemType.RHYTHM_ABNORMAL,
                suggestion = "已识别到站姿体前屈下探，但没有捕捉到站直回正；本次不计次数，建议完整俯身并站直后结束。",
            )
        }
        return state.copy(
            actionType = actionType,
            totalCount = 1,
            holdDurationMs = 0L,
            score = if (actionType.supportsDetailedScore) {
                SquatScorePolicy.reportableScore(ProblemType.RHYTHM_ABNORMAL, 95f)
            } else {
                null
            },
            problemType = ProblemType.RHYTHM_ABNORMAL,
            suggestion = bestRecognitionSaveSuggestion(actionType, bestRecognition.confidence, isHoldBased = false),
        )
    }

    private fun bestRecognitionSaveSuggestion(
        actionType: ActionType,
        confidence: Float,
        isHoldBased: Boolean,
    ): String {
        val confidenceText = "${(confidence * 100f).toInt().coerceIn(0, 100)}%"
        return if (isHoldBased) {
            "已根据训练过程中最高置信度识别到的${actionType.displayName}保存（置信度约 $confidenceText）；本次按有效保持记录进入复盘。"
        } else {
            "已根据训练过程中最高置信度识别到的${actionType.displayName}保存（置信度约 $confidenceText）；本次按 1 次待复盘记录保存，建议下次完整完成动作后结束。"
        }
    }

    private fun String?.withBestRecognitionText(
        actionType: ActionType,
        bestRecognition: BestActionRecognition,
    ): String? {
        val base = this.orEmpty()
        if (
            !bestRecognition.hasTrainingAction ||
            bestRecognition.actionType != actionType ||
            base.contains("最高置信度")
        ) {
            return this
        }
        val confidenceText = "${(bestRecognition.confidence * 100f).toInt().coerceIn(0, 100)}%"
        val prefix = "识别动作：${actionType.displayName}（训练中最高动作置信度约 $confidenceText）。"
        return prefix + base.ifBlank { "请在训练详情中复盘动作表现。" }
    }

    private fun replaySaveStateFromFrames(liveState: SaveState): SaveState {
        val orderedFrames = liveState.frames.sortedBy { frame -> frame.timestampMs }
        if (orderedFrames.isEmpty()) return liveState
        val replayClassifier = RuleBasedActionClassifier(analysisConfig)
        val replayStableRecognizer = StableActionRecognizer()
        val replaySquatAnalyzer = SimpleSquatAnalyzer(analysisConfig)
        val replayJumpingJackAnalyzer = SimpleJumpingJackAnalyzer(analysisConfig)
        val replayBasicAnalyzer = SimpleBasicActionAnalyzer(analysisConfig)
        val replayProgressTracker = ActionProgressTracker()
        val replayWindow = ArrayDeque<PoseFrame>()
        val forcedActionType = when {
            liveState.expectedActionType != ActionType.UNKNOWN -> liveState.expectedActionType
            liveState.actionType != ActionType.UNKNOWN -> liveState.actionType
            else -> ActionType.UNKNOWN
        }
        var actionType = ActionType.UNKNOWN
        var totalCount = 0
        var holdDurationMs = 0L
        var score: Float? = null
        var problemType = ProblemType.NONE
        var suggestion: String? = null
        orderedFrames.forEach { frame ->
            replayWindow.addLast(frame)
            while (replayWindow.size > CLASSIFIER_WINDOW_SIZE) {
                replayWindow.removeFirst()
            }
            val windowFrames = replayWindow.toList()
            val classifiedActionType = if (forcedActionType != ActionType.UNKNOWN) {
                forcedActionType
            } else {
                replayStableRecognizer
                    .observe(replayClassifier.classify(windowFrames))
                    .result
                    .actionType
            }
            val frameActionType = if (classifiedActionType != ActionType.UNKNOWN) {
                classifiedActionType
            } else {
                actionType
            }
            if (frameActionType != ActionType.UNKNOWN) {
                actionType = frameActionType
            }
            when (frameActionType) {
                ActionType.SQUAT -> {
                    val result = replaySquatAnalyzer.analyze(frame)
                    val progress = replayProgressTracker.record(
                        actionType = ActionType.SQUAT,
                        totalCount = result.totalCount,
                        holdDurationMs = 0L,
                        score = SquatScorePolicy.reportableScore(result.problemType, result.score),
                        problemType = result.problemType,
                        suggestion = result.suggestion,
                    )
                    totalCount = progress.totalCount
                    holdDurationMs = progress.holdDurationMs
                    score = progress.score
                    problemType = progress.problemType
                    suggestion = progress.suggestion
                }
                ActionType.JUMPING_JACK -> {
                    val result = replayJumpingJackAnalyzer.analyze(frame)
                    val replayProblem = if (result.lostFrameCount > 0) ProblemType.LOW_CONFIDENCE else ProblemType.NONE
                    val replaySuggestion = if (result.lostFrameCount > 0) {
                        "请保持全身入镜，避免丢失关键点。"
                    } else {
                        "保持稳定节奏。"
                    }
                    val progress = replayProgressTracker.record(
                        actionType = ActionType.JUMPING_JACK,
                        totalCount = result.totalCount,
                        holdDurationMs = 0L,
                        score = null,
                        problemType = replayProblem,
                        suggestion = replaySuggestion,
                    )
                    totalCount = progress.totalCount
                    holdDurationMs = progress.holdDurationMs
                    score = progress.score
                    problemType = progress.problemType
                    suggestion = progress.suggestion
                }
                in ActionType.trainingActions -> {
                    val result = replayBasicAnalyzer.analyze(frameActionType, frame)
                    val progress = replayProgressTracker.record(
                        actionType = frameActionType,
                        totalCount = result.totalCount,
                        holdDurationMs = result.holdDurationMs,
                        score = null,
                        problemType = result.problemType,
                        suggestion = result.suggestion,
                    )
                    totalCount = progress.totalCount
                    holdDurationMs = progress.holdDurationMs
                    score = progress.score
                    problemType = progress.problemType
                    suggestion = progress.suggestion
                }
                else -> Unit
            }
        }
        val selectedProgress = replayProgressTracker.bestFor(actionType)
        return liveState.copy(
            actionType = actionType,
            totalCount = selectedProgress?.totalCount ?: totalCount,
            holdDurationMs = selectedProgress?.holdDurationMs ?: holdDurationMs,
            score = selectedProgress?.score ?: score,
            problemType = selectedProgress?.problemType ?: problemType,
            suggestion = selectedProgress?.suggestion ?: suggestion,
        )
    }

    private fun promoteRecognizedSquatAttempt(
        replayState: SaveState,
        liveState: SaveState,
    ): SaveState {
        val baseState = when {
            replayState.actionType != ActionType.UNKNOWN -> replayState
            liveState.actionType != ActionType.UNKNOWN -> liveState
            else -> replayState
        }
        if (
            baseState.actionType != ActionType.SQUAT ||
            baseState.totalCount > 0 ||
            !hasSaveableSquatAttempt(baseState.frames)
        ) {
            return baseState
        }
        return baseState.copy(
            totalCount = 1,
            holdDurationMs = 0L,
            problemType = ProblemType.RHYTHM_ABNORMAL,
            score = SquatScorePolicy.reportableScore(ProblemType.RHYTHM_ABNORMAL, 95f),
            suggestion = "已识别到深蹲动作，但没有捕捉到完整回到站立；本次按 1 次深蹲尝试保存，建议下次从站立开始并完整站起。",
        )
    }

    private fun hasSaveableSquatAttempt(frames: List<PoseFrame>): Boolean =
        frames.any { frame ->
            PoseActionRules.features(frame, analysisConfig).isSquatSignal()
        }

    private fun blockTrainingSave(reason: String) {
        isSavingTraining = false
        binding.saveTrainingButton.isEnabled = trainingStarted && !isRemoteControlledNode
        renderVideoButton()
        renderPauseButton()
        diagnostics.recordEvent("保存受阻：$reason", System.currentTimeMillis())
        showCameraStatus(
            """
            $reason
            当前训练仍保留，请继续完成动作后再次点击保存。
            """.trimIndent()
        )
        Toast.makeText(this, reason, Toast.LENGTH_LONG).show()
        renderDiagnostics()
    }

    private fun archiveLatestVideoForSession(sessionId: Long) {
        val source = latestVideoFile?.takeIf { it.exists() } ?: return
        val outputDir = TrainingFileLayout.videosDir(filesDir, sessionId).apply { mkdirs() }
        if (source.parentFile?.absolutePath == outputDir.absolutePath) return
        val target = File(outputDir, "training_${sessionId}_${source.name}")
        runCatching {
            source.copyTo(target, overwrite = true)
            source.delete()
            latestVideoFile = target
            diagnostics.recordEvent("视频素材已归档：${target.name}", System.currentTimeMillis())
        }.onFailure { error ->
            diagnostics.recordEvent("视频素材归档失败：${error.message}", System.currentTimeMillis())
        }
    }

    // 诊断快照同步到界面
    private fun renderDiagnostics(nowMs: Long = System.currentTimeMillis()) {
        if (::binding.isInitialized) {
            diagnostics.snapshot(nowMs)
            renderTrainingDashboard()
        }
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

    private fun trainingModeText(actionType: ActionType): String =
        if (actionType == ActionType.UNKNOWN) "自动识别${ActionType.trainingActionNamesText()}" else actionType.displayName

    private fun unsupportedTrainingReason(actionType: ActionType): String? {
        if (actionType == ActionType.SQUAT && currentCameraRole !in PRIMARY_SQUAT_ROLES) {
            return "深蹲训练需要先选择正面机位或侧面机位。"
        }
        if (actionType != ActionType.JUMPING_JACK) return null
        val state = latestNearbyState
        val isSingleFront = state.mode == NearbyConnectionMode.IDLE &&
            currentCameraRole == DeviceRole.FRONT_CAMERA
        return if (isSingleFront) {
            null
        } else {
            "开合跳只支持单机正面机位训练，请退出多机位并切换为正面机位。"
        }
    }

    private fun DeviceRole.displayText(): String =
        when (this) {
            DeviceRole.HOST -> "主控端"
            DeviceRole.FRONT_CAMERA -> "正面机位"
            DeviceRole.SIDE_CAMERA -> "侧面机位"
            DeviceRole.BACKUP_CAMERA -> "备用机位"
            DeviceRole.UNKNOWN -> "未分配"
        }

    // 页面销毁释放识别资源
    override fun onDestroy() {
        trainingCountdownTimer?.cancel()
        activeRecording?.stop()
        activeRecording = null
        poseDetectorAdapter.close()
        cameraExecutor.shutdown()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_REMOTE_AUTO_START = "extra_remote_auto_start"
        private const val MIN_SAVE_HOLD_DURATION_MS = 1_000L
        private const val CLASSIFIER_WINDOW_SIZE = 12
        private const val SUMMARY_SEND_INTERVAL_MS = 800L
        private const val POSE_FRAME_SEND_INTERVAL_MS = 2_000L
        private const val HOST_DASHBOARD_STATUS_SEND_INTERVAL_MS = 500L
        private const val SESSION_FRAME_INTERVAL_MS = 250L
        private const val MAX_SESSION_FRAMES = 240
        private const val MAX_REMOTE_SESSION_FRAMES = 180
        private const val RGB_FRAME_JPEG_QUALITY = 78
        private const val DEFAULT_COUNTDOWN_SECONDS = 3
        private val PRIMARY_SQUAT_ROLES = setOf(DeviceRole.FRONT_CAMERA, DeviceRole.SIDE_CAMERA)
    }

    private data class SaveState(
        val expectedActionType: ActionType,
        val actionType: ActionType,
        val bestRecognition: BestActionRecognition,
        val totalCount: Int,
        val holdDurationMs: Long,
        val score: Float?,
        val problemType: ProblemType,
        val suggestion: String?,
        val durationMs: Long,
        val frames: List<PoseFrame>,
        val actionProblemTypes: List<String>,
    ) {
        fun validationError(): String? =
            TrainingSaveValidator.errorFor(
                actionType = actionType,
                totalCount = totalCount,
                holdDurationMs = holdDurationMs,
            )
    }

    private data class DashboardState(
        val actionType: ActionType,
        val totalCount: Int,
        val holdDurationMs: Long,
        val score: Float?,
        val problemType: ProblemType,
        val suggestion: String?,
        val poseStageText: String?,
    )

    private data class RepResultEvent(
        val endpointId: String,
        val role: DeviceRole,
        val actionType: ActionType,
        val repIndex: Int,
        val problemTypes: List<ProblemType>,
        val score: Float?,
        val suggestion: String?,
    )
}
