package com.damia.movrand.gui;

import com.damia.movrand.Config;
import com.damia.movrand.Journal;
import com.damia.movrand.WorldBounds;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * The coordinate log, drawn as a map.
 *
 * <p>A list of numbers tells you a spawner exists. A picture tells you it is forty blocks from
 * the chest hall you found an hour earlier, which is the thing you actually wanted to know.
 *
 * <p>Drag to pan, wheel to zoom, right-click to recentre, click a pin to copy its teleport.
 * Nether coordinates are drawn at 8x by default so a portal lines up with the overworld it
 * sits under — otherwise two unrelated sets of numbers pile up around the origin.
 */
public final class JournalMap extends Widgets.Element {

	private static final int MAX_PINS = 2000;

	private final Config cfg;
	private final Supplier<List<Journal.Entry>> source;
	/** The dimension the map is drawn in, or empty when everything is shown at once. */
	private final Supplier<String> frame;

	private double centreX, centreZ;
	private boolean centred;
	private boolean dragging;
	private double dragFromX, dragFromZ;
	private double pressX, pressY;
	private Journal.Entry hover;
	private int shown, dropped;
	/** Where the cursor was on the last frame — a release carries no position of its own. */
	private double lastMouseX, lastMouseY;
	/** Set when a pin is clicked, so the screen can say so. Read once and cleared. */
	private String picked;

	public JournalMap(Config cfg, Supplier<List<Journal.Entry>> source, Supplier<String> frame, int height) {
		this.cfg = cfg;
		this.source = source;
		this.frame = frame;
		this.h = height;
	}

	// -------------------------------------------------------- the projection

	private int gridSize() {
		return Math.max(40, Math.min(w, h - 26));
	}

	private int gridX() {
		return x + (w - gridSize()) / 2;
	}

	private int gridY() {
		return y;
	}

	/** Read once a frame; see the note on the same field in {@link AreaMap}. */
	private double maxSpanBlocks = 32_768 * 16;

	/** Pixels per block. */
	private double scale() {
		// never wider than the world - past the border there is nothing to plot
		return gridSize() / Math.max(16.0, Math.min(cfg.mapSpanBlocks, maxSpanBlocks));
	}

	private int sx(double worldX) {
		return gridX() + (int) Math.round((worldX - centreX) * scale() + gridSize() / 2.0);
	}

	private int sz(double worldZ) {
		return gridY() + (int) Math.round((worldZ - centreZ) * scale() + gridSize() / 2.0);
	}

	private double worldXAt(double px) {
		return centreX + (px - gridX() - gridSize() / 2.0) / scale();
	}

	private double worldZAt(double py) {
		return centreZ + (py - gridY() - gridSize() / 2.0) / scale();
	}

	private boolean insideGrid(double mx, double my) {
		return mx >= gridX() && mx < gridX() + gridSize() && my >= gridY() && my < gridY() + gridSize();
	}

	/**
	 * Nether coordinates are worth eight overworld blocks each. Scaling them up puts a portal
	 * where it actually comes out — unless the map is already drawn in nether coordinates, in
	 * which case they are already right.
	 */
	private double stretch(Journal.Entry e) {
		if (!cfg.mapScaleNether) return 1;
		if ("the_nether".equals(frame.get())) return 1;
		return "the_nether".equals(e.dimension()) ? 8 : 1;
	}

	// ------------------------------------------------------------- rendering

	@Override
	public void render(GuiGraphicsExtractor g, Font f, int mx, int my, int accent) {
		lastMouseX = mx;
		lastMouseY = my;
		maxSpanBlocks = WorldBounds.maxSpanBlocks(32_768 * 16);
		Minecraft mc = Minecraft.getInstance();
		if (!centred || (cfg.mapFollowPlayer && !dragging)) {
			if (mc.player != null) {
				centreX = mc.player.getX();
				centreZ = mc.player.getZ();
				centred = true;
			} else if (!centred) {
				centreOnPins();
			}
		}

		int size = gridSize();
		int gx = gridX(), gy = gridY();
		Ui.card(g, gx, gy, size, size, 4, Ui.SIDEBAR, Ui.BORDER);

		g.enableScissor(gx + 1, gy + 1, gx + size - 1, gy + size - 1);
		if (cfg.mapShowGrid) drawGrid(g, f, gx, gy, size);
		drawWorldBorder(g, gx, gy, size);
		if (cfg.mapShowArea && cfg.areaEnabled) drawArea(g, accent);
		drawPins(g, f, mx, my, accent, gx, gy, size);
		drawPlayer(g, mc);
		g.disableScissor();

		drawScaleBar(g, f, gx, gy, size);
		drawReadout(g, f, mx, my, gx, gy, size, accent);
	}

