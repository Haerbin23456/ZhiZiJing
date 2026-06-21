package com.example.zhizijing.data.dao;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import com.example.zhizijing.data.entity.ExportRecordEntity;
import java.util.List;

@Dao
public interface ExportRecordDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    long insert(ExportRecordEntity record);

    @Query("SELECT * FROM export_records WHERE sessionId = :sessionId ORDER BY createdAt DESC, exportId DESC")
    List<ExportRecordEntity> findBySession(long sessionId);

    @Query("DELETE FROM export_records WHERE sessionId = :sessionId")
    void deleteBySession(long sessionId);
}
