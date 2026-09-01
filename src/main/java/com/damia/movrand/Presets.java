package com.damia.movrand;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Ready-made setups, plus two things that make an unfamiliar config readable: a score for
 * how human the current settings look, and a list of combinations that will not do what
 * you probably meant.
 *
 * <p>There are a hundred knobs in this mod. Nobody should have to understand all of them to
 * get a sensible walk out of it.
 */
public final class Presets {

	public record Preset(String name, String blurb, List<String> bullets, Consumer<Config> apply) {
	}

	private Presets() {
	}

	/** Settings shared by every preset, so applying one is a clean slate rather than a diff. */
	private static void common(Config c) {
		c.strafeEnabled = true;
		c.pauseEnabled = true;
		c.turnEnabled = true;
		c.yawJitterEnabled = true;
		c.autoJumpEnabled = true;
		c.stuckDetectEnabled = true;
		c.alertEnabled = true;
		c.journalEnabled = true;
		c.logToFiles = true;

		// No preset ships with anything a server could notice - see Risks for what that means
		// and why each of these is on the list. Anyone who wants the riskier behaviour can turn
		// it back on knowing what it costs; nobody should get it by picking a setup blind.
		c.containerHeightRange = Config.HeightRange.FULL;
		c.cameraSmoothYaw = 0.7;
		c.cameraSmoothPitch = 0.88;
		c.avoidEnabled = true;
		c.avoidHazards = true;
		c.avoidPortals = true;
		c.avoidTurnDegPerTick = 6;
		c.reactionDelayEnabled = true;
		c.reactionDelayMinMs = 180;
		c.reactionDelayMaxMs = 520;
		c.playerStopOnlyIfVisible = true;
		c.hostileStopOnlyIfVisible = true;
		c.pauseWhileScreenOpen = true;
		c.containerReaction = Config.Reaction.ALERT;
		c.stopAfterMaxRuntime = true;
		c.maxRuntimeMinutes = 45;
		c.autoJumpCooldownTicks = 8;
		c.holdSneak = false;
	}

