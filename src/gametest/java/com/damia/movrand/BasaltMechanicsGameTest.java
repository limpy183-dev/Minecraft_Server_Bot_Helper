package com.damia.movrand;

import baritone.api.BaritoneAPI;
import baritone.api.pathing.movement.ActionCosts;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.movement.movements.MovementParkour;
import baritone.utils.pathing.MutableMoveResult;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;

/** Focused live-world regressions from the basalt farm, without a long demolition run. */
final class BasaltMechanicsGameTest {
	static void run(ClientGameTestContext test, TestSingleplayerContext world) {
		TerrainGameTest.setup(test, world);
		world.getServer().runCommand("tp @p 0.5 1 0.5 -90 0");
		world.getServer().runCommand("setblock 2 1 1 repeater");
		world.getServer().runCommand("setblock 2 3 0 stone");
		world.getConnection().waitForClientboundPackets();
		test.waitTicks(5);
		test.runOnClient(mc -> {
			mc.player.getInventory().setSelectedSlot(1);
			BlockPos required = new BlockPos(2, 1, 0);
			if (Bot.placeAgainst(mc, mc.player, required, Direction.SOUTH) != Direction.SOUTH)
				throw new AssertionError("valid repeater placement face rejected");
			if (Bot.placeAgainst(mc, mc.player, new BlockPos(2, 2, 0), Direction.UP) != Direction.UP)
				throw new AssertionError("valid ceiling placement face rejected");
			if (Bot.fullPlacementSupport(mc.level, required.south()))
				throw new AssertionError("thin click anchor became a full standing floor");
			var job = MovRand.controller().destroyer;
			MovRand.config().movementEnabled = true;
			int mined = job.mined, placed = job.placed;
			job.expectEdit(mc.level, required, Blocks.STONE, false);
			if (job.mined != mined) throw new AssertionError("unconfirmed mining counted");
			job.confirmEdit(mc.level, required, Blocks.AIR.defaultBlockState());
			job.confirmEdit(mc.level, required, Blocks.AIR.defaultBlockState());
			if (job.mined != mined + 1) throw new AssertionError("break confirmation duplicated or lost");
			job.expectEdit(mc.level, required, Blocks.COBBLESTONE, true);
			job.confirmEdit(mc.level, required, Blocks.AIR.defaultBlockState());
			job.confirmEdit(mc.level, required, Blocks.COBBLESTONE.defaultBlockState());
			if (job.placed != placed) throw new AssertionError("rejected placement counted");
			job.expectEdit(mc.level, required, Blocks.COBBLESTONE, true);
			job.confirmEdit(mc.level, required, Blocks.COBBLESTONE.defaultBlockState());
			job.confirmEdit(mc.level, required, Blocks.COBBLESTONE.defaultBlockState());
			if (job.placed != placed + 1) throw new AssertionError("placement confirmation duplicated or lost");
			MovRand.controller().stop(mc, "placement faces and accounting checked");
		});

		TerrainGameTest.setup(test, world);
		world.getServer().runCommand("tp @p 0.5 1 0.5 -90 0");
		world.getServer().runCommand("setblock 1 1 0 stone");
		world.getServer().runCommand("setblock 4 1 0 stone");
		world.getConnection().waitForClientboundPackets();
		test.waitTicks(5);
		test.runOnClient(mc -> {
			mc.player.getInventory().setSelectedSlot(1);
			BlockPos required = new BlockPos(3, 1, 0);
			if (Bot.placeAgainst(mc, mc.player, required, Direction.EAST) != Direction.EAST)
				throw new AssertionError("standing placement fixture has no ray");
			var crouchedEyes = new net.minecraft.world.phys.Vec3(mc.player.getX(), mc.player.getY()
					+ mc.player.getEyeHeight(net.minecraft.world.entity.Pose.CROUCHING), mc.player.getZ());
			if (Bot.placeAgainst(mc, mc.player, required, Direction.EAST, crouchedEyes) == Direction.EAST)
				throw new AssertionError("crouched placement accepted an occluded standing-only face");
		});

		world.getServer().runCommand("setblock 1 1 0 redstone_block");
		world.getConnection().waitForClientboundPackets();
		test.runOnClient(mc -> {
			Config cfg = MovRand.config(); cfg.destroyBlocks.add("redstone_block"); cfg.destroyMaxTargets = 1;
			BlockPos near = new BlockPos(1, 1, 0);
			var stale = java.util.List.of(new BlockTargets.Found(new BlockPos(15, 1, 0), 15, "far", false));
			var fresh = new BlockTargets().withNearby(mc.level, mc.player, cfg, stale, p -> true);
			if (fresh.stream().noneMatch(f -> f.pos().equals(near))) throw new AssertionError("stale shortlist hid reachable block");
			if (new BlockTargets().withNearby(mc.level, mc.player, cfg, stale, p -> false).stream().anyMatch(f -> f.pos().equals(near)))
				throw new AssertionError("local refresh bypassed retry eligibility");
			cfg.movementEnabled = true;
			mc.player.getInventory().setItem(0, net.minecraft.world.item.ItemStack.EMPTY);
			if (MineSafety.mayStartBreaking(near) || cfg.movementEnabled)
				throw new AssertionError("missing harvest tool silently destroyed recoverable drops");
		});

		TerrainGameTest.setup(test, world);
		world.getServer().runCommand("fill 2 0 -1 3 0 1 air");
		world.getServer().runCommand("setblock 4 1 0 stone");
		world.getServer().runCommand("tp @p 1.5 1 0.5 -90 0");
		world.getConnection().waitForClientboundPackets();
		test.waitTicks(5);
		test.runOnClient(mc -> {
			MovRand.config().movementEnabled = true;
			var nav = new NativeNavigation(MovRand.config());
			nav.tick(new PathMove.Ctx(mc, mc.player, mc.level, MovRand.config()), new Bot.Steer(),
					new BlockPos(4, 2, 0), (x, y, z) -> x == 4 && y == 2 && z == 0, null, true);
			var result = new MutableMoveResult();
			MovementParkour.cost(new CalculationContext(BaritoneAPI.getProvider().getPrimaryBaritone()), 1, 1, 0, Direction.EAST, result);
			if (result.cost >= ActionCosts.COST_INF || result.y != 2) throw new AssertionError("safe upward parkour was disabled");
		});
		world.getServer().runCommand("setblock 4 2 1 lava[level=1]");
		world.getConnection().waitForClientboundPackets();
		test.runOnClient(mc -> {
			var result = new MutableMoveResult();
			MovementParkour.cost(new CalculationContext(BaritoneAPI.getProvider().getPrimaryBaritone()), 1, 1, 0, Direction.EAST, result);
			if (result.cost < ActionCosts.COST_INF) throw new AssertionError("upward parkour accepted adjacent lava at body height");
			NativeNavigation.stopAll();
			MovRand.controller().stop(mc, "basalt mechanics checks passed");
			MovRand.LOG.info("BASALT MECHANICS PASSED: thin/ceiling anchors, confirmed counts, fresh targets, lava-corner parkour");
		});
	}
}
