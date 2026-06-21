package com.example.zhizijing.ui.recognition

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import com.example.zhizijing.data.datastore.AppSettingsDataStore
import com.example.zhizijing.data.repository.TrainingRepository
import com.example.zhizijing.databinding.ActivityActionRecognitionBinding
import com.example.zhizijing.domain.model.ActionType
import com.example.zhizijing.ui.analysis.ActionAnalysisActivity
import com.example.zhizijing.ui.camera.CameraNodeActivity
import com.example.zhizijing.utils.AppExecutors

class ActionRecognitionActivity : ComponentActivity() {
    private lateinit var binding: ActivityActionRecognitionBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityActionRecognitionBinding.inflate(layoutInflater)
        setContentView(binding.root)

        loadRecognitionStatus()
        binding.startCameraRecognitionButton.setOnClickListener { openCameraRecognition() }
        binding.openAnalysisButton.setOnClickListener { openAnalysis() }
        binding.backButton.setOnClickListener { finish() }
    }

    private fun loadRecognitionStatus() {
        binding.recognitionStatusText.text = "正在读取动作识别状态..."
        AppExecutors.io.execute {
            val settings = AppSettingsDataStore.load(this)
            val latest = TrainingRepository.latestForCurrentUser(this)
            val status = ActionRecognitionStatusFormatter.format(
                actionRecognitionThreshold = settings.actionRecognitionThreshold,
                latest = latest,
            )
            runOnUiThread {
                binding.recognitionStatusText.text = status
            }
        }
    }

    private fun openCameraRecognition() {
        startActivity(
            Intent(this, CameraNodeActivity::class.java)
                .putExtra(ActionAnalysisActivity.EXTRA_ACTION_TYPE, ActionType.UNKNOWN.name)
        )
    }

    private fun openAnalysis() {
        startActivity(
            Intent(this, ActionAnalysisActivity::class.java)
                .putExtra(ActionAnalysisActivity.EXTRA_ACTION_TYPE, ActionType.UNKNOWN.name)
        )
    }
}
