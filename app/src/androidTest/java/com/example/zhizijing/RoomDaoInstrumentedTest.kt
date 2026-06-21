package com.example.zhizijing

import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.zhizijing.data.entity.ActionResultEntity
import com.example.zhizijing.data.entity.DeviceNodeEntity
import com.example.zhizijing.data.entity.ExportRecordEntity
import com.example.zhizijing.data.entity.PoseFrameEntity
import com.example.zhizijing.data.entity.TrainingSessionEntity
import com.example.zhizijing.data.entity.UserEntity
import com.example.zhizijing.data.local.AppDatabase
import com.example.zhizijing.domain.model.ActionType
import com.example.zhizijing.domain.model.DeviceRole
import com.example.zhizijing.domain.model.ProblemType
import com.example.zhizijing.domain.model.TrainingState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomDaoInstrumentedTest {
    private lateinit var database: AppDatabase

    @Before
    fun createDatabase() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun userDaoPersistsUpdatesAndRejectsDuplicateUsername() {
        val user = UserEntity(
            "tester",
            "password_hash",
            "测试用户",
            1_000L,
            1_000L,
        )

        val userId = database.userDao().insert(user)
        val saved = database.userDao().findByUsername("tester")

        assertTrue(userId > 0L)
        assertNotNull(saved)
        assertEquals(userId, saved.userId)
        assertEquals("测试用户", saved.nickname)

        saved.lastLoginAt = 2_000L
        database.userDao().update(saved)

        assertEquals(2_000L, database.userDao().findById(userId).lastLoginAt)

        runCatching {
            database.userDao().insert(
                UserEntity("tester", "other_hash", "重复用户", 3_000L, 3_000L)
            )
        }.onSuccess {
            throw AssertionError("重复用户名应该被唯一索引拒绝")
        }.onFailure { error ->
            assertTrue(error is SQLiteConstraintException)
        }
    }

    @Test
    fun trainingDaosPersistQueryAndDeleteSessionData() {
        val sessionId = database.trainingSessionDao().insert(
            TrainingSessionEntity(
                7L,
                ActionType.SQUAT.name,
                10_000L,
                40_000L,
                2,
                8,
                92f,
                ProblemType.NONE.name,
                null,
                TrainingState.FINISHED.name,
            )
        )
        val actionResultId = database.actionResultDao().insert(
            ActionResultEntity(
                sessionId,
                1,
                ActionType.SQUAT.name,
                10L,
                20L,
                95f,
                88f,
                12f,
                "GOOD",
                ProblemType.NONE.name,
                "保持稳定",
                "frames/frame_1.png",
            )
        )
        val poseFrameId = database.poseFrameDao().insert(
            PoseFrameEntity(
                sessionId,
                2L,
                12_000L,
                DeviceRole.SIDE_CAMERA.name,
                """{"LEFT_HIP":{"x":0.4,"y":0.6}}""",
                0.91f,
                null,
                "REAL_POSE",
            )
        )
        val deviceNodeId = database.deviceNodeDao().insert(
            DeviceNodeEntity(
                sessionId,
                "Pixel Node",
                "endpoint-1",
                DeviceRole.SIDE_CAMERA.name,
                87,
                35,
                true,
                12_345L,
            )
        )
        val exportRecordId = database.exportRecordDao().insert(
            ExportRecordEntity(
                sessionId,
                "PDF",
                "/tmp/report.pdf",
                20_000L,
                1024L,
            )
        )
        val newerSameTimestampExportRecordId = database.exportRecordDao().insert(
            ExportRecordEntity(
                sessionId,
                "JSON",
                "/tmp/report.json",
                20_000L,
                2048L,
            )
        )

        val savedSession = database.trainingSessionDao().findById(sessionId)
        savedSession.reportPath = "/tmp/report.pdf"
        database.trainingSessionDao().update(savedSession)
        val sessionWithReport = database.trainingSessionDao().findById(sessionId)
        val userSessions = database.trainingSessionDao().findByUser(7L)
        val actions = database.actionResultDao().findBySession(sessionId)
        val frames = database.poseFrameDao().findBySession(sessionId)
        val nodes = database.deviceNodeDao().findBySession(sessionId)
        val exports = database.exportRecordDao().findBySession(sessionId)

        assertTrue(actionResultId > 0L)
        assertTrue(poseFrameId > 0L)
        assertTrue(deviceNodeId > 0L)
        assertTrue(exportRecordId > 0L)
        assertTrue(newerSameTimestampExportRecordId > exportRecordId)
        assertEquals(ActionType.SQUAT.name, savedSession.actionType)
        assertEquals("/tmp/report.pdf", sessionWithReport.reportPath)
        assertEquals(1, userSessions.size)
        assertEquals(1, actions.size)
        assertEquals(95f, actions.first().score ?: 0f, 0.01f)
        assertEquals("GOOD", actions.first().postureLevel)
        assertEquals(1, frames.size)
        assertEquals(DeviceRole.SIDE_CAMERA.name, frames.first().cameraRole)
        assertEquals(1, nodes.size)
        assertEquals("endpoint-1", nodes.first().endpointId)
        assertEquals(2, exports.size)
        assertEquals("JSON", exports.first().exportType)
        assertEquals("PDF", exports.last().exportType)

        database.actionResultDao().deleteBySession(sessionId)
        database.poseFrameDao().deleteBySession(sessionId)
        database.deviceNodeDao().deleteBySession(sessionId)
        database.exportRecordDao().deleteBySession(sessionId)
        database.trainingSessionDao().deleteById(sessionId)

        assertEquals(0, database.actionResultDao().findBySession(sessionId).size)
        assertEquals(0, database.poseFrameDao().findBySession(sessionId).size)
        assertEquals(0, database.deviceNodeDao().findBySession(sessionId).size)
        assertEquals(0, database.exportRecordDao().findBySession(sessionId).size)
        assertEquals(null, database.trainingSessionDao().findById(sessionId))
    }
}
