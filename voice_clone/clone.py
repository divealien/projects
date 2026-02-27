"""
Voice cloning via F5-TTS.

Synthesizes speech from text using a reference WAV to clone the speaker's voice.
Requires CUDA — will assert and exit if GPU is not available.

Usage:
    python clone.py --ref voices/speaker.wav --text "Hello world" --out output/result.wav
    python clone.py --ref voices/speaker.wav --ref-text "Known transcript." --text "Hello world" --out output/result.wav
"""

import argparse
import os
import re
import tempfile

# Must be set before any CUDA allocations
os.environ.setdefault("PYTORCH_ALLOC_CONF", "expandable_segments:True")

import nltk
import numpy as np
import soundfile as sf
import torch
from f5_tts.api import F5TTS
from pydub import AudioSegment

from config import DEFAULT_LANGUAGE, OUTPUT_DIR, OUTPUT_SAMPLE_RATE, SAMPLE_RATE

# Ensure NLTK sentence tokenizer data is available
try:
    nltk.data.find("tokenizers/punkt_tab")
except LookupError:
    nltk.download("punkt_tab", quiet=True)

_LONG_TEXT_THRESHOLD = 500  # characters; split into sentences above this

# Symbols whose spoken form is much longer than their character count.
# F5-TTS splits ref/gen audio by character ratio, so unexpanded symbols
# shift the crop point and corrupt the output.
_SYMBOL_EXPANSIONS = [
    (re.compile(r"(\d+)\s*%"), r"\1 percent"),
    (re.compile(r"\$\s*(\d[\d,.]*)"), r"\1 dollars"),
    (re.compile(r"&"), "and"),
    (re.compile(r"@"), "at"),
]


def _normalize_ref_text(text: str) -> str:
    """Expand symbols so character count better matches spoken duration."""
    for pattern, replacement in _SYMBOL_EXPANSIONS:
        text = pattern.sub(replacement, text)
    return text.strip()


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


def _infer_single(tts: F5TTS, ref_file: str, ref_text: str, gen_text: str) -> np.ndarray:
    wav, sr, _ = tts.infer(
        ref_file=ref_file,
        ref_text=ref_text,
        gen_text=gen_text,
        speed=1.0,
    )
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
) -> str:
    """
    Clone voice from reference_wav and synthesize text.

    Args:
        text:          Text to synthesize.
        reference_wav: Path to the cleaned reference WAV (6–30s, 24 kHz mono).
        output_path:   Destination path for the generated WAV.
        ref_text:      Transcript of reference_wav. Empty string triggers auto-transcription.
        language:      Language code (e.g. "en", "fr"). Passed to F5-TTS.

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
    print(f"Normalized ref_text: {ref_text!r}")

    if len(text) > _LONG_TEXT_THRESHOLD:
        print(f"Long text ({len(text)} chars) — splitting into sentences.")
        sentences = nltk.sent_tokenize(text, language=language if language != "en" else "english")
        wavs = []
        for i, sentence in enumerate(sentences):
            print(f"  Synthesizing sentence {i + 1}/{len(sentences)}: {sentence[:60]}...")
            wav = _infer_single(tts, reference_wav, ref_text, sentence)
            wavs.append(wav)
            torch.cuda.empty_cache()
        combined = _concat_wavs(wavs)
    else:
        combined = _infer_single(tts, reference_wav, ref_text, text)

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
    parser.add_argument("--text", required=True, help="Text to synthesize")
    parser.add_argument(
        "--ref-text",
        default="",
        help="Transcript of the reference WAV (improves quality; auto-transcribed if omitted)",
    )
    parser.add_argument(
        "--out",
        default=os.path.join(OUTPUT_DIR, "result.wav"),
        help="Output WAV path (default: output/result.wav)",
    )
    parser.add_argument("--language", default=DEFAULT_LANGUAGE, help="Language code (default: en)")
    args = parser.parse_args()

    synthesize(
        text=args.text,
        reference_wav=args.ref,
        output_path=args.out,
        ref_text=args.ref_text,
        language=args.language,
    )


if __name__ == "__main__":
    main()
