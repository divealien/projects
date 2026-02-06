#!/usr/bin/env python3

import subprocess
import sys
import os
from pathlib import Path
import shutil

def run(cmd):
    print("Running:", " ".join(cmd))
    subprocess.check_call(cmd)

def main(mp4_path):
    mp4_path = Path(mp4_path).resolve()
    if not mp4_path.exists():
        print("File not found:", mp4_path)
        sys.exit(1)

    workdir = Path.home() / "demucs_work" / mp4_path.stem
    workdir.mkdir(parents=True, exist_ok=True)

    audio_wav = workdir / "audio.wav"
    demucs_out = workdir / "demucs"
    vocals_wav = demucs_out / "htdemucs" / "audio" / "vocals.wav"
    output_mp4 = mp4_path.with_name(mp4_path.stem + "_dialogue.mp4")

    # 1) Extract audio
    run([
        "ffmpeg", "-y",
        "-i", str(mp4_path),
        "-vn",
        "-ac", "1",
        "-ar", "16000",
        str(audio_wav)
    ])

    # 2) Run demucs (vocals separation)
    demucs_cmd = [
        "demucs",
        "-n", "htdemucs",
        "--two-stems", "vocals",
        "-o", str(demucs_out),
        str(audio_wav)
    ]
    run(demucs_cmd)

    if not vocals_wav.exists():
        print("Vocals file not found:", vocals_wav)
        sys.exit(1)

    # 3) Normalize vocals (helps intelligibility)
    vocals_norm = workdir / "vocals_norm.wav"
    run([
        "ffmpeg", "-y",
        "-i", str(vocals_wav),
        "-af", "loudnorm",
        "-c:a", "pcm_s16le",
        "-ar", "44100",
        "-ac", "1",
        str(vocals_norm)
    ])

    # 4) Mux cleaned audio back into video
    run([
        "ffmpeg", "-y",
        "-i", str(mp4_path),
        "-i", str(vocals_norm),
        "-map", "0:v:0",
        "-map", "1:a:0",
        "-c:v", "copy",
        "-c:a", "aac",
        "-b:a", "192k",
        str(output_mp4)
    ])

    print("Done:")
    print(output_mp4)

    # Cleanup
    shutil.rmtree(workdir, ignore_errors=True)

if __name__ == "__main__":
    if len(sys.argv) != 2:
        print("Usage: clean_dialogue.py input.mp4")
        sys.exit(1)

    main(sys.argv[1])
