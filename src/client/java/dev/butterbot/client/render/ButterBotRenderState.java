package dev.butterbot.client.render;

import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import dev.butterbot.client.skin.ClientSkinManager;
import dev.butterbot.entity.BotTier;
import net.minecraft.client.renderer.item.ItemStackRenderState;
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
	public int level = 1;
	/** Ticks into the level-up cheer, or -1 when it is not playing; the tier it reached and whether that is new. */
	public float levelUpTime = -1.0F;
	public BotTier levelUpTier = BotTier.KITTEN;
	public boolean levelUpTierUp;
	/** Mood, shown by the ears and tail: the owner close by, scared (just hurt), fighting, badly hurt. */
	public boolean ownerNear;
	public boolean scared;
	public boolean fighting;
	public boolean lowHealth;
	/** The skin tone for the eyelids (0: none), and how far they are closed (0 open, 0.5 a happy squint, 1 a blink). */
	public int faceColor;
	public float lidsClosed;
	/** Dressed for the weather: a scarf in the snow, sunglasses in the desert sun, an umbrella in the rain. */
	public boolean scarf;
	public boolean sunglasses;
	public boolean umbrella;
	/** The idle pose (ButterBotEntity.ACTION_*), and the one-off move playing (GESTURE_*), its time and strength. */
	public int action;
	public int gesture;
	public float gestureTime = -1.0F;
	public float gestureStrength;
	/** The emote icon over the head (ButterBotEntity.EMOTE_*) and ticks into it. */
	public int emote;
	public float emoteTime = -1.0F;
	/** An item in a thought bubble (something it needs), and whether there is one. */
	public final ItemStackRenderState thought = new ItemStackRenderState();
	public boolean thinking;
	/** Drawn on the panel card: no light pillar there. */
	public boolean inPanel;
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
	/** Hits of the running combo (shown from 2), stars past level 30, and health for the bar under the name. */
	public int combo;
	public int stars;
	public float healthFraction = 1.0F;
	public boolean showHealthBar;
}
