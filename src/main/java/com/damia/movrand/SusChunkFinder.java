package com.damia.movrand;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.AABB;

import java.util.*;

/** Read-only, client-thread scanner. No seed, server queries, or chunk generation. */
public final class SusChunkFinder {
    public static final SusChunkFinder INSTANCE = new SusChunkFinder();
    private static final int STRONG = 1, STORAGE = 2, LIGHT = 4, FARM = 8, BUILD = 16, GLOW = 32, GLASS = 64;
    private static final Map<Block, Integer> TYPES = new IdentityHashMap<>();
    private final LinkedHashMap<Long, Entry> chunks = new LinkedHashMap<>();
    private final ArrayDeque<Entry> queue = new ArrayDeque<>();
    private final Set<Long> ignored = new HashSet<>();
    private ClientLevel world;
    private Config config;
    private Rules rules;
    private Entry active;
    private int tick, discovery;
    public long scans, sections, skippedSections;
    public double lastTickMs, maxTickMs, totalScanMs;
    public int rendered;

    private record Rules(boolean player, boolean storage, boolean lights, boolean farms, boolean patterns,
                         int storageMin, int lightsMin, int farmMin, int patternMin, int score) {
        static Rules of(Config cfg) {
            return new Rules(cfg.susPlayerBlocks, cfg.susStorage, cfg.susLights, cfg.susFarms, cfg.susPatterns,
                    cfg.susStorageMin, cfg.susLightsMin, cfg.susFarmMin, cfg.susPatternMin, cfg.susScore);
        }
    }

    public record Finding(int x, int z, int score, List<String> reasons, int minY, int maxY) {
        public String summary() { return "Chunk %d, %d: %s".formatted(x, z, String.join(", ", reasons)); }
    }

    private static final class Entry {
        final LevelChunk chunk;
        Counts counts = new Counts();
        Finding finding;
        int section, nextScan;
        boolean queued;
        Entry(LevelChunk chunk) { this.chunk = chunk; }
    }

    static final class Counts {
        int strong, storage, lights, farm, plane, glassPlane;
        int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        void add(int type, int y) {
            if ((type & STRONG) != 0) strong++;
            if ((type & STORAGE) != 0) storage++;
            if ((type & LIGHT) != 0) lights++;
            if ((type & FARM) != 0) farm++;
            if (type != 0) { minY = Math.min(minY, y); maxY = Math.max(maxY, y); }
        }
    }

    public void register() {
        ClientChunkEvents.CHUNK_LOAD.register((level, chunk) -> {
            if (level == world && MovRand.config().susEnabled) track(chunk);
        });
        ClientChunkEvents.CHUNK_UNLOAD.register((level, chunk) -> {
            if (level != world) return;
            Entry removed = chunks.remove(chunk.getPos().pack());
            if (removed != null) {
                queue.remove(removed);
                if (active == removed) active = null;
            }
        });
        ClientTickEvents.END_CLIENT_TICK.register(mc -> tick(mc, MovRand.config()));
        LevelExtractionEvents.END_EXTRACTION.register(context -> render(Minecraft.getInstance(), MovRand.config()));
    }

    public void reset() {
        chunks.clear(); queue.clear(); ignored.clear(); active = null;
        discovery = tick = 0;
        scans = sections = skippedSections = 0;
        lastTickMs = maxTickMs = totalScanMs = 0;
        rendered = 0;
        rules = null;
    }

    public void rescan() {
        active = null; queue.clear();
        for (Entry e : chunks.values()) {
            e.finding = null; e.counts = new Counts(); e.section = 0; e.queued = false;
            enqueue(e);
        }
    }

    public void ignore(int x, int z) {
        if (ignored.size() < 8192) ignored.add(net.minecraft.world.level.ChunkPos.pack(x, z));
    }
    public void clearIgnored() { ignored.clear(); }
    public int ignoredCount() { return ignored.size(); }
    public int tracked() { return chunks.size(); }
    public int pending() { return queue.size() + (active == null ? 0 : 1); }
    public Finding finding(int x, int z) {
        long key = net.minecraft.world.level.ChunkPos.pack(x, z);
        Entry e = ignored.contains(key) ? null : chunks.get(key);
        return e == null ? null : e.finding;
    }
    public List<Finding> findings() {
        List<Finding> found = new ArrayList<>();
        for (var item : chunks.entrySet())
            if (item.getValue().finding != null && !ignored.contains(item.getKey())) found.add(item.getValue().finding);
        return found;
    }

