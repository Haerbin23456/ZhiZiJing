package com.example.zhizijing.data.dao;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;
import com.example.zhizijing.data.entity.UserEntity;

@Dao
public interface UserDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    long insert(UserEntity user);

    @Query("SELECT * FROM users WHERE userId = :userId LIMIT 1")
    UserEntity findById(long userId);

    @Query("SELECT * FROM users WHERE username = :username LIMIT 1")
    UserEntity findByUsername(String username);

    @Update
    void update(UserEntity user);
}
