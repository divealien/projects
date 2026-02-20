"""Step 3: Parse OCR results into structured reminders using spatial layout."""

import logging
import re
from dataclasses import dataclass

import cv2
import numpy as np

import config
from ocr_processor import OCRBox

logger = logging.getLogger(__name__)


@dataclass
class Reminder:
    """A single parsed reminder."""
    date: str
    time: str
    text: str
    repeats: bool
    confidence: float  # Average OCR confidence for dedup preference

    def __repr__(self) -> str:
        r = " [repeats]" if self.repeats else ""
        return f"{self.date} {self.time} — {self.text}{r}"


def _normalize_time(time_str: str) -> str:
    """Normalize time to HH:MM format (replace . with :, zero-pad)."""
    t = time_str.strip().replace(".", ":")
    parts = t.split(":")
    if len(parts) == 2:
        return f"{int(parts[0]):02d}:{parts[1]}"
    return t


def _is_date_header(text: str) -> bool:
    """Check if text matches a date header pattern."""
    for pattern in config.DATE_PATTERNS:
        if re.search(pattern, text.strip(), re.IGNORECASE):
            return True
    return False


def _extract_time_prefix(text: str) -> tuple[str, str] | None:
    """Try to extract a leading time from text like '13:19 valentines'.

    Returns (time, remaining_text) or None if no time found.
    """
    m = re.match(r"^(\d{1,2}[:.]\d{2})\s+(.*)", text.strip())
    if m:
        return m.group(1), m.group(2)
    # Just a standalone time
    if re.fullmatch(config.TIME_PATTERN, text.strip()):
        return text.strip(), ""
    return None


def _is_repeat_icon_ocr(box: OCRBox, frame_width: int) -> bool:
    """Detect the repeat icon from OCR — short, low-confidence text at right edge."""
    right_threshold = frame_width * config.REPEAT_ICON_X_THRESHOLD
    if (box.x_min > right_threshold
            and box.confidence <= config.REPEAT_ICON_MAX_CONFIDENCE
            and len(box.text.strip()) <= config.REPEAT_ICON_MAX_TEXT_LEN):
        return True
    return False


def _detect_repeat_icons_pixel(frame_gray: np.ndarray) -> list[int]:
    """Detect repeat icons by scanning the right margin for bright pixel clusters.

    Returns a list of y-center positions where repeat icons were found.
    """
    h, w = frame_gray.shape
    right_margin_start = int(w * (1 - config.REPEAT_ICON_RIGHT_MARGIN))
    top_cutoff = int(h * config.CONTENT_Y_TOP)
    bottom_cutoff = int(h * config.CONTENT_Y_BOTTOM)

    strip = frame_gray[top_cutoff:bottom_cutoff, right_margin_start:]
    threshold = config.REPEAT_ICON_BRIGHTNESS_THRESHOLD

    bright_mask = strip > threshold
    row_brightness = bright_mask.sum(axis=1)

    icon_y_centers = []
    in_cluster = False
    cluster_start = 0

    min_pixels = config.REPEAT_ICON_MIN_BRIGHT_PIXELS
    for y_idx, count in enumerate(row_brightness):
        if count >= min_pixels:
            if not in_cluster:
                in_cluster = True
                cluster_start = y_idx
        else:
            if in_cluster:
                cluster_center = top_cutoff + (cluster_start + y_idx) // 2
                icon_y_centers.append(cluster_center)
                in_cluster = False

    if in_cluster:
        cluster_center = top_cutoff + (cluster_start + len(row_brightness)) // 2
        icon_y_centers.append(cluster_center)

    return icon_y_centers


def _filter_content_boxes(boxes: list[OCRBox], frame_height: int) -> list[OCRBox]:
    """Filter out boxes in the status bar, app header, and nav bar regions."""
    y_top = frame_height * config.CONTENT_Y_TOP
    y_bottom = frame_height * config.CONTENT_Y_BOTTOM
    return [b for b in boxes if b.y_min >= y_top and b.y_max <= y_bottom]


def _group_into_rows(boxes: list[OCRBox]) -> list[list[OCRBox]]:
    """Group OCR boxes into rows by y-coordinate proximity.

    Within each row, boxes are ordered by sub-line (y-sorted) then x-position
    to correctly handle multi-line reminders.
    """
    if not boxes:
        return []

    sorted_boxes = sorted(boxes, key=lambda b: b.y_center)
    rows: list[list[OCRBox]] = []
    current_row: list[OCRBox] = [sorted_boxes[0]]

    for box in sorted_boxes[1:]:
        row_y_center = sum(b.y_center for b in current_row) / len(current_row)
        if abs(box.y_center - row_y_center) <= config.ROW_Y_TOLERANCE:
            current_row.append(box)
        else:
            rows.append(_order_row_boxes(current_row))
            current_row = [box]

    if current_row:
        rows.append(_order_row_boxes(current_row))

    return rows


