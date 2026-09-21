package com.damia.movrand;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import java.nio.file.*;
import java.util.*;

/** Creates playable fixture and seeded natural worlds; exercises the production scanner and renderer. */
public final class SusChunkGameTest implements FabricClientGameTest {
    private final List<String> rows = new ArrayList<>(List.of("scenario,expected,actual,score,detail"));
    private final SusChunkFinder finder = SusChunkFinder.INSTANCE;

    @Override public void runTest(ClientGameTestContext test) {
        try {
            try (var world = test.worldBuilder().adjustSettings(s -> s.setName("Sus Chunk Finder - Test Lab")).create()) {
                configure(test);
                world.getServer().runCommand("gamemode spectator @p");
                world.getServer().runCommand("tp @p 24 48 64 180 35");
                world.getServer().runCommand("fill -16 0 0 63 0 31 stone");
                world.getServer().runCommand("fill 2 1 2 5 1 3 chest");
                world.getServer().runCommand("fill 18 1 2 25 1 2 torch");
                world.getServer().runCommand("fill 34 0 2 41 0 9 farmland[moisture=7]");
                world.getServer().runCommand("fill 34 1 2 41 1 9 wheat[age=7]");
                world.getServer().runCommand("fill 50 1 2 57 1 9 glass");
                world.getServer().runCommand("setblock -8 1 8 beacon");
                world.getServer().runCommand("fill 2 1 18 9 1 18 glowstone");
                world.getServer().runCommand("setblock 20 1 20 chest");
                world.getServer().runCommand("fill 34 1 18 41 1 25 sea_lantern");
                world.getServer().runCommand("fill 50 1 18 57 8 25 stone");
                world.getConnection().waitForClientboundPackets();
                // Start through the registered client tick, without movement, manual rescan or other watchers.
                test.waitFor(mc -> finder.finding(-1, 0) != null, 1200);
                test.runOnClient(mc -> require(!MovRand.config().movementEnabled && !MovRand.config().containerScanEnabled,
                        "Finder enabled movement or the container watcher"));
                settle(test);
                check(test, "storage-room", 0, 0, true);
                check(test, "torch-cluster", 1, 0, true);
                check(test, "crop-farm", 2, 0, true);
                check(test, "glass-floor", 3, 0, true);
                check(test, "beacon-negative-coordinate", -1, 0, true);
                check(test, "overworld-glowstone", 0, 1, true);
                check(test, "lone-loot-chest", 1, 1, false);
                check(test, "natural-sea-lanterns", 2, 1, false);
                check(test, "plain-stone", 3, 1, false);
                test.takeScreenshot("sus-lab-red-overlay");
                test.runOnClient(mc -> require(finder.rendered >= 6, "Overlay failed to submit the six suspicious chunks"));
                thresholds(test, world);

                test.runOnClient(mc -> {
                    finder.ignore(0, 0);
                    require(finder.findings().stream().noneMatch(f -> f.x() == 0 && f.z() == 0), "Ignored chunk still displayed");
                    finder.clearIgnored();
                    require(finder.findings().stream().anyMatch(f -> f.x() == 0 && f.z() == 0), "Restore lost detection");
                    MovRand.config().susScore = 20; finder.rescan();
                });
                settle(test); check(test, "strict-single-rule", 0, 0, false);
                test.runOnClient(mc -> { MovRand.config().susScore = 8; MovRand.config().susLights = false; finder.rescan(); });
                settle(test); check(test, "lights-toggle", 1, 0, false);
                test.runOnClient(mc -> { MovRand.config().susLights = true; finder.rescan(); });
                settle(test);
                world.getServer().runCommand("fill 2 1 2 5 1 3 air");
                world.getConnection().waitForClientboundPackets();
                test.waitFor(mc -> finder.finding(0, 0) == null, 600);
                check(test, "removed-storage-auto-rescan", 0, 0, false);
                world.getServer().runCommand("setblock 4 1 4 beacon");
                world.getConnection().waitForClientboundPackets();
                test.waitFor(mc -> finder.finding(0, 0) != null, 600);
                check(test, "added-block-auto-rescan", 0, 0, true);
                // Rotation and section boundary regressions: a 4x16 row pattern spanning Y=15/16.
                world.getServer().runCommand("fill 50 1 2 57 1 9 air");
                world.getServer().runCommand("fill 50 16 0 53 16 15 glass");
                world.getConnection().waitForClientboundPackets();
                settle(test); check(test, "rotated-pattern-section-boundary", 3, 0, true);
                test.runOnClient(mc -> { MovRand.config().susFullHeight = false; MovRand.config().susOpacity = 70; });
                test.waitTicks(5); test.takeScreenshot("sus-lab-evidence-height");
                test.runOnClient(mc -> { MovRand.config().susOverlay = false; });
                test.waitTicks(5); test.takeScreenshot("sus-lab-overlay-off");
                test.runOnClient(mc -> require(finder.rendered == 0, "Overlay toggle ignored"));
                test.runOnClient(mc -> { MovRand.config().susEnabled = false; });
                test.waitTicks(2);
                test.runOnClient(mc -> require(finder.tracked() == 0 && finder.pending() == 0, "Disabled scanner retains chunks"));
                test.runOnClient(mc -> { MovRand.config().susEnabled = true; MovRand.config().susOverlay = true; });
                settle(test); check(test, "enable-already-loaded", -1, 0, true);
                performance(test, "fixture");
                world.getServer().runCommand("tp @p 2048 48 2048");
                world.getConnection().waitForClientboundPackets();
                test.waitFor(mc -> finder.finding(-1, 0) == null, 400);
                check(test, "unloaded-result-removed", -1, 0, false);
                world.getServer().runCommand("tp @p 24 48 64 180 35");
                world.getConnection().waitForClientboundPackets();
                settle(test); check(test, "reloaded-result-restored", -1, 0, true);
                // Dense loaded map: 64 built chunks, each with two solid layers. Separate from labelled lab.
                world.getServer().runCommand("fill -64 32 80 63 33 207 white_concrete");
                world.getConnection().waitForClientboundPackets();
                world.getServer().runCommand("tp @p 0 60 144 180 35");
                world.getConnection().waitForClientboundPackets();
                settle(test);
                test.runOnClient(mc -> {
                    require(finder.findings().size() >= 64, "Dense map not scanned");
                    MovRand.config().susMaxOverlays = 3;
                });
                test.waitTicks(5);
                test.runOnClient(mc -> require(finder.rendered == 3, "Nearest overlay cap not honoured"));
                performance(test, "dense-64-chunks");
                test.takeScreenshot("sus-dense-overlay-cap");
                test.runOnClient(mc -> { MovRand.config().susMaxOverlays = 128; MovRand.config().susThroughWalls = false; });
                test.waitTicks(5); test.takeScreenshot("sus-dense-depth-tested");
                world.getServer().runCommand("tp @p 24 48 64 180 35");
                test.runOnClient(mc -> {
                    mc.setScreenAndShow(new com.damia.movrand.gui.ConfigScreen());
                });
                for (int i = 0; i < 10; i++) test.getInput().pressKey(org.lwjgl.glfw.GLFW.GLFW_KEY_TAB);
                test.waitTicks(3); test.takeScreenshot("sus-menu-section");
                test.runOnClient(mc -> mc.setScreenAndShow(null));
            }
            try (var world = test.worldBuilder().setUseConsistentSettings(false).adjustSettings(s -> {
                s.setName("Sus Chunk Finder - Natural Seed 20260910"); s.setSeed("20260910"); s.setAllowCommands(true);
            }).create()) {
                configure(test);
                world.getServer().runCommand("gamemode spectator @p");
                settle(test); naturalSample(test, "natural-overworld");
                world.getServer().runCommand("execute in minecraft:the_nether run tp @p 0 90 0");
                world.getConnection().waitForClientboundPackets();
                test.waitFor(mc -> mc.level.dimension().equals(Level.NETHER), 200);
                settle(test); naturalSample(test, "natural-nether");
                world.getServer().runCommand("execute in minecraft:the_nether run fill 0 80 0 15 80 15 glowstone");
                world.getConnection().waitForClientboundPackets();
                settle(test);
                test.runOnClient(mc -> {
                    require(mc.level.getBlockState(new BlockPos(0, 80, 0)).is(Blocks.GLOWSTONE), "Nether glowstone fixture missing");
                    var c = new SusChunkFinder.Counts();
                    var chunk = mc.level.getChunk(0, 0);
                    for (int i = 0; i < chunk.getSections().length; i++)
                        SusChunkFinder.scanSection(chunk.getSections()[i], chunk.getMinY() + i * 16, true, MovRand.config(), c);
                    require(c.lights == 0, "Natural Nether glowstone counted as artificial lighting");
                    rows.add("nether-glowstone,false,false,0,256 blocks plus natural glowstone excluded");
                });
                world.getServer().runCommand("execute in minecraft:the_end run tp @p 0 90 0");
                world.getConnection().waitForClientboundPackets();
                test.waitFor(mc -> mc.level.dimension().equals(Level.END), 200);
                settle(test); naturalSample(test, "natural-end");
                test.runOnClient(mc -> require(finder.finding(-1, 0) == null, "Prior-world beacon leaked into End"));
            }
            Files.write(Path.of("sus-chunk-results.csv"), rows);
            MovRand.LOG.info("SUS PASS: {} observations; reports and playable maps in the clientGameTest run directory", rows.size() - 1);
        } catch (Exception e) { throw new RuntimeException(e); }
        finally {
            if (baritone.Baritone.getExecutor() instanceof java.util.concurrent.ExecutorService executor) executor.shutdownNow();
        }
    }

