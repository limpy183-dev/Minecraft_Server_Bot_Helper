package com.damia.movrand;

import net.minecraft.client.Minecraft;
import net.minecraft.world.level.border.WorldBorder;

/**
 * The world border, as four block coordinates.
 *
 * <p>Both maps want it for the same two things: it is the edge worth drawing, and it is the
 * point past which zooming out stops showing anything — every chunk beyond it is not a place.
 *
 * <p>A vanilla border is sixty million blocks across, so on an ordinary world this changes
 * nothing visible, which is the right answer: the world really is that big. On a server that
 * sets one, it is the whole map.
 */
public record WorldBounds(double minX, double minZ, double maxX, double maxZ) {

	/** Null when there is no world loaded. */
	public static WorldBounds current() {
		try {
			Minecraft mc = Minecraft.getInstance();
			if (mc.level == null) return null;
			WorldBorder b = mc.level.getWorldBorder();
			return new WorldBounds(b.getMinX(), b.getMinZ(), b.getMaxX(), b.getMaxZ());
		} catch (Exception | LinkageError e) {
			return null;
		}
	}

	public double spanBlocks() {
		return Math.max(maxX - minX, maxZ - minZ);
	}

	/** Chunks across the wider side. Never below 4 — a tiny border is still a map. */
	public int spanChunks() {
		return (int) Math.max(4, Math.min(Integer.MAX_VALUE, Math.ceil(spanBlocks() / 16.0)));
	}

	/** What the config screens print, so a border that changes nothing is still visible. */
	public String describe() {
		double span = spanBlocks();
		if (span >= 1_000_000) return "%.0f million blocks across".formatted(span / 1_000_000);
		return "%,.0f blocks across  (x %,.0f to %,.0f)".formatted(span, minX, maxX);
	}

	/** The widest a map may usefully zoom out, in blocks. */
	public static double maxSpanBlocks(double ceiling) {
		WorldBounds b = current();
		return b == null ? ceiling : Math.max(64, Math.min(ceiling, b.spanBlocks()));
	}

	/** The widest a chunk map may usefully zoom out. */
	public static int maxSpanChunks(int ceiling) {
		WorldBounds b = current();
		return b == null ? ceiling : Math.max(4, Math.min(ceiling, b.spanChunks()));
	}

	/**
	 * Self-check — the arithmetic only, since a border needs a world:
	 * {@code ./gradlew selfCheck -Pcheck=com.damia.movrand.WorldBounds}
	 */
	public static void main(String[] args) {
		WorldBounds vanilla = new WorldBounds(-29999984, -29999984, 29999984, 29999984);
		assert vanilla.spanBlocks() == 59999968 : vanilla.spanBlocks();
		assert vanilla.spanChunks() == 3749998 : vanilla.spanChunks();
		assert vanilla.describe().startsWith("60 million") : vanilla.describe();

		// a server border is the case that actually bites
		WorldBounds server = new WorldBounds(-5000, -5000, 5000, 5000);
		assert server.spanBlocks() == 10000;
		assert server.spanChunks() == 625 : server.spanChunks();
		assert server.describe().contains("10,000 blocks across") : server.describe();

		// an off-centre border is measured by its wider side, not by its centre
		WorldBounds oblong = new WorldBounds(1000, -200, 3000, 600);
		assert oblong.spanBlocks() == 2000 : oblong.spanBlocks();
		assert oblong.spanChunks() == 125 : oblong.spanChunks();

		// a border smaller than the smallest map must not collapse the zoom to nothing
		WorldBounds tiny = new WorldBounds(0, 0, 3, 3);
		assert tiny.spanChunks() == 4 : "a tiny border should still leave a usable map";

		// and a ragged span still rounds up rather than cutting the last chunk off
		assert new WorldBounds(0, 0, 17, 17).spanChunks() == 4 : "17 blocks is more than one chunk";
		assert new WorldBounds(0, 0, 1600, 1600).spanChunks() == 100;

		System.out.println("WorldBounds self-check passed");
	}
}
