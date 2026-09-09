package com.damia.movrand.mixin;

import baritone.api.pathing.movement.ActionCosts;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.movement.movements.MovementTraverse;
import com.damia.movrand.MineSafety;
import com.damia.movrand.NativeNavigation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Walking can overhang a lava edge just as a jump landing can. */
@Mixin(value = MovementTraverse.class, remap = false)
public abstract class BaritoneTraverseSafetyMixin {
	@Inject(method = "cost", at = @At("RETURN"), cancellable = true)
	private static void movrand$landing(CalculationContext ctx, int x, int y, int z, int destX, int destZ, CallbackInfoReturnable<Double> cir) {
		if (NativeNavigation.activeConfig() != null && cir.getReturnValue() < ActionCosts.COST_INF
				&& !NativeNavigation.clearLanding((px, py, pz) -> MineSafety.classify(ctx.get(px, py, pz)), destX, y, destZ))
			cir.setReturnValue(ActionCosts.COST_INF);
	}
}
