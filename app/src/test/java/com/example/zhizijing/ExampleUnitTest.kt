package com.example.zhizijing

import android.Manifest
import android.os.Build
import com.example.zhizijing.data.datastore.AppSettings
import com.example.zhizijing.data.datastore.toPoseAnalysisConfig
import com.example.zhizijing.domain.model.ActionType
import com.example.zhizijing.domain.model.ActionClassificationResult
import com.example.zhizijing.domain.model.ActionProgressTracker
import com.example.zhizijing.domain.model.ClassifierSource
import com.example.zhizijing.domain.model.DeviceRole
import com.example.zhizijing.domain.model.ProblemType
import com.example.zhizijing.domain.model.SquatStage
import com.example.zhizijing.domain.model.TrainingSaveValidator
import com.example.zhizijing.domain.model.TrainingSummary
import com.example.zhizijing.data.entity.ActionResultEntity
import com.example.zhizijing.data.entity.DeviceNodeEntity
import com.example.zhizijing.data.entity.ExportRecordEntity
import com.example.zhizijing.data.entity.PoseFrameEntity
import com.example.zhizijing.data.repository.ExportType
import com.example.zhizijing.data.repository.TrainingDeviceSnapshot
import com.example.zhizijing.data.repository.TrainingKeyFrameSelector
import com.example.zhizijing.data.repository.TrainingPoseMetricsExtractor
import com.example.zhizijing.data.repository.TrainingRecognitionConfidenceCalculator
import com.example.zhizijing.data.repository.TrainingRecordMapper
import com.example.zhizijing.data.repository.persistsAsSessionReportPath
import com.example.zhizijing.diagnostics.CameraNodeDiagnostics
import com.example.zhizijing.domain.rule.PoseAnalysisConfig
import com.example.zhizijing.domain.rule.SimpleBasicActionAnalyzer
import com.example.zhizijing.domain.rule.SimpleJumpingJackAnalyzer
import com.example.zhizijing.domain.rule.SimpleSquatAnalyzer
import com.example.zhizijing.domain.rule.SquatScorePolicy
import com.example.zhizijing.nearby.message.GsonNearbyMessageCodec
import com.example.zhizijing.nearby.message.NearbyMessage
import com.example.zhizijing.nearby.message.NearbyMessageType
import com.example.zhizijing.nearby.message.NearbyPoseFrameCodec
import com.example.zhizijing.nearby.connection.NearbyPermissions
import com.example.zhizijing.nearby.connection.NearbyMessageDiagnostics
import com.example.zhizijing.nearby.connection.NearbyEndpoint
import com.example.zhizijing.pose.detector.poseImageCoordinateSize
import com.example.zhizijing.pose.classifier.BestActionRecognitionTracker
import com.example.zhizijing.pose.classifier.FrontViewPoseQualityGate
import com.example.zhizijing.pose.classifier.RuleBasedActionClassifier
import com.example.zhizijing.pose.classifier.StableActionRecognizer
import com.example.zhizijing.pose.feature.PoseActionRules
import com.example.zhizijing.pose.feature.PoseMath
import com.example.zhizijing.pose.model.LandmarkPoint
import com.example.zhizijing.pose.model.PoseFrame
import com.example.zhizijing.pose.overlay.PoseOverlayCoordinateMapper
import com.example.zhizijing.report.json.JsonTrainingReportExporter
import com.example.zhizijing.report.json.RawPoseSampleJsonExporter
import com.example.zhizijing.report.ReportShareHelper
import com.example.zhizijing.report.ReportOutputDirectory
import com.example.zhizijing.report.TrainingFileLayout
import com.example.zhizijing.report.TrainingArtifactCleaner
import com.example.zhizijing.report.ReportExportSelector
import com.example.zhizijing.report.TrainingReportFormatter
import com.example.zhizijing.report.TrainingReportStats
import com.example.zhizijing.report.TrainingReportStatsCalculator
import com.example.zhizijing.report.TrainingVideoArtifacts
import com.example.zhizijing.report.frame.KeyFrameStore
import com.example.zhizijing.ui.analysis.ActionAnalysisSavePolicy
import com.example.zhizijing.ui.analysis.RealtimeAnalysisFormatter
import com.example.zhizijing.ui.analysis.RemoteAnalysisSummaryAggregator
import com.example.zhizijing.ui.analysis.RemotePoseFrameBuffer
import com.example.zhizijing.ui.camera.CameraNodeRoleResolver
import com.example.zhizijing.ui.camera.PendingVideoSaveCoordinator
import com.example.zhizijing.ui.device.DeviceRoleAssignmentPolicy
import com.example.zhizijing.ui.history.HistoryFormatter
import com.example.zhizijing.ui.main.MainDashboardFormatter
import com.example.zhizijing.ui.main.ProjectStatus
import com.example.zhizijing.ui.recognition.ActionRecognitionStatusFormatter
import com.example.zhizijing.ui.result.TrainingDetailFormatter
import com.example.zhizijing.ui.result.ResultFallbackFormatter
import com.example.zhizijing.ui.room.RoomCodeFormatter
import com.example.zhizijing.ui.room.RoomCodeParser
import com.example.zhizijing.ui.room.RoomQrCodeEncoder
import com.example.zhizijing.ui.room.RoomQrScanResultParser
import com.example.zhizijing.ui.settings.SettingsValidator
import com.example.zhizijing.utils.PasswordHasher
import com.google.gson.JsonParser
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExampleUnitTest {
    @Test
    fun projectStatusExposesAppTitle() {
        assertEquals("智姿镜", ProjectStatus.appTitle)
        assertTrue(ProjectStatus.summary.contains("日常运动训练"))
        assertTrue(ProjectStatus.summary.contains("训练记录"))
        assertTrue(ProjectStatus.summary.contains("动作识别"))
        assertTrue(ProjectStatus.summary.contains("其他手机协同"))
        assertTrue(ProjectStatus.summary.contains("训练报告"))
        assertTrue(!ProjectStatus.summary.contains("Room"))
        assertTrue(!ProjectStatus.summary.contains("DataStore"))
        assertTrue(!ProjectStatus.summary.contains("CameraX"))
        assertTrue(!ProjectStatus.summary.contains("ML Kit"))
        assertTrue(!ProjectStatus.summary.contains("Fake"))
    }

    @Test
    fun backupRulesExcludeLocalTrainingArtifacts() {
        val dataExtractionRules = readMainXml("data_extraction_rules.xml")
        val backupRules = readMainXml("backup_rules.xml")

        listOf(dataExtractionRules, backupRules).forEach { xml ->
            assertTrue(!xml.contains("TODO"))
            assertTrue(xml.contains("domain=\"database\" path=\"zhizijing.db\""))
            assertTrue(xml.contains("domain=\"file\" path=\"datastore/zhizijing_settings.preferences_pb\""))
            assertTrue(xml.contains("domain=\"file\" path=\"training/\""))
            assertTrue(xml.contains("domain=\"file\" path=\"exports/\""))
        }
    }

    @Test
    fun actionTypeCanParseJumpingJack() {
        assertEquals(ActionType.JUMPING_JACK, ActionType.fromName("JUMPING_JACK"))
    }

    @Test
    fun actionTypeDefinesElevenTrainingActionsWithChineseNames() {
        val expected = listOf(
            ActionType.SQUAT,
            ActionType.JUMPING_JACK,
            ActionType.PUSH_UP,
            ActionType.SIT_UP,
            ActionType.LUNGE,
            ActionType.HIGH_KNEES,
            ActionType.PLANK,
            ActionType.LATERAL_RAISE,
            ActionType.MOUNTAIN_CLIMBER,
            ActionType.JUMP_ROPE,
            ActionType.STANDING_FORWARD_BEND,
        )

        assertEquals(expected, ActionType.trainingActions)
        assertEquals(11, ActionType.trainingActions.size)
        assertTrue(ActionType.trainingActions.all { it.displayName.isNotBlank() && it.displayName != it.name })
        assertTrue(ActionType.trainingActions.filter { it.isHoldBased }.containsAll(listOf(ActionType.PLANK)))
        assertTrue(ActionType.trainingActions.filter { it.isCountBased }.containsAll(expected - listOf(ActionType.PLANK)))
        assertTrue(ActionType.SQUAT.supportsDetailedScore)
    }

    @Test
    fun actionTypeCanRepresentAutomaticRecognitionMode() {
        assertEquals(ActionType.UNKNOWN, ActionType.fromNameOrUnknown(null))
        assertEquals(ActionType.UNKNOWN, ActionType.fromNameOrUnknown("UNKNOWN"))
        assertEquals(ActionType.UNKNOWN, ActionType.fromName("not-a-real-action"))
    }

    @Test
    fun trainingSaveValidatorRejectsUnknownOrZeroCountTraining() {
        assertTrue(TrainingSaveValidator.errorFor(ActionType.UNKNOWN, 0).orEmpty().contains("识别"))
        assertTrue(TrainingSaveValidator.errorFor(ActionType.STANDING, 3).orEmpty().contains("识别"))
        assertTrue(TrainingSaveValidator.errorFor(ActionType.SQUAT, 0).orEmpty().contains("至少 1 次"))
        assertTrue(TrainingSaveValidator.errorFor(ActionType.JUMPING_JACK, 0).orEmpty().contains("至少 1 次"))
        assertTrue(TrainingSaveValidator.errorFor(ActionType.PUSH_UP, 0).orEmpty().contains("至少 1 次"))
        assertTrue(TrainingSaveValidator.errorFor(ActionType.STANDING_FORWARD_BEND, 0).orEmpty().contains("至少 1 次"))
        assertTrue(TrainingSaveValidator.errorFor(ActionType.PLANK, 0, holdDurationMs = 500L).orEmpty().contains("保持"))
        assertEquals(null, TrainingSaveValidator.errorFor(ActionType.SQUAT, 1))
        assertEquals(null, TrainingSaveValidator.errorFor(ActionType.JUMPING_JACK, 1))
        assertEquals(null, TrainingSaveValidator.errorFor(ActionType.STANDING_FORWARD_BEND, 1))
        assertEquals(null, TrainingSaveValidator.errorFor(ActionType.PLANK, 0, holdDurationMs = 2_000L))
    }

    @Test
    fun actionProgressTrackerKeepsMaximumProgressPerAction() {
        val tracker = ActionProgressTracker()

        tracker.record(ActionType.SQUAT, totalCount = 5, holdDurationMs = 0L, score = 88f, problemType = ProblemType.NONE, suggestion = "已完成")
        tracker.record(ActionType.SQUAT, totalCount = 1, holdDurationMs = 0L, score = 95f, problemType = ProblemType.RHYTHM_ABNORMAL, suggestion = "较小计数")
        tracker.record(ActionType.JUMPING_JACK, totalCount = 8, holdDurationMs = 0L, score = null, problemType = ProblemType.NONE, suggestion = "开合跳")
        tracker.record(ActionType.JUMPING_JACK, totalCount = 0, holdDurationMs = 0L, score = null, problemType = ProblemType.NONE, suggestion = "回落")
        tracker.record(ActionType.PLANK, totalCount = 0, holdDurationMs = 4_000L, score = null, problemType = ProblemType.NONE, suggestion = "保持")
        tracker.record(ActionType.PLANK, totalCount = 0, holdDurationMs = 500L, score = null, problemType = ProblemType.NONE, suggestion = "较短保持")
        tracker.record(ActionType.STANDING_FORWARD_BEND, totalCount = 7, holdDurationMs = 1_000L, score = null, problemType = ProblemType.NONE, suggestion = "站姿体前屈")
        tracker.record(ActionType.STANDING_FORWARD_BEND, totalCount = 3, holdDurationMs = 3_000L, score = null, problemType = ProblemType.NONE, suggestion = "较少次数")

        assertEquals(5, tracker.bestFor(ActionType.SQUAT)?.totalCount)
        assertEquals(8, tracker.bestFor(ActionType.JUMPING_JACK)?.totalCount)
        assertEquals(4_000L, tracker.bestFor(ActionType.PLANK)?.holdDurationMs)
        assertEquals(7, tracker.bestFor(ActionType.STANDING_FORWARD_BEND)?.totalCount)
        assertEquals(1_000L, tracker.bestFor(ActionType.STANDING_FORWARD_BEND)?.holdDurationMs)
    }

    @Test
    fun mainDashboardShowsRecentTrainingScoreTotalCountAndRecognitionState() {
        val records = listOf(
            TrainingSummary(
                sessionId = 1L,
                actionType = ActionType.SQUAT,
                totalCount = 8,
                averageScore = 92f,
                durationMs = 12_000L,
                mainProblem = ProblemType.NONE,
                suggestion = "保持稳定",
                timestampMs = 1_000L,
            ),
            TrainingSummary(
                sessionId = 2L,
                actionType = ActionType.JUMPING_JACK,
                totalCount = 20,
                averageScore = null,
                durationMs = 30_000L,
                mainProblem = ProblemType.NONE,
                suggestion = "保持节奏",
                timestampMs = 2_000L,
            ),
        )

        val stats = MainDashboardFormatter.calculate(records)
        val latestText = MainDashboardFormatter.latestTrainingText(stats)
        val summaryText = MainDashboardFormatter.summaryText(stats)

        assertEquals(28, stats.totalCount)
        assertEquals(ActionType.JUMPING_JACK, stats.latestRecord?.actionType)
        assertTrue(latestText.contains("最近训练：开合跳"))
        assertTrue(summaryText.contains("综合评分：92 分"))
        assertTrue(summaryText.contains("累计次数：28 次"))
        assertTrue(summaryText.contains("已记录：深蹲、开合跳"))
    }

    @Test
    fun actionRecognitionStatusUsesAutomaticRecognitionLanguage() {
        val latest = TrainingSummary(
            sessionId = 8L,
            actionType = ActionType.SQUAT,
            totalCount = 6,
            averageScore = 90f,
            durationMs = 10_000L,
            mainProblem = ProblemType.NONE,
            suggestion = "保持稳定",
            timestampMs = 5_000L,
        )

        val text = ActionRecognitionStatusFormatter.format(
            actionRecognitionThreshold = 0.7f,
            latest = latest,
        )

        assertTrue(text.contains("自动识别深蹲、开合跳、俯卧撑"))
        assertTrue(text.contains("识别阈值：0.70"))
        assertTrue(text.contains("最近识别：深蹲，总次数：6"))
        assertTrue(text.contains("最高置信度的动作"))
    }

    @Test
    fun appSettingsUsesBuiltInAnalysisThresholds() {
        val settings = AppSettings(
            isLoggedIn = true,
            lastUserId = 1L,
            lastUsername = "tester",
            defaultActionType = ActionType.SQUAT,
            lastRoomCode = "123456",
            minPoseConfidence = 0.5f,
            squatDepthThreshold = 0.04f,
            backLeanThreshold = 24f,
            actionRecognitionThreshold = 0.7f,
            defaultEvaluationRuleSet = "RULE_BASED_11_ACTIONS",
            jumpingJackOpenRatio = 1.6f,
            jumpingJackClosedRatio = 0.9f,
            jumpingJackWristUpMargin = 0.05f,
            jumpingJackWristDownMargin = 0.1f,
            minSquatRepDurationMs = 900L,
            maxSquatRepDurationMs = 7000L,
            showSkeletonOverlay = false,
            saveVideoEnabled = false,
            defaultExportDir = "training/reports",
        )

        val config = settings.toPoseAnalysisConfig()
        val defaultConfig = PoseAnalysisConfig()

        assertEquals(defaultConfig.minPoseConfidence, config.minPoseConfidence, 0.01f)
        assertEquals(defaultConfig.squatDepthThreshold, config.squatDepthThreshold, 0.01f)
        assertEquals(defaultConfig.backLeanAngleThreshold, config.backLeanAngleThreshold, 0.01f)
        assertEquals(defaultConfig.actionRecognitionThreshold, config.actionRecognitionThreshold, 0.01f)
        assertEquals(defaultConfig.jumpingJackOpenAnkleShoulderRatio, config.jumpingJackOpenAnkleShoulderRatio, 0.01f)
        assertEquals(defaultConfig.jumpingJackClosedAnkleShoulderRatio, config.jumpingJackClosedAnkleShoulderRatio, 0.01f)
        assertEquals(defaultConfig.jumpingJackWristUpMargin, config.jumpingJackWristUpMargin, 0.01f)
        assertEquals(defaultConfig.jumpingJackWristDownMargin, config.jumpingJackWristDownMargin, 0.01f)
        assertEquals(defaultConfig.minSquatRepDurationMs, config.minSquatRepDurationMs)
        assertEquals(defaultConfig.maxSquatRepDurationMs, config.maxSquatRepDurationMs)
    }

    @Test
    fun settingsValidatorChecksDefaultExportDirectory() {
        assertEquals(null, SettingsValidator.exportDirectoryError("training/reports"))
        assertEquals(null, SettingsValidator.exportDirectoryError("training/{sessionId}/reports"))
        assertTrue(SettingsValidator.exportDirectoryError("").orEmpty().contains("不能为空"))
        assertTrue(SettingsValidator.exportDirectoryError("../reports").orEmpty().contains(".."))
        assertTrue(SettingsValidator.exportDirectoryError("training/./reports").orEmpty().contains("."))
        assertTrue(SettingsValidator.exportDirectoryError("C:/reports").orEmpty().contains("冒号"))
        assertTrue(SettingsValidator.exportDirectoryError("training\\reports").orEmpty().contains("/ 分隔"))
    }

    @Test
    fun roomCodeFormatterNormalizesAndDisplaysCode() {
        assertEquals("123456", RoomCodeFormatter.normalize("123 456 789"))
        assertEquals("123 456", RoomCodeFormatter.display("123456"))
    }

    @Test
    fun roomCodeParserRequiresExactlySixDigits() {
        assertEquals("123456", RoomCodeParser.parseExactSixDigits("room=123 456"))
        assertEquals(null, RoomCodeParser.parseExactSixDigits("12345"))
        assertEquals(null, RoomCodeParser.parseExactSixDigits("123 456 789"))
    }

    @Test
    fun roomQrCodeEncoderCreatesVersionOneQrMatrix() {
        val matrix = RoomQrCodeEncoder.encodeRoomCode("123456")

        assertEquals(21, matrix.size)
        assertEquals(21 * 21, matrix.modules.size)
        assertTrue(matrix.isDark(0, 0))
        assertTrue(matrix.isDark(6, 0))
        assertTrue(matrix.isDark(0, 6))
        assertTrue(matrix.isDark(8, 13))
    }

    @Test(expected = IllegalArgumentException::class)
    fun roomQrCodeEncoderRejectsShortRoomCode() {
        RoomQrCodeEncoder.encodeRoomCode("123")
    }

    @Test(expected = IllegalArgumentException::class)
    fun roomQrCodeEncoderRejectsLongRoomCode() {
        RoomQrCodeEncoder.encodeRoomCode("1234567")
    }

    @Test
    fun roomQrScanResultParserExtractsSixDigitCode() {
        assertEquals("123456", RoomQrScanResultParser.parseRoomCode("room=123 456"))
        assertEquals("654321", RoomQrScanResultParser.parseRoomCode("654321"))
        assertEquals(null, RoomQrScanResultParser.parseRoomCode("12345"))
        assertEquals(null, RoomQrScanResultParser.parseRoomCode("room=123 456 789"))
    }

    @Test
    fun roomQrScannerUsesVolatileFlagsForAnalyzerThreadState() {
        val source = readMainKotlin("ui/room/RoomQrScannerActivity.kt")

        assertTrue(source.contains("@Volatile\n    private var isProcessingFrame"))
        assertTrue(source.contains("@Volatile\n    private var hasResult"))
    }

    @Test
    fun imageAnalyzersCloseFramesWhenMlKitStartsFailing() {
        val qrScanner = readMainKotlin("ui/room/RoomQrScannerActivity.kt")
        val poseDetector = readMainKotlin("pose/detector/PoseDetectorAdapter.kt")

        assertTrue(qrScanner.contains("runCatching {\n                            val inputImage = InputImage.fromMediaImage"))
        assertTrue(qrScanner.contains("}.onFailure { error ->\n                            isProcessingFrame = false\n                            imageProxy.close()"))
        assertTrue(poseDetector.contains("runCatching {\n            val image = InputImage.fromMediaImage"))
        assertTrue(poseDetector.contains("}.onFailure { error ->\n            inputFrame.close()"))
    }

    @Test
    fun poseOverlayUsesPreviewFillCenterCoordinateMapping() {
        val frame = PoseFrame(
            sessionId = 1L,
            nodeId = 1L,
            timestampMs = 1_000L,
            cameraRole = DeviceRole.FRONT_CAMERA,
            landmarks = emptyMap(),
            overallConfidence = 1f,
            imageWidth = 720,
            imageHeight = 1280,
        )
        val center = LandmarkPoint("CENTER", 0.5f, 0.5f, confidence = 1f)
        val topLeft = LandmarkPoint("TOP_LEFT", 0f, 0f, confidence = 1f)

        val mappedCenter = PoseOverlayCoordinateMapper.mapPoint(center, frame, viewWidth = 1080, viewHeight = 320)
        val mappedTopLeft = PoseOverlayCoordinateMapper.mapPoint(topLeft, frame, viewWidth = 1080, viewHeight = 320)

        assertEquals(540f, mappedCenter.x, 0.01f)
        assertEquals(160f, mappedCenter.y, 0.01f)
        assertEquals(0f, mappedTopLeft.x, 0.01f)
        assertEquals(-800f, mappedTopLeft.y, 0.01f)
    }

    @Test
    fun poseDetectorUsesRotatedImageSizeForOverlayCoordinates() {
        val upright = poseImageCoordinateSize(width = 640, height = 480, rotationDegrees = 0)
        val rotated = poseImageCoordinateSize(width = 640, height = 480, rotationDegrees = 90)
        val rotatedBack = poseImageCoordinateSize(width = 640, height = 480, rotationDegrees = 270)

        assertEquals(640, upright.width)
        assertEquals(480, upright.height)
        assertEquals(480, rotated.width)
        assertEquals(640, rotated.height)
        assertEquals(480, rotatedBack.width)
        assertEquals(640, rotatedBack.height)
    }

    @Test
    fun cameraPreviewAndOverlayUseMatchingCoordinateAssumptions() {
        val layout = readMainLayout("activity_camera_node.xml")
        val cameraSource = readMainKotlin("ui/camera/CameraNodeActivity.kt")
        val poseDetector = readMainKotlin("pose/detector/PoseDetectorAdapter.kt")
        val overlay = readMainKotlin("pose/overlay/PoseOverlayView.kt")

        assertTrue(layout.contains("app:scaleType=\"fillCenter\""))
        assertTrue(cameraSource.contains(".setTargetAspectRatio(AspectRatio.RATIO_4_3)"))
        assertTrue(cameraSource.contains(".setTargetRotation(targetRotation)"))
        assertTrue(poseDetector.contains("poseImageCoordinateSize"))
        assertTrue(overlay.contains("PoseOverlayCoordinateMapper.mapPoint"))
        assertTrue(!overlay.contains("point.x * width"))
        assertTrue(!overlay.contains("point.y * height"))
    }

    @Test
    fun passwordHasherUsesStableSha256() {
        assertEquals(
            "8d969eef6ecad3c29a3a629280e686cf0c3f5d5a86aff3ca12020c923adc6c92",
            PasswordHasher.sha256("123456"),
        )
    }

    @Test
    fun trainingRecordMapperPreservesFakeSummary() {
        val summary = TrainingSummary(
            userId = 7L,
            actionType = ActionType.SQUAT,
            totalCount = 3,
            averageScore = 91f,
            durationMs = 3000L,
            mainProblem = ProblemType.KNEE_INWARD,
            suggestion = "保持膝盖朝向脚尖",
            timestampMs = 10_000L,
        )

        val session = TrainingRecordMapper.toSessionEntity(7L, summary)
        session.sessionId = 42L
        session.reportPath = "/tmp/training_42.pdf"
        val actions = TrainingRecordMapper.toActionResults(session.sessionId, summary)
        val restored = TrainingRecordMapper.toSummary(session, actions)

        assertEquals(42L, restored.sessionId)
        assertEquals(7L, restored.userId)
        assertEquals(ActionType.SQUAT, restored.actionType)
        assertEquals(3, restored.totalCount)
        assertEquals(91f, restored.averageScore ?: 0f, 0.01f)
        assertEquals(ProblemType.KNEE_INWARD, restored.mainProblem)
        assertEquals("/tmp/training_42.pdf", restored.reportPath)
        assertEquals(3, actions.size)
    }

    @Test
    fun trainingRecordMapperCreatesHoldBasedActionDetail() {
        val summary = TrainingSummary(
            userId = 7L,
            actionType = ActionType.PLANK,
            totalCount = 0,
            averageScore = null,
            durationMs = 12_000L,
            mainProblem = ProblemType.NONE,
            suggestion = "保持身体接近一条直线",
            timestampMs = 20_000L,
        )

        val actions = TrainingRecordMapper.toActionResults(77L, summary)

        assertEquals(1, actions.size)
        assertEquals(ActionType.PLANK.name, actions.first().actionType)
        assertEquals("HOLDING", actions.first().postureLevel)
        assertEquals(8_000L, actions.first().startTimeMs)
        assertEquals(20_000L, actions.first().endTimeMs)
        assertEquals(null, actions.first().score)
    }

    @Test
    fun exportTypeReportPathPolicyKeepsRawPoseSeparate() {
        assertTrue(ExportType.JSON.persistsAsSessionReportPath())
        assertTrue(ExportType.PDF.persistsAsSessionReportPath())
        assertTrue(!ExportType.RAW_POSE_JSON.persistsAsSessionReportPath())
    }

    @Test
    fun fakeTrainingGeneratesRawPoseSamples() {
        val summary = TrainingSummary(
            sessionId = 9L,
            actionType = ActionType.SQUAT,
            totalCount = 2,
            averageScore = 88f,
            durationMs = 4000L,
            mainProblem = ProblemType.SQUAT_DEPTH_NOT_ENOUGH,
            suggestion = "增加下蹲深度",
            timestampMs = 10_000L,
        )
        val frames = TrainingRecordMapper.toPoseFrameSamples(summary.sessionId, summary)
        frames.first().frameImagePath = "frames/action_1_keyframe.png"
        val rootDir = File(System.getProperty("java.io.tmpdir"), "zhizijing_raw_pose_test_${System.nanoTime()}")
        val outputDir = TrainingFileLayout.rawPoseDir(rootDir, summary.sessionId)

        val file = RawPoseSampleJsonExporter().export(summary, frames, outputDir)
        val jsonText = file.readText()

        assertEquals(8, frames.size)
        assertEquals("raw_pose", file.parentFile?.name)
        assertTrue(jsonText.contains("\"label\": \"SQUAT\""))
        assertTrue(jsonText.contains("\"frameCount\": 8"))
        assertTrue(jsonText.contains("\"frameImagePath\": \"frames/action_1_keyframe.png\""))
        assertTrue(jsonText.contains("\"LEFT_HIP\""))
    }

    @Test
    fun rawPoseExporterKeepsExportingWhenLandmarksJsonIsInvalid() {
        val summary = TrainingSummary(
            sessionId = 56L,
            actionType = ActionType.SQUAT,
            totalCount = 1,
            averageScore = 90f,
            durationMs = 1_000L,
            mainProblem = ProblemType.NONE,
            suggestion = "保持稳定",
        )
        val outputDir = File(System.getProperty("java.io.tmpdir"), "zhizijing_raw_pose_invalid_${System.nanoTime()}")
        val frame = PoseFrameEntity(
            56L,
            1L,
            1_000L,
            DeviceRole.FRONT_CAMERA.name,
            "{not-valid-json",
            0.8f,
            null,
            "REAL_POSE",
        )

        val file = RawPoseSampleJsonExporter().export(summary, listOf(frame), outputDir)
        val frameJson = JsonParser.parseString(file.readText(Charsets.UTF_8))
            .asJsonObject
            .getAsJsonArray("frames")
            .get(0)
            .asJsonObject

        assertEquals(0, frameJson.getAsJsonObject("landmarks").entrySet().size)
        assertEquals("REAL_POSE", frameJson.get("frameType").asString)
    }

    @Test
    fun trainingFileLayoutSeparatesSessionArtifacts() {
        val root = File("files")
        val sessionId = 42L

        assertEquals(File(root, "training/42").path, TrainingFileLayout.sessionDir(root, sessionId).path)
        assertEquals(File(root, "training/42/frames").path, TrainingFileLayout.framesDir(root, sessionId).path)
        assertEquals(File(root, "training/42/reports").path, TrainingFileLayout.reportsDir(root, sessionId).path)
        assertEquals(File(root, "training/42/raw_pose").path, TrainingFileLayout.rawPoseDir(root, sessionId).path)
        assertEquals(File(root, "training/42/videos").path, TrainingFileLayout.videosDir(root, sessionId).path)
        assertEquals(File(root, "training/video_drafts").path, TrainingFileLayout.videoDraftsDir(root).path)
    }

    @Test
    fun reportOutputDirectoryUsesConfiguredPrivateRelativePath() {
        val root = File("files")

        assertEquals(
            File(root, "training/reports/42").path,
            ReportOutputDirectory.resolve(root, 42L, "training/reports").path,
        )
        assertEquals(
            File(root, "exports/42/reports").path,
            ReportOutputDirectory.resolve(root, 42L, "exports/{sessionId}/reports").path,
        )
        assertEquals(
            File(root, "training/reports/42").path,
            ReportOutputDirectory.resolve(root, 42L, "  ").path,
        )
        assertEquals(
            File(root, "training/reports/42").path,
            ReportOutputDirectory.resolve(root, 42L, "../outside").path,
        )
        assertEquals(
            File(root, "training/reports/42").path,
            ReportOutputDirectory.resolve(root, 42L, "/outside").path,
        )
        assertEquals(
            File(root, "training/reports/42").path,
            ReportOutputDirectory.resolve(root, 42L, "training/./reports").path,
        )
        assertEquals(
            File(root, "training/reports/42").path,
            ReportOutputDirectory.resolve(root, 42L, "training/reports/").path,
        )
        assertEquals(
            File(root, "training/reports/42").path,
            ReportOutputDirectory.resolve(root, 42L, "C:/outside").path,
        )
    }

    @Test
    fun trainingArtifactCleanerRemovesOnlyCurrentSessionFilesInsideRoot() {
        val root = File(System.getProperty("java.io.tmpdir"), "zhizijing_cleanup_root_${System.nanoTime()}")
        val sessionDir = TrainingFileLayout.sessionDir(root, 42L)
        val keyFrame = File(TrainingFileLayout.framesDir(root, 42L), "frame_1.png")
        val video = File(TrainingFileLayout.videosDir(root, 42L), "front.mp4")
        val rawPose = File(TrainingFileLayout.rawPoseDir(root, 42L), "training_42_raw_pose.json")
        val customReport = File(root, "exports/42/reports/training_42.pdf")
        val otherSessionReport = File(root, "exports/43/reports/training_43.pdf")
        val maliciousInsideFile = File(root, "datastore/zhizijing_settings.preferences_pb")
        val wrongSessionNamedReport = File(root, "exports/43/reports/training_42.pdf")
        val wrongNameInSessionDir = File(root, "exports/42/reports/not_training_42.pdf")
        val outsideFile = File(root.parentFile, "outside_training_42.pdf")
        listOf(
            keyFrame,
            video,
            rawPose,
            customReport,
            otherSessionReport,
            maliciousInsideFile,
            wrongSessionNamedReport,
            wrongNameInSessionDir,
            outsideFile,
        ).forEach { file ->
            file.parentFile?.mkdirs()
            file.writeText(file.name, Charsets.UTF_8)
        }

        val deletedCount = TrainingArtifactCleaner.cleanup(
            rootDir = root,
            sessionId = 42L,
            exportRecords = listOf(
                ExportRecordEntity(42L, "PDF", customReport.absolutePath, 1_000L, customReport.length()),
                ExportRecordEntity(42L, "PDF", maliciousInsideFile.absolutePath, 1_000L, maliciousInsideFile.length()),
                ExportRecordEntity(42L, "PDF", wrongSessionNamedReport.absolutePath, 1_000L, wrongSessionNamedReport.length()),
                ExportRecordEntity(42L, "PDF", wrongNameInSessionDir.absolutePath, 1_000L, wrongNameInSessionDir.length()),
                ExportRecordEntity(42L, "PDF", outsideFile.absolutePath, 1_000L, outsideFile.length()),
            ),
        )

        assertTrue(deletedCount >= 2)
        assertTrue(!sessionDir.exists())
        assertTrue(!customReport.exists())
        assertTrue(outsideFile.exists())
        assertTrue(otherSessionReport.exists())
        assertTrue(maliciousInsideFile.exists())
        assertTrue(wrongSessionNamedReport.exists())
        assertTrue(wrongNameInSessionDir.exists())
    }

    @Test
    fun trainingVideoArtifactsListsOnlySessionMp4Files() {
        val root = File(System.getProperty("java.io.tmpdir"), "zhizijing_video_artifacts_${System.nanoTime()}")
        val videoDir = TrainingFileLayout.videosDir(root, 42L)
        videoDir.mkdirs()
        val oldVideo = File(videoDir, "front_old.mp4").apply {
            writeBytes(byteArrayOf(1, 2))
            setLastModified(1_000L)
        }
        val newVideo = File(videoDir, "side_new.MP4").apply {
            writeBytes(byteArrayOf(1, 2, 3))
            setLastModified(2_000L)
        }
        File(videoDir, "notes.txt").writeText("ignore", Charsets.UTF_8)

        val videos = TrainingVideoArtifacts.listForSession(root, 42L)
        val summary = TrainingVideoArtifacts.formatSummary(videos)

        assertEquals(listOf(newVideo.name, oldVideo.name), videos.map { it.name })
        assertTrue(summary.contains("视频素材：2 个"))
        assertTrue(summary.contains("side_new.MP4"))
        assertTrue(summary.contains("front_old.mp4"))
        assertTrue(!summary.contains("notes.txt"))
        assertTrue(TrainingVideoArtifacts.formatSummary(emptyList()).contains("暂无"))
    }

    @Test
    fun poseFrameEntityMapperKeepsLandmarksAndRole() {
        val frame = PoseFrame(
            sessionId = 0L,
            nodeId = 2L,
            timestampMs = 1234L,
            cameraRole = DeviceRole.SIDE_CAMERA,
            landmarks = mapOf(
                "LEFT_HIP" to LandmarkPoint("LEFT_HIP", 0.4f, 0.6f, confidence = 0.9f),
            ),
            overallConfidence = 0.9f,
            imageWidth = 720,
            imageHeight = 1280,
        )

        val entity = TrainingRecordMapper.toPoseFrameEntity(88L, frame, "REAL_POSE")

        assertEquals(88L, entity.sessionId)
        assertEquals(2L, entity.nodeId)
        assertEquals(DeviceRole.SIDE_CAMERA.name, entity.cameraRole)
        assertEquals("REAL_POSE", entity.frameType)
        assertTrue(entity.landmarksJson.contains("LEFT_HIP"))
    }

    @Test
    fun trainingPoseMetricsExtractorPersistsSquatAnglesAndDepth() {
        val summary = TrainingSummary(
            sessionId = 14L,
            actionType = ActionType.SQUAT,
            totalCount = 1,
            averageScore = 85f,
            durationMs = 4_000L,
            mainProblem = ProblemType.SQUAT_DEPTH_NOT_ENOUGH,
            suggestion = "增加下蹲深度",
            timestampMs = 10_000L,
        )
        val frame = TrainingRecordMapper.toPoseFrameSamples(summary.sessionId, summary)[1]
        val result = TrainingRecordMapper.toActionResults(summary.sessionId, summary).single()

        TrainingPoseMetricsExtractor.applyTo(result, frame)

        assertTrue((result.kneeAngle ?: 0f) > 0f)
        assertTrue((result.trunkAngle ?: -1f) >= 0f)
        assertEquals("SHALLOW", result.depthLevel)
    }

    @Test
    fun trainingPoseMetricsExtractorRecalculatesRecognizedSquatEvaluation() {
        val summary = TrainingSummary(
            sessionId = 15L,
            actionType = ActionType.SQUAT,
            totalCount = 1,
            averageScore = 100f,
            durationMs = 4_000L,
            mainProblem = ProblemType.NONE,
            suggestion = "旧的整场建议",
            timestampMs = 10_000L,
        )
        val shallowSummary = summary.copy(mainProblem = ProblemType.SQUAT_DEPTH_NOT_ENOUGH)
        val frame = TrainingRecordMapper.toPoseFrameSamples(summary.sessionId, shallowSummary)[1]
        val result = TrainingRecordMapper.toActionResults(summary.sessionId, summary).single()

        TrainingPoseMetricsExtractor.applyTo(result, frame, overwriteEvaluation = true)

        assertEquals(85f, result.score ?: 0f, 0.01f)
        assertEquals(ProblemType.SQUAT_DEPTH_NOT_ENOUGH.name, result.problemType)
        assertTrue(result.suggestion.orEmpty().contains("增加下蹲幅度"))
        assertEquals("SHALLOW", result.depthLevel)
    }

    @Test
    fun trainingPoseMetricsExtractorRecalculatesRecognizedKneeInwardEvaluation() {
        val summary = TrainingSummary(
            sessionId = 16L,
            actionType = ActionType.SQUAT,
            totalCount = 1,
            averageScore = 100f,
            durationMs = 4_000L,
            mainProblem = ProblemType.KNEE_INWARD,
            suggestion = "旧的整场建议",
            timestampMs = 10_000L,
        )
        val frame = TrainingRecordMapper.toPoseFrameSamples(summary.sessionId, summary)[1]
        val result = TrainingRecordMapper.toActionResults(
            summary.sessionId,
            summary.copy(mainProblem = ProblemType.NONE),
        ).single()

        TrainingPoseMetricsExtractor.applyTo(result, frame, overwriteEvaluation = true)

        assertEquals(80f, result.score ?: 0f, 0.01f)
        assertEquals(ProblemType.KNEE_INWARD.name, result.problemType)
        assertTrue(result.suggestion.orEmpty().contains("膝盖朝向脚尖"))
    }

    @Test
    fun trainingPoseMetricsExtractorDoesNotScoreLowConfidenceFrame() {
        val summary = TrainingSummary(
            sessionId = 17L,
            actionType = ActionType.SQUAT,
            totalCount = 1,
            averageScore = 100f,
            durationMs = 4_000L,
            mainProblem = ProblemType.NONE,
            suggestion = "保持稳定",
            timestampMs = 10_000L,
        )
        val frame = TrainingRecordMapper.toPoseFrameEntity(
            summary.sessionId,
            squatFrame(timestampMs = 8_000L, hipY = 0.76f, confidence = 0.2f),
            "REAL_POSE",
        )
        val result = TrainingRecordMapper.toActionResults(summary.sessionId, summary).single()

        TrainingPoseMetricsExtractor.applyTo(result, frame, overwriteEvaluation = true)

        assertEquals(ProblemType.LOW_CONFIDENCE.name, result.problemType)
        assertEquals(null, result.score)
        assertTrue(result.suggestion.orEmpty().contains("全身入镜"))
    }

    @Test
    fun trainingPoseMetricsExtractorFusesFrontAndSideCameraResponsibilities() {
        val summary = TrainingSummary(
            sessionId = 17L,
            actionType = ActionType.SQUAT,
            totalCount = 1,
            averageScore = 100f,
            durationMs = 4_000L,
            mainProblem = ProblemType.NONE,
            suggestion = "保持稳定",
            timestampMs = 10_000L,
        )
        val result = TrainingRecordMapper.toActionResults(summary.sessionId, summary).single()
        val frontFrame = TrainingRecordMapper.toPoseFrameEntity(
            summary.sessionId,
            squatFrame(
                timestampMs = 8_000L,
                hipY = 0.76f,
                leftKneeX = 0.48f,
                rightKneeX = 0.52f,
            ).copy(cameraRole = DeviceRole.FRONT_CAMERA),
            "REAL_POSE",
        )
        val sideFrame = TrainingRecordMapper.toPoseFrameEntity(
            summary.sessionId,
            squatFrame(
                timestampMs = 8_020L,
                hipY = 0.64f,
            ).copy(cameraRole = DeviceRole.SIDE_CAMERA),
            "REAL_POSE",
        )

        TrainingPoseMetricsExtractor.applyMultiViewTo(
            result = result,
            fallbackFrame = frontFrame,
            frontFrame = frontFrame,
            sideFrame = sideFrame,
        )

        assertEquals(80f, result.score ?: 0f, 0.01f)
        assertEquals(ProblemType.KNEE_INWARD.name, result.problemType)
        assertEquals("SHALLOW", result.depthLevel)
        assertTrue(result.suggestion.orEmpty().contains("膝盖朝向脚尖"))
    }

    @Test
    fun trainingRecordMapperAggregatesRecognizedActionOverview() {
        val summary = TrainingSummary(
            actionType = ActionType.SQUAT,
            totalCount = 3,
            averageScore = 100f,
            durationMs = 6_000L,
            mainProblem = ProblemType.NONE,
            suggestion = "旧建议",
            timestampMs = 10_000L,
        )
        val session = TrainingRecordMapper.toSessionEntity(1L, summary)
        val actions = TrainingRecordMapper.toActionResults(8L, summary)
        actions[0].score = 85f
        actions[0].problemType = ProblemType.SQUAT_DEPTH_NOT_ENOUGH.name
        actions[0].suggestion = TrainingRecordMapper.suggestionFor(ProblemType.SQUAT_DEPTH_NOT_ENOUGH)
        actions[1].score = 80f
        actions[1].problemType = ProblemType.KNEE_INWARD.name
        actions[1].suggestion = TrainingRecordMapper.suggestionFor(ProblemType.KNEE_INWARD)
        actions[2].score = 80f
        actions[2].problemType = ProblemType.KNEE_INWARD.name
        actions[2].suggestion = TrainingRecordMapper.suggestionFor(ProblemType.KNEE_INWARD)

        TrainingRecordMapper.applyRecognizedActionOverview(session, actions)
        val restored = TrainingRecordMapper.toSummary(session, actions)

        assertEquals((85f + 80f + 80f) / 3f, restored.averageScore ?: 0f, 0.01f)
        assertEquals(ProblemType.KNEE_INWARD, restored.mainProblem)
        assertTrue(restored.suggestion.orEmpty().contains("膝盖朝向脚尖"))
    }

    @Test
    fun trainingRecordMapperClearsAverageWhenRecognizedActionsCannotBeScored() {
        val summary = TrainingSummary(
            actionType = ActionType.SQUAT,
            totalCount = 1,
            averageScore = 90f,
            durationMs = 2_000L,
            mainProblem = ProblemType.LOW_CONFIDENCE,
            suggestion = "保持全身入镜",
            timestampMs = 10_000L,
        )
        val session = TrainingRecordMapper.toSessionEntity(1L, summary)
        val actions = TrainingRecordMapper.toActionResults(8L, summary)
        actions.single().score = null

        TrainingRecordMapper.applyRecognizedActionOverview(session, actions)

        assertEquals(null, session.averageScore)
    }

    @Test
    fun trainingRecordMapperPersistsDeviceSnapshotAndCount() {
        val summary = TrainingSummary(
            userId = 7L,
            actionType = ActionType.SQUAT,
            totalCount = 1,
            averageScore = 96f,
            durationMs = 2000L,
            mainProblem = ProblemType.NONE,
            suggestion = "保持稳定",
            timestampMs = 10_000L,
        )
        val session = TrainingRecordMapper.toSessionEntity(7L, summary, deviceCount = 3)
        val node = TrainingRecordMapper.toDeviceNodeEntity(
            sessionId = 88L,
            snapshot = TrainingDeviceSnapshot(
                deviceName = "Pixel Side",
                endpointId = "endpoint-side",
                role = DeviceRole.SIDE_CAMERA,
                batteryLevel = 83,
                networkDelayMs = 42,
                isOnline = true,
                lastHeartbeatAt = 9_999L,
            ),
        )

        assertEquals(3, session.deviceCount)
        assertEquals(88L, node.sessionId)
        assertEquals("Pixel Side", node.deviceName)
        assertEquals("endpoint-side", node.endpointId)
        assertEquals(DeviceRole.SIDE_CAMERA.name, node.role)
        assertEquals(83, node.batteryLevel)
        assertEquals(42, node.networkDelayMs)
        assertTrue(node.isOnline)
        assertEquals(9_999L, node.lastHeartbeatAt)
    }

    @Test
    fun reportFormatterContainsCoreTrainingFields() {
        val summary = TrainingSummary(
            sessionId = 5L,
            actionType = ActionType.JUMPING_JACK,
            totalCount = 20,
            averageScore = null,
            durationMs = 30_000L,
            mainProblem = ProblemType.NONE,
            suggestion = "保持节奏",
            timestampMs = 40_000L,
            averagePoseConfidence = 0.92f,
        )
        val actions = listOf(
            ActionResultEntity(
                5L,
                1,
                ActionType.JUMPING_JACK.name,
                10L,
                20L,
                null,
                null,
                null,
                "GOOD",
                ProblemType.NONE.name,
                "保持节奏",
                null,
            )
        )
        val devices = listOf(
            DeviceNodeEntity(
                5L,
                "Pixel Side",
                "endpoint-side",
                DeviceRole.SIDE_CAMERA.name,
                83,
                42,
                true,
                39_000L,
            )
        )

        val report = TrainingReportFormatter.plainText(summary, actions, devices)

        assertTrue(report.contains("智姿镜训练报告"))
        assertTrue(report.contains("开合跳"))
        assertTrue(report.contains("总次数：20"))
        assertTrue(report.contains("平均节奏：40.0 次/分钟"))
        assertTrue(report.contains("平均姿态识别置信度：92.0%"))
        assertTrue(report.contains("合格次数：1"))
        assertTrue(report.contains("参与设备：2 台"))
        assertTrue(report.contains("Pixel Side / 侧面机位"))
        assertTrue(report.contains("动作明细：1 条"))
        assertTrue(report.contains("#1 开合跳：评分 仅计数"))
        assertTrue(report.contains("问题 暂无明显问题"))
        assertTrue(report.contains("建议 保持节奏"))
        assertTrue(report.contains("关键帧图片：0 张"))
        assertTrue(report.contains("视频素材：暂无"))
    }

    @Test
    fun pdfReportFormatterKeepsOnlyUserFacingSummary() {
        val summary = TrainingSummary(
            sessionId = 5L,
            actionType = ActionType.SQUAT,
            totalCount = 2,
            averageScore = 91f,
            durationMs = 75_000L,
            mainProblem = ProblemType.KNEE_INWARD,
            suggestion = "膝盖朝向脚尖，保持稳定节奏。",
            timestampMs = 40_000L,
            averagePoseConfidence = 0.92f,
        )
        val actions = listOf(
            ActionResultEntity(
                5L,
                1,
                ActionType.SQUAT.name,
                10L,
                20L,
                91f,
                112f,
                14f,
                "GOOD",
                ProblemType.KNEE_INWARD.name,
                "下蹲时让膝盖对齐脚尖。",
                "/private/training/5/frames/key_1.png",
            )
        )
        val devices = listOf(
            DeviceNodeEntity(
                5L,
                "Pixel Side",
                "endpoint-side",
                DeviceRole.SIDE_CAMERA.name,
                83,
                42,
                true,
                39_000L,
            )
        )

        val overview = TrainingReportFormatter.pdfOverviewText(
            summary = summary,
            actionResults = actions,
            deviceNodes = devices,
            videoFiles = listOf(File("/private/training/5/videos/demo.mp4")),
        )
        val review = TrainingReportFormatter.pdfActionReviewText(actions)
        val combined = "$overview\n$review"

        assertTrue(overview.contains("动作：深蹲"))
        assertTrue(overview.contains("总次数：2"))
        assertTrue(overview.contains("训练时长：1分15秒"))
        assertTrue(overview.contains("平均评分：91 分"))
        assertTrue(overview.contains("完成情况：合格 1 次，需复盘 0 次"))
        assertTrue(overview.contains("主要问题：膝盖内扣"))
        assertTrue(overview.contains("建议：膝盖朝向脚尖，保持稳定节奏。"))
        assertTrue(overview.contains("采集机位：本机、侧面机位"))
        assertTrue(overview.contains("训练视频：1 段"))
        assertTrue(review.contains("第 1 次：91 分"))
        assertTrue(review.contains("问题：膝盖内扣"))
        assertTrue(review.contains("建议：下蹲时让膝盖对齐脚尖。"))
        assertFalse(combined.contains("记录 ID"))
        assertFalse(combined.contains("合格规则"))
        assertFalse(combined.contains("endpoint-side"))
        assertFalse(combined.contains("Pixel Side"))
        assertFalse(combined.contains("电量"))
        assertFalse(combined.contains("延迟"))
        assertFalse(combined.contains("/private/"))
        assertFalse(combined.contains("关键帧图片"))
    }

    @Test
    fun pdfExporterUsesConciseUserFacingFormatter() {
        val source = readMainKotlin("report/pdf/PdfTrainingReportExporter.kt")

        assertTrue(source.contains("TrainingReportFormatter.pdfOverviewText"))
        assertTrue(source.contains("TrainingReportFormatter.pdfActionReviewText"))
        assertTrue(source.contains("writer.drawSection(\"训练结论\")"))
        assertTrue(source.contains("writer.drawSection(\"动作复盘\")"))
        assertTrue(source.contains("MAX_KEY_FRAME_PREVIEWS = 2"))
        assertFalse(source.contains("TrainingReportFormatter.plainText"))
        assertFalse(source.contains("TrainingReportFormatter.actionDetailsText"))
        assertFalse(source.contains("drawWrappedText(\"关键帧 ${'$'}index："))
    }

    @Test
    fun trainingReportFormatterLimitsActionDetailRowsWhenRequested() {
        val actions = (1..3).map { index ->
            ActionResultEntity(
                7L,
                index,
                ActionType.SQUAT.name,
                1_000L * index,
                1_000L * index + 800L,
                90f,
                110f,
                12f,
                "GOOD",
                ProblemType.NONE.name,
                "保持稳定",
                null,
            )
        }

        val detail = TrainingReportFormatter.actionDetailsText(actions, maxRows = 2)

        assertTrue(detail.contains("#1 深蹲：评分 90 分"))
        assertTrue(detail.contains("膝角 110 度"))
        assertTrue(detail.contains("躯干角 12 度"))
        assertTrue(detail.contains("还有 1 条动作明细未展示"))
    }

    @Test
    fun jsonReportExporterIncludesDeviceNodes() {
        val summary = TrainingSummary(
            sessionId = 12L,
            actionType = ActionType.SQUAT,
            totalCount = 2,
            averageScore = 90f,
            durationMs = 8_000L,
            mainProblem = ProblemType.NONE,
            suggestion = "保持稳定",
            timestampMs = 50_000L,
            averagePoseConfidence = 0.88f,
        )
        val actions = listOf(
            ActionResultEntity(
                12L,
                1,
                ActionType.SQUAT.name,
                10L,
                20L,
                90f,
                null,
                null,
                "GOOD",
                ProblemType.NONE.name,
                "保持稳定",
                null,
            )
        )
        val devices = listOf(
            DeviceNodeEntity(
                12L,
                "Pixel Front",
                "endpoint-front",
                DeviceRole.FRONT_CAMERA.name,
                91,
                28,
                true,
                49_000L,
            )
        )
        val outputDir = File(System.getProperty("java.io.tmpdir"), "zhizijing_report_devices_${System.nanoTime()}")
        val videoFile = File(outputDir, "training_12_front.mp4").apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(1, 2, 3, 4))
        }

        val file = JsonTrainingReportExporter().export(summary, actions, devices, outputDir, listOf(videoFile))
        val jsonText = file.readText()

        assertTrue(jsonText.contains("\"deviceCount\": 2"))
        assertTrue(jsonText.contains("\"averageTempoPerMinute\": null"))
        assertTrue(jsonText.contains("\"averagePoseConfidence\": 0.88"))
        assertTrue(jsonText.contains("\"deviceNodes\""))
        assertTrue(jsonText.contains("\"deviceName\": \"Pixel Front\""))
        assertTrue(jsonText.contains("\"role\": \"FRONT_CAMERA\""))
        assertTrue(jsonText.contains("\"batteryLevel\": 91"))
        assertTrue(jsonText.contains("\"roleKeyFrames\""))
        assertTrue(jsonText.contains("\"postureLevel\": \"GOOD\""))
        assertTrue(jsonText.contains("\"videoFiles\""))
        assertTrue(jsonText.contains("\"fileName\": \"training_12_front.mp4\""))
        assertTrue(jsonText.contains("\"sizeBytes\": 4"))
    }

    @Test
    fun trainingRecognitionConfidenceCalculatorUsesValidPoseFrames() {
        val frames = listOf(
            poseFrameEntity(confidence = 0.8f),
            poseFrameEntity(confidence = 1f),
            poseFrameEntity(confidence = null),
            poseFrameEntity(confidence = Float.NaN),
            poseFrameEntity(confidence = 1.2f),
        )

        assertEquals(0.9f, TrainingRecognitionConfidenceCalculator.averageFrom(frames) ?: 0f, 0.01f)
        assertEquals("90.0%", TrainingReportFormatter.recognitionConfidenceText(0.9f))
        assertEquals("暂无", TrainingReportFormatter.recognitionConfidenceText(null))
    }

    @Test
    fun reportStatsCountsQualifiedActionsAndKeyFrames() {
        val actions = listOf(
            ActionResultEntity(
                5L,
                1,
                ActionType.SQUAT.name,
                10L,
                20L,
                92f,
                null,
                null,
                "GOOD",
                ProblemType.NONE.name,
                "保持稳定",
                "frame_1.png",
            ),
            ActionResultEntity(
                5L,
                2,
                ActionType.SQUAT.name,
                20L,
                30L,
                80f,
                null,
                null,
                "GOOD",
                ProblemType.KNEE_INWARD.name,
                "膝盖朝向脚尖",
                "frame_2.png",
            ),
            ActionResultEntity(
                5L,
                3,
                ActionType.SQUAT.name,
                30L,
                40L,
                90f,
                null,
                null,
                "GOOD",
                ProblemType.LOW_CONFIDENCE.name,
                "保持全身入镜",
                "frame_2.png",
            )
        )

        val stats = TrainingReportStatsCalculator.from(actions)

        assertEquals(3, stats.actionCount)
        assertEquals(1, stats.qualifiedCount)
        assertEquals(2, stats.reviewCount)
        assertEquals(2, stats.keyFramePaths.size)
        assertEquals("frame_1.png", stats.firstKeyFramePath)
        assertEquals("frame_2.png", stats.secondKeyFramePath)
    }

    @Test
    fun reportStatsDoesNotTreatUnscoredSquatAsQualified() {
        val actions = listOf(
            ActionResultEntity(
                5L,
                1,
                ActionType.SQUAT.name,
                10L,
                20L,
                null,
                null,
                null,
                "GOOD",
                ProblemType.NONE.name,
                "缺少评分，应复盘",
                null,
            ),
            ActionResultEntity(
                5L,
                2,
                ActionType.PUSH_UP.name,
                20L,
                30L,
                null,
                null,
                null,
                "COUNT_ONLY",
                ProblemType.NONE.name,
                "仅计数动作",
                null,
            ),
        )

        val stats = TrainingReportStatsCalculator.from(actions)

        assertEquals(1, stats.qualifiedCount)
        assertEquals(1, stats.reviewCount)
    }

    @Test
    fun reportQualificationRuleTextMatchesCurrentCountingPolicy() {
        val text = TrainingReportStatsCalculator.qualificationRuleText()

        assertTrue(text.contains("深蹲等评分动作必须有单次评分"))
        assertTrue(text.contains("开合跳、俯卧撑等计数型动作按完成次数计入"))
        assertTrue(text.contains("平板支撑按保持时长展示"))
        assertTrue(!text.contains("无评分动作按完成次数计入"))
    }

    @Test
    fun resultFallbackDoesNotInventQualifiedSquatCount() {
        assertEquals(
            "暂无（兜底数据无逐次评分）",
            ResultFallbackFormatter.qualifiedCountText(ActionType.SQUAT, 8),
        )
        assertEquals("20", ResultFallbackFormatter.qualifiedCountText(ActionType.JUMPING_JACK, 20))
        assertEquals("10", ResultFallbackFormatter.qualifiedCountText(ActionType.PUSH_UP, 10))
        assertEquals("0", ResultFallbackFormatter.qualifiedCountText(ActionType.SQUAT, 0))
    }

    @Test
    fun reportStatsPrioritizesFrontAndSideRoleKeyFrames() {
        val root = File(System.getProperty("java.io.tmpdir"), "zhizijing_role_keyframes_${System.nanoTime()}")
        val framesDir = File(root, "frames").apply { mkdirs() }
        val actionFrame = File(framesDir, "action_1_keyframe.png").apply { writeBytes(byteArrayOf(1)) }
        val frontFrame = File(framesDir, KeyFrameStore.rolePreviewFileName(DeviceRole.FRONT_CAMERA)).apply {
            writeBytes(byteArrayOf(2))
        }
        val sideFrame = File(framesDir, KeyFrameStore.rolePreviewFileName(DeviceRole.SIDE_CAMERA)).apply {
            writeBytes(byteArrayOf(3))
        }
        val actions = listOf(
            ActionResultEntity(
                5L,
                1,
                ActionType.SQUAT.name,
                10L,
                20L,
                92f,
                null,
                null,
                "GOOD",
                ProblemType.NONE.name,
                "保持稳定",
                actionFrame.absolutePath,
            )
        )

        val stats = TrainingReportStatsCalculator.from(actions)

        assertEquals(frontFrame.absolutePath, stats.frontKeyFramePath)
        assertEquals(sideFrame.absolutePath, stats.sideKeyFramePath)
        assertEquals(frontFrame.absolutePath, stats.firstKeyFramePath)
        assertEquals(sideFrame.absolutePath, stats.secondKeyFramePath)
        assertEquals(listOf(frontFrame.absolutePath, sideFrame.absolutePath, actionFrame.absolutePath), stats.keyFramePaths)
    }

    @Test
    fun historyFormatterGroupsRecordsByDayAndSortsNewestFirst() {
        val oldRecord = TrainingSummary(
            sessionId = 1L,
            actionType = ActionType.SQUAT,
            totalCount = 8,
            averageScore = 90f,
            durationMs = 20_000L,
            mainProblem = ProblemType.NONE,
            suggestion = "保持稳定",
            timestampMs = 1_000L,
        )
        val newRecord = oldRecord.copy(sessionId = 2L, timestampMs = 2_000L)

        val groups = HistoryFormatter.groupByDay(listOf(oldRecord, newRecord))

        assertEquals(1, groups.size)
        assertEquals(2L, groups.first().records.first().sessionId)
        assertTrue(HistoryFormatter.summaryText("全部", groups.first().records).contains("共 2 条"))
        val recordLine = HistoryFormatter.recordLine(newRecord)
        assertTrue(recordLine.contains("深蹲"))
        assertTrue(recordLine.contains("总次数：8"))
        assertFalse(recordLine.contains("ID"))
        assertFalse(recordLine.contains("正式报告"))
    }

    @Test
    fun historyFormatterFiltersRecordsByValidDate() {
        val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).apply {
            isLenient = false
        }
        val firstDay = dayFormat.parse("2026-05-30")!!.time
        val secondDay = dayFormat.parse("2026-05-31")!!.time
        val firstRecord = TrainingSummary(
            sessionId = 1L,
            actionType = ActionType.SQUAT,
            totalCount = 5,
            averageScore = 90f,
            mainProblem = ProblemType.NONE,
            suggestion = "保持稳定",
            timestampMs = firstDay,
        )
        val secondRecord = firstRecord.copy(sessionId = 2L, timestampMs = secondDay)

        val records = HistoryFormatter.filterByDate(listOf(firstRecord, secondRecord), "2026-05-31")

        assertEquals(listOf(2L), records.map { record -> record.sessionId })
        assertEquals(null, HistoryFormatter.dateFilterError("2026-05-31"))
        assertEquals(null, HistoryFormatter.dateFilterError(""))
        assertTrue(HistoryFormatter.dateFilterError("2026-02-30").orEmpty().contains("有效日期"))
        assertTrue(HistoryFormatter.dateFilterError("05-31").orEmpty().contains("yyyy-MM-dd"))
        assertTrue(HistoryFormatter.summaryText("深蹲", records, "2026-05-31").contains("日期 2026-05-31"))
    }

    @Test
    fun trainingReportFormatterCalculatesJumpingJackTempoOnlyForCountAction() {
        assertEquals(
            40f,
            TrainingReportFormatter.averageTempoPerMinute(ActionType.JUMPING_JACK, 20, 30_000L) ?: 0f,
            0.01f,
        )
        assertEquals(null, TrainingReportFormatter.averageTempoPerMinute(ActionType.SQUAT, 20, 30_000L))
        assertEquals(null, TrainingReportFormatter.averageTempoPerMinute(ActionType.JUMPING_JACK, 0, 30_000L))
    }

    @Test
    fun squatScorePolicyHidesLowConfidenceScore() {
        assertEquals(null, SquatScorePolicy.reportableScore(ProblemType.LOW_CONFIDENCE, 90f))
        assertEquals("暂不评分", SquatScorePolicy.displayText(ProblemType.LOW_CONFIDENCE, 90f))
        assertEquals(85f, SquatScorePolicy.reportableScore(ProblemType.BACK_LEAN_TOO_MUCH, 85f) ?: 0f, 0.01f)
        assertEquals(
            "暂不评分",
            TrainingReportFormatter.averageScoreText(
                TrainingSummary(
                    actionType = ActionType.SQUAT,
                    totalCount = 1,
                    averageScore = null,
                    mainProblem = ProblemType.LOW_CONFIDENCE,
                    suggestion = null,
                )
            ),
        )
        assertEquals(
            "仅计数",
            TrainingReportFormatter.averageScoreText(
                TrainingSummary(
                    actionType = ActionType.JUMPING_JACK,
                    totalCount = 1,
                    averageScore = null,
                    mainProblem = ProblemType.NONE,
                    suggestion = null,
                )
            ),
        )
    }

    @Test
    fun keyFrameStoreChoosesBottomFrameForEachAction() {
        assertEquals(1, KeyFrameStore.chooseKeyFrameIndex(actionIndex = 1, frameCountPerAction = 4))
        assertEquals(5, KeyFrameStore.chooseKeyFrameIndex(actionIndex = 2, frameCountPerAction = 4))
        assertEquals("front_camera_keyframe.png", KeyFrameStore.rolePreviewFileName(DeviceRole.FRONT_CAMERA))
        assertEquals("side_camera_keyframe.png", KeyFrameStore.rolePreviewFileName(DeviceRole.SIDE_CAMERA))
    }

    @Test
    fun trainingKeyFrameSelectorChoosesDeepestSquatFrame() {
        val summary = TrainingSummary(
            sessionId = 15L,
            actionType = ActionType.SQUAT,
            totalCount = 1,
            averageScore = 100f,
            durationMs = 4_000L,
            mainProblem = ProblemType.NONE,
            suggestion = "保持稳定",
            timestampMs = 10_000L,
        )
        val frames = TrainingRecordMapper.toPoseFrameSamples(summary.sessionId, summary)

        val selected = TrainingKeyFrameSelector.select(ActionType.SQUAT.name, 1, 1, frames)

        assertEquals(frames[1].timestampMs, selected?.timestampMs)
    }

    @Test
    fun trainingKeyFrameSelectorChoosesOpenJumpingJackFrame() {
        val summary = TrainingSummary(
            sessionId = 16L,
            actionType = ActionType.JUMPING_JACK,
            totalCount = 1,
            averageScore = null,
            durationMs = 4_000L,
            mainProblem = ProblemType.NONE,
            suggestion = "保持节奏",
            timestampMs = 10_000L,
        )
        val frames = TrainingRecordMapper.toPoseFrameSamples(summary.sessionId, summary)

        val selected = TrainingKeyFrameSelector.select(ActionType.JUMPING_JACK.name, 1, 1, frames)

        assertEquals(frames[1].timestampMs, selected?.timestampMs)
    }

    @Test
    fun trainingKeyFrameSelectorUsesAlignedTimestampRange() {
        val summary = TrainingSummary(
            sessionId = 17L,
            actionType = ActionType.SQUAT,
            totalCount = 1,
            averageScore = 100f,
            durationMs = 4_000L,
            mainProblem = ProblemType.NONE,
            suggestion = "保持稳定",
            timestampMs = 10_000L,
        )
        val frames = TrainingRecordMapper.toPoseFrameSamples(summary.sessionId, summary)

        val selected = TrainingKeyFrameSelector.selectInTimeRange(
            rawActionType = ActionType.SQUAT.name,
            startTimeMs = frames[2].timestampMs,
            endTimeMs = frames[3].timestampMs,
            frames = frames.reversed(),
        )

        assertEquals(frames[2].timestampMs, selected?.timestampMs)
    }

    @Test
    fun nearbyMessageCodecPreservesRoleAssignment() {
        val codec = GsonNearbyMessageCodec()
        val message = NearbyMessage(
            type = NearbyMessageType.ASSIGN_ROLE,
            roomCode = "123456",
            deviceName = "Pixel Node",
            endpointId = "endpoint-1",
            role = DeviceRole.FRONT_CAMERA,
            actionType = ActionType.SQUAT,
        )

        val restored = codec.decode(codec.encode(message))

        assertEquals(NearbyMessageType.ASSIGN_ROLE, restored.type)
        assertEquals("123456", restored.roomCode)
        assertEquals("endpoint-1", restored.endpointId)
        assertEquals(DeviceRole.FRONT_CAMERA, restored.role)
        assertEquals(ActionType.SQUAT, restored.actionType)
    }

    @Test
    fun nearbyMessageCodecPreservesJumpingJackCountdown() {
        val codec = GsonNearbyMessageCodec()
        val message = NearbyMessage(
            type = NearbyMessageType.START_COUNTDOWN,
            roomCode = "654321",
            actionType = ActionType.JUMPING_JACK,
            countdownSeconds = 3,
            message = "开始同步倒计时：3 秒",
        )

        val restored = codec.decode(codec.encode(message))

        assertEquals(NearbyMessageType.START_COUNTDOWN, restored.type)
        assertEquals(ActionType.JUMPING_JACK, restored.actionType)
        assertEquals(3, restored.countdownSeconds)
    }

    @Test
    fun nearbyMessageCodecPreservesHoldDurationAndActionConfidenceSummary() {
        val codec = GsonNearbyMessageCodec()
        val message = NearbyMessage(
            type = NearbyMessageType.ANALYSIS_SUMMARY,
            roomCode = "654321",
            role = DeviceRole.FRONT_CAMERA,
            actionType = ActionType.PLANK,
            actionConfidence = 0.82f,
            totalCount = 0,
            holdDurationMs = 12_000L,
            postureLevel = "HOLDING",
            problemType = ProblemType.NONE,
        )

        val restored = codec.decode(codec.encode(message))

        assertEquals(NearbyMessageType.ANALYSIS_SUMMARY, restored.type)
        assertEquals(ActionType.PLANK, restored.actionType)
        assertEquals(0.82f, restored.actionConfidence ?: 0f, 0.01f)
        assertEquals(12_000L, restored.holdDurationMs)
        assertEquals("HOLDING", restored.postureLevel)
    }

    @Test
    fun nearbyMessageCodecPreservesHostAnalysisStatus() {
        val codec = GsonNearbyMessageCodec()
        val message = NearbyMessage(
            type = NearbyMessageType.HOST_ANALYSIS_STATUS,
            roomCode = "654321",
            role = DeviceRole.HOST,
            actionType = ActionType.SQUAT,
            totalCount = 5,
            score = 88f,
            postureLevel = "NORMAL",
            problemType = ProblemType.KNEE_INWARD,
            suggestion = "膝盖保持朝向脚尖。",
            message = "主控实时结果：深蹲；当前次数：5",
        )

        val restored = codec.decode(codec.encode(message))

        assertEquals(NearbyMessageType.HOST_ANALYSIS_STATUS, restored.type)
        assertEquals(DeviceRole.HOST, restored.role)
        assertEquals(ActionType.SQUAT, restored.actionType)
        assertEquals(5, restored.totalCount)
        assertEquals(88f, restored.score ?: 0f, 0.01f)
        assertEquals(ProblemType.KNEE_INWARD, restored.problemType)
        assertTrue(restored.suggestion.orEmpty().contains("膝盖"))
        assertTrue(restored.message.orEmpty().contains("主控实时结果"))
    }

    @Test
    fun remoteAnalysisSummaryAggregatorFusesRolesWithoutDoubleCounting() {
        val endpoints = listOf(
            nearbyEndpoint("front", "Pixel Front", DeviceRole.FRONT_CAMERA),
            nearbyEndpoint("side", "Pixel Side", DeviceRole.SIDE_CAMERA),
        )
        val aggregator = RemoteAnalysisSummaryAggregator()
        aggregator.record(
            endpointId = "front",
            message = NearbyMessage(
                type = NearbyMessageType.ANALYSIS_SUMMARY,
                role = DeviceRole.FRONT_CAMERA,
                actionType = ActionType.SQUAT,
                totalCount = 4,
                score = 80f,
                kneeAngle = 104f,
                trunkAngle = 11f,
                postureLevel = "GOOD",
                problemType = ProblemType.KNEE_INWARD,
                suggestion = "保持膝盖朝向脚尖",
            ),
            endpoints = endpoints,
        )
        val aggregate = aggregator.record(
            endpointId = "side",
            message = NearbyMessage(
                type = NearbyMessageType.ANALYSIS_SUMMARY,
                role = DeviceRole.SIDE_CAMERA,
                actionType = ActionType.SQUAT,
                totalCount = 4,
                score = 90f,
                kneeAngle = 88f,
                trunkAngle = 24f,
                postureLevel = "SHALLOW",
                problemType = ProblemType.SQUAT_DEPTH_NOT_ENOUGH,
                suggestion = "增加下蹲深度",
            ),
            endpoints = endpoints,
        )

        assertEquals(ActionType.SQUAT, aggregate.actionType)
        assertEquals(4, aggregate.totalCount)
        assertEquals(85f, aggregate.score ?: 0f, 0.01f)
        assertEquals(88f, aggregate.kneeAngle ?: 0f, 0.01f)
        assertEquals(24f, aggregate.trunkAngle ?: 0f, 0.01f)
        assertEquals("SHALLOW", aggregate.postureLevel)
        assertEquals(ProblemType.KNEE_INWARD, aggregate.problemType)
        assertTrue(!aggregate.isDegraded)
        assertTrue(aggregate.statusText.contains("已启用融合"))
        assertTrue(aggregate.statusText.contains("膝角 88 度"))
        assertTrue(aggregate.statusText.contains("姿态 下蹲深度不足"))
    }

    @Test
    fun remoteAnalysisSummaryAggregatorCarriesHoldDuration() {
        val endpoints = listOf(
            nearbyEndpoint("front", "Pixel Front", DeviceRole.FRONT_CAMERA),
        )

        val aggregate = RemoteAnalysisSummaryAggregator().record(
            endpointId = "front",
            message = NearbyMessage(
                type = NearbyMessageType.ANALYSIS_SUMMARY,
                role = DeviceRole.FRONT_CAMERA,
                actionType = ActionType.PLANK,
                totalCount = 0,
                holdDurationMs = 6_500L,
                postureLevel = "HOLDING",
                problemType = ProblemType.NONE,
            ),
            endpoints = endpoints,
        )

        assertEquals(ActionType.PLANK, aggregate.actionType)
        assertEquals(0, aggregate.totalCount)
        assertEquals(6_500L, aggregate.holdDurationMs)
        assertEquals("HOLDING", aggregate.postureLevel)
    }

    @Test
    fun remoteAnalysisSummaryAggregatorChoosesHighestConfidenceAction() {
        val endpoints = listOf(
            nearbyEndpoint("front", "Pixel Front", DeviceRole.FRONT_CAMERA),
            nearbyEndpoint("side", "Pixel Side", DeviceRole.SIDE_CAMERA),
        )
        val aggregator = RemoteAnalysisSummaryAggregator()
        aggregator.record(
            endpointId = "front",
            message = NearbyMessage(
                type = NearbyMessageType.ANALYSIS_SUMMARY,
                role = DeviceRole.FRONT_CAMERA,
                actionType = ActionType.SQUAT,
                actionConfidence = 0.68f,
                totalCount = 3,
                timestampMs = 2_000L,
            ),
            endpoints = endpoints,
        )
        val aggregate = aggregator.record(
            endpointId = "side",
            message = NearbyMessage(
                type = NearbyMessageType.ANALYSIS_SUMMARY,
                role = DeviceRole.SIDE_CAMERA,
                actionType = ActionType.JUMPING_JACK,
                actionConfidence = 0.86f,
                totalCount = 7,
                timestampMs = 1_000L,
            ),
            endpoints = endpoints,
        )

        assertEquals(ActionType.JUMPING_JACK, aggregate.actionType)
        assertEquals(0.86f, aggregate.actionConfidence ?: 0f, 0.01f)
        assertEquals(7, aggregate.totalCount)
    }

    @Test
    fun remoteAnalysisSummaryAggregatorShowsSingleCameraDegradation() {
        val endpoints = listOf(
            nearbyEndpoint("front", "Pixel Front", DeviceRole.FRONT_CAMERA),
            nearbyEndpoint("side", "Pixel Side", DeviceRole.SIDE_CAMERA, isOnline = false),
        )
        val aggregate = RemoteAnalysisSummaryAggregator().record(
            endpointId = "front",
            message = NearbyMessage(
                type = NearbyMessageType.ANALYSIS_SUMMARY,
                role = DeviceRole.FRONT_CAMERA,
                actionType = ActionType.SQUAT,
                totalCount = 3,
                score = 80f,
                problemType = ProblemType.KNEE_INWARD,
            ),
            endpoints = endpoints,
        )

        assertEquals(3, aggregate.totalCount)
        assertEquals(ProblemType.KNEE_INWARD, aggregate.problemType)
        assertTrue(aggregate.isDegraded)
        assertTrue(aggregate.statusText.contains("单机位降级"))
        assertTrue(aggregate.statusText.contains("缺少侧面机位"))
        assertTrue(aggregate.statusText.contains("侧面机位：离线"))
    }

    @Test
    fun remoteAnalysisSummaryAggregatorIgnoresLowConfidenceScore() {
        val endpoints = listOf(
            nearbyEndpoint("front", "Pixel Front", DeviceRole.FRONT_CAMERA),
        )
        val aggregate = RemoteAnalysisSummaryAggregator().record(
            endpointId = "front",
            message = NearbyMessage(
                type = NearbyMessageType.ANALYSIS_SUMMARY,
                role = DeviceRole.FRONT_CAMERA,
                actionType = ActionType.SQUAT,
                totalCount = 2,
                score = 90f,
                problemType = ProblemType.LOW_CONFIDENCE,
            ),
            endpoints = endpoints,
        )

        assertEquals(null, aggregate.score)
        assertEquals(ProblemType.LOW_CONFIDENCE, aggregate.problemType)
        assertTrue(aggregate.statusText.contains("评分 暂不评分"))
    }

    @Test
    fun nearbyMessageCodecPreservesLatencyCorrelationId() {
        val codec = GsonNearbyMessageCodec()
        val message = NearbyMessage(
            type = NearbyMessageType.LATENCY_PING,
            roomCode = "123456",
            deviceName = "Pixel Node",
            correlationId = "host-endpoint-1000",
            timestampMs = 1_000L,
        )

        val restored = codec.decode(codec.encode(message))

        assertEquals(NearbyMessageType.LATENCY_PING, restored.type)
        assertEquals("host-endpoint-1000", restored.correlationId)
        assertEquals(1_000L, restored.timestampMs)
    }

    @Test
    fun nearbyMessageCodecPreservesRealtimePoseMetrics() {
        val codec = GsonNearbyMessageCodec()
        val message = NearbyMessage(
            type = NearbyMessageType.ANALYSIS_SUMMARY,
            actionType = ActionType.SQUAT,
            totalCount = 3,
            score = 86f,
            kneeAngle = 92f,
            trunkAngle = 18f,
            postureLevel = "SHALLOW",
        )

        val restored = codec.decode(codec.encode(message))

        assertEquals(92f, restored.kneeAngle ?: 0f, 0.01f)
        assertEquals(18f, restored.trunkAngle ?: 0f, 0.01f)
        assertEquals("SHALLOW", restored.postureLevel)
        assertEquals("膝角 92 度，躯干角 18 度", RealtimeAnalysisFormatter.coreAngleText(restored.kneeAngle, restored.trunkAngle))
        assertEquals("下蹲深度不足", RealtimeAnalysisFormatter.postureLevelText(restored.postureLevel))
    }

    @Test
    fun nearbyPoseFrameCodecRoundTripsPoseFrame() {
        val frame = jumpingJackFrame(timestampMs = 2_000L, isOpen = true)
            .copy(
                sessionId = 12L,
                nodeId = 3L,
                cameraRole = DeviceRole.SIDE_CAMERA,
                imageWidth = 1080,
                imageHeight = 1920,
            )

        val restored = NearbyPoseFrameCodec.decode(NearbyPoseFrameCodec.encode(frame))

        assertEquals(12L, restored?.sessionId)
        assertEquals(3L, restored?.nodeId)
        assertEquals(DeviceRole.SIDE_CAMERA, restored?.cameraRole)
        assertEquals(1080, restored?.imageWidth)
        assertEquals(1920, restored?.imageHeight)
        assertEquals(frame.landmarks.size, restored?.landmarks?.size)
        assertEquals(1f, restored?.overallConfidence ?: 0f, 0.01f)
        assertEquals(null, NearbyPoseFrameCodec.decode(""))
    }

    @Test
    fun nearbyMessageCodecPreservesPoseFrameSnapshotJson() {
        val codec = GsonNearbyMessageCodec()
        val frame = jumpingJackFrame(timestampMs = 2_500L, isOpen = false)
        val message = NearbyMessage(
            type = NearbyMessageType.POSE_FRAME,
            roomCode = "123456",
            deviceName = "Pixel Node",
            role = DeviceRole.FRONT_CAMERA,
            actionType = ActionType.JUMPING_JACK,
            poseFrameJson = NearbyPoseFrameCodec.encode(frame),
            message = "节点关键点样本",
        )

        val restored = codec.decode(codec.encode(message))
        val restoredFrame = NearbyPoseFrameCodec.decode(restored.poseFrameJson)

        assertEquals(NearbyMessageType.POSE_FRAME, restored.type)
        assertEquals(ActionType.JUMPING_JACK, restored.actionType)
        assertEquals(DeviceRole.FRONT_CAMERA, restored.role)
        assertEquals(frame.landmarks.size, restoredFrame?.landmarks?.size)
        assertEquals(frame.timestampMs, restoredFrame?.timestampMs)
    }

    @Test
    fun remotePoseFrameBufferKeepsLatestFramesWithinLimit() {
        val buffer = RemotePoseFrameBuffer(maxFrames = 2)
        val first = jumpingJackFrame(timestampMs = 1_000L, isOpen = false)
        val second = jumpingJackFrame(timestampMs = 2_000L, isOpen = true)
        val third = jumpingJackFrame(timestampMs = 3_000L, isOpen = false)

        buffer.add(first)
        buffer.add(second)
        buffer.add(third)

        val frames = buffer.snapshot()
        assertEquals(2, buffer.size())
        assertEquals(2_000L, frames.first().timestampMs)
        assertEquals(3_000L, frames.last().timestampMs)
    }

    @Test
    fun actionAnalysisSavePolicyAllowsSummaryOnlySaveButDoesNotFakePoseFrames() {
        assertEquals(null, ActionAnalysisSavePolicy.errorFor(remoteSummaryCount = 1, remotePoseFrameCount = 0))
        assertEquals(null, ActionAnalysisSavePolicy.errorFor(remoteSummaryCount = 1, remotePoseFrameCount = 1))
        assertEquals(null, ActionAnalysisSavePolicy.errorFor(remoteSummaryCount = 0, remotePoseFrameCount = 0))
        assertTrue(ActionAnalysisSavePolicy.useRecognizedPoseFrames(remotePoseFrameCount = 1))
        assertTrue(!ActionAnalysisSavePolicy.useRecognizedPoseFrames(remotePoseFrameCount = 0))
    }

    @Test
    fun nearbyMessageDiagnosticsEstimatesReasonableLatency() {
        val message = NearbyMessage(
            type = NearbyMessageType.HEARTBEAT,
            timestampMs = 1_000L,
        )

        assertEquals(240, NearbyMessageDiagnostics.estimateDelayMs(message, receivedAtMs = 1_240L))
        assertEquals("240ms 前", NearbyMessageDiagnostics.formatAge(nowMs = 1_240L, timestampMs = 1_000L))
    }

    @Test
    fun nearbyMessageDiagnosticsRejectsClockSkewAndPrefersReportedDelay() {
        val futureMessage = NearbyMessage(
            type = NearbyMessageType.HEARTBEAT,
            timestampMs = 5_000L,
        )
        val staleMessage = NearbyMessage(
            type = NearbyMessageType.HEARTBEAT,
            timestampMs = 1_000L,
        )
        val reportedMessage = NearbyMessage(
            type = NearbyMessageType.HEARTBEAT,
            timestampMs = 1_000L,
            networkDelayMs = 88,
        )

        assertEquals(null, NearbyMessageDiagnostics.estimateDelayMs(futureMessage, receivedAtMs = 4_000L))
        assertEquals(null, NearbyMessageDiagnostics.estimateDelayMs(staleMessage, receivedAtMs = 80_000L))
        assertEquals(88, NearbyMessageDiagnostics.estimateDelayMs(reportedMessage, receivedAtMs = 2_000L))
        assertEquals(320, NearbyMessageDiagnostics.roundTripDelayMs(sentAtMs = 1_000L, receivedAtMs = 1_320L))
        assertEquals(null, NearbyMessageDiagnostics.roundTripDelayMs(sentAtMs = 2_000L, receivedAtMs = 1_000L))
        assertEquals("1.5s 前", NearbyMessageDiagnostics.formatAge(nowMs = 2_500L, timestampMs = 1_000L))
    }

    @Test
    fun nearbyConnectionManagerChecksRoomCodeBeforeDiscoveryAndMessageHandling() {
        val source = readMainKotlin("nearby/connection/NearbyConnectionManager.kt")

        assertTrue(source.contains("endpointName.startsWith(\"智姿镜-${'$'}{formatRoomCode(cleanRoomCode)}-\")"))
        assertTrue(source.contains("matchesCurrentRoom(message.roomCode)"))
        assertTrue(source.contains("已忽略其它房间的连接消息。"))
        assertTrue(source.contains("if (handleMessage(endpointId, message, receivedAtMs))"))
        assertTrue(source.contains("return false"))
        assertTrue(!source.contains("contains(roomCode.filter"))
    }

    @Test
    fun navigationClearsStaleAuthTrainingAndNearbyState() {
        val registerSource = readMainKotlin("ui/auth/RegisterActivity.kt")
        val resultSource = readMainKotlin("ui/result/ResultActivity.kt")
        val historyDetailSource = readMainKotlin("ui/history/HistoryDetailActivity.kt")
        val mainSource = readMainKotlin("ui/main/MainActivity.kt")
        val sessionSource = readMainKotlin("nearby/connection/NearbyRoomSession.kt")

        assertTrue(registerSource.contains("Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK"))
        assertTrue(resultSource.contains("HistoryDetailActivity::class.java"))
        assertTrue(historyDetailSource.contains("ReturnTarget.HOME"))
        assertTrue(historyDetailSource.contains("MainActivity::class.java"))
        assertTrue(historyDetailSource.contains("Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP"))
        assertTrue(mainSource.contains("NearbyRoomSession.stopIfCreated()"))
        assertTrue(sessionSource.contains("fun stopIfCreated()"))
    }

    @Test
    fun trainingSaveSuccessSafelyOpensDetailPage() {
        val analysisSource = readMainKotlin("ui/analysis/ActionAnalysisActivity.kt")
        val cameraSource = readMainKotlin("ui/camera/CameraNodeActivity.kt")

        assertTrue(analysisSource.contains("HistoryDetailActivity::class.java"))
        assertTrue(analysisSource.contains("openSavedTrainingDetail(sessionId)"))
        assertTrue(analysisSource.contains("HistoryDetailActivity.RETURN_TARGET_HOME"))
        assertTrue(analysisSource.contains("runCatching {"))
        assertTrue(analysisSource.contains("训练已保存，但打开详情失败"))
        assertTrue(analysisSource.contains("binding.finishTrainingButton.text = \"已保存，正在打开详情...\""))
        assertTrue(cameraSource.contains("HistoryDetailActivity::class.java"))
        assertTrue(cameraSource.contains("openSavedTrainingDetail(sessionId)"))
        assertTrue(cameraSource.contains("HistoryDetailActivity.RETURN_TARGET_HOME"))
        assertTrue(cameraSource.contains("runCatching {"))
        assertTrue(cameraSource.contains("训练已保存，但打开详情失败"))
        assertTrue(cameraSource.contains("binding.saveTrainingButton.text = \"已保存，正在打开详情...\""))
    }

    @Test
    fun cameraNodePageRemovesManualPreviewAndSupportsVideoPauseAndSaveBlocking() {
        val layout = readMainLayout("activity_camera_node.xml")
        val source = readMainKotlin("ui/camera/CameraNodeActivity.kt")

        assertTrue(!layout.contains("startCameraButton"))
        assertTrue(!source.contains("startCameraButton"))
        assertTrue(layout.contains("android:id=\"@+id/toggleVideoButton\""))
        assertTrue(layout.contains("android:id=\"@+id/pauseRecognitionButton\""))
        assertTrue(layout.contains("android:id=\"@+id/trainingControlHintText\""))
        assertTrue(layout.contains("android:id=\"@+id/actionValueText\""))
        assertTrue(layout.contains("android:id=\"@+id/countValueText\""))
        assertTrue(layout.contains("android:id=\"@+id/problemValueText\""))
        assertTrue(layout.contains("android:text=\"次数\""))
        assertFalse(layout.contains("次数/时长"))
        assertTrue(source.contains("binding.countValueText.text = \"${'$'}{state.totalCount} 次\""))
        assertTrue(source.contains("val progressText = \"${'$'}{message.totalCount ?: 0} 次\""))
        assertFalse(layout.contains("android:id=\"@+id/scoreValueText\""))
        assertFalse(layout.contains("android:id=\"@+id/connectionStatusText\""))
        assertFalse(layout.contains("android:id=\"@+id/videoStatusText\""))
        assertFalse(layout.contains("android:id=\"@+id/diagnosticsText\""))
        assertFalse(source.contains("binding.diagnosticsText"))
        assertFalse(source.contains("binding.scoreValueText"))
        assertFalse(source.contains("binding.connectionStatusText"))
        assertFalse(source.contains("binding.videoStatusText"))
        assertFalse(source.contains("关键点数量："))
        assertFalse(source.contains("已分析 "))
        assertTrue(source.contains("renderTrainingDashboard"))
        assertTrue(source.contains("maybeBroadcastHostDashboardState"))
        assertTrue(source.contains("NearbyRoomSession.manager(this).sendHostAnalysisStatus"))
        assertTrue(source.contains("!trainingStarted || isRemoteControlledNode || !canControlRemoteNodes()"))
        assertTrue(source.contains("HOST_DASHBOARD_STATUS_SEND_INTERVAL_MS"))
        assertTrue(source.contains("lastHostDashboardStatusSignature = \"\""))
        assertTrue(source.contains("当前次数：${'$'}{state.totalCount}"))
        assertTrue(source.contains("compactStatusText"))
        assertTrue(source.contains("hostAnalysisStatusText"))
        assertTrue(source.contains("主控：${'$'}actionText，${'$'}progressText"))
        assertTrue(source.contains("enableVideoSaving"))
        assertTrue(source.contains("视频保存已开启"))
        assertTrue(source.contains("isRecognitionPaused"))
        assertTrue(source.contains("resolveSaveState"))
        assertTrue(source.contains("actionProgressTracker.record"))
        assertTrue(source.contains("actionProgressTracker.bestFor(actionType)"))
        assertTrue(source.contains("replaySaveStateFromFrames"))
        assertTrue(source.contains("promoteRecognizedSquatAttempt"))
        assertTrue(source.contains("hasSaveableSquatAttempt"))
        assertTrue(source.contains("按 1 次深蹲尝试保存"))
        assertTrue(source.contains("blockTrainingSave"))
        assertTrue(source.contains("保存受阻"))
        assertTrue(source.contains("当前训练仍保留"))
        assertFalse(source.contains("finishWithNoValidAction"))
        assertFalse(source.contains("未成功识别有效动作"))
    }

    @Test
    fun trainingControlIsDrivenByHostAndCameraNodeSupportsPinchZoom() {
        val cameraSource = readMainKotlin("ui/camera/CameraNodeActivity.kt")
        val analysisSource = readMainKotlin("ui/analysis/ActionAnalysisActivity.kt")
        val nearbySource = readMainKotlin("nearby/connection/NearbyConnectionManager.kt")
        val messageSource = readMainKotlin("nearby/message/NearbyMessage.kt")
        val cameraLayout = readMainLayout("activity_camera_node.xml")
        val analysisLayout = readMainLayout("activity_action_analysis.xml")

        assertTrue(messageSource.contains("PAUSE_ANALYSIS"))
        assertTrue(messageSource.contains("RESUME_ANALYSIS"))
        assertTrue(messageSource.contains("HOST_ANALYSIS_STATUS"))
        assertTrue(nearbySource.contains("fun sendPauseAnalysis"))
        assertTrue(nearbySource.contains("fun sendResumeAnalysis"))
        assertTrue(nearbySource.contains("fun sendHostAnalysisStatus"))
        assertTrue(analysisLayout.contains("android:id=\"@+id/pauseRemoteTrainingButton\""))
        assertTrue(analysisSource.contains("toggleRemoteTrainingPause"))
        assertTrue(analysisSource.contains("sendPauseAnalysis(actionType)"))
        assertTrue(analysisSource.contains("sendResumeAnalysis(actionType)"))
        assertTrue(analysisSource.contains("maybeSendHostAnalysisStatus"))
        assertTrue(analysisSource.contains("actionProgressTracker.bestFor(actionType)"))
        assertTrue(analysisSource.contains("recordProgress("))
        assertTrue(cameraSource.contains("NearbyMessageType.PAUSE_ANALYSIS"))
        assertTrue(cameraSource.contains("NearbyMessageType.RESUME_ANALYSIS"))
        assertTrue(cameraSource.contains("NearbyMessageType.HOST_ANALYSIS_STATUS"))
        assertTrue(cameraSource.contains("applyHostAnalysisStatus"))
        assertTrue(cameraSource.contains("hostAnalysisStatusText"))
        assertTrue(cameraSource.contains("maybeBroadcastHostDashboardState"))
        assertTrue(cameraSource.contains("sendHostAnalysisStatus"))
        assertTrue(cameraSource.contains("isRemoteControlledNode"))
        assertTrue(cameraSource.contains("!state.isHostSession"))
        assertTrue(cameraSource.contains("renderTrainingControls"))
        assertTrue(cameraSource.contains("View.GONE"))
        assertTrue(cameraLayout.contains("主控端统一开始、暂停和结束训练"))
        assertTrue(cameraSource.contains("开始、暂停和结束均由主控端统一发起"))
        assertTrue(cameraSource.contains("ScaleGestureDetector"))
        assertTrue(cameraSource.contains("setZoomRatio"))
        assertTrue(cameraSource.contains("boundCamera"))
    }

    @Test
    fun cameraNodeAttemptsRecognitionForAnyAssignedCameraRole() {
        val cameraSource = readMainKotlin("ui/camera/CameraNodeActivity.kt")
        val aggregatorSource = readMainKotlin("ui/analysis/RemoteAnalysisSummaryAggregator.kt")
        val analysisSource = readMainKotlin("ui/analysis/ActionAnalysisActivity.kt")

        assertTrue(cameraSource.contains("val canDetermineActionType = currentCameraRole != DeviceRole.UNKNOWN"))
        assertTrue(cameraSource.contains("ruleBasedActionClassifier.classify"))
        assertTrue(cameraSource.contains("当前${'$'}{currentCameraRole.displayText()}会尝试识别"))
        assertTrue(!cameraSource.contains("不负责判定动作类型"))
        assertTrue(aggregatorSource.contains("node.message.actionType.isTrainingAction"))
        assertTrue(aggregatorSource.contains("node.message.actionConfidence ?: 0f"))
        assertTrue(analysisSource.contains("message.actionType != ActionType.UNKNOWN && message.role == DeviceRole.FRONT_CAMERA"))
    }

    @Test
    fun cameraNodeRoleResolverKeepsAssignedCameraRoles() {
        assertEquals(DeviceRole.FRONT_CAMERA, CameraNodeRoleResolver.captureRole(DeviceRole.FRONT_CAMERA))
        assertEquals(DeviceRole.SIDE_CAMERA, CameraNodeRoleResolver.captureRole(DeviceRole.SIDE_CAMERA))
        assertEquals(DeviceRole.BACKUP_CAMERA, CameraNodeRoleResolver.captureRole(DeviceRole.BACKUP_CAMERA))
        assertEquals(DeviceRole.FRONT_CAMERA, CameraNodeRoleResolver.captureRole(DeviceRole.UNKNOWN))
        assertEquals(DeviceRole.FRONT_CAMERA, CameraNodeRoleResolver.captureRole(DeviceRole.HOST))
    }

    @Test
    fun pendingVideoSaveCoordinatorPreservesRemoteSaveIntent() {
        val coordinator = PendingVideoSaveCoordinator()

        assertTrue(coordinator.request(triggeredByRemote = true))
        assertTrue(coordinator.isPending())
        assertTrue(!coordinator.request(triggeredByRemote = false))
        assertEquals(true, coordinator.consumeTriggeredByRemote())
        assertEquals(null, coordinator.consumeTriggeredByRemote())
        assertTrue(!coordinator.isPending())
    }

    @Test
    fun deviceGroupActivityHandlesRemoteCountdownAndAnalysisMessages() {
        val source = readMainKotlin("ui/device/DeviceGroupActivity.kt")

        assertTrue(source.contains("override fun onNearbyMessageReceived"))
        assertTrue(source.contains("NearbyMessageType.START_COUNTDOWN"))
        assertTrue(source.contains("startRemoteNodeCountdown"))
        assertTrue(source.contains("NearbyMessageType.START_ANALYSIS"))
        assertTrue(source.contains("NearbyMessageType.END_TRAINING"))
        assertTrue(source.contains("trainingStatusText"))
        assertTrue(source.contains("showRemoteTrainingEnded"))
        assertTrue(source.contains("openCameraNodeFromRemote"))
        assertTrue(source.contains("autoStart = true"))
        assertTrue(source.contains("CameraNodeActivity.EXTRA_REMOTE_AUTO_START"))
        assertTrue(source.contains("CameraNodeActivity::class.java"))
    }

    @Test
    fun remoteStartAndEndTrainingUpdateJoinedPhoneDisplay() {
        val prepareSource = readMainKotlin("ui/prepare/PrepareActivity.kt")
        val cameraSource = readMainKotlin("ui/camera/CameraNodeActivity.kt")
        val deviceGroupSource = readMainKotlin("ui/device/DeviceGroupActivity.kt")

        assertTrue(prepareSource.contains("NearbyMessageType.START_ANALYSIS"))
        assertTrue(prepareSource.contains("NearbyMessageType.END_TRAINING"))
        assertTrue(prepareSource.contains("CameraNodeActivity.EXTRA_REMOTE_AUTO_START"))
        assertTrue(cameraSource.contains("const val EXTRA_REMOTE_AUTO_START"))
        assertTrue(cameraSource.contains("maybeStartRemoteTrainingFromIntent"))
        assertTrue(cameraSource.contains("beginTrainingSession(expectedActionType, triggeredByRemote = true)"))
        assertTrue(cameraSource.contains("已收到主控端开始训练指令，当前训练已经在进行中。"))
        assertTrue(cameraSource.contains("stopRemoteControlledTraining"))
        assertTrue(cameraSource.contains("正在保存本机采集结果，保存完成后会打开训练详情"))
        assertTrue(cameraSource.contains("saveRecognizedTraining(triggeredByRemote = true)"))
        assertTrue(deviceGroupSource.contains("训练状态：主控端已开始训练"))
        assertTrue(deviceGroupSource.contains("训练状态：主控端已结束本轮训练"))
        assertTrue(deviceGroupSource.contains("主控端已结束本轮训练"))
        assertTrue(deviceGroupSource.contains("NearbyMessageType.PAUSE_ANALYSIS"))
        assertTrue(deviceGroupSource.contains("NearbyMessageType.RESUME_ANALYSIS"))
    }

    @Test
    fun hostCameraControlsJoinedPhoneWhileJoinedPhoneSavesLocalResult() {
        val cameraSource = readMainKotlin("ui/camera/CameraNodeActivity.kt")

        assertTrue(cameraSource.contains("broadcastStartCountdownIfNeeded"))
        assertTrue(cameraSource.contains("sendStartCountdown(seconds, actionType)"))
        assertTrue(cameraSource.contains("broadcastStartAnalysisIfNeeded"))
        assertTrue(cameraSource.contains("sendStartAnalysis(actionType)"))
        assertTrue(cameraSource.contains("broadcastPauseOrResumeIfNeeded"))
        assertTrue(cameraSource.contains("sendPauseAnalysis(actionType)"))
        assertTrue(cameraSource.contains("sendResumeAnalysis(actionType)"))
        assertTrue(cameraSource.contains("broadcastEndTrainingIfNeeded"))
        assertTrue(cameraSource.contains("sendEndTraining(actionType)"))
        assertTrue(cameraSource.contains("remoteEndBroadcastForSession"))
        assertTrue(cameraSource.contains("shouldRunActionRecognition"))
        assertTrue(cameraSource.contains("trainingStarted && !isPaused"))
        assertTrue(cameraSource.contains("showSkeletonOverlay || isRemoteControlledNode"))
        assertTrue(cameraSource.contains("本机将按主控选择的"))
        assertTrue(cameraSource.contains("采集、计数，并在主控结束后显示本机结果"))
        assertTrue(cameraSource.contains("isRemoteControlledNode ||"))
    }

    @Test
    fun deviceGroupActivityOffersFourRoleButtonsWithSelectedColors() {
        val source = readMainKotlin("ui/device/DeviceGroupActivity.kt")
        val managerSource = readMainKotlin("nearby/connection/NearbyConnectionManager.kt")
        val joinRoomSource = readMainKotlin("ui/room/JoinRoomActivity.kt")
        val roomSource = readMainKotlin("ui/room/RoomActivity.kt")
        val layout = readMainLayout("activity_device_group.xml")

        assertTrue(source.contains("assignSelfRole(DeviceRole.FRONT_CAMERA)"))
        assertTrue(source.contains("assignSelfRole(DeviceRole.SIDE_CAMERA)"))
        assertTrue(source.contains("assignJoinedRole(DeviceRole.FRONT_CAMERA)"))
        assertTrue(source.contains("assignJoinedRole(DeviceRole.SIDE_CAMERA)"))
        assertTrue(source.contains("manager.assignLocalRole(role)"))
        assertTrue(source.contains("manager.assignRole(endpoint.endpointId, role)"))
        assertTrue(source.contains("renderRoleButtonState"))
        assertTrue(source.contains("backgroundTintList"))
        assertTrue(source.contains("R.color.zzj_primary"))
        assertTrue(source.contains("isHostController"))
        assertTrue(source.contains("View.GONE"))
        assertTrue(source.contains("加入房间的手机只显示当前机位，请在主控端配置机位"))
        assertTrue(source.contains("state.isHostSession || state.localRole == DeviceRole.HOST"))
        assertTrue(managerSource.contains("fun assignRole(endpointId: String, role: DeviceRole)"))
        assertTrue(managerSource.contains("val isHostSession: Boolean = false"))
        assertTrue(managerSource.contains("isHostSession = true"))
        assertTrue(managerSource.contains("localRole = DeviceRole.UNKNOWN"))
        assertTrue(managerSource.contains("NearbyMessageType.ASSIGN_ROLE"))
        assertTrue(managerSource.contains("PRIMARY_CAMERA_ROLES"))
        assertTrue(joinRoomSource.contains("Intent(this, DeviceGroupActivity::class.java)"))
        assertTrue(!joinRoomSource.contains("，机位："))
        assertTrue(!roomSource.contains("，机位："))
        assertTrue(layout.contains("android:id=\"@+id/localRoleText\""))
        assertTrue(layout.contains("android:id=\"@+id/roleControlHintText\""))
        assertTrue(!layout.contains("autoAssignButton"))
        assertTrue(layout.contains("android:id=\"@+id/assignSelfFrontButton\""))
        assertTrue(layout.contains("android:id=\"@+id/assignSelfSideButton\""))
        assertTrue(layout.contains("android:id=\"@+id/assignJoinedFrontButton\""))
        assertTrue(layout.contains("android:id=\"@+id/assignJoinedSideButton\""))
        assertTrue(layout.contains("正面机位：未分配"))
        assertTrue(layout.contains("侧面机位：未分配"))
        assertTrue(layout.contains("设为本机"))
        assertTrue(layout.contains("设为副机"))
        assertTrue(layout.contains("android:id=\"@+id/frontSlotActions\""))
        assertTrue(layout.contains("android:id=\"@+id/sideSlotActions\""))
    }

    @Test
    fun resultPageShowsLayeredSummaryInsteadOfDenseRawDetails() {
        val layout = readMainLayout("activity_result.xml")
        val source = readMainKotlin("ui/result/ResultActivity.kt")
        val formatterSource = readMainKotlin("ui/result/TrainingDetailFormatter.kt")

        assertTrue(layout.contains("android:id=\"@+id/resultActionText\""))
        assertTrue(layout.contains("android:id=\"@+id/resultPrimaryMetricText\""))
        assertTrue(layout.contains("android:id=\"@+id/resultScoreText\""))
        assertTrue(layout.contains("android:id=\"@+id/resultSuggestionText\""))
        assertTrue(layout.contains("android:id=\"@+id/qualifiedCountText\""))
        assertFalse(layout.contains("android:id=\"@+id/reviewCountText\""))
        assertTrue(layout.contains("android:id=\"@+id/durationText\""))
        assertTrue(layout.contains("android:id=\"@+id/confidenceText\""))
        assertTrue(layout.contains("android:id=\"@+id/homeButton\""))
        assertTrue(layout.contains("android:text=\"报告\""))
        assertTrue(layout.contains("android:id=\"@+id/exportPdfButton\""))
        assertTrue(layout.contains("android:text=\"生成pdf报告\""))
        assertTrue(layout.contains("android:id=\"@+id/sharePdfReportButton\""))
        assertTrue(layout.contains("android:text=\"分享pdf报告\""))
        assertTrue(layout.split("android:id=\"@+id/sharePdfReportButton\"")[1].contains("android:enabled=\"false\""))
        assertTrue(layout.split("android:id=\"@+id/sharePdfReportButton\"")[1].contains("android:alpha=\"0.45\""))
        assertFalse(layout.contains("android:id=\"@+id/exportJsonButton\""))
        assertFalse(layout.contains("android:id=\"@+id/exportPoseJsonButton\""))
        assertFalse(layout.contains("android:id=\"@+id/shareReportButton\""))
        assertTrue(source.contains("HistoryDetailActivity::class.java"))
        assertFalse(source.contains("renderSummaryResult"))
        assertFalse(source.contains("TrainingDetailFormatter.fromSummary"))
        assertTrue(!source.contains("TrainingReportFormatter.actionDetailsText(actions)"))
        assertTrue(!source.contains("TrainingReportStatsCalculator.qualificationRuleText()"))
        assertTrue(!source.contains("latestExportFile?.absolutePath"))
        assertTrue(!source.contains("findPoseFrames"))
        assertTrue(!source.contains("findDeviceNodes"))
        assertTrue(!formatterSource.contains("qualificationRuleText"))
        assertTrue(!formatterSource.contains("absolutePath"))
    }

    @Test
    fun historyDetailPageUsesSameTrainingDetailSurface() {
        val layout = readMainLayout("activity_history_detail.xml")
        val historySource = readMainKotlin("ui/history/HistoryActivity.kt")
        val source = readMainKotlin("ui/history/HistoryDetailActivity.kt")

        assertTrue(layout.contains("android:id=\"@+id/resultActionText\""))
        assertTrue(layout.contains("android:id=\"@+id/resultPrimaryMetricText\""))
        assertTrue(layout.contains("android:id=\"@+id/resultScoreText\""))
        assertTrue(layout.contains("android:id=\"@+id/resultSuggestionText\""))
        assertTrue(layout.contains("android:id=\"@+id/qualifiedCountText\""))
        assertFalse(layout.contains("android:id=\"@+id/reviewCountText\""))
        assertTrue(layout.contains("android:id=\"@+id/durationText\""))
        assertTrue(layout.contains("android:id=\"@+id/confidenceText\""))
        assertTrue(layout.contains("android:id=\"@+id/resultText\""))
        assertTrue(layout.contains("android:id=\"@+id/homeButton\""))
        assertTrue(layout.contains("android:text=\"返回历史记录\""))
        assertTrue(layout.contains("android:text=\"报告\""))
        assertTrue(layout.contains("android:id=\"@+id/exportPdfButton\""))
        assertTrue(layout.contains("android:text=\"生成pdf报告\""))
        assertTrue(layout.contains("android:id=\"@+id/sharePdfReportButton\""))
        assertTrue(layout.contains("android:text=\"分享pdf报告\""))
        assertTrue(layout.split("android:id=\"@+id/sharePdfReportButton\"")[1].contains("android:enabled=\"false\""))
        assertTrue(layout.split("android:id=\"@+id/sharePdfReportButton\"")[1].contains("android:alpha=\"0.45\""))
        assertFalse(layout.contains("android:id=\"@+id/exportJsonButton\""))
        assertFalse(layout.contains("android:id=\"@+id/exportPoseJsonButton\""))
        assertFalse(layout.contains("android:id=\"@+id/shareReportButton\""))
        assertTrue(!layout.contains("android:id=\"@+id/detailText\""))
        assertTrue(!layout.contains("android:id=\"@+id/exportRecordsText\""))
        assertTrue(!layout.contains("android:id=\"@+id/backButton\""))
        assertTrue(source.contains("TrainingDetailFormatter.fromSummary"))
        assertTrue(source.contains("ReportShareHelper.shareReport(this, file)"))
        assertTrue(source.contains("setSharePdfReportEnabled(false)"))
        assertTrue(source.contains("setSharePdfReportEnabled(latestGeneratedPdfFile != null)"))
        assertTrue(source.contains("DISABLED_SHARE_BUTTON_ALPHA = 0.45f"))
        assertTrue(source.contains("latestGeneratedPdfFile = result.file.takeIf"))
        assertTrue(source.contains("file.extension.equals(\"pdf\", ignoreCase = true)"))
        assertTrue(source.contains("ReportShareHelper.canShareReportFile(filesDir, file)"))
        assertTrue(source.contains("请先生成 PDF 报告"))
        assertTrue(source.contains("binding.homeButton.setOnClickListener { handleReturnButton() }"))
        assertTrue(historySource.contains("runCatching { TrainingRepository.findSummary(this, sessionId) }"))
        assertTrue(historySource.contains("HistoryDetailActivity.RETURN_TARGET_HISTORY"))
        assertTrue(historySource.contains("showHistoryDebug"))
        assertTrue(historySource.contains("打开详情前读取记录失败"))
        assertTrue(historySource.contains("打开详情页面失败"))
        assertTrue(historySource.contains("无法打开详情：记录"))
        assertTrue(source.contains("setupContent()"))
        assertTrue(source.contains("训练详情页面启动失败"))
        assertTrue(source.contains("showStartupErrorPage(message)"))
        assertTrue(source.contains("训练详情暂时无法打开"))
        assertTrue(source.contains("返回上一页"))
        assertTrue(source.contains("训练详情读取失败"))
        assertTrue(source.contains("训练详情渲染失败"))
        assertTrue(source.contains("关键帧预览加载失败"))
        assertTrue(source.contains("renderFatalDetailError"))
        assertTrue(source.contains("decodeKeyFrameBitmap"))
        assertTrue(source.contains("previewSampleSize"))
        assertTrue(source.contains("KEY_FRAME_PREVIEW_MAX_SIZE"))
        assertTrue(source.contains("if (::videoPlaybackBinder.isInitialized)"))
        assertTrue(source.contains("EXTRA_RETURN_TARGET"))
        assertTrue(source.contains("RETURN_TARGET_HISTORY"))
        assertTrue(source.contains("RETURN_TARGET_HOME"))
        assertTrue(source.contains("ReturnTarget.HISTORY"))
        assertTrue(source.contains("ReturnTarget.HOME"))
        assertTrue(source.contains("binding.homeButton.text = returnTarget.buttonText"))
        assertTrue(source.contains("MainActivity::class.java"))
        assertTrue(source.contains("Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP"))
        assertTrue(!source.contains("TrainingReportStatsCalculator.qualificationRuleText()"))
        assertTrue(!source.contains("TrainingReportFormatter.actionDetailsText(actions)"))
        assertTrue(!source.contains("formatExportRecords"))
        assertTrue(!source.contains("findPoseFrames"))
        assertTrue(!source.contains("findDeviceNodes"))
        assertTrue(!source.contains("latestExportFile?.absolutePath"))
        assertTrue(!source.contains("record.sessionId"))
    }

    @Test
    fun detailPagesExposeRecordedVideoPlayback() {
        val resultLayout = readMainLayout("activity_result.xml")
        val historyLayout = readMainLayout("activity_history_detail.xml")
        val resultSource = readMainKotlin("ui/result/ResultActivity.kt")
        val historySource = readMainKotlin("ui/history/HistoryDetailActivity.kt")
        val playbackSource = readMainKotlin("ui/result/TrainingVideoPlaybackBinder.kt")

        listOf(resultLayout, historyLayout).forEach { layout ->
            assertTrue(layout.contains("训练视频回放"))
            assertTrue(layout.contains("android:id=\"@+id/videoPlaybackStatusText\""))
            assertTrue(layout.contains("android:id=\"@+id/videoPlayerView\""))
            val videoPlayerBlock = layout.substringAfter("android:id=\"@+id/videoPlayerView\"")
                .substringBefore("/>")
            assertTrue(videoPlayerBlock.contains("android:layout_width=\"match_parent\""))
            assertTrue(videoPlayerBlock.contains("android:layout_height=\"360dp\""))
            assertTrue(layout.contains("<TextureView"))
            assertFalse(
                videoPlayerBlock.contains("android:background=")
            )
            assertFalse(layout.contains("<VideoView"))
            assertTrue(layout.contains("android:id=\"@+id/videoPlaybackControlRow\""))
            assertTrue(layout.contains("android:id=\"@+id/playPauseVideoButton\""))
            assertTrue(layout.contains("android:id=\"@+id/videoSeekBar\""))
            assertTrue(layout.contains("android:id=\"@+id/videoPositionText\""))
            assertTrue(layout.contains("android:id=\"@+id/videoSwitchRow\""))
            assertTrue(layout.contains("android:id=\"@+id/previousVideoButton\""))
            assertTrue(layout.contains("android:id=\"@+id/nextVideoButton\""))
        }

        assertTrue(resultSource.contains("HistoryDetailActivity::class.java"))
        assertFalse(resultSource.contains("TrainingVideoPlaybackBinder"))

        listOf(historySource).forEach { source ->
            assertTrue(source.contains("TrainingVideoPlaybackBinder"))
            assertTrue(source.contains("videoPlaybackBinder.render(result.videoFiles)"))
            assertTrue(source.contains("override fun onPause()"))
            assertTrue(source.contains("videoPlaybackBinder.pause()"))
            assertTrue(source.contains("override fun onDestroy()"))
            assertTrue(source.contains("videoPlaybackBinder.stop()"))
        }

        assertFalse(playbackSource.contains("MediaController"))
        assertTrue(playbackSource.contains("SeekBar"))
        assertTrue(playbackSource.contains("playPauseButton"))
        assertTrue(playbackSource.contains("progressHandler"))
        assertTrue(playbackSource.contains("TextureView"))
        assertTrue(playbackSource.contains("MediaPlayer"))
        assertTrue(playbackSource.contains("setSurface(surface)"))
        assertTrue(playbackSource.contains("setDataSource(file.absolutePath)"))
        assertTrue(playbackSource.contains("statusText.visibility = View.GONE"))
        assertFalse(playbackSource.contains("Uri.fromFile(file)"))
        assertFalse(playbackSource.contains("videoView.setVideoURI"))
        assertFalse(playbackSource.contains("TrainingVideoArtifacts.formatFileSize"))
        assertFalse(playbackSource.contains("段训练视频"))
        assertFalse(playbackSource.contains("正在播放"))
    }

    @Test
    fun trainingDetailFormatterKeepsUserFacingSummaryCompact() {
        val state = TrainingDetailFormatter.fromSummary(
            summary = TrainingSummary(
                sessionId = 42L,
                actionType = ActionType.SQUAT,
                totalCount = 12,
                averageScore = 91f,
                durationMs = 32_000L,
                mainProblem = ProblemType.KNEE_INWARD,
                suggestion = "膝盖朝向脚尖，节奏保持稳定。",
                reportPath = "/private/report.pdf",
                averagePoseConfidence = 0.93f,
            ),
            stats = TrainingReportStats(
                actionCount = 12,
                qualifiedCount = 10,
                reviewCount = 2,
                keyFramePaths = listOf("/private/front.jpg", "/private/side.jpg"),
                frontKeyFramePath = "/private/front.jpg",
                sideKeyFramePath = "/private/side.jpg",
            ),
            videoCount = 1,
        )
        val combinedText = listOf(
            state.actionTitle,
            state.primaryMetric,
            state.scoreText,
            state.suggestionText,
            state.qualifiedText,
            state.reviewText,
            state.durationText,
            state.confidenceText,
            state.reviewSummaryText,
            state.keyFrameText,
        ).joinToString("\n")

        assertTrue(combinedText.contains("主要问题"))
        assertFalse(combinedText.contains("训练表现"))
        assertFalse(combinedText.contains("平均节奏"))
        assertFalse(combinedText.contains("动作记录"))
        assertFalse(combinedText.contains("训练素材"))
        assertTrue(!combinedText.contains("记录 ID"))
        assertTrue(!combinedText.contains("合格规则"))
        assertTrue(!combinedText.contains("/private/"))
    }

    @Test
    fun rgbKeyFrameBackgroundPersistsFromCameraFrameToPreview() {
        val poseModelSource = readMainKotlin("pose/model/PoseModels.kt")
        val mapperSource = readMainKotlin("data/repository/TrainingRecordMapper.kt")
        val repositorySource = readMainKotlin("data/repository/TrainingRepository.kt")
        val cameraSource = readMainKotlin("ui/camera/CameraNodeActivity.kt")
        val keyFrameStoreSource = readMainKotlin("report/frame/KeyFrameStore.kt")

        assertTrue(poseModelSource.contains("val rgbImagePath: String? = null"))
        assertTrue(poseModelSource.contains("val rgbImageBase64: String? = null"))
        assertTrue(mapperSource.contains("frame.rgbImagePath"))
        assertTrue(repositorySource.contains("persistPoseFrameRgbImage"))
        assertTrue(repositorySource.contains("rgbImageBase64"))
        assertTrue(cameraSource.contains("rememberSessionFrame(frame, imageProxy)"))
        assertTrue(cameraSource.contains("saveRgbFrameDraft"))
        assertTrue(cameraSource.contains("Base64.encodeToString"))
        assertTrue(cameraSource.contains("imageProxyToJpegBytes"))
        assertTrue(keyFrameStoreSource.contains("BitmapFactory.decodeFile"))
        assertTrue(keyFrameStoreSource.contains("canvas.drawBitmap"))
    }

    @Test
    fun deviceRoleAssignmentPolicyRejectsSelfAssignmentWhenRemoteRoleIsOccupied() {
        val endpoints = listOf(
            nearbyEndpoint("front", "Pixel Front", DeviceRole.FRONT_CAMERA),
            nearbyEndpoint("side", "Pixel Side", DeviceRole.SIDE_CAMERA),
            nearbyEndpoint("offline-front", "Offline Front", DeviceRole.FRONT_CAMERA, isOnline = false),
        )

        val frontError = DeviceRoleAssignmentPolicy.selfAssignmentError(
            endpoints = endpoints,
            targetRole = DeviceRole.FRONT_CAMERA,
        )
        val sideError = DeviceRoleAssignmentPolicy.selfAssignmentError(
            endpoints = endpoints,
            targetRole = DeviceRole.SIDE_CAMERA,
        )
        val backupError = DeviceRoleAssignmentPolicy.selfAssignmentError(
            endpoints = endpoints,
            targetRole = DeviceRole.BACKUP_CAMERA,
        )

        assertTrue(frontError.orEmpty().contains("Pixel Front"))
        assertTrue(frontError.orEmpty().contains("不能重复选择"))
        assertTrue(sideError.orEmpty().contains("Pixel Side"))
        assertEquals(null, backupError)
    }

    @Test
    fun deviceRoleAssignmentPolicyAvoidsReusingFrontNodeForSideRole() {
        val endpoints = listOf(
            nearbyEndpoint("front", "Pixel Front", DeviceRole.FRONT_CAMERA),
            nearbyEndpoint("side", "Pixel Side", DeviceRole.UNKNOWN),
        )

        val selected = DeviceRoleAssignmentPolicy.selectEndpointForRole(
            endpoints = endpoints,
            targetRole = DeviceRole.SIDE_CAMERA,
        )

        assertEquals("side", selected?.endpointId)
    }

    @Test
    fun deviceRoleAssignmentPolicyAutoAssignsFrontAndSideToDifferentNodes() {
        val endpoints = listOf(
            nearbyEndpoint("node-1", "Pixel 1", DeviceRole.UNKNOWN),
            nearbyEndpoint("node-2", "Pixel 2", DeviceRole.UNKNOWN),
            nearbyEndpoint("node-3", "Pixel 3", DeviceRole.UNKNOWN, isOnline = false),
        )

        val assignments = DeviceRoleAssignmentPolicy.autoAssignPrimaryRoles(endpoints)

        assertEquals(2, assignments.size)
        assertEquals(DeviceRole.FRONT_CAMERA, assignments[0].role)
        assertEquals(DeviceRole.SIDE_CAMERA, assignments[1].role)
        assertTrue(assignments[0].endpointId != assignments[1].endpointId)
        assertTrue(DeviceRoleAssignmentPolicy.assignmentSummary(assignments).contains("Pixel 1"))
    }

    @Test
    fun cameraNodeDiagnosticsTracksPoseSuccessAndRecentEvents() {
        val diagnostics = CameraNodeDiagnostics(fpsWindowMs = 1_000L, maxEvents = 2)

        diagnostics.onImageFrame(1_000L)
        diagnostics.onImageFrame(1_500L)
        diagnostics.onPoseResult(jumpingJackFrame(timestampMs = 1_400L, isOpen = false), 1_500L)
        diagnostics.onPoseResult(null, 1_600L)
        diagnostics.onError("boom", 1_700L)
        diagnostics.onSessionFrameSaved(3, 1_800L)
        diagnostics.recordEvent("事件 A", 1_900L)
        diagnostics.recordEvent("事件 B", 2_000L)
        diagnostics.recordEvent("事件 C", 2_100L)

        val snapshot = diagnostics.snapshot(2_100L)

        assertEquals(2, snapshot.totalImageFrames)
        assertEquals(1, snapshot.poseFrameCount)
        assertEquals(1, snapshot.emptyPoseFrameCount)
        assertEquals(1, snapshot.errorCount)
        assertEquals(3, snapshot.savedPoseFrameCount)
        assertEquals(1f / 3f, snapshot.poseSuccessRate, 0.001f)
        assertEquals(2, snapshot.recentEvents.size)
        assertTrue(snapshot.recentEvents.first().contains("事件 C"))
        assertTrue(snapshot.recentEvents.none { it.contains("事件 A") })
        val formattedText = snapshot.formatText()
        assertTrue(formattedText.contains("人体识别成功/未检出/错误：1 / 1 / 1"))
        assertTrue(formattedText.lines().none { it != it.trimStart() })
        assertTrue(formattedText.none { it == '\t' })
        assertTrue(!formattedText.contains("最近"))
        assertTrue(!formattedText.contains("事件 C"))
    }

    @Test
    fun nearbyPermissionsCoverCommonAndroidVersions() {
        val androidNine = NearbyPermissions.requiredRuntimePermissionsForSdk(Build.VERSION_CODES.P)
        val androidTwelve = NearbyPermissions.requiredRuntimePermissionsForSdk(Build.VERSION_CODES.S)
        val androidThirteen = NearbyPermissions.requiredRuntimePermissionsForSdk(Build.VERSION_CODES.TIRAMISU)

        assertTrue(androidNine.contains(Manifest.permission.ACCESS_COARSE_LOCATION))
        assertTrue(androidNine.contains(Manifest.permission.ACCESS_FINE_LOCATION))
        assertTrue(androidTwelve.contains(Manifest.permission.ACCESS_COARSE_LOCATION))
        assertTrue(androidTwelve.contains(Manifest.permission.ACCESS_FINE_LOCATION))
        assertTrue(androidTwelve.contains(Manifest.permission.BLUETOOTH_SCAN))
        assertTrue(androidThirteen.contains(Manifest.permission.ACCESS_COARSE_LOCATION))
        assertTrue(androidThirteen.contains(Manifest.permission.ACCESS_FINE_LOCATION))
        assertTrue(androidThirteen.contains(Manifest.permission.BLUETOOTH_CONNECT))
        assertTrue(androidThirteen.contains(Manifest.permission.NEARBY_WIFI_DEVICES))
    }

    @Test
    fun reportShareHelperUsesExpectedMimeTypes() {
        assertEquals("application/pdf", ReportShareHelper.mimeType(File("report.pdf")))
        assertEquals("application/json", ReportShareHelper.mimeType(File("report.json")))
        assertEquals("application/octet-stream", ReportShareHelper.mimeType(File("report.bin")))
    }

    @Test
    fun reportShareHelperOnlyAllowsFilesInsidePrivateFilesDir() {
        val root = File(System.getProperty("java.io.tmpdir"), "zhizijing_share_root_${System.nanoTime()}")
        val insideCustomExport = File(root, "exports/42/reports/training_42.pdf")
        val outside = File(root.parentFile, "outside_training_42.pdf")
        insideCustomExport.parentFile?.mkdirs()
        outside.writeText("outside", Charsets.UTF_8)

        assertTrue(ReportShareHelper.isInsideDirectory(root, insideCustomExport))
        assertTrue(!ReportShareHelper.isInsideDirectory(root, outside))
        assertTrue(!ReportShareHelper.isInsideDirectory(root, File(root, "../${outside.name}")))
    }

    @Test
    fun reportShareHelperOnlyAllowsPdfAndJsonReportsInsidePrivateFilesDir() {
        val root = File(System.getProperty("java.io.tmpdir"), "zhizijing_share_type_root_${System.nanoTime()}")
        val pdf = File(root, "exports/42/training_42.pdf")
        val json = File(root, "exports/42/training_42.json")
        val database = File(root, "databases/zhizijing.db")
        val outsideJson = File(root.parentFile, "training_42.json")
        listOf(pdf, json, database).forEach { file ->
            file.parentFile?.mkdirs()
            file.writeText("x", Charsets.UTF_8)
        }
        outsideJson.writeText("{}", Charsets.UTF_8)

        assertTrue(ReportShareHelper.canShareReportFile(root, pdf))
        assertTrue(ReportShareHelper.canShareReportFile(root, json))
        assertTrue(!ReportShareHelper.canShareReportFile(root, database))
        assertTrue(!ReportShareHelper.canShareReportFile(root, outsideJson))
    }

    @Test
    fun reportExportSelectorUsesLatestExistingFile() {
        val root = File(System.getProperty("java.io.tmpdir"), "zhizijing_export_selector_${System.nanoTime()}")
        val missingLatest = File(root, "training_42_missing.pdf")
        val nonReportLatest = File(root, "training_42.db")
        val existingOlder = File(root, "training_42.json")
        val existingOldest = File(root, "training_42_raw_pose.json")
        existingOlder.parentFile?.mkdirs()
        nonReportLatest.writeText("db", Charsets.UTF_8)
        existingOlder.writeText("{}", Charsets.UTF_8)
        existingOldest.writeText("{}", Charsets.UTF_8)
        val missingLatestRecord = ExportRecordEntity(42L, "PDF", missingLatest.absolutePath, 3_000L, 0L).apply {
            exportId = 4L
        }
        val nonReportLatestRecord = ExportRecordEntity(42L, "DB", nonReportLatest.absolutePath, 2_500L, nonReportLatest.length()).apply {
            exportId = 3L
        }
        val existingOlderRecord = ExportRecordEntity(42L, "JSON", existingOlder.absolutePath, 2_000L, existingOlder.length()).apply {
            exportId = 2L
        }
        val existingOldestRecord = ExportRecordEntity(42L, "RAW_POSE_JSON", existingOldest.absolutePath, 1_000L, existingOldest.length()).apply {
            exportId = 1L
        }

        val selected = ReportExportSelector.latestExistingExportFile(
            listOf(
                existingOldestRecord,
                existingOlderRecord,
                nonReportLatestRecord,
                missingLatestRecord,
            )
        )

        assertEquals(existingOlder.canonicalFile, selected?.canonicalFile)
    }

    @Test
    fun reportExportSelectorUsesExportIdWhenTimestampsTie() {
        val root = File(System.getProperty("java.io.tmpdir"), "zhizijing_export_selector_tie_${System.nanoTime()}")
        val first = File(root, "training_42_first.json")
        val second = File(root, "training_42_second.pdf")
        first.parentFile?.mkdirs()
        first.writeText("{}", Charsets.UTF_8)
        second.writeText("%PDF", Charsets.UTF_8)

        val olderInsert = ExportRecordEntity(42L, "JSON", first.absolutePath, 5_000L, first.length()).apply {
            exportId = 10L
        }
        val newerInsert = ExportRecordEntity(42L, "PDF", second.absolutePath, 5_000L, second.length()).apply {
            exportId = 11L
        }

        val selected = ReportExportSelector.latestExistingExportFile(listOf(olderInsert, newerInsert))

        assertEquals(second.canonicalFile, selected?.canonicalFile)
    }

    @Test
    fun exportRecordDaoUsesStableLatestOrdering() {
        val source = readMainJava("data/dao/ExportRecordDao.java")

        assertTrue(source.contains("ORDER BY createdAt DESC, exportId DESC"))
    }

    @Test
    fun keyFramePreviewImagesHaveVisibleWeights() {
        val resultLayout = readMainLayout("activity_result.xml")
        val historyDetailLayout = readMainLayout("activity_history_detail.xml")

        assertTrue(resultLayout.contains("android:id=\"@+id/firstKeyFrameImage\""))
        assertTrue(resultLayout.contains("android:id=\"@+id/secondKeyFrameImage\""))
        assertTrue(resultLayout.split("android:id=\"@+id/firstKeyFrameImage\"")[1].contains("android:layout_weight=\"1\""))
        assertTrue(resultLayout.split("android:id=\"@+id/secondKeyFrameImage\"")[1].contains("android:layout_weight=\"1\""))
        assertTrue(historyDetailLayout.split("android:id=\"@+id/firstKeyFrameImage\"")[1].contains("android:layout_weight=\"1\""))
        assertTrue(historyDetailLayout.split("android:id=\"@+id/secondKeyFrameImage\"")[1].contains("android:layout_weight=\"1\""))
    }

    @Test
    fun poseMathComputesRightAngle() {
        val a = LandmarkPoint("A", 1f, 0f, confidence = 1f)
        val vertex = LandmarkPoint("B", 0f, 0f, confidence = 1f)
        val c = LandmarkPoint("C", 0f, 1f, confidence = 1f)

        assertEquals(90f, PoseMath.angle(a, vertex, c), 0.01f)
    }

    @Test
    fun poseMathNormalizesDistanceByReference() {
        val a = LandmarkPoint("A", 0f, 0f, confidence = 1f)
        val b = LandmarkPoint("B", 0.3f, 0f, confidence = 1f)

        assertEquals(0.3f, PoseMath.horizontalDistance(a, b), 0.001f)
        assertEquals(1.5f, PoseMath.normalizedDistance(a, b, 0.2f), 0.001f)
    }

    @Test
    fun squatAnalyzerCountsCompleteRep() {
        val analyzer = SimpleSquatAnalyzer()

        analyzer.analyze(squatFrame(timestampMs = 1_000L, hipY = 0.52f))
        analyzer.analyze(squatFrame(timestampMs = 1_800L, hipY = 0.64f))
        analyzer.analyze(squatFrame(timestampMs = 2_600L, hipY = 0.76f))
        analyzer.analyze(squatFrame(timestampMs = 3_400L, hipY = 0.62f))
        val result = analyzer.analyze(squatFrame(timestampMs = 4_200L, hipY = 0.52f))

        assertEquals(1, result.totalCount)
        assertEquals(SquatStage.STANDING, result.currentStage)
        assertEquals(ProblemType.NONE, result.problemType)
        assertTrue((result.kneeAngle ?: 0f) > 150f)
    }

    @Test
    fun squatAnalyzerDetectsRhythmAbnormalWhenRepIsTooFast() {
        val analyzer = SimpleSquatAnalyzer()

        analyzer.analyze(squatFrame(timestampMs = 1_000L, hipY = 0.52f))
        analyzer.analyze(squatFrame(timestampMs = 1_100L, hipY = 0.64f))
        analyzer.analyze(squatFrame(timestampMs = 1_200L, hipY = 0.76f))
        analyzer.analyze(squatFrame(timestampMs = 1_300L, hipY = 0.62f))
        val result = analyzer.analyze(squatFrame(timestampMs = 1_400L, hipY = 0.52f))

        assertEquals(1, result.totalCount)
        assertEquals(ProblemType.RHYTHM_ABNORMAL, result.problemType)
        assertEquals(95f, result.score, 0.01f)
        assertTrue(result.suggestion.orEmpty().contains("节奏"))
    }

    @Test
    fun squatAnalyzerDetectsKneeInward() {
        val result = SimpleSquatAnalyzer().analyze(
            squatFrame(
                timestampMs = 1L,
                hipY = 0.76f,
                leftKneeX = 0.48f,
                rightKneeX = 0.52f,
            )
        )

        assertEquals(ProblemType.KNEE_INWARD, result.problemType)
        assertEquals(80f, result.score, 0.01f)
        assertTrue(result.suggestion.orEmpty().contains("膝盖朝向脚尖"))
    }

    @Test
    fun squatAnalyzerDetectsBackLean() {
        val result = SimpleSquatAnalyzer().analyze(
            squatFrame(
                timestampMs = 1L,
                hipY = 0.76f,
                shoulderShiftX = 0.24f,
            )
        )

        assertEquals(ProblemType.BACK_LEAN_TOO_MUCH, result.problemType)
        assertTrue((result.trunkAngle ?: 0f) > 22f)
    }

    @Test
    fun squatAnalyzerDetectsAsymmetry() {
        val result = SimpleSquatAnalyzer().analyze(
            squatFrame(
                timestampMs = 1L,
                hipY = 0.76f,
                rightKneeY = 0.82f,
            )
        )

        assertEquals(ProblemType.ASYMMETRY, result.problemType)
        assertEquals(90f, result.score, 0.01f)
    }

    @Test
    fun squatAnalyzerUsesConfiguredMinimumConfidence() {
        val result = SimpleSquatAnalyzer(
            PoseAnalysisConfig(minPoseConfidence = 0.8f)
        ).analyze(
            squatFrame(
                timestampMs = 1L,
                hipY = 0.76f,
                confidence = 0.7f,
            )
        )

        assertEquals(ProblemType.LOW_CONFIDENCE, result.problemType)
    }

    @Test
    fun squatAnalyzerUsesConfiguredBackLeanThreshold() {
        val result = SimpleSquatAnalyzer(
            PoseAnalysisConfig(backLeanAngleThreshold = 60f)
        ).analyze(
            squatFrame(
                timestampMs = 1L,
                hipY = 0.76f,
                shoulderShiftX = 0.24f,
            )
        )

        assertEquals(ProblemType.NONE, result.problemType)
    }

    @Test
    fun ruleBasedClassifierDetectsJumpingJackSignals() {
        val landmarks = mapOf(
            "LEFT_SHOULDER" to LandmarkPoint("LEFT_SHOULDER", 0.4f, 0.34f, confidence = 1f),
            "RIGHT_SHOULDER" to LandmarkPoint("RIGHT_SHOULDER", 0.6f, 0.34f, confidence = 1f),
            "LEFT_WRIST" to LandmarkPoint("LEFT_WRIST", 0.2f, 0.2f, confidence = 1f),
            "RIGHT_WRIST" to LandmarkPoint("RIGHT_WRIST", 0.8f, 0.2f, confidence = 1f),
            "LEFT_ANKLE" to LandmarkPoint("LEFT_ANKLE", 0.2f, 0.9f, confidence = 1f),
            "RIGHT_ANKLE" to LandmarkPoint("RIGHT_ANKLE", 0.8f, 0.9f, confidence = 1f),
        )
        val frame = PoseFrame(
            sessionId = 1L,
            nodeId = 1L,
            timestampMs = 1000L,
            cameraRole = DeviceRole.FRONT_CAMERA,
            landmarks = landmarks,
            overallConfidence = 1f,
            imageWidth = 1080,
            imageHeight = 1920,
        )

        assertEquals(
            ActionType.JUMPING_JACK,
            RuleBasedActionClassifier()
                .classify(listOf(frame))
            .actionType,
        )
    }

    @Test
    fun ruleBasedClassifierUsesConfiguredRecognitionThreshold() {
        val frame = jumpingJackFrame(timestampMs = 1L, isOpen = true)

        val result = RuleBasedActionClassifier(
            PoseAnalysisConfig(actionRecognitionThreshold = 0.9f)
        ).classify(listOf(frame))

        assertEquals(ActionType.UNKNOWN, result.actionType)
        assertEquals(0.78f, result.confidence, 0.01f)
    }

    @Test
    fun ruleBasedClassifierFallsBackToAvailableCameraWhenFrontIsMissing() {
        val sideFrame = jumpingJackFrame(timestampMs = 1L, isOpen = true)
            .copy(cameraRole = DeviceRole.SIDE_CAMERA)

        val result = RuleBasedActionClassifier().classify(listOf(sideFrame))

        assertEquals(ActionType.JUMPING_JACK, result.actionType)
        assertEquals(0.78f, result.confidence, 0.01f)
    }

    @Test
    fun ruleBasedClassifierFallsBackWhenFrontFramesAreUnreliable() {
        val lowConfidenceFront = jumpingJackFrame(timestampMs = 1L, isOpen = true, confidence = 0.1f)
        val reliableSide = jumpingJackFrame(timestampMs = 2L, isOpen = true)
            .copy(cameraRole = DeviceRole.SIDE_CAMERA)

        val result = RuleBasedActionClassifier().classify(listOf(lowConfidenceFront, reliableSide))

        assertEquals(ActionType.JUMPING_JACK, result.actionType)
    }

    @Test
    fun ruleBasedClassifierToleratesMildPoseConfidenceDrop() {
        val frame = jumpingJackFrame(timestampMs = 1L, isOpen = true, confidence = 0.34f)

        val result = RuleBasedActionClassifier().classify(listOf(frame))

        assertEquals(ActionType.JUMPING_JACK, result.actionType)
    }

    @Test
    fun ruleBasedClassifierAcceptsOneVisibleJumpingJackWrist() {
        val landmarks = mapOf(
            "LEFT_SHOULDER" to LandmarkPoint("LEFT_SHOULDER", 0.4f, 0.34f, confidence = 1f),
            "RIGHT_SHOULDER" to LandmarkPoint("RIGHT_SHOULDER", 0.6f, 0.34f, confidence = 1f),
            "LEFT_WRIST" to LandmarkPoint("LEFT_WRIST", 0.24f, 0.22f, confidence = 1f),
            "RIGHT_WRIST" to LandmarkPoint("RIGHT_WRIST", 0.76f, 0.48f, confidence = 1f),
            "LEFT_ANKLE" to LandmarkPoint("LEFT_ANKLE", 0.2f, 0.92f, confidence = 1f),
            "RIGHT_ANKLE" to LandmarkPoint("RIGHT_ANKLE", 0.8f, 0.92f, confidence = 1f),
        )
        val frame = PoseFrame(
            sessionId = 1L,
            nodeId = 1L,
            timestampMs = 1_000L,
            cameraRole = DeviceRole.FRONT_CAMERA,
            landmarks = landmarks,
            overallConfidence = 1f,
            imageWidth = 1080,
            imageHeight = 1920,
        )

        val result = RuleBasedActionClassifier().classify(listOf(frame))

        assertEquals(ActionType.JUMPING_JACK, result.actionType)
    }

    @Test
    fun ruleBasedClassifierPrefersMajoritySignalsInsideWindow() {
        val frames = listOf(
            jumpingJackFrame(timestampMs = 1_000L, isOpen = true),
            squatFrame(timestampMs = 1_100L, hipY = 0.76f),
            squatFrame(timestampMs = 1_200L, hipY = 0.76f),
            squatFrame(timestampMs = 1_300L, hipY = 0.76f),
        )

        val result = RuleBasedActionClassifier().classify(frames)

        assertEquals(ActionType.SQUAT, result.actionType)
    }

    @Test
    fun ruleBasedClassifierPrefersTrainingSignalOverStandingTransitionFrames() {
        val frames = listOf(
            standingFrame(timestampMs = 1_000L),
            jumpingJackFrame(timestampMs = 1_100L, isOpen = true),
            standingFrame(timestampMs = 1_200L),
        )

        val result = RuleBasedActionClassifier().classify(frames)

        assertEquals(ActionType.JUMPING_JACK, result.actionType)
    }

    @Test
    fun ruleBasedClassifierDoesNotTreatDeepSquatAsSitUp() {
        val bottomSquat = squatFrame(timestampMs = 1_200L, hipY = 0.76f)
        val features = PoseActionRules.features(bottomSquat, PoseAnalysisConfig())
        val result = RuleBasedActionClassifier().classify(
            listOf(
                squatFrame(timestampMs = 1_000L, hipY = 0.68f),
                squatFrame(timestampMs = 1_100L, hipY = 0.72f),
                bottomSquat,
                squatFrame(timestampMs = 1_300L, hipY = 0.72f),
            )
        )

        assertTrue(features.isSquatSignal())
        assertTrue(!features.isSitUpSignal())
        assertEquals(ActionType.SQUAT, result.actionType)
    }

    @Test
    fun ruleBasedClassifierDetectsFrontViewSquatMotionWhenKneeAngleBarelyChanges() {
        val frames = listOf(
            frontViewSquatMotionFrame(timestampMs = 1_000L, hipY = 0.56f),
            frontViewSquatMotionFrame(timestampMs = 1_100L, hipY = 0.64f),
            frontViewSquatMotionFrame(timestampMs = 1_200L, hipY = 0.72f),
            frontViewSquatMotionFrame(timestampMs = 1_300L, hipY = 0.73f),
        )
        val features = frames.map { frame -> PoseActionRules.features(frame, PoseAnalysisConfig()) }

        assertTrue(PoseActionRules.hasSquatMotionSequence(features))
        assertEquals(ActionType.SQUAT, RuleBasedActionClassifier().classify(frames).actionType)
    }

    @Test
    fun frontViewPoseQualityGateRequiresStableReliableFrontFrames() {
        val config = PoseAnalysisConfig()
        val goodFrames = listOf(
            squatFrame(timestampMs = 1_000L, hipY = 0.76f),
            squatFrame(timestampMs = 1_100L, hipY = 0.76f),
            squatFrame(timestampMs = 1_200L, hipY = 0.76f),
        )
        val lowConfidenceFrames = goodFrames.map { frame -> frame.copy(overallConfidence = 0.2f) }
        val sideFrames = goodFrames.map { frame -> frame.copy(cameraRole = DeviceRole.SIDE_CAMERA) }

        val goodResult = FrontViewPoseQualityGate.evaluate(goodFrames, config)
        val lowConfidenceResult = FrontViewPoseQualityGate.evaluate(lowConfidenceFrames, config)
        val sideResult = FrontViewPoseQualityGate.evaluate(sideFrames, config)

        assertTrue(goodResult.canClassify)
        assertEquals(3, goodResult.reliableFrameCount)
        assertTrue(!lowConfidenceResult.canClassify)
        assertTrue(lowConfidenceResult.reason.contains("关键点不足"))
        assertTrue(!sideResult.canClassify)
        assertTrue(sideResult.reason.contains("正面机位"))
    }

    @Test
    fun stableActionRecognizerRequiresRepeatedWindowsBeforeConfirming() {
        val recognizer = StableActionRecognizer(requiredStableWindows = 3)
        val classifier = RuleBasedActionClassifier()
        val classification = classifier.classify(listOf(jumpingJackFrame(timestampMs = 1_000L, isOpen = true)))

        val first = recognizer.observe(classification)
        val second = recognizer.observe(classification.copy(windowStartMs = 1_100L, windowEndMs = 1_100L))
        val third = recognizer.observe(classification.copy(windowStartMs = 1_200L, windowEndMs = 1_200L))

        assertEquals(ActionType.UNKNOWN, first.result.actionType)
        assertEquals(ActionType.UNKNOWN, second.result.actionType)
        assertEquals(ActionType.JUMPING_JACK, third.result.actionType)
        assertTrue(third.isConfirmed)
    }

    @Test
    fun stableActionRecognizerDefaultConfirmsAfterTwoWindows() {
        val recognizer = StableActionRecognizer()
        val classification = RuleBasedActionClassifier()
            .classify(listOf(jumpingJackFrame(timestampMs = 1_000L, isOpen = true)))

        val first = recognizer.observe(classification)
        val second = recognizer.observe(classification.copy(windowStartMs = 1_100L, windowEndMs = 1_100L))

        assertEquals(ActionType.UNKNOWN, first.result.actionType)
        assertEquals(ActionType.JUMPING_JACK, second.result.actionType)
        assertTrue(second.isConfirmed)
    }

    @Test
    fun bestActionRecognitionTrackerPrefersConfirmedActionThenConfidence() {
        val tracker = BestActionRecognitionTracker()
        tracker.observe(classification(ActionType.JUMPING_JACK, 0.90f, 1_000L), isConfirmed = false)
        tracker.observe(classification(ActionType.SQUAT, 0.70f, 1_100L), isConfirmed = true)

        assertEquals(ActionType.SQUAT, tracker.best().actionType)
        assertTrue(tracker.best().isConfirmed)

        tracker.observe(classification(ActionType.JUMPING_JACK, 0.95f, 1_200L), isConfirmed = true)

        assertEquals(ActionType.JUMPING_JACK, tracker.best().actionType)
        assertEquals(0.95f, tracker.best().confidence, 0.01f)
    }

    @Test
    fun actionRecognitionRulesUseTolerantDefaultsForRealCameraNoise() {
        val configSource = readMainKotlin("domain/rule/PoseAnalysisConfig.kt")
        val featureSource = readMainKotlin("pose/feature/PoseActionRules.kt")
        val classifierSource = readMainKotlin("pose/classifier/ActionClassifier.kt")
        val stableSource = readMainKotlin("pose/classifier/StableActionRecognizer.kt")
        val cameraSource = readMainKotlin("ui/camera/CameraNodeActivity.kt")
        val removedCompositeAction = "BUR" + "PEE"

        assertTrue(configSource.contains("minPoseConfidence: Float = 0.38f"))
        assertTrue(configSource.contains("actionRecognitionThreshold: Float = 0.62f"))
        assertTrue(configSource.contains("jumpingJackOpenAnkleShoulderRatio: Float = 1.35f"))
        assertTrue(featureSource.contains("RELAXED_CONFIDENCE_FACTOR = 0.75f"))
        assertTrue(featureSource.contains("span.horizontal > 0.24f"))
        assertTrue(featureSource.contains("span.vertical <= 0.20f"))
        assertTrue(featureSource.contains("span.vertical <= 0.32f"))
        assertTrue(featureSource.contains("(leftWristUp || rightWristUp)"))
        assertTrue(featureSource.contains("isJumpRopeElbowPose()"))
        assertTrue(featureSource.contains("hasJumpRopeHandMotion(jumpRopeLike)"))
        assertTrue(featureSource.contains("hasJumpRopeBouncePattern(jumpRopeLike)"))
        assertTrue(featureSource.contains("hasLungeAsymmetry(left, right)"))
        assertTrue(featureSource.contains("isLateralRaiseSignal()"))
        assertTrue(featureSource.contains("hasLateralRaiseWindow("))
        assertTrue(!featureSource.contains(removedCompositeAction))
        assertTrue(classifierSource.contains("frontFrames.isNotEmpty()"))
        assertTrue(classifierSource.contains("PoseActionRules.allLowConfidence(frontFrames, config)"))
        assertTrue(classifierSource.contains("trainingBest ?: standingBest"))
        assertTrue(classifierSource.contains("nonLungeFeatures"))
        assertTrue(classifierSource.contains("val jumpRopeCycle = PoseActionRules.hasJumpRopeCycle(features)"))
        assertTrue(classifierSource.contains("ActionType.LATERAL_RAISE"))
        assertTrue(classifierSource.contains("lateralRaiseSupport"))
        assertTrue(!classifierSource.contains(removedCompositeAction))
        assertTrue(classifierSource.contains("frameCount <= 8 -> 2"))
        assertTrue(stableSource.contains("DEFAULT_REQUIRED_STABLE_WINDOWS = 2"))
        assertTrue(stableSource.contains("DEFAULT_UNKNOWN_RESET_WINDOWS = 6"))
        assertTrue(cameraSource.contains("currentCameraRole != DeviceRole.UNKNOWN"))
        assertTrue(cameraSource.contains("ruleBasedActionClassifier.classify(frameWindow)"))
        assertTrue(!cameraSource.contains("qualityResult?.canClassify == true"))
        assertTrue(!cameraSource.contains("不负责判定动作类型"))
    }

    @Test
    fun ruleBasedClassifierDetectsAddedActionSignals() {
        val classifier = RuleBasedActionClassifier()
        val samples = listOf(
            ActionType.PUSH_UP to listOf(pushUpFrame(1_000L)),
            ActionType.SIT_UP to listOf(sitUpFrame(1_000L)),
            ActionType.LUNGE to listOf(lungeFrame(1_000L)),
            ActionType.HIGH_KNEES to listOf(highKneesFrame(1_000L, leftRaised = true)),
            ActionType.PLANK to listOf(plankFrame(1_000L)),
            ActionType.LATERAL_RAISE to listOf(lateralRaiseFrame(1_000L)),
            ActionType.MOUNTAIN_CLIMBER to listOf(mountainClimberFrame(1_000L, leftForward = true)),
            ActionType.JUMP_ROPE to listOf(
                jumpRopeFrame(1_000L, ankleY = 0.92f, wristY = 0.54f),
                jumpRopeFrame(1_300L, ankleY = 0.88f, wristY = 0.50f),
                jumpRopeFrame(1_600L, ankleY = 0.92f, wristY = 0.55f),
            ),
            ActionType.STANDING_FORWARD_BEND to listOf(standingForwardBendFrame(1_000L, isBent = true)),
        )

        samples.forEach { (actionType, frames) ->
            assertEquals("classifier should detect $actionType", actionType, classifier.classify(frames).actionType)
        }
    }

    @Test
    fun ruleBasedClassifierPrefersLungeOverSquatForAsymmetricSplitStance() {
        val result = RuleBasedActionClassifier().classify(listOf(lungeSquatOverlapFrame(1_000L)))

        assertEquals(ActionType.LUNGE, result.actionType)
    }

    @Test
    fun ruleBasedClassifierPrefersPlankOverSquatForStaticHorizontalHold() {
        val result = RuleBasedActionClassifier().classify(listOf(imperfectPlankFrame(1_000L)))

        assertEquals(ActionType.PLANK, result.actionType)
    }

    @Test
    fun ruleBasedClassifierTreatsStillProneGroundHoldAsPlank() {
        val frames = listOf(
            proneGroundStillFrame(1_000L, jitter = 0f),
            proneGroundStillFrame(1_120L, jitter = 0.004f),
            proneGroundStillFrame(1_240L, jitter = -0.003f),
        )
        val features = frames.map { frame -> PoseActionRules.features(frame, PoseAnalysisConfig()) }

        assertTrue(features.all { it.isProneHoldCandidate() })
        assertTrue(features.none { it.isSquatSignal() })
        assertTrue(PoseActionRules.hasStaticProneHold(features))
        assertEquals(ActionType.PLANK, RuleBasedActionClassifier().classify(frames).actionType)
    }

    @Test
    fun ruleBasedClassifierUsesLegMotionToSeparateMountainClimberFromPlank() {
        val frames = listOf(
            relaxedMountainClimberMotionFrame(1_000L, leftForward = true),
            relaxedMountainClimberMotionFrame(1_120L, leftForward = false),
            relaxedMountainClimberMotionFrame(1_240L, leftForward = true),
        )
        val features = frames.map { frame -> PoseActionRules.features(frame, PoseAnalysisConfig()) }

        assertTrue(features.none { it.isMountainClimberSignal() })
        assertTrue(features.all { it.isPlankSignal() })
        assertTrue(PoseActionRules.hasMountainClimberMotion(features))
        assertTrue(!PoseActionRules.hasStaticProneHold(features))
        assertEquals(ActionType.MOUNTAIN_CLIMBER, RuleBasedActionClassifier().classify(frames).actionType)
    }

    @Test
    fun ruleBasedClassifierKeepsHighKneesAheadOfLunge() {
        val result = RuleBasedActionClassifier().classify(listOf(highKneesFrame(1_000L, leftRaised = true)))

        assertEquals(ActionType.HIGH_KNEES, result.actionType)
    }

    @Test
    fun ruleBasedClassifierDetectsLowAmplitudeJumpRopeInsteadOfStanding() {
        val classifier = RuleBasedActionClassifier()
        val lowAmplitudeWithWristSwing = listOf(
            jumpRopeFrame(1_000L, ankleY = 0.92f, wristY = 0.54f),
            jumpRopeFrame(1_300L, ankleY = 0.913f, wristY = 0.535f),
            jumpRopeFrame(1_600L, ankleY = 0.92f, wristY = 0.541f),
        )
        val lowAmplitudeSteadyHands = listOf(
            jumpRopeFrame(1_000L, ankleY = 0.92f, wristY = 0.54f),
            jumpRopeFrame(1_300L, ankleY = 0.913f, wristY = 0.54f),
            jumpRopeFrame(1_600L, ankleY = 0.92f, wristY = 0.54f),
        )
        val noBounce = listOf(
            jumpRopeFrame(1_000L, ankleY = 0.92f, wristY = 0.54f),
            jumpRopeFrame(1_300L, ankleY = 0.92f, wristY = 0.539f),
            jumpRopeFrame(1_600L, ankleY = 0.92f, wristY = 0.541f),
        )

        assertEquals(ActionType.JUMP_ROPE, classifier.classify(lowAmplitudeWithWristSwing).actionType)
        assertEquals(ActionType.JUMP_ROPE, classifier.classify(lowAmplitudeSteadyHands).actionType)
        assertEquals(ActionType.STANDING, classifier.classify(noBounce).actionType)
    }

    @Test
    fun ruleBasedClassifierPrefersJumpRopeOverLateralRaiseWhenBodyBouncesAndElbowsStayVertical() {
        val frames = listOf(
            jumpRopeLateralRaiseOverlapFrame(1_000L, ankleY = 0.92f),
            jumpRopeLateralRaiseOverlapFrame(1_300L, ankleY = 0.913f),
            jumpRopeLateralRaiseOverlapFrame(1_600L, ankleY = 0.92f),
        )
        val features = frames.map { PoseActionRules.features(it, PoseAnalysisConfig()) }

        assertTrue(features.any { it.isLateralRaiseSignal() })
        assertTrue(features.all { it.isJumpRopeElbowPose() })
        assertTrue(PoseActionRules.hasJumpRopeCycle(features))
        assertEquals(ActionType.JUMP_ROPE, RuleBasedActionClassifier().classify(frames).actionType)
    }

    @Test
    fun ruleBasedClassifierDetectsRelaxedLateralRaiseInsteadOfStanding() {
        val frames = listOf(
            standingFrame(timestampMs = 1_000L),
            angleOpenLateralRaiseFrame(timestampMs = 1_100L),
            angleOpenLateralRaiseFrame(timestampMs = 1_200L),
            standingFrame(timestampMs = 1_300L),
        )

        val result = RuleBasedActionClassifier().classify(frames)

        assertEquals(ActionType.LATERAL_RAISE, result.actionType)
    }

    @Test
    fun ruleBasedClassifierUsesArmOpenAngleForLateralRaise() {
        val frames = listOf(
            standingFrame(timestampMs = 1_000L),
            angleDominantLateralRaiseFrame(timestampMs = 1_100L),
            angleDominantLateralRaiseFrame(timestampMs = 1_200L),
        )

        val result = RuleBasedActionClassifier().classify(frames)

        assertEquals(ActionType.LATERAL_RAISE, result.actionType)
    }

    @Test
    fun ruleBasedClassifierKeepsNaturalArmStandingAsStanding() {
        val result = RuleBasedActionClassifier().classify(
            listOf(naturalArmStandingFrame(1_000L), naturalArmStandingFrame(1_100L))
        )

        assertEquals(ActionType.STANDING, result.actionType)
    }

    @Test
    fun ruleBasedClassifierRejectsLowConfidenceNewActionFrame() {
        val frame = fullBodyFrame(
            timestampMs = 1_000L,
            points = uprightPoints(
                shoulderY = 0.32f,
                hipY = 0.56f,
                leftKneeY = 0.54f,
                rightKneeY = 0.74f,
                leftKneeX = 0.44f,
                rightKneeX = 0.56f,
                wristY = 0.46f,
            ),
            confidence = 0.2f,
        )

        assertEquals(ActionType.UNKNOWN, RuleBasedActionClassifier().classify(listOf(frame)).actionType)
    }

    @Test
    fun basicAnalyzerCountsRepsButTracksPlankHoldDuration() {
        val analyzer = SimpleBasicActionAnalyzer()

        val pushUp = analyzer.analyze(ActionType.PUSH_UP, pushUpFrame(1_000L))
        val plankStart = analyzer.analyze(ActionType.PLANK, plankFrame(2_000L))
        val plankLater = analyzer.analyze(ActionType.PLANK, plankFrame(4_500L))

        assertEquals(1, pushUp.totalCount)
        assertEquals(0L, pushUp.holdDurationMs)
        assertEquals(0, plankLater.totalCount)
        assertEquals(0L, plankStart.holdDurationMs)
        assertTrue(plankLater.holdDurationMs >= 2_000L)
    }

    @Test
    fun basicAnalyzerCountsStandingForwardBendAfterReturn() {
        val analyzer = SimpleBasicActionAnalyzer()

        val start = analyzer.analyze(ActionType.STANDING_FORWARD_BEND, standingForwardBendFrame(1_000L, isBent = false))
        val bent = analyzer.analyze(ActionType.STANDING_FORWARD_BEND, standingForwardBendFrame(1_300L, isBent = true))
        val stillBent = analyzer.analyze(ActionType.STANDING_FORWARD_BEND, standingForwardBendFrame(1_500L, isBent = true))
        val returned = analyzer.analyze(ActionType.STANDING_FORWARD_BEND, standingForwardBendFrame(1_800L, isBent = false))

        assertEquals(0, start.totalCount)
        assertEquals(0, bent.totalCount)
        assertEquals(0, stillBent.totalCount)
        assertEquals(1, returned.totalCount)
    }

    @Test
    fun basicAnalyzerCountsLateralRaiseAfterRaiseAndReturn() {
        val analyzer = SimpleBasicActionAnalyzer()

        val start = analyzer.analyze(ActionType.LATERAL_RAISE, naturalArmStandingFrame(1_000L))
        val raised = analyzer.analyze(ActionType.LATERAL_RAISE, lateralRaiseFrame(1_300L))
        val stillRaised = analyzer.analyze(ActionType.LATERAL_RAISE, lateralRaiseFrame(1_500L))
        val lowered = analyzer.analyze(ActionType.LATERAL_RAISE, naturalArmStandingFrame(1_800L))
        val raisedAgain = analyzer.analyze(ActionType.LATERAL_RAISE, lateralRaiseFrame(2_100L))
        val loweredAgain = analyzer.analyze(ActionType.LATERAL_RAISE, naturalArmStandingFrame(2_400L))

        assertEquals(0, start.totalCount)
        assertEquals(0, raised.totalCount)
        assertEquals(0, stillRaised.totalCount)
        assertEquals(1, lowered.totalCount)
        assertEquals(1, raisedAgain.totalCount)
        assertEquals(2, loweredAgain.totalCount)
    }

    @Test
    fun basicAnalyzerCountsJumpRopeBounceCycles() {
        val analyzer = SimpleBasicActionAnalyzer()

        analyzer.analyze(ActionType.JUMP_ROPE, jumpRopeFrame(1_000L, ankleY = 0.92f))
        analyzer.analyze(ActionType.JUMP_ROPE, jumpRopeFrame(1_120L, ankleY = 0.913f))
        val firstLanding = analyzer.analyze(ActionType.JUMP_ROPE, jumpRopeFrame(1_240L, ankleY = 0.92f))
        analyzer.analyze(ActionType.JUMP_ROPE, jumpRopeFrame(1_360L, ankleY = 0.913f))
        val secondLanding = analyzer.analyze(ActionType.JUMP_ROPE, jumpRopeFrame(1_480L, ankleY = 0.92f))

        assertEquals(1, firstLanding.totalCount)
        assertEquals(2, secondLanding.totalCount)
    }

    @Test
    fun basicAnalyzerCountsSameSideLungesAfterResetPose() {
        val analyzer = SimpleBasicActionAnalyzer()

        analyzer.analyze(ActionType.LUNGE, lungeFrame(1_000L))
        analyzer.analyze(ActionType.LUNGE, standingFrame(1_300L))
        val result = analyzer.analyze(ActionType.LUNGE, lungeFrame(1_600L))

        assertEquals(2, result.totalCount)
    }

    @Test
    fun jumpingJackAnalyzerCountsCompleteCycle() {
        val analyzer = SimpleJumpingJackAnalyzer()

        analyzer.analyze(jumpingJackFrame(timestampMs = 1L, isOpen = false))
        analyzer.analyze(jumpingJackFrame(timestampMs = 2L, isOpen = true))
        analyzer.analyze(jumpingJackFrame(timestampMs = 3L, isOpen = true))
        analyzer.analyze(jumpingJackFrame(timestampMs = 4L, isOpen = false))
        val result = analyzer.analyze(jumpingJackFrame(timestampMs = 5L, isOpen = false))

        assertEquals(1, result.totalCount)
        assertEquals(0, result.lostFrameCount)
        assertTrue(result.averageTempo > 0f)
    }

    @Test
    fun jumpingJackAnalyzerPausesOnLowConfidence() {
        val analyzer = SimpleJumpingJackAnalyzer()

        val result = analyzer.analyze(jumpingJackFrame(timestampMs = 1L, isOpen = true, confidence = 0.2f))

        assertEquals(0, result.totalCount)
        assertEquals(1, result.lostFrameCount)
    }

    @Test
    fun jumpingJackAnalyzerUsesConfiguredOpenRatio() {
        val analyzer = SimpleJumpingJackAnalyzer(
            PoseAnalysisConfig(jumpingJackOpenAnkleShoulderRatio = 5f)
        )

        analyzer.analyze(jumpingJackFrame(timestampMs = 1L, isOpen = false))
        analyzer.analyze(jumpingJackFrame(timestampMs = 2L, isOpen = true))
        analyzer.analyze(jumpingJackFrame(timestampMs = 3L, isOpen = true))
        analyzer.analyze(jumpingJackFrame(timestampMs = 4L, isOpen = false))
        val result = analyzer.analyze(jumpingJackFrame(timestampMs = 5L, isOpen = false))

        assertEquals(0, result.totalCount)
    }

    private fun squatFrame(
        timestampMs: Long,
        hipY: Float,
        leftKneeX: Float = 0.36f,
        rightKneeX: Float = 0.64f,
        rightKneeY: Float = 0.72f,
        shoulderShiftX: Float = 0f,
        confidence: Float = 1f,
    ): PoseFrame {
        val leftHipX = 0.43f
        val rightHipX = 0.57f
        val leftAnkleX = if (hipY > 0.7f) 0.33f else leftHipX
        val rightAnkleX = if (hipY > 0.7f) 0.67f else rightHipX
        val actualLeftKneeX = if (hipY > 0.7f) leftKneeX else leftHipX
        val actualRightKneeX = if (hipY > 0.7f) rightKneeX else rightHipX
        val leftKneeY = 0.72f
        return PoseFrame(
            sessionId = 1L,
            nodeId = 1L,
            timestampMs = timestampMs,
            cameraRole = DeviceRole.FRONT_CAMERA,
            landmarks = mapOf(
                "LEFT_SHOULDER" to LandmarkPoint("LEFT_SHOULDER", 0.39f + shoulderShiftX, 0.32f, confidence = confidence),
                "RIGHT_SHOULDER" to LandmarkPoint("RIGHT_SHOULDER", 0.61f + shoulderShiftX, 0.32f, confidence = confidence),
                "LEFT_HIP" to LandmarkPoint("LEFT_HIP", leftHipX, hipY, confidence = confidence),
                "RIGHT_HIP" to LandmarkPoint("RIGHT_HIP", rightHipX, hipY, confidence = confidence),
                "LEFT_KNEE" to LandmarkPoint("LEFT_KNEE", actualLeftKneeX, leftKneeY, confidence = confidence),
                "RIGHT_KNEE" to LandmarkPoint("RIGHT_KNEE", actualRightKneeX, rightKneeY, confidence = confidence),
                "LEFT_ANKLE" to LandmarkPoint("LEFT_ANKLE", leftAnkleX, 0.92f, confidence = confidence),
                "RIGHT_ANKLE" to LandmarkPoint("RIGHT_ANKLE", rightAnkleX, 0.92f, confidence = confidence),
            ),
            overallConfidence = confidence,
            imageWidth = 720,
            imageHeight = 1280,
        )
    }

    private fun classification(
        actionType: ActionType,
        confidence: Float,
        timestampMs: Long,
    ): ActionClassificationResult =
        ActionClassificationResult(
            actionType = actionType,
            confidence = confidence,
            windowStartMs = timestampMs,
            windowEndMs = timestampMs,
            source = ClassifierSource.RULE_BASED,
        )

    private fun frontViewSquatMotionFrame(timestampMs: Long, hipY: Float): PoseFrame =
        fullBodyFrame(
            timestampMs = timestampMs,
            points = mapOf(
                "LEFT_SHOULDER" to point("LEFT_SHOULDER", 0.39f, 0.32f),
                "RIGHT_SHOULDER" to point("RIGHT_SHOULDER", 0.61f, 0.32f),
                "LEFT_WRIST" to point("LEFT_WRIST", 0.34f, 0.52f),
                "RIGHT_WRIST" to point("RIGHT_WRIST", 0.66f, 0.52f),
                "LEFT_HIP" to point("LEFT_HIP", 0.43f, hipY),
                "RIGHT_HIP" to point("RIGHT_HIP", 0.57f, hipY),
                "LEFT_KNEE" to point("LEFT_KNEE", 0.43f, 0.74f),
                "RIGHT_KNEE" to point("RIGHT_KNEE", 0.57f, 0.74f),
                "LEFT_ANKLE" to point("LEFT_ANKLE", 0.43f, 0.92f),
                "RIGHT_ANKLE" to point("RIGHT_ANKLE", 0.57f, 0.92f),
            )
        )

    private fun poseFrameEntity(confidence: Float?): PoseFrameEntity =
        PoseFrameEntity(
            1L,
            1L,
            1_000L,
            DeviceRole.FRONT_CAMERA.name,
            "{}",
            confidence,
            null,
            "REAL_POSE",
        )

    private fun nearbyEndpoint(
        endpointId: String,
        deviceName: String,
        role: DeviceRole,
        isOnline: Boolean = true,
    ): NearbyEndpoint =
        NearbyEndpoint(
            endpointId = endpointId,
            deviceName = deviceName,
            role = role,
            isOnline = isOnline,
        )

    private fun jumpingJackFrame(
        timestampMs: Long,
        isOpen: Boolean,
        confidence: Float = 1f,
    ): PoseFrame {
        val leftAnkleX = if (isOpen) 0.18f else 0.43f
        val rightAnkleX = if (isOpen) 0.82f else 0.57f
        val wristY = if (isOpen) 0.22f else 0.62f
        return PoseFrame(
            sessionId = 1L,
            nodeId = 1L,
            timestampMs = timestampMs,
            cameraRole = DeviceRole.FRONT_CAMERA,
            landmarks = mapOf(
                "LEFT_SHOULDER" to LandmarkPoint("LEFT_SHOULDER", 0.4f, 0.34f, confidence = confidence),
                "RIGHT_SHOULDER" to LandmarkPoint("RIGHT_SHOULDER", 0.6f, 0.34f, confidence = confidence),
                "LEFT_WRIST" to LandmarkPoint("LEFT_WRIST", 0.24f, wristY, confidence = confidence),
                "RIGHT_WRIST" to LandmarkPoint("RIGHT_WRIST", 0.76f, wristY, confidence = confidence),
                "LEFT_ANKLE" to LandmarkPoint("LEFT_ANKLE", leftAnkleX, 0.92f, confidence = confidence),
                "RIGHT_ANKLE" to LandmarkPoint("RIGHT_ANKLE", rightAnkleX, 0.92f, confidence = confidence),
            ),
            overallConfidence = confidence,
            imageWidth = 720,
            imageHeight = 1280,
        )
    }

    private fun standingFrame(timestampMs: Long): PoseFrame =
        fullBodyFrame(
            timestampMs = timestampMs,
            points = mapOf(
                "LEFT_SHOULDER" to point("LEFT_SHOULDER", 0.39f, 0.32f),
                "RIGHT_SHOULDER" to point("RIGHT_SHOULDER", 0.61f, 0.32f),
                "LEFT_HIP" to point("LEFT_HIP", 0.43f, 0.56f),
                "RIGHT_HIP" to point("RIGHT_HIP", 0.57f, 0.56f),
                "LEFT_KNEE" to point("LEFT_KNEE", 0.43f, 0.74f),
                "RIGHT_KNEE" to point("RIGHT_KNEE", 0.57f, 0.74f),
                "LEFT_ANKLE" to point("LEFT_ANKLE", 0.43f, 0.92f),
                "RIGHT_ANKLE" to point("RIGHT_ANKLE", 0.57f, 0.92f),
            )
        )

    private fun pushUpFrame(timestampMs: Long): PoseFrame =
        horizontalFrame(timestampMs, elbowY = 0.58f, leftKneeY = 0.57f, rightKneeY = 0.57f)

    private fun plankFrame(timestampMs: Long): PoseFrame =
        horizontalFrame(timestampMs, elbowY = 0.50f, leftKneeY = 0.57f, rightKneeY = 0.57f)

    private fun imperfectPlankFrame(timestampMs: Long): PoseFrame =
        fullBodyFrame(
            timestampMs = timestampMs,
            points = mapOf(
                "LEFT_SHOULDER" to point("LEFT_SHOULDER", 0.24f, 0.50f),
                "RIGHT_SHOULDER" to point("RIGHT_SHOULDER", 0.28f, 0.50f),
                "LEFT_ELBOW" to point("LEFT_ELBOW", 0.40f, 0.56f),
                "RIGHT_ELBOW" to point("RIGHT_ELBOW", 0.42f, 0.56f),
                "LEFT_WRIST" to point("LEFT_WRIST", 0.56f, 0.62f),
                "RIGHT_WRIST" to point("RIGHT_WRIST", 0.58f, 0.62f),
                "LEFT_HIP" to point("LEFT_HIP", 0.52f, 0.58f),
                "RIGHT_HIP" to point("RIGHT_HIP", 0.55f, 0.58f),
                "LEFT_KNEE" to point("LEFT_KNEE", 0.68f, 0.64f),
                "RIGHT_KNEE" to point("RIGHT_KNEE", 0.70f, 0.64f),
                "LEFT_ANKLE" to point("LEFT_ANKLE", 0.86f, 0.74f),
                "RIGHT_ANKLE" to point("RIGHT_ANKLE", 0.88f, 0.74f),
            )
        )

    private fun proneGroundStillFrame(timestampMs: Long, jitter: Float): PoseFrame =
        fullBodyFrame(
            timestampMs = timestampMs,
            points = mapOf(
                "LEFT_SHOULDER" to point("LEFT_SHOULDER", 0.46f + jitter, 0.62f + jitter),
                "RIGHT_SHOULDER" to point("RIGHT_SHOULDER", 0.54f + jitter, 0.62f + jitter),
                "LEFT_ELBOW" to point("LEFT_ELBOW", 0.44f + jitter, 0.66f + jitter),
                "RIGHT_ELBOW" to point("RIGHT_ELBOW", 0.56f + jitter, 0.66f + jitter),
                "LEFT_WRIST" to point("LEFT_WRIST", 0.43f + jitter, 0.70f + jitter),
                "RIGHT_WRIST" to point("RIGHT_WRIST", 0.57f + jitter, 0.70f + jitter),
                "LEFT_HIP" to point("LEFT_HIP", 0.47f + jitter, 0.70f + jitter),
                "RIGHT_HIP" to point("RIGHT_HIP", 0.53f + jitter, 0.70f + jitter),
                "LEFT_KNEE" to point("LEFT_KNEE", 0.48f + jitter, 0.76f + jitter),
                "RIGHT_KNEE" to point("RIGHT_KNEE", 0.52f + jitter, 0.76f + jitter),
                "LEFT_ANKLE" to point("LEFT_ANKLE", 0.46f + jitter, 0.84f + jitter),
                "RIGHT_ANKLE" to point("RIGHT_ANKLE", 0.54f + jitter, 0.84f + jitter),
            )
        )

    private fun mountainClimberFrame(timestampMs: Long, leftForward: Boolean): PoseFrame =
        horizontalFrame(
            timestampMs = timestampMs,
            elbowY = 0.50f,
            leftKneeY = if (leftForward) 0.45f else 0.60f,
            rightKneeY = if (leftForward) 0.60f else 0.45f,
        )

    private fun relaxedMountainClimberMotionFrame(timestampMs: Long, leftForward: Boolean): PoseFrame =
        horizontalFrame(
            timestampMs = timestampMs,
            elbowY = 0.50f,
            leftKneeY = if (leftForward) 0.68f else 0.76f,
            rightKneeY = if (leftForward) 0.76f else 0.68f,
        )

    private fun sitUpFrame(timestampMs: Long): PoseFrame =
        fullBodyFrame(
            timestampMs = timestampMs,
            points = uprightPoints(
                shoulderY = 0.48f,
                hipY = 0.66f,
                leftKneeY = 0.76f,
                rightKneeY = 0.76f,
                leftKneeX = 0.42f,
                rightKneeX = 0.58f,
                wristY = 0.52f,
            )
        )

    private fun lateralRaiseFrame(timestampMs: Long): PoseFrame =
        fullBodyFrame(
            timestampMs = timestampMs,
            points = mapOf(
                "LEFT_SHOULDER" to point("LEFT_SHOULDER", 0.39f, 0.34f),
                "RIGHT_SHOULDER" to point("RIGHT_SHOULDER", 0.61f, 0.34f),
                "LEFT_ELBOW" to point("LEFT_ELBOW", 0.30f, 0.35f),
                "RIGHT_ELBOW" to point("RIGHT_ELBOW", 0.70f, 0.35f),
                "LEFT_WRIST" to point("LEFT_WRIST", 0.20f, 0.36f),
                "RIGHT_WRIST" to point("RIGHT_WRIST", 0.80f, 0.36f),
                "LEFT_HIP" to point("LEFT_HIP", 0.43f, 0.56f),
                "RIGHT_HIP" to point("RIGHT_HIP", 0.57f, 0.56f),
                "LEFT_KNEE" to point("LEFT_KNEE", 0.43f, 0.74f),
                "RIGHT_KNEE" to point("RIGHT_KNEE", 0.57f, 0.74f),
                "LEFT_ANKLE" to point("LEFT_ANKLE", 0.42f, 0.92f),
                "RIGHT_ANKLE" to point("RIGHT_ANKLE", 0.58f, 0.92f),
            )
        )

    private fun angleOpenLateralRaiseFrame(timestampMs: Long): PoseFrame =
        fullBodyFrame(
            timestampMs = timestampMs,
            points = mapOf(
                "LEFT_SHOULDER" to point("LEFT_SHOULDER", 0.39f, 0.34f),
                "RIGHT_SHOULDER" to point("RIGHT_SHOULDER", 0.61f, 0.34f),
                "LEFT_ELBOW" to point("LEFT_ELBOW", 0.35f, 0.47f),
                "RIGHT_ELBOW" to point("RIGHT_ELBOW", 0.65f, 0.47f),
                "LEFT_WRIST" to point("LEFT_WRIST", 0.27f, 0.60f),
                "RIGHT_WRIST" to point("RIGHT_WRIST", 0.73f, 0.60f),
                "LEFT_HIP" to point("LEFT_HIP", 0.43f, 0.56f),
                "RIGHT_HIP" to point("RIGHT_HIP", 0.57f, 0.56f),
                "LEFT_KNEE" to point("LEFT_KNEE", 0.43f, 0.74f),
                "RIGHT_KNEE" to point("RIGHT_KNEE", 0.57f, 0.74f),
                "LEFT_ANKLE" to point("LEFT_ANKLE", 0.42f, 0.92f),
                "RIGHT_ANKLE" to point("RIGHT_ANKLE", 0.58f, 0.92f),
            )
        )

    private fun angleDominantLateralRaiseFrame(timestampMs: Long): PoseFrame =
        fullBodyFrame(
            timestampMs = timestampMs,
            points = mapOf(
                "LEFT_SHOULDER" to point("LEFT_SHOULDER", 0.39f, 0.34f),
                "RIGHT_SHOULDER" to point("RIGHT_SHOULDER", 0.61f, 0.34f),
                "LEFT_ELBOW" to point("LEFT_ELBOW", 0.35f, 0.39f),
                "RIGHT_ELBOW" to point("RIGHT_ELBOW", 0.65f, 0.39f),
                "LEFT_WRIST" to point("LEFT_WRIST", 0.32f, 0.42f),
                "RIGHT_WRIST" to point("RIGHT_WRIST", 0.68f, 0.42f),
                "LEFT_HIP" to point("LEFT_HIP", 0.43f, 0.56f),
                "RIGHT_HIP" to point("RIGHT_HIP", 0.57f, 0.56f),
                "LEFT_KNEE" to point("LEFT_KNEE", 0.43f, 0.74f),
                "RIGHT_KNEE" to point("RIGHT_KNEE", 0.57f, 0.74f),
                "LEFT_ANKLE" to point("LEFT_ANKLE", 0.42f, 0.92f),
                "RIGHT_ANKLE" to point("RIGHT_ANKLE", 0.58f, 0.92f),
            )
        )

    private fun naturalArmStandingFrame(timestampMs: Long): PoseFrame =
        fullBodyFrame(
            timestampMs = timestampMs,
            points = mapOf(
                "LEFT_SHOULDER" to point("LEFT_SHOULDER", 0.39f, 0.34f),
                "RIGHT_SHOULDER" to point("RIGHT_SHOULDER", 0.61f, 0.34f),
                "LEFT_ELBOW" to point("LEFT_ELBOW", 0.36f, 0.50f),
                "RIGHT_ELBOW" to point("RIGHT_ELBOW", 0.64f, 0.50f),
                "LEFT_WRIST" to point("LEFT_WRIST", 0.34f, 0.68f),
                "RIGHT_WRIST" to point("RIGHT_WRIST", 0.66f, 0.68f),
                "LEFT_HIP" to point("LEFT_HIP", 0.43f, 0.56f),
                "RIGHT_HIP" to point("RIGHT_HIP", 0.57f, 0.56f),
                "LEFT_KNEE" to point("LEFT_KNEE", 0.43f, 0.74f),
                "RIGHT_KNEE" to point("RIGHT_KNEE", 0.57f, 0.74f),
                "LEFT_ANKLE" to point("LEFT_ANKLE", 0.42f, 0.92f),
                "RIGHT_ANKLE" to point("RIGHT_ANKLE", 0.58f, 0.92f),
            )
        )

    private fun lungeFrame(timestampMs: Long): PoseFrame =
        fullBodyFrame(
            timestampMs = timestampMs,
            points = mapOf(
                "LEFT_SHOULDER" to point("LEFT_SHOULDER", 0.39f, 0.32f),
                "RIGHT_SHOULDER" to point("RIGHT_SHOULDER", 0.61f, 0.32f),
                "LEFT_HIP" to point("LEFT_HIP", 0.43f, 0.56f),
                "RIGHT_HIP" to point("RIGHT_HIP", 0.57f, 0.56f),
                "LEFT_KNEE" to point("LEFT_KNEE", 0.43f, 0.70f),
                "RIGHT_KNEE" to point("RIGHT_KNEE", 0.62f, 0.72f),
                "LEFT_ANKLE" to point("LEFT_ANKLE", 0.58f, 0.70f),
                "RIGHT_ANKLE" to point("RIGHT_ANKLE", 0.66f, 0.92f),
            )
        )

    private fun lungeSquatOverlapFrame(timestampMs: Long): PoseFrame =
        fullBodyFrame(
            timestampMs = timestampMs,
            points = mapOf(
                "LEFT_SHOULDER" to point("LEFT_SHOULDER", 0.39f, 0.32f),
                "RIGHT_SHOULDER" to point("RIGHT_SHOULDER", 0.61f, 0.32f),
                "LEFT_HIP" to point("LEFT_HIP", 0.43f, 0.70f),
                "RIGHT_HIP" to point("RIGHT_HIP", 0.57f, 0.70f),
                "LEFT_KNEE" to point("LEFT_KNEE", 0.43f, 0.78f),
                "RIGHT_KNEE" to point("RIGHT_KNEE", 0.62f, 0.78f),
                "LEFT_ANKLE" to point("LEFT_ANKLE", 0.58f, 0.78f),
                "RIGHT_ANKLE" to point("RIGHT_ANKLE", 0.66f, 0.94f),
            )
        )

    private fun highKneesFrame(timestampMs: Long, leftRaised: Boolean): PoseFrame =
        fullBodyFrame(
            timestampMs = timestampMs,
            points = uprightPoints(
                shoulderY = 0.32f,
                hipY = 0.56f,
                leftKneeY = if (leftRaised) 0.54f else 0.74f,
                rightKneeY = if (leftRaised) 0.74f else 0.54f,
                leftKneeX = 0.44f,
                rightKneeX = 0.56f,
                wristY = 0.46f,
            )
        )

    private fun standingForwardBendFrame(timestampMs: Long, isBent: Boolean): PoseFrame =
        fullBodyFrame(
            timestampMs = timestampMs,
            points = uprightPoints(
                shoulderY = if (isBent) 0.54f else 0.32f,
                hipY = 0.56f,
                leftKneeY = 0.74f,
                rightKneeY = 0.74f,
                leftKneeX = 0.43f,
                rightKneeX = 0.57f,
                wristY = if (isBent) 0.76f else 0.58f,
                ankleSpread = 0.08f,
            )
        )

    private fun jumpRopeFrame(timestampMs: Long, ankleY: Float, wristY: Float = 0.54f): PoseFrame =
        fullBodyFrame(
            timestampMs = timestampMs,
            points = uprightPoints(
                shoulderY = 0.32f,
                hipY = 0.56f,
                leftKneeY = 0.73f,
                rightKneeY = 0.73f,
                leftKneeX = 0.45f,
                rightKneeX = 0.55f,
                wristY = wristY,
                ankleY = ankleY,
                ankleSpread = 0.08f,
            )
        )

    private fun jumpRopeLateralRaiseOverlapFrame(timestampMs: Long, ankleY: Float): PoseFrame =
        fullBodyFrame(
            timestampMs = timestampMs,
            points = mapOf(
                "LEFT_SHOULDER" to point("LEFT_SHOULDER", 0.39f, 0.34f),
                "RIGHT_SHOULDER" to point("RIGHT_SHOULDER", 0.61f, 0.34f),
                "LEFT_ELBOW" to point("LEFT_ELBOW", 0.34f, 0.50f),
                "RIGHT_ELBOW" to point("RIGHT_ELBOW", 0.66f, 0.50f),
                "LEFT_WRIST" to point("LEFT_WRIST", 0.30f, 0.58f),
                "RIGHT_WRIST" to point("RIGHT_WRIST", 0.70f, 0.58f),
                "LEFT_HIP" to point("LEFT_HIP", 0.43f, 0.56f),
                "RIGHT_HIP" to point("RIGHT_HIP", 0.57f, 0.56f),
                "LEFT_KNEE" to point("LEFT_KNEE", 0.45f, 0.73f),
                "RIGHT_KNEE" to point("RIGHT_KNEE", 0.55f, 0.73f),
                "LEFT_ANKLE" to point("LEFT_ANKLE", 0.46f, ankleY),
                "RIGHT_ANKLE" to point("RIGHT_ANKLE", 0.54f, ankleY),
            )
        )

    private fun horizontalFrame(
        timestampMs: Long,
        elbowY: Float,
        leftKneeY: Float,
        rightKneeY: Float,
    ): PoseFrame =
        fullBodyFrame(
            timestampMs = timestampMs,
            points = mapOf(
                "LEFT_SHOULDER" to point("LEFT_SHOULDER", 0.26f, 0.50f),
                "RIGHT_SHOULDER" to point("RIGHT_SHOULDER", 0.30f, 0.50f),
                "LEFT_ELBOW" to point("LEFT_ELBOW", 0.34f, elbowY),
                "RIGHT_ELBOW" to point("RIGHT_ELBOW", 0.36f, elbowY),
                "LEFT_WRIST" to point("LEFT_WRIST", 0.42f, 0.50f),
                "RIGHT_WRIST" to point("RIGHT_WRIST", 0.44f, 0.50f),
                "LEFT_HIP" to point("LEFT_HIP", 0.56f, 0.54f),
                "RIGHT_HIP" to point("RIGHT_HIP", 0.59f, 0.54f),
                "LEFT_KNEE" to point("LEFT_KNEE", 0.68f, leftKneeY),
                "RIGHT_KNEE" to point("RIGHT_KNEE", 0.70f, rightKneeY),
                "LEFT_ANKLE" to point("LEFT_ANKLE", 0.86f, 0.58f),
                "RIGHT_ANKLE" to point("RIGHT_ANKLE", 0.88f, 0.58f),
            )
        )

    private fun uprightPoints(
        shoulderY: Float,
        hipY: Float,
        leftKneeY: Float,
        rightKneeY: Float,
        leftKneeX: Float,
        rightKneeX: Float,
        wristY: Float,
        ankleY: Float = 0.92f,
        ankleSpread: Float = 0.16f,
    ): Map<String, LandmarkPoint> =
        mapOf(
            "LEFT_SHOULDER" to point("LEFT_SHOULDER", 0.39f, shoulderY),
            "RIGHT_SHOULDER" to point("RIGHT_SHOULDER", 0.61f, shoulderY),
            "LEFT_ELBOW" to point("LEFT_ELBOW", 0.34f, wristY - 0.02f),
            "RIGHT_ELBOW" to point("RIGHT_ELBOW", 0.66f, wristY - 0.02f),
            "LEFT_WRIST" to point("LEFT_WRIST", 0.31f, wristY),
            "RIGHT_WRIST" to point("RIGHT_WRIST", 0.69f, wristY),
            "LEFT_HIP" to point("LEFT_HIP", 0.43f, hipY),
            "RIGHT_HIP" to point("RIGHT_HIP", 0.57f, hipY),
            "LEFT_KNEE" to point("LEFT_KNEE", leftKneeX, leftKneeY),
            "RIGHT_KNEE" to point("RIGHT_KNEE", rightKneeX, rightKneeY),
            "LEFT_ANKLE" to point("LEFT_ANKLE", 0.5f - ankleSpread, ankleY),
            "RIGHT_ANKLE" to point("RIGHT_ANKLE", 0.5f + ankleSpread, ankleY),
        )

    private fun fullBodyFrame(
        timestampMs: Long,
        points: Map<String, LandmarkPoint>,
        confidence: Float = 1f,
    ): PoseFrame =
        PoseFrame(
            sessionId = 1L,
            nodeId = 1L,
            timestampMs = timestampMs,
            cameraRole = DeviceRole.FRONT_CAMERA,
            landmarks = points,
            overallConfidence = confidence,
            imageWidth = 720,
            imageHeight = 1280,
        )

    private fun point(name: String, x: Float, y: Float, confidence: Float = 1f): LandmarkPoint =
        LandmarkPoint(name, x, y, confidence = confidence)

    private fun readMainLayout(fileName: String): String {
        val candidates = listOf(
            File("src/main/res/layout/$fileName"),
            File("app/src/main/res/layout/$fileName"),
        )
        return candidates.first { it.exists() }.readText(Charsets.UTF_8)
    }

    private fun readMainXml(fileName: String): String {
        val candidates = listOf(
            File("src/main/res/xml/$fileName"),
            File("app/src/main/res/xml/$fileName"),
        )
        return candidates.first { it.exists() }.readText(Charsets.UTF_8)
    }

    private fun readMainJava(relativePath: String): String {
        val candidates = listOf(
            File("src/main/java/com/example/zhizijing/$relativePath"),
            File("app/src/main/java/com/example/zhizijing/$relativePath"),
        )
        return candidates.first { it.exists() }.readText(Charsets.UTF_8)
    }

    private fun readMainKotlin(relativePath: String): String {
        val candidates = listOf(
            File("src/main/java/com/example/zhizijing/$relativePath"),
            File("app/src/main/java/com/example/zhizijing/$relativePath"),
        )
        return candidates.first { it.exists() }.readText(Charsets.UTF_8)
    }
}
