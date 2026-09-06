package com.damia.movrand.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.DoubleSupplier;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * A small widget kit. These are not vanilla {@code AbstractWidget}s — they are laid out
 * and scrolled by {@link ConfigScreen}, which needs full control over their y position.
 */
public final class Widgets {

	private Widgets() {
	}

	// ------------------------------------------------------------------ base

	public abstract static class Element {
		public int x, y, w, h = 22;
		public int relY;
		public String tip = "";
		/** Non-empty when this setting could give the bot away. Drawn as a red edge. */
		public String risk = "";
		/** Non-empty for recommended settings. Drawn as a green edge. */
		public String recommendation = "";
		public boolean enabled = true;
		protected double anim;

		public abstract void render(GuiGraphicsExtractor g, Font f, int mx, int my, int accent);

		/**
		 * Called once the width is known and before positions are assigned, so an element
		 * whose height depends on how its content wraps can report the right {@code h}.
		 */
		public void measure() {
		}

		public boolean mouseClicked(double mx, double my, int button) {
			return false;
		}

		/**
		 * Only overridden by widgets where a second click means something different from the
		 * first. Everything else never learns the difference.
		 */
		public boolean mouseClicked(double mx, double my, int button, boolean doubled) {
			return mouseClicked(mx, my, button);
		}

		public void mouseDragged(double mx, double my) {
		}

		public void mouseReleased() {
		}

		public boolean keyPressed(int key, int modifiers) {
			return false;
		}

		public boolean charTyped(int codepoint) {
			return false;
		}

		public boolean hovered(double mx, double my) {
			return enabled && mx >= x && mx < x + w && my >= y && my < y + h;
		}

		protected void approach(double target) {
			anim += (target - anim) * 0.28;
			if (Math.abs(target - anim) < 0.001) anim = target;
		}

		public Element tip(String t) {
			this.tip = t;
			return this;
		}

		/** Flags this control as one a server could notice. The reason joins the hover text. */
		public Element risk(String why) {
			this.risk = why;
			this.tip = tip.isEmpty() ? "Unsafe: " + why : tip + "   —   Unsafe: " + why;
			return this;
		}

		public Element recommend(String why) {
			this.recommendation = why;
			this.tip = tip.isEmpty() ? "Recommended: " + why : tip + "   —   Recommended: " + why;
			return this;
		}

		/** @return true if this element consumed the wheel, so the page should not scroll. */
		public boolean mouseScrolled(double mx, double my, double amount) {
			return false;
		}
	}

	// --------------------------------------------------------------- header

	public static final class Section extends Element {
		private final String title;

		public Section(String title) {
			this.title = title.toUpperCase(Locale.ROOT);
			this.h = 26;
		}

		@Override
		public void render(GuiGraphicsExtractor g, Font f, int mx, int my, int accent) {
			int ty = y + h - 11;
			Ui.text(g, f, title, x, ty, accent);
			int lineX = x + f.width(title) + 8;
			Ui.hLine(g, lineX, ty + 3, Math.max(0, x + w - lineX), Ui.BORDER_SOFT);
		}
	}

	public static final class Note extends Element {
		private final String body;
		private final int colour;

		public Note(String body) {
			this(body, Ui.TEXT_MUTED);
		}

		public Note(String body, int colour) {
			this.body = body;
			this.colour = colour;
			this.h = 12;
		}

		@Override
		public void render(GuiGraphicsExtractor g, Font f, int mx, int my, int accent) {
			Ui.text(g, f, Ui.elide(f, body, w), x, y + 2, colour);
		}
	}

	public static final class KeyValue extends Element {
		private final String key;
		private final Supplier<String> value;
		private final Supplier<Integer> colour;

		public KeyValue(String key, Supplier<String> value, Supplier<Integer> colour) {
			this.key = key;
			this.value = value;
			this.colour = colour;
			this.h = 16;
		}

		@Override
		public void render(GuiGraphicsExtractor g, Font f, int mx, int my, int accent) {
			Ui.text(g, f, key, x, y + 4, Ui.TEXT_MUTED);
			Ui.textRight(g, f, Ui.elide(f, value.get(), w - f.width(key) - 12), x + w, y + 4, colour.get());
		}
	}

	// --------------------------------------------------------------- toggle

	public static final class Toggle extends Element {
		private static final int TRACK_W = 30, TRACK_H = 16;
		private final String label;
		private final BooleanSupplier getter;
		private final Consumer<Boolean> setter;

