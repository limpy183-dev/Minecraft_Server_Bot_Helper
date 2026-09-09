package com.damia.movrand;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** Sticky pickup journeys, physical pickup goals, and temporary failure memory. */
final class DropCollector {
	private final Config cfg;
	private final BlockTargets targets = new BlockTargets();
	final Pathing nav;
	private int itemId = -1;
	private int itemTicks, settleTicks, batchTicks, restTicks;
	private long tick;
	private final NavProgress directProgress = new NavProgress();
	private int directCooldown;
	private final java.util.Set<Long> rejectedCells = new java.util.HashSet<>();
	private final Map<Integer, Retry> retries = new HashMap<>();
	private final Map<Integer, Long> touched = new HashMap<>();
	private record Retry(long until, Vec3 itemPosition) {}
	String detail = "";
	int collected;

	DropCollector(Config cfg) { this.cfg = cfg; nav = new Pathing(cfg); }

	void reset() {
		itemId = -1;
		itemTicks = settleTicks = batchTicks = restTicks = 0;
		directCooldown = 0;
		directProgress.reset();
		retries.clear();
		touched.clear();
		rejectedCells.clear();
		nav.reset();
	}

	/** Called even while mining/combat has priority, so confirmation and retries keep time. */
	void observe(PathMove.Ctx ctx) {
		tick++;
		if (restTicks > 0) restTicks--;
		if (directCooldown > 0) directCooldown--;
		touched.entrySet().removeIf(entry -> {
			Entity e = ctx.level().getEntity(entry.getKey());
			if (e == null) { collected++; return true; }
			return entry.getValue() < tick;
		});
		retries.entrySet().removeIf(entry -> entry.getValue().until <= tick
				|| ctx.level().getEntity(entry.getKey()) == null);
	}

	boolean tick(PathMove.Ctx ctx, Bot.Steer steer, java.util.Set<net.minecraft.world.level.block.Block> mayBreak) {
		if (restTicks > 0) return false;
		batchTicks++;
		Set<Item> allowed = cfg.collectOnlySelectedDrops ? targets.dropItems(cfg) : null;
		Entity active = ctx.level().getEntity(itemId);
		ItemEntity item = active instanceof ItemEntity drop && eligible(ctx, drop, allowed) ? drop : null;
		if (item == null && batchTicks > cfg.collectBatchSec * 20) {
			// Yield between journeys. Interrupting a bridge or descent here restarts the same route forever.
			batchTicks = 0;
			restTicks = 10;
			itemId = -1;
			nav.reset();
			return false;
		}
		if (item == null) {
			double best = Double.POSITIVE_INFINITY;
			for (Entity e : ctx.level().entitiesForRendering()) {
				if (!(e instanceof ItemEntity drop) || !eligible(ctx, drop, allowed)) continue;
				double distance = drop.distanceToSqr(ctx.player());
				if (distance < best) { best = distance; item = drop; }
			}
			if (item == null) { itemId = -1; batchTicks = 0; nav.reset(); return false; }
			itemId = item.getId();
			itemTicks = settleTicks = 0;
			rejectedCells.clear();
			directProgress.reset();
			directCooldown = 0;
			nav.reset();
		}
		if (++itemTicks > cfg.collectGiveUpSec * 20) { defer(item); return false; }
		detail = item.getItem().getHoverName().getString();

		// Distance between feet is not pickup reach: an item can be above a ceiling or in a pit.
		if (pickupOverlap(ctx.player().getBoundingBox(), item.getBoundingBox())) {
			touched.putIfAbsent(itemId, tick + 40);
			if (++settleTicks > cfg.collectPickupWaitSec * 20) { defer(item); return false; }
			detail += " · waiting for pickup";
			return true;
		}
		settleTicks = 0;
		Vec3 destination = item.position();
		// The direct shortcut is only for a proven clear, supported corridor at the same height.
		if (directCooldown == 0 && destination.distanceToSqr(ctx.player().position()) < 9 && directWalk(ctx, destination)) {
			if (!directProgress.update(destination.x, destination.z,
					destination.distanceTo(ctx.player().position()), cfg.pathStallSec)) {
				aimAndWalk(ctx, steer, destination);
				return true;
			}
			directCooldown = 40;
			nav.invalidate("pickup approach stalled");
		}

		AABB itemBox = item.getBoundingBox();
		double width = ctx.player().getBbWidth(), height = ctx.player().getBbHeight();
		PathFinder.Goal goal = (x, y, z) -> {
			if (rejectedCells.contains(BlockPos.asLong(x, y, z))) return false;
			double top = Avoidance.topOf(ctx.level(), new BlockPos(x, y, z));
			double floorY = y + (top <= Avoidance.STEPPABLE ? Math.max(0, top) : 0);
			AABB body = new AABB(x + 0.5 - width / 2, floorY, z + 0.5 - width / 2,
					x + 0.5 + width / 2, floorY + height, z + 0.5 + width / 2);
			return pickupOverlap(body, itemBox);
		};
		BlockPos feet = ctx.player().blockPosition();
		if (goal.reached(feet.getX(), feet.getY(), feet.getZ()) && ctx.player().onGround()) {
			// Grid arrival may occur at the corner of the square. Finish centring physically.
			Vec3 centre = new Vec3(feet.getX() + 0.5, ctx.player().getY(), feet.getZ() + 0.5);
			if (directWalk(ctx, centre) && !directProgress.update(centre.x, centre.z,
					centre.distanceTo(ctx.player().position()), cfg.pathStallSec)) {
				aimAndWalk(ctx, steer, centre);
				return true;
			}
			// A grid cell can be accepted while its reachable corner cannot touch the item.
			// Try another approach, remembering this one until the item journey ends.
			rejectedCells.add(feet.asLong());
			directProgress.reset();
			nav.invalidate("trying another pickup position");
		}
		Pathing.Nav outcome = nav.tick(ctx, steer, item.blockPosition(), goal,
				mayBreak, cfg.collectAllowEdits);
		detail += " · " + nav.status;
		if (outcome == Pathing.Nav.NO_ROUTE) { defer(item); return false; }
		return true; // ARRIVED is checked against the actual pickup volume on the following tick.
	}

