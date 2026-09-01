package com.damia.movrand;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Sweeps every chunk of a chosen area.
 *
 * <p>A lawnmower pattern covers ground fastest and looks exactly like a machine doing it.
 * The default route instead picks randomly among the nearest few unvisited chunks and aims
 * at a random point inside the chosen one, so the path wanders the way a person searching a
 * region wanders while still converging on full coverage.
 */
public final class AreaCoverage {

	public enum Route {
		ORGANIC("Organic", "Random pick among the nearest few chunks. Efficient but never straight."),
		NEAREST("Nearest", "Always the closest unvisited chunk. Tight, slightly robotic."),
		SERPENTINE("Serpentine", "Row by row, alternating direction. Fastest, most obviously a bot."),
		SPIRAL("Spiral", "Works outward from the centre."),
		RANDOM("Random", "Any unvisited chunk at all. Wanders a lot, covers slowly.");

		public final String label, tip;

		Route(String label, String tip) {
			this.label = label;
			this.tip = tip;
		}
	}

	/** A point to walk to, plus the chunk it belongs to. */
	public record Target(int chunkX, int chunkZ, double x, double z) {
	}

	private static final Gson GSON = new GsonBuilder().create();

	private final Config cfg;
	private final Set<Long> visited = new HashSet<>();
	private Target target;

	// isComplete() runs every tick, and in circle mode both counts walk the whole
	// rectangle, so cache them and drop the cache when the area or the set changes.
	private double sigX1, sigZ1, sigX2, sigZ2;
	private boolean sigCircular;
	private boolean sigValid;
	private int cachedTotal = -1;
	private int cachedVisited = -1;
	/** A big sweep serialises to megabytes; writing that on every menu close is a stutter. */
	private boolean dirty;
	/** Bumped on every change, so a drawing cache can tell in O(1) whether it is stale. */
	private int version;

	public AreaCoverage(Config cfg) {
		this.cfg = cfg;
		load();
	}

	/** Changes since this object was made. Only ever compared, never interpreted. */
	public int version() {
		return version;
	}

	private void invalidate() {
		cachedTotal = -1;
		cachedVisited = -1;
		version++;
	}

	/**
	 * Compares the four corners rather than a formatted signature: this runs twice a tick, and
	 * String.format allocating in a per-tick path is exactly the sort of thing that adds up to
	 * a stutter nobody can find later. The string form is still what the save file keys on.
	 */
	private void ensureFresh() {
		if (sigValid && sigX1 == cfg.areaX1 && sigZ1 == cfg.areaZ1
				&& sigX2 == cfg.areaX2 && sigZ2 == cfg.areaZ2 && sigCircular == cfg.areaCircular) {
			return;
		}
		sigX1 = cfg.areaX1;
		sigZ1 = cfg.areaZ1;
		sigX2 = cfg.areaX2;
		sigZ2 = cfg.areaZ2;
		sigCircular = cfg.areaCircular;
		sigValid = true;
		invalidate();
	}

	/** Null when there is no game around us — the self-check runs without a config dir. */
	private static Path path() {
		try {
			return FabricLoader.getInstance().getConfigDir().resolve("movrand-coverage.json");
		} catch (Exception | LinkageError e) {
			return null;
		}
	}

	public static long key(int cx, int cz) {
		return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
	}

	// ------------------------------------------------------------ the area

	public int minChunkX() {
		return Math.min(blockToChunk(cfg.areaX1), blockToChunk(cfg.areaX2));
	}

	public int maxChunkX() {
		return Math.max(blockToChunk(cfg.areaX1), blockToChunk(cfg.areaX2));
	}

	public int minChunkZ() {
		return Math.min(blockToChunk(cfg.areaZ1), blockToChunk(cfg.areaZ2));
	}

	public int maxChunkZ() {
		return Math.max(blockToChunk(cfg.areaZ1), blockToChunk(cfg.areaZ2));
	}

	private static int blockToChunk(double block) {
		return (int) Math.floor(block) >> 4;
	}

	public int widthChunks() {
		return maxChunkX() - minChunkX() + 1;
	}

	public int depthChunks() {
		return maxChunkZ() - minChunkZ() + 1;
	}

