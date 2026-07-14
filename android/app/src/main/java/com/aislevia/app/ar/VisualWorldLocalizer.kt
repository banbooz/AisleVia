package com.aislevia.app.ar

import android.content.Context
import android.util.Base64
import com.google.ar.core.Frame
import com.google.ar.core.Pose
import com.google.ar.core.exceptions.NotYetAvailableException
import org.opencv.android.OpenCVLoader
import org.opencv.calib3d.Calib3d
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.DMatch
import org.opencv.core.Mat
import org.opencv.core.MatOfDMatch
import org.opencv.core.MatOfDouble
import org.opencv.core.MatOfKeyPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.MatOfPoint3f
import org.opencv.core.Point
import org.opencv.core.Point3
import org.opencv.core.Size
import org.opencv.features2d.BFMatcher
import org.opencv.features2d.ORB
import org.opencv.imgproc.Imgproc
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPInputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

data class CameraFrameSample(
    val width: Int,
    val height: Int,
    val luminance: ByteArray,
    val focalX: Double,
    val focalY: Double,
    val principalX: Double,
    val principalY: Double,
    val depthWidth: Int = 0,
    val depthHeight: Int = 0,
    val depthMillimetres: IntArray? = null
) {
    companion object {
        fun capture(frame: Frame): CameraFrameSample? {
            val image = try {
                frame.acquireCameraImage()
            } catch (_: NotYetAvailableException) {
                return null
            }
            return try {
                val plane = image.planes.first()
                val buffer = plane.buffer
                val rowStride = plane.rowStride
                val pixelStride = plane.pixelStride
                val width = image.width
                val height = image.height
                val luminance = ByteArray(width * height)
                for (y in 0 until height) {
                    val rowOffset = y * rowStride
                    val outputOffset = y * width
                    for (x in 0 until width) {
                        luminance[outputOffset + x] = buffer.get(rowOffset + x * pixelStride)
                    }
                }
                val intrinsics = frame.camera.imageIntrinsics
                val focal = intrinsics.focalLength
                val principal = intrinsics.principalPoint
                val depth = runCatching {
                    frame.acquireDepthImage16Bits().use { depthImage ->
                        val depthPlane = depthImage.planes.first()
                        val depthBuffer = depthPlane.buffer.order(ByteOrder.LITTLE_ENDIAN)
                        val values = IntArray(depthImage.width * depthImage.height)
                        for (depthY in 0 until depthImage.height) {
                            val rowOffset = depthY * depthPlane.rowStride
                            for (depthX in 0 until depthImage.width) {
                                val offset = rowOffset + depthX * depthPlane.pixelStride
                                values[depthY * depthImage.width + depthX] =
                                    depthBuffer.getShort(offset).toInt() and 0xFFFF
                            }
                        }
                        Triple(depthImage.width, depthImage.height, values)
                    }
                }.getOrNull()
                CameraFrameSample(
                    width = width,
                    height = height,
                    luminance = luminance,
                    focalX = focal[0].toDouble(),
                    focalY = focal[1].toDouble(),
                    principalX = principal[0].toDouble(),
                    principalY = principal[1].toDouble(),
                    depthWidth = depth?.first ?: 0,
                    depthHeight = depth?.second ?: 0,
                    depthMillimetres = depth?.third
                )
            } finally {
                image.close()
            }
        }
    }
}

enum class VisualLocalizationPhase { SEARCHING, CHECKING, LOCKED }

data class VisualLocalizationResult(
    val phase: VisualLocalizationPhase,
    val worldFromMap: Pose?,
    val featureMatches: Int,
    val poseInliers: Int,
    val agreeingFrames: Int,
    val message: String,
    val matchedKeyframeId: Int? = null,
    val confidence: Float = 0f
)

private data class VisualFeatureMap(
    val points: List<Point3>,
    val descriptors: Mat
)

private data class PoseObservation(
    val pose: Pose,
    val timestampMillis: Long,
    val inliers: Int
)

/**
 * Matches any live camera view against thousands of 3D points extracted from the room scan.
 * No named picture, fireplace or bookcase needs to be shown. Three independent camera frames
 * must solve to the same six-dimensional pose before a lock is exposed to navigation.
 */
class VisualWorldLocalizer(context: Context) {
    private val appContext = context.applicationContext
    private val observations = ArrayDeque<PoseObservation>()
    private var lockedPose: Pose? = null
    private var lastVerifiedAtMillis = 0L

