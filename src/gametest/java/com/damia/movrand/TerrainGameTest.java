package com.damia.movrand;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

/** Real server physics, survival inventory, production controller and Baritone executor. */
public final class TerrainGameTest implements FabricClientGameTest {
	@Override public void runTest(ClientGameTestContext test) {
		try (var world = test.worldBuilder().create()) {
			if (!Boolean.parseBoolean(System.getenv("MOVRAND_TERRAIN_ONLY"))) {
				smoothingMatrix(test, world);
				StorageGameTest.run(test, world);
			}
			stalledRoute(test, world);
			vanishedProtectionTarget(test, world);
			setup(test, world);
			world.getServer().runCommand("fill 0 1 -1 2 3 1 stone");
			world.getServer().runCommand("fill 5 3 -1 7 3 1 stone");
			world.getServer().runCommand("fill 8 1 -1 8 3 1 stone");
			world.getServer().runCommand("tp @p 0.5 4 0.5 -90 0");
			drop(world, 6.5, 1.2, 0.5);
			collect(test, "lower-floor-overhang", 600);

			for (double rate : new double[]{8, 24, 90}) {
				setup(test, world);
				test.runOnClient(mc -> {
					MovRand.config().baritoneTurnRate = rate;
					MovRand.config().baritoneParkourPlace = true;
				});
				world.getServer().runCommand("fill -2 8 -1 2 8 1 stone");
				world.getServer().runCommand("fill 7 8 -1 10 8 1 stone");
				world.getServer().runCommand("tp @p 0.5 9 0.5 -90 0");
				drop(world, 8.5, 9.2, 0.5);
				collect(test, "bridge-gap-rate-" + rate, 1000);
				boolean built = world.getServer().computeOnServer(server -> {
					for (int x = 3; x <= 6; x++) for (int y = 1; y <= 9; y++) for (int z = -2; z <= 2; z++)
						if (server.overworld().getBlockState(new BlockPos(x, y, z)).is(Blocks.COBBLESTONE)) return true;
					return false;
				});
				if (!built) throw new AssertionError("gap test did not exercise placement");
			}

			setup(test, world);
			world.getServer().runCommand("fill -2 1 -2 9 1 2 stone");
			world.getServer().runCommand("setblock 6 1 0 lava");
            world.getServer().runCommand("setblock 5 1 0 air"); // exposed side access beneath the target
			world.getServer().runCommand("setblock 6 2 0 redstone_block");
			world.getServer().runCommand("tp @p 0.5 2 0.5 -90 0");
			world.getConnection().waitForClientboundPackets();
			test.runOnClient(mc -> {
				MovRand.config().destroyBlocks.add("redstone_block");
				MovRand.controller().start(mc);
			});
			try {
				java.util.concurrent.atomic.AtomicInteger trace = new java.util.concurrent.atomic.AtomicInteger();
                int ticks = test.waitFor(mc -> {
                    if (trace.incrementAndGet() % 100 == 0) MovRand.LOG.info("LAVA TRACE at {} yaw {} pitch {} target {} lava {} approach {} status {}", mc.player.position(), mc.player.getYRot(), mc.player.getXRot(), mc.level.getBlockState(new BlockPos(6,2,0)), mc.level.getBlockState(new BlockPos(6,1,0)), mc.level.getBlockState(new BlockPos(5,1,0)), MovRand.controller().destroyer.describe());
                    return mc.player.getInventory().contains(s -> s.is(Items.REDSTONE_BLOCK));
                }, 240);
				test.runOnClient(mc -> {
					if (!mc.level.getBlockState(new BlockPos(6, 1, 0)).is(Blocks.COBBLESTONE))
						throw new AssertionError("lava below the target was not contained");
					if (mc.player.getHealth() < 20) throw new AssertionError("lava mining caused damage");
					MovRand.controller().stop(mc, "lava protection passed in " + ticks + " ticks");
				});
			} catch (Throwable failure) { diagnose(test, "lava-protection"); throw failure; }
		} finally {
			// Baritone's cache packer and saver are non-daemon workers. Once the test world
			// has closed, stop them so Minecraft's shutdown watchdog does not fail a passed run.
			if (baritone.Baritone.getExecutor() instanceof java.util.concurrent.ExecutorService executor)
				executor.shutdownNow();
		}
	}