		public Toggle(String label, BooleanSupplier getter, Consumer<Boolean> setter) {
			this.label = label;
			this.getter = getter;
			this.setter = setter;
			this.h = 24;
			this.anim = getter.getAsBoolean() ? 1 : 0;
		}

		@Override
		public void render(GuiGraphicsExtractor g, Font f, int mx, int my, int accent) {
			boolean on = getter.getAsBoolean();
			boolean hover = hovered(mx, my);
			approach(on ? 1 : 0);

			Ui.text(g, f, Ui.elide(f, label, w - TRACK_W - 16), x, y + (h - 8) / 2, enabled ? Ui.TEXT : Ui.TEXT_FAINT);

			int tx = x + w - TRACK_W;
			int ty = y + (h - TRACK_H) / 2;
			int track = Ui.mix(hover ? Ui.CARD_HOVER : Ui.TRACK, accent, anim);
			Ui.pill(g, tx, ty, TRACK_W, TRACK_H, track);

			int knob = (int) Math.round(tx + 2 + anim * (TRACK_W - TRACK_H));
			Ui.roundRect(g, knob, ty + 2, TRACK_H - 4, TRACK_H - 4, (TRACK_H - 4) / 2,
					Ui.mix(0xFF9A9AAE, 0xFFFFFFFF, anim));
		}

		@Override
		public boolean mouseClicked(double mx, double my, int button) {
			if (!hovered(mx, my) || button != 0) return false;
			setter.accept(!getter.getAsBoolean());
			return true;
		}
	}

	// --------------------------------------------------------------- slider

	public static final class Slider extends Element {
		private final String label;
		private final double min, max, step;
		private final int decimals;
		private final String suffix;
		private final DoubleSupplier getter;
		private final Consumer<Double> setter;
		private boolean dragging;

		public Slider(String label, double min, double max, double step, int decimals, String suffix,
		              DoubleSupplier getter, Consumer<Double> setter) {
			this.label = label;
			this.min = min;
			this.max = max;
			this.step = step;
			this.decimals = decimals;
			this.suffix = suffix;
			this.getter = getter;
			this.setter = setter;
			this.h = 32;
		}

		public static Slider ints(String label, int min, int max, IntSupplier getter, Consumer<Integer> setter) {
			return new Slider(label, min, max, 1, 0, "", getter::getAsInt, v -> setter.accept((int) Math.round(v)));
		}

		private double fraction() {
			return max <= min ? 0 : (getter.getAsDouble() - min) / (max - min);
		}

		private String valueText() {
			return String.format(Locale.ROOT, "%." + decimals + "f%s", getter.getAsDouble(), suffix);
		}

		@Override
		public void render(GuiGraphicsExtractor g, Font f, int mx, int my, int accent) {
			boolean hover = hovered(mx, my) || dragging;
			approach(hover ? 1 : 0);

			Ui.text(g, f, label, x, y + 2, enabled ? Ui.TEXT : Ui.TEXT_FAINT);
			Ui.textRight(g, f, valueText(), x + w, y + 2, enabled ? accent : Ui.TEXT_FAINT);

			int ty = y + 19;
			Ui.roundRect(g, x, ty, w, 4, 2, Ui.TRACK);
			double frac = Math.max(0, Math.min(1, fraction()));
			int fill = (int) Math.round(w * frac);
			if (fill > 0) {
				Ui.roundRect(g, x, ty, fill, 4, 2, enabled ? accent : Ui.TEXT_FAINT);
			}
			int r = (int) Math.round(4 + anim * 1.5);
			int hx = x + fill;
			Ui.roundRect(g, hx - r, ty + 2 - r, r * 2, r * 2, r, 0xFFFFFFFF);
		}

		@Override
		public boolean mouseClicked(double mx, double my, int button) {
			if (!hovered(mx, my) || button != 0) return false;
			dragging = true;
			apply(mx);
			return true;
		}

		@Override
		public void mouseDragged(double mx, double my) {
			if (dragging) apply(mx);
		}

		@Override
		public void mouseReleased() {
			dragging = false;
		}

		private void apply(double mx) {
			double frac = Math.max(0, Math.min(1, (mx - x) / (double) Math.max(1, w)));
			double value = min + frac * (max - min);
			if (step > 0) value = Math.round(value / step) * step;
			setter.accept(Math.max(min, Math.min(max, value)));
		}
	}

	/** Two handles on one track — a min/max pair, which is what most settings here are. */
	public static final class RangeSlider extends Element {
		private final String label;
		private final double min, max, step;
		private final int decimals;
		private final String suffix;
		private final DoubleSupplier getLow, getHigh;
		private final Consumer<Double> setLow, setHigh;
		private int dragging = -1;

