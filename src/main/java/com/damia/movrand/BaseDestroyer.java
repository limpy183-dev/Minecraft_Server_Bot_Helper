package com.damia.movrand;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The job: take a base apart, block by block, without needing anybody to watch it.
 *
 * <p>This is a priority list, not a plan. Everything a person would interrupt themselves for
 * interrupts it, in the order a person would, and each of those is a state that runs to a
 * natural stopping point with the work picked back up afterwards rather than restarted.
 *
 * <p>Getting anywhere is not this class's problem. {@link Pathing} plans the route, walks it,
 * revalidates it while it is being walked, and — the part that matters — always comes back
 * with an answer: walking, arrived, still thinking, or there is no way there. Every branch
 * below ends in a decision. A tick that hands back an empty steer with no counter running is
 * a bot standing perfectly still until somebody notices, and it is the specific failure this
 * class is shaped around not having.
 *
 * <p>Nothing here writes a rotation or a key. It fills in a {@link Bot.Steer} and hands it
 * back, and {@link MovementController} pushes that through the same wobble, easing and filter
 * a wandering bot uses. That is the whole reason the humanisation applies to this at all —
 * there is one camera in the mod, and it does not know what job it is doing.
 */
public final class BaseDestroyer {

	public enum Phase {
		OFF("Off"), SCANNING("Looking for blocks"), WALKING("Walking"), MINING("Mining"),
		COLLECTING("Picking up drops"), COVERING("Covering liquid"), PREPARING("Protecting drops"), BRIDGING("Bridging"),
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
	/** Resolved once per change of selection: the scan asks it a few hundred thousand times. */
	private final BlockTargets targets = new BlockTargets();
	private final BlockTargets supplies = new BlockTargets();
	private Config supplySelection;
	private Set<Block> supplySources = Set.of();
	private BlockPos pausedTarget;
	private int supplyTicks, supplyCooldown;

	public boolean gatheringSupplies() { return supplySelection != null; }
	private BlockTargets activeTargets() { return gatheringSupplies() ? supplies : targets; }
	private Config selection() { return gatheringSupplies() ? supplySelection : cfg; }
	public final Combat combat;
	public final Backpack backpack;
	/**
	 * Two routers, because the job and the shopping are two separate journeys.
	 *
	 * <p>Sharing one would mean every dropped item threw away the route to the block and every
	 * block threw away the route to the item, which is a bot walking the first third of two
	 * journeys over and over. They cost one field.
	 */
	private final Pathing nav;
	private final Pathing fetch;

	public Phase phase = Phase.OFF;
	public String detail = "";
	public int mined;
	public int placed;
	private record PendingEdit(ClientLevel level, BlockPos pos, Block block, boolean placement, String name, long expires) {}
	private final List<PendingEdit> pendingEdits = new ArrayList<>();

	/** Record actual vanilla interactions, including native path excavation and building. */
	public void expectEdit(ClientLevel level, BlockPos pos, Block block, boolean placement) {
		if (!cfg.movementEnabled || !cfg.destroyerEnabled) return;
		pendingEdits.removeIf(e -> e.level != level || e.expires < level.getGameTime()
				|| (e.pos.equals(pos) && e.placement == placement));
		if (pendingEdits.size() >= 128) pendingEdits.removeFirst();
		pendingEdits.add(new PendingEdit(level, pos.immutable(), block, placement,
				block.getName().getString(), level.getGameTime() + 200));
	}

	/** Server block updates/acknowledgements confirm edits; predictions alone never count. */
	public void confirmEdit(ClientLevel level, BlockPos pos, BlockState state) {
		pendingEdits.removeIf(e -> e.level != level || e.expires < level.getGameTime());
		for (var it = pendingEdits.iterator(); it.hasNext();) {
			PendingEdit e = it.next();
			if (!e.pos.equals(pos)) continue;
			boolean confirmed = e.placement ? state.is(e.block)
					: !state.is(e.block) && (state.isAir() || !state.getFluidState().isEmpty());
			it.remove();
			if (!confirmed) return; // a rejected prediction cannot claim a later unrelated edit
			if (e.placement) placed++;
			else noteMined(level, pos, e.name);
			return;
		}
	}
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
	private int arrivalTicks;
	private final NavProgress arrivalProgress = new NavProgress();
	private final Set<Long> rejectedWorkCells = new java.util.HashSet<>();
	private int waitTicks;
	/** Why a target is temporarily out: when to retry it and where that failure happened. */
	private record Retry(long afterTick, BlockPos from) {}
	/** Targets that could not be reached or broken, without turning one failure into a permanent ban. */
	private final Map<Long, Retry> retries = new HashMap<>();
	private int scanCooldown;
	private BlockPos scanFrom;
	private List<BlockTargets.Found> found = List.of();
	/** Full-pass facts, including matching blocks omitted from the bounded target shortlist. */
	private BlockTargets.ScanResult lastScan = BlockTargets.ScanResult.EMPTY;
	/** Consecutive exhaustive empty passes; one transient client-world frame is not completion. */
	private int emptyScans;
	/** The block actually being broken: the target, unless something is in front of it. */
	private BlockPos breaking;
	/** Ticks spent on that block. The only way to notice a block that will never break. */
	private int mineTicks;
	/** The face being aimed at, kept between ticks so the crosshair does not hop. */
	private Direction aimFace;
	/** Set once on running out of work, so the reaction fires once rather than every tick. */
	private boolean finished;
	/** Ticks the job has been running, for the sale cooldown. */
	private long tick;
	/** Whether we are on the way up. Latches, so it does not flicker at the threshold. */
	private boolean surfacing;
	/** Where a block is going and which face is being clicked to put it there. */
	private BlockPos placeTarget;
	private Direction placeFace;
	private int placeTicks;
	/** Whether the active placement square was fillable last tick, for confirmed counts. */
	private boolean placeWasFillable;
	/** Ticks before lava is worth another look, after one that could not be capped. */
	private int capCooldown;
	private final DropCollector drops;
	private final Bot.MiningAim miningAim = new Bot.MiningAim();
	private final SitePreparation preparation;
	private int aimMissTicks;
	/** The bag is full with nothing left to do about it, and whether that has been said yet. */
	private boolean bagFull;
	private boolean bagFullNews;

	public BaseDestroyer(Config cfg, Journal journal) {
		this.cfg = cfg;
		this.journal = journal;
		this.combat = new Combat(cfg);
		this.backpack = new Backpack(cfg);
		this.nav = new Pathing(cfg);
		this.drops = new DropCollector(cfg);
		this.fetch = drops.nav;
		this.preparation = new SitePreparation(cfg);
	}

	public void reset() {
		supplySelection = null;
		pausedTarget = null;
		supplyTicks = supplyCooldown = 0;
		supplies.resetScan();
		pendingEdits.clear();
		targets.resetScan();
		preparation.reset();
		MineSafety.clearDenied();
		phase = Phase.SCANNING;
		detail = "";
		target = null;
		targetTicks = 0;
		waitTicks = 0;
		scanCooldown = 0;
		scanFrom = null;
		lastScan = BlockTargets.ScanResult.EMPTY;
		emptyScans = 0;
		found = List.of();
		retries.clear();
		drops.reset();
		finished = false;
		surfacing = false;
		bagFull = false;
		bagFullNews = false;
		capCooldown = 0;
		forgetBlock();
		forgetPlacement();
		nav.reset();
		fetch.reset();
	}

