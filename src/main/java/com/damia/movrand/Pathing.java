package com.damia.movrand;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;

import java.util.List;
import java.util.Set;

/**
 * Getting somewhere: planning, replanning, and always having an answer.
 *
 * <p>Three things here, and each of them is the fix for a specific way the old navigation
 * could stand perfectly still forever.
 *
 * <p><b>The search runs a slice at a time.</b> A full expansion is several milliseconds and a
 * tick is fifty, so planning on the tick is a visible stutter — and when a plan fails and is
 * immediately retried, it is twenty of those a second producing the same answer. Sliced, a
 * twenty-millisecond search costs ten quiet ticks and the frame rate never notices.
 *
 * <p><b>Every outcome is a reported outcome.</b> {@link Nav#ARRIVED} and {@link Nav#NO_ROUTE}
 * are told apart, which sounds obvious and is the entire bug: a goal that tests the position
 * you are already standing on returns a route of one step, which is indistinguishable from an
 * empty one if all you do is count. The old code counted, read arrival as failure, replanned,
 * got the same one-step answer, and did that until somebody switched it off.
 *
 * <p><b>Nothing is unbounded.</b> A failed plan rests before it is retried, and a run of plans
 * that produce nothing usable ends in {@code NO_ROUTE} rather than in another attempt. The
 * caller can then do something else — give up on that block and take the next one — which is
 * what a person does and what the bot never used to get the chance to.
 *
 * <p>Baritone reaches the same place from the other direction: a worker thread with a
 * millisecond budget instead of slices on the tick. The thread is faster and needs the world
 * to be safe to read from off the client thread, which on a client without mixins it is not.
 */
public final class Pathing {

	public enum Nav {
		/** Working out where to go. Nothing to walk yet, and that state is bounded. */
		PLANNING,
		/** Walking a route. */
		WALKING,
		/** Standing somewhere the goal accepts. */
		ARRIVED,
		/** There is no way there from here, and saying so is the point. */
		NO_ROUTE
	}

	private final Config cfg;
	private final NativeNavigation nativeNav;

	private PathFinder.Search search;
	/** What the in-flight search is for, so a changed mind cancels it rather than walking it. */
	private BlockPos searchFor;
	/** Whether that search continues the current route rather than replacing it. */
	private boolean searchIsContinuation;
	private int searchTicks;

	private PathRunner runner;
	private PathRunner next;
	private BlockPos runnerFor;

	private int rest;
	private int attempts;
	private BlockPos activeGoal;
	private boolean allowEdits = true;
	private long tick;
	private long sliceRemaining;
	private final java.util.Map<PathFinder.Edge, Long> failedEdges = new java.util.HashMap<>();
	/**
	 * How much route to leave before starting to think about the next leg, in ticks.
	 *
	 * <p>Drawn fresh for every route rather than fixed. A constant here is a bot that always
	 * starts planning at exactly the same distance from the end of a leg, which is a rhythm
	 * rather than a decision — and it is the one number in this class anything watching from
	 * outside could actually see, because it is the moment the walk stops being smooth.
	 */
	private int planAheadTicks;

	public String status = "";
	public double lastCost;
	public int lastNodes;
	/** Running totals the job shows on its HUD. */
	public int placed;
	public int mined;
	public BlockPos justMined;
	public String justMinedName = "";

	public Pathing(Config cfg) {
		this.cfg = cfg;
		this.nativeNav = new NativeNavigation(cfg);
	}

	public void reset() {
		nativeNav.reset();
		search = null;
		searchFor = null;
		searchIsContinuation = false;
		runner = null;
		next = null;
		runnerFor = null;
		rest = 0;
		attempts = 0;
		activeGoal = null;
		failedEdges.clear();
		planAheadTicks = 0;
		status = "";
	}

	/**
	 * Told from outside that the world here is not what the plan assumed.
	 *
	 * <p>Cheaper than waiting for the runner to notice, and the caller often knows first — the
	 * local steering refuses a step, a block lands in the doorway. The plan goes; the goal
	 * stays, so the next tick plans a fresh route to the same place.
	 */
	public void invalidate(String why) {
		nativeNav.reset();
		if (runner != null) rememberFailure(runner.currentEdge());
		status = why;
		runner = null;
		next = null;
		search = null;
		searchFor = null;
		searchIsContinuation = false;
		searchTicks = 0;
	}

