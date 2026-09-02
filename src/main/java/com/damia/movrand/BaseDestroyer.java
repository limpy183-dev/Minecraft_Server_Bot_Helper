package com.damia.movrand;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The job: take a base apart, block by block, without needing anybody to watch it.
 *
 * <p>This is a priority list, not a plan. Everything a person would interrupt themselves for
 * interrupts it, in the order a person would: something is hitting me, then I cannot carry
 * any more, then I am standing next to lava, then there is a pile of my own drops on the
 * floor, and only then the actual work. Each of those is a state, each one runs to a natural
 * stopping point, and the work is picked back up afterwards rather than restarted.
 *
 * <p>Nothing here writes a rotation or a key. It fills in a {@link Bot.Steer} and hands it
 * back, and {@link MovementController} pushes that through the same wobble, easing and
 * filter that a wandering bot uses. That is the whole reason the humanisation applies to
 * this at all — there is one camera in the mod, and it does not know what job it is doing.
 */
public final class BaseDestroyer {

	public enum Phase {
		OFF("Off"), SCANNING("Looking for blocks"), WALKING("Walking"), MINING("Mining"),
		COLLECTING("Picking up drops"), COVERING("Covering liquid"), BRIDGING("Bridging"),
		CLEARING("Clearing the way"), FIGHTING("Fighting"), SELLING("Selling"),
		SURFACING("Coming up for air"),
		TIDYING("Sorting the bag"), WAITING("Waiting"), DONE("Nothing left in range");

		public final String label;

		Phase(String label) {
			this.label = label;
		}
	}

	private final Config cfg;
	private final Journal journal;
	private final BlockTargets targets = new BlockTargets();
	public final Combat combat;
	public final Backpack backpack;

	public Phase phase = Phase.OFF;
	public String detail = "";
	public int mined;
	public int placed;
	public int collected;
	public double lastPathCost;
	public int lastPathNodes;

	private BlockPos target;
	/** What the current route was planned to reach, so changing our mind replans rather
	 * than walking the old one to somewhere we have stopped wanting to go. */
	private BlockPos routeTo;
	/** Whether that route actually reaches the goal, so a good one is not thrown away. */
	private boolean routeComplete;
	private List<long[]> path = List.of();
	private int step;
	private int repathIn;
	private int stuckTicks;
	/** Consecutive stalls on the current target. One is bad luck; two is a route that is a lie. */
	private int stalls;
	private double lastX, lastZ;
	private int waitTicks;
	private long tick;
	/** Targets that could not be reached, so the search does not keep choosing them. */
	private final Set<Long> giveUp = new HashSet<>();
	private int scanCooldown;
	private List<BlockTargets.Found> found = List.of();
	/** The block actually being broken: the target, unless something is in front of it. */
	private BlockPos breaking;
	/** Ticks spent on that block. The only way to notice a block that will never break. */
	private int mineTicks;
	/** The face being aimed at, kept between ticks so the crosshair does not hop. */
	private Direction aimFace;
	/** The same, for putting a block down: where it is going and which face is being clicked. */
	private BlockPos placeTarget;
	private Direction placeFace;
	private int placeTicks;
	/** Ticks before lava is worth another look, after one that could not be capped. */
	private int capCooldown;
	/** Ticks left since the way ahead was refused for being dangerous. See {@link #blocked}. */
	private int hazardAhead;
	/** The drop being walked to, and how long that has been going on. */
	private int collectId = -1;
	private int collectTicks;
	/** Drops that could not be reached, so the walk does not keep choosing them. */
	private final Set<Integer> unreachableDrops = new HashSet<>();
	/** Set once on running out of work, so the reaction fires once rather than every tick. */
	private boolean finished;
	/** Whether we are on the way up. Latches, so it does not flicker at the threshold. */
	private boolean surfacing;

	public BaseDestroyer(Config cfg, Journal journal) {
		this.cfg = cfg;
		this.journal = journal;
		this.combat = new Combat(cfg);
		this.backpack = new Backpack(cfg);
	}

	public void reset(LocalPlayer player) {
		phase = Phase.SCANNING;
		detail = "";
		target = null;
		path = List.of();
		step = 0;
		repathIn = 0;
		stuckTicks = 0;
		stalls = 0;
		waitTicks = 0;
		scanCooldown = 0;
		giveUp.clear();
		unreachableDrops.clear();
		finished = false;
		surfacing = false;
		forgetBlock();
		collectId = -1;
		capCooldown = 0;
		hazardAhead = 0;
		if (player != null) {
			lastX = player.getX();
			lastZ = player.getZ();
		}
	}

	public void stop() {
		phase = Phase.OFF;
		path = List.of();
		target = null;
	}

	public BlockPos target() {
		return target;
	}

	public int remaining() {
		return found.size();
	}

	/** True once per run of running out of work, so the caller can react to it exactly once. */
	public boolean takeFinished() {
		boolean was = finished;
		finished = false;
		return was;
	}

	public String describe() {
		return detail.isEmpty() ? phase.label : phase.label + " — " + detail;
	}

	// ---------------------------------------------------------------- ticking

