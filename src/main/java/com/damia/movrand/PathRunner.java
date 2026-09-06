package com.damia.movrand;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;

import java.util.List;
import java.util.Set;

/**
 * Walks a route that has already been found.
 *
 * <p>Everything here is bookkeeping around one line — ask the current {@link PathMove} to
 * take its own tick — and the bookkeeping is the part that used to be missing. Three
 * questions get asked before that line runs, every tick, and each of them exists because not
 * asking it is a specific way a bot stands still forever:
 *
 * <ul>
 *   <li><b>Where am I actually?</b> Found rather than counted. A counter is fine until the
 *   first mob in a doorway or corner taken wide, and then the step it is waiting for is never
 *   reached, the index never moves, and the bot walks back to a point it passed a minute ago.
 *   <li><b>Is this still possible?</b> The route was planned in a world this bot takes blocks
 *   out of for a living. A plan that has stopped being true is dropped where it went wrong,
 *   not walked into.
 *   <li><b>Is this taking longer than it physically can?</b> The only honest way to tell a
 *   slow block from an impossible one, because a client is never told which it is.
 * </ul>
 *
 * <p>And when the answer to any of them is bad, this returns {@link Result#FAILED} with a
 * reason. It never returns "nothing to say": a runner that quietly hands back an empty tick
 * is a bot standing perfectly still while something upstream waits for news that never comes.
 */
public final class PathRunner {

	public enum Result {
		/** Still walking it. */
		RUNNING,
		/** The last move finished. */
		DONE,
		/** The route is wrong, gone, or impossible. See {@link #reason}. */
		FAILED
	}

	/**
	 * How many moves may be finished inside one tick.
	 *
	 * <p>A cap rather than a preference: after a skip forward several moves can already be
	 * behind us, and running them costs nothing — but a move that reports success without the
	 * player having gone anywhere would otherwise walk the whole route in one tick.
	 */
	private static final int CATCH_UP = 8;

	private final List<PathMove> moves;
	/** Whether the route actually reaches what it was planned for, or just gets closer. */
	public final boolean complete;
	public final BlockPos goal;
	private final Set<Block> mayBreak;

	private int index;
	private int progressIndex = -1;
	private double bestStepDistance = Double.POSITIVE_INFINITY;
	private int stalledTicks;
	private PathFinder.Edge failedEdge;
	public PathFinder.Edge failedEdge() { return failedEdge; }
	public BlockPos breakingBlock() {
		return index < moves.size() ? moves.get(index).breakingBlock() : null;
	}
	public PathFinder.Edge currentEdge() {
		if (index >= moves.size()) return null;
		PathMove move = moves.get(index);
		return new PathFinder.Edge(move.src.asLong(), move.dest.asLong());
	}
	public String reason = "";
	public int placed;

	/** The block being swung at last tick, so its disappearance can be counted exactly once. */
	private BlockPos wasBreaking;
	private String wasBreakingName = "";
	public int mined;
	/** The last block that finished breaking, and its name while it still had one. */
	public BlockPos justMined;
	public String justMinedName = "";

	public PathRunner(List<PathMove> moves, boolean complete, BlockPos goal) {
		this(moves, complete, goal, null);
	}

	public PathRunner(List<PathMove> moves, boolean complete, BlockPos goal, Set<Block> mayBreak) {
		this.moves = moves;
		this.complete = complete;
		this.goal = goal;
		this.mayBreak = mayBreak;
		for (int i = 0; i + 1 < moves.size(); i++) {
			if (plain(moves.get(i)) && !sameDirection(moves.get(i), moves.get(i + 1))) {
				moves.get(i).arrivalRadius = 0.2;
			}
		}
	}

	public boolean isEmpty() {
		return moves.isEmpty();
	}

	public int step() {
		return index;
	}

	public int length() {
		return moves.size();
	}

	/** Where the route ends, which is not the goal when the search ran out of room. */
	public BlockPos destination() {
		return moves.isEmpty() ? goal : moves.getLast().dest;
	}

	/** Roughly how many ticks of route are left, for deciding when to plan the next one. */
	public double ticksLeft() {
		double cost = 0;
		for (int i = index; i < moves.size(); i++) cost += moves.get(i).price;
		return cost * PathFinder.TICKS_PER_BLOCK;
	}