	public void stop() {
		supplySelection = null;
		pausedTarget = null;
		preparation.reset();
		MineSafety.clearDenied();
		NativeNavigation.stopAll();
		phase = Phase.OFF;
		target = null;
		nav.reset();
		fetch.reset();
	}

	/** The block being worked towards, for anything outside that wants to watch it. */
	public BlockPos target() {
		return target;
	}

	public int remaining() {
		return lastScan.matching();
	}

	/** True once per run of running out of work, so the caller can react to it exactly once. */
	public boolean takeFinished() {
		boolean was = finished;
		finished = false;
		return was;
	}

	public String describe() {
		return (gatheringSupplies() ? "Gathering building blocks · " : "")
				+ (detail.isEmpty() ? phase.label : phase.label + " — " + detail);
	}

	/**
	 * The only blocks a route may break through, or null for anything breakable.
	 *
	 * <p>"Only break what I picked" is a restriction on the route rather than on the targets.
	 * Somebody who ticked redstone and containers did not ask for a hole through the obsidian
	 * wall on the way to them, and a search will happily take one if nothing says otherwise.
	 */
	public Set<Block> mayBreak() {
		if (!cfg.pathMineOnlySelected) return null;
		if (!gatheringSupplies()) return targets.blocks(cfg);
		Set<Block> allowed = new java.util.HashSet<>(targets.blocks(cfg));
		allowed.addAll(supplies.blocks(supplySelection));
		return allowed;
	}

	// ---------------------------------------------------------------- ticking

	/**
	 * One tick of the job, as a priority ladder.
	 *
	 * <p>The order is the order a person would interrupt themselves in, and each rung runs to a
	 * natural stopping point with the work picked back up afterwards rather than restarted — the
	 * target survives a fight, a sale and a swim, and so does the route under it wherever it
	 * still makes sense.
	 *
	 * @param healthLost how much health went missing since last tick, so the fight knows the
	 *                   difference between a threat and scenery
	 * @return the steer for this tick, or null when the destroyer has nothing to say and the
	 * ordinary wandering should take over
	 */
	public Bot.Steer tick(Minecraft mc, LocalPlayer player, ClientLevel level, float healthLost) {
		if (!cfg.destroyerEnabled) {
			if (phase != Phase.OFF) stop();
			return null;
		}
		if (phase == Phase.OFF) reset();
		tick++;
		if (scanCooldown > 0) scanCooldown--;
		if (capCooldown > 0) capCooldown--;
		if (supplyCooldown > 0) supplyCooldown--;
		combat.onDamage(player, healthLost);

		Bot.Steer steer = new Bot.Steer();
		PathMove.Ctx ctx = new PathMove.Ctx(mc, player, level, cfg);
		drops.observe(ctx);
		collected = drops.collected;
		observePlacement(mc);

		// 0. Air. Everything else on this list is a thing that might go wrong; this one is a
		//    clock that is already running, and it only runs one way. A fight underwater with no
		//    air left is not a fight worth winning, so this comes above even that.
		if (breathe(mc, player, level, steer)) {
			phase = Phase.SURFACING;
			return steer;
		}

		// 1. Something is hitting us. Nothing else matters until it is not — and the route is
		//    left exactly where it was, because a fight that ends where it started is a fight
		//    the walk can simply carry on from.
		if (combat.tick(mc, player, steer)) {
			phase = Phase.FIGHTING;
			detail = combat.status;
			return steer;
		}

		// 2. A menu is open, which means a sale is in progress.
		if (backpack.tick(mc, player)) {
			phase = Phase.SELLING;
			detail = backpack.status;
			return steer;
		}

		// 3. A deliberate pause. Reaction time rather than idleness — see waitABit — and the one
		//    rung here that hands back a steer with nothing in it, because it has a counter
		//    running and the counter is the whole point of it.
		if (waitTicks > 0) {
			waitTicks--;
			phase = Phase.WAITING;
			if (target != null) {
				double[] look = Bot.aimAt(player, Bot.aimPoint(mc, player, target));
				steer.lookAt(look[0], look[1]);
				steer.precise = true;
			}
			return steer;
		}

		// 4. The bag. Selling and tidying both stand still, so they come before walking.
		if (handleInventory(mc, player, level, steer)) return steer;
		updateSupplies(player);

		// Preparation and denied route mining used to return before the target deadline was
		// counted. Keep the deadline above every kind of work that can hold onto a target.
		if (target != null && ++targetTicks > targetCeilingTicks()) {
			detail = "spent long enough on " + describeBlock(level, target);
			writeOff(target, player);
		}

		// 5. Lava at our own feet, and only when it is the thing stopping us getting on with it.
		//    Lava that is in the *way* is the route's problem and the route bridges over it,
		//    priced and planned. This is only for the square that turns up underfoot.
		if (!NativeNavigation.controlling() && !preparation.active()
				&& cfg.coverLiquids && capLiquid(mc, player, level, steer)) return steer;

		// 6. Our own drops, before they despawn — but not in the middle of breaking something. A
		//    block already in arm's reach takes a second; a drop lasts five minutes, and walking
		//    off mid-swing throws away the progress on both.
		BlockPos denied = MineSafety.deniedBlock();
		if (denied != null) {
			SitePreparation.Result result = prepare(ctx, denied, steer);
			if (result == SitePreparation.Result.WORKING) return steer;
			MineSafety.clearDenied();
			if (result == SitePreparation.Result.FAILED) { writeOff(denied, player); steer.clear(); }
		}
		if ((cfg.collectDrops || gatheringSupplies()) && !preparation.active() && !holdingABlock(mc, player)) {
			Bot.Steer fetching = collectDrops(ctx, steer);
			if (fetching != null) return fetching;
		}

		// 7. The job.
		return work(ctx, steer);
	}

	/** Finish an active swing before collecting, but let drops interrupt between blocks. */
	private boolean holdingABlock(Minecraft mc, LocalPlayer player) {
		if (mc.level == null) return false;
		BlockPos active = breaking != null && !mc.level.getBlockState(breaking).isAir() ? breaking : nav.breakingBlock();
		return active != null && !mc.level.getBlockState(active).isAir() && Bot.inReach(mc, player, active);
	}

	// -------------------------------------------------------------- the job

	static boolean needsSupplies(Config cfg, int available) {
		return cfg.gatherBuildingBlocks && !cfg.gatherBlocks.isEmpty()
				&& available <= cfg.bridgeKeepBlocks;
	}

