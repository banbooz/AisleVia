package com.aislevia.app.ar

import android.content.Context
import android.util.Base64
import org.opencv.calib3d.Calib3d
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.DMatch
import org.opencv.core.Mat
import org.opencv.core.MatOfDMatch
import org.opencv.core.MatOfDouble
import org.opencv.core.MatOfPoint2f
import org.opencv.core.MatOfPoint3f
import org.opencv.core.Point
import org.opencv.core.Point3
import org.opencv.core.Size
import org.opencv.features2d.BFMatcher
import org.opencv.imgproc.Imgproc
import org.tensorflow.lite.Interpreter
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPInputStream
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

internal data class LearnedMetricPose(
    val cameraFromMap: com.google.ar.core.Pose,
    val cameraInMap: com.google.ar.core.Pose,
    val matches: Int,
    val inliers: Int,
    val coverageCells: Int,
    val reprojectionError: Float,
    val confidence: Float
)

private data class LearnedFeatureMap(
    val points: List<Point3>,
    val descriptors: Mat
)

private data class LearnedImageFeatures(
    val points: List<Point>,
    val descriptors: Mat
)

private data class InferenceBuffers(
    val input: ByteBuffer,
    val outputs: Array<ByteBuffer>,
    val outputMap: Map<Int, Any>,
    val values: Array<FloatArray>
)

private data class ScoredPixel(val x: Int, val y: Int, val score: Float)

/**
 * XFeat fallback over the scan's original photographic texture.
 *
 * This path is deliberately strict: it can rescue the synthetic-view matcher when appearance
 * differs, but it still needs a metric PnP solution spread across the image. It never locks from
 * room classification or Wi-Fi alone.
 */
internal class LearnedVisualPoseMatcher(context: Context) {
    private val appContext = context.applicationContext
    private val featureMap: LearnedFeatureMap by lazy(LazyThreadSafetyMode.SYNCHRONIZED, ::loadMap)
    private val interpreter: Interpreter by lazy(LazyThreadSafetyMode.SYNCHRONIZED, ::loadInterpreter)
    private val inferenceBuffers: InferenceBuffers by lazy(LazyThreadSafetyMode.SYNCHRONIZED, ::createInferenceBuffers)
    private val probabilityImage = FloatArray(INPUT_WIDTH * INPUT_HEIGHT)

    fun solve(sample: CameraFrameSample): LearnedMetricPose? {
        val live = extract(sample)
        if (live.descriptors.rows() < MINIMUM_LIVE_FEATURES) {
            live.descriptors.release()
            return null
        }
        val matcher = BFMatcher.create(Core.NORM_L2, false)
        val neighbours = ArrayList<MatOfDMatch>()
        return try {
            matcher.knnMatch(live.descriptors, featureMap.descriptors, neighbours, 2)
            val uniqueByMapFeature = HashMap<Int, DMatch>()
            neighbours.forEach { pair ->
                val matches = pair.toArray()
                if (matches.size >= 2 && matches[0].distance < matches[1].distance * RATIO_THRESHOLD) {
                    val best = matches[0]
                    val previous = uniqueByMapFeature[best.trainIdx]
                    if (previous == null || best.distance < previous.distance) {
                        uniqueByMapFeature[best.trainIdx] = best
                    }
                }
            }
            solvePose(sample, live.points, uniqueByMapFeature.values.toList())
        } finally {
            neighbours.forEach(MatOfDMatch::release)
            matcher.clear()
            live.descriptors.release()
        }
    }

