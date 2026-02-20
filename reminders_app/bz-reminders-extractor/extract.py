#!/usr/bin/env python3
"""BZ Reminders Video Extractor — main pipeline script.

Usage:
    python extract.py /path/to/video.mp4 [options]
"""

import argparse
import logging
import sys
from pathlib import Path

import config
from frame_extractor import extract_frames
from ocr_processor import create_reader, process_all_frames
from parser import parse_all_frames
from dedup import deduplicate
from output import write_csv


def setup_logging(debug: bool = False) -> None:
    level = logging.DEBUG if debug else logging.INFO
    logging.basicConfig(
        level=level,
        format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
        datefmt="%H:%M:%S",
    )


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Extract reminders from a BZ Reminders screen recording."
    )
    parser.add_argument("video", help="Path to the MP4 video file")
    parser.add_argument(
        "--output-dir",
        type=Path,
        default=Path("."),
        help="Directory for output files (default: current directory)",
    )
    parser.add_argument(
        "--save-frames",
        action=argparse.BooleanOptionalAction,
        default=True,
        help="Save extracted frame PNGs for review (default: on)",
    )
    parser.add_argument(
        "--debug",
        action="store_true",
        help="Verbose logging and save intermediate OCR data as JSON per frame",
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="Extract frames only, don't run OCR (useful for tuning thresholds)",
    )
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    setup_logging(args.debug)
    logger = logging.getLogger("extract")

    video_path = args.video
    if not Path(video_path).is_file():
        logger.error(f"Video file not found: {video_path}")
        sys.exit(1)

    frames_dir = args.output_dir / "frames"
    config.FRAMES_DIR = frames_dir
    config.OUTPUT_DIR = args.output_dir

    # Step 1: Extract static frames
    logger.info("=" * 60)
    logger.info("Step 1: Extracting static frames from video")
    logger.info("=" * 60)
    frame_paths = extract_frames(video_path, frames_dir, save=args.save_frames)

    if not frame_paths:
        logger.error("No stable frames found! Try adjusting STABILITY_THRESHOLD or STABILITY_WINDOW.")
        sys.exit(1)

    if args.dry_run:
        logger.info(f"Dry run complete. {len(frame_paths)} frames saved to {frames_dir}")
        sys.exit(0)

    # Step 2: OCR each frame
    logger.info("=" * 60)
    logger.info("Step 2: Running OCR on extracted frames")
    logger.info("=" * 60)
    reader = create_reader()
    all_ocr_results = process_all_frames(reader, frame_paths, debug=args.debug)

    # Step 3: Parse structure
    logger.info("=" * 60)
    logger.info("Step 3: Parsing reminder structure from OCR results")
    logger.info("=" * 60)
    per_frame_reminders = parse_all_frames(all_ocr_results, frame_paths)

    # Step 4: Deduplicate
    logger.info("=" * 60)
    logger.info("Step 4: Deduplicating reminders")
    logger.info("=" * 60)
    reminders = deduplicate(per_frame_reminders)

    # Step 5: Output
    logger.info("=" * 60)
    logger.info("Step 5: Writing output files")
    logger.info("=" * 60)
    args.output_dir.mkdir(parents=True, exist_ok=True)
    csv_path = write_csv(reminders, args.output_dir)

    logger.info("=" * 60)
    logger.info(f"Done! {len(reminders)} reminders extracted.")
    logger.info(f"  CSV: {csv_path}")
    logger.info("=" * 60)


if __name__ == "__main__":
    main()
