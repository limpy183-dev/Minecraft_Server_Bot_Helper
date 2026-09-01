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
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

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

	/** One tick of intent: where to look, what to hold down. The controller does the rest. */
	public static final class Steer {
		public double yaw, pitch;
		public boolean hasLook;
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

		public void clear() {
			hasLook = false;
			forward = back = left = right = jump = sneak = sprint = attack = use = false;
		}
	}

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
	 * A point on the block that the eyes can actually see.
	 *
	 * <p>Aiming at the centre of a block works right up until the block is in a wall, where
	 * the ray stops at the wall and the crosshair never lands on the target. So each face is
	 * tried, nearest first, and the first one with a clear line wins.
	 */
	public static Vec3 aimPoint(Minecraft mc, LocalPlayer player, BlockPos pos) {
		Vec3 eyes = player.getEyePosition();
		Vec3 centre = Vec3.atCenterOf(pos);
		Vec3 best = null;
		double bestDist = Double.MAX_VALUE;

		for (Direction face : Direction.values()) {
			Vec3 point = centre.add(face.getUnitVec3().scale(0.5 - 1.0E-4));
			// a face pointing away from the eyes is behind the block, so never visible
			if (point.subtract(eyes).dot(face.getUnitVec3()) > 0) continue;
			if (!clearLine(mc, player, eyes, point, pos)) continue;
			double d = point.distanceToSqr(eyes);
			if (d < bestDist) {
				bestDist = d;
				best = point;
			}
		}
		return best != null ? best : centre;
	}

	/** Whether a ray from the eyes to this point stops at the block we meant. */
	private static boolean clearLine(Minecraft mc, LocalPlayer player, Vec3 from, Vec3 to, BlockPos pos) {
		if (mc.level == null) return false;
		BlockHitResult hit = mc.level.clip(new ClipContext(from, to,
				ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
		return hit.getType() == HitResult.Type.MISS || hit.getBlockPos().equals(pos);
	}

	/** Whether the crosshair is genuinely on this block right now. */
	public static boolean lookingAt(Minecraft mc, BlockPos pos) {
		return mc.hitResult instanceof BlockHitResult hit
				&& hit.getType() == HitResult.Type.BLOCK
				&& hit.getBlockPos().equals(pos);
	}

	/** Within arm's length — the server's own limit, not a guessed one. */
	public static boolean inReach(LocalPlayer player, BlockPos pos) {
		double range = player.blockInteractionRange();
		return Vec3.atCenterOf(pos).distanceToSqr(player.getEyePosition()) <= range * range;
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
	 * Aim at a face that a block can be placed against, filling {@code target}.
	 *
	 * @return true when the steer was pointed at something placeable; the caller decides
	 * whether to actually press use, because a tick spent aiming is not a tick spent placing
	 */
	public static boolean aimToPlace(Minecraft mc, LocalPlayer player, BlockPos target, Steer steer) {
		if (mc.level == null) return false;
		for (Direction face : Direction.values()) {
			BlockPos against = target.relative(face);
			BlockState state = mc.level.getBlockState(against);
			if (state.isAir() || !state.getFluidState().isEmpty()) continue;
			if (state.getCollisionShape(mc.level, against).isEmpty()) continue;
			if (!inReach(player, against)) continue;

			Vec3 point = Vec3.atCenterOf(against).add(face.getOpposite().getUnitVec3().scale(0.5 - 1.0E-4));
			if (!clearLine(mc, player, player.getEyePosition(), point, against)) continue;
			double[] look = aimAt(player, point);
			steer.lookAt(look[0], look[1]);
			return true;
		}
		return false;
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

		Steer s = new Steer();
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

	private static double yawTo(double dx, double dz) {
		return Math.toDegrees(Math.atan2(-dx, dz));
	}

	private static double pitchTo(double dx, double dy, double dz) {
		return Math.toDegrees(-Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
	}
}
