package com.damia.movrand;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * The log. Every coordinate worth walking back to, written to disk in the Logs folder.
 *
 * <p>Each entry carries the context you would otherwise have to remember: what was found,
 * where, in which dimension and biome, at what health and hunger, what the bot was doing at
 * the time and how long it had been running. Entries deduplicate by kind, distance and age,
 * so standing next to the same chest hall for ten minutes writes one line, not three hundred.
 */
public final class Journal {

	public enum Kind {
		// Places. Finding the same one twice in a row is noise, so these deduplicate.
		CONTAINER_CLUSTER("Storage cluster", "A lot of chests, hoppers or barrels in one place.", true),
		LANDMARK("Landmark", "A spawner, beacon or enchanting table.", true),
		PLAYER_SPOTTED("Player", "Another player came within range.", true),
		DAMAGE("Damage", "Where something hurt you.", true),
		LOW_HEALTH("Low health", "Where health dropped past the threshold.", true),
		HOSTILE("Hostile mob", "Where a hostile got close enough to react to.", true),
		STUCK("Stuck", "Where movement stopped making progress.", true),
		LEDGE("Ledge", "Where a drop stopped the walk.", true),

		// Moments. Two of these in the same spot are two different things that happened,
		// so they are always recorded - otherwise a stop right after a start disappears.
		SESSION("Session", "Movement started or stopped.", false),
		DEATH("Death", "Where you died — and what was left behind.", false),
		SAFE_STOP("Safe stop", "Where the integrity watchdog pulled the handbrake.", false),
		CHAT("Chat trigger", "Where a watched word appeared in chat.", false),
		ARRIVED("Arrived", "A go-to destination was reached.", false),
		AREA_DONE("Area covered", "An area sweep finished.", false),
		DIMENSION("Dimension change", "You changed dimension.", false),
		HUNGER("Out of food", "Auto-eat had nothing left to eat.", false),
		MINED("Mined", "A block the base destroyer broke, or set out to break.", false),
		SOLD("Sold", "An automatic sale.", false),
		MANUAL("Pin", "Dropped by hand.", false);

		public final String label, tip;
		/** Whether a nearby recent entry of this kind counts as the same thing. */
		public final boolean positional;

		Kind(String label, String tip, boolean positional) {
			this.label = label;
			this.tip = tip;
			this.positional = positional;
		}
	}

	/** The vanilla dimensions, spelled the way {@link Entry#dimension()} records them. */
	public static final List<String> DIMENSIONS = List.of("overworld", "the_nether", "the_end");

	/** What to put in front of a person. Unknown modded dimensions get their own path back. */
	public static String dimensionLabel(String path) {
		return switch (path == null ? "" : path) {
			case "overworld" -> "Overworld";
			case "the_nether" -> "Nether";
			case "the_end" -> "The End";
			case "", "unknown" -> "Unknown";
			default -> path.replace('_', ' ');
		};
	}

	/** The name a dimension filter is keyed by. Never blank, so the map always has a key. */
	public static String dimensionKey(String path) {
		return path == null || path.isBlank() ? "unknown" : path;
	}

	/**
	 * Every dimension worth offering a filter chip for: the vanilla three always, plus
	 * anything else the log actually contains. The three are listed even when empty so the
	 * filter can be set before going there rather than after.
	 */
	public static List<String> dimensionsIn(List<Entry> list) {
		List<String> out = new ArrayList<>(DIMENSIONS);
		for (Entry e : list) {
			String d = dimensionKey(e.dimension());
			if (!out.contains(d)) out.add(d);
		}
		return out;
	}

	/** Counts per dimension, keyed the same way the filter is. */
	public static Map<String, Integer> dimensionCounts(List<Entry> list) {
		Map<String, Integer> counts = new LinkedHashMap<>();
		for (Entry e : list) counts.merge(dimensionKey(e.dimension()), 1, Integer::sum);
		return counts;
	}

	public enum Rotation {
		SINGLE("One file", "Everything goes in movrand.json, forever."),
		PER_DAY("One per day", "movrand-2026-08-31.json — the usual choice."),
		PER_SESSION("One per session", "A fresh file every time the game starts.");

		public final String label, tip;