	public static final List<Preset> ALL = List.of(

			new Preset("Cautious AFK",
					"For a public server you would rather not be thrown off. Slow, heavily varied, "
							+ "and it stops at the first sign of anything.",
					List.of("Runs of 90–240s, gaussian",
							"Heavy pauses and turns, gentle 1.8°/tick",
							"Stops for any player within 64 blocks",
							"Stops on damage, low health, chat keywords",
							"Safe stop tight at 45% of predicted speed"),
					c -> {
						common(c);
						c.segmentMinSec = 90;
						c.segmentMaxSec = 240;
						c.segmentDistribution = Config.Distribution.GAUSSIAN;
						c.strafeWeight = 35;
						c.pauseWeight = 40;
						c.pauseMinSec = 1.5;
						c.pauseMaxSec = 9;
						c.turnWeight = 30;
						c.turnMinDeg = 4;
						c.turnMaxDeg = 25;
						c.turnSpeedDegPerTick = 1.8;
						c.hopEnabled = true;
						c.hopWeight = 6;
						c.lookAroundEnabled = true;
						c.lookAroundWeight = 14;
						c.yawJitterAmplitudeDeg = 0.7;
						c.holdSprint = false;
						c.stopOnNearbyPlayer = true;
						c.nearbyPlayerRadius = 64;
						c.nearbyPlayerReaction = Config.Reaction.ALERT_AND_STOP;
						c.stopOnDamage = true;
						c.damageReaction = Config.Reaction.ALERT_AND_STOP;
						c.stopOnLowHealth = true;
						c.lowHealthThreshold = 16;
						c.stopOnChatKeyword = true;
						c.chatKeywordReaction = Config.Reaction.ALERT_AND_STOP;
						c.safeStopEnabled = true;
						c.safeStopMinSpeedRatio = 0.45;
						c.stuckReaction = Config.Reaction.ALERT_AND_STOP;
						c.areaRoute = AreaCoverage.Route.ORGANIC;
					}),

			new Preset("Balanced",
					"The sensible middle. Varied enough not to look mechanical, quick enough to "
							+ "actually get somewhere.",
					List.of("Runs of 60–120s, gaussian",
							"Even mix of strafes, pauses and turns",
							"Sprints, stops for players within 32",
							"Alerts and stops when stuck"),
					c -> {
						common(c);
						c.segmentMinSec = 60;
						c.segmentMaxSec = 120;
						c.segmentDistribution = Config.Distribution.GAUSSIAN;
						c.strafeWeight = 40;
						c.pauseWeight = 25;
						c.pauseMinSec = 0.6;
						c.pauseMaxSec = 3.5;
						c.turnWeight = 25;
						c.turnMinDeg = 3;
						c.turnMaxDeg = 22;
						c.turnSpeedDegPerTick = 2.5;
						c.hopEnabled = false;
						c.lookAroundEnabled = false;
						c.yawJitterAmplitudeDeg = 0.55;
						c.holdSprint = true;
						c.stopOnNearbyPlayer = true;
						c.nearbyPlayerRadius = 32;
						c.nearbyPlayerReaction = Config.Reaction.ALERT_AND_STOP;
						c.safeStopEnabled = true;
						c.safeStopMinSpeedRatio = 0.35;
						c.areaRoute = AreaCoverage.Route.ORGANIC;
					}),

			new Preset("Base hunting",
					"Sweep ground and find storage. Maximum detection range, logs everything, and "
							+ "does not stop just because it found a chest.",
					List.of("Container scan at full render distance",
							"Full world height — finds buried and sky bases",
							"Alerts on a cluster but keeps walking",
							"Organic route, counts scanned chunks as covered",
							"Logs clusters, landmarks and players"),
					c -> {
						common(c);
						c.segmentMinSec = 45;
						c.segmentMaxSec = 110;
						c.segmentDistribution = Config.Distribution.GAUSSIAN;
						c.strafeWeight = 35;
						c.pauseWeight = 20;
						c.turnWeight = 30;
						c.holdSprint = true;
						c.containerScanEnabled = true;
						c.containerAutoRadius = true;
						c.containerHeightRange = Config.HeightRange.FULL;
						c.containerThreshold = 10;
						c.containerScanIntervalTicks = 60;
						c.containerReaction = Config.Reaction.ALERT;
						c.scanHoppers = true;
						c.scanChests = true;
						c.scanBarrels = true;
						c.scanShulkers = true;
						c.scanDroppersDispensers = true;
						c.scanSpawners = true;
						c.scanBeacons = true;
						c.scanEnchantingTables = true;
						c.areaEnabled = true;
						c.areaRoute = AreaCoverage.Route.ORGANIC;
						c.areaUseScanRadius = true;
						c.journalEnabled = true;
						c.logToFiles = true;
						c.stopOnNearbyPlayer = true;
						c.nearbyPlayerReaction = Config.Reaction.ALERT_AND_STOP;
						c.playerAlertEnabled = true;
					}),

			new Preset("Getting somewhere",
					"You have coordinates and want to arrive. Still not a straight line, but it "
							+ "does not dawdle.",
					List.of("Runs of 25–70s, few pauses",
							"Tight steering, 12° of wander",
							"Sprints, alerts on arrival",
							"Watchdog if it stops making progress"),
					c -> {
						common(c);
						c.segmentMinSec = 25;
						c.segmentMaxSec = 70;
						c.segmentDistribution = Config.Distribution.GAUSSIAN;
						c.strafeWeight = 45;
						c.pauseWeight = 10;
						c.pauseMinSec = 0.4;
						c.pauseMaxSec = 1.6;
						c.turnWeight = 20;
						c.turnMinDeg = 2;
						c.turnMaxDeg = 12;
						c.turnSpeedDegPerTick = 3;
						c.holdSprint = true;
						c.gotoMaxWanderDeg = 12;
						c.gotoCorrectionDegPerTick = 2.2;
						c.gotoStopOnArrive = true;
						c.gotoArriveReaction = Config.Reaction.ALERT_AND_STOP;
						c.gotoNoProgressSec = 45;
						c.areaEnabled = false;
					}),

			new Preset("Maximum humanisation",
					"Every variation switched on and turned up. The least predictable and the "
							+ "slowest — this is the one for the longest unattended runs.",
					List.of("Runs of 100–300s, gaussian",
							"Every event type on, including hops and looking around",
							"Very smooth 1.2°/tick turns, strong jitter",
							"Walks rather than sprints",
							"Random route, wide scatter inside each chunk"),
					c -> {
						common(c);
						c.segmentMinSec = 100;
						c.segmentMaxSec = 300;
						c.segmentDistribution = Config.Distribution.GAUSSIAN;
						c.strafeWeight = 30;
						c.strafeMinSec = 0.3;
						c.strafeMaxSec = 1.6;
						c.pauseWeight = 30;
						c.pauseMinSec = 2;
						c.pauseMaxSec = 14;
						c.turnWeight = 25;
						c.turnMinDeg = 5;
						c.turnMaxDeg = 40;
						c.turnSpeedDegPerTick = 1.2;
						c.hopEnabled = true;
						c.hopWeight = 10;
						c.lookAroundEnabled = true;
						c.lookAroundWeight = 20;
						c.lookPitchMinDeg = -35;
						c.lookPitchMaxDeg = 25;
						c.yawJitterEnabled = true;
						c.yawJitterAmplitudeDeg = 0.9;
						c.yawJitterSpeed = 0.022;
						c.holdSprint = false;
						c.areaRoute = AreaCoverage.Route.ORGANIC;
						c.areaRouteLookahead = 10;
						c.areaTargetJitter = 7;
						c.safeStopEnabled = true;
					}),

			new Preset("Single player",
					"Your own world, nobody watching. Fast coverage, no social paranoia, still "
							+ "stops if something actually goes wrong.",
					List.of("Runs of 30–80s",
							"Serpentine route — fastest full coverage",
							"No player or chat watching",
							"Keeps the safe stop and the stuck alert"),
					c -> {
						common(c);
						c.segmentMinSec = 30;
						c.segmentMaxSec = 80;
						c.segmentDistribution = Config.Distribution.UNIFORM;
						c.strafeWeight = 30;
						c.pauseWeight = 15;
						c.turnWeight = 25;
						c.holdSprint = true;
						c.stopOnNearbyPlayer = false;
						c.stopOnChatKeyword = false;
						c.stopOnHostileMob = true;
						c.hostileMobReaction = Config.Reaction.ALERT_AND_STOP;
						c.areaRoute = AreaCoverage.Route.SERPENTINE;
						c.areaTargetJitter = 3;
						c.safeStopEnabled = true;
						c.stuckReaction = Config.Reaction.ALERT_AND_STOP;
					}),

			new Preset("Paranoid",
					"Stops at the faintest hint of trouble. Expect it to stop often — that is "
							+ "the point.",
					List.of("Any player within 96 blocks",
							"Any damage at all, health under 18",
							"Chat keywords, hostiles within 24",
							"Safe stop at 55% of predicted speed",
							"Hard stop after 20 minutes"),
					c -> {
						common(c);
						c.segmentMinSec = 60;
						c.segmentMaxSec = 150;
						c.holdSprint = false;
						c.stopOnNearbyPlayer = true;
						c.nearbyPlayerRadius = 96;
						c.nearbyPlayerReaction = Config.Reaction.ALERT_AND_STOP;
						c.playerAlertEnabled = true;
						c.playerAlertRepeats = 8;
						c.stopOnDamage = true;
						c.damageThreshold = 0.5;
						c.damageReaction = Config.Reaction.ALERT_AND_STOP;
						c.stopOnLowHealth = true;
						c.lowHealthThreshold = 18;
						c.stopOnHostileMob = true;
						c.hostileMobRadius = 24;
						c.hostileMobReaction = Config.Reaction.ALERT_AND_STOP;
						c.stopOnChatKeyword = true;
						c.chatKeywordReaction = Config.Reaction.ALERT_AND_STOP;
						c.stopAtLedge = true;
						c.ledgeReaction = Config.Reaction.ALERT_AND_STOP;
						c.safeStopEnabled = true;
						c.safeStopMinSpeedRatio = 0.55;
						c.safeStopWindowSec = 1.5;
						c.stopAfterMaxRuntime = true;
						c.maxRuntimeMinutes = 20;
						c.stopWhenUnfocused = true;
					})
	);