	private void rememberFailure(PathFinder.Edge edge) {
		if (edge == null) return;
		if (failedEdges.size() >= 128) failedEdges.remove(failedEdges.entrySet().stream()
				.min(java.util.Map.Entry.comparingByValue()).orElseThrow().getKey());
		failedEdges.put(edge, tick + Math.round(cfg.pathFailedEdgeRetrySec * 20));
	}

	public boolean walking() {
		return runner != null;
	}

	public BlockPos breakingBlock() {
		if (cfg.baritoneNavigation) return nativeNav.breakingBlock();
		return runner == null ? null : runner.breakingBlock();
	}

	public int step() {
		return runner == null ? 0 : runner.step();
	}

	public int length() {
		return runner == null ? 0 : runner.length();
	}

	/** What the route is doing right now, so the job can label the phase honestly. */
	public PathFinder.Kind currentKind() {
		return runner == null ? PathFinder.Kind.START : runner.currentKind();
	}

	// ------------------------------------------------------------------ tick

	/**
	 * One tick of getting to {@code goalPos}.
	 *
	 * @param goalPos  what the goal is about. Compared between ticks to notice a changed mind,
	 *                 because {@code goal} itself is a fresh lambda every time.
	 * @param goal     what counts as arriving
	 * @param mayBreak the only blocks the route may break through, or null for anything
	 */
	public Nav tick(PathMove.Ctx ctx, Bot.Steer steer, BlockPos goalPos,
	                PathFinder.Goal goal, Set<Block> mayBreak) {
		return tick(ctx, steer, goalPos, goal, mayBreak, true);
	}

	public Nav tick(PathMove.Ctx ctx, Bot.Steer steer, BlockPos goalPos,
	                PathFinder.Goal goal, Set<Block> mayBreak, boolean edits) {
		if (cfg.baritoneNavigation) {
			Nav result = nativeNav.tick(ctx, steer, goalPos, goal, mayBreak, edits);
			status = nativeNav.status;
			lastCost = nativeNav.cost;
			lastNodes = nativeNav.nodes;
			return result;
		}
		if (!goalPos.equals(activeGoal) || allowEdits != edits) {
			reset();
			activeGoal = goalPos;
			allowEdits = edits;
		}
		tick++;
		sliceRemaining = (long) (Math.max(0.1, cfg.pathSliceMs) * 1_000_000L);
		failedEdges.entrySet().removeIf(entry -> entry.getValue() <= tick);
		justMined = null;
		if (rest > 0) rest--;

		// A changed mind throws away the plan rather than walking the old one somewhere we
		// have stopped wanting to go.
		if (runnerFor != null && !runnerFor.equals(goalPos)) {
			runner = null;
			next = null;
			runnerFor = null;
			attempts = 0;
		}
		if (searchFor != null && !searchFor.equals(goalPos)) {
			search = null;
			searchFor = null;
		}

		// Already there. Told apart from having no route on purpose: this is the whole reason
		// the bot used to stand in front of a block it could reach, replanning forever.
		if (goal.reached(feetX(ctx), feetY(ctx), feetZ(ctx))) {
			attempts = 0;
			status = "arrived";
			return Nav.ARRIVED;
		}

		advanceSearch(ctx, goalPos, goal, mayBreak);

		if (runner != null) {
			Nav walked = walk(ctx, steer, goalPos, goal, mayBreak);
			if (walked != null) return walked;
		}

		// No route in hand. Start one if we are allowed to, and say so either way.
		if (search == null && rest == 0) {
			if (attempts >= attemptLimit()) {
				status = "no way there";
				return Nav.NO_ROUTE;
			}
			begin(ctx, ctx.player().blockPosition(), goalPos, goal, mayBreak, false);
			// Short local routes can start walking this tick within the same slice budget.
			advanceSearch(ctx, goalPos, goal, mayBreak);
			if (runner != null) {
				Nav walked = walk(ctx, steer, goalPos, goal, mayBreak);
				if (walked != null) return walked;
			}
		}
		status = search != null ? "working out a route" : status;
		return attempts >= attemptLimit() ? Nav.NO_ROUTE : Nav.PLANNING;
	}