	/** Production mining, thin shapes, navigation and camera handoffs at slider boundaries. */
	private static void smoothingMatrix(ClientGameTestContext test, TestSingleplayerContext world) {
		for (int scenario = 0; scenario < 5; scenario++) {
			setup(test, world);
			double smoothing = new double[]{0, 0.35, 0.8, 1, 1}[scenario];
			boolean extreme = scenario == 4;
			world.getServer().runCommand("setblock 2 1 0 redstone_block");
			world.getServer().runCommand("setblock 8 1 0 redstone_block");
			world.getServer().runCommand("setblock 9 1 1 iron_bars");
			world.getServer().runCommand("tp @p 0.5 1 0.5 90 0");
			world.getConnection().waitForClientboundPackets();
			test.waitTicks(10);
			test.runOnClient(mc -> {
				Config cfg = MovRand.config();
				cfg.baritoneTurnSmoothing = cfg.taskAimSmoothing = smoothing;
				cfg.destroyBlocks.add("redstone_block"); cfg.destroyBlocks.add("iron_bars");
				cfg.taskReactionChance = cfg.destroyTargetRandomness = 0;
				cfg.yawJitterEnabled = extreme;
				cfg.yawJitterAmplitudeDeg = 5; cfg.yawJitterSpeed = 0.3;
				cfg.taskAimWobbleScale = 1;
				cfg.taskAimPointSpread = extreme ? 0.4 : 0;
				cfg.baritoneAimVariation = extreme ? 0.2 : 0;
				cfg.taskAimMaxTurnDeg = extreme ? 2 : 24;
				cfg.baritoneTurnRate = extreme ? 8 : 24;
				cfg.clampAll();
				if (cfg.baritoneTurnSmoothing != smoothing || cfg.taskAimSmoothing != smoothing)
					throw new AssertionError("smoothing setting was clamped below its requested value");
				MovRand.controller().start(mc);
			});
			try {
				double[] lastLook = {90, 0};
				boolean[] navigated = {false};
				int ticks = test.waitFor(mc -> {
					double rate = Math.max(MovRand.config().baritoneTurnRate, MovRand.config().taskAimMaxTurnDeg);
					if (Math.abs(Human.wrap(mc.player.getYRot() - lastLook[0])) > rate + 0.01
							|| Math.abs(mc.player.getXRot() - lastLook[1]) > rate + 0.01)
						throw new AssertionError("camera snapped past its rate limit during a job/handoff");
					lastLook[0] = mc.player.getYRot(); lastLook[1] = mc.player.getXRot();
					navigated[0] |= NativeNavigation.controlling();
					return mc.level.getBlockState(new BlockPos(2, 1, 0)).isAir()
							&& mc.level.getBlockState(new BlockPos(8, 1, 0)).isAir()
							&& mc.level.getBlockState(new BlockPos(9, 1, 1)).isAir();
				}, 600);
				if (!navigated[0]) throw new AssertionError("smoothing fixture never exercised navigation");
				test.runOnClient(mc -> {
					if (mc.player.getHealth() < 20) throw new AssertionError("smoothing fixture caused damage");
					MovRand.LOG.info("Smoothing {} extreme humanisation {} completed in {} ticks", smoothing, extreme, ticks);
					MovRand.controller().stop(mc, "smoothing fixture passed");
				});
			} catch (Throwable failure) { diagnose(test, "smoothing-" + scenario); throw failure; }
		}
	}

	/** Real Baritone paths must time out even while the executor keeps reporting a path. */
	private static void stalledRoute(ClientGameTestContext test, TestSingleplayerContext world) {
		setup(test, world);
		world.getServer().runCommand("setblock 8 1 0 redstone_block");
		world.getServer().runCommand("tp @p 0.5 1 0.5 -90 0");
		world.getServer().runCommand("attribute @p minecraft:movement_speed base set 0");
		world.getConnection().waitForClientboundPackets();
		test.waitTicks(10);
		test.runOnClient(mc -> {
			Config cfg = MovRand.config();
			cfg.baritoneParkour = false;
			cfg.destroySprint = false;
			cfg.destroyBlocks.add("redstone_block");
			MovRand.controller().start(mc);
		});
		java.util.concurrent.atomic.AtomicBoolean hadPath = new java.util.concurrent.atomic.AtomicBoolean();
		try {
			int ticks = test.waitFor(mc -> {
				BaseDestroyer job = MovRand.controller().destroyer;
				if (job.phase == BaseDestroyer.Phase.WALKING && NativeNavigation.controlling()) hadPath.set(true);
				return hadPath.get() && job.target() == null;
			}, 240);
			if (!hadPath.get()) throw new AssertionError("stall fixture did not exercise a live path");
			test.runOnClient(mc -> {
				if (NativeNavigation.controlling()) throw new AssertionError("failed route retained the controls");
				MovRand.LOG.info("Stationary route recovered in {} ticks", ticks);
			});
		} finally {
			test.runOnClient(mc -> MovRand.controller().stop(mc, "stationary route fixture complete"));
			world.getServer().runCommand("attribute @p minecraft:movement_speed base set 0.1");
		}
	}