	private void centreOnPins() {
		List<Journal.Entry> all = source.get();
		if (all.isEmpty()) return;
		double sx = 0, sz = 0;
		for (Journal.Entry e : all) {
			double k = stretch(e);
			sx += e.x() * k;
			sz += e.z() * k;
		}
		centreX = sx / all.size();
		centreZ = sz / all.size();
		centred = true;
	}

	/**
	 * The world border, drawn in the same frame the pins are.
	 *
	 * <p>The stretch matters here for the same reason it does for a pin: standing in the
	 * nether while looking at an overworld-framed map, the border really is eight times
	 * further out than its own coordinates say.
	 */
	private void drawWorldBorder(GuiGraphicsExtractor g, int gx, int gy, int size) {
		WorldBounds b = WorldBounds.current();
		if (b == null) return;
		double k = borderStretch();
		int x1 = sx(b.minX() * k), x2 = sx(b.maxX() * k);
		int y1 = sz(b.minZ() * k), y2 = sz(b.maxZ() * k);
		if (x2 < gx || x1 > gx + size || y2 < gy || y1 > gy + size) return; // nowhere near

		int cx1 = Math.max(gx, x1), cx2 = Math.min(gx + size, x2);
		int cy1 = Math.max(gy, y1), cy2 = Math.min(gy + size, y2);
		if (x1 >= gx && x1 <= gx + size) Ui.rect(g, x1, cy1, 1, cy2 - cy1, Ui.WORLD_EDGE);
		if (x2 >= gx && x2 <= gx + size) Ui.rect(g, x2, cy1, 1, cy2 - cy1, Ui.WORLD_EDGE);
		if (y1 >= gy && y1 <= gy + size) Ui.rect(g, cx1, y1, cx2 - cx1, 1, Ui.WORLD_EDGE);
		if (y2 >= gy && y2 <= gy + size) Ui.rect(g, cx1, y2, cx2 - cx1, 1, Ui.WORLD_EDGE);
	}

	/** The same rule {@link #stretch} applies to a pin, asked about where you are standing. */
	private double borderStretch() {
		if (!cfg.mapScaleNether || "the_nether".equals(frame.get())) return 1;
		try {
			Minecraft mc = Minecraft.getInstance();
			return mc.level != null
					&& "the_nether".equals(mc.level.dimension().identifier().getPath()) ? 8 : 1;
		} catch (Exception | LinkageError e) {
			return 1;
		}
	}

	/** A grid step that lands somewhere between 28 and 200 pixels, whatever the zoom. */
	private int gridStep() {
		int[] steps = {16, 32, 64, 128, 256, 512, 1024, 2048, 4096, 8192};
		for (int step : steps) if (step * scale() >= 28) return step;
		return steps[steps.length - 1];
	}

	private void drawGrid(GuiGraphicsExtractor g, Font f, int gx, int gy, int size) {
		int step = gridStep();
		double left = worldXAt(gx), top = worldZAt(gy);
		long firstX = (long) Math.floor(left / step) * step;
		long firstZ = (long) Math.floor(top / step) * step;

		for (long wx = firstX; sx(wx) <= gx + size; wx += step) {
			int px = sx(wx);
			if (px < gx) continue;
			Ui.rect(g, px, gy, 1, size, wx == 0 ? Ui.BORDER : Ui.BORDER_SOFT);
		}
		for (long wz = firstZ; sz(wz) <= gy + size; wz += step) {
			int py = sz(wz);
			if (py < gy) continue;
			Ui.rect(g, gx, py, size, 1, wz == 0 ? Ui.BORDER : Ui.BORDER_SOFT);
		}
	}

