package com.damia.movrand.gui;

import com.damia.movrand.AreaCoverage;
import com.damia.movrand.Config;
import com.damia.movrand.WorldBounds;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * The chunk grid, drawn to scale and draggable.
 *
 * <p>Typing four numbers to describe a region is precise and horrible. Here the grid is
 * centred on the player, a drag paints the rectangle you want swept, and the same squares
 * fill in as the bot actually covers them — so defining the job and watching it happen use
 * one picture.
 */
public final class AreaMap extends Widgets.Element {

	private final Config cfg;
	private final AreaCoverage area;

	private boolean dragging;
	private int dragStartCx, dragStartCz;
	private int dragNowCx, dragNowCz;

	// Where the view is looking. Following the player until you drag it somewhere else.
	// Static, like the screen's scroll positions: rebuilding the tab should not throw away
	// somewhere you deliberately panned to.
	private static double panCx, panCz;
	private static boolean following = true;
	private boolean panning;
	private double grabChunkX, grabChunkZ;
	/**
	 * Whether the next drag redraws the area. Off by default: a plain drag used to wipe the
	 * region and its progress with it, which is a lot to lose to a slipped mouse.
	 */
	private boolean selecting;
	/** Chunk under the cursor, for the readout under the grid. */
	private int hoverCx, hoverCz;
	private boolean hovering;

	// The covered chunks, pre-rasterised. Rebuilt only when the coverage or the view moves.
	private boolean[] mask;
	private int[] runs;
	private int runCount;
	private String runKey;
	private java.util.List<AreaCoverage.Region> regions = java.util.List.of();
	private static String worldScope = "";

	public AreaMap(Config cfg, AreaCoverage area, int height) {
		this.cfg = cfg;
		this.area = area;
		this.h = height;
	}

	// ------------------------------------------------------- the projection

	/**
	 * Read once a frame rather than per lookup: the projection is asked for this thousands of
	 * times while rasterising, and a border is four field reads plus an allocation each time.
	 */
	private int maxViewChunks = 32_768;

	private int viewChunks() {
		// never wider than the world - past the border there is nothing to draw
		return Math.max(4, Math.min(cfg.areaMapView, maxViewChunks));
	}

	private int gridSize() {
		return Math.min(w, h - 14);
	}

	private int gridX() {
		return x + (w - gridSize()) / 2;
	}

	private int gridY() {
		return y;
	}

	private double cellSize() {
		return gridSize() / (double) viewChunks();
	}

	private double centreChunkX() {
		if (!following) return panCx;
		Minecraft mc = Minecraft.getInstance();
		if (mc.player != null) return mc.player.blockPosition().getX() >> 4;
		return (area.minChunkX() + area.maxChunkX()) / 2.0;
	}

	private double centreChunkZ() {
		if (!following) return panCz;
		Minecraft mc = Minecraft.getInstance();
		if (mc.player != null) return mc.player.blockPosition().getZ() >> 4;
		return (area.minChunkZ() + area.maxChunkZ()) / 2.0;
	}

	private double firstChunkX() {
		return centreChunkX() - viewChunks() / 2.0;
	}

	private double firstChunkZ() {
		return centreChunkZ() - viewChunks() / 2.0;
	}

	private int screenXOf(double cx) {
		return gridX() + (int) Math.round((cx - firstChunkX()) * cellSize());
	}

	private int screenYOf(double cz) {
		return gridY() + (int) Math.round((cz - firstChunkZ()) * cellSize());
	}

	private double preciseChunkXAt(double mouseX) {
		return firstChunkX() + (mouseX - gridX()) / cellSize();
	}

	private double preciseChunkZAt(double mouseY) {
		return firstChunkZ() + (mouseY - gridY()) / cellSize();
	}

	private int chunkXAt(double mouseX) {
		return (int) Math.floor(preciseChunkXAt(mouseX));
	}

	private int chunkZAt(double mouseY) {
		return (int) Math.floor(preciseChunkZAt(mouseY));
	}

	// ----------------------------------------------------------- the view

	/** Snap back to the player and keep up with them. */
	public void follow() {
		following = true;
		panning = false;
	}

