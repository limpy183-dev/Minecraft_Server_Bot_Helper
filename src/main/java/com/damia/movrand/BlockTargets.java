package com.damia.movrand;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Which blocks the destroyer is after, and where they are.
 *
 * <p>Selection is two things ORed together: <em>families</em>, which are rules ("anything
 * whose name ends in _ore"), and an explicit list of block ids. Families matter because a
 * base is not built out of a list somebody typed in advance — a rule keeps working when the
 * server adds a block, and it survives the user not knowing that the thing they call a
 * "comparator" is {@code minecraft:comparator} and not {@code redstone_comparator}.
 *
 * <p>Matching in the scan loop has to be a set lookup: the loop runs over a few hundred
 * thousand blocks. So the rules are evaluated once against the whole block registry, and
 * what the loop actually sees is a {@code Set<Block>}.
 */
public final class BlockTargets {

	/** A rule, rather than a list, so it keeps up with whatever a base is actually built of. */
	public enum Family {
		REDSTONE("Redstone", "Wire, repeaters, comparators, pistons, observers, hoppers, "
				+ "droppers, dispensers, levers, buttons, plates, lamps, note blocks, targets, "
				+ "rails and the redstone block itself."),
		ORES("Ores", "Anything whose name ends in _ore, plus ancient debris."),
		VALUABLES("Valuable blocks", "Diamond, emerald, netherite, gold, iron and copper "
				+ "blocks, beacons, conduits, enchanting tables, anvils and totem-tier decor."),
		STORAGE("Storage", "Chests, barrels, shulker boxes, hoppers, furnaces and brewing "
				+ "stands. Break these last — they drop their contents on the floor."),
		SPAWNERS("Spawners & vaults", "Monster spawners, trial spawners and vaults."),
		UTILITY("Utility", "Crafting tables, grindstones, smithing tables, cartography and "
				+ "fletching tables, looms, stonecutters, composters, lecterns and bells.");

		public final String label, tip;

		Family(String label, String tip) {
			this.label = label;
			this.tip = tip;
		}
	}

	// The families that are lists rather than rules. Ids, not classes: a class name is one
	// mapping change away from not compiling, and an id is what the user sees in F3.
	private static final Set<String> REDSTONE_IDS = Set.of(
			"redstone_block", "redstone_wire", "redstone_torch", "redstone_wall_torch",
			"repeater", "comparator", "observer", "piston", "sticky_piston", "piston_head",
			"moving_piston", "hopper", "dropper", "dispenser", "lever", "note_block",
			"redstone_lamp", "target", "daylight_detector", "tripwire", "tripwire_hook",
			"slime_block", "honey_block", "rail", "powered_rail", "detector_rail",
			"activator_rail", "lightning_rod", "calibrated_sculk_sensor", "sculk_sensor",
			"crafter");

	private static final Set<String> VALUABLE_IDS = Set.of(
			"diamond_block", "emerald_block", "netherite_block", "gold_block", "iron_block",
			"copper_block", "lapis_block", "coal_block", "raw_iron_block", "raw_gold_block",
			"raw_copper_block", "amethyst_block", "beacon", "conduit", "enchanting_table",
			"anvil", "chipped_anvil", "damaged_anvil", "respawn_anchor", "dragon_egg",
			"end_crystal", "sponge", "wet_sponge", "shulker_box", "ender_chest");

	private static final Set<String> STORAGE_IDS = Set.of(
			"chest", "trapped_chest", "ender_chest", "barrel", "hopper", "dropper", "dispenser",
			"furnace", "blast_furnace", "smoker", "brewing_stand", "chiseled_bookshelf",
			"decorated_pot", "crafter");

	private static final Set<String> UTILITY_IDS = Set.of(
			"crafting_table", "grindstone", "smithing_table", "cartography_table",
			"fletching_table", "loom", "stonecutter", "composter", "lectern", "bell",
			"cauldron", "beehive", "bee_nest", "jukebox", "bookshelf");

	private static final Set<String> SPAWNER_IDS = Set.of(
			"spawner", "trial_spawner", "vault", "ominous_vault");

	/**
	 * Never mined whatever the settings say. Bedrock is caught by the hardness test anyway;
	 * the rest is here because breaking it is how a quiet job becomes a loud one.
	 */
	private static final Set<String> NEVER = Set.of(
			"bedrock", "barrier", "command_block", "chain_command_block", "repeating_command_block",
			"structure_block", "structure_void", "jigsaw", "light", "end_portal", "end_portal_frame",
			"end_gateway", "nether_portal", "reinforced_deepslate", "budding_amethyst", "air",
			"cave_air", "void_air", "water", "lava", "fire", "soul_fire", "tnt");

	static boolean neverBreak(String path) { return NEVER.contains(path); }

	// The resolved set, rebuilt only when the selection actually changes. The scan loop asks
	// this a few hundred thousand times a pass, so it cannot be doing string work.
	private Set<Block> resolved = Set.of();
	private String resolvedFrom = "<unresolved>";

	/** Everything the current settings select, as blocks. */
	public Set<Block> blocks(Config cfg) {
		String signature = signature(cfg);
		if (!signature.equals(resolvedFrom)) {
			resolved = resolve(cfg);
			resolvedFrom = signature;
		}
		return resolved;
	}

	private static String signature(Config cfg) {
		StringBuilder sb = new StringBuilder();
		for (Family f : Family.values()) sb.append(cfg.destroyFamily(f) ? '1' : '0');
		sb.append('|').append(String.join(",", cfg.destroyBlocks));
		sb.append('|').append(String.join(",", cfg.destroyExclude));
		return sb.toString();
	}

	private static Set<Block> resolve(Config cfg) {
		Set<Block> out = new LinkedHashSet<>();
		Set<String> extra = normalise(cfg.destroyBlocks);
		Set<String> excluded = normalise(cfg.destroyExclude);

		for (Block block : BuiltInRegistries.BLOCK) {
			Identifier id = BuiltInRegistries.BLOCK.getKey(block);
			if (id == null) continue;
			String path = id.getPath();
			String full = id.toString();
			if (NEVER.contains(path) || excluded.contains(path) || excluded.contains(full)) continue;
			if (extra.contains(path) || extra.contains(full) || matchesFamily(cfg, path)) out.add(block);
		}
		return out;
	}

	private static boolean matchesFamily(Config cfg, String path) {
		if (cfg.destroyFamily(Family.REDSTONE) && (REDSTONE_IDS.contains(path)
				|| path.endsWith("_button") || path.endsWith("_pressure_plate"))) return true;
		if (cfg.destroyFamily(Family.ORES) && (path.endsWith("_ore") || path.equals("ancient_debris"))) return true;
		if (cfg.destroyFamily(Family.VALUABLES)
				&& (VALUABLE_IDS.contains(path) || path.endsWith("_shulker_box"))) return true;
		if (cfg.destroyFamily(Family.STORAGE) && isStoragePath(path)) return true;
		if (cfg.destroyFamily(Family.SPAWNERS) && SPAWNER_IDS.contains(path)) return true;
		return cfg.destroyFamily(Family.UTILITY) && UTILITY_IDS.contains(path);
	}

	private static boolean isStoragePath(String path) {
		return STORAGE_IDS.contains(path) || path.endsWith("_shulker_box");
	}

	/** Ids as typed: trimmed, lower case, with any namespace left alone. */
	static Set<String> normalise(List<String> ids) {
		Set<String> out = new LinkedHashSet<>();
		if (ids == null) return out;
		for (String id : ids) {
			if (id == null) continue;
			String trimmed = id.trim().toLowerCase(Locale.ROOT);
			if (!trimmed.isEmpty()) out.add(trimmed);
		}
		return out;
	}

	// ------------------------------------------------------------- the scan

	/** One target: where it is, and how far from the player it was when found. */
	public record Found(BlockPos pos, double distance, String name, boolean storage) {
	}

	/**
	 * What one complete pass learned, including blocks deliberately left out of the shortlist.
	 *
	 * <p>{@code matching} is the important number: every selected, breakable block in the
	 * loaded search volume, before retry, liquid-safety and perception filters. A shortlist can
	 * legitimately be empty while this is non-zero, so treating {@code found.isEmpty()} as
	 * proof that the job is done is incorrect.
	 */
	public record ScanResult(List<Found> found, int matching, int eligible, int hidden,
	                         int scannedChunks, int unloadedChunks, boolean complete) {
		public ScanResult(List<Found> found, int matching, int eligible, int hidden, int scannedChunks, int unloadedChunks) {
            this(found, matching, eligible, hidden, scannedChunks, unloadedChunks, true);
        }
        public static final ScanResult EMPTY = new ScanResult(List.of(), 0, 0, 0, 0, 0);

		public int deferred() {
			return Math.max(0, matching - eligible);
		}
	}

	private static final class ScanStats {
		int matching;
		int eligible;
		int hidden;
		int scannedChunks;
		int unloadedChunks;
	}

	/**
	 * Every selected block within reach of the player, nearest first.
	 *
	 * <p>The section palette does the heavy lifting: {@code maybeHas} answers "could this
	 * 16³ box contain any of these" from the palette alone, so a chunk of plain stone costs
	 * a handful of comparisons instead of four thousand block reads. This deliberately remains
	 * separate from {@link ContainerScanner}: block entities are an excellent fast index for
	 * chests and furnaces, but they cannot discover redstone dust, rails, ores, or ordinary
	 * selected blocks. The palette pass is the authoritative block scan; both scanners use the
	 * same load=false rule and therefore never pretend an unseen server chunk is empty.
	 */
	public List<Found> scan(ClientLevel level, LocalPlayer player, Config cfg) {
		return scanDetailed(level, player, cfg, ignored -> true).found();
	}

	/**
	 * Scan with an eligibility filter while still counting every configured match.
	 *
	 * <p>The destroyer uses this to omit temporarily failed or liquid-unsafe blocks from the
	 * bounded nearest-target heap without losing the fact that those blocks still exist. This
	 * also means a full heap of deferred nearby blocks cannot hide a workable block just beyond
	 * the heap limit.
	 */
    private ScanJob pending;
	void resetScan() { pending = null; }
    private static final class ScanJob {
        final ClientLevel level;
        final BlockPos origin;
        final String signature;
        final boolean loaded;
        final int radius, vertical, minY, maxY, cap;
        final double maxDistSq;
        final Comparator<Found> priority;
        final PriorityQueue<Found> nearest;
        final List<net.minecraft.world.level.ChunkPos> chunks = new ArrayList<>();
        final ScanStats stats = new ScanStats();
        int chunkIndex, sectionIndex;
        ScanJob(ClientLevel level, LocalPlayer player, Config cfg) {
            this.level = level; origin = player.blockPosition().immutable(); signature = signature(cfg);
            loaded = cfg.destroyLoadedChunks; radius = Math.max(4, cfg.destroyRadius);
            vertical = cfg.destroyVerticalRadius;
            minY = loaded ? level.getMinY() : Math.max(level.getMinY(), origin.getY() - vertical);
            maxY = loaded ? level.getMaxY() - 1 : Math.min(level.getMaxY() - 1, origin.getY() + vertical);
            maxDistSq = loaded ? Double.POSITIVE_INFINITY : (double) radius * radius;
            cap = Math.max(1, cfg.destroyMaxTargets);
            priority = Comparator.comparingInt((Found f) -> cfg.destroyStorageLast && f.storage() ? 1 : 0).thenComparingDouble(Found::distance);
            nearest = new PriorityQueue<>(cap, priority.reversed());
            int r = loaded ? ContainerScanner.autoRadius() + 1 : (radius >> 4) + 1;
            for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) {
                int x = (origin.getX() >> 4) + dx, z = (origin.getZ() >> 4) + dz;
                if (loaded || chunkIntersects(origin, radius, x, z)) chunks.add(new net.minecraft.world.level.ChunkPos(x, z));
            }
            chunks.sort(Comparator.comparingDouble(c -> origin.distSqr(new BlockPos(c.getMiddleBlockX(), origin.getY(), c.getMiddleBlockZ()))));
        }
    }

    /** A bounded client-thread scan, closest chunks first, including hidden blocks at all heights. */
    public ScanResult scanDetailed(ClientLevel level, LocalPlayer player, Config cfg, Predicate<BlockPos> eligible) {
        Set<Block> wanted = blocks(cfg);
        if (wanted.isEmpty()) { pending = null; return ScanResult.EMPTY; }
        if (pending == null || pending.level != level || !pending.signature.equals(signature(cfg))
                || pending.loaded != cfg.destroyLoadedChunks || pending.radius != cfg.destroyRadius
                || pending.vertical != cfg.destroyVerticalRadius || pending.origin.distSqr(player.blockPosition()) > 256) {
            pending = new ScanJob(level, player, cfg);
        }
        ScanJob job = pending;
        long deadline = System.nanoTime() + 4_000_000;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        // Yield between sections; a dense selected section costs at most 4096 block reads.
        do {
            var position = job.chunks.get(job.chunkIndex);
            LevelChunk chunk = level.getChunkSource().getChunk(position.x(), position.z(), ChunkStatus.FULL, false);
            if (chunk == null) {
                if (!job.loaded) job.stats.unloadedChunks++;
                job.chunkIndex++; job.sectionIndex = 0;
                continue;
            }
            if (job.sectionIndex == 0) job.stats.scannedChunks++;
            scanChunk(chunk, level, player, wanted, job.origin, job.minY, job.maxY, job.maxDistSq,
                    cursor, job.nearest, job.cap, cfg, job.priority, eligible, job.stats, job.sectionIndex, job.sectionIndex + 1);
            if (++job.sectionIndex >= chunk.getSections().length) { job.chunkIndex++; job.sectionIndex = 0; }
        } while (job.chunkIndex < job.chunks.size() && System.nanoTime() < deadline);
        boolean complete = job.chunkIndex >= job.chunks.size();
        List<Found> out = new ArrayList<>(job.nearest);
        out.sort(job.priority);
        if (complete) pending = null;
        return new ScanResult(List.copyOf(out), job.stats.matching, job.stats.eligible, job.stats.hidden,
                job.stats.scannedChunks, job.stats.unloadedChunks, complete);
    }

	/** Refresh nearby candidates before choosing from a partially scanned or stale heap. */
	List<Found> withNearby(ClientLevel level, LocalPlayer player, Config cfg, List<Found> cached,
	                       Predicate<BlockPos> eligible) {
		// A fresh reach-sized cube is cheap and includes thin outline shapes whose centres
		// differ from the block centre. Never let the global heap hide reachable work.
		var merged = new java.util.LinkedHashMap<BlockPos, Found>();
		for (Found f : cached) merged.put(f.pos(), f);
		Set<Block> wanted = blocks(cfg);
		int radius = (int) Math.ceil(player.blockInteractionRange()) + 1;
		BlockPos origin = BlockPos.containing(player.getEyePosition());
		for (BlockPos cursor : BlockPos.betweenClosed(origin.offset(-radius, -radius, -radius),
				origin.offset(radius, radius, radius))) {
			if (!level.hasChunkAt(cursor)) continue;
			BlockState state = level.getBlockState(cursor);
			if (!wanted.contains(state.getBlock()) || !breakable(state, level, cursor)
					|| Storage.protectedWorldBlock(cfg, level, cursor) || !eligible.test(cursor)) continue;
			BlockPos pos = cursor.immutable();
			merged.put(pos, new Found(pos, Math.sqrt(pos.distToCenterSqr(player.getX(), player.getY(), player.getZ())),
					state.getBlock().getName().getString(), isStoragePath(BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath())));
		}
		return List.copyOf(merged.values());
	}

	/** Whether this chunk's horizontal block square touches the circular search area. */
	private static boolean chunkIntersects(BlockPos origin, int radius, int chunkX, int chunkZ) {
		int minX = chunkX << 4, minZ = chunkZ << 4;
		int maxX = minX + 15, maxZ = minZ + 15;
		long closestX = Math.max(minX, Math.min(maxX, origin.getX()));
		long closestZ = Math.max(minZ, Math.min(maxZ, origin.getZ()));
		long dx = closestX - origin.getX(), dz = closestZ - origin.getZ();
		return dx * dx + dz * dz <= (long) radius * radius;
	}

	private static void scanChunk(LevelChunk chunk, ClientLevel level, LocalPlayer player,
	                              Set<Block> wanted,
	                              BlockPos origin, int minY, int maxY, double maxDistSq,
	                              BlockPos.MutableBlockPos cursor, PriorityQueue<Found> nearest,
	                              int cap, Config cfg, Comparator<Found> priority,
	                              Predicate<BlockPos> eligible, ScanStats stats, int fromSection, int toSection) {
		int baseX = chunk.getPos().getMinBlockX();
		int baseZ = chunk.getPos().getMinBlockZ();
		LevelChunkSection[] sections = chunk.getSections();

		for (int index = fromSection; index < Math.min(toSection, sections.length); index++) {
			LevelChunkSection section = sections[index];
			if (section == null || section.hasOnlyAir()) continue;
			int sectionY = level.getSectionYFromSectionIndex(index) << 4;
			if (sectionY + 15 < minY || sectionY > maxY) continue;
			if (!section.maybeHas(state -> wanted.contains(state.getBlock()))) continue;

			for (int y = 0; y < 16; y++) {
				int worldY = sectionY + y;
				if (worldY < minY || worldY > maxY) continue;
				for (int x = 0; x < 16; x++) {
					for (int z = 0; z < 16; z++) {
						BlockState state = section.getBlockState(x, y, z);
						if (!wanted.contains(state.getBlock())) continue;
						cursor.set(baseX + x, worldY, baseZ + z);
						double d2 = cursor.distSqr(origin);
						if (d2 > maxDistSq) continue;
						if (!breakable(state, level, cursor)) continue;
						if (Storage.protectedWorldBlock(cfg, level, cursor)) continue;
						stats.matching++;
						BlockPos position = cursor.immutable();
						if (!eligible.test(position)) continue;
						stats.eligible++;
						if (cfg.destroyRequireLineOfSight
								&& !visibleToPlayer(level, player, cursor, state, cfg.destroyFieldOfViewDeg)) {
							stats.hidden++;
							continue;
						}
						Identifier id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
						Found candidate = new Found(position, Math.sqrt(d2),
								state.getBlock().getName().getString(), id != null && isStoragePath(id.getPath()));
						if (nearest.size() < cap) nearest.add(candidate);
						else if (priority.compare(candidate, nearest.peek()) < 0) {
							nearest.poll();
							nearest.add(candidate);
						}
					}
				}
			}
		}
	}

	/** True only when this block is in the configured view cone and is the first raycast hit. */
	static boolean visibleToPlayer(ClientLevel level, LocalPlayer player, BlockPos pos,
	                                       BlockState state, double fieldOfViewDeg) {
		var shape = state.getShape(level, pos);
		Vec3 aim = (shape.isEmpty() ? new AABB(pos) : shape.bounds().move(pos)).getCenter();
		Vec3 eyes = player.getEyePosition();
		Vec3 delta = aim.subtract(eyes);
		if (fieldOfViewDeg < 359.999 && delta.lengthSqr() > 1.0e-8) {
			double half = Math.toRadians(fieldOfViewDeg * 0.5);
			if (player.getViewVector(1.0F).dot(delta.normalize()) < Math.cos(half)) return false;
		}
		BlockHitResult hit = level.clip(new ClipContext(eyes, aim, ClipContext.Block.OUTLINE,
				ClipContext.Fluid.NONE, player));
		return hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(pos);
	}

	/** Hardness below zero is the game's own word for "you cannot break this". */
	public static boolean breakable(BlockState state, ClientLevel level, BlockPos pos) {
		if (state.isAir() || state.liquid()) return false;
		try {
			return state.getDestroySpeed(level, pos) >= 0;
		} catch (Exception | LinkageError e) {
			return false;
		}
	}

	/**
	 * Self-check on the selection rules — the scan needs a world, the matching does not:
	 * {@code ./gradlew selfCheck -Pcheck=com.damia.movrand.BlockTargets}
	 */
	public static void main(String[] args) {
		Config cfg = new Config();
		cfg.destroyFamilies.clear();
		cfg.destroyBlocks = new ArrayList<>();
		cfg.destroyExclude = new ArrayList<>();

		// out of the box the section does the job it is named after and nothing else
		assert cfg.destroyFamily(Family.REDSTONE) : "redstone should be on by default";
		for (Family f : Family.values()) {
			if (f != Family.REDSTONE) assert !cfg.destroyFamily(f) : "should start off: " + f;
		}

		// families are rules, and with every one unticked nothing at all is selected
		for (Family f : Family.values()) cfg.setDestroyFamily(f, false);
		assert !matchesFamily(cfg, "redstone_block") : "nothing is selected with every family off";
		assert !matchesFamily(cfg, "diamond_ore") : "nothing is selected with every family off";

		cfg.setDestroyFamily(Family.REDSTONE, true);
		assert matchesFamily(cfg, "redstone_block") : "the redstone family missed the redstone block";
		assert matchesFamily(cfg, "comparator") : "a comparator is redstone";
		assert !matchesFamily(cfg, "diamond_ore") : "an ore is not redstone";

		cfg.setDestroyFamily(Family.ORES, true);
		assert matchesFamily(cfg, "diamond_ore") : "the ore rule missed diamond ore";
		assert matchesFamily(cfg, "deepslate_redstone_ore") : "the ore rule is a suffix, not a list";
		assert matchesFamily(cfg, "ancient_debris") : "ancient debris counts as an ore";
		assert !matchesFamily(cfg, "orebfuscator") : "_ore has to end the name, not appear in it";
		assert !matchesFamily(cfg, "stone") : "plain stone is never a target";

		cfg.setDestroyFamily(Family.VALUABLES, true);
		assert matchesFamily(cfg, "red_shulker_box") : "every dye of shulker box is a valuable";
		cfg.setDestroyFamily(Family.STORAGE, true);
		assert matchesFamily(cfg, "blue_shulker_box") : "storage missed a dyed shulker box";
		assert isStoragePath("barrel") && isStoragePath("red_shulker_box")
				: "storage-last ordering cannot recognise storage";
		assert matchesFamily(cfg, "oak_button") && matchesFamily(cfg, "stone_pressure_plate")
				: "redstone missed buttons or pressure plates";

		// the never-list is not a preference
		assert NEVER.contains("bedrock") && NEVER.contains("end_portal_frame")
				: "the never-list lost something it must not";

		// ids are normalised, so a stray space or capital does not create a second entry
		Set<String> ids = normalise(Arrays.asList("  Minecraft:Redstone_Block ", "redstone_block", "", null));
		assert ids.size() == 2 : "normalise kept a blank or dropped a real id: " + ids;
		assert ids.contains("minecraft:redstone_block") && ids.contains("redstone_block") : ids;

		// and the resolved set is only rebuilt when the selection changes
		BlockTargets t = new BlockTargets();
		String before = signature(cfg);
		cfg.destroyBlocks.add("obsidian");
		assert !signature(cfg).equals(before) : "adding a block did not change the signature";
		assert t.resolvedFrom.equals("<unresolved>") : "nothing should have resolved without a registry";

		System.out.println("BlockTargets self-check passed");
	}
}
