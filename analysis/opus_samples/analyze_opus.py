#!/usr/bin/env python3
"""
Diagnose raw Opus segment files as used by NunaRecorder:
  16 kHz, stereo, fixed 80 bytes per packet, 20 ms per frame.

Usage:
  python3 analyze_opus.py [file_or_directory ...]
"""

from __future__ import annotations

import argparse
import shutil
import struct
import subprocess
import sys
from pathlib import Path

FRAME_SIZE = 80
SAMPLE_RATE = 16000
CHANNELS = 2

# Opus config byte (TOC): top 5 bits = config, rest = s/c flags
# Config 0-3 are SILK/hybrid, 16-31 are CELT-only (common for low-latency BLE)
def opus_config_name(cfg: int) -> str:
    if cfg <= 3:
        return f"Silk/Hybrid(config={cfg})"
    if 16 <= cfg <= 31:
        return f"CELT(config={cfg})"
    return f"unknown(config={cfg})"


def parse_toc(byte0: int) -> dict:
    config = (byte0 >> 3) & 0x1F
    s = (byte0 >> 2) & 1
    c = byte0 & 3
    return {
        "config": config,
        "config_name": opus_config_name(config),
        "stereo": s == 1 or c == 3,
        "c": c,
    }


def scan_ble_leakage(data: bytes) -> list[int]:
    """Find possible BLE message headers (0xAA 0x10) inside opus payload."""
    hits = []
    for i in range(len(data) - 6):
        if data[i] == 0xAA and data[i + 1] == 0x10:
            hits.append(i)
    return hits


def analyze_file(path: Path) -> str:
    data = path.read_bytes()
    n = len(data)
    lines: list[str] = []
    lines.append(f"=== {path.name} ===")
    lines.append(f"path: {path.resolve()}")
    lines.append(f"size: {n} bytes")
    lines.append(f"frames (80B): {n // FRAME_SIZE}, remainder: {n % FRAME_SIZE} bytes")

    if n == 0:
        lines.append("ERROR: empty file")
        return "\n".join(lines)

    if n % FRAME_SIZE != 0:
        lines.append(
            "WARN: size not multiple of 80 — decoder assumes fixed 80B frames; "
            "tail or mid-stream gap will desync all following frames."
        )

    # TOC stats on frame boundaries
    configs: dict[int, int] = {}
    bad_toc = 0
    for off in range(0, n - FRAME_SIZE + 1, FRAME_SIZE):
        toc = data[off]
        cfg = (toc >> 3) & 0x1F
        if cfg > 31:
            bad_toc += 1
        configs[cfg] = configs.get(cfg, 0) + 1

    lines.append(f"distinct TOC configs (top 8): {dict(sorted(configs.items(), key=lambda x: -x[1])[:8])}")
    lines.append(f"frames with config>31 (suspicious TOC): {bad_toc}")

    # First / middle / last frame TOC
    for label, idx in [("first", 0), ("mid", (n // FRAME_SIZE // 2) * FRAME_SIZE), ("last", (n // FRAME_SIZE - 1) * FRAME_SIZE)]:
        if idx + FRAME_SIZE <= n:
            toc = parse_toc(data[idx])
            lines.append(f"{label} frame @ {idx}: TOC=0x{data[idx]:02x} {toc}")

    # BLE header leakage
    leaks = scan_ble_leakage(data)
    if leaks:
        lines.append(f"WARN: found {len(leaks)} possible BLE headers (0xAA 0x10) inside file at offsets: {leaks[:20]}")
        if len(leaks) > 20:
            lines.append(f"  ... and {len(leaks) - 20} more")
    else:
        lines.append("OK: no obvious BLE 0xAA 0x10 headers inside opus payload")

    # Zero / constant runs (drops or padding)
    zero_frames = sum(
        1 for off in range(0, n, FRAME_SIZE) if off + FRAME_SIZE <= n and data[off : off + FRAME_SIZE] == b"\x00" * FRAME_SIZE
    )
    if zero_frames:
        lines.append(f"WARN: {zero_frames} all-zero 80-byte frames")

    # ffmpeg decode probe
    ffmpeg = shutil.which("ffmpeg")
    if ffmpeg:
        out = subprocess.run(
            [ffmpeg, "-hide_banner", "-loglevel", "error", "-f", "opus", "-i", str(path), "-f", "null", "-"],
            capture_output=True,
            text=True,
        )
        if out.returncode == 0:
            lines.append("ffmpeg: decode completed without error")
        else:
            err = (out.stderr or "").strip()
            lines.append(f"ffmpeg: decode FAILED (rc={out.returncode})")
            if err:
                lines.append(f"  stderr: {err[:500]}")
    else:
        lines.append("ffmpeg: not installed — skip full decode probe")

    # Hex snippet at first misaligned-looking region (config>31)
    for off in range(0, min(n, FRAME_SIZE * 20), FRAME_SIZE):
        if ((data[off] >> 3) & 0x1F) > 31:
            snippet = data[max(0, off - 16) : off + 96]
            lines.append(f"hex around suspicious frame @ {off}:")
            lines.append(snippet.hex(" "))
            break

    return "\n".join(lines)


def main() -> int:
    parser = argparse.ArgumentParser(description="Analyze NunaRecorder raw Opus segments")
    parser.add_argument("paths", nargs="+", help="Files or directories")
    args = parser.parse_args()

    files: list[Path] = []
    for p in args.paths:
        path = Path(p)
        if path.is_dir():
            files.extend(sorted(path.glob("*.opus")))
        elif path.is_file():
            files.append(path)

    if not files:
        print("No .opus files found.", file=sys.stderr)
        return 1

    report_dir = Path(__file__).resolve().parent / "_reports"
    report_dir.mkdir(exist_ok=True)

    for f in files:
        report = analyze_file(f)
        print(report)
        print()
        (report_dir / f"{f.stem}.txt").write_text(report, encoding="utf-8")

    print(f"Reports written to {report_dir}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
