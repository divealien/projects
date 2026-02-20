"""
Adjust each wav file's volume by a uniform gain (whole track)
to bring RMS to a common target. Uses ffmpeg volume filter.
"""

import subprocess
import re
from pathlib import Path

FOLDER = Path(__file__).parent
TARGET_RMS = -22.0  # dB


def get_rms(filepath: Path) -> float | None:
    cmd = [
        "ffmpeg", "-i", str(filepath),
        "-af", "volumedetect",
        "-f", "null", "-"
    ]
    result = subprocess.run(cmd, capture_output=True, text=True)
    m = re.search(r"mean_volume:\s*([-\d.]+)\s*dB", result.stderr)
    if m:
        return float(m.group(1))
    return None


def apply_gain(src: Path, gain_db: float) -> bool:
    tmp = src.with_suffix(".tmp.wav")
    cmd = [
        "ffmpeg", "-y", "-i", str(src),
        "-af", f"volume={gain_db:+.1f}dB",
        "-ar", "44100", "-sample_fmt", "s16", "-ac", "2",
        str(tmp),
    ]
    result = subprocess.run(cmd, capture_output=True, text=True)
    if result.returncode != 0:
        print(f"  ERROR: {result.stderr.splitlines()[-1] if result.stderr else 'unknown'}")
        if tmp.exists():
            tmp.unlink()
        return False
    src.unlink()
    tmp.rename(src)
    return True


def main():
    files = sorted(
        f for f in FOLDER.iterdir()
        if f.is_file() and f.suffix.lower() == ".wav"
    )

    if not files:
        print("No wav files found.")
        return

    print(f"Target RMS: {TARGET_RMS:.1f} dB\n")

    name_width = max(len(f.name) for f in files)
    adjusted = 0
    skipped = 0
    errors = 0

    for f in files:
        rms = get_rms(f)
        if rms is None:
            print(f"  {f.name:<{name_width}}  could not read RMS")
            errors += 1
            continue

        gain = TARGET_RMS - rms

        if abs(gain) < 0.3:
            print(f"  {f.name:<{name_width}}  RMS {rms:+.1f}  gain {gain:+.1f} dB  -> skipped (close enough)")
            skipped += 1
            continue

        print(f"  {f.name:<{name_width}}  RMS {rms:+.1f}  gain {gain:+.1f} dB  -> ", end="", flush=True)

        if apply_gain(f, gain):
            print("done")
            adjusted += 1
        else:
            errors += 1

    print(f"\nDone: {adjusted} adjusted, {skipped} skipped, {errors} errors")


if __name__ == "__main__":
    main()