	/** Circle mode trims the rectangle to a disc, so an "N chunks around me" area is round. */
	public boolean contains(int cx, int cz) {
		if (cx < minChunkX() || cx > maxChunkX() || cz < minChunkZ() || cz > maxChunkZ()) return false;
		if (!cfg.areaCircular) return true;
		double ccx = (minChunkX() + maxChunkX()) / 2.0;
		double ccz = (minChunkZ() + maxChunkZ()) / 2.0;
		double r = Math.min(widthChunks(), depthChunks()) / 2.0;
		double dx = cx - ccx, dz = cz - ccz;
		return dx * dx + dz * dz <= r * r;
	}

	public int totalChunks() {
		ensureFresh();
		if (cachedTotal >= 0) return cachedTotal;
		if (!cfg.areaCircular) {
			cachedTotal = widthChunks() * depthChunks();
			return cachedTotal;
		}
		int n = 0;
		for (int cx = minChunkX(); cx <= maxChunkX(); cx++) {
			for (int cz = minChunkZ(); cz <= maxChunkZ(); cz++) if (contains(cx, cz)) n++;
		}
		cachedTotal = n;
		return n;
	}

	// -------------------------------------------------------------- progress

	public boolean isVisited(int cx, int cz) {
		return visited.contains(key(cx, cz));
	}

	/**
	 * Read-only, for anything that needs to draw the progress rather than query it. Iterating
	 * this beats asking {@link #isVisited} about every chunk of a large area.
	 */
	public Set<Long> visitedKeys() {
		return java.util.Collections.unmodifiableSet(visited);
	}

	/**
	 * Counted as chunks are ticked off rather than recounted on demand.
	 *
	 * <p>This runs every tick by way of {@code isComplete()}, and a full recount is O(visited).
	 * On a 500-chunk-wide area that is a quarter of a million iterations per tick for a number
	 * that changed by one - which is most of what made a big sweep stutter. Every add is
	 * already gated on {@link #contains}, so keeping a running total is exact, and the area
	 * changing under it still invalidates through the signature.
	 */
	public int visitedCount() {
		ensureFresh();
		if (cachedVisited >= 0) return cachedVisited;
		int n = 0;
		for (long k : visited) {
			if (contains((int) (k >> 32), (int) k)) n++;
		}
		cachedVisited = n;
		return n;
	}

	public int remaining() {
		return Math.max(0, totalChunks() - visitedCount());
	}

	public double progress() {
		int total = totalChunks();
		return total <= 0 ? 1 : Math.min(1, visitedCount() / (double) total);
	}

	public boolean isComplete() {
		return remaining() <= 0;
	}

	/** Marks the player's chunk, and the ring the container scan already swept. */
	public void markCovered(int cx, int cz, int radius) {
		ensureFresh();
		for (int dx = -radius; dx <= radius; dx++) {
			for (int dz = -radius; dz <= radius; dz++) {
				if (!contains(cx + dx, cz + dz)) continue;
				if (visited.add(key(cx + dx, cz + dz))) {
					if (cachedVisited >= 0) cachedVisited++;
					dirty = true;
					version++;
				}
			}
		}
		if (target != null && Math.abs(cx - target.chunkX()) <= radius && Math.abs(cz - target.chunkZ()) <= radius) {
			target = null;
		}
	}

	public void reset() {
		visited.clear();
		target = null;
		invalidate();
		dirty = true;
		save();
	}

	/** Un-tick a chunk, for when you want the sweep to go back over somewhere. */
	public void unmark(int cx, int cz) {
		ensureFresh();
		if (visited.remove(key(cx, cz))) {
			if (cachedVisited > 0 && contains(cx, cz)) cachedVisited--;
			dirty = true;
			version++;
		}
	}

	/** Tick every chunk off without walking them — "I have already done this bit". */
	public void markAll() {
		for (int cx = minChunkX(); cx <= maxChunkX(); cx++) {
			for (int cz = minChunkZ(); cz <= maxChunkZ(); cz++) {
				if (contains(cx, cz)) visited.add(key(cx, cz));
			}
		}
		target = null;
		dirty = true;
		version++;
		// everything inside the area is now ticked off, which is what totalChunks counts
		cachedVisited = totalChunks();
	}