		public RangeSlider(String label, double min, double max, double step, int decimals, String suffix,
		                   DoubleSupplier getLow, Consumer<Double> setLow,
		                   DoubleSupplier getHigh, Consumer<Double> setHigh) {
			this.label = label;
			this.min = min;
			this.max = max;
			this.step = step;
			this.decimals = decimals;
			this.suffix = suffix;
			this.getLow = getLow;
			this.setLow = setLow;
			this.getHigh = getHigh;
			this.setHigh = setHigh;
			this.h = 32;
		}

		private int px(double value) {
			return x + (int) Math.round(w * Math.max(0, Math.min(1, (value - min) / (max - min))));
		}

		@Override
		public void render(GuiGraphicsExtractor g, Font f, int mx, int my, int accent) {
			boolean hover = hovered(mx, my) || dragging >= 0;
			approach(hover ? 1 : 0);

			Ui.text(g, f, label, x, y + 2, enabled ? Ui.TEXT : Ui.TEXT_FAINT);
			String v = String.format(Locale.ROOT, "%." + decimals + "f – %." + decimals + "f%s",
					getLow.getAsDouble(), getHigh.getAsDouble(), suffix);
			Ui.textRight(g, f, v, x + w, y + 2, enabled ? accent : Ui.TEXT_FAINT);

			int ty = y + 19;
			Ui.roundRect(g, x, ty, w, 4, 2, Ui.TRACK);
			int lo = px(getLow.getAsDouble());
			int hi = px(getHigh.getAsDouble());
			Ui.rect(g, lo, ty, Math.max(1, hi - lo), 4, enabled ? accent : Ui.TEXT_FAINT);

			int r = (int) Math.round(4 + anim * 1.5);
			Ui.roundRect(g, lo - r, ty + 2 - r, r * 2, r * 2, r, 0xFFFFFFFF);
			Ui.roundRect(g, hi - r, ty + 2 - r, r * 2, r * 2, r, 0xFFFFFFFF);
		}

		@Override
		public boolean mouseClicked(double mx, double my, int button) {
			if (!hovered(mx, my) || button != 0) return false;
			dragging = Math.abs(mx - px(getLow.getAsDouble())) <= Math.abs(mx - px(getHigh.getAsDouble())) ? 0 : 1;
			mouseDragged(mx, my);
			return true;
		}

		@Override
		public void mouseDragged(double mx, double my) {
			if (dragging < 0) return;
			double frac = Math.max(0, Math.min(1, (mx - x) / (double) Math.max(1, w)));
			double value = min + frac * (max - min);
			if (step > 0) value = Math.round(value / step) * step;
			if (dragging == 0) setLow.accept(Math.min(value, getHigh.getAsDouble()));
			else setHigh.accept(Math.max(value, getLow.getAsDouble()));
		}

		@Override
		public void mouseReleased() {
			dragging = -1;
		}
	}

	// ---------------------------------------------------------------- cycle

	public static final class Cycle<E> extends Element {
		private final String label;
		private final List<E> values;
		private final java.util.function.Function<E, String> naming;
		private final Supplier<E> getter;
		private final Consumer<E> setter;

		public Cycle(String label, List<E> values, java.util.function.Function<E, String> naming,
		             Supplier<E> getter, Consumer<E> setter) {
			this.label = label;
			this.values = values;
			this.naming = naming;
			this.getter = getter;
			this.setter = setter;
			this.h = 26;
		}

		@Override
		public void render(GuiGraphicsExtractor g, Font f, int mx, int my, int accent) {
			boolean hover = hovered(mx, my);
			approach(hover ? 1 : 0);
			Ui.text(g, f, label, x, y + (h - 8) / 2, enabled ? Ui.TEXT : Ui.TEXT_FAINT);

			String value = naming.apply(getter.get());
			int bw = Math.min(w / 2, f.width(value) + 30);
			int bx = x + w - bw;
			Ui.card(g, bx, y, bw, h, 5, Ui.mix(Ui.CARD, Ui.CARD_HOVER, anim),
					Ui.mix(Ui.BORDER, accent, anim * 0.8));
			Ui.textCentre(g, f, value, bx + bw / 2, y + (h - 8) / 2, enabled ? Ui.TEXT : Ui.TEXT_FAINT);
			Ui.text(g, f, "‹", bx + 6, y + (h - 8) / 2, Ui.mix(Ui.TEXT_FAINT, accent, anim));
			Ui.text(g, f, "›", bx + bw - 10, y + (h - 8) / 2, Ui.mix(Ui.TEXT_FAINT, accent, anim));
		}

