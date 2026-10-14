package dev.butterbot.client.render;

import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import dev.butterbot.client.skin.ClientSkinManager;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.Nullable;

/** Player-like render state plus the bot's skin, neko parts and labels. */
public class ButterBotRenderState extends AvatarRenderState {
	public Identifier texture = ClientSkinManager.DEFAULT_SKIN;
	public boolean slim = true;
	public boolean neko;
	/** ARGB colour of the cat ears and tail, taken from the skin's hair. */
	public int nekoColor = 0xFF3B2A20;
	public boolean working;
	@Nullable
	public Component infoLine;
	@Nullable
	public Component speech;
}
