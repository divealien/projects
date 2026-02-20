# BZ Reminders Video Extractor — Spec

## Goal

Extract structured reminder data from an MP4 screen recording of the BZ Reminders Android app. The video shows the user scrolling through reminders one page at a time (scroll, pause, scroll, pause). The output should be a clean, deduplicated list of all reminders with their metadata.

## Input

- An MP4 video file recorded on an Android phone (screen capture)
- The video consists of alternating scroll/pause sequences — each pause shows a static screenful of reminders
- Approximately 50 reminders total across multiple screens

## Screen Layout (per static frame)

Each visible screen contains:

1. **Date header(s)** — a date label (e.g. "Tuesday, 14 January") that groups the reminders below it by day
2. **Reminder items**, each containing:
   - **Time** — at the left of the row (e.g. "09:00")
   - **Reminder text** — the main body of the reminder, to the right of the time
   - **Repeat indicator** — a double-arrow icon (≫ or similar) at the right edge of the row, indicating the reminder is recurring. Not all reminders may have this.

A single screen may contain reminders from more than one date (i.e. a date header partway down the screen starting a new day's group).

## Processing Pipeline

### Step 1: Static Frame Extraction

- Use OpenCV to read the video frame by frame
- Compare consecutive frames (e.g. mean absolute difference of pixel values across the frame, or a central crop of the frame to avoid status bar clock changes)
- When the difference is below a threshold for N consecutive frames (suggesting the scroll has stopped), capture one representative frame from that stable period
- Skip the first/last few frames of each stable period to avoid capturing mid-settle frames
- Save extracted frames as numbered PNGs for debugging/review
- Log how many stable periods were detected and frames captured

### Step 2: OCR Each Frame

- Use EasyOCR (with GPU acceleration via PyTorch/CUDA) as the primary OCR engine
- Process each extracted frame to get bounding boxes + text + confidence scores
- Preserve spatial information (x, y coordinates of each text block) — this is needed to reconstruct the row structure

### Step 3: Structural Parsing

For each frame, use the spatial layout of OCR results to reconstruct the reminder structure:

- **Date headers**: Identify text blocks that match a date pattern (e.g. "Tuesday, 14 January", "Wed, 15 Jan", or similar). These tend to be standalone lines, possibly with distinct formatting. Use y-position to determine which reminders fall under which date.
- **Reminder rows**: Group remaining text blocks into rows by y-coordinate proximity. Within each row:
  - The leftmost text block matching a time pattern (HH:MM) is the **time**
  - The rightmost element may be the **repeat indicator** — detect this either via OCR (if the arrows render as text like ">>" or "»") or by checking for a small icon-like region at the right edge of the row. If OCR doesn't pick it up, consider template matching or simply checking for a non-text graphic element in that region.
  - Everything in between is the **reminder text**
- Handle multi-line reminder text if present (some reminders may wrap to a second line)

### Step 4: Deduplication

Since consecutive screens will overlap (the bottom items of screen N appear at the top of screen N+1):

- After parsing all frames, merge the results into a single ordered list
- Deduplicate by matching on (date + time + reminder text). Use fuzzy matching (e.g. difflib.SequenceMatcher with a ratio threshold ≥ 0.85) to handle minor OCR variations between frames
- Preserve the order: date ascending, then time ascending within each date
- If the same reminder appears in multiple frames with slightly different OCR results, prefer the version with the higher average OCR confidence score

### Step 5: Output

Produce two output files:

1. **JSON** (`reminders.json`):
```json
[
  {
    "date": "Tuesday, 14 January",
    "time": "09:00",
    "text": "Take medication",
    "repeats": true
  },
  {
    "date": "Tuesday, 14 January",
    "time": "14:30",
    "text": "Call dentist",
    "repeats": false
  }
]
```

2. **Markdown** (`reminders.md`) — human-readable, grouped by date:
```markdown
## Tuesday, 14 January

- 09:00 — Take medication 🔁
- 14:30 — Call dentist

## Wednesday, 15 January

- 08:00 — Morning standup 🔁
```

(Use 🔁 or `[repeats]` to mark recurring reminders.)

## Technical Requirements

- Python 3.10+
- OpenCV (`opencv-python`)
- EasyOCR (with CUDA/GPU support)
- PyTorch (already installed with CUDA support for GTX 5070 Ti)
- No other heavy dependencies required; standard library for the rest (`json`, `difflib`, `pathlib`, `re`, etc.)

## Project Structure

```
bz-reminders-extractor/
├── extract.py          # Main script — runs the full pipeline
├── frame_extractor.py  # Step 1: video → static frames
├── ocr_processor.py    # Step 2: frames → raw OCR results
├── parser.py           # Step 3: OCR results → structured reminders
├── dedup.py            # Step 4: merge and deduplicate
├── output.py           # Step 5: write JSON + Markdown
├── config.py           # Thresholds, paths, tunables (see below)
├── frames/             # Output directory for extracted frame PNGs
├── reminders.json      # Final output
└── reminders.md        # Final output
```

## Configurable Parameters (config.py)

| Parameter | Description | Suggested Default |
|---|---|---|
| `VIDEO_PATH` | Path to input MP4 | (required, CLI arg) |
| `FRAMES_DIR` | Directory to save extracted frames | `./frames` |
| `STABILITY_THRESHOLD` | Max mean pixel diff to consider frame "stable" | 2.0 |
| `STABILITY_WINDOW` | Number of consecutive stable frames required | 10 |
| `STABILITY_SKIP_FRAMES` | Frames to skip at start/end of stable period | 3 |
| `COMPARE_CROP` | Region of frame to compare (avoid status bar clock) | Crop out top 10% and bottom 5% |
| `OCR_CONFIDENCE_THRESHOLD` | Minimum confidence to keep an OCR result | 0.3 |
| `ROW_Y_TOLERANCE` | Max vertical pixel distance to group text into same row | 20 |
| `DEDUP_SIMILARITY_THRESHOLD` | Fuzzy match ratio to consider two reminders the same | 0.85 |
| `TIME_PATTERN` | Regex for matching time strings | `r'\d{1,2}:\d{2}'` |
| `DATE_PATTERNS` | Regexes for matching date headers | e.g. day-of-week + date patterns |

## Edge Cases to Handle

- A date header may be the last line on a screen (with its reminders only visible on the next screen) — associate it correctly
- A reminder's text may span two lines — detect via y-gap analysis and merge
- The repeat indicator (double arrow) may not OCR cleanly — consider falling back to detecting a non-text graphical element at the right edge of a row, or template matching against a reference crop of the icon
- The very first and last frames of the video may be partial/transitional — don't assume they're valid screens
- Status bar clock changes between frames should not trigger false "new screen" detection — use the cropped comparison region

## Usage

```bash
python extract.py /path/to/video.mp4
```

Optional flags:
- `--output-dir` — where to write results (default: current directory)
- `--save-frames` — save extracted frame PNGs for review (default: on)
- `--debug` — verbose logging, save intermediate OCR data as JSON per frame
- `--dry-run` — extract frames only, don't OCR (useful for tuning stability thresholds)

## Success Criteria

- All ~50 reminders are extracted with correct date, time, text, and repeat status
- No duplicates in the final output
- Output is ordered chronologically
- The tool runs in under a few minutes on the given hardware
