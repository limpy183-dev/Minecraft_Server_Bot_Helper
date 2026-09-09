package com.damia.movrand;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * The hands. Everything the task layer needs to actually touch the world, and nothing
 * about what it should be touching.
 *
 * <p>Two decisions run through all of this. The first is that nothing here sends a packet:
 * mining holds the attack key and placing holds the use key, exactly as a person does, and
 * vanilla does the rest — the progress, the swing, the packets, the cooldown. Reimplementing
 * that would be more code producing a stream a server can tell apart from a player's.
 *
 * <p>The second is that nothing here sets a rotation either. It reports where it would like
 * to look and the controller feeds that through {@link Human}, so the wobble, the easing and
 * the filter apply to a bot mining a wall exactly as they do to one walking a field.
 */
public final class Bot {

	/**
	 * One tick of intent: where to look, where to walk, what to hold down.
	 *
	 * <p>Looking and walking are two separate statements, and that is the whole point. The
	 * camera in this mod is filtered on purpose — it takes the better part of half a second to
	 * swing ninety degrees, because that is what a hand on a mouse does and a servo does not.
	 * Tying the movement keys to it means every corner is taken by walking into the wall for
	 * eight ticks first, and a route through a building is nothing but corners. So the task
	 * layer says where to go, the camera takes its time getting there, and
	 * {@link Bot#keysFor} works out which keys carry the player that way from wherever the
	 * view happens to be pointed right now.
	 */
	public static final class Steer {
		public double yaw, pitch;
		public boolean hasLook;
		/** Where to walk, as a world heading. Ignored unless {@link #hasMove}. */
		public double moveYaw;
		public boolean hasMove;
		public boolean forward, back, left, right;
		public boolean jump, sneak, sprint;
		public boolean attack, use;
		/** Baritone owns movement, interaction and rotation for this tick. */
		public boolean externalNavigation;
		/** Precise block intents, rechecked after the final smoothed rotation is applied. */
		public BlockPos attackTarget, useTarget, placeTarget;
		/** Required clicked face for placement, or null to allow any face. */
		public Direction placeFace;
		/**
		 * Aiming at one specific block rather than walking somewhere.
		 *
		 * <p>Two things follow from it, and they are two halves of one point. The camera gets
		 * the tighter filter, because a crosshair that takes half a second to settle is fine
		 * when the destination is a chunk and useless when it is one face of one block. And
		 * the crosshair is re-cast from the rotation this steer actually produced, rather than
		 * from wherever the last rendered frame happened to be looking — without which the
		 * decision to press the mouse is made against a view that no longer exists, which is
		 * the difference between mining a wall and standing in front of one.
		 */
		public boolean precise;
		/** Shown on the HUD and in the status line. */
		public String status = "";

		public void lookAt(double yawDeg, double pitchDeg) {
			yaw = yawDeg;
			pitch = Math.max(-90, Math.min(90, pitchDeg));
			hasLook = true;
		}

		/** Walk this way, whatever the camera is doing. */
		public void moveTowards(double yawDeg) {
			moveYaw = yawDeg;
			hasMove = true;
		}

		public void attackAt(BlockPos pos) {
			attackTarget = pos;
		}

		public void useAt(BlockPos pos) {
			useTarget = pos;
		}

		public void placeInto(BlockPos pos) {
			placeInto(pos, null);
		}

		public void placeInto(BlockPos pos, Direction clickedFace) {
			placeTarget = pos;
			placeFace = clickedFace;
		}

		public void clear() {
			externalNavigation = false;
			hasLook = false;
			hasMove = false;
			precise = false;
			attackTarget = useTarget = placeTarget = null;
			placeFace = null;
			forward = back = left = right = jump = sneak = sprint = attack = use = false;
		}
	}

	/**
	 * The movement keys that carry a player facing {@code facingYaw} towards {@code moveYaw}.
	 *
	 * <p>Minecraft turns the two impulses into world motion by rotating them by the player's
	 * yaw, so going the other way is the same rotation backwards: with {@code d = facing -
	 * move}, the forward impulse is {@code cos d} and the left impulse is {@code sin d}. The
	 * keys are on or off rather than analogue, so each axis is pressed once its component is
	 * past {@code sin 22.5°} — which cuts the circle into the eight directions a keyboard can
	 * actually express, and never leaves a heading with no keys at all.
	 *
	 * @return forward, back, left, right
	 */
	public static boolean[] keysFor(double facingYaw, double moveYaw) {
		return keysFor(facingYaw, moveYaw, null);
	}

	/**
	 * The same, with the keys held last tick, so a heading sitting on a boundary does not
	 * chatter.
	 *
	 * <p>The camera has a deliberate wobble on it of a degree or two. A heading that happens
	 * to land near where one of the eight keyboard directions gives way to the next then flips
	 * between them every single tick — strafe on, strafe off, on, off — and what comes out is
	 * a bot that jitters down a corridor at half speed instead of walking down it. Baritone
	 * never meets this because it snaps the camera exactly where it wants it; a mod whose
	 * whole point is that it does not snap has to hold the choice steady itself.
	 *
	 * @param previous the last answer, in the same order, or null for no opinion
	 */
	public static boolean[] keysFor(double facingYaw, double moveYaw, boolean[] previous) {
		double d = Math.toRadians(Human.wrap(facingYaw - moveYaw));
		double forward = Math.cos(d);
		double left = Math.sin(d);
		return new boolean[]{
				latch(forward, previous != null && previous[0]),
				latch(-forward, previous != null && previous[1]),
				latch(left, previous != null && previous[2]),
				latch(-left, previous != null && previous[3])};
	}

	/**
	 * A key already down stays down a little past where it would have come on.
	 *
	 * <p>One-sided on purpose. Raising the threshold as well would widen the worst case the
	 * eight directions can be wrong by, which is the one thing this must not cost: a key comes
	 * on exactly where it always did, and only lets go late.
	 */
	private static boolean latch(double value, boolean wasDown) {
		return value > (wasDown ? OCTANT - SLACK : OCTANT);
	}

