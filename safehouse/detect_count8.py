#!/usr/bin/env python3
"""
detect_count8.py — Detect a "count of 8" (8 evenly-spaced transients) at the
start of WAV files in a folder.

Works with spoken counts, clicks, claps, beats, or any rhythmic transient.

Usage:
    python detect_count8.py /path/to/wav/folder
    python detect_count8.py /path/to/wav/folder --max-search 20 --tolerance 0.4
    python detect_count8.py /path/to/wav/folder --csv results.csv
    python detect_count8.py /path/to/wav/folder --verbose

# Export results to CSV
python detect_count8.py ./wavs --csv results.csv

# Debug mode — prints raw onset times per file so you can see what it's picking up
python detect_count8.py ./wavs -v

# If it's detecting too many false positives (noisy files), fix delta high
python detect_count8.py ./wavs --delta 0.35

# If counts are slow (e.g. spoken "one... two... three..."), lower min-bpm
python detect_count8.py ./wavs --min-bpm 20

# Tighter timing requirement (stricter matching)
python detect_count8.py ./wavs --tolerance 0.3

"""

import argparse
import csv
import sys
from pathlib import Path

import librosa
import numpy as np


def _find_best_group(
    onset_times: np.ndarray,
    min_interval: float,
    max_interval: float,
    tolerance: float,
    max_start_positions: int,
    verbose: bool,
    delta_label: str = "",
) -> tuple[dict | None, float]:
    """
    Given a list of onset times, try all groups of 8 consecutive onsets and
    return the best matching group (or None) plus its score.
    Uses median interval to be robust against a single outlier beat.
    """
    best_result = None
    best_score = 0.0

    max_start = min(max_start_positions, len(onset_times) - 7)
    for i in range(max_start):
        group = onset_times[i : i + 8]
        intervals = np.diff(group)
        # Use median — more robust than mean when one interval is an outlier
        median_iv = float(np.median(intervals))

        # Check tempo range
        if median_iv < min_interval or median_iv > max_interval:
            if verbose:
                print(
                    f"  [{delta_label}] Group @{i}: median_iv={median_iv:.3f}s "
                    f"out of BPM range"
                )
            continue

        # Check regularity
        deviations = np.abs(intervals - median_iv) / median_iv
        max_dev = float(np.max(deviations))
        mean_dev = float(np.mean(deviations))

        if max_dev > tolerance:
            if verbose:
                print(
                    f"  [{delta_label}] Group @{i}: max_dev={max_dev:.2%} exceeds "
                    f"tolerance ({tolerance:.0%})"
                )
            continue

        # Confidence: lower mean deviation → higher confidence
        confidence = max(0.0, 1.0 - mean_dev * 3)

        # Prefer groups starting earlier
        positional_bonus = max(0.5, 1.0 - (i * 0.03))
        score = confidence * positional_bonus

        if verbose:
            bpm = 60.0 / median_iv
            print(
                f"  [{delta_label}] Group @{i}: BPM={bpm:.1f}, "
                f"max_dev={max_dev:.1%}, confidence={confidence:.2f}, score={score:.2f}"
            )

        if score > best_score:
            best_score = score
            count_end = group[-1] + median_iv  # one beat after last onset
            best_result = {
                "found": True,
                "onset_times": group.tolist(),
                "end_time": round(float(count_end), 3),
                "bpm": round(60.0 / median_iv, 1),
                "mean_interval": round(median_iv, 4),
                "confidence": round(confidence, 3),
            }

    return best_result, best_score


def _merge_close_onsets(onset_times: np.ndarray, min_gap_s: float) -> np.ndarray:
    """
    Greedy deduplication: keep the first onset in each cluster, discard any
    subsequent onset within min_gap_s of the last kept onset.
    This collapses double/triple triggers from a single spoken syllable.
    """
    if len(onset_times) < 2:
        return onset_times
    merged = [float(onset_times[0])]
    for t in onset_times[1:]:
        if t - merged[-1] >= min_gap_s:
            merged.append(float(t))
    return np.array(merged)