	private boolean eligible(PathMove.Ctx ctx, ItemEntity item, Set<Item> allowed) {
		if (!item.isAlive() || item.getItem().isEmpty()
				|| (allowed != null && !allowed.contains(item.getItem().getItem()))
				|| item.distanceToSqr(ctx.player()) > (double) cfg.collectRadius * cfg.collectRadius) return false;
		Retry retry = retries.get(item.getId());
		return retry == null || retry.until <= tick || item.position().distanceToSqr(retry.itemPosition) > 1;
	}

	private void defer(ItemEntity item) {
		if (retries.size() >= 256) {
			Integer oldest = retries.entrySet().stream().min(java.util.Comparator.comparingLong(e -> e.getValue().until))
					.orElseThrow().getKey();
			retries.remove(oldest);
		}
		retries.put(item.getId(), new Retry(tick + Math.round(cfg.collectRetrySec * 20), item.position()));
		itemId = -1;
		nav.reset();
	}

	static boolean pickupOverlap(AABB player, AABB item) {
		// Conservative margins inside vanilla's pickup area, including the item's own dimensions.
		return player.inflate(0.65, 0.25, 0.65).intersects(item);
	}

	static boolean directWalk(PathMove.Ctx ctx, Vec3 to) {
		Vec3 from = ctx.player().position();
		if (Math.abs(to.y - from.y) > 0.6 || !ctx.player().onGround()) return false;
		double dx = to.x - from.x, dz = to.z - from.z;
		AABB box = ctx.player().getBoundingBox().deflate(0.01);
		return clearCorridor(box, dx, dz, body -> {
			if (!ctx.level().noCollision(ctx.player(), body)) return false;
			for (double x : new double[]{body.minX, body.maxX}) {
				for (double z : new double[]{body.minZ, body.maxZ}) {
					BlockPos feet = BlockPos.containing(x, from.y + 0.05, z);
					if (!ctx.level().hasChunk(feet.getX() >> 4, feet.getZ() >> 4)
							|| Avoidance.hazardAt(ctx.level(), feet, ctx.cfg())
							|| Avoidance.hazardAt(ctx.level(), feet.below(), ctx.cfg())) return false;
					if (!Avoidance.holdsWeight(ctx.level(), feet.below())
							&& Avoidance.topOf(ctx.level(), feet) <= 0) return false;
				}
			}
			return true;
		});
	}

	static boolean clearCorridor(AABB box, double dx, double dz, java.util.function.Predicate<AABB> safe) {
		int samples = Math.max(1, (int) Math.ceil(Math.sqrt(dx * dx + dz * dz) / 0.2));
		for (int i = 1; i <= samples; i++) {
			double t = (double) i / samples;
			if (!safe.test(box.move(dx * t, 0, dz * t))) return false;
		}
		return true;
	}

	static void aimAndWalk(PathMove.Ctx ctx, Bot.Steer steer, Vec3 to) {
		double heading = Math.toDegrees(Math.atan2(ctx.player().getX() - to.x, to.z - ctx.player().getZ()));
		steer.moveTowards(heading);
		steer.lookAt(heading, 15);
	}

	public static void main(String[] args) {
		AABB player = new AABB(-0.3, 1, -0.3, 0.3, 2.8, 0.3);
		assert pickupOverlap(player, new AABB(0.5, 1, 0.5, 0.75, 1.25, 0.75));
		assert !pickupOverlap(player, new AABB(0, -0.5, 0, 0.25, -0.25, 0.25)) : "drop beneath the floor counted as touched";
		assert !pickupOverlap(player, new AABB(0, 3.5, 0, 0.25, 3.75, 0.25)) : "drop above a ceiling counted as touched";
		assert !pickupOverlap(player, new AABB(1.1, 1, 0, 1.35, 1.25, 0.25));
		AABB wall = new AABB(0.7, 1, -1, 1, 3, 1);
		assert !clearCorridor(player, 1.6, 0, body -> !body.intersects(wall)) : "nearby pickup shortcut crossed a wall";
		AABB shoulder = new AABB(0.8, 1, 0.2, 1, 3, 0.4);
		assert !clearCorridor(player, 1.6, 0, body -> !body.intersects(shoulder)) : "centre ray missed a shoulder collision";
		assert !clearCorridor(player, 2, 0, body -> body.maxX < 1 || body.minX > 1.5)
				: "shortcut crossed unsupported ground";
		assert clearCorridor(player, 1.6, 0, body -> true) : "clear direct pickup required a search";
		Config cfg = new Config();
		DropCollector collector = new DropCollector(cfg);
		collector.itemId = 42;
		collector.retries.put(42, new Retry(100, Vec3.ZERO));
		collector.reset();
		assert collector.itemId == -1 && collector.retries.isEmpty();
		System.out.println("DropCollector self-check passed");
	}
}
