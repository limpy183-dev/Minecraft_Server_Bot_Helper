package com.damia.movrand.gui;

import com.damia.movrand.AlertSound;
import com.damia.movrand.AreaCoverage;
import com.damia.movrand.BaseDestroyer;
import com.damia.movrand.BlockTargets;
import com.damia.movrand.Bot;
import com.damia.movrand.Config;
import com.damia.movrand.ContainerScanner;
import com.damia.movrand.Journal;
import com.damia.movrand.MovRand;
import com.damia.movrand.MovementController;
import com.damia.movrand.Presets;
import com.damia.movrand.Risks;
import com.damia.movrand.Storage;
import com.damia.movrand.WorldId;
import com.damia.movrand.gui.Widgets.Action;
import com.damia.movrand.gui.Widgets.Cycle;
import com.damia.movrand.gui.Widgets.Element;
import com.damia.movrand.gui.Widgets.KeyValue;
import com.damia.movrand.gui.Widgets.Note;
import com.damia.movrand.gui.Widgets.RangeSlider;
import com.damia.movrand.gui.Widgets.Row;
import com.damia.movrand.gui.Widgets.Section;
import com.damia.movrand.gui.Widgets.Slider;
import com.damia.movrand.gui.Widgets.TextInput;
import com.damia.movrand.gui.Widgets.Toggle;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public final class ConfigScreen extends Screen {

	private enum Group {
		START("Start here", false), MOVE("Moving", false), WATCH("Watching", false),
		// A rule drawn above the heading, because everything below it is a different kind of
		// thing: the rest of the mod watches and walks, this half reaches out and changes the
		// world. That is worth being able to see in the sidebar without reading it.
		DESTROY("Base destroyer", true),
		DATA("Data", true), SETUP("Setup", false);

		final String label;
		/** Draw a divider above this group's heading. */
		final boolean separated;

		Group(String label, boolean separated) {
			this.label = label;
			this.separated = separated;
		}
	}

	private enum Tab {
		PRESETS("Setup guide", Group.START),
		PROFILES("Configs", Group.START),

		MOVEMENT("Movement", Group.MOVE),
		RANDOM("Randomisation", Group.MOVE),
		AREA("Area sweep", Group.MOVE),
		GOTO("Go to", Group.MOVE),
		OBSTACLES("Obstacles", Group.MOVE),
		FOOD("Food", Group.MOVE),

		CONTAINERS("Containers", Group.WATCH),
		SAFETY("Safety", Group.WATCH),
		STUCK("Stuck & alert", Group.WATCH),
		SAFESTOP("Safe stop", Group.WATCH),

		DESTROYER("Base destroyer", Group.DESTROY),
		BLOCKS("Blocks to mine", Group.DESTROY),
		INVENTORY("Inventory", Group.DESTROY),
		STORAGE("Storage", Group.DESTROY),
		SELLING("Auto sell", Group.DESTROY),
		COMBAT("Combat", Group.DESTROY),

		LOGGING("Logging", Group.DATA),
		LOGS("Log viewer", Group.DATA),
		MAP("Map", Group.DATA),

		LOOK("HUD & theme", Group.SETUP),
		ABOUT("About", Group.SETUP);

		final String label;
		final Group group;

		Tab(String label, Group group) {
			this.label = label;
			this.group = group;
		}
	}

	/** One line in the sidebar: a group heading, or a tab. */
	private record SidebarRow(int relY, int height, Tab tab, String heading, boolean rule) {
	}

	private static final int[] ACCENTS = {
			0xFF5B8CFF, 0xFF7C5BFF, 0xFF00C2A8, 0xFF4ADE80, 0xFFFBBF24, 0xFFF87171, 0xFFEC4899, 0xFFE8E8F2
	};
	private static final String[] ACCENT_NAMES = {
			"Blue", "Violet", "Teal", "Green", "Amber", "Red", "Pink", "Mono"
	};
	private static final String NUMERIC = "-0123456789.";
	private static final List<Integer> MAP_ZOOMS =
			List.of(8, 16, 32, 64, 128, 256, 512, 1024, 2048, 4096, 8192, 16384, 32768);

	/** Only the zooms that fit inside the world border, so no listed option is a lie. */
	private static List<Integer> mapZooms() {
		int max = com.damia.movrand.WorldBounds.maxSpanChunks(32_768);
		List<Integer> out = new ArrayList<>();
		for (int z : MAP_ZOOMS) if (z <= max) out.add(z);
		if (out.isEmpty()) out.add(MAP_ZOOMS.getFirst());
		return out;
	}

	/** The listed zoom closest to whatever the wheel left behind. */
	private static int nearestZoom(int view) {
		List<Integer> zooms = mapZooms();
		int best = zooms.getFirst();
		for (int z : zooms) if (Math.abs(z - view) < Math.abs(best - view)) best = z;
		return best;
	}

	/** One line saying how big the world actually is, so the zoom ceiling is not a mystery. */
	private Element worldBorderRow() {
		com.damia.movrand.WorldBounds b = com.damia.movrand.WorldBounds.current();
		return new KeyValue("World border", () -> b == null ? "no world loaded" : b.describe(),
				() -> Ui.WORLD_EDGE)
				.tip("Drawn on both maps in orange, and the point past which zooming out stops: "
						+ "there is nothing beyond it to show. A vanilla border is 60 million "
						+ "blocks across, so on an ordinary world it changes nothing.");
	}

	private static Tab activeTab = Tab.PRESETS;
	private static final double[] SCROLL = new double[Tab.values().length];
	private static double sidebarScroll;

	private enum Scrollbar {
		NONE, SIDEBAR, CONTENT
	}

	private final Config cfg = MovRand.config();
	private final MovementController ctl = MovRand.controller();

	private final List<Element> elements = new ArrayList<>();
	private int panelX, panelY, panelW, panelH;
	private int contentX, contentY, contentW, contentH;
	private int sidebarW;
	private int contentHeight;
	private String hoverTip = "";
	private int journalSizeWhenBuilt = -1;
	private String newProfileName = "";
	private String pendingDelete = "";
	/** The scrollbar currently held by the mouse, if any. */
	private Scrollbar draggingScrollbar = Scrollbar.NONE;
	/** Where inside the thumb the mouse grabbed it, so dragging does not make it jump. */
	private double scrollbarGrabOffset;
	/** Recomputed on every build, so the red flags never lag behind a setting. */
	private List<Risks.Risk> risks = List.of();
	private String worldNow = "";
	/** A log file read once and kept, because the map re-reads its source every frame. */
	private String loadedFile;
	private List<Journal.Entry> loadedEntries = List.of();

	public ConfigScreen() {
		super(Component.literal("Movement & Randomisation"));
	}

	private int accent() {
		return cfg.accentColor;
	}

	// ------------------------------------------------------------- layout

	@Override
	protected void init() {
		draggingScrollbar = Scrollbar.NONE;
		panelW = Math.min(700, width - 30);
		panelH = Math.min(460, height - 30);
		panelX = (width - panelW) / 2;
		panelY = (height - panelH) / 2;
		sidebarW = 132;

		contentX = panelX + sidebarW + 18;
		contentY = panelY + 52;
		contentW = panelW - sidebarW - 36;
		contentH = panelH - 52 - 30;

		revealActiveTab();
		build();
	}

	/** The search box in the block picker, which is a view filter rather than a setting. */
	private String blockSearch = "";
	/** The same, for the grid of blocks the destroyer is allowed to place. */
	private String placeSearch = "";
	private String junkSearch = "";
	private String storageSearch = "";
	private Storage.Target storageTarget;

	private void build() {
		// A text box whose setter rebuilds the page - the two searches both do - would
		// otherwise lose focus on its first keystroke and swallow every one after it.
		String keepFocus = null;
		int keepCursor = 0;
		TextInput was = focusedInput();
		if (was != null) {
			keepFocus = was.label();
			keepCursor = was.cursor();
		}
		elements.clear();
		risks = Risks.of(cfg);
		worldNow = WorldId.current();
		switch (activeTab) {
			case MOVEMENT -> buildMovement();
			case RANDOM -> buildRandom();
			case AREA -> buildArea();
			case OBSTACLES -> buildObstacles();
			case STUCK -> buildStuck();
			case SAFESTOP -> buildSafeStop();
			case GOTO -> buildGoto();
			case CONTAINERS -> buildContainers();
			case SAFETY -> buildSafety();
			case FOOD -> buildFood();
			case DESTROYER -> buildDestroyer();
			case BLOCKS -> buildBlocks();
			case INVENTORY -> buildInventory();
			case STORAGE -> buildStorage();
			case SELLING -> buildSelling();
			case COMBAT -> buildCombat();
			case LOGGING -> buildLogging();
			case LOGS -> buildLogViewer();
			case MAP -> buildMap();
			case PRESETS -> buildPresets();
			case PROFILES -> buildProfiles();
			case LOOK -> buildLook();
			case ABOUT -> buildAbout();
		}
		if (keepFocus != null) refocus(keepFocus, keepCursor);
		int y = 0;
		for (Element e : elements) {
			// width first: a wrapping element cannot know its height until it knows its width
			e.w = contentW;
			e.measure();
			e.relY = y;
			y += e.h + 4;
		}
		contentHeight = y;
		journalSizeWhenBuilt = ctl.journal.size();
	}

	private void add(Element e) {
		elements.add(e);
	}

	/**
	 * Marks the control just added as one a server could notice, and explains it underneath
	 * in red with a button that fixes it. Matching is by prefix, because a few risk names
	 * carry the offending value in them.
	 */
	private void flag(String settingPrefix) {
		for (Risks.Risk r : risks) {
			if (!r.setting().startsWith(settingPrefix)) continue;
			if (!elements.isEmpty()) elements.getLast().risk(r.why());
			add(new Note("⚠ " + r.level().label.toUpperCase(Locale.ROOT) + " — " + r.setting(), Ui.BAD));
			for (String line : Ui.wrap(font, r.why(), contentW - 8)) {
				add(new Note(line, Ui.mix(Ui.BAD, Ui.TEXT_MUTED, 0.5)));
			}
			add(new Action("Make this safe", false, () -> applyFix(r)));
			return;
		}
	}

	/**
	 * Entries logged before the world was recorded belong to no world in particular, so
	 * scoping to one has to drop them. Doing that silently looks like the filter is broken,
	 * which is exactly how this was found, so say how many and offer both ways out.
	 */
	private void addUnknownWorldControls(List<Journal.Entry> source, boolean scoped) {
		if (!scoped) return;
		int unknown = Journal.countUnknownWorld(source);
		if (unknown == 0) return;

		add(new Note("\u26a0 " + unknown + " entries predate the world being recorded.", Ui.WARN));
		for (String line : Ui.wrap(font,
				"Nothing can work out where those came from, so they are left out. If this log is "
						+ "all from one world, adopt them into it and they stay for good.",
				contentW - 8)) {
			add(new Note(line, Ui.TEXT_FAINT));
		}
		add(new Toggle("Show them anyway", () -> cfg.showUnknownWorldEntries, v -> {
			cfg.showUnknownWorldEntries = v;
			build();
		}).tip("Puts them back in every world at once, which is what they were doing before."));

		if (!cfg.logViewFile.isBlank()) {
			add(new Note("Switch the file back to the live session to adopt them.", Ui.TEXT_FAINT));
		} else if (worldNow.isBlank()) {
			add(new Note("Join a world first \u2014 there is nothing to adopt them into.", Ui.TEXT_FAINT));
		} else {
			add(new Action("Adopt all " + unknown + " into " + WorldId.label(worldNow), true, () -> {
				int changed = ctl.journal.adoptUnknownWorld(worldNow);
				ctl.lastReason = changed + " entries adopted into " + WorldId.label(worldNow);
				build();
			}).tip("Rewrites the live log so those entries belong to this world. Only right if the "
					+ "log really is all from one place \u2014 there is no undo."));
		}
	}

	private void applyFix(Risks.Risk r) {
		r.apply().accept(cfg);
		cfg.clampAll();
		cfg.save();
		ctl.lastReason = "Fixed: " + r.setting();
		build();
	}

	// -------------------------------------------------------------- tabs

	private void addBaritoneRecommendation() {
		add(new Toggle("Use Baritone navigation", () -> cfg.baritoneNavigation,
				v -> { cfg.baritoneNavigation = v; build(); })
				.tip("Uses the bundled Baritone engine for digging, bridging, pillars, stairs, slabs, ladders and terrain recovery. Off selects the legacy planner.")
				.recommend("Keep enabled for terrain navigation and recovery."));
	}

	private void buildPresets() {
		add(new Note("New here? Pick a setup below. Everything it changes is listed, and every", Ui.TEXT));
		add(new Note("value stays editable afterwards on the other tabs.", Ui.TEXT));

		add(new Section("How human do the current settings look"));
		add(new Widgets.ScoreBar(() -> Presets.score(cfg).value(), () -> Presets.score(cfg).verdict())
				.tip("A weighted checklist, not a measurement. It asks what a reviewer would ask."));
		for (String reason : Presets.score(cfg).reasons()) {
			int colour = reason.startsWith("\u2713") ? Ui.GOOD : reason.startsWith("\u2717") ? Ui.BAD : Ui.WARN;
			add(new Note(reason, colour));
		}

		List<String> warnings = Presets.warnings(cfg);
		add(new Section(warnings.isEmpty() ? "Checks — all clear" : "Checks — " + warnings.size() + " to look at"));
		if (warnings.isEmpty()) {
			add(new Note("Nothing in the current settings contradicts itself.", Ui.GOOD));
		} else {
			for (String warning : warnings) {
				for (String line : Ui.wrap(font, warning, contentW - 14)) {
					add(new Note(line, Ui.WARN));
				}
			}
		}

		add(new Section(risks.isEmpty() ? "Detection risk — nothing flagged"
				: "Detection risk — " + Risks.summary(cfg)));
		add(new Note("What a server could actually notice. Everything not listed here is invisible to one.",
				Ui.TEXT_MUTED));
		if (risks.isEmpty()) {
			add(new Note("✓ Nothing reacts to something you could not have seen.", Ui.GOOD));
			add(new Note("✓ Nothing about the movement is periodic, linear or constant.", Ui.GOOD));
		} else {
			for (Risks.Risk r : risks) {
				add(new Widgets.Card(r.level().label.toUpperCase(Locale.ROOT) + "  ·  " + r.setting(),
						r.why(), List.of("Lives on the " + r.tab() + " tab", "Fix: " + r.fix()),
						"Fix", () -> applyFix(r)).risk(r.why()));
			}
			add(new Action("Make every one of them safe", true, () -> {
				Risks.fixAll(cfg);
				cfg.save();
				ctl.lastReason = "Applied every safety fix";
				build();
			}).tip("Applies all " + risks.size() + " fixes above at once."));
		}

		add(new Section("Cannot be detected at all"));
		for (String line : Risks.SAFE_BY_CONSTRUCTION) {
			boolean first = true;
			for (String wrapped : Ui.wrap(font, line, contentW - 14)) {
				add(new Note((first ? "· " : "   ") + wrapped, first ? Ui.TEXT_MUTED : Ui.TEXT_FAINT));
				first = false;
			}
		}

		add(new Section("Ready-made setups"));
		for (Presets.Preset preset : Presets.ALL) {
			List<String> bullets = new ArrayList<>(preset.bullets());
			Config probe = cfg.copy();
			preset.apply().accept(probe);
			probe.clampAll();
			String verdict = Risks.summary(probe);
			bullets.add(verdict.equals("No detection risks") ? "Leaves no detection risks" : "Leaves " + verdict);
			add(new Widgets.Card(preset.name(), preset.blurb(), bullets, "Apply", () -> {
				if (cfg.movementEnabled) ctl.stop(minecraft, "Applying the " + preset.name() + " setup");
				preset.apply().accept(cfg);
				cfg.clampAll();
				cfg.save();
				ctl.lastReason = "Applied the " + preset.name() + " setup";
				build();
			}).tip("Applies every value listed. Stops movement first."));
		}

		add(new Section("Start over"));
		add(new Action("Reset every setting to the defaults", false, this::resetAll));
	}

	private void buildProfiles() {
		add(new Note("Keep a different set of settings for each thing you use the bot for.", Ui.TEXT_MUTED));

		add(new Section("Current"));
		add(new KeyValue("Loaded profile",
				() -> cfg.activeProfile.isBlank() ? "unnamed (config/movrand.json)" : cfg.activeProfile,
				() -> accent()));
		add(new KeyValue("Humanisation", () -> Presets.score(cfg).verdict(), () -> Ui.TEXT_MUTED));

		add(new Section("Save"));
		add(new TextInput("Name", null, () -> newProfileName, v -> newProfileName = v)
				.tip("Letters, numbers, spaces, dashes and underscores."));
		add(new Row(List.of(
				new Action(() -> Config.profileExists(newProfileName) ? "Overwrite" : "Save as new", true, () -> {
					String saved = cfg.saveAsProfile(newProfileName);
					if (saved == null) {
						ctl.lastReason = "That name will not do";
					} else {
						ctl.lastReason = "Saved profile " + saved;
						newProfileName = "";
						build();
					}
				}),
				new Action("Save to loaded", false, () -> {
					if (cfg.activeProfile.isBlank()) {
						ctl.lastReason = "No profile loaded — give it a name first";
					} else {
						cfg.saveAsProfile(cfg.activeProfile);
						ctl.lastReason = "Saved " + cfg.activeProfile;
					}
				}),
				new Action("Duplicate", false, () -> {
					String from = cfg.activeProfile.isBlank() ? "config" : cfg.activeProfile;
					String saved = cfg.saveAsProfile(from + " copy");
					if (saved != null) {
						ctl.lastReason = "Created " + saved;
						build();
					}
				}))));

		List<String> profiles = Config.listProfiles();
		add(new Section(profiles.isEmpty() ? "No saved profiles yet" : profiles.size() + " saved"));
		if (profiles.isEmpty()) {
			add(new Note("Type a name above and press Save as new.", Ui.TEXT_FAINT));
		}
		for (String name : profiles) {
			boolean active = name.equals(cfg.activeProfile);
			// reading each file is what lets a profile be judged before you switch to it
			Config saved = Config.peekProfile(name);
			Risks.Level worst = saved == null ? null : Risks.worst(saved);
			String verdict = saved == null ? "could not be read" : Risks.summary(saved);
			add(new Row(List.of(
					new Action(() -> (active ? "\u25cf " : "") + (worst == Risks.Level.HIGH ? "\u26a0 " : "") + name,
							active, () -> loadProfile(name))
							.tip(active ? "This one is loaded. Click to reload it from disk."
									: "Load " + name + ", replacing every current setting."),
					new Action(() -> pendingDelete.equals(name) ? "Really?" : "Delete", false, () -> {
						if (pendingDelete.equals(name)) {
							Config.deleteProfile(name);
							pendingDelete = "";
							ctl.lastReason = "Deleted " + name;
						} else {
							pendingDelete = name;
						}
						build();
					}).tip("Click twice to confirm."))));
			if (worst == Risks.Level.HIGH) elements.getLast().risk(verdict);
			add(new Note("      " + (worst == null && saved != null ? "✓ " : "⚠ ") + verdict
					+ (saved == null ? "" : "  ·  " + Presets.score(saved).verdict()),
					worst == Risks.Level.HIGH ? Ui.BAD : worst == null ? Ui.TEXT_FAINT : Ui.WARN));
		}

		add(new Section("Files"));
		add(new Note("config/movrand-profiles/ — one .json each, safe to copy between installs.", Ui.TEXT_FAINT));
		add(new Action("Open the profiles folder", false, () -> {
			java.nio.file.Path dir = Config.profilesDir();
			if (dir != null) open(dir.toFile());
		}));
	}

	/** Swapping the whole config means the screen's captured references are stale. */
	private void loadProfile(String name) {
		Config loaded = Config.loadProfile(name);
		if (loaded == null) {
			ctl.lastReason = "Could not load " + name;
			return;
		}
		if (cfg.movementEnabled) ctl.stop(minecraft, "Switching to profile " + name);
		MovRand.replaceConfig(loaded);
		if (minecraft != null) minecraft.setScreenAndShow(new ConfigScreen());
	}

	private void buildMovement() {
		add(new Section("Walking"));
		add(new Toggle("Move forward automatically",
				() -> cfg.movementEnabled,
				v -> {
					if (v) ctl.start(minecraft);
					else ctl.stop(minecraft, "Turned off in the menu");
				}).tip("The master switch. Also on a key you can rebind in Controls."));
		add(new Toggle("Hold sprint", () -> cfg.holdSprint, v -> cfg.holdSprint = v)
				.tip("Sprint while walking forward. Costs hunger."));
		add(new Toggle("Sprint-jump while running", () -> cfg.jumpSprintEnabled, v -> cfg.jumpSprintEnabled = v)
				.tip("Bunny-hop on nearly every landing. A sprint-jump carries further than a "
						+ "sprint, so someone actually going somewhere does it almost always - and "
						+ "a run of open ground crossed with the feet never leaving the floor is "
						+ "the easier of the two to pick out. Needs hold sprint on."));
		add(new Slider("Hop on this share of landings", 0, 1, 0.01, 2, "",
				() -> cfg.jumpSprintChance, v -> cfg.jumpSprintChance = v)
				.tip("1 is every single landing, which no hand manages. The misses are spaced "
						+ "randomly rather than every nth one."));
		add(new Toggle("Hold sneak", () -> cfg.holdSneak, v -> cfg.holdSneak = v)
				.tip("Sneak the whole time — slower, but you will not walk off an edge."));
		flag("Sneak the whole time");
		add(new Toggle("Pause while another screen is open", () -> cfg.pauseWhileScreenOpen, v -> cfg.pauseWhileScreenOpen = v)
				.tip("Off means it keeps walking through chat and inventories. This menu never pauses it either way."));
		flag("Keep walking while a screen is open");
		add(new Note("This menu does not pause the bot — watch the map and counters update as it runs.",
				Ui.TEXT_FAINT));
		add(new Toggle("Stop when the window loses focus", () -> cfg.stopWhenUnfocused, v -> cfg.stopWhenUnfocused = v)
				.tip("Alt-tabbing away stops movement entirely."));

		add(new Section("Forward runs"));
		add(new RangeSlider("Run for", 1, 600, 1, 0, "s",
				() -> cfg.segmentMinSec, v -> cfg.segmentMinSec = v,
				() -> cfg.segmentMaxSec, v -> cfg.segmentMaxSec = v)
				.tip("How long the player walks in a straight line before one random event fires."));
		add(new Cycle<>("Distribution", Arrays.asList(Config.Distribution.values()), d -> d.label,
				() -> cfg.segmentDistribution, v -> cfg.segmentDistribution = v)
				.tip("Gaussian clusters near the middle of the range, which reads as more human."));
		flag("Fixed run length");

		add(new Section("Live"));
		add(new KeyValue("State", ctl::describeState,
				() -> cfg.movementEnabled ? Ui.GOOD : Ui.TEXT_MUTED));
		add(new KeyValue("Heading for", ctl::navigationLabel, () -> accent()));
		add(new KeyValue("Running for", () -> Ui.seconds(ctl.runtimeSeconds()), () -> Ui.TEXT));
		add(new KeyValue("Next event in", () -> Ui.seconds(ctl.nextEventSeconds()), () -> accent()));
		add(new KeyValue("Speed vs predicted",
				() -> "%.0f%%".formatted(ctl.safeStop.averageRatio * 100),
				() -> ctl.safeStop.averageRatio < cfg.safeStopMinSpeedRatio ? Ui.BAD : Ui.GOOD));
		add(new KeyValue("Last reason", () -> ctl.lastReason.isEmpty() ? "—" : ctl.lastReason, () -> Ui.TEXT_MUTED));
	}

	private void buildRandom() {
		add(new Note("Weights are relative. When a run ends, one event is drawn using them.", Ui.TEXT_MUTED));
		flag("No random events");

		add(new Section("Step left / right"));
		add(new Toggle("Enabled", () -> cfg.strafeEnabled, v -> cfg.strafeEnabled = v));
		add(new Slider("Weight", 0, 100, 1, 0, "", () -> cfg.strafeWeight, v -> cfg.strafeWeight = v));
		add(new RangeSlider("Hold sideways for", 0.05, 5, 0.05, 2, "s",
				() -> cfg.strafeMinSec, v -> cfg.strafeMinSec = v,
				() -> cfg.strafeMaxSec, v -> cfg.strafeMaxSec = v));
		add(new Toggle("Keep walking forward while stepping", () -> cfg.strafeKeepsForward, v -> cfg.strafeKeepsForward = v)
				.tip("On: a diagonal drift. Off: a pure sidestep, which is more obvious."));

		add(new Section("Pause"));
		add(new Toggle("Enabled", () -> cfg.pauseEnabled, v -> cfg.pauseEnabled = v));
		add(new Slider("Weight", 0, 100, 1, 0, "", () -> cfg.pauseWeight, v -> cfg.pauseWeight = v));
		add(new RangeSlider("Stand still for", 0.05, 20, 0.05, 2, "s",
				() -> cfg.pauseMinSec, v -> cfg.pauseMinSec = v,
				() -> cfg.pauseMaxSec, v -> cfg.pauseMaxSec = v));

		add(new Section("Turn"));
		add(new Toggle("Enabled", () -> cfg.turnEnabled, v -> cfg.turnEnabled = v));
		add(new Slider("Weight", 0, 100, 1, 0, "", () -> cfg.turnWeight, v -> cfg.turnWeight = v));
		add(new RangeSlider("Turn by", 0, 180, 1, 0, "°",
				() -> cfg.turnMinDeg, v -> cfg.turnMinDeg = v,
				() -> cfg.turnMaxDeg, v -> cfg.turnMaxDeg = v));
		add(new Slider("Turn speed", 0.2, 15, 0.1, 1, "°/tick",
				() -> cfg.turnSpeedDegPerTick, v -> cfg.turnSpeedDegPerTick = v)
				.tip("Peak rate. A turn eases in and out rather than holding one speed, so it lasts "
						+ "half again as long as this number alone suggests."));
		flag("Turn speed");

		add(new Section("Hop"));
		add(new Toggle("Enabled", () -> cfg.hopEnabled, v -> cfg.hopEnabled = v)
				.tip("An occasional jump for no reason."));
		add(new Slider("Weight", 0, 100, 1, 0, "", () -> cfg.hopWeight, v -> cfg.hopWeight = v));

		add(new Section("Look around"));
		add(new Toggle("Enabled", () -> cfg.lookAroundEnabled, v -> cfg.lookAroundEnabled = v));
		add(new Slider("Weight", 0, 100, 1, 0, "", () -> cfg.lookAroundWeight, v -> cfg.lookAroundWeight = v));
		add(new RangeSlider("Pitch range", -90, 90, 1, 0, "°",
				() -> cfg.lookPitchMinDeg, v -> cfg.lookPitchMinDeg = v,
				() -> cfg.lookPitchMaxDeg, v -> cfg.lookPitchMaxDeg = v));

		add(new Section("Constant jitter"));
		add(new Toggle("Wobble the view every tick", () -> cfg.yawJitterEnabled, v -> cfg.yawJitterEnabled = v)
				.tip("A random walk on yaw and pitch. Not a sine wave: a sine leaves two razor-sharp "
						+ "peaks in the spectrum and never loses them, which is a signature, not a disguise."));
		flag("No view wobble");
		add(new Slider("Amplitude", 0, 5, 0.05, 2, "°",
				() -> cfg.yawJitterAmplitudeDeg, v -> cfg.yawJitterAmplitudeDeg = v));
		add(new Slider("Speed", 0.002, 0.3, 0.002, 3, "",
				() -> cfg.yawJitterSpeed, v -> cfg.yawJitterSpeed = v));

		add(new Section("Smoothing"));
		add(new Slider("Turning", 0, 0.95, 0.01, 2, "",
				() -> cfg.cameraSmoothYaw, v -> cfg.cameraSmoothYaw = v)
				.tip("How much of last tick's heading the camera keeps. 0 points straight at where "
						+ "it wants to face; 0.95 takes about a second to get there. Higher is "
						+ "smoother and lags a little wider round corners."));
		add(new Slider("Looking up and down", 0, 0.95, 0.01, 2, "",
				() -> cfg.cameraSmoothPitch, v -> cfg.cameraSmoothPitch = v)
				.tip("The same for the pitch, which a hand moves less and settles more slowly, so "
						+ "it is worth keeping higher than the turning."));
		add(new Row(List.of(
				new Action("Glide", false, () -> {
					cfg.cameraSmoothYaw = 0.85;
					cfg.cameraSmoothPitch = 0.92;
				}).tip("Slow and heavy. Corners get wide."),
				new Action("Normal", true, () -> {
					cfg.cameraSmoothYaw = 0.7;
					cfg.cameraSmoothPitch = 0.88;
				}),
				new Action("Sharp", false, () -> {
					cfg.cameraSmoothYaw = 0.35;
					cfg.cameraSmoothPitch = 0.7;
				}).tip("Follows the heading closely. Still eased by the turn curve."))));

		add(new Action("Reseed the generator now", false, () -> {
			com.damia.movrand.Rng.reseed();
			ctl.lastReason = "Generator reseeded";
		}).tip("Draws a fresh seed from the OS entropy pool."));
	}

	private void buildArea() {
		AreaCoverage area = ctl.area;
		ctl.syncAreaWorld(minecraft);
		add(new Toggle("This world or server only", () -> cfg.areaThisWorldOnly, v -> {
			cfg.areaThisWorldOnly = v;
			build();
		}).tip("Shows only this world's current dimension. Off overlays all saved areas; edits and the bot still use this dimension only."));
		add(new KeyValue("You are on", WorldId::currentLabel, () -> accent()));
		add(new KeyValue("Dimension", () -> minecraft.level == null ? "unknown"
				: minecraft.level.dimension().identifier().toString(), () -> accent()));

		add(new Toggle("Sweep an area", () -> cfg.areaEnabled, v -> {
			cfg.areaEnabled = v;
			if (v) cfg.gotoEnabled = false; // one navigator at a time
		}).tip("Walks every chunk in the region below. Overrides the go-to destination."));

		add(new Section("The region"));
		AreaMap map = new AreaMap(cfg, area, 190);
		add(map.tip("Drag to move the map \u00b7 double-click to redraw the area \u00b7 "
				+ "right-click a chunk to tick it off or put it back"));
		add(new Row(List.of(
				new Action(() -> map.isSelecting() ? "Now drag a box" : "Redraw the area", true, map::armSelection)
						.tip("Or double-click the map. A plain drag only moves the view, so a slipped "
								+ "mouse cannot take the area and its progress with it."),
				new Action(() -> map.isFollowing() ? "Following you" : "Centre on me", false, map::follow)
						.tip("The map keeps up with you until you drag it somewhere else."))));
		add(new Cycle<>("Map zoom", mapZooms(), z -> z + " chunks across",
				() -> nearestZoom(cfg.areaMapView), v -> cfg.areaMapView = v)
				.tip("Or use the wheel over the map, which steps in finer increments than this. "
						+ "Zooms wider than the world border are not offered."));
		add(worldBorderRow());

		add(new Row(List.of(
				new Action("Corner A here", false, () -> {
					if (minecraft != null && minecraft.player != null) {
						cfg.areaX1 = Math.round(minecraft.player.getX());
						cfg.areaZ1 = Math.round(minecraft.player.getZ());
						area.save();
					}
				}),
				new Action("Corner B here", false, () -> {
					if (minecraft != null && minecraft.player != null) {
						cfg.areaX2 = Math.round(minecraft.player.getX());
						cfg.areaZ2 = Math.round(minecraft.player.getZ());
						area.save();
					}
				}))));
		add(new Row(List.of(
				new Action(() -> "Around me: " + cfg.areaAroundRadius + " chunks", true, () -> {
					if (minecraft != null && minecraft.player != null) {
						area.setAround(minecraft.player.getX(), minecraft.player.getZ(), cfg.areaAroundRadius);
						area.save();
					}
				}),
				new Action("Reset progress", false, () -> area.reset()))));
		add(Slider.ints("Chunks either side", 1, 512, () -> cfg.areaAroundRadius, v -> cfg.areaAroundRadius = v)
				.tip("512 is an area 16,000 blocks across. The route search rings outward from "
						+ "where you are, so a big area costs no more per step than a small one."));
		add(new Toggle("Round it off into a circle", () -> cfg.areaCircular, v -> cfg.areaCircular = v)
				.tip("Trims the rectangle to a disc — usually what \"N chunks around me\" means."));

		add(new Section("Exact corners"));
		add(new Row(List.of(
				new TextInput("X1", NUMERIC, () -> fmt(cfg.areaX1), v -> cfg.areaX1 = parse(v, cfg.areaX1)),
				new TextInput("Z1", NUMERIC, () -> fmt(cfg.areaZ1), v -> cfg.areaZ1 = parse(v, cfg.areaZ1)))));
		add(new Row(List.of(
				new TextInput("X2", NUMERIC, () -> fmt(cfg.areaX2), v -> cfg.areaX2 = parse(v, cfg.areaX2)),
				new TextInput("Z2", NUMERIC, () -> fmt(cfg.areaZ2), v -> cfg.areaZ2 = parse(v, cfg.areaZ2)))));

		add(new Section("How it walks"));
		add(new Cycle<>("Route", Arrays.asList(AreaCoverage.Route.values()), r -> r.label,
				() -> cfg.areaRoute, v -> {
			cfg.areaRoute = v;
			build();
		})
				.tip("Organic picks randomly among the nearest few chunks — covers efficiently without a straight line in sight. "
						+ "Scout walks only far enough apart for the container scans to touch, which finds the same "
						+ "storage for a fraction of the walking."));
		flag("Serpentine route");
		add(new Note(cfg.areaRoute == AreaCoverage.Route.SCOUT && !(cfg.areaUseScanRadius && cfg.containerScanEnabled)
						? "Scout needs the scan credit below — without it there is nothing to space the stops by."
						: AreaCoverage.Route.values()[Math.max(0,
						Arrays.asList(AreaCoverage.Route.values()).indexOf(cfg.areaRoute))].tip,
				cfg.areaRoute == AreaCoverage.Route.SCOUT && !(cfg.areaUseScanRadius && cfg.containerScanEnabled)
						? Ui.WARN : Ui.TEXT_FAINT));
		add(Slider.ints("Chunks to choose between", 1, 32,
				() -> cfg.areaRouteLookahead, v -> cfg.areaRouteLookahead = v)
				.tip("Organic and scout routes only. Higher wanders more."));
		add(new Slider("Scatter inside a chunk", 0, 7.5, 0.5, 1, " blocks",
				() -> cfg.areaTargetJitter, v -> cfg.areaTargetJitter = v)
				.tip("Aims at a random point in the chunk instead of dead centre."));
		add(new Toggle("Count scanned chunks as covered", () -> cfg.areaUseScanRadius, v -> {
			cfg.areaUseScanRadius = v;
			build();
		}).tip("If the container scan already read a chunk, there is no reason to walk into it. "
				+ "This is also what the scout route spaces its stops by."));
		add(new Slider("Steer back when this far outside", 8, 256, 4, 0, " blocks",
				() -> cfg.areaLeashBlocks, v -> cfg.areaLeashBlocks = v));

		add(new Section("When it is finished"));
		add(new Toggle("Stop when the area is done", () -> cfg.areaStopWhenDone, v -> cfg.areaStopWhenDone = v));
		add(reaction("Reaction", () -> cfg.areaDoneReaction, v -> cfg.areaDoneReaction = v));
		add(new KeyValue("Progress", area::describe, () -> accent()));
		add(new KeyValue("Chunks left", () -> String.valueOf(area.remaining()),
				() -> area.remaining() == 0 ? Ui.GOOD : Ui.TEXT));
		add(new Action("Mark the whole area as done", false, () -> {
			area.markAll();
			area.save();
		}).tip("Skips the sweep without walking it."));
	}

	private void buildObstacles() {
		add(new Section("Going round things"));
		add(new Toggle("Steer around what is in the way", () -> cfg.avoidEnabled, v -> cfg.avoidEnabled = v)
				.tip("Probes a fan of headings a few blocks ahead, takes the smallest turn that is "
						+ "actually walkable, and unwinds it once the way ahead clears."));
		add(new Note("Walls, trees, cliffs, ravines and pillars. It is not a pathfinder \u2014 it cannot",
				Ui.TEXT_FAINT));
		add(new Note("solve a maze, and a dead end still falls through to the stuck detector.",
				Ui.TEXT_FAINT));
		add(new KeyValue("Right now", () -> ctl.avoid.status,
				() -> ctl.avoid.trapped() ? Ui.BAD : ctl.avoid.steering() ? Ui.WARN : Ui.GOOD));
		add(new Slider("Look ahead", 1, 12, 0.5, 1, " blocks",
				() -> cfg.avoidLookahead, v -> cfg.avoidLookahead = v)
				.tip("Further sees trouble sooner and swings wider around it. Three blocks is about "
						+ "one second of sprinting."));
		add(new Slider("Widest detour", 15, 180, 5, 0, "\u00b0",
				() -> cfg.avoidMaxDeviationDeg, v -> cfg.avoidMaxDeviationDeg = v)
				.tip("How far off the intended heading it may turn to get past something. Below 90 "
						+ "it cannot go back the way it came, so a dead end becomes a stuck alert."));
		add(new Slider("Turn into a dodge at", 0.5, 20, 0.5, 1, "\u00b0/tick",
				() -> cfg.avoidTurnDegPerTick, v -> cfg.avoidTurnDegPerTick = v)
				.tip("A cap, not a fixed rate \u2014 the correction eases in and out on its own."));
		flag("Dodge speed");
		add(new Slider("Unwind at", 0.5, 20, 0.5, 1, "\u00b0/tick",
				() -> cfg.avoidReturnDegPerTick, v -> cfg.avoidReturnDegPerTick = v)
				.tip("How quickly it comes back onto the original heading afterwards. Slower cuts "
						+ "the corner wider, which looks more like a person."));
		add(new Toggle("Go around holes as well as blocks", () -> cfg.avoidHoles, v -> cfg.avoidHoles = v)
				.tip("Anything deeper than the ledge drop below counts as a hole."));
		add(new Toggle("Treat lava as a wall", () -> cfg.avoidLava, v -> cfg.avoidLava = v));
		add(new Toggle("Go round anything that hurts", () -> cfg.avoidHazards, v -> cfg.avoidHazards = v)
				.tip("Fire, magma, cactus, berry bushes, wither roses, dripstone, powder snow and "
						+ "cobwebs. Most of these have no collision at all, so nothing else in the "
						+ "steering sees them: to a wall detector they are open ground."));
		add(new Toggle("Go round portals", () -> cfg.avoidPortals, v -> cfg.avoidPortals = v)
				.tip("Nether portals, end portals and gateways. Not dangerous — but walking into "
						+ "one takes the bot somewhere it was never asked to go, mid-sweep."));
		add(new Toggle("Treat water as a wall", () -> cfg.avoidWater, v -> cfg.avoidWater = v)
				.tip("Off by default \u2014 swimming is usually fine, and rivers are everywhere."));

		add(new Section("Jump over obstacles"));
		add(new Toggle("Jump single blocks and keep going", () -> cfg.autoJumpEnabled, v -> cfg.autoJumpEnabled = v)
				.tip("Probes ahead each tick and hops when the obstacle is short enough to clear."));
		add(Slider.ints("Highest obstacle to hop", 1, 3, () -> cfg.autoJumpMaxHeight, v -> cfg.autoJumpMaxHeight = v)
				.tip("1 is the vanilla step-up. 2 and 3 need a slab or a sprint jump to actually work."));
		add(Slider.ints("Cooldown between hops", 0, 40, () -> cfg.autoJumpCooldownTicks, v -> cfg.autoJumpCooldownTicks = v)
				.tip("Ticks. Stops the machine-gun hop against a wall."));
		flag("Jump cooldown");
		add(new Slider("Probe distance", 0.2, 1.5, 0.05, 2, " blocks",
				() -> cfg.autoJumpProbeDistance, v -> cfg.autoJumpProbeDistance = v)
				.tip("How far ahead of the player the obstacle check samples."));
		add(new Toggle("Swim up in water", () -> cfg.autoJumpSwimUp, v -> cfg.autoJumpSwimUp = v));

		add(new Section("Ledges"));
		add(new Toggle("React to a drop ahead", () -> cfg.stopAtLedge, v -> cfg.stopAtLedge = v)
				.tip("The fallback for when steering finds no way round. With avoidance on, a drop "
						+ "it can walk around never reaches this at all."));
		add(Slider.ints("Drop that counts", 1, 32, () -> cfg.ledgeDropBlocks, v -> cfg.ledgeDropBlocks = v));
		add(reaction("When a ledge is found", () -> cfg.ledgeReaction, v -> cfg.ledgeReaction = v));
	}

	private void buildStuck() {
		add(new Section("Stuck detection"));
		add(new Toggle("Detect when movement stops", () -> cfg.stuckDetectEnabled, v -> cfg.stuckDetectEnabled = v));
		add(new Slider("No progress for", 0.5, 30, 0.5, 1, "s",
				() -> cfg.stuckWindowSec, v -> cfg.stuckWindowSec = v));
		add(new Slider("Distance that counts as progress", 0.1, 10, 0.1, 1, " blocks",
				() -> cfg.stuckMinDistance, v -> cfg.stuckMinDistance = v));
		add(new Toggle("Also detect blocked input", () -> cfg.detectInputBlocked, v -> cfg.detectInputBlocked = v)
				.tip("Trips when something outside this mod eats the forward key."));
		add(new Toggle("Try to free myself first", () -> cfg.stuckAutoUnstick, v -> cfg.stuckAutoUnstick = v));
		add(Slider.ints("Attempts before alerting", 1, 10, () -> cfg.stuckUnstickAttempts, v -> cfg.stuckUnstickAttempts = v));
		add(reaction("When still stuck", () -> cfg.stuckReaction, v -> cfg.stuckReaction = v));

		add(new Section("Alert sound"));
		add(new Toggle("Play a sound file", () -> cfg.alertEnabled, v -> cfg.alertEnabled = v));
		add(new TextInput("Folder", null, () -> cfg.alertFolder, v -> cfg.alertFolder = v));
		add(new Action(() -> "File: " + (cfg.alertFile.isBlank() ? "first one found" : cfg.alertFile), false, this::cycleAlertFile)
				.tip("Click to cycle through the audio files in that folder."));
		add(new KeyValue("Will play", () -> AlertSound.status(cfg),
				() -> AlertSound.status(cfg).startsWith("⚠") ? Ui.BAD : Ui.GOOD));
		add(Slider.ints("Play it this many times", 1, 20, () -> cfg.alertRepeatCount, v -> cfg.alertRepeatCount = v));
		add(new Slider("Gap between plays", 0, 5, 0.05, 2, "s", () -> cfg.alertGapSec, v -> cfg.alertGapSec = v));
		add(new Slider("Volume", 0, 1, 0.01, 2, "", () -> cfg.alertVolume, v -> cfg.alertVolume = v));
		add(new Slider("Do not repeat within", 0, 120, 1, 0, "s",
				() -> cfg.alertCooldownSec, v -> cfg.alertCooldownSec = v)
				.tip("One alert per this many seconds, whatever triggered it."));
		add(new Toggle("Also ping in-game", () -> cfg.alertInGameSound, v -> cfg.alertInGameSound = v));
		add(new Toggle("Also print to chat", () -> cfg.alertChatMessage, v -> cfg.alertChatMessage = v));
		add(new Row(List.of(
				new Action("Test", true, () -> AlertSound.play(cfg, cfg.alertRepeatCount)),
				new Action("Stop", false, AlertSound::stop),
				new Action("Open folder", false, this::openAlertFolder))));
		add(new Note(".wav plays through the game's own decoder. .mp3 and .ogg go to the system player.",
				Ui.TEXT_FAINT));
	}

	private void buildSafeStop() {
		add(new Note("Predicts where you should be each tick and pulls the handbrake when reality disagrees.",
				Ui.TEXT_MUTED));
		add(new Toggle("Safe stop enabled", () -> cfg.safeStopEnabled, v -> cfg.safeStopEnabled = v));
		add(reaction("Reaction", () -> cfg.safeStopReaction, v -> cfg.safeStopReaction = v));

		add(new Section("Speed prediction"));
		add(new Toggle("Compare speed against the held keys", () -> cfg.safeStopOnSpeed, v -> cfg.safeStopOnSpeed = v)
				.tip("Sprinting is 0.28 blocks/tick, walking 0.22, sneaking 0.07. Anything much slower is a problem."));
		add(new Slider("Trip below", 0.05, 1.0, 0.01, 2, " × predicted",
				() -> cfg.safeStopMinSpeedRatio, v -> cfg.safeStopMinSpeedRatio = v));
		add(new Slider("Averaged over", 0.5, 15, 0.5, 1, "s",
				() -> cfg.safeStopWindowSec, v -> cfg.safeStopWindowSec = v)
				.tip("Longer is calmer; shorter reacts faster to a sudden freeze."));
		add(new KeyValue("Last window",
				() -> "%.0f%% of predicted".formatted(ctl.safeStop.averageRatio * 100),
				() -> ctl.safeStop.averageRatio < cfg.safeStopMinSpeedRatio ? Ui.BAD : Ui.GOOD));
		add(new Note("Water, lava, falling and riding are exempt — those legitimately change your speed.",
				Ui.TEXT_FAINT));

		add(new Section("Hard faults"));
		add(new Toggle("Teleported", () -> cfg.safeStopOnTeleport, v -> cfg.safeStopOnTeleport = v)
				.tip("The server yanking you somewhere is worth stopping over."));
		add(new Slider("Jump that counts", 2, 64, 1, 0, " blocks/tick",
				() -> cfg.safeStopTeleportBlocks, v -> cfg.safeStopTeleportBlocks = v));
		add(new Toggle("Except being put back", () -> cfg.safeStopIgnoreRubberBand,
				v -> cfg.safeStopIgnoreRubberBand = v)
				.tip("Servers with strict movement checks reject a step and drop you back where "
						+ "you already were. That arrives as the same position packet a teleport "
						+ "does, so the only way to tell them apart is where you land: back on "
						+ "the last few seconds of your own path, or somewhere new. Landing back "
						+ "on it carries on without a stop or an alert."));
		add(new Slider("Counts as the same place", 0.5, 16, 0.5, 1, " blocks",
				() -> cfg.safeStopRubberBandBlocks, v -> cfg.safeStopRubberBandBlocks = v)
				.tip("Raise it if setbacks still stop the bot, lower it if a short teleport is "
						+ "being waved through."));
		add(new KeyValue("Put back", () -> ctl.safeStop.rubberBands + "x this session",
				() -> ctl.safeStop.rubberBands > 0 ? Ui.WARN : Ui.TEXT_FAINT)
				.tip("Ignored, not stopped for. A number that climbs steadily means the server is "
						+ "rejecting movement, not that anything here is wrong."));
		add(new Toggle("Put in a vehicle", () -> cfg.safeStopOnVehicle, v -> cfg.safeStopOnVehicle = v));
		add(new Toggle("Dimension changed", () -> cfg.safeStopOnDimensionChange, v -> cfg.safeStopOnDimensionChange = v));
		add(new Toggle("Client froze", () -> cfg.safeStopOnFreeze, v -> cfg.safeStopOnFreeze = v));
		add(new Slider("Freeze that counts", 200, 10000, 100, 0, "ms",
				() -> cfg.safeStopFreezeMs, v -> cfg.safeStopFreezeMs = v));
		add(new Toggle("Something else moved the camera", () -> cfg.safeStopOnRotationHijack, v -> cfg.safeStopOnRotationHijack = v)
				.tip("Compares the yaw we wrote against the yaw that actually stuck."));
		add(new Slider("Rotation tolerance", 2, 90, 1, 0, "°",
				() -> cfg.safeStopRotationToleranceDeg, v -> cfg.safeStopRotationToleranceDeg = v));
	}


	// --------------------------------------------------------- base destroyer

	private void buildDestroyer() {
		BaseDestroyer d = ctl.destroyer;

		add(new Section("The job"));
		add(new Toggle("Take bases apart automatically", () -> cfg.destroyerEnabled, v -> {
			cfg.destroyerEnabled = v;
			build();
		})
				.tip("While this is on, the movement toggle runs the job instead of wandering: "
						+ "it finds the blocks you picked, walks to them, mines them, fights back "
						+ "if something attacks, covers liquid, picks the drops up and empties its "
						+ "bag. Everything it does goes through the same camera the wander uses, "
						+ "so the wobble and the easing still apply.")
				.risk("acting on blocks the client knows about but you have not seen"));
		add(new KeyValue("Doing", d::describe, () -> cfg.destroyerEnabled ? accent() : Ui.TEXT_FAINT));
		add(new KeyValue("Mined", () -> "%d blocks · %d placed · %d picked up"
				.formatted(d.mined, d.placed, d.collected), () -> Ui.TEXT_MUTED));
		add(new KeyValue("In range", () -> d.remaining() + " selected blocks", () -> Ui.TEXT_MUTED));
		add(new Note("Turn the movement toggle on as usual — this replaces what it does, it does "
				+ "not run on its own.", Ui.TEXT_FAINT));

		add(new Section("How far it looks"));
        add(new Toggle("Search all loaded terrain", () -> cfg.destroyLoadedChunks, v -> { cfg.destroyLoadedChunks = v; build(); })
                .tip("Searches every height in the loaded view distance, through walls. The server must have sent the chunks. Scanning is spread over ticks."));
        if (!cfg.destroyLoadedChunks) {
		add(Slider.ints("Search radius", 4, 160, () -> cfg.destroyRadius, v -> cfg.destroyRadius = v)
				.tip("Blocks, not chunks. The scan skips whole 16-block sections whose palette "
						+ "does not contain anything selected, so a wide radius through plain "
						+ "stone costs very little — but a wide radius through a base costs real "
						+ "time, and only loaded chunks are ever read."));
		add(Slider.ints("Height band", 2, 160, () -> cfg.destroyVerticalRadius,
				v -> cfg.destroyVerticalRadius = v)
				.tip("How far above and below you to look."));
		}
		add(new RangeSlider("Rescan every", 0.25, 30, 0.25, 2, "s",
				() -> cfg.destroyScanSec, v -> cfg.destroyScanSec = v,
				() -> cfg.destroyScanMaxSec, v -> cfg.destroyScanMaxSec = v)
				.tip("Drawn fresh from this range each time rather than run on a fixed clock. The "
						+ "scan itself sends nothing — it reads chunks the server already sent — "
						+ "so this is about cost, not about being seen."));
		add(Slider.ints("At most", 16, 4000, () -> cfg.destroyMaxTargets, v -> cfg.destroyMaxTargets = v)
				.tip("A ceiling on one scan, so a warehouse does not build a list of fifty thousand."));
		add(new Action("Apply fast, smooth mining settings", true, () -> cfg.fastDestroyerTuning())
				.tip("Updates targeting, aim, reaction and recovery timing. Keeps your block selections, inventory and path-edit permissions."));
		add(new Toggle("Prefer blocks already in reach", () -> cfg.destroyPreferReachable,
				v -> cfg.destroyPreferReachable = v)
				.tip("Mine visible nearby blocks before planning a journey to an obstructed block."));
		add(Slider.ints("Choose between the nearest", 1, 32,
				() -> cfg.destroyTargetChoices, v -> cfg.destroyTargetChoices = v)
				.tip("Maximum near-tie candidates. Re-ranked from your current position at each decision."));
		flag("Always the nearest block");
		add(new Slider("Near-tie distance allowance", 0, 2, 0.05, 2, " blocks",
				() -> cfg.destroyTargetDistanceSlack, v -> cfg.destroyTargetDistanceSlack = v)
				.tip("Random choices cannot be farther than the nearest eligible block plus this allowance."));
		add(new Slider("Vary the target on this share", 0, 1, 0.05, 2, " x",
				() -> cfg.destroyTargetRandomness, v -> cfg.destroyTargetRandomness = v)
				.tip("0 always chooses the nearest eligible block. Variation stays inside the distance allowance."));
		add(new Toggle("Break storage last", () -> cfg.destroyStorageLast,
				v -> cfg.destroyStorageLast = v)
				.tip("Keeps chests, shulkers, barrels, hoppers and furnaces out of the shortlist "
						+ "until other selected blocks are gone, so their contents do not flood the floor early."));
		add(new Toggle("Only choose blocks I can see", () -> cfg.destroyRequireLineOfSight,
				v -> cfg.destroyRequireLineOfSight = v)
				.tip("Requires the target to be inside the view cone and the first block hit by a "
						+ "ray from the eyes. The full scan still counts hidden matches so they can never "
						+ "be mistaken for a finished job. Off can choose every selected block in chunks "
						+ "the server has already sent."));
		add(new Slider("Visible view cone", 10, 360, 5, 0, "°",
				() -> cfg.destroyFieldOfViewDeg, v -> cfg.destroyFieldOfViewDeg = v)
				.tip("Used by the visibility option. 360 still requires a clear line of sight, but "
						+ "allows a remembered block behind the current camera direction."));

		add(new Section("When to give up"));
		add(new Slider("On one block after", 25, 600, 5, 0, "s",
				() -> cfg.destroyBlockSec, v -> cfg.destroyBlockSec = v)
				.tip("The only honest way to tell a slow block from an impossible one: a client "
						+ "is never told why a swing did nothing, so claimed land, region "
						+ "protection and spawn protection all look exactly like mining that has "
						+ "not finished yet. Obsidian with an iron pickaxe is twenty-five seconds "
						+ "of honest work, so this cannot go below that."));
		add(new Slider("On one target after", 60, 3600, 10, 0, "s",
				() -> cfg.destroyTargetSec, v -> cfg.destroyTargetSec = v)
				.tip("However the time was spent — walking, planning, lining up, swinging. With "
						+ "every individual step bounded and nothing bounding the whole, a bot can "
						+ "still spend an afternoon on one block by failing at it in a slightly "
						+ "different way each time."));
		add(new Slider("Retry a failed block after", 1, 600, 1, 0, "s",
				() -> cfg.destroyRetrySec, v -> cfg.destroyRetrySec = v)
				.tip("A protected or unreachable block stays out for this long instead of being "
						+ "selected again immediately when it is the only candidate."));
		add(new Slider("Or after moving", 0, 64, 1, 0, " blocks",
				() -> cfg.destroyRetryMoveBlocks, v -> cfg.destroyRetryMoveBlocks = v)
				.tip("A new vantage point can make a failed block reachable before its timer expires. "
						+ "Zero retries as soon as another selection pass reaches it."));

		add(new Section("Route"));
        addBaritoneRecommendation();
        if (cfg.baritoneNavigation) {
            add(new Toggle("Parkour across gaps", () -> cfg.baritoneParkour, v -> cfg.baritoneParkour = v));
            add(new Toggle("Place while crossing a gap", () -> cfg.baritoneParkourPlace, v -> cfg.baritoneParkourPlace = v)
                    .tip("Also needs bridging permission and expendable building blocks."));
            add(new Toggle("Climb vines", () -> cfg.baritoneVines, v -> cfg.baritoneVines = v));
            add(new Toggle("Use water buckets for long falls", () -> cfg.baritoneWaterBucketFalls, v -> cfg.baritoneWaterBucketFalls = v)
                    .tip("Requires a usable water bucket. Off keeps routes within the longest-drop limit."));
            add(new Slider("Navigation turn smoothing", 0, 1, 0.05, 2, " x", () -> cfg.baritoneTurnSmoothing, v -> cfg.baritoneTurnSmoothing = v)
                    .tip("1 gives full glide with continuous frame-by-frame camera motion. Sets the minimum smoothing for all Base destroyer actions. Higher smoothness does not lower the turn rate or hold movement keys."));
            add(new Slider("Maximum navigation turn per tick", 8, 90, 1, 0, " degrees", () -> cfg.baritoneTurnRate, v -> cfg.baritoneTurnRate = v));
            add(new Slider("Navigation aim variation", 0, 0.2, 0.01, 2, " degrees", () -> cfg.baritoneAimVariation, v -> cfg.baritoneAimVariation = v)
                    .tip("Bounded random aim variation, passed through the turn filter during navigation. Jumps, placement and landing keep the same smoothing."));
            add(new Slider("Extra time per terrain move", 3, 60, 1, 0, "s", () -> cfg.baritoneNoProgressSec, v -> cfg.baritoneNoProgressSec = v)
                    .tip("Added to the predicted movement cost before Baritone cancels a stalled step."));
            add(Slider.ints("Failed route attempts", 1, 20, () -> cfg.pathAttempts, v -> cfg.pathAttempts = v));
        }
		add(new Toggle("Mine through walls", () -> cfg.pathMine, v -> cfg.pathMine = v)
				.tip("Lets the route go through a block rather than round it. Without this the "
						+ "bot can only reach places it could already walk to."));
		add(new Toggle("Only break what I picked", () -> cfg.pathMineOnlySelected,
				v -> cfg.pathMineOnlySelected = v)
				.tip("The route digs through your selected blocks and nothing else. Turn this off "
						+ "to let it tunnel through anything breakable, which is the only way into "
						+ "a sealed room - and the way walls end up with holes in them."));
		add(new Toggle("Bridge across gaps", () -> cfg.pathBridge, v -> cfg.pathBridge = v)
				.tip("Places a block to stand on. Only uses the blocks listed below, and never "
						+ "from a protected slot."));
		if (!cfg.baritoneNavigation) add(new Toggle("Cut corners", () -> cfg.pathDiagonal, v -> cfg.pathDiagonal = v)
				.tip("Diagonal steps. Faster routes; a few more nodes to search."));
		add(Slider.ints("Longest drop", 1, 24, () -> cfg.pathMaxFall, v -> cfg.pathMaxFall = v)
				.tip("A drop taller than this is not a route. Fall damage starts past three."));
		add(Slider.ints("A broken block is worth", 1, 32,
				() -> cfg.pathMineCost, v -> cfg.pathMineCost = v)
				.tip("In walked blocks. High means \"go round if there is any way round\"; low "
						+ "means \"dig straight there\"."));
		add(Slider.ints("A placed block is worth", 1, 32,
				() -> cfg.pathPlaceCost, v -> cfg.pathPlaceCost = v));
		if (!cfg.baritoneNavigation) {
		add(Slider.ints("Search budget", 500, 60000, () -> cfg.pathMaxNodes, v -> cfg.pathMaxNodes = v)
				.tip("Nodes per plan. Running out is not a failure — the best partial route is "
						+ "walked anyway and replanned from further along."));
		add(new Slider("Settle for a route this much longer", 1, 3, 0.05, 2, " x",
				() -> cfg.pathHeuristicWeight, v -> cfg.pathHeuristicWeight = v)
				.tip("1 finds the shortest route there is and searches hardest for it. Above that "
						+ "it will accept a route up to this much longer in exchange for looking "
						+ "at far fewer places, which is nearly always the better trade on a "
						+ "client. Past about 1.5 it stops being a search and becomes a walk "
						+ "straight at the target, wall or no wall."));
		}
		add(new KeyValue("Last route", () -> "%d nodes searched · cost %.0f"
				.formatted(d.lastPathNodes, d.lastPathCost), () -> Ui.TEXT_MUTED));

		if (!cfg.baritoneNavigation) {
		add(new Section("How the route is walked"));
		add(new Slider("Planning per tick", 0.1, 25, 0.1, 1, "ms",
				() -> cfg.pathSliceMs, v -> cfg.pathSliceMs = v)
				.tip("A tick is fifty milliseconds, and a whole route can take twenty to work "
						+ "out. Spreading that over several ticks is the difference between a "
						+ "pause you can see and one you cannot."));
		add(new RangeSlider("Start the next leg with", 0.5, 30, 0.5, 1, "s left",
				() -> cfg.pathRefreshSec, v -> cfg.pathRefreshSec = v,
				() -> cfg.pathRefreshMaxSec, v -> cfg.pathRefreshMaxSec = v)
				.tip("A route that stops short is normal — the search budget runs out long before "
						+ "a base does — so the next leg gets planned behind the one being walked. "
						+ "Drawn fresh from this range every time: a fixed number is the one thing "
						+ "here anybody watching could see, because it is the moment the walk "
						+ "stops being smooth."));
		add(new RangeSlider("Wait after a plan comes to nothing", 0.1, 10, 0.05, 2, "s",
				() -> cfg.pathRestMinSec, v -> cfg.pathRestMinSec = v,
				() -> cfg.pathRestMaxSec, v -> cfg.pathRestMaxSec = v)
				.tip("Retrying on the next tick asks the same question of the same world from the "
						+ "same place, twenty times a second. Never zero."));
		add(Slider.ints("Give up on a destination after", 1, 20,
				() -> cfg.pathAttempts, v -> cfg.pathAttempts = v)
				.tip("Plans in a row that find nothing walkable before the place is called "
						+ "unreachable. A plan that got some of the way does not count — it moved "
						+ "us, so the next one starts somewhere new."));
		add(Slider.ints("Look this many moves ahead", 1, 32,
				() -> cfg.pathLookaheadMoves, v -> cfg.pathLookaheadMoves = v)
				.tip("Where the camera points between things it has to aim at. The next square is "
						+ "under a block away, and a heading to something that close swings hard "
						+ "as you close on it — so a filtered camera spends the whole route "
						+ "chasing a target that never settles."));
		add(Slider.ints("Check this many moves ahead", 1, 16,
				() -> cfg.pathVerifyAhead, v -> cfg.pathVerifyAhead = v)
				.tip("Stops the bot walking three blocks up a corridor to find the doorway it was "
						+ "routed through has been filled in."));
		add(new Slider("Off the route past", 1, 32, 0.5, 1, " blocks",
				() -> cfg.pathOffRouteBlocks, v -> cfg.pathOffRouteBlocks = v)
				.tip("Further than this from every square of the route and it is not that route "
						+ "being walked any more, so it is replanned from where we actually are. "
						+ "This is what a knockback, a teleport and a server putting you back all "
						+ "come out as."));
		add(new RangeSlider("Patience per step", 0.5, 30, 0.5, 1, "s",
				() -> cfg.pathMoveSlackSec, v -> cfg.pathMoveSlackSec = v,
				() -> cfg.pathMoveSlackMaxSec, v -> cfg.pathMoveSlackMaxSec = v)
				.tip("On top of what the step should physically take, which is worked out from "
						+ "the block and the tool. It is the only way to tell a slow block from "
						+ "an impossible one — a client is never told which it is — so claimed "
						+ "land and region protection end here rather than swinging forever."));

		add(new Slider("Replan a stalled walk after", 0.4, 10, 0.05, 2, "s",
				() -> cfg.pathStallSec, v -> cfg.pathStallSec = v)
				.tip("No progress towards the next step triggers a new route. Mining, placing and airborne motion use their own deadlines."));
		add(new Slider("Avoid a failed step for", 1, 60, 1, 0, "s",
				() -> cfg.pathFailedEdgeRetrySec, v -> cfg.pathFailedEdgeRetrySec = v)
				.tip("A replan tries another approach instead of repeating the same blocked transition."));

		}
        add(new Section("Protect mined drops"));
        add(new Toggle("Prepare a safe drop area before mining", () -> cfg.protectMiningDrops, v -> cfg.protectMiningDrops = v)
                .tip("Contains exposed lava and builds catch floors before breaking. Includes route digging. Requires solid, nonflammable building supplies; defers blocks whose protection cannot be completed."));
        add(Slider.ints("Check below each drop", 3, 64, () -> cfg.dropSafetyDepth, v -> cfg.dropSafetyDepth = v)
                .tip("Checks a 3 by 3 landing patch. A deeper shaft needs a catch floor."));
        add(new Slider("Time to prepare one mining site", 5, 180, 5, 0, "s", () -> cfg.prepareSiteSec, v -> cfg.prepareSiteSec = v));
        add(new Section("Building and liquid"));
		add(new Toggle("Cap liquid underfoot", () -> cfg.coverLiquids, v -> cfg.coverLiquids = v)
				.tip("Only a square right beside the feet. Liquid that is in the way is the "
						+ "route's problem and the route bridges over it, priced against the dry "
						+ "ground going the same direction — a standing scan of everything liquid "
						+ "nearby meant that in a base with a lava floor the job never ran once."));
		add(new Toggle("Cover lava", () -> cfg.coverLava, v -> cfg.coverLava = v));
		add(new Toggle("Cover water", () -> cfg.coverWater, v -> cfg.coverWater = v)
				.tip("Optional because water is usually an inconvenience rather than a lethal hazard. "
						+ "When enabled, the route also refuses to open walls that would flood it."));
		add(new Slider("Spend at most", 0.5, 30, 0.5, 1, "s on one square",
				() -> cfg.coverGiveUpSec, v -> cfg.coverGiveUpSec = v)
				.tip("Then leave it alone. A square with nothing to place a block against never "
						+ "becomes one by being stared at for longer."));
		add(new Slider("Then leave it alone for", 1, 600, 1, 0, "s",
				() -> cfg.coverRestSec, v -> cfg.coverRestSec = v));
		if (!cfg.baritoneNavigation) add(new Toggle("Sneak while placing", () -> cfg.bridgeSneak, v -> cfg.bridgeSneak = v)
				.tip("Stops the bot walking off the edge it is building from."));
		add(Slider.ints("Always keep back", 1, 64, () -> cfg.bridgeKeepBlocks,
				v -> cfg.bridgeKeepBlocks = v)
				.tip("Never spends a stack down to nothing, so there is always something left "
						+ "to climb out of a hole with."));

		add(new Section("Blocks it may place"));
		add(new TextInput("Find a block", null, () -> placeSearch, v -> {
			placeSearch = v;
			build();
		})
				.tip("Type part of a block name, then click an icon to pick it. Picked blocks "
						+ "stay at the front of the grid, so they do not move as you type."));

		// picked first, then whatever the search turned up, so the click targets hold still
		List<String> placeable = new ArrayList<>(cfg.buildingBlocks);
		for (String id : searchBlocks(placeSearch)) {
			// a block with no item is a block the bot could never place
			if (!placeable.contains(id) && !Widgets.ItemGrid.stackOf(id).isEmpty()) placeable.add(id);
		}
		if (placeable.isEmpty()) {
			add(new Note(placeSearch.isBlank()
					? "Nothing picked — it may place any block item that is not in a protected slot."
					: "Nothing matches \"" + placeSearch + "\".", Ui.TEXT_FAINT));
		} else {
			add(new Widgets.ItemGrid(placeable, cfg.buildingBlocks::contains, id -> {
				if (!cfg.buildingBlocks.remove(id)) cfg.buildingBlocks.add(id);
				build();
			}).tip("Click to pick or drop a block. With none picked it may place anything that "
					+ "is not in a protected slot."));
		}

		add(new Section("Breathing"));
		add(new Toggle("Come up for air", () -> cfg.watchAir, v -> cfg.watchAir = v)
				.tip("Watches the air bar and drops everything to surface before it empties — "
						+ "above the fight, because a fight underwater with no air left is not "
						+ "one worth winning. Separate from \"Treat water as a wall\" on the "
						+ "Obstacles tab: that one is about not getting in, this is the only one "
						+ "of the two that helps once you already are."));
		add(new Slider("Surface with", 1, 14, 0.5, 1, "s left",
				() -> cfg.airSecondsLeft, v -> cfg.airSecondsLeft = v)
				.tip("A full bar is fifteen seconds. Once it starts climbing it keeps climbing "
						+ "until it is breathing again, rather than bobbing at the line."));
		add(new Toggle("Dig up through a ceiling", () -> cfg.airMineCeiling,
				v -> cfg.airMineCeiling = v)
				.tip("Swimming up is enough in open water. It is not when you have just mined "
						+ "into an aquifer from below and the way out is the stone above your "
						+ "head — which is the way the job usually gets itself wet."));

		add(new Section("Drops"));
		add(new Toggle("Pick things up", () -> cfg.collectDrops, v -> cfg.collectDrops = v)
				.tip("Never mid-swing at a block already in reach: that block takes a second and "
						+ "the drop lasts five minutes, and walking off throws away the progress "
						+ "on both."));
		add(Slider.ints("Pick up within", 1, 48, () -> cfg.collectRadius, v -> cfg.collectRadius = v));
		add(new Slider("Give up on one after", 2, 300, 1, 0, "s",
				() -> cfg.collectGiveUpSec, v -> cfg.collectGiveUpSec = v)
				.tip("A pile behind a wall the route may not break looks exactly like a pile two "
						+ "steps away until the time has been spent proving otherwise. Written off "
						+ "temporarily, then tried again after the retry delay or if the item moves."));

		add(new Slider("Retry an unreachable drop after", 1, 120, 1, 0, "s",
				() -> cfg.collectRetrySec, v -> cfg.collectRetrySec = v));
		add(new Slider("Wait for pickup confirmation", 0.2, 3, 0.05, 2, "s",
				() -> cfg.collectPickupWaitSec, v -> cfg.collectPickupWaitSec = v)
				.tip("Time to allow for pickup delay once physically in range. Items that remain are deferred."));
		add(new Slider("Collect for at most per batch", 0.5, 30, 0.5, 1, "s",
				() -> cfg.collectBatchSec, v -> cfg.collectBatchSec = v)
				.tip("Yields between completed journeys once this time has elapsed. An active bridge or descent is allowed to finish."));
		add(new Toggle("Mine or bridge to reach drops", () -> cfg.collectAllowEdits,
				v -> cfg.collectAllowEdits = v)
				.tip("Off uses walking, doors, steps and safe drops. On permits the same mining and building rules as the main job."));

		add(new Section("When it runs out"));
		add(Slider.ints("Empty scans before done", 1, 10,
				() -> cfg.destroyEmptyScansToFinish, v -> cfg.destroyEmptyScansToFinish = v)
				.tip("Requires this many fresh, complete scans with zero selected blocks. Three "
						+ "filters out transient chunk updates without making a genuinely finished job "
						+ "wait very long."));
		add(new Toggle("Allow done with unloaded chunks", () -> cfg.destroyAllowIncompleteScanFinish,
				v -> cfg.destroyAllowIncompleteScanFinish = v)
				.tip("Off means every chunk touched by the search circle must be loaded before an "
						+ "empty scan counts. Turn this on only if the radius deliberately extends beyond "
						+ "your loaded distance.")
				.risk("calling an unseen part of the search area empty"));
		add(new Toggle("Stop when nothing is left", () -> cfg.destroyStopWhenDone,
				v -> cfg.destroyStopWhenDone = v));
		add(reaction("When the job is done", () -> cfg.destroyDoneReaction,
				v -> cfg.destroyDoneReaction = v));
		add(new Toggle("Log every block", () -> cfg.destroyLogTargets, v -> cfg.destroyLogTargets = v)
				.tip("Writes a coordinate for every block it sets out to break and every one it "
						+ "finishes. Thorough, and a lot of lines."));

		add(new Section("Humanisation"));
		add(new RangeSlider("Reaction before starting a target", 0.05, 5, 0.05, 2, "s",
				() -> cfg.taskReactionMinSec, v -> cfg.taskReactionMinSec = v,
				() -> cfg.taskReactionMaxSec, v -> cfg.taskReactionMaxSec = v)
				.tip("A pause between noticing something and acting on it. Nothing else in the "
						+ "job has a constant delay, and a constant zero is the easiest thing "
						+ "in the world to notice."));
		add(new Slider("Pause on this share of targets", 0, 1, 0.05, 2, " x",
				() -> cfg.taskReactionChance, v -> cfg.taskReactionChance = v)
				.tip("1 pauses before every newly selected block; 0 never does. A probability avoids "
						+ "turning reaction time into a fixed rhythm across a wall of adjacent blocks."));
		add(new Slider("Aim smoothing while working", 0, 1, 0.01, 2, "",
				() -> cfg.taskAimSmoothing, v -> cfg.taskAimSmoothing = v)
				.tip("Rounds acceleration and braking with a finite settling time, even at 1. Base destroyer uses at least the navigation smoothing for every action. Turn speed is controlled separately below."));
		add(new Slider("Aim wobble while working", 0, 1, 0.05, 2, " x",
				() -> cfg.taskAimWobbleScale, v -> cfg.taskAimWobbleScale = v)
				.tip("Scales working wobble; 0 disables it. Noise is reduced when it would move the crosshair off a small block."));
		add(new Slider("Aim point variation", 0, 0.4, 0.01, 2, " x",
				() -> cfg.taskAimPointSpread, v -> cfg.taskAimPointSpread = v)
				.tip("A stable random offset inside each visible face, held for the whole swing. 0 uses the face centre."));
		add(new Slider("Maximum working turn per tick", 2, 90, 1, 0, "\u00b0",
				() -> cfg.taskAimMaxTurnDeg, v -> cfg.taskAimMaxTurnDeg = v)
				.tip("Caps large camera turns while preserving the smoothing setting. Higher turns to new blocks faster."));
		add(new Toggle("Sprint between blocks", () -> cfg.destroySprint, v -> cfg.destroySprint = v));
		add(new Toggle("Work through interruptions", () -> cfg.destroyerKeepWorking,
				v -> cfg.destroyerKeepWorking = v)
				.tip("The safety stops above were written for a bot that wanders quietly. A base "
						+ "destroyer trips them by doing its job - rooms full of chests, getting "
						+ "hit, wading, stalling in a doorway - so while it is working they alert "
						+ "and log instead of stopping. Low health, hunger, another player and the "
						+ "runtime limit still stop it."));
	}

	// ------------------------------------------------------- blocks to mine

	private void buildBlocks() {
		add(new Section("Families"));
		add(new Note("Rules rather than lists, so they keep working when a base is built out of "
				+ "something you did not think of.", Ui.TEXT_FAINT));
		add(new Widgets.Chips<>(Arrays.asList(BlockTargets.Family.values()),
				fam -> fam.label, fam -> accent(), null,
				cfg::destroyFamily,
				(fam, on) -> {
					cfg.setDestroyFamily(fam, on);
					build();
				}));
		for (BlockTargets.Family fam : BlockTargets.Family.values()) {
			if (cfg.destroyFamily(fam)) add(new Note(fam.label + ": " + fam.tip, Ui.TEXT_FAINT));
		}

		add(new Section("Add a block by name"));
		add(new TextInput("Search", null, () -> blockSearch, v -> {
			blockSearch = v;
			build();
		})
				.tip("Type part of a block id — the name F3 shows you. Click a result to add it."));
		List<String> matches = searchBlocks(blockSearch);
		if (!matches.isEmpty()) {
			add(new Widgets.Chips<>(matches, id -> id, id -> Ui.GOOD, null,
					id -> cfg.destroyBlocks.contains(id),
					(id, on) -> {
						if (on) cfg.destroyBlocks.add(id);
						else cfg.destroyBlocks.remove(id);
						build();
					}));
		} else if (!blockSearch.isBlank()) {
			add(new Note("Nothing matches \"" + blockSearch + "\".", Ui.TEXT_FAINT));
		}

		add(new Section("Picked by hand"));
		if (cfg.destroyBlocks.isEmpty()) {
			add(new Note("Nothing yet — the families above are doing all the work.", Ui.TEXT_FAINT));
		} else {
			add(new Widgets.Chips<>(new ArrayList<>(cfg.destroyBlocks), id -> id, id -> Ui.GOOD, null,
					id -> true,
					(id, on) -> {
						cfg.destroyBlocks.remove(id);
						build();
					}).tip("Click to remove."));
		}

		add(new Section("Never break these"));
		add(new TextInput("Excluded ids, comma separated", null,
				() -> String.join(", ", cfg.destroyExclude),
				v -> cfg.destroyExclude = splitList(v))
				.tip("Wins over everything above. Bedrock, portals, command blocks and anything "
						+ "the game says is unbreakable are already excluded and cannot be added."));
		add(new KeyValue("Selected in total", this::countSelectedBlocks, () -> accent()));
	}

	/** Block ids matching what has been typed, capped so the page stays a page. */
	private static List<String> searchBlocks(String query) {
		List<String> out = new ArrayList<>();
		String needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
		if (needle.length() < 2) return out;
		try {
			for (var block : net.minecraft.core.registries.BuiltInRegistries.BLOCK) {
				var id = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(block);
				if (id == null) continue;
				String path = id.getPath();
				if (path.contains(needle)) out.add(path);
				if (out.size() >= 24) break;
			}
		} catch (Exception | LinkageError e) {
			// no registry outside a game: an empty list is the honest answer
		}
		return out;
	}

	private String countSelectedBlocks() {
		try {
			return new BlockTargets().blocks(cfg).size() + " kinds of block";
		} catch (Exception | LinkageError e) {
			return "join a world to count";
		}
	}

	private static List<String> splitList(String raw) {
		List<String> out = new ArrayList<>();
		if (raw == null) return out;
		for (String part : raw.split(",")) {
			String t = part.trim().toLowerCase(Locale.ROOT);
			if (!t.isEmpty()) out.add(t);
		}
		return out;
	}

	// ----------------------------------------------------------- inventory

	private void buildInventory() {
		add(new Toggle("Store collected items in containers", () -> cfg.storageEnabled, v -> cfg.storageEnabled = v));
		add(new Action("Choose storage containers, items and order", false, () -> { activeTab = Tab.STORAGE; revealActiveTab(); build(); }));
		add(new Section("Your slots"));
		add(new Note("Left click a slot to protect it — never sold, never dropped, never spent "
				+ "as scaffolding. Right click to mark it for sale.", Ui.TEXT_MUTED));
		add(new SlotGrid(cfg));
		add(new Row(List.of(
				new Action("Protect hotbar", false, () -> {
					SlotGrid.protectHotbar(cfg);
					build();
				}),
				new Action("Protect all", false, () -> {
					SlotGrid.protectAll(cfg, true);
					build();
				}),
				new Action("Clear protection", false, () -> {
					SlotGrid.protectAll(cfg, false);
					build();
				}))));
		add(new Row(List.of(
				new Action("Sell everything unprotected", false, () -> {
					SlotGrid.sellRest(cfg, true);
					build();
				}),
				new Action("Clear the sale list", false, () -> {
					SlotGrid.sellRest(cfg, false);
					build();
				}))));
		add(new KeyValue("Bag", this::describeBag, () -> Ui.TEXT_MUTED));

		add(new Section("When it fills up"));
		add(new Slider("Counts as full at", 0.1, 1.0, 0.05, 2, " x",
				() -> cfg.inventoryFullFraction, v -> cfg.inventoryFullFraction = v)
				.tip("Of the slots the bot is allowed to touch — protected slots are not part "
						+ "of the sum, so protecting half your bag does not make it full."));
		add(new Toggle("Stop when full", () -> cfg.stopWhenInventoryFull,
				v -> cfg.stopWhenInventoryFull = v)
				.tip("With selling off and nothing left to throw away, this is what stops it "
						+ "mining into a floor it cannot pick up."));
		add(reaction("When the bag is full", () -> cfg.inventoryFullReaction,
				v -> cfg.inventoryFullReaction = v));
		add(new Toggle("Move blocks up to the hotbar", () -> cfg.restockHotbar,
				v -> cfg.restockHotbar = v)
				.tip("Shift-clicks a stack of building blocks up when the bar runs dry."));

		add(new Section("Rubbish"));
		add(new Toggle("Throw away junk", () -> cfg.dropJunk, v -> cfg.dropJunk = v)
				.tip("One stack at a time, and never from a protected slot. Thirty-six throws "
						+ "in one tick is the most obvious thing this mod could send, so it "
						+ "does not."));
		add(new TextInput("Find a block or item", null, () -> junkSearch, v -> {
			junkSearch = v;
			build();
		}).tip("Type at least two letters of a name, then click an icon to mark it as junk. "
				+ "Selected items stay at the front of the grid."));

		List<String> junk = new ArrayList<>(cfg.junkItems);
		List<String> matches = searchItems(junkSearch);
		for (String id : matches) {
			if (!cfg.isJunk(id)) junk.add(id);
		}
		if (junkSearch.trim().length() >= 2 && matches.isEmpty()) {
			add(new Note("Nothing matches \"" + junkSearch + "\".", Ui.TEXT_FAINT));
		}
		add(new Note(cfg.junkItems.isEmpty()
				? "Nothing selected — no items will be thrown away."
				: "Highlighted items are junk. Click an icon to add or remove it.", Ui.TEXT_FAINT));
		if (!junk.isEmpty()) {
			add(new Widgets.ItemGrid(junk, cfg.junkItems::contains, id -> {
				if (!cfg.junkItems.remove(id)) cfg.junkItems.add(id);
				build();
			}));
		}
	}

	private void buildStorage() {
		add(new Section("Collected item storage"));
		add(new Toggle("Store collected items in containers", () -> cfg.storageEnabled, v -> cfg.storageEnabled = v));
		add(new Note("Stores matching unprotected stacks, including any already in your bag. Choose each container, "
				+ "its allowed slots and its item filter below. Empty filters store nothing. Runs before selling and junk disposal.", Ui.TEXT_MUTED));
		add(new KeyValue("Storage", () -> ctl.storage.status, () -> ctl.storage.failed() ? Ui.WARN : Ui.TEXT_MUTED));
		if (ctl.storage.busy()) {
			add(new Note("A storage trip is running. Container rules can be edited once it finishes.", Ui.TEXT_MUTED));
			add(new Action("Stop storage trip", false, () -> { ctl.stop(minecraft, "Storage cancelled from menu"); build(); }));
			return;
		}
		if (ctl.storage.failed()) {
			add(new Note("Check the reported sites and recover any containers left there before clearing the stop. "
					+ "Movement stays off until you start it again.", Ui.WARN));
			add(new Action("I have checked the containers — clear storage stop", false, () -> { ctl.storage.acknowledge(); build(); }));
		}
		add(new Toggle("Return shulkers to their original ender-chest slots", () -> cfg.storageReturnShulkers,
				v -> cfg.storageReturnShulkers = v).tip("Off keeps the filled shulkers in your bag and changes their routes to bag shulkers."));
		add(new Slider("Keep away from every other player", 16, 128, 4, 0, " blocks",
				() -> cfg.storagePlayerRadius, v -> cfg.storagePlayerRadius = v));
		add(new Note("Placement needs solid ground and at least 5 blocks of clearance from all liquids, including waterlogged blocks. "
				+ "Shulkers go next to the ender chest, one at a time. Keep two bag slots empty and one hotbar slot unprotected. "
				+ "Ender-chest access requires a Silk Touch pickaxe; placed containers are recovered afterward.", Ui.TEXT_FAINT));
		add(new Section("Choose a shulker or ender chest from your inventory"));
		add(new SlotGrid(36, true, "Click a shulker to configure it; click an ender chest to inspect it",
				i -> minecraft.player == null ? net.minecraft.world.item.ItemStack.EMPTY : minecraft.player.getInventory().getItem(i),
				i -> storageTarget != null && storageTarget.kind == Storage.Kind.INVENTORY_SHULKER && storageTarget.inventorySlot == i,
				(i, button) -> {
					if (minecraft.player == null) return;
					var stack = minecraft.player.getInventory().getItem(i);
					if (stack.is(net.minecraft.world.item.Items.ENDER_CHEST)) {
						storageTarget = ctl.storage.enderTarget();
						ctl.storage.inspect(minecraft, storageTarget, this);
					} else {
						Storage.Target t = ctl.storage.selectShulker(minecraft, i, false);
						if (t != null) storageTarget = t;
						build();
					}
				}));
		List<net.minecraft.world.item.ItemStack> ender = ctl.storage.enderView();
		if (!ender.isEmpty()) {
			add(new Section("Inside your ender chest · last inspection"));
			add(new Note("Click a shulker here to configure its contents. Use the Ender chest destination below to choose "
					+ "slots for loose items. Inspect again after manually changing the chest.", Ui.TEXT_MUTED));
			add(new SlotGrid(27, false, "Click one or more shulkers to create their individual routes", ender::get,
					i -> cfg.storageTargets.stream().anyMatch(t -> t.kind == Storage.Kind.ENDER_SHULKER && t.enderSlot == i
							&& t.world.equals(WorldId.current())),
					(i, button) -> { Storage.Target t = ctl.storage.selectShulker(minecraft, i, true); if (t != null) storageTarget = t; build(); }));
		}
		add(new Section("Nearby placed chests, barrels and shulkers"));
		add(new Action("Refresh nearby containers", false, this::build));
		if (minecraft.player != null && minecraft.level != null) {
			var origin = minecraft.player.blockPosition();
			int found = 0;
			for (var p : net.minecraft.core.BlockPos.betweenClosed(origin.offset(-6, -3, -6), origin.offset(6, 3, 6))) {
				if (!minecraft.level.hasChunk(p.getX() >> 4, p.getZ() >> 4)
						|| !Storage.supported(minecraft.level.getBlockState(p).getBlock())) continue;
				var pos = p.immutable();
				String label = minecraft.level.getBlockState(pos).getBlock().getName().getString() + " · " + pos.toShortString();
				add(new Action("Inspect " + label, false, () -> {
					storageTarget = ctl.storage.selectWorld(minecraft, pos);
					ctl.storage.inspect(minecraft, storageTarget, this);
				}));
				if (++found >= 24) break;
			}
			if (found == 0) add(new Note("No supported containers within 6 blocks.", Ui.TEXT_FAINT));
		}
		List<Storage.Target> routes = cfg.storageTargets.stream().filter(t -> t.world.equals(WorldId.current())).toList();
		if (routes.isEmpty()) return;
		if (!routes.contains(storageTarget)) storageTarget = routes.getFirst();
		Storage.Target t = storageTarget;
		add(new Section("Container rules"));
		add(new Cycle<>("Destination", routes, r -> r.name,
				() -> storageTarget, r -> { storageTarget = r; build(); }));
		add(new Toggle("Use this destination", () -> t.enabled, v -> t.enabled = v));
		add(new Action("Remove this destination", false, () -> { cfg.storageTargets.remove(t); storageTarget = null; build(); }));
		add(new Note("Highlighted destination slots may receive items. Click cells to toggle them. Existing stacks stay where they are; "
				+ "the order controls how new deposits fill compatible or empty slots. Shulkers cannot go inside shulkers.", Ui.TEXT_MUTED));
		List<net.minecraft.world.item.ItemStack> view = ctl.storage.view(t);
		add(new SlotGrid(t.size, false, "Destination slots · last known contents", view::get, t.slots::contains,
				(i, button) -> { if (!t.slots.remove(Integer.valueOf(i))) t.slots.add(i); }));
		add(new Row(List.of(new Action("Select all slots", false, () -> {
			t.slots.clear(); for (int i = 0; i < t.size; i++) t.slots.add(i);
		}), new Action("Clear slots", false, t.slots::clear))));
		add(new Cycle<>("Deposit items by", List.of(Storage.ItemOrder.values()), r -> switch (r) {
			case INVENTORY -> "Inventory order"; case NAME -> "Item name";
			case LARGEST_STACK -> "Largest stack first"; case FILTER_ORDER -> "Filter selection order";
		}, () -> t.itemOrder, v -> t.itemOrder = v));
		add(new Cycle<>("Fill destination by", List.of(Storage.SlotOrder.values()), r -> switch (r) {
			case ROWS -> "Rows, top left first"; case REVERSE_ROWS -> "Rows, bottom right first"; case COLUMNS -> "Columns, top to bottom";
		}, () -> t.slotOrder, v -> t.slotOrder = v));
		add(new Section("Items for this container"));
		add(new SlotGrid(36, true, "Click carried items to add/remove their item types",
				i -> minecraft.player == null ? net.minecraft.world.item.ItemStack.EMPTY : minecraft.player.getInventory().getItem(i),
				i -> minecraft.player != null && t.accepts(net.minecraft.core.registries.BuiltInRegistries.ITEM
						.getKey(minecraft.player.getInventory().getItem(i).getItem()).toString()),
				(i, button) -> {
					if (minecraft.player == null) return;
					var stack = minecraft.player.getInventory().getItem(i);
					if (stack.isEmpty() || Storage.shulker(stack) || stack.is(net.minecraft.world.item.Items.ENDER_CHEST) || Storage.silk(stack)) return;
					String id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
					if (!t.items.remove(id)) t.items.add(id); build();
				}));
		add(new TextInput("Find items to store", null, () -> storageSearch, v -> { storageSearch = v; build(); }));
		List<String> items = new ArrayList<>(t.items);
		for (String match : searchItems(storageSearch)) if (!items.contains(Storage.id(match))) items.add(Storage.id(match));
		add(new Note(t.items.isEmpty() ? "No items selected — nothing will be stored here." : "Selected types, in filter order: "
				+ String.join(", ", t.items).replace("minecraft:", ""), Ui.TEXT_FAINT));
		if (!items.isEmpty()) add(new Widgets.ItemGrid(items, t.items::contains, id -> {
			if (!t.items.remove(Storage.id(id))) t.items.add(Storage.id(id)); build();
		}));
	}

	/** Search inventory items, including blocks, by display name or registry id. */
	private static List<String> searchItems(String query) {
		List<String> out = new ArrayList<>();
		String needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
		if (needle.length() < 2) return out;
		try {
			for (var item : net.minecraft.core.registries.BuiltInRegistries.ITEM) {
				var stack = item.getDefaultInstance();
				if (stack.isEmpty()) continue;
				var id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item);
				if (id == null) continue;
				if (id.toString().contains(needle)
						|| stack.getHoverName().getString().toLowerCase(Locale.ROOT).contains(needle)) {
					out.add(id.getNamespace().equals("minecraft") ? id.getPath() : id.toString());
				}
				if (out.size() >= 24) break;
			}
		} catch (Exception | LinkageError e) {
			// Registries are unavailable outside a game.
		}
		return out;
	}

	private String describeBag() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) return "not in a world";
		return "%.0f%% full · %d stacks marked for sale"
				.formatted(ctl.destroyer.backpack.fullness(mc.player) * 100,
						ctl.destroyer.backpack.countForSale(mc.player));
	}

	// ------------------------------------------------------------- selling

	private void buildSelling() {
		add(new Section("Auto sell"));
		add(new Toggle("Sell automatically", () -> cfg.autoSellEnabled, v -> cfg.autoSellEnabled = v)
				.tip("Sends the command below, waits for the menu, shift-clicks everything from "
						+ "the slots you marked for sale into it, clicks the confirm button and "
						+ "closes it. Every step waits a randomised moment.")
				.risk("sending a command and clicking a menu on a timer"));
		add(new KeyValue("Status", () -> ctl.destroyer.backpack.status, () -> Ui.TEXT_MUTED));
		add(new KeyValue("So far", () -> "%d runs · %d stacks moved"
				.formatted(ctl.destroyer.backpack.sellRuns, ctl.destroyer.backpack.soldStacks),
				() -> Ui.TEXT_MUTED));

		add(new Section("The command"));
		add(new TextInput("Command, without the slash", null,
				() -> cfg.sellCommand, v -> cfg.sellCommand = v.trim().replaceFirst("^/", "")));
		add(new TextInput("Menu title must contain (optional)", null,
				() -> cfg.sellMenuTitleContains, v -> cfg.sellMenuTitleContains = v.trim())
				.tip("When set, the bot will never click a container whose title does not contain this "
						+ "text. Useful when another plugin or lag can open a different menu."));
		add(new TextInput("Confirm button item", null,
				() -> cfg.sellConfirmItem, v -> cfg.sellConfirmItem = v.trim())
				.tip("The item id on the button that completes the sale — on most servers that "
						+ "is lime_stained_glass_pane. Named rather than positioned, because the "
						+ "position is the server's choice and the item is what you are looking "
						+ "at. Among matches the bottom-right one is clicked."));
		add(Slider.ints("...or slot number", -1, 60,
				() -> cfg.sellConfirmSlot, v -> cfg.sellConfirmSlot = v)
				.tip("Counted from the top left of the menu, used only when the item above "
						+ "matches nothing. -1 turns it off."));
		add(new Toggle("Only confirm if something went in", () -> cfg.sellRequiresDeposit,
				v -> cfg.sellRequiresDeposit = v)
				.tip("Stops it pressing a button on an empty menu."));

		add(new Section("When"));
		add(new Toggle("Sell even when the bag is not full", () -> cfg.autoSellAlways,
				v -> cfg.autoSellAlways = v));
		add(Slider.ints("At least", 1, 36, () -> cfg.autoSellMinStacks, v -> cfg.autoSellMinStacks = v)
				.tip("Stacks sitting in slots marked for sale before it is worth a trip."));
		add(new Slider("No more often than", 5, 1800, 5, 0, "s",
				() -> cfg.autoSellCooldownSec, v -> cfg.autoSellCooldownSec = v));

		add(new Section("Timing"));
		add(new RangeSlider("Wait for the menu", 0.1, 8, 0.1, 2, "s",
				() -> cfg.sellDelayMinSec, v -> cfg.sellDelayMinSec = v,
				() -> cfg.sellDelayMaxSec, v -> cfg.sellDelayMaxSec = v)
				.tip("A menu arrives a packet after the command, and a confirm pressed before "
						+ "the menu has repainted sells nothing."));
		add(new RangeSlider("Between clicks", 0.05, 3, 0.05, 2, "s",
				() -> cfg.sellClickMinSec, v -> cfg.sellClickMinSec = v,
				() -> cfg.sellClickMaxSec, v -> cfg.sellClickMaxSec = v)
				.tip("One shift-click per step, spaced out. A whole inventory emptied in a "
						+ "single tick is not something a mouse can do."));
		add(new Note("Nothing here can be set to zero. Every delay has a floor, because the "
				+ "point of the delay is that it exists.", Ui.TEXT_FAINT));
	}

	// -------------------------------------------------------------- combat

	private void buildCombat() {
		add(new Section("Fighting back"));
		add(new Toggle("Fight back", () -> cfg.combatEnabled, v -> cfg.combatEnabled = v)
				.tip("Faces whatever is attacking and swings at it. Discrete swings that wait "
						+ "for the cooldown, not a held button."));
		add(new KeyValue("Now", () -> ctl.destroyer.combat.status, () -> Ui.TEXT_MUTED));
		add(new Toggle("Only once something hits you", () -> cfg.combatOnlyWhenAttacked,
				v -> cfg.combatOnlyWhenAttacked = v)
				.tip("On, the bot defends and does not hunt. Off, it swings at anything hostile "
						+ "in range — including things behind a wall that it could not have "
						+ "seen, which is the version that gets noticed.")
				.risk("attacking mobs before any of them has touched you"));
		add(new Toggle("Mobs", () -> cfg.combatFightMobs, v -> cfg.combatFightMobs = v));
		add(new Toggle("Players", () -> cfg.combatFightPlayers, v -> cfg.combatFightPlayers = v)
				.tip("Off by default. A bot that swings at people is a different thing from a "
						+ "bot that mines.")
				.risk("swinging at other players automatically"));
		add(new Slider("Notice within", 2, 48, 1, 0, " blocks",
				() -> cfg.combatRadius, v -> cfg.combatRadius = v));
		add(new Slider("Stays a threat for", 1, 60, 1, 0, "s",
				() -> cfg.combatMemorySec, v -> cfg.combatMemorySec = v)
				.tip("After the last hit it landed."));
		add(new Toggle("Chase it", () -> cfg.combatChase, v -> cfg.combatChase = v)
				.tip("Walks towards a target that is out of arm's reach. Off, it hits whatever "
						+ "comes to it and carries on with the job."));

		add(new Section("Swinging"));
		add(new Slider("Wait for charge", 0.1, 1, 0.05, 2, " x",
				() -> cfg.combatMinCharge, v -> cfg.combatMinCharge = v)
				.tip("A swing at forty percent charge does forty percent damage, and nobody "
						+ "swings that way on purpose."));
		add(new RangeSlider("Gap between swings", 0.05, 3, 0.05, 2, "s",
				() -> cfg.combatSwingMinSec, v -> cfg.combatSwingMinSec = v,
				() -> cfg.combatSwingMaxSec, v -> cfg.combatSwingMaxSec = v)
				.tip("On top of the cooldown, because a perfectly periodic swing is a signature."));
		add(new Toggle("Raise a shield", () -> cfg.combatUseShield, v -> cfg.combatUseShield = v)
				.tip("While closing the distance, if there is one in the off hand."));

		add(new Section("Losing"));
		add(new Toggle("Back away when hurt", () -> cfg.combatRetreat, v -> cfg.combatRetreat = v));
		add(new Slider("Back away below", 0, 20, 0.5, 1, " hearts",
				() -> cfg.combatRetreatHealth, v -> cfg.combatRetreatHealth = v));
		add(new Note("With this on and the destroyer running, \"Stop when damaged\" on the "
				+ "Safety tab stands down — the two are contradictory instructions, and with "
				+ "both on the stop always wins, on the same tick as the hit. Low health, and "
				+ "backing away above, are what keep the bot alive instead.", Ui.TEXT_MUTED));
	}

	private void buildGoto() {
		add(new Section("Destination"));
		add(new Toggle("Walk to these coordinates", () -> cfg.gotoEnabled, v -> {
			cfg.gotoEnabled = v;
			if (v) cfg.areaEnabled = false;
		}).tip("Steers toward the target. The area sweep takes priority over this."));
		add(new Row(List.of(
				new TextInput("X", NUMERIC, () -> fmt(cfg.gotoX), v -> cfg.gotoX = parse(v, cfg.gotoX)),
				new TextInput("Z", NUMERIC, () -> fmt(cfg.gotoZ), v -> cfg.gotoZ = parse(v, cfg.gotoZ)))));
		add(new Row(List.of(
				new Action("Use my position", false, () -> {
					if (minecraft != null && minecraft.player != null) {
						cfg.gotoX = Math.round(minecraft.player.getX());
						cfg.gotoZ = Math.round(minecraft.player.getZ());
						cfg.gotoY = Math.round(minecraft.player.getY());
					}
				}),
				new Action("Clear", false, () -> {
					cfg.gotoX = 0;
					cfg.gotoZ = 0;
					cfg.gotoEnabled = false;
				}))));
		add(new Toggle("Note the Y too", () -> cfg.gotoUseY, v -> cfg.gotoUseY = v)
				.tip("Informational only — the mod never digs, builds or climbs on purpose."));
		add(new TextInput("Y", NUMERIC, () -> fmt(cfg.gotoY), v -> cfg.gotoY = parse(v, cfg.gotoY)));

		add(new Section("Steering"));
		add(new Toggle("Walk there with the router", () -> cfg.gotoPathfind, v -> {
			cfg.gotoPathfind = v;
			build();
		})
				.tip("Off, the bot leans on the bearing: it knows which way the target is and "
						+ "nothing about what is between here and there, which is enough across a "
						+ "field and useless in a building. On, the same search the base destroyer "
						+ "uses plans the way - round the wall, through the door, down the drop - "
						+ "and the same follower walks it, through the same camera. The route "
						+ "settings live on the Base destroyer tab; if it cannot find a way it "
						+ "says so once and goes back to the bearing."));
		Element bearing = new Slider("Arrive within", 0.5, 32, 0.5, 1, " blocks",
				() -> cfg.gotoArriveRadius, v -> cfg.gotoArriveRadius = v);
		add(bearing);
		if (cfg.gotoPathfind) {
			add(new KeyValue("Route", () -> ctl.route.walking()
					? "step %d of %d".formatted(ctl.route.step() + 1, ctl.route.length())
					: ctl.route.status.isEmpty() ? "—" : ctl.route.status, () -> accent()));
			add(new Note("The wander, the correction speed and the allowed wander below are the "
					+ "bearing's, and do not apply while the router is walking a route.",
					Ui.TEXT_FAINT));
		}
		add(new Slider("Correction speed", 0.05, 8, 0.05, 2, "°/tick",
				() -> cfg.gotoCorrectionDegPerTick, v -> cfg.gotoCorrectionDegPerTick = v)
				.tip("How hard it pulls back onto the bearing. Low is lazier and more natural."));
		add(new Slider("Allowed wander", 0, 90, 1, 0, "°",
				() -> cfg.gotoMaxWanderDeg, v -> cfg.gotoMaxWanderDeg = v));
		flag("No wander off the bearing");
		add(new Slider("Give up after no progress for", 0, 600, 5, 0, "s",
				() -> cfg.gotoNoProgressSec, v -> cfg.gotoNoProgressSec = v)
				.tip("0 disables the watchdog. Applies to the area sweep too."));

		add(new Section("On arrival"));
		add(new Toggle("Stop when I get there", () -> cfg.gotoStopOnArrive, v -> cfg.gotoStopOnArrive = v));
		add(reaction("Arrival reaction", () -> cfg.gotoArriveReaction, v -> cfg.gotoArriveReaction = v));
		add(new KeyValue("Distance remaining",
				() -> ctl.gotoDistance < 0 ? "—" : String.format(Locale.ROOT, "%.1f blocks", ctl.gotoDistance),
				() -> accent()));
	}

	private void buildContainers() {
		add(new Section("Storage density"));
		add(new Toggle("Watch for chests and hoppers", () -> cfg.containerScanEnabled, v -> cfg.containerScanEnabled = v));
		add(new Note("Range is not limited by what is drawn on screen — it sees through walls,", Ui.TEXT_FAINT));
		add(new Note("behind you, and hundreds of blocks straight down.", Ui.TEXT_FAINT));
		add(new Toggle("Reach as far as the client can see", () -> cfg.containerAutoRadius, v -> cfg.containerAutoRadius = v)
				.tip("Follows your effective render distance. Nothing client-side can see past that — the server has not sent those chunks."));
		add(Slider.ints("Chunk radius", 0, 64, () -> cfg.containerChunkRadius, v -> cfg.containerChunkRadius = v)
				.tip("Used when the automatic radius is off. Chunks the server has not sent are skipped, not guessed at."));
		add(new Section("Height"));
		add(new Cycle<>("Look between", Arrays.asList(Config.HeightRange.values()), r -> r.label,
				() -> cfg.containerHeightRange, v -> {
			cfg.containerHeightRange = v;
			build();
		}).tip("Whole world finds everything at any depth. A band finds only what you are after: "
				+ "buried bases, or a skybase, without the surface chests in between."));
		add(new Note(Config.HeightRange.values()[Math.max(0,
				Arrays.asList(Config.HeightRange.values()).indexOf(cfg.containerHeightRange))].tip,
				Ui.TEXT_FAINT));

		boolean band = cfg.containerHeightRange == Config.HeightRange.ABSOLUTE;
		boolean around = cfg.containerHeightRange == Config.HeightRange.RELATIVE;

		Element between = new RangeSlider("Between y", -64, 320, 1, 0, "",
				() -> cfg.containerMinY, v -> cfg.containerMinY = (int) Math.round(v),
				() -> cfg.containerMaxY, v -> cfg.containerMaxY = (int) Math.round(v))
				.tip("Only containers inside this band are counted, alerted on and logged. "
						+ "Landmarks follow the same band.");
		between.enabled = band;
		add(between);

		Element quick = new Row(List.of(
				new Action("Deep  -64 to 16", false, () -> setBand(-64, 16)),
				new Action("Surface  16 to 100", false, () -> setBand(16, 100)),
				new Action("High  100 to 320", false, () -> setBand(100, 320))));
		quick.enabled = band;
		add(quick);

		Element mine = new Row(List.of(
				new Action("Below me", false, () -> {
					int y = playerY();
					setBand(y - 128, y - 4);
				}).tip("From 128 blocks under your feet to just below them - digs out cellars and "
						+ "buried storage without counting anything on the surface."),
				new Action("Around my y \u00b132", false, () -> {
					int y = playerY();
					setBand(y - 32, y + 32);
				}),
				new Action("Above me", false, () -> {
					int y = playerY();
					setBand(y + 4, y + 200);
				}).tip("Skybases and anything built over your head.")));
		mine.enabled = band;
		add(mine);

		Element range = Slider.ints("Vertical range", 1, 512, () -> cfg.containerYRange, v -> cfg.containerYRange = v)
				.tip("Blocks above and below wherever you are standing.");
		range.enabled = around;
		add(range);

		add(new KeyValue("Band in use", () -> ContainerScanner.describeBand(cfg), () -> accent()));
		add(new KeyValue("The world allows",
				() -> minecraft != null && minecraft.level != null
						? "y " + minecraft.level.getMinY() + " to " + minecraft.level.getMaxY()
						: "\u2014",
				() -> Ui.TEXT_MUTED)
				.tip("The band is trimmed to this, so an overworld range does not misreport in "
						+ "a nether that stops at 128."));

		add(new Section("How many is a lot"));
		add(Slider.ints("Trip at", 1, 128, () -> cfg.containerThreshold, v -> cfg.containerThreshold = v)
				.tip("Matching blocks that counts as 'a lot'. This is what gets logged as a cluster."));
		add(new Toggle("Only count storage that sits together",
				() -> cfg.containerGroupEnabled, v -> cfg.containerGroupEnabled = v)
				.tip("Off counts every container in the whole scan radius as one number — at the "
						+ "default reach that is a thousand blocks in every direction, and three "
						+ "unrelated farms can add up to a base that is not there. On counts the "
						+ "fullest patch instead, and logs its middle."));
		add(new Slider("Group within", 0, 32, 1, 0, " chunks either side",
				() -> cfg.containerGroupChunks, v -> cfg.containerGroupChunks = (int) Math.round(v))
				.tip("0 is a single chunk. 2 is a 5×5 patch, 80 blocks across — about the size of "
						+ "a base. Widening it costs nothing: the search is the same work at any "
						+ "size."));
		add(Slider.ints("Rescan every", 5, 200, () -> cfg.containerScanIntervalTicks, v -> cfg.containerScanIntervalTicks = v)
				.tip("Ticks. 20 ticks is one second."));
		add(reaction("When the threshold is hit", () -> cfg.containerReaction, v -> cfg.containerReaction = v));
		flag("Stop on a storage cluster");
		add(new Toggle("Only the first time", () -> cfg.containerLogOnce, v -> cfg.containerLogOnce = v)
				.tip("A cluster whose coordinates are already in the log is not news: it is not "
						+ "written again and it does not set the reaction off again. Off falls back "
						+ "to the duplicate window on the Logging tab, which lets the same base "
						+ "reappear every half hour. Covers landmarks as well, and needs the "
						+ "coordinate log on — that is where \"already found\" is remembered."));

		add(new Section("What counts as storage"));
		add(new Toggle("Hoppers", () -> cfg.scanHoppers, v -> cfg.scanHoppers = v));
		add(new Toggle("Chests and ender chests", () -> cfg.scanChests, v -> cfg.scanChests = v));
		add(new Toggle("Barrels", () -> cfg.scanBarrels, v -> cfg.scanBarrels = v));
		add(new Toggle("Shulker boxes", () -> cfg.scanShulkers, v -> cfg.scanShulkers = v));
		add(new Toggle("Droppers and dispensers", () -> cfg.scanDroppersDispensers, v -> cfg.scanDroppersDispensers = v));
		add(new Toggle("Furnaces, smokers, blast furnaces", () -> cfg.scanFurnaces, v -> cfg.scanFurnaces = v));
		add(new Toggle("Crafters and brewing stands", () -> cfg.scanCraftingStations, v -> cfg.scanCraftingStations = v));

		add(new Section("Landmarks worth logging"));
		add(new Note("These do not count toward the threshold — each one is written straight to the log.",
				Ui.TEXT_FAINT));
		add(new Toggle("Mob and trial spawners", () -> cfg.scanSpawners, v -> cfg.scanSpawners = v));
		add(new Toggle("Beacons", () -> cfg.scanBeacons, v -> cfg.scanBeacons = v));
		add(new Toggle("Enchanting tables", () -> cfg.scanEnchantingTables, v -> cfg.scanEnchantingTables = v));

		add(new Section("Last scan"));
		add(new KeyValue("Total", () -> String.valueOf(ctl.lastScan.grouped()),
				() -> ctl.lastScan.grouped() >= cfg.containerThreshold ? Ui.BAD : Ui.GOOD)
				.tip("What the threshold is tested against."));
		add(new KeyValue("Counting", () -> ctl.lastScan.groupNote(), () -> Ui.TEXT_MUTED));
		add(new KeyValue("Breakdown", () -> ctl.lastScan.summary(), () -> Ui.TEXT_MUTED));
		add(new KeyValue("Nearest",
				() -> ctl.lastScan.nearestDistance() < 0 ? "—"
						: "%.0f blocks away at %s".formatted(ctl.lastScan.nearestDistance(),
						ctl.lastScan.nearest().toShortString()),
				() -> accent()));
		add(new KeyValue("Covered", () -> ctl.lastScan.coverage(), () -> Ui.TEXT_MUTED));
		add(new KeyValue("Height band", () -> ctl.lastScan.bandNote(),
				() -> ctl.lastScan.outsideBand() > 0 ? Ui.WARN : Ui.TEXT_MUTED)
				.tip("Containers the band threw away. A big number here means the band is tighter "
						+ "than you meant it to be."));
		add(new KeyValue("Radius in use",
				() -> ContainerScanner.effectiveRadius(cfg) + " chunks"
						+ (cfg.containerAutoRadius ? " (auto)" : ""),
				() -> Ui.TEXT));
		add(new Action("Scan now", false, () -> {
			if (minecraft != null && minecraft.level != null && minecraft.player != null) {
				ctl.lastScan = ContainerScanner.scan(minecraft.level, minecraft.player, cfg);
			}
		}));
	}

	private void buildSafety() {
		add(new Note("Each of these can alert, stop, or both.", Ui.TEXT_MUTED));

		add(new Section("Reaction time"));
		add(new Toggle("Wait a beat before stopping",
				() -> cfg.reactionDelayEnabled, v -> cfg.reactionDelayEnabled = v)
				.tip("A stop that lands on the exact tick its trigger fired is the one thing here no "
						+ "reaction time explains. The alert still plays immediately, and ledges and "
						+ "liquid still stop at once - waiting would mean walking off the edge."));
		add(new RangeSlider("Take", 0, 2000, 10, 0, "ms",
				() -> cfg.reactionDelayMinMs, v -> cfg.reactionDelayMinMs = v,
				() -> cfg.reactionDelayMaxMs, v -> cfg.reactionDelayMaxMs = v)
				.tip("Drawn fresh for every stop. 180-520ms is roughly a person noticing something."));
		flag("React instantly");

		add(new Section("Other players"));
		add(new Toggle("React when someone comes near", () -> cfg.stopOnNearbyPlayer, v -> cfg.stopOnNearbyPlayer = v));
		add(new Slider("Within", 4, 128, 1, 0, " blocks", () -> cfg.nearbyPlayerRadius, v -> cfg.nearbyPlayerRadius = v));
		add(reaction("Reaction", () -> cfg.nearbyPlayerReaction, v -> cfg.nearbyPlayerReaction = v));
		add(new Toggle("Only stop for players you can see",
				() -> cfg.playerStopOnlyIfVisible, v -> cfg.playerStopOnlyIfVisible = v)
				.tip("The alert always plays. Only the stop is held back, because a sound on this "
						+ "machine is not something anyone else can observe and walking away from someone "
						+ "behind a wall is."));
		flag("Stop for players you cannot see");
		add(new Toggle("Its own louder alert", () -> cfg.playerAlertEnabled, v -> cfg.playerAlertEnabled = v)
				.tip("A person walking up matters more than a stuck warning, so it gets its own repeat count."));
		add(Slider.ints("Play the sound this many times", 1, 30,
				() -> cfg.playerAlertRepeats, v -> cfg.playerAlertRepeats = v));
		add(new Toggle("Log where they were seen", () -> cfg.playerLogCoords, v -> cfg.playerLogCoords = v));

		add(new Section("Health"));
		add(new Toggle("Stop on low health", () -> cfg.stopOnLowHealth, v -> cfg.stopOnLowHealth = v));
		add(new Slider("Below", 1, 20, 0.5, 1, " hp", () -> cfg.lowHealthThreshold, v -> cfg.lowHealthThreshold = v));
		add(reaction("Reaction", () -> cfg.lowHealthReaction, v -> cfg.lowHealthReaction = v));

		add(new Section("Damage"));
		add(new Toggle("React to taking a hit", () -> cfg.stopOnDamage, v -> cfg.stopOnDamage = v));
		add(new Slider("At least", 0.5, 20, 0.5, 1, " hp", () -> cfg.damageThreshold, v -> cfg.damageThreshold = v));
		add(reaction("Reaction", () -> cfg.damageReaction, v -> cfg.damageReaction = v));

		add(new Section("Hostile mobs"));
		add(new Toggle("React to hostiles", () -> cfg.stopOnHostileMob, v -> cfg.stopOnHostileMob = v));
		add(new Slider("Within", 2, 64, 1, 0, " blocks", () -> cfg.hostileMobRadius, v -> cfg.hostileMobRadius = v));
		add(reaction("Reaction", () -> cfg.hostileMobReaction, v -> cfg.hostileMobReaction = v));
		add(new Toggle("Only stop for mobs you can see",
				() -> cfg.hostileStopOnlyIfVisible, v -> cfg.hostileStopOnlyIfVisible = v));
		flag("Stop for mobs you cannot see");

		add(new Section("Walk away from hostiles"));
		add(new Toggle("Leave when something closes in", () -> cfg.fleeFromHostiles, v -> cfg.fleeFromHostiles = v)
				.tip("Turn away from a hostile that is getting closer and keep walking until "
						+ "nothing is in range. Standing still leaves the bot exactly where the "
						+ "thing chasing it was already heading, which is why a stop is not enough."));
		add(new Slider("Start when it is within", 4, 64, 1, 0, " blocks",
				() -> cfg.fleeRadius, v -> cfg.fleeRadius = v)
				.tip("Only a mob that is actually closing counts - one milling about at this "
						+ "range is left alone. Anything inside 3 blocks counts straight away."));
		add(new Slider("Clear for", 0.5, 60, 0.5, 1, "s",
				() -> cfg.fleeSafeSec, v -> cfg.fleeSafeSec = v)
				.tip("How long the radius has to stay empty before the route resumes. The "
						+ "countdown only runs while it is empty."));

		add(new Section("Endermen"));
		add(new Toggle("Look at the floor near an enderman",
				() -> cfg.endermanAvoidLook, v -> cfg.endermanAvoidLook = v)
				.tip("An enderman aggros on being looked at, and a sweep that keeps turning "
						+ "towards its next chunk will sooner or later turn towards one. Pointing "
						+ "the view down costs nothing and no stare check can hit it."));
		add(new Slider("While one is within", 4, 64, 1, 0, " blocks",
				() -> cfg.endermanLookRadius, v -> cfg.endermanLookRadius = v)
				.tip("Vanilla checks for a stare out to 64 blocks."));
		add(new Slider("Point down at least", 10, 90, 1, 0, "\u00b0",
				() -> cfg.endermanLookDownDeg, v -> cfg.endermanLookDownDeg = v)
				.tip("Anything past a few degrees misses a head. Mining is left alone - it is "
						+ "already aiming at a specific block."));

		add(new Section("Chat"));
		add(new Toggle("Watch chat for keywords", () -> cfg.stopOnChatKeyword, v -> cfg.stopOnChatKeyword = v)
				.tip("Works whether or not the bot is walking — an alert matters most when you are away."));
		add(new TextInput("Keywords, comma separated", null,
				() -> String.join(", ", cfg.chatKeywords),
				v -> cfg.chatKeywords = new ArrayList<>(Arrays.stream(v.split(","))
						.map(String::trim).filter(s -> !s.isEmpty()).toList())));
		add(new Toggle("Watch server and system messages", () -> cfg.chatWatchSystemMessages,
				v -> cfg.chatWatchSystemMessages = v)
				.tip("On also reads broadcasts, /say and death messages. Off narrows it to what "
						+ "other players actually type. This mod's own output never counts either "
						+ "way — that loop is closed where the message is printed."));
		add(new Toggle("Also match my own name", () -> cfg.chatKeywordMatchOwnName, v -> cfg.chatKeywordMatchOwnName = v)
				.tip("Matches both the in-game name and the account name."));
		add(new Toggle("Ignore my own messages and advancements", () -> cfg.chatIgnoreSelfAndAdvancements,
				v -> cfg.chatIgnoreSelfAndAdvancements = v)
				.tip("Your name is in every advancement you earn and every line you type, and "
						+ "neither is somebody talking about you. Only the name match is dropped "
						+ "for those - a keyword in them still counts."));
		add(reaction("Reaction", () -> cfg.chatKeywordReaction, v -> cfg.chatKeywordReaction = v));
		add(new Action("Test the chat trigger", false, () -> ctl.onChatMessage("test keyword"))
				.tip("Fires the reaction as if a message had matched."));

		add(new Section("Environment"));
		add(new Toggle("Stop in water or lava", () -> cfg.stopInLiquid, v -> cfg.stopInLiquid = v));
		add(reaction("Reaction", () -> cfg.liquidReaction, v -> cfg.liquidReaction = v));
		add(new Toggle("Stop on disconnect", () -> cfg.stopOnDisconnect, v -> cfg.stopOnDisconnect = v));

		add(new Section("Time limit"));
		add(new Toggle("Stop after a while", () -> cfg.stopAfterMaxRuntime, v -> cfg.stopAfterMaxRuntime = v));
		add(new Slider("After", 1, 480, 1, 0, " min", () -> cfg.maxRuntimeMinutes, v -> cfg.maxRuntimeMinutes = v));
		add(reaction("Reaction", () -> cfg.maxRuntimeReaction, v -> cfg.maxRuntimeReaction = v));
		flag("No time limit");
	}

	private void buildFood() {
		add(new Section("Auto eat"));
		add(new Toggle("Eat when the hunger bar drops", () -> cfg.autoEatEnabled, v -> cfg.autoEatEnabled = v));
		add(Slider.ints("Eat at or below", 0, 19, () -> cfg.autoEatThreshold, v -> cfg.autoEatThreshold = v)
				.tip("20 is a full bar. 17 or lower is where sprinting starts costing you."));
		add(new Toggle("Stand still while eating", () -> cfg.autoEatHoldStill, v -> cfg.autoEatHoldStill = v)
				.tip("Sprinting cancels eating, so it always drops sprint regardless."));
		add(new Toggle("Skip food that hurts you", () -> cfg.autoEatAvoidHarmful, v -> cfg.autoEatAvoidHarmful = v)
				.tip("Rotten flesh, spider eyes, pufferfish, raw chicken, poisonous potatoes, chorus fruit."));
		add(new Toggle("Save golden apples", () -> cfg.autoEatSaveGoldenApples, v -> cfg.autoEatSaveGoldenApples = v));
		add(new Toggle("Put the old item back in hand", () -> cfg.autoEatRestoreSlot, v -> cfg.autoEatRestoreSlot = v));
		add(Slider.ints("Give up after", 20, 200, () -> cfg.autoEatMaxTicks, v -> cfg.autoEatMaxTicks = v)
				.tip("Ticks. Stops it holding right-click forever if something goes wrong."));

		add(new Section("Running out"));
		add(new Toggle("React when the hotbar has no food", () -> cfg.stopWhenOutOfFood, v -> cfg.stopWhenOutOfFood = v));
		add(reaction("Reaction", () -> cfg.outOfFoodReaction, v -> cfg.outOfFoodReaction = v));
		add(new Toggle("Stop on low hunger regardless", () -> cfg.stopOnLowHunger, v -> cfg.stopOnLowHunger = v));
		add(Slider.ints("Below", 0, 20, () -> cfg.lowHungerThreshold, v -> cfg.lowHungerThreshold = v));
		add(reaction("Reaction", () -> cfg.hungerReaction, v -> cfg.hungerReaction = v));

		add(new Section("Live"));
		add(new KeyValue("Hunger",
				() -> minecraft != null && minecraft.player != null
						? minecraft.player.getFoodData().getFoodLevel() + " / 20" : "—",
				() -> Ui.TEXT));
		add(new KeyValue("Auto eat", () -> ctl.autoEat.status,
				() -> ctl.autoEat.status.startsWith("no food") ? Ui.BAD : Ui.TEXT_MUTED));
		add(new Note("Hotbar only. Moving a stack up from the backpack means faking container clicks,",
				Ui.TEXT_FAINT));
		add(new Note("which is a lot of protocol for something you can solve by keeping food on the bar.",
				Ui.TEXT_FAINT));
	}

	private void buildLogging() {
		add(new Section("Recording"));
		add(new Toggle("Record coordinates", () -> cfg.journalEnabled, v -> cfg.journalEnabled = v));
		add(new Toggle("Write log files to disk", () -> cfg.logToFiles, v -> cfg.logToFiles = v));
		add(new TextInput("Folder", null, () -> cfg.logFolder, v -> cfg.logFolder = v)
				.tip("The .json in here is what the log viewer reads back."));
		add(new Row(List.of(
				new Action("Open the Logs folder", true, this::openLogFolder),
				new Action("Write now", false, () -> {
					ctl.journal.writeFiles();
					ctl.lastReason = "Log written";
				}))));
		add(new KeyValue("Current file", () -> ctl.journal.currentFileName(), () -> accent()));
		add(new KeyValue("Entries held", () -> String.valueOf(ctl.journal.size()), () -> Ui.TEXT));

		add(new Section("Files"));
		add(new Cycle<>("New file", Arrays.asList(Journal.Rotation.values()), r -> r.label,
				() -> cfg.logRotation, v -> cfg.logRotation = v)
				.tip("Per day is the usual choice. Changing this takes effect on the next entry."));
		add(new Note(".json is always written - the viewer reads it back.", Ui.TEXT_FAINT));
		add(new Toggle("Also write .csv (for a spreadsheet)", () -> cfg.logWriteCsv, v -> cfg.logWriteCsv = v));
		add(new Toggle("Also write .txt (aligned columns)", () -> cfg.logWriteText, v -> cfg.logWriteText = v));
		add(new Toggle("Write on every entry", () -> cfg.logFlushImmediately, v -> cfg.logFlushImmediately = v)
				.tip("Off writes on stop and on closing this menu instead. On survives a crash."));
		add(Slider.ints("Keep at most", 100, 50000, () -> cfg.journalMaxEntries, v -> cfg.journalMaxEntries = v)
				.tip("Oldest entries drop off the end."));

		add(new Section("Columns"));
		add(new Toggle("Biome", () -> cfg.logIncludeBiome, v -> cfg.logIncludeBiome = v));
		add(new Toggle("Health and hunger", () -> cfg.logIncludeStatus, v -> cfg.logIncludeStatus = v));
		add(new Toggle("Bot state and runtime", () -> cfg.logIncludeState, v -> cfg.logIncludeState = v));
		add(new Note("Every entry always carries time, kind, dimension, x/y/z and chunk.", Ui.TEXT_FAINT));

		add(new Section("Duplicates"));
		add(new Slider("Ignore repeats within", 0, 512, 8, 0, " blocks",
				() -> cfg.journalDedupeRadius, v -> cfg.journalDedupeRadius = v)
				.tip("Standing next to the same chest hall should write one line, not three hundred."));
		add(new Slider("...and within", 0, 240, 1, 0, " min",
				() -> cfg.journalDedupeMinutes, v -> cfg.journalDedupeMinutes = v));

		add(new Section("What gets recorded"));
		add(new Widgets.Chips<>(Arrays.asList(Journal.Kind.values()),
				k -> k.label, k -> kindColour(k, accent()), null,
				k -> cfg.journalKinds.getOrDefault(k.name(), true),
				(k, on) -> cfg.journalKinds.put(k.name(), on))
				.tip("Click a chip to start or stop recording that kind."));
		add(new Row(List.of(
				new Action("All on", false, () -> {
					for (Journal.Kind k : Journal.Kind.values()) cfg.journalKinds.put(k.name(), true);
				}),
				new Action("All off", false, () -> {
					for (Journal.Kind k : Journal.Kind.values()) cfg.journalKinds.put(k.name(), false);
				}))));
	}

	private void buildLogViewer() {
		List<Journal.Entry> source = viewerSource();

		add(new Section("Which log"));
		add(new Action(() -> "File: " + (cfg.logViewFile.isBlank() ? "this session (live)" : cfg.logViewFile),
				true, this::cycleLogFile)
				.tip("Click to step through the files in the Logs folder."));
		add(new Row(List.of(
				new Action("Newest file", false, () -> {
					List<String> files = ctl.journal.listLogFiles();
					cfg.logViewFile = files.isEmpty() ? "" : files.getFirst();
					build();
				}),
				new Action("Back to live", false, () -> {
					cfg.logViewFile = "";
					build();
				}),
				new Action("Open folder", false, this::openLogFolder))));

		add(new Section("Where"));
		add(new Toggle("This world or server only",
				() -> cfg.logViewThisWorldOnly, v -> {
					cfg.logViewThisWorldOnly = v;
					build();
				})
				.tip("Log files rotate by day, not by world, so one file can hold two servers. "
						+ "Off lists everything in the file, whichever world it came from."));
		add(new KeyValue("You are on", () -> WorldId.label(worldNow), () -> accent()));
		add(new KeyValue("This file holds", () -> {
			List<String> worlds = Journal.worldsIn(source);
			if (worlds.isEmpty()) return "no world recorded";
			if (worlds.size() == 1) return WorldId.label(worlds.getFirst());
			return worlds.size() + " worlds: " + String.join(", ",
					worlds.stream().map(WorldId::label).toList());
		}, () -> Ui.TEXT_MUTED));
		addUnknownWorldControls(source, cfg.logViewThisWorldOnly);

		add(new Section("Dimension"));
		addDimensionChips(source);

		add(new Section("Filter"));
		add(new TextInput("Search - kind, note, biome, dimension or coordinates", null,
				() -> cfg.logViewSearch, v -> {
			cfg.logViewSearch = v;
			build();
		}));
		// counted once here, not once per chip per frame
		java.util.Map<Journal.Kind, Integer> counts = Journal.countsOf(source);
		add(new Widgets.Chips<>(Arrays.asList(Journal.Kind.values()),
				k -> k.label, k -> kindColour(k, accent()),
				k -> counts.getOrDefault(k, 0),
				k -> cfg.logViewKinds.getOrDefault(k.name(), true),
				(k, on) -> {
					cfg.logViewKinds.put(k.name(), on);
					build();
				}).tip("Numbers are how many of that kind are in this log."));
		add(new Row(List.of(
				new Action("Show all", false, () -> {
					for (Journal.Kind k : Journal.Kind.values()) cfg.logViewKinds.put(k.name(), true);
					cfg.logViewSearch = "";
					build();
				}),
				new Action(() -> cfg.logViewNewestFirst ? "Newest first" : "Oldest first", false, () -> {
					cfg.logViewNewestFirst = !cfg.logViewNewestFirst;
					build();
				}),
				new Action("Copy shown", false, () -> {
					List<Journal.Entry> shownNow = filtered(source);
					if (minecraft != null) minecraft.keyboardHandler.setClipboard(Journal.asText(shownNow));
					ctl.lastReason = "Copied " + shownNow.size() + " entries";
				}))));

		List<Journal.Entry> shown = filtered(source);
		add(new Section(shown.size() + " of " + source.size() + " entries"));
		add(new Row(List.of(
				new Action("Pin here", true, () -> {
					if (minecraft != null && minecraft.player != null) {
						ctl.journal.log(Journal.Kind.MANUAL, minecraft.level,
								minecraft.player.blockPosition(), "Dropped by hand");
						build();
					}
				}),
				new Action("Clear live log", false, () -> {
					ctl.journal.clear();
					build();
				}))));

		if (shown.isEmpty()) {
			add(new Note(source.isEmpty()
					? "Nothing logged yet - the bot writes here as it finds things."
					: "Nothing matches that filter.", Ui.TEXT_FAINT));
			return;
		}

		String currentDay = null;
		int drawn = 0;
		for (Journal.Entry e : shown) {
			if (drawn++ >= cfg.logViewLimit) {
				add(new Note((shown.size() - cfg.logViewLimit)
						+ " more not shown. Narrow the filter, or raise the limit on the HUD tab.", Ui.TEXT_FAINT));
				break;
			}
			if (!e.day().equals(currentDay)) {
				currentDay = e.day();
				add(new Widgets.Divider(currentDay));
			}
			add(new JournalRow(e));
		}
	}

	/** The live log, or a file the user picked. Files are read once and kept. */
	private List<Journal.Entry> viewerSource() {
		if (cfg.logViewFile.isBlank()) return ctl.journal.all();
		if (!cfg.logViewFile.equals(loadedFile)) {
			loadedFile = cfg.logViewFile;
			loadedEntries = ctl.journal.readLogFile(cfg.logViewFile);
		}
		return loadedEntries;
	}

	/**
	 * The overworld / nether / end filter. Shared between the log viewer and the map for the
	 * same reason the kind chips are: two filters that disagree just read as a bug.
	 */
	private void addDimensionChips(List<Journal.Entry> source) {
		List<String> dims = Journal.dimensionsIn(source);
		java.util.Map<String, Integer> counts = Journal.dimensionCounts(source);
		add(new Widgets.Chips<>(dims, Journal::dimensionLabel, ConfigScreen::dimensionColour,
				d -> counts.getOrDefault(d, 0),
				cfg::dimensionShown,
				(d, on) -> {
					cfg.dimensionFilter.put(d, on);
					build();
				}).tip("Numbers are how many entries are in this dimension. The vanilla three are "
						+ "always listed, so the filter can be set before going there."));
		add(new Row(List.of(
				new Action("Every dimension", false, () -> {
					for (String d : dims) cfg.dimensionFilter.put(d, true);
					build();
				}),
				new Action("Only this one", false, () -> {
					String here = Journal.dimensionKey(currentDimension());
					for (String d : dims) cfg.dimensionFilter.put(d, d.equals(here));
					cfg.dimensionFilter.put(here, true);
					build();
				}).tip("Which is " + Journal.dimensionLabel(currentDimension()) + " right now."))));
	}

	private static int dimensionColour(String path) {
		return switch (path) {
			case "overworld" -> Ui.GOOD;
			case "the_nether" -> Ui.BAD;
			case "the_end" -> 0xFFC4A0FF;
			default -> Ui.TEXT_MUTED;
		};
	}

	private List<Journal.Entry> filtered(List<Journal.Entry> source) {
		List<Journal.Entry> out = new ArrayList<>();
		for (Journal.Entry e : source) {
			if (cfg.logViewThisWorldOnly
					&& !WorldId.matches(e.world(), worldNow, cfg.showUnknownWorldEntries)) continue;
			if (!cfg.dimensionShown(e.dimension())) continue;
			if (!cfg.logViewKinds.getOrDefault(e.kind().name(), true)) continue;
			if (!e.matches(cfg.logViewSearch)) continue;
			out.add(e);
		}
		if (cfg.logViewNewestFirst) java.util.Collections.reverse(out);
		return out;
	}

	private void cycleLogFile() {
		List<String> options = new ArrayList<>();
		options.add(""); // the live session
		options.addAll(ctl.journal.listLogFiles());
		int i = options.indexOf(cfg.logViewFile);
		cfg.logViewFile = options.get((Math.max(i, 0) + 1) % options.size());
		build();
	}

	private void openLogFolder() {
		java.nio.file.Path dir = ctl.journal.folder();
		if (dir == null) {
			ctl.lastReason = "Could not create " + cfg.logFolder;
			return;
		}
		open(dir.toFile());
	}

	private void buildLook() {
		add(new Section("Overlay"));
		add(new Toggle("Show the status overlay", () -> cfg.hudEnabled, v -> cfg.hudEnabled = v));
		add(new Cycle<>("Corner", Arrays.asList(Config.HudCorner.values()), c -> c.label,
				() -> cfg.hudCorner, v -> cfg.hudCorner = v));
		add(Slider.ints("Offset X", 0, 200, () -> cfg.hudOffsetX, v -> cfg.hudOffsetX = v));
		add(Slider.ints("Offset Y", 0, 200, () -> cfg.hudOffsetY, v -> cfg.hudOffsetY = v));
		add(new Slider("Scale", 0.5, 2, 0.05, 2, "×", () -> cfg.hudScale, v -> cfg.hudScale = v));
		add(new Toggle("Next event countdown", () -> cfg.hudShowNextEvent, v -> cfg.hudShowNextEvent = v));
		add(new Toggle("Container count", () -> cfg.hudShowContainers, v -> cfg.hudShowContainers = v));
		add(new Toggle("Destination or area progress", () -> cfg.hudShowGoto, v -> cfg.hudShowGoto = v));
		add(new Toggle("Logged coordinate count", () -> cfg.hudShowJournal, v -> cfg.hudShowJournal = v));
		add(Slider.ints("Entries shown in the log viewer", 20, 2000, () -> cfg.logViewLimit, v -> cfg.logViewLimit = v));
		add(new Toggle("Hunger", () -> cfg.hudShowFood, v -> cfg.hudShowFood = v));

		add(new Section("Theme"));
		add(new Cycle<>("Accent", Arrays.stream(ACCENTS).boxed().toList(),
				c -> ACCENT_NAMES[Math.max(0, indexOfAccent(c))],
				() -> cfg.accentColor, v -> cfg.accentColor = v));
		add(new Slider("Backdrop dimming", 0, 1, 0.01, 2, "",
				() -> cfg.guiBackdropOpacity, v -> cfg.guiBackdropOpacity = v)
				.tip("Turn this down to watch the world while the bot walks."));
		add(new Toggle("Blur the world behind the menu", () -> cfg.guiBlurBackground, v -> cfg.guiBlurBackground = v));

		add(new Section("Behaviour"));
		add(new Toggle("Reseed the generator on every start", () -> cfg.reseedOnStart, v -> cfg.reseedOnStart = v));
		add(new Toggle("Verbose chat logging", () -> cfg.verboseLogging, v -> cfg.verboseLogging = v));
		add(new Row(List.of(
				new Action("Save now", true, () -> {
					cfg.clampAll();
					cfg.save();
					ctl.area.save();
					ctl.journal.save();
					ctl.lastReason = "Settings saved";
				}),
				new Action("Reset everything", false, this::resetAll))));
	}

	private void buildAbout() {
		add(new Section("Movement & Randomisation"));
		add(new Note("A client-side walking assistant for Minecraft " + MovRand.MC_VERSION + ".", Ui.TEXT));
		add(new Note(""));
		add(new Section("Keys"));
		add(new KeyValue("Open this menu", MovRand::openKeyName, () -> accent()));
		add(new KeyValue("Toggle movement", MovRand::toggleKeyName, () -> accent()));
		add(new Note("Both are rebindable under Options → Controls → Movement.", Ui.TEXT_MUTED));
		add(new Note(""));
		add(new Section("Files"));
		add(new Note("config/movrand.json — every setting here.", Ui.TEXT_MUTED));
		add(new Note("config/movrand-coverage.json — which chunks the sweep has done.", Ui.TEXT_MUTED));
		add(new Note("Logs/ — the coordinate log, as .json, .csv and .txt.", Ui.TEXT_MUTED));
		add(new Row(List.of(
				new Action("Open the config folder", false, this::openConfigFolder),
				new Action("Open the Logs folder", false, this::openLogFolder))));
		add(new Note(""));
		add(new Section("What a server can see"));
		add(new Note("The movement is vanilla key presses through vanilla physics, so the packets", Ui.TEXT_MUTED));
		add(new Note("are the ones a hand on the keyboard would send. The scan, the log, the map and", Ui.TEXT_MUTED));
		add(new Note("the alert sound never touch the network at all.", Ui.TEXT_MUTED));
		add(new Note("What is visible is behaviour: reacting to something you could not have seen,", Ui.TEXT_MUTED));
		add(new Note("and anything periodic or perfectly constant in the rotation. Both are covered", Ui.TEXT_MUTED));
		add(new Note("on the Setup guide tab, which lists whatever the current settings still carry.", Ui.TEXT_MUTED));
		add(new Action("Open the risk report", true, () -> {
			activeTab = Tab.PRESETS;
			revealActiveTab();
			SCROLL[Tab.PRESETS.ordinal()] = 0;
			build();
		}));
		add(new Note(""));
		add(new Section("Please note"));
		add(new Note("Automating movement breaks the rules on many multiplayer servers.", Ui.WARN));
		add(new Note("None of the above changes that. Being hard to notice is not permission,", Ui.WARN));
		add(new Note("and whether you may run this somewhere is between you and that server.", Ui.TEXT_MUTED));
	}

	// ------------------------------------------------------------- the map

	private String currentDimension() {
		try {
			return minecraft != null && minecraft.level != null
					? minecraft.level.dimension().identifier().getPath() : "";
		} catch (Exception e) {
			return "";
		}
	}

	/**
	 * The dimension the map is drawn in, which only decides the 8x. With the filter down to
	 * one dimension that is unambiguously the frame; with several the map is a mix, and the
	 * one you are standing in is the sensible frame to draw a mix in.
	 */
	private String mapFrame() {
		String only = null;
		for (String d : Journal.DIMENSIONS) {
			if (!cfg.dimensionShown(d)) continue;
			if (only != null) return currentDimension();
			only = d;
		}
		return only == null ? currentDimension() : only;
	}

	/** Filtered for the map. Called every frame, so it allocates one list and nothing else. */
	private List<Journal.Entry> mapEntries() {
		long cutoff = cfg.mapMaxAgeHours <= 0 ? 0
				: System.currentTimeMillis() - (long) (cfg.mapMaxAgeHours * 3_600_000L);
		List<Journal.Entry> out = new ArrayList<>();
		for (Journal.Entry e : viewerSource()) {
			if (cfg.mapThisWorldOnly
					&& !WorldId.matches(e.world(), worldNow, cfg.showUnknownWorldEntries)) continue;
			if (!cfg.dimensionShown(e.dimension())) continue;
			if (cutoff > 0 && e.time() < cutoff) continue;
			if (!cfg.logViewKinds.getOrDefault(e.kind().name(), true)) continue;
			out.add(e);
		}
		return out;
	}

	private void buildMap() {
		add(new Note("Every logged coordinate, drawn where it happened.", Ui.TEXT_MUTED));

		JournalMap map = new JournalMap(cfg, this::mapEntries, this::mapFrame, 236);
		add(map.tip("Drag to pan \u00b7 wheel to zoom \u00b7 right-click to recentre \u00b7 "
				+ "click a pin to copy its teleport"));

		add(new Row(List.of(
				new Action("Centre on me", true, () -> {
					cfg.mapFollowPlayer = true;
					map.recentre();
				}),
				new Action("Fit the pins", false, map::recentre)
						.tip("Centres on the average of everything currently shown."),
				new Action("Open the log", false, () -> {
					activeTab = Tab.LOGS;
					revealActiveTab();
					build();
				}))));

		add(new Section("Which pins"));
		add(new Toggle("This world or server only", () -> cfg.mapThisWorldOnly, v -> {
			cfg.mapThisWorldOnly = v;
			build();
		})
				.tip("Off plots every world in the log at once, which is only worth doing if you "
						+ "already know two of them share coordinates."));
		add(new KeyValue("You are on", () -> WorldId.label(worldNow), () -> accent()));
		addUnknownWorldControls(viewerSource(), cfg.mapThisWorldOnly);

		add(new Section("Dimension"));
		add(new Note("Overworld and nether coordinates are different scales. Showing both without "
				+ "the 8x below puts a portal in the wrong place.", Ui.TEXT_MUTED));
		addDimensionChips(viewerSource());

		add(new Section("Age"));		add(new Slider("Only the last", 0, 168, 1, 0, " hours",
				() -> cfg.mapMaxAgeHours, v -> cfg.mapMaxAgeHours = v)
				.tip("0 shows everything ever logged."));
		add(new Action(() -> "File: " + (cfg.logViewFile.isBlank() ? "this session (live)" : cfg.logViewFile),
				false, this::cycleLogFile)
				.tip("The same file the log viewer is reading."));

		java.util.Map<Journal.Kind, Integer> counts = Journal.countsOf(mapEntries());
		add(new Widgets.Chips<>(Arrays.asList(Journal.Kind.values()),
				k -> k.label, k -> kindColour(k, accent()),
				k -> counts.getOrDefault(k, 0),
				k -> cfg.logViewKinds.getOrDefault(k.name(), true),
				(k, on) -> {
					cfg.logViewKinds.put(k.name(), on);
					build();
				}).tip("Shared with the log viewer, so the two always agree."));
		add(new Row(List.of(
				new Action("Show all kinds", false, () -> {
					for (Journal.Kind k : Journal.Kind.values()) cfg.logViewKinds.put(k.name(), true);
					build();
				}),
				new Action("Places only", false, () -> {
					for (Journal.Kind k : Journal.Kind.values()) {
						cfg.logViewKinds.put(k.name(), k.positional);
					}
					build();
				}).tip("Storage, landmarks, players and the rest of the things whose location is "
						+ "worth walking back to."))));

		add(new Section("How it looks"));
		add(new Slider("Blocks across", 64, com.damia.movrand.WorldBounds.maxSpanBlocks(32_768 * 16), 64, 0, "",
				() -> Math.min(cfg.mapSpanBlocks, com.damia.movrand.WorldBounds.maxSpanBlocks(32_768 * 16)),
				v -> cfg.mapSpanBlocks = v)
				.tip("Zoom out to 32,768 chunks across (524,288 blocks), limited by the world border. "
						+ "Use the wheel over the map for finer zoom control."));
		add(worldBorderRow());
		add(new Toggle("Follow the player", () -> cfg.mapFollowPlayer, v -> cfg.mapFollowPlayer = v)
				.tip("Panning or zooming switches this off on its own."));
		add(new Toggle("Nether pins at 8x", () -> cfg.mapScaleNether, v -> cfg.mapScaleNether = v)
				.tip("Puts a nether coordinate where it comes out in the overworld. Ignored when the "
						+ "map is already drawn in nether coordinates."));
		add(new Toggle("Grid", () -> cfg.mapShowGrid, v -> cfg.mapShowGrid = v));
		add(new Toggle("Label every pin", () -> cfg.mapShowLabels, v -> cfg.mapShowLabels = v)
				.tip("Readable when there are a dozen, a mess when there are three hundred."));
		add(new Toggle("Outline the sweep area", () -> cfg.mapShowArea, v -> cfg.mapShowArea = v));

		add(new Section("Legend"));
		add(new Widgets.Chips<>(Arrays.asList(Journal.Kind.values()),
				k -> k.label, k -> kindColour(k, accent()), null, k -> true, (k, on) -> {
		}).tip("Colours only. Use the chips further up to filter."));
	}

	// ------------------------------------------------------------ helpers

	/** One line of the coordinate log. Click copies a teleport command. */
	private final class JournalRow extends Element {
		private final Journal.Entry entry;

		JournalRow(Journal.Entry entry) {
			this.entry = entry;
			this.h = 26;
			this.tip = entry.when() + " — click to copy " + entry.teleport();
		}

		@Override
		public void render(GuiGraphicsExtractor g, net.minecraft.client.gui.Font f, int mx, int my, int accentColour) {
			boolean hover = hovered(mx, my);
			approach(hover ? 1 : 0);
			Ui.card(g, x, y, w, h, 4, Ui.mix(Ui.CARD, Ui.CARD_HOVER, anim),
					Ui.mix(Ui.BORDER_SOFT, accentColour, anim * 0.8));
			Ui.roundRect(g, x + 6, y + 7, 3, h - 14, 1, kindColour(entry.kind(), accentColour));
			Ui.textElided(g, f, entry.kind().label, w / 2, x + 14, y + 5, Ui.TEXT);
			Ui.textElided(g, f, entry.dimension() + "  " + entry.coords(), w - 20, x + 14, y + 15, Ui.TEXT_MUTED);
			Ui.textRight(g, f, entry.when(), x + w - 8, y + 5, Ui.TEXT_FAINT);
			if (!entry.note().isEmpty()) {
				Ui.textRightElided(g, f, entry.note(), w / 2 - 20, x + w - 8, y + 15, Ui.TEXT_FAINT);
			}
		}

		@Override
		public boolean mouseClicked(double mx, double my, int button) {
			if (!hovered(mx, my) || button != 0) return false;
			if (minecraft != null) minecraft.keyboardHandler.setClipboard(entry.teleport());
			ctl.lastReason = "Copied " + entry.teleport();
			return true;
		}
	}

	static int kindColour(Journal.Kind kind, int accent) {
		return switch (kind) {
			case DEATH, SAFE_STOP -> Ui.BAD;
			case PLAYER_SPOTTED, DAMAGE, LOW_HEALTH, HUNGER -> Ui.WARN;
			case CONTAINER_CLUSTER, LANDMARK -> Ui.GOOD;
			default -> accent;
		};
	}

	private int playerY() {
		return minecraft != null && minecraft.player != null
				? minecraft.player.blockPosition().getY() : 64;
	}

	private void setBand(int min, int max) {
		cfg.containerHeightRange = Config.HeightRange.ABSOLUTE;
		cfg.containerMinY = Math.min(min, max);
		cfg.containerMaxY = Math.max(min, max);
		cfg.clampAll();
		ctl.lastReason = "Scanning " + ContainerScanner.describeBand(cfg);
		build();
	}

	private Cycle<Config.Reaction> reaction(String label, java.util.function.Supplier<Config.Reaction> get,
	                                        java.util.function.Consumer<Config.Reaction> set) {
		Cycle<Config.Reaction> c = new Cycle<>(label, Arrays.asList(Config.Reaction.values()), r -> r.label, get, set);
		c.tip = "Alert plays the sound file; stop releases the movement keys.";
		return c;
	}

	private static int indexOfAccent(int colour) {
		for (int i = 0; i < ACCENTS.length; i++) if (ACCENTS[i] == colour) return i;
		return 0;
	}

	private static String fmt(double v) {
		return v == Math.rint(v) ? String.valueOf((long) v) : String.format(Locale.ROOT, "%.2f", v);
	}

	private static double parse(String s, double fallback) {
		try {
			return s.isBlank() || s.equals("-") ? 0 : Double.parseDouble(s);
		} catch (NumberFormatException e) {
			return fallback;
		}
	}

	private void cycleAlertFile() {
		List<String> files = AlertSound.listFiles(cfg);
		if (files.isEmpty()) return;
		List<String> options = new ArrayList<>();
		options.add("");
		options.addAll(files);
		int i = options.indexOf(cfg.alertFile);
		cfg.alertFile = options.get((Math.max(i, 0) + 1) % options.size());
	}

	private void openAlertFolder() {
		open(AlertSound.folderAsFile(cfg));
	}

	private void openConfigFolder() {
		open(net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().toFile());
	}

	/**
	 * Not {@code Desktop.getDesktop().open(..)}: AWT inside a GLFW process opens nothing on
	 * Windows and reports it nowhere useful, because {@code isDesktopSupported()} is false in
	 * the game's own JVM. This is the call vanilla's "Open pack folder" button makes.
	 */
	private void open(java.io.File file) {
		if (!file.isDirectory() && !file.exists()) {
			ctl.lastReason = "No folder at " + file;
			return;
		}
		net.minecraft.util.Util.getPlatform().openFile(file);
	}

	private void resetAll() {
		Config fresh = new Config();
		fresh.clampAll();
		fresh.save();
		MovRand.replaceConfig(fresh);
		if (minecraft != null) minecraft.setScreenAndShow(new ConfigScreen());
	}

	// ------------------------------------------------------------ rendering

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		if (cfg.guiBlurBackground) extractBlurredBackground(g);
		g.fill(0, 0, width, height, Ui.alpha(Ui.BACKDROP, cfg.guiBackdropOpacity));
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(g, mouseX, mouseY, partialTick);
		// the log grows while the menu is open, so keep the list in step with it
		if ((activeTab == Tab.LOGS || activeTab == Tab.MAP)
				&& ctl.journal.size() != journalSizeWhenBuilt) build();

		Ui.beginTextHover(g, mouseX, mouseY);
		hoverTip = "";
		int accent = accent();

		Ui.card(g, panelX, panelY, panelW, panelH, 8, Ui.PANEL, Ui.BORDER);
		Ui.gradient(g, panelX + 1, panelY + 1, panelW - 2, 44, Ui.PANEL_TOP, Ui.PANEL);
		Ui.rect(g, panelX + 1, panelY + 44, panelW - 2, 1, Ui.BORDER_SOFT);
		Ui.rect(g, panelX + 1, panelY + 1, panelW - 2, 2, accent);

		drawHeader(g, mouseX, mouseY, accent);
		drawSidebar(g, mouseX, mouseY, accent);
		drawContent(g, mouseX, mouseY, accent);
		drawFooter(g);
		Ui.endTextHover();
	}

	private void drawHeader(GuiGraphicsExtractor g, int mx, int my, int accent) {
		int tx = panelX + 16;
		Ui.text(g, font, "Movement & Randomisation", tx, panelY + 13, Ui.TEXT);
		Ui.text(g, font, "v" + MovRand.VERSION + "  ·  " + activeTab.label, tx, panelY + 26, Ui.TEXT_FAINT);

		boolean on = cfg.movementEnabled;
		String status = ctl.describeState();
		int pw = font.width(status) + 26;
		int px = panelX + panelW - 16 - pw;
		int py = panelY + 14;
		int colour = on ? accent : Ui.TRACK;
		Ui.pill(g, px, py, pw, 18, on ? Ui.shade(colour, 0.45) : colour);
		Ui.roundRect(g, px + 8, py + 6, 6, 6, 3, on ? accent : Ui.TEXT_FAINT);
		Ui.text(g, font, status, px + 19, py + 5, on ? Ui.TEXT : Ui.TEXT_MUTED);

		if (mx >= px && mx < px + pw && my >= py && my < py + 18) {
			hoverTip = on ? "Click to stop moving" : "Click to start moving";
		}
	}

	private int sidebarX() {
		return panelX + 8;
	}

	private int sidebarW() {
		return sidebarW - 8;
	}

	private int sidebarTop() {
		return panelY + 52;
	}

	private int sidebarBoxHeight() {
		return panelH - 52 - 26;
	}

	/** Group headings and tabs, positioned relative to the top of the sidebar. */
	private List<SidebarRow> sidebarRows() {
		List<SidebarRow> rows = new ArrayList<>();
		Group current = null;
		int y = 0;
		for (Tab tab : Tab.values()) {
			if (tab.group != current) {
				current = tab.group;
				// a separated group gets breathing room above it as well as the rule itself
				boolean rule = current.separated && !rows.isEmpty();
				if (rule) y += 8;
				rows.add(new SidebarRow(y, 16, null, current.label, rule));
				y += 16;
			}
			rows.add(new SidebarRow(y, 22, tab, null, false));
			y += 24;
		}
		return rows;
	}

	private int sidebarContentHeight() {
		List<SidebarRow> rows = sidebarRows();
		return rows.isEmpty() ? 0 : rows.getLast().relY() + rows.getLast().height() + 4;
	}

	private double sidebarMaxScroll() {
		return Math.max(0, sidebarContentHeight() - sidebarBoxHeight());
	}

	/** The thumb geometry is shared by drawing and input, so a thin bar stays easy to grab. */
	private int sidebarThumbHeight() {
		int box = sidebarBoxHeight();
		return Math.min(box, Math.max(18, (int) ((double) box * box / sidebarContentHeight())));
	}

	private int sidebarThumbY() {
		int top = sidebarTop();
		int box = sidebarBoxHeight();
		int thumbH = sidebarThumbHeight();
		return top + (int) ((box - thumbH) * (sidebarScroll / sidebarMaxScroll()));
	}

	/** Keeps the selected tab on screen when it is chosen with the keyboard. */
	private void revealActiveTab() {
		for (SidebarRow row : sidebarRows()) {
			if (row.tab() != activeTab) continue;
			double top = row.relY();
			double bottom = top + row.height();
			if (top < sidebarScroll) sidebarScroll = top;
			else if (bottom > sidebarScroll + sidebarBoxHeight()) sidebarScroll = bottom - sidebarBoxHeight();
			break;
		}
	}

	private void drawSidebar(GuiGraphicsExtractor g, int mx, int my, int accent) {
		int x = sidebarX(), w = sidebarW(), top = sidebarTop(), box = sidebarBoxHeight();
		sidebarScroll = Math.max(0, Math.min(sidebarMaxScroll(), sidebarScroll));

		Ui.roundRect(g, x, top - 4, w, box + 8, 6, Ui.SIDEBAR);
		g.enableScissor(x, top - 2, x + w, top + box + 2);

		for (SidebarRow row : sidebarRows()) {
			int ry = top + row.relY() - (int) Math.round(sidebarScroll);
			if (ry + row.height() < top - 4 || ry > top + box + 4) continue;

			if (row.tab() == null) {
				if (row.rule()) Ui.hLine(g, x + 6, ry - 5, w - 12, Ui.BORDER);
				Ui.text(g, font, row.heading().toUpperCase(Locale.ROOT), x + 10, ry + 5,
						row.rule() ? Ui.mix(Ui.TEXT_FAINT, accent, 0.55) : Ui.TEXT_FAINT);
				continue;
			}
			boolean active = row.tab() == activeTab;
			boolean hover = mx >= x && mx < x + w && my >= ry && my < ry + row.height()
					&& my >= top && my < top + box;
			if (active) {
				Ui.roundRect(g, x, ry, w, row.height(), 5, Ui.mix(Ui.CARD, accent, 0.14));
				Ui.roundRect(g, x + 2, ry + 5, 3, row.height() - 10, 1, accent);
			} else if (hover) {
				Ui.roundRect(g, x, ry, w, row.height(), 5, Ui.CARD);
			}
			Ui.textElided(g, font, row.tab().label, w - 22, x + 12, ry + (row.height() - 8) / 2,
					active || hover ? Ui.TEXT : Ui.TEXT_MUTED);
		}
		g.disableScissor();

		double max = sidebarMaxScroll();
		if (max > 0) {
			int trackX = x + w - 3;
			Ui.roundRect(g, trackX, top, 2, box, 1, Ui.TRACK);
			int thumbH = sidebarThumbHeight();
			int thumbY = sidebarThumbY();
			Ui.roundRect(g, trackX, thumbY, 2, thumbH, 1, Ui.mix(Ui.TRACK, accent, 0.8));
		}
	}

	/** @return the tab under the cursor, or null. */
	private Tab tabAt(double mx, double my) {
		int x = sidebarX(), w = sidebarW(), top = sidebarTop(), box = sidebarBoxHeight();
		if (mx < x || mx >= x + w || my < top || my >= top + box) return null;
		for (SidebarRow row : sidebarRows()) {
			if (row.tab() == null) continue;
			int ry = top + row.relY() - (int) Math.round(sidebarScroll);
			if (my >= ry && my < ry + row.height()) return row.tab();
		}
		return null;
	}

	private void drawContent(GuiGraphicsExtractor g, int mx, int my, int accent) {
		double maxScroll = Math.max(0, contentHeight - contentH);
		SCROLL[activeTab.ordinal()] = Math.max(0, Math.min(maxScroll, SCROLL[activeTab.ordinal()]));
		int scroll = (int) Math.round(SCROLL[activeTab.ordinal()]);

		g.enableScissor(contentX - 4, contentY, contentX + contentW + 4, contentY + contentH);
		for (Element e : elements) {
			e.x = contentX;
			e.y = contentY + e.relY - scroll;
			e.w = contentW;
			if (e.y + e.h < contentY - 8 || e.y > contentY + contentH + 8) continue;
			e.render(g, font, mx, my, accent);
			// A status edge out in the gutter: visible at a glance, and it cannot collide with
			// anything the element itself draws
			if (!e.risk.isEmpty()) Ui.roundRect(g, e.x - 4, e.y + 1, 2, Math.max(2, e.h - 2), 1, Ui.BAD);
			else if (!e.recommendation.isEmpty()) Ui.roundRect(g, e.x - 4, e.y + 1, 2, Math.max(2, e.h - 2), 1, Ui.GOOD);
			if (e instanceof JournalMap jm) {
				String copied = jm.takePicked();
				if (copied != null) ctl.lastReason = "Copied " + copied;
			}
			if (!e.tip.isEmpty() && e.hovered(mx, my)) hoverTip = e.tip;
		}
		g.disableScissor();

		if (maxScroll > 0) {
			int trackX = contentX + contentW + 8;
			Ui.roundRect(g, trackX, contentY, 3, contentH, 1, Ui.TRACK);
			int thumbH = contentThumbHeight();
			int thumbY = contentThumbY();
			Ui.roundRect(g, trackX, thumbY, 3, thumbH, 1, accent);
		}
	}

	private int contentThumbHeight() {
		return Math.min(contentH, Math.max(20,
				(int) ((double) contentH * contentH / Math.max(1, contentHeight))));
	}

	private int contentThumbY() {
		double max = Math.max(0, contentHeight - contentH);
		double scroll = Math.max(0, Math.min(max, SCROLL[activeTab.ordinal()]));
		return contentY + (max == 0 ? 0 : (int) ((contentH - contentThumbHeight()) * scroll / max));
	}

	/**
	 * Starts a scrollbar drag. The visual bars are deliberately only a few pixels wide, so
	 * the mouse target extends around them without stealing clicks from the content controls.
	 * Clicking the track beside the thumb centers it at that spot and starts a drag there, as
	 * users generally expect from a scrollbar.
	 */
	private boolean beginScrollbarDrag(double mx, double my) {
		if (mx >= sidebarX() + sidebarW() - 9 && mx <= sidebarX() + sidebarW() + 4
				&& my >= sidebarTop() && my < sidebarTop() + sidebarBoxHeight()) {
			double max = sidebarMaxScroll();
			if (max > 0) {
				int thumbH = sidebarThumbHeight();
				int thumbY = sidebarThumbY();
				draggingScrollbar = Scrollbar.SIDEBAR;
				if (my >= thumbY && my < thumbY + thumbH) {
					scrollbarGrabOffset = my - thumbY;
				} else {
					scrollbarGrabOffset = thumbH / 2.0;
					dragScrollbar(my);
				}
				return true;
			}
		}

		int trackX = contentX + contentW + 8;
		if (mx >= trackX - 5 && mx <= trackX + 8
				&& my >= contentY && my < contentY + contentH) {
			double max = Math.max(0, contentHeight - contentH);
			if (max > 0) {
				int thumbH = contentThumbHeight();
				int thumbY = contentThumbY();
				draggingScrollbar = Scrollbar.CONTENT;
				if (my >= thumbY && my < thumbY + thumbH) {
					scrollbarGrabOffset = my - thumbY;
				} else {
					scrollbarGrabOffset = thumbH / 2.0;
					dragScrollbar(my);
				}
				return true;
			}
		}
		return false;
	}

	/** Converts the held thumb's y position back into the corresponding continuous scroll. */
	private void dragScrollbar(double mouseY) {
		if (draggingScrollbar == Scrollbar.SIDEBAR) {
			int box = sidebarBoxHeight();
			int thumbH = sidebarThumbHeight();
			double travel = Math.max(1, box - thumbH);
			double fraction = (mouseY - scrollbarGrabOffset - sidebarTop()) / travel;
			fraction = Math.max(0, Math.min(1, fraction));
			sidebarScroll = fraction * sidebarMaxScroll();
		} else if (draggingScrollbar == Scrollbar.CONTENT) {
			int thumbH = contentThumbHeight();
			double travel = Math.max(1, contentH - thumbH);
			double fraction = (mouseY - scrollbarGrabOffset - contentY) / travel;
			fraction = Math.max(0, Math.min(1, fraction));
			SCROLL[activeTab.ordinal()] = fraction * Math.max(0, contentHeight - contentH);
		}
	}

	private void drawFooter(GuiGraphicsExtractor g) {
		int y = panelY + panelH - 20;
		Ui.hLine(g, panelX + 8, y - 4, panelW - 16, Ui.BORDER_SOFT);
		String tip = hoverTip.isEmpty()
				? "Esc closes and saves  ·  right-click a cycle to go backwards  ·  the bot keeps walking"
				: hoverTip;
		Ui.textElided(g, font, tip, panelW - 32, panelX + 12, y + 2,
				hoverTip.isEmpty() ? Ui.TEXT_FAINT : Ui.TEXT_MUTED);
	}

	// --------------------------------------------------------------- input

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
		double mx = event.x();
		double my = event.y();
		int button = event.button();

		if (draggingScrollbar != Scrollbar.NONE) return true;

		if (button == 0 && beginScrollbarDrag(mx, my)) {
			unfocusInputs();
			return true;
		}

		String status = ctl.describeState();
		int pw = font.width(status) + 26;
		int px = panelX + panelW - 16 - pw;
		int py = panelY + 14;
		if (button == 0 && mx >= px && mx < px + pw && my >= py && my < py + 18) {
			ctl.toggle(minecraft);
			return true;
		}

		if (button == 0) {
			Tab clicked = tabAt(mx, my);
			if (clicked != null) {
				unfocusInputs();
				activeTab = clicked;
				pendingDelete = "";
				build();
				return true;
			}
		}

		boolean insideContent = my >= contentY && my < contentY + contentH;
		if (insideContent) {
			for (Element e : elements) {
				if (e.mouseClicked(mx, my, button, doubled)) {
					cfg.clampAll();
					return true;
				}
			}
		}
		if (button == 0) unfocusInputs();
		return super.mouseClicked(event, doubled);
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
		if (draggingScrollbar != Scrollbar.NONE) {
			if (event.button() == 0) dragScrollbar(event.y());
			return true;
		}
		for (Element e : elements) e.mouseDragged(event.x(), event.y());
		cfg.clampAll();
		return true;
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		if (draggingScrollbar != Scrollbar.NONE) {
			if (event.button() == 0) {
				dragScrollbar(event.y());
				draggingScrollbar = Scrollbar.NONE;
			}
			return true;
		}
		for (Element e : elements) e.mouseReleased();
		return super.mouseReleased(event);
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double dx, double dy) {
		if (draggingScrollbar != Scrollbar.NONE) return true;
		if (mx >= sidebarX() && mx < sidebarX() + sidebarW()) {
			sidebarScroll = Math.max(0, Math.min(sidebarMaxScroll(), sidebarScroll - dy * 22));
			return true;
		}
		if (my >= contentY && my < contentY + contentH) {
			// the map zooms on the wheel; everything else lets the page scroll
			for (Element e : elements) if (e.mouseScrolled(mx, my, dy)) return true;
		}
		if (mx >= contentX - 8 && mx <= contentX + contentW + 12) {
			SCROLL[activeTab.ordinal()] -= dy * 22;
			return true;
		}
		return super.mouseScrolled(mx, my, dx, dy);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		TextInput focused = focusedInput();
		if (focused != null) {
			focused.keyPressed(event.key(), event.modifiers());
			cfg.clampAll();
			return true;
		}
		if (event.key() == GLFW.GLFW_KEY_TAB) {
			int next = (activeTab.ordinal() + ((event.modifiers() & GLFW.GLFW_MOD_SHIFT) != 0 ? -1 : 1)
					+ Tab.values().length) % Tab.values().length;
			activeTab = Tab.values()[next];
			revealActiveTab();
			build();
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public boolean charTyped(CharacterEvent event) {
		TextInput focused = focusedInput();
		if (focused != null) return focused.charTyped(event.codepoint());
		return super.charTyped(event);
	}

	/** Puts the cursor back in the box with the same label after a rebuild. */
	private void refocus(String label, int cursor) {
		for (Element e : elements) {
			if (e instanceof TextInput t && t.label().equals(label)) {
				t.focus(cursor);
				return;
			}
			if (e instanceof Row row) {
				for (Element c : row.children) {
					if (c instanceof TextInput t2 && t2.label().equals(label)) {
						t2.focus(cursor);
						return;
					}
				}
			}
		}
	}

	private TextInput focusedInput() {
		for (Element e : elements) {
			if (e instanceof TextInput t && t.isFocused()) return t;
			if (e instanceof Row row) {
				for (Element c : row.children) if (c instanceof TextInput t2 && t2.isFocused()) return t2;
			}
		}
		return null;
	}

	private void unfocusInputs() {
		for (Element e : elements) {
			if (e instanceof TextInput t) t.unfocus();
			if (e instanceof Row row) for (Element c : row.children) if (c instanceof TextInput t2) t2.unfocus();
		}
	}

	@Override
	public boolean isPauseScreen() {
		return false; // the world has to keep ticking, otherwise the mod cannot walk
	}

	@Override
	public void onClose() {
		unfocusInputs();
		cfg.clampAll();
		cfg.save();
		ctl.area.save();
		ctl.journal.save();
		super.onClose();
	}
}