	/**
	 * @return the steer for this tick, or null when the destroyer has nothing to say and
	 * the ordinary wandering should take over
	 */
	public Bot.Steer tick(Minecraft mc, LocalPlayer player, ClientLevel level, float healthLost) {
		tick++;
		if (hazardAhead > 0) hazardAhead--;
		combat.onDamage(healthLost);
		Bot.Steer steer = new Bot.Steer();

		if (!cfg.destroyerEnabled) {
			if (phase != Phase.OFF) stop();
			return null;
		}
		if (phase == Phase.OFF) reset(player);

		// 0. Air. Everything else on this list is a thing that might go wrong; this one is a
		//    clock that is already running, and it only runs one way. A fight underwater with
		//    no air left is not a fight worth winning, so this comes above even that.
		if (breathe(mc, player, level, steer)) {
			phase = Phase.SURFACING;
			return steer;
		}

		// 1. Something is hitting us. Nothing else matters until it is not.
		if (combat.tick(mc, player, steer)) {
			phase = Phase.FIGHTING;
			detail = combat.status;
			path = List.of();       // whatever route we had is stale by the time this ends
			return steer;
		}

		// 2. A menu is open, which means a sale is in progress or the user opened something.
		if (backpack.tick(mc, player)) {
			phase = Phase.SELLING;
			detail = backpack.status;
			return steer;
		}

		// 3. A deliberate pause. Reaction time, not idleness — see waitABit.
		if (waitTicks > 0) {
			waitTicks--;
			phase = phase == Phase.OFF ? Phase.WAITING : phase;
			return steer;
		}

		// 4. The bag. Selling and tidying both stand still, so they come before walking.
		if (handleInventory(mc, player, level, steer)) return steer;

		// 5. Lava at our own feet, and only when it is the thing stopping us getting on with
		//    it. This used to be a standing scan of everything liquid nearby, ahead of the
		//    job, which in a base with a lava floor meant the job never ran once.
		if (cfg.coverLiquids && capLava(mc, player, level, steer)) return steer;

		// 6. Our own drops, before they despawn - but not in the middle of breaking
		//    something. A block already in arm's reach takes a second; a drop lasts five
		//    minutes, and walking off mid-swing throws away the progress on both.
		if (cfg.collectDrops && !holdingABlock(mc, player)) {
			Bot.Steer fetching = collectDrops(mc, player, level, steer);
			if (fetching != null) return fetching;
		}

		// 7. The job.
		return work(mc, player, level, steer);
	}

	/** Whether there is a target in arm's reach that is worth finishing before anything else. */
	private boolean holdingABlock(Minecraft mc, LocalPlayer player) {
		return target != null && Bot.inReach(mc, player, target);
	}

	// -------------------------------------------------------------- the job

	private Bot.Steer work(Minecraft mc, LocalPlayer player, ClientLevel level, Bot.Steer steer) {
		if (target != null && !stillATarget(level, target)) {
			// it went: either we broke it or somebody else did
			target = null;
			path = List.of();
			forgetBlock();
		}

		if (target == null) {
			if (scanCooldown > 0) scanCooldown--;
			else {
				found = targets.scan(level, player, cfg);
				scanCooldown = Math.max(1, (int) (cfg.destroyScanSec * 20));
			}
			target = pickTarget(level);
			if (target == null) {
				if (phase != Phase.DONE) finished = true;
				phase = Phase.DONE;
				detail = cfg.destroyBlocks.isEmpty() && noFamilies()
						? "no blocks selected" : "nothing selected within " + cfg.destroyRadius + " blocks";
				// Nothing here is not the same as nothing anywhere. Handing the tick back lets
				// the wandering and the area sweep carry us somewhere with blocks in it, and
				// the next scan picks the job straight back up - which beats standing on an
				// empty patch of floor waiting for a base to walk past.
				return null;
			}
			// back in business after a dry spell: the next dry spell is news again
			finished = false;
			stalls = 0;
			path = List.of();
			// A person does not start walking the instant a block appears on screen. They do
			// not stop to think between two blocks already under their nose either, and a
			// quarter second of standing still after every swing is most of what a bot
			// clearing a wall of redstone would spend its time doing.
			if (!Bot.inReach(mc, player, target)) waitABit();
			journalTarget(level);
		}

		double reach = player.blockInteractionRange() - 0.6;
		if (Bot.inReach(mc, player, target)) {
			Bot.Steer mining = mine(mc, player, level, steer);
			if (mining != null) return mining;
			// close enough to touch it and no line on it: walk to somewhere with one
			repathIn = 0;
		}

		// Replanning on a timer throws away a route that is working, and a full search is
		// several milliseconds of the tick it lands on - so a route that reaches the target
		// and still has some way to run is kept. What actually invalidates one is the world
		// changing, and the next-node checks in follow() catch that the moment it matters.
		boolean timerUp = repathIn-- <= 0;
		if (path.isEmpty() || !target.equals(routeTo)
				|| (timerUp && (!routeComplete || step >= path.size() - 3))) {
			planToTarget(mc, player, level, reach);
		} else if (timerUp) {
			repathIn = Math.max(10, (int) (cfg.pathRefreshSec * 20));
		}
		return follow(mc, player, level, steer);
	}

	private boolean noFamilies() {
		for (BlockTargets.Family f : BlockTargets.Family.values()) if (cfg.destroyFamily(f)) return false;
		return true;
	}

	private BlockPos pickTarget(ClientLevel level) {
		BlockPos found1 = firstWorthTaking(level);
		if (found1 != null) return found1;
		// everything within reach has been written off: let them back in and try again
		if (!giveUp.isEmpty() && !found.isEmpty()) {
			giveUp.clear();
			return firstWorthTaking(level);
		}
		return null;
	}

	/**
	 * The nearest thing on the list still worth walking to.
	 *
	 * <p>The staleness check is the point. The list is only rebuilt every scan interval, and
	 * the block at the top of it is very often the one just mined — so handing it back means
	 * picking it, discarding it as no longer a target, taking a reaction pause, and picking
	 * it again, standing perfectly still, until the next scan. A second and a half of that
	 * after every single block.
	 */
	private BlockPos firstWorthTaking(ClientLevel level) {
		for (BlockTargets.Found f : found) {
			if (giveUp.contains(f.pos().asLong())) continue;
			if (!stillATarget(level, f.pos())) continue;
			// a block holding lava back is a block that ends the run, not a target
			if (Avoidance.floodsWhenBroken(level, f.pos())) continue;
			return f.pos();
		}
		return null;
	}

	/**
	 * A one-line account of what the job thinks it is doing, for when what it is doing and
	 * what it says it is doing have stopped agreeing.
	 */
	public String diagnose(Minecraft mc, LocalPlayer player) {
		if (target == null) return "no target";
		double d = Math.sqrt(Bot.blockCentre(mc, target).distanceToSqr(player.getEyePosition()));
		BlockPos hit = Bot.hitBlock(mc);
		String on = hit == null ? "-" : hit.equals(target) ? "TGT"
				: hit.equals(breaking) ? "wall" : "%d,%d,%d".formatted(hit.getX(), hit.getY(), hit.getZ());
		return "%d,%d,%d d%.1f see%s hit%s p%d/%d".formatted(
				target.getX(), target.getY(), target.getZ(), d,
				Bot.visibleFace(mc, player, target, aimFace) != null ? "Y" : "N",
				on, step, Math.max(0, path.size() - 1));
	}