	// -------------------------------------------------------- humanisation

	public record Score(int value, String verdict, List<String> reasons) {
	}

	/**
	 * A weighted checklist, not a measurement. It answers "does this look like a person" the
	 * way a reviewer would: is the timing varied, are the turns smooth, is there noise.
	 */
	public static Score score(Config c) {
		int score = 0;
		List<String> reasons = new ArrayList<>();

		// timing variety is the single biggest tell
		double spread = c.segmentMaxSec <= 0 ? 0 : (c.segmentMaxSec - c.segmentMinSec) / c.segmentMaxSec;
		if (spread <= 0.01) {
			reasons.add("✗ Every run is exactly the same length — a perfect metronome");
		} else if (spread < 0.25) {
			score += 8;
			reasons.add("~ Run lengths barely vary");
		} else {
			score += 20;
			reasons.add("✓ Run lengths vary widely");
		}

		if (c.segmentDistribution != Config.Distribution.UNIFORM) {
			score += 8;
			reasons.add("✓ Non-uniform timing distribution");
		} else {
			reasons.add("~ Uniform timing — gaussian reads more human");
		}

		double weight = c.totalEventWeight();
		if (weight <= 0) {
			reasons.add("✗ No random events at all — this walks in a dead straight line");
		} else {
			int kinds = (c.strafeEnabled ? 1 : 0) + (c.pauseEnabled ? 1 : 0) + (c.turnEnabled ? 1 : 0)
					+ (c.hopEnabled ? 1 : 0) + (c.lookAroundEnabled ? 1 : 0);
			score += Math.min(20, kinds * 5);
			reasons.add((kinds >= 4 ? "✓ " : "~ ") + kinds + " of 5 event types enabled");
		}

		if (c.turnEnabled) {
			if (c.turnSpeedDegPerTick <= 3) {
				score += 12;
				reasons.add("✓ Turns are smooth");
			} else if (c.turnSpeedDegPerTick <= 8) {
				score += 6;
				reasons.add("~ Turns are a little quick");
			} else {
				reasons.add("✗ Turns snap — no hand moves a mouse that fast");
			}
		}

		if (c.yawJitterEnabled && c.yawJitterAmplitudeDeg >= 0.2) {
			score += 12;
			reasons.add("✓ Constant view jitter");
		} else {
			reasons.add("✗ No view jitter — a perfectly still camera is unmistakable");
		}

		if (c.pauseEnabled && c.pauseMaxSec >= 2) {
			score += 8;
			reasons.add("✓ Pauses long enough to read as a person stopping");
		}

		if (c.areaEnabled) {
			if (c.areaRoute == AreaCoverage.Route.SERPENTINE) {
				reasons.add("✗ Serpentine route — perfect rows are the most obvious pattern there is");
			} else {
				score += 10;
				reasons.add("✓ Route is not a lawnmower pattern");
			}
			if (c.areaTargetJitter >= 2) {
				score += 5;
				reasons.add("✓ Aims at scattered points, not chunk centres");
			} else {
				reasons.add("~ Walks to exact chunk centres");
			}
		} else {
			score += 10; // free wandering cannot show a coverage pattern
		}

		if (c.reseedOnStart) {
			score += 5;
			reasons.add("✓ Fresh random seed every start");
		}

		int value = Math.max(0, Math.min(100, score));
		String verdict = value >= 80 ? "Very human"
				: value >= 60 ? "Convincing"
				: value >= 40 ? "Passable"
				: value >= 20 ? "Mechanical"
				: "Obviously automated";
		return new Score(value, verdict, reasons);
	}

