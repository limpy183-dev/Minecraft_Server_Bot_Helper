package com.damia.movrand.testmixin;

import com.damia.movrand.BasaltFarmGameTest;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(BlockItem.class)
public abstract class PlacementTraceMixin {
    @Inject(method="placeBlock",at=@At("RETURN"))
    private void basalt$placed(BlockPlaceContext ctx, BlockState state, CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValue()) BasaltFarmGameTest.placement(ctx.getLevel(),ctx.getClickedPos(),state);
    }
}
