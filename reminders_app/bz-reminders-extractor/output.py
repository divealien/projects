"""Step 5: Write final output as CSV."""

import logging
import re
from datetime import datetime
from pathlib import Path

from parser import Reminder

logger = logging.getLogger(__name__)

# The video was recorded in 2026
YEAR = 2026

# Month name/abbreviation to number
MONTH_MAP = {
    "jan": 1, "feb": 2, "mar": 3, "apr": 4, "may": 5, "jun": 6,
    "jul": 7, "aug": 8, "sep": 9, "sept": 9, "oct": 10, "nov": 11, "dec": 12,
    "january": 1, "february": 2, "march": 3, "april": 4, "june": 6,
    "july": 7, "august": 8, "september": 9, "october": 10, "november": 11, "december": 12,
}


def _parse_date(date_str: str) -> str:
    """Convert date like 'Thursday 13 Feb' to '2026-02-13'."""
    m = re.search(r"(\d{1,2})\s+(\w+)", date_str)
    if not m:
        return ""
    day = int(m.group(1))
    month_name = m.group(2).lower()
    month = MONTH_MAP.get(month_name)
    if not month:
        logger.warning(f"Unknown month in date '{date_str}'")
        return ""
    return f"{YEAR}-{month:02d}-{day:02d}"


def write_csv(reminders: list[Reminder], output_dir: Path) -> Path:
    """Write reminders to CSV with ~ delimiter.

    Format: title~datetime~recurrence
    """
    out_path = output_dir / "reminders.csv"
    lines = ["title~datetime~recurrence"]

    for r in reminders:
        iso_date = _parse_date(r.date)
        if not iso_date:
            logger.warning(f"Skipping reminder with unparseable date: {r}")
            continue
        dt = f"{iso_date} {r.time}"
        # Escape actual newlines as literal \n
        text = r.text.replace("\n", "\\n")
        recurrence = "Y" if r.repeats else ""
        lines.append(f"{text}~{dt}~{recurrence}")

    with open(out_path, "w", encoding="utf-8") as f:
        f.write("\n".join(lines))
        f.write("\n")

    logger.info(f"Wrote {len(lines) - 1} reminders to {out_path}")
    return out_path
