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
		if (System.getenv("MOVRAND_STORAGE_WORLD") != null) {
			StorageProfileGameTest.run(test);
			return;
		}
		if (System.getenv("MOVRAND_BASALT_SOURCE") != null) {
			new BasaltFarmGameTest().runTest(test);
			return;
		}
		try (var world = test.worldBuilder().create()) {
			if (Boolean.parseBoolean(System.getenv("MOVRAND_EATING_ONLY"))) {
				eatingDuringPlacement(test, world);
				smoothEating(test, world);
				return;
			}
			gatherBuildingSupplies(test, world);
			if (Boolean.parseBoolean(System.getenv("MOVRAND_SUPPLIES_ONLY"))) return;
			readyTargetPriority(test, world);
			walkingLavaEdge(test, world);
			pickupRangeToggle(test, world);
			if (Boolean.parseBoolean(System.getenv("MOVRAND_PICKUP_ONLY"))) return;
			if (Boolean.parseBoolean(System.getenv("MOVRAND_STORAGE_ONLY"))) {
				StorageGameTest.run(test, world);
				return;
			}
			if (Boolean.parseBoolean(System.getenv("MOVRAND_MECHANICS_ONLY"))) {
				BasaltMechanicsGameTest.run(test, world);
				return;
			}
			navigationPace(test, world);
			miningAimPoints(test, world);
			if (!Boolean.parseBoolean(System.getenv("MOVRAND_TERRAIN_ONLY"))) {
				smoothingMatrix(test, world);
				smoothEating(test, world);
				eatingDuringPlacement(test, world);
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

	/** Exercise the real sprint hook, then leave the terrain suite to cover critical moves. */
	private static void navigationPace(ClientGameTestContext test, TestSingleplayerContext world) {
		for (boolean enabled : new boolean[]{true, false}) {
			setup(test, world);
			world.getServer().runCommand("tp @p 0.5 1 0.5 -90 0");
			world.getServer().runCommand("effect give @p saturation 1 4 true");
			drop(world, 18.5, 1.2, 0.5);
			world.getConnection().waitForClientboundPackets();
			test.waitTicks(15);
			test.runOnClient(mc -> {
				Config cfg = MovRand.config();
				cfg.baritoneRandomisePace = enabled;
				cfg.baritoneSprintChance = 0;
				cfg.destroySprint = true;
				cfg.collectRadius = 24;
				MovRand.controller().start(mc);
			});
			int[] samples = new int[2];
			try {
				test.waitFor(mc -> {
					if (NativeNavigation.controlling() && mc.player.getX() > 5 && mc.player.getX() < 12) {
						samples[0]++;
						if (mc.player.isSprinting()) samples[1]++;
					}
					return mc.player.getInventory().contains(s -> s.is(Items.DIAMOND));
				}, 600);
				if (samples[0] < 5 || (enabled ? samples[1] != 0 : samples[1] == 0))
					throw new AssertionError("pace toggle=" + enabled + ", travel ticks=" + samples[0] + ", sprint ticks=" + samples[1]);
				test.runOnClient(mc -> MovRand.controller().stop(mc, "navigation pace toggle=" + enabled + " passed"));
			} catch (Throwable failure) { diagnose(test, "navigation-pace-" + enabled); throw failure; }
		}
	}

	/** Real outline/reach rays, sticky offsets and live toggle changes. */
	private static void readyTargetPriority(ClientGameTestContext test, TestSingleplayerContext world) {
		setup(test, world);
		world.getServer().runCommand("tp @p 0.5 1 0.5 -90 0");
		world.getServer().runCommand("setblock 0 2 1 redstone_block");
		world.getServer().runCommand("setblock 0 2 2 lava");
		world.getServer().runCommand("setblock 1 1 0 redstone_block");
		world.getConnection().waitForClientboundPackets();
		test.runOnClient(mc -> {
			Config cfg = MovRand.config();
			cfg.destroyBlocks.add("redstone_block"); cfg.destroyTargetRandomness = cfg.taskReactionChance = 0;
			MovRand.controller().start(mc);
			try {
				MovRand.controller().destroyer.tick(mc, mc.player, mc.level, 0);
				if (!new BlockPos(1, 1, 0).equals(MovRand.controller().destroyer.target()))
					throw new AssertionError("nearby lava preparation outranked a ready mining target");
				MovRand.LOG.info("Ready mining target outranked nearer block requiring lava preparation");
			} finally { MovRand.controller().stop(mc, "ready target fixture complete"); }
		});
	}

	/** Exercise the actual injected route costs, including walking beside the farm's lava. */
	private static void walkingLavaEdge(ClientGameTestContext test, TestSingleplayerContext world) {
		setup(test, world);
		world.getServer().runCommand("tp @p 0.5 1 0.5 -90 0");
		world.getServer().runCommand("setblock 2 1 0 lava");
		world.getConnection().waitForClientboundPackets();
		test.runOnClient(mc -> {
			var nav = new Pathing(MovRand.config());
			try {
				nav.tick(new PathMove.Ctx(mc, mc.player, mc.level, MovRand.config()), new Bot.Steer(),
						new BlockPos(4, 1, 0), (x, y, z) -> x == 4 && y == 1 && z == 0, null);
				var ctx = new baritone.pathing.movement.CalculationContext(baritone.api.BaritoneAPI.getProvider().getPrimaryBaritone());
				if (baritone.pathing.movement.movements.MovementTraverse.cost(ctx, 0, 1, 0, 1, 0)
						< baritone.api.pathing.movement.ActionCosts.COST_INF)
					throw new AssertionError("walking route accepted a lava edge");
				var result = new baritone.utils.pathing.MutableMoveResult();
				baritone.pathing.movement.movements.MovementDiagonal.cost(ctx, 0, 1, 0, 1, 1, result);
				if (result.cost < baritone.api.pathing.movement.ActionCosts.COST_INF)
					throw new AssertionError("diagonal route accepted a lava edge");
				if (baritone.pathing.movement.movements.MovementTraverse.cost(ctx, 0, 1, 0, 0, -1)
						>= baritone.api.pathing.movement.ActionCosts.COST_INF)
					throw new AssertionError("lava-edge guard blocked a clear walking route");
				MovRand.LOG.info("Walking and diagonal lava-edge costs rejected unsafe routes");
			} finally { nav.reset(); }
		});
	}

	/** An overhead block is mineable from the floor, but its spawning drops are above pickup height. */
	private static void pickupRangeToggle(ClientGameTestContext test, TestSingleplayerContext world) {
		setup(test, world);
		BlockPos target = new BlockPos(0, 4, 0);
		world.getServer().runCommand("setblock 0 4 0 redstone_block");
		world.getServer().runCommand("tp @p 0.5 1 0.5 0 -90");
		world.getServer().runCommand("attribute @p minecraft:movement_speed base set 0");
		world.getConnection().waitForClientboundPackets(); test.waitTicks(10);
		try {
			test.runOnClient(mc -> {
				Config cfg = MovRand.config();
				cfg.destroyBlocks.add("redstone_block"); cfg.taskReactionChance = 0;
				cfg.mineWithinPickupRange = true; cfg.pathBridge = false; cfg.pathMine = false;
				if (!MineSafety.inspect(new PathMove.Ctx(mc, mc.player, mc.level, cfg), target).safe())
					throw new AssertionError("pickup fixture requires a safe drop area");
				MovRand.controller().start(mc);
			});
			test.waitTicks(20);
			test.runOnClient(mc -> {
				if (!mc.level.getBlockState(target).is(Blocks.REDSTONE_BLOCK) || MovRand.controller().destroyer.mined != 0)
					throw new AssertionError("pickup-only mode mined above pickup height");
				MovRand.config().mineWithinPickupRange = false;
			});
			int ticks = test.waitFor(mc -> mc.level.getBlockState(target).isAir()
					&& MovRand.controller().destroyer.mined > 0, 120);
			test.runOnClient(mc -> {
				if (!MovRand.config().protectMiningDrops || mc.player.position().distanceToSqr(new net.minecraft.world.phys.Vec3(0.5, 1, 0.5)) > 0.04)
					throw new AssertionError("normal reach disabled protection or required a route");
				MovRand.LOG.info("Pickup range toggle: overhead mining confirmed in {} ticks without moving", ticks);
			});
		} finally {
			test.runOnClient(mc -> MovRand.controller().stop(mc, "pickup range fixture complete"));
			world.getServer().runCommand("attribute @p minecraft:movement_speed base set 0.1");
		}
	}

	private static void miningAimPoints(ClientGameTestContext test, TestSingleplayerContext world) {
		setup(test, world);
		world.getServer().runCommand("tp @p 0.5 1 0.5 -90 0");
		BlockPos pos = new BlockPos(2, 1, 0);
		for (String block : new String[]{"stone", "redstone_wire", "iron_bars"}) {
			world.getServer().runCommand("setblock 2 1 0 " + block);
			world.getConnection().waitForClientboundPackets();
			test.waitTicks(5);
			test.runOnClient(mc -> {
				Config cfg = new Config();
				cfg.taskAimPointSpread = 0.4;
				Bot.MiningAim aim = new Bot.MiningAim();
				var face = Bot.visibleFace(mc, mc.player, pos, null);
				var centre = face == null ? Bot.blockCentre(mc, pos) : Bot.facePoint(mc, pos, face);
				var points = new java.util.HashSet<net.minecraft.world.phys.Vec3>();
				for (int i = 0; i < 32; i++) {
					aim.reset();
					var point = aim.point(mc, mc.player, pos, face, cfg);
					points.add(point);
					double[] look = Bot.aimAt(mc.player, point);
					if (!Bot.rotationHits(mc, mc.player, pos, look[0], look[1]))
						throw new AssertionError("random aim missed " + block);
					if (!point.equals(aim.point(mc, mc.player, pos, face, cfg)))
						throw new AssertionError("aim moved during a swing");
					cfg.taskAimRandomisation = false;
					if (!centre.equals(aim.point(mc, mc.player, pos, face, cfg)))
						throw new AssertionError("toggle did not clear cached variation");
					cfg.taskAimRandomisation = true;
				}
				if (block.equals("stone") && points.size() < 2)
					throw new AssertionError("mining aim never varied");
			});
		}
		world.getServer().runCommand("setblock 2 1 0 stone");
		world.getConnection().waitForClientboundPackets();
		test.waitTicks(5);
		test.runOnClient(mc -> {
			Config cfg = new Config();
			cfg.taskAimPointSpread = 0.4;
			var previous = mc.player.position();
			try {
				// The face centre is reachable, but almost every lateral offset is beyond reach.
				mc.player.setPos(2.001 - mc.player.blockInteractionRange(), 1.5 - mc.player.getEyeHeight(), 0.5);
				for (int i = 0; i < 64; i++) {
					var point = new Bot.MiningAim().point(mc, mc.player, pos, net.minecraft.core.Direction.WEST, cfg);
					double[] look = Bot.aimAt(mc.player, point);
					if (!Bot.rotationHits(mc, mc.player, pos, look[0], look[1]))
						throw new AssertionError("variation lost a block at the edge of reach");
				}
			} finally { mc.player.setPos(previous); }
			MovRand.LOG.info("Mining aim variation, toggle, thin shapes and reach checks passed");
		});
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

	/** Eating shares job aim and must finish, restore the tool and resume mining without a snap. */
	private static void smoothEating(ClientGameTestContext test, TestSingleplayerContext world) {
		setup(test, world);
		world.getServer().runCommand("setblock 2 1 0 chest");
		world.getServer().runCommand("setblock 2 1 2 redstone_block");
		world.getServer().runCommand("tp @p 0.5 1 0.5 -90 35");
		world.getServer().runOnServer(server -> {
			var player = server.getPlayerList().getPlayers().getFirst();
			player.getFoodData().setFoodLevel(12);
			player.getInventory().setItem(8, new net.minecraft.world.item.ItemStack(Items.COOKED_BEEF, 2));
			player.inventoryMenu.broadcastChanges();
		});
		world.getConnection().waitForClientboundPackets(); test.waitTicks(10);
		test.runOnClient(mc -> {
			if (!new BlockPos(2, 1, 0).equals(Bot.hitBlock(mc))) throw new AssertionError("eating fixture must initially aim at the chest");
			Config cfg = MovRand.config();
			cfg.autoEatEnabled = true; cfg.taskReactionChance = 0; cfg.yawJitterEnabled = false;
			cfg.destroyBlocks.add("redstone_block");
			MovRand.controller().start(mc);
		});
		double[] previous = {35};
		boolean[] ate = {false};
		int ticks = test.waitFor(mc -> {
			if (mc.player.containerMenu != mc.player.inventoryMenu) throw new AssertionError("eating opened the chest while turning");
			if (Math.abs(mc.player.getXRot() - previous[0]) > MovRand.config().taskAimMaxTurnDeg + 0.01)
				throw new AssertionError("eating bypassed the pitch turn limit");
			previous[0] = mc.player.getXRot();
			ate[0] |= mc.player.isUsingItem();
			return mc.player.getFoodData().getFoodLevel() == 20
					&& mc.level.getBlockState(new BlockPos(2, 1, 2)).isAir();
		}, 240);
		if (!ate[0]) throw new AssertionError("eating fixture never used food");
		test.runOnClient(mc -> {
			if (!mc.player.getMainHandItem().is(Items.DIAMOND_PICKAXE)) throw new AssertionError("eating did not restore the tool");
			MovRand.controller().stop(mc, "smooth eating passed in " + ticks + " ticks");
		});
	}

	/** A short configured timeout must not repeatedly trade an unfinished meal for a block. */
	private static void eatingDuringPlacement(ClientGameTestContext test, TestSingleplayerContext world) {
		setup(test, world);
		world.getServer().runCommand("fill -2 1 -2 9 1 2 stone");
		world.getServer().runCommand("setblock 6 1 0 lava");
		world.getServer().runCommand("setblock 5 1 0 air");
		world.getServer().runCommand("setblock 6 2 0 redstone_block");
		world.getServer().runCommand("tp @p 0.5 2 0.5 -90 0");
		world.getConnection().waitForClientboundPackets();
		test.runOnClient(mc -> {
			Config cfg = MovRand.config();
			cfg.autoEatEnabled = false;
			cfg.autoEatMaxTicks = 20;
			cfg.taskReactionChance = 0;
			cfg.destroyBlocks.add("redstone_block");
			MovRand.controller().start(mc);
		});
		test.waitFor(mc -> MovRand.controller().destroyer.phase == BaseDestroyer.Phase.COVERING, 100);
		world.getServer().runOnServer(server -> {
			var player = server.getPlayerList().getPlayers().getFirst();
			player.getFoodData().setFoodLevel(4);
			player.getInventory().setItem(8, new net.minecraft.world.item.ItemStack(Items.COOKED_BEEF, 4));
			player.inventoryMenu.broadcastChanges();
		});
		world.getConnection().waitForClientboundPackets();
		test.runOnClient(mc -> MovRand.config().autoEatEnabled = true);
		boolean[] started = {false};
		test.waitFor(mc -> {
			started[0] |= mc.player.isUsingItem() && mc.player.getMainHandItem().is(Items.COOKED_BEEF);
			if (mc.player.getFoodData().getFoodLevel() >= 20) return true;
			if (started[0] && !mc.player.getMainHandItem().is(Items.COOKED_BEEF))
				throw new AssertionError("placement stole the hand before the meal finished");
			return false;
		}, 240);
		if (!started[0]) throw new AssertionError("placement fixture never ate");
		test.waitFor(mc -> mc.player.getInventory().contains(s -> s.is(Items.REDSTONE_BLOCK)), 400);
		test.runOnClient(mc -> {
			if (!mc.level.getBlockState(new BlockPos(6, 1, 0)).is(Blocks.COBBLESTONE))
				throw new AssertionError("placement did not resume after eating");
			MovRand.controller().stop(mc, "eating during placement passed");
		});
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

	/** Start empty, recover stone as cobblestone, then resume the selected demolition job. */
	private static void gatherBuildingSupplies(ClientGameTestContext test, TestSingleplayerContext world) {
		setup(test, world);
		world.getServer().runCommand("clear @p cobblestone");
		world.getServer().runCommand("fill 2 1 -1 3 1 1 stone");
		world.getServer().runCommand("setblock 0 1 4 redstone_block");
		world.getServer().runCommand("tp @p 0.5 1 0.5 -90 0");
		world.getConnection().waitForClientboundPackets();
		test.waitTicks(10);
		test.runOnClient(mc -> {
			Config cfg = MovRand.config();
			cfg.gatherBuildingBlocks = true;
			cfg.gatherBlocks = new java.util.ArrayList<>(java.util.List.of("stone"));
			cfg.buildingBlocks = new java.util.ArrayList<>(java.util.List.of("cobblestone"));
			cfg.gatherBlockCount = 3;
			cfg.bridgeKeepBlocks = 1;
			cfg.collectDrops = false;
			cfg.collectOnlySelectedDrops = true;
			cfg.restockHotbar = false;
			cfg.destroyBlocks.add("redstone_block");
			cfg.taskReactionChance = 0;
			MovRand.controller().start(mc);
		});
		boolean[] gathered = {false};
		try {
			test.waitFor(mc -> {
				BaseDestroyer job = MovRand.controller().destroyer;
				gathered[0] |= job.gatheringSupplies();
				return gathered[0] && !job.gatheringSupplies()
						&& Bot.buildingBlockCount(mc.player, MovRand.config(), true) >= 4
						&& mc.level.getBlockState(new BlockPos(0, 1, 4)).isAir();
			}, 1000);
			test.runOnClient(mc -> {
				if (Bot.buildingSlot(mc.player, MovRand.config()) < 0)
					throw new AssertionError("gathered supplies never became usable on the hotbar");
				MovRand.controller().stop(mc, "building supply gathering passed");
			});
		} catch (Throwable failure) { diagnose(test, "building-supplies"); throw failure; }
	}

	static void setup(ClientGameTestContext test, TestSingleplayerContext world) {
		test.runOnClient(mc -> {
			MovRand.controller().stop(mc, "terrain fixture");
			Config cfg = new Config();
			cfg.terrainDefaults();
			cfg.fastDestroyerTuning();
			// Walk wherever eligible to stress the transition back to normal terrain execution.
			cfg.baritoneRandomisePace = true;
			cfg.baritoneSprintChance = 0;
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
