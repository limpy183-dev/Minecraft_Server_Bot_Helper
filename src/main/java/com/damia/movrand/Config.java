package com.damia.movrand;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Every knob in the mod. Plain public fields, serialised straight to JSON.
 */
public final class Config {

	// ---------------------------------------------------------------- enums

	public enum Distribution {
		UNIFORM("Uniform", "Every value in the range is equally likely."),
		GAUSSIAN("Gaussian", "Clusters near the middle of the range — looks more human."),
		SKEW_LOW("Skew low", "Favours the short end of the range."),
		SKEW_HIGH("Skew high", "Favours the long end of the range.");

		public final String label, tip;

		Distribution(String label, String tip) {
			this.label = label;
			this.tip = tip;
		}
	}

	public enum Reaction {
		NOTHING("Do nothing", "Log it and keep going."),
		ALERT("Alert only", "Play the alert sound, keep walking."),
		STOP("Stop only", "Stop movement silently."),
		ALERT_AND_STOP("Alert + stop", "Play the alert sound and stop movement.");

		public final String label, tip;

		Reaction(String label, String tip) {
			this.label = label;
			this.tip = tip;
		}

		public boolean alerts() {
			return this == ALERT || this == ALERT_AND_STOP;
		}

		public boolean stops() {
			return this == STOP || this == ALERT_AND_STOP;
		}
	}

	public enum HeightRange {
		FULL("Whole world", "Bedrock to the build limit. Finds everything, at any depth."),
		RELATIVE("Around me", "A band above and below wherever you happen to be standing."),
		ABSOLUTE("Between two levels", "A fixed pair of Y levels, whatever height you are at.");

		public final String label, tip;

		HeightRange(String label, String tip) {
			this.label = label;
			this.tip = tip;
		}
	}

	public enum HudCorner {
		TOP_LEFT("Top left"), TOP_RIGHT("Top right"), BOTTOM_LEFT("Bottom left"), BOTTOM_RIGHT("Bottom right");

		public final String label;

		HudCorner(String label) {
			this.label = label;
		}
	}

	// ------------------------------------------------------------ movement

	public boolean movementEnabled = false;
	public boolean holdSprint = true;
	/**
	 * Sprint-jump on nearly every landing. A sprint-jump carries further than a sprint does,
	 * so someone actually going somewhere does it almost every time their feet touch the
	 * ground — and a run of open country crossed without once leaving the floor is not
	 * something a person produces.
	 */
	public boolean jumpSprintEnabled = true;
	/** Odds of hopping on any given landing. Not 1: nobody hits every single one. */
	public double jumpSprintChance = 0.92;
	public boolean holdSneak = false;
	/**
	 * Release every key the moment a screen opens. This mod's own menu never counts, so the
	 * bot still runs while you watch it — but a chest or villager window is one the server
	 * opened and knows is still open, and a vanilla client cannot move while one is up.
	 */
	public boolean pauseWhileScreenOpen = true;
	/** Stop everything if the game window loses focus. */
	public boolean stopWhenUnfocused = false;

	// ------------------------------------------------- forward-run segments

	/** A "segment" is an uninterrupted forward run. When it ends, one random event fires. */
	public double segmentMinSec = 60.0;
	public double segmentMaxSec = 120.0;
	public Distribution segmentDistribution = Distribution.GAUSSIAN;

	// ------------------------------------------------------- random events

	public boolean strafeEnabled = true;
	public double strafeWeight = 40;
	public double strafeMinSec = 0.25;
	public double strafeMaxSec = 1.10;
	/** Keep holding forward while strafing, so the player drifts diagonally instead of sidestepping. */
	public boolean strafeKeepsForward = true;

	public boolean pauseEnabled = true;
	public double pauseWeight = 25;
	public double pauseMinSec = 0.6;
	public double pauseMaxSec = 3.5;

	public boolean turnEnabled = true;
	public double turnWeight = 25;
	public double turnMinDeg = 3.0;
	public double turnMaxDeg = 22.0;
	/** Degrees of yaw applied per tick while a turn event plays out. Lower = smoother. */
	public double turnSpeedDegPerTick = 2.5;

	public boolean hopEnabled = false;
	public double hopWeight = 10;

	public boolean lookAroundEnabled = false;
	public double lookAroundWeight = 10;
	public double lookPitchMinDeg = -25.0;
	public double lookPitchMaxDeg = 20.0;

	/** Constant tiny yaw noise, applied every tick. Looks like a human hand on a mouse. */
	public boolean yawJitterEnabled = true;
	public double yawJitterAmplitudeDeg = 0.55;
	public double yawJitterSpeed = 0.03;

	/**
	 * How much of last tick's heading the camera keeps, 0 (point straight at it) to 0.95
	 * (about a second to arrive). Everything upstream produces steps — a turn is sampled once
	 * a tick, a dodge appears the moment a wall does — and this is what rounds them off.
	 */
	public double cameraSmoothYaw = 0.7;
	/** The same for the pitch, which a hand moves less and settles more slowly. */
	public double cameraSmoothPitch = 0.88;

	// ------------------------------------------------------------ auto jump

	public boolean autoJumpEnabled = true;
	/** Only hop obstacles this tall (blocks). 1 = classic single-block step-up. */
	public int autoJumpMaxHeight = 1;
	/** Minimum ticks between auto jumps, stops the machine-gun hop. */
	public int autoJumpCooldownTicks = 8;
	/** Probe distance ahead of the player, in blocks. */
	public double autoJumpProbeDistance = 0.65;
	public boolean autoJumpSwimUp = true;

	// ------------------------------------------------------- getting round

	/**
	 * Steer around what is in the way rather than walking into it and waiting for the stuck
	 * detector to notice. The correction is kept separate from the heading, so once the way
	 * ahead clears it simply unwinds and the original direction is resumed.
	 */
	public boolean avoidEnabled = true;
	/** How far ahead each candidate heading is probed, in blocks. */
	public double avoidLookahead = 3.0;
	/** The widest the steering may deviate from where it wants to go. */
	public double avoidMaxDeviationDeg = 150;
	/** Degrees per tick applied to a dodge, and taken back off it afterwards. */
	public double avoidTurnDegPerTick = 6.0;
	public double avoidReturnDegPerTick = 3.0;
	/** Walk round a drop rather than stopping at it. Uses the ledge depth below. */
	public boolean avoidHoles = true;
	public boolean avoidLava = true;
	public boolean avoidWater = false;
	/**
	 * Fire, magma, cactus, berry bushes, wither roses, dripstone, powder snow and cobwebs —
	 * the things that hurt or trap on contact, most of which you can walk straight through.
	 */
	public boolean avoidHazards = true;
	/** Nether portals, end portals and gateways. Not dangerous; just somewhere else. */
	public boolean avoidPortals = true;

	// ------------------------------------------------------ stuck detection

	public boolean stuckDetectEnabled = true;
	/** Blocks of horizontal progress required inside the window to count as "moving". */
	public double stuckMinDistance = 1.0;
	public double stuckWindowSec = 3.0;
	/** Also trip when something else releases our movement keys or eats our input. */
	public boolean detectInputBlocked = true;
	/** Try to free ourselves (jump + random turn) before crying for help. */
	public boolean stuckAutoUnstick = true;
	public int stuckUnstickAttempts = 2;
	public Reaction stuckReaction = Reaction.ALERT_AND_STOP;

	// ----------------------------------------------------------- alert sound

