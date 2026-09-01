package com.damia.movrand;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;

/**
 * The handbrake. Predicts where the player should be after each tick and pulls the plug
 * when reality disagrees.
 *
 * <p>Stuck detection asks "have I covered ground lately". This asks the sharper question:
 * "am I moving at the speed my own key presses imply". A conveyor, a slowness effect, a
 * server dragging you back, another mod stealing the camera or an anti-cheat pinning you
 * in place all show up here long before the distance check notices.
 */
public final class SafeStop {

	/** Blocks per tick on flat ground, from the vanilla movement constants. */
	private static final double SPRINT_SPEED = 0.2806;
	private static final double WALK_SPEED = 0.2158;
	private static final double SNEAK_SPEED = 0.0650;

	private final Config cfg;

	private double lastX, lastY, lastZ;
	private float expectedYaw = Float.NaN;
	private String lastDimension = "";
	private long lastTickNanos;
	private boolean primed;

	private double ratioSum;
	private int ratioCount;
	private int graceTicks;

	/**
	 * Where we have been for the last five seconds, one entry per tick. A server putting you
	 * back drops you on this trail; a teleport lands off it. Nothing else can tell the two
	 * apart — both arrive as the same position packet.
	 */
	private static final int TRAIL = 100;
	private final double[] trailX = new double[TRAIL];
	private final double[] trailY = new double[TRAIL];
	private final double[] trailZ = new double[TRAIL];
	private int trailAt, trailFilled;

	/** How many times the server has put us back this session. */
	public int rubberBands;

	public double lastRatio = 1.0;
	public double averageRatio = 1.0;

	public SafeStop(Config cfg) {
		this.cfg = cfg;
	}

	/** Call after any deliberate jump in position or heading, so it is not read as a fault. */
	public void grace(int ticks) {
		graceTicks = Math.max(graceTicks, ticks);
		ratioSum = 0;
		ratioCount = 0;
	}

	public void reset(LocalPlayer player) {
		primed = false;
		// a deliberate move must not be excused by where we were before it
		trailAt = 0;
		trailFilled = 0;
		expectedYaw = Float.NaN;
		ratioSum = 0;
		ratioCount = 0;
		graceTicks = 40;
		lastTickNanos = 0;
		if (player != null) {
			lastX = player.getX();
			lastY = player.getY();
			lastZ = player.getZ();
			lastDimension = dimensionOf(player);
			primed = true;
		}
	}

	/** Records the yaw the controller just wrote, so a hijack can be told from our own turn. */
	public void expectYaw(float yaw) {
		expectedYaw = yaw;
	}

	private static String dimensionOf(LocalPlayer player) {
		return player.level() == null ? "" : player.level().dimension().identifier().getPath();
	}

