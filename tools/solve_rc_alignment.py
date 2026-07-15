"""Recover the rigid RealityCapture-XMP to exported-GLB transform.

RealityCapture can export a model in a different coordinate system from its XMP
camera poses.  This tool searches the finite axis/convention choices and scores
them by rendering the textured mesh into several registered source photographs.
The winning transform is therefore supported by image evidence, not by a guessed
up-axis conversion.
"""

from __future__ import annotations

import argparse
import json
import re
from dataclasses import dataclass
from itertools import permutations, product
from pathlib import Path

import cv2
import numpy as np
import trimesh
from PIL import Image
from trimesh.ray.ray_pyembree import RayMeshIntersector


@dataclass(frozen=True)
class Camera:
    stem: str
    image: Path
    position: np.ndarray
    rotation: np.ndarray
    focal_35mm: float
    principal_u: float
    principal_v: float


def xmp_value(text: str, name: str) -> str:
    attribute = re.search(rf'xcr:{name}="([^"]+)"', text)
    if attribute:
        return attribute.group(1)
    element = re.search(rf'<xcr:{name}>([^<]+)</xcr:{name}>', text)
    if element:
        return element.group(1).strip()
    raise ValueError(f"Missing xcr:{name}")


def load_cameras(photo_dir: Path) -> list[Camera]:
    cameras: list[Camera] = []
    for image in sorted(photo_dir.glob("*.jpg")):
        xmp = image.with_suffix(".xmp")
        if not xmp.exists():
            continue
        text = xmp.read_text(encoding="utf-8", errors="replace")
        cameras.append(
            Camera(
                stem=image.stem,
                image=image,
                position=np.fromstring(xmp_value(text, "Position"), sep=" "),
                rotation=np.fromstring(xmp_value(text, "Rotation"), sep=" ").reshape(3, 3),
                focal_35mm=float(xmp_value(text, "FocalLength35mm")),
                principal_u=float(xmp_value(text, "PrincipalPointU")),
                principal_v=float(xmp_value(text, "PrincipalPointV")),
            )
        )
    if not cameras:
        raise ValueError(f"No JPG/XMP pairs found in {photo_dir}")
    return cameras


def signed_permutation_rotations(proper_only: bool = True) -> list[np.ndarray]:
    rotations: list[np.ndarray] = []
    for perm in permutations(range(3)):
        for signs in product((-1.0, 1.0), repeat=3):
            matrix = np.zeros((3, 3), dtype=np.float64)
            matrix[np.arange(3), perm] = signs
            if not proper_only or abs(np.linalg.det(matrix)) > 0.5:
                rotations.append(matrix)
    return rotations


def correlation(a: np.ndarray, b: np.ndarray) -> float:
    a = a.astype(np.float64)
    b = b.astype(np.float64)
    a -= a.mean()
    b -= b.mean()
    denominator = np.linalg.norm(a) * np.linalg.norm(b)
    return float(a.dot(b) / denominator) if denominator > 1e-9 else -1.0


def sample_texture(
    mesh: trimesh.Trimesh,
    texture: np.ndarray,
    locations: np.ndarray,
    triangles: np.ndarray,
    flip_v: bool,
) -> np.ndarray:
    triangle_vertices = mesh.triangles[triangles]
    barycentric = trimesh.triangles.points_to_barycentric(triangle_vertices, locations)
    triangle_uv = mesh.visual.uv[mesh.faces[triangles]]
    uv = np.einsum("ni,nij->nj", barycentric, triangle_uv)
    height, width = texture.shape[:2]
    x = np.clip(np.rint(uv[:, 0] * (width - 1)), 0, width - 1).astype(np.int32)
    v = 1.0 - uv[:, 1] if flip_v else uv[:, 1]
    y = np.clip(np.rint(v * (height - 1)), 0, height - 1).astype(np.int32)
    return texture[y, x]


def camera_grid(camera: Camera, width: int) -> tuple[np.ndarray, np.ndarray, tuple[int, int]]:
    with Image.open(camera.image) as source:
        source = source.convert("RGB")
        output_width = width
        output_height = round(source.height * output_width / source.width)
        photo = np.asarray(source.resize((output_width, output_height), Image.Resampling.BILINEAR))

    # RealityCapture's normalized 35 mm calibration uses max(image dimensions).
    scale = float(max(output_width, output_height))
    focal = camera.focal_35mm / 36.0 * scale
    cx = output_width * 0.5 + camera.principal_u * scale
    cy = output_height * 0.5 + camera.principal_v * scale
    yy, xx = np.mgrid[0:output_height, 0:output_width]
    rays = np.column_stack(
        ((xx.ravel() - cx) / focal, (yy.ravel() - cy) / focal, np.ones(xx.size))
    )
    rays /= np.linalg.norm(rays, axis=1, keepdims=True)
    return rays, photo.reshape(-1, 3), (output_height, output_width)


