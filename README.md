# AisleVia AR — living-room proof of concept

A browser-based augmented-reality prototype that uses a living room as a pretend shop. It places route arrows on the real floor, adds a vertical guide at the destination, and draws a pulsing red 3D highlight around the selected item.

## What it demonstrates

- WebXR phone tracking during an AR session
- Floor detection through WebXR hit testing
- Doorway calibration while facing the fireplace
- A route that updates as the user walks
- Green floor arrows in room-scale coordinates
- Vertical item guidance and a red target highlight
- Fine alignment controls because the current measurements were estimated from photos

## Run it on a phone

The app must be served through HTTPS. GitHub Pages can provide that.

1. Open this repository's **Settings**.
2. Open **Pages**.
3. Under **Build and deployment**, select **Deploy from a branch**.
4. Choose the `main` branch and `/ (root)`, then save.
5. Open the Pages address in Chrome on an ARCore-supported Android phone.
6. Allow camera permission and tap **Start tracked AR**.
7. Stand in the living-room doorway, face the fireplace, slowly scan the carpet, and place the entrance marker on the doorway floor.

The app imports Three.js from jsDelivr, so the phone needs an internet connection when the page first loads.

## Calibration

The room geometry was inferred from photographs and is not survey-grade. After placing the entrance, use **Fine-tune alignment** to move or rotate the digital map until the Pringles can, television, bookcase, or remote highlights line up with the real objects.

For a production-quality version, replace the estimated coordinates with measurements, a LiDAR scan, or photogrammetry, and use stable visual anchors or store markers for repeatable relocalisation.

## Current limitations

- The map is anchored only for the current AR session.
- Real furniture does not occlude the virtual graphics in this web prototype.
- Browser support varies; this build targets WebXR on supported Android Chrome devices.
- Item coordinates are approximate and based on the supplied photos.
- Camera frames are processed by the phone's XR system and are not uploaded by this code.
