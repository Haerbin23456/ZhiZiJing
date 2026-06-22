package com.example.zhizijing.nearby.connection

import android.content.Context
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.example.zhizijing.domain.model.ActionType
import com.example.zhizijing.domain.model.DeviceRole
import com.example.zhizijing.domain.model.ProblemType
import com.example.zhizijing.domain.model.TrainingState
import com.example.zhizijing.nearby.message.GsonNearbyMessageCodec
import com.example.zhizijing.nearby.message.NearbyMessage
import com.example.zhizijing.nearby.message.NearbyMessageCodec
import com.example.zhizijing.nearby.message.NearbyMessageType
import com.example.zhizijing.nearby.message.NearbyPoseFrameCodec
import com.example.zhizijing.pose.model.PoseFrame
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CopyOnWriteArraySet

enum class NearbyConnectionMode {
    IDLE,
    HOST_ADVERTISING,
    NODE_DISCOVERING,
    CONNECTED,
    ERROR,
}

data class NearbyEndpoint(
    val endpointId: String,
    val deviceName: String,
    val role: DeviceRole = DeviceRole.UNKNOWN,
    val trainingState: TrainingState = TrainingState.IDLE,
    val batteryLevel: Int? = null,
    val networkDelayMs: Int? = null,
    val isOnline: Boolean = false,
    val lastHeartbeatAt: Long = 0L,
) {
    val isCaptureReady: Boolean
        get() = trainingState == TrainingState.PREPARING || trainingState == TrainingState.ANALYZING
}

object RemoteTrainingStartGate {
    private val cameraRoles = setOf(
        DeviceRole.FRONT_CAMERA,
        DeviceRole.SIDE_CAMERA,
        DeviceRole.BACKUP_CAMERA,
    )

    fun blockReason(state: NearbyConnectionState): String? {
        if (!state.isHostSession) return null
        val onlineEndpoints = state.endpoints.filter { endpoint -> endpoint.isOnline }
        if (onlineEndpoints.isEmpty()) return null

        val assignedCameraEndpoints = onlineEndpoints.filter { endpoint -> endpoint.role in cameraRoles }
        if (assignedCameraEndpoints.isEmpty()) {
            return "请先给在线副机分配正面或侧面机位，再开始多机位训练。"
        }

        val notReady = assignedCameraEndpoints.filterNot { endpoint -> endpoint.isCaptureReady }
        if (notReady.isEmpty()) return null

        val deviceNames = notReady.joinToString("、") { endpoint -> endpoint.deviceName }
        return "请先让 $deviceNames 进入摄像头采集页，看到预览后再开始训练。"
    }
}

data class NearbyConnectionState(
    val mode: NearbyConnectionMode = NearbyConnectionMode.IDLE,
    val roomCode: String = "",
    val localName: String = "",
    val localRole: DeviceRole = DeviceRole.UNKNOWN,
    val isHostSession: Boolean = false,
    val endpoints: List<NearbyEndpoint> = emptyList(),
    val statusText: String = "多设备连接尚未启动。",
    val lastMessage: NearbyMessage? = null,
    val sentMessageCount: Int = 0,
    val receivedMessageCount: Int = 0,
    val lastPayloadAtMs: Long = 0L,
    val lastPayloadDirection: String = "暂无",
)

interface NearbyConnectionListener {
    fun onNearbyStateChanged(state: NearbyConnectionState)
    fun onNearbyMessageReceived(endpointId: String, message: NearbyMessage) = Unit
}

