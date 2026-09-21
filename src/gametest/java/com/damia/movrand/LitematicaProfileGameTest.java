package com.damia.movrand;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.impl.client.gametest.world.TestWorldSaveImpl;
import java.nio.file.*;
import java.util.UUID;

/** Replay a supplied save, inventory and profile in an isolated copy. */
final class LitematicaProfileGameTest {
    static void run(ClientGameTestContext test) {
        Path source = Path.of(System.getenv("MOVRAND_BUILDER_WORLD")).toAbsolutePath().normalize();
        Path profile = Path.of(System.getenv("MOVRAND_BUILDER_PROFILE"));
        Path save = test.computeOnClient(mc -> mc.gameDirectory.toPath().resolve("saves/builder-profile-" + UUID.randomUUID()));
        try {
            try (var paths = Files.walk(source)) {
                for (Path p : paths.toList()) {
                    Path relative = source.relativize(p);
                    if (relative.startsWith("baritone") || p.getFileName().toString().equals("session.lock")) continue;
                    Path dest = save.resolve(relative.toString());
                    if (Files.isDirectory(p)) Files.createDirectories(dest); else Files.copy(p, dest);
                }
            }
            try (var players = Files.list(source.resolve("players/data"))) {
                Path player = players.filter(p -> p.toString().endsWith(".dat")).findFirst().orElseThrow();
                UUID id = test.computeOnClient(mc -> mc.getUser().getProfileId());
                Files.copy(player, save.resolve("players/data/" + id + ".dat"), StandardCopyOption.REPLACE_EXISTING);
            }
            Path profiles = save.getParent().getParent().resolve("config/movrand-profiles");
            Files.createDirectories(profiles);
            Files.copy(profile, profiles.resolve("Other smp.json"), StandardCopyOption.REPLACE_EXISTING);
            try (var world = new TestWorldSaveImpl(test, save).open()) {
                world.getConnection().waitForClientboundPackets();
                test.runOnClient(mc -> {
                    LitematicaBuilderGameTest.checkTransforms();
                    Config cfg = Config.loadProfile("Other smp");
                    if (cfg == null) throw new AssertionError("Missing profile");
                    MovRand.replaceConfig(cfg);
                    mc.options.pauseOnLostFocus = false;
                    MovRand.LOG.info("BUILDER PROFILE start save={} position={} inventory={} config={} origin={},{},{} mirror={}",
                            save, mc.player.position(), mc.player.getInventory(), cfg.activeProfile, cfg.builderX, cfg.builderY, cfg.builderZ, cfg.builderMirror);
                    MovRand.controller().builder.load();
                    if (!MovRand.controller().builder.loaded()) throw new AssertionError(MovRand.controller().builder.status);
                    cfg.builderEnabled = true;
                    MovRand.controller().start(mc);
                });
                try {
                    int[] ticks = {0};
                    test.waitFor(mc -> {
                        var b = MovRand.controller().builder;
                        if (++ticks[0] % 100 == 0) MovRand.LOG.info("BUILDER PROFILE tick={} position={} target={} phase={} interactions={} verified={} status={} wait={}",
                                ticks[0], mc.player.position(), b.targetDescription(), b.phase, b.interactions, b.verified, b.status, b.interactionWait);
                        if (b.phase == LitematicaBuilder.Phase.BLOCKED) throw new AssertionError(b.status + " " + b.issues());
                        if (!MovRand.config().movementEnabled) throw new AssertionError("Movement stopped: " + MovRand.controller().lastReason);
                        return b.phase == LitematicaBuilder.Phase.DONE;
                    }, 24000);
                    MovRand.LOG.info("BUILDER PROFILE PASS");
                } finally {
                    var desired = test.computeOnClient(mc -> {
                        Config cfg = MovRand.config();
                        try { return LitematicPlan.read(Path.of(cfg.builderFile)).placed(new net.minecraft.core.BlockPos(cfg.builderX, cfg.builderY, cfg.builderZ),
                                net.minecraft.world.level.block.Rotation.values()[cfg.builderRotation], net.minecraft.world.level.block.Mirror.values()[cfg.builderMirror]); }
                        catch (java.io.IOException e) { throw new RuntimeException(e); }
                    });
                    world.getServer().runOnServer(s -> {
                        long exact = desired.stream().filter(c -> !c.state().isAir() && s.overworld().getBlockState(c.pos()) == c.state()).count();
                        MovRand.LOG.info("BUILDER PROFILE server exact={}/{}", exact, desired.stream().filter(c -> !c.state().isAir()).count());
                        desired.stream().filter(c -> !c.state().isAir() && s.overworld().getBlockState(c.pos()) != c.state()).forEach(c ->
                                MovRand.LOG.info("BUILDER PROFILE mismatch {} actual={} wanted={}", c.pos(), s.overworld().getBlockState(c.pos()), c.state()));
                    });
                    test.runOnClient(mc -> {
                        var b = MovRand.controller().builder;
                        MovRand.LOG.info("BUILDER PROFILE end interactions={} verified={} status={} issues={}", b.interactions, b.verified, b.status, b.issues());
                        MovRand.controller().stop(mc, "Builder profile replay complete");
                    });
                    test.takeScreenshot("builder-profile-result");
                }
            }
        } catch (Exception e) { throw new RuntimeException(e); }
        finally {
            if (baritone.Baritone.getExecutor() instanceof java.util.concurrent.ExecutorService executor) executor.shutdownNow();
        }
    }
}