	/** Arm the next drag to redraw the area. Cleared as soon as that drag ends. */
	public void armSelection() {
		selecting = true;
	}

	public boolean isSelecting() {
		return selecting;
	}

	public boolean isFollowing() {
		return following;
	}

	private boolean insideGrid(double mx, double my) {
		return mx >= gridX() && mx < gridX() + gridSize() && my >= gridY() && my < gridY() + gridSize();
	}

	// ------------------------------------------------------------ rendering

	@Override
	public void render(GuiGraphicsExtractor g, Font f, int mx, int my, int accent) {
		String currentScope = area.scope();
		if (!worldScope.equals(currentScope)) {
			worldScope = currentScope;
			follow();
			dragging = selecting = false;
		}
		regions = area.mapRegions();
		maxViewChunks = WorldBounds.maxSpanChunks(32_768);
		int size = gridSize();
		int gx = gridX(), gy = gridY();
		hovering = insideGrid(mx, my);
		if (hovering) {
			hoverCx = chunkXAt(mx);
			hoverCz = chunkZAt(my);
		}

		Ui.card(g, gx, gy, size, size, 4, Ui.SIDEBAR, selecting ? Ui.WARN : Ui.BORDER);

		double cell = cellSize();

		for (AreaCoverage.Region region : regions) drawSelection(g, gx, gy, size, accent, region);
		drawVisited(g, gx, gy, size, accent);
		drawGridLines(g, gx, gy, size, cell);
		drawWorldBorder(g, gx, gy, size);

		// the selection outline
		for (AreaCoverage.Region region : regions) drawSelectionOutline(g, accent, region);

		// the current target
		AreaCoverage.Target t = area.currentTarget();
		if (t != null) {
			int px = screenXOf(t.chunkX());
			int py = screenYOf(t.chunkZ());
			Ui.rect(g, px, py, (int) Math.max(2, cell), 1, Ui.WARN);
			Ui.rect(g, px, py + (int) Math.max(2, cell) - 1, (int) Math.max(2, cell), 1, Ui.WARN);
			Ui.rect(g, px, py, 1, (int) Math.max(2, cell), Ui.WARN);
			Ui.rect(g, px + (int) Math.max(2, cell) - 1, py, 1, (int) Math.max(2, cell), Ui.WARN);
		}

		// the player
		Minecraft mc = Minecraft.getInstance();
		if (mc.player != null) {
			int px = gx + (int) Math.round((mc.player.getX() / 16.0 - firstChunkX()) * cell);
			int py = gy + (int) Math.round((mc.player.getZ() / 16.0 - firstChunkZ()) * cell);
			Ui.roundRect(g, px - 2, py - 2, 5, 5, 2, 0xFFFFFFFF);
		}

		// readout
		String label;
		int colour;
		if (selecting) {
			label = dragging
					? "%d \u00d7 %d chunks".formatted(
					Math.abs(dragNowCx - dragStartCx) + 1, Math.abs(dragNowCz - dragStartCz) + 1)
					: "drag a box to set the area";
			colour = Ui.WARN;
		} else if (hovering) {
			label = "chunk %d, %d   (block %d, %d)".formatted(hoverCx, hoverCz, hoverCx << 4, hoverCz << 4);
			colour = Ui.TEXT;
		} else {
			label = area.describe();
			colour = Ui.TEXT_MUTED;
		}
		Ui.textElided(g, f, label, w, gx, gy + size + 4, colour);
	}

