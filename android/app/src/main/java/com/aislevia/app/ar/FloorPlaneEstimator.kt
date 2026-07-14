package com.aislevia.app.ar

import com.google.ar.core.Plane
import com.google.ar.core.Pose
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import kotlin.math.abs

/**
 * Finds the lowest sizeable upward-facing ARCore plane below the camera and smooths its height.
 * Tables and shelves are intentionally ignored; navigation is withheld until a floor is known.
 */
class FloorPlaneEstimator(
    private val minimumAreaSquareMetres: Float = 0.35f,
    private val minimumDropBelowCameraMetres: Float = 0.55f,
    private val maximumDropBelowCameraMetres: Float = 2.20f,
    private val smoothing: Float = 0.18f
) {
    var floorY: Float? = null
        private set

    fun update(session: Session, cameraPose: Pose): Float? {
        val cameraY = cameraPose.ty()
        val candidate = session.getAllTrackables(Plane::class.java)
            .asSequence()
            .filter { plane ->
                plane.trackingState == TrackingState.TRACKING &&
                    plane.subsumedBy == null &&
                    plane.type == Plane.Type.HORIZONTAL_UPWARD_FACING &&
                    plane.extentX * plane.extentZ >= minimumAreaSquareMetres
            }
            .map { it.centerPose.ty() }
            .filter { y -> cameraY - y in minimumDropBelowCameraMetres..maximumDropBelowCameraMetres }
            .minOrNull()
            ?: return floorY

        val previous = floorY
        floorY = when {
            previous == null -> candidate
            abs(previous - candidate) > 0.30f -> candidate
            else -> previous + (candidate - previous) * smoothing
        }
        return floorY
    }

    fun reset() {
        floorY = null
    }
}
