import glob
import os
import re
import shutil
import subprocess
import sys


def main():
    if len(sys.argv) < 2:
        print(f"Usage: {sys.argv[0]} <output_folder>")
        sys.exit(1)

    output_folder = sys.argv[1]
    src_dir = os.path.dirname(os.path.abspath(__file__))

    wav_out = os.path.join(output_folder, "wav")
    mp3_out = os.path.join(output_folder, "mp3")
    os.makedirs(wav_out, exist_ok=True)
    os.makedirs(mp3_out, exist_ok=True)

    # Match files like DD09_blah blah.wav (two letters, two+ digits, underscore, rest)
    pattern = re.compile(r'^([A-Za-z]{2}\d{2})_.*\.wav$')

    wav_files = sorted(glob.glob(os.path.join(src_dir, "*.wav")))
    if not wav_files:
        print("No .wav files found in", src_dir)
        sys.exit(1)

    for src_path in wav_files:
        filename = os.path.basename(src_path)
        m = pattern.match(filename)
        if not m:
            print(f"  Skipping (no match): {filename}")
            continue

        prefix = m.group(1)
        wav_dest = os.path.join(wav_out, f"{prefix}.wav")
        mp3_dest = os.path.join(mp3_out, f"{prefix}.mp3")

        print(f"  {filename}")
        print(f"    -> {wav_dest}")
        shutil.copy2(src_path, wav_dest)

        print(f"    -> {mp3_dest}")
        subprocess.run(
            ["ffmpeg", "-y", "-i", src_path, "-q:a", "2", mp3_dest],
            check=True,
            stdout=subprocess.DEVNULL,
        )

    print("Done.")


if __name__ == "__main__":
    main()
