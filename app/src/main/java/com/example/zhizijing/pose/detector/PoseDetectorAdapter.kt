package com.example.zhizijing.pose.detector

import androidx.camera.core.ImageProxy
import com.example.zhizijing.domain.model.DeviceRole
import com.example.zhizijing.pose.model.LandmarkPoint
import com.example.zhizijing.pose.model.PoseFrame
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.pose.Pose
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.PoseDetector
import com.google.mlkit.vision.pose.PoseLandmark
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions

interface PoseDetectorAdapter<InputFrame> {
    fun detect(
        inputFrame: InputFrame,
        sessionId: Long,
        nodeId: Long,
        onResult: (PoseFrame?) -> Unit,
        onError: (Exception) -> Unit,
    )

    fun close()
}

class MlKitPoseDetectorAdapter(
    private val cameraRoleProvider: () -> DeviceRole,
) : PoseDetectorAdapter<ImageProxy> {
    constructor(cameraRole: DeviceRole) : this({ cameraRole })

    private val detector: PoseDetector = PoseDetection.getClient(
        PoseDetectorOptions.Builder()
            .setDetectorMode(PoseDetectorOptions.STREAM_MODE)
            .build()
    )

    override fun detect(
        inputFrame: ImageProxy,
        sessionId: Long,
        nodeId: Long,
        onResult: (PoseFrame?) -> Unit,
        onError: (Exception) -> Unit,
    ) {
        val mediaImage = inputFrame.image
        if (mediaImage == null) {
            inputFrame.close()
            onResult(null)
            return
        }
        runCatching {
            val image = InputImage.fromMediaImage(mediaImage, inputFrame.imageInfo.rotationDegrees)
            val coordinateSize = poseImageCoordinateSize(
                width = inputFrame.width,
                height = inputFrame.height,
                rotationDegrees = inputFrame.imageInfo.rotationDegrees,
            )
            detector.process(image)
                .addOnSuccessListener { pose ->
                    onResult(
                        pose.toPoseFrame(
                            sessionId,
                            nodeId,
                            cameraRoleProvider(),
                            coordinateSize.width,
                            coordinateSize.height,
                        )
                    )
                }
                .addOnFailureListener { error ->
                    onError(error)
                }
                .addOnCompleteListener {
                    inputFrame.close()
                }
        }.onFailure { error ->
            inputFrame.close()
            onError(error as? Exception ?: RuntimeException(error))
        }
    }

    override fun close() {
        detector.close()
    }
}

data class PoseImageCoordinateSize(
    val width: Int,
    val height: Int,
)

fun poseImageCoordinateSize(
    width: Int,
    height: Int,
    rotationDegrees: Int,
): PoseImageCoordinateSize {
    val normalizedRotation = ((rotationDegrees % 360) + 360) % 360
    return if (normalizedRotation == 90 || normalizedRotation == 270) {
        PoseImageCoordinateSize(width = height, height = width)
    } else {
        PoseImageCoordinateSize(width = width, height = height)
    }
}

private fun Pose.toPoseFrame(
    sessionId: Long,
    nodeId: Long,
    cameraRole: DeviceRole,
    imageWidth: Int,
    imageHeight: Int,
): PoseFrame? {
    val landmarks = landmarkTypes.mapNotNull { (name, type) ->
        getPoseLandmark(type)?.toLandmarkPoint(name, imageWidth, imageHeight)
    }.associateBy { it.name }
    if (landmarks.isEmpty()) return null
    val confidence = landmarks.values.map { it.confidence }.average().toFloat()
    return PoseFrame(
        sessionId = sessionId,
        nodeId = nodeId,
        timestampMs = System.currentTimeMillis(),
        cameraRole = cameraRole,
        landmarks = landmarks,
        overallConfidence = confidence,
        imageWidth = imageWidth,
        imageHeight = imageHeight,
    )
}

private fun PoseLandmark.toLandmarkPoint(
    name: String,
    imageWidth: Int,
    imageHeight: Int,
): LandmarkPoint =
    LandmarkPoint(
        name = name,
        x = position.x / imageWidth.coerceAtLeast(1),
        y = position.y / imageHeight.coerceAtLeast(1),
        z = position3D.z,
        confidence = inFrameLikelihood,
    )

private val landmarkTypes = listOf(
    "NOSE" to PoseLandmark.NOSE,
    "LEFT_EYE_INNER" to PoseLandmark.LEFT_EYE_INNER,
    "LEFT_EYE" to PoseLandmark.LEFT_EYE,
    "LEFT_EYE_OUTER" to PoseLandmark.LEFT_EYE_OUTER,
    "RIGHT_EYE_INNER" to PoseLandmark.RIGHT_EYE_INNER,
    "RIGHT_EYE" to PoseLandmark.RIGHT_EYE,
    "RIGHT_EYE_OUTER" to PoseLandmark.RIGHT_EYE_OUTER,
    "LEFT_EAR" to PoseLandmark.LEFT_EAR,
    "RIGHT_EAR" to PoseLandmark.RIGHT_EAR,
    "LEFT_MOUTH" to PoseLandmark.LEFT_MOUTH,
    "RIGHT_MOUTH" to PoseLandmark.RIGHT_MOUTH,
    "LEFT_SHOULDER" to PoseLandmark.LEFT_SHOULDER,
    "RIGHT_SHOULDER" to PoseLandmark.RIGHT_SHOULDER,
    "LEFT_ELBOW" to PoseLandmark.LEFT_ELBOW,
    "RIGHT_ELBOW" to PoseLandmark.RIGHT_ELBOW,
    "LEFT_WRIST" to PoseLandmark.LEFT_WRIST,
    "RIGHT_WRIST" to PoseLandmark.RIGHT_WRIST,
    "LEFT_HIP" to PoseLandmark.LEFT_HIP,
    "RIGHT_HIP" to PoseLandmark.RIGHT_HIP,
    "LEFT_KNEE" to PoseLandmark.LEFT_KNEE,
    "RIGHT_KNEE" to PoseLandmark.RIGHT_KNEE,
    "LEFT_ANKLE" to PoseLandmark.LEFT_ANKLE,
    "RIGHT_ANKLE" to PoseLandmark.RIGHT_ANKLE,
    "LEFT_HEEL" to PoseLandmark.LEFT_HEEL,
    "RIGHT_HEEL" to PoseLandmark.RIGHT_HEEL,
    "LEFT_FOOT_INDEX" to PoseLandmark.LEFT_FOOT_INDEX,
    "RIGHT_FOOT_INDEX" to PoseLandmark.RIGHT_FOOT_INDEX,
)
