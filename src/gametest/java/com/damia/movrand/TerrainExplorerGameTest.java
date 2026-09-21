package com.damia.movrand;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import java.nio.file.*;
import java.util.*;

/** Generated survival maps, production controller, real server physics and expedition telemetry. */
final class TerrainExplorerGameTest {
    private static final List<String> rows = new ArrayList<>(List.of("map,ticks,metres,metres_per_second,health,mined,placed,failures,controller_mean_ms,controller_max_ms"));

    static void run(ClientGameTestContext test, TestSingleplayerContext world) {
        try {
            if (Boolean.parseBoolean(System.getenv("MOVRAND_EXPLORER_BOAT_ONLY"))) { boatCrossing(test, world); return; }
            setup(test, world);
            world.getServer().runCommand("setblock 9 1 3 chest");
            journey(test, world, "flat-containers", 12, 1, 0, 600);
            test.runOnClient(mc -> require(MovRand.controller().journal.all().stream()
                    .anyMatch(e -> e.kind() == Journal.Kind.CONTAINER_CLUSTER), "Container discovery was not logged"));

            setup(test, world);
            world.getServer().runCommand("fill -2 1 -2 16 4 2 bedrock");
            world.getServer().runCommand("fill -1 1 -1 15 2 1 air");
            world.getServer().runCommand("fill 6 1 -1 6 2 1 stone");
            journey(test, world, "sealed-mining-tunnel", 12, 1, 0, 1000);
            require(world.getServer().computeOnServer(s -> !s.overworld().getBlockState(new BlockPos(6, 1, 0)).is(Blocks.STONE)
                    || !s.overworld().getBlockState(new BlockPos(6, 1, 1)).is(Blocks.STONE)
                    || !s.overworld().getBlockState(new BlockPos(6, 1, -1)).is(Blocks.STONE)), "Tunnel did not exercise mining");

            setup(test, world);
            world.getServer().runCommand("fill -2 8 -2 2 8 2 stone");
            world.getServer().runCommand("fill 7 8 -2 14 8 2 stone");
            world.getServer().runCommand("tp @p 0.5 9 0.5 -90 0");
            journey(test, world, "elevated-gap", 12, 9, 0, 1400);

            setup(test, world);
            world.getServer().runCommand("fill 4 1 -4 7 1 1 lava");
            world.getServer().runCommand("fill 4 2 -4 7 7 -3 cobblestone");
            test.runOnClient(mc -> { MovRand.config().explorerMine = false; MovRand.config().explorerBridge = false; });
            journey(test, world, "lava-cast-detour", 14, 1, 0, 1000);

            setup(test, world);
            journey(test, world, "exact-high-Y-pillar", 0, 14, 0, 1800);

            setup(test, world);
            world.getServer().runCommand("effect clear @p saturation");
            world.getServer().runOnServer(s -> { var p = s.getPlayerList().getPlayers().getFirst(); p.getFoodData().setFoodLevel(8); p.getFoodData().setSaturation(0); });
            journey(test, world, "hungry-journey", 18, 1, 0, 1200);
            test.runOnClient(mc -> require(mc.player.getFoodData().getFoodLevel() >= 17, "Food was not eaten"));

            setup(test, world);
            world.getServer().runCommand("fill 1 1 -1 1 12 1 stone");
            world.getServer().runCommand("fill 0 1 0 0 12 0 ladder[facing=west]");
            test.runOnClient(mc -> { MovRand.config().explorerMine = false; MovRand.config().explorerBridge = false; });
            journey(test, world, "ladder-climb", 1, 13, 0, 1200);

            setup(test, world);
            world.getServer().runCommand("fill -2 18 -1 0 18 1 stone");
            world.getServer().runCommand("tp @p 0.5 19 0.5 -90 0");
            test.runOnClient(mc -> { MovRand.config().explorerMine = false; MovRand.config().explorerBridge = false; });
            journey(test, world, "water-bucket-descent", 4, 1, 0, 1200);

            boatCrossing(test, world);

            setup(test, world);
            world.getServer().runCommand("fill -8 0 -8 63 0 31 stone");
            world.getServer().runCommand("fill -8 1 -8 63 5 31 air");
            world.getConnection().waitForClientboundPackets();
            test.runOnClient(mc -> {
                var ctl = MovRand.controller(); ctl.syncAreaWorld(mc);
                ctl.area.setCorners(0, 0, 47, 15); ctl.area.reset();
                MovRand.config().explorerCoordinates = false; ctl.start(mc);
            });
            await(test, "map-area-three-chunks", 2000, null);

            setup(test, world);
            world.getServer().runCommand("fill 4 1 -2 8 4 2 bedrock");
            test.runOnClient(mc -> { var c = MovRand.config(); c.explorerRetries = 0; c.explorerTargetSec = 30; });
            start(test, 6, 1, 0);
            test.waitFor(mc -> MovRand.controller().explorer.phase == TerrainExplorer.Phase.BLOCKED, 900);
            test.runOnClient(mc -> require(!MovRand.config().movementEnabled, "Unreachable goal did not stop"));

            for (String dimension : List.of("the_nether", "the_end")) {
                setup(test, world);
                var server = world.getServer();
                server.runOnServer(srv -> {
                    var level = srv.getLevel(dimension.equals("the_nether") ? net.minecraft.world.level.Level.NETHER : net.minecraft.world.level.Level.END);
                    for (int cx = -1; cx <= 1; cx++) for (int cz = -1; cz <= 1; cz++) level.getChunk(cx, cz);
                });
                server.runCommand("execute in minecraft:" + dimension + " run fill -8 79 -8 20 79 8 " + (dimension.equals("the_nether") ? "netherrack" : "end_stone"));
                server.runCommand("execute in minecraft:" + dimension + " run fill -8 80 -8 20 86 8 air");
                server.runCommand("execute in minecraft:" + dimension + " run tp @p 0.5 80 0.5 -90 0");
                journey(test, world, dimension + "-terrain", 12, 80, 0, 1200);
            }
            test.runOnClient(mc -> {
                var screen = new com.damia.movrand.gui.ConfigScreen(); mc.gui.setScreen(screen);
                for (int i = 0; i < 5; i++) screen.keyPressed(new net.minecraft.client.input.KeyEvent(org.lwjgl.glfw.GLFW.GLFW_KEY_TAB, 0, 0));
            });
            test.takeScreenshot("terrain-explorer-panel");
        } finally {
            test.runOnClient(mc -> MovRand.controller().stop(mc, "Explorer test cleanup"));
            try { Files.write(Path.of(System.getenv().getOrDefault("MOVRAND_EXPLORER_REPORT", "terrain-explorer-results.csv")), rows); }
            catch (java.io.IOException e) { throw new RuntimeException(e); }
        }
    }

