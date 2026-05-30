package com.example.zhizijing.ui.main

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.example.zhizijing.databinding.ActivityMainBinding

data class FeatureStatus(
    val title: String,
    val state: String,
    val detail: String,
)

object ProjectStatus {
    const val appTitle = "智姿镜"
    const val summary = "当前版本已迁回 XML + ViewBinding 工程基础，真实业务能力按制作指南后续分阶段接入。"
    const val pendingHint = "待实现"

    val featureStatuses = listOf(
        FeatureStatus("工程基础", "已修复", "入口 Activity 使用 XML + ViewBinding，Compose 展示壳已移除。"),
        FeatureStatus("登录注册", pendingHint, "后续接入 Room 与 DataStore 后实现注册、登录和自动登录。"),
        FeatureStatus("训练房间", pendingHint, "后续实现创建房间、加入房间、二维码和 Nearby 连接。"),
        FeatureStatus("摄像头节点", pendingHint, "后续接入 CameraX 预览、ImageAnalysis 和权限处理。"),
        FeatureStatus("姿态识别", pendingHint, "后续接入 ML Kit Pose Detection 与人体骨架 Overlay。"),
        FeatureStatus("动作评估", pendingHint, "后续先用规则完成深蹲和开合跳识别、计数、评分。"),
        FeatureStatus("历史与报告", pendingHint, "后续实现 Room 历史记录、JSON 导出和 PdfDocument 报告。"),
    )
}

class MainActivity : ComponentActivity() {
    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        renderProjectStatus()
        bindPendingActions()
    }

    private fun renderProjectStatus() {
        binding.appTitle.text = ProjectStatus.appTitle
        binding.appSummary.text = ProjectStatus.summary
        binding.statusList.text = ProjectStatus.featureStatuses.joinToString(separator = "\n\n") { status ->
            "${status.title}：${status.state}\n${status.detail}"
        }
    }

    private fun bindPendingActions() {
        val pendingAction = {
            Toast.makeText(this, "该入口将在后续阶段实现", Toast.LENGTH_SHORT).show()
        }

        binding.loginEntryButton.setOnClickListener { pendingAction() }
        binding.roomEntryButton.setOnClickListener { pendingAction() }
        binding.cameraEntryButton.setOnClickListener { pendingAction() }
        binding.analysisEntryButton.setOnClickListener { pendingAction() }
        binding.historyEntryButton.setOnClickListener { pendingAction() }
    }
}