	/** A removed target must release both its preparation request and its navigation owner. */
	private static void vanishedProtectionTarget(ClientGameTestContext test, TestSingleplayerContext world) {
		setup(test, world);
		world.getServer().runCommand("fill -2 1 -2 9 1 2 stone");
		world.getServer().runCommand("setblock 6 1 0 lava");
		world.getServer().runCommand("setblock 5 1 0 air");
		world.getServer().runCommand("setblock 6 2 0 redstone_block");
		world.getServer().runCommand("tp @p 0.5 2 0.5 -90 0");
		world.getConnection().waitForClientboundPackets();
		test.runOnClient(mc -> {
			MovRand.config().destroyBlocks.add("redstone_block");
			MovRand.controller().start(mc);
			if (MineSafety.mayStartBreaking(new BlockPos(6, 2, 0)))
				throw new AssertionError("unsafe mining was not denied");
		});
		test.waitFor(mc -> MovRand.controller().destroyer.phase == BaseDestroyer.Phase.COVERING, 100);
		world.getServer().runCommand("setblock 6 2 0 air");
		world.getServer().runCommand("setblock 1 2 0 redstone_block");
		world.getConnection().waitForClientboundPackets();
		test.waitFor(mc -> mc.player.getInventory().contains(s -> s.is(Items.REDSTONE_BLOCK)), 240);
		test.runOnClient(mc -> {
			if (MineSafety.deniedBlock() != null) throw new AssertionError("vanished block left a stale mining denial");
			MovRand.controller().stop(mc, "vanished protection target recovered");
		});
	}

	private static void setup(ClientGameTestContext test, TestSingleplayerContext world) {
		test.runOnClient(mc -> {
			MovRand.controller().stop(mc, "terrain fixture");
			Config cfg = new Config();
			cfg.terrainDefaults();
			cfg.fastDestroyerTuning();
			cfg.baritoneTurnSmoothing = cfg.taskAimSmoothing = 1;
			cfg.destroyerEnabled = true;
			cfg.destroyStopWhenDone = false;
			cfg.containerScanEnabled = false;
			cfg.stopOnChatKeyword = false;
			cfg.stopWhenUnfocused = false;
			cfg.alertEnabled = false;
			cfg.coverLiquids = false; // exercise the mining preparation itself
			for (BlockTargets.Family f : BlockTargets.Family.values()) cfg.setDestroyFamily(f, false);
			cfg.destroyBlocks.clear();
			MovRand.replaceConfig(cfg);
		});
		var server = world.getServer();
		server.runCommand("kill @e[type=item]");
		server.runCommand("gamemode survival @p");
		server.runCommand("clear @p");
		server.runCommand("give @p diamond_pickaxe");
		server.runCommand("give @p cobblestone 256");
		server.runCommand("effect give @p instant_health 1 4 true");
		server.runCommand("fill -8 1 -8 20 20 8 air");
		server.runCommand("fill -8 0 -8 20 0 8 stone");
		world.getConnection().waitForClientboundPackets();
	}

	private static void drop(TestSingleplayerContext world, double x, double y, double z) {
		world.getServer().runOnServer(server -> {
			var item = new net.minecraft.world.entity.item.ItemEntity(server.overworld(), x, y, z, new net.minecraft.world.item.ItemStack(Items.DIAMOND));
			item.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
			server.overworld().addFreshEntity(item);
		});
	}

	private static void collect(ClientGameTestContext test, String name, int limit) {
		test.waitTicks(15);
		test.runOnClient(mc -> MovRand.controller().start(mc));
		try {
			int ticks = test.waitFor(mc -> mc.player.getInventory().contains(s -> s.is(Items.DIAMOND)), limit);
			test.runOnClient(mc -> {
				if (mc.player.getHealth() < 20) throw new AssertionError(name + " caused damage");
				MovRand.controller().stop(mc, name + " passed in " + ticks + " ticks");
			});
		} catch (Throwable failure) { diagnose(test, name); throw failure; }
	}

	private static void diagnose(ClientGameTestContext test, String name) {
		test.runOnClient(mc -> MovRand.LOG.error("TERRAIN TEST {}: at {}, health {}, state {}, task {}", name,
				mc.player.position(), mc.player.getHealth(), MovRand.controller().lastReason, MovRand.controller().destroyer.describe()));
		test.takeScreenshot(name);
	}
}