	private void updateSupplies(LocalPlayer player) {
		int available = Bot.buildingBlockCount(player, cfg, true);
		if (gatheringSupplies()) {
			if (available >= cfg.bridgeKeepBlocks + cfg.gatherBlockCount) {
				endSupplies();
				supplyCooldown = 0;
				return;
			}
			if (!cfg.gatherBuildingBlocks || !supplySelection.destroyBlocks.equals(cfg.gatherBlocks)
					|| ++supplyTicks > targetCeilingTicks() || backpack.full(player)) endSupplies();
			return;
		}
		if (supplyCooldown > 0 || backpack.full(player) || !needsSupplies(cfg, available)) return;
		supplySelection = cfg.copy();
		for (BlockTargets.Family family : BlockTargets.Family.values()) supplySelection.setDestroyFamily(family, false);
		supplySelection.destroyBlocks = new ArrayList<>(cfg.gatherBlocks);
		supplySources = new java.util.HashSet<>();
		for (Block block : supplies.blocks(supplySelection)) {
			if (BlockTargets.dropItems(block).stream().anyMatch(item ->
					Bot.usableBuildingStack(new net.minecraft.world.item.ItemStack(item), cfg))) supplySources.add(block);
		}
		pausedTarget = target;
		target = null;
		supplyTicks = 0;
		clearSupplyJourney();
	}

	private void endSupplies() {
		supplySelection = null;
		target = pausedTarget;
		pausedTarget = null;
		// A source can be absent, protected, or produce server-custom drops. Bound the detour.
		supplyCooldown = Math.max(20, (int) Math.round(cfg.destroyRetrySec * 20));
		clearSupplyJourney();
	}

	private void clearSupplyJourney() {
		supplies.resetScan();
		targets.resetScan();
		found = List.of();
		lastScan = BlockTargets.ScanResult.EMPTY;
		scanFrom = null;
		scanCooldown = targetTicks = emptyScans = 0;
		finished = false;
		preparation.reset();
		MineSafety.clearDenied();
		forgetBlock();
		forgetPlacement();
		nav.reset();
		drops.reset();
	}

	private Bot.Steer work(PathMove.Ctx ctx, Bot.Steer steer) {
		ClientLevel level = ctx.level();
		LocalPlayer player = ctx.player();
		Minecraft mc = ctx.mc();

		boolean finishedTarget = target != null && !stillATarget(level, target);
		if (finishedTarget) {
			// it went: either we broke it or somebody else did
			target = null;
			preparation.reset();
			MineSafety.clearDenied();
			nav.reset();
			// The cached list may now be exhausted. The next choice must be backed by a fresh
			// full pass, not an empty list left over from before this block disappeared.
			forgetBlock();
		}

		if (target == null) {
			if (scanFrom != null && player.blockPosition().distSqr(scanFrom) >= 4) scanCooldown = 0;
			// Advance even while cached candidates remain: otherwise a partial scan can
			// stay frozen for the entire demolition. Local targets are refreshed below.
			boolean scanned = scanCooldown == 0 || finishedTarget;
			if (scanned) {
				phase = Phase.SCANNING;
				lastScan = activeTargets().scanDetailed(level, player, selection(),
						pos -> eligibleForScan(level, player, pos));
				found = lastScan.found();
				scanFrom = player.blockPosition().immutable();
				scanCooldown = lastScan.complete() ? Rng.ticks(cfg.destroyScanSec, cfg.destroyScanMaxSec) : 0;
			}
			found = activeTargets().withNearby(level, player, selection(), found, pos -> eligibleForScan(level, player, pos));
			target = pickTarget(mc, level, player);
			if (target == null) {
				if (!scanned) {
					phase = Phase.SCANNING;
					detail = "waiting for the next block scan";
					return steer;
				}
				return handleEmptyShortlist();
			}
			// back in business after a dry spell: the next dry spell is news again
			finished = false;
			emptyScans = 0;
			targetTicks = 0;
			arrivalTicks = 0;
			arrivalProgress.reset();
			rejectedWorkCells.clear();
			// A person does not start walking the instant a block appears on screen. They do not
			// stop to think between two blocks already under their nose either, and a quarter
			// second of standing still after every swing is most of what a bot clearing a wall
			// of redstone would spend its time doing.
			if (Rng.chance(cfg.taskReactionChance)) waitABit();
			journalTarget(level);
			if (waitTicks > 0) {
				double[] look = Bot.aimAt(player, Bot.aimPoint(mc, player, target));
				steer.lookAt(look[0], look[1]);
				steer.precise = true;
				phase = Phase.WAITING;
				return steer;
			}
		}

		// In arm's reach: swing at it, or at whatever turns out to be in front of it. This hands
		// the tick back only when something we cannot break is in the way, which is a reason to
		// go and stand somewhere else rather than a reason to stand still.
		SitePreparation.Result prepared = prepare(ctx, target, steer);
		if (prepared == SitePreparation.Result.WORKING) return steer;
		if (prepared == SitePreparation.Result.FAILED) { writeOff(target, player); steer.clear(); return steer; }
		if (Bot.inReach(mc, player, target) && closeToDrops(player, target)) {
			Bot.Steer mining = mine(ctx, steer);
			if (mining != null) return mining;
		}

		return travel(ctx, steer);
	}

	private SitePreparation.Result prepare(PathMove.Ctx ctx, BlockPos block, Bot.Steer steer) {
		SitePreparation.Result result = preparation.tick(ctx, block, steer, mayBreak());
		if (result != SitePreparation.Result.READY) {
			phase = preparation.coveringLiquid ? Phase.COVERING : Phase.PREPARING;
			detail = preparation.detail;
		}
		return result;
	}

	private boolean closeToDrops(LocalPlayer player, BlockPos block) {
		return closeToDrops(cfg, player.getBoundingBox(), block);
	}

	static boolean closeToDrops(Config cfg, net.minecraft.world.phys.AABB player, BlockPos block) {
		return !cfg.mineWithinPickupRange || DropCollector.pickupOverlap(player,
				new net.minecraft.world.phys.AABB(block).deflate(0.25));
	}

	/**
	 * Interpret a freshly scanned but empty shortlist without confusing filters with absence.
	 *
	 * <p>A scan can have no selectable target because every match is behind the current view,
	 * waiting for retry, unsafe to uncover beside liquid, or outside an unloaded chunk. None of
	 * those means the configured blocks are gone, and none is allowed to fire the done reaction.
	 */
	private Bot.Steer handleEmptyShortlist() {
        finished = false;
		if (gatheringSupplies() && lastScan.complete()) {
			endSupplies();
			detail = "no reachable building supplies; retrying later";
			phase = Phase.SCANNING;
			return new Bot.Steer();
		}
        if (!lastScan.complete()) {
            phase = Phase.SCANNING;
            detail = "scanning loaded terrain: " + lastScan.scannedChunks() + " chunks";
            return new Bot.Steer();
        }
		if (cfg.destroyBlocks.isEmpty() && noFamilies()) {
			emptyScans = 0;
			phase = Phase.WAITING;
			detail = "no blocks selected";
			return new Bot.Steer();
		}

		if (lastScan.matching() > 0) {
			emptyScans = 0;
			phase = Phase.SCANNING;
			int deferred = lastScan.deferred();
			if (lastScan.hidden() > 0 && deferred > 0) {
				detail = "%d selected remain · %d outside view · %d deferred or liquid-unsafe"
						.formatted(lastScan.matching(), lastScan.hidden(), deferred);
			} else if (lastScan.hidden() > 0) {
				detail = "%d selected blocks remain outside the current view"
						.formatted(lastScan.matching());
			} else if (deferred > 0) {
				detail = "%d selected blocks remain, waiting for retry or liquid-safe access"
						.formatted(lastScan.matching());
			} else {
				// The world changed between section scan and candidate validation. Recheck on
				// the next tick instead of sitting through the ordinary interval.
				detail = "blocks changed during the scan — checking again";
				scanCooldown = 0;
			}
			return new Bot.Steer();
		}

		if (!provesEmpty(lastScan, cfg.destroyAllowIncompleteScanFinish)) {
			emptyScans = 0;
			phase = Phase.SCANNING;
			detail = "scan incomplete — %d nearby chunk%s not loaded"
					.formatted(lastScan.unloadedChunks(), lastScan.unloadedChunks() == 1 ? " is" : "s are");
			return new Bot.Steer();
		}

		emptyScans++;
		if (emptyScans < cfg.destroyEmptyScansToFinish) {
			phase = Phase.SCANNING;
			detail = "empty scan %d/%d — confirming before finishing"
					.formatted(emptyScans, cfg.destroyEmptyScansToFinish);
			return new Bot.Steer();
		}

		if (phase != Phase.DONE) finished = true;
		phase = Phase.DONE;
		detail = "confirmed no selected blocks within " + cfg.destroyRadius + " blocks";
		// Nothing here is not the same as nothing anywhere. Handing the tick back lets
		// the wandering and the area sweep carry us somewhere with blocks in it if the
		// configured done action does not stop the run.
		return new Bot.Steer();
	}

