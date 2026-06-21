package com.example.zhizijing.data.entity;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "export_records")
public class ExportRecordEntity {
    @PrimaryKey(autoGenerate = true)
    public long exportId;
    public long sessionId;
    public String exportType;
    public String filePath;
    public long createdAt;
    public Long fileSize;

    public ExportRecordEntity(long sessionId, String exportType, String filePath, long createdAt, Long fileSize) {
        this.sessionId = sessionId;
        this.exportType = exportType;
        this.filePath = filePath;
        this.createdAt = createdAt;
        this.fileSize = fileSize;
    }
}
