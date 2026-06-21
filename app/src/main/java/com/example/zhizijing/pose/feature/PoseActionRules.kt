package com.example.zhizijing.pose.feature

import com.example.zhizijing.domain.model.ActionType
import com.example.zhizijing.domain.rule.PoseAnalysisConfig
import com.example.zhizijing.pose.model.LandmarkPoint
import com.example.zhizijing.pose.model.PoseFrame
import kotlin.math.abs
import kotlin.math.max

class PoseActionFeatures(
    val frame: PoseFrame,
    private val config: PoseAnalysisConfig,
) {
    private val landmarks = frame.landmarks

    val leftShoulder: LandmarkPoint? = landmarks["LEFT_SHOULDER"]
    val rightShoulder: LandmarkPoint? = landmarks["RIGHT_SHOULDER"]
    val leftElbow: LandmarkPoint? = landmarks["LEFT_ELBOW"]
    val rightElbow: LandmarkPoint? = landmarks["RIGHT_ELBOW"]
    val leftWrist: LandmarkPoint? = landmarks["LEFT_WRIST"]
    val rightWrist: LandmarkPoint? = landmarks["RIGHT_WRIST"]
    val leftHip: LandmarkPoint? = landmarks["LEFT_HIP"]
    val rightHip: LandmarkPoint? = landmarks["RIGHT_HIP"]
    val leftKnee: LandmarkPoint? = landmarks["LEFT_KNEE"]
    val rightKnee: LandmarkPoint? = landmarks["RIGHT_KNEE"]
    val leftAnkle: LandmarkPoint? = landmarks["LEFT_ANKLE"]
    val rightAnkle: LandmarkPoint? = landmarks["RIGHT_ANKLE"]

    val shoulderMid: LandmarkPoint? = midpoint("SHOULDER_MID", leftShoulder, rightShoulder)
    val hipMid: LandmarkPoint? = midpoint("HIP_MID", leftHip, rightHip)
    val kneeMid: LandmarkPoint? = midpoint("KNEE_MID", leftKnee, rightKnee)
    val ankleMid: LandmarkPoint? = midpoint("ANKLE_MID", leftAnkle, rightAnkle)

    val shoulderWidth: Float? = distanceX(leftShoulder, rightShoulder)
    val hipWidth: Float? = distanceX(leftHip, rightHip)
    val bodyHeight: Float? = verticalRange(
        listOfNotNull(leftShoulder, rightShoulder, leftHip, rightHip, leftKnee, rightKnee, leftAnkle, rightAnkle)
    )

    val leftKneeAngle: Float? = angle(leftHip, leftKnee, leftAnkle)
    val rightKneeAngle: Float? = angle(rightHip, rightKnee, rightAnkle)
    val leftElbowAngle: Float? = angle(leftShoulder, leftElbow, leftWrist)
    val rightElbowAngle: Float? = angle(rightShoulder, rightElbow, rightWrist)
    val leftHipAngle: Float? = angle(leftShoulder, leftHip, leftKnee)
    val rightHipAngle: Float? = angle(rightShoulder, rightHip, rightKnee)
    val trunkAngleFromVertical: Float? = shoulderMid?.let { shoulder ->
        hipMid?.let { hip -> PoseMath.trunkLeanAngleFromVertical(shoulder, hip) }
    }

    val ankleSpreadRatio: Float? = ratio(distanceX(leftAnkle, rightAnkle), shoulderWidth)
    val wristSpreadRatio: Float? = ratio(distanceX(leftWrist, rightWrist), shoulderWidth)

    fun hasMinimum(names: Collection<String>): Boolean {
        val points = names.mapNotNull { landmarks[it] }
        val relaxedConfidence = config.minPoseConfidence * RELAXED_CONFIDENCE_FACTOR
        val confidentPointCount = points.count { point -> point.confidence >= relaxedConfidence }
        val averageConfidence = points
            .takeIf { it.isNotEmpty() }
            ?.map { point -> point.confidence }
            ?.average()
            ?.toFloat()
            ?: 0f
        return points.size == names.size &&
            frame.overallConfidence >= relaxedConfidence &&
            confidentPointCount >= (names.size - 1).coerceAtLeast(1) &&
            averageConfidence >= relaxedConfidence
    }

    fun isSquatSignal(): Boolean {
        val hip = hipMid ?: return false
        val knee = kneeMid ?: return false
        val avgKnee = average(leftKneeAngle, rightKneeAngle) ?: return false
        val hipNearOrBelowKnee = hip.y >= knee.y - max(config.squatDepthThreshold, 0.08f)
        return hasMinimum(LOWER_BODY_NAMES) &&
            !isBodyHorizontal() &&
            !isPlankSignal() &&
            !isProneHoldCandidate() &&
            !isLungeSignal() &&
            hipNearOrBelowKnee &&
            avgKnee < 178f
    }

    fun isJumpingJackOpen(): Boolean {
        val shoulders = shoulderMid ?: return false
        val ratio = ankleSpreadRatio ?: return false
        val leftWristUp = leftWrist?.let { wrist -> wrist.y < shoulders.y - config.jumpingJackWristUpMargin } ?: false
        val rightWristUp = rightWrist?.let { wrist -> wrist.y < shoulders.y - config.jumpingJackWristUpMargin } ?: false
        return hasMinimum(JUMPING_JACK_NAMES) &&
            ratio >= config.jumpingJackOpenAnkleShoulderRatio &&
            (leftWristUp || rightWristUp)
    }

    fun isStandingSignal(): Boolean {
        val hip = hipMid ?: return false
        val knee = kneeMid ?: return false
        val avgKnee = average(leftKneeAngle, rightKneeAngle) ?: return false
        return hasMinimum(LOWER_BODY_NAMES) &&
            avgKnee >= 155f &&
            hip.y < knee.y - 0.10f
    }

    fun isBodyHorizontal(): Boolean {
        val span = horizontalBodySpan() ?: return false
        return hasMinimum(HORIZONTAL_BODY_NAMES) &&
            span.horizontal > 0.24f &&
            span.vertical <= 0.20f
    }

    fun isPushUpSignal(): Boolean {
        val avgElbow = average(leftElbowAngle, rightElbowAngle) ?: return false
        return isBodyHorizontal() && hasMinimum(PUSH_UP_NAMES) && avgElbow < 155f
    }

    fun isProneActionBasePose(): Boolean =
        hasMinimum(PRONE_HOLD_NAMES) &&
            (isBodyHorizontal() || isHorizontalHoldPose() || isProneHoldCandidate())

    fun isPlankSignal(): Boolean {
        val avgElbow = average(leftElbowAngle, rightElbowAngle)
        return (isHorizontalHoldPose() || isProneHoldCandidate()) &&
            (avgElbow == null || avgElbow >= PLANK_MIN_ELBOW_ANGLE) &&
            !hasAlternatingKneeTuck()
    }

    fun isMountainClimberSignal(): Boolean {
        val hip = hipMid ?: return false
        val leftNear = leftKnee?.let { knee -> knee.y <= hip.y + 0.13f } ?: false
        val rightNear = rightKnee?.let { knee -> knee.y <= hip.y + 0.13f } ?: false
        val asymmetric = abs((leftKnee?.y ?: return false) - (rightKnee?.y ?: return false)) > 0.06f
        return isBodyHorizontal() && asymmetric && (leftNear || rightNear)
    }

    fun isSitUpSignal(): Boolean {
        val shoulder = shoulderMid ?: return false
        val hip = hipMid ?: return false
        val knee = kneeMid ?: return false
        val avgKnee = average(leftKneeAngle, rightKneeAngle) ?: return false
        val avgHip = average(leftHipAngle, rightHipAngle)
        val torsoVerticalSpan = hip.y - shoulder.y
        val kneeBelowHip = knee.y - hip.y
        val squatBottomLike = hip.y >= knee.y - config.squatDepthThreshold
        return hasMinimum(SIT_UP_NAMES) &&
            avgKnee < 170f &&
            torsoVerticalSpan in 0.08f..0.36f &&
            kneeBelowHip >= 0.04f &&
            !squatBottomLike &&
            (avgHip == null || avgHip < 170f)
    }

    fun isLungeSignal(): Boolean {
        val left = leftKneeAngle ?: return false
        val right = rightKneeAngle ?: return false
        val hip = hipMid ?: return false
        val knee = kneeMid ?: return false
        val hasBentLeg = left < LUNGE_BENT_KNEE_MAX_ANGLE || right < LUNGE_BENT_KNEE_MAX_ANGLE
        val hipLowered = hip.y > knee.y - 0.24f
        return hasMinimum(LOWER_BODY_NAMES) &&
            !isBodyHorizontal() &&
            isUprightEnough() &&
            hasBentLeg &&
            hipLowered &&
            raisedKneeSide(high = true) == null &&
            hasLungeAsymmetry(left, right)
    }

    fun raisedKneeSide(high: Boolean): String? {
        val hip = hipMid ?: return null
        val leftDelta = leftKnee?.let { it.y - hip.y }
        val rightDelta = rightKnee?.let { it.y - hip.y }
        if (leftDelta == null || rightDelta == null) return null
        val threshold = if (high) 0.06f else 0.20f
        val lowThreshold = 0.03f
        val sideGap = 0.03f
        return when {
            high && leftDelta <= threshold && leftDelta < rightDelta - sideGap -> "LEFT"
            high && rightDelta <= threshold && rightDelta < leftDelta - sideGap -> "RIGHT"
            !high && leftDelta in lowThreshold..threshold && leftDelta < rightDelta - sideGap -> "LEFT"
            !high && rightDelta in lowThreshold..threshold && rightDelta < leftDelta - sideGap -> "RIGHT"
            else -> null
        }
    }

    fun isHighKneesSignal(): Boolean =
        hasMinimum(LOWER_BODY_NAMES) && raisedKneeSide(high = true) != null && isUprightEnough()

    fun isStandingForwardBendSignal(): Boolean {
        val shoulder = shoulderMid ?: return false
        val hip = hipMid ?: return false
        val knee = kneeMid ?: return false
        val avgKnee = average(leftKneeAngle, rightKneeAngle) ?: return false
        val avgHip = average(leftHipAngle, rightHipAngle) ?: return false
        val leftWristPoint = leftWrist ?: return false
        val rightWristPoint = rightWrist ?: return false
        val torsoFolded = hip.y - shoulder.y <= STANDING_FORWARD_BEND_MAX_TORSO_VERTICAL_SPAN ||
            shoulder.y >= hip.y - STANDING_FORWARD_BEND_SHOULDER_NEAR_HIP_MARGIN
        val handsDown = leftWristPoint.y >= knee.y - STANDING_FORWARD_BEND_WRIST_ABOVE_KNEE_MARGIN &&
            rightWristPoint.y >= knee.y - STANDING_FORWARD_BEND_WRIST_ABOVE_KNEE_MARGIN
        val kneesStraight = avgKnee >= STANDING_FORWARD_BEND_MIN_KNEE_ANGLE
        val hipsAboveKnees = hip.y < knee.y - STANDING_FORWARD_BEND_HIP_ABOVE_KNEE_MARGIN
        val hipsFolded = avgHip <= STANDING_FORWARD_BEND_MAX_HIP_ANGLE
        val feetStable = (ankleSpreadRatio ?: 0f) <= STANDING_FORWARD_BEND_MAX_ANKLE_SPREAD_RATIO
        return hasMinimum(STANDING_FORWARD_BEND_NAMES) &&
            !isBodyHorizontal() &&
            kneesStraight &&
            hipsAboveKnees &&
            hipsFolded &&
            torsoFolded &&
            handsDown &&
            feetStable
    }

    fun isStandingForwardBendRestSignal(): Boolean {
        val hip = hipMid ?: return false
        val knee = kneeMid ?: return false
        val avgKnee = average(leftKneeAngle, rightKneeAngle) ?: return false
        val avgHip = average(leftHipAngle, rightHipAngle) ?: return false
        return hasMinimum(STANDING_FORWARD_BEND_NAMES) &&
            isUprightEnough() &&
            avgKnee >= STANDING_FORWARD_BEND_REST_MIN_KNEE_ANGLE &&
            avgHip >= STANDING_FORWARD_BEND_REST_MIN_HIP_ANGLE &&
            hip.y < knee.y - STANDING_FORWARD_BEND_HIP_ABOVE_KNEE_MARGIN
    }

    fun isJumpRopePose(): Boolean {
        val ratio = ankleSpreadRatio ?: return false
        return hasMinimum(JUMP_ROPE_NAMES) &&
            ratio <= 1.30f &&
            isJumpRopeHandPose() &&
            isJumpRopeElbowPose() &&
            isUprightEnough()
    }

    fun isJumpRopeHandPose(): Boolean {
        val hip = hipMid ?: return false
        val shoulder = shoulderMid ?: return false
        val left = leftWrist ?: return false
        val right = rightWrist ?: return false
        return left.x < hip.x - 0.05f &&
            right.x > hip.x + 0.05f &&
            left.y in (shoulder.y + 0.05f)..(hip.y + 0.20f) &&
            right.y in (shoulder.y + 0.05f)..(hip.y + 0.20f)
    }

    fun isJumpRopeElbowPose(): Boolean {
        val leftShoulderPoint = leftShoulder ?: return false
        val rightShoulderPoint = rightShoulder ?: return false
        val leftElbowPoint = leftElbow ?: return false
        val rightElbowPoint = rightElbow ?: return false
        val leftWristPoint = leftWrist ?: return false
        val rightWristPoint = rightWrist ?: return false
        val width = shoulderWidth ?: return false
        val maxForearmHorizontalOffset = width * JUMP_ROPE_MAX_FOREARM_HORIZONTAL_RATIO
        val leftForearmVertical = abs(leftWristPoint.x - leftElbowPoint.x) <= maxForearmHorizontalOffset &&
            leftElbowPoint.y >= leftShoulderPoint.y + JUMP_ROPE_MIN_ELBOW_BELOW_SHOULDER &&
            leftWristPoint.y >= leftElbowPoint.y - JUMP_ROPE_WRIST_ABOVE_ELBOW_TOLERANCE
        val rightForearmVertical = abs(rightWristPoint.x - rightElbowPoint.x) <= maxForearmHorizontalOffset &&
            rightElbowPoint.y >= rightShoulderPoint.y + JUMP_ROPE_MIN_ELBOW_BELOW_SHOULDER &&
            rightWristPoint.y >= rightElbowPoint.y - JUMP_ROPE_WRIST_ABOVE_ELBOW_TOLERANCE
        return leftForearmVertical && rightForearmVertical
    }

    fun isLateralRaiseSignal(): Boolean {
        val leftShoulderPoint = leftShoulder ?: return false
        val rightShoulderPoint = rightShoulder ?: return false
        val leftWristPoint = leftWrist ?: return false
        val rightWristPoint = rightWrist ?: return false
        val shoulder = shoulderMid ?: return false
        val hip = hipMid ?: return false
        val wristSpread = wristSpreadRatio ?: return false
        val torsoHeight = (hip.y - shoulder.y).coerceAtLeast(0.12f)
        val leftArmAngle = angle(leftHip, leftShoulder, leftWrist) ?: return false
        val rightArmAngle = angle(rightHip, rightShoulder, rightWrist) ?: return false
        val leftWristOut = leftWristPoint.x < leftShoulderPoint.x - LATERAL_RAISE_MIN_WRIST_OUT
        val rightWristOut = rightWristPoint.x > rightShoulderPoint.x + LATERAL_RAISE_MIN_WRIST_OUT
        val wristsClearlyOut = leftWristOut && rightWristOut
        val armsOpenByAngle = leftArmAngle >= LATERAL_RAISE_MIN_ARM_OPEN_ANGLE &&
            rightArmAngle >= LATERAL_RAISE_MIN_ARM_OPEN_ANGLE
        val armsClearlyOpenByAngle = leftArmAngle >= LATERAL_RAISE_CLEAR_ARM_OPEN_ANGLE &&
            rightArmAngle >= LATERAL_RAISE_CLEAR_ARM_OPEN_ANGLE
        val armsOpenEnough = armsClearlyOpenByAngle || (armsOpenByAngle && wristsClearlyOut)
        val armsNotFullyOverhead = leftArmAngle <= LATERAL_RAISE_MAX_ARM_OPEN_ANGLE &&
            rightArmAngle <= LATERAL_RAISE_MAX_ARM_OPEN_ANGLE
        val maxRaisedWristY = hip.y + torsoHeight * LATERAL_RAISE_WRIST_BELOW_HIP_MARGIN_RATIO
        val wristsNotAtRest = leftWristPoint.y <= maxRaisedWristY &&
            rightWristPoint.y <= maxRaisedWristY
        val notOverhead = leftWristPoint.y >= shoulder.y - LATERAL_RAISE_OVERHEAD_MARGIN &&
            rightWristPoint.y >= shoulder.y - LATERAL_RAISE_OVERHEAD_MARGIN
        val feetNotJumpingJack = (ankleSpreadRatio ?: 0f) <= LATERAL_RAISE_MAX_ANKLE_SPREAD_RATIO
        return hasMinimum(LATERAL_RAISE_REQUIRED_NAMES) &&
            isUprightEnough() &&
            wristSpread >= LATERAL_RAISE_MIN_WRIST_SPREAD_RATIO &&
            armsOpenEnough &&
            armsNotFullyOverhead &&
            wristsNotAtRest &&
            notOverhead &&
            feetNotJumpingJack
    }

    fun isLateralRaiseRestSignal(): Boolean {
        val leftWristPoint = leftWrist ?: return false
        val rightWristPoint = rightWrist ?: return false
        val shoulder = shoulderMid ?: return false
        val hip = hipMid ?: return false
        val wristSpread = wristSpreadRatio ?: return false
        val torsoHeight = (hip.y - shoulder.y).coerceAtLeast(0.12f)
        val leftArmAngle = angle(leftHip, leftShoulder, leftWrist) ?: return false
        val rightArmAngle = angle(rightHip, rightShoulder, rightWrist) ?: return false
        val armsBackToSides = leftArmAngle <= LATERAL_RAISE_REST_MAX_ARM_OPEN_ANGLE &&
            rightArmAngle <= LATERAL_RAISE_REST_MAX_ARM_OPEN_ANGLE
        val wristsLowered = leftWristPoint.y >= shoulder.y + torsoHeight * LATERAL_RAISE_REST_WRIST_MIN_Y_RATIO &&
            rightWristPoint.y >= shoulder.y + torsoHeight * LATERAL_RAISE_REST_WRIST_MIN_Y_RATIO
        val feetNotJumpingJack = (ankleSpreadRatio ?: 0f) <= LATERAL_RAISE_MAX_ANKLE_SPREAD_RATIO
        return hasMinimum(LATERAL_RAISE_REQUIRED_NAMES) &&
            isUprightEnough() &&
            armsBackToSides &&
            wristsLowered &&
            wristSpread <= LATERAL_RAISE_REST_MAX_WRIST_SPREAD_RATIO &&
            feetNotJumpingJack
    }

    fun lungeSide(): String? {
        val left = leftKneeAngle ?: return null
        val right = rightKneeAngle ?: return null
        return when {
            left < LUNGE_BENT_KNEE_MAX_ANGLE && right - left >= LUNGE_ANGLE_GAP_MIN -> "LEFT"
            right < LUNGE_BENT_KNEE_MAX_ANGLE && left - right >= LUNGE_ANGLE_GAP_MIN -> "RIGHT"
            else -> null
        }
    }

    fun activeSideFor(actionType: ActionType): String? =
        when (actionType) {
            ActionType.HIGH_KNEES -> raisedKneeSide(high = true)
            ActionType.MOUNTAIN_CLIMBER -> {
                val hip = hipMid ?: return null
                val leftNear = leftKnee?.let { it.y <= hip.y + 0.13f } ?: false
                val rightNear = rightKnee?.let { it.y <= hip.y + 0.13f } ?: false
                when {
                    leftNear && !rightNear -> "LEFT"
                    rightNear && !leftNear -> "RIGHT"
                    else -> null
                }
            }
            ActionType.LUNGE -> lungeSide()
            else -> null
        }

    fun isActiveFor(actionType: ActionType): Boolean =
        when (actionType) {
            ActionType.SQUAT -> isSquatSignal()
            ActionType.JUMPING_JACK -> isJumpingJackOpen()
            ActionType.PUSH_UP -> isPushUpSignal()
            ActionType.SIT_UP -> isSitUpSignal()
            ActionType.LUNGE -> isLungeSignal()
            ActionType.HIGH_KNEES -> isHighKneesSignal()
            ActionType.PLANK -> isPlankSignal()
            ActionType.LATERAL_RAISE -> isLateralRaiseSignal()
            ActionType.MOUNTAIN_CLIMBER -> isMountainClimberSignal()
            ActionType.JUMP_ROPE -> isJumpRopePose()
            ActionType.STANDING_FORWARD_BEND -> isStandingForwardBendSignal()
            else -> false
        }

    private fun isUprightEnough(): Boolean =
        (trunkAngleFromVertical ?: 0f) < 35f

    private fun isHorizontalHoldPose(): Boolean {
        val span = horizontalBodySpan() ?: return false
        return hasMinimum(HORIZONTAL_BODY_NAMES) &&
            span.horizontal > 0.24f &&
            span.vertical <= 0.32f &&
            span.horizontal >= span.vertical * 1.4f
    }

    fun isProneHoldCandidate(): Boolean {
        val span = horizontalBodySpan() ?: return false
        val shoulder = shoulderMid ?: return false
        val hip = hipMid ?: return false
        val knee = kneeMid ?: return false
        val ankle = ankleMid ?: return false
        val torsoCompact = abs(shoulder.y - hip.y) <= PRONE_TORSO_VERTICAL_SPAN_MAX
        val lowerBodyCompact = abs(hip.y - knee.y) <= PRONE_LOWER_BODY_VERTICAL_SPAN_MAX &&
            abs(knee.y - ankle.y) <= PRONE_LOWER_BODY_VERTICAL_SPAN_MAX
        return hasMinimum(PRONE_HOLD_NAMES) &&
            !isStandingSignal() &&
            span.vertical <= PRONE_FULL_BODY_VERTICAL_SPAN_MAX &&
            torsoCompact &&
            lowerBodyCompact &&
            !hasAlternatingKneeTuck()
    }

    private fun horizontalBodySpan(): BodySpan? {
        val shoulder = shoulderMid ?: return null
        val hip = hipMid ?: return null
        val ankle = ankleMid ?: return null
        val horizontalSpan = listOfNotNull(leftShoulder, rightShoulder, leftAnkle, rightAnkle)
            .let { points -> points.maxOf { it.x } - points.minOf { it.x } }
        val verticalSpan = maxOf(abs(shoulder.y - hip.y), abs(hip.y - ankle.y), abs(shoulder.y - ankle.y))
        return BodySpan(horizontal = horizontalSpan, vertical = verticalSpan)
    }

    private fun hasAlternatingKneeTuck(): Boolean {
        val hip = hipMid ?: return false
        val leftKneeY = leftKnee?.y ?: return false
        val rightKneeY = rightKnee?.y ?: return false
        val leftNear = leftKneeY <= hip.y + 0.13f
        val rightNear = rightKneeY <= hip.y + 0.13f
        val asymmetric = abs(leftKneeY - rightKneeY) > 0.06f
        return asymmetric && (leftNear || rightNear)
    }

    private fun hasLungeAsymmetry(leftKneeAngle: Float, rightKneeAngle: Float): Boolean {
        val leftKneePoint = leftKnee ?: return false
        val rightKneePoint = rightKnee ?: return false
        val leftAnklePoint = leftAnkle ?: return false
        val rightAnklePoint = rightAnkle ?: return false
        val kneeAngleGap = abs(leftKneeAngle - rightKneeAngle) >= LUNGE_ANGLE_GAP_MIN
        val kneeHeightStagger = abs(leftKneePoint.y - rightKneePoint.y) >= LUNGE_KNEE_HEIGHT_GAP_MIN
        val ankleHeightStagger = abs(leftAnklePoint.y - rightAnklePoint.y) >= LUNGE_ANKLE_HEIGHT_GAP_MIN
        val lowerLegOffsetGap = abs(
            abs(leftKneePoint.x - leftAnklePoint.x) - abs(rightKneePoint.x - rightAnklePoint.x)
        )
        val strongLowerLegStagger = lowerLegOffsetGap >= LUNGE_STRONG_LOWER_LEG_OFFSET_GAP
        val visualStaggerCount = listOf(
            kneeHeightStagger,
            ankleHeightStagger,
            lowerLegOffsetGap >= LUNGE_LOWER_LEG_OFFSET_GAP_MIN,
        ).count { it }
        return kneeAngleGap || strongLowerLegStagger || visualStaggerCount >= 2
    }

    private fun midpoint(name: String, a: LandmarkPoint?, b: LandmarkPoint?): LandmarkPoint? =
        if (a == null || b == null) null else PoseMath.midpoint(name, a, b)

    private fun distanceX(a: LandmarkPoint?, b: LandmarkPoint?): Float? =
        if (a == null || b == null) null else PoseMath.horizontalDistance(a, b)

    private fun angle(a: LandmarkPoint?, vertex: LandmarkPoint?, c: LandmarkPoint?): Float? =
        if (a == null || vertex == null || c == null) null else PoseMath.angle(a, vertex, c)

    private fun ratio(value: Float?, reference: Float?): Float? =
        if (value == null || reference == null || reference <= 0f) null else value / reference

    private fun verticalRange(points: List<LandmarkPoint>): Float? =
        points
            .takeIf { it.isNotEmpty() }
            ?.let { values -> values.maxOf { it.y } - values.minOf { it.y } }

    private fun average(a: Float?, b: Float?): Float? =
        when {
            a != null && b != null -> (a + b) / 2f
            a != null -> a
            b != null -> b
            else -> null
        }

    private data class BodySpan(
        val horizontal: Float,
        val vertical: Float,
    )

    companion object {
        val LOWER_BODY_NAMES = listOf("LEFT_HIP", "RIGHT_HIP", "LEFT_KNEE", "RIGHT_KNEE", "LEFT_ANKLE", "RIGHT_ANKLE")
        private val JUMPING_JACK_NAMES = listOf("LEFT_SHOULDER", "RIGHT_SHOULDER", "LEFT_WRIST", "RIGHT_WRIST", "LEFT_ANKLE", "RIGHT_ANKLE")
        private val HORIZONTAL_BODY_NAMES = listOf("LEFT_SHOULDER", "RIGHT_SHOULDER", "LEFT_HIP", "RIGHT_HIP", "LEFT_ANKLE", "RIGHT_ANKLE")
        private val PRONE_HOLD_NAMES = HORIZONTAL_BODY_NAMES + listOf("LEFT_KNEE", "RIGHT_KNEE")
        private val PUSH_UP_NAMES = HORIZONTAL_BODY_NAMES + listOf("LEFT_ELBOW", "RIGHT_ELBOW", "LEFT_WRIST", "RIGHT_WRIST")
        private val SIT_UP_NAMES = listOf("LEFT_SHOULDER", "RIGHT_SHOULDER", "LEFT_HIP", "RIGHT_HIP", "LEFT_KNEE", "RIGHT_KNEE")
        private val JUMP_ROPE_NAMES = listOf("LEFT_SHOULDER", "RIGHT_SHOULDER", "LEFT_ELBOW", "RIGHT_ELBOW", "LEFT_WRIST", "RIGHT_WRIST", "LEFT_HIP", "RIGHT_HIP", "LEFT_ANKLE", "RIGHT_ANKLE")
        private val LATERAL_RAISE_REQUIRED_NAMES = listOf("LEFT_SHOULDER", "RIGHT_SHOULDER", "LEFT_WRIST", "RIGHT_WRIST", "LEFT_HIP", "RIGHT_HIP")
        private val STANDING_FORWARD_BEND_NAMES = listOf("LEFT_SHOULDER", "RIGHT_SHOULDER", "LEFT_WRIST", "RIGHT_WRIST", "LEFT_HIP", "RIGHT_HIP", "LEFT_KNEE", "RIGHT_KNEE", "LEFT_ANKLE", "RIGHT_ANKLE")
        private const val RELAXED_CONFIDENCE_FACTOR = 0.75f
        private const val PLANK_MIN_ELBOW_ANGLE = 70f
        private const val PRONE_FULL_BODY_VERTICAL_SPAN_MAX = 0.42f
        private const val PRONE_TORSO_VERTICAL_SPAN_MAX = 0.24f
        private const val PRONE_LOWER_BODY_VERTICAL_SPAN_MAX = 0.24f
        private const val LUNGE_BENT_KNEE_MAX_ANGLE = 155f
        private const val LUNGE_ANGLE_GAP_MIN = 18f
        private const val LUNGE_KNEE_HEIGHT_GAP_MIN = 0.04f
        private const val LUNGE_ANKLE_HEIGHT_GAP_MIN = 0.05f
        private const val LUNGE_LOWER_LEG_OFFSET_GAP_MIN = 0.06f
        private const val LUNGE_STRONG_LOWER_LEG_OFFSET_GAP = 0.10f
        private const val LATERAL_RAISE_MIN_WRIST_SPREAD_RATIO = 1.35f
        private const val LATERAL_RAISE_MAX_ANKLE_SPREAD_RATIO = 1.45f
        private const val LATERAL_RAISE_MIN_WRIST_OUT = 0.08f
        private const val LATERAL_RAISE_MIN_ARM_OPEN_ANGLE = 30f
        private const val LATERAL_RAISE_CLEAR_ARM_OPEN_ANGLE = 45f
        private const val LATERAL_RAISE_MAX_ARM_OPEN_ANGLE = 145f
        private const val LATERAL_RAISE_WRIST_BELOW_HIP_MARGIN_RATIO = 0.35f
        private const val LATERAL_RAISE_OVERHEAD_MARGIN = 0.14f
        private const val LATERAL_RAISE_REST_MAX_ARM_OPEN_ANGLE = 35f
        private const val LATERAL_RAISE_REST_WRIST_MIN_Y_RATIO = 0.75f
        private const val LATERAL_RAISE_REST_MAX_WRIST_SPREAD_RATIO = 1.70f
        private const val JUMP_ROPE_MAX_FOREARM_HORIZONTAL_RATIO = 0.32f
        private const val JUMP_ROPE_MIN_ELBOW_BELOW_SHOULDER = 0.08f
        private const val JUMP_ROPE_WRIST_ABOVE_ELBOW_TOLERANCE = 0.03f
        private const val STANDING_FORWARD_BEND_MIN_KNEE_ANGLE = 150f
        private const val STANDING_FORWARD_BEND_MAX_HIP_ANGLE = 145f
        private const val STANDING_FORWARD_BEND_MAX_TORSO_VERTICAL_SPAN = 0.12f
        private const val STANDING_FORWARD_BEND_SHOULDER_NEAR_HIP_MARGIN = 0.04f
        private const val STANDING_FORWARD_BEND_WRIST_ABOVE_KNEE_MARGIN = 0.08f
        private const val STANDING_FORWARD_BEND_HIP_ABOVE_KNEE_MARGIN = 0.10f
        private const val STANDING_FORWARD_BEND_MAX_ANKLE_SPREAD_RATIO = 1.25f
        private const val STANDING_FORWARD_BEND_REST_MIN_KNEE_ANGLE = 155f
        private const val STANDING_FORWARD_BEND_REST_MIN_HIP_ANGLE = 155f
    }
}