	/** A bounded shortlist is proof of nothing; only a complete full pass can prove empty. */
	static boolean provesEmpty(BlockTargets.ScanResult scan, boolean allowIncomplete) {
		return scan.matching() == 0 && (allowIncomplete || scan.unloadedChunks() == 0);
	}

	/**
	 * Walk to somewhere the target can actually be swung at.
	 *
	 * <p>Every branch of this ends in a decision, and that is the whole point of it. Arriving
	 * with no shot writes the block off; no route writes the block off; and the whole
	 * written-off list gets a second chance only after its retry timer or a changed vantage.
	 */
	private Bot.Steer travel(PathMove.Ctx ctx, Bot.Steer steer) {
		ClientLevel level = ctx.level();
		BlockPos want = target;
		double reach = Math.max(MIN_REACH, ctx.player().blockInteractionRange() - REACH_MARGIN);
		// The goal is somewhere the block can actually be broken from, not somewhere within four
		// blocks of it. Those are the same thing in an open field and nothing like it in a
		// building, which is where this job happens.
		Vec3 aim = Bot.blockCentre(ctx.mc(), want);
		PathFinder.Goal goal = (x, y, z) ->
				!rejectedWorkCells.contains(BlockPos.asLong(x, y, z))
				&& Bot.canWorkFrom(level, ctx.player(), x, y, z, want, aim, reach)
				&& closeToDrops(cfg,
						new net.minecraft.world.phys.AABB(x + 0.2, y, z + 0.2, x + 0.8, y + 1.8, z + 0.8),
						want);

		Pathing.Nav result = nav.tick(ctx, steer, want, goal, mayBreak());
		absorb(nav);

		switch (result) {
			case WALKING -> {
				phase = phaseFor(nav.currentKind());
				detail = nav.status;
				return steer;
			}
			case PLANNING -> {
				phase = Phase.PLANNING;
				detail = nav.status;
				// Face the target while the bounded route search runs.
				double[] look = Bot.aimAt(ctx.player(), aim);
				steer.lookAt(look[0], look[1]);
				return steer;
			}
			case ARRIVED -> {
				phase = Phase.WALKING;
				detail = "settling into reach of " + describeBlock(level, target);
				// The grid can accept an edge of a cell before the player's body reaches it.
				// Give the last step a few ticks to settle before rejecting that working cell.
				if (++arrivalTicks <= 4) {
					double[] look = Bot.aimAt(ctx.player(), aim);
					steer.lookAt(look[0], look[1]);
					return steer;
				}
				BlockPos feet = ctx.player().blockPosition();
				Vec3 centre = new Vec3(feet.getX() + 0.5, ctx.player().getY(), feet.getZ() + 0.5);
				if (centre.distanceToSqr(ctx.player().position()) > 0.015 && DropCollector.directWalk(ctx, centre)
						&& !arrivalProgress.update(centre.x, centre.z, centre.distanceTo(ctx.player().position()), cfg.pathStallSec)) {
					DropCollector.aimAndWalk(ctx, steer, centre);
					return steer;
				}
				// Another side of the block may be reachable even when this cell's centre
				// is behind a hole. Remember the rejected cell across the next route search.
				if (rejectedWorkCells.size() < 6 && rejectedWorkCells.add(feet.asLong())) {
					arrivalTicks = 0;
					arrivalProgress.reset();
					nav.invalidate("trying another working position");
					return steer;
				}
				detail = "cannot line up on " + describeBlock(level, target);
				writeOff(target, ctx.player());
				return steer;
			}
			case NO_ROUTE -> {
				detail = "cannot get to " + describeBlock(level, target) + ": " + nav.status;
				writeOff(target, ctx.player());
				return steer;
			}
		}
		return steer;
	}

	/**
	 * How much to shrink the reach by when asking whether a square could be worked from.
	 *
	 * <p>Not a rounder number for a reason. The search asks the question with the eye at the
	 * middle of a candidate square; the mining asks it from wherever in that square the player
	 * actually ends up standing, which is up to 0.71 away corner to corner. Any margin smaller
	 * than that lets the search call a square workable that the mining then finds is out of
	 * reach — and the job, arriving somewhere it cannot work from and being told it has
	 * arrived, gives up on a block it could have had.
	 */
	private static final double REACH_MARGIN = 0.8;
	/** Servers can configure reach down. Below this there is no job here to do. */
	private static final double MIN_REACH = 1.5;

	/** A route doing something worth naming on the HUD gets to name it. */
	private static Phase phaseFor(PathFinder.Kind kind) {
		return switch (kind) {
			case MINE, DIG_DOWN -> Phase.CLEARING;
			case BRIDGE, PILLAR -> Phase.BRIDGING;
			default -> Phase.WALKING;
		};
	}

	/** Read route statistics; edit totals come from server confirmations for both executors. */
	private void absorb(Pathing from) {
		from.placed = 0;
		from.mined = 0;
		from.justMined = null;
		lastPathCost = from.lastCost;
		lastPathNodes = from.lastNodes;
	}

	private boolean noFamilies() {
		for (BlockTargets.Family f : BlockTargets.Family.values()) if (cfg.destroyFamily(f)) return false;
		return true;
	}

	// -------------------------------------------------------- choosing one

	private BlockPos pickTarget(Minecraft mc, ClientLevel level, LocalPlayer player) {
		return chooseFrom(mc, level, player);
	}

