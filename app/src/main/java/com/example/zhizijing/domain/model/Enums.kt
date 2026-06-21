package com.example.zhizijing.domain.model

enum class DeviceRole {
    HOST,
    FRONT_CAMERA,
    SIDE_CAMERA,
    BACKUP_CAMERA,
    UNKNOWN,
}

enum class ActionType(
    val displayName: String,
    val isTrainingAction: Boolean,
    val isCountBased: Boolean,
    val isHoldBased: Boolean,
    val supportsDetailedScore: Boolean,
    val defaultTargetText: String,
) {
    SQUAT("深蹲", true, true, false, true, "建议目标：8 次，重点关注下蹲深度、膝盖轨迹和躯干角度。"),
    JUMPING_JACK("开合跳", true, true, false, false, "建议目标：20 次，重点关注手脚开合完整度和节奏。"),
    PUSH_UP("俯卧撑", true, true, false, false, "建议目标：10 次，先完成基础计数，后续真机调优肘角阈值。"),
    SIT_UP("仰卧起坐", true, true, false, false, "建议目标：10 次，先完成基础计数，后续真机调优躯干抬起阈值。"),
    LUNGE("弓步蹲", true, true, false, false, "建议目标：10 次，可左右交替，也可单侧连续完成。"),
    HIGH_KNEES("高抬腿", true, true, false, false, "建议目标：30 次，重点统计左右膝交替抬高次数。"),
    PLANK("平板支撑", true, false, true, false, "建议目标：30 秒，按保持时长统计，不转换成次数。"),
    LATERAL_RAISE("侧平举", true, true, false, false, "建议目标：12 次，双臂从身体两侧抬到肩部附近再放下。"),
    MOUNTAIN_CLIMBER("登山跑", true, true, false, false, "建议目标：30 次，统计俯撑姿态下左右腿交替次数。"),
    JUMP_ROPE("跳绳动作", true, true, false, false, "建议目标：40 次，使用小幅纵跳和手腕甩绳动作近似识别。"),
    STANDING_FORWARD_BEND("站姿体前屈", true, true, false, false, "建议目标：10 次，双脚站稳，俯身下探到腿部附近后再站直。"),
    STANDING("站立", false, false, false, false, "非训练状态：用于识别用户准备姿态。"),
    UNKNOWN("未知动作", false, false, false, false, "非训练状态：关键点不足或动作不稳定时使用。");

    companion object {
        val trainingActions: List<ActionType>
            get() = entries.filter { it.isTrainingAction }

        val selectableActions: List<ActionType>
            get() = listOf(UNKNOWN) + trainingActions

        fun trainingActionNamesText(): String =
            trainingActions.joinToString("、") { it.displayName }

        fun fromName(name: String?): ActionType =
            entries.firstOrNull { it.name == name } ?: UNKNOWN

        fun fromNameOrUnknown(name: String?): ActionType =
            entries.firstOrNull { it.name == name } ?: UNKNOWN
    }
}

enum class TrainingState {
    IDLE,
    PREPARING,
    ANALYZING,
    FINISHED,
    ERROR,
}

enum class ProblemType(val displayName: String) {
    NONE("暂无明显问题"),
    SQUAT_DEPTH_NOT_ENOUGH("下蹲深度不足"),
    KNEE_INWARD("膝盖内扣"),
    BACK_LEAN_TOO_MUCH("背部前倾明显"),
    ASYMMETRY("左右不对称"),
    LOW_CONFIDENCE("人体识别不稳定"),
    RHYTHM_ABNORMAL("节奏异常"),
}

enum class ClassifierSource {
    RULE_BASED,
    TFLITE,
}

enum class SquatStage {
    STANDING,
    DESCENDING,
    SQUATTING,
    RISING,
}

enum class JumpingJackStage {
    CLOSED,
    OPENING,
    OPEN,
    CLOSING,
}