	private Nav walk(PathMove.Ctx ctx, Bot.Steer steer, BlockPos goalPos,
	                 PathFinder.Goal goal, Set<Block> mayBreak) {
		PathRunner.Result result = runner.tick(ctx, steer);
		placed += runner.placed;
		mined += runner.mined;
		runner.placed = 0;
		runner.mined = 0;
		if (runner.justMined != null) {
			justMined = runner.justMined;
			justMinedName = runner.justMinedName;
		}

		switch (result) {
			case RUNNING -> {
				status = runner.describe();
				planAhead(ctx, goalPos, goal, mayBreak);
				return Nav.WALKING;
			}
			case DONE -> {
				// The end of the route. If it reached what it was for, that is arrival; if it
				// only got closer, this is a segment and the next one starts from here.
				boolean got = runner.complete && goal.reached(feetX(ctx), feetY(ctx), feetZ(ctx));
				runner = null;
				runnerFor = null;
				if (got) {
					attempts = 0;
					status = "arrived";
					return Nav.ARRIVED;
				}
				if (next != null) {
					runner = next;
					runnerFor = goalPos;
					next = null;
					status = "carrying on";
					return Nav.WALKING;
				}
				// A segment that got somewhere is progress, whatever the next plan says.
				attempts = 0;
				return null;
			}
			case FAILED -> {
				status = runner.reason;
				rememberFailure(runner.failedEdge());
				steer.clear();
				// Running for a tick is not proof of progress. Count failures even when a route
				// took a few steps first, and cancel any continuation from its obsolete endpoint.
				runner = null;
				runnerFor = null;
				next = null;
				search = null;
				searchFor = null;
				searchIsContinuation = false;
				attempts++;
				rest();
				return null;
			}
		}
		return null;
	}

	/**
	 * Wait before trying again.
	 *
	 * <p>Jittered, and never nothing. A failed plan retried on the very next tick asks the same
	 * question of the same world from the same place and gets the same answer, twenty times a
	 * second — so the rest is what stops a goal nobody can reach from burning a core, and the
	 * jitter is what stops the retries landing on a fixed cadence.
	 */
	private void rest() {
		rest = Rng.ticks(cfg.pathRestMinSec, cfg.pathRestMaxSec);
	}

	private int attemptLimit() {
		return Math.max(1, cfg.pathAttempts);
	}

	// ---------------------------------------------------------------- search

	private void advanceSearch(PathMove.Ctx ctx, BlockPos goalPos, PathFinder.Goal goal,
	                           Set<Block> mayBreak) {
		if (search == null) return;
		if (sliceRemaining <= 0) return;
		long started = System.nanoTime();
		boolean done = search.advance(sliceRemaining);
		// Return the best safe partial route by this deadline, even with a huge node
		// budget or a very small configured slice. The executor revalidates every step.
		done |= ++searchTicks >= NavigationWatchdog.PLAN_TICKS;
		sliceRemaining = Math.max(0, sliceRemaining - (System.nanoTime() - started));
		if (!done) {
			status = "working out a route (%d nodes)".formatted(search.expandedNodes());
			return;
		}

		PathFinder.Path path = search.result();
		lastCost = path.cost();
		lastNodes = path.searched();
		boolean continuation = searchIsContinuation;
		search = null;
		searchFor = null;
		searchIsContinuation = false;

		// Standing on the goal already. One step, complete, and nothing to walk — which is
		// arrival, and the tick after this will say so.
		if (path.atGoal()) {
			attempts = 0;
			return;
		}
		List<PathMove> moves = PathMove.of(path);
		if (moves.isEmpty()) {
			// Nothing was found and nothing was even approached. Retrying immediately gets the
			// same answer from the same place, so rest, and count it against the goal.
			attempts++;
			rest();
			status = "no way through";
			return;
		}
		PathRunner built = new PathRunner(moves, path.complete(), goalPos, mayBreak);
		planAheadTicks = Rng.ticks(cfg.pathRefreshSec, cfg.pathRefreshMaxSec);
		if (continuation && runner != null) {
			next = built;
		} else {
			runner = built;
			next = null;
			runnerFor = goalPos;
		}
	}

