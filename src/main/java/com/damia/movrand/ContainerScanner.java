package com.damia.movrand;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.BrewingStandBlock;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.CrafterBlock;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.EnderChestBlock;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.entity.BeaconBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.EnchantingTableBlockEntity;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;
import net.minecraft.world.level.block.entity.TrialSpawnerBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.util.ArrayList;
import java.util.List;

/**
 * Counts storage blocks around the player — the tell-tale of a base, a farm or a shop.
 *
 * <p>Range is not limited by what is on screen. The server sends every block entity of a
 * loaded chunk, so this sees chests behind you, through walls, and hundreds of blocks
 * straight down. The only real ceiling is chunk loading itself: nothing client-side can
 * know about a chunk the server has not sent, which is why the default radius follows the
 * effective render distance and unloaded chunks are skipped rather than guessed at.
 */
public final class ContainerScanner {

	/** A single notable block worth writing into the log. */
	public record Landmark(BlockPos pos, String name) {
	}

	public record Result(int hoppers, int chests, int barrels, int shulkers, int droppers, int furnaces,
	                     int stations, int total, int grouped, BlockPos densestChunkCentre,
	                     List<Landmark> landmarks,
	                     int chunksScanned, int chunksSkipped, int radiusUsed, int minY, int maxY,
	                     double millis, BlockPos nearest, double nearestDistance, int outsideBand) {

		public static final Result EMPTY =
				new Result(0, 0, 0, 0, 0, 0, 0, 0, 0, null, List.of(), 0, 0, 0, 0, 0, 0, null, -1, 0);

		public String summary() {
			StringBuilder sb = new StringBuilder();
			if (hoppers > 0) sb.append(hoppers).append(" hopper").append(hoppers == 1 ? "" : "s").append(", ");
			if (chests > 0) sb.append(chests).append(" chest").append(chests == 1 ? "" : "s").append(", ");
			if (barrels > 0) sb.append(barrels).append(" barrel").append(barrels == 1 ? "" : "s").append(", ");
			if (shulkers > 0) sb.append(shulkers).append(" shulker").append(shulkers == 1 ? "" : "s").append(", ");
			if (droppers > 0) sb.append(droppers).append(" dropper/dispenser, ");
			if (furnaces > 0) sb.append(furnaces).append(" furnace").append(furnaces == 1 ? "" : "s").append(", ");
			if (stations > 0) sb.append(stations).append(" station").append(stations == 1 ? "" : "s").append(", ");
			if (sb.isEmpty()) return "nothing nearby";
			sb.setLength(sb.length() - 2);
			return sb.toString();
		}

		public String coverage() {
			return "%d chunks (%d not loaded) · y %d to %d · %.1fms"
					.formatted(chunksScanned, chunksSkipped, minY, maxY, millis);
		}

		/** What the threshold is measured against, spelled out. */
		public String groupNote() {
			if (grouped >= total) return "everything in range";
			return "%d of %d in one place".formatted(grouped, total);
		}

		/** What the height band is costing you, so a band that is too tight is obvious. */
		public String bandNote() {
			if (outsideBand == 0) return "nothing outside the band";
			return "%d ignored for being outside y %d to %d".formatted(outsideBand, minY, maxY);
		}
	}

	private ContainerScanner() {
	}

	/**
	 * The widest radius worth trying. Chunks past the render distance are simply not on
	 * the client, so asking for more only wastes null lookups.
	 */
	public static int autoRadius() {
		Minecraft mc = Minecraft.getInstance();
		// +1 covers the ring that is loaded but not drawn
		return Math.max(2, Math.min(64, mc.options.getEffectiveRenderDistance() + 1));
	}

	public static int effectiveRadius(Config cfg) {
		return cfg.containerAutoRadius ? autoRadius() : Math.max(0, cfg.containerChunkRadius);
	}

