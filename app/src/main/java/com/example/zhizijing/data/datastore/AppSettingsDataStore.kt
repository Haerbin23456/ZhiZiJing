package com.example.zhizijing.data.datastore

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.zhizijing.domain.model.ActionType
import com.example.zhizijing.domain.rule.PoseAnalysisConfig
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

// 应用轻量配置文件
private val Context.zhizijingSettings by preferencesDataStore(name = "zhizijing_settings")

data class AppSettings(
    val isLoggedIn: Boolean,
    val lastUserId: Long,
    val lastUsername: String,
    val defaultActionType: ActionType,
    val lastRoomCode: String,
    val minPoseConfidence: Float,
    val squatDepthThreshold: Float,
    val backLeanThreshold: Float,
    val actionRecognitionThreshold: Float,
    val defaultEvaluationRuleSet: String,
    val jumpingJackOpenRatio: Float,
    val jumpingJackClosedRatio: Float,
    val jumpingJackWristUpMargin: Float,
    val jumpingJackWristDownMargin: Float,
    val minSquatRepDurationMs: Long,
    val maxSquatRepDurationMs: Long,
    val showSkeletonOverlay: Boolean,
    val saveVideoEnabled: Boolean,
    val defaultExportDir: String,
)

object AppSettingsDataStore {
    const val DEFAULT_EXPORT_DIR = "training/reports"
    const val DEFAULT_EVALUATION_RULE_SET = "RULE_BASED_11_ACTIONS"

    private val isLoggedInKey = booleanPreferencesKey("is_logged_in")
    private val lastUserIdKey = longPreferencesKey("last_user_id")
    private val lastUsernameKey = stringPreferencesKey("last_username")
    private val defaultActionTypeKey = stringPreferencesKey("default_action_type")
    private val lastRoomCodeKey = stringPreferencesKey("last_room_code")
    private val minPoseConfidenceKey = floatPreferencesKey("min_pose_confidence")
    private val squatDepthThresholdKey = floatPreferencesKey("squat_depth_threshold")
    private val backLeanThresholdKey = floatPreferencesKey("back_lean_threshold")
    private val actionRecognitionThresholdKey = floatPreferencesKey("action_recognition_threshold")
    private val defaultEvaluationRuleSetKey = stringPreferencesKey("default_evaluation_rule_set")
    private val jumpingJackOpenRatioKey = floatPreferencesKey("jumping_jack_open_ratio")
    private val jumpingJackClosedRatioKey = floatPreferencesKey("jumping_jack_closed_ratio")
    private val jumpingJackWristUpMarginKey = floatPreferencesKey("jumping_jack_wrist_up_margin")
    private val jumpingJackWristDownMarginKey = floatPreferencesKey("jumping_jack_wrist_down_margin")
    private val minSquatRepDurationMsKey = longPreferencesKey("min_squat_rep_duration_ms")
    private val maxSquatRepDurationMsKey = longPreferencesKey("max_squat_rep_duration_ms")
    private val showSkeletonOverlayKey = booleanPreferencesKey("show_skeleton_overlay")
    private val saveVideoEnabledKey = booleanPreferencesKey("save_video_enabled")
    private val defaultExportDirKey = stringPreferencesKey("default_export_dir")
    private val defaultAnalysisConfig = PoseAnalysisConfig()