	/** What the move being taken right now is, for the status line. */
	public PathFinder.Kind currentKind() {
		return moves.isEmpty() ? PathFinder.Kind.START
				: moves.get(Math.min(index, moves.size() - 1)).kind;
	}

	public String describe() {
		if (moves.isEmpty()) return "nowhere to go";
		PathMove m = moves.get(Math.min(index, moves.size() - 1));
		return "%s (%d of %d)".formatted(m.detail.isEmpty() ? "walking" : m.detail, index + 1, moves.size());
	}

	// ------------------------------------------------------------------ tick

	public Result tick(PathMove.Ctx ctx, Bot.Steer steer) {
		if (moves.isEmpty()) {
			reason = "nowhere to go";
			return Result.FAILED;
		}
		if (index >= moves.size()) return Result.DONE;

		countMined(ctx);

		if (!resync(ctx)) {
			reason = "off the route";
			return Result.FAILED;
		}

		PathMove move = moves.get(index);
		// This move and the next couple. Checking ahead is what stops the bot walking three
		// blocks up a corridor to find the doorway it was routed through has been filled in.
		int ahead = Math.max(1, ctx.cfg().pathVerifyAhead);
		for (int i = index; i < Math.min(moves.size(), index + ahead); i++) {
			if (!moves.get(i).stillPossible(ctx, mayBreak)) {
				reason = i == index ? "the way is not what it was" : "the way ahead has changed";
				return Result.FAILED;
			}
		}
		if (move.timedOut(ctx)) {
			failedEdge = currentEdge();
			reason = move.detail.isEmpty() ? "stuck on a step" : move.detail + " is taking too long";
			return Result.FAILED;
		}

		PathMove.Status status = move.update(ctx, steer);
		if (move.placedOne) placed++;

		if (status == PathMove.Status.FAILED) {
			failedEdge = currentEdge();
			reason = move.detail;
			return Result.FAILED;
		}
		// Do not waste the tick standing between two moves: run the next one now, with the same
		// steer, exactly as if it had been the current one all along. Looped rather than done
		// once, because after a skip forward several moves can already be behind us — and every
		// one of those that is not run is a tick handed back with no keys in it.
		for (int guard = 0; status == PathMove.Status.SUCCESS && guard < CATCH_UP; guard++) {
			index++;
			if (index >= moves.size()) return Result.DONE;
			PathMove next = moves.get(index);
			if (!next.stillPossible(ctx, mayBreak) || next.timedOut(ctx)) {
				reason = "the next step is no longer usable";
				failedEdge = currentEdge();
				steer.clear();
				return Result.FAILED;
			}
			status = next.update(ctx, steer);
			if (next.placedOne) placed++;
			if (status == PathMove.Status.FAILED) {
				failedEdge = currentEdge();
				reason = next.detail;
				return Result.FAILED;
			}
		}

		if (walkingStalled(ctx, steer)) {
			reason = "no progress towards the next step";
			failedEdge = currentEdge();
			steer.clear();
			return Result.FAILED;
		}
		cruise(ctx, steer);
		return Result.RUNNING;
	}

	private boolean walkingStalled(PathMove.Ctx ctx, Bot.Steer steer) {
		PathMove move = moves.get(index);
		double dx = move.dest.getX() + 0.5 - ctx.player().getX();
		double dy = move.dest.getY() - ctx.player().getY();
		double dz = move.dest.getZ() + 0.5 - ctx.player().getZ();
		return trackProgress(index, Math.sqrt(dx * dx + dy * dy + dz * dz),
				steer.hasMove && !steer.precise && ctx.player().onGround(), ctx.cfg().pathStallSec);
	}

	boolean trackProgress(int step, double distance, boolean walking, double seconds) {
		if (!walking || step != progressIndex || distance < bestStepDistance - 0.04) {
			progressIndex = step;
			bestStepDistance = distance;
			stalledTicks = 0;
			return false;
		}
		return ++stalledTicks > seconds * 20;
	}

	// --------------------------------------------------------------- looking