	/**
	 * Self-check:
	 * {@code ./gradlew selfCheck -Pcheck=com.damia.movrand.Presets}
	 *
	 * <p>A preset that silently produces a config the clamps then rewrite, or one that scores
	 * badly on the very thing it promises, is worse than no preset at all.
	 */
	public static void main(String[] args) {
		assert !ALL.isEmpty() : "no presets defined";

		for (Preset preset : ALL) {
			assert !preset.name().isBlank() : "a preset has no name";
			assert !preset.blurb().isBlank() : preset.name() + " has no blurb";
			assert !preset.bullets().isEmpty() : preset.name() + " lists nothing it changes";

			Config c = new Config();
			preset.apply().accept(c);

			// a preset must survive the clamps untouched - if it does not, its values are out of range
			String before = c.segmentMinSec + "|" + c.segmentMaxSec + "|" + c.turnSpeedDegPerTick
					+ "|" + c.alertRepeatCount + "|" + c.containerChunkRadius;
			c.clampAll();
			String after = c.segmentMinSec + "|" + c.segmentMaxSec + "|" + c.turnSpeedDegPerTick
					+ "|" + c.alertRepeatCount + "|" + c.containerChunkRadius;
			assert before.equals(after) : preset.name() + " needed clamping: " + before + " -> " + after;

			assert c.segmentMinSec < c.segmentMaxSec : preset.name() + " has a fixed run length";
			assert c.totalEventWeight() > 0 : preset.name() + " enabled no random events";

			Score s = score(c);
			assert s.value() >= 40 : preset.name() + " only scores " + s.value() + " (" + s.verdict() + ")";
			assert !s.reasons().isEmpty();

			// picking a setup blind must never hand you something a server could notice
			assert Risks.of(c).isEmpty() : preset.name() + " ships with a detection risk: " + Risks.of(c);

			// a preset should not ship with a warning already attached
			List<String> w = warnings(c);
			w.removeIf(line -> line.startsWith("Alert sound:")); // depends on the user's folder, not the preset
			assert w.isEmpty() : preset.name() + " triggers a warning: " + w;

			System.out.printf("%-24s %3d  %-18s %d bullets  %s%n",
					preset.name(), s.value(), s.verdict(), preset.bullets().size(), Risks.summary(c));
		}

		// the presets that promise humanity should beat the ones that promise speed
		Config human = new Config();
		ALL.stream().filter(p -> p.name().equals("Maximum humanisation")).findFirst().orElseThrow()
				.apply().accept(human);
		Config quick = new Config();
		ALL.stream().filter(p -> p.name().equals("Getting somewhere")).findFirst().orElseThrow()
				.apply().accept(quick);
		assert score(human).value() > score(quick).value()
				: "maximum humanisation (" + score(human).value() + ") did not beat getting somewhere ("
				+ score(quick).value() + ")";

		// the score has to actually notice the tells it claims to
		Config robot = new Config();
		robot.segmentMinSec = robot.segmentMaxSec = 60;
		robot.segmentDistribution = Config.Distribution.UNIFORM;
		robot.strafeEnabled = robot.pauseEnabled = robot.turnEnabled = false;
		robot.hopEnabled = robot.lookAroundEnabled = false;
		robot.yawJitterEnabled = false;
		robot.reseedOnStart = false;
		assert score(robot).value() < 20 : "a metronome scored " + score(robot).value();
		assert !warnings(robot).isEmpty() : "a config with no events raised no warning";

		// and the specific broken combinations
		Config clash = new Config();
		clash.areaEnabled = true;
		clash.gotoEnabled = true;
		assert warnings(clash).stream().anyMatch(x -> x.contains("both on"))
				: "area + go-to clash went unreported";

		Config noFiles = new Config();
		noFiles.journalEnabled = true;
		noFiles.logToFiles = false;
		assert warnings(noFiles).stream().anyMatch(x -> x.contains("not writing files"))
				: "logging without files went unreported";

		System.out.println("Presets self-check passed");
	}

