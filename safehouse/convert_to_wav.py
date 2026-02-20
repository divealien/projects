"""
Convert all audio files (wav, mp3, m4a, aif/aiff) in this folder
to 44.1kHz, 16-bit, stereo WAV using ffmpeg.

Non-wav files get a .wav extension. Existing wav files are converted
in-place (via a temp file) if they don't already match the target spec.
"""

import subprocess
import sys
from pathlib import Path

FOLDER = Path(__file__).parent
EXTENSIONS = {".wav", ".mp3", ".m4a", ".aif", ".aiff"}
TARGET_RATE = "44100"
TARGET_BITS = "s16"  # signed 16-bit PCM
TARGET_CHANNELS = "2"


def probe(filepath: Path) -> dict:
    """Return sample rate, bits per sample, and channels via ffprobe."""
    cmd = [
        "ffprobe", "-v", "error",
        "-select_streams", "a:0",
        "-show_entries", "stream=sample_rate,bits_per_sample,channels,codec_name",
        "-of", "csv=p=0",
        str(filepath),
    ]
    result = subprocess.run(cmd, capture_output=True, text=True)
    parts = result.stdout.strip().split(",")
    if len(parts) >= 4:
        return {
            "codec": parts[0],
            "sample_rate": parts[1],
            "channels": parts[2],
            "bits": parts[3],
        }
    return {}


def already_correct(filepath: Path) -> bool:
    """Check if a wav file already matches 44.1k/16bit/stereo."""
    info = probe(filepath)
    return (
        info.get("codec") == "pcm_s16le"
        and info.get("sample_rate") == TARGET_RATE
        and info.get("channels") == TARGET_CHANNELS
        and info.get("bits") == "16"
    )


def convert(src: Path, dst: Path):
    """Convert src to 44.1k/16bit/stereo wav at dst."""
    cmd = [
        "ffmpeg", "-y", "-i", str(src),
        "-ar", TARGET_RATE,
        "-sample_fmt", TARGET_BITS,
        "-ac", TARGET_CHANNELS,
        str(dst),
    ]
    result = subprocess.run(cmd, capture_output=True, text=True)
    if result.returncode != 0:
        print(f"  ERROR: {result.stderr.splitlines()[-1] if result.stderr else 'unknown'}")
        return False
    return True


def main():
    files = sorted(
        f for f in FOLDER.iterdir()
        if f.is_file() and f.suffix.lower() in EXTENSIONS
    )

    if not files:
        print("No audio files found.")
        return

    print(f"Found {len(files)} audio files.\n")

    converted = 0
    skipped = 0
    errors = 0

    for f in files:
        print(f"  {f.name}")

        if f.suffix.lower() == ".wav":
            if already_correct(f):
                print("    -> already 44.1k/16bit/stereo, skipping")
                skipped += 1
                continue
            # Convert in-place via temp file
            tmp = f.with_suffix(".tmp.wav")
            if convert(f, tmp):
                f.unlink()
                tmp.rename(f)
                print("    -> converted in-place")
                converted += 1
            else:
                if tmp.exists():
                    tmp.unlink()
                errors += 1
        else:
            # Non-wav: convert to .wav
            dst = f.with_suffix(".wav")
            if dst.exists():
                print(f"    -> WARNING: {dst.name} already exists, skipping")
                skipped += 1
                continue
            if convert(f, dst):
                f.unlink()
                print(f"    -> converted to {dst.name}, original removed")
                converted += 1
            else:
                errors += 1

    print(f"\nDone: {converted} converted, {skipped} skipped, {errors} errors")


if __name__ == "__main__":
    main()
