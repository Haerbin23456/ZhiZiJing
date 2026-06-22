package com.example.zhizijing.ui.device

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.os.CountDownTimer
import android.view.View
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.example.zhizijing.R
import com.example.zhizijing.data.datastore.AppSettingsDataStore
import com.example.zhizijing.databinding.ActivityDeviceGroupBinding
import com.example.zhizijing.domain.model.ActionType
import com.example.zhizijing.domain.model.DeviceRole
import com.example.zhizijing.domain.model.TrainingState
import com.example.zhizijing.nearby.connection.NearbyConnectionListener
import com.example.zhizijing.nearby.connection.NearbyConnectionMode
import com.example.zhizijing.nearby.connection.NearbyConnectionState
import com.example.zhizijing.nearby.connection.NearbyEndpoint
import com.example.zhizijing.nearby.connection.NearbyPermissions
import com.example.zhizijing.nearby.connection.NearbyRoomSession
import com.example.zhizijing.nearby.message.NearbyMessage
import com.example.zhizijing.nearby.message.NearbyMessageType
import com.example.zhizijing.ui.analysis.ActionAnalysisActivity
import com.example.zhizijing.ui.camera.CameraNodeActivity
import com.example.zhizijing.ui.room.RoomCodeFormatter
import com.example.zhizijing.ui.room.RoomCodeParser
import com.example.zhizijing.ui.room.RoomQrCodeEncoder
import com.example.zhizijing.ui.room.RoomQrCodeRenderer
import com.example.zhizijing.ui.room.RoomQrScanResultParser
import com.example.zhizijing.ui.room.RoomQrScannerActivity
import com.google.android.material.button.MaterialButton

class DeviceGroupActivity : ComponentActivity() {
    private lateinit var binding: ActivityDeviceGroupBinding
    private var currentState = NearbyConnectionState()
    private var selectedSetupMode = SetupMode.SINGLE
    private var hostRoomCode = ""
    private var pendingNearbyAction: NearbyAction? = null
    private var remoteCountdownTimer: CountDownTimer? = null
    private var openedRemoteTraining = false

