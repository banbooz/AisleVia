package com.aislevia.app.ar

import com.google.ar.core.Pose

/**
 * Converts recognised landmark poses into one continuously corrected world-from-map pose.
 * Each landmark supplies a complete 6DoF candidate. Recent candidates are fused and smoothed.
 */
class LandmarkRelocalizer(
    private val maximumAgeMillis: Long = 1_800L,
    private val smoothing: Float = 0.22f
) {
    private data class Observation(val pose: Pose, val timeMillis: Long)

    private val observations = linkedMapOf<String, Observation>()

    var worldFromMap: Pose? = null
        private set

    val visibleLandmarkCount: Int
        get() = observations.size

    fun observe(
        landmarkId: String,
        detectedWorldPose: Pose,
        storedMapPose: Pose,
        nowMillis: Long = System.currentTimeMillis()
    ): Pose? {
        // worldFromMap × mapFromLandmark = worldFromLandmark
        val candidate = detectedWorldPose.compose(storedMapPose.inverse())
        observations[landmarkId] = Observation(candidate, nowMillis)
        expire(nowMillis)

        val fused = PoseMath.average(observations.values.map { it.pose }) ?: return worldFromMap
        worldFromMap = worldFromMap?.let { Pose.makeInterpolated(it, fused, smoothing) } ?: fused
        return worldFromMap
    }

    fun tick(nowMillis: Long = System.currentTimeMillis()) {
        expire(nowMillis)
    }

    fun reset() {
        observations.clear()
        worldFromMap = null
    }

    private fun expire(nowMillis: Long) {
        observations.entries.removeAll { nowMillis - it.value.timeMillis > maximumAgeMillis }
    }
}
