"""Build and evaluate an AVXF map from registered RealityCapture photographs.

Unlike the legacy texture-derived map, every output descriptor originates in a
real source photograph.  Its metric 3D point is triangulated from at least two
XMP-registered views and rejected unless the rays agree geometrically.
"""

from __future__ import annotations

import argparse
import base64
import gzip
import json
import struct
from dataclasses import dataclass
from pathlib import Path

import cv2
import numpy as np
import torch

from solve_rc_alignment import Camera, load_cameras


@dataclass
class Features:
    keypoints: np.ndarray
    descriptors: np.ndarray
    scores: np.ndarray
    width: int
    height: int
    orientation: int


def resize_photo(camera: Camera, width: int) -> np.ndarray:
    image = cv2.imread(str(camera.image), cv2.IMREAD_GRAYSCALE)
    height = round(image.shape[0] * width / image.shape[1])
    return cv2.resize(image, (width, height), interpolation=cv2.INTER_AREA)


def extract_features(model: object, camera: Camera, width: int, top_k: int, orientation: int) -> Features:
    image = resize_photo(camera, width)
    if orientation == 1:
        image = cv2.rotate(image, cv2.ROTATE_90_CLOCKWISE)
    elif orientation == -1:
        image = cv2.rotate(image, cv2.ROTATE_90_COUNTERCLOCKWISE)
    output = model.detectAndCompute(image, top_k=top_k)[0]
    return Features(
        keypoints=output["keypoints"].cpu().numpy(),
        descriptors=output["descriptors"].cpu().numpy(),
        scores=output["scores"].cpu().numpy(),
        width=image.shape[1],
        height=image.shape[0],
        orientation=orientation,
    )


def rays(camera: Camera, features: Features, indices: np.ndarray) -> np.ndarray:
    points = features.keypoints[indices]
    if features.orientation == 1:
        points = np.column_stack((points[:, 1], features.width - 1.0 - points[:, 0]))
        original_width, original_height = features.height, features.width
    elif features.orientation == -1:
        points = np.column_stack((features.height - 1.0 - points[:, 1], points[:, 0]))
        original_width, original_height = features.height, features.width
    else:
        original_width, original_height = features.width, features.height
    scale = float(max(original_width, original_height))
    focal = camera.focal_35mm / 36.0 * scale
    local = np.column_stack(
        (
            (points[:, 0] - (original_width * 0.5 + camera.principal_u * scale)) / focal,
            (points[:, 1] - (original_height * 0.5 + camera.principal_v * scale)) / focal,
            np.ones(len(points)),
        )
    )
    local /= np.linalg.norm(local, axis=1, keepdims=True)
    return (camera.rotation.T @ local.T).T


def triangulate(
    first_camera: Camera,
    first_features: Features,
    first_indices: np.ndarray,
    second_camera: Camera,
    second_features: Features,
    second_indices: np.ndarray,
) -> tuple[np.ndarray, np.ndarray]:
    first_rays = rays(first_camera, first_features, first_indices)
    second_rays = rays(second_camera, second_features, second_indices)
    cosine = np.einsum("ij,ij->i", first_rays, second_rays)
    offset = second_camera.position - first_camera.position
    along_first = first_rays @ offset
    along_second = second_rays @ offset
    denominator = np.maximum(1e-8, 1.0 - cosine * cosine)
    first_depth = (along_first - cosine * along_second) / denominator
    second_depth = (cosine * along_first - along_second) / denominator
    first_points = first_camera.position + first_depth[:, None] * first_rays
    second_points = second_camera.position + second_depth[:, None] * second_rays
    points = (first_points + second_points) * 0.5
    gap = np.linalg.norm(first_points - second_points, axis=1)
    angle = np.degrees(np.arccos(np.clip(cosine, -1.0, 1.0)))
    valid = (
        (first_depth > 0.20)
        & (second_depth > 0.20)
        & (first_depth < 8.0)
        & (second_depth < 8.0)
        & (gap < 0.035)
        & (angle > 1.25)
        & (angle < 65.0)
    )
    quality = np.where(valid, angle / np.maximum(gap, 0.003), 0.0)
    return points, quality


def camera_pairs(cameras: list[Camera], neighbours: int) -> list[tuple[int, int]]:
    positions = np.stack([camera.position for camera in cameras])
    forwards = np.stack([camera.rotation.T @ np.array((0.0, 0.0, 1.0)) for camera in cameras])
    pairs: set[tuple[int, int]] = set()
    for first in range(len(cameras)):
        distance = np.linalg.norm(positions - positions[first], axis=1)
        direction = forwards @ forwards[first]
        candidates = np.where((distance > 0.035) & (distance < 1.10) & (direction > 0.25))[0]
        candidates = candidates[np.argsort(distance[candidates])[:neighbours]]
        for second in candidates:
            pairs.add(tuple(sorted((first, int(second)))))
    return sorted(pairs)