    private fun extract(sample: CameraFrameSample): LearnedImageFeatures {
        val source = Mat(sample.height, sample.width, CvType.CV_8UC1)
        val resized = Mat()
        source.put(0, 0, sample.luminance)
        Imgproc.resize(source, resized, Size(INPUT_WIDTH.toDouble(), INPUT_HEIGHT.toDouble()))
        val pixels = ByteArray(INPUT_WIDTH * INPUT_HEIGHT)
        resized.get(0, 0, pixels)
        source.release()
        resized.release()

        var sum = 0.0
        var squared = 0.0
        pixels.forEach { value ->
            val number = (value.toInt() and 0xff).toDouble()
            sum += number
            squared += number * number
        }
        val mean = sum / pixels.size
        val variance = max(1e-5, squared / pixels.size - mean * mean)
        val inverseDeviation = (1.0 / sqrt(variance + 1e-5)).toFloat()
        val input = inferenceBuffers.input
        input.clear()
        pixels.forEach { value -> input.putFloat(((value.toInt() and 0xff) - mean).toFloat() * inverseDeviation) }
        input.rewind()

        inferenceBuffers.outputs.forEach(ByteBuffer::clear)
        interpreter.runForMultipleInputsOutputs(arrayOf(input), inferenceBuffers.outputMap)

        var denseFeatures: FloatArray? = null
        var keypointLogits: FloatArray? = null
        var reliability: FloatArray? = null
        inferenceBuffers.outputs.forEachIndexed { index, buffer ->
            buffer.rewind()
            val values = inferenceBuffers.values[index]
            buffer.asFloatBuffer().get(values)
            when (interpreter.getOutputTensor(index).shape().getOrNull(1)) {
                64 -> denseFeatures = values
                65 -> keypointLogits = values
                1 -> reliability = values
            }
        }
        return decode(
            denseFeatures ?: return LearnedImageFeatures(emptyList(), Mat()),
            keypointLogits ?: return LearnedImageFeatures(emptyList(), Mat()),
            reliability ?: return LearnedImageFeatures(emptyList(), Mat())
        )
    }

    private fun decode(
        denseFeatures: FloatArray,
        logits: FloatArray,
        reliability: FloatArray
    ): LearnedImageFeatures {
        probabilityImage.fill(0f)
        for (cellY in 0 until GRID_HEIGHT) for (cellX in 0 until GRID_WIDTH) {
            var maximum = Float.NEGATIVE_INFINITY
            for (channel in 0 until 65) {
                maximum = max(maximum, logits[tensorIndex(channel, cellY, cellX)])
            }
            var denominator = 0.0
            for (channel in 0 until 65) {
                denominator += exp((logits[tensorIndex(channel, cellY, cellX)] - maximum).toDouble())
            }
            for (position in 0 until 64) {
                val x = cellX * 8 + position % 8
                val y = cellY * 8 + position / 8
                probabilityImage[y * INPUT_WIDTH + x] =
                    (exp((logits[tensorIndex(position, cellY, cellX)] - maximum).toDouble()) / denominator).toFloat()
            }
        }

        val candidates = ArrayList<ScoredPixel>()
        for (y in 2 until INPUT_HEIGHT - 2) for (x in 2 until INPUT_WIDTH - 2) {
            val probability = probabilityImage[y * INPUT_WIDTH + x]
            if (probability <= KEYPOINT_THRESHOLD) continue
            var isMaximum = true
            loop@ for (offsetY in -2..2) for (offsetX in -2..2) {
                if (probabilityImage[(y + offsetY) * INPUT_WIDTH + x + offsetX] > probability) {
                    isMaximum = false
                    break@loop
                }
            }
            if (isMaximum) {
                val cellReliability = reliability[min(GRID_HEIGHT - 1, y / 8) * GRID_WIDTH + min(GRID_WIDTH - 1, x / 8)]
                candidates += ScoredPixel(x, y, probability * cellReliability)
            }
        }
        val selected = candidates.sortedByDescending(ScoredPixel::score).take(MAXIMUM_LIVE_FEATURES)
        val points = selected.map { Point(it.x.toDouble(), it.y.toDouble()) }
        val descriptors = Mat(selected.size, DESCRIPTOR_FLOATS, CvType.CV_32FC1)
        val descriptorValues = FloatArray(selected.size * DESCRIPTOR_FLOATS)
        selected.forEachIndexed { feature, pixel ->
            val gridX = (pixel.x / 8f).coerceIn(0f, GRID_WIDTH - 1.001f)
            val gridY = (pixel.y / 8f).coerceIn(0f, GRID_HEIGHT - 1.001f)
            val x0 = gridX.toInt(); val y0 = gridY.toInt()
            val x1 = min(GRID_WIDTH - 1, x0 + 1); val y1 = min(GRID_HEIGHT - 1, y0 + 1)
            val wx = gridX - x0; val wy = gridY - y0
            var norm = 0f
            for (channel in 0 until DESCRIPTOR_FLOATS) {
                val value = denseFeatures[tensorIndex(channel, y0, x0)] * (1 - wx) * (1 - wy) +
                    denseFeatures[tensorIndex(channel, y0, x1)] * wx * (1 - wy) +
                    denseFeatures[tensorIndex(channel, y1, x0)] * (1 - wx) * wy +
                    denseFeatures[tensorIndex(channel, y1, x1)] * wx * wy
                descriptorValues[feature * DESCRIPTOR_FLOATS + channel] = value
                norm += value * value
            }
            val inverseNorm = 1f / sqrt(max(norm, 1e-8f))
            for (channel in 0 until DESCRIPTOR_FLOATS) {
                descriptorValues[feature * DESCRIPTOR_FLOATS + channel] *= inverseNorm
            }
        }
        if (selected.isNotEmpty()) descriptors.put(0, 0, descriptorValues)
        return LearnedImageFeatures(points, descriptors)
    }