	public boolean alertEnabled = true;
	public String alertFolder = "C:\\Users\\damia\\Desktop\\AI_Projects\\Minecraft\\Movement_And_Randomisation\\Sound_alert";
	/** Empty = use the first playable file found in the folder. */
	public String alertFile = "";
	public int alertRepeatCount = 3;
	public double alertGapSec = 0.35;
	public double alertVolume = 1.0;
	/** Also fire a vanilla in-game ping, in case the external player fails. */
	public boolean alertInGameSound = true;
	/** Print the reason to chat too. */
	public boolean alertChatMessage = true;
	/** Never fire more than one alert inside this many seconds. */
	public double alertCooldownSec = 5.0;

	// ------------------------------------------------- go-to-coordinates

	public boolean gotoEnabled = false;
	public double gotoX = 0;
	public double gotoZ = 0;
	/** Purely informational — the mod never tries to change altitude on purpose. */
	public double gotoY = 0;
	public boolean gotoUseY = false;
	public double gotoArriveRadius = 2.0;
	/** How hard we steer back onto the bearing. Lower = lazier, more natural arcs. */
	public double gotoCorrectionDegPerTick = 1.6;
	/** Let the random turn events wander this far off the bearing before correcting. */
	public double gotoMaxWanderDeg = 28.0;
	/**
	 * Walk there on a route rather than steering at the bearing.
	 *
	 * <p>The bearing is enough across open country and useless in a building: it answers "which
	 * way is it" and never "is there a wall in the way". With this on the same search the base
	 * destroyer uses plans the way there — round the wall, through the door, down the drop —
	 * and the same follower walks it, through the same camera.
	 */
	public boolean gotoPathfind = false;
	public boolean gotoStopOnArrive = true;
	public Reaction gotoArriveReaction = Reaction.ALERT;
	/** Give up and alert if we get no closer for this long. 0 = never. */
	public double gotoNoProgressSec = 45.0;

	// ------------------------------------------------ looking like a person

	/**
	 * Wait a human beat before acting on a trigger. Stopping dead on the exact tick a player
	 * crosses a radius is the one thing in this mod that no reaction time can explain.
	 */
	public boolean reactionDelayEnabled = true;
	public double reactionDelayMinMs = 180;
	public double reactionDelayMaxMs = 520;

	/**
	 * Only let a player sighting stop movement when you could actually have seen them. The
	 * alert still plays either way, because a sound on your own machine is not something the
	 * server can observe — walking away from someone behind a wall is.
	 */
	public boolean playerStopOnlyIfVisible = true;
	public boolean hostileStopOnlyIfVisible = true;

	// -------------------------------------------------- container scanning

	public boolean containerScanEnabled = true;
	/** Follow the effective render distance — the widest the client can actually see. */
	public boolean containerAutoRadius = true;
	/** Chunks around the player to sweep when the radius is set by hand. */
	public int containerChunkRadius = 8;
	/**
	 * Which slice of the world column the scan looks at. Null in a config written before this
	 * existed; {@link #clampAll()} works it out from {@link #containerFullHeight}.
	 */
	public HeightRange containerHeightRange = null;
	/** Kept in step with the mode above so the json stays readable, and to migrate old files. */
	public boolean containerFullHeight = true;
	/** Vertical range around the player, in blocks, in {@link HeightRange#RELATIVE}. */
	public int containerYRange = 64;
	/** The fixed band, in {@link HeightRange#ABSOLUTE}. Clamped to the world's own limits. */
	public int containerMinY = -64;
	public int containerMaxY = 320;
	public int containerScanIntervalTicks = 60;
	/** Trip once the weighted total reaches this. */
	public int containerThreshold = 12;
	/**
	 * Count only storage that sits together, rather than everything within reach. At the
	 * default radius the scan reaches a thousand blocks, and three unrelated farms adding up
	 * to a base that does not exist is not a useful alert.
	 */
	public boolean containerGroupEnabled = true;
	/** Chunks either side of the middle of a group. 2 is a 5x5 patch, 80 blocks across. */
	public int containerGroupChunks = 2;
	public Reaction containerReaction = Reaction.ALERT_AND_STOP;
	/**
	 * Record a place the scanner finds once and never again, rather than once per dedupe
	 * window. A chest hall does not become news half an hour later.
	 */
	public boolean containerLogOnce = true;

	public boolean scanHoppers = true;
	public boolean scanChests = true;
	public boolean scanBarrels = true;
	public boolean scanShulkers = true;
	public boolean scanDroppersDispensers = true;
	public boolean scanFurnaces = false;
	public boolean scanCraftingStations = false;

	// -------------------------------------------------------- safety stops

	public boolean stopOnLowHealth = true;
	public double lowHealthThreshold = 12.0;
	public Reaction lowHealthReaction = Reaction.ALERT_AND_STOP;

	public boolean stopOnDamage = true;
	public double damageThreshold = 1.0;
	public Reaction damageReaction = Reaction.ALERT_AND_STOP;

	public boolean stopOnLowHunger = false;
	public int lowHungerThreshold = 6;
	public Reaction hungerReaction = Reaction.STOP;

	public boolean stopOnNearbyPlayer = true;
	public double nearbyPlayerRadius = 32.0;
	public Reaction nearbyPlayerReaction = Reaction.ALERT_AND_STOP;

	public boolean stopOnHostileMob = false;
	public double hostileMobRadius = 12.0;
	public Reaction hostileMobReaction = Reaction.STOP;

	/**
	 * Walk away from a hostile that is closing in, and keep walking until nothing is. This is
	 * the half of "react to hostiles" that a stop cannot cover: standing still leaves the bot
	 * exactly where the thing chasing it was already heading.
	 */
	public boolean fleeFromHostiles = true;
	/** A hostile closing inside this many blocks starts the retreat. */
	public double fleeRadius = 14.0;
	/** Nothing hostile inside the radius for this long before the route resumes. */
	public double fleeSafeSec = 4.0;

	/**
	 * Endermen aggro on being looked at, and a sweep that keeps turning towards its next
	 * chunk will sooner or later turn towards one. Pointing the view at the floor while one
	 * is in range costs nothing and is the only reliable way to not look at it.
	 */
	public boolean endermanAvoidLook = true;
	/** Vanilla checks for a stare out to 64 blocks; this is how close one has to be to matter. */
	public double endermanLookRadius = 40.0;
	/** How far down to point while one is in range. Past a few degrees no stare check can hit. */
	public double endermanLookDownDeg = 35.0;

	public boolean stopAtLedge = true;
	/** Trip when the drop directly ahead is deeper than this. */
	public int ledgeDropBlocks = 3;
	public Reaction ledgeReaction = Reaction.STOP;

	public boolean stopInLiquid = false;
	public Reaction liquidReaction = Reaction.STOP;

	public boolean stopOnChatKeyword = true;
	public List<String> chatKeywords = new ArrayList<>(List.of("staff", "admin", "afk", "bot"));
	public boolean chatKeywordMatchOwnName = true;
	/**
	 * Your own name is in every advancement broadcast you earn and in every line you type,
	 * and neither is somebody talking about you. On drops the name match for those two.
	 */
	public boolean chatIgnoreSelfAndAdvancements = true;
	/**
	 * Whether server and system messages count, or only what other players type. On keeps
	 * broadcasts like "[Staff] watching you" in scope; off narrows it to real chat.
	 */
	public boolean chatWatchSystemMessages = true;
	public Reaction chatKeywordReaction = Reaction.ALERT_AND_STOP;

