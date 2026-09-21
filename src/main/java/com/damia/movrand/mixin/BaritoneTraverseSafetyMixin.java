package com.damia.movrand.mixin;

import baritone.api.pathing.movement.ActionCosts;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.movement.movements.MovementTraverse;
import com.damia.movrand.MineSafety;
import com.damia.movrand.NativeNavigation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import baritone.pathing.movement.MovementHelper;
import baritone.api.utils.IPlayerContext;
import baritone.api.utils.BetterBlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** Walking can overhang a lava edge just as a jump landing can. */
@Mixin(value = MovementTraverse.class, remap = false)
public abstract class BaritoneTraverseSafetyMixin {
	// Honey supports walking, but suppresses jumps. Limit this exception to traverses
	// so the planner cannot also start offering impossible honey parkour or ascents.
	@Redirect(method = "cost", at = @At(value = "INVOKE", target = "Lbaritone/pathing/movement/MovementHelper;canWalkOn(Lbaritone/pathing/movement/CalculationContext;IIILnet/minecraft/world/level/block/state/BlockState;)Z"))
	private static boolean movrand$walkOnHoney(CalculationContext ctx, int x, int y, int z, BlockState state) {
		return NativeNavigation.activeConfig() != null && state.is(Blocks.HONEY_BLOCK)
				|| MovementHelper.canWalkOn(ctx, x, y, z, state);
	}

	@Redirect(method = "updateState", at = @At(value = "INVOKE", target = "Lbaritone/pathing/movement/MovementHelper;canWalkOn(Lbaritone/api/utils/IPlayerContext;Lbaritone/api/utils/BetterBlockPos;)Z"))
	private boolean movrand$honeySupport(IPlayerContext ctx, BetterBlockPos pos) {
		return NativeNavigation.activeConfig() != null && ctx.world().getBlockState(pos).is(Blocks.HONEY_BLOCK)
				|| MovementHelper.canWalkOn(ctx, pos);
	}

	@Inject(method = "cost", at = @At("RETURN"), cancellable = true)
	private static void movrand$landing(CalculationContext ctx, int x, int y, int z, int destX, int destZ, CallbackInfoReturnable<Double> cir) {
		if (NativeNavigation.activeConfig() == null || cir.getReturnValue() >= ActionCosts.COST_INF) return;
		BlockState support = ctx.get(destX, y - 1, destZ);
		if (!NativeNavigation.clearLanding((px, py, pz) -> MineSafety.classify(ctx.get(px, py, pz)), destX, y, destZ)
				|| (!NativeNavigation.normalJumpFrom(ctx, x, y, z)
				&& !movrand$walkOnHoney(ctx, destX, y - 1, destZ, support))) {
			cir.setReturnValue(ActionCosts.COST_INF);
			return;
		}
		if (support.is(Blocks.HONEY_BLOCK) || ctx.get(x, y - 1, z).is(Blocks.HONEY_BLOCK))
			cir.setReturnValue(cir.getReturnValue() / Blocks.HONEY_BLOCK.getSpeedFactor());
	}
}
