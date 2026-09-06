package com.damia.movrand;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/** Revalidates drop protection every tick, places reachable blocks directly, and bounds all work. */
final class SitePreparation {
	enum Result { READY, WORKING, FAILED }
	private final NativeNavigation builder;
	private BlockPos placing;
	private BlockPos protecting;
	private Direction face;
	private int ticks, placeTicks;
	int placed;
	boolean coveringLiquid;
	String detail = "";
	SitePreparation(Config cfg) { builder = new NativeNavigation(cfg); }
	boolean active() { return protecting != null; }
	void reset() { builder.reset(); placing = protecting = null; face = null; ticks = placeTicks = 0; }

	Result tick(PathMove.Ctx ctx, BlockPos block, Bot.Steer steer, java.util.Set<net.minecraft.world.level.block.Block> mayBreak) {
		if (!ctx.cfg().protectMiningDrops || ctx.level().getBlockState(block).isAir()) {
			reset(); return Result.READY;
		}
		MineSafety.Assessment assessment = MineSafety.inspect(ctx, block);
		// Confirm the placement before revalidating a queued mining request: containing
		// liquid can make that request safe on the very same tick.
		if (placing != null && Bot.fullPlacementSupport(ctx.level(), placing)) {
			placed++; placing = null; builder.reset();
		}
		if (assessment.safe()) { reset(); return Result.READY; }
		if (protecting != null && !protecting.equals(block)) {
			// The builder tried mining another unsafe block to reach the first one. Recursing
			// into its protection would repeatedly replace the job and restart its deadline.
			detail = "protection route requires another unsafe block; trying another target";
			reset(); return Result.FAILED;
		}
		protecting = block.immutable();
		if (!assessment.known()) { detail = "drop area is not loaded"; reset(); return Result.FAILED; }
		if (++ticks > ctx.cfg().prepareSiteSec * 20) { detail = "could not finish drop protection in time"; reset(); return Result.FAILED; }
		int slot = Bot.buildingSlot(ctx.player(), ctx.cfg());
		if (slot < 0 || !ctx.cfg().pathBridge
				|| Bot.buildingBlockCount(ctx.player(), ctx.cfg()) <= ctx.cfg().bridgeKeepBlocks) {
			detail = "no permitted building blocks for drop protection"; reset(); return Result.FAILED;
		}
		ctx.player().getInventory().setSelectedSlot(slot);
		if (placing == null || !assessment.cover().contains(placing)) {
			builder.reset();
			placing = assessment.cover().stream().min(java.util.Comparator
					.comparingInt((BlockPos p) -> Bot.placeAgainst(ctx.mc(), ctx.player(), p, null) != null ? 0 : 1)
					.thenComparingInt(p -> ctx.level().getFluidState(p).isSource() ? 0 : 1)
					.thenComparingDouble(p -> p.distToCenterSqr(ctx.player().getX(), ctx.player().getY(), ctx.player().getZ()))).orElseThrow();
			face = null;
			placeTicks = 0;
		}
		coveringLiquid = !ctx.level().getFluidState(placing).isEmpty();
		face = Bot.placeAgainst(ctx.mc(), ctx.player(), placing, face);
		if (face != null) {
			if (NativeNavigation.yieldFor(steer)) { steer.externalNavigation = true; return Result.WORKING; }
			if (++placeTicks > Math.max(60, BaseDestroyer.aimDeadlineTicks(ctx.cfg()))) {
				detail = "protection placement was not accepted; trying another target";
				reset(); return Result.FAILED;
			}
			double[] look = Bot.aimAt(ctx.player(), Bot.placePoint(ctx.mc(), placing, face));
			steer.lookAt(look[0], look[1]);
			steer.precise = true;
			steer.sneak = true;
			steer.placeInto(placing);
			steer.use = Bot.aboutToPlaceInto(ctx.mc(), placing);
			detail = (coveringLiquid ? "capping liquid at " : "building a drop-catching floor at ") + placing.toShortString();
			return Result.WORKING;
		}
		Pathing.Nav result = builder.place(ctx, steer, placing, mayBreak);
		detail = builder.status;
		if (result == Pathing.Nav.NO_ROUTE) { reset(); return Result.FAILED; }
		return Result.WORKING;
	}
}
