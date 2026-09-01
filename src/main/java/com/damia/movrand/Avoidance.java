package com.damia.movrand;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CactusBlock;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.EndGatewayBlock;
import net.minecraft.world.level.block.EndPortalBlock;
import net.minecraft.world.level.block.MagmaBlock;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.level.block.PointedDripstoneBlock;
import net.minecraft.world.level.block.PowderSnowBlock;
import net.minecraft.world.level.block.SweetBerryBushBlock;
import net.minecraft.world.level.block.WebBlock;
import net.minecraft.world.level.block.WitherRoseBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;

/**
 * Steering around whatever is in the way, and then going back to where it was headed.
 *
 * <p>Not a pathfinder. A pathfinder searches a graph of the world and commits to a route;
 * this looks a few blocks ahead along a fan of headings, picks the smallest turn that is
 * actually walkable, and unwinds it the moment the original heading clears again. That is
 * enough for walls, trees, cliffs, ravines and pillars, and it costs a handful of block
 * lookups rather than a search. It cannot solve a maze, and it does not pretend to.
 *
 * <p>Three things make the difference between this and a bot that jitters against a corner:
 *
 * <ul>
 *   <li><b>Shoulders.</b> A player is 0.6 blocks wide, so the probe checks both sides of the
 *       ray rather than a centre line. Clipping a corner is the usual way a single-ray
 *       avoider gets itself wedged.
 *   <li><b>Hysteresis.</b> Once it has chosen a side it keeps preferring that side until the
 *       way ahead is properly clear. Without it, a doorway or a tree trunk with equal room
 *       either side makes it dither left-right on the spot forever.
 *   <li><b>The dodge is separate from the heading.</b> Steering never touches where the bot
 *       thinks it is going, only where it is currently pointed, so "carry on to the target
 *       afterwards" needs no memory at all - the correction simply decays to zero.
 * </ul>
 */
public final class Avoidance {

	/** Degrees between candidate headings. Finer buys little and costs a probe each. */
	private static final double STEP = 15;
	/** How much a side already chosen is worth, in blocks of clearance. */
	private static final double HYSTERESIS = 0.6;

	/**
	 * How far a heading is walkable. Separating this from the steering is what lets the
	 * whole decision layer be tested without a world.
	 */
	@FunctionalInterface
	public interface Probe {
		double clearance(double yawDeg);
	}

	private double dodge;
	/** The side committed to, -1 left, +1 right, 0 none. */
	private int side;
	private boolean trapped;
	public String status = "clear";

	public double offset() {
		return dodge;
	}

	/** True while a correction is being held, so a ledge guard knows this is handled. */
	public boolean steering() {
		return side != 0 || Math.abs(dodge) > 0.5;
	}

	/** True when every heading was blocked - nothing left to try but the stuck logic. */
	public boolean trapped() {
		return trapped;
	}

	public void reset() {
		dodge = 0;
		side = 0;
		trapped = false;
		status = "clear";
	}