	public static Result scan(ClientLevel level, LocalPlayer player, Config cfg) {
		long startNanos = System.nanoTime();

		BlockPos origin = player.blockPosition();
		int cx = origin.getX() >> 4;
		int cz = origin.getZ() >> 4;
		int r = effectiveRadius(cfg);

		// the band never reaches past the world itself, so a leftover overworld range does not
		// silently report "y -64 to 320" while standing in a nether that stops at 128
		int minY = Math.max(level.getMinY(), bandMinY(cfg, origin.getY()));
		int maxY = Math.min(level.getMaxY(), bandMaxY(cfg, origin.getY()));

		// one cell per scanned chunk, so the grouping below has something to slide a window over
		int side = 2 * r + 1;
		int[] grid = new int[side * side];
		BlockPos[] samples = new BlockPos[side * side];

		int hoppers = 0, chests = 0, barrels = 0, shulkers = 0, droppers = 0, furnaces = 0, stations = 0;
		int scanned = 0, skipped = 0, outsideBand = 0;
		int densest = -1;
		BlockPos densestCentre = null;
		BlockPos nearest = null;
		double nearestDistSq = Double.MAX_VALUE;
		List<Landmark> landmarks = new ArrayList<>();

		for (int dx = -r; dx <= r; dx++) {
			for (int dz = -r; dz <= r; dz++) {
				// load=false: never force a chunk in, just report what the server already sent
				LevelChunk chunk = level.getChunkSource().getChunk(cx + dx, cz + dz, ChunkStatus.FULL, false);
				if (chunk == null) {
					skipped++;
					continue;
				}
				scanned++;
				int inChunk = 0;
				BlockPos sample = null;

				for (BlockEntity be : chunk.getBlockEntities().values()) {
					BlockPos pos = be.getBlockPos();
					boolean inBand = pos.getY() >= minY && pos.getY() <= maxY;

					String landmark = landmarkName(be, cfg);
					if (landmark != null && inBand) landmarks.add(new Landmark(pos, landmark));

					// classify first, count second: something has to know how many matches the
					// band threw away, or a band set too tight just looks like an empty world
					Category category = categorise(be.getBlockState().getBlock(), cfg);
					if (category == null) continue;
					if (!inBand) {
						outsideBand++;
						continue;
					}
					switch (category) {
						case HOPPER -> hoppers++;
						case CHEST -> chests++;
						case BARREL -> barrels++;
						case SHULKER -> shulkers++;
						case DROPPER -> droppers++;
						case FURNACE -> furnaces++;
						case STATION -> stations++;
					}
					inChunk++;
					if (sample == null) sample = pos;

					double d = pos.distSqr(origin);
					if (d < nearestDistSq) {
						nearestDistSq = d;
						nearest = pos;
					}
				}

				grid[(dz + r) * side + (dx + r)] = inChunk;
				samples[(dz + r) * side + (dx + r)] = sample;

				if (inChunk > densest) {
					densest = inChunk;
					// a real container beats a synthetic chunk centre at the player's own altitude:
					// the whole point of the entry is being able to walk back to it
					densestCentre = sample != null
							? sample
							: new BlockPos(((cx + dx) << 4) + 8, origin.getY(), ((cz + dz) << 4) + 8);
				}
			}
		}

		int total = hoppers + chests + barrels + shulkers + droppers + furnaces + stations;

		int grouped = total;
		BlockPos centre = densestCentre;
		if (cfg.containerGroupEnabled && total > 0) {
			Group g = bestGroup(grid, side, Math.max(0, cfg.containerGroupChunks));
			grouped = g.count();
			if (g.index() >= 0) {
				BlockPos sample = samples[g.index()];
				centre = sample != null ? sample : new BlockPos(
						((cx + g.index() % side - r) << 4) + 8, origin.getY(),
						((cz + g.index() / side - r) << 4) + 8);
			}
		}

		double millis = (System.nanoTime() - startNanos) / 1_000_000.0;
		return new Result(hoppers, chests, barrels, shulkers, droppers, furnaces, stations, total, grouped,
				total > 0 ? centre : null, landmarks,
				scanned, skipped, r, minY, maxY, millis,
				nearest, nearest == null ? -1 : Math.sqrt(nearestDistSq), outsideBand);
	}

	/** How full the fullest patch is, and which chunk inside it to point at. */
	record Group(int count, int index) {
	}

	/**
	 * The densest patch of chunks, rather than everything within reach.
	 *
	 * <p>A total over the whole radius answers "is there storage anywhere near me", which at
	 * the default reach is a thousand blocks in every direction — three unrelated farms and a
	 * village adding up to a base that does not exist. A window sum answers the question
	 * actually worth asking: is there a lot of storage <em>in one place</em>.
	 *
	 * <p>Summed-area table, so the window size costs nothing: every rectangle is four lookups
	 * however wide it is, and a 64-chunk scan with a 33-chunk window is the same work as a
	 * 1-chunk one.
	 */
	static Group bestGroup(int[] grid, int side, int k) {
		int stride = side + 1;
		long[] sat = new long[stride * stride];
		for (int j = 0; j < side; j++) {
			for (int i = 0; i < side; i++) {
				sat[(j + 1) * stride + i + 1] = grid[j * side + i]
						+ sat[j * stride + i + 1] + sat[(j + 1) * stride + i] - sat[j * stride + i];
			}
		}

		int best = 0, bx1 = 0, by1 = 0, bx2 = 0, by2 = 0;
		for (int j = 0; j < side; j++) {
			for (int i = 0; i < side; i++) {
				int x1 = Math.max(0, i - k), x2 = Math.min(side, i + k + 1);
				int y1 = Math.max(0, j - k), y2 = Math.min(side, j + k + 1);
				int sum = (int) (sat[y2 * stride + x2] - sat[y1 * stride + x2]
						- sat[y2 * stride + x1] + sat[y1 * stride + x1]);
				if (sum > best) {
					best = sum;
					bx1 = x1;
					by1 = y1;
					bx2 = x2;
					by2 = y2;
				}
			}
		}
		// the fullest chunk in the winning window, worked out once rather than per candidate
		return new Group(best, best == 0 ? -1 : fullestIn(grid, side, bx1, by1, bx2, by2));
	}

