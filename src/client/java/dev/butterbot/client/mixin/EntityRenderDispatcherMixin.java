package dev.butterbot.client.mixin;

import dev.butterbot.client.render.ButterBotRenderState;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.EntityType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Map;

/**
 * A bot's render state is a player's (the player model needs one), and the game hands every player-style state to
 * the player renderer when it draws. Send a bot's to the bot renderer instead, which adds the cat ears and tail,
 * armor, held items, name line and animations.
 */
@Mixin(EntityRenderDispatcher.class)
public abstract class EntityRenderDispatcherMixin {

	@Shadow
	private Map<EntityType<?>, EntityRenderer<?, ?>> renderers;

	@Inject(method = "getRenderer(Lnet/minecraft/client/renderer/entity/state/EntityRenderState;)Lnet/minecraft/client/renderer/entity/EntityRenderer;",
			at = @At("HEAD"), cancellable = true)
	private void butterbot$botRenderer(EntityRenderState state, CallbackInfoReturnable<EntityRenderer<?, ?>> cir) {
		if (state instanceof ButterBotRenderState) {
			EntityRenderer<?, ?> renderer = renderers.get(state.entityType);
			if (renderer != null) {
				cir.setReturnValue(renderer);
			}
		}
	}
}
