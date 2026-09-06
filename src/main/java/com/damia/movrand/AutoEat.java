package com.damia.movrand;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.InteractionHand;

import java.util.Set;

/**
 * Eats when the hunger bar drops, then puts the old item back in hand.
 *
 * <p>Hotbar only. Moving a stack up from the backpack means faking container clicks, which
 * is a great deal of protocol for a mod that can simply tell you to keep food on the bar.
 */
public final class AutoEat {

	/** Food that costs more than it gives. */
	private static final Set<net.minecraft.world.item.Item> HARMFUL = Set.of(
			Items.ROTTEN_FLESH, Items.SPIDER_EYE, Items.POISONOUS_POTATO, Items.PUFFERFISH,
			Items.CHICKEN, Items.SUSPICIOUS_STEW, Items.CHORUS_FRUIT);

	private final Config cfg;

	private int restoreSlot = -1;
	private int eatingTicks;
	private boolean eating;
	private double startYaw, startPitch;
	double lookYaw, lookPitch;
	private int wobbleTicks;
	public String status = "idle";

	public AutoEat(Config cfg) {
		this.cfg = cfg;
	}

	public boolean isEating() {
		return eating;
	}

	/**
	 * @return true while eating, so the caller can drop sprint and hold still.
	 */
	public boolean tick(Minecraft mc, LocalPlayer player) {
		if (!cfg.autoEatEnabled) {
			if (eating) finish(mc, player);
			status = "off";
			return false;
		}
		// right-click while a screen is open would click the screen instead
		if (mc.gui.screen() != null) {
			if (eating) finish(mc, player);
			return false;
		}

		int food = player.getFoodData().getFoodLevel();

		if (!eating) {
			if (food > cfg.autoEatThreshold) {
				status = "not hungry (" + food + "/20)";
				return false;
			}
			int slot = bestFoodSlot(player);
			if (slot < 0) {
				status = "no food on the hotbar";
				return false;
			}
			restoreSlot = player.getInventory().getSelectedSlot();
			player.getInventory().setSelectedSlot(slot);
			eating = true;
			eatingTicks = 0;
			startYaw = lookYaw = player.getYRot();
			startPitch = lookPitch = player.getXRot();
			wobbleTicks = 0;
			status = "eating " + player.getInventory().getItem(slot).getHoverName().getString();
		}

		eatingTicks++;

		// full, out of food, or something went wrong and we are just holding right-click
		boolean full = food >= 20;
		boolean gone = !isEdible(player.getInventory().getSelectedItem());
		if (full || gone || eatingTicks > cfg.autoEatMaxTicks) {
			finish(mc, player);
			status = full ? "full" : gone ? "finished the stack" : "gave up waiting";
			return false;
		}

		if (--wobbleTicks <= 0) {
			lookYaw = startYaw + Rng.range(-1.5, 1.5);
			lookPitch = Math.max(-90, Math.min(90, startPitch + Rng.range(-1, 1)));
			wobbleTicks = 8 + Rng.nextInt(9);
		}
		return true;
	}

	void useFood(Minecraft mc, LocalPlayer player) {
		player.setSprinting(false);
		// Use the held food directly, bypassing block/entity right-click interactions.
		if (!player.isUsingItem() && mc.gameMode != null) {
			mc.gameMode.useItem(player, InteractionHand.MAIN_HAND);
		}
		mc.options.keyUse.setDown(player.isUsingItem());
	}

	private void finish(Minecraft mc, LocalPlayer player) {
		mc.options.keyUse.setDown(false);
		if (restoreSlot >= 0 && cfg.autoEatRestoreSlot) {
			player.getInventory().setSelectedSlot(restoreSlot);
		}
		restoreSlot = -1;
		eating = false;
		eatingTicks = 0;
	}

	/** Highest-nutrition edible on the hotbar, skipping the ones that poison you. */
	private int bestFoodSlot(LocalPlayer player) {
		Inventory inv = player.getInventory();
		int best = -1;
		int bestNutrition = -1;
		for (int slot = 0; slot < Inventory.SELECTION_SIZE; slot++) {
			ItemStack stack = inv.getItem(slot);
			if (!isEdible(stack)) continue;
			if (cfg.autoEatAvoidHarmful && HARMFUL.contains(stack.getItem())) continue;
			FoodProperties food = stack.get(DataComponents.FOOD);
			int nutrition = food == null ? 0 : food.nutrition();
			// do not burn a golden apple on a half-empty bar
			if (cfg.autoEatSaveGoldenApples
					&& (stack.is(Items.GOLDEN_APPLE) || stack.is(Items.ENCHANTED_GOLDEN_APPLE))) {
				continue;
			}
			if (nutrition > bestNutrition) {
				bestNutrition = nutrition;
				best = slot;
			}
		}
		return best;
	}

	private static boolean isEdible(ItemStack stack) {
		return !stack.isEmpty() && stack.has(DataComponents.FOOD);
	}

	/** True when the hotbar has nothing left to eat — worth logging once. */
	public boolean isOutOfFood(LocalPlayer player) {
		return cfg.autoEatEnabled
				&& player.getFoodData().getFoodLevel() <= cfg.autoEatThreshold
				&& bestFoodSlot(player) < 0;
	}
}
