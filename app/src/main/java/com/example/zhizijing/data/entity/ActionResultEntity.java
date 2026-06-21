package com.example.zhizijing.data.entity;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "action_results")
public class ActionResultEntity {
    @PrimaryKey(autoGenerate = true)
    public long resultId;
    public long sessionId;
    public int actionIndex;
    public String actionType;
    public long startTimeMs;
    public long endTimeMs;
    public Float score;
    public Float kneeAngle;
    public Float trunkAngle;
    public String depthLevel;
    public String postureLevel;
    public String problemType;
    public String suggestion;
    public String keyFramePath;

    public ActionResultEntity(
            long sessionId,
            int actionIndex,
            String actionType,
            long startTimeMs,
            long endTimeMs,
            Float score,
            Float kneeAngle,
            Float trunkAngle,
            String depthLevel,
            String problemType,
            String suggestion,
            String keyFramePath
    ) {
        this.sessionId = sessionId;
        this.actionIndex = actionIndex;
        this.actionType = actionType;
        this.startTimeMs = startTimeMs;
        this.endTimeMs = endTimeMs;
        this.score = score;
        this.kneeAngle = kneeAngle;
        this.trunkAngle = trunkAngle;
        this.depthLevel = depthLevel;
        this.postureLevel = depthLevel;
        this.problemType = problemType;
        this.suggestion = suggestion;
        this.keyFramePath = keyFramePath;
    }
}
