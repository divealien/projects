import subprocess
import json
import time
import os
from pathlib import Path


# ---------------------------
# helpers
# ---------------------------

def run(cmd):
    start = time.time()
    subprocess.run(cmd, check=True)
    return time.time() - start


def probe_json(path):
    cmd = [
        "ffprobe",
        "-v", "error",
        "-of", "json",
        "-show_format",
        "-show_streams",
        path
    ]
    r = subprocess.run(cmd, capture_output=True, text=True, check=True)
    return json.loads(r.stdout)


def duration_seconds(path):
    info = probe_json(path)
    return float(info["format"]["duration"])


def has_subtitles(path):
    info = probe_json(path)
    for s in info["streams"]:
        if s.get("codec_type") == "subtitle":
            return True
    return False


def file_mb(path):
    return os.path.getsize(path) / (1024 * 1024)


# ---------------------------
# whisper
# ---------------------------

def generate_whisper_srt(
    input_media,
    srt_out,
    model="medium",
    device="cuda"
):
    base = Path(input_media).stem

    cmd = [
        "whisper",
        input_media,
        "--model", model,
        "--device", device,
        "--output_format", "srt",
        "--output_dir", ".",
        "--fp16", "True"
    ]

    elapsed = run(cmd)

    generated = base + ".srt"
    if not os.path.exists(generated):
        raise RuntimeError("Whisper did not produce SRT")

    os.rename(generated, srt_out)
    return elapsed


# ---------------------------
# muxing
# ---------------------------

def mux_srt_into_mkv(
    input_mkv,
    srt,
    output_mkv,
    language="eng",
    title="Whisper"
):
    cmd = [
        "ffmpeg", "-y",
        "-i", input_mkv,
        "-i", srt,
        "-map", "0",
        "-map", "1",
        "-c", "copy",
        "-c:s", "srt",
        "-metadata:s:s:0", f"language={language}",
        "-metadata:s:s:0", f"title={title}",
        "-disposition:s:0", "default",
        output_mkv
    ]
    return run(cmd)


def ensure_subtitles(input_mkv, output_mkv):
    if has_subtitles(input_mkv):
        return run([
            "ffmpeg", "-y",
            "-i", input_mkv,
            "-c", "copy",
            output_mkv
        ])

    tmp_srt = "_whisper_tmp.srt"
    t1 = generate_whisper_srt(input_mkv, tmp_srt)
    t2 = mux_srt_into_mkv(input_mkv, tmp_srt, output_mkv)
    os.remove(tmp_srt)
    return t1 + t2


# ---------------------------
# bitrate math
# ---------------------------

def bitrate_for_target_size(
    duration_sec,
    target_mb_per_hour,
    audio_kbps=128
):
    video_kbps_total = (target_mb_per_hour * 8192) / 3600
    return int(video_kbps_total - audio_kbps)


# ---------------------------
# encoders
# ---------------------------

def encode_x264(
    input_mkv,
    output_mkv,
    width,
    height,
    video_kbps
):
    cmd = [
        "ffmpeg", "-y",
        "-i", input_mkv,
        "-vf", f"scale={width}:{height}",
        "-c:v", "libx264",
        "-profile:v", "high",
        "-preset", "slow",
        "-b:v", f"{video_kbps}k",
        "-maxrate", f"{video_kbps}k",
        "-bufsize", f"{video_kbps * 2}k",
        "-c:a", "aac",
        "-b:a", "128k",
        "-c:s", "copy",
        output_mkv
    ]
    return run(cmd)


def encode_nvenc(
    input_mkv,
    output_mkv,
    width,
    height,
    video_kbps
):
    cmd = [
        "ffmpeg", "-y",
        "-i", input_mkv,
        "-vf", f"scale={width}:{height}",
        "-c:v", "h264_nvenc",
        "-profile:v", "high",
        "-preset", "p5",
        "-rc", "vbr",
        "-b:v", f"{video_kbps}k",
        "-maxrate", f"{video_kbps}k",
        "-bufsize", f"{video_kbps * 2}k",
        "-c:a", "aac",
        "-b:a", "128k",
        "-c:s", "copy",
        output_mkv
    ]
    return run(cmd)


# ---------------------------
# comparison runner
# ---------------------------

def encode_compare(
    input_mkv,
    width,
    height,
    target_mb_per_hour,
    out_prefix
):
    dur = duration_seconds(input_mkv)
    v_kbps = bitrate_for_target_size(dur, target_mb_per_hour)

    results = {}

    out_nv = f"{out_prefix}_nvenc.mkv"
    t = encode_nvenc(input_mkv, out_nv, width, height, v_kbps)
    results["nvenc"] = {
        "time_sec": t,
        "size_mb": file_mb(out_nv)
    }

    out_x = f"{out_prefix}_x264.mkv"
    t = encode_x264(input_mkv, out_x, width, height, v_kbps)
    results["x264"] = {
        "time_sec": t,
        "size_mb": file_mb(out_x)
    }

    return results
