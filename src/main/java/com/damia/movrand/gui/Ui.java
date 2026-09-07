package com.damia.movrand.gui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Palette and drawing helpers. Everything the screen draws goes through here so the
 * whole GUI shares one visual language.
 */
public final class Ui {

	public static final int BACKDROP = 0xE607070B;
	public static final int PANEL = 0xFF14141B;
	public static final int PANEL_TOP = 0xFF1B1B25;
	public static final int SIDEBAR = 0xFF101017;
	public static final int CARD = 0xFF1C1C26;
	public static final int CARD_HOVER = 0xFF24242F;
	public static final int TRACK = 0xFF2B2B38;
	public static final int BORDER = 0xFF2E2E3C;
	public static final int BORDER_SOFT = 0xFF23232F;

	public static final int TEXT = 0xFFECECF5;
	public static final int TEXT_MUTED = 0xFF8E8EA2;
	public static final int TEXT_FAINT = 0xFF5F5F72;

	public static final int GOOD = 0xFF4ADE80;
	public static final int WARN = 0xFFFBBF24;
	public static final int BAD = 0xFFF87171;
	/** The world border on the maps. Orange, because no accent and no other marker is. */
	public static final int WORLD_EDGE = 0xFFFF7A45;

	private Ui() {
	}

	// ---------------------------------------------------------------- colour

	public static int alpha(int argb, double a) {
		int aa = (int) Math.round(Math.max(0, Math.min(1, a)) * 255);
		return (aa << 24) | (argb & 0x00FFFFFF);
	}

	public static int mix(int a, int b, double t) {
		t = Math.max(0, Math.min(1, t));
		int out = 0;
		for (int shift = 0; shift < 32; shift += 8) {
			int ca = (a >>> shift) & 0xFF;
			int cb = (b >>> shift) & 0xFF;
			out |= ((int) Math.round(ca + (cb - ca) * t) & 0xFF) << shift;
		}
		return out;
	}

	/** Darker sibling of the accent, for gradients and pressed states. */
	public static int shade(int argb, double factor) {
		int a = (argb >>> 24) & 0xFF;
		int r = (int) Math.min(255, ((argb >> 16) & 0xFF) * factor);
		int g = (int) Math.min(255, ((argb >> 8) & 0xFF) * factor);
		int b = (int) Math.min(255, (argb & 0xFF) * factor);
		return (a << 24) | (r << 16) | (g << 8) | b;
	}

	// -------------------------------------------------------------- shapes

	public static void rect(GuiGraphicsExtractor g, int x, int y, int w, int h, int colour) {
		if (w <= 0 || h <= 0) return;
		g.fill(x, y, x + w, y + h, colour);
	}

	/**
	 * Vanilla has no rounded primitive, so the corners are cut row by row against a
	 * circle. Only the {@code r} rows at each end need per-row work; the middle is one fill.
	 */
	public static void roundRect(GuiGraphicsExtractor g, int x, int y, int w, int h, int r, int colour) {
		if (w <= 0 || h <= 0) return;
		r = Math.max(0, Math.min(r, Math.min(w, h) / 2));
		if (r == 0) {
			rect(g, x, y, w, h, colour);
			return;
		}
		for (int dy = 0; dy < r; dy++) {
			int inset = cornerInset(r, dy);
			rect(g, x + inset, y + dy, w - inset * 2, 1, colour);
			rect(g, x + inset, y + h - 1 - dy, w - inset * 2, 1, colour);
		}
		rect(g, x, y + r, w, h - r * 2, colour);
	}

	private static int cornerInset(int r, int dy) {
		double yy = r - dy - 0.5;
		return (int) Math.round(r - Math.sqrt(Math.max(0, r * r - yy * yy)));
	}

	/** Filled rounded rect with a one-pixel border. */
	public static void card(GuiGraphicsExtractor g, int x, int y, int w, int h, int r, int fill, int border) {
		roundRect(g, x, y, w, h, r, border);
		roundRect(g, x + 1, y + 1, w - 2, h - 2, Math.max(0, r - 1), fill);
	}

	public static void gradient(GuiGraphicsExtractor g, int x, int y, int w, int h, int top, int bottom) {
		if (w <= 0 || h <= 0) return;
		g.fillGradient(x, y, x + w, y + h, top, bottom);
	}

	/** A rounded pill with a vertical accent gradient — used for buttons and badges. */
	public static void pill(GuiGraphicsExtractor g, int x, int y, int w, int h, int colour) {
		int r = h / 2;
		roundRect(g, x, y, w, h, r, colour);
	}