def _order_row_boxes(boxes: list[OCRBox]) -> list[OCRBox]:
    """Order boxes within a row: group into sub-lines by tight y-proximity,
    then sort each sub-line by x, and concatenate sub-lines top to bottom.
    """
    if len(boxes) <= 1:
        return boxes

    SUB_LINE_TOLERANCE = 25  # Tighter than row tolerance

    sorted_by_y = sorted(boxes, key=lambda b: b.y_center)
    sub_lines: list[list[OCRBox]] = []
    current_line: list[OCRBox] = [sorted_by_y[0]]

    for box in sorted_by_y[1:]:
        line_y = sum(b.y_center for b in current_line) / len(current_line)
        if abs(box.y_center - line_y) <= SUB_LINE_TOLERANCE:
            current_line.append(box)
        else:
            sub_lines.append(sorted(current_line, key=lambda b: b.x_min))
            current_line = [box]

    if current_line:
        sub_lines.append(sorted(current_line, key=lambda b: b.x_min))

    # Flatten: sub-lines in y-order, boxes within each sub-line in x-order
    result = []
    for line in sub_lines:
        result.extend(line)
    return result


def _row_has_repeat_icon_pixel(row_y: float, icon_y_positions: list[int], tolerance: int = 50) -> bool:
    """Check if any repeat icon y-position matches this row's y-position."""
    for icon_y in icon_y_positions:
        if abs(row_y - icon_y) < tolerance:
            return True
    return False


def parse_frame(
    boxes: list[OCRBox],
    frame_gray: np.ndarray,
    frame_width: int,
    frame_height: int,
) -> list[Reminder]:
    """Parse OCR boxes from a single frame into structured Reminder objects."""
    # Filter to content region only
    content_boxes = _filter_content_boxes(boxes, frame_height)
    if not content_boxes:
        return []

    # Detect repeat icons via pixel analysis
    icon_y_positions = _detect_repeat_icons_pixel(frame_gray)
    logger.debug(f"  Pixel repeat icons at y={icon_y_positions}")

    # Separate repeat-icon OCR boxes from content
    real_boxes = []
    ocr_repeat_ys = []
    for box in content_boxes:
        if _is_repeat_icon_ocr(box, frame_width):
            ocr_repeat_ys.append(box.y_center)
            logger.debug(f"  Repeat icon OCR: '{box.text}' conf={box.confidence:.2f} at y={box.y_center:.0f}")
        else:
            real_boxes.append(box)

    rows = _group_into_rows(real_boxes)
    if not rows:
        return []

    current_date = ""
    reminders: list[Reminder] = []

    for row in rows:
        full_text = " ".join(b.text for b in row).strip()
        full_text = re.sub(r"\s+", " ", full_text)
        row_y = sum(b.y_center for b in row) / len(row)

        # Check if this is a date header
        if _is_date_header(full_text):
            current_date = full_text.strip()
            # Remove "Tomorrow " prefix
            current_date = re.sub(r"^Tomorrow\s+", "", current_date)
            logger.debug(f"  Date header: {current_date}")
            continue

        # Try to extract a time from the row
        time_str = ""
        text_parts = []
        confidences = []

        for box in row:
            confidences.append(box.confidence)
            if not time_str:
                extracted = _extract_time_prefix(box.text)
                if extracted:
                    time_str, remainder = extracted
                    if remainder:
                        text_parts.append(remainder)
                    continue
            text_parts.append(box.text)

        if not time_str:
            # No time found — treat as continuation of previous reminder
            if reminders:
                extra = full_text
                reminders[-1].text += "\n" + extra
                logger.debug(f"  Continuation: {extra}")
            continue

        # Check for repeat icon (combine OCR and pixel detection)
        has_repeat = (
            _row_has_repeat_icon_pixel(row_y, icon_y_positions)
            or _row_has_repeat_icon_pixel(row_y, [int(y) for y in ocr_repeat_ys])
        )

        reminder_text = " ".join(text_parts).strip()
        avg_conf = sum(confidences) / len(confidences) if confidences else 0
        normalized_time = _normalize_time(time_str)

        if normalized_time and reminder_text:
            reminders.append(Reminder(
                date=current_date,
                time=normalized_time,
                text=reminder_text,
                repeats=has_repeat,
                confidence=avg_conf,
            ))
            logger.debug(f"  Reminder: {reminders[-1]}")
        elif normalized_time:
            # Time but no text yet — might get continuation
            reminders.append(Reminder(
                date=current_date,
                time=normalized_time,
                text="",
                repeats=has_repeat,
                confidence=avg_conf,
            ))

    return reminders


def parse_all_frames(
    all_boxes: list[list[OCRBox]],
    frame_paths: list,
) -> list[list[Reminder]]:
    """Parse all frames. Returns per-frame lists of reminders."""
    all_reminders = []
    for i, (boxes, path) in enumerate(zip(all_boxes, frame_paths)):
        logger.info(f"Parsing frame {i + 1}/{len(all_boxes)}: {path.name}")
        img = cv2.imread(str(path))
        if img is None:
            logger.warning(f"  Could not read frame image: {path}")
            all_reminders.append([])
            continue
        gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
        h, w = img.shape[:2]
        reminders = parse_frame(boxes, gray, w, h)

        # Assign default date to reminders with no date header
        for r in reminders:
            if not r.date:
                r.date = config.DEFAULT_INITIAL_DATE

        all_reminders.append(reminders)
        logger.info(f"  Found {len(reminders)} reminders in frame {i + 1}")

    return all_reminders