		Rotation(String label, String tip) {
			this.label = label;
			this.tip = tip;
		}
	}

	public record Entry(long time, String session, String world, Kind kind, String dimension,
	                    int x, int y, int z, String biome, float health, int food,
	                    String state, double runtimeSec, String note) {

		/** Entries written before the world was recorded have none; they belong everywhere. */
		public String world() {
			return world == null ? "" : world;
		}

		public String worldLabel() {
			return WorldId.label(world);
		}

		public String coords() {
			return x + ", " + y + ", " + z;
		}

		public String when() {
			return TIME.format(Instant.ofEpochMilli(time));
		}

		public String clock() {
			return CLOCK.format(Instant.ofEpochMilli(time));
		}

		public String day() {
			return DAY.format(Instant.ofEpochMilli(time));
		}

		public int chunkX() {
			return x >> 4;
		}

		public int chunkZ() {
			return z >> 4;
		}

		/** Ready to paste into chat. */
		public String teleport() {
			return "/tp @s " + x + " " + y + " " + z;
		}

		/** Everything on one line, for the text export and the clipboard. */
		public String line() {
			StringBuilder sb = new StringBuilder();
			sb.append(when()).append("  ").append(pad(kind.label, 16)).append("  ")
					.append(pad(WorldId.label(world), 26)).append("  ")
					.append(pad(dimension, 12)).append("  ")
					.append(pad(coords(), 22)).append("  chunk ").append(pad(chunkX() + "," + chunkZ(), 12));
			if (biome != null && !biome.isEmpty()) sb.append("  ").append(pad(biome, 18));
			sb.append("  hp ").append(String.format("%4.1f", health)).append("  food ").append(pad(food + "", 3));
			if (state != null && !state.isEmpty()) sb.append("  ").append(pad(state, 12));
			if (!note.isEmpty()) sb.append("  ").append(note);
			return sb.toString();
		}

		private static String pad(String s, int n) {
			if (s == null) s = "";
			return s.length() >= n ? s : s + " ".repeat(n - s.length());
		}

		public String csv() {
			return String.join(",",
					q(when()), q(session), q(world()), q(kind.name()), q(dimension),
					String.valueOf(x), String.valueOf(y), String.valueOf(z),
					String.valueOf(chunkX()), String.valueOf(chunkZ()),
					q(biome), String.format("%.1f", health), String.valueOf(food),
					q(state), String.format("%.0f", runtimeSec), q(note));
		}

		private static String q(String s) {
			if (s == null) s = "";
			return '"' + s.replace("\"", "\"\"") + '"';
		}

		/** Case-insensitive match across every field the viewer's search box should reach. */
		public boolean matches(String needle) {
			if (needle == null || needle.isBlank()) return true;
			String n = needle.toLowerCase(java.util.Locale.ROOT);
			return kind.label.toLowerCase(java.util.Locale.ROOT).contains(n)
					|| dimension.toLowerCase(java.util.Locale.ROOT).contains(n)
					|| world().toLowerCase(java.util.Locale.ROOT).contains(n)
					|| note.toLowerCase(java.util.Locale.ROOT).contains(n)
					|| (biome != null && biome.toLowerCase(java.util.Locale.ROOT).contains(n))
					|| coords().contains(n)
					|| when().contains(n);
		}
	}

	public static final String CSV_HEADER =
			"time,session,world,kind,dimension,x,y,z,chunk_x,chunk_z,biome,health,food,state,runtime_seconds,note";

