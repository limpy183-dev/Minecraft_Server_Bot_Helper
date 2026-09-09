package com.damia.movrand;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.Settings;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.movement.IMovement;
import baritone.api.schematic.FillSchematic;
import baritone.api.utils.Rotation;
import baritone.api.utils.input.Input;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.Vec3;

import java.util.*;

/** One owner of Baritone's real path executor; the task controller yields both keys and camera. */
public final class NativeNavigation {
	private static volatile NativeNavigation owner;
	private final Human.Turn yawTurn = new Human.Turn(), pitchTurn = new Human.Turn(false);
	private boolean cameraSynced;
	private final Pace pace = new Pace();
	private final Config cfg;
	private IBaritone engine;
	private BlockPos destination;
	private Goal goal;
	private boolean building;
	private boolean retiring;
	private int ticks, idleTicks, failures;
	private final NavigationWatchdog progress = new NavigationWatchdog();
	private BlockPos observedBreaking;
	private Vec3 handoffAnchor;
	private int handoffTicks, handoffSampleTick = -1;
	private final Map<Long, Integer> visits = new HashMap<>();
	private long lastCell = Long.MIN_VALUE;
	private final Map<Settings.Setting<?>, Object> saved = new IdentityHashMap<>();
	private final Map<Settings.Setting<?>, Object> assigned = new IdentityHashMap<>();
	String status = "";
	int nodes, step, length;
	double cost;

	NativeNavigation(Config cfg) { this.cfg = cfg; }

