# AisleVia zero-setup recognition requirement

## Implementation prompt

Replace the current synthetic-first localisation database with a real-photo metric visual-positioning
map. A shopper who has already selected an item must be able to enter the mapped room, open the
camera, and look around naturally. Within **5.0 seconds of the first usable ARCore camera frame**,
the app must:

1. recognise the correct room without asking the shopper to scan, frame, or aim at a landmark,
   product, marker, or floor point;
2. estimate and geometrically verify the phone's six-degree-of-freedom pose in the metric 3D-map
   coordinate system;
3. reject false or physically impossible poses using the mesh bounds, floor, depth when available,
   and agreement across camera frames; and
4. load the already-mapped locations of every item without rescanning them; and
5. start the selected-item navigation task by rendering correctly aligned floor arrows and the
   target marker.

Do not attempt to fix the failed matcher by merely lowering thresholds, increasing timeouts, adding
another single-image landmark, or changing customer instructions. Rebuild the localisation database
from the original registered, full-resolution room photographs and their real camera intrinsics,
distortion, positions, and rotations. Extract descriptors from those real photographs and connect
them to accurate 3D coordinates using reconstruction tracks where available, or occlusion-checked
mesh intersections otherwise. Split the photographs into mapping and viewpoint-separated holdout
sets before tuning. Synthetic renders may add coverage only after the real-photo map works; they
must never be the primary appearance database.

Use the full textured mesh as the common metric coordinate system and the source of scale, floor
height, room bounds, item locations, visibility, and depth verification. At runtime, retrieve likely
real-photo views, match live features to registered 3D points, solve a robust metric camera pose,
reject inconsistent geometry, and require agreement across a short natural camera sequence. Once
the map transform is verified, ARCore may track movement between visual rechecks.

Do not claim that the requirement is met because the APK compiles, because training/reference
photographs relocalise, or because a single landmark is recognised. Report measured results from
held-out real photographs, out-of-room negative scenes, recorded walk-in sequences, and finally a
Samsung Galaxy S20 FE. Do not distribute another APK as a recognition update until those tests pass.

## Current validation status

**FAILED — v1.0.0, 14 July 2026.** A real-room test reported no room recognition and therefore no
floor route. The build, signature, and lint checks passed, but those are not localisation evidence.
Version 1.1.0 replaces the learned asset with 27,900 features triangulated from 240 registered
source photographs. On 19 excluded photos it recovered 18 portrait metric-good poses, 16
clockwise-landscape poses, and 17 counter-clockwise-landscape poses. This is sufficient to create a
debug candidate for phone testing, but the five-second requirement remains open until the Samsung
Galaxy S20 FE live-room test passes.

## Required mapping inputs

- All original full-resolution room photographs with unchanged filenames and metadata.
- The RealityCapture project plus referenced inputs, or an export containing per-image intrinsics,
  distortion, position, and rotation in the same coordinate system as the supplied mesh.
- Preferably the registered sparse point cloud and image observation tracks; RealityCapture XMP,
  CSV, or COLMAP camera/point exports are acceptable when they preserve the necessary geometry.
- The exact mesh coordinate transform, floor plane, and item positions.
- Separate real walk-in video or photo sequences that are excluded from map construction.

## Timing definition

- **Start:** the first frame for which ARCore reports `TrackingState.TRACKING` in a new session.
- **Finish:** the first frame for which a verified map transform and an in-bounds camera pose make
  the item route ready to render.
- First-install permission dialogs are measured separately, not hidden inside the localisation
  result.
- A result slower than 5,000 ms is logged as a target miss. Localisation must continue rather than
  displaying a false success.

## Acceptance evidence

- Use a viewpoint-separated held-out set that was never used to construct or tune the map.
- Every normal acceptance walk-in sequence must produce a verified route within 5,000 ms; one miss
  means the five-second requirement has not passed.
- Report per-sequence time to first verified route, translation error, rotation error, inlier count,
  confidence, and whether depth/floor verification was available.
- Test ordinary entrance, walking, standing, portrait-camera, lighting, and partial-occlusion cases.
- Test unrelated rooms and visually similar objects; a wrong-room lock is a failure.
- Require zero wrong-room locks in the negative test set.
- Verify on-device that floor arrows remain on the floor and aligned while the shopper walks to the
  stored item location; compilation or photo-only evaluation cannot pass this gate.
- The final pass/fail statement must include results recorded on the Samsung Galaxy S20 FE.
