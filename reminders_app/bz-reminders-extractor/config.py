"""Configurable parameters for the BZ Reminders Extractor."""

from pathlib import Path

# Paths
VIDEO_PATH: str = ""  # Set via CLI
FRAMES_DIR: Path = Path("./frames")
OUTPUT_DIR: Path = Path(".")

# Frame extraction — stability detection
STABILITY_THRESHOLD: float = 2.0  # Max mean pixel diff to consider "stable"
STABILITY_WINDOW: int = 10  # Consecutive stable frames required
STABILITY_SKIP_FRAMES: int = 3  # Frames to skip at start/end of stable period

# Comparison region crop (fractions of frame height to exclude)
COMPARE_CROP_TOP: float = 0.10  # Skip top 10% (status bar)
COMPARE_CROP_BOTTOM: float = 0.05  # Skip bottom 5%

# OCR
OCR_CONFIDENCE_THRESHOLD: float = 0.3
OCR_LANGUAGES: list[str] = ["en"]

# Content region (fraction of frame height) — exclude status bar + app bar + nav bar
CONTENT_Y_TOP: float = 0.12  # Below status bar and "Reminders" header
CONTENT_Y_BOTTOM: float = 0.88  # Above bottom nav bar

# Structural parsing
ROW_Y_TOLERANCE: int = 60  # Max vertical px distance to group text into same row
# Time pattern: matches HH:MM or HH.MM (EasyOCR sometimes reads : as .)
TIME_PATTERN: str = r"\d{1,2}[:.]\d{2}"
DATE_PATTERNS: list[str] = [
    # "Tomorrow Sat 14 Feb" / "Tomorrow Saturday 14 Feb"
    r"^Tomorrow\s+(?:Mon|Tue|Wed|Thu|Fri|Sat|Sun)\w*\s+\d{1,2}\s+\w+",
    # "Friday 20 Feb" / "Thursday 12 Mar" / "Saturday 14 Nov"
    r"^(?:Monday|Tuesday|Wednesday|Thursday|Friday|Saturday|Sunday)\s+\d{1,2}\s+\w{3,}",
    # "Fri 20 Feb" / "Thu 12 Mar"
    r"^(?:Mon|Tue|Wed|Thu|Fri|Sat|Sun)\s+\d{1,2}\s+\w{3,}",
]

# Default date for reminders before the first visible date header
DEFAULT_INITIAL_DATE: str = "Thursday 13 Feb"

# Repeat icon detection — the icon OCRs as short low-confidence text at the right edge
REPEAT_ICON_X_THRESHOLD: float = 0.88  # Must be in rightmost 12% of frame
REPEAT_ICON_MAX_CONFIDENCE: float = 0.80  # Icon OCR readings tend to be low-mid confidence
REPEAT_ICON_MAX_TEXT_LEN: int = 3  # Icon OCR text is very short

# Repeat icon detection (pixel-based fallback)
REPEAT_ICON_RIGHT_MARGIN: float = 0.12
REPEAT_ICON_BRIGHTNESS_THRESHOLD: int = 100
REPEAT_ICON_MIN_BRIGHT_PIXELS: int = 15

# Deduplication
DEDUP_SIMILARITY_THRESHOLD: float = 0.85
