package com.damia.movrand;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * A* over block positions, so the bot can get to a block instead of walking at it.
 *
 * <p>The steering in {@link Avoidance} answers "is there a wall in front of me", which is
 * enough to cross open ground and not enough to leave a room. A base is corridors, floors
 * and ceilings, and the difference between the two is a search: this one walks, steps up,
 * drops, and — when it is allowed to — mines through and bridges across, which is the whole
 * reason a route through a building exists at all.
 *
 * <p>The world is an interface rather than a {@code ClientLevel} for one reason: a search
 * this fiddly needs a test, and a test needs a world you can draw by hand. {@link Level}
 * below is the adapter that makes the real one fit.
 */
public final class PathFinder {

	/** Everything the search needs to know about the world, and nothing else. */
	public interface World {
		/** Has collision: you cannot stand inside it. */
		boolean solid(int x, int y, int z);

		/** Hurts, traps, or teleports on contact. Never entered and never landed on. */
		boolean hazard(int x, int y, int z);

		/** May be mined through, if mining is allowed at all. */
		boolean breakable(int x, int y, int z);

		int minY();

		int maxY();
	}

	/** What the search is allowed to do, and what each of those things is worth. */
	public record Rules(boolean mine, boolean bridge, boolean diagonal, int maxFall, int maxStepUp,
	                    double mineCost, double placeCost, int maxNodes) {

		public static Rules of(Config cfg, boolean canMine, boolean canBridge) {
			return new Rules(cfg.pathMine && canMine, cfg.pathBridge && canBridge, cfg.pathDiagonal,
					Math.max(1, cfg.pathMaxFall), Math.max(1, cfg.autoJumpMaxHeight),
					Math.max(1, cfg.pathMineCost), Math.max(1, cfg.pathPlaceCost),
					Math.max(500, cfg.pathMaxNodes));
		}
	}

	/**
	 * @param steps    feet positions, packed; the first is where the search started
	 * @param complete false when the budget ran out and this is only the best partial route
	 * @param searched how many nodes were expanded, for the status line
	 */
	public record Path(List<long[]> steps, boolean complete, int searched, double cost) {

		public boolean isEmpty() {
			return steps.size() <= 1;
		}
	}

	// Walking is the unit. Everything else is priced against it.
	private static final double WALK = 1.0;
	private static final double DIAGONAL = 1.414;
	private static final double STEP_UP = 0.6;
	private static final double FALL = 0.4;
	/**
	 * Weighted A*: the heuristic is inflated slightly, which gives up on finding the very
	 * shortest route in exchange for expanding far fewer nodes. On a client tick budget that
	 * is the right trade — a route two blocks longer, found in a tenth of the time.
	 */
	private static final double HEURISTIC_WEIGHT = 1.15;

	private static final int[][] SIDES = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
	private static final int[][] CORNERS = {{1, 1}, {1, -1}, {-1, 1}, {-1, -1}};

	private PathFinder() {
	}

	// ------------------------------------------------------------- searching

	private static final class Node implements Comparable<Node> {
		final int x, y, z;
		double g;
		double f;
		Node from;

		Node(int x, int y, int z) {
			this.x = x;
			this.y = y;
			this.z = z;
		}

		@Override
		public int compareTo(Node o) {
			return Double.compare(f, o.f);
		}
	}

	/**
	 * @param goalRadius how close to the goal counts as arriving — a block being mined is
	 *                   reached by standing next to it, not inside it
	 */
	public static Path find(World world, int sx, int sy, int sz, int gx, int gy, int gz,
	                        double goalRadius, Rules rules) {
		Map<Long, Node> seen = new HashMap<>();
		PriorityQueue<Node> open = new PriorityQueue<>();

		Node start = new Node(sx, sy, sz);
		start.g = 0;
		start.f = heuristic(sx, sy, sz, gx, gy, gz);
		seen.put(key(sx, sy, sz), start);
		open.add(start);

		Node best = start;
		double bestScore = heuristic(sx, sy, sz, gx, gy, gz);
		int expanded = 0;
		double goalR2 = goalRadius * goalRadius;

		while (!open.isEmpty() && expanded < rules.maxNodes()) {
			Node current = open.poll();
			expanded++;

			double d2 = dist2(current.x, current.y, current.z, gx, gy, gz);
			if (d2 <= goalR2) return build(current, true, expanded);

			// keep the closest approach, so running out of budget still gets us moving
			double score = Math.sqrt(d2);
			if (score < bestScore) {
				bestScore = score;
				best = current;
			}

			for (Node next : neighbours(world, current, rules)) {
				long k = key(next.x, next.y, next.z);
				Node existing = seen.get(k);
				if (existing != null && existing.g <= next.g) continue;
				next.f = next.g + heuristic(next.x, next.y, next.z, gx, gy, gz);
				seen.put(k, next);
				open.add(next);
			}
		}
		return build(best, false, expanded);
	}