	public boolean stopAfterMaxRuntime = false;
	public double maxRuntimeMinutes = 30.0;
	public Reaction maxRuntimeReaction = Reaction.ALERT_AND_STOP;

	public boolean stopOnDisconnect = true;

	// ------------------------------------------------------- area coverage

	public boolean areaEnabled = false;
	public double areaX1 = -128, areaZ1 = -128, areaX2 = 128, areaZ2 = 128;
	/** Trim the rectangle to a disc — what "N chunks around me" should mean. */
	public boolean areaCircular = false;
	/** Filter the sweep map overlay; navigation always uses this world and dimension. */
	public boolean areaThisWorldOnly = true;
	public AreaCoverage.Route areaRoute = AreaCoverage.Route.ORGANIC;
	/** How many of the nearest unvisited chunks the organic route picks between. */
	public int areaRouteLookahead = 6;
	/** Blocks of scatter around the chunk centre, so it never walks to the exact middle. */
	public double areaTargetJitter = 5.0;
	/** Count the chunks the container scan already swept as covered, not just the one you stand in. */
	public boolean areaUseScanRadius = true;
	/** Steer back once you drift this far outside the area. */
	public double areaLeashBlocks = 48;
	public boolean areaStopWhenDone = true;
	public Reaction areaDoneReaction = Reaction.ALERT_AND_STOP;
	/** Chunks either side of centre for the "around me" button. */
	public int areaAroundRadius = 8;
	/** Chunks across the interactive map. Zoomed with the wheel, so the range is wide. */
	public int areaMapView = 24;


	// --------------------------------------------------- the base destroyer

	public boolean destroyerEnabled = false;
	/** Versioned migration applies the requested terrain capabilities to existing saved settings once. */
	public int terrainSettingsVersion = 0;
	public boolean baritoneNavigation = true;
	public boolean destroyLoadedChunks = true;
	public boolean protectMiningDrops = true;
	/** Optional close-range mining; independent of lava containment and catch floors. */
	public boolean mineWithinPickupRange = true;
	public int dropSafetyDepth = 16;
	public double prepareSiteSec = 45;
	public boolean baritoneParkour = true;
	public boolean baritoneParkourPlace = true;
	public boolean baritoneVines = true;
	public boolean baritoneWaterBucketFalls = false;
	public double baritoneTurnSmoothing = 0.35;
	public double baritoneTurnRate = 24;
	public double baritoneAimVariation = 0.03;
	/** Optional walking/sprinting variation on ordinary supported ground only. */
	public boolean baritoneRandomisePace = false;
	public double baritoneSprintChance = 0.8;
	public double baritonePaceMinSec = 2;
	public double baritonePaceMaxSec = 6;
	public double baritoneNoProgressSec = 8;
	/** How far to look for blocks worth breaking. Blocks, not chunks. */
	public int destroyRadius = 32;
	public int destroyVerticalRadius = 16;
	/** A ceiling on one scan, so a warehouse of redstone does not build a huge list. */
	public int destroyMaxTargets = 512;
	/** How often to look again, drawn fresh between the two so it is not a metronome. */
	public double destroyScanSec = 1.5;
	public double destroyScanMaxSec = 3.0;
	/** Maximum near-tie candidates, re-ranked by current distance at each decision. */
	public int destroyTargetChoices = 3;
	/** Random choices stay this close to the nearest candidate, measured in blocks. */
	public double destroyTargetDistanceSlack = 0.35;
	public double destroyTargetRandomness = 0.2;
	public boolean destroyPreferReachable = true;
	/** Keep inventories and their potentially large item drops until other selected blocks are gone. */
	public boolean destroyStorageLast = false;
	/** Only select blocks the player's current view can genuinely raycast to. */
	public boolean destroyRequireLineOfSight = false;
	/** Horizontal/vertical view cone used with line-of-sight. 360 means any visible direction. */
	public double destroyFieldOfViewDeg = 120;
	/** Consecutive complete empty scans required before the destroyer can announce completion. */
	public int destroyEmptyScansToFinish = 3;
	/** Permit completion when part of the configured search circle is not currently loaded. */
	public boolean destroyAllowIncompleteScanFinish = false;
	/**
	 * How long one block may be swung at before it is written off.
	 *
	 * <p>The only honest way to tell a slow block from an impossible one: a client is never
	 * told why a swing did nothing, so claimed land, region protection and spawn protection all
	 * look exactly like mining that has not finished yet. Obsidian with an iron pickaxe is
	 * twenty-five seconds of honest work, so this is generous on purpose.
	 */
	public double destroyBlockSec = 35;
	/**
	 * How long one target may occupy the bot in total, however that time is spent.
	 *
	 * <p>Wider than the block ceiling, because it covers walking there as well as breaking it.
	 * The point is that it exists: with every individual step bounded and nothing bounding the
	 * whole, a bot can still spend an afternoon on one block by failing at it in a slightly
	 * different way each time.
	 */
	public double destroyTargetSec = 120;
	/** How long an unreachable or protected target stays out of the candidate list. */
	public double destroyRetrySec = 45;
	/** Moving this far makes a written-off target worth reconsidering before the timer expires. */
	public double destroyRetryMoveBlocks = 8;
	public boolean destroySprint = true;
	/**
	 * Whether the job carries on through the interruptions the job itself causes.
	 *
	 * <p>The guards above were written for a bot that wanders quietly and wants to be told
	 * when anything happens. A base destroyer sets every one of them off by doing its job:
	 * it walks into rooms full of chests, it gets hit, it wades, and it stalls in doorways.
	 * With all of them stopping the mod outright, turning the destroyer on is a way of
	 * switching the mod off a few seconds later - and from the outside that looks exactly
	 * like a bot that cannot do anything.
	 *
	 * <p>They still alert and still go in the journal. What they stop doing is stopping.
	 * Low health, hunger, another player and the runtime limit are untouched: none of those
	 * are things the job causes, and all of them are worth stopping for.
	 */
	public boolean destroyerKeepWorking = true;
	public boolean destroyLogTargets = true;
	public boolean destroyStopWhenDone = true;
	public Reaction destroyDoneReaction = Reaction.ALERT;
	/** Rules rather than lists: see {@link BlockTargets.Family}. */
	public java.util.Map<String, Boolean> destroyFamilies = new java.util.HashMap<>();
	/** Extra block ids, on top of whatever the families select. */
	public List<String> destroyBlocks = new ArrayList<>();
	/** Block ids never to break, whatever else selects them. */
	public List<String> destroyExclude = new ArrayList<>();

	// ------------------------------------------------------------- pathing