	private static int fullestIn(int[] grid, int side, int x1, int y1, int x2, int y2) {
		int best = 0, index = -1;
		for (int j = y1; j < y2; j++) {
			for (int i = x1; i < x2; i++) {
				if (grid[j * side + i] > best) {
					best = grid[j * side + i];
					index = j * side + i;
				}
			}
		}
		return index;
	}

	/** The kinds of block that count toward the total, once the user's filters have had a say. */
	private enum Category {
		HOPPER, CHEST, BARREL, SHULKER, DROPPER, FURNACE, STATION
	}

	private static Category categorise(Block b, Config cfg) {
		if (cfg.scanHoppers && b instanceof HopperBlock) return Category.HOPPER;
		if (cfg.scanChests && (b instanceof ChestBlock || b instanceof EnderChestBlock)) return Category.CHEST;
		if (cfg.scanBarrels && b instanceof BarrelBlock) return Category.BARREL;
		if (cfg.scanShulkers && b instanceof ShulkerBoxBlock) return Category.SHULKER;
		// DropperBlock extends DispenserBlock, so one test covers both
		if (cfg.scanDroppersDispensers && b instanceof DispenserBlock) return Category.DROPPER;
		if (cfg.scanFurnaces && b instanceof AbstractFurnaceBlock) return Category.FURNACE;
		if (cfg.scanCraftingStations && (b instanceof CrafterBlock || b instanceof BrewingStandBlock)) {
			return Category.STATION;
		}
		return null;
	}

	/** The bottom of the band, before the world's own floor is applied. */
	public static int bandMinY(Config cfg, int playerY) {
		return switch (cfg.containerHeightRange) {
			case FULL -> Integer.MIN_VALUE / 4;
			case RELATIVE -> playerY - cfg.containerYRange;
			case ABSOLUTE -> cfg.containerMinY;
		};
	}

	/** The top of the band, before the world's own ceiling is applied. */
	public static int bandMaxY(Config cfg, int playerY) {
		return switch (cfg.containerHeightRange) {
			case FULL -> Integer.MAX_VALUE / 4;
			case RELATIVE -> playerY + cfg.containerYRange;
			case ABSOLUTE -> cfg.containerMaxY;
		};
	}

	/** What the current setting means, in words, without needing a world loaded. */
	public static String describeBand(Config cfg) {
		return switch (cfg.containerHeightRange) {
			case FULL -> "the whole world column";
			case RELATIVE -> "y \u00b1%d around you".formatted(cfg.containerYRange);
			case ABSOLUTE -> "y %d to %d".formatted(cfg.containerMinY, cfg.containerMaxY);
		};
	}