	private static List<Node> neighbours(World world, Node from, Rules rules) {
		List<Node> out = new ArrayList<>(10);
		for (int[] d : SIDES) step(world, from, d[0], d[1], WALK, rules, out);
		if (rules.diagonal()) {
			for (int[] d : CORNERS) {
				// a corner is only a move if both sides of it are open; cutting a corner
				// through two walls is the classic path that looks fine and never walks
				if (!clear(world, from.x + d[0], from.y, from.z, rules)) continue;
				if (!clear(world, from.x, from.y, from.z + d[1], rules)) continue;
				step(world, from, d[0], d[1], DIAGONAL, rules, out);
			}
		}
		return out;
	}

	/** One horizontal move, resolved into whichever of walk / step up / drop / mine it is. */
	private static void step(World world, Node from, int dx, int dz, double base, Rules rules, List<Node> out) {
		int x = from.x + dx, z = from.z + dz;

		// level ground, or a step up we can jump
		for (int up = 0; up <= rules.maxStepUp(); up++) {
			int y = from.y + up;
			if (up > 0 && !clear(world, from.x, from.y + up + 1, from.z, rules)) break; // no headroom to jump
			if (!standable(world, x, y, z, rules)) continue;
			if (up > 0 && !passable(world, x, y + 1, z, rules)) continue;
			add(out, from, x, y, z, base + up * STEP_UP);
			return;
		}

		// a drop: fall to the first floor within reach, refusing anything we cannot survive
		if (clear(world, x, from.y, z, rules)) {
			for (int down = 1; down <= rules.maxFall(); down++) {
				int y = from.y - down;
				if (y < world.minY()) break;
				if (world.hazard(x, y, z)) break;
				if (world.solid(x, y, z)) {
					// solid at the feet means the floor is the block above it
					if (standable(world, x, y + 1, z, rules)) {
						add(out, from, x, y + 1, z, base + (down - 1) * FALL);
					}
					break;
				}
			}
		}

		// mine through: the same move, paid for with a broken block
		if (rules.mine()) {
			int y = from.y;
			boolean blockedFeet = !passable(world, x, y, z, rules);
			boolean blockedHead = !passable(world, x, y + 1, z, rules);
			if ((blockedFeet || blockedHead) && standableFloor(world, x, y, z, rules)) {
				int toBreak = (blockedFeet && world.breakable(x, y, z) ? 1 : 0)
						+ (blockedHead && world.breakable(x, y + 1, z) ? 1 : 0);
				boolean allBreakable = (!blockedFeet || world.breakable(x, y, z))
						&& (!blockedHead || world.breakable(x, y + 1, z));
				if (allBreakable && toBreak > 0) {
					add(out, from, x, y, z, base + toBreak * rules.mineCost());
				}
			}
		}

		// bridge: open air with nothing under it, floored with a block from the inventory
		if (rules.bridge() && clear(world, x, from.y, z, rules)
				&& !world.solid(x, from.y - 1, z) && !world.hazard(x, from.y - 1, z)) {
			add(out, from, x, from.y, z, base + rules.placeCost());
		}
	}

	private static void add(List<Node> out, Node from, int x, int y, int z, double cost) {
		Node n = new Node(x, y, z);
		n.from = from;
		n.g = from.g + cost;
		out.add(n);
	}

	/** Two blocks of space for a player, and nothing in either that hurts. */
	private static boolean clear(World world, int x, int y, int z, Rules rules) {
		return passable(world, x, y, z, rules) && passable(world, x, y + 1, z, rules);
	}

