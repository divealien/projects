"""
Convert all audio files (wav, mp3, m4a, aif/aiff) in a folder
to 44.1kHz, 16-bit, stereo WAV using ffmpeg.

Non-wav files get a .wav extension. Existing wav files are converted
in-place; the original is preserved as file_orig.wav.

Usage: python convert_to_wav.py <folder>
"""

import argparse
import subprocess
import sys
from pathlib import Path

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
    parser = argparse.ArgumentParser(description="Convert audio files to 44.1kHz/16-bit/stereo WAV.")
    parser.add_argument("folder", type=Path, help="Folder containing audio files to convert")
    args = parser.parse_args()

    folder = args.folder
    if not folder.is_dir():
        print(f"Error: '{folder}' is not a directory.", file=sys.stderr)
        sys.exit(1)

    files = sorted(
        f for f in folder.iterdir()
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
            # Rename original to file_orig.wav, convert it to file.wav
            orig_backup = f.with_name(f.stem + "_orig.wav")
            f.rename(orig_backup)
            if convert(orig_backup, f):
                print(f"    -> converted in-place, original saved as {orig_backup.name}")
                converted += 1
            else:
                # Restore original on failure
                if f.exists():
                    f.unlink()
                orig_backup.rename(f)
                errors += 1
        else:
            # Non-wav: convert to .wav
            dst = f.with_suffix(".wav")
            if dst.exists():
                print(f"    -> WARNING: {dst.name} already exists, skipping")
                skipped += 1
                continue
            if convert(f, dst):
                print(f"    -> converted to {dst.name}")
                converted += 1
            else:
                errors += 1

    print(f"\nDone: {converted} converted, {skipped} skipped, {errors} errors")


if __name__ == "__main__":
    main()
