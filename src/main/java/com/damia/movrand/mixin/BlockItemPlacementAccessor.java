package com.damia.movrand.mixin;

import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Use each item's vanilla placement rules, including wall torches/signs and multi-block items. */
@Mixin(BlockItem.class)
public interface BlockItemPlacementAccessor {
    @Invoker("getPlacementState") BlockState movrand$placementState(BlockPlaceContext context);
}