object PoseActionRules {
    fun features(frame: PoseFrame, config: PoseAnalysisConfig): PoseActionFeatures =
        PoseActionFeatures(frame, config)

    fun allLowConfidence(frames: List<PoseFrame>, config: PoseAnalysisConfig): Boolean =
        frames.all { it.overallConfidence < config.minPoseConfidence * 0.75f }

    fun ankleVerticalRange(features: List<PoseActionFeatures>): Float =
        features.mapNotNull { it.ankleMid?.y }
            .takeIf { it.size >= 2 }
            ?.let { values -> values.maxOrNull()!! - values.minOrNull()!! }
            ?: 0f

    fun hipVerticalRange(features: List<PoseActionFeatures>): Float =
        features.mapNotNull { it.hipMid?.y }
            .takeIf { it.size >= 2 }
            ?.let { values -> values.maxOrNull()!! - values.minOrNull()!! }
            ?: 0f

    fun hasSquatMotionSequence(features: List<PoseActionFeatures>): Boolean {
        if (features.size < 3) return false
        val reliable = features.filter { feature ->
            feature.hasMinimum(PoseActionFeatures.LOWER_BODY_NAMES) &&
                feature.hipMid != null &&
                feature.kneeMid != null
        }
        if (reliable.size < 3) return false
        val hipValues = reliable.mapNotNull { it.hipMid?.y }
        val hipRange = hipValues.maxOrNull()!! - hipValues.minOrNull()!!
        val hasStandingLikeFrame = reliable.any { it.isStandingSignal() }
        val hasBottomLikeFrame = reliable.any { feature ->
            val hip = feature.hipMid ?: return@any false
            val knee = feature.kneeMid ?: return@any false
            hip.y >= knee.y - 0.10f
        }
        return hipRange >= 0.08f && (hasStandingLikeFrame || reliable.first().isStandingSignal()) && hasBottomLikeFrame
    }

