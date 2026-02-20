"""Step 2: Run OCR on extracted frames and return structured bounding box data."""

import json
import logging
from dataclasses import dataclass, asdict
from pathlib import Path

import easyocr

import config

logger = logging.getLogger(__name__)


@dataclass
class OCRBox:
    """A single OCR detection with spatial info."""
    text: str
    confidence: float
    x_min: int
    y_min: int
    x_max: int
    y_max: int

    @property
    def y_center(self) -> float:
        return (self.y_min + self.y_max) / 2

    @property
    def x_center(self) -> float:
        return (self.x_min + self.x_max) / 2


def create_reader() -> easyocr.Reader:
    """Create an EasyOCR reader with GPU support."""
    logger.info("Initializing EasyOCR reader (GPU)...")
    reader = easyocr.Reader(config.OCR_LANGUAGES, gpu=True)
    logger.info("EasyOCR reader ready")
    return reader


def process_frame(reader: easyocr.Reader, frame_path: Path) -> list[OCRBox]:
    """Run OCR on a single frame and return filtered results."""
    logger.debug(f"OCR processing: {frame_path.name}")
    results = reader.readtext(str(frame_path))

    boxes = []
    for bbox, text, conf in results:
        if conf < config.OCR_CONFIDENCE_THRESHOLD:
            continue
        # bbox is [[x1,y1],[x2,y2],[x3,y3],[x4,y4]] — take bounding rect
        xs = [pt[0] for pt in bbox]
        ys = [pt[1] for pt in bbox]
        boxes.append(OCRBox(
            text=text.strip(),
            confidence=conf,
            x_min=int(min(xs)),
            y_min=int(min(ys)),
            x_max=int(max(xs)),
            y_max=int(max(ys)),
        ))

    logger.debug(f"  {frame_path.name}: {len(boxes)} text regions (after filtering)")
    return boxes


def process_all_frames(
    reader: easyocr.Reader,
    frame_paths: list[Path],
    debug: bool = False,
) -> list[list[OCRBox]]:
    """Run OCR on all frames. Optionally save debug JSON per frame."""
    all_results = []
    for i, path in enumerate(frame_paths):
        logger.info(f"OCR frame {i + 1}/{len(frame_paths)}: {path.name}")
        boxes = process_frame(reader, path)
        all_results.append(boxes)

        if debug:
            debug_path = path.with_suffix(".ocr.json")
            with open(debug_path, "w") as f:
                json.dump([asdict(b) for b in boxes], f, indent=2)
            logger.debug(f"  Saved debug OCR data to {debug_path}")

    return all_results
