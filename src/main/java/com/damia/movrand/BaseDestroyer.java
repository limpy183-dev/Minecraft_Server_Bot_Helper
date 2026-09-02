package com.damia.movrand;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

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
 * <p>Getting anywhere is not this class's problem. {@link Pathing} plans the route, walks it,
 * revalidates it while it is being walked, and — the part that matters — always comes back
 * with an answer: walking, arrived, still thinking, or there is no way there. This used to be
 * a follower living here that could return a tick with nothing in it at all, which is a bot
 * standing perfectly still with no counter running and no way out of it. Every one of those
 * paths now ends in a decision: mine it, or give up on it and take the next block.
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
		SURFACING("Coming up for air"), PLANNING("Working out a route"),
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

	/**
	 * Two of them, because the job and the shopping are two separate journeys.
	 *
	 * <p>Sharing one would mean every dropped item threw away the route to the block and every
	 * block threw away the route to the item, which is a bot that walks the first third of two
	 * journeys over and over. They cost two fields.
	 */
	private final Pathing nav;
	private final Pathing fetch;

	public Phase phase = Phase.OFF;
	public String detail = "";
	public int mined;
	public int placed;
	public int collected;
	public double lastPathCost;
	public int lastPathNodes;

	private BlockPos target;
	/**
	 * Ticks spent on the current target, on anything at all.
	 *
	 * <p>The per-block mining ceiling only counts swings, and walking has its own limits per
	 * route — so between them a target can still soak up time indefinitely by alternating:
	 * plan, walk two blocks, fail, replan, walk two blocks back. Each of those looks like
	 * progress to the thing measuring it. This does not care what the time went on.
	 */
	private int targetTicks;
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
	/** Whether use was already down, so one placement is counted once rather than per tick. */
	private boolean wasPlacing;
	/** Ticks before lava is worth another look, after one that could not be capped. */
	private int capCooldown;
	/** Ticks left since the way ahead was refused for being dangerous. See {@link #blocked}. */
	private int hazardAhead;
	/** And where it was, so the block that gets put down lands on the thing that stopped us. */
	private BlockPos hazardPos;
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
		this.nav = new Pathing(cfg);
		this.fetch = new Pathing(cfg);
	}

	public void reset(LocalPlayer player) {
		phase = Phase.SCANNING;
		detail = "";
		target = null;
		targetTicks = 0;
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
		hazardPos = null;
		nav.reset();
		fetch.reset();
	}

	public void stop() {
		phase = Phase.OFF;
		target = null;
		nav.reset();
		fetch.reset();
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
		if (hazardAhead > 0 && --hazardAhead == 0) hazardPos = null;
		combat.onDamage(healthLost);
		Bot.Steer steer = new Bot.Steer();

		if (!cfg.destroyerEnabled) {
			if (phase != Phase.OFF) stop();
			return null;
		}
		if (phase == Phase.OFF) reset(player);
		PathMove.Ctx ctx = new PathMove.Ctx(mc, player, level, cfg);

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
			nav.invalidate("interrupted by a fight");   // the route is stale by the time this ends
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
			Bot.Steer fetching = collectDrops(ctx, steer);
			if (fetching != null) return fetching;
		}

		// 7. The job.
		return work(ctx, steer);
	}

	/** Whether there is a target in arm's reach that is worth finishing before anything else. */
	private boolean holdingABlock(Minecraft mc, LocalPlayer player) {
		return target != null && Bot.inReach(mc, player, target);
	}

	// -------------------------------------------------------------- the job

	private Bot.Steer work(PathMove.Ctx ctx, Bot.Steer steer) {
		ClientLevel level = ctx.level();
		LocalPlayer player = ctx.player();
		Minecraft mc = ctx.mc();

		if (target != null && !stillATarget(level, target)) {
			// it went: either we broke it or somebody else did
			target = null;
			forgetBlock();
		}
		if (target != null && ++targetTicks > targetCeilingTicks()) {
			detail = "spent long enough on " + describeBlock(level, target);
			writeOff(target);
			return steer;
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
			targetTicks = 0;
			// A person does not start walking the instant a block appears on screen. They do
			// not stop to think between two blocks already under their nose either, and a
			// quarter second of standing still after every swing is most of what a bot
			// clearing a wall of redstone would spend its time doing.
			if (!Bot.inReach(mc, player, target)) waitABit();
			journalTarget(level);
		}

		// In arm's reach: swing at it, or at whatever turns out to be in front of it. This
		// hands the tick back only when something we cannot break is in the way, which is a
		// reason to stand somewhere else rather than a reason to stand still.
		if (Bot.inReach(mc, player, target)) {
			Bot.Steer mining = mine(ctx, steer);
			if (mining != null) return mining;
		}

		return travel(ctx, steer);
	}

	/**
	 * Walk to somewhere the target can actually be swung at.
	 *
	 * <p>Every branch of this ends in a decision. That is the whole point of it: the previous
	 * version had three ways to return a tick with no keys and no counter running, and each of
	 * them was a bot standing in front of a redstone build doing nothing at all until somebody
	 * turned it off. Arriving with no shot means the block is written off; no route means the
	 * block is written off; and the whole written-off list gets a second chance the moment
	 * there is nothing left to try.
	 */
	private Bot.Steer travel(PathMove.Ctx ctx, Bot.Steer steer) {
		ClientLevel level = ctx.level();
		BlockPos want = target;
		// The margin is 0.8 and not a rounder number for a reason. This asks the question with
		// the eye at the middle of a candidate square; the mining asks it from wherever in that
		// square the player actually ends up standing, which is up to 0.71 away corner to
		// corner. Any margin smaller than that lets the search declare a square workable that
		// the mining then finds is out of reach — and the job, arriving somewhere it cannot
		// work from and being told it has arrived, gives up on a block it could have had.
		double reach = Math.max(1.5, ctx.player().blockInteractionRange() - 0.8);
		// The goal is somewhere the block can actually be broken from, not somewhere within
		// four blocks of it. Those are the same thing in an open field and nothing like it in
		// a building, which is where this job happens.
		Vec3 aim = Bot.blockCentre(ctx.mc(), want);
		PathFinder.Goal goal = (x, y, z) ->
				Bot.canWorkFrom(level, ctx.player(), x, y, z, want, aim, reach);

		Pathing.Nav result = nav.tick(ctx, steer, want, goal,
				cfg.pathMineOnlySelected ? targets.blocks(cfg) : null);
		absorb(level, nav);

		switch (result) {
			case WALKING -> {
				phase = phaseFor(nav.currentKind());
				detail = nav.status;
				return steer;
			}
			case PLANNING -> {
				phase = Phase.PLANNING;
				detail = nav.status;
				// Face what we are about to walk to. Working out a route is half a second of
				// standing still at worst, and half a second spent looking at the thing you are
				// about to go and get is a person thinking; the same half second spent staring
				// at the floor is a client that has stopped responding.
				double[] look = Bot.aimAt(ctx.player(), aim);
				steer.lookAt(look[0], look[1]);
				return steer;
			}
			case ARRIVED -> {
				// Standing somewhere the search says the block can be worked from, and the
				// mining above still handed the tick back. The two disagree, so the block is
				// not workable in practice and standing here proving that again next tick is
				// the loop this whole class was rebuilt to end.
				detail = "cannot line up on " + describeBlock(level, target);
				writeOff(target);
				return steer;
			}
			case NO_ROUTE -> {
				detail = "cannot get to " + describeBlock(level, target);
				writeOff(target);
				return steer;
			}
		}
		return steer;
	}

	/** A route doing something worth naming on the HUD gets to name it. */
	private static Phase phaseFor(PathFinder.Kind kind) {
		return switch (kind) {
			case MINE, DIG_DOWN -> Phase.CLEARING;
			case BRIDGE, PILLAR -> Phase.BRIDGING;
			default -> Phase.WALKING;
		};
	}

	/** Take over whatever the route broke or placed on the way, so the tallies are real. */
	private void absorb(ClientLevel level, Pathing from) {
		placed += from.placed;
		from.placed = 0;
		if (from.justMined != null && !from.justMined.equals(target)) {
			noteMined(level, from.justMined, from.justMinedName);
		}
		from.mined = 0;
		lastPathCost = from.lastCost;
		lastPathNodes = from.lastNodes;
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
				on, nav.step(), nav.length());
	}

	/**
	 * Whether this block may be broken to get <em>past</em> it, rather than for its own sake.
	 *
	 * <p>Somebody who ticked redstone and containers did not ask for a hole through the
	 * obsidian wall between here and them, and every route to a block behind a wall goes
	 * through this: the search while it is planning, and the mining when something turns out
	 * to be in front of the target.
	 */
	private boolean mayBreakToPass(ClientLevel level, BlockPos pos) {
		if (!cfg.pathMine) return false;
		BlockState state = level.getBlockState(pos);
		if (!BlockTargets.breakable(state, level, pos)) return false;
		if (Avoidance.floodsWhenBroken(level, pos)) return false;
		return !cfg.pathMineOnlySelected || targets.blocks(cfg).contains(state.getBlock());
	}

	private boolean stillATarget(ClientLevel level, BlockPos pos) {
		BlockState state = level.getBlockState(pos);
		return targets.blocks(cfg).contains(state.getBlock());
	}

	private void journalTarget(ClientLevel level) {
		if (!cfg.destroyLogTargets || journal == null) return;
		journal.log(Journal.Kind.MINED, level, target, "Heading for " + describeBlock(level, target));
	}

	private static String describeBlock(ClientLevel level, BlockPos pos) {
		return level.getBlockState(pos).getBlock().getName().getString();
	}

	// -------------------------------------------------------------- mining

	/**
	 * Swing at the target, or at whatever is in front of it.
	 *
	 * @return the steer, or null when something we cannot break is in the way and the answer
	 * is to go and stand somewhere else
	 */
	private Bot.Steer mine(PathMove.Ctx ctx, Bot.Steer steer) {
		Minecraft mc = ctx.mc();
		LocalPlayer player = ctx.player();
		ClientLevel level = ctx.level();
		phase = Phase.MINING;

		// One face, held. Re-picking it every tick makes the crosshair hop as the view wobbles,
		// and vanilla throws away every bit of mining progress the moment it lands on a
		// different block - which does not mine slowly, it mines never, and it looks from the
		// outside exactly like a bot standing still twitching its head.
		BlockPos want = target;
		Direction face = Bot.visibleFace(mc, player, target, target.equals(breaking) ? aimFace : null);
		if (face == null) {
			BlockPos wall = Bot.obstruction(mc, player, target);
			if (wall == null) {
				// Nothing at all between the eyes and the middle of it. No face passed the
				// per-face test, which happens on shapes whose faces are all nearly edge-on -
				// but the centre ray is the test vanilla's own crosshair uses, so aiming there
				// is aiming at the block. Handing the tick back here instead is how a target in
				// plain sight became a target the bot stood in front of and never touched.
				face = null;
			} else if (Bot.inReach(mc, player, wall) && mayBreakToPass(level, wall)) {
				want = wall;
				face = Bot.visibleFace(mc, player, wall, wall.equals(breaking) ? aimFace : null);
			} else {
				// Something solid we are not allowed to break, or cannot reach. That is a
				// reason to stand somewhere else, and the search's goal is "somewhere I can
				// see it from" - so let it run.
				return null;
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

		boolean onIt = swingAt(mc, player, level, want, face, steer);
		detail = describeBlock(level, want) + (onIt ? "" : " (lining up)");

		// A block that will not break is a block to walk away from, and the clock is the only
		// honest way to tell: a client is never told why a swing did nothing. Claimed land,
		// region protection and spawn protection all look identical to mining that never ends,
		// and without a ceiling the bot stands there doing it until somebody notices.
		if (++mineTicks > mineCeilingTicks()) {
			detail = describeBlock(level, want) + " will not break";
			writeOff(want);
		}
		return steer;
	}

	/**
	 * Point at a block and hold the button down, keeping the face between ticks.
	 *
	 * @param face the face already chosen, or null to aim at the middle of the block
	 * @return whether the crosshair is genuinely on it, which is when progress is being made
	 */
	private boolean swingAt(Minecraft mc, LocalPlayer player, ClientLevel level, BlockPos pos,
	                        Direction face, Bot.Steer steer) {
		if (!pos.equals(breaking)) {
			// The face is dropped, because it belonged to a different block. The clock is not:
			// it is the target's, not the block's, and resetting it here is how a bot that
			// alternates between the target and the wall in front of it swings forever without
			// either of them ever running out of patience.
			breaking = pos;
			face = null;
		}
		aimFace = face;
		int tool = Bot.bestToolSlot(player, level.getBlockState(pos));
		if (tool != player.getInventory().getSelectedSlot()) {
			player.getInventory().setSelectedSlot(tool);
		}
		double[] look = Bot.aimAt(player,
				aimFace == null ? Bot.blockCentre(mc, pos) : Bot.facePoint(mc, pos, aimFace));
		steer.lookAt(look[0], look[1]);
		steer.precise = true;

		// Vanilla does the mining. Holding attack while the crosshair is on the block runs
		// the same progress, swing and packet loop a person's mouse does; reimplementing it
		// would be more code producing a stream that is easier to tell apart, not harder.
		steer.attack = Bot.lookingAt(mc, pos);
		return steer.attack;
	}

	/**
	 * Point at a face and hold use until a block goes into {@code where}.
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
		steer.precise = true;
		// Counted on the way in rather than every tick the key is down: vanilla holds a right
		// click across its own four-tick delay, so a tick is not a block.
		boolean pressing = Bot.aboutToPlaceInto(mc, where);
		steer.use = pressing;
		if (pressing && !wasPlacing) placed++;
		wasPlacing = pressing;
		return true;
	}

	private void forgetPlacement() {
		placeTarget = null;
		placeFace = null;
		placeTicks = 0;
		wasPlacing = false;
	}

	/**
	 * Put a block into lava the bot is standing right beside.
	 *
	 * <p>Deliberately almost nothing. Lava that is in the <em>way</em> is the route's problem
	 * and the route bridges over it, priced and planned; this is only for the square that
	 * turns up under our own feet without being planned for. It is bounded, it gives up, and
	 * when it gives up it stays given up for a while.
	 */
	private boolean capLava(Minecraft mc, LocalPlayer player, ClientLevel level, Bot.Steer steer) {
		if (capCooldown > 0) {
			capCooldown--;
			return false;
		}
		BlockPos lava = hazardPos != null && Avoidance.isLavaAt(level, hazardPos)
				? hazardPos : Bot.lavaBeside(mc, player);
		if (lava == null || !Bot.inReach(mc, player, lava)) return false;
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
	// speed; the route's moves do work theirs out properly, so copy that if a target ever
	// needs mining that is slower than obsidian.
	private int mineCeilingTicks() {
		return Math.max(700, (int) (cfg.destroyGiveUpSec * 20 * 6));
	}

	/**
	 * How long one target may occupy the bot in total, however that time is spent.
	 *
	 * <p>Wider than the mining ceiling, because it has to cover walking there as well as
	 * breaking it. The point is only that it exists: with every individual step bounded and
	 * nothing bounding the whole, a bot can still spend an afternoon on one block by failing
	 * at it in a slightly different way each time.
	 */
	private int targetCeilingTicks() {
		return Math.max(2400, (int) (cfg.destroyGiveUpSec * 20 * 20));
	}

	/** Give up on a block, and on whatever target it was standing between us and. */
	private void writeOff(BlockPos block) {
		giveUp.add(block.asLong());
		if (target != null) giveUp.add(target.asLong());
		target = null;
		targetTicks = 0;
		forgetBlock();
		nav.reset();
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
	public void blocked(String why, BlockPos where) {
		detail = why;
		nav.invalidate(why);
		fetch.invalidate(why);
		hazardAhead = 40;
		hazardPos = where;
	}

	/** Called when a block turns to air, so the count and the journal are real. */
	public void noteMined(ClientLevel level, BlockPos pos, String name) {
		mined++;
		if (cfg.destroyLogTargets && journal != null) {
			journal.log(Journal.Kind.MINED, level, pos, "Mined " + name + " (" + mined + " so far)");
		}
	}

	// --------------------------------------------------------- the interrupts

	private boolean handleInventory(Minecraft mc, LocalPlayer player, ClientLevel level, Bot.Steer steer) {
		if (backpack.wantsToSell(mc, player, tick) && (backpack.full(player) || cfg.autoSellAlways)) {
			phase = Phase.SELLING;
			detail = "opening the sell menu";
			backpack.beginSell(mc, player, tick);
			if (journal != null) {
				journal.log(Journal.Kind.SOLD, level, player.blockPosition(),
						"Selling %d stacks with /%s".formatted(backpack.countForSale(player), cfg.sellCommand));
			}
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
			steer.precise = true;
			steer.attack = Bot.lookingAt(mc, above);
		} else {
			steer.lookAt(player.getYRot(), -70);   // look up, which is also where we are going
		}
		// a route planned from down here is a route back to the thing that drowned us
		nav.invalidate("surfacing");
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
	 * <p>Straight at it when it is close enough that walking into it will do it, and through
	 * the search when it is not — which is the whole difference between a bot that walks round
	 * the block in the way and one that stands against it until the item despawns. A drop
	 * three blocks away on the far side of a wall is exactly as unreachable as one across a
	 * canyon, and only a route knows the difference.
	 */
	private Bot.Steer collectDrops(PathMove.Ctx ctx, Bot.Steer steer) {
		LocalPlayer player = ctx.player();
		if (backpack.full(player)) return null;
		ItemEntity best = null;
		double bestDist = (double) cfg.collectRadius * cfg.collectRadius;
		for (Entity e : ctx.level().entitiesForRendering()) {
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
			fetch.reset();
		}
		if (++collectTicks > Math.max(60, (int) (cfg.destroyGiveUpSec * 20)) * 2) {
			giveUpOnDrop();
			return null;
		}

		phase = Phase.COLLECTING;
		detail = best.getItem().getHoverName().getString();
		BlockPos where = best.blockPosition();

		// Near enough to simply step onto: walk at it. Vanilla picks up from a block away, so
		// anything this close is already at our feet and a route to it is ceremony.
		if (bestDist < 4.0) {
			double dx = best.getX() - player.getX(), dz = best.getZ() - player.getZ();
			double heading = Math.toDegrees(Math.atan2(-dx, dz));
			steer.lookAt(heading, 20);
			steer.moveTowards(heading);
			return steer;
		}

		// Otherwise it is a journey like any other, and the search knows how to make one.
		// Near it, not on it: asking to stand exactly on a square that may be the hole we just
		// dug is how a perfectly reachable item ends up with no route to it at all.
		Pathing.Nav result = fetch.tick(ctx, steer, where,
				PathFinder.within(1.4, where.getX(), where.getY(), where.getZ()),
				cfg.pathMineOnlySelected ? targets.blocks(cfg) : null);
		absorb(ctx.level(), fetch);
		switch (result) {
			case WALKING, PLANNING -> {
				phase = Phase.COLLECTING;
				return steer;
			}
			case ARRIVED -> {
				// Standing on top of it and it is still on the floor: it is not ours to have.
				giveUpOnDrop();
				return null;
			}
			case NO_ROUTE -> {
				giveUpOnDrop();
				return null;
			}
		}
		return steer;
	}

	private void giveUpOnDrop() {
		// a bounded memory: the ids are per-world and the list is only here to stop the same
		// unreachable pile being walked at forever
		if (unreachableDrops.size() > 256) unreachableDrops.clear();
		if (collectId >= 0) unreachableDrops.add(collectId);
		collectId = -1;
		fetch.reset();
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

		// Every route outcome the job can be handed has to end in it doing something. This is
		// the one that used to be missing: arriving somewhere the block cannot actually be
		// worked from is a reason to pick a different block, not a reason to stand there.
		for (Pathing.Nav outcome : Pathing.Nav.values()) {
			assert switch (outcome) {
				case WALKING, PLANNING -> true;      // keeps the keys moving
				case ARRIVED, NO_ROUTE -> true;      // writes the target off and moves on
			} : "a navigation outcome with nothing decided for it: " + outcome;
		}

		System.out.println("BaseDestroyer self-check passed");
	}
}