    fun hasJumpRopeCycle(features: List<PoseActionFeatures>): Boolean {
        if (features.size < 3) return false
        val poseSupport = features.count { it.isJumpRopePose() }
        if (poseSupport < requiredJumpRopePoseSupport(features.size)) return false
        val jumpRopeLike = features.filter { it.isJumpRopePose() }
        val ankleRange = ankleVerticalRange(jumpRopeLike)
        val hipRange = hipVerticalRange(jumpRopeLike)
        val kneeRange = kneeVerticalRange(jumpRopeLike)
        val hasSmallBounce = ankleRange in JUMP_ROPE_ANKLE_BOUNCE_MIN..JUMP_ROPE_ANKLE_BOUNCE_MAX ||
            hipRange in JUMP_ROPE_HIP_BOUNCE_MIN..JUMP_ROPE_HIP_BOUNCE_MAX ||
            kneeRange in JUMP_ROPE_KNEE_BOUNCE_MIN..JUMP_ROPE_KNEE_BOUNCE_MAX
        return hasSmallBounce && (hasJumpRopeHandMotion(jumpRopeLike) || hasJumpRopeBouncePattern(jumpRopeLike))
    }

    fun hasLateralRaiseWindow(features: List<PoseActionFeatures>): Boolean {
        val support = features.count { it.isLateralRaiseSignal() }
        if (support <= 0) return false
        val required = if (features.size <= 3) 1 else 2
        return support >= required
    }