	/** sin 22.5°, which is where one of the eight keyboard directions gives way to the next. */
	private static final double OCTANT = 0.3827;
	/** About three and a half degrees of boundary, which is wider than the wobble ever is. */
	private static final double SLACK = 0.06;

	private Bot() {
	}

	// ---------------------------------------------------------------- aiming

	/** Yaw and pitch that point the player's eyes at a world position. */
	public static double[] aimAt(LocalPlayer player, Vec3 target) {
		double dx = target.x - player.getX();
		double dy = target.y - player.getEyeY();
		double dz = target.z - player.getZ();
		double flat = Math.sqrt(dx * dx + dz * dz);
		// yaw 0 faces +Z and 90 faces -X, which is why x and z come in the order they do
		return new double[]{Math.toDegrees(Math.atan2(-dx, dz)), Math.toDegrees(-Math.atan2(dy, flat))};
	}

	/**
	 * The face of a block the eyes can actually see, preferring one already chosen.
	 *
	 * <p>Aiming at the centre of a block works right up until the block is in a wall, where
	 * the ray stops at the wall and the crosshair never lands on the target. So each face is
	 * tried and one with a clear line is taken — but which one matters, for two reasons that
	 * are both about mining rather than about geometry.
	 *
	 * <p>The first is that the choice has to be sticky. Vanilla throws away every bit of
	 * mining progress on any tick the crosshair is on a different block, so a face re-picked
	 * from scratch each tick, hopping as the view wobbles, does not mine slowly — it mines
	 * never. A face that still has a clear line is kept.
	 *
	 * <p>The second is that a face seen edge-on is a bad face however close it is: it
	 * subtends almost no angle, so the smallest wobble slides off it. Nearest is the wrong
	 * ordering; most square-on is the right one.
	 *
	 * @param preferred the face used last tick, or null
	 * @return the face to aim at, or null when none of them can be seen
	 */
	public static Direction visibleFace(Minecraft mc, LocalPlayer player, BlockPos pos, Direction preferred) {
		if (mc.level == null) return null;
		AABB box = blockBox(mc, pos);
		Vec3 eyes = player.getEyePosition();
		if (preferred != null && seesFace(mc, player, eyes, pos, box, preferred)) return preferred;

		Direction best = null;
		double bestSeen = -1;
		for (Direction face : Direction.values()) {
			if (!seesFace(mc, player, eyes, pos, box, face)) continue;
			// How much of the face is actually presented to the eye: its area, foreshortened
			// by how side-on it is. Squareness alone is not enough - the edge of a redstone
			// dust is dead square-on from across the room and is a sixteenth of a block tall,
			// which is a target the view wobble can slide off. Area times squareness picks the
			// big flat top instead, which is the face a person would aim at anyway.
			double seen = faceVisibility(box, face, eyes);
			if (seen > bestSeen) {
				bestSeen = seen;
				best = face;
			}
		}
		return best;
	}

	/** How much of a face the eye actually sees: its area, foreshortened by how side-on it is. */
	static double faceVisibility(AABB box, Direction face, Vec3 eyes) {
		double square = -facePoint(box, face).subtract(eyes).normalize().dot(face.getUnitVec3());
		return square * faceArea(box, face);
	}

	/** The area of one face of a box: the two sides that are not along its normal. */
	private static double faceArea(AABB box, Direction face) {
		return switch (face.getAxis()) {
			case X -> box.getYsize() * box.getZsize();
			case Y -> box.getXsize() * box.getZsize();
			case Z -> box.getXsize() * box.getYsize();
		};
	}

	private static boolean seesFace(Minecraft mc, LocalPlayer player, Vec3 eyes, BlockPos pos,
	                                AABB box, Direction face) {
		Vec3 point = facePoint(box, face);
		// a face pointing away from the eyes is round the back of the block, so never visible
		if (point.subtract(eyes).dot(face.getUnitVec3()) > 0) return false;
		return clearLine(mc, player, eyes, point, pos);
	}

	/**
	 * The box a block actually occupies, in world coordinates.
	 *
	 * <p>Very little of what a base is made of fills its own cube. Redstone dust is a sixteenth
	 * of a block tall and sits on the floor; so do rails and pressure plates, and repeaters,
	 * levers, buttons, torches and hoppers are all their own odd shape. Aiming at the middle of
	 * the <em>cube such a block sits in</em> points at empty air the better part of a block
	 * above the block itself, the crosshair lands on whatever is behind it, and nothing is ever
	 * mined — which is exactly what "it says it is mining and it is not" looks like, and it is
	 * every default target this mod ships with.
	 */
	public static AABB blockBox(Minecraft mc, BlockPos pos) {
		if (mc.level == null) return new AABB(pos);
		// the outline shape, because that is the one the game's own crosshair pick uses
		VoxelShape shape = mc.level.getBlockState(pos).getShape(mc.level, pos);
		return shape.isEmpty() ? new AABB(pos) : shape.bounds().move(pos);
	}

	/**
	 * A point well inside one face of a box — 85% of the way out from the middle, rather than
	 * on the surface. A point sitting exactly on a face is a coin flip for the raycast that
	 * has to confirm it, and on a shape a sixteenth of a block thick there is no room to be
	 * casual about which side of the boundary a double lands on.
	 */
	public static Vec3 facePoint(AABB box, Direction face) {
		Vec3 n = face.getUnitVec3();
		double span = Math.abs(n.x) * box.getXsize()
				+ Math.abs(n.y) * box.getYsize()
				+ Math.abs(n.z) * box.getZsize();
		return box.getCenter().add(n.scale(span * 0.425));
	}

	public static Vec3 facePoint(Minecraft mc, BlockPos pos, Direction face) {
		return facePoint(blockBox(mc, pos), face);
	}

