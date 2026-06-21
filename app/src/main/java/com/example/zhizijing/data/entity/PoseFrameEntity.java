package com.example.zhizijing.data.entity;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "pose_frames")
public class PoseFrameEntity {
    @PrimaryKey(autoGenerate = true)
    public long frameId;
    public long sessionId;
    public long nodeId;
    public long timestampMs;
    public String cameraRole;
    public String landmarksJson;
    public Float confidence;
    public String frameImagePath;
    public String frameType;

    public PoseFrameEntity(
            long sessionId,
            long nodeId,
            long timestampMs,
            String cameraRole,
            String landmarksJson,
            Float confidence,
            String frameImagePath,
            String frameType
    ) {
        this.sessionId = sessionId;
        this.nodeId = nodeId;
        this.timestampMs = timestampMs;
        this.cameraRole = cameraRole;
        this.landmarksJson = landmarksJson;
        this.confidence = confidence;
        this.frameImagePath = frameImagePath;
        this.frameType = frameType;
    }
}