	/**
	 * Eligibility used while building the bounded nearest-target heap.
	 *
	 * <p>Filtering here rather than after the heap is full prevents sixteen nearby protected
	 * blocks from hiding the seventeenth, workable block. The scan still counts rejected matches
	 * separately, so they cannot make the job announce completion.
	 */
	private boolean eligibleForScan(ClientLevel level, LocalPlayer player, BlockPos pos) {
		if (gatheringSupplies()) {
			BlockState state = level.getBlockState(pos);
			if (!supplySources.contains(state.getBlock())) return false;
			if (state.requiresCorrectToolForDrops() && !player.getInventory()
					.getItem(Bot.bestToolSlot(player, state)).isCorrectToolForDrops(state)) return false;
			// Empty supplies cannot build a floor to protect the blocks being gathered.
			if (cfg.protectMiningDrops && !MineSafety.inspect(new PathMove.Ctx(
					Minecraft.getInstance(), player, level, cfg), pos).safe()) return false;
		}
		Retry retry = retries.get(pos.asLong());
		if (retry != null
				&& !retryReady(retry, tick, player.blockPosition(), cfg.destroyRetryMoveBlocks)) return false;
		return cfg.protectMiningDrops || !Avoidance.floodsWhenBroken(level, pos, cfg.coverWater, true);
	}

	/** Re-rank from the current eye position, with randomisation confined to near ties. */
	private record Candidate(BlockPos pos, double distance, boolean reachable, boolean storage) {}

	private BlockPos chooseFrom(Minecraft mc, ClientLevel level, LocalPlayer player) {
		List<Candidate> candidates = new ArrayList<>();
		for (BlockTargets.Found f : found) {
			if (!stillATarget(level, f.pos()) || !eligibleForScan(level, player, f.pos())) continue;
			if (!cfg.destroyLoadedChunks && Math.abs(f.pos().getY() - player.getY()) > cfg.destroyVerticalRadius) continue;
			double dx = f.pos().getX() + 0.5 - player.getX(), dz = f.pos().getZ() + 0.5 - player.getZ();
			if (!cfg.destroyLoadedChunks && dx * dx + dz * dz > (double) cfg.destroyRadius * cfg.destroyRadius) continue;
			if (cfg.destroyRequireLineOfSight && !BlockTargets.visibleToPlayer(level, player, f.pos(),
					level.getBlockState(f.pos()), cfg.destroyFieldOfViewDeg)) continue;
			double distance = Math.sqrt(Bot.blockCentre(mc, f.pos()).distanceToSqr(player.getEyePosition()));
			boolean reachable = distance <= player.blockInteractionRange() && closeToDrops(player, f.pos())
					&& (Bot.visibleFace(mc, player, f.pos(), null) != null
					|| Bot.rotationHits(mc, player, f.pos(), Bot.aimAt(player, Bot.blockCentre(mc, f.pos()))[0],
							Bot.aimAt(player, Bot.blockCentre(mc, f.pos()))[1]))
					&& (!cfg.protectMiningDrops || MineSafety.inspect(new PathMove.Ctx(mc, player, level, cfg), f.pos()).safe());
			candidates.add(new Candidate(f.pos(), distance, reachable, f.storage()));
		}
		return chooseCandidate(candidates, cfg);
	}

	static BlockPos chooseCandidate(List<Candidate> candidates, Config cfg) {
		if (candidates.isEmpty()) return null;
		candidates.sort(java.util.Comparator
				.comparingInt((Candidate c) -> cfg.destroyStorageLast && c.storage ? 1 : 0)
				.thenComparingInt(c -> cfg.destroyPreferReachable && !c.reachable ? 1 : 0)
				.thenComparingDouble(Candidate::distance)
				.thenComparingLong(c -> c.pos.asLong()));
		Candidate first = candidates.getFirst();
		int choices = 1;
		while (choices < Math.min(cfg.destroyTargetChoices, candidates.size())) {
			Candidate next = candidates.get(choices);
			if ((cfg.destroyStorageLast && next.storage != first.storage)
					|| (cfg.destroyPreferReachable && next.reachable != first.reachable)
					|| next.distance > first.distance + cfg.destroyTargetDistanceSlack) break;
			choices++;
		}
		return candidates.get(choices > 1 && Rng.chance(cfg.destroyTargetRandomness) ? Rng.nextInt(choices) : 0).pos;
	}

	static boolean retryReady(Retry retry, long now, BlockPos current, double moveBlocks) {
		return now >= retry.afterTick() || moveBlocks <= 0
				|| current.distSqr(retry.from()) >= moveBlocks * moveBlocks;
	}

	private boolean stillATarget(ClientLevel level, BlockPos pos) {
		return level.hasChunkAt(pos) && !Storage.protectedWorldBlock(cfg, level, pos)
				&& activeTargets().blocks(selection()).contains(level.getBlockState(pos).getBlock())
				&& BlockTargets.breakable(level.getBlockState(pos), level, pos);
	}

	/**
	 * Whether this block may be broken to get <em>past</em> it, rather than for its own sake.
	 *
	 * <p>Somebody who ticked redstone and containers did not ask for a hole through the
	 * obsidian wall between here and them, and every route to a block behind a wall goes
	 * through this: the search while it is planning, and the mining when something turns out to
	 * be in front of the target.
	 */
	private boolean mayBreakToPass(ClientLevel level, BlockPos pos) {
		if (!cfg.pathMine) return false;
		BlockState state = level.getBlockState(pos);
		if (!BlockTargets.breakable(state, level, pos)) return false;
		if (Avoidance.floodsWhenBroken(level, pos, cfg.coverWater, true)) return false;
		return !cfg.pathMineOnlySelected || mayBreak().contains(state.getBlock());
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
	 * @return the steer, or null when something we cannot break is in the way and the answer is
	 * to go and stand somewhere else
	 */
	private Bot.Steer mine(PathMove.Ctx ctx, Bot.Steer steer) {
		Minecraft mc = ctx.mc();
		LocalPlayer player = ctx.player();
		ClientLevel level = ctx.level();
		phase = Phase.MINING;

		// One face, held. Re-picking it every tick makes the crosshair hop as the view wobbles,
		// and vanilla throws away every bit of mining progress the moment it lands on a
		// different block — which does not mine slowly, it mines never, and from the outside it
		// looks exactly like a bot standing still twitching its head.
		BlockPos want = target;
		Direction face = Bot.visibleFace(mc, player, target, target.equals(breaking) ? aimFace : null);
		if (face == null) {
			BlockPos wall = Bot.obstruction(mc, player, target);
			if (wall == null) {
				// Nothing at all between the eyes and the middle of it. No face passed the
				// per-face test, which happens on shapes whose faces are all nearly edge-on — but
				// the centre ray is the test vanilla's own crosshair uses, so aiming there is
				// aiming at the block. Handing the tick back here instead is how a target in
				// plain sight became one the bot stood in front of and never touched.
				face = null;
			} else if (Bot.inReach(mc, player, wall) && mayBreakToPass(level, wall)) {
				want = wall;
				face = Bot.visibleFace(mc, player, wall, wall.equals(breaking) ? aimFace : null);
			} else {
				// Something solid we are not allowed to break, or cannot reach. That is a reason
				// to stand somewhere else, and the search's goal is "somewhere I can see it
				// from" — so let it run.
				return null;
			}
		}

		// Standing on what you are breaking is a controlled fall over a floor and a death over a
		// shaft, and the search has already said how far a drop it is willing to take.
		if (want.equals(player.blockPosition().below())
				&& Bot.dropUnder(level, want, cfg.pathMaxFall + PAST_THE_LIMIT) > cfg.pathMaxFall) {
			detail = "not standing on that one";
			writeOff(want, player);
			return steer;
		}

		boolean onIt = swingAt(mc, player, level, want, face, steer);
		detail = describeBlock(level, want) + (onIt ? "" : " (lining up)");

		// A block that will not break is a block to walk away from, and the clock is the only
		// honest way to tell: a client is never told why a swing did nothing. Claimed land,
		// region protection and spawn protection all look identical to mining that never ends,
		// and without a ceiling the bot stands there doing it until somebody notices.
		aimMissTicks = onIt ? 0 : aimMissTicks + 1;
		if (aimMissTicks > aimDeadlineTicks(cfg)) {
			detail = "cannot keep a clear aim on " + describeBlock(level, want);
			writeOff(want, player);
			steer.clear();
			return steer;
		}
		if (onIt && ++mineTicks > mineCeilingTicks()) {
			detail = describeBlock(level, want) + " will not break";
			writeOff(want, player);
		}
		return steer;
	}

	/** Looked at one block past the limit, so "at the limit" and "past it" are different answers. */
	private static final int PAST_THE_LIMIT = 2;

	static int aimDeadlineTicks(Config cfg) {
		// The finite filter adds two ticks, independently of smoothing strength.
		return Math.max(60, (int) Math.ceil(180 / cfg.taskAimMaxTurnDeg) + 20);
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
			aimMissTicks = 0;
		}
		aimFace = face;
		int tool = Bot.bestToolSlot(player, level.getBlockState(pos));
		if (tool != player.getInventory().getSelectedSlot()) {
			player.getInventory().setSelectedSlot(tool);
		}
		double[] look = Bot.aimAt(player,
				miningAim.point(mc, player, pos, aimFace, cfg));
		steer.lookAt(look[0], look[1]);
		// The tighter camera and the re-cast crosshair both hang off this. Nothing snaps: the
		// rotation still goes out through the same filter and the same wobble, and the crosshair
		// is re-read from where that filter actually put it.
		steer.precise = true;

		// Vanilla does the mining. Holding attack while the crosshair is on the block runs the
		// same progress, swing and packet loop a person's mouse does; reimplementing it would be
		// more code producing a stream that is easier to tell apart, not harder.
		steer.attackAt(pos);
		steer.attack = Bot.lookingAt(mc, pos);
		return steer.attack;
	}

