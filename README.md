# AisleVia AR — living-room proof of concept

This repository contains a browser experiment and the native Android ARCore prototype. The Android
app is the current implementation and now packages the supplied metric 3D room scan.

See [`android/README.md`](android/README.md) for the v0.5 scan-backed tracking design, calibration
flow, build instructions and current limitations.

## Browser experiment

- Two-point room alignment: doorway floor plus the carpet in front of the fireplace
- Direction and scale are calculated from those two real-world points
- Smaller, capped route arrows to prevent overlapping arrow boxes
- A simpler red target ring instead of the oversized three-axis target
- **Teach exact item spot** mode so a photo-estimated target can be corrected and saved on the phone
- Learned item coordinates are stored in browser `localStorage`

## Test it

Open the GitHub Pages site in Chrome on an ARCore-supported Android phone.

1. Tap **Start room-aligned AR**.
2. Slowly scan the carpet.
3. Place the first point on the doorway floor.
4. Place the second point on the carpet directly in front of the centre of the fireplace.
5. Select an item.
6. If the highlight is not exact, tap **Teach exact item spot**, aim at the base of the real item, and save.

## Browser limitation

The GitHub Pages version still uses two user-supplied points. The native Android app adds fixed-zone
visual relocalisation, floor locking and 3D-scan bounds checking.

Camera frames are handled by the phone's XR system and are not uploaded by this code.