	// ------------------------------------------------------------- warnings

	/** Combinations that compile fine and then do not do what you meant. */
	public static List<String> warnings(Config c) {
		List<String> out = new ArrayList<>();

		if (c.totalEventWeight() <= 0) {
			out.add("No random events are enabled — the bot will walk in a straight line forever.");
		}
		if (c.segmentMaxSec - c.segmentMinSec < 0.01) {
			out.add("Run length has no range, so events fire on a fixed cadence. Widen it.");
		}
		if (c.areaEnabled && c.gotoEnabled) {
			out.add("Area sweep and go-to are both on. The sweep wins; go-to is ignored.");
		}
		if (c.areaEnabled && c.areaUseScanRadius && !c.containerScanEnabled) {
			out.add("The sweep is set to count scanned chunks, but the container scan is off. "
					+ "Only the chunk you stand in will tick off.");
		}
		if (c.alertEnabled) {
			String status = AlertSound.status(c);
			if (status.startsWith("⚠")) out.add("Alert sound: " + status.substring(1).trim());
		}
		if (!c.alertEnabled && (c.stuckReaction.alerts() || c.containerReaction.alerts()
				|| c.nearbyPlayerReaction.alerts())) {
			out.add("Reactions are set to alert, but the alert sound is switched off.");
		}
		if (!c.safeStopEnabled) {
			out.add("Safe stop is off. Nothing will notice if the server pins you in place.");
		}
		if (c.autoEatEnabled && c.autoEatThreshold <= 2) {
			out.add("Auto-eat only triggers at " + c.autoEatThreshold
					+ "/20 hunger, which is late enough to start losing health.");
		}
		if (c.holdSprint && c.autoEatEnabled && c.autoEatThreshold >= 19) {
			out.add("Sprinting with auto-eat at " + c.autoEatThreshold + "/20 will eat almost constantly.");
		}
		if (!c.journalEnabled) {
			out.add("Coordinate logging is off — nothing found will be recorded.");
		}
		if (c.journalEnabled && !c.logToFiles) {
			out.add("Logging is on but not writing files. Entries are lost when the game closes.");
		}
		if (c.turnSpeedDegPerTick > 8) {
			out.add("Turn speed above 8°/tick snaps the camera instantly.");
		}
		if (c.alertCooldownSec > 60) {
			out.add("Alert cooldown is " + (int) c.alertCooldownSec
					+ "s, so most events will pass without a sound.");
		}
		if (c.containerScanEnabled && c.containerHeightRange == Config.HeightRange.ABSOLUTE
				&& c.containerMaxY - c.containerMinY < 8) {
			out.add("The container height band is only " + (c.containerMaxY - c.containerMinY)
					+ " blocks tall, which will find almost nothing. Widen it or use the whole world.");
		}
		if (c.containerScanEnabled && !c.containerAutoRadius && c.containerChunkRadius > 24) {
			out.add("A manual scan radius of " + c.containerChunkRadius
					+ " chunks is far past any render distance — most of it will be unloaded.");
		}
		return out;
	}
}
