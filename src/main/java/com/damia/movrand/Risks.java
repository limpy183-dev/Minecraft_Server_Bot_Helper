package com.damia.movrand;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Which settings could give the bot away, and why.
 *
 * <p>Worth being precise about what is and is not at stake here, because almost none of this
 * mod is detectable and it is easy to worry about the wrong half.
 *
 * <p><b>Nothing about the movement itself can be caught.</b> The mod holds vanilla key
 * bindings down. Vanilla reads them, vanilla physics computes the motion, and vanilla sends
 * the packet. There is no modified speed, no flight, no reach, no packet the client would not
 * otherwise have sent — the server receives exactly what it would from a person holding W.
 * The container scan, the log, the map and the alert sound never touch the network at all:
 * they read chunks the server already sent and write files on this machine.
 *
 * <p><b>What can be caught is behaviour.</b> Two kinds of it. First, signatures — anything
 * periodic, perfectly linear or bit-for-bit constant in a rotation stream, which is why the
 * view wobble is a random walk and turns ease in and out ({@link Human}). Second, and much
 * more serious, reacting to something the player could not possibly have seen. The scanner
 * can find a chest hall sixty blocks underground; stopping because of it is a decision no
 * legitimate player could make, and it is exactly what a staff member in vanish is testing
 * for when they walk up behind you. Reading hidden information is invisible. Acting on it
 * is not.
 *
 * <p>Everything below is one of those two.
 */
public final class Risks {

	public enum Level {
		HIGH("Unsafe"), MEDIUM("Risky"), LOW("Minor");

		public final String label;

		Level(String label) {
			this.label = label;
		}
	}

	/**
	 * @param setting what to look for in the GUI
	 * @param tab     which tab it lives on
	 * @param why     what a server could notice
	 * @param fix     what pressing the button will do
	 */
	public record Risk(String setting, String tab, Level level, String why, String fix, Consumer<Config> apply) {
	}

	private Risks() {
	}