    fun hasMountainClimberMotion(features: List<PoseActionFeatures>): Boolean =
        hasMountainClimberLegMotion(features)

    fun hasStaticProneHold(features: List<PoseActionFeatures>): Boolean {
        if (features.size < STATIC_PRONE_MIN_FRAMES) return false
        if (hasMountainClimberLegMotion(features)) return false
        val reliable = features.filter { it.isProneHoldCandidate() || it.isPlankSignal() }
        if (reliable.size < STATIC_PRONE_MIN_FRAMES) return false
        val motionRange = maxOf(
            reliable.rangeOf { it.shoulderMid?.x },
            reliable.rangeOf { it.shoulderMid?.y },
            reliable.rangeOf { it.hipMid?.x },
            reliable.rangeOf { it.hipMid?.y },
            reliable.rangeOf { it.kneeMid?.x },
            reliable.rangeOf { it.kneeMid?.y },
            reliable.rangeOf { it.ankleMid?.x },
            reliable.rangeOf { it.ankleMid?.y },
        )
        return motionRange <= STATIC_PRONE_MAX_MOTION_RANGE
    }

    private fun hasMountainClimberLegMotion(features: List<PoseActionFeatures>): Boolean {
        if (features.size < MOUNTAIN_CLIMBER_MOTION_MIN_FRAMES) return false
        val reliable = features.filter { feature ->
            feature.isProneActionBasePose() || feature.isMountainClimberSignal()
        }
        if (reliable.size < MOUNTAIN_CLIMBER_MOTION_MIN_FRAMES) return false
        val relativeLegMotion = maxOf(
            reliable.relativeRangeOf({ it.leftKnee?.x }, { it.hipMid?.x }),
            reliable.relativeRangeOf({ it.leftKnee?.y }, { it.hipMid?.y }),
            reliable.relativeRangeOf({ it.rightKnee?.x }, { it.hipMid?.x }),
            reliable.relativeRangeOf({ it.rightKnee?.y }, { it.hipMid?.y }),
            reliable.relativeRangeOf({ it.leftAnkle?.x }, { it.hipMid?.x }),
            reliable.relativeRangeOf({ it.leftAnkle?.y }, { it.hipMid?.y }),
            reliable.relativeRangeOf({ it.rightAnkle?.x }, { it.hipMid?.x }),
            reliable.relativeRangeOf({ it.rightAnkle?.y }, { it.hipMid?.y }),
        )
        val torsoMotion = maxOf(
            reliable.rangeOf { it.shoulderMid?.x },
            reliable.rangeOf { it.shoulderMid?.y },
            reliable.rangeOf { it.hipMid?.x },
            reliable.rangeOf { it.hipMid?.y },
        )
        val kneePairDeltaRange = reliable.rangeOf { feature ->
            val hip = feature.hipMid ?: return@rangeOf null
            val left = feature.leftKnee ?: return@rangeOf null
            val right = feature.rightKnee ?: return@rangeOf null
            (left.y - hip.y) - (right.y - hip.y)
        }
        val hasSideSwitch = reliable
            .mapNotNull { it.activeSideFor(ActionType.MOUNTAIN_CLIMBER) }
            .distinct()
            .size >= 2
        val hasDirectSupport = reliable.any { it.isMountainClimberSignal() }
        val legMovesBeyondTorso = relativeLegMotion >= MOUNTAIN_CLIMBER_LEG_MOTION_MIN &&
            relativeLegMotion >= torsoMotion + MOUNTAIN_CLIMBER_LEG_OVER_TORSO_MARGIN
        val kneesAlternate = kneePairDeltaRange >= MOUNTAIN_CLIMBER_KNEE_PAIR_DELTA_RANGE_MIN
        return legMovesBeyondTorso && (kneesAlternate || hasSideSwitch || hasDirectSupport)
    }