	public static void hLine(GuiGraphicsExtractor g, int x, int y, int w, int colour) {
		rect(g, x, y, w, 1, colour);
	}

	// ---------------------------------------------------------------- text

	private static GuiGraphicsExtractor hoverGraphics;
	private static int hoverX, hoverY;

	/** Scope hover detection to the current screen frame, including its active scissors. */
	public static void beginTextHover(GuiGraphicsExtractor g, int mx, int my) {
		hoverGraphics = g;
		hoverX = mx;
		hoverY = my;
	}

	public static void endTextHover() {
		hoverGraphics = null;
	}

	public static void textElided(GuiGraphicsExtractor g, Font f, String s, int maxWidth,
	                             int x, int y, int colour) {
		drawElided(g, f, s, maxWidth, x, y, colour, 0);
	}

	public static void textRightElided(GuiGraphicsExtractor g, Font f, String s, int maxWidth,
	                                  int right, int y, int colour) {
		drawElided(g, f, s, maxWidth, right, y, colour, 1);
	}

	public static void textCentreElided(GuiGraphicsExtractor g, Font f, String s, int maxWidth,
	                                   int centre, int y, int colour) {
		drawElided(g, f, s, maxWidth, centre, y, colour, 2);
	}

	private static void drawElided(GuiGraphicsExtractor g, Font f, String s, int maxWidth,
	                              int x, int y, int colour, int alignment) {
		String visible = elide(f, s, maxWidth);
		int textWidth = f.width(visible);
		if (alignment == 1) x -= textWidth;
		else if (alignment == 2) x -= textWidth / 2;
		text(g, f, visible, x, y, colour);
		if (g == hoverGraphics && textHoverHit(!visible.equals(s),
				g.containsPointInScissor(hoverX, hoverY), hoverX, hoverY, x, y, textWidth, f.lineHeight)) {
			// Vanilla positions the wrapped overlay inside the window, after scissored content.
			g.setTooltipForNextFrame(f, f.split(net.minecraft.network.chat.Component.literal(s),
					Math.max(1, Math.min(360, g.guiWidth() - 24))), hoverX, hoverY);
		}
	}

	static boolean textHoverHit(boolean clipped, boolean inScissor, int mx, int my,
	                            int x, int y, int w, int h) {
		return clipped && inScissor && mx >= x && mx < x + w && my >= y && my < y + h;
	}

	public static void text(GuiGraphicsExtractor g, Font f, String s, int x, int y, int colour) {
		g.text(f, s, x, y, colour, false);
	}

	public static void textShadow(GuiGraphicsExtractor g, Font f, String s, int x, int y, int colour) {
		g.text(f, s, x, y, colour, true);
	}

	public static void textRight(GuiGraphicsExtractor g, Font f, String s, int right, int y, int colour) {
		g.text(f, s, right - f.width(s), y, colour, false);
	}

	public static void textCentre(GuiGraphicsExtractor g, Font f, String s, int centreX, int y, int colour) {
		g.text(f, s, centreX - f.width(s) / 2, y, colour, false);
	}

	/** Cuts a string down to width and appends an ellipsis. */
	public static String elide(Font f, String s, int maxWidth) {
		if (f.width(s) <= maxWidth) return s;
		String cut = f.plainSubstrByWidth(s, Math.max(0, maxWidth - f.width("…")));
		return cut + "…";
	}

	/** Greedy word wrap. Font.split works in FormattedCharSequence, which these helpers avoid. */
	public static java.util.List<String> wrap(Font f, String text, int maxWidth) {
		java.util.List<String> lines = new java.util.ArrayList<>();
		if (text == null || text.isEmpty()) return lines;
		StringBuilder line = new StringBuilder();
		for (String word : text.split(" ")) {
			String candidate = line.isEmpty() ? word : line + " " + word;
			if (f.width(candidate) > maxWidth && !line.isEmpty()) {
				lines.add(line.toString());
				line = new StringBuilder(word);
			} else {
				line = new StringBuilder(candidate);
			}
		}
		if (!line.isEmpty()) lines.add(line.toString());
		return lines;
	}

	public static String seconds(double s) {
		if (s < 1) return String.format("%.0fms", s * 1000);
		if (s < 60) return String.format("%.1fs", s);
		return String.format("%dm %02ds", (int) (s / 60), (int) (s % 60));
	}
}