	/** Every risk the current settings carry, worst first. */
	public static List<Risk> of(Config c) {
		List<Risk> out = new ArrayList<>();

		// --- acting on things the player cannot see ---------------------------

		if (c.destroyerEnabled) {
			out.add(new Risk("Take bases apart automatically", "Base destroyer", Level.HIGH,
					"This is the whole of the mod's detectable surface in one switch. The scan "
							+ "reads blocks through walls, and the bot then walks to them and "
							+ "mines them - a decision no player could make, repeated for hours. "
							+ "Everything else here is about how it looks; this is about what it "
							+ "does.",
					"Turn the destroyer off",
					cfg -> cfg.destroyerEnabled = false));
		}
		if (c.destroyerEnabled && c.destroyRadius > 48) {
			out.add(new Risk("Search radius %d blocks".formatted(c.destroyRadius), "Base destroyer",
					Level.MEDIUM,
					"Walking straight to something forty-eight blocks away and through a wall is "
							+ "not a route a person finds. A shorter radius keeps the bot working "
							+ "on what is in front of it.",
					"Bring the radius back to 32",
					cfg -> cfg.destroyRadius = 32));
		}
		if (c.combatEnabled && c.combatFightPlayers) {
			out.add(new Risk("Fight players", "Combat", Level.HIGH,
					"An automatic swing at a person is aim assist by another name, and it is the "
							+ "one thing here that another player experiences directly rather "
							+ "than infers.",
					"Only fight mobs",
					cfg -> cfg.combatFightPlayers = false));
		}
		if (c.combatEnabled && !c.combatOnlyWhenAttacked) {
			out.add(new Risk("Attack before being attacked", "Combat", Level.MEDIUM,
					"Turning to face a hostile that has not touched you means reacting to "
							+ "something you may not be able to see - the same mistake as "
							+ "stopping for a chest through a wall.",
					"Only fight back once something hits you",
					cfg -> cfg.combatOnlyWhenAttacked = true));
		}
		if (c.autoSellEnabled && c.sellClickMaxSec <= 0.12) {
			out.add(new Risk("Sell clicks %.2fs apart".formatted(c.sellClickMaxSec), "Auto sell",
					Level.MEDIUM,
					"An inventory emptied faster than a hand can click it is the clearest "
							+ "possible signal in a container packet log.",
					"Slow the clicks to a fifth of a second",
					cfg -> {
						cfg.sellClickMinSec = 0.18;
						cfg.sellClickMaxSec = 0.45;
					}));
		}
		if (c.destroyerEnabled && c.taskAimWobbleScale <= 0.1) {
			out.add(new Risk("No wobble while aiming", "Base destroyer", Level.MEDIUM,
					"A rotation stream with no noise in it at all is the single easiest thing "
							+ "to pick out of a log, and mining is where the bot spends most of "
							+ "its rotations.",
					"Put a third of the wobble back",
					cfg -> cfg.taskAimWobbleScale = 0.35));
		}

		if (c.stopOnNearbyPlayer && c.nearbyPlayerReaction.stops() && !c.playerStopOnlyIfVisible) {
			out.add(new Risk("Stop for players you cannot see", "Safety", Level.HIGH,
					"The client is told about players through walls and around corners. Walking away the "
							+ "moment one gets near, without ever having seen them, is precisely the check a "
							+ "staff member in vanish performs.",
					"Only stop for players in line of sight. The alert still plays for every sighting.",
					cfg -> cfg.playerStopOnlyIfVisible = true));
		}

		if (c.containerScanEnabled && c.containerReaction.stops()) {
			out.add(new Risk("Stop on a storage cluster", "Containers", Level.HIGH,
					"The scan reaches through walls and down to bedrock. Halting next to a base you have "
							+ "no line of sight to is a decision no legitimate player could make.",
					"Alert instead of stopping. You still get the sound and the log entry, and the walk "
							+ "carries on as if nothing had been seen.",
					cfg -> cfg.containerReaction = Config.Reaction.ALERT));
		}

		if (!c.pauseWhileScreenOpen) {
			out.add(new Risk("Keep walking while a screen is open", "Movement", Level.HIGH,
					"A chest, furnace or villager window is one the server opened and knows is still open, "
							+ "and a vanilla client cannot move while one is up. This menu is exempt either "
							+ "way, so turning it on costs nothing.",
					"Release the keys while another screen is open. This menu still runs the bot.",
					cfg -> cfg.pauseWhileScreenOpen = true));
		}

		if (c.stopOnHostileMob && c.hostileMobReaction.stops() && !c.hostileStopOnlyIfVisible) {
			out.add(new Risk("Stop for mobs you cannot see", "Safety", Level.MEDIUM,
					"Same shape as the player check: mobs behind walls are in the client's entity list.",
					"Only stop for hostiles in line of sight.",
					cfg -> cfg.hostileStopOnlyIfVisible = true));
		}

		// --- signatures -------------------------------------------------------

		if (!c.reactionDelayEnabled) {
			out.add(new Risk("React instantly", "Safety", Level.MEDIUM,
					"Every stop lands on the exact tick its trigger fired. Nothing with hands reacts in "
							+ "0ms, and the constant zero is easier to spot than any single stop.",
					"Wait a randomised %.0f-%.0fms before acting, as a person would."
							.formatted(c.reactionDelayMinMs, c.reactionDelayMaxMs),
					cfg -> cfg.reactionDelayEnabled = true));
		}

		if (!c.yawJitterEnabled) {
			out.add(new Risk("No view wobble", "Randomisation", Level.MEDIUM,
					"A yaw that holds one float exactly, for minutes, is the cheapest thing in the world "
							+ "to query for. Live hands never sit still.",
					"Switch the constant wobble back on.",
					cfg -> cfg.yawJitterEnabled = true));
		}

		if (c.turnSpeedDegPerTick > 8) {
			out.add(new Risk("Turn speed %.1f deg/tick".formatted(c.turnSpeedDegPerTick), "Randomisation",
					Level.MEDIUM,
					"That is %.0f degrees a second. A turn that fast is a snap, not a hand."
							.formatted(c.turnSpeedDegPerTick * 20),
					"Bring it down to 2.5 deg/tick.",
					cfg -> cfg.turnSpeedDegPerTick = 2.5));
		}

		if (c.avoidEnabled && c.avoidTurnDegPerTick > 12) {
			out.add(new Risk("Dodge speed %.1f deg/tick".formatted(c.avoidTurnDegPerTick), "Obstacles",
					Level.MEDIUM,
					"Steering that hard around an obstacle is a flick, not a correction. Turn events "
							+ "are rate limited for exactly this reason, and dodges are far more frequent.",
					"Bring it down to 6 deg/tick.",
					cfg -> cfg.avoidTurnDegPerTick = 6));
		}

		if (c.segmentMaxSec - c.segmentMinSec < 5) {
			out.add(new Risk("Fixed run length", "Movement", Level.MEDIUM,
					"Events fire on a near-constant cadence, so the intervals between direction changes "
							+ "form a flat histogram with one spike in it.",
					"Widen the run length to a real range.",
					cfg -> {
						cfg.segmentMinSec = Math.max(1, cfg.segmentMinSec);
						cfg.segmentMaxSec = cfg.segmentMinSec * 2;
					}));
		}

		if (c.totalEventWeight() <= 0) {
			out.add(new Risk("No random events", "Randomisation", Level.HIGH,
					"With nothing enabled the walk is a dead-straight line at a constant heading until "
							+ "something physically stops it.",
					"Turn strafes, pauses and turns back on.",
					cfg -> {
						cfg.strafeEnabled = true;
						cfg.pauseEnabled = true;
						cfg.turnEnabled = true;
					}));
		}

		// --- patterns ---------------------------------------------------------

		if (!c.stopAfterMaxRuntime) {
			out.add(new Risk("No time limit", "Safety", Level.MEDIUM,
					"Sessions run until something interrupts them. Hours of continuous input with no idle "
							+ "gap is the oldest AFK-machine heuristic there is, and it needs no movement "
							+ "analysis at all.",
					"Stop after 45 minutes.",
					cfg -> {
						cfg.stopAfterMaxRuntime = true;
						cfg.maxRuntimeMinutes = 45;
					}));
		}

		if (c.areaEnabled && c.areaRoute == AreaCoverage.Route.SERPENTINE) {
			out.add(new Risk("Serpentine route", "Area sweep", Level.MEDIUM,
					"Perfect parallel rows are the most recognisable shape a coverage log can have. It is "
							+ "what the pattern is named after.",
					"Use the organic route instead.",
					cfg -> cfg.areaRoute = AreaCoverage.Route.ORGANIC));
		}

		if ((c.gotoEnabled || c.areaEnabled) && c.gotoMaxWanderDeg < 5) {
			out.add(new Risk("No wander off the bearing", "Go to", Level.LOW,
					"The path becomes a ruler-straight line between two points, held to a fraction of a "
							+ "degree for its whole length.",
					"Allow 28 degrees of wander.",
					cfg -> cfg.gotoMaxWanderDeg = 28));
		}

		if (c.autoJumpEnabled && c.autoJumpCooldownTicks < 5) {
			out.add(new Risk("Jump cooldown %d ticks".formatted(c.autoJumpCooldownTicks), "Obstacles",
					Level.LOW,
					"Against a wall it cannot clear, this hops %.1f times a second without pause."
							.formatted(20.0 / Math.max(1, c.autoJumpCooldownTicks)),
					"Raise the cooldown to 8 ticks.",
					cfg -> cfg.autoJumpCooldownTicks = 8));
		}

		if (c.holdSneak) {
			out.add(new Risk("Sneak the whole time", "Movement", Level.LOW,
					"Nobody crouches for six hours. It breaks no rule, it is just unusual enough to get "
							+ "you watched.",
					"Stop holding sneak.",
					cfg -> cfg.holdSneak = false));
		}

		out.sort((a, b) -> a.level().compareTo(b.level()));
		return out;
	}

