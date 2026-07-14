package com.aislevia.app.model

import com.google.ar.core.Pose
import kotlinx.serialization.Serializable

@Serializable
data class PoseRecord(
    val tx: Float,
    val ty: Float,
    val tz: Float,
    val qx: Float,
    val qy: Float,
    val qz: Float,
    val qw: Float
) {
    fun toPose(): Pose = Pose(
        floatArrayOf(tx, ty, tz),
        floatArrayOf(qx, qy, qz, qw)
    )

    companion object {
        fun fromPose(pose: Pose): PoseRecord {
            val t = pose.translation
            val q = pose.rotationQuaternion
            return PoseRecord(t[0], t[1], t[2], q[0], q[1], q[2], q[3])
        }
    }
}

@Serializable
data class LandmarkRecord(
    val id: String,
    val name: String,
    val imageFile: String,
    val physicalWidthMetres: Float,
    val mapPose: PoseRecord,
    val referenceType: String = "room",
    val zoneId: String? = null,
    val recognitionLabels: List<String> = emptyList()
)

@Serializable
data class ItemRecord(
    val id: String,
    val name: String,
    val mapPose: PoseRecord,
    val visualReferenceIds: List<String> = emptyList(),
    val recognitionLabels: List<String> = emptyList()
)

@Serializable
data class WorldBoundsRecord(
    val minX: Float,
    val maxX: Float,
    val minY: Float,
    val maxY: Float,
    val minZ: Float,
    val maxZ: Float
) {
    fun contains(pose: Pose, marginMetres: Float = 0f): Boolean {
        val point = pose.translation
        return point[0] in (minX - marginMetres)..(maxX + marginMetres) &&
            point[1] in (minY - marginMetres)..(maxY + marginMetres) &&
            point[2] in (minZ - marginMetres)..(maxZ + marginMetres)
    }
}

@Serializable
data class WorldModelRecord(
    val id: String,
    val assetPath: String,
    val floorYMetres: Float,
    val bounds: WorldBoundsRecord,
    val triangleCount: Int,
    val source: String,
    val originDescription: String,
    val forwardDescription: String
)

@Serializable
data class StoreMap(
    val version: Int = 4,
    val name: String = "Living-room proof shop",
    val landmarks: List<LandmarkRecord> = emptyList(),
    val items: List<ItemRecord> = emptyList(),
    val minimumLandmarksForLock: Int = 3,
    val worldModel: WorldModelRecord? = null
)
