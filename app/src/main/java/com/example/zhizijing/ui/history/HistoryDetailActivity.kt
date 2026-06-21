package com.example.zhizijing.ui.history

import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.example.zhizijing.R
import com.example.zhizijing.data.repository.ExportType
import com.example.zhizijing.data.repository.ReportRepository
import com.example.zhizijing.data.repository.TrainingRepository
import com.example.zhizijing.databinding.ActivityHistoryDetailBinding
import com.example.zhizijing.report.ReportShareHelper
import com.example.zhizijing.report.TrainingReportStats
import com.example.zhizijing.report.TrainingReportStatsCalculator
import com.example.zhizijing.report.TrainingVideoArtifacts
import com.example.zhizijing.ui.main.MainActivity
import com.example.zhizijing.ui.result.TrainingDetailFormatter
import com.example.zhizijing.ui.result.TrainingDetailUiState
import com.example.zhizijing.ui.result.TrainingVideoPlaybackBinder
import com.example.zhizijing.utils.AppExecutors
import java.io.File

class HistoryDetailActivity : ComponentActivity() {
    private lateinit var binding: ActivityHistoryDetailBinding
    private lateinit var videoPlaybackBinder: TrainingVideoPlaybackBinder
    private var sessionId: Long = -1L
    private var latestGeneratedPdfFile: File? = null
    private var returnTarget = ReturnTarget.HISTORY

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runCatching {
            setupContent()
        }.onFailure { error ->
            val message = "训练详情页面启动失败：${error.message ?: error.javaClass.simpleName}"
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            if (::binding.isInitialized) {
                renderFatalDetailError(message)
            } else {
                showStartupErrorPage(message)
            }
        }
    }

    private fun setupContent() {
        binding = ActivityHistoryDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)
        videoPlaybackBinder = TrainingVideoPlaybackBinder(
            statusText = binding.videoPlaybackStatusText,
            videoView = binding.videoPlayerView,
            controlRow = binding.videoPlaybackControlRow,
            playPauseButton = binding.playPauseVideoButton,
            seekBar = binding.videoSeekBar,
            positionText = binding.videoPositionText,
            switchRow = binding.videoSwitchRow,
            previousButton = binding.previousVideoButton,
            nextButton = binding.nextVideoButton,
        )

        sessionId = intent.getLongExtra(EXTRA_SESSION_ID, -1L)
        returnTarget = ReturnTarget.from(intent.getStringExtra(EXTRA_RETURN_TARGET))
        binding.resultText.text = "正在启动训练详情：记录 $sessionId。"
        binding.exportPdfButton.isEnabled = sessionId > 0L
        setSharePdfReportEnabled(false)
        loadDetail()
        binding.exportPdfButton.setOnClickListener { exportReport(ExportType.PDF) }
        binding.sharePdfReportButton.setOnClickListener { shareGeneratedPdfReport() }
        binding.homeButton.text = returnTarget.buttonText
        binding.homeButton.setOnClickListener { handleReturnButton() }
    }

    private fun handleReturnButton() {
        when (returnTarget) {
            ReturnTarget.HOME -> {
                startActivity(
                    Intent(this, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                )
                finish()
            }
            ReturnTarget.HISTORY -> finish()
        }
    }

    private fun loadDetail() {
        binding.resultText.text = "正在读取历史训练详情..."
        if (sessionId <= 0L) {
            binding.exportPdfButton.isEnabled = false
            latestGeneratedPdfFile = null
            setSharePdfReportEnabled(false)
            videoPlaybackBinder.render(emptyList())
            renderKeyFramePreview(TrainingReportStatsCalculator.from(emptyList()))
            renderDetailState(TrainingDetailFormatter.unavailable("未找到这次训练记录。"))
            return
        }

        AppExecutors.io.execute {
            runCatching {
                val record = TrainingRepository.findSummary(this, sessionId)
                val actions = TrainingRepository.findActionResults(this, sessionId)
                val videoFiles = TrainingVideoArtifacts.listForSession(filesDir, sessionId)
                val stats = TrainingReportStatsCalculator.from(actions)
                DetailLoadResult(
                    state = record?.let { summary ->
                        TrainingDetailFormatter.fromSummary(
                            summary = summary,
                            stats = stats,
                            videoCount = videoFiles.size,
                        )
                    } ?: TrainingDetailFormatter.unavailable("暂无可展示的历史训练详情。"),
                    stats = stats,
                    videoFiles = videoFiles,
                    canExport = record != null,
                )
            }.onSuccess { result ->
                runOnUiThread {
                    runCatching {
                        renderLoadedDetail(result)
                    }.onFailure { error ->
                        renderFatalDetailError(
                            "训练详情渲染失败：${error.message ?: error.javaClass.simpleName}"
                        )
                    }
                }
            }.onFailure { error ->
                runOnUiThread {
                    renderFatalDetailError(
                        "训练详情读取失败：${error.message ?: "请返回历史记录后重试。"}"
                    )
                }
            }
        }
    }

    private fun renderLoadedDetail(result: DetailLoadResult) {
        binding.exportPdfButton.isEnabled = result.canExport
        videoPlaybackBinder.render(result.videoFiles)
        renderKeyFramePreview(result.stats)
        renderDetailState(result.state)
    }

    override fun onPause() {
        super.onPause()
        if (::videoPlaybackBinder.isInitialized) {
            videoPlaybackBinder.pause()
        }
    }

    override fun onDestroy() {
        if (::videoPlaybackBinder.isInitialized) {
            videoPlaybackBinder.stop()
        }
        super.onDestroy()
    }

    private fun renderDetailState(state: TrainingDetailUiState) {
        binding.resultActionText.text = state.actionTitle
        binding.resultPrimaryMetricText.text = state.primaryMetric
        binding.resultScoreText.text = state.scoreText
        binding.resultSuggestionText.text = state.suggestionText
        binding.qualifiedCountText.text = state.qualifiedText
        binding.durationText.text = state.durationText
        binding.confidenceText.text = state.confidenceText
        binding.resultText.text = state.reviewSummaryText
        binding.keyFramePreviewText.text = state.keyFrameText
    }

    private fun renderKeyFramePreview(stats: TrainingReportStats) {
        binding.keyFramePreviewText.text = TrainingDetailFormatter.keyFrameText(stats)
        val firstBitmap = stats.firstKeyFramePath?.let { decodeKeyFrameBitmap(it) }
        val secondBitmap = stats.secondKeyFramePath?.let { decodeKeyFrameBitmap(it) }
        binding.keyFramePreviewContainer.visibility = if (firstBitmap != null || secondBitmap != null) {
            View.VISIBLE
        } else {
            View.GONE
        }
        binding.firstKeyFrameImage.setImageBitmap(firstBitmap)
        binding.secondKeyFrameImage.setImageBitmap(secondBitmap)
    }

    private fun decodeKeyFrameBitmap(path: String) =
        runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                return@runCatching null
            }
            BitmapFactory.decodeFile(
                path,
                BitmapFactory.Options().apply {
                    inSampleSize = previewSampleSize(bounds.outWidth, bounds.outHeight)
                },
            )
        }
            .onFailure { error ->
                Toast.makeText(
                    this,
                    "关键帧预览加载失败：${error.message ?: error.javaClass.simpleName}",
                    Toast.LENGTH_LONG,
                ).show()
            }
            .getOrNull()

    private fun previewSampleSize(width: Int, height: Int): Int {
        var sampleSize = 1
        var safeWidth = width
        var safeHeight = height
        while (safeWidth > KEY_FRAME_PREVIEW_MAX_SIZE || safeHeight > KEY_FRAME_PREVIEW_MAX_SIZE) {
            sampleSize *= 2
            safeWidth /= 2
            safeHeight /= 2
        }
        return sampleSize.coerceAtLeast(1)
    }

    private fun renderFatalDetailError(message: String) {
        if (!::binding.isInitialized) {
            showStartupErrorPage(message)
            return
        }
        binding.exportPdfButton.isEnabled = false
        latestGeneratedPdfFile = null
        setSharePdfReportEnabled(false)
        if (::videoPlaybackBinder.isInitialized) {
            videoPlaybackBinder.render(emptyList())
        }
        renderKeyFramePreview(TrainingReportStatsCalculator.from(emptyList()))
        renderDetailState(TrainingDetailFormatter.unavailable(message))
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun showStartupErrorPage(message: String) {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(24), dp(32), dp(24), dp(32))
            setBackgroundColor(getColor(R.color.zzj_background))
        }
        container.addView(TextView(this).apply {
            text = "训练详情暂时无法打开"
            setTextColor(getColor(R.color.zzj_text_primary))
            textSize = 24f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        container.addView(TextView(this).apply {
            text = message
            setTextColor(getColor(R.color.zzj_text_secondary))
            textSize = 15f
            setPadding(0, dp(14), 0, dp(18))
        })
        container.addView(Button(this).apply {
            text = "返回上一页"
            setOnClickListener { finish() }
        })
        setContentView(container)
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun exportReport(type: ExportType) {
        if (sessionId <= 0L) return
        binding.exportPdfButton.isEnabled = false
        AppExecutors.io.execute {
            runCatching { ReportRepository.export(this, sessionId, type) }
                .onSuccess { result ->
                    runOnUiThread {
                        binding.exportPdfButton.isEnabled = true
                        latestGeneratedPdfFile = result.file.takeIf { file ->
                            file.extension.equals("pdf", ignoreCase = true) &&
                                ReportShareHelper.canShareReportFile(filesDir, file)
                        }
                        setSharePdfReportEnabled(latestGeneratedPdfFile != null)
                        Toast.makeText(
                            this,
                            "PDF 报告已生成：${result.file.name}",
                            Toast.LENGTH_LONG,
                        ).show()
                        loadDetail()
                    }
                }
                .onFailure { error ->
                    runOnUiThread {
                        binding.exportPdfButton.isEnabled = true
                        Toast.makeText(this, "导出失败：${error.message}", Toast.LENGTH_SHORT).show()
                    }
                }
        }
    }

    private fun setSharePdfReportEnabled(enabled: Boolean) {
        binding.sharePdfReportButton.isEnabled = enabled
        binding.sharePdfReportButton.alpha = if (enabled) 1f else DISABLED_SHARE_BUTTON_ALPHA
    }

    private fun shareGeneratedPdfReport() {
        val file = latestGeneratedPdfFile
        if (file == null || !file.exists()) {
            setSharePdfReportEnabled(false)
            Toast.makeText(this, "请先生成 PDF 报告", Toast.LENGTH_SHORT).show()
            return
        }
        runCatching {
            ReportShareHelper.shareReport(this, file)
        }.onFailure { error ->
            Toast.makeText(this, "分享失败：${error.message}", Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        const val EXTRA_SESSION_ID = "extra_session_id"
        const val EXTRA_RETURN_TARGET = "extra_return_target"
        const val RETURN_TARGET_HISTORY = "history"
        const val RETURN_TARGET_HOME = "home"
        private const val DISABLED_SHARE_BUTTON_ALPHA = 0.45f
        private const val KEY_FRAME_PREVIEW_MAX_SIZE = 1024
    }

    private enum class ReturnTarget(val rawValue: String, val buttonText: String) {
        HISTORY("history", "返回历史记录"),
        HOME("home", "返回首页");

        companion object {
            fun from(rawValue: String?): ReturnTarget =
                entries.firstOrNull { target -> target.rawValue == rawValue } ?: HISTORY
        }
    }

    private data class DetailLoadResult(
        val state: TrainingDetailUiState,
        val stats: TrainingReportStats,
        val videoFiles: List<File>,
        val canExport: Boolean,
    )
}


