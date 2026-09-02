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

	/** How long a search may run per tick. A tick is fifty; this is not felt. */
	private static final long SLICE_NANOS = 2_000_000L;
	/** Ticks of route left before the next segment starts being planned behind it. */
	private static final int PLAN_AHEAD_TICKS = 40;
	/** Ticks to wait after a plan comes to nothing, so a bad goal cannot burn a core. */
	private static final int REST_TICKS = 8;
	/** Plans in a row that produce nothing walkable before the goal is called impossible. */
	private static final int MAX_ATTEMPTS = 3;

	private final Config cfg;

	private PathFinder.Search search;
	/** What the in-flight search is for, so a changed mind cancels it rather than walking it. */
	private BlockPos searchFor;
	/** Whether that search continues the current route rather than replacing it. */
	private boolean searchIsContinuation;

	private PathRunner runner;
	private PathRunner next;
	private BlockPos runnerFor;

	private int rest;
	private int attempts;

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
	}

	public void reset() {
		search = null;
		searchFor = null;
		searchIsContinuation = false;
		runner = null;
		next = null;
		runnerFor = null;
		rest = 0;
		attempts = 0;
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
		status = why;
		runner = null;
		next = null;
		search = null;
		searchFor = null;
	}

	public boolean walking() {
		return runner != null;
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
			if (attempts >= MAX_ATTEMPTS) {
				status = "no way there";
				return Nav.NO_ROUTE;
			}
			begin(ctx, ctx.player().blockPosition(), goalPos, goal, mayBreak, false);
		}
		status = search != null ? "working out a route" : status;
		return attempts >= MAX_ATTEMPTS ? Nav.NO_ROUTE : Nav.PLANNING;
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
				attempts = 0;
				status = runner.describe();
				planAhead(ctx, goalPos, goal, mayBreak);
				return Nav.WALKING;
			}
			case DONE -> {
				// The end of the route. If it reached what it was for, that is arrival; if it
				// only got closer, this is a segment and the next one starts from here.
				boolean got = runner.complete;
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
				// A route that got some of the way before failing has still moved us, so the
				// next attempt starts from somewhere new and is not the same attempt again.
				boolean moved = runner.step() > 0;
				runner = null;
				runnerFor = null;
				next = null;
				if (moved) attempts = 0;
				else attempts++;
				rest = REST_TICKS;
				return null;
			}
		}
		return null;
	}

	// ---------------------------------------------------------------- search

	private void advanceSearch(PathMove.Ctx ctx, BlockPos goalPos, PathFinder.Goal goal,
	                           Set<Block> mayBreak) {
		if (search == null) return;
		if (!search.advance(SLICE_NANOS)) {
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
			rest = REST_TICKS;
			status = "no way through";
			return;
		}
		PathRunner built = new PathRunner(moves, path.complete(), goalPos);
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
		if (runner.ticksLeft() > PLAN_AHEAD_TICKS) return;
		begin(ctx, runner.destination(), goalPos, goal, mayBreak, true);
	}

	private void begin(PathMove.Ctx ctx, BlockPos from, BlockPos goalPos, PathFinder.Goal goal,
	                   Set<Block> mayBreak, boolean continuation) {
		boolean canBridge = Bot.buildingSlot(ctx.player(), cfg) >= 0;
		PathFinder.Rules rules = PathFinder.Rules.of(cfg, true, canBridge);
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
				goalPos.getX(), goalPos.getY(), goalPos.getZ(), goal, rules);
		searchFor = goalPos;
		searchIsContinuation = continuation;
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