def write_avxf(points: np.ndarray, descriptors: np.ndarray, outputs: list[Path]) -> None:
    body = bytearray(b"AVXF")
    body += struct.pack("<ii", 1, len(points))
    quantized = np.clip(np.rint(descriptors * 127.0), -127, 127).astype(np.int8)
    for point, descriptor in zip(points.astype(np.float32), quantized):
        body += struct.pack("<fff", *point)
        body += descriptor.tobytes()
    encoded = base64.b64encode(gzip.compress(bytes(body), compresslevel=9)).decode("ascii")
    chunk = (len(encoded) + len(outputs) - 1) // len(outputs)
    for index, output in enumerate(outputs):
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(encoded[index * chunk : (index + 1) * chunk], encoding="ascii")


def rotation_error_degrees(first: np.ndarray, second: np.ndarray) -> float:
    value = (np.trace(first @ second.T) - 1.0) * 0.5
    return float(np.degrees(np.arccos(np.clip(value, -1.0, 1.0))))


def evaluate(
    holdout: list[Camera],
    feature_cache: dict[str, Features],
    points: np.ndarray,
    descriptors: np.ndarray,
    axis: np.ndarray,
    translation: np.ndarray,
    orientation: int,
) -> list[dict[str, object]]:
    matcher = cv2.BFMatcher(cv2.NORM_L2, crossCheck=False)
    map_descriptors = descriptors.astype(np.float32)
    results: list[dict[str, object]] = []
    for camera in holdout:
        features = feature_cache[f"{camera.stem}:{orientation}"]
        neighbours = matcher.knnMatch(features.descriptors.astype(np.float32), map_descriptors, k=2)
        matches = [first for first, second in neighbours if first.distance < 0.86 * second.distance]
        record: dict[str, object] = {"camera": camera.stem, "matches": len(matches), "success": False}
        if len(matches) >= 8:
            objects = np.float32([points[match.trainIdx] for match in matches])
            images = np.float32([features.keypoints[match.queryIdx] for match in matches])
            scale = float(max(features.width, features.height))
            focal = camera.focal_35mm / 36.0 * scale
            cx = 480 * 0.5 + camera.principal_u * scale
            cy = 640 * 0.5 + camera.principal_v * scale
            if orientation == 1:
                cx, cy = 640 - 1.0 - cy, cx
            elif orientation == -1:
                cx, cy = cy, 480 - 1.0 - cx
            matrix = np.array(
                (
                    (focal, 0.0, cx),
                    (0.0, focal, cy),
                    (0.0, 0.0, 1.0),
                ),
                dtype=np.float64,
            )
            solved, rotation_vector, vector, inliers = cv2.solvePnPRansac(
                objects,
                images,
                matrix,
                None,
                iterationsCount=10_000,
                reprojectionError=7.0,
                confidence=0.9999,
                flags=cv2.SOLVEPNP_AP3P,
            )
            count = 0 if inliers is None else len(inliers)
            record["inliers"] = count
            if solved and count >= 8:
                rotation, _ = cv2.Rodrigues(rotation_vector)
                centre = (-rotation.T @ vector).ravel()
                expected_centre = axis @ camera.position + translation
                sensor_rotation = np.eye(3)
                if orientation == 1:
                    sensor_rotation = np.array(((0.0, -1.0, 0.0), (1.0, 0.0, 0.0), (0.0, 0.0, 1.0)))
                elif orientation == -1:
                    sensor_rotation = np.array(((0.0, 1.0, 0.0), (-1.0, 0.0, 0.0), (0.0, 0.0, 1.0)))
                expected_rotation = sensor_rotation @ camera.rotation @ axis.T
                record.update(
                    success=True,
                    translation_error_metres=float(np.linalg.norm(centre - expected_centre)),
                    rotation_error_degrees=rotation_error_degrees(rotation, expected_rotation),
                )
        results.append(record)
        print("eval", camera.stem[:8], record)
    return results


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--photos", type=Path, required=True)
    parser.add_argument("--asset-dir", type=Path, required=True)
    parser.add_argument("--report", type=Path, required=True)
    parser.add_argument("--train", type=int, default=160)
    parser.add_argument("--holdout", type=int, default=36)
    parser.add_argument("--width", type=int, default=480)
    parser.add_argument("--top-k", type=int, default=1600)
    args = parser.parse_args()

    torch.set_num_threads(max(1, min(8, torch.get_num_threads())))
    model = torch.hub.load(
        "verlab/accelerated_features", "XFeat", pretrained=True, top_k=args.top_k, trust_repo=True
    )
    cameras = load_cameras(args.photos)
    # UUID filenames randomize capture order, giving deterministic spatial coverage
    # without leaking deliberately adjacent frames into the holdout by sequence.
    selected = cameras[: args.train + args.holdout]
    training = selected[: args.train]
    holdout = selected[args.train :]
    cache: dict[str, Features] = {}
    for number, camera in enumerate(selected, 1):
        for orientation in (0, 1, -1):
            cache[f"{camera.stem}:{orientation}"] = extract_features(
                model, camera, args.width, args.top_k, orientation
            )
        if number % 20 == 0 or number == len(selected):
            print(f"features {number}/{len(selected)}")

    positions = np.stack([camera.position for camera in training])
    minimum = positions.min(axis=0) - 2.0
    maximum = positions.max(axis=0) + 2.0
    candidates: list[tuple[float, np.ndarray, np.ndarray, int]] = []
    pairs = camera_pairs(training, neighbours=4)
    for pair_number, (first, second) in enumerate(pairs, 1):
        for orientation in (0, 1, -1):
            first_features = cache[f"{training[first].stem}:{orientation}"]
            second_features = cache[f"{training[second].stem}:{orientation}"]
            first_tensor = torch.from_numpy(first_features.descriptors)
            second_tensor = torch.from_numpy(second_features.descriptors)
            first_indices, second_indices = model.match(first_tensor, second_tensor, min_cossim=0.78)
            first_indices = first_indices.cpu().numpy()
            second_indices = second_indices.cpu().numpy()
            if len(first_indices) < 8:
                continue
            points, quality = triangulate(
                training[first], first_features, first_indices,
                training[second], second_features, second_indices,
            )
            valid = (quality > 0.0) & np.all((points >= minimum) & (points <= maximum), axis=1)
            for feature_index in np.where(valid)[0]:
                point = points[feature_index]
                candidates.append(
                    (
                        float(quality[feature_index] * first_features.scores[first_indices[feature_index]]),
                        point,
                        first_features.descriptors[first_indices[feature_index]],
                        orientation,
                    )
                )
                candidates.append(
                    (
                        float(quality[feature_index] * second_features.scores[second_indices[feature_index]]),
                        point,
                        second_features.descriptors[second_indices[feature_index]],
                        orientation,
                    )
                )
        if pair_number % 100 == 0 or pair_number == len(pairs):
            print(f"pairs {pair_number}/{len(pairs)} candidates {len(candidates)}")

    candidates.sort(key=lambda value: value[0], reverse=True)
    kept: list[tuple[np.ndarray, np.ndarray]] = []
    orientation_counts = {0: 0, 1: 0, -1: 0}
    per_orientation_limit = 9_300
    occupied: dict[tuple[int, int, int, int, int], int] = {}
    for _, point, descriptor, orientation in candidates:
        if orientation_counts[orientation] >= per_orientation_limit:
            continue
        voxel = tuple(np.floor(point / 0.025).astype(int))
        signature = int(np.argmax(np.abs(descriptor)) // 8)
        key = (*voxel, signature, orientation)
        if key in occupied:
            continue
        occupied[key] = len(kept)
        kept.append((point, descriptor / max(np.linalg.norm(descriptor), 1e-8)))
        orientation_counts[orientation] += 1
        if len(kept) == per_orientation_limit * 3:
            break
    if len(kept) < 1_000:
        raise RuntimeError(f"Only {len(kept)} validated features were triangulated")

    xmp_points = np.stack([value[0] for value in kept])
    descriptors = np.stack([value[1] for value in kept]).astype(np.float32)
    # Proper right-handed Z-up XMP -> Y-up application map conversion.  The
    # translation is the current scan pack's metric recentering approximation.
    axis = np.array(((1.0, 0.0, 0.0), (0.0, 0.0, 1.0), (0.0, -1.0, 0.0)))
    translation = np.array((18.7197206, -2.5986028, 17.8044839)) - np.array((18.15, -3.41, 16.0))
    points = (axis @ xmp_points.T).T + translation
    outputs = [
        args.asset_dir / "xfeat_feature_map_part_00",
        args.asset_dir / "xfeat_feature_map_part_01",
    ]
    write_avxf(points, descriptors, outputs)
    evaluations = {
        "portrait": evaluate(holdout, cache, points, descriptors, axis, translation, 0),
        "clockwise": evaluate(holdout, cache, points, descriptors, axis, translation, 1),
        "counterclockwise": evaluate(holdout, cache, points, descriptors, axis, translation, -1),
    }
    evaluation = evaluations["portrait"]
    successes = [item for item in evaluation if item["success"]]
    report = {
        "source_photo_count": len(cameras),
        "training_photo_count": len(training),
        "holdout_photo_count": len(holdout),
        "pair_count": len(pairs),
        "triangulated_candidate_count": len(candidates),
        "map_feature_count": len(points),
        "orientation_feature_counts": orientation_counts,
        "coordinate_axis": axis.tolist(),
        "coordinate_translation": translation.tolist(),
        "holdout_success_count": len(successes),
        "holdout_success_rate": len(successes) / len(evaluation),
        "median_translation_error_metres": None if not successes else float(np.median([item["translation_error_metres"] for item in successes])),
        "median_rotation_error_degrees": None if not successes else float(np.median([item["rotation_error_degrees"] for item in successes])),
        "holdout_results": evaluation,
        "orientation_results": {
            name: {
                "success_count": sum(bool(item["success"]) for item in values),
                "success_rate": sum(bool(item["success"]) for item in values) / len(values),
                "results": values,
            }
            for name, values in evaluations.items()
        },
    }
    args.report.write_text(json.dumps(report, indent=2), encoding="utf-8")
    print(json.dumps({key: value for key, value in report.items() if key not in ("holdout_results", "orientation_results")}, indent=2))


if __name__ == "__main__":
    main()