	/**
	 * Self-check:
	 * {@code ./gradlew selfCheck -Pcheck=com.damia.movrand.ContainerScanner}
	 *
	 * <p>The band is three modes, a migration and two clamps, which is exactly the sort of
	 * thing that quietly starts reporting the wrong slice of the world.
	 */
	public static void main(String[] args) {
		Config c = new Config();
		c.clampAll();

		// a config written before the mode existed still says what it wanted
		Config old = new Config();
		old.containerHeightRange = null;
		old.containerFullHeight = false;
		old.clampAll();
		assert old.containerHeightRange == Config.HeightRange.RELATIVE
				: "an old config with full height off should migrate to a band around the player";
		Config oldFull = new Config();
		oldFull.containerHeightRange = null;
		oldFull.containerFullHeight = true;
		oldFull.clampAll();
		assert oldFull.containerHeightRange == Config.HeightRange.FULL : "full height did not migrate";

		// and the boolean is kept in step so the json never disagrees with itself
		c.containerHeightRange = Config.HeightRange.ABSOLUTE;
		c.clampAll();
		assert !c.containerFullHeight : "the legacy flag drifted out of step with the mode";

		// full height must reach past any world, in both directions
		c.containerHeightRange = Config.HeightRange.FULL;
		assert bandMinY(c, 64) < -2048 && bandMaxY(c, 64) > 2048 : "full height does not span a world";
		assert Math.max(-64, bandMinY(c, 64)) == -64 && Math.min(320, bandMaxY(c, 64)) == 320
				: "full height should clamp to whatever the world allows";

		// a band around the player follows the player
		c.containerHeightRange = Config.HeightRange.RELATIVE;
		c.containerYRange = 32;
		assert bandMinY(c, 100) == 68 && bandMaxY(c, 100) == 132 : "the relative band is off centre";
		assert bandMinY(c, 8) == -24 && bandMaxY(c, 8) == 40 : "the relative band did not move with the player";

		// a fixed band does not
		c.containerHeightRange = Config.HeightRange.ABSOLUTE;
		c.containerMinY = -60;
		c.containerMaxY = 12;
		c.clampAll();
		assert bandMinY(c, 100) == -60 && bandMaxY(c, 100) == 12 : "the fixed band moved with the player";
		assert bandMinY(c, -50) == -60 && bandMaxY(c, -50) == 12 : "the fixed band moved with the player";
		assert describeBand(c).equals("y -60 to 12") : describeBand(c);

		// an inverted band is a typo, not an empty result
		c.containerMinY = 200;
		c.containerMaxY = 10;
		c.clampAll();
		assert c.containerMinY <= c.containerMaxY
				: "an inverted band survived the clamps: %d to %d".formatted(c.containerMinY, c.containerMaxY);

		// and a hand-edited absurdity cannot escape the world by more than the clamp allows
		c.containerMinY = -999_999;
		c.containerMaxY = 999_999;
		c.clampAll();
		assert c.containerMinY >= -2048 && c.containerMaxY <= 2048 : "the band clamp let something through";

		// every mode has to describe itself, or the readout lies
		for (Config.HeightRange mode : Config.HeightRange.values()) {
			c.containerHeightRange = mode;
			assert !describeBand(c).isBlank() : mode + " has no description";
			assert bandMinY(c, 64) <= bandMaxY(c, 64) : mode + " produced an inverted band";
		}

		// --- grouping: the fullest patch, not everything in range ---
		// a 9x9 scan with two separate piles, one of 6 and one of 10
		int side = 9;
		int[] grid = new int[side * side];
		grid[0 * side + 0] = 3;
		grid[1 * side + 1] = 3;   // pile A, 6 across two touching chunks
		grid[7 * side + 7] = 4;
		grid[8 * side + 8] = 6;   // pile B, 10 across two touching chunks
		int all = 0;
		for (int v : grid) all += v;
		assert all == 16 : "the test grid holds " + all;

		Group single = bestGroup(grid, side, 0);
		assert single.count() == 6 : "a single chunk should find the fullest one, got " + single.count();
		assert single.index() == 8 * side + 8 : "pointed at the wrong chunk: " + single.index();

		Group patch = bestGroup(grid, side, 1);
		assert patch.count() == 10 : "a 3x3 window should hold pile B whole, got " + patch.count();
		assert patch.index() == 8 * side + 8 : "should point at the fullest chunk in the window";

		Group wide = bestGroup(grid, side, 8);
		assert wide.count() == all : "a window covering everything must equal the total, got " + wide.count();

		// a wider window can never find less than a narrower one
		int last = 0;
		for (int k = 0; k <= side; k++) {
			int count = bestGroup(grid, side, k).count();
			assert count >= last : "widening the window from %d lost %d blocks".formatted(k, last - count);
			last = count;
		}

		// an empty world has no group and nothing to point at
		Group none = bestGroup(new int[side * side], side, 2);
		assert none.count() == 0 && none.index() == -1 : "an empty scan invented a group";

		// the window must clamp at the edges rather than wrapping round
		int[] corner = new int[side * side];
		corner[0] = 5;
		corner[side - 1] = 5; // opposite corners of the top row
		assert bestGroup(corner, side, 1).count() == 5
				: "a window at the edge reached round to the other side";

		// grouping being off has to mean the whole scan, exactly as before
		Config off = new Config();
		off.clampAll();
		assert off.containerGroupEnabled : "grouping should be on by default";
		assert off.containerGroupChunks == 2 : "default group radius changed: " + off.containerGroupChunks;

		System.out.println("ContainerScanner self-check passed");
	}

	private static String landmarkName(BlockEntity be, Config cfg) {
		if (cfg.scanSpawners && be instanceof SpawnerBlockEntity) return "Mob spawner";
		if (cfg.scanSpawners && be instanceof TrialSpawnerBlockEntity) return "Trial spawner";
		if (cfg.scanBeacons && be instanceof BeaconBlockEntity) return "Beacon";
		if (cfg.scanEnchantingTables && be instanceof EnchantingTableBlockEntity) return "Enchanting table";
		return null;
	}
}