    private void configure(ClientGameTestContext test) {
        test.runOnClient(mc -> {
            Config cfg = new Config(); cfg.susEnabled = true; cfg.movementEnabled = false;
            cfg.containerScanEnabled = false; cfg.susRescanTicks = 200; cfg.clampAll();
            MovRand.replaceConfig(cfg); mc.options.renderDistance().set(8);
        });
    }

    private void thresholds(ClientGameTestContext test, TestSingleplayerContext world) {
        // Change every minimum without calling rescan: old findings must be invalidated automatically.
        test.runOnClient(mc -> {
            Config cfg = MovRand.config();
            cfg.susStorageMin = 12; cfg.susLightsMin = 9; cfg.susFarmMin = 65; cfg.susPatternMin = 65;
        });
        test.waitTicks(2);
        check(test, "raised-storage-clears-old-result", 0, 0, false);
        check(test, "raised-lights-clears-old-result", 1, 0, false);
        check(test, "raised-farm-clears-old-result", 2, 0, false);
        check(test, "raised-pattern-clears-old-result", 3, 0, false);
        settle(test);
        check(test, "lights-below-minimum", 1, 0, false);
        check(test, "farm-below-minimum", 2, 0, false);
        check(test, "pattern-below-minimum", 3, 0, false);
        test.runOnClient(mc -> {
            Config cfg = MovRand.config(); cfg.susLightsMin = 8; cfg.susFarmMin = 64; cfg.susPatternMin = 64;
        });
        settle(test);
        check(test, "lights-exact-minimum", 1, 0, true);
        check(test, "farm-exact-minimum", 2, 0, true);
        check(test, "pattern-exact-minimum", 3, 0, true);
        for (String block : List.of("chest", "ender_chest", "red_shulker_box")) {
            world.getServer().runCommand("fill 0 1 32 15 1 32 air");
            world.getServer().runCommand("setblock 0 1 32 " + block);
            world.getConnection().waitForClientboundPackets();
            settle(test); check(test, block + "-1-of-12", 0, 2, false);
            world.getServer().runCommand("fill 0 1 32 10 1 32 " + block);
            world.getConnection().waitForClientboundPackets();
            settle(test); check(test, block + "-11-of-12", 0, 2, false);
            world.getServer().runCommand("setblock 11 1 32 " + block);
            world.getConnection().waitForClientboundPackets();
            settle(test); check(test, block + "-12-of-12", 0, 2, true);
            test.runOnClient(mc -> require(finder.finding(0, 2).reasons().equals(List.of("12 storage/machinery blocks")),
                    "Container counted under multiple rules"));
            world.getServer().runCommand("setblock 11 1 32 air");
            world.getConnection().waitForClientboundPackets();
            test.waitFor(mc -> finder.finding(0, 2) == null, 600);
            check(test, block + "-removal-below-minimum", 0, 2, false);
        }
        // A long row does not let scattered material elsewhere meet the building minimum.
        world.getServer().runCommand("fill 0 1 32 15 1 32 air");
        world.getServer().runCommand("fill 0 1 32 7 1 32 glass");
        world.getServer().runCommand("fill 0 1 34 15 1 37 glass");
        test.runOnClient(mc -> MovRand.config().susPatternMin = 65);
        world.getConnection().waitForClientboundPackets();
        settle(test); check(test, "disconnected-building-patches", 0, 2, false);
        world.getServer().runCommand("fill 0 1 32 15 1 37 air");
        test.runOnClient(mc -> {
            Config cfg = MovRand.config(); cfg.susStorageMin = 4; cfg.susFarmMin = 32; cfg.susPatternMin = 48;
        });
        settle(test);
    }
    private void settle(ClientGameTestContext test) {
        test.waitTicks(80);
        test.runOnClient(mc -> finder.rescan());
        test.waitFor(mc -> finder.scans > 0 && finder.pending() == 0, 1200);
        test.waitTicks(5);
    }
    private void check(ClientGameTestContext test, String name, int x, int z, boolean expected) {
        test.runOnClient(mc -> {
            var f = finder.finding(x, z);
            require((f != null) == expected, name + ": " + f);
            rows.add(name + "," + expected + "," + (f != null) + "," + (f == null ? 0 : f.score()) + ",\""
                    + (f == null ? "no flag" : String.join("; ", f.reasons())) + "\"");
            MovRand.LOG.info("SUS CASE {}: {}", name, f);
        });
    }
    private void naturalSample(ClientGameTestContext test, String label) {
        test.runOnClient(mc -> {
            require(finder.tracked() >= 100, "Insufficient natural terrain sample");
            require(finder.findings().isEmpty(), "Natural seed sample falsely flagged: " + finder.findings());
            rows.add(label + ",baseline," + finder.findings().size() + ",0," + finder.tracked() + " loaded chunks");
            for (var f : finder.findings()) rows.add(label + "-flag,baseline,true," + f.score() + ",\"" + f.summary() + "\"");
            MovRand.LOG.info("SUS BASELINE {}: {} / {} suspicious", label, finder.findings().size(), finder.tracked());
        });
        performance(test, label);
        test.takeScreenshot("sus-" + label);
    }
    private void performance(ClientGameTestContext test, String label) {
        List<Double> times = new ArrayList<>();
        test.runOnClient(mc -> finder.rescan());
        for (int i = 0; i < 120; i++) {
            test.waitTicks(1); test.runOnClient(mc -> times.add(finder.lastTickMs));
        }
        Collections.sort(times);
        double mean = times.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        String result = String.format(Locale.ROOT, "mean %.4f ms; p95 %.4f ms; max %.4f ms", mean, times.get(113), times.getLast());
        rows.add(label + "-speed,measurement,120,0," + result);
        test.runOnClient(mc -> rows.add(label + "-work,measurement," + finder.scans + ",0," + finder.skippedSections + " palette skips of " + finder.sections + " section visits"));
        MovRand.LOG.info("SUS PERFORMANCE {}: {}", label, result);
    }
    private static void require(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
}