	/**
	 * Plan the next segment while the current one is still being walked.
	 *
	 * <p>A route that stops short is normal — the node budget runs out long before a base
	 * does — and the honest way to handle it is to start looking for the continuation before
	 * arriving at the end, so there is something to walk onto rather than a pause to think.
	 */
	private void planAhead(PathMove.Ctx ctx, BlockPos goalPos, PathFinder.Goal goal,
	                       Set<Block> mayBreak) {
		if (search != null || next != null || runner.complete || rest > 0) return;
		if (runner.ticksLeft() > planAheadTicks) return;
		begin(ctx, runner.destination(), goalPos, goal, mayBreak, true);
	}

	private void begin(PathMove.Ctx ctx, BlockPos from, BlockPos goalPos, PathFinder.Goal goal,
	                   Set<Block> mayBreak, boolean continuation) {
		int availablePlacements = Math.max(0,
				Bot.buildingBlockCount(ctx.player(), cfg) - Math.max(0, cfg.bridgeKeepBlocks));
		PathFinder.Rules rules = PathFinder.Rules.of(cfg, allowEdits, allowEdits ? availablePlacements : 0);
		// The player, so a swing is priced with the tools actually on the bar; and the
		// selection, so the route digs through the blocks that were asked for and leaves the
		// wall alone. Both of those are what stops a route being a tunnel through the nearest
		// thing between here and there.
		PathFinder.World world = new PathFinder.Level(ctx.level(), cfg, ctx.player(), mayBreak);
		// Feet in the air is the normal case, not an edge case: plans get made walking off a
		// step and coming down from a jump. Rooted there the search expands nothing, hands back
		// an empty route, and a block we are standing next to gets written off.
		int fromY = PathFinder.groundY(world, from.getX(), from.getY(), from.getZ(), rules.maxFall());
		search = PathFinder.begin(world, from.getX(), fromY, from.getZ(),
				goalPos.getX(), goalPos.getY(), goalPos.getZ(), goal, rules).avoiding(failedEdges.keySet());
		searchFor = goalPos;
		searchIsContinuation = continuation;
		searchTicks = 0;
	}

	// ----------------------------------------------------------- self-check