	private static final DateTimeFormatter TIME =
			DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());
	private static final DateTimeFormatter CLOCK =
			DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());
	private static final DateTimeFormatter DAY =
			DateTimeFormatter.ofPattern("EEEE d MMMM").withZone(ZoneId.systemDefault());
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	private final List<Entry> entries = Collections.synchronizedList(new ArrayList<>());
	private final Config cfg;
	private final String sessionId;
	private boolean dirty;
	/** Lazily read, and only when something asks to be logged once and never again. */
	private List<Entry> history;

	public Journal(Config cfg) {
		this.cfg = cfg;
		this.sessionId = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
				.withZone(ZoneId.systemDefault()).format(Instant.now());
		load();
	}

	public String sessionId() {
		return sessionId;
	}

	// ------------------------------------------------------------- the files

	/** Null when the folder cannot be created — the self-checks run without one. */
	public Path folder() {
		try {
			Path p = Path.of(cfg.logFolder);
			Files.createDirectories(p);
			return p;
		} catch (Exception | LinkageError e) {
			return null;
		}
	}

	private String baseName() {
		return switch (cfg.logRotation) {
			case SINGLE -> "movrand";
			case PER_DAY -> "movrand-" + LocalDate.now();
			case PER_SESSION -> "movrand-session-" + sessionId;
		};
	}

	private Path file(String extension) {
		Path dir = folder();
		return dir == null ? null : dir.resolve(baseName() + extension);
	}

	public String currentFileName() {
		return baseName() + ".json";
	}

	/** Every readable log in the folder, newest first. */
	public List<String> listLogFiles() {
		List<String> out = new ArrayList<>();
		Path dir = folder();
		if (dir == null) return out;
		try (Stream<Path> s = Files.list(dir)) {
			s.filter(Files::isRegularFile)
					.filter(p -> p.getFileName().toString().endsWith(".json"))
					.sorted(Comparator.comparing((Path p) -> {
						try {
							return Files.getLastModifiedTime(p).toMillis();
						} catch (Exception e) {
							return 0L;
						}
					}).reversed())
					.forEach(p -> out.add(p.getFileName().toString()));
		} catch (Exception e) {
			MovRand.LOG.warn("[movrand] could not list the Logs folder", e);
		}
		return out;
	}

	/** Reads one log file back for the viewer. */
	public List<Entry> readLogFile(String name) {
		Path dir = folder();
		if (dir == null) return List.of();
		try {
			Path p = dir.resolve(name);
			if (!Files.isRegularFile(p)) return List.of();
			List<Entry> loaded = GSON.fromJson(Files.readString(p),
					TypeToken.getParameterized(List.class, Entry.class).getType());
			if (loaded == null) return List.of();
			loaded.removeIf(e -> e == null || e.kind() == null);
			return loaded;
		} catch (Exception e) {
			MovRand.LOG.warn("[movrand] could not read log file {}", name, e);
			return List.of();
		}
	}

	// ------------------------------------------------------------- recording

	/** @return true if the entry was new (not a duplicate of a recent nearby one). */
	public boolean log(Kind kind, ClientLevel level, BlockPos pos, String note) {
		if (!cfg.journalEnabled || !cfg.journalKinds.getOrDefault(kind.name(), true)) return false;
		if (pos == null) return false;

		String dim = "unknown";
		String biome = "";
		if (level != null) {
			try {
				dim = level.dimension().identifier().getPath();
				if (cfg.logIncludeBiome) {
					biome = level.getBiome(pos).getRegisteredName().replace("minecraft:", "");
				}
			} catch (Exception e) {
				// a partially loaded world can refuse a biome lookup; the entry is still worth keeping
			}
		}

		long now = System.currentTimeMillis();
		if (isDuplicate(kind, dim, pos, now)) return false;

		float health = 0;
		int food = 0;
		String state = "";
		double runtime = 0;
		try {
			Minecraft mc = Minecraft.getInstance();
			if (cfg.logIncludeStatus && mc.player != null) {
				health = mc.player.getHealth();
				food = mc.player.getFoodData().getFoodLevel();
			}
			if (cfg.logIncludeState) {
				state = MovRand.controller().state.label;
				runtime = MovRand.controller().runtimeSeconds();
			}
		} catch (Exception | LinkageError e) {
			// context is a nicety, never a reason to drop the coordinate
		}

		Entry e = new Entry(now, sessionId, WorldId.current(), kind, dim,
				pos.getX(), pos.getY(), pos.getZ(),
				biome, health, food, state, runtime, note == null ? "" : note);

		synchronized (entries) {
			entries.add(e);
			while (entries.size() > Math.max(10, cfg.journalMaxEntries)) entries.removeFirst();
		}
		dirty = true;
		if (cfg.logToFiles && cfg.logFlushImmediately) writeFiles();
		return true;
	}

	private boolean isDuplicate(Kind kind, String dim, BlockPos pos, long now) {
		if (!kind.positional) return false;
		String world = WorldId.current();
		double r2 = cfg.journalDedupeRadius * cfg.journalDedupeRadius;

		// Some places are worth writing down once and never again. Age is irrelevant to those:
		// a hit anywhere in the log counts, including in a file this session is not appending
		// to, which is what stops "ever" from quietly meaning "today".
		if (cfg.logOnce(kind)) {
			for (Entry e : history()) if (samePlace(e, kind, dim, world, pos, r2)) return true;
			synchronized (entries) {
				for (Entry e : entries) if (samePlace(e, kind, dim, world, pos, r2)) return true;
			}
			return false;
		}

		long window = (long) (cfg.journalDedupeMinutes * 60_000);
		synchronized (entries) {
			for (int i = entries.size() - 1; i >= 0; i--) {
				Entry e = entries.get(i);
				if (now - e.time() > window) break; // chronological, nothing older can match
				if (samePlace(e, kind, dim, world, pos, r2)) return true;
			}
		}
		return false;
	}

	private static boolean samePlace(Entry e, Kind kind, String dim, String world, BlockPos pos, double r2) {
		if (e.kind() != kind || !e.dimension().equals(dim) || !e.world().equals(world)) return false;
		double dx = e.x() - pos.getX(), dy = e.y() - pos.getY(), dz = e.z() - pos.getZ();
		return dx * dx + dy * dy + dz * dz <= r2;
	}

	/**
	 * Every other log file in the folder, read once and kept. Only the log-once check reads
	 * this; with the usual one-file-per-day rotation the live list holds today and nothing
	 * else, so without it a base found yesterday would be news again this morning.
	 */
	private List<Entry> history() {
		if (history != null) return history;
		history = new ArrayList<>();
		String current = currentFileName();
		for (String name : listLogFiles()) {
			if (!name.equals(current)) history.addAll(readLogFile(name));
		}
		return history;
	}

	/** Whether a kind is being recorded at all — the same test {@link #log} makes first. */
	public boolean records(Kind kind) {
		return cfg.journalEnabled && cfg.journalKinds.getOrDefault(kind.name(), true);
	}

	// -------------------------------------------------------------- reading

	public List<Entry> all() {
		synchronized (entries) {
			return new ArrayList<>(entries);
		}
	}

	/** Newest first. */
	public List<Entry> recent(int limit) {
		List<Entry> copy = all();
		Collections.reverse(copy);
		return copy.size() > limit ? new ArrayList<>(copy.subList(0, limit)) : copy;
	}

	public int size() {
		return entries.size();
	}

	/** Entries written before the world was recorded. Nothing can say where they came from. */
	public static int countUnknownWorld(List<Entry> list) {
		int count = 0;
		for (Entry e : list) if (e.world().isEmpty()) count++;
		return count;
	}

	/**
	 * Stamps every entry that has no world with this one, so old coordinates stop being
	 * homeless and start belonging somewhere. Only correct if the log really did come from
	 * one world, which is why nothing calls this on its own.
	 *
	 * @return how many entries changed.
	 */
	public int adoptUnknownWorld(String world) {
		if (world == null || world.isBlank()) return 0;
		int changed = 0;
		synchronized (entries) {
			for (int i = 0; i < entries.size(); i++) {
				Entry e = entries.get(i);
				if (!e.world().isEmpty()) continue;
				entries.set(i, new Entry(e.time(), e.session(), world, e.kind(), e.dimension(),
						e.x(), e.y(), e.z(), e.biome(), e.health(), e.food(),
						e.state(), e.runtimeSec(), e.note()));
				changed++;
			}
		}
		if (changed > 0) {
			dirty = true;
			writeFiles();
		}
		return changed;
	}

	/** Every world or server that appears in a list, in the order first seen. */
	public static List<String> worldsIn(List<Entry> list) {
		List<String> out = new ArrayList<>();
		for (Entry e : list) if (!e.world().isEmpty() && !out.contains(e.world())) out.add(e.world());
		return out;
	}

	/** Counts per kind, only for the kinds that actually appear. */
	public static Map<Kind, Integer> countsOf(List<Entry> list) {
		Map<Kind, Integer> counts = new LinkedHashMap<>();
		for (Entry e : list) counts.merge(e.kind(), 1, Integer::sum);
		return counts;
	}

	public void clear() {
		entries.clear();
		dirty = true;
		writeFiles();
	}

	public String asText() {
		return asText(all());
	}

	public static String asText(List<Entry> list) {
		StringBuilder sb = new StringBuilder("Movement & Randomisation — coordinate log\n");
		sb.append("=".repeat(100)).append('\n');
		for (Entry e : list) sb.append(e.line()).append('\n');
		sb.append("=".repeat(100)).append('\n');
		sb.append(list.size()).append(" entries\n");
		for (Map.Entry<Kind, Integer> c : countsOf(list).entrySet()) {
			sb.append("  ").append(c.getValue()).append("  ").append(c.getKey().label).append('\n');
		}
		return sb.toString();
	}

	public static String asCsv(List<Entry> list) {
		StringBuilder sb = new StringBuilder(CSV_HEADER).append('\n');
		for (Entry e : list) sb.append(e.csv()).append('\n');
		return sb.toString();
	}

	// ---------------------------------------------------------- persistence

	/** Writes every enabled format. The .json is the one the viewer reads back. */
	public void writeFiles() {
		if (!cfg.logToFiles) return;
		List<Entry> snapshot = all();
		try {
			Path json = file(".json");
			if (json == null) return;
			Files.writeString(json, GSON.toJson(snapshot));
			if (cfg.logWriteCsv) Files.writeString(file(".csv"), asCsv(snapshot));
			if (cfg.logWriteText) Files.writeString(file(".txt"), asText(snapshot));
			dirty = false;
		} catch (Exception | LinkageError e) {
			MovRand.LOG.warn("[movrand] could not write the log files", e);
		}
	}

	public void save() {
		if (!dirty) return;
		writeFiles();
	}

	/**
	 * Self-check - no Minecraft classes touched:
	 * {@code java -ea -cp build/classes/java/main com.damia.movrand.Journal}
	 */
	public static void main(String[] args) throws Exception {
		Config cfg = new Config();
		cfg.clampAll();
		// never read or write the real Logs folder from a test
		cfg.logFolder = java.nio.file.Files.createTempDirectory("movrand-check").toString();
		cfg.logToFiles = false;
		cfg.journalDedupeRadius = 64;
		cfg.journalDedupeMinutes = 30;

		Journal j = new Journal(cfg);
		BlockPos a = new BlockPos(0, 64, 0);

		assert j.log(Kind.CONTAINER_CLUSTER, null, a, "first") : "the first entry should always take";
		assert j.size() == 1;

		// same kind, same place, straight away -> a duplicate
		assert !j.log(Kind.CONTAINER_CLUSTER, null, new BlockPos(10, 64, 10), "near") : "should have deduplicated";
		assert j.size() == 1;

		// a different kind at the same spot is a different fact
		assert j.log(Kind.LANDMARK, null, a, "spawner") : "a different kind should not deduplicate";

		// far enough away is a different cluster
		assert j.log(Kind.CONTAINER_CLUSTER, null, new BlockPos(500, 64, 500), "far") : "65+ blocks should not dedupe";
		assert j.size() == 3;

		// a moment is not a place: two sessions in the same spot are two facts
		assert j.log(Kind.SESSION, null, a, "started") : "the first session entry should take";
		assert j.log(Kind.SESSION, null, a, "stopped")
				: "a stop right after a start must not be swallowed as a duplicate";

		// a switched-off kind is not recorded at all
		cfg.journalKinds.put(Kind.DEATH.name(), false);
		assert !j.log(Kind.DEATH, null, new BlockPos(9, 9, 9), "nope") : "a disabled kind must not record";
		cfg.journalKinds.put(Kind.DEATH.name(), true);

		// the cap drops the oldest rather than growing without bound
		cfg.journalMaxEntries = 10;
		for (int i = 0; i < 50; i++) j.log(Kind.MANUAL, null, new BlockPos(i * 200, 64, i * 200), "pin " + i);
		assert j.size() <= 10 : "cap not enforced, held " + j.size();

		// csv has to survive a note containing its own separators
		Journal quoting = new Journal(cfg);
		quoting.log(Kind.MANUAL, null, a, "a, comma and a \" quote");
		String csv = asCsv(quoting.all());
		assert csv.startsWith(CSV_HEADER) : "csv lost its header";
		String row = csv.split("\n")[1];
		assert row.contains("\"a, comma and a \"\" quote\"") : "csv did not escape the note: " + row;
		assert row.split("\",\"").length >= 4 : "csv row looks malformed: " + row;

		// the search box reaches every field it claims to
		Entry e = quoting.all().getFirst();
		assert e.matches("") && e.matches(null) : "an empty search must match everything";
		assert e.matches("PIN") : "search should be case-insensitive on the kind";
		assert e.matches("comma") : "search should reach the note";
		assert e.matches("0, 64, 0") : "search should reach the coordinates";
		assert !e.matches("zzzz") : "search matched something it should not";

		// counts only mention kinds that are actually present
		java.util.Map<Kind, Integer> counts = countsOf(quoting.all());
		assert counts.get(Kind.MANUAL) == 1 : "count was " + counts.get(Kind.MANUAL);
		assert !counts.containsKey(Kind.DEATH) : "counted a kind that never appeared";

		assert e.teleport().equals("/tp @s 0 64 0") : e.teleport();
		assert e.chunkX() == 0 && e.chunkZ() == 0;
		assert new Entry(0, "s", "mp:example.net", Kind.MANUAL, "overworld", -17, 64, -17, "", 0, 0, "", 0, "")
				.chunkX() == -2 : "negative coordinates must floor, not truncate";

		// an entry from before the world was recorded belongs to no world in particular
		Entry legacy = new Entry(0, "s", null, Kind.MANUAL, "overworld", 0, 64, 0, "", 0, 0, "", 0, "");
		assert legacy.world().isEmpty() : "a null world should read as empty, not blow up";
		assert !WorldId.matches(legacy.world(), "mp:anything", false)
				: "\"this world only\" must not leak entries that belong to no world";
		assert WorldId.matches(legacy.world(), "mp:anything", true)
				: "asking for the unrecorded ones should still produce them";
		assert !WorldId.matches("mp:a.net", "mp:b.net", true) : "two servers must not mix";
		assert WorldId.matches("mp:a.net", "mp:a.net", false);

		// adopting gives the homeless ones a home, and leaves the others alone
		Journal adopting = new Journal(cfg);
		adopting.log(Kind.MANUAL, null, new BlockPos(1, 1, 1), "old");
		assert countUnknownWorld(adopting.all()) == 1 : "the test entry should have no world";
		assert adopting.adoptUnknownWorld("sp:Survival") == 1;
		assert countUnknownWorld(adopting.all()) == 0 : "adopt left one behind";
		assert adopting.all().getFirst().world().equals("sp:Survival");
		assert adopting.adoptUnknownWorld("sp:Other") == 0 : "adopt must not re-stamp what it already did";
		assert adopting.all().getFirst().world().equals("sp:Survival") : "adopt overwrote a known world";
		// log once means once, not once per window
		Config onceCfg = new Config();
		onceCfg.clampAll();
		onceCfg.logFolder = java.nio.file.Files.createTempDirectory("movrand-once").toString();
		onceCfg.logToFiles = false;
		onceCfg.journalDedupeMinutes = 0.0001; // any window-based check would have expired
		Journal once = new Journal(onceCfg);
		BlockPos hall = new BlockPos(100, 64, 100);

		assert onceCfg.containerLogOnce : "logging a place once should be the default";
		assert once.log(Kind.CONTAINER_CLUSTER, null, hall, "a base") : "the first find must record";
		Thread.sleep(20); // well past the window above
		assert !once.log(Kind.CONTAINER_CLUSTER, null, hall, "the same base")
				: "an expired window must not bring a logged place back";
		assert !once.log(Kind.CONTAINER_CLUSTER, null, new BlockPos(140, 64, 100), "still in range")
				: "within the dedupe radius is the same place";
		assert once.log(Kind.CONTAINER_CLUSTER, null, new BlockPos(900, 64, 900), "a different base")
				: "a genuinely new place must still record";
		assert once.log(Kind.LANDMARK, null, hall, "spawner") : "a different kind is a different fact";
		assert !once.log(Kind.LANDMARK, null, hall, "spawner again") : "landmarks are once-only too";
		// a moment is never once-only, however the toggle is set
		assert once.log(Kind.SESSION, null, hall, "started") && once.log(Kind.SESSION, null, hall, "stopped")
				: "the log-once path swallowed a moment";
		// and the windowed path still works when the toggle is off
		onceCfg.containerLogOnce = false;
		onceCfg.journalDedupeMinutes = 30;
		Journal windowed = new Journal(onceCfg);
		assert windowed.log(Kind.CONTAINER_CLUSTER, null, hall, "a base");
		assert !windowed.log(Kind.CONTAINER_CLUSTER, null, hall, "same") : "the window stopped working";

		assert once.records(Kind.CONTAINER_CLUSTER) : "records() disagreed with a kind that is on";
		onceCfg.journalKinds.put(Kind.CONTAINER_CLUSTER.name(), false);
		assert !once.records(Kind.CONTAINER_CLUSTER) : "records() missed a switched-off kind";
		onceCfg.journalKinds.put(Kind.CONTAINER_CLUSTER.name(), true);
		onceCfg.journalEnabled = false;
		assert !once.records(Kind.CONTAINER_CLUSTER) : "records() ignored the master switch";
		onceCfg.journalEnabled = true;

		// the dimension filter has to see every dimension in the file, and the vanilla three
		// whether they are in it or not
		List<Entry> mixed = List.of(
				new Entry(0, "s", "w", Kind.MANUAL, "overworld", 0, 0, 0, "", 0, 0, "", 0, ""),
				new Entry(0, "s", "w", Kind.MANUAL, "the_nether", 0, 0, 0, "", 0, 0, "", 0, ""),
				new Entry(0, "s", "w", Kind.MANUAL, "the_nether", 0, 0, 0, "", 0, 0, "", 0, ""),
				new Entry(0, "s", "w", Kind.MANUAL, "twilight_forest", 0, 0, 0, "", 0, 0, "", 0, ""),
				new Entry(0, "s", "w", Kind.MANUAL, null, 0, 0, 0, "", 0, 0, "", 0, ""));
		assert dimensionsIn(mixed).equals(
				List.of("overworld", "the_nether", "the_end", "twilight_forest", "unknown"))
				: "dimensionsIn gave " + dimensionsIn(mixed);
		assert dimensionsIn(List.of()).equals(DIMENSIONS) : "the vanilla three must always be offered";
		Map<String, Integer> dc = dimensionCounts(mixed);
		assert dc.get("the_nether") == 2 && dc.get("unknown") == 1 && !dc.containsKey("the_end") : dc.toString();
		assert dimensionLabel("the_nether").equals("Nether") && dimensionLabel(null).equals("Unknown");
		assert dimensionLabel("twilight_forest").equals("twilight forest") : "a modded dimension lost its name";

		Config dims = new Config();
		dims.clampAll();
		assert dims.dimensionShown("overworld") && dims.dimensionShown("twilight_forest")
				: "a fresh config must show everything";
		dims.dimensionFilter.put("the_nether", false);
		assert !dims.dimensionShown("the_nether") && dims.dimensionShown("overworld")
				: "hiding one dimension hid another";

		assert worldsIn(List.of(legacy, new Entry(0, "s", "mp:a.net", Kind.MANUAL, "o", 0, 0, 0, "", 0, 0, "", 0, "")))
				.equals(List.of("mp:a.net")) : "worldsIn should skip the blanks";

		System.out.println("Journal self-check passed");
	}

	/** Loads the file this session would be appending to, so a restart continues it. */
	private void load() {
		try {
			if (cfg.logRotation == Rotation.PER_SESSION) return; // a new session starts empty by definition
			Path p = file(".json");
			if (p == null || !Files.exists(p)) return;
			List<Entry> loaded = GSON.fromJson(Files.readString(p),
					TypeToken.getParameterized(List.class, Entry.class).getType());
			if (loaded != null) {
				loaded.removeIf(e -> e == null || e.kind() == null);
				entries.addAll(loaded);
			}
		} catch (Exception | LinkageError e) {
			MovRand.LOG.warn("[movrand] could not read the log, starting a fresh one", e);
		}
	}
}