    private void enqueue(Entry e) {
        if (!e.queued && e != active) { e.queued = true; queue.addLast(e); }
    }
    private void track(LevelChunk chunk) {
        long key = chunk.getPos().pack();
        Entry old = chunks.get(key);
        if (old != null && old.chunk == chunk) return;
        if (old != null) { queue.remove(old); if (active == old) active = null; }
        // Client render distance is bounded; also cap references for unusual server/mod combinations.
        if (old == null && chunks.size() >= 8192) return;
        Entry e = new Entry(chunk); chunks.put(key, e); enqueue(e);
    }

    public void tick(Minecraft mc, Config cfg) {
        if (world != mc.level || config != cfg) { reset(); world = mc.level; config = cfg; }
        if (!cfg.susEnabled || world == null || mc.player == null) {
            if (!chunks.isEmpty() || !ignored.isEmpty()) reset();
            return;
        }
        if (mc.isPaused()) return;
        Rules current = Rules.of(cfg);
        if (!current.equals(rules)) { rescan(); rules = current; }
        long start = System.nanoTime();
        long deadline = start + cfg.susBudgetMicros * 1000L;
        tick++;
        // Catch already-loaded chunks when enabling. Fixed work per tick, never force a load.
        int radius = Math.min(33, mc.options.getEffectiveRenderDistance() + 1), side = radius * 2 + 1;
        int cx = mc.player.blockPosition().getX() >> 4, cz = mc.player.blockPosition().getZ() >> 4;
        LevelChunk here = world.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false);
        if (here != null) track(here);
        for (int i = 0; i < 64; i++) {
            int index = discovery % (side * side);
            discovery = (index + 1) % (side * side);
            LevelChunk chunk = world.getChunkSource().getChunk(cx + index % side - radius,
                    cz + index / side - radius, ChunkStatus.FULL, false);
            if (chunk != null) track(chunk);
        }
        if (tick % 20 == 0) {
            // The client cache can replace slots on teleports without an unload event for every old entry.
            for (var entries = chunks.values().iterator(); entries.hasNext();) {
                Entry e = entries.next();
                var pos = e.chunk.getPos();
                if (world.getChunkSource().getChunk(pos.x(), pos.z(), ChunkStatus.FULL, false) != e.chunk) {
                    entries.remove(); queue.remove(e);
                    if (active == e) active = null;
                } else if (tick >= e.nextScan) enqueue(e);
            }
        }
        int steps = 0;
        while (steps++ < 64 && System.nanoTime() < deadline) {
            if (active == null) {
                active = queue.pollFirst();
                if (active == null) break;
                active.queued = false;
            }
            Entry e = active;
            var ss = e.chunk.getSections();
            if (e.section < ss.length) {
                LevelChunkSection section = ss[e.section];
                int baseY = e.chunk.getMinY() + e.section++ * 16;
                sections++;
                if (!scanSection(section, baseY, world.dimension().equals(Level.NETHER), cfg, e.counts)) skippedSections++;
            }
            if (e.section == ss.length) {
                e.finding = evaluate(e.chunk.getPos().x(), e.chunk.getPos().z(), e.counts, cfg);
                scans++; e.nextScan = tick + cfg.susRescanTicks;
                e.section = 0; e.counts = new Counts(); active = null;
            }
        }
        lastTickMs = (System.nanoTime() - start) / 1e6;
        totalScanMs += lastTickMs; maxTickMs = Math.max(maxTickMs, lastTickMs);
    }

    private static int type(BlockState state, boolean nether, Config cfg) {
        int type = TYPES.computeIfAbsent(state.getBlock(), block -> {
            var id = BuiltInRegistries.BLOCK.getKey(block);
            return id.getNamespace().equals("minecraft") ? classify(id.getPath()) : 0;
        });
        if ((type & GLOW) != 0) type = nether ? 0 : LIGHT;
        int mask = (cfg.susPlayerBlocks ? STRONG : 0) | (cfg.susStorage ? STORAGE : 0)
                | (cfg.susLights ? LIGHT : 0) | (cfg.susFarms ? FARM : 0) | (cfg.susPatterns ? BUILD | GLASS : 0);
        return type & mask;
    }

    static int classify(String id) {
        if (id.equals("beacon") || id.equals("conduit") || id.equals("enchanting_table")
                || id.equals("respawn_anchor")
                || id.endsWith("_concrete") || id.equals("tinted_glass") || id.equals("netherite_block")) return STRONG;
        // All containers share the storage minimum; rare containers must not bypass it via STRONG.
        if (Set.of("chest", "trapped_chest", "barrel", "hopper", "dispenser", "dropper", "crafter", "ender_chest").contains(id)
                || id.endsWith("shulker_box")) return STORAGE;
        if (id.equals("glowstone")) return GLOW;
        if (id.endsWith("torch") || id.endsWith("lantern") && !id.equals("sea_lantern")
                || id.equals("redstone_lamp") || id.equals("jack_o_lantern")) return LIGHT;
        if (id.equals("farmland")) return FARM;
        if (id.equals("glass") || id.endsWith("stained_glass")) return BUILD | GLASS;
        if (id.endsWith("_planks")
                || id.equals("cobblestone") || id.equals("stone_bricks") || id.equals("bricks")) return BUILD;
        return 0;
    }

    // ponytail: heuristic material/geometry rules cannot identify arbitrary stone builds or distinguish
    // villages from player copies. Exact comparison would require the seed AND matching generator data.
    static boolean scanSection(LevelChunkSection section, int baseY, boolean nether, Config cfg, Counts c) {
        if (section == null || section.hasOnlyAir() || !section.maybeHas(s -> type(s, nether, cfg) != 0)) return false;
        int[] layer = new int[256], connected = new int[256];
        for (int y = 0; y < 16; y++) {
            int[] columns = new int[16];
            for (int z = 0; z < 16; z++) {
                int run = 0;
                for (int x = 0; x < 16; x++) {
                    int t = type(section.getBlockState(x, y, z), nether, cfg);
                    c.add(t, baseY + y);
                    layer[z * 16 + x] = 0;
                    if ((t & BUILD) != 0) {
                        layer[z * 16 + x] = Math.max(++run, ++columns[x]) >= 8 ? 2 : 1;
                        if ((t & GLASS) != 0) layer[z * 16 + x] |= 4;
                    } else { run = 0; columns[x] = 0; }
                }
            }
            c.plane = Math.max(c.plane, largestPattern(layer, connected, c));
        }
        return true;
    }

    // Count one connected patch, not scattered blocks elsewhere in a layer with a single long row.
    private static int largestPattern(int[] layer, int[] connected, Counts counts) {
        int largest = 0;
        int[] steps = {-16, -1, 1, 16};
        for (int start = 0; start < 256; start++) {
            if (layer[start] == 0) continue;
            boolean longRow = (layer[start] & 2) != 0;
            int glass = (layer[start] & 4) != 0 ? 1 : 0;
            int size = 1;
            connected[0] = start; layer[start] = 0;
            for (int head = 0; head < size; head++) {
                int cell = connected[head];
                for (int step : steps) {
                    int next = cell + step;
                    if (next < 0 || next >= 256 || (Math.abs(step) == 1 && cell / 16 != next / 16)
                            || layer[next] == 0) continue;
                    longRow |= (layer[next] & 2) != 0;
                    if ((layer[next] & 4) != 0) glass++;
                    layer[next] = 0; connected[size++] = next;
                }
            }
            if (longRow) {
                largest = Math.max(largest, size);
                counts.glassPlane = Math.max(counts.glassPlane, glass);
            }
        }
        return largest;
    }

    static Finding evaluate(int x, int z, Counts c, Config cfg) {
        List<String> reasons = new ArrayList<>(); int score = 0;
        if (cfg.susPlayerBlocks && c.strong > 0) { score += 12; reasons.add(c.strong + " player-associated blocks"); }
        if (cfg.susStorage && c.storage >= cfg.susStorageMin) { score += 10; reasons.add(c.storage + " storage/machinery blocks"); }
        if (cfg.susLights && c.lights >= cfg.susLightsMin) { score += 8; reasons.add(c.lights + " artificial lights"); }
        if (cfg.susFarms && c.farm >= cfg.susFarmMin) { score += 8; reasons.add(c.farm + " farmland blocks"); }
        // Plank/cobblestone/brick floors also generate naturally. Only a glass patch meeting the
        // same minimum can stand alone; common materials need another rule that met its own minimum.
        if (cfg.susPatterns && c.plane >= cfg.susPatternMin
                && (c.glassPlane >= cfg.susPatternMin || !reasons.isEmpty())) {
            score += 8; reasons.add(c.plane + " connected building blocks in a flat layer with a long row");
        }
        return score >= cfg.susScore && !reasons.isEmpty()
                ? new Finding(x, z, score, List.copyOf(reasons), c.minY, c.maxY) : null;
    }

    private void render(Minecraft mc, Config cfg) {
        rendered = 0;
        if (!cfg.susEnabled || !cfg.susOverlay || mc.level != world || mc.player == null) return;
        var pos = mc.gameRenderer.mainCamera().position();
        List<Finding> found = findings();
        found.sort(Comparator.comparingDouble(f -> distanceSq(f, pos.x, pos.z)));
        int fill = cfg.susOpacity << 24 | 0xFF2424;
        var style = cfg.susOutline ? GizmoStyle.strokeAndFill(0xBBFF2424, 1.5f, fill) : GizmoStyle.fill(fill);
        try (var collector = mc.levelExtractor.collectPerFrameMainThreadGizmos()) {
            for (Finding f : found) {
                if (rendered >= cfg.susMaxOverlays || distanceSq(f, pos.x, pos.z) > cfg.susRenderDistance * cfg.susRenderDistance) break;
                double minY = cfg.susFullHeight ? world.getMinY() : f.minY;
                double maxY = cfg.susFullHeight ? world.getMaxY() + 1 : f.maxY + 1;
                var box = Gizmos.cuboid(new AABB(f.x * 16.0 + .02, minY, f.z * 16.0 + .02,
                        f.x * 16.0 + 15.98, maxY, f.z * 16.0 + 15.98), style);
                if (cfg.susThroughWalls) box.setAlwaysOnTop();
                rendered++;
            }
        }
    }
    private static double distanceSq(Finding f, double x, double z) {
        double dx = f.x * 16.0 + 8 - x, dz = f.z * 16.0 + 8 - z; return dx * dx + dz * dz;
    }

    public static void main(String[] args) {
        for (int rule = 0; rule < 4; rule++) {
            Config thresholds = new Config(); thresholds.susStorageMin = thresholds.susLightsMin =
                    thresholds.susFarmMin = thresholds.susPatternMin = 12;
            for (int count : new int[]{0, 1, 11, 12, 13}) {
                Counts sample = new Counts();
                switch (rule) {
                    case 0 -> sample.storage = count;
                    case 1 -> sample.lights = count;
                    case 2 -> sample.farm = count;
                    case 3 -> sample.plane = sample.glassPlane = count;
                }
                assert (evaluate(0, 0, sample, thresholds) != null) == (count >= 12) : "Threshold bypass: " + rule;
            }
        }
        for (String container : List.of("chest", "trapped_chest", "barrel", "hopper", "dropper", "dispenser",
                "crafter", "ender_chest", "shulker_box", "red_shulker_box")) {
            assert classify(container) == STORAGE : "Container bypasses storage rule: " + container;
            Config thresholds = new Config(); thresholds.susStorageMin = 12;
            Counts sample = new Counts();
            for (int i = 1; i <= 12; i++) {
                sample.add(classify(container), 0);
                assert (evaluate(0, 0, sample, thresholds) != null) == (i == 12) : container + " at " + i;
            }
            thresholds.susStorage = false;
            assert evaluate(0, 0, sample, thresholds) == null : "Disabled container rule bypassed";
        }
        int[] layer = new int[256];
        Arrays.fill(layer, 0, 7, 1); layer[7] = 2;
        Arrays.fill(layer, 32, 48, 1);
        assert largestPattern(layer, new int[256], new Counts()) == 8 : "Disconnected patches added together";
        Config patterns = new Config(); patterns.susStorageMin = 12;
        Counts common = new Counts(); common.plane = 98; common.storage = 11; common.lights = 1;
        assert evaluate(0, 0, common, patterns) == null : "Generated floors or sub-threshold evidence flagged a chunk";
        common.storage = 12;
        assert evaluate(0, 0, common, patterns).score() == 18 : "Qualifying evidence did not corroborate the pattern";
        Config cfg = new Config(); cfg.clampAll();
        Counts c = new Counts(); c.storage = 1;
        assert evaluate(0, 0, c, cfg) == null : "One generated chest is not a storage room";
        c.storage = 8; assert evaluate(-1, -2, c, cfg) != null;
        cfg.susStorage = false; assert evaluate(0, 0, c, cfg) == null;
        cfg.susStorage = true; cfg.susScore = 20; assert evaluate(0, 0, c, cfg) == null;
        c.strong = 1; assert evaluate(0, 0, c, cfg).score == 22;
        assert classify("sea_lantern") == 0 && classify("glowstone") == GLOW;
        assert classify("copper_torch") == LIGHT && classify("oxidized_copper_lantern") == LIGHT;
        assert classify("stone") == 0 && classify("lava") == 0;
        cfg.susBudgetMicros = -1; cfg.susOpacity = 999; cfg.susRescanTicks = -4;
        cfg.clampAll(); assert cfg.susBudgetMicros == 250 && cfg.susOpacity == 160 && cfg.susRescanTicks == 20;
        cfg.susEnabled = true; cfg.susThroughWalls = false; cfg.susFarmMin = 63;
        var gson = new com.google.gson.Gson();
        Config roundTrip = gson.fromJson(gson.toJson(cfg), Config.class); roundTrip.clampAll();
        assert roundTrip.susEnabled && !roundTrip.susThroughWalls && roundTrip.susFarmMin == 63;
        System.out.println("SusChunkFinder: all count boundaries, container classification, connected patterns, scoring, toggles and clamps passed");
    }
}
