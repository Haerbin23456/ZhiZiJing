package com.example.zhizijing.report.pdf

import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import com.example.zhizijing.data.entity.ActionResultEntity
import com.example.zhizijing.data.entity.DeviceNodeEntity
import com.example.zhizijing.domain.model.TrainingSummary
import com.example.zhizijing.report.ReportExporter
import com.example.zhizijing.report.TrainingReportFormatter
import com.example.zhizijing.report.TrainingReportStatsCalculator
import java.io.File
import java.io.FileOutputStream

class PdfTrainingReportExporter : ReportExporter {
    override fun export(
        summary: TrainingSummary,
        actionResults: List<ActionResultEntity>,
        deviceNodes: List<DeviceNodeEntity>,
        outputDir: File,
        videoFiles: List<File>,
    ): File {
        outputDir.mkdirs()
        val output = File(outputDir, "training_${summary.sessionId}.pdf")
        val document = PdfDocument()
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 22f
            isFakeBoldText = true
        }
        val sectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 16f
            isFakeBoldText = true
        }
        val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 13f
        }
        val smallPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 11f
        }
        val stats = TrainingReportStatsCalculator.from(actionResults)

        try {
            val writer = PdfPageWriter(document, titlePaint, sectionPaint, bodyPaint, smallPaint)
            writer.drawTitle(TrainingReportFormatter.title(summary))
            writer.drawSection("训练结论")
            TrainingReportFormatter.pdfOverviewText(
                summary = summary,
                actionResults = actionResults,
                deviceNodes = deviceNodes,
                videoFiles = videoFiles,
            )
                .lines()
                .forEach { line -> writer.drawWrappedText(line, bodyPaint) }

            if (actionResults.isNotEmpty()) {
                writer.drawSection("动作复盘")
                TrainingReportFormatter.pdfActionReviewText(actionResults)
                    .lines()
                    .forEach { line ->
                        writer.drawWrappedText(line, bodyPaint)
                    }
            }

            val keyFrameFiles = stats.keyFramePaths
                .take(MAX_KEY_FRAME_PREVIEWS)
                .map { File(it) }
                .filter { it.exists() }
            if (keyFrameFiles.isNotEmpty()) {
                writer.drawSection("关键帧预览")
                keyFrameFiles.forEachIndexed { index, file ->
                    writer.drawKeyFrame(index + 1, file)
                }
            }

            writer.finish()
            FileOutputStream(output).use { document.writeTo(it) }
        } finally {
            document.close()
        }
        return output
    }

    private class PdfPageWriter(
        private val document: PdfDocument,
        private val titlePaint: Paint,
        private val sectionPaint: Paint,
        private val bodyPaint: Paint,
        private val smallPaint: Paint,
    ) {
        private val footerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 10f
        }
        private var pageNumber = 0
        private var page: PdfDocument.Page? = null
        private var y = TOP

        init {
            startPage()
        }

        fun drawTitle(text: String) {
            ensureSpace(40f)
            currentCanvas.drawText(text, LEFT, y, titlePaint)
            y += 36f
        }

        fun drawSection(text: String) {
            ensureSpace(42f)
            y += 10f
            currentCanvas.drawText(text, LEFT, y, sectionPaint)
            y += 24f
        }

        fun drawWrappedText(
            text: String,
            paint: Paint,
            lineHeight: Float = 20f,
        ) {
            if (text.isBlank()) {
                ensureSpace(lineHeight)
                y += lineHeight / 2f
                return
            }
            var remaining = text.trim()
            while (remaining.isNotEmpty()) {
                val count = paint.breakText(remaining, true, CONTENT_WIDTH, null)
                    .coerceAtLeast(1)
                val line = remaining.take(count).trimEnd()
                ensureSpace(lineHeight)
                currentCanvas.drawText(line, LEFT, y, paint)
                y += lineHeight
                remaining = remaining.drop(count).trimStart()
            }
        }

        fun drawKeyFrame(index: Int, file: File) {
            val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: return
            try {
                ensureSpace(KEY_FRAME_HEIGHT + 48f)
                currentCanvas.drawBitmap(
                    bitmap,
                    null,
                    RectF(LEFT, y, LEFT + KEY_FRAME_WIDTH, y + KEY_FRAME_HEIGHT),
                    null,
                )
                y += KEY_FRAME_HEIGHT + 16f
                drawWrappedText("关键帧 $index", smallPaint, lineHeight = 16f)
            } finally {
                bitmap.recycle()
            }
        }

        fun finish() {
            finishPage()
        }

        private val currentCanvas: Canvas
            get() = requireNotNull(page).canvas

        private fun ensureSpace(requiredHeight: Float) {
            if (y + requiredHeight > PAGE_HEIGHT - BOTTOM) {
                finishPage()
                startPage()
            }
        }

        private fun startPage() {
            pageNumber += 1
            val pageInfo = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber).create()
            page = document.startPage(pageInfo)
            y = TOP
        }

        private fun finishPage() {
            val currentPage = page ?: return
            currentPage.canvas.drawText("第 $pageNumber 页", LEFT, PAGE_HEIGHT - 28f, footerPaint)
            document.finishPage(currentPage)
            page = null
        }
    }

    companion object {
        private const val PAGE_WIDTH = 595
        private const val PAGE_HEIGHT = 842
        private const val LEFT = 48f
        private const val TOP = 48f
        private const val BOTTOM = 56f
        private const val CONTENT_WIDTH = PAGE_WIDTH - LEFT * 2
        private const val KEY_FRAME_WIDTH = 160f
        private const val KEY_FRAME_HEIGHT = 240f
        private const val MAX_KEY_FRAME_PREVIEWS = 2
    }
}
