package com.example.zhizijing.data.dao;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import com.example.zhizijing.data.entity.DeviceNodeEntity;
import java.util.List;

@Dao
public interface DeviceNodeDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    long insert(DeviceNodeEntity node);

    @Query("SELECT * FROM device_nodes WHERE sessionId = :sessionId")
    List<DeviceNodeEntity> findBySession(long sessionId);

    @Query("DELETE FROM device_nodes WHERE sessionId = :sessionId")
    void deleteBySession(long sessionId);
}
