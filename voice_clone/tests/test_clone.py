"""
Smoke tests for voice_clone project.

Run from project root:
    python tests/test_clone.py

Tests:
1. CUDA availability assertion
2. preprocess.py on a sample WAV (skipped if voices/ is empty)
3. synthesize() with a short reference WAV (skipped if voices/ is empty)
"""

import os
import sys
import tempfile
import unittest

# Allow importing from project root
sys.path.insert(0, os.path.dirname(os.path.dirname(__file__)))


class TestCUDA(unittest.TestCase):
    def test_cuda_available(self):
        import torch
        self.assertTrue(
            torch.cuda.is_available(),
            "CUDA must be available. Run `nvidia-smi` in WSL to verify GPU access.",
        )


class TestPreprocess(unittest.TestCase):
    def _first_voice(self):
        voices_dir = os.path.join(os.path.dirname(os.path.dirname(__file__)), "voices")
        wavs = [f for f in os.listdir(voices_dir) if f.endswith(".wav")]
        return os.path.join(voices_dir, wavs[0]) if wavs else None

    def test_preprocess_smoke(self):
        wav = self._first_voice()
        if wav is None:
            self.skipTest("No WAV files found in voices/ — add a reference WAV to run this test.")

        from preprocess import preprocess

        with tempfile.NamedTemporaryFile(suffix=".wav", delete=False) as f:
            out_path = f.name
        try:
            result = preprocess(wav, out_path)
            self.assertTrue(os.path.exists(result), "Preprocessed WAV was not created.")
            self.assertGreater(os.path.getsize(result), 0, "Preprocessed WAV is empty.")
        finally:
            if os.path.exists(out_path):
                os.unlink(out_path)


class TestSynthesize(unittest.TestCase):
    def _first_voice(self):
        voices_dir = os.path.join(os.path.dirname(os.path.dirname(__file__)), "voices")
        wavs = [f for f in os.listdir(voices_dir) if f.endswith(".wav")]
        return os.path.join(voices_dir, wavs[0]) if wavs else None

    def test_synthesize_smoke(self):
        wav = self._first_voice()
        if wav is None:
            self.skipTest("No WAV files found in voices/ — add a reference WAV to run this test.")

        from clone import synthesize

        with tempfile.NamedTemporaryFile(suffix=".wav", delete=False) as f:
            out_path = f.name
        try:
            result = synthesize(
                text="Hello, this is a voice cloning smoke test.",
                reference_wav=wav,
                output_path=out_path,
            )
            self.assertTrue(os.path.exists(result), "Output WAV was not created.")
            self.assertGreater(os.path.getsize(result), 0, "Output WAV is empty.")
        finally:
            if os.path.exists(out_path):
                os.unlink(out_path)


if __name__ == "__main__":
    unittest.main(verbosity=2)