		@Override
		public boolean mouseClicked(double mx, double my, int button) {
			if (!hovered(mx, my)) return false;
			int i = values.indexOf(getter.get());
			if (i < 0) i = 0;
			i = button == 1 ? (i - 1 + values.size()) % values.size() : (i + 1) % values.size();
			setter.accept(values.get(i));
			return true;
		}
	}

	// --------------------------------------------------------------- button

	public static final class Action extends Element {
		private final Supplier<String> label;
		private final Runnable onClick;
		private final boolean primary;

		public Action(String label, boolean primary, Runnable onClick) {
			this(() -> label, primary, onClick);
		}

		public Action(Supplier<String> label, boolean primary, Runnable onClick) {
			this.label = label;
			this.primary = primary;
			this.onClick = onClick;
			this.h = 24;
		}

		@Override
		public void render(GuiGraphicsExtractor g, Font f, int mx, int my, int accent) {
			boolean hover = hovered(mx, my);
			approach(hover ? 1 : 0);
			int base = primary ? accent : Ui.CARD;
			int fill = Ui.mix(base, primary ? Ui.mix(base, 0xFFFFFFFF, 0.22) : Ui.CARD_HOVER, anim);
			int border = primary ? Ui.shade(accent, 1.25) : Ui.mix(Ui.BORDER, accent, anim * 0.7);
			Ui.card(g, x, y, w, h, 5, enabled ? fill : Ui.CARD, enabled ? border : Ui.BORDER_SOFT);
			int textColour = !enabled ? Ui.TEXT_FAINT : primary ? 0xFF0B0B12 : Ui.TEXT;
			Ui.textCentre(g, f, Ui.elide(f, label.get(), w - 10), x + w / 2, y + (h - 8) / 2, textColour);
		}

		@Override
		public boolean mouseClicked(double mx, double my, int button) {
			if (!hovered(mx, my) || button != 0) return false;
			onClick.run();
			return true;
		}
	}

	/** Several buttons sharing one row. */
	public static final class Row extends Element {
		public final List<Element> children;

		public Row(List<Element> children) {
			this.children = children;
			this.h = children.stream().mapToInt(c -> c.h).max().orElse(24);
		}

		public void layout() {
			int gap = 6;
			int each = (w - gap * (children.size() - 1)) / Math.max(1, children.size());
			int cx = x;
			for (Element c : children) {
				c.x = cx;
				c.y = y;
				c.w = each;
				cx += each + gap;
			}
		}

		@Override
		public void render(GuiGraphicsExtractor g, Font f, int mx, int my, int accent) {
			layout();
			for (Element c : children) c.render(g, f, mx, my, accent);
		}

		@Override
		public boolean mouseClicked(double mx, double my, int button) {
			layout();
			for (Element c : children) if (c.mouseClicked(mx, my, button)) return true;
			return false;
		}

		@Override
		public void mouseDragged(double mx, double my) {
			for (Element c : children) c.mouseDragged(mx, my);
		}

		@Override
		public void mouseReleased() {
			for (Element c : children) c.mouseReleased();
		}
	}

	// ----------------------------------------------------------- filter chips

	/**
	 * A wrapping row of small on/off pills. Far better than a column of toggles when there
	 * are a dozen categories and you mostly want to flick a couple of them.
	 */
	public static final class Chips<E> extends Element {
		private static final int ROW_H = 18, GAP = 4;

		private final List<E> values;
		private final java.util.function.Function<E, String> naming;
		private final java.util.function.Function<E, Integer> colouring;
		private final java.util.function.Predicate<E> getter;
		private final java.util.function.BiConsumer<E, Boolean> setter;
		private final java.util.function.Function<E, Integer> counter;
		/** Offsets from the element origin, so measuring does not need a position. */
		private final List<int[]> boxes = new ArrayList<>();

		public Chips(List<E> values,
		             java.util.function.Function<E, String> naming,
		             java.util.function.Function<E, Integer> colouring,
		             java.util.function.Function<E, Integer> counter,
		             java.util.function.Predicate<E> getter,
		             java.util.function.BiConsumer<E, Boolean> setter) {
			this.values = values;
			this.naming = naming;
			this.colouring = colouring;
			this.counter = counter;
			this.getter = getter;
			this.setter = setter;
			measure();
		}

		private String label(E value) {
			int n = counter == null ? -1 : counter.apply(value);
			return n < 0 ? naming.apply(value) : naming.apply(value) + "  " + n;
		}

