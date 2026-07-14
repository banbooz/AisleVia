package com.aislevia.app.ar

import android.content.Context
import android.util.Base64
import com.google.ar.core.Pose
import org.opencv.android.OpenCVLoader
import org.opencv.calib3d.Calib3d
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.DMatch
import org.opencv.core.Mat
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

private data class VisualKeyframe(
    val id: Int,
    val mapPoints: List<Point3>,
    val imagePoints: List<Point>,
    val descriptors: Mat
)

private data class KeyframeCandidate(
    val keyframe: VisualKeyframe,
    val matches: List<DMatch>,
    val score: Int
)

private data class VerifiedPose(
    val worldFromMap: Pose,
    val matches: Int,
    val inliers: Int
)

private data class HierarchicalPoseObservation(
    val pose: Pose,
    val timestampMillis: Long
)

/**
 * A small, offline VPS for the supplied room.
 *
 * Stage one retrieves the most similar rendered 3D viewpoints. Stage two removes geometrically
 * inconsistent matches inside each viewpoint, solves a metric 2D-to-3D pose, and rejects camera
 * positions outside the scanned room. Two independent frames must agree before navigation starts.
 */
class HierarchicalWorldLocalizer(context: Context) {
    private val appContext = context.applicationContext
    private val observations = ArrayDeque<HierarchicalPoseObservation>()
    private var lockedPose: Pose? = null
    private var lastVerifiedAtMillis = 0L