	public static int count(Config c) {
		return of(c).size();
	}

	public static long countAt(Config c, Level level) {
		return of(c).stream().filter(r -> r.level() == level).count();
	}

	/** Null when there is nothing to report. */
	public static Level worst(Config c) {
		List<Risk> all = of(c);
		return all.isEmpty() ? null : all.getFirst().level();
	}

	/** One line for a profile row or a header. */
	public static String summary(Config c) {
		List<Risk> all = of(c);
		if (all.isEmpty()) return "No detection risks";
		long high = all.stream().filter(r -> r.level() == Level.HIGH).count();
		if (high > 0) return high + " unsafe, " + (all.size() - high) + " minor";
		return all.size() + (all.size() == 1 ? " risky setting" : " risky settings");
	}

	public static void fixAll(Config c) {
		// one fix can expose another, so keep going until the list is empty
		for (int pass = 0; pass < 4; pass++) {
			List<Risk> all = of(c);
			if (all.isEmpty()) return;
			for (Risk r : all) r.apply().accept(c);
			c.clampAll();
		}
	}

	/** Things people worry about that are not actually observable. Shown next to the risks. */
	public static final List<String> SAFE_BY_CONSTRUCTION = List.of(
			"Movement: vanilla key bindings, vanilla physics, vanilla packets. The server cannot tell "
					+ "these apart from a hand on the keyboard.",
			"The container scan: reads chunk data the server already sent. It sends nothing and asks "
					+ "for nothing.",
			"The log, the map and the alert sound: files and audio on this machine only.",
			"Chat watching: inbound messages only, nothing is ever sent.",
			"Auto-eat: a held-slot change and a use, both of which vanilla sends constantly.");

