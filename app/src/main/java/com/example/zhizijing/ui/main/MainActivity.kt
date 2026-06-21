package com.example.zhizijing.ui.main

import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
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
import com.example.zhizijing.ui.device.DeviceGroupActivity
import com.example.zhizijing.ui.history.HistoryActivity
import com.example.zhizijing.ui.room.JoinRoomActivity
import com.example.zhizijing.ui.room.RoomActivity
import com.example.zhizijing.ui.settings.SettingsActivity
import com.example.zhizijing.utils.AppExecutors
import com.google.android.material.dialog.MaterialAlertDialogBuilder

object ProjectStatus {
    const val appTitle = "智姿镜"
    const val summary = "智姿镜是一款面向日常运动训练的姿态记录与复盘工具，可通过摄像头完成动作识别、保存训练记录，并与其他手机协同记录训练过程，帮助你查看姿势表现和训练报告。"
}

class MainActivity : ComponentActivity() {
    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        renderProjectStatus()
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
        binding.appSummary.text = "选择项目，开始采集。"
        binding.supportedActionsText.text = "训练项目"
    }

    private fun bindActions() {
        binding.modeCard.setOnClickListener {
            showTrainingModeDialog()
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
        binding.startTab.setOnClickListener {
            renderTab(binding.startTab, true)
            renderTab(binding.historyTab, false)
        }
        binding.historyTab.setOnClickListener {
            startActivity(Intent(this, HistoryActivity::class.java))
        }
        binding.settingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        binding.logoutButton.setOnClickListener {
            logout()
        }
    }

    private fun renderTab(tabView: TextView, selected: Boolean) {
        tabView.setBackgroundResource(
            if (selected) R.drawable.bg_zzj_tab_selected else R.drawable.bg_zzj_tab_unselected
        )
        tabView.setTextColor(getColor(if (selected) R.color.zzj_on_primary else R.color.zzj_text_muted))
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

    private fun showTrainingModeDialog() {
        val items = arrayOf(
            "单机 · 正面机位",
            "单机 · 侧面机位",
            "多机位 · 创建房间",
            "多机位 · 加入房间",
            "机位分配",
        )
        MaterialAlertDialogBuilder(this)
            .setTitle("机位模式")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> setLocalSingleRole(DeviceRole.FRONT_CAMERA)
                    1 -> setLocalSingleRole(DeviceRole.SIDE_CAMERA)
                    2 -> startActivity(Intent(this, RoomActivity::class.java))
                    3 -> startActivity(Intent(this, JoinRoomActivity::class.java))
                    4 -> startActivity(Intent(this, DeviceGroupActivity::class.java))
                }
            }
            .show()
    }

    private fun setLocalSingleRole(role: DeviceRole) {
        NearbyRoomSession.manager(this).assignLocalRole(role)
        Toast.makeText(this, "已设置为${role.displayText()}。", Toast.LENGTH_SHORT).show()
        renderTrainingMode()
    }

    private fun startActionTraining(actionType: ActionType) {
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

}
