import subprocess
import json
import os
import math
import argparse

def run(cmd):
    result = subprocess.run(
        cmd,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True
    )
    if result.returncode != 0:
        raise RuntimeError(result.stderr)
    return result.stdout

def get_chapters(filename):
    cmd = [
        "ffprobe",
        "-loglevel", "error",
        "-print_format", "json",
        "-show_chapters",
        filename
    ]
    out = run(cmd)
    data = json.loads(out)
    chapters = []
    for ch in data.get("chapters", []):
        title = ch.get("tags", {}).get("title", "")
        if title.lower() != "credits":
            chapters.append((
                float(ch["start_time"]),
                float(ch["end_time"])
            ))
    return chapters

def group_chapters(chapters, target, max_duration):
    groups = []
    current = []
    current_len = 0.0

    n = len(chapters)
    for i, (start, end) in enumerate(chapters):
        length = end - start
        
        if current:
            # If adding this chapter exceeds the maximum allowed duration, cut now.
            if current_len + length > max_duration:
                groups.append(current)
                current = []
                current_len = 0.0
            # If we've already reached the target duration, cut now to keep it near 30m.
            elif current_len >= target:
                # Check if we can just finish the file in this group without exceeding max
                remaining_duration = 0.0
                for j in range(i, n):
                    s, e = chapters[j]
                    remaining_duration += (e - s)

                if current_len + remaining_duration <= max_duration:
                    pass  # Don't cut, we can fit everything!
                else:
                    groups.append(current)
                    current = []
                    current_len = 0.0
        
        current.append((start, end))
        current_len += length

    if current:
        groups.append(current)

    return groups

def split_file(filename, groups, xvid_mode=False):
    base_name = os.path.splitext(os.path.basename(filename))[0]
    
    for i, group in enumerate(groups, start=1):
        start_time = group[0][0]
        end_time = group[-1][1]
        
        if xvid_mode:
            out_name = f"{base_name}_part{i:02d}.avi"
            cmd = [
                "ffmpeg",
                "-y",
                "-i", filename,
                "-ss", f"{start_time}",
                "-to", f"{end_time}",
                "-vf", "scale=720:480:force_original_aspect_ratio=decrease",
                "-c:v", "libxvid",
                "-profile:v", "0",
                "-b:v", "1800k",
                "-pix_fmt", "yuv420p",
                "-c:a", "libmp3lame",
                "-b:a", "128k",
                out_name
            ]
        else:
            out_name = f"{base_name}_part{i:02d}.mp4"
            cmd = [
                "ffmpeg",
                "-y",
                "-i", filename,
                "-ss", f"{start_time}",
                "-to", f"{end_time}",
                "-c", "copy",
                out_name
            ]

        duration_mins = (end_time - start_time) / 60
        print(f"Creating {out_name}: {start_time:.2f} -> {end_time:.2f} ({duration_mins:.2f} mins)")
        run(cmd)

def main():
    parser = argparse.ArgumentParser(description="Split MP4 file by chapters with flexible duration.")
    parser.add_argument("filenames", nargs="+", help="Paths to the MP4 files to split")
    parser.add_argument("-t", "--target", type=int, default=30, help="Target duration in minutes (default: 30)")
    parser.add_argument("-m", "--max", type=int, default=40, help="Maximum allowed duration in minutes (default: 40)")
    parser.add_argument("--xvid", action="store_true", help="Re-encode to Xvid (AVI) for old players")
    args = parser.parse_args()

    target_seconds = args.target * 60
    max_seconds = args.max * 60

    for filename in args.filenames:
        if not os.path.exists(filename):
            raise FileNotFoundError(f"File not found: {filename}")

        chapters = get_chapters(filename)
        if not chapters:
            raise RuntimeError("No chapters found")

        groups = group_chapters(chapters, target_seconds, max_seconds)
        split_file(filename, groups, args.xvid)

if __name__ == "__main__":
    main()
