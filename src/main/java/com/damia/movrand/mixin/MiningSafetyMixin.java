package com.damia.movrand.mixin;

import com.damia.movrand.MineSafety;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MultiPlayerGameMode.class)
public abstract class MiningSafetyMixin {
	@Inject(method = {"startDestroyBlock", "continueDestroyBlock"}, at = @At("HEAD"), cancellable = true)
	private void movrand$checkLive(BlockPos pos, Direction face, CallbackInfoReturnable<Boolean> cir) {
		if (!MineSafety.mayStartBreaking(pos)) cir.setReturnValue(false);
	}
}
