package com.damia.movrand;

/**
 * The "pointed at something but never arriving" watchdog.
 *
 * <p>The obvious version keeps one record-closest distance for the whole run. That is wrong
 * the moment the destination moves: the area sweep ticks a chunk off and aims at the next
 * one, which is nearly always further away than the record set on the last one, so every
 * distance from then on reads as no progress and the alert fires while the map is visibly
 * filling in. A record belongs to the target it was measured against, and is thrown away
 * with it.
 */
public final class NavProgress {

	/** A step has to beat the record by this much to count; less is wander, not progress. */
	private static final double EPSILON = 0.5;

	private double targetX = Double.NaN, targetZ;
	private double best;
	private int ticks;

	public void reset() {
		targetX = Double.NaN;
		ticks = 0;
	}

	/**
	 * @return true on the single tick the watchdog gives up; the countdown then starts over,
	 *         so a bot left running against a wall alerts once per period rather than forever.
	 */
	public boolean update(double x, double z, double distance, double limitSec) {
		if (limitSec <= 0) {
			ticks = 0;
			return false;
		}
		// NaN != NaN, so the first call after a reset always lands here
		if (x != targetX || z != targetZ) {
			targetX = x;
			targetZ = z;
			best = distance;
			ticks = 0;
			return false;
		}
		if (distance < best - EPSILON) {
			best = distance;
			ticks = 0;
			return false;
		}
		if (++ticks <= limitSec * 20) return false;
		best = distance;
		ticks = 0;
		return true;
	}

	/**
	 * Self-check:
	 * {@code ./gradlew selfCheck -Pcheck=com.damia.movrand.NavProgress}
	 */
	public static void main(String[] args) {
		double limit = 5; // 100 ticks

		// walking in: never fires
		NavProgress walk = new NavProgress();
		for (int i = 0; i < 400; i++) {
			assert !walk.update(0, 0, 500 - i, limit) : "alerted while closing in, tick " + i;
		}

		// standing still: fires once, just after the period, then again a period later
		NavProgress still = new NavProgress();
		int fired = 0, firstAt = -1;
		for (int i = 0; i < 250; i++) {
			if (still.update(0, 0, 42, limit)) {
				if (firstAt < 0) firstAt = i;
				fired++;
			}
		}
		assert firstAt == 101 : "fired at tick " + firstAt + ", wanted 101";
		assert fired == 2 : "fired " + fired + " times in 250 ticks, wanted 2";

		// the regression: an area sweep hopping to a further chunk every 60 ticks is progress
		NavProgress sweep = new NavProgress();
		double tx = 0, tz = 0, dist = 30;
		for (int i = 0; i < 2000; i++) {
			if (i % 60 == 0) { // chunk done, aim at the next one — further away than the last
				tx += 16;
				tz += 16;
				dist = 30;
			}
			dist -= 0.4;
			assert !sweep.update(tx, tz, dist, limit) : "alerted mid-sweep at tick " + i;
		}

		// a target that never changes and never gets closer still trips, sweep or not
		NavProgress wall = new NavProgress();
		boolean tripped = false;
		for (int i = 0; i < 200; i++) tripped |= wall.update(16, 16, 12, limit);
		assert tripped : "never alerted against a wall";

		// 0 disables it
		NavProgress off = new NavProgress();
		for (int i = 0; i < 5000; i++) assert !off.update(0, 0, 99, 0) : "fired with the watchdog off";

		System.out.println("NavProgress OK");
	}
}
