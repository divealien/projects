"""
Analyse RMS and peak levels for all wav files in this folder.
Uses ffmpeg's volumedetect filter.
"""

import subprocess
import re
from pathlib import Path

FOLDER = Path(__file__).parent


def analyse(filepath: Path) -> dict:
    """Run volumedetect filter and parse results."""
    cmd = [
        "ffmpeg", "-i", str(filepath),
        "-af", "volumedetect",
        "-f", "null", "-"
    ]
    result = subprocess.run(cmd, capture_output=True, text=True)
    stderr = result.stderr

    info = {}
    for key, label in [
        ("mean_volume", "RMS"),
        ("max_volume", "Peak"),
    ]:
        m = re.search(rf"{key}:\s*([-\d.]+)\s*dB", stderr)
        if m:
            info[label] = float(m.group(1))
    return info


def main():
    files = sorted(
        f for f in FOLDER.iterdir()
        if f.is_file() and f.suffix.lower() == ".wav"
    )

    if not files:
        print("No wav files found.")
        return

    results = []
    for f in files:
        info = analyse(f)
        results.append((f.name, info.get("RMS"), info.get("Peak")))

    # Print report
    name_width = max(len(r[0]) for r in results)
    print(f"{'File':<{name_width}}  {'RMS (dB)':>10}  {'Peak (dB)':>10}")
    print("-" * (name_width + 24))

    for name, rms, peak in results:
        rms_str = f"{rms:+.1f}" if rms is not None else "N/A"
        peak_str = f"{peak:+.1f}" if peak is not None else "N/A"
        print(f"{name:<{name_width}}  {rms_str:>10}  {peak_str:>10}")

    # Summary
    rms_vals = [r[1] for r in results if r[1] is not None]
    peak_vals = [r[2] for r in results if r[2] is not None]
    if rms_vals:
        avg_rms = sum(rms_vals) / len(rms_vals)
        print(f"\n{'Average RMS:':<{name_width}}  {avg_rms:>+10.1f}")
        print(f"{'RMS range:':<{name_width}}  {min(rms_vals):+.1f} to {max(rms_vals):+.1f}")
        print(f"{'Peak range:':<{name_width}}  {min(peak_vals):+.1f} to {max(peak_vals):+.1f}")


if __name__ == "__main__":
    main()
