package com.damia.movrand;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * A* over block positions, so the bot can get to a block instead of walking at it.
 *
 * <p>The steering in {@link Avoidance} answers "is there a wall in front of me", which is
 * enough to cross open ground and not enough to leave a room. A base is corridors, floors
 * and ceilings, and the difference between the two is a search: this one walks, steps up,
 * drops, and — when it is allowed to — mines through and bridges across, which is the whole
 * reason a route through a building exists at all.
 *
 * <p>Two things about the output matter more than the search itself.
 *
 * <p>The first is that a step says <em>what kind of move it is</em>, not just where it ends.
 * A list of positions makes the follower guess at execution time what the search already
 * knew — was this a step up or a block to break, a drop or a gap to bridge — and every
 * guess is a fresh chance to disagree with the plan. {@link Kind} is the search handing its
 * reasoning over instead of throwing it away, and {@link PathMove} is what reads it.
 *
 * <p>The second is that the search is resumable. A full expansion is several milliseconds,
 * which is most of a client tick, and a bot that changes the world for a living replans
 * often. {@link Search} runs for a slice of a tick and picks up where it left off, so a
 * route that takes twenty milliseconds to find costs five quiet ticks rather than one
 * visible stutter.
 *
 * <p>The world is an interface rather than a {@code ClientLevel} for one reason: a search
 * this fiddly needs a test, and a test needs a world you can draw by hand. {@link Level}
 * below is the adapter that makes the real one fit.
 */
public final class PathFinder {

	/** Everything the search needs to know about the world, and nothing else. */
	public interface World {
		/** Whether the client actually has this column loaded. Unknown terrain is not air. */
		default boolean known(int x, int z) {
			return true;
		}

		/** Has collision: you cannot stand inside it. */
		boolean solid(int x, int y, int z);

		/**
		 * Whether the upper face is a dependable full support for a pillar placement.
		 *
		 * <p>The default keeps synthetic worlds useful: a full-height collision is treated as a
		 * full support. The real-world adapter overrides this with Minecraft's exact collision and
		 * sturdy-face predicates, which excludes slabs, repeaters, dust, rails and other partial
		 * shapes that can be stood on or ray-hit but cannot safely anchor a column.
		 */
		default boolean fullSupport(int x, int y, int z) {
			return topOf(x, y, z) >= 0.99;
		}

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

		/**
		 * A shut door or gate, which is a way through rather than a wall.
		 *
		 * <p>Without this a base is a set of sealed rooms: the route either mines the door or
		 * decides the room behind it cannot be reached. Baritone has the same special case for
		 * the same reason — a door is the one solid block that stops being solid if you ask.
		 */
		default boolean openable(int x, int y, int z) {
			return false;
		}

		/** May be mined through, if mining is allowed at all. */
		boolean breakable(int x, int y, int z);

		/**
		 * What breaking this block actually costs, as a multiple of walking one block.
		 *
		 * <p>A flat price per broken block is the whole reason a bot tunnels through obsidian
		 * rather than walking ten blocks round it: at four-blocks-a-swing the wall is cheaper
		 * than the corridor, and the search is right about that and wrong about everything
		 * else. Baritone prices a swing in ticks, from the block's hardness and the best tool
		 * on the bar, so obsidian costs what obsidian costs - hundreds of blocks of walking,
		 * which is exactly how far it is worth going to avoid one.
		 *
		 * <p>The default is 1, so a world with no opinion prices every block the same and the
		 * search behaves as it always did.
		 */
		default double breakCost(int x, int y, int z) {
			return 1;
		}

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

		/**
		 * Called at the top of every slice of a search.
		 *
		 * <p>A search now spans several ticks, and the bot spends those ticks changing the
		 * world it is searching. Anything remembered by position rather than by block state has
		 * to be let go of here, or a plan can be built partly on a wall that came down while it
		 * was being planned.
		 */
		default void beginSlice() {
		}
	}

	/**
	 * What one step of a route actually is.
	 *
	 * <p>This is the difference between a plan and a list of coordinates. The search knows
	 * whether a step is a walk, a jump onto a ledge, a block to swing at or a gap to floor —
	 * it decided that when it priced the move — and the follower has to know the same thing
	 * to execute it. Handing over only the position means working it out again from the
	 * world, at a different moment, with different code: two answers to one question, and
	 * the disagreements between them are exactly what "the bot walked into a hole" is.
	 */
	public enum Kind {
		/** Where the route begins. Never executed. */
		START,
		/** Level ground, one block, north/south/east/west. */
		WALK,
		/** Level ground, one block, corner-on. */
		DIAGONAL,
		/** Up onto a ledge: a jump, then forward. */
		ASCEND,
		/** Down off a ledge, one block or several. */
		DESCEND,
		/** Forward through something that has to be broken first. */
		MINE,
		/** Forward over a gap that has to be floored first. */
		BRIDGE,
		/** Straight up, standing on a block placed underfoot. */
		PILLAR,
		/** Straight down through a floor that has to be broken first. */
		DIG_DOWN
	}

	/**
	 * One node of a finished route.
	 *
	 * @param kind what the move <em>into</em> this position is
	 * @param cost what that move was priced at, in blocks-walked
	 */
	public record Step(int x, int y, int z, Kind kind, double cost) {
	}

	/** Directed transition temporarily rejected by the physical route follower. */
	public record Edge(long from, long to) {}