	public boolean pathMine = true;
	/**
	 * Whether the route may only break blocks that are selected targets.
	 *
	 * <p>On, because "mine redstone and containers" is not a request to punch a hole through
	 * the obsidian wall in front of them. Off lets the bot tunnel through anything breakable
	 * to reach a target, which is the only way into a room with no door.
	 */
	public boolean pathMineOnlySelected = false;
	public boolean pathBridge = true;
	public boolean pathDiagonal = true;
	public int pathMaxFall = 3;
	/** What a broken block is worth in walked blocks. Higher means "go round if you can". */
	public int pathMineCost = 4;
	public int pathPlaceCost = 3;
	/** The search budget. Bigger finds longer routes and costs more per plan. */
	public int pathMaxNodes = 8000;
	/**
	 * How far over the shortest route the search may settle, in exchange for expanding far
	 * fewer nodes. 1 is exact and slow; 1.15 is a route up to a seventh longer, found in a
	 * fraction of the time.
	 */
	public double pathHeuristicWeight = 1.15;
	/** How much of a tick one slice of planning may take. A tick is fifty milliseconds. */
	public double pathSliceMs = 2.0;
	/**
	 * How much route to leave before the next leg starts being planned behind it, drawn
	 * fresh between these two every time. A fixed number here is a rhythm rather than a
	 * decision, and it is the one that shows: it is the moment the walk stops being smooth.
	 */
	public double pathRefreshSec = 3.0;
	public double pathRefreshMaxSec = 6.0;
	/**
	 * How long to wait after a plan comes to nothing. Never zero: retrying on the next tick
	 * asks the same question of the same world from the same place, twenty times a second.
	 */
	public double pathRestMinSec = 0.35;
	public double pathRestMaxSec = 0.9;
	/** Plans in a row that produce nothing walkable before a goal is called impossible. */
	public int pathAttempts = 3;
	/** How many moves ahead the camera looks, and how far forward a lost position is sought. */
	public int pathLookaheadMoves = 5;
	/** Further than this from every square of a route, and it is not that route being walked. */
	public double pathOffRouteBlocks = 4.0;
	/** How many moves ahead to revalidate, so a dead end is seen before it is walked into. */
	public int pathVerifyAhead = 3;
	/**
	 * Patience on top of what one step should physically take, drawn between these two. It is
	 * a give-up time, so it only ever adds — and a give-up time that is the same integer every
	 * time is one more constant on the wire.
	 */
	public double pathMoveSlackSec = 3.0;
	public double pathMoveSlackMaxSec = 6.0;
	/** Detect a stationary walking step without applying a walking deadline to mining. */
	public double pathStallSec = 1.25;
	public double pathFailedEdgeRetrySec = 8;

	// --------------------------------------------------- building and cover

	/** What may be placed. Empty means any block item that is not protected. */
	public List<String> buildingBlocks = new ArrayList<>(List.of(
			"cobblestone", "cobbled_deepslate", "dirt", "netherrack", "stone", "andesite",
			"diorite", "granite", "deepslate", "tuff", "end_stone"));
	public boolean bridgeSneak = true;
	/** Never place the last few, so there is always something left to get out of a hole. */
	public int bridgeKeepBlocks = 1;
	/**
	 * Cap lava that turns up right beside the feet.
	 *
	 * <p>Deliberately only the adjacent square. Lava that is in the <em>way</em> is the route's
	 * problem and the route bridges over it, priced and planned against the dry ground going the
	 * same direction. A standing scan of everything liquid nearby meant that in a base with a
	 * lava floor the job never ran once.
	 */
	public boolean coverLiquids = true;
	/** Which adjacent fluids the cover behavior handles. Lava defaults on; water is opt-in. */
	public boolean coverLava = true;
	public boolean coverWater = false;
	/** How long to spend trying to cap one square before leaving it alone. */
	public double coverGiveUpSec = 3;
	/** And how long to leave it alone afterwards, so a square that cannot be capped is not retried forever. */
	public double coverRestSec = 20;

	/**
	 * Watch the air bar and surface before it runs out.
	 *
	 * <p>Separate from {@code avoidWater}, which is about not getting in. This is about
	 * getting out, and it is the only one of the two that helps once you already are.
	 */
	public boolean watchAir = true;
	/** Surface with this many seconds of air left. A full bar is fifteen. */
	public double airSecondsLeft = 7;
	/** Dig up through a ceiling when swimming up is not enough. */
	public boolean airMineCeiling = true;

	public boolean collectDrops = true;
	public int collectRadius = 12;
	/**
	 * How long to spend on one drop before deferring it for a timed retry.
	 *
	 * <p>A pile behind a wall the route may not break looks exactly like a pile two steps away
	 * until you have spent the time proving otherwise.
	 */
	public double collectGiveUpSec = 60;
	public double collectRetrySec = 10;
	public double collectPickupWaitSec = 0.75;
	/** Finish the current swing, then collect a batch before returning to mining. */
	public double collectBatchSec = 4;
	public boolean collectAllowEdits = true;

	// ----------------------------------------------------------- the bag

	public boolean storageEnabled = false;
	public boolean storageFastTransfers = true;
	public boolean storageReturnShulkers = true;
	public double storagePlayerRadius = 32;
	public List<Storage.Target> storageTargets = new ArrayList<>();

	/** Slots that are never sold, dropped, or placed. Indices into the player inventory. */
	public List<Integer> protectedSlots = new ArrayList<>();
	/** Slots whose contents go into the sell menu. Protection always wins over this. */
	public List<Integer> sellSlots = new ArrayList<>();
	public double inventoryFullFraction = 0.9;
	public boolean stopWhenInventoryFull = true;
	/** What to do about a full bag with nothing left to sell and nothing left to throw away. */
	public Reaction inventoryFullReaction = Reaction.ALERT;
	public boolean restockHotbar = true;
	public boolean dropJunk = false;
	public List<String> junkItems = new ArrayList<>(List.of(
			"cobblestone", "dirt", "gravel", "andesite", "diorite", "granite", "tuff",
			"netherrack", "rotten_flesh", "cobbled_deepslate"));

	public boolean autoSellEnabled = false;
	/** Sell whenever there is anything to sell, rather than only when the bag is full. */
	public boolean autoSellAlways = false;
	public int autoSellMinStacks = 1;
	public double autoSellCooldownSec = 60;
	/** Without the slash — the client adds it. */
	public String sellCommand = "sell";
	/** Optional text the opened menu title must contain before the bot will click it. */
	public String sellMenuTitleContains = "";
	/** The item on the confirm button. The bottom-right match in the menu is the one clicked. */
	public String sellConfirmItem = "lime_stained_glass_pane";
	/** A counted-out slot index, used only when the item above matches nothing. -1 to disable. */
	public int sellConfirmSlot = -1;
	/** Refuse to press confirm if nothing was actually moved in. */
	public boolean sellRequiresDeposit = true;
	/** How many times to wait for a menu that never came before giving the sale up. */
	public int sellOpenAttempts = 3;
	public double sellDelayMinSec = 0.6;
	public double sellDelayMaxSec = 1.4;
	public double sellClickMinSec = 0.18;
	public double sellClickMaxSec = 0.45;

	// ------------------------------------------------------------- combat

	public boolean combatEnabled = true;
	public boolean combatFightMobs = true;
	public boolean combatFightPlayers = false;
	public boolean combatOnlyWhenAttacked = true;
	public double combatRadius = 8;
	public boolean combatChase = false;
	/** How long something stays a threat after it last landed a hit. */
	public double combatMemorySec = 8;
	public double combatMinCharge = 0.9;
	public double combatSwingMinSec = 0.55;
	public double combatSwingMaxSec = 0.85;
	public boolean combatUseShield = true;
	public boolean combatRetreat = true;
	public double combatRetreatHealth = 6;

	/** A reaction time in front of every decision the task layer makes. */
	public double taskReactionMinSec = 0.08;
	public double taskReactionMaxSec = 0.25;
	/** Probability that a newly chosen block gets the reaction pause above. */
	public double taskReactionChance = 0.2;
	/** Finite turn rounding for precision work. Navigation smoothing is the destroyer's minimum. */
	public double taskAimSmoothing = 0.35;
	/** How much view wobble to keep while aiming; reduced further when a small shape needs it. */
	public double taskAimWobbleScale = 0.35;
	/** Vary mining aim points without changing the saved amount of variation. */
	public boolean taskAimRandomisation = true;
	/** Fraction of a visible face used for a stable, randomly offset aim point. */
	public double taskAimPointSpread = 0.18;
	public double taskAimMaxTurnDeg = 24;

