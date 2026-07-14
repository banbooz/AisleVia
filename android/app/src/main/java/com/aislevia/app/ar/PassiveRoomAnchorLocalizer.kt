package com.aislevia.app.ar

import android.content.Context
import android.graphics.BitmapFactory
import com.google.ar.core.AugmentedImage
import com.google.ar.core.AugmentedImageDatabase
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Pose
import com.google.ar.core.Session
import com.google.ar.core.TrackingState

/**
 * Fast, passive room localisation from a permanent planar feature already present in the scan.
 *
 * ARCore searches for the artwork continuously as part of normal camera tracking. The customer is
 * never asked to frame, tap, or name it. Its measured metric pose converts ARCore's image pose into
 * the same map coordinate system used by the route arrows. The dense visual localisers remain as
 * automatic fallbacks when this part of the room is not yet visible.
 */
class PassiveRoomAnchorLocalizer(context: Context) {
    private val appContext = context.applicationContext
    private var configuredSession: Session? = null
    private var database: AugmentedImageDatabase? = null
    private val observations = ArrayDeque<Pose>()
    private var lockedPose: Pose? = null
    @Volatile
    private var databaseReady = false

    val isReady: Boolean
        get() = databaseReady

    /** Installs the small image database once per ARCore session. */
    fun configure(session: Session, config: Config) {
        if (configuredSession !== session) {
            configuredSession = session
            observations.clear()
            lockedPose = null
            database = buildDatabase(session)
            databaseReady = database != null
        }
        database?.let { config.augmentedImageDatabase = it }
    }

    /**
     * Returns a stable world-from-map transform after an ARCore FULL_TRACKING observation.
     * Once established it remains valid for the lifetime of the ARCore session, even when the
     * artwork leaves the camera view, because ARCore keeps tracking movement in the same world.
     */
    fun update(frame: Frame, worldFloorY: Float?): Pose? {
        frame.getUpdatedTrackables(AugmentedImage::class.java)
            .asSequence()
            .filter { image ->
                image.name == ANCHOR_NAME &&
                    image.trackingState == TrackingState.TRACKING &&
                    image.trackingMethod == AugmentedImage.TrackingMethod.FULL_TRACKING &&
                    image.extentX in MINIMUM_ACCEPTED_WIDTH..MAXIMUM_ACCEPTED_WIDTH
            }
            .map { image ->
                val raw = image.centerPose.compose(MAP_FROM_IMAGE.inverse())
                if (worldFloorY == null) raw else PoseMath.floorConstrainedPose(raw, worldFloorY)
            }
            .firstOrNull()
            ?.let(::recordObservation)

        return lockedPose
    }

    fun resetLock() {
        observations.clear()
        lockedPose = null
    }

    private fun recordObservation(candidate: Pose) {
        val currentLock = lockedPose
        if (currentLock != null) {
            if (
                PoseMath.translationDistance(currentLock, candidate) <= LOCK_UPDATE_TRANSLATION_METRES &&
                PoseMath.rotationDistanceDegrees(currentLock, candidate) <= LOCK_UPDATE_ROTATION_DEGREES
            ) {
                lockedPose = PoseMath.weightedAverage(listOf(currentLock to 5f, candidate to 1f))
            }
            return
        }

        val previous = observations.lastOrNull()
        if (
            previous != null &&
            PoseMath.translationDistance(previous, candidate) <= CONSENSUS_TRANSLATION_METRES &&
            PoseMath.rotationDistanceDegrees(previous, candidate) <= CONSENSUS_ROTATION_DEGREES
        ) {
            observations.addLast(candidate)
        } else {
            observations.clear()
            observations.addLast(candidate)
        }
        while (observations.size > REQUIRED_AGREEING_OBSERVATIONS) observations.removeFirst()
        if (observations.size >= REQUIRED_AGREEING_OBSERVATIONS) {
            lockedPose = PoseMath.average(observations)
        }
    }

    private fun buildDatabase(session: Session): AugmentedImageDatabase? = runCatching {
        val bitmap = appContext.assets.open(ANCHOR_ASSET_PATH).use { stream ->
            BitmapFactory.decodeStream(stream)
        }
            ?: return@runCatching null
        try {
            AugmentedImageDatabase(session).also { imageDatabase ->
                imageDatabase.addImage(ANCHOR_NAME, bitmap, ANCHOR_WIDTH_METRES)
            }
        } finally {
            bitmap.recycle()
        }
    }.getOrNull()

    companion object {
        private const val ANCHOR_NAME = "living-room-parrot-artwork"
        private const val ANCHOR_ASSET_PATH =
            "world/living_room/passive_anchors/parrot_artwork_real_rectified.jpg"
        private const val ANCHOR_WIDTH_METRES = 0.7250619f
        private const val MINIMUM_ACCEPTED_WIDTH = 0.50f
        private const val MAXIMUM_ACCEPTED_WIDTH = 0.95f
        private const val REQUIRED_AGREEING_OBSERVATIONS = 1
        private const val CONSENSUS_TRANSLATION_METRES = 0.14f
        private const val CONSENSUS_ROTATION_DEGREES = 7f
        private const val LOCK_UPDATE_TRANSLATION_METRES = 0.20f
        private const val LOCK_UPDATE_ROTATION_DEGREES = 10f

        /**
         * Metric pose of the upright, fronto-parallel reference bitmap in the scan coordinate system.
         * Local +X follows map +Z, local +Y is the wall normal into the room (-map X), and local +Z
         * follows map -Y. Its appearance was rectified from a sharp real room photograph, while its
         * centre and physical width come from the original metric RealityCapture mesh.
         */
        internal val MAP_FROM_IMAGE = Pose(
            floatArrayOf(2.5115037f, 1.7444282f, 1.0002344f),
            floatArrayOf(0.5f, -0.5f, 0.5f, 0.5f)
        )
    }
}
