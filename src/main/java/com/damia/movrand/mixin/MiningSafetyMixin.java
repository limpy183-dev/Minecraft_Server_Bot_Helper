package com.damia.movrand.mixin;

import com.damia.movrand.MineSafety;
import com.damia.movrand.MovRand;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MultiPlayerGameMode.class)
public abstract class MiningSafetyMixin {
	@Inject(method = "destroyBlock", at = @At("HEAD"))
	private void movrand$recordBreak(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level != null && MovRand.controller() != null && !mc.level.getBlockState(pos).isAir())
			MovRand.controller().destroyer.expectEdit(mc.level, pos, mc.level.getBlockState(pos).getBlock(), false);
	}

	@Inject(method = "useItemOn", at = @At("HEAD"))
	private void movrand$recordPlacement(LocalPlayer player, InteractionHand hand, BlockHitResult hit, CallbackInfoReturnable<InteractionResult> cir) {
		Minecraft mc = Minecraft.getInstance();
		var stack = player.getItemInHand(hand);
		if (mc.level != null && MovRand.controller() != null && stack.getItem() instanceof BlockItem item) {
			var context = item.updatePlacementContext(new BlockPlaceContext(player, hand, stack, hit));
			if (context != null && context.canPlace())
				MovRand.controller().destroyer.expectEdit(mc.level, context.getClickedPos(), item.getBlock(), true);
		}
	}

	@Inject(method = {"startDestroyBlock", "continueDestroyBlock"}, at = @At("HEAD"), cancellable = true)
	private void movrand$checkLive(BlockPos pos, Direction face, CallbackInfoReturnable<Boolean> cir) {
		if (!MineSafety.mayStartBreaking(pos)) cir.setReturnValue(false);
	}
}