	/** The executor accepts any point in a landing cell, so include the body's overhang
	 * into neighbouring cells, not just the air in the destination column. */
	public static boolean clearLanding(MineSafety.View view, int x, int y, int z) {
		for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++)
			for (int dy = 0; dy <= 1; dy++) {
				int cell = view.cell(x + dx, y + dy, z + dz);
				if (cell == MineSafety.BURNING || cell == MineSafety.UNKNOWN) return false;
			}
		return true;
	}

	public static boolean controlling() { return owner != null; }
	public static Config activeConfig() { NativeNavigation current = owner; return current == null ? null : current.cfg; }

	/** Only reduce an already-approved sprint on a clear, level stretch of the active route. */
	public static boolean randomiseSprint(baritone.api.pathing.path.IPathExecutor executor, boolean sprint) {
		NativeNavigation current = owner;
		if (current == null || current.engine == null || current.engine.getPathingBehavior().getCurrent() != executor)
			return sprint;
		if (!current.cfg.baritoneRandomisePace) { current.pace.reset(); return sprint; }
		var player = current.engine.getPlayerContext().player();
		if (player == null) return sprint;
		boolean eligible = current.cfg.baritoneRandomisePace && current.cfg.destroySprint && sprint
				&& !current.building && !current.retiring && player.onGround()
				&& !player.isInWater() && !player.isInLava() && !player.onClimbable()
				&& !player.isUsingItem() && !player.horizontalCollision;
		var input = current.engine.getInputOverrideHandler();
		eligible &= !input.isInputForcedDown(Input.JUMP) && !input.isInputForcedDown(Input.SNEAK)
				&& !input.isInputForcedDown(Input.CLICK_LEFT) && !input.isInputForcedDown(Input.CLICK_RIGHT);
		// Preserve run-ups and landings: the previous, current and next two moves must
		// all be ordinary traverses with full solid footing and no planned block edits.
		var moves = executor.getPath().movements();
		int index = executor.getPosition();
		eligible &= index > 0 && index + 2 < moves.size();
		if (eligible) for (int i = index - 1; i <= index + 2; i++) {
			IMovement move = moves.get(i);
			if (!(move instanceof baritone.pathing.movement.movements.MovementTraverse)
					|| !move.safeToCancel() || move.getSrc().getY() != move.getDest().getY()) {
				eligible = false;
				break;
			}
			var world = current.engine.getPlayerContext().world();
			for (BlockPos feet : new BlockPos[]{move.getSrc(), move.getDest()}) {
				BlockPos floor = feet.below();
				if (!world.getBlockState(floor).isCollisionShapeFullBlock(world, floor)
						|| baritone.pathing.movement.MovementHelper.avoidWalkingInto(world.getBlockState(floor))
						|| !world.getFluidState(floor).isEmpty()
						|| !world.getBlockState(feet).isAir() || !world.getBlockState(feet.above()).isAir())
					eligible = false;
			}
		}
		return current.pace.sprint(current.cfg, eligible, player.tickCount) && sprint;
	}

	/** Keep a sampled pace for a whole segment; critical moves immediately resume normal execution. */
	static final class Pace {
		private int untilTick;
		private boolean sampled, sprint;
		void reset() { sampled = false; }
		boolean sprint(Config cfg, boolean eligible, int tick) {
			if (!cfg.baritoneRandomisePace || !eligible) { reset(); return true; }
			if (!sampled || tick >= untilTick) {
				sprint = Rng.chance(cfg.baritoneSprintChance);
				untilTick = tick + Rng.ticks(cfg.baritonePaceMinSec, cfg.baritonePaceMaxSec);
				sampled = true;
			}
			return sprint;
		}
	}
    public static boolean finishingCriticalMove() { return owner != null && !owner.safeToCancel(); }

	/** Smooth every actual look, including precision moves; candidate geometry stays exact. */
	public static Rotation smoothRotation(Rotation previous, Rotation desired, boolean interaction) {
		NativeNavigation current = owner;
		if (current == null) return desired;
		// Start looking at the landing during the run-up. At low turn rates, waiting
		// until airborne to request a placement face makes its click window impossible.
		// This spends existing travel time, without holding keys or bypassing smoothing.
		if (!interaction && current.engine != null
				&& current.currentMove() instanceof baritone.pathing.movement.movements.MovementParkour move) {
			var player = current.engine.getPlayerContext().player();
			if (player != null && player.onGround()) {
				double[] landing = Bot.aimAt(player, Vec3.atCenterOf(move.getDest().below()));
				desired = new Rotation(desired.getYaw(), (float) landing[1]);
			}
		}
		if (!current.cameraSynced) {
			current.yawTurn.sync(previous.getYaw());
			current.pitchTurn.sync(previous.getPitch());
			current.cameraSynced = true;
		}
		Config c = current.cfg;
		return new Rotation((float) Human.wrap(current.yawTurn.next(desired.getYaw(), c.baritoneTurnSmoothing, c.baritoneTurnRate)),
				(float) current.pitchTurn.next(desired.getPitch(), c.baritoneTurnSmoothing, c.baritoneTurnRate)).clamp();
	}

	Pathing.Nav tick(PathMove.Ctx ctx, Bot.Steer steer, BlockPos want, PathFinder.Goal test, Set<Block> mayBreak, boolean edits) {
		BlockPos feet = ctx.player().blockPosition();
		// A local arrival needs neither a goal snapshot nor a worker-thread search.
		if (test.reached(feet.getX(), feet.getY(), feet.getZ()) && settled(ctx)) {
			if (finishBeforeHandoff(steer)) return Pathing.Nav.WALKING;
			reset();
			status = "arrived";
			return Pathing.Nav.ARRIVED;
		}
		if (owner != this || retiring || building || !want.equals(destination)) {
			if (finishBeforeHandoff(steer)) return Pathing.Nav.WALKING;
			acquire(ctx, mayBreak, edits);
			destination = want.immutable();
			building = false;
			goal = snapshotGoal(ctx, want, test);
			if (goal == null) { status = "no safe working position in loaded terrain"; reset(); return Pathing.Nav.NO_ROUTE; }
			engine.getCustomGoalProcess().setGoalAndPath(goal);
		}
		ticks++;
		if (ticks % 20 == 0) configure(ctx, mayBreak, edits);
		var pathing = engine.getPathingBehavior();
		String stalled = checkProgress(ctx);
		if (stalled != null && settled(ctx)) {
			// A movement can incorrectly remain marked unsafe after it has stopped on the
			// ground. The watchdog may cancel that stale executor, but never an airborne jump.
			pathing.forceCancel();
			if (++failures >= Math.max(1, Math.min(3, cfg.pathAttempts))) {
				status = stalled;
				reset();
				steer.clear();
				return Pathing.Nav.NO_ROUTE;
			}
			goal = snapshotGoal(ctx, want, test);
			if (goal == null) { status = "no safe working position"; reset(); return Pathing.Nav.NO_ROUTE; }
			engine.getCustomGoalProcess().setGoalAndPath(goal);
			progress.reset();
			idleTicks = 0;
		}
		if (!pathing.hasPath() && pathing.getInProgress().isEmpty()) {
			if (++idleTicks > 6) {
				if (++failures >= Math.max(1, Math.min(3, cfg.pathAttempts))) { status = "Baritone found no route"; reset(); return Pathing.Nav.NO_ROUTE; }
				goal = snapshotGoal(ctx, want, test);
				if (goal == null) { reset(); return Pathing.Nav.NO_ROUTE; }
				engine.getCustomGoalProcess().setGoalAndPath(goal);
				idleTicks = 0;
			}
		} else idleTicks = 0;
		if (looped(feet.asLong()) && safeToCancel()) {
			status = "repeated route without reaching the destination";
			reset();
			return Pathing.Nav.NO_ROUTE;
		}
		readPath();
		steer.externalNavigation = true;
		status = pathing.hasPath() ? "Baritone: " + movementName() : "Baritone: planning";
		return pathing.hasPath() ? Pathing.Nav.WALKING : Pathing.Nav.PLANNING;
	}

	Pathing.Nav place(PathMove.Ctx ctx, Bot.Steer steer, BlockPos where, Set<Block> mayBreak) {
		if (Bot.fullPlacementSupport(ctx.level(), where)) { reset(); return Pathing.Nav.ARRIVED; }
		// Baritone's single-block liquid schematic normally aims for the square above it.
		// That square may contain the block we are protecting. Approach a clickable face instead.
		if (!building && placementAim(ctx, new Vec3(ctx.player().getX(), ctx.player().getY()
				+ ctx.player().getEyeHeight(net.minecraft.world.entity.Pose.CROUCHING), ctx.player().getZ()), where) == null) {
			Pathing.Nav approach = tick(ctx, steer, where, (x, y, z) -> placementAim(ctx,
					new Vec3(x + 0.5, y + 1.27, z + 0.5), where) != null, mayBreak, true);
			if (approach != Pathing.Nav.ARRIVED) return approach;
		}
		if (owner != this || retiring || !building || !where.equals(destination)) {
			if (finishBeforeHandoff(steer)) return Pathing.Nav.WALKING;
			int slot = Bot.buildingSlot(ctx.player(), cfg);
			if (slot < 0) { status = "no expendable blocks for drop protection"; return Pathing.Nav.NO_ROUTE; }
			ItemStack stack = ctx.player().getInventory().getItem(slot);
			if (!(stack.getItem() instanceof BlockItem block)) return Pathing.Nav.NO_ROUTE;
			acquire(ctx, mayBreak, true);
			destination = where.immutable();
			building = true;
			engine.getBuilderProcess().build("Protect mining drops", new FillSchematic(1, 1, 1, block.getBlock().defaultBlockState()), where);
		}
		ticks++;
		if (ticks % 20 == 0) configure(ctx, mayBreak, true);
		String stalled = checkProgress(ctx);
		if (stalled != null && settled(ctx)) {
			engine.getPathingBehavior().forceCancel();
			status = stalled;
			reset();
			steer.clear();
			return Pathing.Nav.NO_ROUTE;
		}
		if (ticks > cfg.prepareSiteSec * 20 || (ticks > 20 && !engine.getBuilderProcess().isActive())) {
			status = "cannot build the required protection";
			reset();
			return Pathing.Nav.NO_ROUTE;
		}
		steer.externalNavigation = true;
		readPath();
		status = "Baritone: protecting " + where.toShortString();
		return Pathing.Nav.WALKING;
	}

	static Vec3 placementAim(PathMove.Ctx ctx, Vec3 eyes, BlockPos where) {
		for (net.minecraft.core.Direction side : net.minecraft.core.Direction.values()) {
			BlockPos against = where.relative(side);
			if (ctx.level().getBlockState(against).getShape(ctx.level(), against).isEmpty()) continue;
			Vec3 aim = Bot.placePoint(ctx.mc(), where, side);
			if (eyes.distanceToSqr(aim) > Math.pow(ctx.player().blockInteractionRange() - 0.15, 2)) continue;
			var hit = ctx.level().clip(new net.minecraft.world.level.ClipContext(eyes, aim,
					net.minecraft.world.level.ClipContext.Block.OUTLINE, net.minecraft.world.level.ClipContext.Fluid.NONE, ctx.player()));
			if (hit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK && hit.getBlockPos().equals(against)
					&& hit.getDirection() == side.getOpposite()) return aim;
		}
		return null;
	}

	private void acquire(PathMove.Ctx ctx, Set<Block> mayBreak, boolean edits) {
		if (owner != null) owner.reset();
		engine = BaritoneAPI.getProvider().getPrimaryBaritone();
		owner = this;
		retiring = false;
		cameraSynced = false;
		pace.reset();
		ticks = idleTicks = failures = 0;
		progress.reset();
		observedBreaking = null;
		handoffAnchor = null;
		handoffTicks = 0;
		visits.clear();
		lastCell = Long.MIN_VALUE;
		configure(ctx, mayBreak, edits);
	}

	private <T> void set(Settings.Setting<T> setting, T value) {
		saved.putIfAbsent(setting, setting.value);
		assigned.put(setting, value);
		setting.value = value;
	}

	private void configure(PathMove.Ctx ctx, Set<Block> mayBreak, boolean edits) {
		Settings s = BaritoneAPI.getSettings();
		set(s.allowBreak, edits && cfg.pathMine && mayBreak == null);
		set(s.allowBreakAnyway, edits && cfg.pathMine && mayBreak != null ? new ArrayList<>(mayBreak) : new ArrayList<>());
		set(s.allowPlace, edits && cfg.pathBridge && Bot.buildingBlockCount(ctx.player(), cfg) > cfg.bridgeKeepBlocks);
		set(s.allowInventory, false); // our slot-aware restocker handles this without touching protected slots
		set(s.allowSprint, cfg.destroySprint);
		set(s.allowParkour, cfg.baritoneParkour);
		set(s.allowParkourPlace, cfg.baritoneParkourPlace && edits && cfg.pathBridge);
		set(s.allowVines, cfg.baritoneVines);
		set(s.allowDownward, true);
		set(s.allowWaterBucketFall, cfg.baritoneWaterBucketFalls);
		set(s.maxFallHeightNoWater, cfg.pathMaxFall);
		set(s.strictLiquidCheck, true);
		set(s.avoidUpdatingFallingBlocks, true);
		set(s.allowWalkOnBottomSlab, true);
		set(s.assumeWalkOnWater, false);
		set(s.assumeWalkOnLava, false);
		set(s.allowDiagonalDescend, false);
		set(s.allowDiagonalAscend, false);
		set(s.freeLook, false);
		set(s.smoothLook, false); // our finite turn filter also covers block interactions and jumps
		set(s.elytraSmoothLook, false);
		set(s.randomLooking113, 0.0);
		set(s.randomLooking, cfg.baritoneAimVariation);
		set(s.remainWithExistingLookDirection, false);
		set(s.blockPlacementPenalty, cfg.pathPlaceCost * PathFinder.TICKS_PER_BLOCK);
		set(s.blockBreakAdditionalPenalty, (double) cfg.pathMineCost);
		set(s.blockReachDistance, (float) ctx.player().blockInteractionRange());
		set(s.movementTimeoutTicks, Math.max(40, (int) (cfg.baritoneNoProgressSec * 20)));
		set(s.primaryTimeoutMS, 500L);
		set(s.failureTimeoutMS, 2000L);
		set(s.disconnectOnArrival, false);
		set(s.notificationOnPathComplete, false);
		set(s.renderPath, false);
		set(s.renderGoal, false);
		set(s.skipFailedLayers, false);
		set(s.buildRepeat, new net.minecraft.core.Vec3i(0, 0, 0));
		List<Item> supplies = new ArrayList<>();
		for (int i = 0; i < 9; i++) {
			ItemStack stack = ctx.player().getInventory().getItem(i);
			if (!cfg.slotProtected(i) && Bot.usableBuildingStack(stack, cfg)) supplies.add(stack.getItem());
		}
		set(s.acceptableThrowawayItems, supplies);
		Set<Block> denied = new HashSet<>();
		Set<String> exclusions = BlockTargets.normalise(cfg.destroyExclude);
		for (Block block : BuiltInRegistries.BLOCK) {
			String id = BuiltInRegistries.BLOCK.getKey(block).toString();
			String path = id.substring(id.indexOf(':') + 1);
			if (BlockTargets.neverBreak(path) || exclusions.contains(id) || exclusions.contains(path)) denied.add(block);
		}
		set(s.blocksToDisallowBreaking, new ArrayList<>(denied));
	}

	/** Freeze all world-dependent predicates on the client thread before Baritone's worker sees them. */
	static Goal snapshotGoal(PathMove.Ctx ctx, BlockPos centre, PathFinder.Goal test) {
		Set<BlockPos> cells = new HashSet<>();
		int radius = Math.max(6, (int) Math.ceil(ctx.player().blockInteractionRange()) + 1);
		for (int x = centre.getX() - radius; x <= centre.getX() + radius; x++) {
			for (int z = centre.getZ() - radius; z <= centre.getZ() + radius; z++) {
				if (!ctx.level().hasChunk(x >> 4, z >> 4)) continue;
				for (int y = Math.max(ctx.level().getMinY(), centre.getY() - radius); y <= Math.min(ctx.level().getMaxY() - 2, centre.getY() + radius); y++) {
					if (test.reached(x, y, z)) cells.add(new BlockPos(x, y, z));
				}
			}
		}
		return cells.isEmpty() ? null : new CellGoal(cells);
	}

	static final class CellGoal implements Goal {
		final Set<Long> cells;
		final int minX, minY, minZ, maxX, maxY, maxZ;
		CellGoal(Set<BlockPos> positions) {
			cells = new HashSet<>();
			int lx = Integer.MAX_VALUE, ly = lx, lz = lx, hx = Integer.MIN_VALUE, hy = hx, hz = hx;
			for (BlockPos p : positions) {
				cells.add(p.asLong()); lx = Math.min(lx, p.getX()); ly = Math.min(ly, p.getY()); lz = Math.min(lz, p.getZ());
				hx = Math.max(hx, p.getX()); hy = Math.max(hy, p.getY()); hz = Math.max(hz, p.getZ());
			}
			minX = lx; minY = ly; minZ = lz; maxX = hx; maxY = hy; maxZ = hz;
		}
		public boolean isInGoal(int x, int y, int z) { return cells.contains(BlockPos.asLong(x, y, z)); }
		public double heuristic(int x, int y, int z) {
			double dx = Math.max(minX - x, Math.max(0, x - maxX)), dz = Math.max(minZ - z, Math.max(0, z - maxZ));
			// Include Baritone's elevation cost. Ignoring Y gave every
			// floor above/below a working position zero remaining cost and wasted searches.
			return Math.sqrt(dx * dx + dz * dz) * 3.5
					+ baritone.api.pathing.goals.GoalYLevel.calculate(Math.clamp(y, minY, maxY), y);
		}
	}

	private boolean looped(long cell) {
		if (cell == lastCell) return false;
		lastCell = cell;
		if (visits.size() > 4096) visits.clear();
		return visits.merge(cell, 1, Integer::sum) > 6;
	}

	private static boolean settled(PathMove.Ctx ctx) { return ctx.player().onGround() || ctx.player().isInWater() || ctx.player().onClimbable(); }

	private String checkProgress(PathMove.Ctx ctx) {
		boolean changed = observedBreaking != null && ctx.level().getBlockState(observedBreaking).isAir();
		BlockPos breaking = breakingBlock();
		if (changed || breaking != null) observedBreaking = breaking;
		return progress.update(ctx.player().position(), engine.getPathingBehavior().hasPath(), breaking != null, changed, cfg);
	}

	private IMovement currentMove() {
		if (engine == null || engine.getPathingBehavior().getCurrent() == null) return null;
		var current = engine.getPathingBehavior().getCurrent();
		var movements = current.getPath().movements();
		return current.getPosition() < movements.size() ? movements.get(Math.max(0, current.getPosition())) : null;
	}
	private boolean safeToCancel() {
		var player = engine == null ? null : engine.getPlayerContext().player();
		if (player != null && !player.onGround() && !player.isInWater() && !player.onClimbable()) return false;
		IMovement move = currentMove(); return move == null || move.safeToCancel();
	}
	private static boolean finishBeforeHandoff(Bot.Steer steer) {
		if (owner == null || owner.canHandOff()) return false;
		owner.engine.getPathingBehavior().cancelEverything();
		owner.retiring = true;
		steer.externalNavigation = true;
		return true;
	}

	/** A stale critical-move flag must not retain the keys forever after landing. */
	private boolean canHandOff() {
		if (safeToCancel()) { handoffAnchor = null; handoffTicks = 0; return true; }
		var player = engine.getPlayerContext().player();
		if (player == null) return false;
		if (handoffSampleTick != player.tickCount) {
			handoffSampleTick = player.tickCount;
			if (handoffAnchor == null || player.position().distanceToSqr(handoffAnchor) >= 0.04) {
				handoffAnchor = player.position(); handoffTicks = 0;
			} else handoffTicks++;
		}
		if (handoffTicks < 40 || (!player.onGround() && !player.isInWater() && !player.onClimbable())) return false;
		engine.getPathingBehavior().forceCancel();
		return true;
	}
	private String movementName() { IMovement move = currentMove(); return move == null ? "travelling" : move.getClass().getSimpleName().replace("Movement", ""); }
	private void readPath() {
		var current = engine.getPathingBehavior().getCurrent();
		if (current == null) return;
		step = current.getPosition(); length = current.getPath().length();
		nodes = current.getPath().getNumNodesConsidered(); cost = current.getPath().ticksRemainingFrom(step);
	}

	public BlockPos breakingBlock() {
		if (owner != this || !engine.getInputOverrideHandler().isInputForcedDown(Input.CLICK_LEFT)) return null;
		return Bot.hitBlock(engine.getPlayerContext().minecraft());
	}

	boolean walking() { return owner == this && engine.getPathingBehavior().hasPath(); }
	PathFinder.Kind currentKind() {
		if (owner != this) return PathFinder.Kind.START;
		if (breakingBlock() != null) return PathFinder.Kind.MINE;
		if (currentMove() instanceof baritone.pathing.movement.movements.MovementPillar) return PathFinder.Kind.PILLAR;
		if (engine.getInputOverrideHandler().isInputForcedDown(Input.CLICK_RIGHT)) return PathFinder.Kind.BRIDGE;
		return walking() ? PathFinder.Kind.WALK : PathFinder.Kind.START;
	}

	/** Give Baritone time to finish an uninterruptible landing before another task takes the keys. */
	public static boolean yieldFor(Bot.Steer steer) {
		if (owner == null || (steer != null && steer.externalNavigation)) return false;
		if (!owner.canHandOff()) return true;
		owner.reset();
		return false;
	}

	public static void stopAll() { if (owner != null) owner.reset(); }

	@SuppressWarnings({"rawtypes", "unchecked"})
	void reset() {
		if (owner == this) {
			engine.getPathingBehavior().cancelEverything();
			if (!safeToCancel()) { retiring = true; destination = null; return; }
			engine.getInputOverrideHandler().clearAllKeys();
			for (var entry : saved.entrySet()) {
				Settings.Setting setting = entry.getKey();
				if (Objects.equals(setting.value, assigned.get(setting))) setting.value = entry.getValue();
			}
			owner = null;
			cameraSynced = false;
		}
		saved.clear(); assigned.clear(); destination = null; goal = null; building = false;
	}

	public static void main(String[] args) {
		MineSafety.View corner = (x, y, z) -> x == 1121 && y == 56 && z == 134 ? MineSafety.BURNING : MineSafety.AIR;
		assert !clearLanding(corner, 1121, 56, 133) : "basalt lava corner accepted as a landing";
		assert clearLanding(corner, 1121, 58, 133) : "contained lava below a landing blocked the route";
		assert clearLanding((x, y, z) -> MineSafety.AIR, 0, 0, 0);
		NavigationWatchdog.selfCheck();
		CellGoal goal = new CellGoal(Set.of(new BlockPos(-2, 3, 4), new BlockPos(7, -5, 0)));
		assert goal.isInGoal(-2, 3, 4) && goal.isInGoal(7, -5, 0);
		assert !goal.isInGoal(-2, 4, 4) : "lower-floor goal accepted the wrong elevation";
		assert goal.heuristic(-2, 3, 4) == 0 && goal.heuristic(12, 3, 4) > 0;
		CellGoal floor = new CellGoal(Set.of(new BlockPos(0, 4, 0)));
		assert floor.heuristic(0, 4, 0) == 0;
		assert floor.heuristic(0, 1, 0) > floor.heuristic(0, 3, 0)
				&& floor.heuristic(0, 8, 0) > floor.heuristic(0, 5, 0)
				: "route search ignored progress between floors";
		Config cfg = new Config();
		Pace pace = new Pace();
		cfg.baritoneSprintChance = 0;
		assert pace.sprint(cfg, true, 0) : "default changed Baritone's pace";
		cfg.baritoneRandomisePace = true;
		cfg.baritonePaceMinSec = cfg.baritonePaceMaxSec = 1;
		assert !pace.sprint(cfg, true, 0);
		cfg.baritoneSprintChance = 1;
		assert !pace.sprint(cfg, true, 19) : "pace was resampled mid-segment";
		assert pace.sprint(cfg, true, 20) : "pace did not expire";
		cfg.baritoneSprintChance = 0;
		pace.reset();
		assert !pace.sprint(cfg, true, 21);
		assert pace.sprint(cfg, false, 22) : "random pace interfered with a critical move";
		cfg.baritoneSprintChance = 1;
		assert pace.sprint(cfg, true, 23) : "critical move did not reset the segment";
		cfg.baritoneSprintChance = Double.NaN;
		cfg.baritonePaceMinSec = Double.POSITIVE_INFINITY;
		cfg.baritonePaceMaxSec = -1;
		cfg.clampAll();
		assert cfg.baritoneSprintChance == 0.8 && cfg.baritonePaceMinSec == 2 && cfg.baritonePaceMaxSec == 2;
		Config copied = cfg.copy();
		assert copied.baritoneRandomisePace && copied.baritonePaceMaxSec == 2 : "profile lost pace settings";
		Rotation previous = new Rotation(179, 0), desired = new Rotation(-120, 60);
		for (boolean interaction : new boolean[]{false, true}) {
			for (double strength : new double[]{0, 0.35, 0.8, 1}) {
				cfg.baritoneTurnSmoothing = strength;
				owner = new NativeNavigation(cfg);
				Rotation actual = smoothRotation(previous, desired, interaction);
				assert Math.abs(Human.wrap(actual.getYaw() - previous.getYaw())) <= cfg.baritoneTurnRate + 0.001
						: "interaction bypassed the turn cap";
				for (int i = 0; i < 40; i++) actual = smoothRotation(actual, desired, interaction);
				assert Math.abs(Human.wrap(actual.getYaw() - desired.getYaw())) < 0.01 : "smoothing never converged";
			}
		}
		owner = null;
		System.out.println("NativeNavigation self-check passed");
	}
}
