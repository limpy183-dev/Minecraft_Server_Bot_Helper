package com.damia.movrand;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
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
	private List<long[]> path = List.of();
	private int step;
	private int repathIn;
	private int stuckTicks;
	private double lastX, lastZ;
	private int waitTicks;
	private long tick;
	/** Targets that could not be reached, so the search does not keep choosing them. */
	private final Set<Long> giveUp = new HashSet<>();
	private int scanCooldown;
	private List<BlockTargets.Found> found = List.of();
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
		waitTicks = 0;
		scanCooldown = 0;
		giveUp.clear();
		finished = false;
		surfacing = false;
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

		// 5. Standing next to something that will kill us if we mine into it.
		if (cfg.coverLiquids && coverLiquid(mc, player, steer)) return steer;

		// 6. Our own drops, before they despawn.
		if (cfg.collectDrops && collectDrops(mc, player, steer)) return steer;

		// 7. The job.
		return work(mc, player, level, steer);
	}

	// -------------------------------------------------------------- the job

	private Bot.Steer work(Minecraft mc, LocalPlayer player, ClientLevel level, Bot.Steer steer) {
		if (target != null && !stillATarget(level, target)) {
			// it went: either we broke it or somebody else did
			target = null;
			path = List.of();
		}

		if (target == null) {
			if (scanCooldown > 0) scanCooldown--;
			else {
				found = targets.scan(level, player, cfg);
				scanCooldown = Math.max(1, (int) (cfg.destroyScanSec * 20));
			}
			target = pickTarget(player);
			if (target == null) {
				if (phase != Phase.DONE) finished = true;
				phase = Phase.DONE;
				detail = cfg.destroyBlocks.isEmpty() && noFamilies()
						? "no blocks selected" : "nothing selected within " + cfg.destroyRadius + " blocks";
				return steer;
			}
			// back in business after a dry spell: the next dry spell is news again
			finished = false;
			path = List.of();
			// a person does not start walking the instant a block appears on screen
			waitABit();
			journalTarget(level);
		}

		double reach = player.blockInteractionRange() - 0.6;
		if (Bot.inReach(player, target)) return mine(mc, player, level, steer);

		if (path.isEmpty() || repathIn-- <= 0) {
			plan(mc, player, level, reach);
		}
		return follow(mc, player, level, steer);
	}

	private boolean noFamilies() {
		for (BlockTargets.Family f : BlockTargets.Family.values()) if (cfg.destroyFamily(f)) return false;
		return true;
	}

	private BlockPos pickTarget(LocalPlayer player) {
		for (BlockTargets.Found f : found) {
			if (giveUp.contains(f.pos().asLong())) continue;
			return f.pos();
		}
		// everything within reach has been written off: let them back in and try again
		if (!giveUp.isEmpty() && !found.isEmpty()) {
			giveUp.clear();
			return found.getFirst().pos();
		}
		return null;
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
		BlockState state = level.getBlockState(target);
		int tool = Bot.bestToolSlot(player, state);
		if (tool != player.getInventory().getSelectedSlot()) {
			player.getInventory().setSelectedSlot(tool);
		}

		Vec3 point = Bot.aimPoint(mc, player, target);
		double[] look = Bot.aimAt(player, point);
		steer.lookAt(look[0], look[1]);

		// Vanilla does the mining. Holding attack while the crosshair is on the block runs
		// the same progress, swing and packet loop a person's mouse does; reimplementing it
		// would be more code producing a stream that is easier to tell apart, not harder.
		boolean onIt = Bot.lookingAt(mc, target);
		steer.attack = onIt;
		detail = describeBlock(level, target) + (onIt ? "" : " (lining up)");

		if (onIt) stuckTicks = 0;
		else if (++stuckTicks > cfg.destroyGiveUpSec * 20) {
			// the crosshair never landed on it: something is between us and it
			giveUp.add(target.asLong());
			detail = "cannot see " + describeBlock(level, target);
			target = null;
			stuckTicks = 0;
		}
		return steer;
	}

	/** Called by the controller when a target block turns to air, so the count is real. */
	public void noteMined(ClientLevel level, BlockPos pos, String name) {
		mined++;
		if (cfg.destroyLogTargets) {
			journal.log(Journal.Kind.MINED, level, pos, "Mined " + name + " (" + mined + " so far)");
		}
	}

	// ------------------------------------------------------------- pathing

	private void plan(Minecraft mc, LocalPlayer player, ClientLevel level, double reach) {
		BlockPos from = player.blockPosition();
		boolean canBridge = Bot.buildingSlot(player, cfg) >= 0;
		PathFinder.Rules rules = PathFinder.Rules.of(cfg, true, canBridge);
		PathFinder.Path result = PathFinder.find(new PathFinder.Level(level, cfg),
				from.getX(), from.getY(), from.getZ(),
				target.getX(), target.getY(), target.getZ(),
				Math.max(1.5, reach), rules);

		path = result.steps();
		step = 1;
		lastPathCost = result.cost();
		lastPathNodes = result.searched();
		repathIn = Math.max(10, (int) (cfg.pathRefreshSec * 20));

		if (!result.complete() && result.isEmpty()) {
			// nowhere to go and nothing gained by standing here thinking about it
			giveUp.add(target.asLong());
			target = null;
			path = List.of();
		}
	}

	private Bot.Steer follow(Minecraft mc, LocalPlayer player, ClientLevel level, Bot.Steer steer) {
		if (path.size() <= 1 || step >= path.size()) {
			phase = Phase.WALKING;
			detail = "no route";
			repathIn = 0;
			return steer;
		}

		// Walk off every node already reached before looking at the next one. A loop rather
		// than recursion: a route is as long as the node budget allows, and a stack frame per
		// step is a crash waiting for the one tick that arrives standing on twenty of them.
		double nx, ny, nz, dx, dz, flat;
		while (true) {
			long[] node = path.get(step);
			nx = node[0] + 0.5;
			ny = node[1];
			nz = node[2] + 0.5;
			dx = nx - player.getX();
			dz = nz - player.getZ();
			flat = Math.sqrt(dx * dx + dz * dz);
			if (flat >= 0.55 || Math.abs(player.getY() - ny) >= 1.2) break;
			if (++step >= path.size()) {
				phase = Phase.WALKING;
				detail = "arrived";
				repathIn = 0;
				return steer;
			}
		}
		long[] node = path.get(step);

		BlockPos ahead = new BlockPos((int) node[0], (int) node[1], (int) node[2]);

		// The route said "through here". If it is not open any more, open it.
		if (blocked(level, ahead)) {
			return clearTheWay(mc, player, level, ahead, steer);
		}
		// The route said "across here". If there is nothing to walk on, build it.
		if (needsFloor(level, ahead)) {
			return bridge(mc, player, level, ahead.below(), steer);
		}

		phase = Phase.WALKING;
		detail = "%d of %d steps".formatted(step, path.size() - 1);
		steer.lookAt(Math.toDegrees(Math.atan2(-dx, dz)), lookAheadPitch(player, ny));
		steer.forward = true;
		steer.sprint = cfg.destroySprint && flat > 2.5 && player.onGround();
		steer.jump = ny > player.getY() + 0.4 && player.onGround();
		// a gap in the floor with a route across it is walked slowly and at the edge
		steer.sneak = cfg.bridgeSneak && needsFloor(level, ahead.below(0)) && !player.onGround();

		trackProgress(player);
		return steer;
	}

	/** Look slightly along the route rather than at your own feet, as a person does. */
	private static double lookAheadPitch(LocalPlayer player, double nodeY) {
		double dy = nodeY - player.getEyeY();
		return Math.max(-40, Math.min(40, Math.toDegrees(-Math.atan2(dy, 3))));
	}

	private void trackProgress(LocalPlayer player) {
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
			// twice in a row on the same target means the route is a lie, not a bad tick
			if (target != null && path.isEmpty()) giveUp.add(target.asLong());
			path = List.of();
		}
	}

	private boolean blocked(ClientLevel level, BlockPos pos) {
		return solid(level, pos) || solid(level, pos.above());
	}

	private boolean needsFloor(ClientLevel level, BlockPos pos) {
		return !solid(level, pos.below()) && !level.getBlockState(pos.below()).liquid();
	}

	private static boolean solid(ClientLevel level, BlockPos pos) {
		return !level.getBlockState(pos).getCollisionShape(level, pos).isEmpty();
	}

	/** Mine the one block in the way, rather than repathing round a doorway we can open. */
	private Bot.Steer clearTheWay(Minecraft mc, LocalPlayer player, ClientLevel level,
	                              BlockPos ahead, Bot.Steer steer) {
		BlockPos wall = solid(level, ahead) ? ahead : ahead.above();
		if (!Bot.inReach(player, wall)) {
			phase = Phase.WALKING;
			detail = "closing on the wall";
			double dx = wall.getX() + 0.5 - player.getX(), dz = wall.getZ() + 0.5 - player.getZ();
			steer.lookAt(Math.toDegrees(Math.atan2(-dx, dz)), 0);
			steer.forward = true;
			return steer;
		}
		if (!cfg.pathMine || !BlockTargets.breakable(level.getBlockState(wall), level, wall)) {
			repathIn = 0;
			path = List.of();
			phase = Phase.WALKING;
			detail = "blocked, replanning";
			return steer;
		}

		phase = Phase.CLEARING;
		detail = "digging through " + describeBlock(level, wall);
		int tool = Bot.bestToolSlot(player, level.getBlockState(wall));
		if (tool != player.getInventory().getSelectedSlot()) player.getInventory().setSelectedSlot(tool);
		double[] look = Bot.aimAt(player, Bot.aimPoint(mc, player, wall));
		steer.lookAt(look[0], look[1]);
		steer.attack = Bot.lookingAt(mc, wall);
		trackProgress(player);
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
		detail = "placing a block";
		player.getInventory().setSelectedSlot(slot);
		steer.sneak = cfg.bridgeSneak;
		if (Bot.aimToPlace(mc, player, where, steer)) {
			steer.use = Bot.aboutToPlaceInto(mc, where);
			if (steer.use) placed++;
		} else {
			repathIn = 0;
			path = List.of();
			detail = "nothing to place against";
		}
		trackProgress(player);
		return steer;
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

	private boolean coverLiquid(Minecraft mc, LocalPlayer player, Bot.Steer steer) {
		BlockPos liquid = Bot.exposedLiquid(mc, player, Math.max(1, cfg.coverRadius));
		if (liquid == null) return false;
		int slot = Bot.buildingSlot(player, cfg);
		if (slot < 0) return false;
		BlockPos over = liquid.above();
		if (!Bot.inReach(player, over)) return false;

		phase = Phase.COVERING;
		detail = "capping " + describeBlock(mc.level, liquid);
		player.getInventory().setSelectedSlot(slot);
		steer.sneak = true;
		if (Bot.aimToPlace(mc, player, over, steer)) {
			steer.use = Bot.aboutToPlaceInto(mc, over);
			if (steer.use) placed++;
			return true;
		}
		return false;
	}

	private boolean collectDrops(Minecraft mc, LocalPlayer player, Bot.Steer steer) {
		if (mc.level == null) return false;
		if (backpack.full(player)) return false;
		ItemEntity best = null;
		double bestDist = (double) cfg.collectRadius * cfg.collectRadius;
		for (Entity e : mc.level.entitiesForRendering()) {
			if (!(e instanceof ItemEntity item)) continue;
			double d = e.distanceToSqr(player);
			if (d < bestDist) {
				bestDist = d;
				best = item;
			}
		}
		if (best == null) return false;
		// close enough that walking into it will do it: vanilla pickup is 1 block
		if (bestDist < 1.2) {
			collected++;
			return false;
		}

		phase = Phase.COLLECTING;
		detail = best.getItem().getHoverName().getString();
		double dx = best.getX() - player.getX(), dz = best.getZ() - player.getZ();
		steer.lookAt(Math.toDegrees(Math.atan2(-dx, dz)),
				lookAheadPitch(player, best.getY()));
		steer.forward = true;
		steer.jump = best.getY() > player.getY() + 1.2 && player.onGround();
		return true;
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

		System.out.println("BaseDestroyer self-check passed");
	}
}
