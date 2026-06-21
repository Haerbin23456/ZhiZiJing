package com.example.zhizijing.nearby.message

import com.example.zhizijing.pose.model.PoseFrame
import com.google.gson.Gson

object NearbyPoseFrameCodec {
    private val gson = Gson()

    fun encode(frame: PoseFrame): String =
        gson.toJson(frame)

    fun decode(json: String?): PoseFrame? {
        if (json.isNullOrBlank()) return null
        return runCatching {
            gson.fromJson(json, PoseFrame::class.java)
        }.getOrNull()
    }
}