	/**
	 * @param intendedYaw where the bot wants to be pointed, before any dodging
	 * @return degrees to add to that heading this tick
	 */
	public double update(Config cfg, double intendedYaw, Probe probe) {
		if (!cfg.avoidEnabled) {
			reset();
			status = "off";
			return 0;
		}

		double reach = Math.max(1.0, cfg.avoidLookahead);
		double clear = reach - 0.01;

		// the way it wants to go is clear, so stop dodging and let the correction unwind
		if (probe.clearance(intendedYaw) >= clear) {
			side = 0;
			trapped = false;
			dodge = approach(dodge, 0, cfg.avoidReturnDegPerTick);
			status = dodge == 0 ? "clear" : "returning";
			return dodge;
		}

		double bestAngle = 0;
		double bestClearance = -1;
		boolean solved = false;

		for (double magnitude = STEP; magnitude <= cfg.avoidMaxDeviationDeg + 0.001; magnitude += STEP) {
			double left = probe.clearance(intendedYaw - magnitude);
			double right = probe.clearance(intendedYaw + magnitude);

			// a side already chosen is worth something, or a doorway with equal room either
			// way makes it dither on the spot instead of walking through
			double leftScore = left + (side < 0 ? HYSTERESIS : 0);
			double rightScore = right + (side > 0 ? HYSTERESIS : 0);
			boolean goRight = rightScore > leftScore
					|| (rightScore == leftScore && side == 0 && Rng.coinFlip());

			double reachable = goRight ? right : left;
			if (reachable > bestClearance) {
				bestClearance = reachable;
				bestAngle = goRight ? magnitude : -magnitude;
			}
			// the smallest turn that actually works is the one to take
			if (bestClearance >= clear) {
				solved = true;
				break;
			}
		}

		trapped = bestClearance <= 0.01;
		if (trapped) {
			// hold whatever correction is already applied rather than snapping straight into
			// the wall; the stuck detector is the thing that gets to give up, not this
			status = "boxed in";
			return dodge;
		}

		if (bestAngle != 0) side = bestAngle > 0 ? 1 : -1;
		double limit = Math.max(0, cfg.avoidMaxDeviationDeg);
		double target = Math.max(-limit, Math.min(limit, bestAngle));
		dodge = approach(dodge, target, cfg.avoidTurnDegPerTick);
		status = solved
				? "going %s %.0f°".formatted(target < 0 ? "left" : "right", Math.abs(target))
				: "squeezing %s".formatted(target < 0 ? "left" : "right");
		return dodge;
	}

	/**
	 * Proportional, with a cap. The proportional part eases the turn in and out on its own;
	 * the cap is what the user actually set.
	 */
	private static double approach(double from, double to, double maxStep) {
		double delta = to - from;
		if (Math.abs(delta) < 0.05) return to;
		double step = delta * 0.3;
		double limit = Math.max(0.05, maxStep);
		return from + Math.max(-limit, Math.min(limit, step));
	}

	// ----------------------------------------------------------- the world

	/**
	 * A probe against the real world, anchored at wherever the player is this tick. All the
	 * candidate headings share one origin, so it is captured once rather than per ray.
	 */
	public static Probe worldProbe(ClientLevel level, LocalPlayer player, Config cfg) {
		double ox = player.getX(), oy = player.getY(), oz = player.getZ();
		return yaw -> clearance(level, ox, oy, oz, yaw, cfg);
	}

	/** What one point along a ray turned out to be. */
	private enum Ground {
		WALKABLE, STEP_UP, DROP, WALL
	}

	/**
	 * Blocks of clear walking along {@code yawDeg}, stopping at the first wall or hole.
	 *
	 * <p>Both shoulders are tested, not just the centre line: the player is 0.6 wide, and a
	 * ray down the middle happily reports a doorframe as passable and then wedges on it.
	 */
	private static double clearance(ClientLevel level, double ox, double oy, double oz,
	                                double yawDeg, Config cfg) {
		double rad = Math.toRadians(yawDeg);
		double fx = -Math.sin(rad), fz = Math.cos(rad);
		double px = -fz, pz = fx; // perpendicular, for the shoulders
		double reach = Math.max(1.0, cfg.avoidLookahead);
		double step = 0.6;
		double half = 0.32;

		double travelled = 0;
		for (double d = step; d <= reach + 0.001; d += step) {
			double x = ox + fx * d, z = oz + fz * d;

			for (int s = -1; s <= 1; s += 2) {
				Ground shoulder = groundAt(level, x + px * half * s, oy, z + pz * half * s, cfg);
				if (shoulder == Ground.WALL) return travelled;
			}
			// a hole under one shoulder is a scraped edge; a hole dead ahead is a fall
			if (cfg.avoidHoles && groundAt(level, x, oy, z, cfg) == Ground.DROP) return travelled;

			travelled = d;
		}
		return reach;
	}