    private val keyframes: List<VisualKeyframe> by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        check(OpenCVLoader.initLocal()) { "OpenCV could not initialise on this phone." }
        loadKeyframes()
    }

    fun localize(
        sample: CameraFrameSample,
        arCameraPose: Pose,
        worldFloorY: Float?,
        nowMillis: Long = System.currentTimeMillis()
    ): VisualLocalizationResult {
        val map = runCatching { keyframes }.getOrElse { error ->
            return VisualLocalizationResult(
                phase = VisualLocalizationPhase.SEARCHING,
                worldFromMap = null,
                featureMatches = 0,
                poseInliers = 0,
                agreeingFrames = 0,
                message = "The room map could not load: ${error.message.orEmpty()}"
            )
        }

        val source = Mat(sample.height, sample.width, CvType.CV_8UC1)
        val gray = Mat()
        val mask = Mat()
        val liveKeypoints = MatOfKeyPoint()
        val liveDescriptors = Mat()
        val orb = ORB.create(
            2400,
            1.2f,
            8,
            31,
            0,
            2,
            ORB.HARRIS_SCORE,
            31,
            7
        )
        try {
            source.put(0, 0, sample.luminance)
            val targetWidth = min(960, sample.width)
            val scale = targetWidth.toDouble() / sample.width.toDouble()
            val targetHeight = max(1, (sample.height * scale).toInt())
            Imgproc.resize(source, gray, Size(targetWidth.toDouble(), targetHeight.toDouble()))
            orb.detectAndCompute(gray, mask, liveKeypoints, liveDescriptors)
            if (liveDescriptors.empty() || liveDescriptors.rows() < 80) {
                return currentResult(nowMillis, 0, 0, "Keep looking around slowly so the room is well lit and sharp.")
            }

            val matcher = BFMatcher.create(Core.NORM_HAMMING, false)
            val candidates = ArrayList<KeyframeCandidate>()
            map.forEach { keyframe ->
                val neighbours = ArrayList<org.opencv.core.MatOfDMatch>()
                matcher.knnMatch(liveDescriptors, keyframe.descriptors, neighbours, 2)
                val broadMatches = ArrayList<DMatch>()
                var strictMatches = 0
                neighbours.forEach { pair ->
                    val pairMatches = pair.toArray()
                    if (pairMatches.size >= 2) {
                        val best = pairMatches[0]
                        val second = pairMatches[1]
                        if (best.distance < second.distance * BROAD_RATIO) broadMatches += best
                        if (best.distance < second.distance * STRICT_RATIO) strictMatches += 1
                    }
                    pair.release()
                }
                if (broadMatches.size >= MINIMUM_KEYFRAME_MATCHES) {
                    candidates += KeyframeCandidate(
                        keyframe = keyframe,
                        matches = broadMatches,
                        score = strictMatches * 4 + broadMatches.size
                    )
                }
            }
            matcher.clear()

            if (candidates.isEmpty()) {
                return currentResult(
                    nowMillis,
                    0,
                    0,
                    "Searching the saved room views… keep turning naturally; no specific object is required."
                )
            }

            val framePoints = liveKeypoints.toArray()
            val cameraMatrix = Mat.eye(3, 3, CvType.CV_64F)
            cameraMatrix.put(0, 0, sample.focalX * scale)
            cameraMatrix.put(1, 1, sample.focalY * scale)
            cameraMatrix.put(0, 2, sample.principalX * scale)
            cameraMatrix.put(1, 2, sample.principalY * scale)
            val best = candidates
                .sortedByDescending(KeyframeCandidate::score)
                .take(MAXIMUM_POSE_CANDIDATES)
                .mapNotNull { candidate ->
                    solveCandidate(candidate, framePoints, cameraMatrix, arCameraPose, worldFloorY)
                }
                .maxWithOrNull(compareBy<VerifiedPose> { it.inliers }.thenBy { it.matches })
            cameraMatrix.release()

            if (best == null) {
                val top = candidates.maxBy(KeyframeCandidate::score)
                return currentResult(
                    nowMillis,
                    top.matches.size,
                    0,
                    "Room recognised, but the position is still being checked. Keep moving slowly."
                )
            }
            return recordCandidate(best, nowMillis)
        } finally {
            orb.clear()
            liveDescriptors.release()
            liveKeypoints.release()
            mask.release()
            gray.release()
            source.release()
        }
    }

    private fun solveCandidate(
        candidate: KeyframeCandidate,
        frameKeypoints: Array<org.opencv.core.KeyPoint>,
        cameraMatrix: Mat,
        arCameraPose: Pose,
        worldFloorY: Float?
    ): VerifiedPose? {
        val validMatches = candidate.matches.filter {
            it.queryIdx in frameKeypoints.indices &&
                it.trainIdx in candidate.keyframe.imagePoints.indices
        }
        if (validMatches.size < MINIMUM_KEYFRAME_MATCHES) return null

        val renderedPoints = MatOfPoint2f().apply {
            fromList(validMatches.map { candidate.keyframe.imagePoints[it.trainIdx] })
        }
        val livePoints = MatOfPoint2f().apply {
            fromList(validMatches.map { frameKeypoints[it.queryIdx].pt })
        }
        val homographyMask = Mat()
        val homography = Calib3d.findHomography(
            renderedPoints,
            livePoints,
            Calib3d.RANSAC,
            HOMOGRAPHY_REPROJECTION_PIXELS,
            homographyMask,
            3000,
            0.999
        )
        val maskBytes = ByteArray(homographyMask.rows() * homographyMask.cols())
        if (maskBytes.isNotEmpty()) homographyMask.get(0, 0, maskBytes)
        val consistentMatches = validMatches.filterIndexed { index, _ ->
            index in maskBytes.indices && maskBytes[index].toInt() != 0
        }
        homography.release()
        homographyMask.release()
        renderedPoints.release()
        livePoints.release()
        if (consistentMatches.size < MINIMUM_HOMOGRAPHY_INLIERS) return null

        val objectPoints = MatOfPoint3f().apply {
            fromList(consistentMatches.map { candidate.keyframe.mapPoints[it.trainIdx] })
        }
        val imagePoints = MatOfPoint2f().apply {
            fromList(consistentMatches.map { frameKeypoints[it.queryIdx].pt })
        }
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
            1000,
            POSE_REPROJECTION_PIXELS,
            0.999,
            inliers,
            Calib3d.SOLVEPNP_EPNP
        )
        val inlierCount = if (solved) inliers.rows() else 0
        val inlierRatio = inlierCount.toFloat() / consistentMatches.size.toFloat()
        var result: VerifiedPose? = null
        if (solved && inlierCount >= MINIMUM_POSE_INLIERS && inlierRatio >= MINIMUM_POSE_INLIER_RATIO) {
            val cameraFromMap = openCvPose(rotationVector, translationVector)
            val cameraInMap = cameraFromMap.inverse().translation
            val insideScan = cameraInMap[0] in (MIN_MAP_X - BOUNDS_MARGIN)..(MAX_MAP_X + BOUNDS_MARGIN) &&
                cameraInMap[1] in MINIMUM_CAMERA_HEIGHT..MAXIMUM_CAMERA_HEIGHT &&
                cameraInMap[2] in (MIN_MAP_Z - BOUNDS_MARGIN)..(MAX_MAP_Z + BOUNDS_MARGIN)
            if (insideScan) {
                var worldFromMap = arCameraPose.compose(cameraFromMap)
                if (worldFloorY != null) {
                    worldFromMap = PoseMath.floorConstrainedPose(worldFromMap, worldFloorY)
                }
                result = VerifiedPose(worldFromMap, validMatches.size, inlierCount)
            }
        }
        objectPoints.release()
        imagePoints.release()
        distortion.release()
        rotationVector.release()
        translationVector.release()
        inliers.release()
        return result
    }

    private fun recordCandidate(candidate: VerifiedPose, nowMillis: Long): VisualLocalizationResult {
        observations.removeAll { nowMillis - it.timestampMillis > CONSENSUS_WINDOW_MILLIS }
        val existingLock = lockedPose
        if (existingLock != null &&
            (PoseMath.translationDistance(existingLock, candidate.worldFromMap) > LOCK_TRANSLATION_TOLERANCE_METRES ||
                PoseMath.rotationDistanceDegrees(existingLock, candidate.worldFromMap) > LOCK_ROTATION_TOLERANCE_DEGREES)
        ) {
            return currentResult(nowMillis, candidate.matches, candidate.inliers, "A conflicting view was rejected automatically.")
        }

        observations += HierarchicalPoseObservation(candidate.worldFromMap, nowMillis)
        val cluster = observations.filter {
            PoseMath.translationDistance(it.pose, candidate.worldFromMap) <= CONSENSUS_TRANSLATION_TOLERANCE_METRES &&
                PoseMath.rotationDistanceDegrees(it.pose, candidate.worldFromMap) <= CONSENSUS_ROTATION_TOLERANCE_DEGREES
        }
        if (cluster.size >= REQUIRED_AGREEING_FRAMES) {
            lockedPose = PoseMath.average(cluster.map(HierarchicalPoseObservation::pose))
            lastVerifiedAtMillis = nowMillis
            return VisualLocalizationResult(
                VisualLocalizationPhase.LOCKED,
                lockedPose,
                candidate.matches,
                candidate.inliers,
                cluster.size,
                "Room and position recognised automatically."
            )
        }
        return VisualLocalizationResult(
            VisualLocalizationPhase.CHECKING,
            null,
            candidate.matches,
            candidate.inliers,
            cluster.size,
            "Position found. One more matching view will confirm it."
        )
    }

    private fun currentResult(
        nowMillis: Long,
        matches: Int,
        inliers: Int,
        searchingMessage: String
    ): VisualLocalizationResult {
        val lock = lockedPose?.takeIf { nowMillis - lastVerifiedAtMillis <= LOCK_STALE_MILLIS }
        return if (lock != null) {
            VisualLocalizationResult(
                VisualLocalizationPhase.LOCKED,
                lock,
                matches,
                inliers,
                REQUIRED_AGREEING_FRAMES,
                "Navigation is live while the room is rechecked."
            )
        } else {
            if (nowMillis - lastVerifiedAtMillis > LOCK_STALE_MILLIS) lockedPose = null
            VisualLocalizationResult(
                VisualLocalizationPhase.SEARCHING,
                null,
                matches,
                inliers,
                0,
                searchingMessage
            )
        }
    }

    private fun loadKeyframes(): List<VisualKeyframe> {
        val encoded = KEYFRAME_ASSETS.joinToString(separator = "") { asset ->
            appContext.assets.open(asset).bufferedReader().use { it.readText() }
        }
        val compressed = Base64.decode(encoded, Base64.DEFAULT)
        val bytes = GZIPInputStream(ByteArrayInputStream(compressed)).use { it.readBytes() }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val magicBytes = ByteArray(4).also(buffer::get)
        check(magicBytes.toString(Charsets.US_ASCII) == "AVKF") { "Unexpected keyframe-map header." }
        check(buffer.int == 1) { "Unsupported keyframe-map version." }
        val keyframeCount = buffer.int
        check(keyframeCount in 32..256) { "Invalid keyframe count." }
        return List(keyframeCount) {
            val id = buffer.int
            val featureCount = buffer.int
            check(featureCount in 100..1200) { "Invalid keyframe feature count." }
            val mapPoints = ArrayList<Point3>(featureCount)
            val imagePoints = ArrayList<Point>(featureCount)
            val descriptorBytes = ByteArray(featureCount * DESCRIPTOR_BYTES)
            repeat(featureCount) { feature ->
                mapPoints += Point3(buffer.float.toDouble(), buffer.float.toDouble(), buffer.float.toDouble())
                imagePoints += Point(buffer.float.toDouble(), buffer.float.toDouble())
                buffer.get(descriptorBytes, feature * DESCRIPTOR_BYTES, DESCRIPTOR_BYTES)
            }
            val descriptors = Mat(featureCount, DESCRIPTOR_BYTES, CvType.CV_8UC1)
            descriptors.put(0, 0, descriptorBytes)
            VisualKeyframe(id, mapPoints, imagePoints, descriptors)
        }
    }

    /** Converts OpenCV camera axes (+X right, +Y down, +Z forward) to ARCore axes. */
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
        private val KEYFRAME_ASSETS = (0..8).map { part ->
            "world/living_room/visual_keyframes/part_${part.toString().padStart(2, '0')}.b64"
        }
        private const val DESCRIPTOR_BYTES = 32
        private const val STRICT_RATIO = 0.78f
        private const val BROAD_RATIO = 0.86f
        private const val MINIMUM_KEYFRAME_MATCHES = 30
        private const val MAXIMUM_POSE_CANDIDATES = 16
        private const val MINIMUM_HOMOGRAPHY_INLIERS = 9
        private const val MINIMUM_POSE_INLIERS = 7
        private const val MINIMUM_POSE_INLIER_RATIO = 0.55f
        private const val HOMOGRAPHY_REPROJECTION_PIXELS = 6.0
        private const val POSE_REPROJECTION_PIXELS = 6.0f
        private const val MIN_MAP_X = -2.10f
        private const val MAX_MAP_X = 2.72f
        private const val MIN_MAP_Z = -2.47f
        private const val MAX_MAP_Z = 2.51f
        private const val BOUNDS_MARGIN = 0.35f
        private const val MINIMUM_CAMERA_HEIGHT = 0.45f
        private const val MAXIMUM_CAMERA_HEIGHT = 2.45f
        private const val REQUIRED_AGREEING_FRAMES = 2
        private const val CONSENSUS_WINDOW_MILLIS = 9_000L
        private const val LOCK_STALE_MILLIS = 20_000L
        private const val CONSENSUS_TRANSLATION_TOLERANCE_METRES = 0.70f
        private const val CONSENSUS_ROTATION_TOLERANCE_DEGREES = 18f
        private const val LOCK_TRANSLATION_TOLERANCE_METRES = 0.85f
        private const val LOCK_ROTATION_TOLERANCE_DEGREES = 22f
    }
}
