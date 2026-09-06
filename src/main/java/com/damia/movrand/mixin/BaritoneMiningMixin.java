package com.damia.movrand.mixin;

import baritone.pathing.movement.MovementHelper;
import baritone.utils.BlockStateInterface;
import com.damia.movrand.MineSafety;
import com.damia.movrand.NativeNavigation;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = MovementHelper.class, remap = false)
public interface BaritoneMiningMixin {
	@Inject(method = "avoidBreaking", at = @At("HEAD"), cancellable = true)
	private static void movrand$protect(BlockStateInterface world, int x, int y, int z, BlockState state, CallbackInfoReturnable<Boolean> cir) {
		var cfg = NativeNavigation.activeConfig();
		if (cfg != null && cfg.protectMiningDrops && !MineSafety.inspect(
				(px, py, pz) -> MineSafety.classify(world.get0(px, py, pz)), new BlockPos(x, y, z), cfg.dropSafetyDepth).safe()) cir.setReturnValue(true);
	}
}
