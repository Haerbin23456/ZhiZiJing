package com.example.zhizijing.domain.model

data class ActionClassificationResult(
    val actionType: ActionType,
    val confidence: Float,
    val windowStartMs: Long,
    val windowEndMs: Long,
    val source: ClassifierSource,
)

data class SquatAnalysisResult(
    val totalCount: Int,
    val currentStage: SquatStage,
    val score: Float,
    val kneeAngle: Float?,
    val trunkAngle: Float?,
    val depthLevel: String?,
    val problemType: ProblemType,
    val suggestion: String?,
)

data class JumpingJackResult(
    val totalCount: Int,
    val durationMs: Long,
    val averageTempo: Float,
    val lostFrameCount: Int,
    val currentStage: JumpingJackStage = JumpingJackStage.CLOSED,
)

data class BasicActionAnalysisResult(
    val actionType: ActionType,
    val totalCount: Int,
    val holdDurationMs: Long,
    val stageText: String,
    val problemType: ProblemType,
    val suggestion: String,
)

data class TrainingSummary(
    val sessionId: Long = 0L,
    val userId: Long = 0L,
    val actionType: ActionType,
    val totalCount: Int,
    val averageScore: Float?,
    val durationMs: Long = 0L,
    val mainProblem: ProblemType,
    val suggestion: String?,
    val timestampMs: Long = System.currentTimeMillis(),
    val reportPath: String? = null,
    val averagePoseConfidence: Float? = null,
)
