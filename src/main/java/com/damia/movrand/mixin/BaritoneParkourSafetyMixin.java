package com.damia.movrand.mixin;

import baritone.api.pathing.movement.ActionCosts;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.movement.movements.MovementParkour;
import baritone.utils.pathing.MutableMoveResult;
import com.damia.movrand.MineSafety;
import com.damia.movrand.NativeNavigation;
import net.minecraft.core.Direction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = MovementParkour.class, remap = false)
public abstract class BaritoneParkourSafetyMixin {
	@Inject(method = "cost(Lbaritone/pathing/movement/CalculationContext;IIILnet/minecraft/core/Direction;Lbaritone/utils/pathing/MutableMoveResult;)V", at = @At("RETURN"))
	private static void movrand$landing(CalculationContext ctx, int x, int y, int z, Direction dir, MutableMoveResult result, CallbackInfo ci) {
		if (NativeNavigation.activeConfig() == null || result.cost >= ActionCosts.COST_INF) return;
		MineSafety.View view = (px, py, pz) -> ctx.bsi.worldContainsLoadedChunk(px, pz)
				? MineSafety.classify(ctx.get(px, py, pz)) : MineSafety.UNKNOWN;
		int distance = Math.abs(result.x - x) + Math.abs(result.z - z);
		// Include the approach, jump headroom and one cell of landing overshoot.
		for (int step = 1; step <= distance + 1; step++) {
			int px = x + dir.getStepX() * step, pz = z + dir.getStepZ() * step;
			if (!NativeNavigation.clearLanding(view, px, y, pz)
					|| !NativeNavigation.clearLanding(view, px, y + 1, pz)) {
				result.cost = ActionCosts.COST_INF;
				return;
			}
		}
	}
}
