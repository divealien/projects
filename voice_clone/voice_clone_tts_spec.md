# Voice Clone TTS — Claude Code Project Spec

## Goal

Clone a speaker's voice from a WAV file and synthesize speech from text using that voice, running locally on an RTX 5070 Ti via WSL2/Ubuntu with conda + PyTorch.

---

## Recommended Stack

**Primary library: [F5-TTS](https://github.com/SWivid/F5-TTS)** — actively maintained, MIT licensed, excellent zero-shot voice cloning from a short WAV reference (~10 seconds). Uses flow-matching for natural prosody. Supports English and multilingual (v1: French, Italian, Hindi, Japanese, Russian, Spanish, Finnish, and more).

Fallback options: **OpenVoice v2** or **CosyVoice 2** for more multilingual control.

---

## Environment Spec

```yaml
# environment.yml
name: voiceclone
channels:
  - pytorch
  - nvidia
  - conda-forge
  - defaults
dependencies:
  - python=3.10
  - pytorch>=2.1
  - torchvision
  - torchaudio
  - pytorch-cuda=12.4   # RTX 5070 Ti requires CUDA 12.4+
  - ffmpeg
  - pip
  - pip:
    - f5-tts             # F5-TTS with flow matching
    - soundfile
    - librosa
    - numpy
    - scipy
    - pydub
    - fastapi             # optional: REST API wrapper
    - uvicorn             # optional
```

---

## Project Structure

```
voiceclone/
├── environment.yml
├── README.md
├── CLAUDE.md             # Claude Code instructions
├── clone.py              # Core voice clone + TTS logic
├── api.py                # Optional FastAPI REST wrapper
├── preprocess.py         # WAV preprocessing/validation
├── config.py             # Paths, defaults, constants
├── voices/               # Store reference WAVs
│   └── .gitkeep
├── output/               # Generated audio
│   └── .gitkeep
└── tests/
    └── test_clone.py
```

---

## Core Modules

### `config.py`

```python
MODEL_NAME = "F5-TTS"          # or "E2-TTS" as alternative
SAMPLE_RATE = 24000            # F5-TTS native sample rate
REFERENCE_WAV_MIN_SEC = 6
REFERENCE_WAV_MAX_SEC = 30
DEFAULT_LANGUAGE = "en"
VOICES_DIR = "voices"
OUTPUT_DIR = "output"
DEVICE = "cuda"  # force GPU
```

### `preprocess.py`

Responsibilities:
- Load WAV, validate sample rate (resample to 24000 Hz if needed)
- Trim silence using librosa
- Validate duration — warn if < 6s or > 30s
- Normalize audio levels
- Export cleaned WAV for use as reference

### `clone.py`

Responsibilities:
- Load F5-TTS model (cached after first download via HuggingFace, ~1.2GB)
- Accept: `reference_wav: str`, `ref_text: str`, `text: str`, `output_path: str`
- `ref_text` is the transcript of the reference WAV — if empty string, auto-transcribed via Whisper
- Run `tts.infer()` with reference wav and text
- Write output WAV via soundfile
- Log GPU memory usage before/after
- Assert model is on CUDA — never silently fall back to CPU

Key function signature:

```python
def synthesize(
    text: str,
    reference_wav: str,
    output_path: str,
    ref_text: str = "",    # transcript of reference_wav; auto-transcribed if empty
    language: str = "en"
) -> str:
    """
    Clone voice from reference_wav and synthesize text.
    Returns path to output WAV file.
    """
    ...
```

Core synthesis pattern:

```python
from f5_tts.api import F5TTS
import soundfile as sf

tts = F5TTS(device="cuda")

wav, sr, _ = tts.infer(
    ref_file=reference_wav,
    ref_text=ref_text,   # empty string triggers auto-transcription
    gen_text=text,
    speed=1.0,
)
sf.write(output_path, wav, sr)
```

### `api.py` (optional REST layer)

FastAPI endpoints:
- `POST /clone` — accepts `reference_wav` filename + `text` + optional `ref_text`, returns audio file
- `GET /voices` — lists available reference WAVs in `voices/`
- `POST /upload` — upload a new reference WAV

---

## CLI Interface

```bash
# Basic usage (auto-transcribes reference audio)
python clone.py --ref voices/speaker.wav --text "Hello world" --out output/result.wav

# With known reference transcript (faster, more accurate)
python clone.py --ref voices/speaker.wav --ref-text "This is what I said." --text "Hello world" --out output/result.wav

# Preprocess a raw WAV first
python preprocess.py --input raw.wav --output voices/speaker_clean.wav
```

---

## GPU / Performance Notes

- F5-TTS on RTX 5070 Ti should generate at realtime speed or faster
- First run downloads ~1.2GB model weights, cached in `~/.cache/huggingface/`
- RTX 5070 Ti needs CUDA 12.4+ — use `pytorch-cuda=12.4` in environment.yml (not 12.1)
- Set env var: `PYTORCH_CUDA_ALLOC_CONF=expandable_segments:True` to avoid memory fragmentation
- If VRAM pressure occurs, add `torch.cuda.empty_cache()` between synthesis calls
- WSL2: confirm CUDA is accessible by running `nvidia-smi` inside WSL before starting

---

## CLAUDE.md

```markdown
## Project: Voice Clone TTS

### Setup
conda env create -f environment.yml
conda activate voiceclone

### First run
The F5-TTS model (~1.2GB) downloads automatically on first use via HuggingFace.
Model is cached at ~/.cache/huggingface/ — do not re-download unnecessarily.

### Key constraints
- Always use device="cuda" — never CPU fallback in production code
- Reference WAV must be 6–30 seconds, 24000 Hz mono after preprocessing
- Output WAVs go to output/ directory only
- Never commit WAV or audio files to git — add to .gitignore
- ref_text (transcript of reference audio) improves quality — provide it when known

### Running
python clone.py --ref voices/speaker.wav --text "Hello world" --out output/result.wav

### Test
python tests/test_clone.py  # uses a bundled 10s reference sample

### Lint/format
ruff check . && black .
```

---

## Known Issues & Mitigations

**WSL2 audio playback**
Output WAV only — use a Windows-side player or `ffplay`. Do not rely on WSL audio subsystem.

**CUDA not found / silent CPU fallback**
Add an explicit check in `clone.py` after model load:
```python
import torch
assert torch.cuda.is_available(), "CUDA not available"
assert next(model.parameters()).is_cuda, "Model must be on CUDA"
```

**Model download behind proxy**
Set `HF_HOME` environment variable before running (F5-TTS uses HuggingFace Hub).

**Long text artifacts**
F5-TTS handles long inputs better than XTTSv2, but inputs over ~500 characters may still produce artifacts. Split into sentences first:
```python
import nltk
sentences = nltk.sent_tokenize(long_text)
```
Then synthesize each sentence and concatenate output WAVs with pydub.

**ref_text accuracy**
If auto-transcription (empty ref_text) produces poor results, provide the exact transcript of the reference WAV manually. This significantly improves voice cloning fidelity.

**Reference WAV quality**
Best results come from clean, single-speaker audio with minimal background noise. Avoid music, reverb, or overlapping voices in the reference clip.

---

## Implementation Order

1. `environment.yml` — set up conda env and verify GPU with `torch.cuda.is_available()`
2. `config.py` — constants and paths
3. `preprocess.py` — WAV validation and cleaning
4. `clone.py` — core synthesis with CLI
5. `tests/test_clone.py` — basic smoke test with a short reference WAV
6. `api.py` — optional, add last if REST interface is needed
