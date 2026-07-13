# AisleVia AR — living-room proof of concept v2

A browser-based augmented-reality prototype that treats a living room as a pretend shop. It aligns a photo-built digital twin to the real room, places route arrows on the floor, and highlights a selected item.

## What changed in v2

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

## Important limitation

This version aligns the digital twin with two user-supplied reference points. It does not yet perform automatic visual relocalisation from the photographs. True automatic room recognition would require a visual feature map from a LiDAR/photogrammetry scan, a persistent spatial-anchor service, or a fixed visual marker in the room.

Camera frames are handled by the phone's XR system and are not uploaded by this code.
