package dev.butterbot.client.render;

import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import dev.butterbot.client.skin.ClientSkinManager;
import dev.butterbot.entity.BotTier;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.List;

/** Player-like render state plus the bot's skin, neko parts and labels. */
public class ButterBotRenderState extends AvatarRenderState {
	public Identifier texture = ClientSkinManager.DEFAULT_SKIN;
	public boolean slim = true;
	public boolean neko;
	/** ARGB colour of the cat ears and tail, taken from the skin's hair. */
	public int nekoColor = 0xFF3B2A20;
	public boolean working;
	/** Looks earned with levels: collar bell, tail band, sparkles, and two tails at level 30. */
	public BotTier tier = BotTier.KITTEN;
	/** Ticks into the level-up cheer, or -1 when it is not playing. */
	public float levelUpTime = -1.0F;
	/** Asked to be shown ("Show" in the panel): glows through walls and sends a beam up. */
	public boolean focused;
	/** The beam's colour (the glow colour) and its animation clock, which loops every 40 ticks. */
	public int focusColor;
	public float beamTime;
	/** The bond reaction playing (pat, pout, treat; see ButterBotEntity.REACTION_*), ticks into it (or -1) and how strongly (0 to 1). */
	public int reaction;
	public float reactionTime = -1.0F;
	public float reactionStrength;
	/** The speech bubble, wrapped into lines, shown over the name tag; empty when the bot is quiet or far away. */
	public List<Component> speechLines = List.of();
}
