package com.damia.movrand.mixin;

import com.damia.movrand.LitematicaBuilder;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A hypothetical approach must not be rejected because the real player has not moved yet. */
@Mixin(BlockItem.class)
public abstract class BlockItemPreviewMixin {
    @Inject(method = "canPlace", at = @At("HEAD"), cancellable = true)
    private void movrand$preview(BlockPlaceContext context, BlockState state, CallbackInfoReturnable<Boolean> ci) {
        if (LitematicaBuilder.previewContext(context)) ci.setReturnValue(state.canSurvive(context.getLevel(), context.getClickedPos()));
    }
}