	private void drawArea(GuiGraphicsExtractor g, int accent) {
		int x1 = sx(Math.min(cfg.areaX1, cfg.areaX2));
		int x2 = sx(Math.max(cfg.areaX1, cfg.areaX2));
		int z1 = sz(Math.min(cfg.areaZ1, cfg.areaZ2));
		int z2 = sz(Math.max(cfg.areaZ1, cfg.areaZ2));
		int colour = Ui.alpha(accent, 0.55);
		Ui.rect(g, x1, z1, Math.max(1, x2 - x1), 1, colour);
		Ui.rect(g, x1, z2, Math.max(1, x2 - x1), 1, colour);
		Ui.rect(g, x1, z1, 1, Math.max(1, z2 - z1), colour);
		Ui.rect(g, x2, z1, 1, Math.max(1, z2 - z1), colour);
	}

	private void drawPins(GuiGraphicsExtractor g, Font f, int mx, int my, int accent,
	                      int gx, int gy, int size) {
		List<Journal.Entry> all = source.get();
		hover = null;
		shown = 0;
		dropped = 0;
		double bestDistance = 36; // six pixels, squared

		for (Journal.Entry e : all) {
			if (shown >= MAX_PINS) {
				dropped = all.size() - shown;
				break;
			}
			double k = stretch(e);
			int px = sx(e.x() * k);
			int py = sz(e.z() * k);
			if (px < gx - 4 || px > gx + size + 4 || py < gy - 4 || py > gy + size + 4) continue;
			shown++;

			int colour = ConfigScreen.kindColour(e.kind(), accent);
			double d = (px - mx) * (px - mx) + (py - my) * (py - my);
			if (d < bestDistance) {
				bestDistance = d;
				hover = e;
			}
			Ui.roundRect(g, px - 2, py - 2, 5, 5, 2, colour);
			if (cfg.mapShowLabels) {
				Ui.text(g, f, Ui.elide(f, e.kind().label, 90), px + 5, py - 3, Ui.alpha(colour, 0.85));
			}
		}

		if (hover != null) {
			double k = stretch(hover);
			int px = sx(hover.x() * k), py = sz(hover.z() * k);
			Ui.roundRect(g, px - 4, py - 4, 9, 9, 4, 0xFFFFFFFF);
			Ui.roundRect(g, px - 2, py - 2, 5, 5, 2, ConfigScreen.kindColour(hover.kind(), accent));
		}
	}

	private void drawPlayer(GuiGraphicsExtractor g, Minecraft mc) {
		if (mc.player == null) return;
		int px = sx(mc.player.getX()), py = sz(mc.player.getZ());
		Ui.roundRect(g, px - 3, py - 3, 7, 7, 3, 0x66FFFFFF);
		Ui.roundRect(g, px - 1, py - 1, 3, 3, 1, 0xFFFFFFFF);
		// a bead ahead of the dot, so the map has an orientation without needing a line
		double yaw = Math.toRadians(mc.player.getYRot());
		int tx = px + (int) Math.round(-Math.sin(yaw) * 7);
		int ty = py + (int) Math.round(Math.cos(yaw) * 7);
		Ui.roundRect(g, tx - 1, ty - 1, 3, 3, 1, 0xAAFFFFFF);
	}

	private void drawScaleBar(GuiGraphicsExtractor g, Font f, int gx, int gy, int size) {
		int step = gridStep();
		int bar = (int) Math.round(step * scale());
		int bx = gx + 8, by = gy + size - 10;
		Ui.rect(g, bx, by, Math.max(2, bar), 1, Ui.TEXT_MUTED);
		Ui.rect(g, bx, by - 2, 1, 5, Ui.TEXT_MUTED);
		Ui.rect(g, bx + Math.max(2, bar) - 1, by - 2, 1, 5, Ui.TEXT_MUTED);
		Ui.text(g, f, step + "m", bx + Math.max(2, bar) + 4, by - 3, Ui.TEXT_MUTED);
	}