def detect_count_of_8(
    filepath: str | Path,
    max_search_seconds: float = 30.0,
    tolerance: float = 0.5,
    onset_delta: float | None = None,
    min_gap_ms: float = 120.0,
    min_bpm: float = 40.0,
    max_bpm: float = 220.0,
    max_start_positions: int = 25,
    verbose: bool = False,
) -> dict | None:
    """
    Detect 8 roughly evenly-spaced onsets near the start of an audio file.

    Parameters
    ----------
    filepath : path to a WAV (or any format librosa supports)
    max_search_seconds : how far into the file to look
    tolerance : max fractional deviation of any interval from the median
                (0.4 = 40 %)
    onset_delta : librosa onset sensitivity — raise to ignore weak transients.
                  If None (default), automatically tries several values and
                  returns the best result.
    min_gap_ms : minimum ms between onsets used for onset_detect wait parameter
    min_bpm : slowest acceptable count tempo
    max_bpm : fastest acceptable count tempo
    max_start_positions : how many onset starting positions to try (default 25)
    verbose : print debug info

    Returns
    -------
    dict with keys: found, onset_times, end_time, bpm, mean_interval, confidence
    or None if no count-of-8 detected.
    """
    y, sr = librosa.load(filepath, sr=None, mono=True)
    search_samples = int(max_search_seconds * sr)
    y_search = y[:search_samples]

    hop = 512
    min_wait = max(1, int(sr * (min_gap_ms / 1000.0) / hop))
    min_interval = 60.0 / max_bpm
    max_interval = 60.0 / min_bpm

    # Compute onset envelope once — shared across all delta passes
    onset_env = librosa.onset.onset_strength(y=y_search, sr=sr, hop_length=hop)

    # Multi-pass: vary onset sensitivity
    if onset_delta is not None:
        delta_values = [onset_delta]
    else:
        delta_values = [0.07, 0.1, 0.15, 0.2, 0.3]

    # Post-detection merge gaps: collapses sub-syllable double/triple triggers.
    # Range covers fast beats (150ms) through slow spoken counts (~550ms per word).
    merge_gaps_ms = [150, 250, 350, 450, 550]

    best_result = None
    best_score = 0.0

    for delta in delta_values:
        onset_frames = librosa.onset.onset_detect(
            onset_envelope=onset_env,
            sr=sr,
            hop_length=hop,
            delta=delta,
            wait=min_wait,
            backtrack=False,
            units="frames",
        )
        raw_times = librosa.frames_to_time(onset_frames, sr=sr, hop_length=hop)

        for merge_ms in merge_gaps_ms:
            onset_times = _merge_close_onsets(raw_times, merge_ms / 1000.0)

            if verbose:
                print(
                    f"  delta={delta}, merge={merge_ms}ms: {len(onset_times)} onsets — "
                    f"{np.round(onset_times[:15], 3).tolist()}"
                )

            if len(onset_times) < 8:
                continue

            result, score = _find_best_group(
                onset_times,
                min_interval=min_interval,
                max_interval=max_interval,
                tolerance=tolerance,
                max_start_positions=max_start_positions,
                verbose=verbose,
                delta_label=f"δ={delta},gap={merge_ms}ms",
            )

            if score > best_score:
                best_score = score
                best_result = result

    return best_result


def format_time(seconds: float) -> str:
    """Format seconds as M:SS.mmm"""
    m, s = divmod(seconds, 60)
    return f"{int(m)}:{s:06.3f}"


