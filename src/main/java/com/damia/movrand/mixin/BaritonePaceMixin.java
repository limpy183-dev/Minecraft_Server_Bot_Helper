package com.damia.movrand.mixin;

import baritone.pathing.path.PathExecutor;
import com.damia.movrand.NativeNavigation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = PathExecutor.class, remap = false)
public abstract class BaritonePaceMixin {
	@Inject(method = "shouldSprintNextTick", at = @At("RETURN"), cancellable = true)
	private void movrand$pace(CallbackInfoReturnable<Boolean> cir) {
		cir.setReturnValue(NativeNavigation.randomiseSprint((PathExecutor) (Object) this, cir.getReturnValue()));
	}
}
