# looks-face (bot face expressions)

Status: DONE, not compiled (integrator compiles).

Done:
- New `src/client/java/dev/butterbot/client/render/FaceLayer.java`, registered in `ButterBotRenderer` after NekoLayer.
  Flat quads on the head face (head pixel space, z -4.09..-4.11) via `submitCustomGeometry`.
  - talking mouth (`state.mouthOpen`, from speech text change tracked per bot in `ButterBotRenderer.lastSpeech`)
  - heart eyes (HEART), wide eyes (SURPRISE), dizzy spirals (QUESTION), angry brows (ANGER), ^ ^ eyes (MUSIC/CHECK, needs faceColor)
  - blush (HEART emote or REACTION_PET)
  - sweat drop (lowHealth), tears (scared = just hurt; no sad emote exists)
  - glowing eyes in dark (`state.inDark`, block/sky light minus sky darken <= 4) via `RenderTypes.eyes`
- New white 4x4 texture `src/main/resources/assets/butterbot/textures/entity/face.png`.
- `ButterBotRenderState`: added `mouthOpen`, `inDark`.

Skipped: glance toward players (already done by LookAtPlayerGoal 8 blocks on server).

Left: in-game check of placement on real skins.
