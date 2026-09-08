package com.damia.movrand;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.LinkedHashSet;
import java.util.List;

/** Before releasing a drop, require a known, non-burning landing area and contained adjacent lava. */
public final class MineSafety {
	public static final int AIR = 0, SOLID = 1, BURNING = 2, UNKNOWN = 3;
	@FunctionalInterface public interface View { int cell(int x, int y, int z); }
	public record Assessment(List<BlockPos> cover, boolean known) {
		public boolean safe() { return known && cover.isEmpty(); }
	}
	private static BlockPos denied;
	private MineSafety() {}

	public static int classify(BlockState state) {
		if (state.getFluidState().is(FluidTags.LAVA) || state.is(Blocks.FIRE) || state.is(Blocks.SOUL_FIRE)
				|| state.is(Blocks.CACTUS) || state.is(Blocks.CAMPFIRE) || state.is(Blocks.SOUL_CAMPFIRE)) return BURNING;
		return state.isSolid() ? SOLID : AIR;
	}

	public static Assessment inspect(View view, BlockPos target, int depth) {
		LinkedHashSet<BlockPos> cover = new LinkedHashSet<>();
		boolean known = true;
		for (Direction side : Direction.values()) {
			BlockPos next = target.relative(side);
			int cell = view.cell(next.getX(), next.getY(), next.getZ());
			if (cell == UNKNOWN) known = false;
			if (cell == BURNING) cover.add(next);
		}
		// Items spawn away from the exact centre and bounce sideways. Check the whole landing patch.
		for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
			boolean floor = false;
			for (int down = 0; down <= depth; down++) {
				if (down == 0 && dx == 0 && dz == 0) continue; // this block is about to disappear
				int x = target.getX() + dx, y = target.getY() - down, z = target.getZ() + dz;
				int cell = view.cell(x, y, z);
				if (cell == UNKNOWN) { known = false; floor = true; break; }
				if (cell == SOLID) { floor = true; break; }
				if (cell == BURNING) {
					cover.add(new BlockPos(x, Math.max(y, target.getY() - 1), z));
					floor = true;
					break;
				}
			}
			if (!floor) cover.add(target.offset(dx, -1, dz)); // build a catch floor over a deep shaft
		}
		cover.remove(target);
		return new Assessment(List.copyOf(cover), known);
	}

	public static Assessment inspect(PathMove.Ctx ctx, BlockPos pos) {
		return inspect((x, y, z) -> {
			if (!ctx.level().hasChunk(x >> 4, z >> 4)) return UNKNOWN;
			if (y < ctx.level().getMinY() || y >= ctx.level().getMaxY()) return AIR;
			return classify(ctx.level().getBlockState(new BlockPos(x, y, z)));
		}, pos, ctx.cfg().dropSafetyDepth);
	}

	/** Used by the vanilla interaction gate, also covering Baritone's digging and changing fluids. */
	public static boolean mayStartBreaking(BlockPos pos) {
		Config cfg = MovRand.config();
		Minecraft mc = Minecraft.getInstance();
		if (cfg != null && cfg.movementEnabled && mc.level != null && Storage.protectedWorldBlock(cfg, mc.level, pos)) return false;
		if (cfg == null || !cfg.movementEnabled || !cfg.destroyerEnabled || !cfg.protectMiningDrops
				|| mc.player == null || mc.level == null) return true;
		BlockState state = mc.level.getBlockState(pos);
		if (state.requiresCorrectToolForDrops() && java.util.stream.IntStream.range(0, 9)
				.noneMatch(slot -> mc.player.getInventory().getItem(slot).isCorrectToolForDrops(state))) {
			MovRand.controller().stop(mc, "No suitable hotbar tool to recover " + state.getBlock().getName().getString());
			return false;
		}
		if (inspect(new PathMove.Ctx(mc, mc.player, mc.level, cfg), pos).safe()) return true;
		// Hold the first request until the controller handles it. A path/builder can try
		// several blocks in successive ticks; overwriting this made protection chase them.
		if (denied == null) denied = pos.immutable();
		return false;
	}

	public static BlockPos deniedBlock() { return denied; }
	public static void clearDenied() { denied = null; }

	public static void main(String[] args) {
		SitePreparation.selfCheck();
		BlockPos target = new BlockPos(0, 5, 0);
		View floor = (x, y, z) -> y == 0 ? SOLID : AIR;
		assert inspect(floor, target, 16).safe();
		View underneath = (x, y, z) -> x == 0 && z == 0 && y == 4 ? BURNING : floor.cell(x, y, z);
		assert inspect(underneath, target, 16).cover.contains(target.below()) : "lava under a block was ignored";
		View side = (x, y, z) -> x == 1 && z == 0 && y == 5 ? BURNING : floor.cell(x, y, z);
		assert inspect(side, target, 16).cover.contains(target.east());
		View shaft = (x, y, z) -> y == -5 ? BURNING : AIR;
		Assessment platform = inspect(shaft, target, 16);
		assert platform.cover.size() == 9 && platform.cover.stream().allMatch(p -> p.getY() == 4)
				: "deep lava needs a catch platform before the block is removed";
		View contained = (x, y, z) -> y == 4 ? SOLID : y == 3 ? BURNING : AIR;
		assert inspect(contained, target, 16).safe() : "contained lava below solid floor was treated as exposed";
		assert !inspect((x, y, z) -> UNKNOWN, target, 16).safe() : "unloaded terrain was assumed safe";
		assert inspect((x, y, z) -> AIR, target, 3).cover.size() == 9 : "void mining had no catch floor";
		System.out.println("MineSafety self-check passed");
	}
}
