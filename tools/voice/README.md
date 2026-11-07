# Butter Bot voice

Reads AI answers aloud in the bot's own voice: Edge "Gadis" (Indonesian) speech, turned into the bot's voice
with OpenVoice v2 using the clips in `src/main/resources/assets/butterbot/sounds`.

Setup (once), next to this repository in `Downloads/butterbot-voice`:

    python -m venv venv
    venv/Scripts/python -m pip install torch --index-url https://download.pytorch.org/whl/cpu
    venv/Scripts/python -m pip install edge-tts librosa soundfile numpy scipy huggingface_hub
    git clone --depth 1 https://github.com/myshell-ai/OpenVoice.git
    venv/Scripts/python -c "from huggingface_hub import hf_hub_download as d; [d('myshell-ai/OpenVoiceV2', f, local_dir='ckpt') for f in ['converter/config.json','converter/checkpoint.pth']]"
    copy server.py from here

The mod starts `server.py` by itself (paths in `config/butterbot-voice.json`). `python server.py --test` writes test.wav.
