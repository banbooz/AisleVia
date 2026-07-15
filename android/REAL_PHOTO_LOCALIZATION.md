# Real-photo localisation map

Version 1.1.0 replaces the texture/synthetic-only XFeat map with a metric map
triangulated from the registered RealityCapture source photographs.

## Dataset and map

- 259 paired full-resolution JPG/XMP records were validated.
- 240 photographs are used for map construction and 19 remain held out.
- 595 overlapping camera pairs produced 623,628 geometrically validated feature candidates.
- The packaged AVXF map contains 27,900 features: 9,300 portrait, 9,300 clockwise
  sensor, and 9,300 counter-clockwise sensor descriptors.
- Every feature has a triangulated metric 3D point; no product or landmark scan is
  requested from the shopper.

## Offline held-out result

The full result is in `tools/registered_xfeat_map_report.json`.

- Portrait: 18/19 metric-good poses; median 4.4 cm / 1.56 degrees.
- Clockwise landscape: 16/19 metric-good poses; median 4.4 cm / 1.49 degrees.
- Counter-clockwise landscape: 17/19 metric-good poses; median 5.2 cm / 1.81 degrees.

These results validate recognition against excluded registered photographs. They
do not replace the required live Samsung Galaxy S20 FE test or prove a five-second
end-to-end lock under every lighting/viewpoint condition.
