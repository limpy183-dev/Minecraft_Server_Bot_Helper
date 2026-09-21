package com.damia.movrand;

import net.minecraft.world.phys.Vec3;

/** Measures physical progress, independently of a planner's busy flag or replacement paths. */
final class NavigationWatchdog {
	static final int PLAN_TICKS = 60;
	private Vec3 anchor;
	private Vec3 localAnchor;
	private double furthest;
	private int stillTicks, planningTicks, localTicks;

	void reset() { anchor = localAnchor = null; furthest = 0; stillTicks = planningTicks = localTicks = 0; }

	String update(Vec3 position, boolean hasPath, boolean mining, boolean changedBlock, Config cfg) {
		if (anchor == null || position.distanceToSqr(anchor) >= 0.04 || changedBlock) {
			anchor = position;
			stillTicks = 0;
		} else stillTicks++;
		// Keep a second anchor until we leave the area. Sliding or circling across the
		// short-distance threshold repeatedly is motion, but does not advance a route.
		double distance = localAnchor == null ? 0 : position.distanceTo(localAnchor);
		if (localAnchor == null || distance >= 1.5 || changedBlock) {
			localAnchor = position;
			furthest = 0;
			localTicks = 0;
		} else if (distance >= furthest + 0.2) {
			furthest = distance;
			localTicks = 0;
		} else localTicks++;
		planningTicks = hasPath || changedBlock ? 0 : planningTicks + 1;
		if (planningTicks >= PLAN_TICKS) return "path search exceeded its deadline";
		int limit = mining ? (int) Math.ceil(cfg.destroyBlockSec * 20)
				: (int) Math.ceil(Math.max(2, Math.min(cfg.baritoneNoProgressSec, cfg.pathStallSec * 2)) * 20);
		if (hasPath && (stillTicks >= limit || localTicks >= limit))
			return mining ? "route mining made no progress" : "route stopped making progress";
		return null;
	}

	static void selfCheck() {
		Config cfg = new Config();
		NavigationWatchdog watch = new NavigationWatchdog();
		for (int i = 1; i <= PLAN_TICKS; i++)
			assert (watch.update(Vec3.ZERO, false, false, false, cfg) != null) == (i == PLAN_TICKS)
					: "an active search could wait forever";
		watch.reset();
		String failure = null;
		for (int i = 0; i <= 60 && failure == null; i++)
			failure = watch.update(new Vec3(i % 2 * 0.03, 0, 0), true, false, false, cfg);
		assert failure != null : "stationary jitter evaded recovery";
		watch.reset();
		failure = null;
		for (int i = 0; i < 200 && failure == null; i++)
			failure = watch.update(new Vec3(0.5 + 0.35 * Math.cos(i), 0.125,
					0.5 + 0.35 * Math.sin(i)), true, false, false, cfg);
		assert failure != null : "circling within a repeater counted as route progress";
		watch.reset();
		for (int i = 0; i < 1000; i++)
			assert watch.update(new Vec3(i * 0.025, 0, 0), true, false, false, cfg) == null : "slow walking was abandoned";
		watch.reset();
		for (int i = 0; i < 500; i++)
			assert watch.update(Vec3.ZERO, true, true, false, cfg) == null : "legitimate slow mining was abandoned";
		for (int i = 0; i < 1000; i++) {
			failure = watch.update(Vec3.ZERO, true, true, false, cfg);
			if (failure != null) break;
		}
		assert failure != null : "held attack counted as confirmed mining";
		watch.reset();
		for (int i = 0; i < 1000; i++)
			assert watch.update(Vec3.ZERO, true, true, i % 100 == 0, cfg) == null : "confirmed block changes were ignored";
	}
}