	/**
	 * @return a human-readable failure, or null when everything looks right.
	 */
	public String check(Minecraft mc, LocalPlayer player, boolean wantsForward, boolean sprinting, boolean sneaking) {
		String dimension = dimensionOf(player);
		double x = player.getX(), y = player.getY(), z = player.getZ();

		if (!primed) {
			lastX = x;
			lastY = y;
			lastZ = z;
			lastDimension = dimension;
			lastTickNanos = System.nanoTime();
			primed = true;
			return null;
		}

		double dx = x - lastX, dz = z - lastZ;
		double moved = Math.sqrt(dx * dx + dz * dz);
		double dy = Math.abs(y - lastY);
		long now = System.nanoTime();
		double sinceLastTickMs = lastTickNanos == 0 ? 50 : (now - lastTickNanos) / 1_000_000.0;

		remember(lastX, lastY, lastZ);
		lastX = x;
		lastY = y;
		lastZ = z;
		lastTickNanos = now;

		String previousDimension = lastDimension;
		lastDimension = dimension;

		if (graceTicks > 0) {
			graceTicks--;
			return null;
		}

		// --- hard faults, no averaging needed ---

		if (cfg.safeStopOnDimensionChange && !previousDimension.equals(dimension)) {
			return "Dimension changed to " + dimension;
		}
		if (cfg.safeStopOnTeleport && (moved > cfg.safeStopTeleportBlocks || dy > cfg.safeStopTeleportBlocks)) {
			grace(20);
			// Landing back on our own trail is a rejected step, not a journey: the server has
			// undone the last second of walking. Carrying on is the whole point - if it keeps
			// happening the stuck check notices the ground we are not covering.
			if (cfg.safeStopIgnoreRubberBand && backOnTheTrail(x, y, z)) {
				rubberBands++;
				return null;
			}
			return "Position jumped %.1f blocks in one tick".formatted(Math.max(moved, dy));
		}
		if (cfg.safeStopOnVehicle && player.isPassenger()) {
			return "Ended up riding " + player.getVehicle().getName().getString();
		}
		if (cfg.safeStopOnFreeze && sinceLastTickMs > cfg.safeStopFreezeMs) {
			grace(20);
			return "Client stalled for %.0fms".formatted(sinceLastTickMs);
		}
		if (cfg.safeStopOnRotationHijack && !Float.isNaN(expectedYaw)) {
			float drift = Math.abs(Mth.wrapDegrees(player.getYRot() - expectedYaw));
			if (drift > cfg.safeStopRotationToleranceDeg) {
				return "Something else moved the camera by %.0f°".formatted(drift);
			}
		}

		// --- the speed prediction ---

		if (!cfg.safeStopOnSpeed) return null;
		if (!wantsForward) return null;
		// these all legitimately change the speed, so the prediction does not apply
		if (!player.onGround() || player.isInWater() || player.isInLava() || player.isPassenger()) {
			ratioSum = 0;
			ratioCount = 0;
			return null;
		}

		double expected = sneaking ? SNEAK_SPEED : sprinting ? SPRINT_SPEED : WALK_SPEED;
		lastRatio = Math.min(2.0, moved / expected);
		ratioSum += lastRatio;
		ratioCount++;

		int window = (int) Math.max(10, cfg.safeStopWindowSec * 20);
		if (ratioCount < window) return null;

		averageRatio = ratioSum / ratioCount;
		ratioSum = 0;
		ratioCount = 0;

		if (averageRatio < cfg.safeStopMinSpeedRatio) {
			return "Moving at %.0f%% of the predicted speed".formatted(averageRatio * 100);
		}
		return null;
	}

	private void remember(double x, double y, double z) {
		trailX[trailAt] = x;
		trailY[trailAt] = y;
		trailZ[trailAt] = z;
		trailAt = (trailAt + 1) % TRAIL;
		if (trailFilled < TRAIL) trailFilled++;
	}

	/** Whether a landing spot is somewhere we stood in the last few seconds. */
	private boolean backOnTheTrail(double x, double y, double z) {
		double r2 = cfg.safeStopRubberBandBlocks * cfg.safeStopRubberBandBlocks;
		for (int i = 0; i < trailFilled; i++) {
			double dx = trailX[i] - x, dy = trailY[i] - y, dz = trailZ[i] - z;
			if (dx * dx + dy * dy + dz * dz <= r2) return true;
		}
		return false;
	}

	/**
	 * Self-check on the trail - the prediction needs a player, the trail does not:
	 * {@code ./gradlew selfCheck -Pcheck=com.damia.movrand.SafeStop}
	 */
	public static void main(String[] args) {
		Config cfg = new Config();
		cfg.clampAll();
		SafeStop s = new SafeStop(cfg);

		// three seconds of walking in a straight line
		for (int i = 0; i < 60; i++) s.remember(i * 0.25, 64, 0);
		assert s.backOnTheTrail(0, 64, 0) : "the start of the walk is on the trail";
		assert s.backOnTheTrail(14.6, 64, 0) : "and so is the far end of it";
		assert !s.backOnTheTrail(400, 64, 400) : "a real teleport is not on the trail";
		assert !s.backOnTheTrail(7.5, 200, 0) : "straight up is not the same place";

		// the ring holds a few seconds, not the whole session
		SafeStop t = new SafeStop(cfg);
		for (int i = 0; i < 500; i++) t.remember(1000 + i, 64, 0);
		assert !t.backOnTheTrail(1000, 64, 0) : "where we were a minute ago should have gone";
		assert t.backOnTheTrail(1495, 64, 0) : "the recent end did not survive the wrap";

		// and a fresh start forgets it, so a deliberate move is never excused
		t.reset(null);
		assert !t.backOnTheTrail(1495, 64, 0) : "reset left the old trail behind";

		System.out.println("SafeStop self-check passed");
	}
}