    private fun solvePose(
        sample: CameraFrameSample,
        livePoints: List<Point>,
        matches: List<DMatch>
    ): LearnedMetricPose? {
        if (matches.size < MINIMUM_RATIO_MATCHES) return null
        val objectList = matches.map { featureMap.points[it.trainIdx] }
        val imageList = matches.map { livePoints[it.queryIdx] }
        val objects = MatOfPoint3f().apply { fromList(objectList) }
        val images = MatOfPoint2f().apply { fromList(imageList) }
        val camera = Mat.eye(3, 3, CvType.CV_64F).apply {
            put(0, 0, sample.focalX * INPUT_WIDTH / sample.width)
            put(1, 1, sample.focalY * INPUT_HEIGHT / sample.height)
            put(0, 2, sample.principalX * INPUT_WIDTH / sample.width)
            put(1, 2, sample.principalY * INPUT_HEIGHT / sample.height)
        }
        val distortion = MatOfDouble()
        val rotation = Mat(); val translation = Mat(); val inliers = Mat()
        val solved = Calib3d.solvePnPRansac(
            objects, images, camera, distortion, rotation, translation, false,
            5_000, POSE_REPROJECTION_PIXELS, 0.9999, inliers, Calib3d.SOLVEPNP_AP3P
        )
        val indices = if (solved) List(inliers.rows()) { inliers.get(it, 0).first().toInt() } else emptyList()
        var result: LearnedMetricPose? = null
        if (indices.size >= MINIMUM_POSE_INLIERS) {
            val inlierObjects = indices.map(objectList::get)
            val inlierImages = indices.map(imageList::get)
            val refinedObjects = MatOfPoint3f().apply { fromList(inlierObjects) }
            val refinedImages = MatOfPoint2f().apply { fromList(inlierImages) }
            Calib3d.solvePnP(refinedObjects, refinedImages, camera, distortion, rotation, translation, true, Calib3d.SOLVEPNP_ITERATIVE)
            val projected = MatOfPoint2f()
            Calib3d.projectPoints(refinedObjects, rotation, translation, camera, distortion, projected)
            val estimates = projected.toArray()
            val error = sqrt(inlierImages.zip(estimates.toList()).sumOf { (observed, estimate) ->
                val dx = observed.x - estimate.x; val dy = observed.y - estimate.y
                dx * dx + dy * dy
            } / inlierImages.size.toDouble()).toFloat()
            val coverage = inlierImages.map { point ->
                val column = (point.x / INPUT_WIDTH * 4).toInt().coerceIn(0, 3)
                val row = (point.y / INPUT_HEIGHT * 3).toInt().coerceIn(0, 2)
                row * 4 + column
            }.toSet().size
            val cameraFromMap = openCvPose(rotation, translation)
            val cameraInMap = cameraFromMap.inverse()
            val position = cameraInMap.translation
            val ratio = indices.size.toFloat() / matches.size.toFloat()
            val confidence = ((indices.size - 5) / 12f).coerceIn(0f, 1f) * 0.38f +
                (coverage / 7f).coerceIn(0f, 1f) * 0.27f +
                (1f - error / MAXIMUM_REPROJECTION_ERROR).coerceIn(0f, 1f) * 0.20f +
                (ratio / 0.35f).coerceIn(0f, 1f) * 0.15f
            val inside = position[0] in -2.45f..3.07f && position[1] in 0.45f..2.45f && position[2] in -2.82f..2.86f
            if (inside && coverage >= MINIMUM_COVERAGE_CELLS && error <= MAXIMUM_REPROJECTION_ERROR && confidence >= MINIMUM_CONFIDENCE) {
                result = LearnedMetricPose(cameraFromMap, cameraInMap, matches.size, indices.size, coverage, error, confidence)
            }
            projected.release(); refinedImages.release(); refinedObjects.release()
        }
        inliers.release(); translation.release(); rotation.release(); distortion.release(); camera.release(); images.release(); objects.release()
        return result
    }

