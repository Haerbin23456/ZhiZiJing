package com.example.zhizijing.data.entity;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "device_nodes")
public class DeviceNodeEntity {
    @PrimaryKey(autoGenerate = true)
    public long nodeId;
    public long sessionId;
    public String deviceName;
    public String endpointId;
    public String role;
    public Integer batteryLevel;
    public Integer networkDelayMs;
    public boolean isOnline;
    public Long lastHeartbeatAt;

    public DeviceNodeEntity(
            long sessionId,
            String deviceName,
            String endpointId,
            String role,
            Integer batteryLevel,
            Integer networkDelayMs,
            boolean isOnline,
            Long lastHeartbeatAt
    ) {
        this.sessionId = sessionId;
        this.deviceName = deviceName;
        this.endpointId = endpointId;
        this.role = role;
        this.batteryLevel = batteryLevel;
        this.networkDelayMs = networkDelayMs;
        this.isOnline = isOnline;
        this.lastHeartbeatAt = lastHeartbeatAt;
    }
}