	/** Called once for a server-confirmed player break. */
	public void noteMined(ClientLevel level, BlockPos pos, String name) {
		mined++;
		if (cfg.destroyLogTargets && journal != null) {
			journal.log(Journal.Kind.MINED, level, pos, "Mined " + name + " (" + mined + " so far)");
		}
	}

	// ------------------------------------------------------------ patience

	/**
	 * How long one block may be swung at before it is written off.
	 *
	 * <p>Generous on purpose. Obsidian with an iron pick is twenty-five seconds of honest work,
	 * and the thing this exists to catch — a server quietly putting the block back — never
	 * finishes at all. So being generous costs one wasted half minute, and being tight costs a
	 * target given up on for the crime of being slow.
	 */
	int mineCeilingTicks() {
		return (int) Math.round(cfg.destroyBlockSec * 20);
	}

	/**
	 * How long one target may occupy the bot in total, however that time is spent.
	 *
	 * <p>Wider than the mining ceiling, because it has to cover walking there as well as
	 * breaking it. The point is only that it exists: with every individual step bounded and
	 * nothing bounding the whole, a bot can still spend an afternoon on one block by failing at
	 * it in a slightly different way each time.
	 */
	int targetCeilingTicks() {
		return (int) Math.round(cfg.destroyTargetSec * 20);
	}

	/** Give up on a block, and on whatever target it was standing between us and. */
	private void writeOff(BlockPos block, LocalPlayer player) {
		MovRand.LOG.info("[movrand] Deferring block {}: {}", block.toShortString(), detail);
		long after = tick + Math.max(20, Math.round(cfg.destroyRetrySec * 20));
		BlockPos from = player.blockPosition().immutable();
		retries.put(block.asLong(), new Retry(after, from));
		if (target != null) retries.put(target.asLong(), new Retry(after, from));
		target = null;
		// Rebuild the bounded heap now that this block is deferred, so it cannot occupy a slot
		// that could have held another workable target.
		scanCooldown = 0;
		targetTicks = 0;
		preparation.reset();
		MineSafety.clearDenied();
		forgetPlacement();
		forgetBlock();
		nav.reset();
		phase = Phase.SCANNING;
	}

	/** Drop the per-block aiming state, so the next one starts by choosing a face again. */
	private void forgetBlock() {
		arrivalTicks = 0;
		arrivalProgress.reset();
		rejectedWorkCells.clear();
		breaking = null;
		miningAim.reset();
		aimMissTicks = 0;
		mineTicks = 0;
		aimFace = null;
	}

	/**
	 * Told from outside that the way ahead is not walkable, whatever the plan said.
	 *
	 * <p>A route is only as fresh as the world it was planned in, and this is a job that takes
	 * blocks out of that world for a living. The plan gets dropped rather than argued with.
	 */
	public void blocked(String why) {
		detail = why;
		nav.invalidate(why);
		fetch.invalidate(why);
	}

	// --------------------------------------------------------- the interrupts

	/**
	 * The bag: sell it, thin it out, or say it is full and mean something by it.
	 *
	 * @return true when the bag is what this tick is about
	 */
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
		// Restocking is useful before the bag is full: a bridge can run out while most inventory
		// slots are still empty. Keeping this below the full check made the option effectively a
		// full-inventory recovery switch rather than a hotbar restocker.
		if (backpack.restockHotbar(mc, player)) {
			phase = Phase.TIDYING;
			detail = backpack.status;
			waitABit();
			return true;
		}
		if (!backpack.full(player)) {
			bagFull = false;
			return false;
		}

		if (backpack.dropOneJunkStack(mc, player)) {
			phase = Phase.TIDYING;
			detail = backpack.status;
			waitABit();
			return true;
		}
		// Full, with nothing left to sell and nothing left to throw away. Standing still over it
		// is a tick with no counter running, so this is said out loud exactly once and the
		// controller decides — carry on mining into a floor we cannot pick up, or stop.
		if (!bagFull) {
			bagFull = true;
			bagFullNews = true;
		}
		return false;
	}

	/**
	 * True once per time the bag fills with nothing left to do about it, so the caller reacts
	 * once rather than every tick.
	 */
	public boolean takeBagFull() {
		boolean was = bagFullNews;
		bagFullNews = false;
		return was;
	}

	/**
	 * Get to the surface before the bar runs out.
	 *
	 * <p>Swimming up is the whole of it in open water. Under a ceiling it is not, which is
	 * exactly the situation the job creates for itself: mine into an aquifer from below and the
	 * way out is through the stone you are standing under. So the ceiling gets broken too, with
	 * whatever is in hand — there is no time to be picky about the tool.
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
			steer.attackAt(above);
			steer.attack = Bot.lookingAt(mc, above);
		} else {
			steer.lookAt(player.getYRot(), SURFACE_PITCH);   // up, which is also where we are going
		}
		// a route planned from down here is a route back to the thing that drowned us
		nav.invalidate("surfacing");
		return true;
	}

	/** Looking up, without being so far up that the camera is on its back. */
	private static final double SURFACE_PITCH = -70;

