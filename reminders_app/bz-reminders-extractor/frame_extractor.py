"""Step 1: Extract static frames from the video where scrolling has paused."""

import logging
from pathlib import Path

import cv2
import numpy as np

import config

logger = logging.getLogger(__name__)


def _crop_compare_region(frame: np.ndarray) -> np.ndarray:
    """Crop out status bar and bottom nav to avoid clock-change false positives."""
    h = frame.shape[0]
    top = int(h * config.COMPARE_CROP_TOP)
    bottom = int(h * (1 - config.COMPARE_CROP_BOTTOM))
    return frame[top:bottom]


def extract_frames(video_path: str, frames_dir: Path, save: bool = True) -> list[Path]:
    """Read video and extract one representative frame per stable (paused) period.

    Returns a list of paths to saved frame PNGs.
    """
    cap = cv2.VideoCapture(video_path)
    if not cap.isOpened():
        raise RuntimeError(f"Cannot open video: {video_path}")

    fps = cap.get(cv2.CAP_PROP_FPS)
    total_frames = int(cap.get(cv2.CAP_PROP_FRAME_COUNT))
    logger.info(f"Video: {total_frames} frames, {fps:.1f} FPS, ~{total_frames / fps:.1f}s")

    frames_dir.mkdir(parents=True, exist_ok=True)

    prev_cropped = None
    stable_count = 0
    stable_start_idx = 0
    captured = False  # Whether we already captured from the current stable period
    saved_frames: list[Path] = []
    frame_buffer: list[tuple[int, np.ndarray]] = []  # (index, frame) buffer for stable period

    frame_idx = 0
    while True:
        ret, frame = cap.read()
        if not ret:
            break

        gray = cv2.cvtColor(frame, cv2.COLOR_BGR2GRAY)
        cropped = _crop_compare_region(gray)

        if prev_cropped is not None:
            diff = np.mean(np.abs(cropped.astype(float) - prev_cropped.astype(float)))

            if diff < config.STABILITY_THRESHOLD:
                if stable_count == 0:
                    stable_start_idx = frame_idx - 1
                    frame_buffer = []
                stable_count += 1
                frame_buffer.append((frame_idx, frame))
            else:
                # End of stable period — capture if we haven't yet
                if stable_count >= config.STABILITY_WINDOW and not captured:
                    saved = _capture_from_stable(frame_buffer, frames_dir, len(saved_frames), save)
                    if saved:
                        saved_frames.append(saved)
                        logger.info(
                            f"Captured frame {len(saved_frames)} from stable period "
                            f"(frames {stable_start_idx}-{frame_idx - 1}, "
                            f"duration {stable_count} frames)"
                        )
                stable_count = 0
                captured = False
                frame_buffer = []
        else:
            frame_buffer.append((frame_idx, frame))

        prev_cropped = cropped
        frame_idx += 1

    # Handle final stable period
    if stable_count >= config.STABILITY_WINDOW and not captured:
        saved = _capture_from_stable(frame_buffer, frames_dir, len(saved_frames), save)
        if saved:
            saved_frames.append(saved)
            logger.info(f"Captured frame {len(saved_frames)} from final stable period")

    cap.release()
    logger.info(f"Extracted {len(saved_frames)} static frames from {frame_idx} total frames")
    return saved_frames


def _capture_from_stable(
    buffer: list[tuple[int, np.ndarray]],
    frames_dir: Path,
    seq: int,
    save: bool,
) -> Path | None:
    """Pick a representative frame from the middle of a stable period, skipping edges."""
    skip = config.STABILITY_SKIP_FRAMES
    if len(buffer) <= 2 * skip:
        # Too short after skipping — just use the middle
        pick = len(buffer) // 2
    else:
        # Use the middle of the usable range
        usable = buffer[skip : len(buffer) - skip]
        pick_in_usable = len(usable) // 2
        pick = skip + pick_in_usable

    frame_idx, frame = buffer[pick]
    out_path = frames_dir / f"frame_{seq:03d}.png"
    if save:
        cv2.imwrite(str(out_path), frame)
    return out_path
