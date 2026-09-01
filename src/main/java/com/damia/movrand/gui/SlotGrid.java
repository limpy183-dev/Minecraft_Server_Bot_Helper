package com.damia.movrand.gui;

import com.damia.movrand.Config;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

/**
 * The inventory, drawn as the inventory.
 *
 * <p>Two rules cannot be expressed as a list of items: "this stack of diamonds is mine" and
 * "whatever ends up here gets sold". Both are about a place rather than a thing, so the
 * control is the thing the rules are about — the actual grid, with the actual contents in
 * it, laid out the way the game lays it out.
 *
 * <p>Left click protects, right click marks for sale. Protection wins, which is why a
 * protected slot draws as protected even when it is also on the sale list: the display and
 * the rule agree, rather than the display promising something the rule will not do.
 */
public final class SlotGrid extends Widgets.Element {

	private static final int CELL = 20;
	private static final int COLS = 9;
	/** The gap between the backpack and the hotbar, as vanilla draws it. */
	private static final int SPLIT = 6;

	private final Config cfg;

	public SlotGrid(Config cfg) {
		this.cfg = cfg;
		this.h = CELL * 4 + SPLIT + 14;
	}

	private int gridX() {
		return x + Math.max(0, (w - COLS * CELL) / 2);
	}

	/** Top-left of a slot's cell, or null when the index is not on the grid. */
	private int[] cellOf(int slot) {
		if (slot < 0 || slot >= Inventory.INVENTORY_SIZE) return null;
		int gx = gridX(), gy = y + 12;
		if (slot < Inventory.SELECTION_SIZE) {
			// the hotbar is the bottom row, under the split
			return new int[]{gx + slot * CELL, gy + 3 * CELL + SPLIT};
		}
		int index = slot - Inventory.SELECTION_SIZE;
		return new int[]{gx + (index % COLS) * CELL, gy + (index / COLS) * CELL};
	}

	private int slotAt(double mx, double my) {
		for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
			int[] c = cellOf(slot);
			if (c == null) continue;
			if (mx >= c[0] && mx < c[0] + CELL && my >= c[1] && my < c[1] + CELL) return slot;
		}
		return -1;
	}

	@Override
	public void render(GuiGraphicsExtractor g, Font f, int mx, int my, int accent) {
		LocalPlayer player = Minecraft.getInstance().player;
		Inventory inv = player == null ? null : player.getInventory();

		int protectedCount = 0, sellCount = 0;
		for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
			if (cfg.slotProtected(slot)) protectedCount++;
			else if (cfg.slotForSale(slot)) sellCount++;
		}
		Ui.text(g, f, "%d protected · %d for sale".formatted(protectedCount, sellCount),
				gridX(), y, Ui.TEXT_MUTED);

		int hovered = slotAt(mx, my);
		tip = "";

		for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
			int[] c = cellOf(slot);
			if (c == null) continue;
			boolean prot = cfg.slotProtected(slot);
			boolean sale = cfg.slotForSale(slot);
			boolean hot = slot == hovered;

			int fill = prot ? Ui.mix(Ui.CARD, Ui.GOOD, hot ? 0.42 : 0.26)
					: sale ? Ui.mix(Ui.CARD, Ui.WARN, hot ? 0.42 : 0.26)
					: hot ? Ui.CARD_HOVER : Ui.CARD;
			int edge = prot ? Ui.GOOD : sale ? Ui.WARN : Ui.BORDER_SOFT;
			Ui.card(g, c[0], c[1], CELL - 1, CELL - 1, 3, fill, edge);

			ItemStack stack = inv == null ? ItemStack.EMPTY : inv.getItem(slot);
			if (!stack.isEmpty()) {
				g.item(stack, c[0] + 2, c[1] + 2);
				g.itemDecorations(f, stack, c[0] + 2, c[1] + 2);
			}
			if (hot) {
				String what = stack.isEmpty() ? "empty" : stack.getHoverName().getString();
				String state = prot ? "protected" : sale ? "for sale" : "free";
				tip = "Slot %d — %s (%s).   Left click protects, right click marks for sale."
						.formatted(slot, what, state);
			}
		}

		if (inv == null) {
			Ui.text(g, f, "join a world to see what is in it", gridX(), y + h - 10, Ui.TEXT_FAINT);
		}
	}

	@Override
	public boolean mouseClicked(double mx, double my, int button) {
		int slot = slotAt(mx, my);
		if (slot < 0) return false;
		if (button == 0) {
			cfg.setSlotProtected(slot, !cfg.slotProtected(slot));
		} else if (button == 1) {
			// protection wins, so marking a protected slot for sale would be a lie: the
			// click takes the protection off first, which is what the user plainly meant
			if (cfg.slotProtected(slot)) cfg.setSlotProtected(slot, false);
			else cfg.setSlotForSale(slot, !cfg.slotForSale(slot));
		} else {
			return false;
		}
		return true;
	}

	// ------------------------------------------------------------ bulk edits

	public static void protectAll(Config cfg, boolean on) {
		for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) cfg.setSlotProtected(slot, on);
	}

	public static void protectHotbar(Config cfg) {
		for (int slot = 0; slot < Inventory.SELECTION_SIZE; slot++) cfg.setSlotProtected(slot, true);
	}

	public static void sellRest(Config cfg, boolean on) {
		for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
			cfg.setSlotForSale(slot, on && !cfg.slotProtected(slot));
		}
	}
}
