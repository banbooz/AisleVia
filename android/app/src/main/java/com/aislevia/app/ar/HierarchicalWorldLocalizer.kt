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
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

private data class VisualKeyframe(
    val id: Int,
    val mapPoints: List<Point3>,
    val imagePoints: List<Point>,
    val descriptors: Mat
)

private data class VisualKeyframeDatabase(
    val keyframes: List<VisualKeyframe>,
    val globalDescriptors: Mat,
    val descriptorOwners: IntArray
)

private data class KeyframeCandidate(
    val keyframe: VisualKeyframe,
    val matches: List<DMatch>,
    val score: Int
)

private data class VerifiedPose(
    val worldFromMap: Pose,
    val cameraInMap: Pose,
    val keyframeId: Int?,
    val matches: Int,
    val inliers: Int,
    val coverageCells: Int,
    val reprojectionError: Float,
    val depthAgreement: Float?,
    val confidence: Float
)

private data class HierarchicalPoseObservation(
    val pose: Pose,
    val arCameraPose: Pose,
    val timestampMillis: Long,
    val weight: Float,
    val keyframeId: Int?
)

/**
 * A small, offline VPS for the supplied room.
 *
 * Stage one retrieves the most similar rendered 3D viewpoints. Stage two removes geometrically
 * inconsistent matches inside each viewpoint, solves a metric 2D-to-3D pose, and rejects camera
 * positions outside the scanned room. A short confidence-weighted camera sequence must agree before
 * navigation starts, after which the pose is rechecked silently while ARCore tracks movement.
 */
class HierarchicalWorldLocalizer(context: Context) {
    private val appContext = context.applicationContext
    private val observations = ArrayDeque<HierarchicalPoseObservation>()
    private var lockedPose: Pose? = null
    private var lastVerifiedAtMillis = 0L
    private var lastMatchedKeyframeId: Int? = null
    private var lastConfidence = 0f
    private var conflictingStrongFrames = 0

