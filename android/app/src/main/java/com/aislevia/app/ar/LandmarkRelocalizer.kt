package com.aislevia.app.ar

import com.google.ar.core.Pose

enum class AlignmentQuality { SEARCHING, CHECKING, LOCKED, STALE }

data class AlignmentSnapshot(
    val pose: Pose?,
    val quality: AlignmentQuality,
    val recentMatches: Int,
    val agreeingMatches: Int
) {
    val canRenderNavigation: Boolean
        get() = pose != null && quality == AlignmentQuality.LOCKED
}

/**
 * Builds a room pose only from a consensus of independent natural landmarks.
 * A single recognition can never unlock navigation. Candidates that disagree in
 * position or rotation are treated as false matches instead of moving the map.
 */
class LandmarkRelocalizer(
    private val minimumMatches: Int = 3,
    private val maximumObservationAgeMillis: Long = 30_000L,
    private val staleAfterMillis: Long = 10_000L,
    private val discardAfterMillis: Long = 24_000L,
    private val translationToleranceMetres: Float = 0.32f,
    private val rotationToleranceDegrees: Float = 11f,
    private val minimumMapSpreadMetres: Float = 0.70f,
    private val smoothing: Float = 0.16f
) {
    private data class Observation(
        val landmarkId: String,
        val candidate: Pose,
        val landmarkMapPose: Pose,
        val timeMillis: Long
    )

    private val observations = linkedMapOf<String, Observation>()
    private var lastConsensusMillis: Long = 0L

    var snapshot: AlignmentSnapshot = AlignmentSnapshot(
        pose = null,
        quality = AlignmentQuality.SEARCHING,
        recentMatches = 0,
        agreeingMatches = 0
    )
        private set

    fun observe(
        landmarkId: String,
        detectedWorldPose: Pose,
        storedMapPose: Pose,
        nowMillis: Long = System.currentTimeMillis()
    ): AlignmentSnapshot {
        // worldFromMap × mapFromLandmark = worldFromLandmark
        val candidate = detectedWorldPose.compose(storedMapPose.inverse())
        observations[landmarkId] = Observation(landmarkId, candidate, storedMapPose, nowMillis)
        return update(nowMillis, triggeringLandmarkId = landmarkId)
    }

    fun tick(nowMillis: Long = System.currentTimeMillis()): AlignmentSnapshot =
        update(nowMillis, triggeringLandmarkId = null)

    fun reset() {
        observations.clear()
        lastConsensusMillis = 0L
        snapshot = AlignmentSnapshot(null, AlignmentQuality.SEARCHING, 0, 0)
    }

    private fun update(nowMillis: Long, triggeringLandmarkId: String?): AlignmentSnapshot {
        observations.entries.removeAll {
            nowMillis - it.value.timeMillis > maximumObservationAgeMillis
        }

        val consensus = strongestConsensus()
        val hasEnoughSpread = consensus?.let(::hasUsefulSpatialSpread) == true
        val consensusIncludesNewObservation = triggeringLandmarkId != null &&
            consensus?.any { it.landmarkId == triggeringLandmarkId } == true
        if (
            consensus != null &&
            consensus.size >= minimumMatches &&
            hasEnoughSpread &&
            consensusIncludesNewObservation
        ) {
            val fused = PoseMath.average(consensus.map { it.candidate })
            if (fused != null) {
                val previous = snapshot.pose
                val safeToBlend = previous == null || (
                    PoseMath.translationDistance(previous, fused) <= 0.75f &&
                        PoseMath.rotationDistanceDegrees(previous, fused) <= 24f
                    )
                if (safeToBlend || nowMillis - lastConsensusMillis >= discardAfterMillis) {
                    val pose = previous?.let { Pose.makeInterpolated(it, fused, smoothing) } ?: fused
                    lastConsensusMillis = nowMillis
                    snapshot = AlignmentSnapshot(
                        pose = pose,
                        quality = AlignmentQuality.LOCKED,
                        recentMatches = observations.size,
                        agreeingMatches = consensus.size
                    )
                    return snapshot
                }
            }
        }

        val age = if (lastConsensusMillis == 0L) Long.MAX_VALUE else nowMillis - lastConsensusMillis
        val previousPose = snapshot.pose
        snapshot = when {
            previousPose != null && age < staleAfterMillis -> snapshot.copy(
                quality = AlignmentQuality.LOCKED,
                recentMatches = observations.size,
                agreeingMatches = consensus?.size ?: 0
            )
            previousPose != null && age < discardAfterMillis -> snapshot.copy(
                quality = AlignmentQuality.STALE,
                recentMatches = observations.size,
                agreeingMatches = consensus?.size ?: 0
            )
            else -> AlignmentSnapshot(
                pose = null,
                quality = if (observations.isEmpty()) AlignmentQuality.SEARCHING else AlignmentQuality.CHECKING,
                recentMatches = observations.size,
                agreeingMatches = consensus?.size ?: 0
            )
        }
        return snapshot
    }

    private fun strongestConsensus(): List<Observation>? = observations.values
        .map { seed ->
            observations.values.filter { candidate ->
                PoseMath.translationDistance(seed.candidate, candidate.candidate) <= translationToleranceMetres &&
                    PoseMath.rotationDistanceDegrees(seed.candidate, candidate.candidate) <= rotationToleranceDegrees
            }
        }
        .maxByOrNull { it.size }

    private fun hasUsefulSpatialSpread(consensus: List<Observation>): Boolean {
        for (first in consensus.indices) {
            for (second in first + 1 until consensus.size) {
                if (
                    PoseMath.translationDistance(
                        consensus[first].landmarkMapPose,
                        consensus[second].landmarkMapPose
                    ) >= minimumMapSpreadMetres
                ) return true
            }
        }
        return false
    }
}