	public Target currentTarget() {
		return target;
	}

	// ----------------------------------------------------------- the route

	/** The point to walk to next, or null when the area is finished. */
	public Target nextTarget(int fromCx, int fromCz) {
		if (target != null && !isVisited(target.chunkX(), target.chunkZ()) && contains(target.chunkX(), target.chunkZ())) {
			return target;
		}
		long[] pick = switch (cfg.areaRoute) {
			case NEAREST -> first(nearbyUnvisited(fromCx, fromCz, 1));
			case ORGANIC -> weightedNear(nearbyUnvisited(fromCx, fromCz, cfg.areaRouteLookahead));
			case RANDOM -> randomNext(fromCx, fromCz);
			case SERPENTINE -> serpentineNext(fromCx, fromCz);
			case SPIRAL -> spiralNext(fromCx, fromCz);
		};
		if (pick == null) {
			target = null;
			return null;
		}
		int cx = (int) pick[0], cz = (int) pick[1];
		// aim at a random point inside the chunk rather than dead centre
		double jitter = Math.max(0, Math.min(7.5, cfg.areaTargetJitter));
		double x = (cx << 4) + 8 + Rng.range(-jitter, jitter);
		double z = (cz << 4) + 8 + Rng.range(-jitter, jitter);
		target = new Target(cx, cz, x, z);
		return target;
	}

	private boolean free(int cx, int cz) {
		return contains(cx, cz) && !isVisited(cx, cz);
	}

	private long[] cell(int cx, int cz, int fromCx, int fromCz) {
		long dx = cx - fromCx, dz = cz - fromCz;
		return new long[]{cx, cz, dx * dx + dz * dz};
	}

	/**
	 * Unvisited chunks near a point, nearest first, stopping as soon as it has enough.
	 *
	 * <p>The obvious version walks the whole rectangle, allocates an array per chunk and sorts
	 * the lot. That is fine for a 16-chunk test area and ruinous for a real one: a 500-chunk
	 * square is a quarter of a million allocations and an n log n sort, run again every time a
	 * chunk is ticked off. Rings outward from where you are instead, and almost always the
	 * first two or three rings already hold the answer.
	 *
	 * <p>Rings are square, so ring {@code r} holds distances from {@code r} to {@code r√2}.
	 * Once enough candidates are in hand, scanning out to {@code ⌈r√2⌉} is what makes the
	 * result identical to the exhaustive version rather than merely close to it.
	 */
	private List<long[]> nearbyUnvisited(int fromCx, int fromCz, int want) {
		List<long[]> out = new ArrayList<>();
		int maxRadius = Math.max(
				Math.max(Math.abs(fromCx - minChunkX()), Math.abs(fromCx - maxChunkX())),
				Math.max(Math.abs(fromCz - minChunkZ()), Math.abs(fromCz - maxChunkZ())));
		int stopAt = Integer.MAX_VALUE;

		for (int r = 0; r <= maxRadius && r <= stopAt; r++) {
			collectRing(fromCx, fromCz, r, out);
			if (stopAt == Integer.MAX_VALUE && out.size() >= want) {
				stopAt = (int) Math.ceil(r * Math.sqrt(2));
			}
		}
		out.sort((a, b) -> Long.compare(a[2], b[2]));
		return out;
	}

	/** The chunks exactly {@code r} rings out, in Chebyshev distance. */
	private void collectRing(int cx, int cz, int r, List<long[]> out) {
		if (r == 0) {
			if (free(cx, cz)) out.add(cell(cx, cz, cx, cz));
			return;
		}
		for (int dx = -r; dx <= r; dx++) {
			if (free(cx + dx, cz - r)) out.add(cell(cx + dx, cz - r, cx, cz));
			if (free(cx + dx, cz + r)) out.add(cell(cx + dx, cz + r, cx, cz));
		}
		for (int dz = -r + 1; dz <= r - 1; dz++) {
			if (free(cx - r, cz + dz)) out.add(cell(cx - r, cz + dz, cx, cz));
			if (free(cx + r, cz + dz)) out.add(cell(cx + r, cz + dz, cx, cz));
		}
	}

