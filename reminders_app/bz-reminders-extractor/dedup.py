"""Step 4: Merge per-frame reminders and deduplicate overlapping entries."""

import logging
import re
from difflib import SequenceMatcher

import config
from parser import Reminder

logger = logging.getLogger(__name__)


def _normalize_text(text: str) -> str:
    """Normalize text for comparison: lowercase, collapse whitespace, strip punctuation edges."""
    t = text.lower().strip()
    t = re.sub(r"\s+", " ", t)
    return t


def _word_set_similarity(a: str, b: str) -> float:
    """Word-set (Jaccard) similarity — order-independent."""
    words_a = set(_normalize_text(a).split())
    words_b = set(_normalize_text(b).split())
    if not words_a and not words_b:
        return 1.0
    if not words_a or not words_b:
        return 0.0
    intersection = words_a & words_b
    union = words_a | words_b
    return len(intersection) / len(union)


def _sequence_similarity(a: str, b: str) -> float:
    """Character-level sequence similarity."""
    return SequenceMatcher(None, _normalize_text(a), _normalize_text(b)).ratio()


def _text_similarity(a: str, b: str) -> float:
    """Combined text similarity — max of word-set and sequence-based."""
    return max(_word_set_similarity(a, b), _sequence_similarity(a, b))


def _date_matches(a: str, b: str) -> bool:
    """Check if two date strings refer to the same date.

    Handles variations like 'Thursday 13 Feb' vs 'Thu 13 Feb'.
    """
    if a == b:
        return True
    # Extract day number and month for comparison
    pat = r"(\d{1,2})\s+(\w{3})"
    ma = re.search(pat, a)
    mb = re.search(pat, b)
    if ma and mb:
        return ma.group(1) == mb.group(1) and ma.group(2).lower() == mb.group(2).lower()
    return _sequence_similarity(a, b) >= config.DEDUP_SIMILARITY_THRESHOLD


def _reminders_match(a: Reminder, b: Reminder) -> bool:
    """Check if two reminders are the same (accounting for OCR variations)."""
    if not _date_matches(a.date, b.date):
        return False
    if a.time != b.time:
        return False
    if _text_similarity(a.text, b.text) < config.DEDUP_SIMILARITY_THRESHOLD:
        return False
    return True


def _is_substring_match(a: Reminder, b: Reminder) -> bool:
    """Check if one reminder's text is mostly contained within another's.

    This catches cases where OCR split one reminder across frames differently,
    resulting in one version having extra text fragments.
    """
    if not _date_matches(a.date, b.date):
        return False
    if a.time != b.time:
        return False
    short, long = (a.text, b.text) if len(a.text) <= len(b.text) else (b.text, a.text)
    if not short:
        return False
    # Check if most words of the shorter text appear in the longer text
    short_words = set(_normalize_text(short).split())
    long_words = set(_normalize_text(long).split())
    if not short_words:
        return False
    overlap = len(short_words & long_words) / len(short_words)
    return overlap >= 0.8


def deduplicate(per_frame_reminders: list[list[Reminder]]) -> list[Reminder]:
    """Merge reminders from all frames into a single deduplicated list."""
    merged: list[Reminder] = []

    for frame_idx, frame_reminders in enumerate(per_frame_reminders):
        for reminder in frame_reminders:
            if not reminder.text:
                continue

            found = False
            for i, existing in enumerate(merged):
                if _reminders_match(existing, reminder):
                    if reminder.confidence > existing.confidence:
                        repeat = merged[i].repeats or reminder.repeats
                        merged[i] = reminder
                        merged[i].repeats = repeat
                        logger.debug(f"  Replaced (higher conf): {reminder}")
                    else:
                        merged[i].repeats = merged[i].repeats or reminder.repeats
                    found = True
                    break
                # Also check substring containment
                elif _is_substring_match(existing, reminder):
                    # Keep the shorter (cleaner) version if it has reasonable confidence
                    shorter = existing if len(existing.text) <= len(reminder.text) else reminder
                    merged[i].repeats = merged[i].repeats or reminder.repeats
                    if len(reminder.text) < len(existing.text) and reminder.confidence >= existing.confidence * 0.8:
                        merged[i].text = reminder.text
                        merged[i].confidence = reminder.confidence
                    found = True
                    logger.debug(f"  Substring match merged: {reminder}")
                    break

            if not found:
                merged.append(reminder)
                logger.debug(f"  New: {reminder}")

    logger.info(f"Deduplication: {sum(len(f) for f in per_frame_reminders)} total "
                f"→ {len(merged)} unique reminders")

    # Sort by date appearance order then time
    date_order: dict[str, int] = {}
    for r in merged:
        if r.date not in date_order:
            date_order[r.date] = len(date_order)

    merged.sort(key=lambda r: (date_order.get(r.date, 999), r.time))
    return merged
