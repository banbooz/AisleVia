# AisleVia native Android prototype v0.5.0

## Scan-backed living-room world

Version 0.5 packages the supplied RealityCapture phone scan as a metric virtual world rather than
treating unrelated camera crops as the world itself.

- measured source extent: about 4.8 × 5.0 × 2.3 metres
- mobile asset: 107,163 vertices, 154,864 triangles and a 2K texture
- model coordinates: +Y up, fireplace-floor centre at the origin and -Z toward the television
- measured world bounds and floor height are stored with every map
- the original 8K texture was reduced to 2K to avoid a roughly 268 MB uncompressed GPU texture

The prepared GLB, texture and machine-readable manifest are under
`app/src/main/assets/world/living_room/`. `tools/recenter_glb.py` performs the metric origin shift.

## One-time calibration

1. Aim at the carpet directly below the fireplace centre to place the scan origin.
2. Aim at the carpet directly below the television centre to set the scan direction.
3. Capture eight named fixed references across four permanent zones: parrot picture, right
   bookcase, left alcove and fireplace.
4. The app rejects dark, overexposed, blurry, featureless or ARCore-low-quality captures.
5. Scan the Pringles group, then point to the exact base of the can.

Two views from the same wall share one zone ID. They increase recognition coverage but can never
count as two independent votes.

## Confidence-gated customer navigation

- Three spatially separated fixed zones must agree before the map can lock.
- A sizeable upward ARCore floor plane must also be detected; tables and shelves are ignored.
- Landmark-derived pitch, roll and vertical drift are removed. The map is forced onto the detected
  floor while retaining its visual X/Z position and yaw.
- A resulting camera pose outside the supplied 3D model bounds is rejected.
- Navigation disappears when confidence becomes stale.
- The target is a small 11 cm-wide pin and route dots are 7 cm wide, preventing the old giant blobs.

## Build

Open the `android` folder in Android Studio and run the `app` configuration on an ARCore-supported
Android phone. The repository workflow builds a debug APK for pull requests and changes to `main`.

## Honest prototype limits

This is a substantial improvement over random flat keyframes, but it is not yet a full
feature-map/VPS engine. The 3D mesh supplies the metric coordinate system, floor and physical
bounds; ARCore visual references reconnect a live session to it. A production shop version should
match live camera features directly against 3D scan keypoints and use a walkable navmesh for paths
around shelves and furniture.

The eight references are deliberately fixed architectural or printed details. People, cushions,
plants, television content and tabletop clutter are excluded because they move. All reference
capture, OCR, labelling and map data stay on the phone.
