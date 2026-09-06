package com.damia.movrand;

import net.minecraft.world.phys.Vec3;

/** Measures physical progress, independently of a planner's busy flag or replacement paths. */
final class NavigationWatchdog {
	static final int PLAN_TICKS = 60;
	private Vec3 anchor;
	private int stillTicks, planningTicks;

	void reset() { anchor = null; stillTicks = planningTicks = 0; }

	String update(Vec3 position, boolean hasPath, boolean mining, boolean changedBlock, Config cfg) {
		if (anchor == null || position.distanceToSqr(anchor) >= 0.04 || changedBlock) {
			anchor = position;
			stillTicks = 0;
		} else stillTicks++;
		planningTicks = hasPath || changedBlock ? 0 : planningTicks + 1;
		if (planningTicks >= PLAN_TICKS) return "path search exceeded its deadline";
		int limit = mining ? (int) Math.ceil(cfg.destroyBlockSec * 20)
				: (int) Math.ceil(Math.max(2, Math.min(cfg.baritoneNoProgressSec, cfg.pathStallSec * 2)) * 20);
		if (hasPath && stillTicks >= limit) return mining ? "route mining made no progress" : "route stopped making progress";
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
