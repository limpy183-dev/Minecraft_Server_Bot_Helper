package com.damia.movrand;

import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalXZ;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Locale;

/** Exploration owns goals; Baritone owns terrain moves; the controller owns survival and scans. */
public final class TerrainExplorer {
    public enum Phase { IDLE, TRAVELLING, PAUSED, DONE, BLOCKED }
    private final Config cfg;
    private final AreaCoverage area;
    private final Journal journal;
    private final NativeNavigation navigation;
    private final Backpack backpack;
    private final Combat combat;
    private final ExplorerBoat boat;
    private ClientLevel world;
    private BlockPos destination;
    private boolean coordinates;
    private int targetTicks, retries, pause, nextPause, retryWait;
    private Vec3 previous;
    private record Edit(net.minecraft.world.level.block.Block block, boolean placement, long expires) {}
    private final java.util.Map<BlockPos, Edit> edits = new java.util.HashMap<>();
    public Phase phase = Phase.IDLE;
    public String status = "Ready";
    public long ticks;
    public double distance;
    public int mined, placed, failures;
    public long cpuNanos, maxCpuNanos, calls;

    TerrainExplorer(Config cfg, AreaCoverage area, Journal journal) {
        this.cfg = cfg; this.area = area; this.journal = journal;
        navigation = new NativeNavigation(cfg, true);
        backpack = new Backpack(cfg);
        combat = new Combat(cfg);
        boat = new ExplorerBoat(cfg);
    }

    public void restart() {
        navigation.reset(); boat.reset();
        world = null; destination = null; previous = null; edits.clear(); retryWait = 0;
        ticks = cpuNanos = maxCpuNanos = calls = 0; distance = 0; mined = placed = failures = retries = targetTicks = pause = 0;
        nextPause = Rng.ticks(30, 90);
        phase = Phase.IDLE; status = "Ready";
    }

    public void stop() { navigation.reset(); }
    net.minecraft.world.entity.Entity expectedVehicle() { return boat.expectedVehicle(); }
    void interact(Minecraft mc) { if (!NativeNavigation.controlling()) boat.interact(mc); }

    public String metrics() {
        return String.format(Locale.ROOT, "%.1f m · %.1f s · %.2f m/s · %d mined · %d placed · %d failures",
                distance, ticks / 20.0, speed(distance, ticks), mined, placed, failures);
    }

    static double speed(double metres, long ticks) { return ticks <= 0 ? 0 : metres * 20 / ticks; }

    /** Includes eating and storage time in expedition speed. */
    void sample(Minecraft mc) {
        if (mc.player == null || phase == Phase.DONE || phase == Phase.BLOCKED) return;
        ticks++;
        if (previous != null && world == mc.level) distance += Math.min(8, previous.distanceTo(mc.player.position()));
        previous = mc.player.position();
        if (world == mc.level && ticks % (cfg.explorerLogSec * 20L) == 0) log(mc, status + " | " + metrics());
    }

    public Bot.Steer tick(Minecraft mc, float damage) {
        long start = System.nanoTime();
        try { return tickImpl(mc, damage); }
        finally { long elapsed = System.nanoTime() - start; cpuNanos += elapsed; maxCpuNanos = Math.max(maxCpuNanos, elapsed); calls++; }
    }

