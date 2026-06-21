package com.example.zhizijing.data.local;

import androidx.room.Database;
import androidx.room.RoomDatabase;
import com.example.zhizijing.data.dao.ActionResultDao;
import com.example.zhizijing.data.dao.DeviceNodeDao;
import com.example.zhizijing.data.dao.ExportRecordDao;
import com.example.zhizijing.data.dao.PoseFrameDao;
import com.example.zhizijing.data.dao.TrainingSessionDao;
import com.example.zhizijing.data.dao.UserDao;
import com.example.zhizijing.data.entity.ActionResultEntity;
import com.example.zhizijing.data.entity.DeviceNodeEntity;
import com.example.zhizijing.data.entity.ExportRecordEntity;
import com.example.zhizijing.data.entity.PoseFrameEntity;
import com.example.zhizijing.data.entity.TrainingSessionEntity;
import com.example.zhizijing.data.entity.UserEntity;

// 训练数据 Room 入口
@Database(
        entities = {
                UserEntity.class,
                TrainingSessionEntity.class,
                DeviceNodeEntity.class,
                PoseFrameEntity.class,
                ActionResultEntity.class,
                ExportRecordEntity.class
        },
        version = 2,
        exportSchema = false
)
public abstract class AppDatabase extends RoomDatabase {
    public abstract UserDao userDao();
    public abstract TrainingSessionDao trainingSessionDao();
    public abstract DeviceNodeDao deviceNodeDao();
    public abstract PoseFrameDao poseFrameDao();
    public abstract ActionResultDao actionResultDao();
    public abstract ExportRecordDao exportRecordDao();
}
