"""Butter Bot voice program, on 127.0.0.1:47321.

POST /tts {"text": "...", "style": "jepang"|"indonesia"} returns a WAV in the bot's own voice:
  jepang:    the Indonesian text is written in katakana and read by Edge's Japanese "Nanami", so it sounds like a
             Japanese anime girl speaking Indonesian; then OpenVoice turns it into the bot's voice.
  indonesia: read by Edge's Indonesian "Gadis", then turned into the bot's voice.
POST /stt (a WAV) returns {"text": "..."}: what was said, recognized on this machine with Whisper (small).
GET /ping answers "ok". The mod starts this program by itself and stops it with the game.
`python server.py --test` writes test_jepang.wav and test_indonesia.wav.
"""
import asyncio
import io
import json
import os
import re
import sys
import tempfile
import threading
import types
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "OpenVoice"))
# Only the tone colour converter is used: its text frontend (Chinese/English tools) is not needed.
sys.modules["openvoice.text"] = types.SimpleNamespace(text_to_sequence=None)
# No watermark (its model would be another download); the constructor can't be told so, hence the stand-in.
sys.modules["wavmark"] = types.SimpleNamespace(load_model=lambda: types.SimpleNamespace(to=lambda device: None))

import edge_tts  # noqa: E402
import soundfile  # noqa: E402
import torch  # noqa: E402
from openvoice.api import ToneColorConverter  # noqa: E402

PORT = 47321
DEFAULT_STYLE = "jepang"
VOICES = {
    # style: (Edge voice, pitch, rate)
    "jepang": ("ja-JP-NanamiNeural", "+10Hz", "+0%"),
    "indonesia": ("id-ID-GadisNeural", "+15Hz", "+5%"),
}
SOUNDS = os.path.join(HERE, "..", "butterbot-fabric", "src", "main", "resources", "assets", "butterbot", "sounds")

converter = ToneColorConverter(os.path.join(HERE, "ckpt", "converter", "config.json"), device="cpu")
converter.watermark_model = None
converter.load_ckpt(os.path.join(HERE, "ckpt", "converter", "checkpoint.pth"))
lock = threading.Lock()

# ---------------------------------------------------------------------------- Indonesian -> katakana

KANA = {
    "": "アイウエオ", "k": "カキクケコ", "g": "ガギグゲゴ", "s": "サシスセソ", "z": "ザジズゼゾ",
    "t": ["タ", "ティ", "トゥ", "テ", "ト"], "d": ["ダ", "ディ", "ドゥ", "デ", "ド"], "n": "ナニヌネノ",
    "h": "ハヒフヘホ", "b": "バビブベボ", "p": "パピプペポ", "m": "マミムメモ", "r": "ラリルレロ",
    "y": ["ヤ", "イ", "ユ", "イェ", "ヨ"], "w": ["ワ", "ウィ", "ウ", "ウェ", "ウォ"],
    "c": ["チャ", "チ", "チュ", "チェ", "チョ"], "j": ["ジャ", "ジ", "ジュ", "ジェ", "ジョ"],
    "ny": ["ニャ", "ニ", "ニュ", "ニェ", "ニョ"], "sy": ["シャ", "シ", "シュ", "シェ", "ショ"],
    "f": ["ファ", "フィ", "フ", "フェ", "フォ"], "ng": ["ンガ", "ンギ", "ング", "ンゲ", "ンゴ"],
}
SAME = {"l": "r", "v": "f", "q": "k", "x": "s", "kh": "h"}
# A consonant closing a syllable: the closest Japanese sound.
CODA = {"n": "ン", "ng": "ン", "m": "ン", "k": "ッ", "t": "ッ", "p": "ップ", "h": "", "r": "ル", "s": "ス",
        "b": "ブ", "d": "ド", "g": "グ", "f": "フ", "c": "チ", "j": "ジ", "y": "イ", "w": "ウ", "z": "ズ", "ny": "ン", "sy": "シュ"}
