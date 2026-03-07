#!/usr/bin/env python3

import subprocess
import sys
import time
from pathlib import Path

FFMPEG = "ffmpeg"

PROFILES = [
    {
        "name": "720p_nvenc",
        "scale": "-2:720",
        "vcodec": ["-c:v", "h264_nvenc", "-preset", "p6", "-cq", "19", "-pix_fmt", "yuv420p"],
    },
    {
        "name": "720p_x264",
        "scale": "-2:720",
        "vcodec": ["-c:v", "libx264", "-preset", "slow", "-crf", "20"],
    },
    {
        "name": "480p_nvenc",
        "scale": "-2:480",
        "vcodec": ["-c:v", "h264_nvenc", "-preset", "p6", "-cq", "18", "-pix_fmt", "yuv420p"],
    },
    {
        "name": "480p_x264",
        "scale": "-2:480",
        "vcodec": ["-c:v", "libx264", "-preset", "slow", "-crf", "19"],
    },
]

results = []

def run_encode(input_path, profile):
    output_path = input_path.with_stem(
        input_path.stem + "_" + profile["name"]
    )

    cmd = [
        FFMPEG,
        "-y",
        "-i", str(input_path),
        "-map", "0",
        "-vf", f"scale={profile['scale']}",
        *profile["vcodec"],
        "-c:a", "copy",
        "-c:s", "copy",
        str(output_path),
    ]

    print("\n=== Encoding:", profile["name"], "===")
    start = time.perf_counter()

    result = subprocess.run(
        cmd,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
    )

    elapsed = time.perf_counter() - start

    if result.returncode != 0:
        print(result.stdout)
        raise RuntimeError("ffmpeg failed: " + profile["name"])

    size_mb = output_path.stat().st_size / (1024 * 1024)

    print("Done:", profile["name"])
    print("Time: {:.2f}s  Size: {:.1f} MB".format(elapsed, size_mb))

    results.append({
        "name": profile["name"],
        "time": elapsed,
        "size": size_mb,
    })


def main():
    if len(sys.argv) != 2:
        print("Usage: encode_compare.py input.mkv")
        sys.exit(1)

    input_path = Path(sys.argv[1])
    if not input_path.exists():
        print("Input not found:", input_path)
        sys.exit(1)

    print("Input:", input_path)

    total_start = time.perf_counter()

    for profile in PROFILES:
        run_encode(input_path, profile)

    total_time = time.perf_counter() - total_start

    print("\n=== Summary ===")
    print("{:<15} {:>10} {:>12}".format("Profile", "Time(s)", "Size(MB)"))
    print("-" * 40)

    for r in results:
        print("{:<15} {:>10.2f} {:>12.1f}".format(
            r["name"], r["time"], r["size"]
        ))

    print("-" * 40)
    print("Total time: {:.2f}s".format(total_time))


if __name__ == "__main__":
    main()
