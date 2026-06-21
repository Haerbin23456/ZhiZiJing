package com.example.zhizijing.ui.settings

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.example.zhizijing.BuildConfig
import com.example.zhizijing.data.datastore.AppSettings
import com.example.zhizijing.data.datastore.AppSettingsDataStore
import com.example.zhizijing.databinding.ActivitySettingsBinding

class SettingsActivity : ComponentActivity() {
    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.buildMarkText.text = "构建：${BuildConfig.BUILD_MARK}"

        renderSettings(AppSettingsDataStore.load(this))
        bindActions()
    }

    private fun bindActions() {
        binding.saveSettingsButton.setOnClickListener { saveSettings() }
        binding.backButton.setOnClickListener { finish() }
    }

    private fun renderSettings(settings: AppSettings) {
        binding.showSkeletonOverlayCheckBox.isChecked = settings.showSkeletonOverlay
        binding.saveVideoEnabledCheckBox.isChecked = settings.saveVideoEnabled
        binding.defaultExportDirInput.setText(settings.defaultExportDir)
    }

    private fun saveSettings() {
        val defaultExportDir = binding.defaultExportDirInput.text.toString().trim()
        val outputError = SettingsValidator.exportDirectoryError(defaultExportDir)
        if (outputError != null) {
            Toast.makeText(this, outputError, Toast.LENGTH_LONG).show()
            return
        }
        AppSettingsDataStore.saveDisplaySettings(this, binding.showSkeletonOverlayCheckBox.isChecked)
        AppSettingsDataStore.saveOutputSettings(
            context = this,
            saveVideoEnabled = binding.saveVideoEnabledCheckBox.isChecked,
            defaultExportDir = defaultExportDir,
        )
        Toast.makeText(this, "设置已保存", Toast.LENGTH_SHORT).show()
    }
}

object SettingsValidator {
    fun exportDirectoryError(defaultExportDir: String): String? {
        val value = defaultExportDir.trim()
        return when {
            value.isBlank() -> "默认导出目录不能为空。"
            value.length > 80 -> "默认导出目录不能超过 80 个字符。"
            value.startsWith("/") || value.startsWith("\\") -> "默认导出目录需要使用应用私有目录下的相对路径。"
            ":" in value -> "默认导出目录不能包含盘符或冒号。"
            "\\" in value -> "默认导出目录请使用 / 分隔。"
            value.split('/').any { it == "." || it == ".." || it.isBlank() } -> "默认导出目录不能包含空路径段、. 或 ..。"
            value.any { it.code < 32 } -> "默认导出目录不能包含控制字符。"
            else -> null
        }
    }
}
