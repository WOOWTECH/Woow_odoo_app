#!/usr/bin/env python3
"""Offline, stdlib-only generation from approved read-only PNG assets. No external tools."""
import argparse
import hashlib
import json
import math
from pathlib import Path
import struct
import zlib

ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "app/src/apporo/res"


def read_rgba(path):
    data = path.read_bytes()
    if data[:8] != b"\x89PNG\r\n\x1a\n":
        raise ValueError("Not PNG")
    width, height, depth, kind, compression, filtering, interlace = struct.unpack(">IIBBBBB", data[16:29])
    if (depth, kind, compression, filtering, interlace) != (8, 6, 0, 0, 0):
        raise ValueError("Only non-interlaced RGBA8 source is supported")
    packed = bytearray()
    pos = 8
    while pos < len(data):
        size = struct.unpack(">I", data[pos:pos + 4])[0]
        if data[pos + 4:pos + 8] == b"IDAT":
            packed.extend(data[pos + 8:pos + 8 + size])
        pos += size + 12
    raw = zlib.decompress(packed)
    stride = width * 4
    pixels = bytearray(width * height * 4)
    for y in range(height):
        start = y * (stride + 1)
        filter_type = raw[start]
        for x in range(stride):
            i = y * stride + x
            a = pixels[i - 4] if x >= 4 else 0
            b = pixels[i - stride] if y else 0
            c = pixels[i - stride - 4] if y and x >= 4 else 0
            p = a + b - c
            distances = (abs(p - a), abs(p - b), abs(p - c))
            paeth = (a, b, c)[distances.index(min(distances))]
            predictor = (0, a, b, (a + b) // 2, paeth)[filter_type]
            pixels[i] = (raw[start + x + 1] + predictor) & 255
    return width, height, pixels


def png_bytes(size, pixels):
    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data))
    rows = b"".join(b"\0" + pixels[y * size * 4:(y + 1) * size * 4] for y in range(size))
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", size, size, 8, 6, 0, 0, 0)) +
            chunk(b"IDAT", zlib.compress(rows, 9)) + chunk(b"IEND", b""))


def on_white(source):
    width, height, pixels = source
    if width != height:
        raise ValueError("Approved sources must be square; no crop is permitted")
    # Composite source on white before bilinear sampling: avoids alpha-fringe darkening.
    white = bytearray(width * height * 3)
    for i in range(width * height):
        alpha = pixels[i * 4 + 3]
        for c in range(3):
            white[i * 3 + c] = (pixels[i * 4 + c] * alpha + 255 * (255 - alpha) + 127) // 255
    return width, height, white


def paint_mark(result, size, flat, fraction):
    """Bilinear-sample the white-composited source into a centred square; RGB only, alpha untouched."""
    width, height, white = flat
    extent = round(size * fraction)
    offset = (size - extent) // 2
    for y in range(extent):
        sy = max(0, min(height - 1, (y + 0.5) * height / extent - 0.5))
        y0 = math.floor(sy)
        y1 = min(y0 + 1, height - 1)
        fy = sy - y0
        for x in range(extent):
            sx = max(0, min(width - 1, (x + 0.5) * width / extent - 0.5))
            x0 = math.floor(sx)
            x1 = min(x0 + 1, width - 1)
            fx = sx - x0
            for c in range(3):
                top = white[(y0 * width + x0) * 3 + c] * (1 - fx) + white[(y0 * width + x1) * 3 + c] * fx
                bottom = white[(y1 * width + x0) * 3 + c] * (1 - fx) + white[(y1 * width + x1) * 3 + c] * fx
                result[((y + offset) * size + x + offset) * 4 + c] = round(top * (1 - fy) + bottom * fy)
    return extent, offset


def render(source, size, fraction):
    result = bytearray([255]) * (size * size * 4)
    paint_mark(result, size, on_white(source), fraction)
    return png_bytes(size, result)


# Circular login badge (shared spec with iOS): transparent outside a full-canvas disc, white fill,
# a light-grey inner stroke so the circle still reads on a white page, mark 60% of the edge centred.
BADGE_FILL = 0xFF
BADGE_STROKE = 0xD9
BADGE_STROKE_FRACTION = 0.03
BADGE_MARK_FRACTION = 0.60
BADGE_SUPERSAMPLE = 4


