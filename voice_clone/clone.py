"""
Voice cloning via F5-TTS.

Synthesizes speech from text using a reference WAV to clone the speaker's voice.
Requires CUDA — will assert and exit if GPU is not available.

Usage:
    python clone.py --ref voices/speaker.wav --text "Hello world" --out output/result.wav
    python clone.py --ref voices/speaker.wav --text-file input.txt --out output/result.wav
    python clone.py --ref voices/speaker.wav --ref-text "Known transcript." --text "Hello world" --out output/result.wav
"""

import argparse
import os
import re

# Must be set before any CUDA allocations
os.environ.setdefault("PYTORCH_ALLOC_CONF", "expandable_segments:True")

import nltk
import numpy as np
import soundfile as sf
import torch
from f5_tts.api import F5TTS
from f5_tts.infer.utils_infer import preprocess_ref_audio_text as _f5_preprocess
from pydub import AudioSegment

from config import DEFAULT_LANGUAGE, OUTPUT_DIR, OUTPUT_SAMPLE_RATE, SAMPLE_RATE

# Ensure NLTK sentence tokenizer data is available
try:
    nltk.data.find("tokenizers/punkt_tab")
except LookupError:
    nltk.download("punkt_tab", quiet=True)

# F5-TTS internal mel spectrogram constants (must match the model config)
_HOP_LENGTH = 256
_GEN_PADDING_FRAMES = 50  # ~0.53 s safety buffer after estimated gen content

# Symbols whose spoken form is much longer than their character count.
# F5-TTS crops ref/gen audio by character ratio, so unexpanded symbols
# shift the crop point and corrupt the output.
_SYMBOL_EXPANSIONS = [
    (re.compile(r"(\d+)\s*%"), r"\1 percent"),
    (re.compile(r"\$\s*(\d[\d,.]*)"), r"\1 dollars"),
    (re.compile(r"&"), "and"),
    (re.compile(r"@"), "at"),
]

# Cache of ref_file → (processed_wav_path, processed_duration_sec)
_ref_proc_cache: dict[str, tuple[str, float]] = {}


def _normalize_ref_text(text: str) -> str:
    """Expand symbols so character count better matches spoken duration."""
    for pattern, replacement in _SYMBOL_EXPANSIONS:
        text = pattern.sub(replacement, text)
    return text.strip()


def _preprocess_ref(ref_file: str, ref_text: str) -> tuple[str, float]:
    """Run F5-TTS preprocessing once and cache (processed_path, duration_sec)."""
    if ref_file not in _ref_proc_cache:
        path, _ = _f5_preprocess(ref_file, ref_text, show_info=lambda _: None)
        _ref_proc_cache[ref_file] = (path, sf.info(path).duration)
    return _ref_proc_cache[ref_file]


def _fit_ref_text(ref_text: str, ref_file: str) -> str:
    """
    Truncate ref_text so it matches the F5-TTS processed audio duration.

    F5-TTS clips reference audio to ≤12 s internally. If ref_text covers more
    speech than the clipped audio, the mel-frame crop overflows, causing:
      - ref-text echo at the start of the generated output
      - gen_text crammed into too few frames (sounds sped up)
    """
    orig_sec = sf.info(ref_file).duration
    _, proc_sec = _preprocess_ref(ref_file, ref_text)
    if proc_sec >= orig_sec:
        return ref_text
    ratio = proc_sec / orig_sec
    n = max(1, int(len(ref_text) * ratio))
    truncated = ref_text[:n]
    last_space = truncated.rfind(" ")
    return truncated[:last_space] if last_space > 0 else truncated


def _get_tts() -> F5TTS:
    assert torch.cuda.is_available(), (
        "CUDA is not available. This project requires a CUDA-capable GPU. "
        "Run `nvidia-smi` in WSL to verify GPU access."
    )
    print(f"GPU memory before model load: {torch.cuda.memory_allocated() / 1e6:.1f} MB")
    tts = F5TTS(device="cuda")
    mem_after = torch.cuda.memory_allocated() / 1e6
    assert mem_after > 0, "Model does not appear to be on CUDA — CPU fallback is not allowed."
    print(f"GPU memory after model load:  {mem_after:.1f} MB")
    return tts


def _infer_single(tts: F5TTS, ref_file: str, ref_text: str, gen_text: str, speed: float = 1.0) -> np.ndarray:
    # Truncate ref_text to match what's actually in F5-TTS's clipped reference,
    # then derive frames_per_char from that consistent (audio, text) pair.
    adapted_ref = _fit_ref_text(ref_text, ref_file)
    proc_path, proc_sec = _preprocess_ref(ref_file, ref_text)
    ref_frames = sf.info(proc_path).frames // _HOP_LENGTH
    frames_per_char = ref_frames / max(len(adapted_ref.encode("utf-8")), 1)
    # Divide by speed: slower speech (speed < 1) needs proportionally more frames.
    gen_frames = int(frames_per_char * len(gen_text.strip().encode("utf-8")) / speed) + _GEN_PADDING_FRAMES
    fix_duration = (ref_frames + gen_frames) * _HOP_LENGTH / SAMPLE_RATE
    print(
        f"  ref_text: {len(ref_text)} → {len(adapted_ref)} chars  "
        f"ref={proc_sec:.2f}s  gen_est={gen_frames * _HOP_LENGTH / SAMPLE_RATE:.2f}s  "
        f"fix_duration={fix_duration:.2f}s  speed={speed}"
    )

    wav, sr, _ = tts.infer(
        ref_file=ref_file,
        ref_text=adapted_ref,
        gen_text=gen_text,
        speed=speed,
        fix_duration=fix_duration,
    )
    print(f"  → {len(wav)/sr:.3f}s at {sr} Hz")
    if sr != SAMPLE_RATE:
        import librosa
        wav = librosa.resample(wav, orig_sr=sr, target_sr=SAMPLE_RATE)
    return wav