	private boolean stillATarget(ClientLevel level, BlockPos pos) {
		BlockState state = level.getBlockState(pos);
		return targets.blocks(cfg).contains(state.getBlock());
	}

	private void journalTarget(ClientLevel level) {
		if (!cfg.destroyLogTargets) return;
		journal.log(Journal.Kind.MINED, level, target, "Heading for " + describeBlock(level, target));
	}

	private static String describeBlock(ClientLevel level, BlockPos pos) {
		return level.getBlockState(pos).getBlock().getName().getString();
	}

	// -------------------------------------------------------------- mining

	private Bot.Steer mine(Minecraft mc, LocalPlayer player, ClientLevel level, Bot.Steer steer) {
		phase = Phase.MINING;

		// The target if it can be seen, and whatever is in front of it if it cannot. Digging
		// an obstruction out is both the way through and what a person would do, and it is the
		// difference between clearing a wall and spending six seconds proving there is one.
		//
		// One face, held. Re-picking it every tick makes the crosshair hop as the view
		// wobbles, and vanilla throws away every bit of mining progress the moment it lands on
		// a different block - which does not mine slowly, it mines never, and it looks from
		// the outside exactly like a bot standing still twitching its head.
		BlockPos want = target;
		Direction face = Bot.visibleFace(mc, player, target, target.equals(breaking) ? aimFace : null);
		if (face == null) {
			BlockPos wall = Bot.obstruction(mc, player, target);
			if (wall != null && cfg.pathMine && Bot.inReach(mc, player, wall)
					&& BlockTargets.breakable(level.getBlockState(wall), level, wall)
					&& !Avoidance.floodsWhenBroken(level, wall)) {
				want = wall;
				face = Bot.visibleFace(mc, player, wall, wall.equals(breaking) ? aimFace : null);
			}
		}
		// Standing on what you are breaking is a controlled fall over a floor and a death over
		// a shaft, and the search has already said how far a drop it is willing to take.
		if (want.equals(player.blockPosition().below())
				&& Bot.dropUnder(level, want, cfg.pathMaxFall + 2) > cfg.pathMaxFall) {
			detail = "not standing on that one";
			writeOff(want);
			return steer;
		}

		// Nothing to see and nothing in the way worth breaking. Standing here swinging at a
		// block we have no line on achieves nothing for six seconds and then writes it off, so
		// hand the tick back instead: the search's goal is "somewhere I can see it from", and
		// letting it run is how we get there.
		if (face == null && want.equals(target) && Bot.obstruction(mc, player, target) == null) {
			return null;
		}

		boolean onIt = swingAt(mc, player, level, want, face, steer);
		detail = describeBlock(level, want) + (onIt ? "" : " (lining up)");

		// A block that will not break is a block to walk away from, and the clock is the only
		// honest way to tell: a client is never told why a swing did nothing. Claimed land,
		// region protection and spawn protection all look identical to mining that never ends,
		// and without a ceiling the bot stands there doing it until somebody notices.
		// Both of these write off the target as well as the block in hand, and on purpose: a
		// wall that will not break is a target that cannot be reached from here, and leaving
		// the target standing means walking back to it and finding the same wall in six
		// seconds' time, forever. The whole list gets a second chance once it is exhausted.
		if (++mineTicks > mineCeilingTicks()) {
			detail = describeBlock(level, want) + " will not break";
			writeOff(want);
			return steer;
		}

		if (onIt) stuckTicks = 0;
		else if (++stuckTicks > cfg.destroyGiveUpSec * 20) {
			// the crosshair never landed on it: something is between us and it
			detail = "cannot see " + describeBlock(level, want);
			writeOff(want);
			stuckTicks = 0;
		}
		return steer;
	}

	/**
	 * Point at a block and hold the button down, keeping the face between ticks.
	 *
	 * <p>Shared with {@link #clearTheWay} rather than written out twice, because the reason
	 * for holding the face has nothing to do with which block it is. Vanilla throws away all
	 * mining progress the moment the crosshair lands somewhere else, so a face re-chosen every
	 * tick as the view wobbles digs through nothing at all — target or wall, same result.
	 *
	 * @param face the face already chosen, or null to choose one
	 * @return whether the crosshair is genuinely on it, which is when progress is being made
	 */
	private boolean swingAt(Minecraft mc, LocalPlayer player, ClientLevel level, BlockPos pos,
	                        Direction face, Bot.Steer steer) {
		if (!pos.equals(breaking)) {
			breaking = pos;
			mineTicks = 0;
			face = null;
		}
		aimFace = face != null ? face : Bot.visibleFace(mc, player, pos, aimFace);
		int tool = Bot.bestToolSlot(player, level.getBlockState(pos));
		if (tool != player.getInventory().getSelectedSlot()) {
			player.getInventory().setSelectedSlot(tool);
		}
		double[] look = Bot.aimAt(player,
				aimFace == null ? Bot.blockCentre(mc, pos) : Bot.facePoint(mc, pos, aimFace));
		steer.lookAt(look[0], look[1]);

		// Vanilla does the mining. Holding attack while the crosshair is on the block runs
		// the same progress, swing and packet loop a person's mouse does; reimplementing it
		// would be more code producing a stream that is easier to tell apart, not harder.
		steer.attack = Bot.lookingAt(mc, pos);
		return steer.attack;
	}

	/**
	 * Point at a face and hold use until a block goes into {@code where}.
	 *
	 * <p>Latched, exactly as breaking a block is latched, and for the same reason: the camera
	 * is filtered, so a support face re-chosen every tick means the crosshair is always on its
	 * way to somewhere it has already stopped wanting to be, and the check that decides
	 * whether to press the button never comes true. Two attempts, neither close.
	 *
	 * @return false when there is nowhere to place it from
	 */
	private boolean placeInto(Minecraft mc, LocalPlayer player, BlockPos where, Bot.Steer steer) {
		if (!where.equals(placeTarget)) {
			placeTarget = where;
			placeFace = null;
			placeTicks = 0;
		}
		placeTicks++;
		placeFace = Bot.placeAgainst(mc, player, where, placeFace);
		if (placeFace == null) return false;
		double[] look = Bot.aimAt(player, Bot.placePoint(mc, where, placeFace));
		steer.lookAt(look[0], look[1]);
		if (Bot.aboutToPlaceInto(mc, where)) {
			steer.use = true;
			placed++;
		}
		return true;
	}

