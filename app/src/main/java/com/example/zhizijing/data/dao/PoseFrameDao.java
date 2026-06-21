package com.example.zhizijing.data.dao;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import com.example.zhizijing.data.entity.PoseFrameEntity;
import java.util.List;

@Dao
public interface PoseFrameDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    long insert(PoseFrameEntity frame);

    @Query("SELECT * FROM pose_frames WHERE sessionId = :sessionId ORDER BY timestampMs ASC")
    List<PoseFrameEntity> findBySession(long sessionId);

    @Query("DELETE FROM pose_frames WHERE sessionId = :sessionId")
    void deleteBySession(long sessionId);
}