	public double miningAimSpread() {
		return taskAimRandomisation ? taskAimPointSpread : 0;
	}

	/** Apply just the work-speed controls; keep block selections and inventory permissions. */
	public void fastDestroyerTuning() {
		destroyTargetChoices = 3;
		destroyTargetDistanceSlack = 0.35;
		destroyTargetRandomness = 0.2;
		destroyPreferReachable = true;
		destroyStorageLast = false;
		taskReactionMinSec = 0.08;
		taskReactionMaxSec = 0.25;
		taskReactionChance = 0.2;
		taskAimSmoothing = 0.35;
		taskAimWobbleScale = 0.35;
		taskAimPointSpread = 0.18;
		taskAimMaxTurnDeg = 24;
		pathStallSec = 1.25;
		pathFailedEdgeRetrySec = 8;
		collectGiveUpSec = 60;
		collectRetrySec = 10;
		collectPickupWaitSec = 0.75;
		collectBatchSec = 4;
	}

	public void terrainDefaults() {
		baritoneNavigation = true;
		destroyLoadedChunks = true;
		destroyRequireLineOfSight = false;
		pathMineOnlySelected = false;
		collectAllowEdits = true;
		protectMiningDrops = true;
		collectGiveUpSec = Math.max(60, collectGiveUpSec);
		terrainSettingsVersion = 1;
	}

	// --------------------------------------------------------- safe stop

	public boolean safeStopEnabled = true;
	public Reaction safeStopReaction = Reaction.ALERT_AND_STOP;
	/** Compare actual speed against what the held keys predict. */
	public boolean safeStopOnSpeed = true;
	/** Trip below this fraction of the predicted speed. */
	public double safeStopMinSpeedRatio = 0.35;
	public double safeStopWindowSec = 2.0;
	public boolean safeStopOnTeleport = true;
	public double safeStopTeleportBlocks = 8;
	/**
	 * Treat a jump that lands somewhere you were standing a moment ago as the server putting
	 * you back rather than taking you somewhere. Servers with heavy movement checks do this
	 * constantly; a real teleport goes somewhere new, so the two are worth telling apart.
	 */
	public boolean safeStopIgnoreRubberBand = true;
	/** How close to a spot you recently stood counts as having been put back. */
	public double safeStopRubberBandBlocks = 3;
	public boolean safeStopOnVehicle = true;
	public boolean safeStopOnDimensionChange = true;
	public boolean safeStopOnFreeze = true;
	public double safeStopFreezeMs = 2000;
	public boolean safeStopOnRotationHijack = true;
	public double safeStopRotationToleranceDeg = 25;

	// ---------------------------------------------------------- auto eat

	public boolean autoEatEnabled = true;
	/** Eat once the bar is at or below this. 20 is full. */
	public int autoEatThreshold = 16;
	public boolean autoEatAvoidHarmful = true;
	public boolean autoEatSaveGoldenApples = true;
	public boolean autoEatRestoreSlot = true;
	/** Legacy setting retained for saved configs; eating now preserves the camera angle. */
	public double autoEatLookPitch = -50;
	public int autoEatMaxTicks = 80;
	/** Stand still while eating. */
	public boolean autoEatHoldStill = true;
	public Reaction outOfFoodReaction = Reaction.ALERT_AND_STOP;
	public boolean stopWhenOutOfFood = true;

	// --------------------------------------------------------- the journal

	public boolean journalEnabled = true;
	public int journalMaxEntries = 5000;

	/** Where the log files go. */
	public String logFolder = "C:\\Users\\damia\\Desktop\\AI_Projects\\Minecraft\\Movement_And_Randomisation\\Logs";
	public boolean logToFiles = true;
	public Journal.Rotation logRotation = Journal.Rotation.PER_DAY;
	/** The .json is always written — it is what the in-game viewer reads back. */
	public boolean logWriteCsv = true;
	public boolean logWriteText = true;
	/** Write on every entry rather than on stop and close. */
	public boolean logFlushImmediately = true;
	public boolean logIncludeBiome = true;
	public boolean logIncludeStatus = true;
	public boolean logIncludeState = true;

	/** Viewer state, remembered between openings. */
	public String logViewFile = "";
	/** Show only what was logged on the world or server you are on now. */
	public boolean logViewThisWorldOnly = true;
	/**
	 * Whether entries written before the world was recorded count as belonging here. They
	 * belong nowhere in particular, so by default they are left out rather than shown in
	 * every world at once, which would make the filter above meaningless.
	 */
	public boolean showUnknownWorldEntries = false;
	public String logViewSearch = "";
	/**
	 * Which dimensions the log viewer and the map show, keyed by dimension path. Shared by
	 * both for the same reason the kind filter is: two filters that disagree read as a bug.
	 */
	public java.util.Map<String, Boolean> dimensionFilter = new java.util.HashMap<>();
	public java.util.Map<String, Boolean> logViewKinds = new java.util.HashMap<>();
	public boolean logViewNewestFirst = true;
	public int logViewLimit = 300;
	/** Do not log the same kind of thing twice within this many blocks... */
	public double journalDedupeRadius = 64;
	/** ...and this many minutes. */
	public double journalDedupeMinutes = 30;
	/** Which kinds get recorded, keyed by Journal.Kind.name(). */
	public java.util.Map<String, Boolean> journalKinds = new java.util.HashMap<>();
	public boolean journalAlertOnLog = false;

	// ------------------------------------------------------------ the map

	public boolean mapThisWorldOnly = true;
	/** Blocks across the widest side of the map. */
	public double mapSpanBlocks = 1024;
	public boolean mapFollowPlayer = true;
	public boolean mapShowLabels = false;
	public boolean mapShowArea = true;
	public boolean mapShowGrid = true;
	/** Draw nether pins at 8x, so they line up with the overworld they sit under. */
	public boolean mapScaleNether = true;
	/** Hide anything older than this. 0 shows everything. */
	public double mapMaxAgeHours = 0;

	// ------------------------------------------------- landmarks and players

	public boolean scanSpawners = true;
	public boolean scanBeacons = true;
	public boolean scanEnchantingTables = true;

	/** Play the alert when another player is spotted, on top of the safety reaction. */
	public boolean playerAlertEnabled = true;
	/** Its own repeat count, so a player sighting can be louder than a stuck warning. */
	public int playerAlertRepeats = 5;
	public boolean playerLogCoords = true;

	// ------------------------------------------------------------------ hud

	public boolean hudEnabled = true;
	public HudCorner hudCorner = HudCorner.TOP_LEFT;
	public int hudOffsetX = 4;
	public int hudOffsetY = 4;
	public boolean hudShowNextEvent = true;
	public boolean hudShowContainers = true;
	public boolean hudShowGoto = true;
	public boolean hudShowJournal = true;
	public boolean hudShowFood = true;
	public double hudScale = 1.0;

	// ---------------------------------------------------------------- theme

	public int accentColor = 0xFF5B8CFF;
	public double guiBackdropOpacity = 0.72;
	public boolean guiBlurBackground = true;

	// ----------------------------------------------------------- behaviour

	/** Which saved profile these settings came from. Blank means the unnamed default. */
	public String activeProfile = "";

	/** Reseed the RNG from the OS entropy pool on every start, so runs never repeat. */
	public boolean reseedOnStart = true;
	/** Print every state change to chat. */
	public boolean verboseLogging = false;

