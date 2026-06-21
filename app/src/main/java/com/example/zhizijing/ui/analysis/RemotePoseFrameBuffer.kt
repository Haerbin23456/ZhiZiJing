package com.example.zhizijing.ui.analysis

import com.example.zhizijing.pose.model.PoseFrame
import java.util.ArrayDeque

class RemotePoseFrameBuffer(
    private val maxFrames: Int = DEFAULT_MAX_FRAMES,
) {
    private val frames = ArrayDeque<PoseFrame>()

    @Synchronized
    fun add(frame: PoseFrame) {
        if (maxFrames <= 0) return
        while (frames.size >= maxFrames) {
            frames.removeFirst()
        }
        frames.addLast(frame)
    }

    @Synchronized
    fun snapshot(): List<PoseFrame> =
        frames.toList()

    @Synchronized
    fun size(): Int =
        frames.size

    companion object {
        const val DEFAULT_MAX_FRAMES = 240
    }
}