def _concat_wavs(wavs: list[np.ndarray], sample_rate: int = SAMPLE_RATE) -> np.ndarray:
    """Concatenate a list of audio arrays with a short silence between each."""
    silence = np.zeros(int(sample_rate * 0.25))  # 250 ms pause between sentences
    parts = []
    for i, wav in enumerate(wavs):
        parts.append(wav)
        if i < len(wavs) - 1:
            parts.append(silence)
    return np.concatenate(parts)


def synthesize(
    text: str,
    reference_wav: str,
    output_path: str,
    ref_text: str = "",
    language: str = DEFAULT_LANGUAGE,
    speed: float = 1.0,
) -> str:
    """
    Clone voice from reference_wav and synthesize text.

    Args:
        text:          Text to synthesize.
        reference_wav: Path to the cleaned reference WAV (6–30s, 24 kHz mono).
        output_path:   Destination path for the generated WAV.
        ref_text:      Transcript of reference_wav. Empty string triggers auto-transcription.
        language:      Language code (e.g. "en", "fr"). Passed to F5-TTS.
        speed:         Speech rate multiplier (1.0 = normal, 0.8 = 20% slower, 1.2 = faster).

    Returns:
        Path to the written output WAV file.
    """
    os.makedirs(os.path.dirname(output_path) if os.path.dirname(output_path) else ".", exist_ok=True)

    tts = _get_tts()
    print(f"GPU memory before synthesis: {torch.cuda.memory_allocated() / 1e6:.1f} MB")

    if not ref_text:
        if hasattr(tts, "transcribe"):
            ref_text = tts.transcribe(reference_wav)
            print(f"Auto-transcribed ref_text: {ref_text!r}")
            print("  (use --ref-text to override for better accuracy)")
        else:
            print("Warning: ref_text is empty — F5-TTS will auto-transcribe internally.")
            print("  If output sounds wrong, re-run with --ref-text '<what the reference says>'.")

    ref_text = _normalize_ref_text(ref_text)
    print(f"ref_text: {ref_text!r}")

    # Normalise whitespace: collapse newlines/multiple spaces so sentence
    # tokenisation works correctly and F5-TTS doesn't see embedded newlines.
    text = " ".join(text.split())

    # Always split into sentences so each _infer_single call gets its own
    # fix_duration. F5-TTS also chunks gen_text internally; if we pass a long
    # text with a fix_duration sized for the whole thing, each internal chunk
    # gets that full duration and the model fills the excess with ref echo.
    nltk_lang = language if language != "en" else "english"
    sentences = [s for s in nltk.sent_tokenize(text, language=nltk_lang) if s.strip()]
    print(f"Synthesising {len(sentences)} sentence(s).")
    wavs = []
    for i, sentence in enumerate(sentences):
        print(f"  [{i + 1}/{len(sentences)}] {sentence[:80]}")
        wav = _infer_single(tts, reference_wav, ref_text, sentence, speed=speed)
        wavs.append(wav)
        torch.cuda.empty_cache()
    combined = _concat_wavs(wavs)

    print(f"GPU memory after synthesis:  {torch.cuda.memory_allocated() / 1e6:.1f} MB")

    if OUTPUT_SAMPLE_RATE != SAMPLE_RATE:
        import librosa
        combined = librosa.resample(combined, orig_sr=SAMPLE_RATE, target_sr=OUTPUT_SAMPLE_RATE)

    sf.write(output_path, combined, OUTPUT_SAMPLE_RATE, subtype="PCM_16")
    print(f"Output written: {output_path}")
    return output_path


def main():
    parser = argparse.ArgumentParser(description="Clone a voice and synthesize speech with F5-TTS.")
    parser.add_argument("--ref", required=True, help="Path to reference WAV (6–30s, preprocessed)")
    text_group = parser.add_mutually_exclusive_group(required=True)
    text_group.add_argument("--text", help="Text to synthesize")
    text_group.add_argument("--text-file", metavar="FILE", help="Path to text file to synthesize")
    ref_text_group = parser.add_mutually_exclusive_group()
    ref_text_group.add_argument(
        "--ref-text",
        default="",
        help="Transcript of the reference WAV (improves quality; auto-transcribed if omitted)",
    )
    ref_text_group.add_argument(
        "--ref-text-file",
        metavar="FILE",
        help="Path to a text file containing the reference WAV transcript",
    )
    parser.add_argument(
        "--out",
        default=os.path.join(OUTPUT_DIR, "result.wav"),
        help="Output WAV path (default: output/result.wav)",
    )
    parser.add_argument("--language", default=DEFAULT_LANGUAGE, help="Language code (default: en)")
    parser.add_argument("--speed", type=float, default=1.0, help="Speech rate (default: 1.0, slower: 0.8, faster: 1.2)")
    args = parser.parse_args()

    if args.text_file:
        with open(args.text_file, encoding="utf-8") as f:
            text = f.read().strip()
    else:
        text = args.text

    if args.ref_text_file:
        with open(args.ref_text_file, encoding="utf-8") as f:
            ref_text = f.read().strip()
    else:
        ref_text = args.ref_text

    synthesize(
        text=text,
        reference_wav=args.ref,
        output_path=args.out,
        ref_text=ref_text,
        language=args.language,
        speed=args.speed,
    )


if __name__ == "__main__":
    main()