	/**
	 * Self-check on the timing and the bookkeeping — planning needs a world, neither of these
	 * does: {@code ./gradlew selfCheck -Pcheck=com.damia.movrand.Pathing}
	 */
	public static void main(String[] args) {
		Config cfg = new Config();

		// Every delay here is a delay. A zero rest is the same question asked of the same world
		// from the same place twenty times a second, and a zero plan-ahead window is a route
		// that is only ever continued after the pause you can see.
		cfg.pathRestMinSec = 0;
		cfg.pathRestMaxSec = 0;
		cfg.pathRefreshSec = 0;
		cfg.pathRefreshMaxSec = 0;
		cfg.pathSliceMs = 0;
		cfg.clampAll();
		assert cfg.pathRestMinSec > 0 : "the rest after a failed plan was allowed to be nothing";
		assert cfg.pathRestMaxSec >= cfg.pathRestMinSec : "the rest range is inverted";
		assert cfg.pathRefreshSec > 0 : "the plan-ahead window was allowed to be nothing";
		assert cfg.pathRefreshMaxSec >= cfg.pathRefreshSec : "the plan-ahead range is inverted";
		assert cfg.pathSliceMs > 0 : "a search given no time at all never finishes";
		assert cfg.pathSliceMs <= 50 : "a slice longer than a tick drops the frame it protects";

		Pathing nav = new Pathing(cfg);
		// An effectively endless search with tiny slices must hand back usable partial
		// progress by the wall-clock tick deadline, rather than exhaust its entire node cap.
		BlockPos distant = new BlockPos(100_000, 1, 0);
		PathFinder.Goal unreachable = (x, y, z) -> false;
		nav.search = PathFinder.begin(new PathFinder.Sketch(1, new String[]{"."}), 0, 1, 0,
				distant.getX(), distant.getY(), distant.getZ(), unreachable,
				new PathFinder.Rules(false, false, true, 3, 1, 4, 6, 200_000));
		for (int i = 0; i < NavigationWatchdog.PLAN_TICKS; i++) {
			nav.sliceRemaining = 1;
			nav.advanceSearch(null, distant, unreachable, null);
		}
		assert nav.search == null && nav.runner != null : "planning deadline did not release a partial route";
		assert !nav.runner.complete && nav.lastNodes < 200_000 : "deadline waited for the node cap";
		nav.reset();
		PathFinder.Edge edge = new PathFinder.Edge(BlockPos.ZERO.asLong(), new BlockPos(1, 0, 0).asLong());
		nav.rememberFailure(edge);
		assert nav.failedEdges.containsKey(edge) && nav.failedEdges.get(edge) > nav.tick;
		long expiry = nav.failedEdges.get(edge);
		nav.tick = expiry;
		nav.failedEdges.entrySet().removeIf(entry -> entry.getValue() <= nav.tick);
		assert nav.failedEdges.isEmpty() : "a temporary route failure became permanent";
		nav.rememberFailure(edge);
		nav.reset();
		assert nav.failedEdges.isEmpty() && nav.activeGoal == null : "new destination inherited old failures";
		for (int i = 0; i < 200; i++) {
			nav.rest();
			assert nav.rest > 0 : "a rest came out as no wait at all";
		}

		// Inverted ranges are a hand-edited json, not a crash, and the draw still has to be
		// inside the range it was given.
		cfg.pathRestMinSec = 4;
		cfg.pathRestMaxSec = 1;
		cfg.clampAll();
		assert cfg.pathRestMaxSec >= cfg.pathRestMinSec : "an inverted rest range survived the clamps";

		// Attempts are bounded and at least one: zero would call every destination impossible
		// without ever having looked for it.
		cfg.pathAttempts = 0;
		cfg.clampAll();
		assert cfg.pathAttempts >= 1 : "a goal was given no attempts at all";
		assert nav.attemptLimit() >= 1;

		// Both the reset and the invalidate have to actually let go of the route. A plan kept
		// past the thing that made it wrong is a bot walking confidently into a wall that
		// arrived while it was thinking about something else.
		assert !nav.walking() && nav.step() == 0 && nav.length() == 0
				: "a fresh router thinks it is walking something";
		assert nav.currentKind() == PathFinder.Kind.START : "a fresh router is mid-move";
		nav.invalidate("the wall moved");
		assert !nav.walking() && nav.status.equals("the wall moved")
				: "invalidate kept the route or lost the reason";
		nav.placed = 7;
		nav.reset();
		assert !nav.walking() && nav.status.isEmpty() : "reset left a route behind";

		// Four outcomes, and the caller has to have something to do with each of them. Three of
		// them keep the keys moving or end the job; the fourth used to be the bug - "arrived"
		// read as "no route", so the bot replanned the same one-step answer forever.
		for (Nav outcome : Nav.values()) {
			assert switch (outcome) {
				case PLANNING, WALKING -> true;      // a tick with something in it
				case ARRIVED, NO_ROUTE -> true;      // a tick that ends the journey
			} : "a navigation outcome with nothing decided for it: " + outcome;
		}
		assert Nav.ARRIVED != Nav.NO_ROUTE : "arriving and failing must never be the same answer";

		System.out.println("Pathing self-check passed");
	}

	private static int feetX(PathMove.Ctx ctx) {
		return ctx.player().blockPosition().getX();
	}

	private static int feetY(PathMove.Ctx ctx) {
		return ctx.player().blockPosition().getY();
	}

	private static int feetZ(PathMove.Ctx ctx) {
		return ctx.player().blockPosition().getZ();
	}
}