    private Bot.Steer tickImpl(Minecraft mc, float damage) {
        Bot.Steer steer = new Bot.Steer();
        var player = mc.player;
        if (player == null || mc.level == null) return steer;
        if (world != null && world != mc.level) return finish(mc, false, "World or dimension changed; restart in the new dimension");
        if (phase == Phase.DONE || phase == Phase.BLOCKED) return steer;
        if (world == null) {
            world = mc.level;
            coordinates = cfg.explorerCoordinates;
            phase = Phase.TRAVELLING;
            log(mc, "Started " + (coordinates ? "XYZ journey" : "area exploration"));
        }
        if (coordinates != cfg.explorerCoordinates) { navigation.reset(); destination = null; coordinates = cfg.explorerCoordinates; }

        // Never take movement or inventory away from an airborne placement or bucket landing.
        if (!NativeNavigation.finishingCriticalMove()) {
            combat.onDamage(player, damage);
            if (!player.isPassenger() && combat.tick(mc, player, steer)) {
                navigation.reset(); status = combat.status; return steer;
            }
            if (backpack.restockHotbar(mc, player)
                    || cfg.explorerWaterBucket && backpack.restockItem(mc, player, stack -> stack.is(net.minecraft.world.item.Items.WATER_BUCKET))
                    || cfg.explorerBoats && backpack.restockItem(mc, player, stack -> stack.getItem() instanceof net.minecraft.world.item.BoatItem)) {
                navigation.reset(); status = backpack.status; return steer;
            }
            if (player.getAirSupply() < 100 && player.isUnderWater()) {
                navigation.reset(); steer.jump = true;
                status = "Surfacing for air"; return steer;
            }
        }

        BlockPos want;
        Goal goal;
        if (coordinates) {
            want = new BlockPos(cfg.explorerX, cfg.explorerY, cfg.explorerZ);
            if (want.getY() < mc.level.getMinY() || want.getY() > mc.level.getMaxY() - 2
                    || !mc.level.getWorldBorder().isWithinBounds(want))
                return finish(mc, false, "Destination is outside this dimension's build height or world border");
            goal = new GoalBlock(want);
        } else {
            int cx = player.blockPosition().getX() >> 4, cz = player.blockPosition().getZ() >> 4;
            // Exploration means physically visiting a chunk. Scanning alone never claims traversal.
            if (player.onGround() || player.isInWater() || player.onClimbable()) area.markCovered(cx, cz, 0);
            if (area.isComplete()) return finish(mc, true, "Area explored: " + area.totalChunks() + " chunks");
            AreaCoverage.Target target = area.nextTarget(cx, cz, 0);
            if (target == null) return finish(mc, false, "No remaining reachable area target");
            want = new BlockPos((int) Math.floor(target.x()), 0, (int) Math.floor(target.z()));
            goal = new GoalXZ(want.getX(), want.getZ());
            if (!mc.level.getWorldBorder().isWithinBounds(new BlockPos(want.getX(), player.blockPosition().getY(), want.getZ())))
                return finish(mc, false, "Selected area extends outside the world border");
        }
        if (!want.equals(destination)) {
            destination = want; retries = targetTicks = 0;
            log(mc, "Target " + want.toShortString() + (coordinates ? " (exact feet block)" : " (any safe elevation)"));
        }
        if (++targetTicks > cfg.explorerTargetSec * 20) return finish(mc, false, "Destination deadline exceeded: " + want.toShortString());

        if (retryWait > 0) { retryWait--; status = "Waiting before route retry"; return steer; }
        Bot.Steer boating = boat.tick(mc, destination, navigation);
        if (boating != null) { status = boat.status; return boating; }
        if (cfg.explorerPause && safePause(mc)) {
            if (--nextPause <= 0 && pause == 0) {
                pause = Rng.ticks(0.3, 1.5); nextPause = Rng.ticks(30, 90);
            }
            if (pause > 0) {
                pause--; navigation.reset(); phase = Phase.PAUSED; status = "Looking around";
                steer.lookAt(player.getYRot(), -5 + Math.sin(ticks * .08) * 4);
                return steer;
            }
        } else pause = 0;
        phase = Phase.TRAVELLING;
        Pathing.Nav result = navigation.travel(new PathMove.Ctx(mc, player, mc.level, cfg), steer, want, goal);
        status = "Explorer: " + navigation.status;
        if (result == Pathing.Nav.ARRIVED) {
            if (coordinates) return finish(mc, true, "Reached XYZ " + want.toShortString());
            area.markCovered(want.getX() >> 4, want.getZ() >> 4, 0);
            destination = null;
        } else if (result == Pathing.Nav.NO_ROUTE) {
            failures++;
            log(mc, "Route failed: " + navigation.status);
            if (retries++ >= cfg.explorerRetries) return finish(mc, false, "No safe route to " + want.toShortString());
            navigation.reset();
            // Keep the coverage target outstanding. A failed chunk is never reported as explored.
            retryWait = Rng.ticks(1, 2);
        }
        return steer;
    }