    // 设置读取统一补默认值
    fun load(context: Context): AppSettings = runBlocking {
        val preferences = context.applicationContext.zhizijingSettings.data.first()
        AppSettings(
            isLoggedIn = preferences[isLoggedInKey] ?: false,
            lastUserId = preferences[lastUserIdKey] ?: 0L,
            lastUsername = preferences[lastUsernameKey].orEmpty(),
            defaultActionType = preferences[defaultActionTypeKey]
                ?.let { ActionType.fromNameOrUnknown(it) }
                ?: ActionType.UNKNOWN,
            lastRoomCode = preferences[lastRoomCodeKey].orEmpty(),
            minPoseConfidence = defaultAnalysisConfig.minPoseConfidence,
            squatDepthThreshold = defaultAnalysisConfig.squatDepthThreshold,
            backLeanThreshold = defaultAnalysisConfig.backLeanAngleThreshold,
            actionRecognitionThreshold = defaultAnalysisConfig.actionRecognitionThreshold,
            defaultEvaluationRuleSet = DEFAULT_EVALUATION_RULE_SET,
            jumpingJackOpenRatio = defaultAnalysisConfig.jumpingJackOpenAnkleShoulderRatio,
            jumpingJackClosedRatio = defaultAnalysisConfig.jumpingJackClosedAnkleShoulderRatio,
            jumpingJackWristUpMargin = defaultAnalysisConfig.jumpingJackWristUpMargin,
            jumpingJackWristDownMargin = defaultAnalysisConfig.jumpingJackWristDownMargin,
            minSquatRepDurationMs = defaultAnalysisConfig.minSquatRepDurationMs,
            maxSquatRepDurationMs = defaultAnalysisConfig.maxSquatRepDurationMs,
            showSkeletonOverlay = preferences[showSkeletonOverlayKey] ?: true,
            saveVideoEnabled = preferences[saveVideoEnabledKey] ?: false,
            defaultExportDir = preferences[defaultExportDirKey] ?: DEFAULT_EXPORT_DIR,
        )
    }

    fun saveLogin(context: Context, userId: Long, username: String) = runBlocking {
        context.applicationContext.zhizijingSettings.edit { preferences ->
            preferences[isLoggedInKey] = true
            preferences[lastUserIdKey] = userId
            preferences[lastUsernameKey] = username
        }
    }

    fun saveDefaultActionType(context: Context, actionType: ActionType) = runBlocking {
        context.applicationContext.zhizijingSettings.edit { preferences ->
            preferences[defaultActionTypeKey] = actionType.name
        }
    }

    fun saveLastRoomCode(context: Context, roomCode: String) = runBlocking {
        context.applicationContext.zhizijingSettings.edit { preferences ->
            preferences[lastRoomCodeKey] = roomCode
        }
    }

    fun saveDisplaySettings(
        context: Context,
        showSkeletonOverlay: Boolean,
    ) = runBlocking {
        context.applicationContext.zhizijingSettings.edit { preferences ->
            preferences.removeLegacyAnalysisOverrides()
            preferences[showSkeletonOverlayKey] = showSkeletonOverlay
        }
    }

    fun saveOutputSettings(
        context: Context,
        saveVideoEnabled: Boolean,
        defaultExportDir: String,
    ) = runBlocking {
        context.applicationContext.zhizijingSettings.edit { preferences ->
            preferences[saveVideoEnabledKey] = saveVideoEnabled
            preferences[defaultExportDirKey] = defaultExportDir.trim()
        }
    }

    fun resetAnalysisSettings(context: Context) = runBlocking {
        context.applicationContext.zhizijingSettings.edit { preferences ->
            preferences.removeLegacyAnalysisOverrides()
            preferences.remove(showSkeletonOverlayKey)
        }
    }

    fun clearLogin(context: Context) = runBlocking {
        context.applicationContext.zhizijingSettings.edit { preferences ->
            preferences[isLoggedInKey] = false
            preferences.remove(lastUserIdKey)
            preferences.remove(lastUsernameKey)
        }
    }

    private fun MutablePreferences.removeLegacyAnalysisOverrides() {
        remove(minPoseConfidenceKey)
        remove(squatDepthThresholdKey)
        remove(backLeanThresholdKey)
        remove(actionRecognitionThresholdKey)
        remove(defaultEvaluationRuleSetKey)
        remove(jumpingJackOpenRatioKey)
        remove(jumpingJackClosedRatioKey)
        remove(jumpingJackWristUpMarginKey)
        remove(jumpingJackWristDownMarginKey)
        remove(minSquatRepDurationMsKey)
        remove(maxSquatRepDurationMsKey)
    }
}

fun AppSettings.toPoseAnalysisConfig(): PoseAnalysisConfig =
    PoseAnalysisConfig()
