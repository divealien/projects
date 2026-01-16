"""
Music stem separation using Demucs with chunking and automatic recombination.

Usage:
  separate_cli.py <input_path> [--output=<folder>] [--in-place-subdir=<subdir>] [--model=<name>] [--shifts=<n>] [--overlap=<o>] [--fp32] [--two-stems=<stem>] [--no-cuda] [--max-chunk-length=<seconds>]
  separate_cli.py (-h | --help)

Options:
  -h --help                   Show this help message.
  <input_path>                Path to an audio file or folder containing audio files.
  --output=<folder>           Output folder [default: stems_output].
  --in-place-subdir=<subdir>  Output to a subdirectory of the input location. Overrides --output.
  --model=<name>              Demucs model to use [default: htdemucs_ft].
  --shifts=<n>                Number of shifts for ensemble averaging [default: 10].
  --overlap=<o>               Overlap fraction for splitting [default: 0.5].
  --fp32                      Use FP32 precision (default: FP16 if not set).
  --two-stems=<stem>          Use two-stem separation: 'vocals' or 'none' [default: none].
  --no-cuda                   Force CPU usage even if CUDA is available.
  --max-chunk-length=<seconds> Maximum chunk length in seconds for long tracks [default: 300].
"""

import os
import sys
import shutil
import subprocess
from docopt import docopt
from demucs import separate
import torch

import warnings
warnings.filterwarnings("ignore", message="The 'encoding' parameter.*")
warnings.filterwarnings("ignore", message="The 'bits_per_sample' parameter.*")

TEMP_FOLDER = "temp_wav"

