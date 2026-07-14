#!/usr/bin/env python3
"""Translate every root node in a GLB into a chosen metric map origin."""

import argparse
import json
import struct
from pathlib import Path

JSON_CHUNK = 0x4E4F534A


def padded(data: bytes, pad: bytes) -> bytes:
    return data + pad * ((-len(data)) % 4)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("input", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--origin", nargs=3, type=float, required=True, metavar=("X", "Y", "Z"))
    args = parser.parse_args()

    data = args.input.read_bytes()
    magic, version, length = struct.unpack_from("<4sII", data, 0)
    if magic != b"glTF" or version != 2 or length != len(data):
        raise ValueError("Expected a complete glTF 2.0 binary file")

    chunks: list[tuple[int, bytes]] = []
    offset = 12
    while offset < len(data):
        chunk_length, chunk_type = struct.unpack_from("<II", data, offset)
        offset += 8
        chunks.append((chunk_type, data[offset : offset + chunk_length]))
        offset += chunk_length

    document = json.loads(next(chunk for kind, chunk in chunks if kind == JSON_CHUNK))
    root_nodes = {
        node_index
        for scene in document.get("scenes", [])
        for node_index in scene.get("nodes", [])
    }
    for node_index in root_nodes:
        node = document["nodes"][node_index]
        if "matrix" in node:
            node["matrix"][12] -= args.origin[0]
            node["matrix"][13] -= args.origin[1]
            node["matrix"][14] -= args.origin[2]
        else:
            translation = node.setdefault("translation", [0.0, 0.0, 0.0])
            node["translation"] = [
                translation[0] - args.origin[0],
                translation[1] - args.origin[1],
                translation[2] - args.origin[2],
            ]
        node["name"] = node.get("name", "Living room metric scan")

    json_bytes = padded(
        json.dumps(document, separators=(",", ":"), ensure_ascii=False).encode("utf-8"),
        b" ",
    )
    output_chunks = []
    for chunk_type, chunk in chunks:
        payload = json_bytes if chunk_type == JSON_CHUNK else padded(chunk, b"\0")
        output_chunks.append(struct.pack("<II", len(payload), chunk_type) + payload)
    body = b"".join(output_chunks)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_bytes(struct.pack("<4sII", b"glTF", 2, 12 + len(body)) + body)


if __name__ == "__main__":
    main()
