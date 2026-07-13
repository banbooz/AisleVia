# AisleVia native Android prototype

This is the first native ARCore version of AisleVia. It is separate from the browser demo in the repository root.

## What it does

### One-time shop mapping

1. A staff member defines the entrance and forward direction once.
2. The app captures three stable, textured landmarks: the parrot picture, fireplace surround and white bookcase.
3. Each landmark image is saved with its pose inside the digital twin.
4. The Pringles position is saved in the same map coordinate system.

### Automatic customer relocalisation

1. The customer opens navigation and looks around briefly.
2. ARCore compares the camera against the stored landmark images.
3. Every recognised landmark produces a complete six-degree-of-freedom estimate for the digital twin.
4. Recent estimates are fused and smoothed.
5. ARCore keeps tracking while the customer walks, and visible landmarks continually correct drift.
6. Green route markers and the red item highlight are rendered under the corrected map pose.

All map data and captured landmark images stay in the app's private on-device storage.

## Build

Open the `android` folder in Android Studio and run the `app` configuration on an ARCore-supported Android phone. The repository workflow also builds a debug APK on each change under `android/`.

## Important prototype limits

- The natural landmarks must be mostly planar, textured, well lit and physically fixed. Artwork, shelf signs, shelf fronts and architectural details are stronger than soft sofas or changing displays.
- The first map still needs a staff setup pass. Customer sessions then relocalise automatically.
- The living-room measurements are approximate. The mapping pass records real landmark and item positions, but the optional furniture debug overlay remains photo-estimated.
- This is Android-first because native ARCore exposes the camera and Augmented Images pipeline needed for reliable relocalisation. The existing WebXR version remains available as a manual-alignment fallback.