	/**
	 * Whether to be heading for the surface.
	 *
	 * <p>Latching matters more than the threshold does. Without it the bot surfaces to one tick
	 * above the line, goes back to work, drops under it again, and spends the whole bar
	 * oscillating an inch below the water — so once it starts climbing it keeps climbing until
	 * it is actually breathing again.
	 */
	static boolean needsAir(boolean submerged, int air, int maxAir, double secondsLeft, boolean already) {
		if (already) return submerged || air < maxAir;
		return submerged && air <= secondsLeft * 20;
	}

	/**
	 * Put a block into lava the bot is standing right beside.
	 *
	 * <p>Deliberately almost nothing. Lava that is in the <em>way</em> is the route's problem and
	 * the route bridges over it, priced and planned; this is only for the square that turns up
	 * under our own feet without being planned for. It is bounded, it gives up, and when it
	 * gives up it stays given up for a while.
	 */
	private boolean capLiquid(Minecraft mc, LocalPlayer player, ClientLevel level, Bot.Steer steer) {
		if (capCooldown > 0) return false;
		BlockPos liquid = Bot.liquidBeside(mc, player, cfg.coverWater, cfg.coverLava);
		if (liquid == null || !Bot.inReach(mc, player, liquid)) { forgetPlacement(); return false; }
		int slot = Bot.buildingSlot(player, cfg);
		if (slot < 0) return false;

		phase = Phase.COVERING;
		detail = "capping " + (Avoidance.isLavaAt(level, liquid) ? "lava" : "water") + " underfoot";
		player.getInventory().setSelectedSlot(slot);
		steer.sneak = true;                        // at the edge of it, so do not walk in
		if (!placeInto(mc, player, liquid, steer) || placeTicks > capGiveUpTicks()) {
			// nothing to place it against, or long enough spent failing to
			capCooldown = (int) Math.round(cfg.coverRestSec * 20);
			forgetPlacement();
			steer.clear();
			return false;
		}
		return true;
	}

	private int capGiveUpTicks() {
		return (int) Math.round(cfg.coverGiveUpSec * 20);
	}

