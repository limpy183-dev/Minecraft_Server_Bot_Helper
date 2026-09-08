package com.damia.movrand.mixin;

import baritone.api.pathing.movement.ActionCosts;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.movement.movements.MovementAscend;
import com.damia.movrand.MineSafety;
import com.damia.movrand.NativeNavigation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = MovementAscend.class, remap = false)
public abstract class BaritoneAscendSafetyMixin {
	@Inject(method = "cost", at = @At("RETURN"), cancellable = true)
	private static void movrand$landing(CalculationContext ctx, int x, int y, int z, int destX, int destZ, CallbackInfoReturnable<Double> cir) {
		if (NativeNavigation.activeConfig() != null && cir.getReturnValue() < ActionCosts.COST_INF
				&& !NativeNavigation.clearLanding((px, py, pz) -> MineSafety.classify(ctx.get(px, py, pz)), destX, y + 1, destZ))
			cir.setReturnValue(ActionCosts.COST_INF);
	}
}
