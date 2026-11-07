# looks-particles

Done (not compiled yet, integrator compiles):
- New `entity/BotFx.java`: landing dust, overheat smoke (>5 min work, not FOLLOW), underwater bubbles, cold-biome breath (WHITE_SMOKE), red dust pulse <25% health, hearts walking near owner at bond >= FOND.
- `ButterBotEntity.tick()`: one line `BotFx.tick(this);` after `companion.tick(...)`.
- `BuildAction`: WAX_ON sparkle on blocks made for free; block-crack particles when natural terrain is silently cleared (destroyBlock already shows its own).

Skipped (already exist): forest fireflies (BotCompanion.fireflies), snow on bot (vanilla).
Left: nothing.