	private void forgetPlacement() {
		placeTarget = null;
		placeFace = null;
		placeTicks = 0;
	}

	/**
	 * Put a block into lava the bot is standing right beside.
	 *
	 * <p>This is deliberately almost nothing, and it used to be almost everything. It was a
	 * standing scan of every liquid within a few blocks, ahead of the job, every tick — which
	 * in a base with a lava floor is every tick forever: the bot sets out to pave the Nether
	 * and never mines a thing. It also placed into the air <em>above</em> the lava, which caps
	 * nothing at all: the lava is still there, still flows the moment a wall comes down, and
	 * what actually gets built is a shelf in mid-air over it.
	 *
	 * <p>What is worth doing is the square the bot could fall into from where it is standing,
	 * and the block goes into the lava rather than over it. It is bounded, it gives up, and
	 * when it gives up it stays given up for a while, because the alternative is the loop it
	 * replaces.
	 */
	private boolean capLava(Minecraft mc, LocalPlayer player, ClientLevel level, Bot.Steer steer) {
		if (capCooldown > 0) capCooldown--;
		// Only when lava is the thing stopping us. Walking past a lava channel and stopping to
		// pave each square beside it is the same loop this replaced, one block further out;
		// what makes a placement worth making is that the route wants to go that way and
		// cannot, which is exactly what the refusal in the controller means.
		if (capCooldown > 0 || hazardAhead <= 0) return false;
		BlockPos lava = Bot.lavaBeside(mc, player);
		if (lava == null) return false;
		int slot = Bot.buildingSlot(player, cfg);
		if (slot < 0) return false;

		phase = Phase.COVERING;
		detail = "capping the lava underfoot";
		player.getInventory().setSelectedSlot(slot);
		steer.sneak = true;                        // at the edge of it, so do not walk in
		if (!placeInto(mc, player, lava, steer) || placeTicks > CAP_GIVE_UP) {
			// nothing to place it against, or long enough spent failing to
			capCooldown = CAP_REST;
			forgetPlacement();
			return false;
		}
		return true;
	}

	/** Ticks to spend on one square of lava, and how long to leave it alone after failing. */
	private static final int CAP_GIVE_UP = 60;
	private static final int CAP_REST = 400;

	/**
	 * How long one block may take before it is written off.
	 *
	 * <p>Generous on purpose. Obsidian with an iron pick is twenty-five seconds of honest
	 * work, and the thing this exists to catch - a server quietly putting the block back -
	 * never finishes at all. So being generous costs one wasted half minute, and being tight
	 * costs a target given up on for the crime of being slow.
	 */
	// ponytail: one fixed ceiling rather than a per-block estimate from hardness and tool
	// speed; work that one out if anything slower than obsidian ever needs mining.
	private int mineCeilingTicks() {
		return Math.max(700, (int) (cfg.destroyGiveUpSec * 20 * 6));
	}

	/** Give up on a block, and on whatever target it was standing between us and. */
	private void writeOff(BlockPos block) {
		giveUp.add(block.asLong());
		if (target != null) giveUp.add(target.asLong());
		target = null;
		forgetBlock();
	}

	/** Drop the per-block aiming state, so the next one starts by choosing a face again. */
	private void forgetBlock() {
		breaking = null;
		mineTicks = 0;
		aimFace = null;
		forgetPlacement();
	}

	/**
	 * Told from outside that the way ahead is not walkable, whatever the plan said.
	 *
	 * <p>A route is only as fresh as the world it was planned in, and this is a job that takes
	 * blocks out of that world for a living. The plan gets dropped rather than argued with.
	 */
	public void blocked(String why) {
		detail = why;
		path = List.of();
		repathIn = 0;
		hazardAhead = 40;
	}

	/** Called by the controller when a target block turns to air, so the count is real. */
	public void noteMined(ClientLevel level, BlockPos pos, String name) {
		mined++;
		if (cfg.destroyLogTargets) {
			journal.log(Journal.Kind.MINED, level, pos, "Mined " + name + " (" + mined + " so far)");
		}
	}

	// ------------------------------------------------------------- pathing

	private void planTo(Minecraft mc, LocalPlayer player, ClientLevel level,
	                    BlockPos goalPos, PathFinder.Goal goal) {
		BlockPos from = player.blockPosition();
		boolean canBridge = Bot.buildingSlot(player, cfg) >= 0;
		PathFinder.Rules rules = PathFinder.Rules.of(cfg, true, canBridge);
		PathFinder.World world = new PathFinder.Level(level, cfg);
		// Feet in the air is the normal case, not an edge case: plans get made walking off a
		// step and coming down from a jump. Rooted there the search expands nothing, hands
		// back an empty route, and a block we are standing next to gets written off.
		int fromY = PathFinder.groundY(world, from.getX(), from.getY(), from.getZ(), rules.maxFall());

		// The goal is somewhere the block can actually be broken from, not somewhere within
		// four blocks of it. Those are the same thing in an open field and nothing like it in
		// a building, which is where this job happens.
		BlockPos want = goalPos;
		PathFinder.Path result = PathFinder.find(world,
				from.getX(), fromY, from.getZ(),
				want.getX(), want.getY(), want.getZ(), goal, rules);
		routeTo = goalPos;

		path = result.steps();
		routeComplete = result.complete();
		step = 1;
		lastPathCost = result.cost();
		lastPathNodes = result.searched();
		repathIn = Math.max(10, (int) (cfg.pathRefreshSec * 20));

		if (!result.complete() && result.isEmpty()) {
			// nowhere to go and nothing gained by standing here thinking about it
			path = List.of();
			routeTo = null;
			if (goalPos.equals(target)) {
				giveUp.add(target.asLong());
				target = null;
			}
		}
	}

	/** The route to the block being mined: the goal is somewhere it can be swung at. */
	private void planToTarget(Minecraft mc, LocalPlayer player, ClientLevel level, double reach) {
		BlockPos want = target;
		double range = Math.max(1.5, reach);
		Vec3 aim = Bot.blockCentre(mc, want);
		planTo(mc, player, level, want,
				(x, y, z) -> Bot.canWorkFrom(level, player, x, y, z, want, aim, range));
	}