def main():
    parser = argparse.ArgumentParser(
        description="Detect a 'count of 8' at the start of WAV files in a folder."
    )
    parser.add_argument("folder", help="Path to folder containing WAV files")
    parser.add_argument(
        "--max-search",
        type=float,
        default=30.0,
        help="Max seconds into each file to search (default: 30)",
    )
    parser.add_argument(
        "--tolerance",
        type=float,
        default=0.5,
        help="Max fractional interval deviation, 0-1 (default: 0.5)",
    )
    parser.add_argument(
        "--delta",
        type=float,
        default=None,
        help=(
            "Fix onset detection sensitivity (0–1). "
            "Default: auto-tries 0.05, 0.07, 0.1, 0.15, 0.2, 0.35 and picks best."
        ),
    )
    parser.add_argument(
        "--max-start",
        type=int,
        default=25,
        help="How many onset starting positions to search (default: 25)",
    )
    parser.add_argument(
        "--min-gap",
        type=float,
        default=120.0,
        help="Minimum ms between onsets (default: 120)",
    )
    parser.add_argument(
        "--min-bpm",
        type=float,
        default=40.0,
        help="Slowest acceptable count tempo (default: 40)",
    )
    parser.add_argument(
        "--max-bpm",
        type=float,
        default=220.0,
        help="Fastest acceptable count tempo (default: 220)",
    )
    parser.add_argument(
        "--csv",
        type=str,
        default=None,
        help="Write results to a CSV file",
    )
    parser.add_argument(
        "--verbose", "-v", action="store_true", help="Print debug info per file"
    )

    args = parser.parse_args()
    folder = Path(args.folder)

    if not folder.is_dir():
        print(f"Error: '{folder}' is not a directory", file=sys.stderr)
        sys.exit(1)

    # Collect WAV files (case-insensitive)
    wav_files = sorted(
        p for p in folder.iterdir() if p.suffix.lower() in (".wav", ".flac", ".mp3", ".ogg")
    )

    if not wav_files:
        print(f"No audio files found in '{folder}'")
        sys.exit(0)

    print(f"Scanning {len(wav_files)} audio file(s) in: {folder}\n")
    print(f"{'File':<45} {'Count?':<8} {'Ends @':<12} {'BPM':<8} {'Confidence':<10}")
    print("-" * 85)

    results = []

    for path in wav_files:
        try:
            result = detect_count_of_8(
                path,
                max_search_seconds=args.max_search,
                tolerance=args.tolerance,
                onset_delta=args.delta,
                min_gap_ms=args.min_gap,
                min_bpm=args.min_bpm,
                max_bpm=args.max_bpm,
                max_start_positions=args.max_start,
                verbose=args.verbose,
            )
        except Exception as e:
            print(f"{path.name:<45} {'ERROR':<8} {str(e)}")
            results.append(
                {"file": path.name, "found": False, "end_time": "", "bpm": "", "confidence": "", "error": str(e)}
            )
            continue

        if result:
            end_str = format_time(result["end_time"])
            print(
                f"{path.name:<45} {'YES':<8} {end_str:<12} "
                f"{result['bpm']:<8} {result['confidence']:.0%}"
            )
            results.append(
                {
                    "file": path.name,
                    "found": True,
                    "end_time": result["end_time"],
                    "end_time_formatted": end_str,
                    "bpm": result["bpm"],
                    "mean_interval_s": result["mean_interval"],
                    "confidence": result["confidence"],
                    "onset_times": ";".join(f"{t:.3f}" for t in result["onset_times"]),
                    "error": "",
                }
            )
        else:
            print(f"{path.name:<45} {'NO':<8}")
            results.append(
                {"file": path.name, "found": False, "end_time": "", "bpm": "", "confidence": "", "error": ""}
            )

    # Summary
    found_count = sum(1 for r in results if r.get("found"))
    print(f"\n{'='*85}")
    print(f"Results: {found_count}/{len(results)} files have a count-of-8")

    # CSV export
    if args.csv:
        csv_path = Path(args.csv)
        fieldnames = [
            "file", "found", "end_time", "end_time_formatted",
            "bpm", "mean_interval_s", "confidence", "onset_times", "error",
        ]
        with open(csv_path, "w", newline="") as f:
            writer = csv.DictWriter(f, fieldnames=fieldnames, extrasaction="ignore")
            writer.writeheader()
            writer.writerows(results)
        print(f"CSV written to: {csv_path}")


if __name__ == "__main__":
    main()