	// ------------------------------------------------------ load and save

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static Path path() {
		return FabricLoader.getInstance().getConfigDir().resolve("movrand.json");
	}

	public static Config load() {
		Path p = path();
		if (Files.exists(p)) {
			try {
				Config c = GSON.fromJson(Files.readString(p), Config.class);
				if (c != null) {
					if (c.terrainSettingsVersion < 1) c.terrainDefaults();
					if (c.chatKeywords == null) c.chatKeywords = new ArrayList<>();
					c.clampAll();
					return c;
				}
			} catch (Exception e) {
				MovRand.LOG.warn("[movrand] could not read {}, using defaults", p, e);
			}
		}
		// a fresh install still has to go through the clamps: some fields are deliberately
		// null until they are derived, and nothing downstream expects to meet one
		Config c = new Config();
		c.clampAll();
		c.save();
		return c;
	}

	public void save() {
		try {
			Path p = path();
			Files.createDirectories(p.getParent());
			Files.writeString(p, GSON.toJson(this));
			String profile = sanitise(activeProfile);
			if (!profile.isEmpty()) {
				Path dir = profilesDir();
				if (dir == null) throw new java.io.IOException("Could not open profiles directory");
				Files.writeString(dir.resolve(profile + ".json"), GSON.toJson(this));
			}
		} catch (Exception e) {
			MovRand.LOG.warn("[movrand] could not write config", e);
		}
	}

	/** Keeps a hand-edited json from producing an inverted or negative range. */
	/**
	 * Kinds worth recording once per place instead of once per dedupe window. Both of these
	 * come out of the container scan, both are fixed features of the world, and both are
	 * re-found every few seconds for as long as you are standing near them.
	 */
	public boolean logOnce(Journal.Kind kind) {
		return containerLogOnce
				&& (kind == Journal.Kind.CONTAINER_CLUSTER || kind == Journal.Kind.LANDMARK);
	}


	// ------------------------------------------------- destroyer accessors

	/** Redstone is on out of the box because that is the job the section was built for. */
	public boolean destroyFamily(BlockTargets.Family family) {
		return destroyFamilies.getOrDefault(family.name(), family == BlockTargets.Family.REDSTONE);
	}

	public void setDestroyFamily(BlockTargets.Family family, boolean on) {
		destroyFamilies.put(family.name(), on);
	}

	/**
	 * Never sold, dropped or placed.
	 *
	 * <p>Placing counts because "protected" has to mean one thing. A slot the bot may bridge
	 * with is a slot that empties, and a stack of shulker boxes spent as scaffolding is
	 * exactly the mistake this setting exists to prevent.
	 */
	public boolean slotProtected(int slot) {
		return slot >= 0 && protectedSlots != null && protectedSlots.contains(slot);
	}

	/** Protection wins, so the two lists never have to be kept consistent by hand. */
	public boolean slotForSale(int slot) {
		return slot >= 0 && !slotProtected(slot) && sellSlots != null && sellSlots.contains(slot);
	}

	public void setSlotProtected(int slot, boolean on) {
		toggleSlot(protectedSlots, slot, on);
	}

	public void setSlotForSale(int slot, boolean on) {
		toggleSlot(sellSlots, slot, on);
	}

	private static void toggleSlot(List<Integer> list, int slot, boolean on) {
		if (list == null) return;
		if (on) {
			if (!list.contains(slot)) list.add(slot);
		} else {
			list.remove(Integer.valueOf(slot));
		}
	}

	/** Matches "minecraft:cobblestone" against a list that may hold either form. */
	public boolean isJunk(String fullId) {
		return matches(junkItems, fullId);
	}

	public boolean isBuildingBlock(String id) {
		return buildingBlocks == null || buildingBlocks.isEmpty() || matches(buildingBlocks, id);
	}

	private static boolean matches(List<String> list, String id) {
		if (list == null || id == null) return false;
		String path = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
		for (String entry : list) {
			if (entry == null) continue;
			String e = entry.trim().toLowerCase(java.util.Locale.ROOT);
			if (e.equals(id) || e.equals(path) || e.equals("minecraft:" + path)) return true;
		}
		return false;
	}

	/** Missing means shown: a dimension nobody has heard of should not be invisible. */
	public boolean dimensionShown(String path) {
		return dimensionFilter == null || dimensionFilter.getOrDefault(Journal.dimensionKey(path), true);
	}