    private val qrScannerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result: ActivityResult ->
        val roomCode = RoomQrScanResultParser.parseRoomCode(
            result.data?.getStringExtra(RoomQrScannerActivity.EXTRA_ROOM_CODE)
        )
        if (result.resultCode == RESULT_OK && roomCode != null) {
            selectedSetupMode = SetupMode.MULTI
            binding.roomCodeInput.setText(roomCode)
            AppSettingsDataStore.saveLastRoomCode(this, roomCode)
            Toast.makeText(this, "已填入房间码：${RoomCodeFormatter.display(roomCode)}", Toast.LENGTH_SHORT).show()
            renderDevices(NearbyRoomSession.manager(this).currentState())
        }
    }

    private val nearbyPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        if (!NearbyPermissions.hasRuntimePermissions(this)) {
            binding.deviceListText.text = "缺少多设备连接所需权限，无法创建或加入训练房间。"
            return@registerForActivityResult
        }
        when (pendingNearbyAction) {
            NearbyAction.START_HOST -> startAdvertising()
            NearbyAction.START_NODE -> startDiscovery()
            null -> Unit
        }
        pendingNearbyAction = null
    }

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

        hostRoomCode = AppSettingsDataStore.load(this).lastRoomCode
        binding.roomCodeInput.setText(hostRoomCode)
        currentState = NearbyRoomSession.manager(this).currentState()
        selectedSetupMode = if (currentState.mode == NearbyConnectionMode.IDLE) SetupMode.SINGLE else SetupMode.MULTI

        bindActions()
        renderDevices(currentState)
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

    private fun bindActions() {
        binding.singleModeButton.setOnClickListener { switchToSingleMode() }
        binding.multiModeButton.setOnClickListener {
            selectedSetupMode = SetupMode.MULTI
            renderDevices(NearbyRoomSession.manager(this).currentState())
        }
        binding.singleFrontButton.setOnClickListener { assignSingleRole(DeviceRole.FRONT_CAMERA) }
        binding.singleSideButton.setOnClickListener { assignSingleRole(DeviceRole.SIDE_CAMERA) }
        binding.createRoomButton.setOnClickListener { requestOrStartAdvertising() }
        binding.startDiscoveryButton.setOnClickListener { requestOrStartDiscovery() }
        binding.scanQrButton.setOnClickListener {
            selectedSetupMode = SetupMode.MULTI
            qrScannerLauncher.launch(Intent(this, RoomQrScannerActivity::class.java))
        }
        binding.assignSelfFrontButton.setOnClickListener { assignSelfRole(DeviceRole.FRONT_CAMERA) }
        binding.assignSelfSideButton.setOnClickListener { assignSelfRole(DeviceRole.SIDE_CAMERA) }
        binding.assignJoinedFrontButton.setOnClickListener { assignJoinedRole(DeviceRole.FRONT_CAMERA) }
        binding.assignJoinedSideButton.setOnClickListener { assignJoinedRole(DeviceRole.SIDE_CAMERA) }
        binding.enterTrainingButton.setOnClickListener { finish() }
        binding.backButton.setOnClickListener { finish() }
    }

    private fun switchToSingleMode() {
        selectedSetupMode = SetupMode.SINGLE
        val manager = NearbyRoomSession.manager(this)
        if (currentState.mode != NearbyConnectionMode.IDLE) {
            manager.stop()
        }
        currentState = manager.currentState()
        renderDevices(currentState)
    }

    private fun assignSingleRole(role: DeviceRole) {
        selectedSetupMode = SetupMode.SINGLE
        val manager = NearbyRoomSession.manager(this)
        if (currentState.mode != NearbyConnectionMode.IDLE) {
            manager.stop()
        }
        manager.assignLocalRole(role)
        currentState = manager.currentState()
        renderDevices(currentState)
        Toast.makeText(this, "已设置本机为${role.displayText()}。", Toast.LENGTH_SHORT).show()
    }

    private fun requestOrStartAdvertising() {
        selectedSetupMode = SetupMode.MULTI
        val missingPermissions = NearbyPermissions.missingRuntimePermissions(this)
        if (missingPermissions.isEmpty()) {
            startAdvertising()
        } else {
            pendingNearbyAction = NearbyAction.START_HOST
            nearbyPermissionLauncher.launch(missingPermissions)
        }
    }

    private fun startAdvertising() {
        val roomCode = ensureHostRoomCode()
        AppSettingsDataStore.saveLastRoomCode(this, roomCode)
        runCatching {
            NearbyRoomSession.manager(this).startHost(roomCode)
        }.onSuccess {
            currentState = NearbyRoomSession.manager(this).currentState()
            renderDevices(currentState)
        }.onFailure { error ->
            Toast.makeText(this, "多设备连接启动失败：${error.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun requestOrStartDiscovery() {
        selectedSetupMode = SetupMode.MULTI
        val missingPermissions = NearbyPermissions.missingRuntimePermissions(this)
        if (missingPermissions.isEmpty()) {
            startDiscovery()
        } else {
            pendingNearbyAction = NearbyAction.START_NODE
            nearbyPermissionLauncher.launch(missingPermissions)
        }
    }

    private fun startDiscovery() {
        val roomCode = RoomCodeParser.parseExactSixDigits(binding.roomCodeInput.text?.toString())
        if (roomCode == null) {
            Toast.makeText(this, "请输入主控端 6 位房间码", Toast.LENGTH_SHORT).show()
            return
        }
        AppSettingsDataStore.saveLastRoomCode(this, roomCode)
        runCatching {
            NearbyRoomSession.manager(this).startNode(roomCode)
        }.onSuccess {
            currentState = NearbyRoomSession.manager(this).currentState()
            renderDevices(currentState)
        }.onFailure { error ->
            Toast.makeText(this, "训练房间搜索失败：${error.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun ensureHostRoomCode(): String {
        val currentRoomCode = currentState.roomCode.ifBlank { hostRoomCode }
        val validRoomCode = RoomCodeParser.parseExactSixDigits(currentRoomCode)
        hostRoomCode = validRoomCode ?: ((100000..999999).random()).toString()
        binding.roomCodeInput.setText(hostRoomCode)
        return hostRoomCode
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
        val endpoint = DeviceRoleAssignmentPolicy.selectEndpointForRole(currentState.endpoints, role)
        if (endpoint == null) {
            Toast.makeText(this, "暂无在线副机，无法设置${role.displayText()}。", Toast.LENGTH_SHORT).show()
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
        if (state.mode != NearbyConnectionMode.IDLE) {
            selectedSetupMode = SetupMode.MULTI
        }
        val isMultiMode = selectedSetupMode == SetupMode.MULTI
        val isHost = isHostController(state)
        val hasOnlineEndpoint = state.endpoints.any { it.isOnline }
        renderModeSelection(isMultiMode)
        binding.singleModeSection.visibility = if (isMultiMode) View.GONE else View.VISIBLE
        binding.multiModeSection.visibility = if (isMultiMode) View.VISIBLE else View.GONE
        binding.localRoleText.text = if (isMultiMode && !isHost) {
            "本机当前机位：${state.localRole.displayText()}。机位由主控端配置。"
        } else {
            "本机当前机位：${state.localRole.displayText()}。"
        }
        binding.modeStatusText.text = when {
            !isMultiMode -> "单机采集 · ${state.localRole.displayText()}"
            state.mode == NearbyConnectionMode.IDLE -> "多机位尚未连接，可创建主控房间或加入已有房间。"
            isHost -> "多机位主控 · 房间 ${RoomCodeFormatter.display(state.roomCode)} · ${state.endpoints.count { it.isOnline }} 台副机在线"
            else -> "多机位副机 · 房间 ${RoomCodeFormatter.display(state.roomCode)} · ${state.localRole.displayText()}"
        }
        renderSingleRoleButtons(state)
        renderRoomCode(state)
        renderRoleSlots(state, isHost, hasOnlineEndpoint)
        binding.deviceListText.text = deviceStatusText(state)
    }

    private fun renderModeSelection(isMultiMode: Boolean) {
        renderRoleButtonState(binding.singleModeButton, selected = !isMultiMode, enabled = true)
        renderRoleButtonState(binding.multiModeButton, selected = isMultiMode, enabled = true)
    }

    private fun renderSingleRoleButtons(state: NearbyConnectionState) {
        renderRoleButtonState(
            button = binding.singleFrontButton,
            selected = state.localRole == DeviceRole.FRONT_CAMERA,
            enabled = true,
        )
        renderRoleButtonState(
            button = binding.singleSideButton,
            selected = state.localRole == DeviceRole.SIDE_CAMERA,
            enabled = true,
        )
    }

    private fun renderRoomCode(state: NearbyConnectionState) {
        val displayedRoomCode = state.roomCode.ifBlank { hostRoomCode }
        val normalizedRoomCode = RoomCodeParser.parseExactSixDigits(displayedRoomCode)
        if (normalizedRoomCode == null) {
            binding.roomCodeText.text = "房间码未创建"
            binding.roomQrImage.setImageDrawable(null)
            binding.roomQrHintText.text = "创建主控房间后会生成二维码。"
            return
        }
        binding.roomCodeText.text = "房间码 ${RoomCodeFormatter.display(normalizedRoomCode)}"
        renderRoomQrCode(normalizedRoomCode)
    }

    private fun renderRoomQrCode(roomCode: String) {
        runCatching {
            RoomQrCodeRenderer.render(RoomQrCodeEncoder.encodeRoomCode(roomCode), moduleSize = 8)
        }.onSuccess { bitmap ->
            binding.roomQrImage.setImageBitmap(bitmap)
            binding.roomQrHintText.text = "副机可扫描二维码加入。"
        }.onFailure { error ->
            binding.roomQrHintText.text = "二维码生成失败：${error.message}"
        }
    }

    private fun renderRoleSlots(
        state: NearbyConnectionState,
        isHost: Boolean,
        hasOnlineEndpoint: Boolean,
    ) {
        val frontEndpoint = state.endpoints.firstOrNull { it.isOnline && it.role == DeviceRole.FRONT_CAMERA }
        val sideEndpoint = state.endpoints.firstOrNull { it.isOnline && it.role == DeviceRole.SIDE_CAMERA }
        binding.frontSlotText.text = "正面机位：${slotOwnerText(state, DeviceRole.FRONT_CAMERA, frontEndpoint)}"
        binding.sideSlotText.text = "侧面机位：${slotOwnerText(state, DeviceRole.SIDE_CAMERA, sideEndpoint)}"
        binding.roleControlHintText.text = when {
            isHost && hasOnlineEndpoint -> "选择每个槽位由本机或副机采集，同一机位同一时间只保留一台设备。"
            isHost -> "可先设置本机机位；副机加入后再完成双机位分配。"
            else -> "当前手机已加入房间，只显示自己的机位；需要修改时请在主控手机操作。"
        }
        binding.frontSlotActions.visibility = if (isHost) View.VISIBLE else View.GONE
        binding.sideSlotActions.visibility = if (isHost) View.VISIBLE else View.GONE
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
            selected = frontEndpoint != null,
            enabled = isHost && hasOnlineEndpoint,
        )
        renderRoleButtonState(
            button = binding.assignJoinedSideButton,
            selected = sideEndpoint != null,
            enabled = isHost && hasOnlineEndpoint,
        )
    }

    private fun slotOwnerText(
        state: NearbyConnectionState,
        role: DeviceRole,
        endpoint: NearbyEndpoint?,
    ): String =
        when {
            state.localRole == role -> "本机"
            endpoint != null -> endpoint.deviceName
            else -> "未分配"
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
                "在线设备：暂无，请让副机加入后再分配机位。"
            } else {
                "在线设备：暂无，正在等待主控端同步机位。"
            }
        } else {
            state.endpoints.joinToString(separator = "\n\n") { endpoint -> endpoint.toDisplayText() }
        }
        return listOf(
            "连接状态：${stableConnectionStateText(state)}",
            "状态说明：${stableConnectionDetailText(state)}",
            trainingStatusText(state),
            "房间码：${RoomCodeFormatter.display(state.roomCode).ifBlank { "未创建/未加入" }}",
            "识别支持：${supportedActionTypesText()}",
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
            return state.statusText.ifBlank { "请重新创建或加入训练房间。" }
        }
        return when {
            state.mode == NearbyConnectionMode.IDLE -> "当前为单机或未连接状态。"
            state.mode == NearbyConnectionMode.HOST_ADVERTISING && state.endpoints.isEmpty() -> "等待副机加入。"
            state.mode == NearbyConnectionMode.NODE_DISCOVERING && state.endpoints.isEmpty() -> "正在等待主控手机响应。"
            state.endpoints.isEmpty() -> "暂无在线副机。"
            isHostController(state) -> "在线设备 ${state.endpoints.count { it.isOnline }} 台，请分配正面/侧面机位。"
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
                "训练状态：主控端已开始训练，副机应进入节点识别页并开始采集。"
            }
            NearbyMessageType.PAUSE_ANALYSIS -> {
                "训练状态：主控端已暂停训练识别。"
            }
            NearbyMessageType.RESUME_ANALYSIS -> {
                "训练状态：主控端已继续训练识别。"
            }
            NearbyMessageType.END_TRAINING -> {
                "训练状态：主控端已结束本轮训练，副机正在保存或已回到待命。"
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
            "采集：${trainingState.displayText()}",
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

    private fun TrainingState.displayText(): String =
        when (this) {
            TrainingState.IDLE -> "待进入采集页"
            TrainingState.PREPARING -> "采集就绪"
            TrainingState.ANALYZING -> "训练中"
            TrainingState.FINISHED -> "已结束"
            TrainingState.ERROR -> "异常"
        }

    private enum class SetupMode {
        SINGLE,
        MULTI,
    }

    private enum class NearbyAction {
        START_HOST,
        START_NODE,
    }
}
