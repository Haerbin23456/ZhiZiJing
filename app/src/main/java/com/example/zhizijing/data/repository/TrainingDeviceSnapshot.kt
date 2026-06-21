package com.example.zhizijing.data.repository

import com.example.zhizijing.domain.model.DeviceRole

data class TrainingDeviceSnapshot(
    val deviceName: String,
    val endpointId: String,
    val role: DeviceRole,
    val batteryLevel: Int?,
    val networkDelayMs: Int?,
    val isOnline: Boolean,
    val lastHeartbeatAt: Long?,
)
