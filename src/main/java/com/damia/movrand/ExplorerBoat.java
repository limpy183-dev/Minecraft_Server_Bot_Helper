package com.damia.movrand;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.item.BoatItem;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.*;
import java.util.HashSet;
import java.util.Set;

/** Optional straight, clear-water legs. Land, bends and obstacles go back to the terrain router. */
final class ExplorerBoat {
    private enum Mode { NONE, DEPLOY, BOARD, ROW, LEAVE, RECOVER }
    private final Config cfg;
    private Mode mode = Mode.NONE;
    private AbstractBoat owned;
    private final Set<Integer> existing = new HashSet<>();
    private Vec3 launch;
    private int wait, age, cooldown, slot = -1, restoreSlot = -1, stuck;
    private Vec3 previous;
    String status = "";

    ExplorerBoat(Config cfg) { this.cfg = cfg; }
    AbstractBoat expectedVehicle() { return mode == Mode.BOARD || mode == Mode.ROW || mode == Mode.LEAVE ? owned : null; }
    void reset() { mode = Mode.NONE; owned = null; launch = previous = null; age = wait = stuck = 0; cooldown = 20; }

    Bot.Steer tick(Minecraft mc, BlockPos goal, NativeNavigation nav) {
        var p = mc.player;
        if (cooldown > 0) cooldown--;
        if (mode == Mode.RECOVER && p.tickCount % 20 == 0) MovRand.LOG.info("BOAT tick age {} native {} critical {} passenger {} ground {} water {}", age, NativeNavigation.controlling(), NativeNavigation.finishingCriticalMove(), p.isPassenger(), p.onGround(), p.isInWater());
        if (NativeNavigation.finishingCriticalMove()) return null;
        if (mode == Mode.NONE) {
            if (!cfg.explorerBoats || cooldown > 0 || p.isPassenger() || !p.isInWater() || p.isUnderWater()) return null;
            Vec3 heading = new Vec3(goal.getX() + .5 - p.getX(), 0, goal.getZ() + .5 - p.getZ());
            if (heading.length() < 16 || clearWater(mc, p.position(), heading.normalize(), 14) < 12) return null;
            slot = -1;
            for (int i = 0; i < 9; i++) if (!cfg.slotProtected(i) && p.getInventory().getItem(i).getItem() instanceof BoatItem) { slot = i; break; }
            if (slot < 0) return null;
            nav.reset();
            launch = p.position().add(heading.normalize().scale(2));
            existing.clear();
            for (var entity : mc.level.entitiesForRendering()) existing.add(entity.getId());
            restoreSlot = p.getInventory().getSelectedSlot();
            mode = Mode.DEPLOY; age = wait = 0;
        }
        nav.reset();
        Bot.Steer steer = new Bot.Steer();
        if (++age > 200 && mode != Mode.ROW) { abandon(mc); return steer; }
        if (wait > 0) wait--;
        if (p.getVehicle() instanceof AbstractBoat riding) {
            // A boat boarded by the user can be steered, but is never marked for destruction.
            if (mode != Mode.LEAVE) mode = Mode.ROW;
            Vec3 heading = new Vec3(goal.getX() + .5 - riding.getX(), 0, goal.getZ() + .5 - riding.getZ());
            double yaw = Math.toDegrees(Math.atan2(-heading.x, heading.z));
            double turn = Human.wrap(yaw - riding.getYRot());
            double clear = clearWater(mc, riding.position(), heading.normalize(), 8);
            if (previous != null && previous.distanceToSqr(riding.position()) < .0004) stuck++; else stuck = 0;
            previous = riding.position();
            if (!cfg.explorerBoats || heading.length() < 4 || clear < 4 || stuck > 100) mode = Mode.LEAVE;
            if (mode == Mode.LEAVE) {
                steer.sneak = true; status = "Leaving boat; resuming terrain route";
                return steer;
            }
            // Boat paddles turn the hull. Strafing logic for feet cannot steer a boat.
            steer.left = turn < -6; steer.right = turn > 6;
            steer.forward = Math.abs(turn) < 35;
            steer.lookAt(yaw, 0);
            status = "Rowing across clear water";
            return steer;
        }
        if (mode == Mode.LEAVE || mode == Mode.ROW) { mode = Mode.RECOVER; age = 0; }
        if (mode == Mode.DEPLOY) {
            for (var e : mc.level.entitiesForRendering()) if (e instanceof AbstractBoat b && !existing.contains(e.getId())
                    && b.distanceToSqr(launch) < 9 && !b.isVehicle()) { owned = b; mode = Mode.BOARD; age = 0; break; }
            if (mode == Mode.DEPLOY) {
                p.getInventory().setSelectedSlot(slot);
                double[] aim = Bot.aimAt(p, launch);
                steer.lookAt(aim[0], aim[1]);
                status = "Placing boat on water";
                return steer;
            }
        }
        if (owned == null || !owned.isAlive()) { abandon(mc); return steer; }
        if (mode == Mode.RECOVER && (!cfg.explorerRecoverBoat || owned.isVehicle() || owned.distanceToSqr(p) > 16)) {
            abandon(mc); return steer;
        }
        double[] aim = Bot.aimAt(p, owned.getBoundingBox().getCenter());
        steer.lookAt(aim[0], aim[1]);
        if (mode == Mode.RECOVER && age % 20 == 0) MovRand.LOG.info("BOAT intent pitch {}", steer.pitch);
        status = mode == Mode.BOARD ? "Boarding boat" : "Recovering empty expedition boat";
        return steer;
    }

