"""Butter Bot voice: text -> Indonesian speech (Edge "Gadis") -> the bot's own voice (OpenVoice v2 tone colour).

Runs on 127.0.0.1:47321. POST /tts {"text": "..."} returns a WAV; GET /ping answers "ok".
The mod starts this program by itself on the first AI answer and stops it with the game.
"""
import asyncio
import io
import json
import os
import sys
import tempfile
import threading
import types
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "OpenVoice"))
# Only the tone colour converter is used: its text frontend (Chinese/English tools) is not needed.
sys.modules["openvoice.text"] = types.SimpleNamespace(text_to_sequence=None)

import edge_tts  # noqa: E402
import soundfile  # noqa: E402
import torch  # noqa: E402
from openvoice.api import ToneColorConverter  # noqa: E402

PORT = 47321
VOICE = "id-ID-GadisNeural"
# A little higher and quicker than Gadis' natural voice, closer to a small neko bot.
PITCH = "+15Hz"
RATE = "+5%"
SOUNDS = os.path.join(HERE, "..", "butterbot-fabric", "src", "main", "resources", "assets", "butterbot", "sounds")

# No watermark (its model would be another download); the constructor can't be told so, hence the stand-in.
sys.modules["wavmark"] = types.SimpleNamespace(load_model=lambda: types.SimpleNamespace(to=lambda device: None))
converter = ToneColorConverter(os.path.join(HERE, "ckpt", "converter", "config.json"), device="cpu")
converter.watermark_model = None
converter.load_ckpt(os.path.join(HERE, "ckpt", "converter", "checkpoint.pth"))
lock = threading.Lock()


def edge_wav(text, path):
    async def run():
        await edge_tts.Communicate(text, VOICE, rate=RATE, pitch=PITCH).save(path + ".mp3")
    asyncio.run(run())
    audio, rate = soundfile.read(path + ".mp3")
    soundfile.write(path, audio, rate)
    os.remove(path + ".mp3")


def bot_clips():
    clips = []
    for folder in (SOUNDS, os.path.join(SOUNDS, "voice")):
        for name in sorted(os.listdir(folder)):
            if name.endswith(".ogg") and name.startswith("voice_"):
                clips.append(os.path.join(folder, name))
    return clips


def embedding(name, make_paths):
    """A voice's tone colour, worked out once and kept in se_<name>.pt."""
    cache = os.path.join(HERE, "se_" + name + ".pt")
    if os.path.exists(cache):
        return torch.load(cache)
    tmp = tempfile.mkdtemp()
    wavs = make_paths(tmp)
    se = converter.extract_se(wavs)
    torch.save(se, cache)
    return se


def bot_wavs(tmp):
    out = []
    for i, clip in enumerate(bot_clips()):
        audio, rate = soundfile.read(clip)
        path = os.path.join(tmp, f"bot{i}.wav")
        soundfile.write(path, audio, rate)
        out.append(path)
    return out


def gadis_wavs(tmp):
    lines = [
        "Halo! Senang sekali bertemu denganmu hari ini.",
        "Aku sudah selesai menambang, tasku penuh dengan besi dan batu bara.",
        "Hati-hati ya, ada monster di belakangmu! Aku akan melindungimu.",
        "Malam ini bintangnya indah sekali, ayo kita lihat bersama.",
        "Terima kasih camilannya, aku jadi semangat lagi bekerja!",
    ]
    out = []
    for i, line in enumerate(lines):
        path = os.path.join(tmp, f"gadis{i}.wav")
        edge_wav(line, path)
        out.append(path)
    return out


bot_se = embedding("bot", bot_wavs)
gadis_se = embedding("gadis", gadis_wavs)


def speak(text):
    with lock:
        tmp = tempfile.mkdtemp()
        src = os.path.join(tmp, "src.wav")
        out = os.path.join(tmp, "out.wav")
        edge_wav(text, src)
        converter.convert(audio_src_path=src, src_se=gadis_se, tgt_se=bot_se, output_path=out)
        with open(out, "rb") as f:
            data = f.read()
        for path in (src, out):
            os.remove(path)
        return data


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        self.send_response(200 if self.path == "/ping" else 404)
        self.end_headers()
        self.wfile.write(b"ok")

    def do_POST(self):
        if self.path != "/tts":
            self.send_response(404)
            self.end_headers()
            return
        try:
            length = int(self.headers.get("Content-Length", 0))
            text = json.loads(self.rfile.read(length).decode("utf-8")).get("text", "").strip()[:1000]
            data = speak(text) if text else b""
            self.send_response(200 if data else 400)
            self.send_header("Content-Type", "audio/wav")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)
        except Exception as e:  # keep serving after one bad request
            print("tts failed:", repr(e), flush=True)
            self.send_response(500)
            self.end_headers()

    def log_message(self, fmt, *args):
        pass


if __name__ == "__main__":
    if "--test" in sys.argv:
        with open(os.path.join(HERE, "test.wav"), "wb") as f:
            f.write(speak("Halo, aku Kurumi! Hari ini aku menambang banyak berlian untukmu, nya!"))
        print("wrote test.wav")
    else:
        print("Butter Bot voice ready on port", PORT, flush=True)
        ThreadingHTTPServer(("127.0.0.1", PORT), Handler).serve_forever()
