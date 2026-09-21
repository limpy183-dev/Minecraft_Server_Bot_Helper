package com.damia.movrand;

import com.damia.movrand.gui.ConfigScreen;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.client.input.KeyEvent;
import org.lwjgl.glfw.GLFW;

/** Checks actual palette compaction, then registered tracking and rendering in a live client. */
public final class ChunkFinderGameTest implements FabricClientGameTest {
    @Override public void runTest(ClientGameTestContext test) {
        try {
        try (var world = test.worldBuilder().create()) {
            test.runOnClient(mc -> {
                var factory = mc.level.palettedContainerFactory();
                var fresh = new LevelChunkSection(factory);
                for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++)
                    fresh.setBlockState(x, y, z, Blocks.STONE.defaultBlockState());
                require(ChunkFinder.classify(new LevelChunkSection[]{fresh, fresh, fresh}) == ChunkFinder.Finding.NEW,
                        "Generated air-first palette was not detected");
                var restored = PalettedContainer.unpack(factory.blockStatesStrategy(),
                        fresh.getStates().pack(factory.blockStatesStrategy())).getOrThrow();
                var old = new LevelChunkSection(restored, fresh.getBiomes());
                old.recalcBlockCounts();
                require(ChunkFinder.classify(new LevelChunkSection[]{old, old, old}) == ChunkFinder.Finding.OLD,
                        "Disk-compacted palette was not detected");
                require(ChunkFinder.classify(new LevelChunkSection[]{new LevelChunkSection(factory)})
                        == ChunkFinder.Finding.UNKNOWN, "Empty chunk should be uncertain");
                require(ChunkFinder.classify(new LevelChunkSection[]{fresh, old, old, old})
                        == ChunkFinder.Finding.UNKNOWN, "Mixed evidence should be uncertain");
                MovRand.config().movementEnabled = false;
                MovRand.config().susEnabled = false;
                MovRand.config().chunkFinderEnabled = true;
                MovRand.config().chunkFinderDisplay = Config.ChunkDisplay.NEW;
            });
            // Three deep sections with identical contents, but different palette histories.
            test.runOnClient(mc -> {
                var chunk = mc.level.getChunk(mc.player.blockPosition());
                var factory = mc.level.palettedContainerFactory();
                for (int i = 0; i < chunk.getSections().length; i++) {
                    var section = new LevelChunkSection(factory);
                    if (i < 3) for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++)
                        section.setBlockState(x, y, z, Blocks.STONE.defaultBlockState());
                    chunk.getSections()[i] = section;
                }
                ChunkFinder.INSTANCE.reset();
            });
            test.waitFor(mc -> ChunkFinder.INSTANCE.rendered > 0, 200);
            test.takeScreenshot("chunk-finder-new");
            test.runOnClient(mc -> {
                var p = mc.player.blockPosition();
                require(ChunkFinder.INSTANCE.finding(p.getX() >> 4, p.getZ() >> 4) == ChunkFinder.Finding.NEW,
                        "Already-loaded current chunk was not discovered");
                MovRand.config().chunkFinderDisplay = Config.ChunkDisplay.OLD;
                // Remove unrelated results and compact every section of the current chunk.
                var chunk = mc.level.getChunk(p);
                var strategy = mc.level.palettedContainerFactory().blockStatesStrategy();
                for (int i = 0; i < chunk.getSections().length; i++) {
                    var section = chunk.getSections()[i];
                    var packed = PalettedContainer.unpack(strategy, section.getStates().pack(strategy)).getOrThrow();
                    chunk.getSections()[i] = new LevelChunkSection(packed, section.getBiomes());
                    chunk.getSections()[i].recalcBlockCounts();
                }
                ChunkFinder.INSTANCE.reset();
            });
            test.waitFor(mc -> ChunkFinder.INSTANCE.rendered > 0, 200);
            test.runOnClient(mc -> {
                var p = mc.player.blockPosition();
                require(ChunkFinder.INSTANCE.finding(p.getX() >> 4, p.getZ() >> 4) == ChunkFinder.Finding.OLD,
                        "Compacted chunk was not tracked as old");
                var screen = new ConfigScreen();
                mc.gui.setScreen(screen);
                for (int i = 0; i < 9; i++) screen.keyPressed(new KeyEvent(GLFW.GLFW_KEY_TAB, 0, 0));
            });
            test.takeScreenshot("chunk-finder-menu");
            test.runOnClient(mc -> MovRand.config().chunkFinderEnabled = false);
            test.waitTicks(5);
            test.runOnClient(mc -> require(ChunkFinder.INSTANCE.rendered == 0
                    && ChunkFinder.INSTANCE.summary().equals("0 / 0 / 0"), "Disabling retained overlays/results"));
        }
        test.waitTicks(5);
        test.runOnClient(mc -> require(ChunkFinder.INSTANCE.summary().equals("0 / 0 / 0"), "Disconnect retained results"));
        MovRand.LOG.info("CHUNK FINDER PASS: real palettes, compaction, unknowns, tracking, both overlays, menu and cleanup");
        } finally {
            if (baritone.Baritone.getExecutor() instanceof java.util.concurrent.ExecutorService executor) executor.shutdownNow();
        }
    }

    private static void require(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
}