    private boolean safePause(Minecraft mc) {
        var p = mc.player;
        if (!p.onGround() || p.isInWater() || p.isInLava() || p.isOnFire() || NativeNavigation.finishingCriticalMove()) return false;
        BlockPos feet = p.blockPosition();
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            BlockPos floor = feet.offset(dx, -1, dz);
            if (!mc.level.getBlockState(floor).isCollisionShapeFullBlock(mc.level, floor)
                    || !mc.level.getFluidState(floor).isEmpty()
                    || baritone.pathing.movement.MovementHelper.avoidWalkingInto(mc.level.getBlockState(floor))) return false;
        }
        return true;
    }

    private Bot.Steer finish(Minecraft mc, boolean success, String reason) {
        navigation.reset();
        phase = success ? Phase.DONE : Phase.BLOCKED;
        status = reason;
        journal.log(success ? coordinates ? Journal.Kind.ARRIVED : Journal.Kind.AREA_DONE : Journal.Kind.STUCK,
                mc.level, mc.player.blockPosition(), "Terrain explorer: " + reason + " | " + metrics());
        MovRand.controller().stop(mc, status);
        return new Bot.Steer();
    }

    private void log(Minecraft mc, String text) {
        journal.log(Journal.Kind.EXPLORER, mc.level, mc.player.blockPosition(), text);
    }

    public void expectEdit(ClientLevel level, BlockPos pos, net.minecraft.world.level.block.Block block, boolean placement) {
        if (!cfg.explorerEnabled || !cfg.movementEnabled || world != level || !NativeNavigation.controlling()) return;
        edits.entrySet().removeIf(e -> e.getValue().expires < level.getGameTime());
        if (edits.size() < 128) edits.put(pos.immutable(), new Edit(block, placement, level.getGameTime() + 200));
    }

    /** Server acknowledgement confirms a pending vanilla interaction, even after client prediction. */
    public void confirmEdit(ClientLevel level, BlockPos pos, BlockState state) {
        if (world != level) return;
        Edit e = edits.remove(pos);
        if (e == null || e.expires < level.getGameTime()) return;
        if (e.placement && state.is(e.block)) placed++;
        else if (!e.placement && !state.is(e.block) && (state.isAir() || !state.getFluidState().isEmpty())) mined++;
    }

    public static void main(String[] args) {
        Goal exact = new GoalBlock(new BlockPos(-17, 120, 31));
        assert exact.isInGoal(-17, 120, 31) && !exact.isInGoal(-17, 119, 31);
        assert !exact.isInGoal(-16, 120, 31);
        Goal area = new GoalXZ(-17, 31);
        assert area.isInGoal(-17, -60, 31) && area.isInGoal(-17, 250, 31);
        assert speed(10, 40) == 5 && speed(0, 0) == 0;
        Config cfg = new Config(); cfg.explorerEnabled = true; cfg.explorerY = 120;
        cfg.explorerRetries = -1; cfg.explorerMaxFall = 24; cfg.explorerTargetSec = 0; cfg.clampAll();
        assert cfg.explorerRetries == 0 && cfg.explorerMaxFall == 3 && cfg.explorerTargetSec == 30;
        Config copy = cfg.copy(); assert copy.explorerEnabled && copy.explorerY == 120 && copy.explorerBoats;
        System.out.println("TerrainExplorer: exact XYZ, all-height goals, metrics and configuration passed");
    }
}
