"""
Preprocess a reference audio file for voice cloning:
- Resample to 24000 Hz
- Trim silence
- Normalize audio levels
- Validate duration (warn if outside 6–30s)
- Export cleaned WAV

Supports WAV and MP3 input (MP3 requires ffmpeg).

Usage:
    python preprocess.py --input raw.wav --output voices/speaker_clean.wav
    python preprocess.py --input raw.mp3 --output voices/speaker_clean.wav
"""

import argparse
import sys
import warnings

import librosa
import numpy as np
import soundfile as sf

from config import REFERENCE_WAV_IDEAL_SEC, REFERENCE_WAV_MAX_SEC, REFERENCE_WAV_MIN_SEC, SAMPLE_RATE


def _extract_best_segment(audio: np.ndarray, sr: int, target_sec: float) -> tuple[np.ndarray, float]:
    """
    Extract the most speech-dense segment of target_sec duration from audio.

    Scores each candidate window by mean RMS × voiced fraction, where voiced
    frames are those above a -20 dB silence threshold. Uses O(n) cumulative
    sums for efficiency.

    Returns (segment, start_time_seconds).
    """
    target_samples = int(target_sec * sr)
    if len(audio) <= target_samples:
        return audio, 0.0

    hop_length = 512
    rms = librosa.feature.rms(y=audio, hop_length=hop_length)[0]
    target_frames = int(target_sec * sr / hop_length)
    n = len(rms) - target_frames + 1
    if n <= 0:
        return audio, 0.0

    # Silence threshold: -20 dB relative to peak
    silence_amp = 10 ** (-20 / 20) * float(np.max(np.abs(audio)))
    voiced = (rms > silence_amp).astype(np.float32)

    # O(n) sliding window via cumulative sums
    rms_cs = np.concatenate([[0.0], np.cumsum(rms)])
    voiced_cs = np.concatenate([[0.0], np.cumsum(voiced)])
    window_rms = (rms_cs[target_frames : target_frames + n] - rms_cs[:n]) / target_frames
    window_voiced_frac = (voiced_cs[target_frames : target_frames + n] - voiced_cs[:n]) / target_frames

    scores = window_rms * window_voiced_frac
    best_frame = int(np.argmax(scores))
    start_sample = best_frame * hop_length
    return audio[start_sample : start_sample + target_samples], start_sample / sr


def preprocess(input_path: str, output_path: str) -> str:
    """
    Load, clean, and export a reference audio file.
    Returns the output path.
    """
    print(f"Loading: {input_path}")
    audio, sr = librosa.load(input_path, sr=SAMPLE_RATE, mono=True)

    # Trim leading/trailing silence
    audio, _ = librosa.effects.trim(audio, top_db=20)

    duration = len(audio) / SAMPLE_RATE
    print(f"Duration after trim: {duration:.2f}s")

    if duration > REFERENCE_WAV_MAX_SEC:
        print(
            f"Audio is {duration:.1f}s — auto-extracting best {REFERENCE_WAV_IDEAL_SEC}s segment "
            f"(F5-TTS max total context is 30s; ideal reference is ≤12s)."
        )
        audio, start_t = _extract_best_segment(audio, SAMPLE_RATE, REFERENCE_WAV_IDEAL_SEC)
        duration = len(audio) / SAMPLE_RATE
        print(f"Extracted {duration:.2f}s segment starting at {start_t:.2f}s")

    # Normalize to peak amplitude
    peak = np.max(np.abs(audio))
    if peak > 0:
        audio = audio / peak * 0.95

    # Append 1s of silence — F5-TTS requires a clean boundary at the end of the
    # reference to correctly locate where reference ends and generation begins.
    trailing_silence = np.zeros(SAMPLE_RATE, dtype=audio.dtype)
    audio = np.concatenate([audio, trailing_silence])
    duration = len(audio) / SAMPLE_RATE
    print(f"Duration with trailing silence: {duration:.2f}s")

    if duration < REFERENCE_WAV_MIN_SEC:
        warnings.warn(
            f"Reference audio is only {duration:.1f}s — recommended minimum is {REFERENCE_WAV_MIN_SEC}s. "
            "Voice cloning quality may be poor.",
            stacklevel=2,
        )
    elif duration > REFERENCE_WAV_MAX_SEC:
        warnings.warn(
            f"Reference audio is {duration:.1f}s — recommended maximum is {REFERENCE_WAV_MAX_SEC}s. "
            "Consider trimming to the cleanest 10–15s segment.",
            stacklevel=2,
        )

    sf.write(output_path, audio, SAMPLE_RATE, subtype="PCM_16")
    print(f"Saved: {output_path}")
    return output_path


def main():
    parser = argparse.ArgumentParser(description="Preprocess a reference WAV for voice cloning.")
    parser.add_argument("--input", required=True, help="Path to input audio file (WAV or MP3)")
    parser.add_argument("--output", required=True, help="Path to output cleaned WAV file")
    args = parser.parse_args()

    preprocess(args.input, args.output)


if __name__ == "__main__":
    main()
