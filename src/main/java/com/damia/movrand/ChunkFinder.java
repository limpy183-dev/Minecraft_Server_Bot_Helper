package com.damia.movrand;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.AABB;

import java.util.*;

/** Estimates generation age from received palettes; never interprets a first client visit as new. */
public final class ChunkFinder {
    public static final ChunkFinder INSTANCE = new ChunkFinder();

    public enum Finding {
        NEW("Likely new"), OLD("Likely previously loaded"), UNKNOWN("Uncertain / no evidence");
        public final String label;
        Finding(String label) { this.label = label; }
        public boolean shown(Config.ChunkDisplay mode) {
            return mode == Config.ChunkDisplay.NEW ? this == NEW : this == OLD;
        }
    }

    private record Entry(LevelChunk chunk, Finding finding) {}
    private final Map<Long, Entry> chunks = new HashMap<>();
    private ClientLevel world;
    private int discovery, ticks;
    public int rendered;

    public void register() {
        ClientChunkEvents.CHUNK_LOAD.register((level, chunk) -> {
            if (!MovRand.config().chunkFinderEnabled) return;
            sync(level);
            track(chunk);
        });
        ClientChunkEvents.CHUNK_UNLOAD.register((level, chunk) -> {
            if (level == world) chunks.remove(chunk.getPos().pack());
        });
        ClientTickEvents.END_CLIENT_TICK.register(mc -> tick(mc, MovRand.config()));
        LevelExtractionEvents.END_EXTRACTION.register(context -> render(Minecraft.getInstance(), MovRand.config()));
    }

    public void reset() {
        chunks.clear(); discovery = ticks = rendered = 0;
    }

    private void sync(ClientLevel level) {
        if (world != level) { reset(); world = level; }
    }

    private void track(LevelChunk chunk) {
        var key = chunk.getPos().pack();
        Entry old = chunks.get(key);
        if (old != null && old.chunk == chunk || old == null && chunks.size() >= 8192) return;
        boolean vanilla = world.dimension().equals(Level.OVERWORLD) || world.dimension().equals(Level.NETHER)
                || world.dimension().equals(Level.END);
        chunks.put(key, new Entry(chunk, vanilla ? classify(chunk.getSections()) : Finding.UNKNOWN));
    }

    public Finding finding(int x, int z) {
        Entry e = chunks.get(ChunkPos.pack(x, z));
        return e == null ? Finding.UNKNOWN : e.finding;
    }

    public String summary() {
        int fresh = 0, old = 0;
        for (Entry e : chunks.values()) {
            if (e.finding == Finding.NEW) fresh++;
            else if (e.finding == Finding.OLD) old++;
        }
        return fresh + " / " + old + " / " + (chunks.size() - fresh - old);
    }