		/** Wraps the chips into rows and reports the height that takes. */
		@Override
		public void measure() {
			Font f = Minecraft.getInstance().font;
			boxes.clear();
			int cx = 0, cy = 0;
			int width = Math.max(40, w);
			for (E value : values) {
				int cw = f.width(label(value)) + 16;
				if (cx + cw > width && cx > 0) {
					cx = 0;
					cy += ROW_H + GAP;
				}
				boxes.add(new int[]{cx, cy, cw, ROW_H});
				cx += cw + GAP;
			}
			this.h = cy + ROW_H + 2;
		}

		@Override
		public void render(GuiGraphicsExtractor g, Font f, int mx, int my, int accent) {
			for (int i = 0; i < values.size() && i < boxes.size(); i++) {
				E value = values.get(i);
				int[] b = boxes.get(i);
				int bx = x + b[0], by = y + b[1];
				boolean on = getter.test(value);
				boolean hover = mx >= bx && mx < bx + b[2] && my >= by && my < by + b[3];
				int colour = colouring == null ? accent : colouring.apply(value);
				int fill = on ? Ui.mix(Ui.CARD, colour, hover ? 0.5 : 0.34) : hover ? Ui.CARD_HOVER : Ui.CARD;
				Ui.pill(g, bx, by, b[2], b[3], fill);
				if (on) Ui.roundRect(g, bx + 6, by + 7, 4, 4, 2, colour);
				Ui.text(g, f, label(value), bx + (on ? 14 : 8), by + 5, on ? Ui.TEXT : Ui.TEXT_FAINT);
			}
		}

		@Override
		public boolean mouseClicked(double mx, double my, int button) {
			if (button != 0) return false;
			for (int i = 0; i < values.size() && i < boxes.size(); i++) {
				int[] b = boxes.get(i);
				int bx = x + b[0], by = y + b[1];
				if (mx >= bx && mx < bx + b[2] && my >= by && my < by + b[3]) {
					setter.accept(values.get(i), !getter.test(values.get(i)));
					return true;
				}
			}
			return false;
		}
	}

	// ------------------------------------------------------------- item grid

	/**
	 * A wrapping grid of block icons, clicked to pick and clicked again to drop.
	 *
	 * <p>A comma separated list of ids is only readable to someone who already knows the ids.
	 * The picture is the thing being picked, so the picture is the control.
	 */
	public static final class ItemGrid extends Element {
		private static final int CELL = 20;

		private final List<String> ids;
		private final java.util.function.Predicate<String> picked;
		private final Consumer<String> onClick;
		/** Resolved once per build — a registry lookup per icon per frame would be silly. */
		private final List<ItemStack> stacks = new ArrayList<>();
		private int cols = 1;

		public ItemGrid(List<String> ids, java.util.function.Predicate<String> picked,
		                Consumer<String> onClick) {
			this.ids = ids;
			this.picked = picked;
			this.onClick = onClick;
			for (String id : ids) stacks.add(stackOf(id));
			measure();
		}

		/**
		 * The item form of a block or an ordinary inventory item. Blocks with no item
		 * (such as fire and water) remain empty.
		 */
		public static ItemStack stackOf(String id) {
			try {
				Identifier key = Identifier.tryParse(id.contains(":") ? id : "minecraft:" + id);
				if (key == null) return ItemStack.EMPTY;
				return BuiltInRegistries.BLOCK.getOptional(key)
						.map(block -> new ItemStack(block))
						.orElseGet(() -> BuiltInRegistries.ITEM.getOptional(key)
								.map(item -> new ItemStack(item)).orElse(ItemStack.EMPTY));
			} catch (Exception | LinkageError e) {
				return ItemStack.EMPTY; // no registry outside a game
			}
		}

		@Override
		public void measure() {
			cols = Math.max(1, Math.max(CELL, w) / CELL);
			this.h = Math.max(CELL, ((ids.size() + cols - 1) / cols) * CELL);
		}

		private int indexAt(double mx, double my) {
			if (mx < x || my < y) return -1;
			int col = (int) ((mx - x) / CELL);
			if (col >= cols) return -1;
			int i = (int) ((my - y) / CELL) * cols + col;
			return i < ids.size() ? i : -1;
		}

