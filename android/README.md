# AisleVia native Android prototype

The current customer-facing performance contract is defined in
[`ZERO_SETUP_RECOGNITION.md`](ZERO_SETUP_RECOGNITION.md). It requires a verified, route-ready pose
within five seconds of the first usable camera frame and forbids treating a successful build as
recognition evidence.

Current status: **v1.1.0 is a real-photo debug candidate**. It replaces the learned synthetic/atlas
map with 27,900 metric features triangulated from 240 registered source photographs. Nineteen
excluded photos provide offline evidence across portrait and both landscape sensor rotations; see
[`REAL_PHOTO_LOCALIZATION.md`](REAL_PHOTO_LOCALIZATION.md). Live five-second recognition and route
alignment still require the Samsung Galaxy S20 FE room test.

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

## Legacy mapper

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

The customer path now matches live XFeat descriptors directly against triangulated real-photo 3D
points. The older manual reference mapper remains only as an internal legacy/fallback path and is
not part of the shopper flow. The offline holdout proves photo relocalisation, not live timing,
wrong-room rejection, or arrow alignment on the target handset; those remain device-test gates.

The eight references are deliberately fixed architectural or printed details. People, cushions,
plants, television content and tabletop clutter are excluded because they move. All reference
capture, OCR, labelling and map data stay on the phone.