    fun hasJumpRopeHandMotion(features: List<PoseActionFeatures>): Boolean {
        val reliable = features.filter { it.isJumpRopeHandPose() }
        if (reliable.size < 3) return false
        val leftWristYRange = reliable.rangeOf { it.leftWrist?.y }
        val rightWristYRange = reliable.rangeOf { it.rightWrist?.y }
        val leftWristXRange = reliable.rangeOf { it.leftWrist?.x }
        val rightWristXRange = reliable.rangeOf { it.rightWrist?.x }
        val wristMotion = maxOf(leftWristYRange, rightWristYRange, leftWristXRange, rightWristXRange)
        return wristMotion in JUMP_ROPE_WRIST_MOTION_MIN..JUMP_ROPE_WRIST_MOTION_MAX
    }

    private fun kneeVerticalRange(features: List<PoseActionFeatures>): Float =
        features.mapNotNull { it.kneeMid?.y }
            .takeIf { it.size >= 2 }
            ?.let { values -> values.maxOrNull()!! - values.minOrNull()!! }
            ?: 0f

    private fun hasJumpRopeBouncePattern(features: List<PoseActionFeatures>): Boolean =
        hasVerticalBouncePattern(features.mapNotNull { it.ankleMid?.y }) ||
            hasVerticalBouncePattern(features.mapNotNull { it.hipMid?.y }) ||
            hasVerticalBouncePattern(features.mapNotNull { it.kneeMid?.y })