	private Bot.Steer follow(Minecraft mc, LocalPlayer player, ClientLevel level, Bot.Steer steer) {
		if (path.size() <= 1) {
			phase = Phase.WALKING;
			detail = "no route";
			repathIn = 0;
			return steer;
		}
		if (!trackRoute(player)) {
			// Not on this route any more: knocked off it, fallen off it, or it was planned
			// from somewhere we have since stopped being. Walking the rest of it from here
			// means walking a line between two points neither of which is where we are.
			phase = Phase.WALKING;
			detail = "off the route";
			path = List.of();
			repathIn = 0;
			return steer;
		}
		if (step >= path.size() - 1 && reached(player, node(path.size() - 1))) {
			phase = Phase.WALKING;
			detail = "arrived";
			repathIn = 0;
			return steer;
		}

		BlockPos ahead = node(step);
		double dx = ahead.getX() + 0.5 - player.getX();
		double dz = ahead.getZ() + 0.5 - player.getZ();

		// The route said "through here". If it is not open any more, open it.
		if (blocked(level, ahead)) {
			return clearTheWay(mc, player, level, ahead, steer);
		}
		// The route said "across here". If there is nothing to walk on, build it.
		if (needsFloor(level, ahead)) {
			return bridge(mc, player, level, ahead.below(), steer);
		}

		// The step after this one, so an edge is walked up to rather than off.
		BlockPos beyond = step + 1 < path.size() ? node(step + 1) : null;
		boolean edgeComing = beyond != null && needsFloor(level, beyond);

		// Steer at the furthest node we could walk straight to, not at the next one. The next
		// node is under a block away by construction, and the heading to something that close
		// swings hard as you close on it - so the camera, which is filtered on purpose and
		// takes half a second to come round, spends the whole route chasing a target that
		// never settles, and the movement keys strafe after it. Aiming further down the line
		// gives a heading that barely moves, which is what walking somewhere looks like.
		BlockPos aim = lookAhead(level, player);
		double ax = aim.getX() + 0.5 - player.getX();
		double az = aim.getZ() + 0.5 - player.getZ();
		double heading = Math.toDegrees(Math.atan2(-ax, az));
		double run = ax * ax + az * az;

		phase = Phase.WALKING;
		detail = "%d of %d steps".formatted(step, path.size() - 1);
		// Looking there and going there are two separate instructions. The camera is filtered
		// on purpose and takes the better part of half a second to come round; a route through
		// a building is a corner every couple of blocks, and a bot that only walks where it is
		// already pointed spends each of those corners walking into the wall.
		steer.lookAt(heading, lookAheadPitch(player, aim.getY()));
		steer.moveTowards(heading);
		// a clear straight run of a few blocks is exactly when a person breaks into one
		steer.sprint = cfg.destroySprint && player.onGround() && !edgeComing && run > 9
				&& Math.abs(aim.getY() - player.getY()) < 1.2;
		// at the step rather than three blocks short of it, or the hop is spent on nothing
		steer.jump = ahead.getY() > player.getY() + 0.4 && player.onGround()
				&& dx * dx + dz * dz < 2.25;
		// a gap in the floor with a route across it is walked up to slowly, and at the edge
		steer.sneak = cfg.bridgeSneak && edgeComing && player.onGround();

		trackProgress(player, level);
		return steer;
	}

	/** How far down the route to look for something to steer at. */
	private static final int LOOKAHEAD = 5;

	/**
	 * The furthest node on the route that can be reached from here in a straight line.
	 *
	 * <p>Furthest first, so the common case — open floor, nothing in the way — costs one
	 * check rather than five. Anything that needs building or breaking on the way ends the
	 * run: those are handled a node at a time, and steering past them walks into the hole.
	 */
	private BlockPos lookAhead(ClientLevel level, LocalPlayer player) {
		int last = Math.min(step + LOOKAHEAD, path.size() - 1);
		outer:
		for (int i = last; i > step; i--) {
			BlockPos n = node(i);
			if (Math.abs(n.getY() - player.getY()) > 1.2) continue;
			for (int j = step; j <= i; j++) {
				if (needsFloor(level, node(j)) || blocked(level, node(j))) continue outer;
			}
			if (walkableLine(level, player, n)) return n;
		}
		return node(step);
	}

	/**
	 * Whether the ground between here and there would carry a player walking it in a straight
	 * line. Both shoulders, because a player is 0.6 wide and a doorframe is not a suggestion.
	 */
	private static boolean walkableLine(ClientLevel level, LocalPlayer player, BlockPos to) {
		double x0 = player.getX(), z0 = player.getZ();
		double x1 = to.getX() + 0.5, z1 = to.getZ() + 0.5;
		double dx = x1 - x0, dz = z1 - z0;
		double span = Math.sqrt(dx * dx + dz * dz);
		if (span < 1.0E-4) return true;
		double px = -dz / span * 0.31, pz = dx / span * 0.31;
		int samples = (int) Math.ceil(span / 0.4);

		for (int i = 1; i <= samples; i++) {
			double t = i / (double) samples;
			for (int s = -1; s <= 1; s += 2) {
				double x = x0 + dx * t + px * s;
				double z = z0 + dz * t + pz * s;
				BlockPos feet = BlockPos.containing(x, player.getY() + 0.1, z);
				if (Avoidance.fillsSpace(level, feet) || Avoidance.fillsSpace(level, feet.above())) {
					return false;
				}
				if (!Avoidance.holdsWeight(level, feet) && !Avoidance.holdsWeight(level, feet.below())) {
					return false;                    // a hole, and stepping over it is not walking
				}
			}
		}
		return true;
	}

	private BlockPos node(int index) {
		long[] n = path.get(index);
		return new BlockPos((int) n[0], (int) n[1], (int) n[2]);
	}

	/**
	 * Standing on this node, near enough.
	 *
	 * <p>The height has to be tight, not generous. A node directly below is one block down and
	 * a generous test calls that "reached" — so the route to dig through a floor gets stepped
	 * over without a single swing, and the same for a pillar node overhead.
	 */
	private static boolean reached(LocalPlayer player, BlockPos n) {
		double dx = n.getX() + 0.5 - player.getX(), dz = n.getZ() + 0.5 - player.getZ();
		return dx * dx + dz * dz < 0.5 && Math.abs(player.getY() - n.getY()) < 0.7;
	}

