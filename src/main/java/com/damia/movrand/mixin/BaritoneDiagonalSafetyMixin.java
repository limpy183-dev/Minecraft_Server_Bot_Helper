package com.damia.movrand.mixin;

import baritone.api.pathing.movement.ActionCosts;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.movement.movements.MovementDiagonal;
import baritone.utils.pathing.MutableMoveResult;
import com.damia.movrand.MineSafety;
import com.damia.movrand.NativeNavigation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = MovementDiagonal.class, remap = false)
public abstract class BaritoneDiagonalSafetyMixin {
	@Inject(method = "cost", at = @At("RETURN"))
	private static void movrand$landing(CalculationContext ctx, int x, int y, int z, int destX, int destZ, MutableMoveResult result, CallbackInfo ci) {
		if (NativeNavigation.activeConfig() != null && result.cost < ActionCosts.COST_INF
				&& !NativeNavigation.clearLanding((px, py, pz) -> MineSafety.classify(ctx.get(px, py, pz)), destX, result.y, destZ))
			result.cost = ActionCosts.COST_INF;
	}
}