def prepare_audio(input_path):
    """Convert input to WAV and normalize loudness."""
    os.makedirs(TEMP_FOLDER, exist_ok=True)
    base_name = os.path.splitext(os.path.basename(input_path))[0]
    wav_path = os.path.join(TEMP_FOLDER, f"{base_name}_prep.wav")
    
    ffmpeg_cmd = [
        "ffmpeg", "-y", "-i", input_path,
        "-ar", "44100", "-ac", "2", wav_path
    ]
    subprocess.run(ffmpeg_cmd, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    
    norm_path = os.path.join(TEMP_FOLDER, f"{base_name}_norm.wav")
    ffmpeg_norm_cmd = [
        "ffmpeg", "-y", "-i", wav_path,
        "-af", "loudnorm", norm_path
    ]
    subprocess.run(ffmpeg_norm_cmd, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    
    return norm_path

def get_audio_duration(wav_path):
    cmd = [
        "ffprobe", "-v", "error", "-show_entries",
        "format=duration", "-of", "default=noprint_wrappers=1:nokey=1", wav_path
    ]
    result = subprocess.run(cmd, capture_output=True, text=True)
    return float(result.stdout.strip())

def split_audio(wav_path, max_length):
    duration = get_audio_duration(wav_path)
    if duration <= max_length:
        return [wav_path]
    
    chunks = []
    num_chunks = int(duration // max_length) + 1
    base_name = os.path.splitext(os.path.basename(wav_path))[0]
    
    for i in range(num_chunks):
        start = i * max_length
        chunk_file = os.path.join(TEMP_FOLDER, f"{base_name}_chunk{i}.wav")
        cmd = [
            "ffmpeg", "-y", "-i", wav_path,
            "-ss", str(start),
            "-t", str(max_length),
            chunk_file
        ]
        subprocess.run(cmd, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        chunks.append(chunk_file)
    return chunks

def concatenate_stems(chunk_folders, final_output_folder):
    """
    Concatenate stems from multiple chunk folders into final stems per track.
    chunk_folders: list of Demucs output folders for each chunk.
    final_output_folder: where the concatenated stems will be saved.
    """
    os.makedirs(final_output_folder, exist_ok=True)
    
    if not chunk_folders:
        return
    
    # Find stem names from the first chunk (looking for .wav files)
    stem_files = [f for f in os.listdir(chunk_folders[0]) if f.endswith(".wav")]
    stem_names = [os.path.splitext(f)[0] for f in stem_files]
    
    for stem in stem_names:
        stem_filename = f"{stem}.wav"
        stem_chunks = []
        
        for folder in chunk_folders:
            chunk_path = os.path.join(folder, stem_filename)
            if os.path.exists(chunk_path):
                stem_chunks.append(chunk_path)
        
        if not stem_chunks:
            continue
        
        # Create a text file for ffmpeg concatenation
        list_file = os.path.join(TEMP_FOLDER, f"{stem}_files.txt")
        with open(list_file, "w") as f:
            for chunk_path in stem_chunks:
                f.write(f"file '{os.path.abspath(chunk_path)}'\n")
        
        out_file = os.path.join(final_output_folder, stem_filename)
        
        cmd = [
            "ffmpeg", "-y", "-f", "concat", "-safe", "0", "-i", list_file, "-c", "copy", out_file
        ]
        subprocess.run(cmd, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

def separate_stems(input_path, output_folder="stems_output", use_cuda=True,
                   model_name="htdemucs_ft", shifts=10, overlap=0.5,
                   float32=True, two_stems=None, max_chunk_length=300):
    """Separate stems from a single audio file, with chunking and recombination."""
    os.makedirs(output_folder, exist_ok=True)
    device = "cuda" if (use_cuda and torch.cuda.is_available()) else "cpu"
    
    prepared_file = prepare_audio(input_path)
    chunks = split_audio(prepared_file, max_chunk_length)
    
    chunk_output_folders = []
    for i, chunk in enumerate(chunks):
        chunk_folder = os.path.join(TEMP_FOLDER, f"chunk_{i}_out")
        os.makedirs(chunk_folder, exist_ok=True)
        
        cmd = [
            "-n", model_name,
            "-o", chunk_folder,
            "--shifts", str(shifts),
            "--overlap", str(overlap)
        ]
        if float32:
            cmd.append("--float32")
        if two_stems:
            cmd.extend(["--two-stems", two_stems])
        cmd.append(chunk)
        separate.main(cmd)
        
        # Demucs output structure: output_folder/model_name/track_name/
        chunk_base_name = os.path.splitext(os.path.basename(chunk))[0]
        actual_output_path = os.path.join(chunk_folder, model_name, chunk_base_name)
        chunk_output_folders.append(actual_output_path)
    
    # Concatenate stems
    concatenate_stems(chunk_output_folders, output_folder)

def process_path(path, output_base="stems_output", in_place_subdir=None, **kwargs):
    """Process a file or folder recursively."""
    if os.path.isfile(path):
        track_name = os.path.splitext(os.path.basename(path))[0]
        if in_place_subdir:
            out_folder = os.path.join(os.path.dirname(path), in_place_subdir, track_name)
        else:
            out_folder = os.path.join(output_base, track_name)
        separate_stems(path, output_folder=out_folder, **kwargs)
    elif os.path.isdir(path):
        # Resolve exclusion paths
        abs_temp = os.path.abspath(TEMP_FOLDER)
        abs_output = os.path.abspath(output_base) if not in_place_subdir else None
        
        for root, dirs, files in os.walk(path):
            # Prune directories to prevent recursion
            # Use a copy of dirs to iterate safely while modifying it in place
            for d in list(dirs):
                d_abs = os.path.abspath(os.path.join(root, d))
                
                # Exclude temporary folder
                if d_abs == abs_temp:
                    dirs.remove(d)
                    continue
                
                # Exclude output folder (if not in-place)
                if abs_output and d_abs == abs_output:
                    dirs.remove(d)
                    continue
                
                # Exclude in-place subdirectory if it matches the name
                if in_place_subdir and d == in_place_subdir:
                    dirs.remove(d)
                    continue
                    
                # Exclude common hidden/system directories
                if d.startswith('.') or d == "__pycache__":
                    dirs.remove(d)
                    continue

            for f in files:
                if f.lower().endswith((".mp3", ".wav", ".flac")):
                    file_path = os.path.join(root, f)
                    
                    # Double check if file is inside an excluded path (in case root started inside one)
                    file_abs = os.path.abspath(file_path)
                    if file_abs.startswith(abs_temp):
                        continue
                    if abs_output and file_abs.startswith(abs_output):
                        continue
                    
                    track_name = os.path.splitext(f)[0]
                    if in_place_subdir:
                        out_folder = os.path.join(root, in_place_subdir, track_name)
                    else:
                        rel_path = os.path.relpath(root, path)
                        out_folder = os.path.join(output_base, rel_path, track_name)
                    separate_stems(file_path, output_folder=out_folder, **kwargs)
    else:
        print(f"Path does not exist: {path}")
        return
    
    # Cleanup temporary folder
    if os.path.exists(TEMP_FOLDER):
        shutil.rmtree(TEMP_FOLDER)

if __name__ == "__main__":
    args = docopt(__doc__)
    
    input_path = args["<input_path>"]
    output_base = args["--output"]
    in_place_subdir = args["--in-place-subdir"]
    model_name = args["--model"]
    shifts = int(args["--shifts"])
    overlap = float(args["--overlap"])
    float32 = args["--fp32"]
    two_stems = args["--two-stems"] if args["--two-stems"] != "none" else None
    use_cuda = not args["--no-cuda"]
    max_chunk_length = float(args["--max-chunk-length"] or 300)
    
    process_path(
        input_path,
        output_base=output_base,
        in_place_subdir=in_place_subdir,
        use_cuda=use_cuda,
        model_name=model_name,
        shifts=shifts,
        overlap=overlap,
        float32=float32,
        two_stems=two_stems,
        max_chunk_length=max_chunk_length
    )
    
    if in_place_subdir:
        print(f"Separation complete. Stems saved in subdirectories named '{in_place_subdir}' relative to inputs.")
    else:
        print(f"Separation complete. Stems saved in: {output_base}")
