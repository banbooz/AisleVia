package com.aislevia.app.model

data class FixedReferenceCapture(
    val id: String,
    val zoneId: String,
    val name: String,
    val instruction: String
)

/**
 * Metadata derived from the supplied metric RealityCapture scan.
 *
 * The map origin is the carpet directly below the fireplace centre. Local -Z points from the
 * fireplace towards the television. The scan was simplified from 281,574 to 154,864 triangles and
 * its 8K texture was reduced to 2K so loading it cannot consume hundreds of megabytes on the phone.
 */
object LivingRoomWorldPack {
    val worldModel = WorldModelRecord(
        id = "living-room-2026-07-13",
        assetPath = "world/living_room/living_room.glb",
        floorYMetres = 0f,
        bounds = WorldBoundsRecord(
            minX = -2.10f,
            maxX = 2.72f,
            minY = -0.34f,
            maxY = 2.04f,
            minZ = -2.47f,
            maxZ = 2.51f
        ),
        triangleCount = 154_864,
        source = "RealityCapture metric phone scan, 13 July 2026",
        originDescription = "Carpet directly below the fireplace centre",
        forwardDescription = "From the fireplace centre towards the television centre"
    )

    /**
     * The product is part of this fixed proof-room pack, so shoppers must never map it themselves.
     * Its floor target was measured from the supplied textured 3D scan: the blue Pringles can sits
     * on the coffee table at approximately (-0.90, 0.10) in the scan coordinate frame.
     */
    val defaultStoreMap = StoreMap(
        version = 6,
        name = "Living-room proof shop",
        items = listOf(
            ItemRecord(
                id = "pringles",
                name = "Pringles can",
                mapPose = PoseRecord(
                    tx = -0.90f,
                    ty = worldModel.floorYMetres,
                    tz = 0.10f,
                    qx = 0f,
                    qy = 0f,
                    qz = 0f,
                    qw = 1f
                )
            )
        ),
        minimumLandmarksForLock = 0,
        worldModel = worldModel
    )

    /**
     * Two deliberately different crops per permanent zone. Captures from the same zone share a
     * zoneId, so they can improve recognition coverage but can never vote twice toward a lock.
     */
    val referenceCaptures = listOf(
        FixedReferenceCapture(
            id = "parrot-frame-wide",
            zoneId = "parrot-wall",
            name = "Whole parrot picture",
            instruction = "Fit the whole framed parrot picture inside the green guide."
        ),
        FixedReferenceCapture(
            id = "parrot-frame-detail",
            zoneId = "parrot-wall",
            name = "Parrot picture detail",
            instruction = "Move closer and fill the guide with the printed parrot artwork."
        ),
        FixedReferenceCapture(
            id = "right-bookcase-upper",
            zoneId = "right-bookcase",
            name = "Upper right bookcase",
            instruction = "Frame the upper shelves of books beside the television."
        ),
        FixedReferenceCapture(
            id = "right-bookcase-lower",
            zoneId = "right-bookcase",
            name = "Lower right bookcase",
            instruction = "Frame the lower book spines and shelf edges beside the television."
        ),
        FixedReferenceCapture(
            id = "left-alcove-chest",
            zoneId = "left-alcove",
            name = "Left alcove chest",
            instruction = "Frame the dark wooden chest and lamp in the left alcove."
        ),
        FixedReferenceCapture(
            id = "left-cabinet-detail",
            zoneId = "left-alcove",
            name = "Left cabinet detail",
            instruction = "Frame the fixed cabinet doors, handles and marble edge below the chest."
        ),
        FixedReferenceCapture(
            id = "fireplace-surround",
            zoneId = "fireplace",
            name = "Fireplace surround",
            instruction = "Frame the marble fireplace surround and its carved corners."
        ),
        FixedReferenceCapture(
            id = "fireplace-insert",
            zoneId = "fireplace",
            name = "Fireplace insert",
            instruction = "Move closer and frame the black fireplace opening and metal insert."
        )
    )
}
