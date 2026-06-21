package com.example.zhizijing.data.dao;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;
import com.example.zhizijing.data.entity.TrainingSessionEntity;
import java.util.List;

// 训练记录增删改查接口
@Dao
public interface TrainingSessionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    long insert(TrainingSessionEntity session);

    @Update
    void update(TrainingSessionEntity session);

    @Query("SELECT * FROM training_sessions WHERE sessionId = :sessionId LIMIT 1")
    TrainingSessionEntity findById(long sessionId);

    @Query("SELECT * FROM training_sessions WHERE userId = :userId ORDER BY startTime DESC")
    List<TrainingSessionEntity> findByUser(long userId);

    @Query("SELECT * FROM training_sessions WHERE userId = :userId ORDER BY startTime DESC LIMIT 1")
    TrainingSessionEntity findLatestByUser(long userId);

    @Query("SELECT * FROM training_sessions ORDER BY startTime DESC")
    List<TrainingSessionEntity> findAll();

    @Query("SELECT * FROM training_sessions ORDER BY startTime DESC LIMIT 1")
    TrainingSessionEntity findLatest();

    @Query("DELETE FROM training_sessions WHERE sessionId = :sessionId")
    void deleteById(long sessionId);
}