	public void clampAll() {
		segmentMinSec = Math.max(0.1, segmentMinSec);
		segmentMaxSec = Math.max(segmentMinSec, segmentMaxSec);
		strafeMinSec = Math.max(0.05, strafeMinSec);
		strafeMaxSec = Math.max(strafeMinSec, strafeMaxSec);
		pauseMinSec = Math.max(0.05, pauseMinSec);
		pauseMaxSec = Math.max(pauseMinSec, pauseMaxSec);
		turnMinDeg = Math.max(0.0, turnMinDeg);
		turnMaxDeg = Math.max(turnMinDeg, turnMaxDeg);
		lookPitchMaxDeg = Math.max(lookPitchMinDeg, lookPitchMaxDeg);
		alertRepeatCount = Math.max(1, Math.min(50, alertRepeatCount));
		alertVolume = Math.max(0.0, Math.min(1.0, alertVolume));
		autoJumpMaxHeight = Math.max(1, Math.min(3, autoJumpMaxHeight));
		jumpSprintChance = Math.max(0.0, Math.min(1.0, jumpSprintChance));
		fleeRadius = Math.max(4.0, Math.min(64.0, fleeRadius));
		fleeSafeSec = Math.max(0.5, Math.min(60.0, fleeSafeSec));
		endermanLookRadius = Math.max(4.0, Math.min(64.0, endermanLookRadius));
		endermanLookDownDeg = Math.max(10.0, Math.min(90.0, endermanLookDownDeg));
		containerScanIntervalTicks = Math.max(5, containerScanIntervalTicks);
		containerThreshold = Math.max(1, containerThreshold);
		containerGroupChunks = Math.max(0, Math.min(32, containerGroupChunks));
		guiBackdropOpacity = Math.max(0.0, Math.min(1.0, guiBackdropOpacity));
		hudScale = Math.max(0.5, Math.min(2.0, hudScale));
		areaRouteLookahead = Math.max(1, Math.min(64, areaRouteLookahead));
		areaTargetJitter = Math.max(0, Math.min(7.5, areaTargetJitter));
		areaAroundRadius = Math.max(0, Math.min(512, areaAroundRadius));
		areaMapView = Math.max(4, Math.min(32_768, areaMapView));
		avoidLookahead = Math.max(1.0, Math.min(12.0, avoidLookahead));
		avoidMaxDeviationDeg = Math.max(15, Math.min(180, avoidMaxDeviationDeg));
		avoidTurnDegPerTick = Math.max(0.2, Math.min(30, avoidTurnDegPerTick));
		avoidReturnDegPerTick = Math.max(0.2, Math.min(30, avoidReturnDegPerTick));
		safeStopMinSpeedRatio = Math.max(0.02, Math.min(1.0, safeStopMinSpeedRatio));
		safeStopWindowSec = Math.max(0.5, safeStopWindowSec);
		safeStopRubberBandBlocks = Math.max(0.5, Math.min(32, safeStopRubberBandBlocks));
		destroyRadius = Math.max(4, Math.min(160, destroyRadius));
		dropSafetyDepth = Math.max(3, Math.min(64, dropSafetyDepth));
		prepareSiteSec = Math.max(5, Math.min(180, prepareSiteSec));
		baritoneTurnSmoothing = Math.max(0, Math.min(1, baritoneTurnSmoothing));
		baritoneTurnRate = Math.max(8, Math.min(90, baritoneTurnRate));
		baritoneAimVariation = Math.max(0, Math.min(0.2, baritoneAimVariation));
		if (!Double.isFinite(baritoneSprintChance)) baritoneSprintChance = 0.8;
		if (!Double.isFinite(baritonePaceMinSec)) baritonePaceMinSec = 2;
		if (!Double.isFinite(baritonePaceMaxSec)) baritonePaceMaxSec = 6;
		baritoneSprintChance = Math.max(0, Math.min(1, baritoneSprintChance));
		baritonePaceMinSec = Math.max(0.5, Math.min(30, baritonePaceMinSec));
		baritonePaceMaxSec = Math.max(baritonePaceMinSec, Math.min(30, baritonePaceMaxSec));
		baritoneNoProgressSec = Math.max(3, Math.min(60, baritoneNoProgressSec));
		destroyVerticalRadius = Math.max(2, Math.min(160, destroyVerticalRadius));
		destroyMaxTargets = Math.max(16, Math.min(20_000, destroyMaxTargets));
		destroyScanSec = Math.max(0.25, Math.min(60, destroyScanSec));
		destroyScanMaxSec = Math.max(destroyScanSec, Math.min(120, destroyScanMaxSec));
		destroyTargetChoices = Math.max(1, Math.min(32, destroyTargetChoices));
		destroyTargetDistanceSlack = Math.max(0, Math.min(2, destroyTargetDistanceSlack));
		destroyTargetRandomness = Math.max(0, Math.min(1, destroyTargetRandomness));
		destroyFieldOfViewDeg = Math.max(10, Math.min(360, destroyFieldOfViewDeg));
		destroyEmptyScansToFinish = Math.max(1, Math.min(10, destroyEmptyScansToFinish));
		// never below what obsidian honestly takes with an iron pick, or the ceiling meant to
		// catch a block that will never break starts catching blocks that simply are slow
		destroyBlockSec = Math.max(25, Math.min(600, destroyBlockSec));
		// and a target is always allowed at least as long as one of its own blocks
		destroyTargetSec = Math.max(destroyBlockSec * 2, Math.min(3600, destroyTargetSec));
		destroyRetrySec = Math.max(1, Math.min(3600, destroyRetrySec));
		destroyRetryMoveBlocks = Math.max(0, Math.min(64, destroyRetryMoveBlocks));
		pathMaxFall = Math.max(1, Math.min(24, pathMaxFall));
		pathMineCost = Math.max(1, Math.min(64, pathMineCost));
		pathPlaceCost = Math.max(1, Math.min(64, pathPlaceCost));
		pathMaxNodes = Math.max(500, Math.min(200_000, pathMaxNodes));
		// below 1 the heuristic overestimates nothing and the search is simply slower; above 3
		// it stops being A* and becomes a greedy walk at the goal, wall or no wall
		pathHeuristicWeight = Math.max(1.0, Math.min(3.0, pathHeuristicWeight));
		// a slice longer than a tick would drop the frame it was supposed to protect
		pathSliceMs = Math.max(0.1, Math.min(25, pathSliceMs));
		pathRefreshSec = Math.max(0.5, Math.min(60, pathRefreshSec));
		pathRefreshMaxSec = Math.max(pathRefreshSec, Math.min(120, pathRefreshMaxSec));
		pathRestMinSec = Math.max(0.1, Math.min(10, pathRestMinSec));
		pathRestMaxSec = Math.max(pathRestMinSec, Math.min(20, pathRestMaxSec));
		pathAttempts = Math.max(1, Math.min(20, pathAttempts));
		pathLookaheadMoves = Math.max(1, Math.min(32, pathLookaheadMoves));
		pathOffRouteBlocks = Math.max(1, Math.min(32, pathOffRouteBlocks));
		pathVerifyAhead = Math.max(1, Math.min(16, pathVerifyAhead));
		pathMoveSlackSec = Math.max(0.5, Math.min(30, pathMoveSlackSec));
		pathMoveSlackMaxSec = Math.max(pathMoveSlackSec, Math.min(60, pathMoveSlackMaxSec));
		pathStallSec = Math.max(0.4, Math.min(10, pathStallSec));
		pathFailedEdgeRetrySec = Math.max(1, Math.min(60, pathFailedEdgeRetrySec));
		bridgeKeepBlocks = Math.max(1, Math.min(64, bridgeKeepBlocks));
		coverGiveUpSec = Math.max(0.5, Math.min(30, coverGiveUpSec));
		// longer than the attempt it follows, or "leave it alone for a while" is not a rest
		coverRestSec = Math.max(coverGiveUpSec, Math.min(600, coverRestSec));
		collectRadius = Math.max(1, Math.min(48, collectRadius));
		collectGiveUpSec = Math.max(2, Math.min(300, collectGiveUpSec));
		collectRetrySec = Math.max(1, Math.min(120, collectRetrySec));
		collectPickupWaitSec = Math.max(0.2, Math.min(3, collectPickupWaitSec));
		collectBatchSec = Math.max(0.5, Math.min(30, collectBatchSec));
		// never zero: surfacing at zero air means surfacing while already taking damage
		airSecondsLeft = Math.max(1, Math.min(14, airSecondsLeft));
		inventoryFullFraction = Math.max(0.1, Math.min(1.0, inventoryFullFraction));
		autoSellMinStacks = Math.max(1, Math.min(36, autoSellMinStacks));
		autoSellCooldownSec = Math.max(5, Math.min(3600, autoSellCooldownSec));
		sellConfirmSlot = Math.max(-1, Math.min(200, sellConfirmSlot));
		sellOpenAttempts = Math.max(1, Math.min(20, sellOpenAttempts));
		// every delay has a floor: a zero here is a burst of packets no hand can produce
		sellDelayMinSec = Math.max(0.1, Math.min(10, sellDelayMinSec));
		sellDelayMaxSec = Math.max(sellDelayMinSec, Math.min(20, sellDelayMaxSec));
		sellClickMinSec = Math.max(0.05, Math.min(5, sellClickMinSec));
		sellClickMaxSec = Math.max(sellClickMinSec, Math.min(10, sellClickMaxSec));
		combatRadius = Math.max(2, Math.min(48, combatRadius));
		combatMemorySec = Math.max(1, Math.min(120, combatMemorySec));
		combatMinCharge = Math.max(0.1, Math.min(1.0, combatMinCharge));
		combatSwingMinSec = Math.max(0.05, Math.min(5, combatSwingMinSec));
		combatSwingMaxSec = Math.max(combatSwingMinSec, Math.min(10, combatSwingMaxSec));
		combatRetreatHealth = Math.max(0, Math.min(20, combatRetreatHealth));
		taskReactionMinSec = Math.max(0.05, Math.min(5, taskReactionMinSec));
		taskReactionMaxSec = Math.max(taskReactionMinSec, Math.min(10, taskReactionMaxSec));
		taskReactionChance = Math.max(0, Math.min(1, taskReactionChance));
		taskAimSmoothing = Math.max(0, Math.min(1, taskAimSmoothing));
		taskAimWobbleScale = Math.max(0, Math.min(1, taskAimWobbleScale));
		taskAimPointSpread = Math.max(0, Math.min(0.4, taskAimPointSpread));
		taskAimMaxTurnDeg = Math.max(2, Math.min(90, taskAimMaxTurnDeg));
		if (sellCommand == null || sellCommand.isBlank()) sellCommand = "sell";
		sellCommand = sellCommand.trim().replaceFirst("^/", "");
		if (sellMenuTitleContains == null) sellMenuTitleContains = "";
		sellMenuTitleContains = sellMenuTitleContains.trim();
		autoEatThreshold = Math.max(0, Math.min(19, autoEatThreshold));
		autoEatMaxTicks = Math.max(20, autoEatMaxTicks);
		playerAlertRepeats = Math.max(1, Math.min(50, playerAlertRepeats));
		journalMaxEntries = Math.max(10, Math.min(100_000, journalMaxEntries));
		containerChunkRadius = Math.max(0, Math.min(64, containerChunkRadius));
		containerYRange = Math.max(1, Math.min(512, containerYRange));
		containerMinY = Math.max(-2048, Math.min(2048, containerMinY));
		containerMaxY = Math.max(containerMinY, Math.min(2048, containerMaxY));
		// a config from before the mode existed says what it wanted with the old boolean
		if (containerHeightRange == null) {
			containerHeightRange = containerFullHeight ? HeightRange.FULL : HeightRange.RELATIVE;
		}
		containerFullHeight = containerHeightRange == HeightRange.FULL;
		logViewLimit = Math.max(20, Math.min(5000, logViewLimit));
		reactionDelayMinMs = Math.max(0, Math.min(5000, reactionDelayMinMs));
		reactionDelayMaxMs = Math.max(reactionDelayMinMs, Math.min(5000, reactionDelayMaxMs));
		mapSpanBlocks = Math.max(64, Math.min(32_768 * 16, mapSpanBlocks));
		mapMaxAgeHours = Math.max(0, Math.min(8760, mapMaxAgeHours));
		cameraSmoothYaw = Math.max(0, Math.min(0.95, cameraSmoothYaw));
		cameraSmoothPitch = Math.max(0, Math.min(0.95, cameraSmoothPitch));
		if (destroyFamilies == null) destroyFamilies = new java.util.HashMap<>();
		if (destroyBlocks == null) destroyBlocks = new ArrayList<>();
		if (destroyExclude == null) destroyExclude = new ArrayList<>();
		if (buildingBlocks == null) buildingBlocks = new ArrayList<>();
		if (junkItems == null) junkItems = new ArrayList<>();
		if (protectedSlots == null) protectedSlots = new ArrayList<>();
		if (sellSlots == null) sellSlots = new ArrayList<>();
		if (!Double.isFinite(storagePlayerRadius)) storagePlayerRadius = 32;
		storagePlayerRadius = Math.max(16, Math.min(128, storagePlayerRadius));
		if (storageTargets == null) storageTargets = new ArrayList<>();
		storageTargets.removeIf(java.util.Objects::isNull);
		storageTargets.forEach(Storage.Target::clamp);
		if (dimensionFilter == null) dimensionFilter = new java.util.HashMap<>();
		for (String d : Journal.DIMENSIONS) dimensionFilter.putIfAbsent(d, true);
		if (logViewKinds == null) logViewKinds = new java.util.HashMap<>();
		for (Journal.Kind k : Journal.Kind.values()) logViewKinds.putIfAbsent(k.name(), true);
		if (journalKinds == null) journalKinds = new java.util.HashMap<>();
		for (Journal.Kind k : Journal.Kind.values()) journalKinds.putIfAbsent(k.name(), true);
	}