	/**
	 * Where to point when the move itself has no opinion.
	 *
	 * <p>Several moves down the line rather than at the next square. The next square is under
	 * a block away by construction, and the heading to something that close swings hard as
	 * you close on it — so a camera that is filtered on purpose, and takes the better part of
	 * half a second to come round, spends the whole route chasing a target that never settles
	 * and the movement keys strafe after it.
	 *
	 * <p>The old version had to prove the line was walkable before looking down it, sampling
	 * both shoulders a third of a block at a time. That is gone, and it is gone because the
	 * route is now the obstacle avoidance: a run of plain steps is a run of squares the search
	 * has already said a player fits through.
	 */
	private void cruise(PathMove.Ctx ctx, Bot.Steer steer) {
		if (steer.hasLook) return;                 // the move wants to look somewhere specific
		LocalPlayer player = ctx.player();
		PathMove current = moves.get(index);
		BlockPos aim = current.dest;
		if (plain(current)) {
			int far = Math.max(1, ctx.cfg().pathLookaheadMoves);
			for (int i = index + 1; i < Math.min(moves.size(), index + far); i++) {
				PathMove m = moves.get(i);
				if (!plain(m) || Math.abs(m.dest.getY() - current.dest.getY()) > 1) break;
				// Looking around a right-angle corner before reaching it makes the keys alternate
				// between strafe and forward against the doorframe. Look along this straight leg.
				if (!sameDirection(current, m)) break;
				aim = m.dest;
			}
		}
		double heading = PathMove.headingTo(player, aim);
		steer.lookAt(heading, pitchAlong(player, aim.getY()));
	}

	static boolean sameDirection(PathMove a, PathMove b) {
		return a.dest.getX() - a.src.getX() == b.dest.getX() - b.src.getX()
				&& a.dest.getZ() - a.src.getZ() == b.dest.getZ() - b.src.getZ();
	}

	private static boolean plain(PathMove m) {
		return m.kind == PathFinder.Kind.WALK || m.kind == PathFinder.Kind.DIAGONAL;
	}

	/** Look slightly along the route rather than at your own feet, as a person does. */
	private static double pitchAlong(LocalPlayer player, double y) {
		double dy = y - player.getEyeY();
		return Math.max(-40, Math.min(40, Math.toDegrees(-Math.atan2(dy, 3))));
	}

	// -------------------------------------------------------------- position

	/**
	 * Find our place on the route, forwards then backwards.
	 *
	 * <p>Forwards first because a sprint carries you past a square, and backwards because a
	 * knockback or a fall puts you behind one. Only when we are on none of them does distance
	 * get asked, and far enough from every square means this is not the route being walked.
	 *
	 * @return false when we have genuinely left the route
	 */
	private boolean resync(PathMove.Ctx ctx) {
		LocalPlayer player = ctx.player();
		BlockPos feet = player.blockPosition();
		if (moves.get(index).validPositions().contains(feet)) return true;

		int far = Math.max(1, ctx.cfg().pathLookaheadMoves) + 2;
		for (int i = index + 1; i < Math.min(moves.size(), index + far); i++) {
			if (moves.get(i).validPositions().contains(feet)) {
				index = i;
				return true;
			}
		}
		for (int i = index - 1; i >= 0; i--) {
			if (moves.get(i).validPositions().contains(feet)) {
				index = i;
				return true;
			}
		}

		// Standing on none of them: mid-jump, mid-fall, or genuinely gone. Distance is the
		// only thing left to ask.
		double best = Double.MAX_VALUE;
		for (int i = Math.max(0, index - 1); i < moves.size(); i++) {
			BlockPos d = moves.get(i).dest;
			double dx = d.getX() + 0.5 - player.getX();
			double dy = d.getY() - player.getY();
			double dz = d.getZ() + 0.5 - player.getZ();
			best = Math.min(best, dx * dx + dy * dy + dz * dz);
		}
		double limit = Math.max(1, ctx.cfg().pathOffRouteBlocks);
		return best <= limit * limit;
	}

	// --------------------------------------------------------------- tallies