	/**
	 * Where on the route we actually are.
	 *
	 * <p>Stepping forward one node at a time whenever its centre comes within half a block is
	 * the obvious version, and it works until the first thing that pushes the bot off the
	 * line — a mob in a doorway, a corner taken wide, a slab, a knockback. Then that node is
	 * never reached, the index never moves, and the bot walks back to a point it has already
	 * passed for as long as anyone lets it. So the position is found rather than counted:
	 * whichever node is nearest, never going backwards, and giving the route up entirely once
	 * even the nearest one is somewhere else.
	 *
	 * @return false when we are no longer on this route at all
	 */
	private boolean trackRoute(LocalPlayer player) {
		int best = step;
		double bestDist = Double.MAX_VALUE;
		for (int i = Math.max(1, step - 1); i < path.size(); i++) {
			BlockPos n = node(i);
			double dx = n.getX() + 0.5 - player.getX(), dz = n.getZ() + 0.5 - player.getZ();
			double dy = n.getY() - player.getY();
			double d = dx * dx + dz * dz + dy * dy;
			if (d < bestDist) {
				bestDist = d;
				best = i;
			}
		}
		if (bestDist > OFF_ROUTE * OFF_ROUTE) return false;
		step = Math.max(step, best);
		// and off the one being stood in, so the heading is towards somewhere else
		while (step + 1 < path.size() && reached(player, node(step))) step++;
		return true;
	}

	/** Further than this from every node on the route, and it is not the route being walked. */
	private static final double OFF_ROUTE = 4.0;

	/** Look slightly along the route rather than at your own feet, as a person does. */
	private static double lookAheadPitch(LocalPlayer player, double nodeY) {
		double dy = nodeY - player.getEyeY();
		return Math.max(-40, Math.min(40, Math.toDegrees(-Math.atan2(dy, 3))));
	}

	private void trackProgress(LocalPlayer player, ClientLevel mcLevel) {
		double moved = Math.hypot(player.getX() - lastX, player.getZ() - lastZ);
		if (moved > 0.08) {
			lastX = player.getX();
			lastZ = player.getZ();
			stuckTicks = 0;
			return;
		}
		if (++stuckTicks > cfg.destroyGiveUpSec * 20) {
			stuckTicks = 0;
			repathIn = 0;
			path = List.of();
			// One stall is a bad tick, a shut door, a mob in the corridor. The same target
			// stalling twice in a row is a route that does not exist - and replanning it from
			// the same spot produces the same route and the same stall, forever.
			// only when this route was the one going to the target: stalling on the way to a
			// dropped item says nothing about whether the block is reachable
			if (target != null && target.equals(routeTo) && ++stalls >= 2) {
				giveUp.add(target.asLong());
				detail = "cannot get to " + describeBlock(mcLevel, target);
				target = null;
				forgetBlock();
				stalls = 0;
			}
		}
	}

	/**
	 * Whether the route cannot simply be walked through here.
	 *
	 * <p>Half a block underfoot is floor, not wall. Read the other way, a room paved in slabs
	 * is a wall per block and the bot sets about mining the floor it is standing on instead
	 * of walking across it — which is most of a redstone build.
	 */
	private boolean blocked(ClientLevel level, BlockPos pos) {
		return Avoidance.fillsSpace(level, pos) || Avoidance.fillsSpace(level, pos.above());
	}

	/** Nothing here to stand on, and nothing under it either. */
	private boolean needsFloor(ClientLevel level, BlockPos pos) {
		if (Avoidance.holdsWeight(level, pos) || Avoidance.holdsWeight(level, pos.below())) return false;
		return !level.getBlockState(pos.below()).liquid();
	}

	/** Mine the one block in the way, rather than repathing round a doorway we can open. */
	private Bot.Steer clearTheWay(Minecraft mc, LocalPlayer player, ClientLevel level,
	                              BlockPos ahead, Bot.Steer steer) {
		BlockPos wall = Avoidance.fillsSpace(level, ahead) ? ahead : ahead.above();
		if (!Bot.inReach(mc, player, wall)) {
			phase = Phase.WALKING;
			detail = "closing on the wall";
			double dx = wall.getX() + 0.5 - player.getX(), dz = wall.getZ() + 0.5 - player.getZ();
			double heading = Math.toDegrees(Math.atan2(-dx, dz));
			steer.lookAt(heading, 0);
			steer.moveTowards(heading);
			return steer;
		}
		boolean underfoot = wall.equals(player.blockPosition().below());
		if (!cfg.pathMine || !BlockTargets.breakable(level.getBlockState(wall), level, wall)
				|| Avoidance.floodsWhenBroken(level, wall)
				|| (underfoot && Bot.dropUnder(level, wall, cfg.pathMaxFall + 2) > cfg.pathMaxFall)) {
			repathIn = 0;
			path = List.of();
			phase = Phase.WALKING;
			detail = Avoidance.floodsWhenBroken(level, wall) ? "lava behind that wall" : "blocked, replanning";
			return steer;
		}

		phase = Phase.CLEARING;
		detail = "digging through " + describeBlock(level, wall);
		swingAt(mc, player, level, wall, null, steer);
		// the same ceiling the target gets: a wall that will not break is a route that is not
		// a route, and standing in front of it swinging is the loudest way to find that out
		if (++mineTicks > mineCeilingTicks()) {
			detail = describeBlock(level, wall) + " will not break";
			writeOff(wall);
			path = List.of();
			repathIn = 0;
		}
		trackProgress(player, level);
		return steer;
	}

