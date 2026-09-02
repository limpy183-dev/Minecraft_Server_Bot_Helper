package com.damia.movrand;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
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

		public void clear() {
			hasLook = false;
			hasMove = false;
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
		double d = Math.toRadians(Human.wrap(facingYaw - moveYaw));
		double forward = Math.cos(d);
		double left = Math.sin(d);
		return new boolean[]{forward > OCTANT, forward < -OCTANT, left > OCTANT, left < -OCTANT};
	}

	/** sin 22.5°, which is where one of the eight keyboard directions gives way to the next. */
	private static final double OCTANT = 0.3827;

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
		for (int slot = 0; slot < Inventory.SELECTION_SIZE; slot++) {
			if (cfg.slotProtected(slot)) continue; // a protected slot is not scaffolding
			ItemStack stack = inv.getItem(slot);
			if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem item)) continue;
			if (stack.getCount() < Math.max(1, cfg.bridgeKeepBlocks)) continue;
			BlockState placed = item.getBlock().defaultBlockState();
			// scaffolding has to hold still and hold weight: no sand, no torches, no slabs
			if (placed.isAir() || !placed.getFluidState().isEmpty()) continue;
			if (!cfg.isBuildingBlock(idOf(item))) continue;
			return slot;
		}
		return -1;
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
			if (stack.getItem() instanceof BlockItem item && cfg.isBuildingBlock(idOf(item))) {
				total += stack.getCount();
			}
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
		if (mc.level == null) return null;
		if (preferred != null && supports(mc, player, target, preferred)) return preferred;

		Direction best = null;
		double bestDist = Double.MAX_VALUE;
		Vec3 eyes = player.getEyePosition();
		for (Direction face : Direction.values()) {
			if (!supports(mc, player, target, face)) continue;
			double d = placePoint(mc, target, face).distanceToSqr(eyes);
			if (d < bestDist) {
				bestDist = d;
				best = face;
			}
		}
		return best;
	}

	private static boolean supports(Minecraft mc, LocalPlayer player, BlockPos target, Direction face) {
		BlockPos against = target.relative(face);
		BlockState state = mc.level.getBlockState(against);
		if (state.isAir() || !state.getFluidState().isEmpty()) return false;
		if (state.getCollisionShape(mc.level, against).isEmpty()) return false;
		if (!inReach(mc, player, against)) return false;
		return clearLine(mc, player, player.getEyePosition(), placePoint(mc, target, face), against);
	}

	/** The point on the support block that is clicked to put a block into {@code target}. */
	public static Vec3 placePoint(Minecraft mc, BlockPos target, Direction toSupport) {
		return facePoint(blockBox(mc, target.relative(toSupport)), toSupport.getOpposite());
	}

	/**
	 * Aim at a face that a block can be placed against, filling {@code target}.
	 *
	 * @return true when the steer was pointed at something placeable; the caller decides
	 * whether to actually press use, because a tick spent aiming is not a tick spent placing
	 */
	public static boolean aimToPlace(Minecraft mc, LocalPlayer player, BlockPos target, Steer steer) {
		Direction face = placeAgainst(mc, player, target, null);
		if (face == null) return false;
		double[] look = aimAt(player, placePoint(mc, target, face));
		steer.lookAt(look[0], look[1]);
		return true;
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
		if (mc.level == null) return null;
		BlockPos feet = player.blockPosition();
		for (BlockPos base : new BlockPos[]{feet, feet.below()}) {
			for (Direction face : Direction.Plane.HORIZONTAL) {
				BlockPos pos = base.relative(face);
				if (Avoidance.isLavaAt(mc.level, pos)) return pos;
			}
		}
		return null;
	}

	/** Whether the crosshair is on a face whose placement would fill {@code target}. */
	public static boolean aboutToPlaceInto(Minecraft mc, BlockPos target) {
		return mc.hitResult instanceof BlockHitResult hit
				&& hit.getType() == HitResult.Type.BLOCK
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
