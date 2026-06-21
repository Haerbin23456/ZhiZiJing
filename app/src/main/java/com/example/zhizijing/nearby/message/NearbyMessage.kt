package com.example.zhizijing.nearby.message

import com.example.zhizijing.domain.model.ActionType
import com.example.zhizijing.domain.model.DeviceRole
import com.example.zhizijing.domain.model.ProblemType
import com.example.zhizijing.domain.model.TrainingState
import com.google.gson.Gson

enum class NearbyMessageType {
    JOIN_REQUEST,
    JOIN_ACCEPTED,
    DEVICE_STATUS,
    ASSIGN_ROLE,
    START_COUNTDOWN,
    START_ANALYSIS,
    PAUSE_ANALYSIS,
    RESUME_ANALYSIS,
    POSE_FRAME,
    ANALYSIS_SUMMARY,
    HOST_ANALYSIS_STATUS,
    END_TRAINING,
    HEARTBEAT,
    LATENCY_PING,
    LATENCY_PONG,
    ERROR,
}

data class NearbyMessage(
    val type: NearbyMessageType,
    val roomCode: String = "",
    val deviceId: String = "",
    val deviceName: String = "",
    val endpointId: String = "",
    val correlationId: String = "",
    val role: DeviceRole = DeviceRole.UNKNOWN,
    val actionType: ActionType = ActionType.UNKNOWN,
    val actionConfidence: Float? = null,
    val trainingState: TrainingState = TrainingState.IDLE,
    val timestampMs: Long = System.currentTimeMillis(),
    val countdownSeconds: Int? = null,
    val batteryLevel: Int? = null,
    val networkDelayMs: Int? = null,
    val totalCount: Int? = null,
    val holdDurationMs: Long? = null,
    val score: Float? = null,
    val kneeAngle: Float? = null,
    val trunkAngle: Float? = null,
    val postureLevel: String? = null,
    val problemType: ProblemType = ProblemType.NONE,
    val suggestion: String? = null,
    val poseFrameJson: String? = null,
    val message: String? = null,
)

class GsonNearbyMessageCodec(
    private val gson: Gson = Gson(),
) : NearbyMessageCodec<NearbyMessage> {
    override fun encode(message: NearbyMessage): String =
        gson.toJson(message)

    override fun decode(json: String): NearbyMessage =
        gson.fromJson(json, NearbyMessage::class.java)
}
