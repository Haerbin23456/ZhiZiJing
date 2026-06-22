package com.example.zhizijing.ui.main

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.example.zhizijing.R
import com.example.zhizijing.data.repository.AuthRepository
import com.example.zhizijing.databinding.ActivityMainBinding
import com.example.zhizijing.domain.model.ActionType
import com.example.zhizijing.domain.model.DeviceRole
import com.example.zhizijing.nearby.connection.NearbyConnectionMode
import com.example.zhizijing.nearby.connection.NearbyRoomSession
import com.example.zhizijing.ui.analysis.ActionAnalysisActivity
import com.example.zhizijing.ui.auth.LoginActivity
import com.example.zhizijing.ui.camera.CameraNodeActivity
import com.example.zhizijing.ui.device.CameraSetupBottomSheet
import com.example.zhizijing.ui.history.HistoryActivity
import com.example.zhizijing.ui.room.RoomQrScannerActivity
import com.example.zhizijing.ui.settings.SettingsActivity
import com.example.zhizijing.utils.AppExecutors

object ProjectStatus {
    const val appTitle = "智姿镜"
    const val summary = "智姿镜是一款面向日常运动训练的姿态记录与复盘工具，可通过摄像头完成动作识别、保存训练记录，并与其他手机协同记录训练过程，帮助你查看姿势表现和训练报告。"
}

class MainActivity : ComponentActivity() {
    private lateinit var binding: ActivityMainBinding
    private var cameraSetupSheet: CameraSetupBottomSheet? = null
    private val cameraSetupPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        cameraSetupSheet?.onNearbyPermissionsResult()
    }
    private val cameraSetupQrScannerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result: ActivityResult ->
        cameraSetupSheet?.onQrScanResult(result)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        renderProjectStatus()
        setupNavigation()
        bindActions()
        renderTrainingMode()
    }

    override fun onResume() {
        super.onResume()
        if (::binding.isInitialized) {
            renderTrainingMode()
        }
    }

    private fun renderProjectStatus() {
        binding.appTitle.text = ProjectStatus.appTitle
        binding.appSummary.text = "配置机位，开始训练"
        binding.supportedActionsText.text = "训练项目"
    }

    private fun bindActions() {
        binding.modeCard.setOnClickListener {
            openCameraSetup()
        }
        binding.startSquatButton.setOnClickListener {
            startActionTraining(ActionType.SQUAT)
        }
        binding.startJumpingJackButton.setOnClickListener {
            startActionTraining(ActionType.JUMPING_JACK)
        }
        binding.startFollowerButton.setOnClickListener {
            startFollowerCapture()
        }
        binding.openHistoryButton.setOnClickListener {
            startActivity(Intent(this, HistoryActivity::class.java))
        }
        binding.settingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        binding.logoutButton.setOnClickListener {
            logout()
        }
    }

    private var currentDestination = MainDestination.TRAINING

    private fun setupNavigation() {
        binding.mainNavigationBar.setOnItemSelectedListener { item ->
            val destination = MainDestination.fromMenuItemId(item.itemId) ?: return@setOnItemSelectedListener false
            selectDestination(destination)
            true
        }
        selectDestination(currentDestination)
    }

    private fun selectDestination(destination: MainDestination) {
        currentDestination = destination
        binding.startPage.visibility = if (destination == MainDestination.TRAINING) View.VISIBLE else View.GONE
        binding.historyPage.visibility = if (destination == MainDestination.HISTORY) View.VISIBLE else View.GONE
        binding.settingsPage.visibility = if (destination == MainDestination.SETTINGS) View.VISIBLE else View.GONE
    }

    private fun renderTrainingMode() {
        val state = NearbyRoomSession.manager(this).currentState()
        val isFollower = state.mode != NearbyConnectionMode.IDLE && !state.isHostSession
        val modeText = when {
            state.mode == NearbyConnectionMode.IDLE -> "单机训练"
            state.isHostSession -> "多机位主机"
            else -> "多机位副机"
        }
        val role = if (state.localRole == DeviceRole.UNKNOWN) DeviceRole.FRONT_CAMERA else state.localRole
        val connectedCount = state.endpoints.count { it.isOnline }
        val connectionText = when {
            state.mode == NearbyConnectionMode.IDLE -> "单机"
            state.roomCode.isNotBlank() && state.isHostSession -> "房间 ${state.roomCode} · $connectedCount 台副机"
            state.roomCode.isNotBlank() -> "房间 ${state.roomCode} · 等待主机"
            else -> state.statusText
        }

        binding.modeTitleText.text = modeText
        binding.modeSummaryText.text = "${role.displayText()} · $connectionText"
        binding.hostActionPanel.visibility = if (isFollower) android.view.View.GONE else android.view.View.VISIBLE
        binding.startFollowerButton.visibility = if (isFollower) android.view.View.VISIBLE else android.view.View.GONE
    }

    private fun openCameraSetup() {
        cameraSetupSheet = CameraSetupBottomSheet(
            activity = this,
            requestNearbyPermissions = { permissions ->
                cameraSetupPermissionLauncher.launch(permissions)
            },
            launchQrScanner = {
                cameraSetupQrScannerLauncher.launch(Intent(this, RoomQrScannerActivity::class.java))
            },
            onDismissed = {
                cameraSetupSheet = null
                renderTrainingMode()
            },
        )
        cameraSetupSheet?.show()
    }

    private fun startActionTraining(actionType: ActionType) {
        if (actionType == ActionType.JUMPING_JACK) {
            val state = NearbyRoomSession.manager(this).currentState()
            if (state.mode != NearbyConnectionMode.IDLE || state.localRole != DeviceRole.FRONT_CAMERA) {
                Toast.makeText(this, "开合跳只支持单机正面机位训练，请退出多机位并切换为正面机位。", Toast.LENGTH_LONG).show()
                return
            }
        }
        startActivity(
            Intent(this, CameraNodeActivity::class.java)
                .putExtra(ActionAnalysisActivity.EXTRA_ACTION_TYPE, actionType.name)
        )
    }

    private fun startFollowerCapture() {
        startActivity(
            Intent(this, CameraNodeActivity::class.java)
                .putExtra(ActionAnalysisActivity.EXTRA_ACTION_TYPE, ActionType.UNKNOWN.name)
        )
    }

    private fun DeviceRole.displayText(): String =
        when (this) {
            DeviceRole.FRONT_CAMERA -> "正面机位"
            DeviceRole.SIDE_CAMERA -> "侧面机位"
            DeviceRole.BACKUP_CAMERA -> "备用机位"
            DeviceRole.HOST -> "主控"
            DeviceRole.UNKNOWN -> "未设置"
        }

    private fun logout() {
        binding.logoutButton.isEnabled = false
        AppExecutors.io.execute {
            AuthRepository.logout(this)
            runOnUiThread {
                NearbyRoomSession.stopIfCreated()
                Toast.makeText(this, "已退出登录", Toast.LENGTH_SHORT).show()
                startActivity(
                    Intent(this, LoginActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                )
                finish()
            }
        }
    }

    private enum class MainDestination(val menuItemId: Int) {
        TRAINING(R.id.navigation_training),
        HISTORY(R.id.navigation_history),
        SETTINGS(R.id.navigation_settings);

        companion object {
            fun fromMenuItemId(menuItemId: Int): MainDestination? =
                entries.firstOrNull { it.menuItemId == menuItemId }
        }
    }

}