	private static Ground groundAt(ClientLevel level, double x, double y, double z, Config cfg) {
		BlockPos feet = BlockPos.containing(x, y + 0.1, z);

		// A hazard is never something to step onto, into, or through, whatever shape it is.
		// This has to come first: a cactus has collision and would otherwise read as a step
		// up, and the bot would cheerfully jump onto it.
		if (hazardAt(level, feet, cfg) || hazardAt(level, feet.above(), cfg)) return Ground.WALL;

		if (solid(level, feet) || solid(level, feet.above())) {
			// something to climb: passable only if it is short enough and has headroom
			int height = 0;
			while (height < 4 && solid(level, feet.above(height))) height++;
			if (height == 0) return Ground.WALL;                       // an overhang, not a step
			if (height > Math.max(1, cfg.autoJumpMaxHeight)) return Ground.WALL;
			if (solid(level, feet.above(height + 1))) return Ground.WALL;
			if (hazardAt(level, feet.above(height), cfg)) return Ground.WALL; // a bad thing to land on
			return Ground.STEP_UP;
		}

		for (int d = 1; d <= Math.max(1, cfg.ledgeDropBlocks) + 1; d++) {
			BlockPos below = feet.below(d);
			// the hazard test comes first because some of them are solid: magma is a floor,
			// and walking onto it is exactly what this is here to refuse
			if (hazardAt(level, below, cfg)) return Ground.DROP;
			if (solid(level, below)) return Ground.WALKABLE;
		}
		return Ground.DROP;
	}

	/**
	 * A block worth refusing to walk into, whether or not it has any collision.
	 *
	 * <p>Collision is a wall detector, not a danger detector, and in this game those are
	 * nearly opposites: the things that hurt are mostly the things you can walk straight
	 * through. Fire, a berry bush, powder snow, a portal — none of them stop you, and every
	 * one of them is a reason to turn.
	 */
	public static boolean hazardAt(ClientLevel level, BlockPos pos, Config cfg) {
		BlockState state = level.getBlockState(pos);
		if (cfg.avoidLava && isLava(state)) return true;
		if (cfg.avoidWater && isWater(state)) return true;
		if (cfg.avoidPortals && isPortal(state)) return true;
		return cfg.avoidHazards && isHurtful(state);
	}

	/** Everything that damages, traps or freezes on contact. */
	private static boolean isHurtful(BlockState state) {
		Block b = state.getBlock();
		return b instanceof BaseFireBlock                                  // fire and soul fire
				|| b instanceof MagmaBlock
				|| b instanceof CactusBlock
				|| b instanceof SweetBerryBushBlock
				|| b instanceof WitherRoseBlock
				|| b instanceof PointedDripstoneBlock
				|| b instanceof PowderSnowBlock
				|| b instanceof WebBlock                                   // not damage, but stuck
				|| CampfireBlock.isLitCampfire(state);
	}

	/** A doorway to somewhere else is not an obstacle, but walking into one is still a mistake. */
	private static boolean isPortal(BlockState state) {
		Block b = state.getBlock();
		return b instanceof NetherPortalBlock || b instanceof EndPortalBlock || b instanceof EndGatewayBlock;
	}

	private static boolean solid(ClientLevel level, BlockPos pos) {
		return !level.getBlockState(pos).getCollisionShape(level, pos).isEmpty();
	}

	private static boolean isLava(BlockState state) {
		var fluid = state.getFluidState().getType();
		return fluid == Fluids.LAVA || fluid == Fluids.FLOWING_LAVA;
	}

	private static boolean isWater(BlockState state) {
		var fluid = state.getFluidState().getType();
		return fluid == Fluids.WATER || fluid == Fluids.FLOWING_WATER;
	}

