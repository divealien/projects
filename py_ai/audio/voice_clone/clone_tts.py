from TTS.api import TTS

tts = TTS("tts_models/multilingual/multi-dataset/xtts_v2", gpu=True)

tts.tts_to_file(
    text="Chapter three. The rain had stopped, but the silence remained.",
    speaker_wav="voice_012.wav",
    language="en",
    file_path="xtts_test.wav"
)