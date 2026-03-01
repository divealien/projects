# Task: Implement `merge_subs.py`

## Goal

Write a single-file Python script called `merge_subs.py` that:

1. Extracts a subtitle track from an MKV file using `ffmpeg`
2. Merges it with an external SRT file
3. Outputs a single merged SRT file where the MKV subtitles take full priority — any external SRT entry that overlaps at all with an MKV entry is **dropped entirely**

No pip installs. Python 3.8+ stdlib only. `ffmpeg` and `ffprobe` must be on PATH (WSL environment).

---

## CLI Interface

```
python merge_subs.py <input.mkv> <external.srt> [options]

Positional arguments:
  input.mkv              Path to source MKV file
  external.srt           Path to external SRT subtitle file

Options:
  -o, --output FILE      Output SRT path (default: <input_basename>_merged.srt)
  -t, --track INDEX      0-based subtitle track index to extract from MKV (default: 0)
  --list-tracks          List all subtitle tracks in the MKV and exit
  --keep-tmp             Keep the temporary extracted SRT file (default: delete it)
  --force                Overwrite output file without prompting
  -v, --verbose          Print merge statistics to stdout
```

---

## Data Model

```python
@dataclass
class SubEntry:
    index: int        # original sequence number; will be reassigned on output
    start: timedelta  # start timestamp
    end: timedelta    # end timestamp
    text: str         # raw subtitle text; preserve inline tags e.g. <i>
    source: str       # 'mkv' or 'ext'
```

---

## Internal Functions

```
parse_srt(path: str) -> list[SubEntry]
write_srt(entries: list[SubEntry], path: str) -> None
extract_srt_from_mkv(mkv_path: str, track_index: int, out_path: str) -> None
list_tracks(mkv_path: str) -> None
has_overlap(entry: SubEntry, mkv_subs: list[SubEntry]) -> bool
merge(mkv_subs: list[SubEntry], ext_subs: list[SubEntry]) -> list[SubEntry]
main()
```

---

## Implementation Details

### `parse_srt(path)`

- Handle both `\r\n` (Windows) and `\n` (Unix) line endings
- Handle BOM (`\ufeff`) at start of file
- Entries are separated by blank lines
- Each entry: sequence number line, timestamp line, one or more text lines
- Timestamp format: `HH:MM:SS,mmm --> HH:MM:SS,mmm`
- Parse timestamps into `timedelta` objects
- Multi-line text: join with `\n`, preserve as-is (don't strip inline tags)
- On malformed entry: print a warning to stderr and skip it, do not crash
- Skip entries with empty text after stripping

### `write_srt(entries, path)`

- Standard SRT format, comma as decimal separator: `HH:MM:SS,mmm`
- Blank line between entries
- Indices are 1-based and sequential (ignore original index values)

### `extract_srt_from_mkv(mkv_path, track_index, out_path)`

- Run: `ffmpeg -y -i <mkv_path> -map 0:s:<track_index> -c:s srt <out_path>`
- Capture stdout and stderr via `subprocess.run(..., capture_output=True)`
- If ffmpeg exits non-zero: raise a descriptive exception with stderr content

### `list_tracks(mkv_path)`

- Run `ffprobe -v error -select_streams s -show_entries stream=index,codec_name:stream_tags=language -of csv=p=0 <mkv_path>`
- Print each subtitle stream with its 0-based index among subtitle streams, codec name, and language tag
- Example output:
  ```
  Track 0: subrip  [eng]
  Track 1: ass     [jpn]
  Track 2: hdmv_pgs_subtitle [jpn]
  ```
- Then exit

### `has_overlap(entry, mkv_subs)`

- Returns `True` if `entry` overlaps with **any** entry in `mkv_subs`
- Overlap condition: `A.start < B.end AND A.end > B.start`
- Touching endpoints (`A.end == B.start`) do **not** count as overlap

### `merge(mkv_subs, ext_subs)`

1. For each entry in `ext_subs`: if `has_overlap(entry, mkv_subs)` → drop it, else keep it
2. Combine all `mkv_subs` + surviving `ext_subs`
3. Sort by `start`; ties broken by placing `mkv` source entries first
4. Reassign sequential 1-based indices
5. Return the final list

### `main()`

- Parse args with `argparse`
- If `--list-tracks`: call `list_tracks()` and exit
- Validate that both input files exist; exit with clear error if not
- Check ffmpeg/ffprobe are on PATH using `shutil.which`; exit with install hint if missing
- Use `tempfile.mktemp(suffix='.srt')` for the extracted subtitle temp file
- Before extraction: use ffprobe to check the selected track's codec. If it is `hdmv_pgs_subtitle` or any other bitmap codec, exit with a message explaining it cannot be converted to text
- Call `extract_srt_from_mkv()`; if the result is empty (0 entries after parsing), warn and proceed using only external subs
- If external SRT parses to 0 entries, warn and proceed using only MKV subs
- If output file exists and `--force` is not set: prompt `"Output file exists. Overwrite? [y/N]: "` and exit if user says no
- Call `merge()` and `write_srt()`
- If `--keep-tmp` is not set: delete the temp extracted SRT
- If `-v`: print statistics (see below)

---

## Verbose Statistics Output

```
Extracted MKV track:     312 entries  (track 1, language: jpn)
External SRT:            487 entries
  Kept (no overlap):     201
  Dropped (overlap):     286
MKV entries:             312
Total merged output:     513 entries
Written to:              episode01_merged.srt
```

---

## Error Handling Reference

| Condition | Behaviour |
|---|---|
| MKV file not found | Print error, `sys.exit(1)` |
| External SRT not found | Print error, `sys.exit(1)` |
| No subtitle tracks in MKV | Print message, `sys.exit(1)` |
| Track index out of range | Print error + available track count, `sys.exit(1)` |
| PGS / bitmap track selected | Print explanation, `sys.exit(1)` |
| ffmpeg/ffprobe not on PATH | Print install hint, `sys.exit(1)` |
| ffmpeg exits non-zero | Print ffmpeg stderr, `sys.exit(1)` |
| Malformed SRT entry | `print(f"Warning: skipping malformed entry ...", file=sys.stderr)` |
| Extracted SRT is empty | `print("Warning: extracted track is empty ...", file=sys.stderr)` |
| External SRT is empty | `print("Warning: external SRT is empty ...", file=sys.stderr)` |
| Output file exists, no `--force` | Prompt user; exit if declined |

---

## Edge Cases to Handle

- External entry that **abuts** an MKV entry (`ext.end == mkv.start`): not an overlap, keep it
- MKV entries that overlap each other: preserve both, do not deduplicate
- Entries with empty text after stripping: skip during parsing
- The same file passed as both MKV source and external SRT: allowed (unusual but don't crash)

---

## Example Usage

```bash
# List subtitle tracks in the MKV
python merge_subs.py movie.mkv dummy.srt --list-tracks

# Merge using track 1 (e.g. Japanese), output to custom path
python merge_subs.py movie.mkv english_forced.srt -t 1 -o movie_merged.srt -v

# Use defaults (track 0, auto-named output)
python merge_subs.py episode01.mkv episode01_extra.srt
```

---

## Dependencies

- Python 3.8+ (stdlib only: `subprocess`, `argparse`, `dataclasses`, `re`, `tempfile`, `shutil`, `sys`, `os`, `datetime`)
- `ffmpeg` and `ffprobe` installed and accessible on WSL PATH

No virtual environment or pip installs required.