		@Override
		public void render(GuiGraphicsExtractor g, Font f, int mx, int my, int accent) {
			int hot = indexAt(mx, my);
			tip = "";
			for (int i = 0; i < ids.size(); i++) {
				int cx = x + (i % cols) * CELL;
				int cy = y + (i / cols) * CELL;
				boolean on = picked.test(ids.get(i));
				boolean hover = i == hot;

				int fill = on ? Ui.mix(Ui.CARD, accent, hover ? 0.5 : 0.32)
						: hover ? Ui.CARD_HOVER : Ui.CARD;
				Ui.card(g, cx, cy, CELL - 1, CELL - 1, 3, fill, on ? accent : Ui.BORDER_SOFT);

				ItemStack stack = stacks.get(i);
				if (stack.isEmpty()) Ui.textCentre(g, f, "?", cx + CELL / 2, cy + 6, Ui.TEXT_FAINT);
				else g.item(stack, cx + 2, cy + 2);

				if (hover) {
					String name = stack.isEmpty() ? ids.get(i) : stack.getHoverName().getString();
					tip = name + "  (" + ids.get(i) + ")  —  click to " + (on ? "remove" : "add") + ".";
				}
			}
		}

		@Override
		public boolean mouseClicked(double mx, double my, int button) {
			int i = indexAt(mx, my);
			if (i < 0 || button != 0) return false;
			onClick.accept(ids.get(i));
			return true;
		}

		/**
		 * Self-check on the grid maths — the icons need a game, working out which one was
		 * clicked does not: {@code ./gradlew selfCheck -Pcheck=com.damia.movrand.gui.Widgets}
		 */
		static void selfCheck() {
			List<String> ids = new ArrayList<>(List.of("a", "b", "c", "d", "e"));
			ItemGrid grid = new ItemGrid(ids, id -> false, id -> {
			});
			grid.x = 100;
			grid.y = 50;
			grid.w = 3 * CELL + 7; // a stray few pixels: three columns, not three and a bit
			grid.measure();

			assert grid.cols == 3 : "three cells fit, so three columns: " + grid.cols;
			assert grid.h == 2 * CELL : "five over three columns is two rows: " + grid.h;

			assert grid.indexAt(100, 50) == 0 : "the top left pixel is the first cell";
			assert grid.indexAt(100 + CELL * 2 + 1, 50 + 1) == 2 : "last column of the first row";
			assert grid.indexAt(100 + 1, 50 + CELL + 1) == 3 : "first column of the second row";
			assert grid.indexAt(99, 50) < 0 && grid.indexAt(100, 49) < 0 : "outside is nothing";
			assert grid.indexAt(100 + CELL * 3 + 2, 50) < 0
					: "the leftover pixels past the last column are not a fourth column";
			assert grid.indexAt(100 + CELL * 2, 50 + CELL) < 0 : "the empty tail of the last row";
			assert grid.indexAt(100, 50 + CELL * 9) < 0 : "far below the grid";
		}
	}

	/** A full-width heading inside a scrolling list, e.g. a date divider. */
	public static final class Divider extends Element {
		private final String text;

		public Divider(String text) {
			this.text = text;
			this.h = 20;
		}

		@Override
		public void render(GuiGraphicsExtractor g, Font f, int mx, int my, int accent) {
			int ty = y + 6;
			Ui.text(g, f, text, x, ty, Ui.TEXT_MUTED);
			int lineX = x + f.width(text) + 8;
			Ui.hLine(g, lineX, ty + 3, Math.max(0, x + w - lineX), Ui.BORDER_SOFT);
		}
	}

	// ------------------------------------------------------- score and cards

	/** A labelled 0-100 bar. Used for the humanisation score. */
	public static final class ScoreBar extends Element {
		private final Supplier<Integer> value;
		private final Supplier<String> verdict;

		public ScoreBar(Supplier<Integer> value, Supplier<String> verdict) {
			this.value = value;
			this.verdict = verdict;
			this.h = 40;
		}

		@Override
		public void render(GuiGraphicsExtractor g, Font f, int mx, int my, int accent) {
			int v = Math.max(0, Math.min(100, value.get()));
			int colour = v >= 70 ? Ui.GOOD : v >= 40 ? Ui.WARN : Ui.BAD;
			approach(v / 100.0);

			Ui.text(g, f, verdict.get(), x, y + 2, colour);
			Ui.textRight(g, f, v + " / 100", x + w, y + 2, colour);

			int ty = y + 18;
			Ui.roundRect(g, x, ty, w, 8, 4, Ui.TRACK);
			int fill = (int) Math.round(w * anim);
			if (fill > 0) Ui.roundRect(g, x, ty, fill, 8, 4, colour);

			// quarter marks, so the bar reads as a scale rather than a mystery
			for (int i = 1; i < 4; i++) {
				int px = x + w * i / 4;
				Ui.rect(g, px, ty, 1, 8, Ui.PANEL);
			}
		}
	}