    private fun hasVerticalBouncePattern(values: List<Float>): Boolean {
        if (values.size < 3) return false
        val range = values.maxOrNull()!! - values.minOrNull()!!
        if (range < JUMP_ROPE_VERTICAL_BOUNCE_PATTERN_MIN) return false
        val diffs = values.zipWithNext { previous, current -> current - previous }
        val hasLiftThenLand = diffs.indices.any { index ->
            diffs[index] <= -JUMP_ROPE_DIRECTION_EPSILON &&
                diffs.drop(index + 1).any { it >= JUMP_ROPE_DIRECTION_EPSILON }
        }
        val hasLandThenLift = diffs.indices.any { index ->
            diffs[index] >= JUMP_ROPE_DIRECTION_EPSILON &&
                diffs.drop(index + 1).any { it <= -JUMP_ROPE_DIRECTION_EPSILON }
        }
        return hasLiftThenLand || hasLandThenLift
    }

    private fun requiredJumpRopePoseSupport(frameCount: Int): Int =
        when {
            frameCount <= 3 -> 2
            frameCount <= 8 -> 3
            else -> 4
        }

    private fun List<PoseActionFeatures>.rangeOf(selector: (PoseActionFeatures) -> Float?): Float =
        mapNotNull(selector)
            .takeIf { it.size >= 2 }
            ?.let { values -> values.maxOrNull()!! - values.minOrNull()!! }
            ?: 0f