	/**
	 * The area itself, as one fill per screen row rather than one per chunk.
	 *
	 * <p>A 2048-chunk view is four million cells. The screen has a couple of hundred rows
	 * whatever the zoom, so that is what this iterates instead.
	 */
	private void drawSelection(GuiGraphicsExtractor g, int gx, int gy, int size, int accent, AreaCoverage.Region region) {
		boolean preview = dragging && region == regions.getLast();
		int colour = Ui.mix(Ui.CARD, accent, 0.18);

		// a drag previews the rectangle it would set, so the fill follows the cursor
		int minCx = preview ? Math.min(dragStartCx, dragNowCx) : region.minX();
		int maxCx = preview ? Math.max(dragStartCx, dragNowCx) : region.maxX();
		int minCz = preview ? Math.min(dragStartCz, dragNowCz) : region.minZ();
		int maxCz = preview ? Math.max(dragStartCz, dragNowCz) : region.maxZ();

		double ccx = (minCx + maxCx) / 2.0;
		double ccz = (minCz + maxCz) / 2.0;
		double radius = Math.min(maxCx - minCx + 1, maxCz - minCz + 1) / 2.0;

		for (int row = 0; row < size; row++) {
			int cz = chunkZAt(gy + row);
			if (cz < minCz || cz > maxCz) continue;

			int fromCx = minCx, toCx = maxCx;
			if (region.circular() && !preview) {
				// the same test contains() makes, solved for the row rather than asked per cell
				double dz = cz - ccz;
				double half = radius * radius - dz * dz;
				if (half < 0) continue;
				half = Math.sqrt(half);
				fromCx = Math.max(fromCx, (int) Math.ceil(ccx - half));
				toCx = Math.min(toCx, (int) Math.floor(ccx + half));
				if (toCx < fromCx) continue;
			}

			int x1 = Math.max(gx, screenXOf(fromCx));
			int x2 = Math.min(gx + size, screenXOf(toCx + 1));
			if (x2 > x1) Ui.rect(g, x1, gy + row, x2 - x1, 1, colour);
		}
	}

	/**
	 * Covered chunks, rasterised once into a pixel mask and then drawn as horizontal runs.
	 *
	 * <p>Drawing one rectangle per visited chunk is what made a wide view crawl: a swept
	 * 500-chunk square is a quarter of a million fills, sixty times a second, most of them
	 * landing on the same pixel. Runs collapse each row to a handful, and the whole thing is
	 * only rebuilt when the coverage or the view actually changes - which is at most once a
	 * tick while walking, and never at all while the bot is idle.
	 */
	private void drawVisited(GuiGraphicsExtractor g, int gx, int gy, int size, int accent) {
		String key = size + ":" + viewChunks() + ":" + firstChunkX() + ":" + firstChunkZ()
				+ ":" + area.version() + ":" + cfg.areaThisWorldOnly;
		if (!key.equals(runKey)) {
			runKey = key;
			rebuildRuns(size);
		}
		int colour = Ui.mix(Ui.CARD, accent, 0.75);
		for (int i = 0; i + 2 < runCount; i += 3) {
			Ui.rect(g, gx + runs[i + 1], gy + runs[i], runs[i + 2], 1, colour);
		}
	}

	private void rebuildRuns(int size) {
		if (mask == null || mask.length != size * size) mask = new boolean[size * size];
		else java.util.Arrays.fill(mask, false);

		int gx = gridX(), gy = gridY();
		for (AreaCoverage.Region region : regions) {
			for (long chunk : region.visited()) {
				int cx = (int) (chunk >> 32), cz = (int) chunk;
				int sx = screenXOf(cx) - gx;
				int sy = screenYOf(cz) - gy;
				if (sx >= size || sy >= size) continue;
				int x1 = Math.max(0, sx), y1 = Math.max(0, sy);
				if (x1 >= size || y1 >= size) continue;
				if (!region.contains(cx, cz)) continue;

				// at least one pixel: zoomed out, a whole chunk is narrower than one
				int x2 = Math.min(size, Math.max(x1 + 1, screenXOf(cx + 1) - gx));
				int y2 = Math.min(size, Math.max(y1 + 1, screenYOf(cz + 1) - gy));
				for (int py = y1; py < y2; py++) {
					int base = py * size;
					for (int px = x1; px < x2; px++) mask[base + px] = true;
				}
			}

		}

		runCount = 0;
		for (int row = 0; row < size; row++) {
			int base = row * size;
			int start = -1;
			for (int col = 0; col <= size; col++) {
				boolean on = col < size && mask[base + col];
				if (on && start < 0) {
					start = col;
				} else if (!on && start >= 0) {
					addRun(row, start, col - start);
					start = -1;
				}
			}
		}
	}

