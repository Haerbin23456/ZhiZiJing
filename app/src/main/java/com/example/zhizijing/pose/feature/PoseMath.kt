package com.example.zhizijing.pose.feature

import com.example.zhizijing.pose.model.LandmarkPoint
import kotlin.math.acos
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.pow
import kotlin.math.sqrt

object PoseMath {
    fun distance(a: LandmarkPoint, b: LandmarkPoint): Float {
        val dx = a.x - b.x
        val dy = a.y - b.y
        return sqrt(dx * dx + dy * dy)
    }

    fun horizontalDistance(a: LandmarkPoint, b: LandmarkPoint): Float =
        abs(a.x - b.x)

    fun normalizedDistance(
        a: LandmarkPoint,
        b: LandmarkPoint,
        reference: Float,
    ): Float =
        if (reference <= 0f) 0f else distance(a, b) / reference

    fun angle(a: LandmarkPoint, vertex: LandmarkPoint, c: LandmarkPoint): Float {
        val avx = a.x - vertex.x
        val avy = a.y - vertex.y
        val cvx = c.x - vertex.x
        val cvy = c.y - vertex.y
        val dot = avx * cvx + avy * cvy
        val lenA = sqrt(avx.pow(2) + avy.pow(2))
        val lenC = sqrt(cvx.pow(2) + cvy.pow(2))
        if (lenA == 0f || lenC == 0f) return 0f
        val cos = (dot / (lenA * lenC)).coerceIn(-1f, 1f)
        return Math.toDegrees(acos(cos).toDouble()).toFloat()
    }

    fun hasMinimumConfidence(points: Iterable<LandmarkPoint>, minConfidence: Float): Boolean =
        points.all { it.confidence >= minConfidence }

    fun midpoint(name: String, a: LandmarkPoint, b: LandmarkPoint): LandmarkPoint =
        LandmarkPoint(
            name = name,
            x = (a.x + b.x) / 2f,
            y = (a.y + b.y) / 2f,
            z = listOfNotNull(a.z, b.z).takeIf { it.isNotEmpty() }?.average()?.toFloat(),
            confidence = minOf(a.confidence, b.confidence),
        )

    fun trunkLeanAngleFromVertical(shoulderMid: LandmarkPoint, hipMid: LandmarkPoint): Float {
        val dx = shoulderMid.x - hipMid.x
        val dy = shoulderMid.y - hipMid.y
        if (dx == 0f && dy == 0f) return 0f
        return Math.toDegrees(atan2(abs(dx), abs(dy)).toDouble()).toFloat()
    }
}
