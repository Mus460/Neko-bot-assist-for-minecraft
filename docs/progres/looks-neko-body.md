# looks-neko-body

Done (not compiled yet, integrator compiles):
- NekoModel: tail puffs on hasRedOverlay too (scared/fighting already did); tail curls around the side when sitting (strong) or idle still (light).
- NekoModel: ears turn toward the local player while key.butterbot.talk is held within 16 blocks (KeyMapping.get by name, no BotKeys edit).
- BotPoses: curious head tilt on question emote, thinking, or speech lines.
- NekoModel: ears droop / tail hangs when wet (raining at bot = state.umbrella, or in water); slight perk in sunshine (bright day + sky light 14+).
- NekoModel: tail lags opposite the head-leads-body turn (state.yRot) and streams out flatter with walk speed.
- NekoModel: ahoge (HAIR group, hidden under a helmet), bobs with walk, flops when wet.

Skipped:
- Bond hop: bond is not synced to the client.
- Hostile-mob scan for tail puff: per-frame scan too costly; scared/fighting/hurt cover it.

Left: nothing.