    private val keyframes: VisualKeyframeDatabase by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        check(OpenCVLoader.initLocal()) { "OpenCV could not initialise on this phone." }
        loadKeyframes()
    }
    private val learnedMatcher by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        LearnedVisualPoseMatcher(appContext)
    }

    fun localize(
        sample: CameraFrameSample,
        arCameraPose: Pose,
        worldFloorY: Float?,
        prior: LocalizationPrior = LocalizationPrior(),
        nowMillis: Long = System.currentTimeMillis()
    ): VisualLocalizationResult {
        val database = runCatching { keyframes }.getOrElse { error ->
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
        val enhanced = Mat()
        val mask = Mat()
        val rawKeypoints = MatOfKeyPoint()
        val enhancedKeypoints = MatOfKeyPoint()
        val rawDescriptors = Mat()
        val enhancedDescriptors = Mat()
        val liveDescriptors = Mat()
        val orb = ORB.create(
            1800,
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
            val laplacian = Mat()
            val mean = MatOfDouble()
            val deviation = MatOfDouble()
            Imgproc.Laplacian(gray, laplacian, CvType.CV_64F)
            Core.meanStdDev(laplacian, mean, deviation)
            val sharpness = deviation.toArray().firstOrNull()?.let { it * it } ?: 0.0
            laplacian.release()
            mean.release()
            deviation.release()
            if (sharpness < MINIMUM_FRAME_SHARPNESS) {
                return currentResult(nowMillis, 0, 0, "Hold the phone naturally; a sharp frame will be recognised automatically.")
            }

            val clahe = Imgproc.createCLAHE(2.0, Size(8.0, 8.0))
            clahe.apply(gray, enhanced)
            clahe.clear()
            orb.detectAndCompute(gray, mask, rawKeypoints, rawDescriptors)
            orb.detectAndCompute(enhanced, mask, enhancedKeypoints, enhancedDescriptors)
            when {
                rawDescriptors.empty() -> enhancedDescriptors.copyTo(liveDescriptors)
                enhancedDescriptors.empty() -> rawDescriptors.copyTo(liveDescriptors)
                else -> Core.vconcat(listOf(rawDescriptors, enhancedDescriptors), liveDescriptors)
            }
            val framePoints = rawKeypoints.toArray() + enhancedKeypoints.toArray()
            if (liveDescriptors.empty() || liveDescriptors.rows() < 80) {
                learnedCandidate(sample, arCameraPose, worldFloorY)?.let { learned ->
                    return recordCandidate(learned, arCameraPose, nowMillis)
                }
                return currentResult(nowMillis, 0, 0, "Keep looking around slowly so the room is well lit and sharp.")
            }

            val matcher = BFMatcher.create(Core.NORM_HAMMING, false)
            val retrievalSource = if (rawDescriptors.empty()) liveDescriptors else rawDescriptors
            val retrievalDescriptors = retrievalSource.rowRange(
                0,
                min(RETRIEVAL_LIVE_DESCRIPTORS, retrievalSource.rows())
            )
            val globalMatches = MatOfDMatch()
            matcher.match(retrievalDescriptors, database.globalDescriptors, globalMatches)
            val retrievalScores = HashMap<Int, Int>()
            globalMatches.toArray().forEach { match ->
                if (match.trainIdx in database.descriptorOwners.indices && match.distance <= RETRIEVAL_MAX_DISTANCE) {
                    val owner = database.descriptorOwners[match.trainIdx]
                    retrievalScores[owner] = retrievalScores.getOrDefault(owner, 0) +
                        (RETRIEVAL_MAX_DISTANCE - match.distance).toInt().coerceAtLeast(1)
                }
            }
            globalMatches.release()
            retrievalDescriptors.release()
            val shortlistedKeyframes = database.keyframes
                .sortedByDescending { keyframe ->
                    retrievalScores.getOrDefault(keyframe.id, 0) +
                        if (keyframe.id in prior.preferredKeyframeIds) PRIOR_RETRIEVAL_BOOST else 0
                }
                .take(RETRIEVAL_KEYFRAME_LIMIT)
            val candidates = ArrayList<KeyframeCandidate>()
            shortlistedKeyframes.forEach { keyframe ->
                val neighbours = ArrayList<org.opencv.core.MatOfDMatch>()
                matcher.knnMatch(liveDescriptors, keyframe.descriptors, neighbours, 2)
                val broadByReference = HashMap<Int, DMatch>()
                var strictMatches = 0
                neighbours.forEach { pair ->
                    val pairMatches = pair.toArray()
                    if (pairMatches.size >= 2) {
                        val best = pairMatches[0]
                        val second = pairMatches[1]
                        if (best.distance < second.distance * BROAD_RATIO) {
                            val previous = broadByReference[best.trainIdx]
                            if (previous == null || best.distance < previous.distance) {
                                broadByReference[best.trainIdx] = best
                            }
                        }
                        if (best.distance < second.distance * STRICT_RATIO) strictMatches += 1
                    }
                    pair.release()
                }
                val broadMatches = broadByReference.values.toList()
                if (broadMatches.size >= MINIMUM_KEYFRAME_MATCHES) {
                    candidates += KeyframeCandidate(
                        keyframe = keyframe,
                        matches = broadMatches,
                        score = strictMatches * 4 + broadMatches.size +
                            if (keyframe.id in prior.preferredKeyframeIds) PRIOR_KEYFRAME_BOOST else 0
                    )
                }
            }
            matcher.clear()

            if (candidates.isEmpty()) {
                learnedCandidate(sample, arCameraPose, worldFloorY)?.let { learned ->
                    return recordCandidate(learned, arCameraPose, nowMillis)
                }
                return currentResult(
                    nowMillis,
                    0,
                    0,
                    "Searching the saved room views… keep turning naturally; no specific object is required."
                )
            }

            val cameraMatrix = Mat.eye(3, 3, CvType.CV_64F)
            cameraMatrix.put(0, 0, sample.focalX * scale)
            cameraMatrix.put(1, 1, sample.focalY * scale)
            cameraMatrix.put(0, 2, sample.principalX * scale)
            cameraMatrix.put(1, 2, sample.principalY * scale)
            val best = candidates
                .sortedByDescending(KeyframeCandidate::score)
                .take(MAXIMUM_POSE_CANDIDATES)
                .mapNotNull { candidate ->
                    solveCandidate(candidate, framePoints, cameraMatrix, arCameraPose, worldFloorY, sample)
                }
                .maxWithOrNull(
                    compareBy<VerifiedPose> { it.confidence }
                        .thenBy { it.inliers }
                        .thenBy { it.matches }
                )
            cameraMatrix.release()

            if (best == null) {
                learnedCandidate(sample, arCameraPose, worldFloorY)?.let { learned ->
                    return recordCandidate(learned, arCameraPose, nowMillis)
                }
                val top = candidates.maxBy(KeyframeCandidate::score)
                return currentResult(
                    nowMillis,
                    top.matches.size,
                    0,
                    "Room recognised, but the position is still being checked. Keep moving slowly."
                )
            }
            return recordCandidate(best, arCameraPose, nowMillis)
        } finally {
            orb.clear()
            liveDescriptors.release()
            enhancedDescriptors.release()
            rawDescriptors.release()
            enhancedKeypoints.release()
            rawKeypoints.release()
            mask.release()
            enhanced.release()
            gray.release()
            source.release()
        }
    }

    private fun learnedCandidate(
        sample: CameraFrameSample,
        arCameraPose: Pose,
        worldFloorY: Float?
    ): VerifiedPose? = runCatching {
        learnedMatcher.solve(sample)?.let { learned ->
            var worldFromMap = arCameraPose.compose(learned.cameraFromMap)
            if (worldFloorY != null) {
                worldFromMap = PoseMath.floorConstrainedPose(worldFromMap, worldFloorY)
            }
            VerifiedPose(
                worldFromMap = worldFromMap,
                cameraInMap = learned.cameraInMap,
                keyframeId = null,
                matches = learned.matches,
                inliers = learned.inliers,
                coverageCells = learned.coverageCells,
                reprojectionError = learned.reprojectionError,
                depthAgreement = null,
                confidence = learned.confidence
            )
        }
    }.getOrNull()

    private fun solveCandidate(
        candidate: KeyframeCandidate,
        frameKeypoints: Array<org.opencv.core.KeyPoint>,
        cameraMatrix: Mat,
        arCameraPose: Pose,
        worldFloorY: Float?,
        sample: CameraFrameSample
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

        val objectPointList = consistentMatches.map { candidate.keyframe.mapPoints[it.trainIdx] }
        val imagePointList = consistentMatches.map { frameKeypoints[it.queryIdx].pt }
        val objectPoints = MatOfPoint3f().apply { fromList(objectPointList) }
        val imagePoints = MatOfPoint2f().apply { fromList(imagePointList) }
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
        val inlierIndices = if (solved) {
            List(inliers.rows()) { row -> inliers.get(row, 0).first().toInt() }
                .filter { it in consistentMatches.indices }
        } else {
            emptyList()
        }
        val inlierCount = inlierIndices.size
        val inlierRatio = inlierCount.toFloat() / consistentMatches.size.toFloat()
        var result: VerifiedPose? = null
        if (solved && inlierCount >= MINIMUM_POSE_INLIERS && inlierRatio >= MINIMUM_POSE_INLIER_RATIO) {
            val refinedObjectList = inlierIndices.map(objectPointList::get)
            val refinedImageList = inlierIndices.map(imagePointList::get)
            val refinedObjects = MatOfPoint3f().apply { fromList(refinedObjectList) }
            val refinedImages = MatOfPoint2f().apply { fromList(refinedImageList) }
            Calib3d.solvePnP(
                refinedObjects,
                refinedImages,
                cameraMatrix,
                distortion,
                rotationVector,
                translationVector,
                true,
                Calib3d.SOLVEPNP_ITERATIVE
            )
            val projected = MatOfPoint2f()
            Calib3d.projectPoints(
                refinedObjects,
                rotationVector,
                translationVector,
                cameraMatrix,
                distortion,
                projected
            )
            val projectedPoints = projected.toArray()
            val reprojectionError = sqrt(
                refinedImageList.zip(projectedPoints.toList()).sumOf { (observed, estimate) ->
                    val dx = observed.x - estimate.x
                    val dy = observed.y - estimate.y
                    dx * dx + dy * dy
                } / refinedImageList.size.toDouble().coerceAtLeast(1.0)
            ).toFloat()
            val coverageCells = spatialCoverage(refinedImageList, cameraMatrix)
            val depthAgreement = depthAgreement(
                sample,
                refinedImageList,
                refinedObjectList,
                cameraMatrix,
                rotationVector,
                translationVector
            )
            val cameraFromMap = openCvPose(rotationVector, translationVector)
            val cameraInMap = cameraFromMap.inverse()
            val cameraPosition = cameraInMap.translation
            val insideScan = cameraPosition[0] in (MIN_MAP_X - BOUNDS_MARGIN)..(MAX_MAP_X + BOUNDS_MARGIN) &&
                cameraPosition[1] in MINIMUM_CAMERA_HEIGHT..MAXIMUM_CAMERA_HEIGHT &&
                cameraPosition[2] in (MIN_MAP_Z - BOUNDS_MARGIN)..(MAX_MAP_Z + BOUNDS_MARGIN)
            val confidence = poseConfidence(
                inlierCount,
                inlierRatio,
                coverageCells,
                reprojectionError,
                depthAgreement
            )
            if (insideScan &&
                coverageCells >= MINIMUM_COVERAGE_CELLS &&
                reprojectionError <= MAXIMUM_REPROJECTION_ERROR &&
                (depthAgreement == null || depthAgreement >= MINIMUM_DEPTH_AGREEMENT) &&
                confidence >= MINIMUM_POSE_CONFIDENCE
            ) {
                var worldFromMap = arCameraPose.compose(cameraFromMap)
                if (worldFloorY != null) {
                    worldFromMap = PoseMath.floorConstrainedPose(worldFromMap, worldFloorY)
                }
                result = VerifiedPose(
                    worldFromMap = worldFromMap,
                    cameraInMap = cameraInMap,
                    keyframeId = candidate.keyframe.id,
                    matches = validMatches.size,
                    inliers = inlierCount,
                    coverageCells = coverageCells,
                    reprojectionError = reprojectionError,
                    depthAgreement = depthAgreement,
                    confidence = confidence
                )
            }
            projected.release()
            refinedImages.release()
            refinedObjects.release()
        }
        objectPoints.release()
        imagePoints.release()
        distortion.release()
        rotationVector.release()
        translationVector.release()
        inliers.release()
        return result
    }

    private fun spatialCoverage(points: List<Point>, cameraMatrix: Mat): Int {
        val width = (cameraMatrix.get(0, 2).first() * 2.0).coerceAtLeast(1.0)
        val height = (cameraMatrix.get(1, 2).first() * 2.0).coerceAtLeast(1.0)
        return points.map { point ->
            val column = (point.x / width * COVERAGE_COLUMNS).toInt().coerceIn(0, COVERAGE_COLUMNS - 1)
            val row = (point.y / height * COVERAGE_ROWS).toInt().coerceIn(0, COVERAGE_ROWS - 1)
            row * COVERAGE_COLUMNS + column
        }.toSet().size
    }

    private fun poseConfidence(
        inliers: Int,
        inlierRatio: Float,
        coverageCells: Int,
        reprojectionError: Float,
        depthAgreement: Float?
    ): Float {
        val inlierScore = ((inliers - 6) / 18f).coerceIn(0f, 1f)
        val coverageScore = (coverageCells / 8f).coerceIn(0f, 1f)
        val reprojectionScore = (1f - reprojectionError / MAXIMUM_REPROJECTION_ERROR).coerceIn(0f, 1f)
        val visualScore = inlierRatio.coerceIn(0f, 1f) * 0.35f +
            inlierScore * 0.25f +
            coverageScore * 0.20f +
            reprojectionScore * 0.20f
        return if (depthAgreement == null) visualScore else visualScore * 0.82f + depthAgreement * 0.18f
    }

    /** Compares solved mesh depth with ARCore depth when the phone supports it. */
    private fun depthAgreement(
        sample: CameraFrameSample,
        imagePoints: List<Point>,
        objectPoints: List<Point3>,
        cameraMatrix: Mat,
        rotationVector: Mat,
        translationVector: Mat
    ): Float? {
        val depth = sample.depthMillimetres ?: return null
        if (sample.depthWidth <= 0 || sample.depthHeight <= 0) return null
        val frameWidth = (cameraMatrix.get(0, 2).first() * 2.0).coerceAtLeast(1.0)
        val frameHeight = (cameraMatrix.get(1, 2).first() * 2.0).coerceAtLeast(1.0)
        val rotationMatrix = Mat()
        Calib3d.Rodrigues(rotationVector, rotationMatrix)
        val rotation = DoubleArray(9).also { rotationMatrix.get(0, 0, it) }
        val translation = DoubleArray(3).also { translationVector.get(0, 0, it) }
        rotationMatrix.release()
        var valid = 0
        var agreeing = 0
        imagePoints.zip(objectPoints).forEach { (imagePoint, objectPoint) ->
            val depthX = (imagePoint.x / frameWidth * sample.depthWidth).toInt()
                .coerceIn(0, sample.depthWidth - 1)
            val depthY = (imagePoint.y / frameHeight * sample.depthHeight).toInt()
                .coerceIn(0, sample.depthHeight - 1)
            val measuredValues = buildList {
                for (offsetY in -1..1) for (offsetX in -1..1) {
                    val x = (depthX + offsetX).coerceIn(0, sample.depthWidth - 1)
                    val y = (depthY + offsetY).coerceIn(0, sample.depthHeight - 1)
                    val value = depth[y * sample.depthWidth + x]
                    if (value in MINIMUM_VALID_DEPTH_MM..MAXIMUM_VALID_DEPTH_MM) add(value)
                }
            }.sorted()
            if (measuredValues.isEmpty()) return@forEach
            val measuredMetres = measuredValues[measuredValues.size / 2] / 1000.0
            val predictedMetres = rotation[6] * objectPoint.x +
                rotation[7] * objectPoint.y +
                rotation[8] * objectPoint.z +
                translation[2]
            if (predictedMetres <= 0.1) return@forEach
            valid += 1
            val tolerance = max(DEPTH_ABSOLUTE_TOLERANCE_METRES, predictedMetres * DEPTH_RELATIVE_TOLERANCE)
            if (abs(measuredMetres - predictedMetres) <= tolerance) agreeing += 1
        }
        return if (valid >= MINIMUM_DEPTH_SAMPLES) agreeing.toFloat() / valid.toFloat() else null
    }

    private fun recordCandidate(
        candidate: VerifiedPose,
        arCameraPose: Pose,
        nowMillis: Long
    ): VisualLocalizationResult {
        observations.removeAll { nowMillis - it.timestampMillis > CONSENSUS_WINDOW_MILLIS }
        val existingLock = lockedPose
        if (existingLock != null) {
            val agrees = PoseMath.translationDistance(existingLock, candidate.worldFromMap) <=
                LOCK_TRANSLATION_TOLERANCE_METRES &&
                PoseMath.rotationDistanceDegrees(existingLock, candidate.worldFromMap) <=
                LOCK_ROTATION_TOLERANCE_DEGREES
            if (agrees) {
                conflictingStrongFrames = 0
                lockedPose = PoseMath.weightedAverage(
                    listOf(existingLock to 4f, candidate.worldFromMap to candidate.confidence)
                )
                lastVerifiedAtMillis = nowMillis
                lastMatchedKeyframeId = candidate.keyframeId
                lastConfidence = candidate.confidence
                return VisualLocalizationResult(
                    VisualLocalizationPhase.LOCKED,
                    lockedPose,
                    candidate.matches,
                    candidate.inliers,
                    REQUIRED_AGREEING_FRAMES_NORMAL,
                    "Navigation is live and the position is being checked continuously.",
                    candidate.keyframeId,
                    candidate.confidence
                )
            }

            if (candidate.confidence >= STRONG_CONFLICT_CONFIDENCE) conflictingStrongFrames += 1
            if (conflictingStrongFrames < REQUIRED_STRONG_CONFLICTS_TO_RESET) {
                return currentResult(
                    nowMillis,
                    candidate.matches,
                    candidate.inliers,
                    "A conflicting view was ignored while the saved position is rechecked."
                )
            }
            lockedPose = null
            observations.clear()
            conflictingStrongFrames = 0
        }

        val previous = observations.lastOrNull()
        val independent = previous == null ||
            nowMillis - previous.timestampMillis >= MAXIMUM_STATIONARY_SAMPLE_GAP_MILLIS ||
            PoseMath.translationDistance(previous.arCameraPose, arCameraPose) >= MINIMUM_CAMERA_MOVEMENT_METRES ||
            PoseMath.rotationDistanceDegrees(previous.arCameraPose, arCameraPose) >= MINIMUM_CAMERA_ROTATION_DEGREES
        if (independent) {
            observations += HierarchicalPoseObservation(
                pose = candidate.worldFromMap,
                arCameraPose = arCameraPose,
                timestampMillis = nowMillis,
                weight = candidate.confidence,
                keyframeId = candidate.keyframeId
            )
        }
        val cluster = observations.filter {
            PoseMath.translationDistance(it.pose, candidate.worldFromMap) <= CONSENSUS_TRANSLATION_TOLERANCE_METRES &&
                PoseMath.rotationDistanceDegrees(it.pose, candidate.worldFromMap) <= CONSENSUS_ROTATION_TOLERANCE_DEGREES
        }
        val requiredFrames = if (candidate.confidence >= HIGH_CONFIDENCE_LOCK) {
            REQUIRED_AGREEING_FRAMES_HIGH_CONFIDENCE
        } else {
            REQUIRED_AGREEING_FRAMES_NORMAL
        }
        if (cluster.size >= requiredFrames) {
            lockedPose = PoseMath.weightedAverage(cluster.map { it.pose to it.weight })
            lastVerifiedAtMillis = nowMillis
            lastMatchedKeyframeId = candidate.keyframeId
            lastConfidence = cluster.map(HierarchicalPoseObservation::weight).average().toFloat()
            return VisualLocalizationResult(
                VisualLocalizationPhase.LOCKED,
                lockedPose,
                candidate.matches,
                candidate.inliers,
                cluster.size,
                "Room and position recognised automatically.",
                candidate.keyframeId,
                lastConfidence
            )
        }
        return VisualLocalizationResult(
            VisualLocalizationPhase.CHECKING,
            null,
            candidate.matches,
            candidate.inliers,
            cluster.size,
            "Position found and is being confirmed automatically.",
            candidate.keyframeId,
            candidate.confidence
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
                REQUIRED_AGREEING_FRAMES_NORMAL,
                "Navigation is live while the room is rechecked.",
                lastMatchedKeyframeId,
                lastConfidence
            )
        } else {
            if (nowMillis - lastVerifiedAtMillis > LOCK_STALE_MILLIS) {
                lockedPose = null
                lastConfidence = 0f
            }
            VisualLocalizationResult(
                VisualLocalizationPhase.SEARCHING,
                null,
                matches,
                inliers,
                0,
                searchingMessage,
                lastMatchedKeyframeId,
                0f
            )
        }
    }

    private fun loadKeyframes(): VisualKeyframeDatabase {
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
        check(keyframeCount in 32..512) { "Invalid keyframe count." }
        val loaded = List(keyframeCount) {
            val id = buffer.int
            val featureCount = buffer.int
            check(featureCount in 60..1200) { "Invalid keyframe feature count." }
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
        val globalDescriptors = Mat()
        Core.vconcat(loaded.map(VisualKeyframe::descriptors), globalDescriptors)
        val descriptorOwners = IntArray(globalDescriptors.rows())
        var descriptorOffset = 0
        loaded.forEach { keyframe ->
            repeat(keyframe.descriptors.rows()) {
                descriptorOwners[descriptorOffset++] = keyframe.id
            }
        }
        return VisualKeyframeDatabase(loaded, globalDescriptors, descriptorOwners)
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
        private val KEYFRAME_ASSETS = (0..13).map { part ->
            "world/living_room/visual_keyframes/part_${part.toString().padStart(2, '0')}.b64"
        }
        private const val DESCRIPTOR_BYTES = 32
        private const val MINIMUM_FRAME_SHARPNESS = 55.0
        private const val RETRIEVAL_LIVE_DESCRIPTORS = 900
        private const val RETRIEVAL_MAX_DISTANCE = 72f
        private const val RETRIEVAL_KEYFRAME_LIMIT = 52
        private const val PRIOR_RETRIEVAL_BOOST = 1_200
        private const val STRICT_RATIO = 0.78f
        private const val BROAD_RATIO = 0.87f
        private const val PRIOR_KEYFRAME_BOOST = 220
        private const val MINIMUM_KEYFRAME_MATCHES = 24
        private const val MAXIMUM_POSE_CANDIDATES = 24
        private const val MINIMUM_HOMOGRAPHY_INLIERS = 10
        private const val MINIMUM_POSE_INLIERS = 8
        private const val MINIMUM_POSE_INLIER_RATIO = 0.45f
        private const val HOMOGRAPHY_REPROJECTION_PIXELS = 6.0
        private const val POSE_REPROJECTION_PIXELS = 6.0f
        private const val MAXIMUM_REPROJECTION_ERROR = 5.5f
        private const val MINIMUM_POSE_CONFIDENCE = 0.42f
        private const val COVERAGE_COLUMNS = 4
        private const val COVERAGE_ROWS = 3
        private const val MINIMUM_COVERAGE_CELLS = 4
        private const val MINIMUM_VALID_DEPTH_MM = 250
        private const val MAXIMUM_VALID_DEPTH_MM = 8_000
        private const val MINIMUM_DEPTH_SAMPLES = 4
        private const val DEPTH_ABSOLUTE_TOLERANCE_METRES = 0.45
        private const val DEPTH_RELATIVE_TOLERANCE = 0.25
        private const val MINIMUM_DEPTH_AGREEMENT = 0.35f
        private const val MIN_MAP_X = -2.10f
        private const val MAX_MAP_X = 2.72f
        private const val MIN_MAP_Z = -2.47f
        private const val MAX_MAP_Z = 2.51f
        private const val BOUNDS_MARGIN = 0.35f
        private const val MINIMUM_CAMERA_HEIGHT = 0.45f
        private const val MAXIMUM_CAMERA_HEIGHT = 2.45f
        private const val REQUIRED_AGREEING_FRAMES_HIGH_CONFIDENCE = 2
        private const val REQUIRED_AGREEING_FRAMES_NORMAL = 3
        private const val HIGH_CONFIDENCE_LOCK = 0.72f
        private const val CONSENSUS_WINDOW_MILLIS = 7_000L
        private const val LOCK_STALE_MILLIS = 15_000L
        private const val CONSENSUS_TRANSLATION_TOLERANCE_METRES = 0.55f
        private const val CONSENSUS_ROTATION_TOLERANCE_DEGREES = 13f
        private const val LOCK_TRANSLATION_TOLERANCE_METRES = 0.65f
        private const val LOCK_ROTATION_TOLERANCE_DEGREES = 16f
        private const val MINIMUM_CAMERA_MOVEMENT_METRES = 0.025f
        private const val MINIMUM_CAMERA_ROTATION_DEGREES = 1.5f
        private const val MAXIMUM_STATIONARY_SAMPLE_GAP_MILLIS = 900L
        private const val STRONG_CONFLICT_CONFIDENCE = 0.72f
        private const val REQUIRED_STRONG_CONFLICTS_TO_RESET = 3
    }
}