    private fun loadInterpreter(): Interpreter {
        val encoded = MODEL_ASSETS.joinToString(separator = "") { asset ->
            appContext.assets.open(asset).bufferedReader().use { it.readText() }
        }
        val bytes = Base64.decode(encoded, Base64.DEFAULT)
        val model = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder())
        model.put(bytes).rewind()
        return Interpreter(model, Interpreter.Options().setNumThreads(4))
    }

    private fun createInferenceBuffers(): InferenceBuffers {
        val input = ByteBuffer.allocateDirect(INPUT_WIDTH * INPUT_HEIGHT * 4).order(ByteOrder.nativeOrder())
        val outputMap = HashMap<Int, Any>()
        val outputs = Array(interpreter.outputTensorCount) { index ->
            val elements = interpreter.getOutputTensor(index).shape().fold(1, Int::times)
            ByteBuffer.allocateDirect(elements * 4).order(ByteOrder.nativeOrder()).also { outputMap[index] = it }
        }
        return InferenceBuffers(
            input = input,
            outputs = outputs,
            outputMap = outputMap,
            values = Array(outputs.size) { index -> FloatArray(outputs[index].capacity() / 4) }
        )
    }

    private fun loadMap(): LearnedFeatureMap {
        val encoded = MAP_ASSETS.joinToString(separator = "") { asset ->
            appContext.assets.open(asset).bufferedReader().use { it.readText() }
        }
        val bytes = GZIPInputStream(ByteArrayInputStream(Base64.decode(encoded, Base64.DEFAULT))).use { it.readBytes() }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val magic = ByteArray(4).also(buffer::get).toString(Charsets.US_ASCII)
        check(magic == "AVXF" && buffer.int == 1) { "Unsupported learned room map." }
        val count = buffer.int
        check(count in 1_000..30_000) { "Invalid learned room map." }
        val points = ArrayList<Point3>(count)
        val values = FloatArray(count * DESCRIPTOR_FLOATS)
        repeat(count) { feature ->
            points += Point3(buffer.float.toDouble(), buffer.float.toDouble(), buffer.float.toDouble())
            var norm = 0f
            repeat(DESCRIPTOR_FLOATS) { channel ->
                val value = buffer.get().toFloat() / 127f
                values[feature * DESCRIPTOR_FLOATS + channel] = value
                norm += value * value
            }
            val inverseNorm = 1f / sqrt(max(norm, 1e-8f))
            repeat(DESCRIPTOR_FLOATS) { channel -> values[feature * DESCRIPTOR_FLOATS + channel] *= inverseNorm }
        }
        return LearnedFeatureMap(points, Mat(count, DESCRIPTOR_FLOATS, CvType.CV_32FC1).apply { put(0, 0, values) })
    }

    private fun tensorIndex(channel: Int, y: Int, x: Int): Int = (channel * GRID_HEIGHT + y) * GRID_WIDTH + x

    /** Converts OpenCV camera axes to ARCore camera axes. */
    private fun openCvPose(rotationVector: Mat, translationVector: Mat): com.google.ar.core.Pose {
        val cvRotation = Mat(); Calib3d.Rodrigues(rotationVector, cvRotation)
        val r = DoubleArray(9).also { cvRotation.get(0, 0, it) }
        val t = DoubleArray(3).also { translationVector.get(0, 0, it) }
        cvRotation.release()
        val rotation = floatArrayOf(
            r[0].toFloat(), r[1].toFloat(), r[2].toFloat(),
            (-r[3]).toFloat(), (-r[4]).toFloat(), (-r[5]).toFloat(),
            (-r[6]).toFloat(), (-r[7]).toFloat(), (-r[8]).toFloat()
        )
        return com.google.ar.core.Pose(floatArrayOf(t[0].toFloat(), (-t[1]).toFloat(), (-t[2]).toFloat()), quaternion(rotation))
    }

    private fun quaternion(m: FloatArray): FloatArray {
        val q = FloatArray(4); val trace = m[0] + m[4] + m[8]
        if (trace > 0f) {
            val s = sqrt(trace + 1f) * 2f; q[3] = .25f*s; q[0]=(m[7]-m[5])/s; q[1]=(m[2]-m[6])/s; q[2]=(m[3]-m[1])/s
        } else if (m[0] > m[4] && m[0] > m[8]) {
            val s=sqrt(1f+m[0]-m[4]-m[8])*2f;q[3]=(m[7]-m[5])/s;q[0]=.25f*s;q[1]=(m[1]+m[3])/s;q[2]=(m[2]+m[6])/s
        } else if (m[4] > m[8]) {
            val s=sqrt(1f+m[4]-m[0]-m[8])*2f;q[3]=(m[2]-m[6])/s;q[0]=(m[1]+m[3])/s;q[1]=.25f*s;q[2]=(m[5]+m[7])/s
        } else {
            val s=sqrt(1f+m[8]-m[0]-m[4])*2f;q[3]=(m[3]-m[1])/s;q[0]=(m[2]+m[6])/s;q[1]=(m[5]+m[7])/s;q[2]=.25f*s
        }
        return q
    }

    companion object {
        private val MODEL_ASSETS = (0..3).map { "models/xfeat_fp16_part_${it.toString().padStart(2, '0')}" }
        private val MAP_ASSETS = (0..1).map { "world/living_room/learned/xfeat_feature_map_part_${it.toString().padStart(2, '0')}" }
        private const val INPUT_WIDTH = 640
        private const val INPUT_HEIGHT = 480
        private const val GRID_WIDTH = 80
        private const val GRID_HEIGHT = 60
        private const val DESCRIPTOR_FLOATS = 64
        private const val KEYPOINT_THRESHOLD = 0.05f
        private const val MAXIMUM_LIVE_FEATURES = 1_000
        private const val MINIMUM_LIVE_FEATURES = 120
        private const val RATIO_THRESHOLD = 0.86f
        private const val MINIMUM_RATIO_MATCHES = 16
        private const val MINIMUM_POSE_INLIERS = 7
        private const val MINIMUM_COVERAGE_CELLS = 4
        private const val POSE_REPROJECTION_PIXELS = 7f
        private const val MAXIMUM_REPROJECTION_ERROR = 5.5f
        private const val MINIMUM_CONFIDENCE = 0.45f
    }
}
