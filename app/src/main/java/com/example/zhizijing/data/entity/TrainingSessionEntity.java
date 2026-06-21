package com.example.zhizijing.data.entity;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "training_sessions")
public class TrainingSessionEntity {
    @PrimaryKey(autoGenerate = true)
    public long sessionId;
    public long userId;
    public String actionType;
    public long startTime;
    public Long endTime;
    public int deviceCount;
    public int totalCount;
    public Float averageScore;
    public String mainProblems;
    public String reportPath;
    public String status;

    public TrainingSessionEntity(
            long userId,
            String actionType,
            long startTime,
            Long endTime,
            int deviceCount,
            int totalCount,
            Float averageScore,
            String mainProblems,
            String reportPath,
            String status
    ) {
        this.userId = userId;
        this.actionType = actionType;
        this.startTime = startTime;
        this.endTime = endTime;
        this.deviceCount = deviceCount;
        this.totalCount = totalCount;
        this.averageScore = averageScore;
        this.mainProblems = mainProblems;
        this.reportPath = reportPath;
        this.status = status;
    }
}