	/** A named setup with a blurb, a few bullets and an apply button. */
	public static final class Card extends Element {
		private final String title;
		private final String blurb;
		private final List<String> bullets;
		private final Runnable onApply;
		private final String applyLabel;
		private List<String> wrapped = List.of();
		private int buttonX, buttonY, buttonW = 62, buttonH = 20;

		/**
		 * The button sits in the top right corner, so the title and the blurb have to stop
		 * before it. Bullets start below it and get the full width.
		 */
		private int headWidth() {
			return Math.max(60, w - 20 - buttonW - 10);
		}

		public Card(String title, String blurb, List<String> bullets, String applyLabel, Runnable onApply) {
			this.title = title;
			this.blurb = blurb;
			this.bullets = bullets;
			this.applyLabel = applyLabel;
			this.onApply = onApply;
		}

		@Override
		public void measure() {
			Font f = Minecraft.getInstance().font;
			wrapped = Ui.wrap(f, blurb, headWidth());
			this.h = 10 + 12 + wrapped.size() * 10 + 4 + bullets.size() * 10 + 10;
			this.h = Math.max(this.h, 10 + buttonH + 10);
		}

		private boolean overButton(double mx, double my) {
			return mx >= buttonX && mx < buttonX + buttonW && my >= buttonY && my < buttonY + buttonH;
		}

		@Override
		public void render(GuiGraphicsExtractor g, Font f, int mx, int my, int accent) {
			boolean hover = hovered(mx, my);
			buttonX = x + w - buttonW - 10;
			buttonY = y + 8;
			boolean onButton = overButton(mx, my);
			approach(onButton ? 1 : hover ? 0.35 : 0);

			Ui.card(g, x, y, w, h, 6, Ui.mix(Ui.CARD, Ui.CARD_HOVER, hover ? 0.6 : 0),
					Ui.mix(Ui.BORDER_SOFT, accent, anim));
			Ui.text(g, f, Ui.elide(f, title, headWidth()), x + 10, y + 11, Ui.TEXT);

			int ty = y + 25;
			for (String line : wrapped) {
				Ui.text(g, f, line, x + 10, ty, Ui.TEXT_MUTED);
				ty += 10;
			}
			ty += 4;
			for (String b : bullets) {
				Ui.roundRect(g, x + 12, ty + 2, 3, 3, 1, accent);
				Ui.text(g, f, Ui.elide(f, b, w - 30), x + 20, ty, Ui.TEXT_FAINT);
				ty += 10;
			}

			int fill = Ui.mix(accent, Ui.mix(accent, 0xFFFFFFFF, 0.25), anim);
			Ui.card(g, buttonX, buttonY, buttonW, buttonH, 5, fill, Ui.shade(accent, 1.25));
			Ui.textCentre(g, f, applyLabel, buttonX + buttonW / 2, buttonY + (buttonH - 8) / 2, 0xFF0B0B12);
		}

		@Override
		public boolean mouseClicked(double mx, double my, int button) {
			if (button != 0 || !overButton(mx, my)) return false;
			onApply.run();
			return true;
		}
	}

	// ------------------------------------------------------------ text input

	public static final class TextInput extends Element {
		private final String label;
		private final Supplier<String> getter;
		private final Consumer<String> setter;
		private final String allowed;
		private String buffer;
		private int cursor;
		private boolean focused;
		private int blink;

		/** {@code allowed} is null for anything, or a set of permitted characters. */
		public TextInput(String label, String allowed, Supplier<String> getter, Consumer<String> setter) {
			this.label = label;
			this.allowed = allowed;
			this.getter = getter;
			this.setter = setter;
			this.buffer = getter.get();
			this.cursor = buffer.length();
			this.h = label.isEmpty() ? 24 : 38;
		}

		private int boxY() {
			return label.isEmpty() ? y : y + 14;
		}

		private int boxH() {
			return 24;
		}

		@Override
		public boolean hovered(double mx, double my) {
			return enabled && mx >= x && mx < x + w && my >= boxY() && my < boxY() + boxH();
		}

		public void commit() {
			setter.accept(buffer);
		}

		public void refresh() {
			if (!focused) {
				buffer = getter.get();
				cursor = Math.min(cursor, buffer.length());
			}
		}

