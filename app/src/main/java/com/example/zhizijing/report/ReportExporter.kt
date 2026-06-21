package com.example.zhizijing.report

import com.example.zhizijing.data.entity.ActionResultEntity
import com.example.zhizijing.data.entity.DeviceNodeEntity
import com.example.zhizijing.domain.model.TrainingSummary
import java.io.File

interface ReportExporter {
    fun export(
        summary: TrainingSummary,
        actionResults: List<ActionResultEntity>,
        deviceNodes: List<DeviceNodeEntity>,
        outputDir: File,
        videoFiles: List<File> = emptyList(),
    ): File
}
