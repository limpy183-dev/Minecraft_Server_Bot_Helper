package com.damia.movrand.mixin;

import baritone.behavior.InventoryBehavior;
import com.damia.movrand.Bot;
import com.damia.movrand.NativeNavigation;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import java.util.function.Predicate;

/** Item type allowlists alone do not protect a particular inventory slot. */
@Mixin(value = InventoryBehavior.class, remap = false)
public abstract class BaritoneInventoryMixin {
	@Inject(method = "throwaway(ZLjava/util/function/Predicate;Z)Z", at = @At("HEAD"), cancellable = true)
	private void movrand$slots(boolean select, Predicate<? super ItemStack> desired, boolean allowInventory, CallbackInfoReturnable<Boolean> cir) {
		var cfg = NativeNavigation.activeConfig();
		var player = Minecraft.getInstance().player;
		if (cfg == null || player == null) return;
		if (Bot.buildingBlockCount(player, cfg) <= cfg.bridgeKeepBlocks) { cir.setReturnValue(false); return; }
		for (int i = 0; i < 9; i++) {
			ItemStack stack = player.getInventory().getItem(i);
			if (!cfg.slotProtected(i) && Bot.usableBuildingStack(stack, cfg) && desired.test(stack)) {
				if (select) player.getInventory().setSelectedSlot(i);
				cir.setReturnValue(true);
				return;
			}
		}
		cir.setReturnValue(false);
	}
}