	/**
	 * What the search is allowed to do, and what each of those things is worth.
	 *
	 * @param heuristicWeight how far over the shortest route the search may settle for. See
	 *                        {@link #DEFAULT_HEURISTIC}.
	 */
	public record Rules(boolean mine, boolean bridge, boolean diagonal, int maxFall, int maxStepUp,
	                    double mineCost, double placeCost, int maxNodes, double heuristicWeight,
	                    int maxPlacements) {

		/** With no opinion on the weight, which is what the self-check has. */
		public Rules(boolean mine, boolean bridge, boolean diagonal, int maxFall, int maxStepUp,
		             double mineCost, double placeCost, int maxNodes) {
			this(mine, bridge, diagonal, maxFall, maxStepUp, mineCost, placeCost, maxNodes,
					DEFAULT_HEURISTIC, bridge ? Integer.MAX_VALUE : 0);
		}

		/** With a heuristic choice but no finite inventory model (mainly self-check callers). */
		public Rules(boolean mine, boolean bridge, boolean diagonal, int maxFall, int maxStepUp,
		             double mineCost, double placeCost, int maxNodes, double heuristicWeight) {
			this(mine, bridge, diagonal, maxFall, maxStepUp, mineCost, placeCost, maxNodes,
					heuristicWeight, bridge ? Integer.MAX_VALUE : 0);
		}

		public static Rules of(Config cfg, boolean canMine, boolean canBridge) {
			return of(cfg, canMine, canBridge ? Integer.MAX_VALUE : 0);
		}

		public static Rules of(Config cfg, boolean canMine, int availablePlacements) {
			int placements = Math.max(0, availablePlacements);
			return new Rules(cfg.pathMine && canMine, cfg.pathBridge && placements > 0, cfg.pathDiagonal,
					Math.max(1, cfg.pathMaxFall), Math.max(1, cfg.autoJumpMaxHeight),
					Math.max(1, cfg.pathMineCost), Math.max(1, cfg.pathPlaceCost),
					Math.max(500, cfg.pathMaxNodes), Math.max(1, cfg.pathHeuristicWeight), placements);
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
	 * @param steps    feet positions, in order; the first is where the search started
	 * @param complete false when the budget ran out and this is only the best partial route
	 * @param searched how many nodes were expanded, for the status line
	 */
	public record Path(List<Step> steps, boolean complete, int searched, double cost) {

		public boolean isEmpty() {
			return steps.size() <= 1;
		}

		/**
		 * Already standing somewhere the goal accepts.
		 *
		 * <p>Told apart from "no route" on purpose, and it is the single most important
		 * distinction this class makes. A goal that tests the <em>start</em> position hands
		 * back one step and calls itself complete — which reads as an empty route to anything
		 * only counting steps, so the caller replans, gets the same one-step answer, and
		 * stands perfectly still doing that forever. It is arrival, not failure.
		 */
		public boolean atGoal() {
			return complete && steps.size() <= 1;
		}

		public Step last() {
			return steps.getLast();
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
	 *
	 * <p>Weighted A* with a closed set is ε-admissible, so at 1.15 a route can be up to 15%
	 * longer than the shortest one. That is the setting behind it: 1.0 is exact and slow, and
	 * anything above it is a promise about how much worse a route is allowed to be.
	 */
	static final double DEFAULT_HEURISTIC = 1.15;

	private static final int[][] SIDES = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
	private static final int[][] CORNERS = {{1, 1}, {1, -1}, {-1, 1}, {-1, -1}};

	/** Ticks to walk one block at the vanilla 4.317 m/s, so a swing and a step compare. */
	public static final double TICKS_PER_BLOCK = 20 / 4.317;

	private PathFinder() {
	}

	// ------------------------------------------------------------- searching

	private static final class Node implements Comparable<Node> {
		final int x, y, z;
		double g;
		double f;
		Node from;
		Kind kind = Kind.START;
		double edge;
		/**
		 * Whether the floor under this position is one the route puts there. Every other node
		 * stands on the world as it is; a pillar and a bridge stand on their own placed block,
		 * and asking the unmodified world what is underneath them gets air — which is why a
		 * pillar could climb exactly one block before deciding it had nothing to jump off.
		 */
		boolean standsOnPlaced;
		/** Blocks this route has consumed before reaching this node. */
		int placements;

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

	private record NodeKey(long position, int placements) {}

	/**
	 * A search in progress.
	 *
	 * <p>Split out of {@code find} so it can be run a slice at a time. A full expansion of a
	 * route across a base is several milliseconds and a tick is fifty; doing it in one go is
	 * a visible stutter every time the bot changes its mind, and it changes its mind whenever
	 * it breaks something, which is constantly. Run in slices it is invisible, and the budget
	 * can be far larger than anything that would fit in a single tick.
	 */
	public static final class Search {
		private final World world;
		private final Goal goal;
		private final Rules rules;
		private final int gx, gy, gz;
		private final Map<NodeKey, Node> seen = new HashMap<>();
		private final PriorityQueue<Node> open = new PriorityQueue<>();
		private final Set<NodeKey> closed = new HashSet<>();
		private final Node start;
		private Node best;
		private double bestScore;
		private Node arrived;
		private int expanded;
		private boolean finished;
		private Set<Edge> blockedEdges = Set.of();

		public Search avoiding(Set<Edge> edges) {
			blockedEdges = Set.copyOf(edges);
			return this;
		}

		Search(World world, int sx, int sy, int sz, int gx, int gy, int gz, Goal goal, Rules rules) {
			this.world = world;
			this.goal = goal;
			this.rules = rules;
			this.gx = gx;
			this.gy = gy;
			this.gz = gz;
			this.start = new Node(sx, sy, sz);
			start.g = 0;
			start.f = heuristic(sx, sy, sz, gx, gy, gz, rules.heuristicWeight());
			seen.put(nodeKey(start), start);
			open.add(start);
			this.best = start;
			this.bestScore = Math.sqrt(dist2(sx, sy, sz, gx, gy, gz));
		}

		/**
		 * Expand for up to {@code nanoBudget} nanoseconds.
		 *
		 * @return true when there is nothing left to do — found, exhausted, or out of nodes
		 */
		public boolean advance(long nanoBudget) {
			if (finished) return true;
			world.beginSlice();
			boolean timed = nanoBudget != Long.MAX_VALUE;
			long deadline = timed ? System.nanoTime() + nanoBudget : 0;
			int sinceCheck = 0;
			while (!open.isEmpty() && expanded < rules.maxNodes()) {
				// the clock is not free either: once every sixty-four nodes is about half a
				// millisecond of resolution, which is finer than anything here needs
				if (timed && (++sinceCheck & 63) == 0 && System.nanoTime() >= deadline) return false;

				Node current = open.poll();
				// A position reached twice is not searched twice. Without this the queue
				// re-expands everything it has already been through, and a budget meant for a
				// route across a base is spent several times over on the room it started in.
				if (!closed.add(nodeKey(current))) continue;
				expanded++;

				if (goal.reached(current.x, current.y, current.z)) {
					arrived = current;
					finished = true;
					return true;
				}

				// keep the closest approach, so running out of budget still gets us moving
				double score = Math.sqrt(dist2(current.x, current.y, current.z, gx, gy, gz));
				if (score < bestScore) {
					bestScore = score;
					best = current;
				}

				for (Node next : neighbours(world, current, rules)) {
					if (blockedEdges.contains(new Edge(key(current.x, current.y, current.z),
							key(next.x, next.y, next.z)))) continue;
					NodeKey k = nodeKey(next);
					Node existing = seen.get(k);
					if (existing != null && existing.g <= next.g) continue;
					next.f = next.g + heuristic(next.x, next.y, next.z, gx, gy, gz, rules.heuristicWeight());
					seen.put(k, next);
					open.add(next);
				}
			}
			finished = true;
			return true;
		}

		public boolean done() {
			return finished;
		}

		public int expandedNodes() {
			return expanded;
		}

		/** The route as it stands: the real one if it was found, the best partial otherwise. */
		public Path result() {
			return arrived != null ? build(arrived, true, expanded) : build(best, false, expanded);
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
	 * Run a search to completion. Kept for callers with no tick to spread the work over, and
	 * for the self-check; the bot itself uses {@link Search} so a plan never costs a frame.
	 *
	 * @param goal what counts as arriving; the target coordinates still steer the search,
	 *             because the heuristic has to point somewhere even when the goal is a test
	 */
	public static Path find(World world, int sx, int sy, int sz, int gx, int gy, int gz,
	                        Goal goal, Rules rules) {
		Search search = begin(world, sx, sy, sz, gx, gy, gz, goal, rules);
		search.advance(Long.MAX_VALUE);   // no deadline at all, so it runs to the end
		return search.result();
	}

	public static Search begin(World world, int sx, int sy, int sz, int gx, int gy, int gz,
	                           Goal goal, Rules rules) {
		return new Search(world, sx, sy, sz, gx, gy, gz, goal, rules);
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
		out.removeIf(node -> node.placements > rules.maxPlacements());
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
				double dig = mineCost(world, rules, x, broken, z);
				for (int y = broken; y >= broken - rules.maxFall(); y--) {
					if (y - 1 < world.minY()) break;
					if (world.hazard(x, y, z) || world.hazard(x, y - 1, z)) break;
					if (world.solid(x, y - 1, z)) {
						add(out, from, x, y, z, dig + (broken - y) * FALL, Kind.DIG_DOWN, false);
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
		boolean footing = from.standsOnPlaced || pillarFooting(world, x, from.y, z);
		if (rules.bridge() && footing && from.y + 2 <= world.maxY()) {
			double extra = 0;
			for (int y = from.y + 1; y <= from.y + 2; y++) {
				if (passable(world, x, y, z, rules)) continue;
				if (!rules.mine() || !world.breakable(x, y, z) || world.leaksLava(x, y, z)
						|| world.hazard(x, y, z)) {
					return;
				}
				extra += mineCost(world, rules, x, y, z);
			}
			add(out, from, x, from.y + 1, z, rules.placeCost() + STEP_UP + extra, Kind.PILLAR, true);
		}
	}

	/** One horizontal move, resolved into whichever of walk / step up / drop / mine it is. */
	private static void step(World world, Node from, int dx, int dz, double base, Rules rules, List<Node> out) {
		int x = from.x + dx, z = from.z + dz;
		if (!world.known(x, z)) return;
		Kind level = base == WALK ? Kind.WALK : Kind.DIAGONAL;

		// level ground, or a step up we can jump
		for (int up = 0; up <= rules.maxStepUp(); up++) {
			int y = from.y + up;
			if (up > 0 && !clear(world, from.x, from.y + up + 1, from.z, rules)) break; // no headroom to jump
			if (!standable(world, x, y, z, rules)) continue;
			if (up > 0 && !passable(world, x, y + 1, z, rules)) continue;
			add(out, from, x, y, z, base + up * STEP_UP, up > 0 ? Kind.ASCEND : level, false);
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
						add(out, from, x, y + 1, z, base + (down - 1) * FALL, Kind.DESCEND, false);
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
				// each swing priced for the block it actually is, so a route through granite and
				// a route through obsidian stop being the same route at the same price
				double dig = (blockedFeet ? mineCost(world, rules, x, y, z) : 0)
						+ (blockedHead ? mineCost(world, rules, x, y + 1, z) : 0);
				boolean allBreakable = (!blockedFeet || world.breakable(x, y, z))
						&& (!blockedHead || world.breakable(x, y + 1, z));
				// a wall with a lake behind it is not a door, whatever it costs to break
				boolean floods = (blockedFeet && world.leaksLava(x, y, z))
						|| (blockedHead && world.leaksLava(x, y + 1, z));
				if (allBreakable && dig > 0 && !floods) {
					add(out, from, x, y, z, base + dig, Kind.MINE, false);
				}
			}
		}

		// Bridge: open air with nothing under it, floored with a block from the inventory.
		// Orthogonal only. A block placed diagonally has no orthogonal neighbour to place it
		// against, so a diagonal bridge move is a route the bot walks up to, cannot build,
		// replans, and walks up to again — which is what "it could not bridge" looks like.
		if (rules.bridge() && base == WALK && clear(world, x, from.y, z, rules)
				&& !world.solid(x, from.y - 1, z)) {
			// A hazard underneath is allowed on purpose, and it is the whole of how lava gets
			// covered. Capping it used to be an opportunistic behaviour bolted on beside the
			// job, which fired when the bot happened to be standing next to some and never when
			// lava was the thing in the way. A block dropped into lava is a floor, so it belongs
			// here, where the search decides whether it is worth the placement - and pays double
			// for it, so dry ground going the same way always wins.
			double risk = world.hazard(x, from.y - 1, z) ? rules.placeCost() : 0;
			add(out, from, x, from.y, z, base + rules.placeCost() + risk, Kind.BRIDGE, true);
		}
	}

	/** What one swing at this block is worth, in blocks walked. */
	private static double mineCost(World world, Rules rules, int x, int y, int z) {
		return rules.mineCost() * world.breakCost(x, y, z);
	}

	private static void add(List<Node> out, Node from, int x, int y, int z, double cost,
	                        Kind kind, boolean placed) {
		Node n = new Node(x, y, z);
		n.from = from;
		n.g = from.g + cost;
		n.edge = cost;
		n.kind = kind;
		n.standsOnPlaced = placed;
		n.placements = from.placements + (placed ? 1 : 0);
		out.add(n);
	}

	/** Two blocks of space for a player, and nothing in either that hurts. */
	private static boolean clear(World world, int x, int y, int z, Rules rules) {
		return world.known(x, z) && passable(world, x, y, z, rules)
				&& passable(world, x, y + 1, z, rules);
	}

	/**
	 * Room to be here. Anything low enough to step onto is not in the way — a bottom slab or
	 * a carpet underfoot is floor, not wall, and vanilla walks onto it without a jump.
	 */
	private static boolean passable(World world, int x, int y, int z, Rules rules) {
		if (y < world.minY() || y > world.maxY()) return false;
		if (world.hazard(x, y, z)) return false;
		return world.topOf(x, y, z) <= STEPPABLE || world.openable(x, y, z);
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
		double atFeet = world.topOf(x, y, z);
		return world.topOf(x, y - 1, z) > 0 || (atFeet > 0 && atFeet <= STEPPABLE);
	}

	/** A pillar is only offered where the block directly under the feet can anchor it. */
	private static boolean pillarFooting(World world, int x, int y, int z) {
		return world.fullSupport(x, y - 1, z);
	}

	private static Path build(Node end, boolean complete, int searched) {
		List<Step> steps = new ArrayList<>();
		double cost = end.g;
		for (Node n = end; n != null; n = n.from) {
			steps.add(new Step(n.x, n.y, n.z, n.from == null ? Kind.START : n.kind, n.edge));
		}
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
			// anything that holds weight is a floor, slab and carpet included
			if (world.topOf(x, y - d - 1, z) > 0) return y - d;
		}
		return y;
	}

	private static double heuristic(int x, int y, int z, int gx, int gy, int gz, double weight) {
		return Math.sqrt(dist2(x, y, z, gx, gy, gz)) * weight;
	}

	private static double dist2(int x, int y, int z, int gx, int gy, int gz) {
		double dx = x - gx, dy = y - gy, dz = z - gz;
		return dx * dx + dy * dy + dz * dz;
	}

	/** Same packing vanilla uses for block positions: 26 bits of x and z, 12 of y. */
	static long key(int x, int y, int z) {
		return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFFL);
	}

	private static NodeKey nodeKey(Node node) {
		return new NodeKey(key(node.x, node.y, node.z), node.placements);
	}

	// -------------------------------------------------------------- adapter

	/**
	 * The real world, seen through the questions the search actually asks.
	 *
	 * <p>Everything here is cached, and that is not a micro-optimisation. A search expands
	 * thousands of positions and asks four or five questions about each, so the honest
	 * version is a quarter of a million chunk lookups and voxel-shape resolutions to plan one
	 * route. Two things make that nearly free. The first is that a base is built out of a few
	 * dozen distinct block states, and every question except "is there lava next door" is
	 * answered by the state alone — so they are worked out once per state and then read from
	 * a map. The second is that consecutive questions are nearly always about the same
	 * position, so one remembered lookup removes most of the rest. Baritone does both, for
	 * the same reason, in {@code PrecomputedData} and {@code BlockStateInterface}.
	 */
	public static final class Level implements World {
		private final net.minecraft.client.multiplayer.ClientLevel level;
		private final Config cfg;
		/** For pricing a swing with the tools actually on the bar. Null means "no opinion". */
		private final net.minecraft.client.player.LocalPlayer player;
		/** What the route is allowed to break, or null for anything breakable. */
		private final java.util.Set<net.minecraft.world.level.block.Block> mayBreak;

		/** Reused rather than allocated: a quarter million BlockPos a plan is a quarter million too many. */
		private final net.minecraft.core.BlockPos.MutableBlockPos cursor =
				new net.minecraft.core.BlockPos.MutableBlockPos();
		private int lastX = Integer.MIN_VALUE, lastY, lastZ;
		private net.minecraft.world.level.block.state.BlockState lastState;

		// Per-state answers. Block states are interned, so these are identity lookups in all
		// but name, and every one of them replaces a chunk fetch and a shape resolution.
		private final java.util.HashMap<net.minecraft.world.level.block.state.BlockState, Double>
				tops = new java.util.HashMap<>();
		private final java.util.HashMap<net.minecraft.world.level.block.state.BlockState, Boolean>
				hazards = new java.util.HashMap<>();
		private final java.util.HashMap<net.minecraft.world.level.block.state.BlockState, Boolean>
				breakables = new java.util.HashMap<>();
		private final java.util.HashMap<net.minecraft.world.level.block.state.BlockState, Boolean>
				doors = new java.util.HashMap<>();
		private final java.util.HashMap<net.minecraft.world.level.block.state.BlockState, Double>
				costs = new java.util.HashMap<>();

		public Level(net.minecraft.client.multiplayer.ClientLevel level, Config cfg) {
			this(level, cfg, null, null);
		}

		/**
		 * @param player   whose hotbar decides what a swing costs; null prices every block alike
		 * @param mayBreak the only blocks the route may break through, or null for anything
		 */
		public Level(net.minecraft.client.multiplayer.ClientLevel level, Config cfg,
		             net.minecraft.client.player.LocalPlayer player,
		             java.util.Set<net.minecraft.world.level.block.Block> mayBreak) {
			this.level = level;
			this.cfg = cfg;
			this.player = player;
			this.mayBreak = mayBreak;
		}

		/**
		 * The state at a position, remembering the last one asked for.
		 *
		 * <p>The search asks four questions about a position in a row and then moves on, so a
		 * single remembered answer removes three quarters of the chunk lookups on its own.
		 */
		private net.minecraft.world.level.block.state.BlockState at(int x, int y, int z) {
			if (x == lastX && y == lastY && z == lastZ && lastState != null) return lastState;
			cursor.set(x, y, z);
			lastState = level.getBlockState(cursor);
			lastX = x;
			lastY = y;
			lastZ = z;
			return lastState;
		}

		@Override
		public void beginSlice() {
			// Only the position memo. What is cached per block state stays true however much
			// the world moves: a slab is half a block tall whenever anybody asks.
			lastX = Integer.MIN_VALUE;
			lastState = null;
		}

		@Override
		public boolean known(int x, int z) {
			return level.getChunkSource().getChunk(x >> 4, z >> 4,
					net.minecraft.world.level.chunk.status.ChunkStatus.FULL, false) != null;
		}

		@Override
		public boolean solid(int x, int y, int z) {
			return topOf(x, y, z) > 0;
		}

		@Override
		public double topOf(int x, int y, int z) {
			var state = at(x, y, z);
			Double cached = tops.get(state);
			if (cached != null) return cached;
			var shape = state.getCollisionShape(level, cursor);
			double top = shape.isEmpty() ? 0 : shape.max(net.minecraft.core.Direction.Axis.Y);
			tops.put(state, top);
			return top;
		}

		@Override
		public boolean fullSupport(int x, int y, int z) {
			var state = at(x, y, z);
			return !state.isAir() && state.getFluidState().isEmpty()
					&& state.isCollisionShapeFullBlock(level, cursor)
					&& state.isFaceSturdy(level, cursor, net.minecraft.core.Direction.UP,
							net.minecraft.world.level.block.SupportType.FULL);
		}

		@Override
		public boolean hazard(int x, int y, int z) {
			// the same definition the steering uses, so a route never leads somewhere the
			// dodge would immediately refuse to walk into
			var state = at(x, y, z);
			Boolean cached = hazards.get(state);
			if (cached != null) return cached;
			boolean bad = Avoidance.hazardAt(level, cursor, cfg);
			hazards.put(state, bad);
			return bad;
		}

		@Override
		public boolean breakable(int x, int y, int z) {
			var state = at(x, y, z);
			Boolean cached = breakables.get(state);
			if (cached != null) return cached;
			boolean can = BlockTargets.breakable(state, level, cursor)
					// "Only break what I picked" is a route restriction, not a target one.
					// Somebody who ticked redstone and containers did not ask for a hole through
					// the obsidian wall on the way to them, and the search will happily take one
					// if nothing says otherwise.
					&& (mayBreak == null || mayBreak.contains(state.getBlock()));
			breakables.put(state, can);
			return can;
		}

		@Override
		public double breakCost(int x, int y, int z) {
			if (player == null) return 1;
			var state = at(x, y, z);
			Double cached = costs.get(state);
			if (cached != null) return cached;
			double ticks = Bot.breakTicks(player, level, cursor, state);
			// in blocks walked, so it is comparable with everything else the search adds up
			double cost = Math.max(0.5, Math.min(80, ticks / TICKS_PER_BLOCK));
			costs.put(state, cost);
			return cost;
		}

		@Override
		public boolean openable(int x, int y, int z) {
			var state = at(x, y, z);
			Boolean cached = doors.get(state);
			if (cached != null) return cached;
			boolean door = Bot.opensByHand(level, cursor);
			doors.put(state, door);
			return door;
		}

		@Override
		public boolean leaksLava(int x, int y, int z) {
			// The one question that genuinely depends on the neighbours rather than the state,
			// so the one that cannot be cached by state. It is also asked only about blocks the
			// route is considering breaking, which is a small fraction of what it looks at.
			at(x, y, z);
			return Avoidance.floodsWhenBroken(level, cursor, cfg.coverWater, true);
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
		/**
		 * Whether everything outside the drawing is solid rock rather than open sky.
		 *
		 * <p>Open by default, which is usually what a test means and occasionally the opposite:
		 * a room drawn as sealed is only sealed if the search cannot walk off the edge of the
		 * paper, drop onto the implied floor underneath, and stroll round the outside of it.
		 */
		private final boolean bounded;

		/**
		 * '#' solid, '~' hazard, '.' air, 'o' solid but unbreakable, '_' a bottom slab,
		 * 'X' solid and breakable but forty times the price (obsidian, in other words),
		 * 'D' a shut door: solid, unbreakable, and a way through all the same.
		 */
		Sketch(int baseY, String[]... layers) {
			this(false, baseY, layers);
		}

		Sketch(boolean bounded, int baseY, String[]... layers) {
			this.baseY = baseY;
			this.layers = layers;
			this.bounded = bounded;
		}

		private char at(int x, int y, int z) {
			int ly = y - baseY;
			if (ly < 0) return '#';
			char outside = bounded ? 'o' : '.';
			if (ly >= layers.length) return outside;
			String[] rows = layers[ly];
			if (z < 0 || z >= rows.length) return outside;
			String row = rows[z];
			if (x < 0 || x >= row.length()) return outside;
			return row.charAt(x);
		}

		@Override
		public boolean solid(int x, int y, int z) {
			char c = at(x, y, z);
			return c == '#' || c == 'o' || c == 'X' || c == 'D';
		}

		@Override
		public boolean hazard(int x, int y, int z) {
			return at(x, y, z) == '~';
		}

		@Override
		public boolean openable(int x, int y, int z) {
			return at(x, y, z) == 'D';
		}

		@Override
		public double topOf(int x, int y, int z) {
			return at(x, y, z) == '_' ? 0.5 : solid(x, y, z) ? 1 : 0;
		}

		@Override
		public boolean breakable(int x, int y, int z) {
			char c = at(x, y, z);
			return c == '#' || c == 'X';
		}

		/** 'X' is the expensive one: breakable, and forty blocks of walking to do it. */
		@Override
		public double breakCost(int x, int y, int z) {
			return at(x, y, z) == 'X' ? 40 : 1;
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

	private static boolean visits(Path p, int x, int y, int z) {
		for (Step s : p.steps()) if (s.x() == x && s.y() == y && s.z() == z) return true;
		return false;
	}

	private static boolean has(Path p, Kind kind) {
		for (Step s : p.steps()) if (s.kind() == kind) return true;
		return false;
	}

	private static long count(Path p, Kind kind) {
		return p.steps().stream().filter(step -> step.kind() == kind).count();
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
		assert p.steps().getFirst().x() == 1 && p.steps().getFirst().z() == 1
				: "the path must start where we are";
		assert p.steps().getFirst().kind() == Kind.START : "the first step is not a move";
		Step last = p.last();
		Step firstMove = p.steps().get(1);
		Edge refused = new Edge(key(1, 1, 1), key(firstMove.x(), firstMove.y(), firstMove.z()));
		Search alternate = begin(room, 1, 1, 1, 3, 1, 3, within(0.5, 3, 1, 3), walk)
				.avoiding(Set.of(refused));
		alternate.advance(Long.MAX_VALUE);
		assert alternate.result().complete() : "failed edge prevented using the other corridor";
		List<Step> rerouted = alternate.result().steps();
		for (int i = 1; i < rerouted.size(); i++) {
			Step a = rerouted.get(i - 1), b = rerouted.get(i);
			assert !refused.equals(new Edge(key(a.x(), a.y(), a.z()), key(b.x(), b.y(), b.z())))
					: "replan repeated the physically blocked step";
		}
		assert last.x() == 3 && last.y() == 1 && last.z() == 3 : "the path must end at the goal";
		// and it must actually go round the pillar at (2,2) rather than through it
		assert !visits(p, 2, 1, 2) : "the route walked through a solid pillar";
		// every step after the first says what it is, and on open floor that is a walk
		for (int i = 1; i < p.steps().size(); i++) {
			assert p.steps().get(i).kind() == Kind.WALK
					: "a level step on open floor came back as " + p.steps().get(i).kind();
			assert p.steps().get(i).cost() > 0 : "a step was priced at nothing";
		}

		// Two sealed cells with a wall between them. Floor everywhere, so the only thing
		// separating them is the wall itself.
		String[] split = {"#####", "#.#.#", "#.#.#", "#.#.#", "#####"};
		Sketch sealed = new Sketch(0, floor, split, split);
		Path out = find(sealed, 1, 1, 1, 3, 1, 1, 0.5, walk);
		assert !out.complete() : "there is no way through a solid wall without mining";
		net.minecraft.world.phys.AABB dropBox = new net.minecraft.world.phys.AABB(3.375, 1, 1.375, 3.625, 1.25, 1.625);
		Goal pickup = (x, y, z) -> DropCollector.pickupOverlap(
				new net.minecraft.world.phys.AABB(x + 0.2, y, z + 0.2, x + 0.8, y + 1.8, z + 0.8), dropBox);
		assert !pickup.reached(1, 1, 1) : "pickup through a wall accepted the starting square";
		assert !find(sealed, 1, 1, 1, 3, 1, 1, pickup, walk).complete()
				: "sealed-room drop had a walking route";
		Path pickupRoute = find(room, 1, 1, 1, 3, 1, 1, pickup, walk);
		assert pickupRoute.complete() && !pickupRoute.atGoal() : "nearby drop with a doorway was abandoned";

		// with mining on, there is - and it costs what two broken blocks cost
		Path dug = find(sealed, 1, 1, 1, 3, 1, 1, 0.5, mining);
		assert dug.complete() : "mining should have found a way through the wall";
		assert dug.cost() >= 2 * mining.mineCost() : "digging through two blocks cost " + dug.cost();
		assert visits(dug, 2, 1, 1) : "the route claimed to mine out without entering the wall";
		// and it says so: the follower has to know this step is a swing, not a stroll
		assert has(dug, Kind.MINE) : "a route through a wall reported no mining step";

		// A hazard is never crossed, even when it is the only straight line.
		String[] lavaFloor = {"#####", "#####", "##~##", "#####", "#####"};
		String[] open = {".....", ".....", ".....", ".....", "....."};
		Sketch lava = new Sketch(0, lavaFloor, open, open);
		Path round = find(lava, 2, 1, 1, 2, 1, 3, 0.5, walk);
		assert round.complete() : "there is a way round the lava";
		assert !visits(round, 2, 1, 2) : "the route stepped into lava";

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
		Step previous = null;
		for (Step s : across.steps()) {
			if (previous != null) {
				boolean floored = chasm.solid(s.x(), s.y() - 1, s.z());
				boolean diagonal = s.x() != previous.x() && s.z() != previous.z();
				assert floored || !diagonal
						: "a diagonal step onto empty air is a bridge that cannot be built";
			}
			previous = s;
		}

		// A trench too wide to walk round and too deep to hop into is bridged, and the route
		// says so — the follower has to know to stop at the edge and put a block down, and
		// working that out again from the world is how a bot walks into the hole it planned
		// to build over.
		String[] trench = {"#####", "#####", ".....", "#####", "#####"};
		Rules noFalling = new Rules(false, true, false, 0, 1, 4, 3, 20000);
		Path built = find(new Sketch(0, trench, air, air), 2, 1, 1, 2, 1, 3, 0.5, noFalling);
		assert built.complete() : "a trench with a bridge allowed should be crossable";
		assert has(built, Kind.BRIDGE) : "a route over a trench reported no bridging step";
		// and it is the placing that made it possible, not the walking
		assert find(new Sketch(0, trench, air, air), 2, 1, 1, 2, 1, 3, 0.5,
				new Rules(false, false, false, 0, 1, 4, 3, 20000)).isEmpty()
				: "a trench was crossed with nothing to place in it";

		// Inventory is part of route feasibility, not something discovered after walking to the
		// second gap. Two empty rows need two blocks and a one-block budget must not promise it.
		String[] doubleTrench = {"#####", "#####", ".....", ".....", "#####", "#####"};
		String[] doubleAir = {".....", ".....", ".....", ".....", ".....", "....."};
		Sketch twoGaps = new Sketch(true, 0, doubleTrench, doubleAir, doubleAir);
		Rules oneBlock = new Rules(false, true, false, 0, 1, 4, 3, 20000,
				DEFAULT_HEURISTIC, 1);
		Rules twoBlocks = new Rules(false, true, false, 0, 1, 4, 3, 20000,
				DEFAULT_HEURISTIC, 2);
		Path shortOnBlocks = find(twoGaps, 2, 1, 1, 2, 1, 4, 0.5, oneBlock);
		assert !shortOnBlocks.complete() && count(shortOnBlocks, Kind.BRIDGE) <= 1
				: "a one-block inventory planned two bridge placements";
		Path enoughBlocks = find(twoGaps, 2, 1, 1, 2, 1, 4, 0.5, twoBlocks);
		assert enoughBlocks.complete() && count(enoughBlocks, Kind.BRIDGE) == 2
				: "two available blocks did not cross two gaps";

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
		for (Step s : paved2.steps()) {
			assert s.y() == 1 : "the route left the slab floor for y" + s.y() + ", which is mid-air";
		}
		assert !pillarFooting(paved, 1, 2, 1)
				: "a half slab was treated as a valid pillar anchor";
		assert pillarFooting(room, 1, 1, 1)
				: "a full block floor stopped being a valid pillar anchor";
		// and the search still refuses to walk into something that genuinely fills the space
		Sketch cubes = new Sketch(0, floor, walls, walls);
		assert !visits(find(cubes, 1, 1, 1, 2, 1, 2, 0.5, walk), 2, 1, 2)
				: "a full cube stopped being a wall";

		// What a broken block costs has to be what breaking THAT block costs. A flat price is
		// how a bot ends up with a tunnel through the obsidian wall it was asked to walk round:
		// at four-blocks-a-swing the wall is cheaper than the corridor, and the search is not
		// wrong about that, it is badly informed. Here the direct line is one 'X' and the way
		// round is a handful of steps, so the price is the only thing deciding it.
		String[] yard = {"#######", "#######", "#######"};
		String[] gappyWall = {".......", "..XXX..", "......."};
		String[] solidWall = {".......", "ooXXXoo", "......."};
		String[] openAir = {".......", ".......", "......."};
		Path detour = find(new Sketch(0, yard, gappyWall, openAir), 3, 1, 0, 3, 1, 2, 0.5, mining);
		assert detour.complete() : "there is a way round the expensive wall and it was not found";
		for (Step n : detour.steps()) {
			assert !(n.y() == 1 && n.z() == 1 && n.x() >= 2 && n.x() <= 4)
					: "the route went through the expensive wall at " + n.x() + "," + n.y() + "," + n.z();
		}
		// and it is a price, not a ban: seal the way round and the wall is the route again
		assert find(new Sketch(0, yard, solidWall, openAir), 3, 1, 0, 3, 1, 2, 0.5, mining).complete()
				: "with no way round, an expensive wall is still a way through";

		// Lava is a hole that happens to kill, so a route across it is a placed block like the
		// route across any other gap. This is the whole of how liquid gets covered: it used to
		// be a behaviour standing beside the job, and the two things it needed to fire were so
		// nearly incompatible that it almost never did.
		Rules onlyPlacing = new Rules(false, true, false, 3, 1, 4, 3, 5000);
		String[] moatFloor = {"###", "~~~", "###"};
		String[] moatAir = {"...", "...", "..."};
		Sketch moat = new Sketch(true, 0, moatFloor, moatAir, moatAir);
		Path over = find(moat, 1, 1, 0, 1, 1, 2, 0.5, onlyPlacing);
		assert over.complete() : "a one-block lava moat should be bridged, not stared at";
		assert has(over, Kind.BRIDGE) : "the block over the lava was not reported as a placement";
		for (Step n : over.steps()) {
			assert !moat.hazard(n.x(), n.y(), n.z())
					: "the route stands inside the lava at " + n.x() + "," + n.y() + "," + n.z();
		}
		// and never waded into: the block goes on top of it, the feet never go in it
		assert find(moat, 1, 1, 0, 1, 1, 2, 0.5, walk).isEmpty()
				: "with nothing to place, lava is not a route";

		// A shut door is the one solid block that stops being solid if you ask. Read as a wall
		// it is worse than an obstacle: with the route only allowed to break what was selected,
		// every room in a base with its door shut is a room the bot decides it cannot reach.
		String[] doorway = {".......", "ooDDDoo", "......."};
		Path through = find(new Sketch(true, 0, yard, doorway, openAir), 3, 1, 0, 3, 1, 2, 0.5, walk);
		assert through.complete() : "a shut door should be a way through, not a wall";
		// and a wall really is one: same room, same search, no handle
		assert find(new Sketch(true, 0, yard, solidWall, openAir), 3, 1, 0, 3, 1, 2, 0.5, walk).isEmpty()
				: "without mining or a door there is no way out of a sealed room";

		// A goal is a test, not a radius, and the test is what stops a search declaring
		// victory four blocks from the target with a wall in between.
		Path fussy = find(room, 1, 1, 1, 3, 1, 3, (x, y, z) -> x == 3 && y == 1 && z == 3, walk);
		assert fussy.complete() : "an exact goal in an open room should have been reached";
		assert fussy.last().x() == 3 && fussy.last().y() == 1 && fussy.last().z() == 3
				: "the exact goal was not where it stopped";
		Path never = find(room, 1, 1, 1, 3, 1, 3, (x, y, z) -> false, walk);
		assert !never.complete() : "a goal nothing satisfies was somehow satisfied";

		// Standing on the goal already. This is one step, and it is complete, and telling it
		// apart from "no route" is the whole difference between a bot that gets on with the
		// job and one that replans the same empty answer twenty times a second forever.
		Path here = find(room, 1, 1, 1, 1, 1, 1, 0.5, walk);
		assert here.complete() && here.isEmpty() : "a search from the goal should be a complete non-move";
		assert here.atGoal() : "already standing on the goal did not report as arrival";
		assert !never.atGoal() : "a failed search reported as arrival";

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
		assert has(sunk, Kind.DIG_DOWN) : "the route down did not report digging down";

		Path climbed = find(storeys, 1, 1, 1, 1, 3, 1, 0.5, storeyRules);
		assert climbed.complete() : "pillaring up through the ceiling was never found";
		assert climbed.cost() >= storeyRules.placeCost() + storeyRules.mineCost()
				: "going up cost less than the block it places and the one it breaks";
		assert has(climbed, Kind.PILLAR) : "the route up did not report pillaring";
		for (Step s : climbed.steps()) {
			assert s.x() == 1 && s.z() == 1 : "the only column there is, and the route left it";
		}
		// and every step of it is one block at a time, which is what a pillar is
		for (int i = 1; i < climbed.steps().size(); i++) {
			assert climbed.steps().get(i).y() - climbed.steps().get(i - 1).y() == 1
					: "a pillar went up more than one block in a step";
		}

		// A step up onto a ledge says so, because the follower has to jump for it and the
		// world at execution time cannot tell a ledge apart from a wall it should mine.
		Sketch ledge = new Sketch(0,
				new String[]{"###", "###", "###"},
				new String[]{"...", ".#.", "..."},
				new String[]{"...", "...", "..."},
				new String[]{"...", "...", "..."});
		Path up = find(ledge, 1, 1, 0, 1, 2, 1, (x, y, z) -> x == 1 && y == 2 && z == 1, walk);
		assert up.complete() && has(up, Kind.ASCEND) : "stepping onto a ledge was not an ascend";

		// Feet in the air: the start is snapped down to whatever is under it, or a plan made
		// mid-step expands nothing and reads as "that block is unreachable".
		assert groundY(room, 1, 4, 1, 5) == 1 : "the start did not snap down to the floor";
		assert groundY(room, 1, 1, 1, 5) == 1 : "a start already on the floor was moved";
		assert groundY(room, 1, 40, 1, 3) == 40 : "a start with nothing under it moved anyway";

		// A sealed room is left by the cheapest wall, not by the one facing the way we want to
		// go. The box below is one block thick on its north side and four on its south, the
		// goal is outside it to the south, and the only other way round is the long corridor at
		// the west edge - so the answer is out through the thin wall and the long way round,
		// which is what a person does and what a bot pricing every swing the same will not.
		String[] boxFloor = new String[8];
		java.util.Arrays.fill(boxFloor, "#####");
		String[] boxWalls = {".....", ".o#o.", ".o.o.", ".o#o.", ".o#o.", ".o#o.", ".o#o.", "....."};
		Sketch box = new Sketch(true, 0, boxFloor, boxWalls, boxWalls);
		Rules thickWall = new Rules(true, false, false, 3, 1, 8, 3, 20000);
		Path out2 = find(box, 2, 1, 2, 2, 1, 7, 0.5, thickWall);
		assert out2.complete() : "a sealed box with breakable walls should have a way out";
		assert visits(out2, 2, 1, 1) : "the route did not leave through the one-block wall";
		for (int z = 3; z <= 6; z++) {
			assert !visits(out2, 2, 1, z)
					: "the route dug through four blocks of wall to save walking round";
		}
		// and it really is sealed: with nothing to break there is no way out at all
		assert find(box, 2, 1, 2, 2, 1, 7, 0.5, walk).isEmpty()
				: "a sealed box was left without breaking anything";

		// The weight is a promise about how much worse a route may be, so the exact search must
		// never come back with a longer one than the weighted search settled for.
		Rules exact = new Rules(false, false, false, 3, 1, 4, 3, 20000, 1.0);
		Rules greedy = new Rules(false, false, false, 3, 1, 4, 3, 20000, 1.5);
		Path best = find(room, 1, 1, 1, 3, 1, 3, 0.5, exact);
		Path quick = find(room, 1, 1, 1, 3, 1, 3, 0.5, greedy);
		assert best.complete() && quick.complete() : "both weights should find the way round a pillar";
		assert best.cost() <= quick.cost() + 1e-9
				: "the exact search found a longer route (%.2f) than the weighted one (%.2f)"
				.formatted(best.cost(), quick.cost());
		assert new Rules(false, false, false, 3, 1, 4, 3, 20000).heuristicWeight() == DEFAULT_HEURISTIC
				: "a search with no opinion on the weight did not get the default";

		// The budget is a floor under the answer, not a cliff: a search that runs out still
		// hands back the best it found, and that is what keeps the bot walking.
		Rules tiny = new Rules(false, false, false, 3, 1, 4, 3, 500);
		Path partial = find(room, 1, 1, 1, 400, 1, 400, 0.5, tiny);
		assert !partial.complete() : "that goal is not reachable";
		assert partial.searched() <= 500 : "the node budget was ignored: " + partial.searched();
		// and "the best it found" has to mean something: a partial route nobody can walk is the
		// same as no route, and leaves the caller with nothing to do but plan it again
		Sketch corridor = new Sketch(true, 0,
				new String[]{"#########################"},
				new String[]{"........................."},
				new String[]{"........................."});
		Path stopped = find(corridor, 0, 1, 0, 400, 1, 0, 0.5, tiny);
		assert !stopped.complete() : "that goal is off the end of the world";
		assert !stopped.isEmpty() : "a search that ran out of budget handed back nothing to walk";
		assert stopped.last().x() > 0 : "the best partial route made no progress at all";

		// Resuming. A search run a slice at a time has to arrive at the same answer as one run
		// in a single go, or a plan means something different depending on how busy the client
		// was when it was made.
		Search sliced = begin(room, 1, 1, 1, 3, 1, 3, within(0.5, 3, 1, 3), walk);
		int slices = 0;
		while (!sliced.advance(1) && slices++ < 100_000) {
			// one nanosecond at a time, which is as adversarial as slicing gets
		}
		assert sliced.done() : "a sliced search never finished";
		Path resumed = sliced.result();
		assert resumed.complete() : "slicing lost the route";
		assert resumed.steps().equals(p.steps()) : "a sliced search found a different route";

		// packing must round-trip the coordinates a real world uses
		assert key(0, 0, 0) != key(1, 0, 0) && key(0, 0, 0) != key(0, 1, 0) && key(0, 0, 0) != key(0, 0, 1)
				: "the position key collides on a unit step";
		assert key(-30_000_000, -64, 30_000_000) != key(30_000_000, 320, -30_000_000)
				: "the position key collides at world edges";

		System.out.println("PathFinder self-check passed");
	}
}