	/** The middle of what the block actually is, for when no face can be picked out. */
	public static Vec3 blockCentre(Minecraft mc, BlockPos pos) {
		return blockBox(mc, pos).getCenter();
	}

	/** The same choice with no memory, for callers that only want somewhere to point. */
	public static Vec3 aimPoint(Minecraft mc, LocalPlayer player, BlockPos pos) {
		Direction face = visibleFace(mc, player, pos, null);
		return face == null ? blockCentre(mc, pos) : facePoint(mc, pos, face);
	}

	/**
	 * Whether a ray from the eyes to this point stops at the block we meant.
	 *
	 * <p>A miss is not a clear line, whatever it looks like. It means the ray reached the
	 * point without touching anything — so the point is not on the block, and aiming there
	 * puts the crosshair through it into whatever is behind. Treating a miss as success is
	 * how an aim point floating in mid air gets confirmed as a good one.
	 */
	private static boolean clearLine(Minecraft mc, LocalPlayer player, Vec3 from, Vec3 to, BlockPos pos) {
		if (mc.level == null) return false;
		BlockHitResult hit = mc.level.clip(new ClipContext(from, to,
				ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
		return hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(pos);
	}

	/**
	 * The first block on the straight line from the eyes to {@code target}, when that turns
	 * out not to be the target itself.
	 *
	 * <p>Deliberately not "whatever the crosshair is on". The crosshair is wherever the camera
	 * has got to this tick, and early in a turn that is the floor — so aiming at a block
	 * behind a wall and then mining whatever the crosshair reports would dig a hole straight
	 * down. This is a question about the world rather than about the view, so it is asked of
	 * the world. Outlines rather than collision, to match what vanilla's own pick would land
	 * on: a redstone wire has no collision and still takes the crosshair.
	 *
	 * @return the block in the way, or null when the line is clear
	 */
	public static BlockPos obstruction(Minecraft mc, LocalPlayer player, BlockPos target) {
		if (mc.level == null) return null;
		// the middle of the block itself, not of the cube it sits in: a ray sent at the cube
		// centre of a redstone dust sails a half block over the top of it and reports whatever
		// is behind as being in the way, which is then dutifully mined
		BlockHitResult hit = mc.level.clip(new ClipContext(player.getEyePosition(),
				blockCentre(mc, target), ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
		if (hit.getType() != HitResult.Type.BLOCK) return null;
		return hit.getBlockPos().equals(target) ? null : hit.getBlockPos();
	}

	/** The block the crosshair is on right now, or null. */
	public static BlockPos hitBlock(Minecraft mc) {
		return mc.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK
				? hit.getBlockPos() : null;
	}

	/** Whether the crosshair is genuinely on this block right now. */
	public static boolean lookingAt(Minecraft mc, BlockPos pos) {
		return pos.equals(hitBlock(mc));
	}

	/**
	 * Whether {@code target} could be broken while standing at this block position.
	 *
	 * <p>Both halves matter and only one of them is distance. A search whose goal is "get
	 * within four blocks of it" will happily stop four blocks away through a wall, declare
	 * itself arrived, and hand back a position the job cannot work from — so the goal is
	 * "somewhere I could actually swing at it", which is a question about line of sight.
	 *
	 * @param aim the point on the block to sight at, from {@link #blockCentre}. Passed in
	 *            rather than worked out: this runs once per node the search expands, and
	 *            resolving the block's shape thousands of times over is work with one answer.
	 */
	public static boolean canWorkFrom(net.minecraft.client.multiplayer.ClientLevel level,
	                                  LocalPlayer player, int x, int y, int z,
	                                  BlockPos target, Vec3 aim, double reach) {
		Vec3 eyes = new Vec3(x + 0.5, y + player.getEyeHeight(), z + 0.5);
		if (aim.distanceToSqr(eyes) > reach * reach) return false;
		BlockHitResult hit = level.clip(new ClipContext(eyes, aim,
				ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
		return hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(target);
	}

	/** How far there is to fall from directly under this block, capped at {@code limit}. */
	public static int dropUnder(net.minecraft.client.multiplayer.ClientLevel level, BlockPos pos, int limit) {
		for (int d = 1; d <= limit; d++) {
			BlockPos below = pos.below(d);
			if (!level.getBlockState(below).getCollisionShape(level, below).isEmpty()) return d - 1;
		}
		return limit;
	}

	/**
	 * Within arm's length — the server's own limit, not a guessed one, and measured to the
	 * block rather than to the middle of the cube around it. Half a block of difference is
	 * the whole margin on something lying on the floor at the far edge of reach.
	 */
	public static boolean inReach(Minecraft mc, LocalPlayer player, BlockPos pos) {
		double range = player.blockInteractionRange();
		return blockCentre(mc, pos).distanceToSqr(player.getEyePosition()) <= range * range;
	}

	// ---------------------------------------------------------------- hotbar

	/**
	 * The hotbar slot that breaks this block fastest.
	 *
	 * <p>Hotbar only, and on purpose: pulling a pickaxe up from the backpack means faking
	 * container clicks mid-fight, and "keep a pickaxe on the bar" is a rule a person follows
	 * anyway. A slot the user has protected is still usable — protection is about what gets
	 * sold or thrown away, not about what may be held.
	 */
	/**
	 * Roughly how many ticks this block takes to break with the best thing on the hotbar.
	 *
	 * <p>Vanilla's own arithmetic, minus the enchantments, potions and standing-in-water
	 * penalties: it is a number the route search compares against walking, not a countdown
	 * shown to anyone, and being out by a fifth on a block nobody is going to break anyway
	 * costs nothing. What it has to get right is the ratio - obsidian against cobblestone is
	 * fifty to one, and a search told they cost the same digs through the obsidian.
	 *
	 * @return ticks, or {@link Double#MAX_VALUE} for a block that cannot be broken at all
	 */
	public static double breakTicks(LocalPlayer player, net.minecraft.world.level.BlockGetter level,
	                                BlockPos pos, BlockState state) {
		float hardness;
		try {
			hardness = state.getDestroySpeed(level, pos);
		} catch (Exception | LinkageError e) {
			return Double.MAX_VALUE;
		}
		if (hardness < 0) return Double.MAX_VALUE;
		if (hardness == 0) return 1;
		ItemStack stack = player.getInventory().getItem(bestToolSlot(player, state));
		float speed = stack.isEmpty() ? 1 : stack.getDestroySpeed(state);
		boolean correct = !stack.isEmpty() && stack.isCorrectToolForDrops(state);
		// the same shape as BlockState#getDestroyProgress: 30 ticks' worth with the right
		// tool, 100 without, scaled by how fast the tool is against how hard the block is
		double perTick = speed / hardness / (correct ? 30.0 : 100.0);
		return perTick <= 0 ? Double.MAX_VALUE : Math.max(1, 1 / perTick);
	}

	public static int bestToolSlot(LocalPlayer player, BlockState state) {
		Inventory inv = player.getInventory();
		int best = inv.getSelectedSlot();
		float bestSpeed = -1;
		for (int slot = 0; slot < Inventory.SELECTION_SIZE; slot++) {
			ItemStack stack = inv.getItem(slot);
			float speed = stack.isEmpty() ? 1 : stack.getDestroySpeed(state);
			// a tool that gets the drop beats a slightly faster one that does not
			if (!stack.isEmpty() && stack.isCorrectToolForDrops(state)) speed *= 4;
			if (speed > bestSpeed) {
				bestSpeed = speed;
				best = slot;
			}
		}
		return best;
	}

	/** A hotbar slot holding something worth putting on the floor, or -1. */
	public static int buildingSlot(LocalPlayer player, Config cfg) {
		Inventory inv = player.getInventory();
		if (buildingBlockCount(player, cfg) <= Math.max(0, cfg.bridgeKeepBlocks)) return -1;
		for (int slot = 0; slot < Inventory.SELECTION_SIZE; slot++) {
			if (cfg.slotProtected(slot)) continue; // a protected slot is not scaffolding
			ItemStack stack = inv.getItem(slot);
			if (!usableBuildingStack(stack, cfg)) continue;
			return slot;
		}
		return -1;
	}

	/** One random point per block/face, retained for the entire swing. */
	public static final class MiningAim {
		private BlockPos block;
		private Direction face;
		private Vec3 point;
		private double spread = -1;

		public void reset() { block = null; point = null; }

		public Vec3 point(Minecraft mc, LocalPlayer player, BlockPos pos, Direction side, Config cfg) {
			double requestedSpread = cfg.miningAimSpread();
			if (pos.equals(block) && side == face && spread == requestedSpread && point != null
					&& canHitPoint(mc, player, pos, point)) return point;
			block = pos;
			face = side;
			spread = requestedSpread;
			AABB box = blockBox(mc, pos);
			point = offsetFacePoint(box, side, Rng.range(-spread, spread), Rng.range(-spread, spread));
			if (canHitPoint(mc, player, pos, point)) return point;
			point = side == null ? box.getCenter() : facePoint(box, side);
			if (clearLine(mc, player, player.getEyePosition(), point, pos)) return point;
			// Concave outlines (hoppers, stairs, fences) may have empty space at their bounds' centre.
			int checked = 0;
			for (AABB part : mc.level.getBlockState(pos).getShape(mc.level, pos).toAabbs()) {
				if (++checked > 24) break;
				Vec3 candidate = part.move(pos).getCenter();
				if (clearLine(mc, player, player.getEyePosition(), candidate, pos)) {
					point = candidate;
					break;
				}
			}
			return point;
		}

		private static boolean canHitPoint(Minecraft mc, LocalPlayer player, BlockPos pos, Vec3 point) {
			if (!clearLine(mc, player, player.getEyePosition(), point, pos)) return false;
			// A clear ray to an offset can still exceed reach. Check the actual camera ray too.
			double[] look = aimAt(player, point);
			return rotationHits(mc, player, pos, look[0], look[1]);
		}
	}

	static Vec3 offsetFacePoint(AABB box, Direction face, double u, double v) {
		if (face == null) return box.getCenter();
		Vec3 centre = facePoint(box, face);
		return switch (face.getAxis()) {
			case X -> centre.add(0, u * box.getYsize(), v * box.getZsize());
			case Y -> centre.add(u * box.getXsize(), 0, v * box.getZsize());
			case Z -> centre.add(u * box.getXsize(), v * box.getYsize(), 0);
		};
	}

	/** Test a proposed camera rotation without moving the camera or changing the crosshair. */
	public static boolean rotationHits(Minecraft mc, LocalPlayer player, BlockPos pos, double yaw, double pitch) {
		Vec3 end = player.getEyePosition().add(Vec3.directionFromRotation((float) pitch, (float) yaw)
				.scale(player.blockInteractionRange()));
		return clearLine(mc, player, player.getEyePosition(), end, pos);
	}

	/** Whether a stack is safe enough to use as route scaffolding. */
	public static boolean usableBuildingStack(ItemStack stack, Config cfg) {
		if (stack.isEmpty() || Storage.reserved(cfg, stack) || !(stack.getItem() instanceof BlockItem item)) return false;
		BlockState placed = item.getBlock().defaultBlockState();
		// Falling blocks do not leave a floor where the planner paid for one.
		if (item.getBlock() instanceof net.minecraft.world.level.block.FallingBlock) return false;
		if (placed.isAir() || !placed.getFluidState().isEmpty()) return false;
		if (!placed.isCollisionShapeFullBlock(net.minecraft.world.level.EmptyBlockGetter.INSTANCE, BlockPos.ZERO)) return false;
		if (cfg.protectMiningDrops && placed.ignitedByLava()) return false;
		return cfg.isBuildingBlock(idOf(item));
	}

	private static String idOf(BlockItem item) {
		var id = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(item.getBlock());
		return id == null ? "" : id.getPath();
	}

	/** How many blocks are on the hotbar for bridging and covering. */
	public static int buildingBlockCount(LocalPlayer player, Config cfg) {
		Inventory inv = player.getInventory();
		int total = 0;
		for (int slot = 0; slot < Inventory.SELECTION_SIZE; slot++) {
			if (cfg.slotProtected(slot)) continue;
			ItemStack stack = inv.getItem(slot);
			if (usableBuildingStack(stack, cfg)) total += stack.getCount();
		}
		return total;
	}

	// ------------------------------------------------------------- placement

	/**
	 * A direction from {@code target} to a block whose face can be clicked to fill it,
	 * keeping one already chosen while it still works.
	 *
	 * <p>Sticky for the same reason aiming at a block to break it is. The camera is filtered,
	 * so a support block re-chosen from scratch every tick is a crosshair chasing a target
	 * that keeps moving, and "is the crosshair on the right face yet" never once comes true.
	 *
	 * <p>Nearest first, rather than whatever order the enum happens to be in. Picking the
	 * first face that passes means picking DOWN or UP because those are declared first — so
	 * filling a hole in the floor gets aimed at the ceiling above it, which is a real face
	 * that really does place into the right square and looks completely deranged.
	 *
	 * @return the direction to the support block, or null when there is nothing to place from
	 */
	public static Direction placeAgainst(Minecraft mc, LocalPlayer player, BlockPos target, Direction preferred) {
		return placeAgainst(mc, player, target, preferred, player.getEyePosition());
	}

	static Direction placeAgainst(Minecraft mc, LocalPlayer player, BlockPos target, Direction preferred, Vec3 eyes) {
		if (mc.level == null) return null;
		if (preferred != null && supports(mc, player, target, preferred, eyes)) return preferred;

		Direction best = null;
		double bestDist = Double.MAX_VALUE;
		for (Direction face : Direction.values()) {
			if (!supports(mc, player, target, face, eyes)) continue;
			double d = placePoint(mc, target, face).distanceToSqr(eyes);
			if (d < bestDist) {
				bestDist = d;
				best = face;
			}
		}
		return best;
	}

	/**
	 * Whether a block can actually be put into {@code target} from here by clicking the
	 * neighbour in this direction.
	 *
	 * <p>The last two checks are the whole of it, and the second one used to be missing. The
	 * game puts a placed block at {@code hit.getBlockPos().relative(hit.getDirection())}, so
	 * reaching the support block is not enough: the ray has to enter it <em>through the face
	 * that points at the target</em>. Without that this returns a direction whose placement
	 * can never happen. The bot then aims at the block beside the lava for as long as you let
	 * it, the check that decides whether to press use is never once true, and nothing is
	 * placed - which is exactly what it looks like from the outside.
	 *
	 * <p>It was reliably the wrong face, too, because the caller takes the nearest support.
	 * For lava at your feet that is the block you are standing on, and the side of it facing
	 * the lava is the one side you cannot see from up there: the ray goes in through the top.
	 */
	private static boolean supports(Minecraft mc, LocalPlayer player, BlockPos target, Direction face, Vec3 eyes) {
		BlockPos against = target.relative(face);
		BlockState state = mc.level.getBlockState(against);
		if (state.isAir() || !state.getFluidState().isEmpty()) return false;
		// Click support and a floor to stand on are different requirements. Vanilla's
		// placement context below decides whether a thin block can anchor this placement.
		if (state.getShape(mc.level, against).isEmpty()) return false;
		// cheap and exact: a face you are behind is a face you cannot click, no ray needed
		if (!facesTheEye(blockBox(mc, against), face.getOpposite(), eyes)) {
			return false;
		}
		Vec3 aim = placePoint(mc, target, face);
		return eyes.distanceToSqr(aim) <= player.blockInteractionRange() * player.blockInteractionRange()
				&& placesInto(mc, player, target, against, aim, eyes)
				&& placementWouldBeAccepted(mc, player, target, against, face, aim);
	}

	/**
	 * A full, upper support face: the conservative support a player can reliably build a column
	 * from. This intentionally rejects partial collision shapes even where vanilla allows a few
	 * decorative placements; route scaffolding must leave a dependable block under the feet.
	 */
	public static boolean fullPlacementSupport(net.minecraft.world.level.BlockGetter level, BlockPos pos) {
		BlockState state = level.getBlockState(pos);
		if (state.isAir() || !state.getFluidState().isEmpty()) return false;
		return state.isCollisionShapeFullBlock(level, pos)
				&& state.isFaceSturdy(level, pos, Direction.UP,
						net.minecraft.world.level.block.SupportType.FULL);
	}

	/** Check the selected block item using the same placement context vanilla will receive. */
	private static boolean placementWouldBeAccepted(Minecraft mc, LocalPlayer player, BlockPos target,
	                                                BlockPos against, Direction face, Vec3 aim) {
		ItemStack stack = player.getMainHandItem();
		if (!(stack.getItem() instanceof BlockItem item)) return false;
		BlockHitResult hit = new BlockHitResult(aim, face.getOpposite(), against, false);
		BlockPlaceContext context = new BlockPlaceContext(player, InteractionHand.MAIN_HAND, stack, hit);
		if (!context.canPlace()) return false;
		BlockPlaceContext updated = item.updatePlacementContext(context);
		if (updated == null || !updated.canPlace() || !updated.getClickedPos().equals(target)) return false;
		BlockState placed = item.getBlock().getStateForPlacement(updated);
		if (placed == null || !placed.canSurvive(mc.level, target)) return false;
		return mc.level.isUnobstructed(placed, target, CollisionContext.placementContext(player));
	}

	/**
	 * Whether the eye is on the outside of this face of this box.
	 *
	 * <p>Geometry rather than a raycast, so it costs nothing - and, unlike everything else on
	 * this path, it can be checked without a running game.
	 *
	 * @param towardTarget which way the face points: from the support block towards the
	 *                     square being filled
	 */
	static boolean facesTheEye(AABB supportBox, Direction towardTarget, Vec3 eyes) {
		Vec3 n = towardTarget.getUnitVec3();
		double span = Math.abs(n.x) * supportBox.getXsize()
				+ Math.abs(n.y) * supportBox.getYsize()
				+ Math.abs(n.z) * supportBox.getZsize();
		Vec3 surface = supportBox.getCenter().add(n.scale(span * 0.5));
		return eyes.subtract(surface).dot(n) > 0;
	}

	/** Whether a ray to this point lands on the support, on the face that fills the target. */
	private static boolean placesInto(Minecraft mc, LocalPlayer player, BlockPos target,
	                                  BlockPos against, Vec3 aim, Vec3 eyes) {
		BlockHitResult hit = mc.level.clip(new ClipContext(eyes, aim,
				ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
		return hit.getType() == HitResult.Type.BLOCK
				&& hit.getBlockPos().equals(against)
				&& hit.getBlockPos().relative(hit.getDirection()).equals(target);
	}

	/** The point on the support block that is clicked to put a block into {@code target}. */
	public static Vec3 placePoint(Minecraft mc, BlockPos target, Direction toSupport) {
		AABB box = blockBox(mc, target.relative(toSupport));
		Vec3 normal = toSupport.getOpposite().getUnitVec3();
		double span = Math.abs(normal.x) * box.getXsize() + Math.abs(normal.y) * box.getYsize() + Math.abs(normal.z) * box.getZsize();
		// Mining can hit any face; placement must enter the specific neighbour-facing face.
		// A deeply inset aim hits the top of a repeater instead of its thin side.
		return box.getCenter().add(normal.scale(span * 0.499));
	}

	/**
	 * A shut door or gate that a hand would open.
	 *
	 * <p>A base has doors in it, and a route that treats one as a wall either mines it or
	 * gives up on the room behind it. Neither is what a person does, and mining it is worse
	 * than giving up: it is a hole in somebody's house to get at a block that was never
	 * behind a locked anything. Iron is left out because a hand does nothing to iron - that
	 * one really is a wall until the route goes round it.
	 */
	public static boolean opensByHand(net.minecraft.world.level.BlockGetter level, BlockPos pos) {
		BlockState state = level.getBlockState(pos);
		if (!(state.getBlock() instanceof net.minecraft.world.level.block.DoorBlock
				|| state.getBlock() instanceof net.minecraft.world.level.block.FenceGateBlock)) {
			return false;
		}
		var open = net.minecraft.world.level.block.state.properties.BlockStateProperties.OPEN;
		if (!state.hasProperty(open) || state.getValue(open)) return false;
		var id = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock());
		return id != null && !id.getPath().startsWith("iron_");
	}

	/** Whether a block put here would stay: air, or a fluid a placement simply replaces. */
	public static boolean fillable(Minecraft mc, BlockPos pos) {
		if (mc.level == null) return false;
		BlockState state = mc.level.getBlockState(pos);
		return state.isAir() || !state.getFluidState().isEmpty();
	}

	/**
	 * Lava the player is standing right beside — at foot level, or under the lip they are
	 * stood on. Nothing further out: a lake across the room is scenery, not a hazard.
	 */
	public static BlockPos lavaBeside(Minecraft mc, LocalPlayer player) {
		return liquidBeside(mc, player, false, true);
	}

	/** Selected fluid right beside the feet, at foot level or under the lip being stood on. */
	public static BlockPos liquidBeside(Minecraft mc, LocalPlayer player, boolean water, boolean lava) {
		if (mc.level == null) return null;
		BlockPos feet = player.blockPosition();
		for (BlockPos base : new BlockPos[]{feet, feet.below()}) {
			for (Direction face : Direction.Plane.HORIZONTAL) {
				BlockPos pos = base.relative(face);
				var fluid = mc.level.getBlockState(pos).getFluidState();
				if ((lava && fluid.is(net.minecraft.tags.FluidTags.LAVA))
						|| (water && fluid.is(net.minecraft.tags.FluidTags.WATER))) return pos;
			}
		}
		return null;
	}

	/** Whether the crosshair is on a face whose placement would fill {@code target}. */
	public static boolean aboutToPlaceInto(Minecraft mc, BlockPos target) {
		return aboutToPlaceInto(mc, target, null);
	}

	public static boolean aboutToPlaceInto(Minecraft mc, BlockPos target, Direction clickedFace) {
		return mc.hitResult instanceof BlockHitResult hit
				&& hit.getType() == HitResult.Type.BLOCK
				&& (clickedFace == null || hit.getDirection() == clickedFace)
				&& hit.getBlockPos().relative(hit.getDirection()).equals(target);
	}

	// --------------------------------------------------------------- liquids

	/** The first exposed liquid within {@code radius} that is worth putting a block on. */
	public static BlockPos exposedLiquid(Minecraft mc, LocalPlayer player, int radius) {
		if (mc.level == null) return null;
		BlockPos feet = player.blockPosition();
		BlockPos best = null;
		double bestDist = Double.MAX_VALUE;
		for (BlockPos pos : BlockPos.betweenClosed(feet.offset(-radius, -2, -radius),
				feet.offset(radius, 2, radius))) {
			if (mc.level.getBlockState(pos).getFluidState().isEmpty()) continue;
			// only the surface: a block dropped on top of a lava lake is a floor, a block
			// dropped into the middle of one is a wasted stack
			if (!mc.level.getBlockState(pos.above()).isAir()) continue;
			double d = pos.distSqr(feet);
			if (d < bestDist) {
				bestDist = d;
				best = pos.immutable();
			}
		}
		return best;
	}

	/**
	 * Self-check on the geometry — the world needs a client, the trigonometry does not:
	 * {@code ./gradlew selfCheck -Pcheck=com.damia.movrand.Bot}
	 */
	public static void main(String[] args) {
		Config aimConfig = new com.google.gson.Gson().fromJson("{}", Config.class);
		assert aimConfig.taskAimRandomisation && aimConfig.miningAimSpread() == 0.18
				: "new and existing configs must default to varied mining aim";
		aimConfig.taskAimRandomisation = false;
		aimConfig = aimConfig.copy();
		assert !aimConfig.taskAimRandomisation && aimConfig.miningAimSpread() == 0
				&& aimConfig.taskAimPointSpread == 0.18 : "disabling variation lost the saved amount";
		aimConfig.taskAimRandomisation = true;
		assert aimConfig.copy().miningAimSpread() == 0.18 : "re-enabling variation did not restore it";
		for (Direction face : Direction.values()) {
			AABB thin = new AABB(0, 0, 0, 1, 0.0625, 1);
			for (double u : new double[]{-0.4, 0, 0.4}) {
				for (double v : new double[]{-0.4, 0, 0.4}) {
					assert thin.contains(offsetFacePoint(thin, face, u, v)) : "random aim escaped a thin target";
				}
			}
		}
		// The yaw convention is the one place this file can be silently wrong: get it
		// backwards and the bot mines the block behind it forever.
		assert Math.abs(yawTo(0, 1) - 0) < 0.001 : "+Z must be yaw 0, got " + yawTo(0, 1);
		assert Math.abs(yawTo(-1, 0) - 90) < 0.001 : "-X must be yaw 90, got " + yawTo(-1, 0);
		// 180 and -180 are the same heading, and which one atan2 hands back depends on the
		// sign of a zero, so the test asks the question the code actually cares about
		assert Math.abs(Math.abs(yawTo(0, -1)) - 180) < 0.001 : "-Z must be yaw 180, got " + yawTo(0, -1);
		assert Math.abs(yawTo(1, 0) + 90) < 0.001 : "+X must be yaw -90, got " + yawTo(1, 0);

		// pitch is inverted: looking down is positive
		assert pitchTo(0, -1, 1) > 0 : "looking down should be a positive pitch";
		assert pitchTo(0, 1, 1) < 0 : "looking up should be a negative pitch";
		assert Math.abs(pitchTo(0, -1, 0) - 90) < 0.001 : "straight down is 90, got " + pitchTo(0, -1, 0);

		// The aim point has to land inside the shape, not on its surface and not in the cube
		// it happens to sit in. A block of redstone dust is this box; nearly a whole block of
		// empty air sits above it, and that is where the old arithmetic pointed.
		AABB dust = new AABB(0, 0, 0, 1, 0.0625, 1);
		for (Direction face : Direction.values()) {
			Vec3 p = facePoint(dust, face);
			assert p.x > dust.minX && p.x < dust.maxX
					&& p.y > dust.minY && p.y < dust.maxY
					&& p.z > dust.minZ && p.z < dust.maxZ
					: "the " + face + " aim point fell outside a redstone dust: " + p;
		}
		// and it still has to be on the correct side of the middle, or the visibility test
		// cannot tell one face from another
		assert facePoint(dust, Direction.UP).y > dust.getCenter().y : "up is not upwards";
		assert facePoint(dust, Direction.DOWN).y < dust.getCenter().y : "down is not downwards";

		// Which face to aim at, seen from three blocks away at standing height. The edge of a
		// dust is nearly dead square-on from there and is a sixteenth of a block tall, so
		// squareness on its own picks a target the view wobble slides off; area times
		// squareness picks the big flat top, which is where a person would put the crosshair.
		Vec3 standing = new Vec3(3.5, 1.62, 0.5);
		double top = faceVisibility(dust, Direction.UP, standing);
		double edge = faceVisibility(dust, Direction.EAST, standing);
		assert top > edge * 4
				: "aiming at the edge of a redstone dust (top %.4f, edge %.4f)".formatted(top, edge);
		// a full cube seen from the east at eye level is aimed at through its east face
		AABB block = new AABB(BlockPos.ZERO);
		Vec3 level = new Vec3(3.5, 0.5, 0.5);
		for (Direction face : Direction.values()) {
			if (face == Direction.EAST) continue;
			assert faceVisibility(block, Direction.EAST, level) >= faceVisibility(block, face, level)
					: "a cube straight ahead should be aimed at through its near face, not " + face;
		}
		AABB cube = new AABB(BlockPos.ZERO);
		assert facePoint(cube, Direction.EAST).x > 0.85 && facePoint(cube, Direction.EAST).x < 1
				: "a full cube's east face point is " + facePoint(cube, Direction.EAST);

		// The movement decomposition. This is the one piece of arithmetic in the mod that,
		// got backwards, produces a bot that walks confidently in exactly the wrong direction.
		assertKeys(0, 0, "F");        // facing the way we want to go
		assertKeys(0, 180, "B");      // it is behind us
		assertKeys(0, -90, "L");      // yaw -90 is +X, which is to the left of +Z
		assertKeys(0, 90, "R");
		assertKeys(90, 90, "F");      // the same, from a different facing
		assertKeys(90, 0, "L");       // facing west, south is on your left
		assertKeys(-90, 180, "L");    // facing east, north is on your left
		assertKeys(0, -45, "FL");     // corners press both
		assertKeys(0, 45, "FR");
		assertKeys(0, 135, "BR");
		assertKeys(0, -135, "BL");
		assertKeys(170, -170, "F");   // and the wrap is the short way round, not 340 degrees

		// no heading may ever leave every key up: that is a bot standing still on a route
		for (int facing = -180; facing < 180; facing += 3) {
			for (int move = -180; move < 180; move += 3) {
				boolean[] k = keysFor(facing, move);
				assert k[0] || k[1] || k[2] || k[3]
						: "facing %d, going %d, pressed nothing".formatted(facing, move);
				assert !(k[0] && k[1]) && !(k[2] && k[3])
						: "facing %d, going %d, pressed two opposites".formatted(facing, move);
			}
		}

		// and the keys have to actually carry the player that way: rotate the pressed impulses
		// back out through Minecraft's own transform and check the result points near enough
		for (int facing = -180; facing < 180; facing += 7) {
			for (int move = -180; move < 180; move += 7) {
				boolean[] k = keysFor(facing, move);
				double z = (k[0] ? 1 : 0) - (k[1] ? 1 : 0);
				double x = (k[2] ? 1 : 0) - (k[3] ? 1 : 0);
				double y = Math.toRadians(facing);
				double wx = x * Math.cos(y) - z * Math.sin(y);
				double wz = z * Math.cos(y) + x * Math.sin(y);
				double actual = Math.toDegrees(Math.atan2(-wx, wz));
				double error = Math.abs(Human.wrap(actual - move));
				assert error <= 22.6
						: "facing %d, wanted %d, walked %.1f (%.1f off)".formatted(facing, move, actual, error);
			}
		}

		Steer s = new Steer();
		s.moveTowards(30);
		assert s.hasMove && s.moveYaw == 30 : "a move heading was not recorded";
		s.clear();
		assert !s.hasMove : "clear left a heading behind";
		s.lookAt(45, 400);
		assert s.pitch == 90 : "pitch must be clamped to straight down, got " + s.pitch;
		s.lookAt(45, -400);
		assert s.pitch == -90 : "pitch must be clamped to straight up, got " + s.pitch;
		assert s.hasLook : "a look target should mark the steer as aiming";
		s.forward = true;
		s.clear();
		assert !s.forward && !s.hasLook : "clear left a key held";

		// The wobble is a degree or two, and a heading sitting near a boundary between two of
		// the eight keyboard directions would otherwise flip between them every tick - which is
		// a bot jittering down a corridor rather than walking down it.
		boolean[] holding = keysFor(23, 0, null);
		assert holding[2] : "23 degrees off the heading should be walking with a strafe key";
		for (double wobble = -2; wobble <= 2; wobble += 0.25) {
			boolean[] next = keysFor(23 + wobble, 0, holding);
			assert next[2] : "the strafe key let go on a " + wobble + " degree wobble";
		}
		// and the test is not vacuous: cold, the same heading really does drop the key
		assert !keysFor(21, 0, null)[2] : "21 degrees is below the boundary and should be forward only";
		// nor is it a lock - a real turn still changes the answer
		assert !keysFor(0, 0, holding)[2] : "a held key outlived the heading that wanted it";

		// and widening the threshold the other way would cost accuracy, so it never happens:
		// every heading has at least one key, whatever was held last tick
		for (double facing = -180; facing < 180; facing += 3) {
			for (boolean[] was : new boolean[][]{null, {true, false, false, false}, holding}) {
				boolean[] k = keysFor(facing, 0, was);
				assert k[0] || k[1] || k[2] || k[3]
						: "no key at all for facing " + facing + ", which is a bot standing still";
			}
		}

		// Putting a block into a square means clicking the face of a neighbour that points at
		// that square - the game places it at hit position plus hit direction. So it has to be a
		// face you are outside of, and this is the check that says so. Getting it wrong is not
		// subtle: the bot aims at the block next to the lava for ever and never presses the
		// button, because the placement it is lining up cannot happen.
		AABB solidCube = new AABB(0, 0, 0, 1, 1, 1);
		Vec3 overhead = new Vec3(0.5, 4, 0.5);
		Vec3 underneath = new Vec3(0.5, -4, 0.5);
		assert facesTheEye(solidCube, Direction.UP, overhead) : "a top face is clickable from above it";
		assert !facesTheEye(solidCube, Direction.UP, underneath) : "a top face is not clickable from below";
		assert facesTheEye(solidCube, Direction.DOWN, underneath) : "an underside is clickable from below";
		assert !facesTheEye(solidCube, Direction.DOWN, overhead) : "an underside is not clickable from above";
		assert facesTheEye(solidCube, Direction.NORTH, new Vec3(0.5, 0.5, -4))
				: "a north face is clickable from the north";
		assert !facesTheEye(solidCube, Direction.NORTH, new Vec3(0.5, 0.5, 4))
				: "a north face is not clickable from the south";

		// and the one that was actually happening: standing on a block, wanting to fill the
		// square beside it. The face that would do it is under your feet pointing sideways, and
		// from on top of the block you cannot see it - the ray goes in through the top instead.
		assert !facesTheEye(solidCube, Direction.NORTH, new Vec3(0.5, 1 + 1.62, 0.5))
				: "the side of the block underfoot was called clickable from on top of it";
		// which is what sneaking to the very edge is for: past the face, and it is clickable
		assert facesTheEye(solidCube, Direction.NORTH, new Vec3(0.5, 1 + 1.62, -0.2))
				: "at the edge and past the face, the side should be clickable";

		// a shape that does not fill its own block is measured from the surface it really has
		assert facesTheEye(dust, Direction.UP, new Vec3(0.5, 1, 0.5))
				: "the top of a redstone dust is a sixteenth up, and clickable from above that";
		assert !facesTheEye(dust, Direction.UP, new Vec3(0.5, 0.01, 0.5))
				: "under the surface of a dust is not over it";

		System.out.println("Bot self-check passed");
	}

	/** {@code want} is the keys expected, as letters from FBLR. */
	private static void assertKeys(double facing, double move, String want) {
		boolean[] k = keysFor(facing, move);
		String got = (k[0] ? "F" : "") + (k[1] ? "B" : "") + (k[2] ? "L" : "") + (k[3] ? "R" : "");
		assert got.equals(want)
				: "facing %.0f going %.0f: pressed %s, wanted %s".formatted(facing, move, got, want);
	}

	private static double yawTo(double dx, double dz) {
		return Math.toDegrees(Math.atan2(-dx, dz));
	}

	private static double pitchTo(double dx, double dy, double dz) {
		return Math.toDegrees(-Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
	}
}