	/** Row by row, alternating direction, taking the first chunk still outstanding. */
	private long[] serpentineNext(int fromCx, int fromCz) {
		for (int cz = minChunkZ(); cz <= maxChunkZ(); cz++) {
			boolean leftToRight = (cz - minChunkZ()) % 2 == 0;
			int from = leftToRight ? minChunkX() : maxChunkX();
			int to = leftToRight ? maxChunkX() : minChunkX();
			int stepX = leftToRight ? 1 : -1;
			for (int cx = from; leftToRight ? cx <= to : cx >= to; cx += stepX) {
				if (free(cx, cz)) return cell(cx, cz, fromCx, fromCz);
			}
		}
		return null;
	}

	/** Outward from the middle of the area, by angle within each ring. */
	private long[] spiralNext(int fromCx, int fromCz) {
		int ccx = (minChunkX() + maxChunkX()) / 2;
		int ccz = (minChunkZ() + maxChunkZ()) / 2;
		int maxRadius = Math.max(widthChunks(), depthChunks());
		List<long[]> ring = new ArrayList<>();
		for (int r = 0; r <= maxRadius; r++) {
			ring.clear();
			collectRing(ccx, ccz, r, ring);
			if (ring.isEmpty()) continue;
			long[] best = null;
			double bestAngle = Double.MAX_VALUE;
			for (long[] c : ring) {
				double angle = Math.atan2(c[1] - ccz, c[0] - ccx) + Math.PI;
				if (angle < bestAngle) {
					bestAngle = angle;
					best = c;
				}
			}
			return cell((int) best[0], (int) best[1], fromCx, fromCz);
		}
		return null;
	}

	/**
	 * Throw darts at the area. While most of it is outstanding this lands immediately; once
	 * it is nearly finished the darts start missing, so fall back to a sweep.
	 */
	private long[] randomNext(int fromCx, int fromCz) {
		int w = widthChunks(), d = depthChunks();
		for (int attempt = 0; attempt < 256; attempt++) {
			int cx = minChunkX() + Rng.nextInt(w);
			int cz = minChunkZ() + Rng.nextInt(d);
			if (free(cx, cz)) return cell(cx, cz, fromCx, fromCz);
		}
		for (int cx = minChunkX(); cx <= maxChunkX(); cx++) {
			for (int cz = minChunkZ(); cz <= maxChunkZ(); cz++) {
				if (free(cx, cz)) return cell(cx, cz, fromCx, fromCz);
			}
		}
		return null;
	}

	private static long[] first(List<long[]> sorted) {
		return sorted.isEmpty() ? null : sorted.getFirst();
	}

	/** Pick among the nearest few, weighted so closer is likelier but not certain. */
	private long[] weightedNear(List<long[]> sorted) {
		if (sorted.isEmpty()) return null;
		int pool = Math.max(1, Math.min(cfg.areaRouteLookahead, sorted.size()));
		double total = 0;
		double[] weights = new double[pool];
		for (int i = 0; i < pool; i++) {
			weights[i] = 1.0 / (1.0 + sorted.get(i)[2]);
			total += weights[i];
		}
		double roll = Rng.nextDouble() * total;
		for (int i = 0; i < pool; i++) {
			if ((roll -= weights[i]) < 0) return sorted.get(i);
		}
		return sorted.getFirst();
	}

	// --------------------------------------------------------- convenience

	/** Centre the area on a point, sized in chunks. */
	/** Forces the next save to actually write, whatever the dirty flag thinks. */
	public void touch() {
		dirty = true;
	}

	public void setAround(double x, double z, int chunkRadius) {
		int cx = blockToChunk(x), cz = blockToChunk(z);
		cfg.areaX1 = (cx - chunkRadius) << 4;
		cfg.areaZ1 = (cz - chunkRadius) << 4;
		cfg.areaX2 = ((cx + chunkRadius) << 4) + 15;
		cfg.areaZ2 = ((cz + chunkRadius) << 4) + 15;
		visited.clear();
		target = null;
		invalidate();
		dirty = true;
	}

	public void setCorners(double x1, double z1, double x2, double z2) {
		cfg.areaX1 = Math.min(x1, x2);
		cfg.areaZ1 = Math.min(z1, z2);
		cfg.areaX2 = Math.max(x1, x2);
		cfg.areaZ2 = Math.max(z1, z2);
		visited.clear();
		target = null;
		invalidate();
		dirty = true;
	}