	/**
	 * Point at a face and hold use until a block goes into {@code where}.
	 *
	 * <p>Latched exactly as breaking is, and for the same reason: the camera is filtered, so a
	 * support face re-chosen every tick means the crosshair is always on its way to somewhere it
	 * has already stopped wanting to be, and the check that presses the button never comes true.
	 *
	 * @return false when there is nowhere to place it from
	 */
	private boolean placeInto(Minecraft mc, LocalPlayer player, BlockPos where, Bot.Steer steer) {
		if (!where.equals(placeTarget)) {
			placeTarget = where;
			placeFace = null;
			placeTicks = 0;
			placeWasFillable = Bot.fillable(mc, where);
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
		steer.placeInto(where);
		steer.use = pressing;
		return true;
	}

	/** Release a completed placement target without counting a client prediction. */
	private void observePlacement(Minecraft mc) {
		if (placeTarget == null) return;
		boolean fillable = Bot.fillable(mc, placeTarget);
		if (placeWasFillable && !fillable) {
			forgetPlacement();
			return;
		}
		placeWasFillable = fillable;
	}

	private void forgetPlacement() {
		placeTarget = null;
		placeFace = null;
		placeTicks = 0;
		placeWasFillable = false;
	}

	private Bot.Steer collectDrops(PathMove.Ctx ctx, Bot.Steer steer) {
		if (backpack.full(ctx.player())) return null;
		boolean active = drops.tick(ctx, steer, mayBreak(), gatheringSupplies());
		absorb(fetch);
		if (!active) return null;
		phase = Phase.COLLECTING;
		detail = drops.detail;
		return steer;
	}

	/** A reaction time, so a decision does not land on the same tick as the thing that caused it. */
	private void waitABit() {
		if (cfg.taskReactionMaxSec <= 0) return;
		waitTicks = Rng.ticks(cfg.taskReactionMinSec, cfg.taskReactionMaxSec);
	}

	/**
	 * A one-line account of what the job thinks it is doing, for when what it is doing and what
	 * it says it is doing have stopped agreeing.
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

	// ----------------------------------------------------------- self-check

	/**
	 * Self-check on the bookkeeping — the job needs a world, the accounting does not:
	 * {@code ./gradlew selfCheck -Pcheck=com.damia.movrand.BaseDestroyer}
	 */
	public static void main(String[] args) {
		Config cfg = new Config();
		cfg.clampAll();
		assert needsSupplies(cfg, 0) : "gathering must be enabled by default";
		cfg.gatherBuildingBlocks = false;
		assert !needsSupplies(cfg, 0) : "disabled gathering changed the job";
		cfg.gatherBuildingBlocks = true;
		assert needsSupplies(cfg, 0) && needsSupplies(cfg, cfg.bridgeKeepBlocks);
		assert !needsSupplies(cfg, cfg.bridgeKeepBlocks + 1) : "gathered despite usable stock";
		Config saved = cfg.copy();
		assert saved.gatherBuildingBlocks && saved.gatherBlocks.equals(cfg.gatherBlocks)
				&& saved.gatherBlockCount == cfg.gatherBlockCount : "profile lost resupply settings";
		cfg.gatherBlocks.clear();
		assert !needsSupplies(cfg, 0) : "an empty source list mined arbitrary blocks";
		cfg.gatherBlockCount = 0;
		cfg.clampAll();
		assert cfg.gatherBlockCount == 1;
		cfg.gatherBuildingBlocks = false;
		BaseDestroyer d = new BaseDestroyer(cfg, null);
		var body = new net.minecraft.world.phys.AABB(0.2, 0, 0.2, 0.8, 1.8, 0.8);
		BlockPos distantDrop = new BlockPos(3, 1, 0), nearbyDrop = new BlockPos(1, 1, 0);
		assert cfg.mineWithinPickupRange : "default dropped the existing pickup-range preference";
		cfg.mineWithinPickupRange = false;
		assert cfg.protectMiningDrops && closeToDrops(cfg, body, distantDrop)
				: "safe drop preparation restricted mining reach";
		cfg.mineWithinPickupRange = true;
		assert !closeToDrops(cfg, body, distantDrop) && closeToDrops(cfg, body, nearbyDrop);
		cfg.protectMiningDrops = false;
		assert !closeToDrops(cfg, body, distantDrop) : "pickup range depended on drop protection";
		assert cfg.copy().mineWithinPickupRange : "profile copy lost pickup preference";
		cfg.mineWithinPickupRange = false;
		cfg.protectMiningDrops = true;

		BlockPos near = new BlockPos(1, 0, 0), far = new BlockPos(8, 0, 0), tie = new BlockPos(1, 0, 1);
		cfg.destroyTargetRandomness = 1;
		for (int i = 0; i < 500; i++) {
			BlockPos chosen = chooseCandidate(new ArrayList<>(List.of(
					new Candidate(far, 8, true, false), new Candidate(near, 1, true, false),
					new Candidate(tie, 1.2, true, false))), cfg);
			assert !far.equals(chosen) : "randomisation selected a distant block";
		}
		cfg.destroyTargetRandomness = 0;
		assert near.equals(chooseCandidate(new ArrayList<>(List.of(
				new Candidate(far, 8, true, false), new Candidate(near, 1, true, false))), cfg))
				: "old shortlist order beat current distance";
		assert far.equals(chooseCandidate(new ArrayList<>(List.of(
				new Candidate(near, 1, false, false), new Candidate(far, 4, true, false))), cfg))
				: "an obstructed target beat a block ready to mine";
		cfg.destroyPreferReachable = false;
		assert near.equals(chooseCandidate(new ArrayList<>(List.of(
				new Candidate(near, 1, false, false), new Candidate(far, 4, true, false))), cfg));
		cfg.destroyStorageLast = true;
		assert far.equals(chooseCandidate(new ArrayList<>(List.of(
				new Candidate(near, 1, true, true), new Candidate(far, 4, true, false))), cfg));
		cfg.taskAimMaxTurnDeg = 2;
		cfg.taskAimSmoothing = 0.95;
		assert aimDeadlineTicks(cfg) >= 110 : "slow configured aiming was timed out prematurely";
		cfg.fastDestroyerTuning();
		cfg.clampAll();

		// a phase always has something to say, even before anything has happened
		assert d.phase == Phase.OFF && !d.describe().isEmpty()
				: "an idle destroyer described itself as nothing";
		assert d.remaining() == 0 && d.target() == null : "a fresh destroyer already has work in hand";

		// Written-off targets stay written off until their retry time instead of becoming
		// immediately eligible again when the shortlist contains nothing else.
		Map<Long, Retry> giveUp = new HashMap<>();
		List<BlockPos> seen = List.of(new BlockPos(1, 1, 1), new BlockPos(2, 1, 1));
		Retry later = new Retry(100, BlockPos.ZERO);
		giveUp.put(seen.getFirst().asLong(), later);
		assert !giveUp.containsKey(seen.get(1).asLong()) : "writing one block off wrote off another";
		giveUp.put(seen.get(1).asLong(), later);
		assert !retryReady(later, 50, BlockPos.ZERO, 8) : "a written-off block came back early";
		assert retryReady(later, 100, BlockPos.ZERO, 8) : "the timed retry never became due";
		assert retryReady(later, 50, new BlockPos(8, 0, 0), 8) : "a new vantage point did not retry";

		// The shortlist is what the target choice draws from, and its two jobs are to have at
		// least one thing in it and to never be the whole list — a bot picking at random from
		// every block in range crosses the room and back for the rest of the afternoon.
		cfg.destroyTargetChoices = 0;
		cfg.clampAll();
		assert cfg.destroyTargetChoices >= 1 : "the shortlist was allowed to be empty";
		assert cfg.destroyTargetChoices <= 32 : "the shortlist is the whole room";

		// The reaction delay has to be a real delay: zero means the bot turns on the same tick
		// the block appears, which is the one thing no person does.
		cfg.taskReactionMinSec = 0;
		cfg.taskReactionMaxSec = 0;
		cfg.clampAll();
		assert cfg.taskReactionMaxSec > 0 : "the reaction delay was allowed to be zero";
		assert cfg.taskReactionMaxSec >= cfg.taskReactionMinSec : "the reaction range is inverted";

		// and the scan cannot be free, or it runs every tick over a quarter million blocks
		cfg.destroyScanSec = 0;
		cfg.destroyScanMaxSec = 0;
		cfg.clampAll();
		assert cfg.destroyScanSec >= 0.25 : "the scan interval was allowed to collapse";
		assert cfg.destroyScanMaxSec >= cfg.destroyScanSec : "the scan range is inverted";

		// The two ceilings are what stop a protected block holding the bot still forever, so
		// they have to be finite - and long enough that honest slow work finishes. Obsidian with
		// an iron pickaxe is about twenty-five seconds, which is the slowest thing worth mining.
		cfg.destroyBlockSec = 0;
		cfg.destroyTargetSec = 0;
		cfg.clampAll();
		int block = d.mineCeilingTicks();
		int whole = d.targetCeilingTicks();
		assert block >= 25 * 20 : "a block is written off before obsidian could break: " + block;
		assert block < 20 * 60 * 20 : "the block ceiling is so high it is not a ceiling: " + block;
		assert whole > block : "a target may not be given less time in total than one of its blocks";
		cfg.destroyBlockSec = 90;
		cfg.clampAll();
		assert d.mineCeilingTicks() > block : "a longer patience did not buy a longer ceiling";

		// Every route outcome the job can be handed has to end in it doing something. This is
		// the one that used to be missing: arriving somewhere the block cannot actually be
		// worked from is a reason to pick a different block, not a reason to stand there.
		for (Pathing.Nav outcome : Pathing.Nav.values()) {
			assert switch (outcome) {
				case WALKING, PLANNING -> true;      // keeps the keys moving
				case ARRIVED, NO_ROUTE -> true;      // writes the target off and moves on
			} : "a navigation outcome with nothing decided for it: " + outcome;
		}

		// Every phase a route can put the job in has to be a phase, and the three that matter
		// have to be told apart - a HUD that says "walking" while the bot is bridging over lava
		// is a HUD nobody can debug from.
		for (PathFinder.Kind kind : PathFinder.Kind.values()) {
			assert phaseFor(kind) != null && !phaseFor(kind).label.isEmpty()
					: "a route move with no phase to show for it: " + kind;
		}
		assert phaseFor(PathFinder.Kind.MINE) == Phase.CLEARING : "digging through is not walking";
		assert phaseFor(PathFinder.Kind.BRIDGE) == Phase.BRIDGING : "placing a floor is not walking";
		assert phaseFor(PathFinder.Kind.WALK) == Phase.WALKING : "walking is walking";

		// and the done reaction fires exactly once per dry spell, or every tick with nothing to
		// break is an alert and a chat line
		d.finished = true;
		assert d.takeFinished() : "running out of work went unreported";
		assert !d.takeFinished() : "running out of work reported itself twice";

		// Regression: an empty shortlist is not an empty world. Hidden blocks, deferred blocks,
		// and unloaded chunks used to flow through the same target == null branch as a genuinely
		// exhaustive empty scan and announce that the job had finished.
		BlockTargets.ScanResult hidden = new BlockTargets.ScanResult(List.of(), 37, 37, 37, 25, 0);
		BlockTargets.ScanResult deferred = new BlockTargets.ScanResult(List.of(), 12, 0, 0, 25, 0);
		BlockTargets.ScanResult incomplete = new BlockTargets.ScanResult(List.of(), 0, 0, 0, 20, 5);
		BlockTargets.ScanResult empty = new BlockTargets.ScanResult(List.of(), 0, 0, 0, 25, 0);
		assert !provesEmpty(hidden, false) : "hidden matches were called finished";
		assert !provesEmpty(deferred, false) : "deferred matches were called finished";
		assert !provesEmpty(incomplete, false) : "an unloaded search area was called empty";
		assert provesEmpty(incomplete, true) : "the explicit incomplete-scan override did nothing";
		assert provesEmpty(empty, false) : "a complete zero-match scan did not prove empty";
		cfg.destroyEmptyScansToFinish = 0;
		cfg.clampAll();
		assert cfg.destroyEmptyScansToFinish == 1 : "empty-scan confirmation could be disabled by accident";

		System.out.println("BaseDestroyer self-check passed");
	}
}
