package com.example.zhizijing.ui.device

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResult
import androidx.core.app.ActivityCompat
import com.example.zhizijing.R
import com.example.zhizijing.data.datastore.AppSettingsDataStore
import com.example.zhizijing.databinding.BottomSheetCameraSetupBinding
import com.example.zhizijing.domain.model.DeviceRole
import com.example.zhizijing.nearby.connection.NearbyConnectionListener
import com.example.zhizijing.nearby.connection.NearbyConnectionMode
import com.example.zhizijing.nearby.connection.NearbyConnectionState
import com.example.zhizijing.nearby.connection.NearbyPermissions
import com.example.zhizijing.nearby.connection.NearbyRoomSession
import com.example.zhizijing.nearby.message.NearbyMessage
import com.example.zhizijing.ui.room.RoomCodeFormatter
import com.example.zhizijing.ui.room.RoomCodeParser
import com.example.zhizijing.ui.room.RoomQrCodeEncoder
import com.example.zhizijing.ui.room.RoomQrCodeRenderer
import com.example.zhizijing.ui.room.RoomQrScanResultParser
import com.example.zhizijing.ui.room.RoomQrScannerActivity
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class CameraSetupBottomSheet(
    private val activity: ComponentActivity,
    private val requestNearbyPermissions: (Array<String>) -> Unit,
    private val launchQrScanner: () -> Unit,
    private val onDismissed: () -> Unit,
) {
    private lateinit var binding: BottomSheetCameraSetupBinding
    private lateinit var dialog: BottomSheetDialog
    private var currentState = NearbyConnectionState()
    private var selectedMode = SetupMode.SINGLE
    private var hostRoomCode = ""
    private var pendingNearbyAction: NearbyAction? = null
    private var isRendering = false

    private val nearbyListener = object : NearbyConnectionListener {
        override fun onNearbyStateChanged(state: NearbyConnectionState) {
            currentState = state
            activity.runOnUiThread { render(state) }
        }

        override fun onNearbyMessageReceived(endpointId: String, message: NearbyMessage) = Unit
    }

    fun show() {
        binding = BottomSheetCameraSetupBinding.inflate(LayoutInflater.from(activity))
        dialog = BottomSheetDialog(activity)
        dialog.setContentView(binding.root)
        dialog.setOnShowListener {
            val bottomSheet = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            bottomSheet?.let { sheet ->
                sheet.layoutParams = sheet.layoutParams.apply {
                    height = (activity.resources.displayMetrics.heightPixels * 0.9f).toInt()
                }
                val behavior = BottomSheetBehavior.from(sheet)
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
                behavior.skipCollapsed = false
                behavior.isDraggable = true
            }
        }
        dialog.setOnDismissListener {
            NearbyRoomSession.manager(activity).removeListener(nearbyListener)
            onDismissed()
        }

        hostRoomCode = AppSettingsDataStore.load(activity).lastRoomCode
        binding.roomCodeInput.setText(hostRoomCode)
        currentState = NearbyRoomSession.manager(activity).currentState()
        selectedMode = when {
            currentState.mode == NearbyConnectionMode.IDLE -> SetupMode.SINGLE
            currentState.isHostSession || currentState.localRole == DeviceRole.HOST -> SetupMode.HOST
            else -> SetupMode.NODE
        }

        bindActions()
        NearbyRoomSession.manager(activity).addListener(nearbyListener)
        render(currentState)
        dialog.show()
    }

    fun onNearbyPermissionsResult() {
        val missingPermissions = NearbyPermissions.missingRuntimePermissions(activity)
        if (missingPermissions.isNotEmpty()) {
            renderStatus("缺少多设备连接所需权限。")
            showMissingPermissionDialog(missingPermissions)
            return
        }
        when (pendingNearbyAction) {
            NearbyAction.START_HOST -> startHost()
            NearbyAction.START_NODE -> startNode()
            null -> Unit
        }
        pendingNearbyAction = null
    }

    fun onQrScanResult(result: ActivityResult) {
        val roomCode = RoomQrScanResultParser.parseRoomCode(
            result.data?.getStringExtra(RoomQrScannerActivity.EXTRA_ROOM_CODE)
        )
        if (result.resultCode == Activity.RESULT_OK && roomCode != null) {
            selectedMode = SetupMode.NODE
            binding.setupModeGroup.check(R.id.nodeModeButton)
            binding.roomCodeInput.setText(roomCode)
            AppSettingsDataStore.saveLastRoomCode(activity, roomCode)
            Toast.makeText(activity, "已识别房间码：${RoomCodeFormatter.display(roomCode)}，正在加入。", Toast.LENGTH_SHORT).show()
            requestOrStartNode()
        }
    }

    private fun bindActions() {
        binding.setupModeGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked || isRendering) return@addOnButtonCheckedListener
            val mode = when (checkedId) {
                R.id.hostModeButton -> SetupMode.HOST
                R.id.nodeModeButton -> SetupMode.NODE
                else -> SetupMode.SINGLE
            }
            onSetupModeSelected(mode)
        }
        binding.singleFrontButton.setOnClickListener { setSingleRole(DeviceRole.FRONT_CAMERA) }
        binding.singleSideButton.setOnClickListener { setSingleRole(DeviceRole.SIDE_CAMERA) }
        binding.hostFrontButton.setOnClickListener { setHostRole(DeviceRole.FRONT_CAMERA) }
        binding.hostSideButton.setOnClickListener { setHostRole(DeviceRole.SIDE_CAMERA) }
        binding.scanQrButton.setOnClickListener {
            selectedMode = SetupMode.NODE
            launchQrScanner()
        }
        binding.startNodeButton.setOnClickListener { requestOrStartNode() }
        binding.refreshRoomCodeButton.setOnClickListener { refreshHostRoomCode() }
        binding.doneButton.setOnClickListener { dialog.dismiss() }
    }

    private fun onSetupModeSelected(mode: SetupMode) {
        selectedMode = mode
        when (mode) {
            SetupMode.SINGLE -> {
                val manager = NearbyRoomSession.manager(activity)
                if (currentState.mode != NearbyConnectionMode.IDLE) {
                    manager.stop()
                }
                currentState = manager.currentState()
                render(currentState)
            }
            SetupMode.HOST -> {
                if (isHostController(currentState)) {
                    render(currentState)
                } else {
                    requestOrStartHost()
                }
            }
            SetupMode.NODE -> {
                val manager = NearbyRoomSession.manager(activity)
                if (isHostController(currentState)) {
                    manager.stop()
                }
                currentState = manager.currentState()
                render(currentState)
            }
        }
    }

    private fun setSingleRole(role: DeviceRole) {
        val manager = NearbyRoomSession.manager(activity)
        if (currentState.mode != NearbyConnectionMode.IDLE) {
            manager.stop()
        }
        manager.assignLocalRole(role)
        currentState = manager.currentState()
        selectedMode = SetupMode.SINGLE
        render(currentState)
    }

    private fun requestOrStartHost() {
        selectedMode = SetupMode.HOST
        render(currentState)
        val missingPermissions = NearbyPermissions.missingRuntimePermissions(activity)
        if (missingPermissions.isEmpty()) {
            startHost()
        } else {
            pendingNearbyAction = NearbyAction.START_HOST
            requestNearbyPermissions(missingPermissions)
        }
    }

    private fun startHost() {
        val roomCode = ensureHostRoomCode()
        AppSettingsDataStore.saveLastRoomCode(activity, roomCode)
        if (currentState.mode != NearbyConnectionMode.IDLE && !isHostController(currentState)) {
            NearbyRoomSession.manager(activity).stop()
        }
        runCatching {
            NearbyRoomSession.manager(activity).startHost(roomCode)
        }.onSuccess {
            currentState = NearbyRoomSession.manager(activity).currentState()
            render(currentState)
        }.onFailure { error ->
            showOperationError("主机房间创建失败", nearbyErrorText(error))
        }
    }

    private fun setHostRole(localRole: DeviceRole) {
        if (!isHostController(currentState)) {
            Toast.makeText(activity, "请先切换为作为主机。", Toast.LENGTH_SHORT).show()
            return
        }
        val peerRole = localRole.oppositeCameraRole()
        val manager = NearbyRoomSession.manager(activity)
        manager.assignLocalRole(localRole)
        val peer = DeviceRoleAssignmentPolicy.selectEndpointForRole(manager.currentState().endpoints, peerRole)
        if (peer != null) {
            manager.assignRole(peer.endpointId, peerRole)
        }
        currentState = manager.currentState()
        render(currentState)
    }

    private fun requestOrStartNode() {
        selectedMode = SetupMode.NODE
        render(currentState)
        val missingPermissions = NearbyPermissions.missingRuntimePermissions(activity)
        if (missingPermissions.isEmpty()) {
            startNode()
        } else {
            pendingNearbyAction = NearbyAction.START_NODE
            requestNearbyPermissions(missingPermissions)
        }
    }

    private fun startNode() {
        val roomCode = RoomCodeParser.parseExactSixDigits(binding.roomCodeInput.text?.toString())
        if (roomCode == null) {
            Toast.makeText(activity, "请输入主控端 6 位房间码", Toast.LENGTH_SHORT).show()
            return
        }
        AppSettingsDataStore.saveLastRoomCode(activity, roomCode)
        if (isHostController(currentState)) {
            NearbyRoomSession.manager(activity).stop()
        }
        runCatching {
            NearbyRoomSession.manager(activity).startNode(roomCode)
        }.onSuccess {
            currentState = NearbyRoomSession.manager(activity).currentState()
            render(currentState)
        }.onFailure { error ->
            showOperationError("加入主机失败", nearbyErrorText(error))
        }
    }

    private fun ensureHostRoomCode(forceRefresh: Boolean = false): String {
        val currentRoomCode = if (forceRefresh) "" else currentState.roomCode.ifBlank { hostRoomCode }
        hostRoomCode = RoomCodeParser.parseExactSixDigits(currentRoomCode) ?: generateRoomCode()
        return hostRoomCode
    }

    private fun refreshHostRoomCode() {
        if (!isHostController(currentState)) return
        val manager = NearbyRoomSession.manager(activity)
        val previousRoomCode = currentState.roomCode.ifBlank { hostRoomCode }
        manager.stop()
        currentState = manager.currentState() // 变为空闲状态
        val newRoomCode = generateRoomCode(excluding = previousRoomCode)
        hostRoomCode = newRoomCode
        AppSettingsDataStore.saveLastRoomCode(activity, newRoomCode)
        runCatching {
            manager.startHost(newRoomCode)
        }.onSuccess {
            currentState = manager.currentState()
            render(currentState)
        }.onFailure { error ->
            showOperationError("刷新房间码失败", nearbyErrorText(error))
        }
    }

    private fun nearbyErrorText(error: Throwable): String {
        val message = error.message.orEmpty()
        return when {
            message.contains("MISSING_PERMISSION_ACCESS_FINE_LOCATION", ignoreCase = true) ->
                "缺少精确位置信息权限，请允许后重试。"
            message.contains("MISSING_PERMISSION", ignoreCase = true) ->
                "缺少多设备连接权限，请允许蓝牙、附近设备和位置信息后重试。"
            message.isBlank() -> "未知错误，请重试。"
            else -> message
        }
    }

    private fun generateRoomCode(excluding: String = ""): String {
        val excluded = RoomCodeParser.parseExactSixDigits(excluding)
        var generated: String
        do {
            generated = ((100000..999999).random()).toString()
        } while (generated == excluded)
        return generated
    }

    private fun render(state: NearbyConnectionState) {
        currentState = state
        isRendering = true
        try {
            binding.setupModeGroup.check(
                when (selectedMode) {
                    SetupMode.SINGLE -> R.id.singleModeButton
                    SetupMode.HOST -> R.id.hostModeButton
                    SetupMode.NODE -> R.id.nodeModeButton
                }
            )
            binding.singleSection.visibility = if (selectedMode == SetupMode.SINGLE) View.VISIBLE else View.GONE
            binding.hostSection.visibility = if (selectedMode == SetupMode.HOST) View.VISIBLE else View.GONE
            binding.nodeSection.visibility = if (selectedMode == SetupMode.NODE) View.VISIBLE else View.GONE
            renderSingle(state)
            renderHost(state)
            renderNode(state)
            binding.setupStatusText.text = statusText(state)
        } finally {
            isRendering = false
        }
    }

    private fun renderSingle(state: NearbyConnectionState) {
        renderButtonState(binding.singleFrontButton, state.localRole == DeviceRole.FRONT_CAMERA, true)
        renderButtonState(binding.singleSideButton, state.localRole == DeviceRole.SIDE_CAMERA, true)
    }

    private fun renderHost(state: NearbyConnectionState) {
        val roomCode = RoomCodeParser.parseExactSixDigits(state.roomCode.ifBlank { hostRoomCode })
        if (roomCode == null) {
            binding.roomCodeText.text = "暂无"
            binding.roomQrImage.setImageDrawable(null)
            binding.hostConnectionText.text = "选择“作为主机”后会自动创建房间并生成二维码。"
        } else {
            binding.roomCodeText.text = RoomCodeFormatter.display(roomCode)
            renderRoomQrCode(roomCode)
            val onlineCount = state.endpoints.count { it.isOnline }
            binding.hostConnectionText.text = if (onlineCount > 0) {
                "$onlineCount 台副机在线，可分配正面/侧面视角。"
            } else {
                "请用另一部手机扫描二维码连接。"
            }
        }
        renderButtonState(binding.hostFrontButton, state.localRole == DeviceRole.FRONT_CAMERA, isHostController(state))
        renderButtonState(binding.hostSideButton, state.localRole == DeviceRole.SIDE_CAMERA, isHostController(state))
        binding.hostRoleSummaryText.text = hostRoleSummary(state)
    }

    private fun renderNode(state: NearbyConnectionState) {
        val isConnectedNode = state.mode == NearbyConnectionMode.CONNECTED && !isHostController(state)
        binding.scanQrButton.text = if (isConnectedNode) "重新扫码加入其他主机" else "扫码并加入主机"
        binding.startNodeButton.text = if (isConnectedNode) "重新手动加入" else "手动加入主机"
        binding.nodeStatusText.text = when {
            state.mode == NearbyConnectionMode.NODE_DISCOVERING -> {
                "正在搜索主机房间 ${RoomCodeFormatter.display(state.roomCode)}。\n请保持主机二维码页面打开。"
            }
            isConnectedNode -> {
                val roleText = if (state.localRole == DeviceRole.UNKNOWN) {
                    "等待主机分配正面或侧面视角"
                } else {
                    "本机视角：${state.localRole.displayText()}"
                }
                "已加入房间 ${RoomCodeFormatter.display(state.roomCode)}。\n$roleText"
            }
            state.mode == NearbyConnectionMode.ERROR -> {
                state.statusText.ifBlank { "连接异常，请重新扫码或手动输入房间码。" }
            }
            else -> "尚未加入房间。扫码后会自动加入；也可以手动输入房间码。"
        }
    }

    private fun renderRoomQrCode(roomCode: String) {
        runCatching {
            RoomQrCodeRenderer.render(RoomQrCodeEncoder.encodeRoomCode(roomCode), moduleSize = 8)
        }.onSuccess { bitmap ->
            binding.roomQrImage.setImageBitmap(bitmap)
        }.onFailure { error ->
            showOperationError("二维码生成失败", error.message ?: "请刷新房间码后重试。")
        }
    }

    private fun renderStatus(message: String) {
        binding.setupStatusText.text = message
        when (selectedMode) {
            SetupMode.HOST -> binding.hostConnectionText.text = message
            SetupMode.NODE -> binding.nodeStatusText.text = message
            SetupMode.SINGLE -> binding.helperText.text = message
        }
    }

    private fun showOperationError(title: String, message: String) {
        renderStatus("$title：$message")
        showBlockingError(title, message)
    }

    private fun showMissingPermissionDialog(missingPermissions: Array<String>) {
        val message = "多机位连接需要蓝牙、附近设备和精确位置信息权限。请授权后再创建或加入房间。"
        val canAskAgain = missingPermissions.any { permission ->
            ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)
        }
        if (canAskAgain) {
            showBlockingError(
                title = "需要多设备连接权限",
                message = message,
                positiveText = "重新授权",
            ) {
                requestNearbyPermissions(missingPermissions)
            }
        } else {
            pendingNearbyAction = null
            showBlockingError(
                title = "需要多设备连接权限",
                message = "$message\n\n如果系统没有弹出授权窗口，请在应用权限中打开相关权限后重试。",
                positiveText = "打开设置",
            ) {
                openAppPermissionSettings()
            }
        }
    }

    private fun showBlockingError(
        title: String,
        message: String,
        positiveText: String = "知道了",
        onPositive: (() -> Unit)? = null,
    ) {
        if (!::dialog.isInitialized || !dialog.isShowing) {
            Toast.makeText(activity, "$title：$message", Toast.LENGTH_LONG).show()
            return
        }
        MaterialAlertDialogBuilder(activity)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(positiveText) { _, _ -> onPositive?.invoke() }
            .show()
    }

    private fun openAppPermissionSettings() {
        activity.startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", activity.packageName, null),
            )
        )
    }

    private fun statusText(state: NearbyConnectionState): String =
        when (selectedMode) {
            SetupMode.SINGLE -> "当前手机：单机采集 · ${state.localRole.displayText()}"
            SetupMode.HOST -> {
                val room = RoomCodeFormatter.display(state.roomCode).ifBlank { "未创建" }
                "当前手机：主机 · 房间 $room"
            }
            SetupMode.NODE -> "当前手机：副机 · ${state.localRole.displayText()}"
        }

    private fun hostRoleSummary(state: NearbyConnectionState): String {
        val peer = state.endpoints.firstOrNull { it.isOnline }
        val peerText = when {
            peer == null -> "副机：未连接"
            peer.role == DeviceRole.UNKNOWN -> "副机：已连接，等待自动分配"
            else -> "副机：${peer.deviceName} · ${peer.role.displayText()}"
        }
        return listOf(
            "本机：${state.localRole.displayText()}",
            peerText,
        ).joinToString("\n")
    }

    private fun renderButtonState(button: MaterialButton, selected: Boolean, enabled: Boolean) {
        button.isEnabled = enabled
        button.alpha = if (enabled) 1f else 0.55f
        button.backgroundTintList = ColorStateList.valueOf(
            activity.getColor(if (selected) R.color.zzj_primary else R.color.zzj_primary_soft)
        )
        button.strokeColor = ColorStateList.valueOf(
            activity.getColor(if (selected) R.color.zzj_primary else R.color.zzj_border)
        )
        button.setTextColor(activity.getColor(if (selected) R.color.white else R.color.zzj_primary))
    }

    private fun isHostController(state: NearbyConnectionState): Boolean =
        state.isHostSession || state.localRole == DeviceRole.HOST

    private fun DeviceRole.oppositeCameraRole(): DeviceRole =
        if (this == DeviceRole.FRONT_CAMERA) DeviceRole.SIDE_CAMERA else DeviceRole.FRONT_CAMERA

    private fun DeviceRole.displayText(): String =
        when (this) {
            DeviceRole.HOST -> "主控"
            DeviceRole.FRONT_CAMERA -> "正面视角"
            DeviceRole.SIDE_CAMERA -> "侧面视角"
            DeviceRole.BACKUP_CAMERA -> "备用视角"
            DeviceRole.UNKNOWN -> "未分配"
        }

    private enum class SetupMode {
        SINGLE,
        HOST,
        NODE,
    }

    private enum class NearbyAction {
        START_HOST,
        START_NODE,
    }
}
