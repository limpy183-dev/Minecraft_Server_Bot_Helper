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
	private boolean anchor;
	private int ticks, placeTicks;
	boolean coveringLiquid;
	String detail = "";
	SitePreparation(Config cfg) { builder = new NativeNavigation(cfg); }
	boolean active() { return protecting != null; }
	void reset() { builder.reset(); placing = protecting = null; face = null; anchor = false; ticks = placeTicks = 0; }

	Result tick(PathMove.Ctx ctx, BlockPos block, Bot.Steer steer, java.util.Set<net.minecraft.world.level.block.Block> mayBreak) {
		if (!ctx.cfg().protectMiningDrops || ctx.level().getBlockState(block).isAir()) {
			reset(); return Result.READY;
		}
		MineSafety.Assessment assessment = MineSafety.inspect(ctx, block);
		// Confirm the placement before revalidating a queued mining request: containing
		// liquid can make that request safe on the very same tick.
		if (placing != null && Bot.fullPlacementSupport(ctx.level(), placing)) {
			placing = null; builder.reset();
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
		if (placing == null || (!anchor && !assessment.cover().contains(placing))) {
			builder.reset();
			placing = assessment.cover().stream().min(java.util.Comparator
					.comparingInt((BlockPos p) -> Bot.placeAgainst(ctx.mc(), ctx.player(), p, null) != null ? 0 : 1)
					.thenComparingInt(p -> ctx.level().getFluidState(p).isSource() ? 0 : 1)
					.thenComparingDouble(p -> p.distToCenterSqr(ctx.player().getX(), ctx.player().getY(), ctx.player().getZ()))).orElseThrow();
			BlockPos required = placing;
			placing = nextAnchor(required, block,
					p -> ctx.level().hasChunkAt(p) && !ctx.level().getBlockState(p).isAir()
							&& ctx.level().getFluidState(p).isEmpty() && !ctx.level().getBlockState(p).getShape(ctx.level(), p).isEmpty(),
					p -> ctx.level().hasChunkAt(p) && p.getY() >= ctx.level().getMinY() && p.getY() < ctx.level().getMaxY()
							&& Bot.fillable(ctx.mc(), p) && !ctx.player().getBoundingBox().intersects(new net.minecraft.world.phys.AABB(p)));
			if (placing == null) { detail = "no nearby anchor for the protection floor"; reset(); return Result.FAILED; }
			anchor = !placing.equals(required);
			face = null;
			placeTicks = 0;
		}
		coveringLiquid = !ctx.level().getFluidState(placing).isEmpty();
		// This action sneaks. Testing only the standing ray alternated every tick between
		// standing navigation and a crouched placement that could no longer see its face.
		face = Bot.placeAgainst(ctx.mc(), ctx.player(), placing, face,
				new net.minecraft.world.phys.Vec3(ctx.player().getX(),
						ctx.player().getY() + ctx.player().getEyeHeight(net.minecraft.world.entity.Pose.CROUCHING), ctx.player().getZ()));
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

	/** Build back toward the required cell from an existing click face, one confirmed block
	 * at a time. The four-block bound keeps scaffolding local and supply use finite. */
	static BlockPos nextAnchor(BlockPos required, BlockPos protectedBlock,
	                          java.util.function.Predicate<BlockPos> support,
	                          java.util.function.Predicate<BlockPos> fillable) {
		var queue = new java.util.ArrayDeque<BlockPos>();
		var seen = new java.util.HashSet<BlockPos>();
		queue.add(required); seen.add(required);
		while (!queue.isEmpty()) {
			BlockPos cell = queue.removeFirst();
			for (Direction side : Direction.values()) if (support.test(cell.relative(side))) return cell;
			for (Direction side : Direction.values()) {
				BlockPos next = cell.relative(side);
				if (!next.equals(protectedBlock) && next.distManhattan(required) <= 4
						&& seen.add(next) && fillable.test(next)) queue.addLast(next);
			}
		}
		return null;
	}

	static void selfCheck() {
		BlockPos required = BlockPos.ZERO, target = required.above();
		assert nextAnchor(required, target, p -> p.equals(required.west()), p -> true).equals(required);
		BlockPos first = nextAnchor(required, target, p -> p.equals(required.west(3)), p -> true);
		assert first.equals(required.west(2)) : "unsupported floor did not start at the existing anchor";
		assert nextAnchor(required, target, p -> false, p -> true) == null : "unbounded scaffolding search";
		assert nextAnchor(required, target, p -> p.equals(target.above()), p -> p.equals(target)) == null
				: "scaffolding replaced the target it was protecting";
	}
}
