package com.damia.movrand;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The inventory: what is protected, what is rubbish, and what gets sold.
 *
 * <p>Slots are the unit rather than items, because that is how a person thinks about their
 * own inventory — "the row across the bottom and the two next to it are mine, do what you
 * like with the rest". An item rule cannot express "this particular stack of diamonds", and
 * a slot rule can.
 *
 * <p>Selling is a state machine rather than a burst of clicks. A server that opens a menu
 * does it a packet later, the deposit has to land before the confirm is worth pressing, and
 * a confirm pressed on a menu that has not repainted yet sells nothing. Every step waits,
 * and every wait is a randomised length.
 */
public final class Backpack {

	public enum Phase {
		IDLE("idle"), OPENING("waiting for the sell menu"), DEPOSITING("moving items in"),
		CONFIRMING("clicking confirm"), SETTLING("waiting for the sale"), CLOSING("closing");

		public final String label;

		Phase(String label) {
			this.label = label;
		}
	}

	private final Config cfg;

	private Phase phase = Phase.IDLE;
	private int waitTicks;
	private int deposited;
	private int attempts;
	private long lastSellTick = Long.MIN_VALUE / 2;
	public String status = "idle";
	/** Counts for the panel, so a sale that quietly does nothing is visible. */
	public int soldStacks;
	public int sellRuns;

	public Backpack(Config cfg) {
		this.cfg = cfg;
	}

	public Phase phase() {
		return phase;
	}

	public boolean busy() {
		return phase != Phase.IDLE;
	}

	// ------------------------------------------------------------- fullness

