package com.example.zhizijing.data.dao;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import com.example.zhizijing.data.entity.ActionResultEntity;
import java.util.List;

@Dao
public interface ActionResultDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    long insert(ActionResultEntity result);

    @Query("SELECT * FROM action_results WHERE sessionId = :sessionId ORDER BY actionIndex ASC")
    List<ActionResultEntity> findBySession(long sessionId);

    @Query("DELETE FROM action_results WHERE sessionId = :sessionId")
    void deleteBySession(long sessionId);
}
