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

		/**
		 * How tall the collision in this block is, from its own floor: 0 for air, 1 for a
		 * cube, 0.5 for a bottom slab, and whatever a stair or a snow layer happens to be.
		 *
		 * <p>One boolean cannot answer the two questions the search actually asks. "Can I
		 * stand on it" and "does it fill the space" are the same question only for full
		 * cubes, and a base is not made of full cubes — it is slabs, stairs, carpets, snow,
		 * paths and farmland. Reading them all as walls turns a slab floor into a wall the
		 * bot has to step over once per block, and puts the route a level above the floor the
		 * player is actually standing on. Baritone splits this into walk-through and walk-on
		 * for the same reason; this is the number both of those come from.
		 */
		default double topOf(int x, int y, int z) {
			return solid(x, y, z) ? 1 : 0;
		}

		/** Hurts, traps, or teleports on contact. Never entered and never landed on. */
		boolean hazard(int x, int y, int z);

		/** May be mined through, if mining is allowed at all. */
		boolean breakable(int x, int y, int z);

		/**
		 * Whether breaking this block would let lava out.
		 *
		 * <p>Refusing to walk into lava is not the same as refusing to let it out, and the
		 * difference is a route that reads as ordinary stone until the moment it is gone. A
		 * default rather than a required method: it is a real-world concern, and the sketch
		 * world the search is tested against has no fluids in it.
		 */
		default boolean leaksLava(int x, int y, int z) {
			return false;
		}

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
	 * Whether standing here counts as having arrived.
	 *
	 * <p>A radius round the target is the obvious answer and it is wrong for the job this
	 * search exists to serve. "Within four blocks" happily includes four blocks away through
	 * a wall: the search declares victory, the walk finishes, and the bot stands in the next
	 * room over with the target in plain sight of nothing. What the destroyer actually wants
	 * is somewhere it could swing from, which is a question about line of sight, so the test
	 * is handed in rather than assumed.
	 */
	@FunctionalInterface
	public interface Goal {
		boolean reached(int x, int y, int z);
	}

	/** The plain answer, for callers that only care how close they got. */
	public static Goal within(double radius, int gx, int gy, int gz) {
		double r2 = radius * radius;
		return (x, y, z) -> dist2(x, y, z, gx, gy, gz) <= r2;
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
		/**
		 * Whether the floor under this position is one the route puts there. Every other node
		 * stands on the world as it is; a pillar and a bridge stand on their own placed block,
		 * and asking the unmodified world what is underneath them gets air — which is why a
		 * pillar could climb exactly one block before deciding it had nothing to jump off.
		 */
		boolean standsOnPlaced;

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
		return find(world, sx, sy, sz, gx, gy, gz, within(goalRadius, gx, gy, gz), rules);
	}

	/**
	 * @param goal what counts as arriving; the target coordinates still steer the search,
	 *             because the heuristic has to point somewhere even when the goal is a test
	 */
	public static Path find(World world, int sx, int sy, int sz, int gx, int gy, int gz,
	                        Goal goal, Rules rules) {
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

		while (!open.isEmpty() && expanded < rules.maxNodes()) {
			Node current = open.poll();
			expanded++;

			if (goal.reached(current.x, current.y, current.z)) return build(current, true, expanded);

			// keep the closest approach, so running out of budget still gets us moving
			double score = Math.sqrt(dist2(current.x, current.y, current.z, gx, gy, gz));
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
		List<Node> out = new ArrayList<>(12);
		vertical(world, from, rules, out);
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

	/**
	 * Straight down through the floor, and straight up through the ceiling.
	 *
	 * <p>Without these a route is stuck on one storey. Every other move here changes x or z,
	 * so a target on the floor below is only reachable if somebody left a staircase, and a
	 * base with a basement and an upper floor is three separate places the bot cannot get
	 * between. Digging down is a swing; going up is a block placed underneath while jumping
	 * off it, which is why it costs a placement as well.
	 */
	private static void vertical(World world, Node from, Rules rules, List<Node> out) {
		int x = from.x, z = from.z;

		// Down: break what is underfoot, then fall to whatever stops you. Where that is
		// matters — the block under the floor of an upper storey is usually the room below,
		// not a step — so it is scanned for rather than assumed to be one block.
		if (rules.mine()) {
			int broken = from.y - 1;
			if (world.solid(x, broken, z) && world.breakable(x, broken, z)
					&& !world.leaksLava(x, broken, z)) {
				for (int y = broken; y >= broken - rules.maxFall(); y--) {
					if (y - 1 < world.minY()) break;
					if (world.hazard(x, y, z) || world.hazard(x, y - 1, z)) break;
					if (world.solid(x, y - 1, z)) {
						add(out, from, x, y, z, rules.mineCost() + (broken - y) * FALL);
						break;
					}
					// the shaft has to be a shaft; the top of it is the block being broken
					if (y != broken && world.solid(x, y, z)) break;
				}
			}
		}

		// Up: a block placed under your own feet while jumping off them. Both the space you
		// rise into and the one above it have to be free, or paid for with a swing - a ceiling
		// is the usual case, since that is what standing on a lower floor means.
		boolean footing = from.standsOnPlaced || standableFloor(world, x, from.y, z, rules);
		if (rules.bridge() && footing && from.y + 2 <= world.maxY()) {
			double extra = 0;
			for (int y = from.y + 1; y <= from.y + 2; y++) {
				if (passable(world, x, y, z, rules)) continue;
				if (!rules.mine() || !world.breakable(x, y, z) || world.leaksLava(x, y, z)
						|| world.hazard(x, y, z)) {
					return;
				}
				extra += rules.mineCost();
			}
			add(out, from, x, from.y + 1, z, rules.placeCost() + STEP_UP + extra, true);
		}
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
				// a wall with a lake behind it is not a door, whatever it costs to break
				boolean floods = (blockedFeet && world.leaksLava(x, y, z))
						|| (blockedHead && world.leaksLava(x, y + 1, z));
				if (allBreakable && toBreak > 0 && !floods) {
					add(out, from, x, y, z, base + toBreak * rules.mineCost());
				}
			}
		}

		// Bridge: open air with nothing under it, floored with a block from the inventory.
		// Orthogonal only. A block placed diagonally has no orthogonal neighbour to place it
		// against, so a diagonal bridge move is a route the bot walks up to, cannot build,
		// replans, and walks up to again — which is what "it could not bridge" looks like.
		if (rules.bridge() && base == WALK && clear(world, x, from.y, z, rules)
				&& !world.solid(x, from.y - 1, z) && !world.hazard(x, from.y - 1, z)) {
			add(out, from, x, from.y, z, base + rules.placeCost(), true);
		}
	}

	private static void add(List<Node> out, Node from, int x, int y, int z, double cost) {
		add(out, from, x, y, z, cost, false);
	}

	private static void add(List<Node> out, Node from, int x, int y, int z, double cost, boolean placed) {
		Node n = new Node(x, y, z);
		n.from = from;
		n.g = from.g + cost;
		n.standsOnPlaced = placed;
		out.add(n);
	}

	/** Two blocks of space for a player, and nothing in either that hurts. */
	private static boolean clear(World world, int x, int y, int z, Rules rules) {
		return passable(world, x, y, z, rules) && passable(world, x, y + 1, z, rules);
	}

	/**
	 * Room to be here. Anything low enough to step onto is not in the way — a bottom slab or
	 * a carpet underfoot is floor, not wall, and vanilla walks onto it without a jump.
	 */
	private static boolean passable(World world, int x, int y, int z, Rules rules) {
		if (y < world.minY() || y > world.maxY()) return false;
		return world.topOf(x, y, z) <= STEPPABLE && !world.hazard(x, y, z);
	}

	/** How much of a block can be under your feet and still be walked onto without jumping. */
	private static final double STEPPABLE = 0.5;

	/** Room to stand, and a floor to stand on. */
	private static boolean standable(World world, int x, int y, int z, Rules rules) {
		return clear(world, x, y, z, rules) && standableFloor(world, x, y, z, rules);
	}

	/**
	 * Something to stand on. Either the block below holds you up, or the one you are in does
	 * — which is what standing on a slab is: feet half a block up, inside the slab's own
	 * position, with the space above free.
	 */
	private static boolean standableFloor(World world, int x, int y, int z, Rules rules) {
		if (world.hazard(x, y - 1, z) || world.hazard(x, y, z)) return false;
		return world.topOf(x, y - 1, z) > 0 || world.topOf(x, y, z) > 0;
	}

	private static Path build(Node end, boolean complete, int searched) {
		List<long[]> steps = new ArrayList<>();
		double cost = end.g;
		for (Node n = end; n != null; n = n.from) steps.add(new long[]{n.x, n.y, n.z});
		Collections.reverse(steps);
		return new Path(steps, complete, searched, cost);
	}

	/**
	 * The block a search should actually start from.
	 *
	 * <p>Feet in the air is the normal case rather than an edge case: a plan gets made while
	 * walking off a step, while a jump is coming down, while swimming. A search rooted at a
	 * position with nothing under it expands nothing and hands back an empty route, and the
	 * caller reads that as "unreachable" and writes off a block it was standing next to.
	 */
	public static int groundY(World world, int x, int y, int z, int maxDrop) {
		for (int d = 0; d <= maxDrop; d++) {
			if (world.solid(x, y - d - 1, z)) return y - d;
		}
		return y;
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
		public double topOf(int x, int y, int z) {
			net.minecraft.core.BlockPos pos = new net.minecraft.core.BlockPos(x, y, z);
			net.minecraft.world.phys.shapes.VoxelShape shape =
					level.getBlockState(pos).getCollisionShape(level, pos);
			return shape.isEmpty() ? 0 : shape.max(net.minecraft.core.Direction.Axis.Y);
		}

		@Override
		public boolean leaksLava(int x, int y, int z) {
			return Avoidance.floodsWhenBroken(level, new net.minecraft.core.BlockPos(x, y, z));
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
	static class Sketch implements World {
		private final String[][] layers;
		private final int baseY;

		/** '#' solid, '~' hazard, '.' air, 'o' solid but unbreakable, '_' a bottom slab. */
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
		public double topOf(int x, int y, int z) {
			return at(x, y, z) == '_' ? 0.5 : solid(x, y, z) ? 1 : 0;
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

		// Bridging. A gap with floor either side is crossed by placing blocks into it, and
		// every one of those steps has to be orthogonal: a block put down diagonally has no
		// orthogonal neighbour to place it against, so the bot walks up to the gap, cannot
		// build, replans, and walks up to it again. That is what "it could not bridge" is.
		String[] gapFloor = {"#####", "#####", "##.##", "#####", "#####"};
		String[] air = {".....", ".....", ".....", ".....", "....."};
		Sketch chasm = new Sketch(0, gapFloor, air, air);
		Rules bridging = new Rules(false, true, true, 3, 1, 4, 3, 20000);
		Path across = find(chasm, 2, 1, 1, 2, 1, 3, 0.5, bridging);
		assert across.complete() : "a one block gap with a bridge allowed should be crossable";
		long[] previous = null;
		for (long[] s : across.steps()) {
			if (previous != null) {
				boolean floored = chasm.solid((int) s[0], (int) s[1] - 1, (int) s[2]);
				boolean diagonal = s[0] != previous[0] && s[2] != previous[2];
				assert floored || !diagonal
						: "a diagonal step onto empty air is a bridge that cannot be built";
			}
			previous = s;
		}

		// A wall with lava behind it is not a door, however cheap breaking it looks. The same
		// two cells as above, and the only difference is that every wall is now holding
		// something back — so the way through that mining just found has to stop existing.
		Sketch sealedLava = new Sketch(0, floor, split, split) {
			@Override
			public boolean leaksLava(int x, int y, int z) {
				return true;
			}
		};
		Path flooded = find(sealedLava, 1, 1, 1, 3, 1, 1, 0.5, mining);
		assert !flooded.complete() : "the search dug through a wall holding lava back";

		// A slab floor is a floor, not a wall a block high. Read as walls, a room paved in
		// them is a step up per block and the route ends up a level above the one the player
		// is standing on - which is most of a redstone build.
		String[] slabRow = {"_____", "_____", "_____", "_____", "_____"};
		String[] headroom = {".....", ".....", ".....", ".....", "....."};
		Sketch paved = new Sketch(0, floor, slabRow, headroom, headroom);
		Path paved2 = find(paved, 1, 1, 1, 3, 1, 3, 0.5, walk);
		assert paved2.complete() : "a slab floor should be walkable";
		for (long[] s : paved2.steps()) {
			assert s[1] == 1 : "the route left the slab floor for y" + s[1] + ", which is mid-air";
		}
		// and the search still refuses to walk into something that genuinely fills the space
		Sketch cubes = new Sketch(0, floor, walls, walls);
		assert find(cubes, 1, 1, 1, 2, 1, 2, 0.5, walk).steps().stream()
				.noneMatch(n -> n[0] == 2 && n[2] == 2) : "a full cube stopped being a wall";

		// A goal is a test, not a radius, and the test is what stops a search declaring
		// victory four blocks from the target with a wall in between.
		Path fussy = find(room, 1, 1, 1, 3, 1, 3, (x, y, z) -> x == 3 && y == 1 && z == 3, walk);
		assert fussy.complete() : "an exact goal in an open room should have been reached";
		long[] end = fussy.steps().getLast();
		assert end[0] == 3 && end[1] == 1 && end[2] == 3 : "the exact goal was not where it stopped";
		Path never = find(room, 1, 1, 1, 3, 1, 3, (x, y, z) -> false, walk);
		assert !never.complete() : "a goal nothing satisfies was somehow satisfied";

		// One storey is not a base. Digging down through a floor and pillaring up through a
		// ceiling are the two moves that make the rest of a building reachable at all, so the
		// shaft here is walled in unbreakable stone: there is no sideways answer to fall back
		// on, and a route that arrives can only have gone straight up or straight down.
		Sketch storeys = new Sketch(0,
				new String[]{"ooo", "ooo", "ooo"},     // y0 the ground
				new String[]{"ooo", "o.o", "ooo"},     // y1 lower room
				new String[]{"ooo", "o#o", "ooo"},     // y2 the floor between them
				new String[]{"ooo", "o.o", "ooo"},     // y3 upper room
				new String[]{"ooo", "o.o", "ooo"});    // y4 head room
		Rules storeyRules = new Rules(true, true, false, 3, 1, 4, 3, 20000);
		Rules noTools = new Rules(false, false, false, 3, 1, 4, 3, 20000);

		assert !find(storeys, 1, 3, 1, 1, 1, 1, 0.5, noTools).complete()
				: "a solid floor is not a way down until something breaks it";
		assert !find(storeys, 1, 1, 1, 1, 3, 1, 0.5, noTools).complete()
				: "a ceiling is not a way up with nothing to break or place";

		Path sunk = find(storeys, 1, 3, 1, 1, 1, 1, 0.5, storeyRules);
		assert sunk.complete() : "digging down through the floor was never found";
		assert sunk.cost() >= storeyRules.mineCost() : "going down cost less than the block it breaks";

		Path climbed = find(storeys, 1, 1, 1, 1, 3, 1, 0.5, storeyRules);
		assert climbed.complete() : "pillaring up through the ceiling was never found";
		assert climbed.cost() >= storeyRules.placeCost() + storeyRules.mineCost()
				: "going up cost less than the block it places and the one it breaks";
		for (long[] s : climbed.steps()) {
			assert s[0] == 1 && s[2] == 1 : "the only column there is, and the route left it";
		}
		// and every step of it is one block at a time, which is what a pillar is
		for (int i = 1; i < climbed.steps().size(); i++) {
			assert climbed.steps().get(i)[1] - climbed.steps().get(i - 1)[1] == 1
					: "a pillar went up more than one block in a step";
		}

		// Feet in the air: the start is snapped down to whatever is under it, or a plan made
		// mid-step expands nothing and reads as "that block is unreachable".
		assert groundY(room, 1, 4, 1, 5) == 1 : "the start did not snap down to the floor";
		assert groundY(room, 1, 1, 1, 5) == 1 : "a start already on the floor was moved";
		assert groundY(room, 1, 40, 1, 3) == 40 : "a start with nothing under it moved anyway";

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
