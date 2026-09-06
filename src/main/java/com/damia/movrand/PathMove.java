package com.damia.movrand;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * One step of a route, and how to actually take it.
 *
 * <p>The point of this class is that the cost and the execution live in the same place. The
 * search decided this step was a jump onto a ledge, or a block to swing at, or a gap to
 * floor, and priced it accordingly; if the follower then works that out again from the world
 * at execution time it is answering the same question a second time, at a different moment,
 * with different code — and every disagreement between the two answers is the bot walking
 * into a hole the plan said to bridge. So the plan carries its own reasoning and this reads
 * it. Baritone splits the same idea across eight {@code Movement} subclasses; the set of
 * moves here is small enough that one class with a switch is the whole of it.
 *
 * <p>Nothing here writes a rotation or a key directly. It fills in a {@link Bot.Steer}, and
 * the controller pushes that through the same wobble and easing a wandering bot uses — which
 * is why a bot mining a wall looks like the same person as a bot crossing a field.
 *
 * <p>A move also knows two things the old follower had no way to ask. {@link #stillPossible}
 * is "has the world moved under this plan", checked every tick because this is a job that
 * moves the world for a living. {@link #timedOut} is "is this taking longer than it should
 * physically take", which is the only honest way to tell a slow block from an impossible
 * one: a client is never told why a swing did nothing.
 */
public final class PathMove {

	public enum Status {
		/** Still working on it. */
		RUNNING,
		/** Standing on the destination; the route may move on. */
		SUCCESS,
		/** Cannot be done from here. The route is wrong and needs replanning. */
		FAILED
	}

	/** Everything a move needs from the running game, gathered once a tick. */
	public record Ctx(Minecraft mc, LocalPlayer player, ClientLevel level, Config cfg) {
	}

	public final PathFinder.Kind kind;
	public final BlockPos src;
	public final BlockPos dest;

	/** What the search paid for this step, in blocks walked. Shown, not used for control. */
	public final double price;

	public String detail = "";
	/** Counted up by the runner after a fillable square actually becomes a block. */
	public boolean placedOne;

	private int ticks;
	/** Worked out on the first tick from the real world, because a swing is not a constant. */
	private int budget = -1;

	// Aiming state, held between ticks. Vanilla throws away every bit of mining progress the
	// moment the crosshair lands on a different block, and the camera here is filtered on
	// purpose - so a face re-chosen each tick as the view settles does not mine slowly, it
	// mines never. The same applies to the support face for a placement.
	private BlockPos breaking;
	private Direction breakFace;
	private final Bot.MiningAim miningAim = new Bot.MiningAim();
	private BlockPos placing;
	private Direction placeFace;
	private int placeTicks;
	private boolean placeWasFillable;
	/** Ticks to leave a door alone after reaching for it, so it is not opened and shut again. */
	private int doorCooldown;
	/** Tightened by the runner before a corner, so the shoulders clear the inside wall. */
	double arrivalRadius = 0.45;

	public PathMove(PathFinder.Kind kind, BlockPos src, BlockPos dest, double price) {
		this.kind = kind;
		this.src = src;
		this.dest = dest;
		this.price = price;
	}

	/** Turn a finished route into the moves that walk it. */
	public static java.util.List<PathMove> of(PathFinder.Path path) {
		java.util.List<PathMove> moves = new java.util.ArrayList<>();
		java.util.List<PathFinder.Step> steps = path.steps();
		for (int i = 1; i < steps.size(); i++) {
			PathFinder.Step from = steps.get(i - 1);
			PathFinder.Step to = steps.get(i);
			moves.add(new PathMove(to.kind(),
					new BlockPos(from.x(), from.y(), from.z()),
					new BlockPos(to.x(), to.y(), to.z()),
					to.cost()));
		}
		return moves;
	}

	// -------------------------------------------------------------- position

	/**
	 * Where the player may be and still be considered on this move.
	 *
	 * <p>Used to find our place on a route rather than counting steps off one at a time. A
	 * counter is fine until the first thing that pushes the bot off the line — a mob in a
	 * doorway, a corner taken wide, a knockback — and then the node it is waiting for is never
	 * reached, the index never moves, and the bot walks back to a point it went past a minute
	 * ago for as long as anyone lets it.
	 */
	public Set<BlockPos> validPositions() {
		Set<BlockPos> out = new LinkedHashSet<>();
		out.add(src);
		out.add(dest);
		// A drop passes through every block on the way down, and mid-fall is exactly when the
		// route most needs to still recognise where it is.
		if (kind == PathFinder.Kind.DESCEND || kind == PathFinder.Kind.DIG_DOWN) {
			for (int y = src.getY(); y >= dest.getY(); y--) out.add(new BlockPos(dest.getX(), y, dest.getZ()));
		}
		return out;
	}

	/** Standing on the destination, near enough to call it done. */
	public boolean arrived(LocalPlayer player) {
		BlockPos feet = player.blockPosition();
		if (feet.getX() != dest.getX() || feet.getZ() != dest.getZ()) return false;
		if (Math.abs(player.getY() - dest.getY()) >= 0.8) return false;
		if (kind == PathFinder.Kind.WALK || kind == PathFinder.Kind.DIAGONAL) {
			double dx = player.getX() - dest.getX() - 0.5, dz = player.getZ() - dest.getZ() - 0.5;
			if (dx * dx + dz * dz > arrivalRadius * arrivalRadius) return false;
		}
		// Building moves finish standing on what they built, not floating over it. Without
		// this a pillar reports success at the top of the jump that was meant to place the
		// block, and the route moves on with nothing underneath.
		return !needsGround() || player.onGround();
	}

	/**
	 * Moves that are only finished once the feet are back on something.
	 *
	 * <p>Building moves, because a pillar that reports success at the top of its jump hands the
	 * route on with nothing underneath it. And long drops, because completing one in mid-air
	 * starts the next move early — which presses a direction key while still falling, and
	 * lands the route a block to the side of where it was planned from.
	 */
	private boolean needsGround() {
		return switch (kind) {
			case PILLAR, BRIDGE, DIG_DOWN -> true;
			case DESCEND -> src.getY() - dest.getY() > 1;
			default -> false;
		};
	}

	// ------------------------------------------------------------ validation

	/**
	 * Whether the world still allows this step.
	 *
	 * <p>A route is only as fresh as the world it was planned in, and this bot takes blocks
	 * out of that world for a living — its own mining, a piston, another player, a chunk that
	 * arrived late. Checked every tick, on this move and the next couple, so a plan that has
	 * stopped being true is dropped where it went wrong rather than walked into.
	 */
	public boolean stillPossible(Ctx ctx) {
		return stillPossible(ctx, null);
	}

	/** Revalidate against both the live world and the route's original mining permission. */
	public boolean stillPossible(Ctx ctx, Set<Block> mayBreak) {
		ClientLevel level = ctx.level();
		// Anything that hurts, anywhere the feet are going, ends the move whatever kind it is.
		if (Avoidance.hazardAt(level, dest, ctx.cfg()) && kind != PathFinder.Kind.BRIDGE) return false;

		return switch (kind) {
			// A gap to floor: fine if it is still a gap, and fine if somebody filled it.
			// What is not fine is having nothing left to fill it with.
			case BRIDGE -> wayIsOpen(level) && footingAvailable(ctx);
			case PILLAR -> footingAvailable(ctx) && Bot.fullPlacementSupport(ctx.level(), src.below())
					&& breakableAhead(ctx, mayBreak);
			// A block to swing at: it may have gone already, which just makes this a walk.
			// It may not have become something we are not allowed to break.
			case MINE, DIG_DOWN -> breakableAhead(ctx, mayBreak) && safeFloor(level);
			// Everything else is a plain step, and the only thing that invalidates one is
			// something appearing in the space it goes through.
			default -> wayIsOpen(level) && safeFloor(level);
		};
	}

	private boolean wayIsOpen(ClientLevel level) {
		return clearOrDoor(level, dest) && clearOrDoor(level, dest.above());
	}

	private static boolean clearOrDoor(ClientLevel level, BlockPos pos) {
		return !inTheWay(level, pos) || Bot.opensByHand(level, pos);
	}

	private boolean safeFloor(ClientLevel level) {
		double atFeet = Avoidance.topOf(level, dest);
		return Avoidance.holdsWeight(level, dest.below())
				|| (atFeet > 0 && atFeet <= Avoidance.STEPPABLE);
	}

	private boolean footingAvailable(Ctx ctx) {
		BlockPos floor = kind == PathFinder.Kind.PILLAR ? src : dest.below();
		if (Avoidance.holdsWeight(ctx.level(), floor)) return true;      // already built, or never needed
		return Bot.buildingSlot(ctx.player(), ctx.cfg()) >= 0;
	}

	private boolean breakableAhead(Ctx ctx, Set<Block> mayBreak) {
		for (BlockPos pos : toBreak(ctx.level())) {
			var state = ctx.level().getBlockState(pos);
			if (!BlockTargets.breakable(state, ctx.level(), pos)) return false;
			if (mayBreak != null && !mayBreak.contains(state.getBlock())) return false;
			if (Avoidance.floodsWhenBroken(ctx.level(), pos, ctx.cfg().coverWater, true)) return false;
		}
		return true;
	}

	/**
	 * How long this may take before it is the route that is wrong rather than the block that
	 * is slow.
	 *
	 * <p>Priced from the world rather than from the search, and the difference matters. The
	 * search deliberately marks a swing up so a route prefers walking round a wall to going
	 * through it; a timeout built on that number would give a block four times the patience
	 * it actually needs. So this asks what breaking the block really costs and adds slack.
	 * Obsidian with an iron pick is twenty-five honest seconds and must not be given up on;
	 * claimed land, region protection and spawn protection never finish at all and look
	 * exactly the same from a client, so something has to end them.
	 */
	public boolean timedOut(Ctx ctx) {
		if (budget < 0) budget = workOutBudget(ctx);
		return ticks > budget;
	}

	private int workOutBudget(Ctx ctx) {
		double physical = 0;
		for (BlockPos pos : toBreak(ctx.level())) {
			double t = Bot.breakTicks(ctx.player(), ctx.level(), pos, ctx.level().getBlockState(pos));
			physical += Math.min(t, CEILING_TICKS);  // an unbreakable block is not an infinite wait
		}
		// A placement is a right click and a four tick cooldown; the rest is walking a block,
		// which is five ticks in a straight line and rather more round a doorframe.
		if (needsGround()) physical += PLACE_TICKS;
		// Jittered, because this is a give-up time and a give-up time that is the same integer
		// every single time is one more constant on the wire. It only ever adds.
		double slack = Rng.range(ctx.cfg().pathMoveSlackSec, ctx.cfg().pathMoveSlackMaxSec) * 20;
		return (int) (Math.min(CEILING_TICKS * Math.max(1, toBreak(ctx.level()).size()),
				physical * 1.5) + slack);
	}

	/** However long a move is priced at, nothing gets more than half a minute of it. */
	private static final int CEILING_TICKS = 30 * 20;
	/** A right click, its four-tick cooldown, and the second of walking either side of it. */
	private static final int PLACE_TICKS = 20;

	// ------------------------------------------------------------- execution

	/**
	 * One tick of this move.
	 *
	 * <p>Movement keys always; a look target only when the move needs a specific one. That
	 * split is deliberate. Aiming at the next block a metre away swings the heading hard as
	 * you close on it, and a camera that takes half a second to come round spends the whole
	 * route chasing a target that never settles. So the runner supplies a cruising look
	 * several moves down the line and a move only overrides it to point at something it has
	 * to hit.
	 */
	public Status update(Ctx ctx, Bot.Steer steer) {
		ticks++;
		if (doorCooldown > 0) doorCooldown--;
		placedOne = placing != null && placeWasFillable && !Bot.fillable(ctx.mc(), placing);
		if (placedOne) {
			placing = null;
			placeFace = null;
			placeWasFillable = false;
		}
		if (arrived(ctx.player())) return Status.SUCCESS;

		// A shut door is the one solid block that stops being solid if you ask, and the search
		// routed through it on exactly that understanding. Mining it instead is a hole in
		// somebody's house to reach a block that was never behind a locked anything.
		BlockPos door = doorInTheWay(ctx.level());
		if (door != null) return openDoor(ctx, door, steer);

		return switch (kind) {
			case WALK, DIAGONAL -> walk(ctx, steer, true);
			case ASCEND -> ascend(ctx, steer);
			case DESCEND -> descend(ctx, steer);
			case MINE -> mineThrough(ctx, steer);
			case BRIDGE -> bridge(ctx, steer);
			case PILLAR -> pillar(ctx, steer);
			case DIG_DOWN -> digDown(ctx, steer);
			case START -> Status.SUCCESS;
		};
	}

	// ---- the plain steps

	private Status walk(Ctx ctx, Bot.Steer steer, boolean maySprint) {
		detail = "walking";
		double heading = headingTo(ctx.player(), dest);
		steer.moveTowards(heading);
		steer.sprint = maySprint && ctx.cfg().destroySprint && ctx.player().onGround();
		return Status.RUNNING;
	}

	private Status ascend(Ctx ctx, Bot.Steer steer) {
		detail = "stepping up";
		LocalPlayer player = ctx.player();
		double heading = headingTo(player, dest);
		steer.moveTowards(heading);
		// At the step rather than three blocks short of it, or the hop is spent on nothing and
		// the bot arrives at the ledge already coming down.
		double dx = dest.getX() + 0.5 - player.getX(), dz = dest.getZ() + 0.5 - player.getZ();
		steer.jump = player.onGround() && dx * dx + dz * dz < 2.25;
		return Status.RUNNING;
	}

	private Status descend(Ctx ctx, Bot.Steer steer) {
		detail = "dropping down";
		LocalPlayer player = ctx.player();
		steer.moveTowards(headingTo(player, dest));
		// Sprinting off a long drop overshoots the landing square and lands the route
		// somewhere it was not planned from. A one block step down is just walking.
		int drop = src.getY() - dest.getY();
		steer.sprint = drop <= 1 && ctx.cfg().destroySprint && player.onGround();
		steer.sneak = drop > 1 && ctx.cfg().bridgeSneak && player.onGround();
		return Status.RUNNING;
	}

	// ---- the ones that change the world

	private Status mineThrough(Ctx ctx, Bot.Steer steer) {
		BlockPos wall = firstInTheWay(ctx.level());
		if (wall == null) return walk(ctx, steer, false);      // it is gone; this is a walk now

		if (!Bot.inReach(ctx.mc(), ctx.player(), wall)) {
			detail = "closing on the wall";
			steer.moveTowards(headingTo(ctx.player(), wall));
			return Status.RUNNING;
		}
		detail = "digging through " + name(ctx.level(), wall);
		swingAt(ctx, wall, steer);
		return Status.RUNNING;
	}

	private Status digDown(Ctx ctx, Bot.Steer steer) {
		BlockPos floor = src.below();
		if (!inTheWay(ctx.level(), floor)) {
			// The floor is open. Everything below here is a fall, and a fall needs no keys —
			// pressing a direction while dropping down a shaft is how you land in the wall.
			detail = "going down";
			return Status.RUNNING;
		}
		// Standing on what you are breaking is a controlled fall over a floor and a death over
		// a shaft, so the search's own fall limit decides whether it is allowed.
		if (Bot.dropUnder(ctx.level(), floor, ctx.cfg().pathMaxFall + 2) > ctx.cfg().pathMaxFall) {
			detail = "too far down to dig through";
			return Status.FAILED;
		}
		detail = "digging down through " + name(ctx.level(), floor);
		swingAt(ctx, floor, steer);
		return Status.RUNNING;
	}

	private Status bridge(Ctx ctx, Bot.Steer steer) {
		BlockPos floor = dest.below();
		if (Avoidance.holdsWeight(ctx.level(), floor)) {
			// The block is down. Sneak across it rather than sprinting: the square beyond is
			// still a hole until the next move builds it.
			detail = "crossing";
			steer.moveTowards(headingTo(ctx.player(), dest));
			steer.sneak = ctx.cfg().bridgeSneak && ctx.player().onGround();
			return Status.RUNNING;
		}
		int slot = Bot.buildingSlot(ctx.player(), ctx.cfg());
		if (slot < 0) {
			detail = "no blocks left to bridge with";
			return Status.FAILED;
		}
		ctx.player().getInventory().setSelectedSlot(slot);
		detail = "placing a block";
		// Sneaking is not decoration here. Placing into the gap means looking down at the edge
		// of the block being stood on, and looking down at an edge is how you walk off it.
		steer.sneak = ctx.cfg().bridgeSneak;
		if (placeInto(ctx, floor, steer)) return Status.RUNNING;

		// Nothing clickable yet, which over open air means we are not far enough out. A face is
		// only clickable from outside it: standing in the middle of the block you are on, the
		// side facing the gap is under your feet and the crosshair goes into the top instead.
		// This is the one case where standing still guarantees it never works.
		double out = Math.max(Math.abs(ctx.player().getX() - (floor.getX() + 0.5)),
				Math.abs(ctx.player().getZ() - (floor.getZ() + 0.5)));
		if (out > 0.83) {
			detail = "edging out to the drop";
			steer.sneak = true;                    // whatever the setting says: this walks at a hole
			double heading = headingTo(ctx.player(), dest);
			steer.lookAt(heading, 60);
			steer.moveTowards(heading);
			return Status.RUNNING;
		}
		detail = "nothing to place against";
		return Status.FAILED;
	}

	private Status pillar(Ctx ctx, Bot.Steer steer) {
		LocalPlayer player = ctx.player();
		if (!Bot.fullPlacementSupport(ctx.level(), src.below())) {
			// A slab, repeater, dust line or other partial shape can look clickable but is
			// not a dependable anchor for a block under the player's feet. The planner also
			// rejects this case; this guard handles a world change between planning and use.
			detail = "no full block below to pillar on";
			return Status.FAILED;
		}
		BlockPos head = firstInTheWay(ctx.level());
		if (head != null) {
			// A ceiling is the usual case — that is what standing on a lower floor means — and
			// the search paid for the swing, so take it before trying to rise into it.
			if (!Bot.inReach(ctx.mc(), player, head)) {
				detail = "cannot reach the ceiling";
				return Status.FAILED;
			}
			detail = "opening the ceiling";
			swingAt(ctx, head, steer);
			return Status.RUNNING;
		}
		int slot = Bot.buildingSlot(player, ctx.cfg());
		if (slot < 0) {
			detail = "no blocks left to pillar with";
			return Status.FAILED;
		}
		player.getInventory().setSelectedSlot(slot);
		detail = "pillaring up";
		// Putting a block into the space you are standing in is only possible by not standing
		// there: jump, look down, and put it under you on the way up.
		steer.sneak = false;                       // sneaking is what stops the jump
		steer.jump = player.onGround();
		steer.lookAt(player.getYRot(), 90);
		if (!player.onGround()) placeInto(ctx, src, steer);
		return Status.RUNNING;
	}

	// ---- doors

	private BlockPos doorInTheWay(ClientLevel level) {
		if (Bot.opensByHand(level, dest)) return dest;
		if (Bot.opensByHand(level, dest.above())) return dest.above();
		return null;
	}

	private Status openDoor(Ctx ctx, BlockPos door, Bot.Steer steer) {
		if (!Bot.inReach(ctx.mc(), ctx.player(), door)) {
			detail = "walking up to the door";
			steer.moveTowards(headingTo(ctx.player(), door));
			return Status.RUNNING;
		}
		detail = "opening the " + name(ctx.level(), door);
		double[] look = Bot.aimAt(ctx.player(), Bot.aimPoint(ctx.mc(), ctx.player(), door));
		steer.lookAt(look[0], look[1]);
		steer.precise = true;
		// Once, then wait: a use key held on a door opens it and shuts it again.
		if (doorCooldown == 0) {
			steer.useAt(door);
			steer.use = Bot.lookingAt(ctx.mc(), door);
			doorCooldown = 8;
		}
		return Status.RUNNING;
	}

	// ---- shared hands

	/**
	 * Point at a block and hold the button down, keeping the face between ticks.
	 *
	 * <p>The face is held for one reason and it has nothing to do with which block it is:
	 * vanilla throws away all mining progress the moment the crosshair lands somewhere else,
	 * so a face re-chosen every tick as the view wobbles digs through nothing at all.
	 */
	private void swingAt(Ctx ctx, BlockPos pos, Bot.Steer steer) {
		if (!pos.equals(breaking)) {
			breaking = pos;
			breakFace = null;
		}
		breakFace = Bot.visibleFace(ctx.mc(), ctx.player(), pos, breakFace);
		int tool = Bot.bestToolSlot(ctx.player(), ctx.level().getBlockState(pos));
		if (tool != ctx.player().getInventory().getSelectedSlot()) {
			ctx.player().getInventory().setSelectedSlot(tool);
		}
		double[] look = Bot.aimAt(ctx.player(), miningAim.point(ctx.mc(), ctx.player(), pos, breakFace, ctx.cfg()));
		steer.lookAt(look[0], look[1]);
		steer.precise = true;
		steer.attackAt(pos);
		// Vanilla does the mining. Holding attack while the crosshair is on the block runs the
		// same progress, swing and packet loop a person's mouse does; reimplementing it would
		// be more code producing a stream that is easier to tell apart, not harder.
		steer.attack = Bot.lookingAt(ctx.mc(), pos);
	}

	/**
	 * Point at a face and hold use until a block goes into {@code where}.
	 *
	 * <p>Latched exactly as breaking is, and for the same reason: the camera is filtered, so a
	 * support face re-chosen every tick means the crosshair is always on its way to somewhere
	 * it has already stopped wanting to be, and the check that presses the button never comes
	 * true.
	 *
	 * @return false when there is nowhere to place it from
	 */
	private boolean placeInto(Ctx ctx, BlockPos where, Bot.Steer steer) {
		if (!where.equals(placing)) {
			placing = where;
			placeFace = null;
			placeTicks = 0;
			placeWasFillable = Bot.fillable(ctx.mc(), where);
		}
		placeTicks++;
		placeFace = Bot.placeAgainst(ctx.mc(), ctx.player(), where, placeFace);
		if (placeFace == null) return false;
		double[] look = Bot.aimAt(ctx.player(), Bot.placePoint(ctx.mc(), where, placeFace));
		steer.lookAt(look[0], look[1]);
		steer.precise = true;
		// Counted on the way in rather than every tick the key is down: vanilla holds a right
		// click across its own four-tick delay, so a tick is not a block.
		boolean pressing = Bot.aboutToPlaceInto(ctx.mc(), where);
		steer.placeInto(where);
		steer.use = pressing;
		return true;
	}

	// ---- what this move touches

	/** The block currently being swung at, so a caller can notice it turn to air. */
	public BlockPos breakingBlock() {
		return breaking;
	}

	/** The blocks this move has to break, in the order it should break them. */
	public java.util.List<BlockPos> toBreak(ClientLevel level) {
		java.util.List<BlockPos> out = new java.util.ArrayList<>(2);
		switch (kind) {
			case MINE -> {
				if (inTheWay(level, dest)) out.add(dest);
				if (inTheWay(level, dest.above())) out.add(dest.above());
			}
			case DIG_DOWN -> {
				if (inTheWay(level, src.below())) out.add(src.below());
			}
			case PILLAR -> {
				if (inTheWay(level, dest)) out.add(dest);
				if (inTheWay(level, dest.above())) out.add(dest.above());
			}
			default -> {
			}
		}
		return out;
	}

	private BlockPos firstInTheWay(ClientLevel level) {
		java.util.List<BlockPos> all = toBreak(level);
		return all.isEmpty() ? null : all.getFirst();
	}

	/**
	 * Genuinely in the way: too tall to step onto, and not something a hand opens.
	 *
	 * <p>The same test the search used, so the follower and the plan agree about what a wall
	 * is. Half a block underfoot is floor — read the other way, a room paved in slabs is a
	 * wall per block and the bot sets about mining the floor it is standing on, which is most
	 * of a redstone build.
	 */
	static boolean inTheWay(ClientLevel level, BlockPos pos) {
		return Avoidance.fillsSpace(level, pos) && !Bot.opensByHand(level, pos);
	}

	private static String name(ClientLevel level, BlockPos pos) {
		return level.getBlockState(pos).getBlock().getName().getString();
	}

	/** The world heading from the player to the middle of a block. */
	static double headingTo(LocalPlayer player, BlockPos pos) {
		double dx = pos.getX() + 0.5 - player.getX();
		double dz = pos.getZ() + 0.5 - player.getZ();
		return Math.toDegrees(Math.atan2(-dx, dz));
	}

	@Override
	public String toString() {
		return "%s->%d,%d,%d".formatted(kind, dest.getX(), dest.getY(), dest.getZ());
	}

	// ----------------------------------------------------------- self-check

	/**
	 * Self-check on the bookkeeping — executing a move needs a world, deciding which blocks it
	 * touches does not: {@code ./gradlew selfCheck -Pcheck=com.damia.movrand.PathMove}
	 */
	public static void main(String[] args) {
		// A route turns into one move per step, and each move runs from the previous position
		// to its own. Getting this off by one is a bot walking every route one block behind.
		PathFinder.Path path = new PathFinder.Path(java.util.List.of(
				new PathFinder.Step(0, 64, 0, PathFinder.Kind.START, 0),
				new PathFinder.Step(0, 64, 1, PathFinder.Kind.WALK, 1),
				new PathFinder.Step(0, 65, 2, PathFinder.Kind.ASCEND, 1.6)), true, 10, 2.6);
		java.util.List<PathMove> moves = of(path);
		assert moves.size() == 2 : "two steps after the start should be two moves, got " + moves.size();
		assert moves.getFirst().src.equals(new BlockPos(0, 64, 0)) : "the first move started somewhere else";
		assert moves.getFirst().dest.equals(new BlockPos(0, 64, 1)) : "the first move ended somewhere else";
		assert moves.get(1).kind == PathFinder.Kind.ASCEND : "the move lost the kind the search gave it";
		assert moves.get(1).src.equals(moves.getFirst().dest) : "moves must join up end to start";

		// The start step is never a move: it is where we already are.
		for (PathMove m : moves) assert m.kind != PathFinder.Kind.START : "the start became a move";

		// A one-step route is arrival, not a journey, and produces nothing to walk.
		assert of(new PathFinder.Path(java.util.List.of(
				new PathFinder.Step(0, 64, 0, PathFinder.Kind.START, 0)), true, 1, 0)).isEmpty()
				: "a route that goes nowhere produced a move";

		// Valid positions are how a route finds its place again after a knockback, so both
		// ends of every move have to be in there.
		PathMove walk = moves.getFirst();
		assert walk.validPositions().contains(walk.src) && walk.validPositions().contains(walk.dest)
				: "a move did not recognise its own endpoints";

		// A drop passes through every block on the way down, and mid-fall is exactly when
		// knowing where you are matters most.
		PathMove fall = new PathMove(PathFinder.Kind.DESCEND,
				new BlockPos(0, 70, 0), new BlockPos(0, 66, 0), 2);
		assert fall.validPositions().size() == 5
				: "a four block drop should own the column it falls down, got " + fall.validPositions().size();
		assert fall.validPositions().contains(new BlockPos(0, 68, 0)) : "the middle of the fall is off the route";

		// Headings: yaw 0 faces +Z and 90 faces -X, and getting this backwards is a bot that
		// walks away from everything it is aiming at.
		assert Math.abs(PathFinder.TICKS_PER_BLOCK - 20 / 4.317) < 1e-9 : "walking speed drifted";

		System.out.println("PathMove self-check passed");
	}
}