	private static boolean passable(World world, int x, int y, int z, Rules rules) {
		if (y < world.minY() || y > world.maxY()) return false;
		return !world.solid(x, y, z) && !world.hazard(x, y, z);
	}

	/** Room to stand, and a floor to stand on. */
	private static boolean standable(World world, int x, int y, int z, Rules rules) {
		return clear(world, x, y, z, rules) && standableFloor(world, x, y, z, rules);
	}

	private static boolean standableFloor(World world, int x, int y, int z, Rules rules) {
		return world.solid(x, y - 1, z) && !world.hazard(x, y - 1, z);
	}

	private static Path build(Node end, boolean complete, int searched) {
		List<long[]> steps = new ArrayList<>();
		double cost = end.g;
		for (Node n = end; n != null; n = n.from) steps.add(new long[]{n.x, n.y, n.z});
		Collections.reverse(steps);
		return new Path(steps, complete, searched, cost);
	}

	private static double heuristic(int x, int y, int z, int gx, int gy, int gz) {
		return Math.sqrt(dist2(x, y, z, gx, gy, gz)) * HEURISTIC_WEIGHT;
	}

	private static double dist2(int x, int y, int z, int gx, int gy, int gz) {
		double dx = x - gx, dy = y - gy, dz = z - gz;
		return dx * dx + dy * dy + dz * dz;
	}

