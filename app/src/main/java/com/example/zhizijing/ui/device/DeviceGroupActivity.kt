package com.example.zhizijing.ui.device

import android.content.res.ColorStateList
import android.content.Intent
import android.os.Bundle
import android.os.CountDownTimer
import android.view.View
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.example.zhizijing.R
import com.example.zhizijing.databinding.ActivityDeviceGroupBinding
import com.example.zhizijing.domain.model.ActionType
import com.example.zhizijing.domain.model.DeviceRole
import com.example.zhizijing.nearby.connection.NearbyConnectionListener
import com.example.zhizijing.nearby.connection.NearbyConnectionMode
import com.example.zhizijing.nearby.connection.NearbyConnectionState
import com.example.zhizijing.nearby.connection.NearbyEndpoint
import com.example.zhizijing.nearby.connection.NearbyRoomSession
import com.example.zhizijing.nearby.message.NearbyMessage
import com.example.zhizijing.nearby.message.NearbyMessageType
import com.example.zhizijing.ui.analysis.ActionAnalysisActivity
import com.example.zhizijing.ui.camera.CameraNodeActivity
import com.google.android.material.button.MaterialButton

class DeviceGroupActivity : ComponentActivity() {
    private lateinit var binding: ActivityDeviceGroupBinding
    private var currentState = NearbyConnectionState()
    private var remoteCountdownTimer: CountDownTimer? = null
    private var openedRemoteTraining = false
    private val nearbyListener = object : NearbyConnectionListener {
        override fun onNearbyStateChanged(state: NearbyConnectionState) {
            currentState = state
            runOnUiThread { renderDevices(state) }
        }

        override fun onNearbyMessageReceived(endpointId: String, message: NearbyMessage) {
            runOnUiThread { handleNearbyMessage(message) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDeviceGroupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 本机与在线节点机位入口
        currentState = NearbyRoomSession.manager(this).currentState()
        renderDevices(currentState)
        binding.assignSelfFrontButton.setOnClickListener { assignSelfRole(DeviceRole.FRONT_CAMERA) }
        binding.assignSelfSideButton.setOnClickListener { assignSelfRole(DeviceRole.SIDE_CAMERA) }
        binding.assignJoinedFrontButton.setOnClickListener { assignJoinedRole(DeviceRole.FRONT_CAMERA) }
        binding.assignJoinedSideButton.setOnClickListener { assignJoinedRole(DeviceRole.SIDE_CAMERA) }
        binding.enterTrainingButton.setOnClickListener {
            startActivity(
                Intent(this, CameraNodeActivity::class.java)
                    .putExtra(ActionAnalysisActivity.EXTRA_ACTION_TYPE, ActionType.UNKNOWN.name)
            )
        }
        binding.backButton.setOnClickListener { finish() }
    }

    override fun onStart() {
        super.onStart()
        NearbyRoomSession.manager(this).addListener(nearbyListener)
    }

    override fun onStop() {
        NearbyRoomSession.manager(this).removeListener(nearbyListener)
        super.onStop()
    }

    override fun onDestroy() {
        remoteCountdownTimer?.cancel()
        super.onDestroy()
    }

    private fun assignSelfRole(role: DeviceRole) {
        if (!isHostController(currentState)) {
            showNodeOnlyRoleToast()
            return
        }
        val manager = NearbyRoomSession.manager(this)
        manager.assignLocalRole(role)
        currentState = manager.currentState()
        renderDevices(currentState)
        Toast.makeText(this, "已设置本机为${role.displayText()}。", Toast.LENGTH_SHORT).show()
    }

    private fun assignJoinedRole(role: DeviceRole) {
        if (!isHostController(currentState)) {
            showNodeOnlyRoleToast()
            return
        }
        // 在线节点角色下发
        val endpoint = DeviceRoleAssignmentPolicy.selectEndpointForRole(currentState.endpoints, role)
        if (endpoint == null) {
            Toast.makeText(this, "暂无在线节点手机，无法设置${role.displayText()}。", Toast.LENGTH_SHORT).show()
            return
        }
        val manager = NearbyRoomSession.manager(this)
        val assigned = manager.assignRole(endpoint.endpointId, role)
        currentState = manager.currentState()
        renderDevices(currentState)
        if (assigned) {
            val text = if (endpoint.role == role) {
                "${endpoint.deviceName} 已经是${role.displayText()}，主控端已重新确认。"
            } else {
                "已将 ${endpoint.deviceName} 设置为${role.displayText()}。"
            }
            Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "机位分配失败，请确认当前手机是主控端且节点在线。", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showNodeOnlyRoleToast() {
        Toast.makeText(this, "加入房间的手机只显示当前机位，请在主控端配置机位。", Toast.LENGTH_SHORT).show()
    }

    private fun renderDevices(state: NearbyConnectionState) {
        val isHost = isHostController(state)
        val hasOnlineEndpoint = state.endpoints.any { it.isOnline }
        binding.localRoleText.text = if (isHost) {
            "本机当前机位：${state.localRole.displayText()}。可在下方设置本机或加入手机的机位。"
        } else {
            "本机当前机位：${state.localRole.displayText()}。机位由主控端配置，本机只显示结果。"
        }
        binding.roleControlHintText.text = if (isHost) {
            if (hasOnlineEndpoint) {
                "选择一个按钮即可立即更新机位；同一时间只允许一台正面机位和一台侧面机位。"
            } else {
                "可先设置本机机位；等待加入手机在线后，再设置加入手机的正面/侧面机位。"
            }
        } else {
            "当前手机已加入房间，只显示自己的机位；需要修改时请在创建房间的主控手机上操作。"
        }
        renderRoleButtons(state, isHost, hasOnlineEndpoint)
        binding.deviceListText.text = deviceStatusText(state)
    }

    private fun renderRoleButtons(
        state: NearbyConnectionState,
        isHost: Boolean,
        hasOnlineEndpoint: Boolean,
    ) {
        val buttonVisibility = if (isHost) View.VISIBLE else View.GONE
        listOf(
            binding.assignSelfFrontButton,
            binding.assignSelfSideButton,
            binding.assignJoinedFrontButton,
            binding.assignJoinedSideButton,
        ).forEach { button -> button.visibility = buttonVisibility }
        renderRoleButtonState(
            button = binding.assignSelfFrontButton,
            selected = state.localRole == DeviceRole.FRONT_CAMERA,
            enabled = isHost,
        )
        renderRoleButtonState(
            button = binding.assignSelfSideButton,
            selected = state.localRole == DeviceRole.SIDE_CAMERA,
            enabled = isHost,
        )
        renderRoleButtonState(
            button = binding.assignJoinedFrontButton,
            selected = state.endpoints.any { it.isOnline && it.role == DeviceRole.FRONT_CAMERA },
            enabled = isHost && hasOnlineEndpoint,
        )
        renderRoleButtonState(
            button = binding.assignJoinedSideButton,
            selected = state.endpoints.any { it.isOnline && it.role == DeviceRole.SIDE_CAMERA },
            enabled = isHost && hasOnlineEndpoint,
        )
    }

    private fun renderRoleButtonState(
        button: MaterialButton,
        selected: Boolean,
        enabled: Boolean,
    ) {
        button.isEnabled = enabled
        button.isSelected = selected
        button.alpha = if (enabled) 1f else 0.55f
        button.backgroundTintList = ColorStateList.valueOf(
            getColor(if (selected) R.color.zzj_primary else R.color.zzj_primary_soft)
        )
        button.strokeColor = ColorStateList.valueOf(
            getColor(if (selected) R.color.zzj_primary else R.color.zzj_border)
        )
        button.setTextColor(getColor(if (selected) R.color.white else R.color.zzj_primary))
    }

    private fun deviceStatusText(state: NearbyConnectionState): String {
        val endpointText = if (state.endpoints.isEmpty()) {
            if (isHostController(state)) {
                "在线设备：暂无，请让节点手机加入后再分配机位。"
            } else {
                "在线设备：暂无，正在等待主控端同步机位。"
            }
        } else {
            state.endpoints.joinToString(separator = "\n\n") { endpoint -> endpoint.toDisplayText() }
        }
        return listOf(
            "多设备状态：${stableConnectionStateText(state)}",
            "状态说明：${stableConnectionDetailText(state)}",
            trainingStatusText(state),
            "房间码：${state.roomCode.ifBlank { "未创建/未加入" }}",
            "识别支持的动作类型：${supportedActionTypesText()}",
            "",
            endpointText,
        ).joinToString("\n")
    }

    private fun stableConnectionStateText(state: NearbyConnectionState): String =
        when (state.mode) {
            NearbyConnectionMode.IDLE -> "未启动"
            NearbyConnectionMode.HOST_ADVERTISING -> "主控连接已开启"
            NearbyConnectionMode.NODE_DISCOVERING -> "正在搜索训练房间"
            NearbyConnectionMode.CONNECTED -> "已连接"
            NearbyConnectionMode.ERROR -> "连接异常"
        }

    private fun stableConnectionDetailText(state: NearbyConnectionState): String {
        if (state.mode == NearbyConnectionMode.ERROR) {
            return state.statusText.ifBlank { "请返回后重新创建或加入训练房间。" }
        }
        return when {
            state.mode == NearbyConnectionMode.IDLE -> "请先创建或加入训练房间。"
            state.mode == NearbyConnectionMode.HOST_ADVERTISING && state.endpoints.isEmpty() -> "等待其它手机加入。"
            state.mode == NearbyConnectionMode.NODE_DISCOVERING && state.endpoints.isEmpty() -> "正在等待主控手机响应。"
            state.endpoints.isEmpty() -> "暂无在线节点。"
            isHostController(state) -> "在线设备 ${state.endpoints.count { it.isOnline }} 台，请在主控端分配正面/侧面机位。"
            else -> "已加入房间，当前机位由主控端统一配置。"
        }
    }

    private fun trainingStatusText(state: NearbyConnectionState): String {
        val lastMessage = state.lastMessage
        return when (lastMessage?.type) {
            NearbyMessageType.START_COUNTDOWN -> {
                "训练状态：主控端已发起倒计时，动作目标 ${trainingActionText(lastMessage.actionType)}。"
            }
            NearbyMessageType.START_ANALYSIS -> {
                "训练状态：主控端已开始训练，加入手机应进入节点识别页并开始采集。"
            }
            NearbyMessageType.PAUSE_ANALYSIS -> {
                "训练状态：主控端已暂停训练识别。"
            }
            NearbyMessageType.RESUME_ANALYSIS -> {
                "训练状态：主控端已继续训练识别。"
            }
            NearbyMessageType.END_TRAINING -> {
                "训练状态：主控端已结束本轮训练，加入手机正在保存或已回到待命。"
            }
            else -> {
                if (isHostController(state)) {
                    "训练状态：等待开始训练。"
                } else {
                    "训练状态：等待主控端开始训练。"
                }
            }
        }
    }

    private fun trainingActionText(actionType: ActionType): String =
        if (actionType == ActionType.UNKNOWN) "自动识别" else actionType.displayName

    private fun supportedActionTypesText(): String =
        "${ActionType.trainingActionNamesText()}，共 ${ActionType.trainingActions.size} 种运动"

    private fun handleNearbyMessage(message: NearbyMessage) {
        currentState = NearbyRoomSession.manager(this).currentState()
        when (message.type) {
            NearbyMessageType.START_COUNTDOWN -> {
                val seconds = message.countdownSeconds ?: 3
                startRemoteNodeCountdown(seconds, message.actionType)
            }
            NearbyMessageType.START_ANALYSIS -> {
                binding.deviceListText.text = "${deviceStatusText(currentState)}\n\n主控端已开始训练，正在进入节点识别页。"
                openCameraNodeFromRemote(message.actionType, autoStart = true)
            }
            NearbyMessageType.PAUSE_ANALYSIS -> {
                binding.deviceListText.text = "${deviceStatusText(currentState)}\n\n主控端已暂停训练。"
            }
            NearbyMessageType.RESUME_ANALYSIS -> {
                binding.deviceListText.text = "${deviceStatusText(currentState)}\n\n主控端已继续训练。"
            }
            NearbyMessageType.END_TRAINING -> showRemoteTrainingEnded(message)
            else -> Unit
        }
    }

    private fun startRemoteNodeCountdown(seconds: Int, actionType: ActionType) {
        remoteCountdownTimer?.cancel()
        openedRemoteTraining = false
        binding.enterTrainingButton.isEnabled = false
        remoteCountdownTimer = object : CountDownTimer(seconds * 1000L, 1000L) {
            override fun onTick(millisUntilFinished: Long) {
                val remainingSeconds = (millisUntilFinished / 1000L) + 1L
                binding.deviceListText.text = "${deviceStatusText(currentState)}\n\n主控端倒计时：$remainingSeconds 秒后进入训练。"
            }

            override fun onFinish() {
                openCameraNodeFromRemote(actionType, autoStart = true)
            }
        }.start()
    }

    private fun openCameraNodeFromRemote(actionType: ActionType, autoStart: Boolean = false) {
        if (openedRemoteTraining) return
        openedRemoteTraining = true
        remoteCountdownTimer?.cancel()
        remoteCountdownTimer = null
        binding.enterTrainingButton.isEnabled = true
        startActivity(
            Intent(this, CameraNodeActivity::class.java)
                .putExtra(ActionAnalysisActivity.EXTRA_ACTION_TYPE, actionType.name)
                .putExtra(CameraNodeActivity.EXTRA_REMOTE_AUTO_START, autoStart)
        )
    }

    private fun showRemoteTrainingEnded(message: NearbyMessage) {
        remoteCountdownTimer?.cancel()
        remoteCountdownTimer = null
        openedRemoteTraining = false
        binding.enterTrainingButton.isEnabled = true
        currentState = NearbyRoomSession.manager(this).currentState()
        renderDevices(currentState)
        binding.deviceListText.text = "${deviceStatusText(currentState)}\n\n主控端已结束本轮训练：${message.actionType.displayName}。"
    }

    private fun NearbyEndpoint.toDisplayText(): String =
        listOf(
            "设备：$deviceName",
            "机位：${role.displayText()}",
            "电量：${batteryLevel?.let { "$it%" } ?: "未知"}",
            "延迟：${networkDelayMs?.let { "$it ms" } ?: "待测"}",
            "在线：${if (isOnline) "是" else "否"}",
        ).joinToString("\n")

    private fun isHostController(state: NearbyConnectionState): Boolean =
        state.isHostSession || state.localRole == DeviceRole.HOST

    private fun DeviceRole.displayText(): String =
        when (this) {
            DeviceRole.HOST -> "主控端"
            DeviceRole.FRONT_CAMERA -> "正面机位"
            DeviceRole.SIDE_CAMERA -> "侧面机位"
            DeviceRole.BACKUP_CAMERA -> "备用机位"
            DeviceRole.UNKNOWN -> "未分配"
        }
}