		@Override
		public void render(GuiGraphicsExtractor g, Font f, int mx, int my, int accent) {
			refresh();
			blink++;
			approach(focused ? 1 : hovered(mx, my) ? 0.4 : 0);

			if (!label.isEmpty()) Ui.text(g, f, label, x, y + 1, Ui.TEXT_MUTED);
			int by = boxY();
			Ui.card(g, x, by, w, boxH(), 5, Ui.CARD, Ui.mix(Ui.BORDER, accent, anim));

			int textY = by + (boxH() - 8) / 2;
			String shown = buffer;
			int pad = 8;
			int avail = w - pad * 2;
			int offset = 0;
			if (f.width(shown) > avail) {
				// keep the cursor visible by scrolling the view
				String upToCursor = shown.substring(0, cursor);
				int cw = f.width(upToCursor);
				offset = Math.max(0, cw - avail + 6);
			}
			Ui.text(g, f, shown, x + pad - offset, textY, buffer.isEmpty() ? Ui.TEXT_FAINT : Ui.TEXT);

			if (focused && (blink / 16) % 2 == 0) {
				int cx = x + pad - offset + f.width(buffer.substring(0, cursor));
				Ui.rect(g, cx, textY - 1, 1, 10, accent);
			}
		}

		@Override
		public boolean mouseClicked(double mx, double my, int button) {
			boolean inside = hovered(mx, my);
			if (focused && !inside) {
				focused = false;
				commit();
			}
			if (!inside || button != 0) return false;
			focused = true;
			blink = 0;
			// place the cursor at the clicked character
			int rel = (int) (mx - x - 8);
			cursor = buffer.length();
			for (int i = 0; i <= buffer.length(); i++) {
				if (f().width(buffer.substring(0, i)) > rel) {
					cursor = Math.max(0, i - 1);
					break;
				}
			}
			return true;
		}

		private static Font f() {
			return Minecraft.getInstance().font;
		}

		public void unfocus() {
			if (focused) {
				focused = false;
				commit();
			}
		}

		/** The label doubles as an identity, so a rebuilt page can find this box again. */
		public String label() {
			return label;
		}

		public int cursor() {
			return cursor;
		}

		public void focus(int at) {
			focused = true;
			cursor = Math.max(0, Math.min(buffer.length(), at));
			blink = 0;
		}

		public boolean isFocused() {
			return focused;
		}

		@Override
		public boolean keyPressed(int key, int modifiers) {
			if (!focused) return false;
			boolean ctrl = (modifiers & GLFW.GLFW_MOD_CONTROL) != 0;
			switch (key) {
				case GLFW.GLFW_KEY_BACKSPACE -> {
					if (cursor > 0) {
						buffer = buffer.substring(0, cursor - 1) + buffer.substring(cursor);
						cursor--;
						commit();
					}
				}
				case GLFW.GLFW_KEY_DELETE -> {
					if (cursor < buffer.length()) {
						buffer = buffer.substring(0, cursor) + buffer.substring(cursor + 1);
						commit();
					}
				}
				case GLFW.GLFW_KEY_LEFT -> cursor = Math.max(0, cursor - 1);
				case GLFW.GLFW_KEY_RIGHT -> cursor = Math.min(buffer.length(), cursor + 1);
				case GLFW.GLFW_KEY_HOME -> cursor = 0;
				case GLFW.GLFW_KEY_END -> cursor = buffer.length();
				case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER, GLFW.GLFW_KEY_ESCAPE -> unfocus();
				default -> {
					if (ctrl && key == GLFW.GLFW_KEY_V) {
						String clip = Minecraft.getInstance().keyboardHandler.getClipboard();
						for (int cp : clip.codePoints().toArray()) insert(cp);
					} else if (ctrl && key == GLFW.GLFW_KEY_C) {
						Minecraft.getInstance().keyboardHandler.setClipboard(buffer);
					} else if (ctrl && key == GLFW.GLFW_KEY_A) {
						cursor = buffer.length();
					} else {
						return true; // swallow, we own the keyboard while focused
					}
				}
			}
			blink = 0;
			return true;
		}

		@Override
		public boolean charTyped(int codepoint) {
			if (!focused) return false;
			insert(codepoint);
			return true;
		}

		private void insert(int codepoint) {
			if (codepoint < 32) return;
			String s = new String(Character.toChars(codepoint));
			if (allowed != null && !allowed.contains(s)) return;
			buffer = buffer.substring(0, cursor) + s + buffer.substring(cursor);
			cursor += s.length();
			commit();
			blink = 0;
		}
	}

	/** {@code ./gradlew selfCheck -Pcheck=com.damia.movrand.gui.Widgets} */
	public static void main(String[] args) {
		ItemGrid.selfCheck();
		System.out.println("Widgets self-check passed");
	}
}
