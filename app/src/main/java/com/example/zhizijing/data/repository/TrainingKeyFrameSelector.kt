package com.example.zhizijing.data.repository

import com.example.zhizijing.data.entity.PoseFrameEntity

object TrainingKeyFrameSelector {
    fun select(
        rawActionType: String?,
        actionIndex: Int,
        totalCount: Int,
        frames: List<PoseFrameEntity>,
    ): PoseFrameEntity? {
        if (frames.isEmpty()) return null
        val orderedFrames = frames.sortedBy { frame -> frame.timestampMs }
        if (totalCount <= 0) return orderedFrames[orderedFrames.size / 2]

        val safeActionIndex = actionIndex.coerceIn(1, totalCount)
        val startIndex = ((safeActionIndex - 1) * orderedFrames.size / totalCount)
            .coerceIn(0, orderedFrames.lastIndex)
        val endExclusive = (safeActionIndex * orderedFrames.size / totalCount)
            .coerceIn(startIndex + 1, orderedFrames.size)
        return chooseRepresentative(rawActionType, orderedFrames.subList(startIndex, endExclusive))
    }

    fun selectInTimeRange(
        rawActionType: String?,
        startTimeMs: Long,
        endTimeMs: Long,
        frames: List<PoseFrameEntity>,
    ): PoseFrameEntity? {
        if (frames.isEmpty()) return null
        val candidates = frames
            .asSequence()
            .filter { frame -> frame.timestampMs in startTimeMs..endTimeMs }
            .sortedBy { frame -> frame.timestampMs }
            .toList()
        if (candidates.isEmpty()) return null
        return chooseRepresentative(rawActionType, candidates)
    }

    private fun chooseRepresentative(
        rawActionType: String?,
        candidates: List<PoseFrameEntity>,
    ): PoseFrameEntity =
        candidates
            .mapNotNull { frame ->
                TrainingPoseMetricsExtractor.representativeScore(rawActionType, frame)
                    ?.let { score -> frame to score }
            }
            .maxByOrNull { (_, score) -> score }
            ?.first
            ?: candidates[candidates.size / 2]
}