	private Bot.Steer bridge(Minecraft mc, LocalPlayer player, ClientLevel level,
	                         BlockPos where, Bot.Steer steer) {
		int slot = Bot.buildingSlot(player, cfg);
		if (slot < 0) {
			// out of scaffolding: this route is not available any more
			repathIn = 0;
			path = List.of();
			phase = Phase.WALKING;
			detail = "no blocks left to bridge with";
			return steer;
		}
		phase = Phase.BRIDGING;
		player.getInventory().setSelectedSlot(slot);

		// Putting a block into the space you are standing in is pillaring, and the only way to
		// do it is to stop standing there: jump, look down, and put it under you on the way
		// up. This is the whole of how the bot reaches an upper floor.
		if (where.equals(player.blockPosition())) {
			detail = "pillaring up";
			steer.sneak = false;                       // sneaking is what stops the jump
			steer.jump = player.onGround();
			steer.lookAt(player.getYRot(), 90);
			if (!player.onGround()) placeInto(mc, player, where, steer);
			if (placeTicks > PLACE_GIVE_UP) giveUpOnPlacing("cannot pillar from here");
			trackProgress(player, level);
			return steer;
		}

		detail = "placing a block";
		// Sneaking is not decoration here. Placing a block into the gap means looking down at
		// the edge of the one being stood on, and looking down at an edge is how you walk off
		// it. Standing still - no forward key - is the other half of the same point.
		steer.sneak = cfg.bridgeSneak;
		// Nothing orthogonally adjacent to hold the block up, or long enough spent failing to
		// use what is there. The search does not plan diagonal bridges for exactly this
		// reason, so either way the world has moved under the plan: look again rather than
		// standing over the hole holding a block out.
		if (!placeInto(mc, player, where, steer) || placeTicks > PLACE_GIVE_UP) {
			giveUpOnPlacing("nothing to place against");
		}
		trackProgress(player, level);
		return steer;
	}

	/** Ticks to spend trying to put one block down before the route is the thing at fault. */
	private static final int PLACE_GIVE_UP = 50;

	private void giveUpOnPlacing(String why) {
		detail = why;
		forgetPlacement();
		path = List.of();
		repathIn = 0;
	}

	// --------------------------------------------------------- the interrupts

	private boolean handleInventory(Minecraft mc, LocalPlayer player, ClientLevel level, Bot.Steer steer) {
		if (backpack.wantsToSell(mc, player, tick) && (backpack.full(player) || cfg.autoSellAlways)) {
			phase = Phase.SELLING;
			detail = "opening the sell menu";
			backpack.beginSell(mc, player, tick);
			journal.log(Journal.Kind.SOLD, level, player.blockPosition(),
					"Selling %d stacks with /%s".formatted(backpack.countForSale(player), cfg.sellCommand));
			return true;
		}
		if (!backpack.full(player)) return false;

		if (backpack.dropOneJunkStack(mc, player) || backpack.restockHotbar(mc, player)) {
			phase = Phase.TIDYING;
			detail = backpack.status;
			waitABit();
			return true;
		}
		if (cfg.stopWhenInventoryFull) {
			phase = Phase.WAITING;
			detail = "inventory full";
			return true;
		}
		return false;
	}

	/**
	 * Get to the surface before the bar runs out.
	 *
	 * <p>Swimming up is the whole of it in open water. Under a ceiling it is not, which is
	 * exactly the situation the job creates for itself: mine into an aquifer from below and
	 * the way out is through the stone you are standing under. So the ceiling gets broken
	 * too, with whatever is in hand — there is no time to be picky about the tool.
	 *
	 * @return true while surfacing, so nothing else gets a say
	 */
	private boolean breathe(Minecraft mc, LocalPlayer player, ClientLevel level, Bot.Steer steer) {
		if (!cfg.watchAir || player.canBreatheUnderwater()) {
			surfacing = false;
			return false;
		}
		int air = player.getAirSupply();
		int max = Math.max(1, player.getMaxAirSupply());
		surfacing = needsAir(player.isUnderWater(), air, max, cfg.airSecondsLeft, surfacing);
		if (!surfacing) return false;

		detail = "%.1fs of air".formatted(air / 20.0);
		steer.jump = true;      // swimming up is the jump key, in water as on land
		steer.sneak = false;    // and sneaking is the one thing that would sink us

		BlockPos head = BlockPos.containing(player.getX(), player.getEyeY(), player.getZ());
		BlockPos above = head.above();
		boolean ceiling = !level.getBlockState(above).getCollisionShape(level, above).isEmpty();
		if (ceiling && cfg.airMineCeiling && BlockTargets.breakable(level.getBlockState(above), level, above)) {
			detail += " · digging up";
			double[] look = Bot.aimAt(player, Bot.aimPoint(mc, player, above));
			steer.lookAt(look[0], look[1]);
			steer.attack = Bot.lookingAt(mc, above);
		} else {
			steer.lookAt(player.getYRot(), -70);   // look up, which is also where we are going
		}
		// a route planned from down here is a route back to the thing that drowned us
		path = List.of();
		repathIn = 0;
		return true;
	}

	/**
	 * Whether to be heading for the surface.
	 *
	 * <p>Latching matters more than the threshold does. Without it the bot surfaces to one
	 * tick above the line, goes back to work, drops under it again, and spends the whole bar
	 * oscillating an inch below the water — so once it starts climbing it keeps climbing
	 * until it is actually breathing again.
	 */
	static boolean needsAir(boolean submerged, int air, int maxAir, double secondsLeft, boolean already) {
		if (already) return submerged || air < maxAir;
		return submerged && air <= secondsLeft * 20;
	}