	/** Same packing vanilla uses for block positions: 26 bits of x and z, 12 of y. */
	static long key(int x, int y, int z) {
		return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFFL);
	}

	// -------------------------------------------------------------- adapter

	/** The real world, seen through the four questions the search actually asks. */
	public static final class Level implements World {
		private final net.minecraft.client.multiplayer.ClientLevel level;
		private final Config cfg;

		public Level(net.minecraft.client.multiplayer.ClientLevel level, Config cfg) {
			this.level = level;
			this.cfg = cfg;
		}

		@Override
		public boolean solid(int x, int y, int z) {
			net.minecraft.core.BlockPos pos = new net.minecraft.core.BlockPos(x, y, z);
			return !level.getBlockState(pos).getCollisionShape(level, pos).isEmpty();
		}

		@Override
		public boolean hazard(int x, int y, int z) {
			// the same definition the steering uses, so a route never leads somewhere the
			// dodge would immediately refuse to walk into
			return Avoidance.hazardAt(level, new net.minecraft.core.BlockPos(x, y, z), cfg);
		}

		@Override
		public boolean breakable(int x, int y, int z) {
			net.minecraft.core.BlockPos pos = new net.minecraft.core.BlockPos(x, y, z);
			return BlockTargets.breakable(level.getBlockState(pos), level, pos);
		}

		@Override
		public int minY() {
			return level.getMinY();
		}

		@Override
		public int maxY() {
			return level.getMaxY();
		}
	}

	// ----------------------------------------------------------- self-check

	/** A world drawn as text, one string per row, so a test can describe a room. */
	static final class Sketch implements World {
		private final String[][] layers;
		private final int baseY;

		/** '#' solid, '~' hazard, '.' air, 'o' solid but unbreakable. */
		Sketch(int baseY, String[]... layers) {
			this.baseY = baseY;
			this.layers = layers;
		}

		private char at(int x, int y, int z) {
			int ly = y - baseY;
			if (ly < 0 || ly >= layers.length) return ly < 0 ? '#' : '.';
			String[] rows = layers[ly];
			if (z < 0 || z >= rows.length) return '.';
			String row = rows[z];
			if (x < 0 || x >= row.length()) return '.';
			return row.charAt(x);
		}

		@Override
		public boolean solid(int x, int y, int z) {
			char c = at(x, y, z);
			return c == '#' || c == 'o';
		}

		@Override
		public boolean hazard(int x, int y, int z) {
			return at(x, y, z) == '~';
		}

		@Override
		public boolean breakable(int x, int y, int z) {
			return at(x, y, z) == '#';
		}

		@Override
		public int minY() {
			return baseY - 1;
		}

		@Override
		public int maxY() {
			return baseY + layers.length;
		}
	}

	/**
	 * Self-check on the search: {@code ./gradlew selfCheck -Pcheck=com.damia.movrand.PathFinder}
	 */
	public static void main(String[] args) {
		Rules walk = new Rules(false, false, false, 3, 1, 4, 3, 20000);
		Rules mining = new Rules(true, false, false, 3, 1, 4, 3, 20000);

		// A room with one doorway. Layer 0 is the floor, layers 1 and 2 are the body.
		String[] floor = {"#####", "#####", "#####", "#####", "#####"};
		String[] walls = {"#####", "#...#", "#.#.#", "#...#", "##.##"};
		Sketch room = new Sketch(0, floor, walls, walls);

		Path p = find(room, 1, 1, 1, 3, 1, 3, 0.5, walk);
		assert p.complete() : "a walk round one pillar should have been found";
		assert !p.isEmpty() : "the path is empty";
		assert p.steps().getFirst()[0] == 1 && p.steps().getFirst()[2] == 1 : "the path must start where we are";
		long[] last = p.steps().getLast();
		assert last[0] == 3 && last[1] == 1 && last[2] == 3 : "the path must end at the goal";
		// and it must actually go round the pillar at (2,2) rather than through it
		for (long[] s : p.steps()) {
			assert !(s[0] == 2 && s[2] == 2) : "the route walked through a solid pillar";
		}

		// Two sealed cells with a wall between them. Floor everywhere, so the only thing
		// separating them is the wall itself.
		String[] split = {"#####", "#.#.#", "#.#.#", "#.#.#", "#####"};
		Sketch sealed = new Sketch(0, floor, split, split);
		Path out = find(sealed, 1, 1, 1, 3, 1, 1, 0.5, walk);
		assert !out.complete() : "there is no way through a solid wall without mining";

		// with mining on, there is - and it costs what two broken blocks cost
		Path dug = find(sealed, 1, 1, 1, 3, 1, 1, 0.5, mining);
		assert dug.complete() : "mining should have found a way through the wall";
		assert dug.cost() >= 2 * mining.mineCost() : "digging through two blocks cost " + dug.cost();
		boolean wentThroughTheWall = false;
		for (long[] s : dug.steps()) if (s[0] == 2 && s[2] == 1) wentThroughTheWall = true;
		assert wentThroughTheWall : "the route claimed to mine out without entering the wall";

		// A hazard is never crossed, even when it is the only straight line.
		String[] lavaFloor = {"#####", "#####", "##~##", "#####", "#####"};
		String[] open = {".....", ".....", ".....", ".....", "....."};
		Sketch lava = new Sketch(0, lavaFloor, open, open);
		Path round = find(lava, 2, 1, 1, 2, 1, 3, 0.5, walk);
		assert round.complete() : "there is a way round the lava";
		for (long[] s : round.steps()) {
			assert !(s[0] == 2 && s[2] == 2 && s[1] == 1) : "the route stepped into lava";
		}

		// A drop is a move; a cliff taller than the limit is not.
		Rules shortFall = new Rules(false, false, false, 2, 1, 4, 3, 20000);
		String[] deepFloor = {"##", "##"};
		String[] gap = {"..", ".."};
		// floor at y0, then five layers of air, with a shelf at y5 on one side
		Sketch cliff = new Sketch(0, deepFloor, gap, gap, gap, gap, new String[]{"#.", "#."}, gap, gap);
		Path down = find(cliff, 0, 6, 0, 1, 1, 0, 0.5, shortFall);
		assert !down.complete() : "a five block drop is past a two block fall limit";

		// The budget is a floor under the answer, not a cliff: a search that runs out still
		// hands back the best it found, and that is what keeps the bot walking.
		Rules tiny = new Rules(false, false, false, 3, 1, 4, 3, 500);
		Path partial = find(room, 1, 1, 1, 400, 1, 400, 0.5, tiny);
		assert !partial.complete() : "that goal is not reachable";
		assert partial.searched() <= 500 : "the node budget was ignored: " + partial.searched();

		// packing must round-trip the coordinates a real world uses
		assert key(0, 0, 0) != key(1, 0, 0) && key(0, 0, 0) != key(0, 1, 0) && key(0, 0, 0) != key(0, 0, 1)
				: "the position key collides on a unit step";
		assert key(-30_000_000, -64, 30_000_000) != key(30_000_000, 320, -30_000_000)
				: "the position key collides at world edges";

		System.out.println("PathFinder self-check passed");
	}
}