	// ------------------------------------------------------------ profiles

	/**
	 * Named copies of every setting, one file each. Switching profile swaps the whole config,
	 * which is why the caller has to rebuild the screen afterwards.
	 */
	public static Path profilesDir() {
		try {
			Path dir = FabricLoader.getInstance().getConfigDir().resolve("movrand-profiles");
			Files.createDirectories(dir);
			return dir;
		} catch (Exception | LinkageError e) {
			return null;
		}
	}

	/** Strips anything a filename cannot hold, so a profile name is always safe to save. */
	public static String sanitise(String name) {
		String clean = name == null ? "" : name.trim().replaceAll("[^A-Za-z0-9 _-]", "");
        return clean.length() > 40 ? clean.substring(0, 40) : clean;
	}

	public static List<String> listProfiles() {
		List<String> out = new ArrayList<>();
		Path dir = profilesDir();
		if (dir == null) return out;
		try (java.util.stream.Stream<Path> files = Files.list(dir)) {
			files.filter(Files::isRegularFile)
					.map(f -> f.getFileName().toString())
					.filter(n -> n.endsWith(".json"))
					.map(n -> n.substring(0, n.length() - 5))
					.sorted(String.CASE_INSENSITIVE_ORDER)
					.forEach(out::add);
		} catch (Exception e) {
			MovRand.LOG.warn("[movrand] could not list profiles", e);
		}
		return out;
	}

	public static boolean profileExists(String name) {
		Path dir = profilesDir();
		return dir != null && Files.isRegularFile(dir.resolve(sanitise(name) + ".json"));
	}

	/** @return the name it was actually saved under, or null on failure. */
	public String saveAsProfile(String name) {
		String clean = sanitise(name);
		if (clean.isEmpty()) return null;
		Path dir = profilesDir();
		if (dir == null) return null;
		try {
			String previous = activeProfile;
			activeProfile = clean;
			Files.writeString(dir.resolve(clean + ".json"), GSON.toJson(this));
			if (!clean.equals(previous)) save(); // keep the live config pointing at the new profile
			return clean;
		} catch (Exception e) {
			MovRand.LOG.warn("[movrand] could not save profile {}", clean, e);
			activeProfile = "";
			return null;
		}
	}

	/** Reads a profile without making it the live one — for showing what it contains. */
	public static Config peekProfile(String name) {
		Path dir = profilesDir();
		if (dir == null) return null;
		try {
			Path f = dir.resolve(sanitise(name) + ".json");
			if (!Files.isRegularFile(f)) return null;
			Config c = GSON.fromJson(Files.readString(f), Config.class);
			if (c == null) return null;
			if (c.terrainSettingsVersion < 1) c.terrainDefaults();
			if (c.chatKeywords == null) c.chatKeywords = new ArrayList<>();
			c.activeProfile = sanitise(name);
			c.clampAll();
			return c;
		} catch (Exception e) {
			MovRand.LOG.warn("[movrand] could not read profile {}", name, e);
			return null;
		}
	}

	public static Config loadProfile(String name) {
		Config c = peekProfile(name);
		if (c != null) c.save();
		return c;
	}

	public static boolean deleteProfile(String name) {
		Path dir = profilesDir();
		if (dir == null) return false;
		try {
			return Files.deleteIfExists(dir.resolve(sanitise(name) + ".json"));
		} catch (Exception e) {
			MovRand.LOG.warn("[movrand] could not delete profile {}", name, e);
			return false;
		}
	}

	/** A deep copy, by way of the same json the profiles use. */
	public Config copy() {
		Config c = GSON.fromJson(GSON.toJson(this), Config.class);
		if (c.chatKeywords == null) c.chatKeywords = new ArrayList<>();
		c.clampAll();
		return c;
	}

	/** Sum of the weights of the events that are actually switched on. */
	public double totalEventWeight() {
		double t = 0;
		if (strafeEnabled) t += strafeWeight;
		if (pauseEnabled) t += pauseWeight;
		if (turnEnabled) t += turnWeight;
		if (hopEnabled) t += hopWeight;
		if (lookAroundEnabled) t += lookAroundWeight;
		return t;
	}
}
