#!/usr/bin/env python3
"""merge_subs.py — Merge MKV subtitle track with external SRT, MKV takes priority."""

import argparse
import os
import re
import shutil
import subprocess
import sys
import tempfile
from dataclasses import dataclass
from datetime import timedelta


@dataclass
class SubEntry:
    index: int
    start: timedelta
    end: timedelta
    text: str
    source: str  # 'mkv' or 'ext'


def _td_to_srt(td: timedelta) -> str:
    total_ms = int(td.total_seconds() * 1000)
    ms = total_ms % 1000
    total_s = total_ms // 1000
    s = total_s % 60
    m = (total_s // 60) % 60
    h = total_s // 3600
    return f"{h:02d}:{m:02d}:{s:02d},{ms:03d}"


def _parse_timestamp(ts: str) -> timedelta:
    m = re.match(r"(\d+):(\d+):(\d+)[,.](\d+)", ts.strip())
    if not m:
        raise ValueError(f"Cannot parse timestamp: {ts!r}")
    h, mn, s, ms = int(m.group(1)), int(m.group(2)), int(m.group(3)), int(m.group(4))
    return timedelta(hours=h, minutes=mn, seconds=s, milliseconds=ms)


def parse_srt(path: str) -> list:
    with open(path, "r", encoding="utf-8-sig", errors="replace") as f:
        content = f.read()

    content = content.replace("\r\n", "\n").replace("\r", "\n")
    blocks = re.split(r"\n{2,}", content.strip())

    entries = []
    for block in blocks:
        lines = block.strip().splitlines()
        if len(lines) < 3:
            continue
        try:
            idx = int(lines[0].strip())
        except ValueError:
            print(f"Warning: skipping malformed entry (bad index): {lines[0]!r}", file=sys.stderr)
            continue

        ts_line = lines[1].strip()
        ts_match = re.match(r"(\S+)\s*-->\s*(\S+)", ts_line)
        if not ts_match:
            print(f"Warning: skipping malformed entry (bad timestamp): {ts_line!r}", file=sys.stderr)
            continue

        try:
            start = _parse_timestamp(ts_match.group(1))
            end = _parse_timestamp(ts_match.group(2))
        except ValueError as e:
            print(f"Warning: skipping malformed entry ({e})", file=sys.stderr)
            continue

        text = "\n".join(lines[2:])
        if not text.strip():
            continue

        entries.append(SubEntry(index=idx, start=start, end=end, text=text, source=""))

    return entries


def write_srt(entries: list, path: str) -> None:
    with open(path, "w", encoding="utf-8") as f:
        for i, entry in enumerate(entries, 1):
            f.write(f"{i}\n")
            f.write(f"{_td_to_srt(entry.start)} --> {_td_to_srt(entry.end)}\n")
            f.write(f"{entry.text}\n")
            f.write("\n")


def extract_srt_from_mkv(mkv_path: str, track_index: int, out_path: str) -> None:
    cmd = [
        "ffmpeg", "-y", "-i", mkv_path,
        "-map", f"0:s:{track_index}",
        "-c:s", "srt",
        out_path,
    ]
    result = subprocess.run(cmd, capture_output=True)
    if result.returncode != 0:
        stderr = result.stderr.decode("utf-8", errors="replace")
        raise RuntimeError(f"ffmpeg failed:\n{stderr}")


def list_tracks(mkv_path: str) -> None:
    cmd = [
        "ffprobe", "-v", "error",
        "-select_streams", "s",
        "-show_entries", "stream=index,codec_name:stream_tags=language",
        "-of", "csv=p=0",
        mkv_path,
    ]
    result = subprocess.run(cmd, capture_output=True)
    if result.returncode != 0:
        stderr = result.stderr.decode("utf-8", errors="replace")
        print(f"ffprobe error:\n{stderr}", file=sys.stderr)
        sys.exit(1)

    output = result.stdout.decode("utf-8", errors="replace").strip()
    if not output:
        print("No subtitle tracks found.")
        sys.exit(0)

    for i, line in enumerate(output.splitlines()):
        parts = [p.strip() for p in line.split(",")]
        codec = parts[1] if len(parts) > 1 else "unknown"
        lang = parts[2] if len(parts) > 2 else "und"
        print(f"Track {i}: {codec}  [{lang}]")


def _get_track_info(mkv_path: str, track_index: int):
    """Returns (codec_name, language) for the given 0-based subtitle track index."""
    cmd = [
        "ffprobe", "-v", "error",
        "-select_streams", "s",
        "-show_entries", "stream=index,codec_name:stream_tags=language",
        "-of", "csv=p=0",
        mkv_path,
    ]
    result = subprocess.run(cmd, capture_output=True)
    if result.returncode != 0:
        return None, None

    output = result.stdout.decode("utf-8", errors="replace").strip()
    lines = output.splitlines()
    if track_index >= len(lines):
        return None, None

    parts = [p.strip() for p in lines[track_index].split(",")]
    codec = parts[1] if len(parts) > 1 else "unknown"
    lang = parts[2] if len(parts) > 2 else "und"
    return codec, lang


BITMAP_CODECS = {"hdmv_pgs_subtitle", "dvd_subtitle", "dvbsub", "xsub", "dvb_subtitle"}


def has_overlap(entry: SubEntry, mkv_subs: list) -> bool:
    for mkv in mkv_subs:
        if entry.start < mkv.end and entry.end > mkv.start:
            return True
    return False


def merge(mkv_subs: list, ext_subs: list) -> list:
    kept = [e for e in ext_subs if not has_overlap(e, mkv_subs)]
    combined = list(mkv_subs) + kept
    combined.sort(key=lambda e: (e.start, 0 if e.source == "mkv" else 1))
    for i, entry in enumerate(combined, 1):
        entry.index = i
    return combined, len(ext_subs) - len(kept), len(kept)


def main():
    parser = argparse.ArgumentParser(
        description="Merge MKV subtitle track with external SRT. MKV subs take priority."
    )
    parser.add_argument("mkv", help="Path to source MKV file")
    parser.add_argument("external_srt", help="Path to external SRT file")
    parser.add_argument("-o", "--output", help="Output SRT path (default: <mkv_basename>_merged.srt)")
    parser.add_argument("-t", "--track", type=int, default=0, help="0-based subtitle track index (default: 0)")
    parser.add_argument("--list-tracks", action="store_true", help="List subtitle tracks and exit")
    parser.add_argument("--keep-tmp", action="store_true", help="Keep temporary extracted SRT file")
    parser.add_argument("--force", action="store_true", help="Overwrite output without prompting")
    parser.add_argument("-v", "--verbose", action="store_true", help="Print merge statistics")
    args = parser.parse_args()

    # Check tools on PATH
    for tool in ("ffmpeg", "ffprobe"):
        if not shutil.which(tool):
            print(f"Error: '{tool}' not found on PATH. Install ffmpeg and ensure it is accessible.", file=sys.stderr)
            sys.exit(1)

    # --list-tracks
    if args.list_tracks:
        if not os.path.isfile(args.mkv):
            print(f"Error: MKV file not found: {args.mkv}", file=sys.stderr)
            sys.exit(1)
        list_tracks(args.mkv)
        sys.exit(0)

    # Validate inputs
    if not os.path.isfile(args.mkv):
        print(f"Error: MKV file not found: {args.mkv}", file=sys.stderr)
        sys.exit(1)
    if not os.path.isfile(args.external_srt):
        print(f"Error: External SRT not found: {args.external_srt}", file=sys.stderr)
        sys.exit(1)

    # Check track codec
    codec, lang = _get_track_info(args.mkv, args.track)
    if codec is None:
        # Could not determine — check if track index is valid
        cmd = ["ffprobe", "-v", "error", "-select_streams", "s",
               "-show_entries", "stream=index", "-of", "csv=p=0", args.mkv]
        result = subprocess.run(cmd, capture_output=True)
        output = result.stdout.decode("utf-8", errors="replace").strip()
        count = len(output.splitlines()) if output else 0
        if count == 0:
            print("Error: No subtitle tracks found in MKV.", file=sys.stderr)
            sys.exit(1)
        print(f"Error: Track index {args.track} out of range. MKV has {count} subtitle track(s) (0–{count-1}).", file=sys.stderr)
        sys.exit(1)

    if codec in BITMAP_CODECS:
        print(
            f"Error: Track {args.track} uses codec '{codec}', which is a bitmap subtitle format "
            f"and cannot be converted to text/SRT.",
            file=sys.stderr,
        )
        sys.exit(1)

    # Determine output path
    if args.output:
        out_path = args.output
    else:
        base = os.path.splitext(os.path.basename(args.mkv))[0]
        out_path = f"{base}_merged.srt"

    # Check output exists
    if os.path.exists(out_path) and not args.force:
        answer = input(f"Output file exists. Overwrite? [y/N]: ").strip().lower()
        if answer != "y":
            print("Aborted.")
            sys.exit(0)

    # Extract subtitle track
    tmp_path = tempfile.mktemp(suffix=".srt")
    try:
        try:
            extract_srt_from_mkv(args.mkv, args.track, tmp_path)
        except RuntimeError as e:
            print(f"Error: {e}", file=sys.stderr)
            sys.exit(1)

        mkv_subs = parse_srt(tmp_path) if os.path.isfile(tmp_path) else []
        for e in mkv_subs:
            e.source = "mkv"

        if not mkv_subs:
            print("Warning: extracted track is empty — proceeding with external SRT only.", file=sys.stderr)

        ext_subs = parse_srt(args.external_srt)
        for e in ext_subs:
            e.source = "ext"

        if not ext_subs:
            print("Warning: external SRT is empty — proceeding with MKV subs only.", file=sys.stderr)

        merged, dropped, kept = merge(mkv_subs, ext_subs)
        write_srt(merged, out_path)

        if args.verbose:
            print(f"Extracted MKV track:     {len(mkv_subs)} entries  (track {args.track}, language: {lang})")
            print(f"External SRT:            {len(ext_subs)} entries")
            print(f"  Kept (no overlap):     {kept}")
            print(f"  Dropped (overlap):     {dropped}")
            print(f"MKV entries:             {len(mkv_subs)}")
            print(f"Total merged output:     {len(merged)} entries")
            print(f"Written to:              {out_path}")

    finally:
        if not args.keep_tmp and os.path.isfile(tmp_path):
            os.remove(tmp_path)


if __name__ == "__main__":
    main()
