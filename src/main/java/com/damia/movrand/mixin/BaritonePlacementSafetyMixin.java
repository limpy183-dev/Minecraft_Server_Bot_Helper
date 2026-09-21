package com.damia.movrand.mixin;

import baritone.api.pathing.movement.ActionCosts;
import baritone.pathing.movement.CalculationContext;
import com.damia.movrand.MovRand;
import com.damia.movrand.NativeNavigation;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Scaffold routes cannot occupy cells reserved for schematic blocks. */
@Mixin(value = CalculationContext.class, remap = false)
public abstract class BaritonePlacementSafetyMixin {
    @Inject(method = "costOfPlacingAt", at = @At("HEAD"), cancellable = true)
    private void movrand$reserved(int x, int y, int z, BlockState state, CallbackInfoReturnable<Double> cir) {
        var cfg = NativeNavigation.activeConfig();
        if (cfg != null && cfg.builderEnabled && !MovRand.controller().builder.navigationMayPlace(new BlockPos(x, y, z)))
            cir.setReturnValue(ActionCosts.COST_INF);
    }
}
