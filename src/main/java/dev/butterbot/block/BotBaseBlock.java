package dev.butterbot.block;

import dev.butterbot.ClientHooks;
import dev.butterbot.entity.ButterBotEntity;
import dev.butterbot.util.Msg;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * The Bot Base: right-click opens the control panel for your bots, sneak + right-click opens
 * its storage. Placing one links your nearby bots to it.
 */
public class BotBaseBlock extends BaseEntityBlock {

	private static final VoxelShape SHAPE = Block.box(1.0D, 0.0D, 1.0D, 15.0D, 16.0D, 15.0D);

	public BotBaseBlock(Properties properties) {
		super(properties);
	}

	@Override
	public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
		return new BotBaseBlockEntity(pos, state);
	}

	@Override
	protected RenderShape getRenderShape(BlockState state) {
		return RenderShape.INVISIBLE;
	}

	@Override
	protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		return SHAPE;
	}

	@Override
	protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hitResult) {
		if (player.isShiftKeyDown()) {
			if (!level.isClientSide() && level.getBlockEntity(pos) instanceof BotBaseBlockEntity base) {
				player.openMenu(new SimpleMenuProvider((id, inventory, p) -> openStorage(id, inventory, base), base.getDisplayName()));
			}
			return InteractionResult.SUCCESS;
		}
		if (level.isClientSide()) {
			ClientHooks.openBaseScreen(pos);
		}
		return InteractionResult.SUCCESS;
	}

	private static AbstractContainerMenu openStorage(int id, net.minecraft.world.entity.player.Inventory inventory, BotBaseBlockEntity base) {
		return ChestMenu.threeRows(id, inventory, base);
	}

	@Override
	public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
		super.setPlacedBy(level, pos, state, placer, stack);
		if (level.isClientSide() || !(placer instanceof ServerPlayer player)) {
			return;
		}
		List<ButterBotEntity> bots = level.getEntitiesOfClass(ButterBotEntity.class, new AABB(pos).inflate(64.0D),
				bot -> bot.isOwnedBy(player));
		for (ButterBotEntity bot : bots) {
			bot.setBasePos(pos);
		}
		if (!bots.isEmpty()) {
			player.sendSystemMessage(Msg.prefixed(Component.translatable("butterbot.msg.base_linked", bots.size())));
		}
	}
}