    private void tick(Minecraft mc, Config cfg) {
        sync(mc.level);
        if (!cfg.chunkFinderEnabled || world == null || mc.player == null) {
            if (!chunks.isEmpty()) reset();
            return;
        }
        if (mc.isPaused()) return;
        // Catch chunks already loaded when enabling, with fixed work and no forced loads.
        int radius = Math.min(33, mc.options.getEffectiveRenderDistance() + 1), side = radius * 2 + 1;
        int cx = mc.player.blockPosition().getX() >> 4, cz = mc.player.blockPosition().getZ() >> 4;
        LevelChunk here = world.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false);
        if (here != null) track(here);
        for (int i = 0; i < 16; i++) {
            int index = discovery % (side * side);
            discovery = (index + 1) % (side * side);
            LevelChunk chunk = world.getChunkSource().getChunk(cx + index % side - radius,
                    cz + index / side - radius, ChunkStatus.FULL, false);
            if (chunk != null) track(chunk);
        }
        if (++ticks % 20 == 0) {
            chunks.values().removeIf(e -> world.getChunkSource().getChunk(e.chunk.getPos().x(),
                    e.chunk.getPos().z(), ChunkStatus.FULL, false) != e.chunk);
        }
    }

    // ponytail: palette clues estimate generation/save history, not player visits. Exact exploration
    // requires server-maintained history. Modified/optimised/upgraded chunks can defeat these clues.
    static Finding classify(LevelChunkSection[] sections) {
        int fresh = 0, compacted = 0;
        boolean unusedPlains = false;
        if (sections.length > 0 && sections[0] != null) {
            var biomes = sections[0].getBiomes();
            if (biomes.maybeHas(b -> b.is(Biomes.PLAINS))) {
                unusedPlains = true;
                for (int y = 0; y < 4 && unusedPlains; y++)
                    for (int z = 0; z < 4 && unusedPlains; z++)
                        for (int x = 0; x < 4; x++)
                            if (biomes.get(x, y, z).is(Biomes.PLAINS)) { unusedPlains = false; break; }
            }
        }
        for (LevelChunkSection section : sections) {
            if (section == null || section.hasOnlyAir()) continue;
            var states = section.getStates();
            // Global palettes have registry order, not generation/insertion order.
            if (states.bitsPerEntry() > 8) continue;
            BlockState[] first = {null};
            states.forEachInPalette(state -> { if (first[0] == null) first[0] = state; });
            BlockState origin = section.getBlockState(0, 0, 0);
            if (first[0] == null || origin.isAir()) continue;
            if (first[0].isAir()) fresh++;
            else if (first[0] == origin) compacted++;
        }
        return evidence(fresh, compacted, unusedPlains);
    }

    static Finding evidence(int fresh, int compacted, boolean unusedPlains) {
        // A generated palette starts with air/plains. Disk loading rebuilds it from used values.
        // Require several sections; one block edit must not decide an entire chunk's age.
        if (unusedPlains || fresh >= 3) return Finding.NEW;
        if (fresh == 0 && compacted >= 3) return Finding.OLD;
        return Finding.UNKNOWN;
    }

    private void render(Minecraft mc, Config cfg) {
        rendered = 0;
        if (!cfg.chunkFinderEnabled || world == null || mc.level != world || mc.player == null) return;
        var camera = mc.gameRenderer.mainCamera().position();
        var selected = new ArrayList<Entry>();
        for (Entry e : chunks.values()) if (e.finding.shown(cfg.chunkFinderDisplay)
                && distanceSq(e, camera.x, camera.z) <= (double) cfg.chunkFinderDistance * cfg.chunkFinderDistance)
            selected.add(e);
        selected.sort(Comparator.comparingDouble(e -> distanceSq(e, camera.x, camera.z)));
        int rgb = cfg.chunkFinderDisplay == Config.ChunkDisplay.NEW ? 0x4ADE80 : 0xFBBF24;
        var style = GizmoStyle.strokeAndFill(0xBB000000 | rgb, 1.5f, cfg.chunkFinderOpacity << 24 | rgb);
        try (var collector = mc.levelExtractor.collectPerFrameMainThreadGizmos()) {
            for (Entry e : selected) {
                if (rendered >= cfg.chunkFinderMaxOverlays) break;
                var p = e.chunk.getPos();
                var box = Gizmos.cuboid(new AABB(p.x() * 16.0 + .02, world.getMinY(), p.z() * 16.0 + .02,
                        p.x() * 16.0 + 15.98, world.getMaxY() + 1, p.z() * 16.0 + 15.98), style);
                if (cfg.chunkFinderThroughWalls) box.setAlwaysOnTop();
                rendered++;
            }
        }
    }

    private static double distanceSq(Entry e, double x, double z) {
        var p = e.chunk.getPos();
        double dx = p.x() * 16.0 + 8 - x, dz = p.z() * 16.0 + 8 - z;
        return dx * dx + dz * dz;
    }

    public static void main(String[] args) {
        assert evidence(0, 0, false) == Finding.UNKNOWN;
        assert evidence(2, 0, false) == Finding.UNKNOWN;
        assert evidence(3, 0, false) == Finding.NEW;
        assert evidence(0, 2, false) == Finding.UNKNOWN;
        assert evidence(0, 3, false) == Finding.OLD;
        assert evidence(1, 20, false) == Finding.UNKNOWN;
        assert evidence(0, 20, true) == Finding.NEW;
        assert Finding.NEW.shown(Config.ChunkDisplay.NEW) && !Finding.NEW.shown(Config.ChunkDisplay.OLD);
        assert Finding.OLD.shown(Config.ChunkDisplay.OLD) && !Finding.OLD.shown(Config.ChunkDisplay.NEW);
        for (var mode : Config.ChunkDisplay.values()) assert !Finding.UNKNOWN.shown(mode);
        Config cfg = new Config();
        cfg.chunkFinderEnabled = true; cfg.chunkFinderDisplay = Config.ChunkDisplay.OLD;
        var gson = new com.google.gson.Gson();
        Config copy = gson.fromJson(gson.toJson(cfg), Config.class);
        assert copy.chunkFinderEnabled && copy.chunkFinderDisplay == Config.ChunkDisplay.OLD;
        cfg.chunkFinderDisplay = null; cfg.chunkFinderOpacity = -1;
        cfg.chunkFinderDistance = 9999; cfg.chunkFinderMaxOverlays = 0; cfg.clampAll();
        assert cfg.chunkFinderDisplay == Config.ChunkDisplay.NEW && cfg.chunkFinderOpacity == 0
                && cfg.chunkFinderDistance == 512 && cfg.chunkFinderMaxOverlays == 1;
        System.out.println("ChunkFinder: evidence thresholds, unknowns, display filters and config round-trip passed");
    }
}
