package com.aislevia.app.ar

import com.google.ar.core.Pose
import kotlin.math.atan2
import kotlin.math.sin
import kotlin.math.cos
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
            val dot = q.indices.sumOf { index ->
                (q[index] * firstQuaternion[index]).toDouble()
            }.toFloat()
            if (dot < 0f) q.indices.forEach { q[it] = -q[it] }

            tx += t[0]
            ty += t[1]
            tz += t[2]
            qx += q[0]
            qy += q[1]
            qz += q[2]
            qw += q[3]
        }

        val count = poses.size.toFloat()
        val length = sqrt(qx * qx + qy * qy + qz * qz + qw * qw).coerceAtLeast(0.0001f)
        return Pose(
            floatArrayOf(tx / count, ty / count, tz / count),
            floatArrayOf(qx / length, qy / length, qz / length, qw / length)
        )
    }

    fun horizontalDistance(a: Pose, b: Pose): Float {
        val at = a.translation
        val bt = b.translation
        val dx = at[0] - bt[0]
        val dz = at[2] - bt[2]
        return sqrt(dx * dx + dz * dz)
    }
}