	public String describe() {
		return "%d × %d chunks — %d/%d done (%.0f%%)".formatted(
				widthChunks(), depthChunks(), visitedCount(), totalChunks(), progress() * 100);
	}

	// ---------------------------------------------------------- persistence

	private String areaSignature() {
		return "%.0f,%.0f,%.0f,%.0f,%b".formatted(cfg.areaX1, cfg.areaZ1, cfg.areaX2, cfg.areaZ2, cfg.areaCircular);
	}

	public void save() {
		if (!dirty) return;
		try {
			Path p = path();
			if (p == null) return;
			Files.createDirectories(p.getParent());
			Saved saved = new Saved(areaSignature(), new ArrayList<>(visited));
			Files.writeString(p, GSON.toJson(saved));
			dirty = false;
		} catch (Exception | LinkageError e) {
			MovRand.LOG.warn("[movrand] could not write coverage progress", e);
		}
	}

	private void load() {
		try {
			Path p = path();
			if (p == null || !Files.exists(p)) return;
			Saved saved = GSON.fromJson(Files.readString(p), TypeToken.get(Saved.class).getType());
			// progress only means anything for the area it was recorded in
			if (saved != null && saved.area != null && saved.area.equals(areaSignature()) && saved.visited != null) {
				visited.addAll(saved.visited);
				invalidate();
			}
		} catch (Exception | LinkageError e) {
			MovRand.LOG.warn("[movrand] could not read coverage progress", e);
		}
	}

	private record Saved(String area, List<Long> visited) {
	}

	/** The slow, obvious count, kept only so the fast one can be checked against it. */
	private static int manualCount(AreaCoverage a) {
		int n = 0;
		for (int cx = a.minChunkX(); cx <= a.maxChunkX(); cx++) {
			for (int cz = a.minChunkZ(); cz <= a.maxChunkZ(); cz++) {
				if (a.contains(cx, cz) && a.isVisited(cx, cz)) n++;
			}
		}
		return n;
	}

