# looks-hud

Done (not compiled yet, integrator compiles):
- Mic indicator under crosshair (BotHud.drawTalk) using BotVoice.talkState()/micLevel(); "recognizing..." running dots.
- Build progress: HudSync overrides status ("butterbot.hud.s.build_progress") and progress bar for BUILD task (no new HudBot fields).
- Hologram hint top-centre (BotHud.drawHologramHint) using BuildPreview.ghostCount(); mark key via Component.keybind.
- Lang keys in id_id/en_us: butterbot.hud.s.build_progress, butterbot.hud.recognizing, butterbot.hud.hologram.

Skipped:
- Thinking dots: BotChat.PENDING is private and no status says "designing".
- Mood face: HudBot has no mood and entity has no mood getter.

Left: nothing.
