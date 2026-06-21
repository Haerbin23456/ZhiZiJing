package com.example.zhizijing.data.entity;

import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(tableName = "users", indices = {@Index(value = "username", unique = true)})
public class UserEntity {
    @PrimaryKey(autoGenerate = true)
    public long userId;
    public String username;
    public String passwordHash;
    public String nickname;
    public long createdAt;
    public Long lastLoginAt;

    public UserEntity(String username, String passwordHash, String nickname, long createdAt, Long lastLoginAt) {
        this.username = username;
        this.passwordHash = passwordHash;
        this.nickname = nickname;
        this.createdAt = createdAt;
        this.lastLoginAt = lastLoginAt;
    }
}