	private void addRun(int row, int col, int length) {
		if (runs == null || runCount + 3 > runs.length) {
			runs = runs == null ? new int[768] : java.util.Arrays.copyOf(runs, runs.length * 2);
		}
		runs[runCount] = row;
		runs[runCount + 1] = col;
		runs[runCount + 2] = length;
		runCount += 3;
	}

	/**
	 * Grid lines, thinned so they stay a grid. Below a few pixels apart they stop reading as
	 * lines and start reading as a grey wash, at the cost of one fill each.
	 */
	private void drawGridLines(GuiGraphicsExtractor g, int gx, int gy, int size, double cell) {
		int step = 1;
		while (step * cell < 6 && step < 1 << 22) step *= 2;
		if (step * cell < 6) return; // nothing can be drawn legibly at this zoom

		// each line is at least six pixels from the last, so neither loop runs more than
		// size/6 times however far out the view is
		double firstX = firstChunkX(), firstZ = firstChunkZ();
		long major = step * 8L;

		for (long cx = (long) Math.ceil(firstX / step) * step; ; cx += step) {
			int px = gx + (int) Math.round((cx - firstX) * cell);
			if (px > gx + size) break;
			if (px >= gx) Ui.rect(g, px, gy, 1, size, cx % major == 0 ? Ui.BORDER : Ui.BORDER_SOFT);
		}
		for (long cz = (long) Math.ceil(firstZ / step) * step; ; cz += step) {
			int py = gy + (int) Math.round((cz - firstZ) * cell);
			if (py > gy + size) break;
			if (py >= gy) Ui.rect(g, gx, py, size, 1, cz % major == 0 ? Ui.BORDER : Ui.BORDER_SOFT);
		}
	}

	/**
	 * The world border. Drawn after the coverage and before the selection, because a sweep
	 * that runs past it is describing chunks that are not there — which is worth seeing
	 * underneath the region you are about to draw.
	 */
	private void drawWorldBorder(GuiGraphicsExtractor g, int gx, int gy, int size) {
		WorldBounds b = WorldBounds.current();
		if (b == null) return;
		int x1 = screenXOf(b.minX() / 16.0), x2 = screenXOf(b.maxX() / 16.0);
		int y1 = screenYOf(b.minZ() / 16.0), y2 = screenYOf(b.maxZ() / 16.0);
		if (x2 < gx || x1 > gx + size || y2 < gy || y1 > gy + size) return; // nowhere near

		int cx1 = Math.max(gx, x1), cx2 = Math.min(gx + size, x2);
		int cy1 = Math.max(gy, y1), cy2 = Math.min(gy + size, y2);
		if (x1 >= gx && x1 <= gx + size) Ui.rect(g, x1, cy1, 1, cy2 - cy1, Ui.WORLD_EDGE);
		if (x2 >= gx && x2 <= gx + size) Ui.rect(g, x2, cy1, 1, cy2 - cy1, Ui.WORLD_EDGE);
		if (y1 >= gy && y1 <= gy + size) Ui.rect(g, cx1, y1, cx2 - cx1, 1, Ui.WORLD_EDGE);
		if (y2 >= gy && y2 <= gy + size) Ui.rect(g, cx1, y2, cx2 - cx1, 1, Ui.WORLD_EDGE);
	}