	/**
	 * Self-check:
	 * {@code ./gradlew selfCheck -Pcheck=com.damia.movrand.Risks}
	 */
	public static void main(String[] args) {
		Config safe = new Config();
		safe.clampAll();

		List<Risk> fresh = of(safe);
		for (Risk r : fresh) {
			assert !r.setting().isBlank() && !r.tab().isBlank() : "a risk has no label";
			assert !r.why().isBlank() && !r.fix().isBlank() : r.setting() + " explains nothing";
			System.out.printf("%-8s %-38s %s%n", r.level().label, r.setting(), r.tab());
		}

		// every fix must actually remove the risk it claims to
		for (Risk r : fresh) {
			Config c = safe.copy();
			r.apply().accept(c);
			c.clampAll();
			assert of(c).stream().noneMatch(x -> x.setting().equals(r.setting()))
					: "fixing \"" + r.setting() + "\" did not clear it";
		}

		// and fixing everything must leave nothing behind
		Config fixed = safe.copy();
		fixAll(fixed);
		assert of(fixed).isEmpty() : "fixAll left " + of(fixed);
		assert summary(fixed).equals("No detection risks") : summary(fixed);

		// the honeypot check is the one that matters most, so prove it fires
		Config exposed = fixed.copy();
		exposed.stopOnNearbyPlayer = true;
		exposed.nearbyPlayerReaction = Config.Reaction.ALERT_AND_STOP;
		exposed.playerStopOnlyIfVisible = false;
		assert worst(exposed) == Level.HIGH : "an ungated player stop was not flagged as unsafe";

		// alerting rather than stopping is not a risk: a sound is not observable
		Config alerting = fixed.copy();
		alerting.containerScanEnabled = true;
		alerting.containerReaction = Config.Reaction.ALERT;
		assert of(alerting).isEmpty() : "alerting on containers was wrongly flagged: " + of(alerting);
		alerting.containerReaction = Config.Reaction.ALERT_AND_STOP;
		assert worst(alerting) == Level.HIGH : "stopping on a through-wall scan was not flagged";

		// a metronome must come out worse than the defaults
		Config robot = fixed.copy();
		robot.yawJitterEnabled = false;
		robot.turnSpeedDegPerTick = 12;
		robot.segmentMinSec = robot.segmentMaxSec = 60;
		assert count(robot) >= 3 : "a metronome only raised " + count(robot) + " risks";

		assert !SAFE_BY_CONSTRUCTION.isEmpty();
		System.out.println("Risks self-check passed");
	}
}
