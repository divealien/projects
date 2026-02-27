## Project: Voice Clone TTS

### Setup
```bash
conda activate ai-audio
pip install -r requirements.txt
```

### First run
The F5-TTS model (~1.2GB) downloads automatically on first use via HuggingFace.
Model is cached at `~/.cache/huggingface/` — do not re-download unnecessarily.

### Key constraints
- Always use `device="cuda"` — never CPU fallback in production code
- Reference WAV must be 6–30 seconds (ideal ≤12s), 24000 Hz mono after preprocessing
- F5-TTS max total context is 30s (reference + generated); longer references are auto-extracted to 12s by preprocess.py
- Output WAVs go to `output/` directory only
- Never commit WAV or audio files to git — they are in `.gitignore`
- `ref_text` (transcript of reference audio) improves quality — provide it when known
- Set `PYTORCH_ALLOC_CONF=expandable_segments:True` (clone.py does this automatically)

### Running
```bash
# Preprocess a raw reference WAV or MP3 (MP3 requires ffmpeg)
python preprocess.py --input raw.wav --output voices/speaker.wav
python preprocess.py --input raw.mp3 --output voices/speaker.wav

# Synthesize speech (auto-transcribes reference audio)
python clone.py --ref voices/speaker.wav --text "Hello world" --out output/result.wav

# With known reference transcript (faster, more accurate)
python clone.py --ref voices/speaker.wav --ref-text "This is what I said." --text "Hello world" --out output/result.wav
```

### Test
```bash
python tests/test_clone.py
```

### Lint/format
```bash
ruff check . && black .
```

### Notes
- WSL2: play output WAVs with a Windows-side player or `ffplay`; WSL audio is unreliable
- Long text (>500 chars) is automatically split into sentences and concatenated
- If VRAM pressure occurs, `torch.cuda.empty_cache()` is called between sentence batches