    private val featureMap: VisualFeatureMap by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        check(OpenCVLoader.initLocal()) { "OpenCV could not initialise on this phone." }
        loadFeatureMap()
    }

    fun localize(
        sample: CameraFrameSample,
        arCameraPose: Pose,
        worldFloorY: Float?,
        nowMillis: Long = System.currentTimeMillis()
    ): VisualLocalizationResult {
        val map = runCatching { featureMap }.getOrElse { error ->
            return VisualLocalizationResult(
                phase = VisualLocalizationPhase.SEARCHING,
                worldFromMap = null,
                featureMatches = 0,
                poseInliers = 0,
                agreeingFrames = 0,
                message = "The automatic visual map could not load: \${error.message.orEmpty()}"
            )
        }

        val source = Mat(sample.height, sample.width, CvType.CV_8UC1)
        val gray = Mat()
        val mask = Mat()
        val keypoints = MatOfKeyPoint()
        val frameDescriptors = Mat()
        val orb = ORB.create(
            1800,
            1.2f,
            8,
            31,
            0,
            2,
            ORB.HARRIS_SCORE,
            31,
            12
        )
        try {
            source.put(0, 0, sample.luminance)
            val targetWidth = min(960, sample.width)
            val scale = targetWidth.toDouble() / sample.width.toDouble()
            val targetHeight = max(1, (sample.height * scale).toInt())
            Imgproc.resize(source, gray, Size(targetWidth.toDouble(), targetHeight.toDouble()))
            orb.detectAndCompute(gray, mask, keypoints, frameDescriptors)

            if (frameDescriptors.empty() || frameDescriptors.rows() < 40) {
                return currentResult(
                    nowMillis,
                    0,
                    0,
                    "Keep moving the phone slowly so more room detail is visible."
                )
            }

            val matcher = BFMatcher.create(Core.NORM_HAMMING, false)
            val neighbours = ArrayList<MatOfDMatch>()
            matcher.knnMatch(frameDescriptors, map.descriptors, neighbours, 2)
            val bestByMapPoint = HashMap<Int, DMatch>()
            neighbours.forEach { pair ->
                val candidates = pair.toArray()
                if (candidates.size >= 2 && candidates[0].distance < candidates[1].distance * 0.72f) {
                    val candidate = candidates[0]
                    val previous = bestByMapPoint[candidate.trainIdx]
                    if (previous == null || candidate.distance < previous.distance) {
                        bestByMapPoint[candidate.trainIdx] = candidate
                    }
                }
                pair.release()
            }
            matcher.clear()

            val frameKeypoints = keypoints.toArray()
            val matches = bestByMapPoint.values
                .filter { it.queryIdx in frameKeypoints.indices && it.trainIdx in map.points.indices }
                .sortedBy { it.distance }
                .take(500)

            if (matches.size < MINIMUM_FEATURE_MATCHES) {
                return currentResult(
                    nowMillis,
                    matches.size,
                    0,
                    "Looking at the whole room… keep turning slowly. No particular object is required."
                )
            }

            val objectPointsList = matches.map { map.points[it.trainIdx] }
            val imagePointsList = matches.map {
                val point = frameKeypoints[it.queryIdx].pt
                Point(point.x, point.y)
            }
            val objectPoints = MatOfPoint3f().apply { fromList(objectPointsList) }
            val imagePoints = MatOfPoint2f().apply { fromList(imagePointsList) }
            val cameraMatrix = Mat.eye(3, 3, CvType.CV_64F)
            cameraMatrix.put(0, 0, sample.focalX * scale)
            cameraMatrix.put(1, 1, sample.focalY * scale)
            cameraMatrix.put(0, 2, sample.principalX * scale)
            cameraMatrix.put(1, 2, sample.principalY * scale)
            val distortion = MatOfDouble()
            val rotationVector = Mat()
            val translationVector = Mat()
            val inliers = Mat()

            val solved = Calib3d.solvePnPRansac(
                objectPoints,
                imagePoints,
                cameraMatrix,
                distortion,
                rotationVector,
                translationVector,
                false,
                220,
                4.5f,
                0.995,
                inliers,
                Calib3d.SOLVEPNP_EPNP
            )
            val inlierCount = if (solved) inliers.rows() else 0
            val inlierRatio = inlierCount.toFloat() / matches.size.toFloat()
            val accepted = solved &&
                inlierCount >= MINIMUM_POSE_INLIERS &&
                inlierRatio >= MINIMUM_INLIER_RATIO

            if (!accepted) {
                objectPoints.release()
                imagePoints.release()
                cameraMatrix.release()
                distortion.release()
                rotationVector.release()
                translationVector.release()
                inliers.release()
                return currentResult(
                    nowMillis,
                    matches.size,
                    inlierCount,
                    "A possible view was found, but it was not accurate enough. Keep looking around."
                )
            }

            val cameraFromMap = openCvPose(rotationVector, translationVector)
            var worldFromMap = arCameraPose.compose(cameraFromMap)
            if (worldFloorY != null) {
                worldFromMap = PoseMath.floorConstrainedPose(worldFromMap, worldFloorY)
            }
            val result = recordCandidate(worldFromMap, nowMillis, matches.size, inlierCount)

            objectPoints.release()
            imagePoints.release()
            cameraMatrix.release()
            distortion.release()
            rotationVector.release()
            translationVector.release()
            inliers.release()
            return result
        } finally {
            orb.clear()
            frameDescriptors.release()
            keypoints.release()
            mask.release()
            gray.release()
            source.release()
        }
    }

    private fun recordCandidate(
        candidate: Pose,
        nowMillis: Long,
        matches: Int,
        inliers: Int
    ): VisualLocalizationResult {
        observations.removeAll { nowMillis - it.timestampMillis > CONSENSUS_WINDOW_MILLIS }

        val existingLock = lockedPose
        if (existingLock != null &&
            (PoseMath.translationDistance(existingLock, candidate) > LOCK_TRANSLATION_TOLERANCE_METRES ||
                PoseMath.rotationDistanceDegrees(existingLock, candidate) > LOCK_ROTATION_TOLERANCE_DEGREES)
        ) {
            return currentResult(
                nowMillis,
                matches,
                inliers,
                "That view disagreed with the verified position, so it was ignored."
            )
        }

        observations += PoseObservation(candidate, nowMillis, inliers)
        val cluster = observations.filter { observation ->
            PoseMath.translationDistance(observation.pose, candidate) <=
                CONSENSUS_TRANSLATION_TOLERANCE_METRES &&
                PoseMath.rotationDistanceDegrees(observation.pose, candidate) <=
                CONSENSUS_ROTATION_TOLERANCE_DEGREES
        }
        if (cluster.size >= REQUIRED_AGREEING_FRAMES) {
            lockedPose = PoseMath.average(cluster.map { it.pose })
            lastVerifiedAtMillis = nowMillis
            return VisualLocalizationResult(
                phase = VisualLocalizationPhase.LOCKED,
                worldFromMap = lockedPose,
                featureMatches = matches,
                poseInliers = inliers,
                agreeingFrames = cluster.size,
                message = "Room recognised automatically from $inliers matching 3D details."
            )
        }

        return VisualLocalizationResult(
            phase = VisualLocalizationPhase.CHECKING,
            worldFromMap = null,
            featureMatches = matches,
            poseInliers = inliers,
            agreeingFrames = cluster.size,
            message = "Position found. Keep moving slowly while accuracy is checked (\${cluster.size}/$REQUIRED_AGREEING_FRAMES)."
        )
    }

    private fun currentResult(
        nowMillis: Long,
        matches: Int,
        inliers: Int,
        searchingMessage: String
    ): VisualLocalizationResult {
        val lock = lockedPose?.takeIf {
            nowMillis - lastVerifiedAtMillis <= LOCK_STALE_MILLIS
        }
        return if (lock != null) {
            VisualLocalizationResult(
                phase = VisualLocalizationPhase.LOCKED,
                worldFromMap = lock,
                featureMatches = matches,
                poseInliers = inliers,
                agreeingFrames = REQUIRED_AGREEING_FRAMES,
                message = "Position is being tracked while the room is rechecked automatically."
            )
        } else {
            if (nowMillis - lastVerifiedAtMillis > LOCK_STALE_MILLIS) lockedPose = null
            VisualLocalizationResult(
                phase = VisualLocalizationPhase.SEARCHING,
                worldFromMap = null,
                featureMatches = matches,
                poseInliers = inliers,
                agreeingFrames = 0,
                message = searchingMessage
            )
        }
    }

    private fun loadFeatureMap(): VisualFeatureMap {
        val encoded = appContext.assets.open(FEATURE_MAP_ASSET)
            .bufferedReader()
            .use { it.readText() }
        val compressed = Base64.decode(encoded, Base64.DEFAULT)
        val bytes = GZIPInputStream(ByteArrayInputStream(compressed)).use { it.readBytes() }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val magicBytes = ByteArray(4).also(buffer::get)
        val magic = magicBytes.toString(Charsets.US_ASCII)
        check(magic == "AVFM") { "Unexpected visual-map header." }
        check(buffer.int == 1) { "Unsupported visual-map version." }
        val count = buffer.int
        check(count in 1000..20_000) { "Visual map has an invalid feature count." }

        val points = ArrayList<Point3>(count)
        val descriptorBytes = ByteArray(count * DESCRIPTOR_BYTES)
        repeat(count) { index ->
            points += Point3(
                buffer.float.toDouble(),
                buffer.float.toDouble(),
                buffer.float.toDouble()
            )
            buffer.get(descriptorBytes, index * DESCRIPTOR_BYTES, DESCRIPTOR_BYTES)
        }
        val descriptors = Mat(count, DESCRIPTOR_BYTES, CvType.CV_8UC1)
        descriptors.put(0, 0, descriptorBytes)
        return VisualFeatureMap(points, descriptors)
    }

    /** Converts OpenCV camera axes (+X right, +Y down, +Z forward) to ARCore camera axes. */
    private fun openCvPose(rotationVector: Mat, translationVector: Mat): Pose {
        val cvRotation = Mat()
        Calib3d.Rodrigues(rotationVector, cvRotation)
        val r = DoubleArray(9)
        cvRotation.get(0, 0, r)
        val t = DoubleArray(3)
        translationVector.get(0, 0, t)
        cvRotation.release()

        val rotation = floatArrayOf(
            r[0].toFloat(), r[1].toFloat(), r[2].toFloat(),
            (-r[3]).toFloat(), (-r[4]).toFloat(), (-r[5]).toFloat(),
            (-r[6]).toFloat(), (-r[7]).toFloat(), (-r[8]).toFloat()
        )
        return Pose(
            floatArrayOf(t[0].toFloat(), (-t[1]).toFloat(), (-t[2]).toFloat()),
            rotationMatrixToQuaternion(rotation)
        )
    }

    private fun rotationMatrixToQuaternion(matrix: FloatArray): FloatArray {
        val m00 = matrix[0]; val m01 = matrix[1]; val m02 = matrix[2]
        val m10 = matrix[3]; val m11 = matrix[4]; val m12 = matrix[5]
        val m20 = matrix[6]; val m21 = matrix[7]; val m22 = matrix[8]
        val trace = m00 + m11 + m22
        val quaternion = FloatArray(4)
        if (trace > 0f) {
            val s = sqrt(trace + 1f) * 2f
            quaternion[3] = 0.25f * s
            quaternion[0] = (m21 - m12) / s
            quaternion[1] = (m02 - m20) / s
            quaternion[2] = (m10 - m01) / s
        } else if (m00 > m11 && m00 > m22) {
            val s = sqrt(1f + m00 - m11 - m22) * 2f
            quaternion[3] = (m21 - m12) / s
            quaternion[0] = 0.25f * s
            quaternion[1] = (m01 + m10) / s
            quaternion[2] = (m02 + m20) / s
        } else if (m11 > m22) {
            val s = sqrt(1f + m11 - m00 - m22) * 2f
            quaternion[3] = (m02 - m20) / s
            quaternion[0] = (m01 + m10) / s
            quaternion[1] = 0.25f * s
            quaternion[2] = (m12 + m21) / s
        } else {
            val s = sqrt(1f + m22 - m00 - m11) * 2f
            quaternion[3] = (m10 - m01) / s
            quaternion[0] = (m02 + m20) / s
            quaternion[1] = (m12 + m21) / s
            quaternion[2] = 0.25f * s
        }
        return quaternion
    }

    companion object {
        private const val FEATURE_MAP_ASSET =
            "world/living_room/visual_feature_map.b64"
        private const val DESCRIPTOR_BYTES = 32
        private const val MINIMUM_FEATURE_MATCHES = 45
        private const val MINIMUM_POSE_INLIERS = 30
        private const val MINIMUM_INLIER_RATIO = 0.34f
        private const val REQUIRED_AGREEING_FRAMES = 3
        private const val CONSENSUS_WINDOW_MILLIS = 6_000L
        private const val LOCK_STALE_MILLIS = 12_000L
        private const val CONSENSUS_TRANSLATION_TOLERANCE_METRES = 0.35f
        private const val CONSENSUS_ROTATION_TOLERANCE_DEGREES = 12f
        private const val LOCK_TRANSLATION_TOLERANCE_METRES = 0.50f
        private const val LOCK_ROTATION_TOLERANCE_DEGREES = 16f
    }
}
