package com.damia.movrand.mixin;

import com.damia.movrand.MovRand;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientLevel.class)
public abstract class BlockEditConfirmationMixin {
	@Inject(method = "setServerVerifiedBlockState", at = @At("HEAD"))
	private void movrand$serverUpdate(BlockPos pos, BlockState state, int flags, CallbackInfo ci) {
		if (MovRand.controller() != null) MovRand.controller().destroyer.confirmEdit((ClientLevel) (Object) this, pos, state);
	}

	@Inject(method = "syncBlockState", at = @At("HEAD"))
	private void movrand$acknowledged(BlockPos pos, BlockState state, Vec3 playerPos, CallbackInfo ci) {
		if (MovRand.controller() != null) MovRand.controller().destroyer.confirmEdit((ClientLevel) (Object) this, pos, state);
	}
}