UNITS = ["nol", "satu", "dua", "tiga", "empat", "lima", "enam", "tujuh", "delapan", "sembilan", "sepuluh", "sebelas"]


def number_words(n):
    if n < 12:
        return UNITS[n]
    if n < 20:
        return UNITS[n - 10] + " belas"
    if n < 100:
        return UNITS[n // 10] + " puluh" + ("" if n % 10 == 0 else " " + UNITS[n % 10])
    if n < 200:
        return "seratus" + ("" if n == 100 else " " + number_words(n - 100))
    if n < 1000:
        return UNITS[n // 100] + " ratus" + ("" if n % 100 == 0 else " " + number_words(n % 100))
    if n < 2000:
        return "seribu" + ("" if n == 1000 else " " + number_words(n - 1000))
    if n < 1000000:
        return number_words(n // 1000) + " ribu" + ("" if n % 1000 == 0 else " " + number_words(n % 1000))
    return str(n)


def word_kana(word):
    out = []
    i = 0
    vowels = "aiueo"
    while i < len(word):
        cons = ""
        for size in (2, 1):
            part = word[i:i + size]
            if size == 2 and part in ("ng", "ny", "sy", "kh"):
                cons = part
                break
            if size == 1 and part not in vowels:
                cons = part
        i += len(cons)
        cons = SAME.get(cons, cons)
        if i < len(word) and word[i] in vowels:
            vowel = vowels.index(word[i])
            i += 1
            row = KANA.get(cons, KANA["k"] if cons not in KANA else None)
            out.append(row[vowel])
        elif cons:
            # A closing consonant, or two in a row ("tr", "pr"): the first gets a soft "u" (or "o" after t/d).
            followed = i < len(word)
            if followed and cons in ("n", "m", "ng", "ny"):
                out.append("ン")
            elif followed:
                out.append({"t": "ト", "d": "ド"}.get(cons, KANA.get(cons, KANA["k"])[2]))
            else:
                out.append(CODA.get(cons, ""))
    return "".join(out)


def to_katakana(text):
    text = re.sub(r"\d+", lambda m: number_words(int(m.group())), text.lower())
    text = text.replace("~", "ー")
    parts = re.split(r"([a-z]+)", text)
    kana = "".join(word_kana(p) + "・" if re.fullmatch(r"[a-z]+", p) else p for p in parts)
    kana = kana.replace("・ ", " ").replace("・", "")
    return kana.replace(",", "、").replace(".", "。").replace("!", "！").replace("?", "？")


# ---------------------------------------------------------------------------- speech

def edge_wav(text, path, style):
    voice, pitch, rate = VOICES[style]
    spoken = to_katakana(text) if style == "jepang" else text

    async def run():
        await edge_tts.Communicate(spoken, voice, rate=rate, pitch=pitch).save(path + ".mp3")
    asyncio.run(run())
    audio, sample_rate = soundfile.read(path + ".mp3")
    soundfile.write(path, audio, sample_rate)
    os.remove(path + ".mp3")


def embedding(name, make_paths):
    """A voice's tone colour, worked out once and kept in se_<name>.pt."""
    cache = os.path.join(HERE, "se_" + name + ".pt")
    if os.path.exists(cache):
        return torch.load(cache)
    wavs = make_paths(tempfile.mkdtemp())
    se = converter.extract_se(wavs)
    torch.save(se, cache)
    return se


def bot_wavs(tmp):
    out = []
    for folder in (SOUNDS, os.path.join(SOUNDS, "voice")):
        for name in sorted(os.listdir(folder)):
            if name.endswith(".ogg") and name.startswith("voice_"):
                audio, sample_rate = soundfile.read(os.path.join(folder, name))
                path = os.path.join(tmp, name[:-4] + ".wav")
                soundfile.write(path, audio, sample_rate)
                out.append(path)
    return out


SAMPLE_LINES = [
    "Halo! Senang sekali bertemu denganmu hari ini.",
    "Aku sudah selesai menambang, tasku penuh dengan besi dan batu bara.",
    "Hati-hati ya, ada monster di belakangmu! Aku akan melindungimu.",
    "Malam ini bintangnya indah sekali, ayo kita lihat bersama.",
    "Terima kasih camilannya, aku jadi semangat lagi bekerja!",
]


def style_wavs(style):
    def make(tmp):
        out = []
        for i, line in enumerate(SAMPLE_LINES):
            path = os.path.join(tmp, f"{style}{i}.wav")
            edge_wav(line, path, style)
            out.append(path)
        return out
    return make


bot_se = embedding("bot", bot_wavs)
style_se = {"indonesia": embedding("gadis", style_wavs("indonesia")), "jepang": embedding("nanami", style_wavs("jepang"))}


def speak(text, style):
    style = style if style in VOICES else DEFAULT_STYLE
    with lock:
        tmp = tempfile.mkdtemp()
        src = os.path.join(tmp, "src.wav")
        out = os.path.join(tmp, "out.wav")
        edge_wav(text, src, style)
        converter.convert(audio_src_path=src, src_se=style_se[style], tgt_se=bot_se, output_path=out)
        with open(out, "rb") as f:
            data = f.read()
        for path in (src, out):
            os.remove(path)
        return data


# ---------------------------------------------------------------------------- listening

whisper = None
whisper_ready = threading.Event()


def load_whisper():
    global whisper
    from faster_whisper import WhisperModel
    whisper = WhisperModel(os.path.join(HERE, "ckpt", "whisper-small"), device="cpu", compute_type="int8")
    whisper_ready.set()


def transcribe(wav_bytes):
    whisper_ready.wait(120)
    # Decoded here, not by faster-whisper: its audio decoder does not match the installed PyAV.
    audio, sample_rate = soundfile.read(io.BytesIO(wav_bytes), dtype="float32")
    if audio.ndim > 1:
        audio = audio.mean(axis=1)
    if sample_rate != 16000:
        import librosa
        audio = librosa.resample(audio, orig_sr=sample_rate, target_sr=16000)
    segments, _ = whisper.transcribe(audio, language="id", beam_size=1, vad_filter=True)
    return " ".join(segment.text.strip() for segment in segments).strip()


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        self.send_response(200 if self.path == "/ping" else 404)
        self.end_headers()
        self.wfile.write(b"ok")

    def do_POST(self):
        try:
            body = self.rfile.read(int(self.headers.get("Content-Length", 0)))
            if self.path == "/tts":
                request = json.loads(body.decode("utf-8"))
                text = request.get("text", "").strip()[:1000]
                data = speak(text, request.get("style", DEFAULT_STYLE)) if text else b""
                kind = "audio/wav"
            elif self.path == "/stt":
                data = json.dumps({"text": transcribe(body)}, ensure_ascii=False).encode("utf-8")
                kind = "application/json"
            else:
                self.send_response(404)
                self.end_headers()
                return
            self.send_response(200 if data else 400)
            self.send_header("Content-Type", kind)
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)
        except Exception as e:  # keep serving after one bad request
            print("request failed:", repr(e), flush=True)
            self.send_response(500)
            self.end_headers()

    def log_message(self, fmt, *args):
        pass


if __name__ == "__main__":
    if "--test" in sys.argv:
        line = "Halo, aku Kurumi! Hari ini aku menambang 32 berlian untukmu, nya~ Ayo kita pulang bersama!"
        print(to_katakana(line))
        for style in VOICES:
            with open(os.path.join(HERE, f"test_{style}.wav"), "wb") as f:
                f.write(speak(line, style))
        print("wrote test_jepang.wav and test_indonesia.wav")
    else:
        threading.Thread(target=load_whisper, daemon=True).start()
        print("Butter Bot voice ready on port", PORT, flush=True)
        ThreadingHTTPServer(("127.0.0.1", PORT), Handler).serve_forever()