	/**
	 * Self-check:
	 * {@code ./gradlew selfCheck -Pcheck=com.damia.movrand.Avoidance}
	 *
	 * <p>The world probing needs a world, but the part that decides anything does not, which
	 * is the whole reason {@link Probe} is an interface. These are synthetic obstacles.
	 */
	public static void main(String[] args) {
		Config cfg = new Config();
		cfg.clampAll();
		cfg.avoidLookahead = 3;
		cfg.avoidTurnDegPerTick = 90;   // no rate limit in the way of the logic being tested
		cfg.avoidReturnDegPerTick = 90;
		double reach = cfg.avoidLookahead;

		// nothing in the way: no correction, ever
		Avoidance a = new Avoidance();
		Probe open = yaw -> reach;
		for (int i = 0; i < 50; i++) {
			assert a.update(cfg, 90, open) == 0 : "steered around an empty field";
		}
		assert !a.steering() && !a.trapped() && a.status.equals("clear");

		// a wall spanning +-30 degrees of the heading: go round, and by the smallest turn
		Probe wall = yaw -> Math.abs(wrap(yaw - 90)) <= 30 ? 0 : reach;
		a.reset();
		double settled = 0;
		for (int i = 0; i < 60; i++) settled = a.update(cfg, 90, wall);
		assert Math.abs(Math.abs(settled) - 45) < 0.001
				: "expected the smallest clear heading, 45 degrees, got " + settled;

		// and stay on that side rather than dithering
		int chosen = settled > 0 ? 1 : -1;
		for (int i = 0; i < 100; i++) {
			double d = a.update(cfg, 90, wall);
			assert Math.signum(d) == chosen : "swapped sides on tick " + i + " (" + d + ")";
		}

		// once the way ahead clears, the correction unwinds to nothing
		for (int i = 0; i < 200 && a.offset() != 0; i++) a.update(cfg, 90, open);
		assert a.offset() == 0 : "the dodge never returned to the intended heading: " + a.offset();
		assert !a.steering() : "still claiming to steer after unwinding";

		// a wall with one gap: take the gap, however far round it is
		Probe gap = yaw -> Math.abs(wrap(yaw - 90 - 120)) <= 8 ? reach : 0;
		a.reset();
		double found = 0;
		for (int i = 0; i < 60; i++) found = a.update(cfg, 90, gap);
		assert Math.abs(found - 120) < STEP : "did not find the gap at 120 degrees, sat at " + found;
		assert !a.trapped() : "reported trapped with a gap available";

		// boxed in on every heading: say so, and do not thrash
		Probe boxed = yaw -> 0;
		a.reset();
		a.update(cfg, 90, boxed);
		assert a.trapped() : "did not notice being boxed in";
		assert a.status.equals("boxed in") : a.status;
		double held = a.offset();
		a.update(cfg, 90, boxed);
		assert a.offset() == held : "kept turning while boxed in";

		// a gap outside the deviation limit is not a gap: stay inside what was allowed, and
		// admit to being stuck rather than quietly turning further than asked
		cfg.avoidMaxDeviationDeg = 45;
		a.reset();
		for (int i = 0; i < 60; i++) a.update(cfg, 90, gap);
		assert Math.abs(a.offset()) <= 45.001 : "exceeded the deviation limit: " + a.offset();
		assert a.trapped() : "a gap past the limit should read as boxed in, not as solved";
		cfg.avoidMaxDeviationDeg = 150;

		// the turn rate is what the user set, on the way out and on the way back
		cfg.avoidTurnDegPerTick = 4;
		cfg.avoidReturnDegPerTick = 2;
		a.reset();
		double previous = 0;
		for (int i = 0; i < 40; i++) {
			double d = a.update(cfg, 90, wall);
			assert Math.abs(d - previous) <= 4.001 : "turned %.2f degrees in one tick".formatted(d - previous);
			previous = d;
		}
		for (int i = 0; i < 200; i++) {
			double d = a.update(cfg, 90, open);
			assert Math.abs(d - previous) <= 2.001 : "unwound %.2f degrees in one tick".formatted(d - previous);
			previous = d;
		}
		assert a.offset() == 0;

		// switched off means no steering at all, whatever is in front of it
		cfg.avoidEnabled = false;
		a.reset();
		assert a.update(cfg, 90, boxed) == 0 && a.status.equals("off") : "steered while disabled";

		System.out.println("Avoidance self-check passed");
	}

	private static double wrap(double degrees) {
		double d = degrees % 360;
		if (d >= 180) d -= 360;
		if (d < -180) d += 360;
		return d;
	}
}
