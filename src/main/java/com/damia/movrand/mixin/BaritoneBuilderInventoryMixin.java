package com.damia.movrand.mixin;

import baritone.process.BuilderProcess;
import com.damia.movrand.Bot;
import com.damia.movrand.NativeNavigation;
import net.minecraft.client.Minecraft;
import net.minecraft.core.NonNullList;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** The builder has its own inventory search in addition to InventoryBehavior.throwaway. */
@Mixin(value = BuilderProcess.class, remap = false)
public abstract class BaritoneBuilderInventoryMixin {
	@Redirect(method = "hasAnyItemThatWouldPlace", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/core/NonNullList;get(I)Ljava/lang/Object;"))
	private Object movrand$available(NonNullList<?> inventory, int slot) {
		Object value = inventory.get(slot);
		var cfg = NativeNavigation.activeConfig();
		var player = Minecraft.getInstance().player;
		if (cfg != null && player != null && (cfg.slotProtected(slot) || !cfg.pathBridge
				|| !(value instanceof ItemStack stack) || !Bot.usableBuildingStack(stack, cfg)
				|| Bot.buildingBlockCount(player, cfg) <= cfg.bridgeKeepBlocks)) return ItemStack.EMPTY;
		return value;
	}
}
