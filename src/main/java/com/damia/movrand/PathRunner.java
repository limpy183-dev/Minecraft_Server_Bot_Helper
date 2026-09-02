package com.damia.movrand;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;

import java.util.List;

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

	/** How many moves ahead the camera cruises, so it is not chasing the block under its feet. */
	private static final int LOOKAHEAD = 5;
	/** Further than this from every square of the route and it is not the route being walked. */
	private static final double OFF_ROUTE = 4.0;
	/** How many moves ahead to revalidate, so a dead end is seen before it is walked into. */
	private static final int VERIFY_AHEAD = 3;

	private final List<PathMove> moves;
	/** Whether the route actually reaches what it was planned for, or just gets closer. */
	public final boolean complete;
	public final BlockPos goal;

	private int index;
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
		this.moves = moves;
		this.complete = complete;
		this.goal = goal;
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

		if (!resync(ctx.player())) {
			reason = "off the route";
			return Result.FAILED;
		}

		PathMove move = moves.get(index);
		// This move and the next couple. Checking ahead is what stops the bot walking three
		// blocks up a corridor to find the doorway it was routed through has been filled in.
		for (int i = index; i < Math.min(moves.size(), index + VERIFY_AHEAD); i++) {
			if (!moves.get(i).stillPossible(ctx)) {
				reason = i == index ? "the way is not what it was" : "the way ahead has changed";
				return Result.FAILED;
			}
		}
		if (move.timedOut(ctx)) {
			reason = move.detail.isEmpty() ? "stuck on a step" : move.detail + " is taking too long";
			return Result.FAILED;
		}

		PathMove.Status status = move.update(ctx, steer);
		if (move.placedOne) placed++;

		if (status == PathMove.Status.FAILED) {
			reason = move.detail;
			return Result.FAILED;
		}
		// Do not waste the tick standing between two moves: run the next one now, with the same
		// steer, exactly as if it had been the current one all along. Looped rather than done
		// once, because after a skip forward several moves can already be behind us — and every
		// one of those that is not run is a tick handed back with no keys in it.
		for (int guard = 0; status == PathMove.Status.SUCCESS && guard < 8; guard++) {
			index++;
			if (index >= moves.size()) return Result.DONE;
			PathMove ahead = moves.get(index);
			status = ahead.update(ctx, steer);
			if (ahead.placedOne) placed++;
			if (status == PathMove.Status.FAILED) {
				reason = ahead.detail;
				return Result.FAILED;
			}
		}

		cruise(ctx.player(), steer);
		return Result.RUNNING;
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
	private void cruise(LocalPlayer player, Bot.Steer steer) {
		if (steer.hasLook) return;                 // the move wants to look somewhere specific
		PathMove current = moves.get(index);
		BlockPos aim = current.dest;
		if (plain(current)) {
			for (int i = index + 1; i < Math.min(moves.size(), index + LOOKAHEAD); i++) {
				PathMove m = moves.get(i);
				if (!plain(m) || Math.abs(m.dest.getY() - current.dest.getY()) > 1) break;
				aim = m.dest;
			}
		}
		double heading = PathMove.headingTo(player, aim);
		steer.lookAt(heading, pitchAlong(player, aim.getY()));
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
	private boolean resync(LocalPlayer player) {
		BlockPos feet = player.blockPosition();
		if (moves.get(index).validPositions().contains(feet)) return true;

		for (int i = index + 1; i < Math.min(moves.size(), index + LOOKAHEAD + 2); i++) {
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
		return best <= OFF_ROUTE * OFF_ROUTE;
	}

	// --------------------------------------------------------------- tallies

	/**
	 * Notice a block the route was breaking turn to air.
	 *
	 * <p>The only honest way to count one. A swing is not a broken block, a held button is not
	 * a broken block, and the client is never told that a break succeeded — the block simply
	 * stops being there.
	 */
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