def render_badge(source, size, fraction=BADGE_MARK_FRACTION):
    center = size / 2
    outer = size / 2
    inner = outer - size * BADGE_STROKE_FRACTION
    n = BADGE_SUPERSAMPLE
    offsets = [(k + 0.5) / n for k in range(n)]
    result = bytearray(size * size * 4)
    for y in range(size):
        for x in range(size):
            covered = ring = 0
            for oy in offsets:
                dy = y + oy - center
                for ox in offsets:
                    dx = x + ox - center
                    d2 = dx * dx + dy * dy
                    if d2 <= outer * outer:
                        covered += 1
                        if d2 > inner * inner:
                            ring += 1
            if not covered:
                continue  # fully outside the disc: RGBA 0,0,0,0
            i = (y * size + x) * 4
            value = round((ring * BADGE_STROKE + (covered - ring) * BADGE_FILL) / covered)
            result[i:i + 3] = bytes((value, value, value))
            result[i + 3] = round(255 * covered / (n * n))
    extent, offset = paint_mark(result, size, on_white(source), fraction)
    # The mark square must sit wholly inside the white fill (no overlap with the stroke or the edge).
    if math.hypot(offset - center, offset - center) > inner or \
            math.hypot(offset + extent - center, offset + extent - center) > inner:
        raise ValueError("Badge mark would overlap the stroke")
    return png_bytes(size, result)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-dir", type=Path, required=True)
    args = parser.parse_args()
    names = ("apporo-mark.png", "apporo-logo-full.png")
    sources = {n: read_rgba(args.source_dir / n) for n in names}
    manifest = {"source_directory": "~/WOOW-mobile-app-analysis/sources/Woow_apporo_ha_app/tools/brand/assets/",
                "method": ("RGBA8 bilinear; composite white #FFFFFF; no crop/recolor; adaptive artwork square 60/108, "
                           "contained in 66dp safe circle; woow_logo = circular badge: alpha 0 outside a disc of "
                           "canvas diameter, anti-aliased by 4x4 supersample coverage, fill #FFFFFF, inner stroke "
                           "#D9D9D9 3% of edge, mark 60% of edge centred, same image light/dark"),
                "inputs": {n: {"sha256": hashlib.sha256((args.source_dir / n).read_bytes()).hexdigest(),
                               "size": [sources[n][0], sources[n][1]]} for n in names}, "outputs": []}
    cache = {}

    def emit(relative, source, size, fraction, badge=False):
        key = (source, size, fraction, badge)
        if key not in cache:
            cache[key] = (render_badge if badge else render)(sources[source], size, fraction)
        data = cache[key]
        path = OUTPUT / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)
        record = {"path": str(path.relative_to(ROOT)), "source": source,
                  "size": [size, size], "fraction": fraction,
                  "sha256": hashlib.sha256(data).hexdigest()}
        if badge:
            record["badge"] = {"shape": "circle", "fill": "#FFFFFF", "stroke": "#D9D9D9",
                               "stroke_fraction": BADGE_STROKE_FRACTION, "supersample": BADGE_SUPERSAMPLE}
        manifest["outputs"].append(record)

    for density, scale in (("mdpi", 1), ("hdpi", 1.5), ("xhdpi", 2), ("xxhdpi", 3), ("xxxhdpi", 4)):
        emit(f"drawable-{density}/woow_logo.png", names[0], round(72 * scale), BADGE_MARK_FRACTION, badge=True)
        for kind in ("drawable", "mipmap"):
            emit(f"{kind}-{density}/ic_launcher_foreground.png", names[0], round(108 * scale), 60 / 108)
        for name in ("ic_launcher", "ic_launcher_round"):
            emit(f"mipmap-{density}/{name}.png", names[0], round(48 * scale), 0.70)
    emit("drawable/ic_launcher_foreground.png", names[0], 432, 60 / 108)
    emit("drawable-nodpi/apporo_logo_full.png", names[1], 512, 1)
    (ROOT / "docs/plans/2026-09-24-apporo-assets.json").write_text(json.dumps(manifest, indent=2) + "\n")


if __name__ == "__main__":
    main()