	/** How full the part of the inventory the bot is allowed to touch is, 0 to 1. */
	public double fullness(LocalPlayer player) {
		Inventory inv = player.getInventory();
		int usable = 0, taken = 0;
		for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
			if (cfg.slotProtected(slot)) continue;
			usable++;
			if (!inv.getItem(slot).isEmpty()) taken++;
		}
		return usable == 0 ? 1 : (double) taken / usable;
	}

	public boolean full(LocalPlayer player) {
		return fullness(player) >= Math.max(0.1, cfg.inventoryFullFraction);
	}

	// ----------------------------------------------------------------- junk

	/**
	 * Throw away one stack of rubbish, or return false when there is none.
	 *
	 * <p>One per call on purpose: a person emptying their bag does it a stack at a time, and
	 * thirty-six throw packets in a single tick is the single most obvious thing this mod
	 * could possibly send.
	 */
	public boolean dropOneJunkStack(Minecraft mc, LocalPlayer player) {
		if (!cfg.dropJunk || cfg.junkItems.isEmpty()) return false;
		Inventory inv = player.getInventory();
		for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
			if (cfg.slotProtected(slot)) continue;
			ItemStack stack = inv.getItem(slot);
			if (stack.isEmpty() || !cfg.isJunk(itemId(stack))) continue;
			throwStack(mc, player, slot);
			status = "dropped " + stack.getHoverName().getString();
			return true;
		}
		return false;
	}

	/** Shift-click a stack of building blocks up to the hotbar when the bar has run dry. */
	public boolean restockHotbar(Minecraft mc, LocalPlayer player) {
		if (!cfg.restockHotbar) return false;
		if (Bot.buildingSlot(player, cfg) >= 0) return false;
		Inventory inv = player.getInventory();
		for (int slot = Inventory.SELECTION_SIZE; slot < Inventory.INVENTORY_SIZE; slot++) {
			if (cfg.slotProtected(slot)) continue;
			ItemStack stack = inv.getItem(slot);
			if (!(stack.getItem() instanceof BlockItem item)) continue;
			if (!cfg.isBuildingBlock(blockId(item))) continue;
			click(mc, player, player.inventoryMenu, menuSlotFor(player.inventoryMenu, player, slot),
					0, ContainerInput.QUICK_MOVE);
			status = "moved " + stack.getHoverName().getString() + " to the hotbar";
			return true;
		}
		return false;
	}

	// ------------------------------------------------------------- the sale

	public boolean wantsToSell(Minecraft mc, LocalPlayer player, long tick) {
		if (!cfg.autoSellEnabled) return false;
		if (tick - lastSellTick < cfg.autoSellCooldownSec * 20) return false;
		return countForSale(player) >= Math.max(1, cfg.autoSellMinStacks);
	}

	/** How many stacks are sitting in slots the user marked as sellable. */
	public int countForSale(LocalPlayer player) {
		Inventory inv = player.getInventory();
		int n = 0;
		for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
			if (cfg.slotProtected(slot) || !cfg.slotForSale(slot)) continue;
			if (!inv.getItem(slot).isEmpty()) n++;
		}
		return n;
	}

	public void beginSell(Minecraft mc, LocalPlayer player, long tick) {
		lastSellTick = tick;
		sellRuns++;
		deposited = 0;
		attempts = 0;
		phase = Phase.OPENING;
		waitTicks = Rng.ticks(cfg.sellDelayMinSec, cfg.sellDelayMaxSec);
		status = "sending /" + cfg.sellCommand;
		player.connection.sendCommand(cfg.sellCommand.trim());
	}

	public void cancel(Minecraft mc) {
		if (phase == Phase.IDLE) return;
		phase = Phase.IDLE;
		waitTicks = 0;
		status = "cancelled";
		if (mc.gui.screen() instanceof AbstractContainerScreen<?>) mc.player.closeContainer();
	}

	/**
	 * One tick of the sale. Returns true while it is still running, so the task above knows
	 * to stand still rather than walk off mid-menu.
	 */
	public boolean tick(Minecraft mc, LocalPlayer player) {
		if (phase == Phase.IDLE) return false;
		if (waitTicks > 0) {
			waitTicks--;
			status = phase.label + " (" + (waitTicks / 20 + 1) + "s)";
			return true;
		}

		AbstractContainerMenu menu = mc.gui.screen() instanceof AbstractContainerScreen<?> screen
				? screen.getMenu() : null;

		switch (phase) {
			case OPENING -> {
				if (menu == null) {
					// the menu never came: the command may not exist on this server
					if (++attempts > 3) {
						fail("the sell menu never opened");
						return false;
					}
					waitTicks = Rng.ticks(0.5, 1.2);
					return true;
				}
				phase = Phase.DEPOSITING;
				waitTicks = pause();
			}
			case DEPOSITING -> {
				if (menu == null) {
					fail("the menu closed while filling it");
					return false;
				}
				int slot = nextSellSlot(player);
				if (slot < 0) {
					phase = deposited > 0 || !cfg.sellRequiresDeposit ? Phase.CONFIRMING : Phase.CLOSING;
					waitTicks = pause();
					return true;
				}
				int menuSlot = menuSlotFor(menu, player, slot);
				if (menuSlot < 0) {
					fail("could not find the inventory inside the sell menu");
					return false;
				}
				click(mc, player, menu, menuSlot, 0, ContainerInput.QUICK_MOVE);
				deposited++;
				soldStacks++;
				status = "moved " + deposited + " stacks in";
				waitTicks = pause();
			}
			case CONFIRMING -> {
				if (menu == null) {
					fail("the menu closed before the confirm");
					return false;
				}
				int confirm = confirmSlot(menu, player);
				if (confirm < 0) {
					fail("no confirm button in the sell menu");
					return false;
				}
				click(mc, player, menu, confirm, 0, ContainerInput.PICKUP);
				status = "confirmed";
				phase = Phase.SETTLING;
				waitTicks = Rng.ticks(cfg.sellDelayMinSec, cfg.sellDelayMaxSec);
			}
			case SETTLING -> {
				phase = Phase.CLOSING;
				waitTicks = pause();
			}
			case CLOSING -> {
				if (menu != null) player.closeContainer();
				phase = Phase.IDLE;
				status = "sold %d stacks".formatted(deposited);
				return false;
			}
			default -> phase = Phase.IDLE;
		}
		return true;
	}

	private void fail(String why) {
		phase = Phase.IDLE;
		status = "sell failed — " + why;
	}

	private int pause() {
		return Rng.ticks(cfg.sellClickMinSec, cfg.sellClickMaxSec);
	}

	private int nextSellSlot(LocalPlayer player) {
		Inventory inv = player.getInventory();
		for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
			if (cfg.slotProtected(slot) || !cfg.slotForSale(slot)) continue;
			if (!inv.getItem(slot).isEmpty()) return slot;
		}
		return -1;
	}

	// ------------------------------------------------------------ menu slots

	/** Where an inventory slot shows up inside whatever menu is open. */
	static int menuSlotFor(AbstractContainerMenu menu, LocalPlayer player, int inventorySlot) {
		for (int i = 0; i < menu.slots.size(); i++) {
			Slot slot = menu.slots.get(i);
			if (slot.container == player.getInventory() && slot.index == inventorySlot) return i;
		}
		return -1;
	}

	/**
	 * The button that completes the sale.
	 *
	 * <p>Named by item rather than by position, because the position is a server's layout
	 * choice and the item is what the user is actually looking at. Among matches, the last
	 * one in the container half wins — a confirm button lives in the bottom right corner,
	 * and the highest slot index in a chest grid <em>is</em> the bottom right corner.
	 */
	static int confirmSlot(AbstractContainerMenu menu, LocalPlayer player, String wanted, int fallback) {
		String needle = wanted == null ? "" : wanted.trim().toLowerCase(Locale.ROOT);
		int found = -1;
		for (int i = 0; i < menu.slots.size(); i++) {
			Slot slot = menu.slots.get(i);
			if (slot.container == player.getInventory()) continue; // never the player's own half
			ItemStack stack = slot.getItem();
			if (stack.isEmpty()) continue;
			if (!needle.isEmpty() && !itemId(stack).equals(needle)
					&& !itemId(stack).endsWith(":" + needle)) {
				continue;
			}
			found = i;
		}
		if (found >= 0) return found;
		// nothing matched: fall back to a slot the user counted out themselves
		return fallback >= 0 && fallback < menu.slots.size() ? fallback : -1;
	}

	private int confirmSlot(AbstractContainerMenu menu, LocalPlayer player) {
		return confirmSlot(menu, player, cfg.sellConfirmItem, cfg.sellConfirmSlot);
	}

	private static void click(Minecraft mc, LocalPlayer player, AbstractContainerMenu menu,
	                          int slot, int button, ContainerInput input) {
		if (slot < 0 || mc.gameMode == null) return;
		mc.gameMode.handleContainerInput(menu.containerId, slot, button, input, player);
	}

	private void throwStack(Minecraft mc, LocalPlayer player, int inventorySlot) {
		AbstractContainerMenu menu = player.inventoryMenu;
		int slot = menuSlotFor(menu, player, inventorySlot);
		// button 1 on a THROW is "the whole stack", which is what pressing ctrl-Q sends
		click(mc, player, menu, slot, 1, ContainerInput.THROW);
	}

	static String itemId(ItemStack stack) {
		var id = BuiltInRegistries.ITEM.getKey(stack.getItem());
		return id == null ? "" : id.toString();
	}

	private static String blockId(BlockItem item) {
		var id = BuiltInRegistries.BLOCK.getKey(item.getBlock());
		return id == null ? "" : id.getPath();
	}

	/**
	 * Self-check on the slot rules — the menus need a server, the bookkeeping does not:
	 * {@code ./gradlew selfCheck -Pcheck=com.damia.movrand.Backpack}
	 */
	public static void main(String[] args) {
		Config cfg = new Config();
		cfg.protectedSlots = new ArrayList<>(List.of(0, 1, 8));
		cfg.sellSlots = new ArrayList<>(List.of(9, 10, 0));

		assert cfg.slotProtected(0) && cfg.slotProtected(8) : "a listed slot must be protected";
		assert !cfg.slotProtected(2) : "an unlisted slot must not be";
		assert !cfg.slotProtected(-1) && !cfg.slotProtected(999) : "a nonsense slot is not protected";

		// Protection beats sale, always and without the user having to keep the two lists
		// consistent. Getting this backwards sells the thing they most wanted kept.
		assert cfg.slotForSale(9) : "slot 9 was marked for sale";
		assert !cfg.slotForSale(0) : "a protected slot must never be for sale, even when listed";
		assert !cfg.slotForSale(20) : "an unlisted slot is not for sale";

		// marking a protected slot for sale is a no-op rather than an error
		cfg.protectedSlots.add(9);
		assert !cfg.slotForSale(9) : "protecting a slot must take it out of the sale";

		// the sell command is sent without a leading slash, which sendCommand adds itself
		assert !cfg.sellCommand.startsWith("/") : "sellCommand must not carry its own slash";

		// and the delays are real delays, or the whole point of them is lost
		cfg.sellClickMinSec = 0;
		cfg.sellClickMaxSec = 0;
		cfg.clampAll();
		assert cfg.sellClickMinSec > 0 : "a zero click delay is a burst of packets";
		assert cfg.sellDelayMinSec > 0 : "a zero menu delay clicks a menu that is not there";
		assert cfg.sellClickMaxSec >= cfg.sellClickMinSec : "the click delay range is inverted";

		System.out.println("Backpack self-check passed");
	}
}