def choose_cameras(cameras: list[Camera], count: int) -> list[Camera]:
    """Deterministic farthest-point sampling over position and viewing rotation."""
    count = min(count, len(cameras))
    positions = np.stack([camera.position for camera in cameras])
    centre = positions.mean(axis=0)
    chosen = [int(np.argmax(np.linalg.norm(positions - centre, axis=1)))]
    distance = np.linalg.norm(positions - positions[chosen[0]], axis=1)
    while len(chosen) < count:
        candidate = int(np.argmax(distance))
        chosen.append(candidate)
        distance = np.minimum(distance, np.linalg.norm(positions - positions[candidate], axis=1))
    return [cameras[index] for index in chosen]


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--photos", type=Path, required=True)
    parser.add_argument("--model", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--camera-count", type=int, default=7)
    parser.add_argument("--render-width", type=int, default=48)
    parser.add_argument("--top", type=int, default=20)
    args = parser.parse_args()

    cameras = load_cameras(args.photos)
    selected = choose_cameras(cameras, args.camera_count)
    scene = trimesh.load(args.model, force="scene", process=False)
    mesh = scene.dump(concatenate=True)
    intersector = RayMeshIntersector(mesh)
    material = mesh.visual.material
    texture_image = getattr(material, "baseColorTexture", None) or getattr(material, "image", None)
    if texture_image is None:
        raise ValueError("The model has no base-colour texture")
    texture = np.asarray(texture_image.convert("RGB"))

    axis_rotations = signed_permutation_rotations()
    # Image coordinates (x right, y down) can introduce a handedness change
    # relative to the camera convention used in XMP, so test both parities here.
    camera_axes = signed_permutation_rotations(proper_only=False)
    position_mean = np.stack([camera.position for camera in cameras]).mean(axis=0)
    mesh_centre = mesh.bounds.mean(axis=0)
    grids = {camera.stem: camera_grid(camera, args.render_width) for camera in selected}
    ray_total = sum(len(grid[0]) for grid in grids.values())
    results: list[dict[str, object]] = []

    for world_axis_index, world_axis in enumerate(axis_rotations):
        translation = mesh_centre - world_axis @ position_mean
        for transpose in (False, True):
            for camera_axis_index, camera_axis in enumerate(camera_axes):
                photo_chunks: list[np.ndarray] = []
                render_chunks = [[], []]
                hits_total = 0
                for camera in selected:
                    local_rays, photo, _ = grids[camera.stem]
                    rotation = camera.rotation.T if transpose else camera.rotation
                    directions = (world_axis @ rotation @ camera_axis @ local_rays.T).T
                    origin = world_axis @ camera.position + translation
                    locations, ray_ids, triangle_ids = intersector.intersects_location(
                        np.repeat(origin[None, :], len(directions), axis=0),
                        directions,
                        multiple_hits=False,
                    )
                    if len(ray_ids) < max(64, len(directions) // 5):
                        continue
                    hits_total += len(ray_ids)
                    photo_chunks.append(photo[ray_ids])
                    for flip_index, flip_v in enumerate((False, True)):
                        render_chunks[flip_index].append(
                            sample_texture(mesh, texture, locations, triangle_ids, flip_v)
                        )
                if not photo_chunks:
                    continue
                photo_pixels = np.concatenate(photo_chunks)
                photo_gray = cv2.cvtColor(photo_pixels[:, None, :], cv2.COLOR_RGB2GRAY).ravel()
                flip_scores: list[float] = []
                for chunks in render_chunks:
                    render_pixels = np.concatenate(chunks)
                    render_gray = cv2.cvtColor(render_pixels[:, None, :], cv2.COLOR_RGB2GRAY).ravel()
                    gray_score = correlation(photo_gray, render_gray)
                    channel_score = np.mean(
                        [correlation(photo_pixels[:, channel], render_pixels[:, channel]) for channel in range(3)]
                    )
                    flip_scores.append(0.55 * gray_score + 0.45 * channel_score)
                best_flip = int(np.argmax(flip_scores))
                photometric_score = flip_scores[best_flip]
                coverage = hits_total / ray_total
                # Tiny accidental patches can correlate well. A localization map needs
                # broad visible geometry, so rank by appearance and ray coverage.
                combined_score = photometric_score * np.sqrt(coverage)
                results.append(
                    {
                        "score": combined_score,
                        "photometric_score": photometric_score,
                        "coverage": coverage,
                        "hits": hits_total,
                        "world_axis_index": world_axis_index,
                        "world_axis": world_axis.astype(int).tolist(),
                        "translation": translation.tolist(),
                        "rotation_transposed": transpose,
                        "camera_axis_index": camera_axis_index,
                        "camera_axis": camera_axis.astype(int).tolist(),
                        "flip_texture_v": bool(best_flip),
                        "camera_stems": [camera.stem for camera in selected],
                    }
                )

    results.sort(key=lambda result: float(result["score"]), reverse=True)
    output = {
        "model": str(args.model.resolve()),
        "photos": str(args.photos.resolve()),
        "photo_count": len(cameras),
        "method": "textured-mesh photometric signed-axis search",
        "results": results[: args.top],
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(output, indent=2), encoding="utf-8")
    print(json.dumps(output, indent=2))


if __name__ == "__main__":
    main()
