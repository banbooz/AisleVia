package com.aislevia.app.ar

import com.google.ar.core.Pose
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

object PoseMath {
    /** Builds a map origin at the entrance, with local -Z pointing into the shop. */
    fun floorAlignedOrigin(entrance: Pose, forwardPoint: Pose): Pose {
        val a = entrance.translation
        val b = forwardPoint.translation
        val dx = b[0] - a[0]
        val dz = b[2] - a[2]
        val yaw = atan2(-dx, -dz)
        val half = yaw * 0.5f
        return Pose(
            floatArrayOf(a[0], a[1], a[2]),
            floatArrayOf(0f, sin(half), 0f, cos(half))
        )
    }

    /**
     * Aligns the saved landmark pose with the captured image axes.
     * X follows screen-right, Y is the surface normal toward the camera, and Z points down.
     */
    fun imageAlignedPose(surfacePose: Pose, cameraPose: Pose): Pose {
        val centre = surfacePose.translation
        var normal = normalise(surfacePose.rotateVector(floatArrayOf(0f, 1f, 0f)))
        val toCamera = floatArrayOf(
            cameraPose.tx() - centre[0],
            cameraPose.ty() - centre[1],
            cameraPose.tz() - centre[2]
        )
        if (dot(normal, toCamera) < 0f) normal = scale(normal, -1f)

        val cameraRight = cameraPose.rotateVector(floatArrayOf(1f, 0f, 0f))
        var right = subtract(cameraRight, scale(normal, dot(cameraRight, normal)))
        if (length(right) < 0.001f) {
            right = surfacePose.rotateVector(floatArrayOf(1f, 0f, 0f))
        }
        right = normalise(right)
        val down = normalise(cross(right, normal))
        right = normalise(cross(normal, down))

        return Pose(centre, quaternionFromBasis(right, normal, down))
    }

    fun average(poses: Collection<Pose>): Pose? {
        if (poses.isEmpty()) return null
        val firstQuaternion = poses.first().rotationQuaternion
        var tx = 0f
        var ty = 0f
        var tz = 0f
        var qx = 0f
        var qy = 0f
        var qz = 0f
        var qw = 0f

        poses.forEach { pose ->
            val t = pose.translation
            val q = pose.rotationQuaternion.copyOf()
            var quaternionDot = 0f
            for (index in q.indices) quaternionDot += q[index] * firstQuaternion[index]
            if (quaternionDot < 0f) q.indices.forEach { q[it] = -q[it] }

            tx += t[0]
            ty += t[1]
            tz += t[2]
            qx += q[0]
            qy += q[1]
            qz += q[2]
            qw += q[3]
        }

        val count = poses.size.toFloat()
        val quaternionLength = sqrt(qx * qx + qy * qy + qz * qz + qw * qw).coerceAtLeast(0.0001f)
        return Pose(
            floatArrayOf(tx / count, ty / count, tz / count),
            floatArrayOf(
                qx / quaternionLength,
                qy / quaternionLength,
                qz / quaternionLength,
                qw / quaternionLength
            )
        )
    }

    fun horizontalDistance(a: Pose, b: Pose): Float {
        val at = a.translation
        val bt = b.translation
        val dx = at[0] - bt[0]
        val dz = at[2] - bt[2]
        return sqrt(dx * dx + dz * dz)
    }

    fun translationDistance(a: Pose, b: Pose): Float {
        val at = a.translation
        val bt = b.translation
        val dx = at[0] - bt[0]
        val dy = at[1] - bt[1]
        val dz = at[2] - bt[2]
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    fun rotationDistanceDegrees(a: Pose, b: Pose): Float {
        val aq = a.rotationQuaternion
        val bq = b.rotationQuaternion
        val dot = abs(
            aq[0] * bq[0] + aq[1] * bq[1] + aq[2] * bq[2] + aq[3] * bq[3]
        ).coerceIn(0f, 1f)
        return (2.0 * acos(dot.toDouble()) * 180.0 / PI).toFloat()
    }

    private fun quaternionFromBasis(x: FloatArray, y: FloatArray, z: FloatArray): FloatArray {
        val m00 = x[0]
        val m01 = y[0]
        val m02 = z[0]
        val m10 = x[1]
        val m11 = y[1]
        val m12 = z[1]
        val m20 = x[2]
        val m21 = y[2]
        val m22 = z[2]
        val trace = m00 + m11 + m22

        val q = FloatArray(4)
        if (trace > 0f) {
            val s = sqrt(trace + 1f) * 2f
            q[3] = 0.25f * s
            q[0] = (m21 - m12) / s
            q[1] = (m02 - m20) / s
            q[2] = (m10 - m01) / s
        } else if (m00 > m11 && m00 > m22) {
            val s = sqrt(1f + m00 - m11 - m22) * 2f
            q[3] = (m21 - m12) / s
            q[0] = 0.25f * s
            q[1] = (m01 + m10) / s
            q[2] = (m02 + m20) / s
        } else if (m11 > m22) {
            val s = sqrt(1f + m11 - m00 - m22) * 2f
            q[3] = (m02 - m20) / s
            q[0] = (m01 + m10) / s
            q[1] = 0.25f * s
            q[2] = (m12 + m21) / s
        } else {
            val s = sqrt(1f + m22 - m00 - m11) * 2f
            q[3] = (m10 - m01) / s
            q[0] = (m02 + m20) / s
            q[1] = (m12 + m21) / s
            q[2] = 0.25f * s
        }
        return q
    }

    private fun dot(a: FloatArray, b: FloatArray): Float = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
    private fun cross(a: FloatArray, b: FloatArray): FloatArray = floatArrayOf(
        a[1] * b[2] - a[2] * b[1],
        a[2] * b[0] - a[0] * b[2],
        a[0] * b[1] - a[1] * b[0]
    )
    private fun subtract(a: FloatArray, b: FloatArray): FloatArray = floatArrayOf(a[0] - b[0], a[1] - b[1], a[2] - b[2])
    private fun scale(a: FloatArray, factor: Float): FloatArray = floatArrayOf(a[0] * factor, a[1] * factor, a[2] * factor)
    private fun length(a: FloatArray): Float = sqrt(dot(a, a))
    private fun normalise(a: FloatArray): FloatArray {
        val length = length(a).coerceAtLeast(0.0001f)
        return scale(a, 1f / length)
    }
}