	private void drawReadout(GuiGraphicsExtractor g, Font f, int mx, int my,
	                         int gx, int gy, int size, int accent) {
		int ty = gy + size + 5;
		if (hover != null) {
			Ui.roundRect(g, x, ty - 2, w, 22, 3, Ui.CARD);
			Ui.roundRect(g, x + 4, ty + 2, 3, 12, 1, ConfigScreen.kindColour(hover.kind(), accent));
			Ui.text(g, f, Ui.elide(f, hover.kind().label + "  " + hover.coords(), w / 2), x + 12, ty, Ui.TEXT);
			Ui.text(g, f, Ui.elide(f, hover.dimension() + "  ·  " + hover.when(), w / 2), x + 12, ty + 10,
					Ui.TEXT_FAINT);
			String note = hover.note().isEmpty() ? "click to copy the teleport" : hover.note();
			Ui.textRight(g, f, Ui.elide(f, note, w / 2 - 16), x + w - 6, ty + 5, Ui.TEXT_MUTED);
			return;
		}

		String left = insideGrid(mx, my)
				? "%.0f, %.0f".formatted(worldXAt(mx), worldZAt(my))
				: "centre %.0f, %.0f".formatted(centreX, centreZ);
		String right = dropped > 0
				? shown + " pins (" + dropped + " past the cap)"
				: shown + (shown == 1 ? " pin" : " pins");
		Ui.text(g, f, left, x, ty + 3, insideGrid(mx, my) ? Ui.TEXT : Ui.TEXT_MUTED);
		Ui.textRight(g, f, String.format(Locale.ROOT, "%s  ·  %.0f blocks across", right, cfg.mapSpanBlocks),
				x + w, ty + 3, Ui.TEXT_FAINT);
	}

	// ---------------------------------------------------------------- input

	@Override
	public boolean mouseClicked(double mx, double my, int button) {
		if (!insideGrid(mx, my)) return false;
		if (button == 1) {
			centreX = worldXAt(mx);
			centreZ = worldZAt(my);
			cfg.mapFollowPlayer = false;
			centred = true;
			return true;
		}
		if (button != 0) return false;
		dragging = true;
		pressX = mx;
		pressY = my;
		dragFromX = worldXAt(mx);
		dragFromZ = worldZAt(my);
		return true;
	}

	@Override
	public void mouseDragged(double mx, double my) {
		if (!dragging) return;
		// only start panning once the cursor has actually moved, so a click still selects
		if (Math.abs(mx - pressX) + Math.abs(my - pressY) < 3) return;
		cfg.mapFollowPlayer = false;
		centreX = dragFromX - (mx - gridX() - gridSize() / 2.0) / scale();
		centreZ = dragFromZ - (my - gridY() - gridSize() / 2.0) / scale();
	}

	@Override
	public void mouseReleased() {
		if (!dragging) return;
		dragging = false;
		boolean moved = Math.abs(pressX - lastMouseX) + Math.abs(pressY - lastMouseY) >= 3;
		if (!moved && hover != null) {
			Minecraft.getInstance().keyboardHandler.setClipboard(hover.teleport());
			picked = hover.teleport();
		}
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double amount) {
		if (!insideGrid(mx, my) || amount == 0) return false;
		// zoom about the cursor, so the block under it stays put
		double anchorX = worldXAt(mx), anchorZ = worldZAt(my);
		double factor = amount > 0 ? 1 / 1.25 : 1.25;
		cfg.mapSpanBlocks = Math.max(64, Math.min(maxSpanBlocks, cfg.mapSpanBlocks * factor));
		centreX = anchorX - (mx - gridX() - gridSize() / 2.0) / scale();
		centreZ = anchorZ - (my - gridY() - gridSize() / 2.0) / scale();
		cfg.mapFollowPlayer = false;
		centred = true;
		return true;
	}

	@Override
	public boolean hovered(double mx, double my) {
		return insideGrid(mx, my);
	}

	public String takePicked() {
		String p = picked;
		picked = null;
		return p;
	}

	public void recentre() {
		centred = false;
		centreOnPins();
	}
}