class NearbyConnectionManager(
    context: Context,
    private val codec: NearbyMessageCodec<NearbyMessage> = GsonNearbyMessageCodec(),
) {
    private val appContext = context.applicationContext
    private val client: ConnectionsClient = Nearby.getConnectionsClient(appContext)
    private val listeners = CopyOnWriteArraySet<NearbyConnectionListener>()
    private val endpoints = linkedMapOf<String, NearbyEndpoint>()
    private var state = NearbyConnectionState()
    private val localDeviceId = UUID.randomUUID().toString()
    private var isHostSession = false
    private var sentMessageCount = 0
    private var receivedMessageCount = 0
    private var lastPayloadAtMs = 0L
    private var lastPayloadDirection = "暂无"
    private var activeTrainingSessionId = ""
    private var activeSessionStartedAtMs = 0L
    private var outgoingMessageSeq = 0L
    private val pendingLatencyPings = mutableMapOf<String, PendingLatencyPing>()
    // 主线程心跳维护连接状态
    private val heartbeatHandler = Handler(Looper.getMainLooper())
    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            sendHeartbeat()
            sendLatencyPings()
            markStaleEndpointsOffline()
            heartbeatHandler.postDelayed(this, HEARTBEAT_INTERVAL_MS)
        }
    }

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, connectionInfo: ConnectionInfo) {
            endpoints[endpointId] = NearbyEndpoint(
                endpointId = endpointId,
                deviceName = cleanEndpointName(connectionInfo.endpointName),
                isOnline = false,
                lastHeartbeatAt = System.currentTimeMillis(),
            )
            updateState(statusText = "收到设备连接请求：${cleanEndpointName(connectionInfo.endpointName)}，正在接受连接...")
            client.acceptConnection(endpointId, payloadCallback)
                .addOnFailureListener { error ->
                    updateState(
                        mode = NearbyConnectionMode.ERROR,
                        statusText = "接受设备连接失败：${error.message}",
                    )
                }
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (result.status.isSuccess) {
                val endpoint = endpoints[endpointId] ?: NearbyEndpoint(
                    endpointId = endpointId,
                    deviceName = "未知设备",
                )
                endpoints[endpointId] = endpoint.copy(
                    isOnline = true,
                    lastHeartbeatAt = System.currentTimeMillis(),
                )
                updateState(
                    mode = NearbyConnectionMode.CONNECTED,
                    statusText = "已连接设备：${endpoint.deviceName}",
                )
                startHeartbeat()
                if (isHostSession) {
                    send(
                        endpointId,
                        NearbyMessage(
                            type = NearbyMessageType.JOIN_ACCEPTED,
                            roomCode = state.roomCode,
                            deviceId = localDeviceId,
                            deviceName = state.localName,
                            role = DeviceRole.HOST,
                            message = "已加入智姿镜训练房间。",
                        ),
                    )
                } else {
                    sendJoinRequest(endpointId)
                    sendDeviceStatus(endpointId)
                    client.stopDiscovery()
                }
            } else {
                endpoints.remove(endpointId)
                updateState(
                    mode = NearbyConnectionMode.ERROR,
                    statusText = "设备连接失败：${result.status.statusMessage ?: result.status.statusCode}",
                )
            }
        }

        override fun onDisconnected(endpointId: String) {
            endpoints[endpointId]?.let { endpoint ->
                endpoints[endpointId] = endpoint.copy(isOnline = false)
                updateState(statusText = "设备已断开：${endpoint.deviceName}")
            } ?: updateState(statusText = "设备已断开。")
        }
    }

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            if (!matchesRoom(info.endpointName, state.roomCode)) return
            endpoints[endpointId] = NearbyEndpoint(
                endpointId = endpointId,
                deviceName = cleanEndpointName(info.endpointName),
                isOnline = false,
                lastHeartbeatAt = System.currentTimeMillis(),
            )
            updateState(statusText = "发现房间设备：${cleanEndpointName(info.endpointName)}，正在请求连接...")
            client.requestConnection(state.localName, endpointId, connectionLifecycleCallback)
                .addOnFailureListener { error ->
                    updateState(
                        mode = NearbyConnectionMode.ERROR,
                        statusText = "请求连接失败：${error.message}",
                    )
                }
        }

        override fun onEndpointLost(endpointId: String) {
            endpoints.remove(endpointId)
            updateState(statusText = "发现的设备已离线。")
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            val receivedAtMs = System.currentTimeMillis()
            val text = payload.asBytes()?.toString(Charsets.UTF_8) ?: return
            val message = runCatching { codec.decode(text) }.getOrElse { error ->
                NearbyMessage(
                    type = NearbyMessageType.ERROR,
                    roomCode = state.roomCode,
                    deviceId = localDeviceId,
                    deviceName = state.localName,
                    message = "多设备消息解析失败：${error.message}",
                )
            }
            receivedMessageCount += 1
            lastPayloadAtMs = receivedAtMs
            lastPayloadDirection = "接收消息"
            if (handleMessage(endpointId, message, receivedAtMs)) {
                listeners.forEach { listener ->
                    listener.onNearbyMessageReceived(endpointId, message)
                }
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) = Unit
    }

    fun addListener(listener: NearbyConnectionListener) {
        listeners += listener
        listener.onNearbyStateChanged(currentState())
    }

    fun removeListener(listener: NearbyConnectionListener) {
        listeners -= listener
    }

    fun currentState(): NearbyConnectionState = state.copy(
        endpoints = endpoints.values.toList(),
        isHostSession = isHostSession,
        sentMessageCount = sentMessageCount,
        receivedMessageCount = receivedMessageCount,
        lastPayloadAtMs = lastPayloadAtMs,
        lastPayloadDirection = lastPayloadDirection,
    )

    fun startHost(roomCode: String, localName: String = defaultDeviceName()) {
        stop()
        isHostSession = true
        val cleanRoomCode = roomCode.filter { it.isDigit() }.ifBlank { roomCode }
        updateState(
            mode = NearbyConnectionMode.HOST_ADVERTISING,
            roomCode = cleanRoomCode,
            localName = localName,
            localRole = DeviceRole.HOST,
            isHostSession = true,
            statusText = "正在开启主控连接，房间码：${formatRoomCode(cleanRoomCode)}",
        )

        client.startAdvertising(
            advertisedName(cleanRoomCode, localName),
            SERVICE_ID,
            connectionLifecycleCallback,
            AdvertisingOptions.Builder().setStrategy(Strategy.P2P_STAR).build(),
        ).addOnSuccessListener {
            updateState(statusText = "主控连接已开启，等待其它手机加入。")
        }.addOnFailureListener { error ->
            updateState(
                mode = NearbyConnectionMode.ERROR,
                statusText = "开启主控连接失败：${error.message}",
            )
        }
    }

    fun startNode(roomCode: String, localName: String = defaultDeviceName()) {
        stop()
        isHostSession = false
        val cleanRoomCode = roomCode.filter { it.isDigit() }.ifBlank { roomCode }
        updateState(
            mode = NearbyConnectionMode.NODE_DISCOVERING,
            roomCode = cleanRoomCode,
            localName = localName,
            localRole = DeviceRole.UNKNOWN,
            isHostSession = false,
            statusText = "正在搜索房间 ${formatRoomCode(cleanRoomCode)} 的训练主控手机...",
        )
        // 节点按房间码发现主控
        client.startDiscovery(
            SERVICE_ID,
            endpointDiscoveryCallback,
            DiscoveryOptions.Builder().setStrategy(Strategy.P2P_STAR).build(),
        ).addOnSuccessListener {
            updateState(statusText = "正在搜索训练房间，请保持两台手机靠近。")
        }.addOnFailureListener { error ->
            updateState(
                mode = NearbyConnectionMode.ERROR,
                statusText = "搜索训练房间失败：${error.message}",
            )
        }
    }

    fun assignLocalRole(role: DeviceRole) {
        if (role in PRIMARY_CAMERA_ROLES) {
            endpoints
                .filterValues { endpoint -> endpoint.role == role }
                .forEach { (id, endpoint) ->
                    endpoints[id] = endpoint.copy(role = DeviceRole.UNKNOWN)
                    sendAssignRoleMessage(
                        endpointId = id,
                        role = DeviceRole.UNKNOWN,
                        message = "主控端已将本机设置为${role.displayText()}，请等待新的机位分配。",
                    )
                }
        }
        updateState(
            localRole = role,
            statusText = "本机已设置为${role.displayText()}。",
        )
        val message = NearbyMessage(
            type = NearbyMessageType.DEVICE_STATUS,
            roomCode = state.roomCode,
            deviceId = localDeviceId,
            deviceName = state.localName,
            role = role,
            batteryLevel = batteryLevel(),
            message = "本机机位已更新为${role.displayText()}。",
        )
        endpoints.values
            .filter { it.isOnline }
            .forEach { endpoint -> runCatching { send(endpoint.endpointId, message) } }
        updateState(lastMessage = message, statusText = message.message ?: state.statusText)
    }

    fun assignRole(endpointId: String, role: DeviceRole): Boolean {
        if (!isHostSession) {
            updateState(statusText = "只有创建房间的主控端可以分配机位。")
            return false
        }
        val endpoint = endpoints[endpointId]
        if (endpoint == null || !endpoint.isOnline) {
            updateState(statusText = "目标手机不在线，无法分配机位。")
            return false
        }

        val previousLocalRole = state.localRole
        if (role in PRIMARY_CAMERA_ROLES) {
            endpoints
                .filter { (id, other) -> id != endpointId && other.role == role }
                .forEach { (id, other) ->
                    endpoints[id] = other.copy(role = DeviceRole.UNKNOWN)
                    sendAssignRoleMessage(
                        endpointId = id,
                        role = DeviceRole.UNKNOWN,
                        message = "主控端已重新分配机位，请等待新的正面/侧面设置。",
                    )
                }
            if (previousLocalRole == role) {
                updateState(
                    localRole = DeviceRole.UNKNOWN,
                    statusText = "本机机位已让位给 ${endpoint.deviceName}。",
                )
            }
        }

        endpoints[endpointId] = endpoint.copy(role = role)
        val message = sendAssignRoleMessage(
            endpointId = endpointId,
            role = role,
            message = "主控端已将你设置为${role.displayText()}。",
        )
        updateState(
            lastMessage = message,
            statusText = "已将 ${endpoint.deviceName} 设置为${role.displayText()}。",
        )
        return true
    }

    // 角色分配消息统一封装
    private fun sendAssignRoleMessage(
        endpointId: String,
        role: DeviceRole,
        message: String,
    ): NearbyMessage {
        val assignMessage = NearbyMessage(
            type = NearbyMessageType.ASSIGN_ROLE,
            roomCode = state.roomCode,
            deviceId = localDeviceId,
            deviceName = state.localName,
            endpointId = endpointId,
            role = role,
            message = message,
        )
        send(endpointId, assignMessage)
        return assignMessage
    }

    fun sendStartCountdown(seconds: Int, actionType: ActionType) {
        val sessionId = startNewTrainingSession()
        broadcast(
            NearbyMessage(
                type = NearbyMessageType.START_COUNTDOWN,
                roomCode = state.roomCode,
                deviceId = localDeviceId,
                deviceName = state.localName,
                role = state.localRole,
                actionType = actionType,
                trainingState = TrainingState.PREPARING,
                trainingSessionId = sessionId,
                messageSeq = nextMessageSeq(),
                sessionStartedAtMs = activeSessionStartedAtMs + seconds * 1_000L,
                countdownSeconds = seconds,
                message = "开始同步倒计时：$seconds 秒",
            )
        )
    }

    fun startTrainingBlockReason(): String? =
        RemoteTrainingStartGate.blockReason(currentState())

    fun sendNodeCaptureReady(actionType: ActionType) {
        sendDeviceStatus(
            trainingState = TrainingState.PREPARING,
            actionType = actionType,
            statusMessage = "节点采集就绪：摄像头预览已开启。",
        )
    }

    fun sendNodeCaptureIdle() {
        sendDeviceStatus(
            trainingState = TrainingState.IDLE,
            actionType = ActionType.UNKNOWN,
            statusMessage = "节点已退出采集页。",
        )
    }

    fun sendStartAnalysis(actionType: ActionType) {
        val sessionId = ensureActiveTrainingSession()
        broadcast(
            NearbyMessage(
                type = NearbyMessageType.START_ANALYSIS,
                roomCode = state.roomCode,
                deviceId = localDeviceId,
                deviceName = state.localName,
                role = state.localRole,
                actionType = actionType,
                trainingState = TrainingState.ANALYZING,
                trainingSessionId = sessionId,
                messageSeq = nextMessageSeq(),
                sessionStartedAtMs = activeSessionStartedAtMs,
                message = "开始训练分析：${actionType.displayName}",
            )
        )
    }

    fun sendPauseAnalysis(actionType: ActionType) {
        val sessionId = ensureActiveTrainingSession()
        broadcast(
            NearbyMessage(
                type = NearbyMessageType.PAUSE_ANALYSIS,
                roomCode = state.roomCode,
                deviceId = localDeviceId,
                deviceName = state.localName,
                role = state.localRole,
                actionType = actionType,
                trainingState = TrainingState.ANALYZING,
                trainingSessionId = sessionId,
                messageSeq = nextMessageSeq(),
                sessionStartedAtMs = activeSessionStartedAtMs,
                message = "主控端已暂停训练识别。",
            )
        )
    }

    fun sendResumeAnalysis(actionType: ActionType) {
        val sessionId = ensureActiveTrainingSession()
        broadcast(
            NearbyMessage(
                type = NearbyMessageType.RESUME_ANALYSIS,
                roomCode = state.roomCode,
                deviceId = localDeviceId,
                deviceName = state.localName,
                role = state.localRole,
                actionType = actionType,
                trainingState = TrainingState.ANALYZING,
                trainingSessionId = sessionId,
                messageSeq = nextMessageSeq(),
                sessionStartedAtMs = activeSessionStartedAtMs,
                message = "主控端已继续训练识别。",
            )
        )
    }

    fun sendEndTraining(
        actionType: ActionType,
        totalCount: Int? = null,
        holdDurationMs: Long? = null,
        score: Float? = null,
        problemType: ProblemType = ProblemType.NONE,
        suggestion: String? = null,
    ) {
        val sessionId = ensureActiveTrainingSession()
        broadcast(
            NearbyMessage(
                type = NearbyMessageType.END_TRAINING,
                roomCode = state.roomCode,
                deviceId = localDeviceId,
                deviceName = state.localName,
                role = state.localRole,
                actionType = actionType,
                trainingState = TrainingState.FINISHED,
                trainingSessionId = sessionId,
                messageSeq = nextMessageSeq(),
                sessionStartedAtMs = activeSessionStartedAtMs,
                capturedAtMs = System.currentTimeMillis(),
                totalCount = totalCount,
                holdDurationMs = holdDurationMs,
                score = score,
                problemType = problemType,
                suggestion = suggestion,
                message = "训练结束。",
            )
        )
        activeTrainingSessionId = ""
        activeSessionStartedAtMs = 0L
    }

    fun sendAnalysisSummary(
        actionType: ActionType,
        actionConfidence: Float? = null,
        totalCount: Int,
        holdDurationMs: Long? = null,
        score: Float?,
        kneeAngle: Float?,
        trunkAngle: Float?,
        postureLevel: String?,
        problemType: ProblemType,
        suggestion: String?,
    ) {
        val sessionId = ensureActiveTrainingSession()
        broadcast(
            NearbyMessage(
                type = NearbyMessageType.ANALYSIS_SUMMARY,
                roomCode = state.roomCode,
                deviceId = localDeviceId,
                deviceName = state.localName,
                role = state.localRole,
                actionType = actionType,
                actionConfidence = actionConfidence,
                trainingState = TrainingState.ANALYZING,
                trainingSessionId = sessionId,
                messageSeq = nextMessageSeq(),
                sessionStartedAtMs = activeSessionStartedAtMs,
                capturedAtMs = System.currentTimeMillis(),
                totalCount = totalCount,
                holdDurationMs = holdDurationMs,
                score = score,
                kneeAngle = kneeAngle,
                trunkAngle = trunkAngle,
                postureLevel = postureLevel,
                problemType = problemType,
                suggestion = suggestion,
                message = "节点分析更新：${actionType.displayName} $totalCount 次",
            )
        )
    }

    fun sendRepResult(
        actionType: ActionType,
        repIndex: Int,
        score: Float?,
        kneeAngle: Float?,
        trunkAngle: Float?,
        postureLevel: String?,
        problemTypes: List<ProblemType>,
        suggestion: String?,
    ) {
        val sessionId = ensureActiveTrainingSession()
        val primaryProblem = problemTypes.firstOrNull() ?: ProblemType.NONE
        broadcast(
            NearbyMessage(
                type = NearbyMessageType.REP_RESULT,
                roomCode = state.roomCode,
                deviceId = localDeviceId,
                deviceName = state.localName,
                role = state.localRole,
                actionType = actionType,
                trainingState = TrainingState.ANALYZING,
                trainingSessionId = sessionId,
                messageSeq = nextMessageSeq(),
                sessionStartedAtMs = activeSessionStartedAtMs,
                capturedAtMs = System.currentTimeMillis(),
                totalCount = repIndex,
                repIndex = repIndex,
                score = score,
                kneeAngle = kneeAngle,
                trunkAngle = trunkAngle,
                postureLevel = postureLevel,
                problemType = primaryProblem,
                problemTypes = problemTypes,
                suggestion = suggestion,
                message = "单次动作结果：${actionType.displayName} 第 $repIndex 次",
            )
        )
    }

    fun sendHostAnalysisStatus(
        actionType: ActionType,
        totalCount: Int,
        holdDurationMs: Long? = null,
        score: Float?,
        kneeAngle: Float?,
        trunkAngle: Float?,
        postureLevel: String?,
        problemType: ProblemType,
        suggestion: String?,
        message: String,
    ) {
        val sessionId = ensureActiveTrainingSession()
        broadcast(
            NearbyMessage(
                type = NearbyMessageType.HOST_ANALYSIS_STATUS,
                roomCode = state.roomCode,
                deviceId = localDeviceId,
                deviceName = state.localName,
                role = DeviceRole.HOST,
                actionType = actionType,
                trainingState = TrainingState.ANALYZING,
                trainingSessionId = sessionId,
                messageSeq = nextMessageSeq(),
                sessionStartedAtMs = activeSessionStartedAtMs,
                capturedAtMs = System.currentTimeMillis(),
                totalCount = totalCount,
                holdDurationMs = holdDurationMs,
                score = score,
                kneeAngle = kneeAngle,
                trunkAngle = trunkAngle,
                postureLevel = postureLevel,
                problemType = problemType,
                suggestion = suggestion,
                message = message,
            )
        )
    }

    fun sendPoseFrameSnapshot(actionType: ActionType, frame: PoseFrame) {
        val sessionId = ensureActiveTrainingSession()
        broadcast(
            NearbyMessage(
                type = NearbyMessageType.POSE_FRAME,
                roomCode = state.roomCode,
                deviceId = localDeviceId,
                deviceName = state.localName,
                role = frame.cameraRole,
                actionType = actionType,
                trainingState = TrainingState.ANALYZING,
                trainingSessionId = sessionId,
                messageSeq = nextMessageSeq(),
                sessionStartedAtMs = activeSessionStartedAtMs,
                capturedAtMs = frame.timestampMs,
                poseFrameJson = NearbyPoseFrameCodec.encode(frame),
                message = "节点关键点样本：${frame.landmarks.size} 点，置信度 ${
                    "%.2f".format(Locale.US, frame.overallConfidence)
                }",
            )
        )
    }

    // 在线节点逐个发送消息
    fun broadcast(message: NearbyMessage) {
        endpoints.values
            .filter { it.isOnline }
            .forEach { endpoint -> send(endpoint.endpointId, message) }
        updateState(lastMessage = message, statusText = message.message ?: state.statusText)
    }

    fun send(endpointId: String, message: NearbyMessage) {
        val bytes = codec.encode(message).toByteArray(Charsets.UTF_8)
        sentMessageCount += 1
        lastPayloadAtMs = System.currentTimeMillis()
        lastPayloadDirection = "发送消息"
        client.sendPayload(endpointId, Payload.fromBytes(bytes))
    }

    fun stop() {
        heartbeatHandler.removeCallbacks(heartbeatRunnable)
        client.stopAdvertising()
        client.stopDiscovery()
        client.stopAllEndpoints()
        endpoints.clear()
        pendingLatencyPings.clear()
        isHostSession = false
        sentMessageCount = 0
        receivedMessageCount = 0
        lastPayloadAtMs = 0L
        lastPayloadDirection = "暂无"
        updateState(
            mode = NearbyConnectionMode.IDLE,
            endpoints = emptyList(),
            isHostSession = false,
            statusText = "多设备连接已停止。",
        )
    }

    private fun sendJoinRequest(endpointId: String) {
        send(
            endpointId,
            NearbyMessage(
                type = NearbyMessageType.JOIN_REQUEST,
                roomCode = state.roomCode,
                deviceId = localDeviceId,
                deviceName = state.localName,
                role = state.localRole,
                batteryLevel = batteryLevel(),
                message = "请求加入智姿镜训练房间。",
            )
        )
    }

    private fun sendDeviceStatus(
        endpointId: String? = null,
        trainingState: TrainingState = TrainingState.IDLE,
        actionType: ActionType = ActionType.UNKNOWN,
        statusMessage: String = "节点状态上报。",
    ) {
        val message = NearbyMessage(
            type = NearbyMessageType.DEVICE_STATUS,
            roomCode = state.roomCode,
            deviceId = localDeviceId,
            deviceName = state.localName,
            role = state.localRole,
            actionType = actionType,
            trainingState = trainingState,
            batteryLevel = batteryLevel(),
            message = statusMessage,
        )
        if (endpointId == null) {
            broadcast(message)
        } else {
            send(endpointId, message)
        }
    }

    private fun startHeartbeat() {
        heartbeatHandler.removeCallbacks(heartbeatRunnable)
        heartbeatHandler.postDelayed(heartbeatRunnable, HEARTBEAT_INTERVAL_MS)
    }

    private fun sendHeartbeat() {
        if (endpoints.values.none { it.isOnline }) return
        val message = NearbyMessage(
            type = NearbyMessageType.HEARTBEAT,
            roomCode = state.roomCode,
            deviceId = localDeviceId,
            deviceName = state.localName,
            role = state.localRole,
            batteryLevel = batteryLevel(),
        )
        endpoints.values
            .filter { it.isOnline }
            .forEach { endpoint -> runCatching { send(endpoint.endpointId, message) } }
    }

    private fun sendLatencyPings(nowMs: Long = System.currentTimeMillis()) {
        endpoints.values
            .filter { it.isOnline }
            .forEach { endpoint ->
                val correlationId = "${localDeviceId}-${endpoint.endpointId}-$nowMs"
                pendingLatencyPings[correlationId] = PendingLatencyPing(
                    endpointId = endpoint.endpointId,
                    sentAtMs = nowMs,
                )
                runCatching {
                    send(
                        endpoint.endpointId,
                        NearbyMessage(
                            type = NearbyMessageType.LATENCY_PING,
                            roomCode = state.roomCode,
                            deviceId = localDeviceId,
                            deviceName = state.localName,
                            correlationId = correlationId,
                            role = state.localRole,
                            message = "多设备连接延迟探测。",
                        )
                    )
                }.onFailure {
                    pendingLatencyPings.remove(correlationId)
                }
            }
        trimExpiredLatencyPings(nowMs)
    }

    private fun replyLatencyPong(endpointId: String, message: NearbyMessage) {
        if (message.correlationId.isBlank()) return
        send(
            endpointId,
            NearbyMessage(
                type = NearbyMessageType.LATENCY_PONG,
                roomCode = state.roomCode,
                deviceId = localDeviceId,
                deviceName = state.localName,
                correlationId = message.correlationId,
                role = state.localRole,
                message = "多设备连接延迟探测响应。",
            )
        )
    }

    private fun handleLatencyPong(endpointId: String, message: NearbyMessage, receivedAtMs: Long) {
        val pending = pendingLatencyPings.remove(message.correlationId) ?: return
        if (pending.endpointId != endpointId) return
        val rttMs = NearbyMessageDiagnostics.roundTripDelayMs(pending.sentAtMs, receivedAtMs) ?: return
        val previous = endpoints[endpointId]
        if (previous != null) {
            endpoints[endpointId] = previous.copy(
                networkDelayMs = rttMs,
                isOnline = true,
                lastHeartbeatAt = receivedAtMs,
            )
            updateState(lastMessage = message)
        }
    }

    private fun trimExpiredLatencyPings(nowMs: Long) {
        val expiredKeys = pendingLatencyPings
            .filterValues { nowMs - it.sentAtMs > LATENCY_PING_TIMEOUT_MS }
            .keys
        expiredKeys.forEach { key -> pendingLatencyPings.remove(key) }
    }

    private fun markStaleEndpointsOffline(nowMs: Long = System.currentTimeMillis()) {
        val staleEndpoints = endpoints.values.filter { endpoint ->
            endpoint.isOnline && nowMs - endpoint.lastHeartbeatAt > ENDPOINT_STALE_TIMEOUT_MS
        }
        if (staleEndpoints.isEmpty()) return
        staleEndpoints.forEach { endpoint ->
            endpoints[endpoint.endpointId] = endpoint.copy(isOnline = false)
        }
        updateState(statusText = "设备连接超时：${staleEndpoints.joinToString { it.deviceName }}")
    }

    private fun handleMessage(
        endpointId: String,
        message: NearbyMessage,
        receivedAtMs: Long = System.currentTimeMillis(),
    ): Boolean {
        if (!matchesCurrentRoom(message.roomCode)) {
            updateState(statusText = "已忽略其它房间的连接消息。")
            return false
        }
        when (message.type) {
            NearbyMessageType.JOIN_REQUEST,
            NearbyMessageType.DEVICE_STATUS,
            NearbyMessageType.HEARTBEAT -> {
                val previous = endpoints[endpointId]
                val estimatedDelay = NearbyMessageDiagnostics.estimateDelayMs(message, receivedAtMs)
                val nextTrainingState = when (message.type) {
                    NearbyMessageType.HEARTBEAT -> previous?.trainingState ?: TrainingState.IDLE
                    NearbyMessageType.JOIN_REQUEST -> TrainingState.IDLE
                    else -> message.trainingState
                }
                endpoints[endpointId] = NearbyEndpoint(
                    endpointId = endpointId,
                    deviceName = message.deviceName.ifBlank { previous?.deviceName ?: "未知设备" },
                    role = message.role.takeUnless { it == DeviceRole.UNKNOWN } ?: previous?.role ?: DeviceRole.UNKNOWN,
                    trainingState = nextTrainingState,
                    batteryLevel = message.batteryLevel,
                    networkDelayMs = estimatedDelay ?: previous?.networkDelayMs,
                    isOnline = true,
                    lastHeartbeatAt = receivedAtMs,
                )
                if (message.type == NearbyMessageType.JOIN_REQUEST && isHostSession) {
                    send(
                        endpointId,
                        NearbyMessage(
                            type = NearbyMessageType.JOIN_ACCEPTED,
                            roomCode = state.roomCode,
                            deviceId = localDeviceId,
                            deviceName = state.localName,
                            role = DeviceRole.HOST,
                            message = "主控端已接受加入请求。",
                        )
                    )
                }
                updateState(lastMessage = message, statusText = message.message ?: "收到设备状态。")
            }
            NearbyMessageType.JOIN_ACCEPTED -> {
                updateState(
                    mode = NearbyConnectionMode.CONNECTED,
                    lastMessage = message,
                    statusText = "已加入房间 ${formatRoomCode(message.roomCode)}，请到设备组队页设置本机机位。",
                )
            }
            NearbyMessageType.ASSIGN_ROLE -> {
                updateState(
                    localRole = message.role,
                    lastMessage = message,
                    statusText = "已接收机位角色：${message.role}",
                )
                sendDeviceStatus(endpointId)
            }
            NearbyMessageType.START_COUNTDOWN,
            NearbyMessageType.START_ANALYSIS,
            NearbyMessageType.PAUSE_ANALYSIS,
            NearbyMessageType.RESUME_ANALYSIS,
            NearbyMessageType.ANALYSIS_SUMMARY,
            NearbyMessageType.REP_RESULT,
            NearbyMessageType.HOST_ANALYSIS_STATUS,
            NearbyMessageType.END_TRAINING,
            NearbyMessageType.POSE_FRAME,
            NearbyMessageType.ERROR -> {
                applyTrainingSessionFrom(message)
                updateState(lastMessage = message, statusText = message.message ?: state.statusText)
            }
            NearbyMessageType.LATENCY_PING -> {
                replyLatencyPong(endpointId, message)
                updateState(lastMessage = message)
            }
            NearbyMessageType.LATENCY_PONG -> {
                handleLatencyPong(endpointId, message, receivedAtMs)
            }
        }
        return true
    }

    private fun startNewTrainingSession(): String {
        activeTrainingSessionId = UUID.randomUUID().toString()
        activeSessionStartedAtMs = System.currentTimeMillis()
        outgoingMessageSeq = 0L
        return activeTrainingSessionId
    }

    private fun ensureActiveTrainingSession(): String {
        if (activeTrainingSessionId.isBlank()) {
            return startNewTrainingSession()
        }
        if (activeSessionStartedAtMs <= 0L) {
            activeSessionStartedAtMs = System.currentTimeMillis()
        }
        return activeTrainingSessionId
    }

    private fun nextMessageSeq(): Long {
        outgoingMessageSeq += 1
        return outgoingMessageSeq
    }

    private fun applyTrainingSessionFrom(message: NearbyMessage) {
        when (message.type) {
            NearbyMessageType.START_COUNTDOWN,
            NearbyMessageType.START_ANALYSIS -> {
                if (message.trainingSessionId.isNotBlank()) {
                    activeTrainingSessionId = message.trainingSessionId
                    activeSessionStartedAtMs = message.sessionStartedAtMs ?: System.currentTimeMillis()
                    outgoingMessageSeq = 0L
                }
            }
            NearbyMessageType.END_TRAINING -> {
                if (message.trainingSessionId.isBlank() || message.trainingSessionId == activeTrainingSessionId) {
                    activeTrainingSessionId = ""
                    activeSessionStartedAtMs = 0L
                    outgoingMessageSeq = 0L
                }
            }
            else -> Unit
        }
    }

    private fun updateState(
        mode: NearbyConnectionMode = state.mode,
        roomCode: String = state.roomCode,
        localName: String = state.localName,
        localRole: DeviceRole = state.localRole,
        isHostSession: Boolean = state.isHostSession,
        endpoints: List<NearbyEndpoint> = this.endpoints.values.toList(),
        statusText: String = state.statusText,
        lastMessage: NearbyMessage? = null,
    ) {
        state = NearbyConnectionState(
            mode = mode,
            roomCode = roomCode,
            localName = localName,
            localRole = localRole,
            isHostSession = isHostSession,
            endpoints = endpoints,
            statusText = statusText,
            lastMessage = lastMessage,
            sentMessageCount = sentMessageCount,
            receivedMessageCount = receivedMessageCount,
            lastPayloadAtMs = lastPayloadAtMs,
            lastPayloadDirection = lastPayloadDirection,
        )
        listeners.forEach { listener -> listener.onNearbyStateChanged(state) }
    }

    private fun batteryLevel(): Int? {
        val batteryManager = appContext.getSystemService(BatteryManager::class.java) ?: return null
        val level = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return level.takeIf { it >= 0 }
    }

    private fun defaultDeviceName(): String =
        "${Build.MANUFACTURER} ${Build.MODEL}".trim().ifBlank { "Android 设备" }

    private fun advertisedName(roomCode: String, localName: String): String =
        "智姿镜-${formatRoomCode(roomCode)}-$localName"

    private fun cleanEndpointName(endpointName: String): String =
        endpointName.substringAfterLast("-").ifBlank { endpointName }

    private fun matchesRoom(endpointName: String, roomCode: String): Boolean =
        roomCode.filter { it.isDigit() }.ifBlank { return true }
            .let { cleanRoomCode ->
                endpointName.startsWith("智姿镜-${formatRoomCode(cleanRoomCode)}-")
            }

    private fun matchesCurrentRoom(messageRoomCode: String): Boolean {
        val currentRoomCode = state.roomCode.filter { it.isDigit() }
        val incomingRoomCode = messageRoomCode.filter { it.isDigit() }
        return currentRoomCode.isBlank() || incomingRoomCode.isBlank() || currentRoomCode == incomingRoomCode
    }

    private fun formatRoomCode(roomCode: String): String =
        roomCode.filter { it.isDigit() }.chunked(3).joinToString(" ").ifBlank { roomCode }

    private fun DeviceRole.displayText(): String =
        when (this) {
            DeviceRole.HOST -> "主控端"
            DeviceRole.FRONT_CAMERA -> "正面机位"
            DeviceRole.SIDE_CAMERA -> "侧面机位"
            DeviceRole.BACKUP_CAMERA -> "备用机位"
            DeviceRole.UNKNOWN -> "未分配"
        }

    companion object {
        private const val SERVICE_ID = "com.example.zhizijing.NEARBY_CONNECTIONS"
        private const val HEARTBEAT_INTERVAL_MS = 5_000L
        private const val ENDPOINT_STALE_TIMEOUT_MS = 15_000L
        private const val LATENCY_PING_TIMEOUT_MS = 20_000L
        private val PRIMARY_CAMERA_ROLES = setOf(DeviceRole.FRONT_CAMERA, DeviceRole.SIDE_CAMERA)
    }

    private data class PendingLatencyPing(
        val endpointId: String,
        val sentAtMs: Long,
    )
}