	/**
	 * Self-check — no Minecraft classes touched:
	 * {@code java -ea -cp build/classes/java/main com.damia.movrand.AreaCoverage}
	 *
	 * <p>The property that matters is convergence: whatever the route, repeatedly asking for
	 * a target and walking to it must finish the area. A route that can return a chunk it
	 * already ticked off, or skip one, would loop forever in game and look like a hang.
	 */
	public static void main(String[] args) {
		Config cfg = new Config();
		cfg.areaX1 = 0;
		cfg.areaZ1 = 0;
		cfg.areaX2 = 63;   // 4 chunks
		cfg.areaZ2 = 63;
		cfg.areaCircular = false;

		AreaCoverage a = new AreaCoverage(cfg);
		assert a.widthChunks() == 4 : "width was " + a.widthChunks();
		assert a.totalChunks() == 16 : "total was " + a.totalChunks();
		assert a.remaining() == 16;
		assert !a.isComplete();

		// every route must finish, and must not need more steps than there are chunks
		for (Route route : Route.values()) {
			cfg.areaRoute = route;
			AreaCoverage sweep = new AreaCoverage(cfg);
			sweep.reset();
			int steps = 0;
			while (!sweep.isComplete()) {
				Target t = sweep.nextTarget(0, 0);
				assert t != null : route + " ran out of targets with " + sweep.remaining() + " chunks left";
				assert sweep.contains(t.chunkX(), t.chunkZ()) : route + " aimed outside the area";
				assert !sweep.isVisited(t.chunkX(), t.chunkZ()) : route + " re-targeted a finished chunk";
				// the target point must actually land in the chunk it claims
				assert (int) Math.floor(t.x()) >> 4 == t.chunkX() : route + " target x left its chunk";
				assert (int) Math.floor(t.z()) >> 4 == t.chunkZ() : route + " target z left its chunk";
				sweep.markCovered(t.chunkX(), t.chunkZ(), 0);
				assert ++steps <= 16 : route + " took " + steps + " steps for 16 chunks";
			}
			assert sweep.progress() == 1.0 : route + " finished at " + sweep.progress();
			System.out.printf("%-11s covered 16 chunks in %d targets%n", route, steps);
		}

		// a scan radius should tick off more than one chunk at a time
		AreaCoverage wide = new AreaCoverage(cfg);
		wide.reset();
		wide.markCovered(1, 1, 1);
		assert wide.visitedCount() == 9 : "radius 1 marked " + wide.visitedCount();
		wide.unmark(1, 1);
		assert wide.visitedCount() == 8;

		// the running total has to survive overlapping marks and an unmark
		Config countCfg = new Config();
		countCfg.areaX1 = 0;
		countCfg.areaZ1 = 0;
		countCfg.areaX2 = 63;
		countCfg.areaZ2 = 63;
		countCfg.clampAll();
		AreaCoverage counted = new AreaCoverage(countCfg);
		counted.reset();
		counted.markCovered(1, 1, 1);
		counted.markCovered(2, 2, 1);   // overlaps the first, so a naive counter double-counts
		assert counted.visitedCount() == manualCount(counted)
				: "running total %d, actual %d".formatted(counted.visitedCount(), manualCount(counted));
		counted.unmark(1, 1);
		assert counted.visitedCount() == manualCount(counted) : "unmark lost track of the total";
		counted.unmark(1, 1);           // already gone, must not go negative
		assert counted.visitedCount() == manualCount(counted) : "a redundant unmark moved the total";

		// scale: a real area is 500 chunks a side, not 4, and the route search has to cope
		Config bigCfg = new Config();
		bigCfg.areaX1 = -4096;
		bigCfg.areaZ1 = -4096;
		bigCfg.areaX2 = 4095;
		bigCfg.areaZ2 = 4095;
		bigCfg.areaRoute = Route.ORGANIC;
		bigCfg.clampAll();
		AreaCoverage huge = new AreaCoverage(bigCfg);
		huge.reset();
		assert huge.totalChunks() == 512 * 512 : "big area held " + huge.totalChunks();

		long startNanos = System.nanoTime();
		int atX = 0, atZ = 0;
		for (int i = 0; i < 3000; i++) {
			Target t = huge.nextTarget(atX, atZ);
			assert t != null : "ran out of targets after " + i + " on a 262144 chunk area";
			atX = t.chunkX();
			atZ = t.chunkZ();
			huge.markCovered(atX, atZ, 0);
		}
		double millis = (System.nanoTime() - startNanos) / 1_000_000.0;
		assert huge.visitedCount() == 3000 : "running total drifted to " + huge.visitedCount();
		// the exhaustive version allocated 262144 arrays and sorted them per target; anything
		// near that is a regression back to it, whatever the exact machine
		assert millis < 3000 : "3000 targets over a 262144 chunk area took %.0fms".formatted(millis);
		System.out.printf("3000 targets over %d chunks in %.0fms%n", huge.totalChunks(), millis);

		// and every route has to stay usable at that size
		for (Route route : Route.values()) {
			bigCfg.areaRoute = route;
			AreaCoverage sweep = new AreaCoverage(bigCfg);
			sweep.reset();
			long routeStart = System.nanoTime();
			int rx = 0, rz = 0;
			for (int i = 0; i < 200; i++) {
				Target t = sweep.nextTarget(rx, rz);
				assert t != null : route + " ran out of targets on a big area";
				rx = t.chunkX();
				rz = t.chunkZ();
				sweep.markCovered(rx, rz, 0);
			}
			double routeMs = (System.nanoTime() - routeStart) / 1_000_000.0;
			assert routeMs < 3000 : "%s took %.0fms for 200 targets".formatted(route, routeMs);
			System.out.printf("%-11s 200 targets in %6.1fms%n", route, routeMs);
		}

		// circle mode must be a strict subset of the box it is cut from
		cfg.areaCircular = true;
		AreaCoverage disc = new AreaCoverage(cfg);
		assert disc.totalChunks() < 16 && disc.totalChunks() > 0 : "circle held " + disc.totalChunks();

		// the cache must not survive the area moving under it
		cfg.areaCircular = false;
		cfg.areaX2 = 127;
		assert disc.totalChunks() == 8 * 4 : "stale cache: " + disc.totalChunks();

		System.out.println("AreaCoverage self-check passed");
	}
}