	/**
	 * Go and get whatever is on the floor.
	 *
	 * <p>Straight at it when the floor between here and there would actually carry us, and
	 * through the search when it would not — which is the whole difference between a bot that
	 * walks round the block in the way and one that stands against it until the item despawns.
	 * Walking at a thing is not the same as knowing how to reach it, and a drop three blocks
	 * away on the far side of a wall is exactly as unreachable as one across a canyon.
	 */
	private Bot.Steer collectDrops(Minecraft mc, LocalPlayer player, ClientLevel level, Bot.Steer steer) {
		if (backpack.full(player)) return null;
		ItemEntity best = null;
		double bestDist = (double) cfg.collectRadius * cfg.collectRadius;
		for (Entity e : level.entitiesForRendering()) {
			if (!(e instanceof ItemEntity item)) continue;
			if (unreachableDrops.contains(e.getId())) continue;
			double d = e.distanceToSqr(player);
			if (d < bestDist) {
				bestDist = d;
				best = item;
			}
		}
		if (best == null) {
			collectId = -1;
			return null;
		}
		// close enough that walking into it will do it: vanilla pickup is 1 block
		if (bestDist < 1.2) {
			collected++;
			collectId = -1;
			return null;
		}

		if (best.getId() != collectId) {
			collectId = best.getId();
			collectTicks = 0;
		}
		if (++collectTicks > Math.max(60, (int) (cfg.destroyGiveUpSec * 20)) * 2) {
			// a bounded memory: the ids are per-world and the list is only here to stop the
			// same unreachable pile being walked at forever
			if (unreachableDrops.size() > 256) unreachableDrops.clear();
			unreachableDrops.add(collectId);
			collectId = -1;
			return null;
		}

		phase = Phase.COLLECTING;
		detail = best.getItem().getHoverName().getString();
		BlockPos where = best.blockPosition();

		// Near enough to simply step onto, or the ground in between holds all the way: walk
		// at it. The close case is not an optimisation - vanilla picks up from a block away,
		// so anything this near is already at our feet, and asking the floor whether a
		// straight line works is how a drop sitting in the hole we just dug gets refused.
		if (bestDist < 2.25 || (bestDist < 36 && Math.abs(best.getY() - player.getY()) < 1.5
				&& walkableLine(level, player, where))) {
			double dx = best.getX() - player.getX(), dz = best.getZ() - player.getZ();
			double heading = Math.toDegrees(Math.atan2(-dx, dz));
			steer.lookAt(heading, lookAheadPitch(player, best.getY()));
			steer.moveTowards(heading);
			return steer;
		}

		// Otherwise it is a journey like any other, and the search knows how to make one.
		if (path.isEmpty() || repathIn-- <= 0 || !where.equals(routeTo)) {
			planTo(mc, player, level, where,
					PathFinder.within(0.9, where.getX(), where.getY(), where.getZ()));
		}
		if (path.size() <= 1) {
			// no way to it at all - do not stand here proving that for the next few seconds
			if (unreachableDrops.size() > 256) unreachableDrops.clear();
			unreachableDrops.add(collectId);
			collectId = -1;
			return null;
		}
		Bot.Steer walking = follow(mc, player, level, steer);
		phase = Phase.COLLECTING;
		return walking;
	}

	/** A reaction time, so a decision does not land on the same tick as the thing that caused it. */
	private void waitABit() {
		if (cfg.taskReactionMaxSec <= 0) return;
		waitTicks = Rng.ticks(cfg.taskReactionMinSec, cfg.taskReactionMaxSec);
	}

	// ----------------------------------------------------------- self-check

	/**
	 * Self-check on the bookkeeping — the job needs a world, the accounting does not:
	 * {@code ./gradlew selfCheck -Pcheck=com.damia.movrand.BaseDestroyer}
	 */
	public static void main(String[] args) {
		Config cfg = new Config();
		cfg.clampAll();

		// The give-up set is what stops the bot picking the same unreachable block forever,
		// and clearing it is what stops it giving up on a base it could finish later.
		Set<Long> giveUp = new HashSet<>();
		List<BlockPos> found = List.of(new BlockPos(1, 1, 1), new BlockPos(2, 1, 1));
		giveUp.add(found.getFirst().asLong());
		BlockPos next = null;
		for (BlockPos p : found) {
			if (giveUp.contains(p.asLong())) continue;
			next = p;
			break;
		}
		assert next != null && next.equals(found.get(1)) : "a written-off block was picked again";
		giveUp.add(found.get(1).asLong());
		next = null;
		for (BlockPos p : found) {
			if (giveUp.contains(p.asLong())) continue;
			next = p;
			break;
		}
		assert next == null : "everything is written off, so nothing should be chosen";

		// The reaction delay has to be a real delay: zero means the bot turns on the same
		// tick the block appears, which is the one thing no person does.
		cfg.taskReactionMinSec = 0;
		cfg.taskReactionMaxSec = 0;
		cfg.clampAll();
		assert cfg.taskReactionMaxSec > 0 : "the reaction delay was allowed to be zero";
		assert cfg.taskReactionMaxSec >= cfg.taskReactionMinSec : "the reaction range is inverted";

		// and the scan cannot be free, or it runs every tick over a quarter million blocks
		cfg.destroyScanSec = 0;
		cfg.clampAll();
		assert cfg.destroyScanSec >= 0.25 : "the scan interval was allowed to collapse";

		// Air. The threshold is the easy half; the latch is the half that matters, because
		// without it the bot bobs an inch under the surface until the bar empties.
		assert !needsAir(false, 300, 300, 7, false) : "dry land is not a reason to surface";
		assert !needsAir(true, 300, 300, 7, false) : "a full bar underwater is fine";
		assert !needsAir(true, 200, 300, 7, false) : "200 ticks is 10s, which is above the line";
		assert needsAir(true, 140, 300, 7, false) : "7s left is the line, and should trip it";
		assert needsAir(true, 0, 300, 7, false) : "an empty bar must certainly trip it";
		// once climbing, keep climbing
		assert needsAir(true, 299, 300, 7, true) : "still under water, so still climbing";
		assert needsAir(false, 299, 300, 7, true) : "head out but not refilled, so still climbing";
		assert !needsAir(false, 300, 300, 7, true) : "breathing again, so stop climbing";

		// a phase always has something to say, even before anything has happened
		BaseDestroyer d = new BaseDestroyer(cfg, null);
		assert d.phase == Phase.OFF && !d.describe().isEmpty() : "an idle destroyer described itself as nothing";

		// The mining ceiling is the thing that stops a protected block holding the bot still
		// forever, so it has to be finite - and long enough that honest slow work finishes.
		// Obsidian with an iron pickaxe is about 25s, which is the slowest thing worth mining.
		cfg.destroyGiveUpSec = 1;
		cfg.clampAll();
		int ceiling = d.mineCeilingTicks();
		assert ceiling >= 25 * 20 : "a block is written off before obsidian could break: " + ceiling;
		assert ceiling < 20 * 60 * 20 : "the ceiling is so high it is not a ceiling: " + ceiling;
		cfg.destroyGiveUpSec = 60;
		cfg.clampAll();
		assert d.mineCeilingTicks() > ceiling : "a longer patience did not buy a longer ceiling";

		System.out.println("BaseDestroyer self-check passed");
	}
}