	/**
	 * Notice a block the route was breaking turn to air.
	 *
	 * <p>The only honest way to count one. A swing is not a broken block, a held button is not
	 * a broken block, and the client is never told that a break succeeded — the block simply
	 * stops being there.
	 */
	/**
	 * Self-check on the route bookkeeping — walking one needs a world, knowing where it goes
	 * and how much of it is left does not:
	 * {@code ./gradlew selfCheck -Pcheck=com.damia.movrand.PathRunner}
	 */
	public static void main(String[] args) {
		BlockPos goal = new BlockPos(0, 64, 8);
		PathFinder.Path path = new PathFinder.Path(List.of(
				new PathFinder.Step(0, 64, 0, PathFinder.Kind.START, 0),
				new PathFinder.Step(0, 64, 1, PathFinder.Kind.WALK, 1),
				new PathFinder.Step(0, 64, 2, PathFinder.Kind.MINE, 5),
				new PathFinder.Step(0, 65, 3, PathFinder.Kind.ASCEND, 1.6)), false, 40, 7.6);
		PathRunner runner = new PathRunner(PathMove.of(path), false, goal);

		for (int i = 0; i < 30; i++) {
			boolean stalled = runner.trackProgress(0, 1 + (i % 2) * 0.005, true, 1.25);
			assert stalled == (i > 25) : "stationary jitter defeated the walking watchdog";
		}
		for (int i = 0; i < 50; i++) {
			assert !runner.trackProgress(1, 5 - i * 0.05, true, 1.25) : "slow walking was treated as stuck";
		}
		for (int i = 0; i < 1000; i++) {
			assert !runner.trackProgress(2, 1, false, 1.25) : "mining was given a walking timeout";
		}
		PathMove east = new PathMove(PathFinder.Kind.WALK, BlockPos.ZERO, new BlockPos(1, 0, 0), 1);
		PathMove south = new PathMove(PathFinder.Kind.WALK, east.dest, new BlockPos(1, 0, 1), 1);
		new PathRunner(List.of(east, south), true, south.dest);
		assert !sameDirection(east, south) && east.arrivalRadius <= 0.2 : "corner was cut before clearing the wall";

		assert !runner.isEmpty() && runner.length() == 3 : "three steps became " + runner.length();
		assert runner.step() == 0 : "a route starts at its first move";
		assert runner.currentKind() == PathFinder.Kind.WALK : "the first move is a walk";

		// Where a route ends is not what it was planned for. A partial route stops short by
		// design - the budget runs out long before a base does - and reading the goal as the
		// destination is how the next leg gets planned from a place we never reached.
		assert runner.destination().equals(new BlockPos(0, 65, 3)) : "the route ended somewhere else";
		assert !runner.destination().equals(goal) : "a partial route claimed to reach the goal";
		assert !runner.complete : "a partial route reported itself complete";

		// What is left is priced from the moves rather than counted, so a route whose remaining
		// moves are four broken blocks is not "three steps from the end".
		double all = runner.ticksLeft();
		assert all > 3 * PathFinder.TICKS_PER_BLOCK
				: "a route with a mine step in it costs no more than walking: " + all;
		assert Math.abs(all - 7.6 * PathFinder.TICKS_PER_BLOCK) < 1e-6
				: "what is left did not add up to what the search paid: " + all;

		// An empty route is a failure with a reason, never a quiet tick: something upstream is
		// waiting for news, and a runner that hands back nothing is a bot standing perfectly
		// still while it waits for it.
		PathRunner nothing = new PathRunner(List.of(), true, goal);
		assert nothing.isEmpty() && nothing.length() == 0 : "an empty route was not empty";
		assert nothing.destination().equals(goal) : "an empty route goes to the goal, having not moved";
		assert nothing.currentKind() == PathFinder.Kind.START : "an empty route is mid-move";
		assert !nothing.describe().isEmpty() : "an empty route described itself as nothing";
		assert nothing.ticksLeft() == 0 : "an empty route has time left on it";

		// and a route always has something to say about where it has got to
		assert runner.describe().contains("1 of 3") : "the route lost its place: " + runner.describe();

		System.out.println("PathRunner self-check passed");
	}

	private void countMined(PathMove.Ctx ctx) {
		justMined = null;
		BlockPos now = index < moves.size() ? moves.get(index).breakingBlock() : null;
		if (wasBreaking != null && !wasBreaking.equals(now)
				&& ctx.level().getBlockState(wasBreaking).isAir()) {
			mined++;
			justMined = wasBreaking;
			justMinedName = wasBreakingName;
		}
		if (now != null && !now.equals(wasBreaking)) {
			// read the name while the block still has one, not after it is air
			wasBreakingName = ctx.level().getBlockState(now).getBlock().getName().getString();
		}
		wasBreaking = now;
	}
}
