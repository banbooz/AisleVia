# AisleVia native Android prototype v0.3.1

## Crash-safety update

- waits for camera permission before creating any AR view
- captures the short-lived ARCore camera frame on the AR thread instead of retaining it inside background work
- copies the camera pose before asynchronous image and map processing
- records an unexpected crash locally and shows a copyable report after restart

This is the native ARCore version of AisleVia. It is separate from the browser demo in the repository root.

## Why v0.2 remaps the room

The first Android build could accept one landmark as a full room alignment. A false or badly scaled match could therefore place the red target and green route at the wrong pose. Landmark widths were also estimated rather than measured.

Version 0.2 deliberately rejects maps made by that build.

Version 0.3 automatically captures landmark and item-group frames after the
detected surface remains stable. The capture check can use a narrower measured
span on the same plane, so wall sections beside arches no longer require the
entire frame to be one large detected plane. Mapping text is capped to prevent
overlap on phones using larger font settings.

## One-time detailed mapping
1. Set the entrance and a forward floor point.
2. Capture eight fixed references distributed around the room: picture, arch details, three fireplace views, bookcase and coffee-table front.
3. Each capture must lie on a detected real plane. The app hit-tests both edges of the capture frame and measures its physical width automatically.
4. Scan the Pringles and nearby items. Bundled on-device ML Kit text recognition and image labelling suggest the product name; the camera image is not uploaded.
5. Aim at the exact point where the item touches its shelf or table and save it in the same map coordinate system.

In a real shop, use fixed textured shelf fronts, aisle signs, end-cap artwork and architectural details. Avoid moving stock displays and soft furniture as primary room references.

## Confidence-gated navigation

- A single image match never shows navigation.
- At least three references from different parts of the map must produce poses that agree within strict translation and rotation limits.
- Outlier matches are ignored.
- The route and target are hidden when the last verified consensus becomes stale.
- Route dots are smaller, capped at nine and start 65 cm in front of the phone.
- The old large red sphere is replaced with a small item pin.

## Build

Open the `android` folder in Android Studio and run the `app` configuration on an ARCore-supported Android phone. The repository workflow also builds a debug APK on changes under `android/`.

## Prototype limits

- Product recognition is a mapping assistant, not an unattended stock database. Staff must still point to the exact product position and should verify the suggested name.
- ARCore Augmented Images work best with flat, textured, well-lit references. Product groups can be scanned together only when their visible fronts form a sufficiently flat reference view.
- This prototype records one selected item after its group scan. The data model supports multiple items and visual reference IDs, but the multi-item catalogue editor is not built yet.
- All map data, reference photos and ML processing stay on the phone.