    private fun List<PoseActionFeatures>.relativeRangeOf(
        selector: (PoseActionFeatures) -> Float?,
        reference: (PoseActionFeatures) -> Float?,
    ): Float =
        mapNotNull { feature ->
            val value = selector(feature)
            val base = reference(feature)
            if (value != null && base != null) value - base else null
        }
            .takeIf { it.size >= 2 }
            ?.let { values -> values.maxOrNull()!! - values.minOrNull()!! }
            ?: 0f

    private const val JUMP_ROPE_ANKLE_BOUNCE_MIN = 0.006f
    private const val JUMP_ROPE_ANKLE_BOUNCE_MAX = 0.14f
    private const val JUMP_ROPE_HIP_BOUNCE_MIN = 0.005f
    private const val JUMP_ROPE_HIP_BOUNCE_MAX = 0.10f
    private const val JUMP_ROPE_KNEE_BOUNCE_MIN = 0.005f
    private const val JUMP_ROPE_KNEE_BOUNCE_MAX = 0.12f
    private const val JUMP_ROPE_VERTICAL_BOUNCE_PATTERN_MIN = 0.005f
    private const val JUMP_ROPE_DIRECTION_EPSILON = 0.0035f
    private const val JUMP_ROPE_WRIST_MOTION_MIN = 0.006f
    private const val JUMP_ROPE_WRIST_MOTION_MAX = 0.18f
    private const val STATIC_PRONE_MIN_FRAMES = 3
    private const val STATIC_PRONE_MAX_MOTION_RANGE = 0.035f
    private const val MOUNTAIN_CLIMBER_MOTION_MIN_FRAMES = 2
    private const val MOUNTAIN_CLIMBER_LEG_MOTION_MIN = 0.045f
    private const val MOUNTAIN_CLIMBER_LEG_OVER_TORSO_MARGIN = 0.018f
    private const val MOUNTAIN_CLIMBER_KNEE_PAIR_DELTA_RANGE_MIN = 0.035f
}