	private void drawSelectionOutline(GuiGraphicsExtractor g, int accent, AreaCoverage.Region region) {
		boolean preview = dragging && region == regions.getLast();
		int minCx = preview ? Math.min(dragStartCx, dragNowCx) : region.minX();
		int maxCx = preview ? Math.max(dragStartCx, dragNowCx) : region.maxX();
		int minCz = preview ? Math.min(dragStartCz, dragNowCz) : region.minZ();
		int maxCz = preview ? Math.max(dragStartCz, dragNowCz) : region.maxZ();

		int x1 = screenXOf(minCx), y1 = screenYOf(minCz);
		int x2 = screenXOf(maxCx + 1), y2 = screenYOf(maxCz + 1);
		int gx = gridX(), gy = gridY(), size = gridSize();

		// clip to the visible grid so an area larger than the view still frames correctly
		int cx1 = Math.max(gx, Math.min(gx + size, x1));
		int cy1 = Math.max(gy, Math.min(gy + size, y1));
		int cx2 = Math.max(gx, Math.min(gx + size, x2));
		int cy2 = Math.max(gy, Math.min(gy + size, y2));
		if (cx2 <= cx1 || cy2 <= cy1) return;

		int colour = preview ? Ui.WARN : accent;
		Ui.rect(g, cx1, cy1, cx2 - cx1, 1, colour);
		Ui.rect(g, cx1, cy2 - 1, cx2 - cx1, 1, colour);
		Ui.rect(g, cx1, cy1, 1, cy2 - cy1, colour);
		Ui.rect(g, cx2 - 1, cy1, 1, cy2 - cy1, colour);
	}

	// ---------------------------------------------------------------- input

	@Override
	public boolean mouseClicked(double mx, double my, int button) {
		return mouseClicked(mx, my, button, false);
	}

	@Override
	public boolean mouseClicked(double mx, double my, int button, boolean doubled) {
		if (!insideGrid(mx, my)) return false;

		if (button == 1) {
			// right-click marks a single chunk as already done, for skipping somewhere you know
			int cx = chunkXAt(mx), cz = chunkZAt(my);
			if (area.isVisited(cx, cz)) area.unmark(cx, cz);
			else area.markCovered(cx, cz, 0);
			return true;
		}
		if (button != 0) return false;

		// a double click arms a new selection; the first of the two started a pan that has
		// not moved anywhere, so dropping it costs nothing
		if (doubled) {
			selecting = true;
			panning = false;
		}

		if (selecting) {
			dragging = true;
			panning = false;
			dragStartCx = dragNowCx = chunkXAt(mx);
			dragStartCz = dragNowCz = chunkZAt(my);
			return true;
		}

		// otherwise the drag moves the view, which is the safe default. Read the centre
		// BEFORE dropping follow: centreChunkX() answers with the stale pan the moment
		// following is false, so clearing it first makes the view jump back to wherever it
		// was before "Centre on me" as soon as the button goes down.
		panCx = centreChunkX();
		panCz = centreChunkZ();
		panning = true;
		following = false;
		grabChunkX = preciseChunkXAt(mx);
		grabChunkZ = preciseChunkZAt(my);
		return true;
	}

	@Override
	public void mouseDragged(double mx, double my) {
		if (dragging) {
			dragNowCx = chunkXAt(mx);
			dragNowCz = chunkZAt(my);
			return;
		}
		if (!panning) return;
		// keep whatever chunk was grabbed underneath the cursor
		panCx += grabChunkX - preciseChunkXAt(mx);
		panCz += grabChunkZ - preciseChunkZAt(my);
	}

	@Override
	public void mouseReleased() {
		panning = false;
		if (!dragging) return;
		dragging = false;
		selecting = false; // one drag per arming, so a slip cannot take the area with it
		int minCx = Math.min(dragStartCx, dragNowCx), maxCx = Math.max(dragStartCx, dragNowCx);
		int minCz = Math.min(dragStartCz, dragNowCz), maxCz = Math.max(dragStartCz, dragNowCz);
		// a click without a drag is not an area, ignore it rather than making a 1-chunk job
		if (minCx == maxCx && minCz == maxCz) return;
		area.setCorners(minCx << 4, minCz << 4, (maxCx << 4) + 15, (maxCz << 4) + 15);
		area.touch();
		area.save();
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double amount) {
		if (!insideGrid(mx, my) || amount == 0) return false;
		int view = viewChunks();
		// clamped on the way in as well as on the way out, so zooming past the border does
		// not silently bank notches that have to be scrolled back through
		cfg.areaMapView = amount > 0
				? (int) Math.max(4, Math.round(view / 1.5))
				: (int) Math.min(maxViewChunks, Math.round(view * 1.5));
		return true;
	}

	@Override
	public boolean hovered(double mx, double my) {
		return insideGrid(mx, my);
	}

	public boolean isDragging() {
		return dragging;
	}
}