    /** Called after the controller applies its smoothed camera, with range and ray checks. */
    void interact(Minecraft mc) {
        if (mode == Mode.NONE || mode == Mode.ROW || mode == Mode.LEAVE || wait > 0 || mc.gameMode == null) return;
        var p = mc.player;
        Vec3 eyes = p.getEyePosition(), end = eyes.add(p.getViewVector(1).scale(p.entityInteractionRange()));
        if (mode == Mode.DEPLOY && p.getMainHandItem().getItem() instanceof BoatItem) {
            BlockHitResult hit = mc.level.clip(new ClipContext(eyes, eyes.add(p.getViewVector(1).scale(p.blockInteractionRange())),
                    ClipContext.Block.OUTLINE, ClipContext.Fluid.ANY, p));
            if (hit.getType() == HitResult.Type.BLOCK && mc.level.getFluidState(hit.getBlockPos()).is(FluidTags.WATER)
                    && hit.getLocation().distanceToSqr(launch) < 4) {
                mc.gameMode.useItem(p, InteractionHand.MAIN_HAND); wait = 30;
            }
        } else if (owned != null && owned.isAlive() && !owned.isVehicle()) {
            var hit = owned.getBoundingBox().inflate(.1).clip(eyes, end);
            if (mode == Mode.RECOVER && age % 40 == 0) MovRand.LOG.info("BOAT recovery eye {} boat {} pitch {} yaw {} ray {}", eyes, owned.position(), p.getXRot(), p.getYRot(), hit);
            if (hit.isEmpty() || mc.level.clip(new ClipContext(eyes, hit.get(), ClipContext.Block.COLLIDER,
                    ClipContext.Fluid.NONE, p)).getType() != HitResult.Type.MISS) return;
            if (mode == Mode.BOARD) mc.gameMode.interact(p, owned, new EntityHitResult(owned, hit.get()), InteractionHand.MAIN_HAND);
            else if (mode == Mode.RECOVER) { mc.gameMode.attack(p, owned); p.swing(InteractionHand.MAIN_HAND); }
            wait = 8 + Rng.nextInt(5);
        }
    }

    private void abandon(Minecraft mc) {
        if (restoreSlot >= 0) mc.player.getInventory().setSelectedSlot(restoreSlot);
        restoreSlot = -1; reset(); cooldown = 300;
    }

    private static int clearWater(Minecraft mc, Vec3 start, Vec3 direction, int length) {
        int y = net.minecraft.util.Mth.floor(start.y);
        for (int d = 1; d <= length; d++) {
            BlockPos centre = BlockPos.containing(start.add(direction.scale(d)));
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                BlockPos pos = new BlockPos(centre.getX() + dx, y, centre.getZ() + dz);
                if (!mc.level.hasChunkAt(pos) || !mc.level.getWorldBorder().isWithinBounds(pos)) return d - 1;
                if (!mc.level.getFluidState(pos).is(FluidTags.WATER)) pos = pos.below();
                if (!mc.level.getFluidState(pos).is(FluidTags.WATER) || !mc.level.getBlockState(pos.above()).isAir()
                        || !mc.level.getBlockState(pos.above(2)).isAir()) return d - 1;
            }
        }
        return length;
    }
}