    private static void boatCrossing(ClientGameTestContext test, TestSingleplayerContext world) {
            setup(test, world);
            world.getServer().runCommand("fill -8 -1 -8 48 -1 8 stone");
            world.getServer().runCommand("fill -8 0 -8 40 0 8 water");
            world.getServer().runCommand("fill 21 1 -8 48 4 8 air");
            world.getServer().runCommand("fill 41 0 -8 48 0 8 stone");
            world.getServer().runCommand("give @p oak_boat");
            journey(test, world, "water-boat-shore", 45, 1, 0, 2400);
        test.runOnClient(mc -> require(mc.player.getInventory().contains(stack -> stack.is(net.minecraft.world.item.Items.OAK_BOAT)), "Expedition boat was not recovered"));
    }

    private static void setup(ClientGameTestContext test, TestSingleplayerContext world) {
        world.getServer().runCommand("execute in minecraft:overworld run tp @p 0.5 1 0.5 -90 0");
        TerrainGameTest.setup(test, world);
        world.getServer().runCommand("kill @e[type=oak_boat]");
        world.getServer().runCommand("give @p cooked_beef 64");
        world.getServer().runCommand("give @p water_bucket");
        world.getServer().runCommand("effect give @p saturation 1 4 true");
        world.getServer().runCommand("tp @p 0.5 1 0.5 -90 0");
        world.getConnection().waitForClientboundPackets();
        test.waitTicks(20);
        test.runOnClient(mc -> {
            var c = MovRand.config();
            c.explorerEnabled = c.explorerCoordinates = true;
            c.destroyerEnabled = c.builderEnabled = c.areaEnabled = c.gotoEnabled = false;
            c.stopOnDamage = c.stopOnNearbyPlayer = c.fleeFromHostiles = false;
            c.containerScanEnabled = true; c.containerThreshold = 1; c.containerReaction = Config.Reaction.NOTHING;
            c.explorerPause = false; c.explorerLogSec = 5; c.explorerTargetSec = 90;
            c.logFolder = Path.of(System.getenv().getOrDefault("MOVRAND_EXPLORER_REPORT", "terrain-explorer-results.csv")).toAbsolutePath().getParent().resolve("explorer-journal").toString();
            c.clampAll();
        });
    }

    private static void start(ClientGameTestContext test, int x, int y, int z) {
        test.runOnClient(mc -> { var c = MovRand.config(); c.explorerX = x; c.explorerY = y; c.explorerZ = z; MovRand.controller().start(mc); });
    }
    private static void journey(ClientGameTestContext test, TestSingleplayerContext world, String name, int x, int y, int z, int limit) {
        world.getConnection().waitForClientboundPackets(); test.waitTicks(15);
        start(test, x, y, z); await(test, name, limit, new BlockPos(x, y, z));
    }
    private static void await(ClientGameTestContext test, String name, int limit, BlockPos goal) {
        try {
            int[] samples = {0};
            test.waitFor(mc -> {
                var e = MovRand.controller().explorer;
                if (++samples[0] % 100 == 0) MovRand.LOG.info("EXPLORER {} {} {} {}", name, mc.player.position(), e.status, e.metrics());
                require(e.phase != TerrainExplorer.Phase.BLOCKED, name + ": " + e.status);
                require(MovRand.config().movementEnabled || e.phase == TerrainExplorer.Phase.DONE, name + ": " + MovRand.controller().lastReason);
                return e.phase == TerrainExplorer.Phase.DONE;
            }, limit);
            test.runOnClient(mc -> {
                var e = MovRand.controller().explorer;
                require(goal == null || mc.player.blockPosition().equals(goal), "Wrong XYZ arrival: " + mc.player.blockPosition() + " wanted " + goal);
                require(mc.player.getHealth() >= 20, name + " caused damage");
                rows.add(String.format(Locale.ROOT, "%s,%d,%.3f,%.3f,%.1f,%d,%d,%d,%.4f,%.4f", name, e.ticks, e.distance, TerrainExplorer.speed(e.distance, e.ticks), mc.player.getHealth(), e.mined, e.placed, e.failures, e.cpuNanos / 1e6 / Math.max(1, e.calls), e.maxCpuNanos / 1e6));
                MovRand.LOG.info("EXPLORER PASS {} {}", name, e.metrics());
            });
        } catch (Throwable error) {
            test.takeScreenshot("explorer-failure-" + name);
            throw error;
        }
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
