package com.damia.movrand;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.*;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.*;
import net.minecraft.world.phys.*;
import net.minecraft.world.phys.shapes.CollisionContext;

import java.nio.file.Path;
import java.util.*;

/** Survival placement with Baritone approach paths and server-confirmed, exact-state verification. */
public final class LitematicaBuilder {
    public enum Phase { EMPTY, READY, BUILDING, VERIFYING, BLOCKED, DONE }
    private enum Action { PLACE, ADJUST, BREAK, FLUID, TOOL, REFILL }
    private record Aim(BlockHitResult hit, double yaw, double pitch) {}
    private final Config cfg;
    private final NativeNavigation nav;
    private LitematicPlan plan;
    private List<LitematicPlan.Cell> cells = List.of();
    private ClientLevel world;
    private LitematicPlan.Cell target;
    private Action action;
    private Aim aim;
    private int cursor, matches, idlePasses, targetTicks, cooldown, pendingTicks, attempts, stablePasses;
    private boolean changed, pending, acknowledged, mining;
    private boolean refilling;
    private int refillCollected;
    private final Set<BlockPos> failedWaterSources = new HashSet<>();
    private final Set<BlockPos> placedCoral = new HashSet<>();
    private BlockState before;
    private String obstacle = "";
    private final Set<BlockPos> rejectedStands = new HashSet<>();
    private final Map<BlockPos, String> issues = new LinkedHashMap<>();
    private Map<BlockPos, BlockState> planned = Map.of();
    private volatile Set<BlockPos> navigationReserved = Set.of();
    private final Map<BlockPos, Block> navigationEdits = new HashMap<>();
    private final Set<BlockPos> temporarySupports = new LinkedHashSet<>();
    private final Deque<LitematicPlan.Cell> suspended = new ArrayDeque<>();
    private int readyTick = -1, readyTicks;
    private double readyYaw, readyPitch;
    private int loadVersion;
    private BlockPos loadWaypoint;
    private int creativeSlot = -1;
    private ItemStack creativeStack = ItemStack.EMPTY;
    public Phase phase = Phase.EMPTY;
    public String status = "Choose a schematic";
    public int verified, interactions;
    public String interactionWait = "";

    public LitematicaBuilder(Config cfg) { this.cfg = cfg; nav = new NativeNavigation(cfg); }
    public int total() { return cells.size(); }
    public boolean loaded() { return plan != null; }
    public void pauseNavigation() { nav.reset(); readyTicks = 0; }
    public static boolean previewContext(BlockPlaceContext context) { return context instanceof PreviewContext; }
    public String extras() {
        return plan == null ? "" : plan.entities() + " saved entities, " + plan.blockEntities()
                + " block-entity records: contents, text and entity data require manual setup";
    }
    public String targetDescription() { return target == null ? "—" : target.pos().toShortString() + " " + target.state(); }
    public List<String> issues() { return List.copyOf(issues.values()); }
    public List<String> materials() {
        Map<String, Integer> counts = new TreeMap<>();
        for (var c : cells) {
            BlockState state = c.state();
            if (state.isAir() || state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
                    && state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER
                    || state.hasProperty(BedBlock.PART) && state.getValue(BedBlock.PART) == BedPart.HEAD) continue;
            int count = state.hasProperty(SlabBlock.TYPE) && state.getValue(SlabBlock.TYPE) == SlabType.DOUBLE ? 2 : 1;
            for (String name : List.of("layers", "candles", "pickles", "eggs", "flower_amount", "segment_amount")) {
                Property<?> property = state.getBlock().getStateDefinition().getProperty(name);
                if (property instanceof IntegerProperty p) count = state.getValue(p);
            }
            Item item = placementState(state).getBlock().asItem();
            String label = item == Items.AIR ? state.getBlock().getName().getString() + " (special setup)" : new ItemStack(item).getHoverName().getString();
            counts.merge(label, count, Integer::sum);
            if (state.hasProperty(BlockStateProperties.WATERLOGGED) && state.getValue(BlockStateProperties.WATERLOGGED))
                counts.merge("Water bucket uses", 1, Integer::sum);
        }
        return counts.entrySet().stream().map(e -> e.getValue() + " × " + e.getKey()).toList();
    }

    public void load() {
        beginLoad();
        try { install(LitematicPlan.read(Path.of(cfg.builderFile))); }
        catch (Exception e) { status = "Cannot load: " + e.getMessage(); }
    }
    public void loadAsync(Runnable finished) {
        beginLoad();
        int version = loadVersion;
        String file = cfg.builderFile;
        java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            try { return LitematicPlan.read(Path.of(file)); }
            catch (Exception e) { throw new java.util.concurrent.CompletionException(e); }
        }).whenComplete((read, error) -> Minecraft.getInstance().execute(() -> {
            if (version != loadVersion) return;
            if (error != null) status = "Cannot load: " + (error.getCause() == null ? error : error.getCause()).getMessage();
            else try { install(read); } catch (RuntimeException e) { status = "Cannot load: " + e.getMessage(); }
            finished.run();
        }));
    }
    private void beginLoad() {
        loadVersion++;
        stop();
        cfg.builderEnabled = false;
        plan = null;
        cells = List.of();
        phase = Phase.EMPTY;
        status = "Reading and validating schematic…";
    }
    private void install(LitematicPlan read) {
        cells = read.placed(new BlockPos(cfg.builderX, cfg.builderY, cfg.builderZ), rotation(), mirror());
        planned = new HashMap<>();
        for (var c : cells) planned.put(c.pos(), c.state());
        navigationReserved = cells.stream().filter(c -> !c.state().isAir()).map(LitematicPlan.Cell::pos).collect(java.util.stream.Collectors.toUnmodifiableSet());
        // Keep cleanup obligations across reloads in the same world. Never apply
        // positions from a previous dimension/server to a newly loaded world.
        if (world != Minecraft.getInstance().level) { temporarySupports.clear(); navigationEdits.clear(); placedCoral.clear(); creativeSlot = -1; }
        plan = read;
        phase = Phase.READY;
        status = "Loaded " + cells.size() + " cells. Start when ready; creative auto-fetch supplies missing materials when enabled.";
        world = null;
        restart();
    }
    private Rotation rotation() { return Rotation.values()[Math.floorMod(cfg.builderRotation, 4)]; }
    private Mirror mirror() { return Mirror.values()[Math.floorMod(cfg.builderMirror, 3)]; }
    public void invalidate() { loadVersion++; stop(); cfg.builderEnabled = false; plan = null; cells = List.of(); phase = Phase.EMPTY; status = "Settings changed — load the schematic again"; }
    public void stop() {
        nav.reset(); target = null; pending = acknowledged = mining = refilling = false; aim = null; suspended.clear(); readyTicks = 0; loadWaypoint = null;
    }
    public void restart() {
        stop(); cursor = matches = idlePasses = targetTicks = cooldown = stablePasses = 0;
        changed = false; verified = interactions = 0; obstacle = ""; issues.clear();
        failedWaterSources.clear();
        if (plan != null) phase = Phase.READY;
    }
    private void block(String reason) {
        stop(); phase = Phase.BLOCKED; status = reason;
    }

    public Bot.Steer tick(Minecraft mc) {
        Bot.Steer steer = new Bot.Steer();
        if (plan == null) { block("Load a schematic before starting"); return steer; }
        if (phase == Phase.BLOCKED || phase == Phase.DONE) return steer;
        if (world == null) {
            world = mc.level;
            for (var c : cells) if (c.pos().getY() < world.getMinY() || c.pos().getY() >= world.getMaxY()
                    || !world.getWorldBorder().isWithinBounds(c.pos())) {
                block("Schematic crosses the world border or build height at " + c.pos().toShortString()); return steer;
            }
        } else if (world != mc.level) { block("World changed — reload the schematic to bind it to this world"); return steer; }
        if (mc.player.containerMenu != mc.player.inventoryMenu) { status = "Close the container to continue building"; nav.reset(); return steer; }
        // Do not change Baritone's selected scaffold while it is jumping/pillaring.
        // Re-selecting the schematic item every tick prevents the landing block being placed.
        if (nav.active() && NativeNavigation.finishingCriticalMove() && NativeNavigation.yieldFor(null)) {
            steer.externalNavigation = true; status = "Finishing scaffold approach"; return steer;
        }
        if (cooldown > 0) { cooldown--; return steer; }
        if (refilling && pending && itemCount(mc, Items.WATER_BUCKET) > refillCollected) {
            refillCollected = itemCount(mc, Items.WATER_BUCKET);
            pending = acknowledged = false; aim = null; cooldown = 5;
            if (refillCollected >= 4 || inventorySlot(mc, Items.BUCKET) < 0) {
                refilling = false; target = null; nav.reset();
            }
            return steer;
        }
        if (pending) {
            if (acknowledged) {
                changed |= world.getBlockState(target.pos()) != before;
                pending = acknowledged = false;
                cooldown = delay();
                aim = null;
            } else if (++pendingTicks > 100) {
                // A prediction with no server acknowledgement never counts as a successful build.
                block("No server confirmation at " + target.pos().toShortString() + "; retry after checking the connection/permissions");
            }
            return steer;
        }
        if (target == null && !suspended.isEmpty()) { target = suspended.pop(); targetTicks = attempts = 0; rejectedStands.clear(); }
        if (target == null) select(mc);
        if (target == null || phase == Phase.BLOCKED || phase == Phase.DONE) return steer;
        if (!world.hasChunkAt(target.pos())) {
            if (++targetTicks > 1200) { defer("Could not load chunk at " + target.pos().toShortString()); return steer; }
            if (loadWaypoint == null) {
                Vec3 toward = Vec3.atCenterOf(target.pos()).subtract(mc.player.position()).multiply(1, 0, 1);
                if (toward.lengthSqr() < 16) { status = "Waiting for the server to send the target chunk"; return steer; }
                loadWaypoint = BlockPos.containing(mc.player.position().add(toward.normalize().scale(Math.min(16, toward.length()))));
            }
            BlockPos waypoint = loadWaypoint;
            var result = nav.tick(new PathMove.Ctx(mc, mc.player, world, cfg), steer, waypoint,
                    (x, y, z) -> Math.abs(x - waypoint.getX()) <= 2 && Math.abs(z - waypoint.getZ()) <= 2, Set.of(), false);
            status = "Loading build area · " + nav.status;
            if (result == Pathing.Nav.ARRIVED) { loadWaypoint = null; nav.reset(); }
            if (result == Pathing.Nav.NO_ROUTE) defer("No route towards unloaded chunk " + target.pos().toShortString());
            return steer;
        }
        if (loadWaypoint != null) { loadWaypoint = null; targetTicks = 0; nav.reset(); }
        BlockState current = world.getBlockState(target.pos()), want = target.state();
        if (mining && current != before) {
            if (++pendingTicks > 100) block("No server confirmation for clearing " + target.pos().toShortString());
            return steer;
        }
        if (!refilling && current == want) {
            if (want.isAir()) temporarySupports.remove(target.pos());
            target = null; aim = null; return steer;
        }
        if (current.isAir() && (want.hasProperty(BedBlock.PART) && want.getValue(BedBlock.PART) == BedPart.HEAD
                || want.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF) && want.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER)) {
            defer("Waiting for the companion block at " + targetDescription()); return steer;
        }
        if (++targetTicks > 360) { defer("Cannot reach or place " + targetDescription() + " · " + interactionWait); return steer; }
        phase = Phase.BUILDING;
        if (refilling) action = Action.REFILL;
        else if (adjustment(current, want)) action = Action.ADJUST;
        else if (soilTransform(current, want)) action = Action.TOOL;
        else if (needsFluid(current, want)) action = Action.FLUID;
        else if (canAdd(current, want)) action = Action.PLACE;
        else {
            if (current.is(want.getBlock()) && compatible(current, want)) {
                defer("Waiting for neighbours, growth or redstone state at " + targetDescription()); return steer;
            }
            boolean ownDeadCoral = placedCoral.contains(target.pos()) && want.getBlock() instanceof CoralBlock
                    && current.getBlock().asItem().toString().equals("minecraft:dead_" + want.getBlock().asItem().toString().replace("minecraft:", ""));
            if (ownDeadCoral && !wetNeighbours(target.pos())) { defer("Waiting for water before replacing dried coral at " + targetDescription()); return steer; }
            if ((!cfg.builderReplace && !temporarySupports.contains(target.pos()) && !ownDeadCoral) || !current.getFluidState().isEmpty() || current.hasBlockEntity()
                    || current.getDestroySpeed(world, target.pos()) < 0 || Storage.protectedWorldBlock(cfg, world, target.pos())) {
                defer("Clear/prepare " + targetDescription() + " (existing " + current + ")"); return steer;
            }
            action = Action.BREAK;
        }
        if (action == Action.REFILL) {
            if (!renewableWater(target.pos()) && !pending) { defer("Water source is no longer renewable at " + target.pos().toShortString()); return steer; }
            if (!selectItem(mc, Items.BUCKET)) return steer;
        } else if (action == Action.PLACE || action == Action.FLUID) {
            if (action == Action.FLUID && want.hasProperty(LiquidBlock.LEVEL) && want.getValue(LiquidBlock.LEVEL) != 0) {
                defer("Waiting for fluid to flow into " + targetDescription()); return steer;
            }
            Item item = action == Action.FLUID ? (want.is(Blocks.LAVA) ? Items.LAVA_BUCKET : Items.WATER_BUCKET) : placementState(want).getBlock().asItem();
            if (item == Items.AIR) { defer("No survival placement item for " + targetDescription()); return steer; }
            if (!selectItem(mc, item)) return steer;
        } else if (action == Action.TOOL) {
            var tag = want.is(Blocks.FARMLAND) ? net.minecraft.tags.ItemTags.HOES : net.minecraft.tags.ItemTags.SHOVELS;
            Item tool = Items.AIR;
            for (int i = 0; i < 36; i++) {
                var stack = mc.player.getInventory().getItem(i);
                if (!cfg.slotProtected(i) && stack.is(tag)) { tool = stack.getItem(); break; }
            }
            if (tool == Items.AIR && creativeMaterials(mc)) tool = want.is(Blocks.FARMLAND) ? Items.DIAMOND_HOE : Items.DIAMOND_SHOVEL;
            if (tool == Items.AIR) { defer("Supply a " + (want.is(Blocks.FARMLAND) ? "hoe" : "shovel") + " for " + targetDescription()); return steer; }
            if (!selectItem(mc, tool)) return steer;
        } else if (action == Action.ADJUST) {
            // Empty hand avoids accidentally placing a held block or opening a secondary item action.
            int empty = -1;
            for (int i = 0; i < 9; i++) if (mc.player.getInventory().getItem(i).isEmpty() && !cfg.slotProtected(i)) { empty = i; break; }
            if (empty < 0) {
                for (int i = 9; i < 36; i++) if (!cfg.slotProtected(i) && mc.player.getInventory().getItem(i).isEmpty()) {
                    for (int j = 0; j < 9; j++) if (!cfg.slotProtected(j)) {
                        mc.gameMode.handleContainerInput(mc.player.inventoryMenu.containerId,
                                Backpack.menuSlotFor(mc.player.inventoryMenu, mc.player, i), j, ContainerInput.SWAP, mc.player);
                        cooldown = 5; return steer;
                    }
                }
                defer("Free an unprotected inventory slot for configuring blocks"); return steer;
            }
            mc.player.getInventory().setSelectedSlot(empty);
        } else {
            Item tool = mc.player.getInventory().getItem(Bot.bestToolSlot(mc.player, current)).getItem();
            float speed = 0;
            for (int i = 0; i < 36; i++) {
                var stack = mc.player.getInventory().getItem(i);
                if (!cfg.slotProtected(i) && stack.getDestroySpeed(current) > speed) { speed = stack.getDestroySpeed(current); tool = stack.getItem(); }
            }
            if (tool != Items.AIR && !selectItem(mc, tool)) return steer;
        }

        // Plan the eye height that the interaction will actually use. A standing-only
        // sightline must not alternate forever between crouching and replanning.
        double eyeHeight = mc.player.getEyeHeight(sneakForAction() ? net.minecraft.world.entity.Pose.CROUCHING : net.minecraft.world.entity.Pose.STANDING);
        Vec3 eyes = mc.player.position().add(0, eyeHeight, 0);
        Aim found = findAim(mc, eyes);
        if (found == null) {
            aim = null;
            if (cfg.builderScaffold && !navigationBlocks(mc)) return steer;
            var result = nav.tick(new PathMove.Ctx(mc, mc.player, world, cfg), steer, target.pos(),
                    (x, y, z) -> !rejectedStands.contains(new BlockPos(x, y, z))
                            && findAim(mc, new Vec3(x + 0.5, Avoidance.standingY(world, x, y, z) + eyeHeight, z + 0.5)) != null, Set.of(), cfg.builderScaffold);
            status = "Approaching " + target.pos().toShortString() + " · " + nav.status;
            // A path ends anywhere inside a cell; the actual eye can be on the wrong side of
            // a facing boundary even though the cell centre had a valid placement angle.
            if (result == Pathing.Nav.ARRIVED) { rejectedStands.add(mc.player.blockPosition()); nav.reset(); }
            if (result == Pathing.Nav.NO_ROUTE && !temporarySupport(mc)) defer("No accessible placement angle/support at " + targetDescription());
            return steer;
        }
        nav.reset();
        aim = found;
        steer.precise = true;
        steer.lookAt(aim.yaw, aim.pitch);
        steer.sneak = sneakForAction();
        if (action == Action.BREAK) {
            if (!MineSafety.mayStartBreaking(target.pos())) { defer("Mining safety refused " + targetDescription()); return new Bot.Steer(); }
            if (!mining) { before = current; mining = true; pendingTicks = 0; }
            steer.attackAt(target.pos());
        } else steer.builderAction = true;
        status = switch (action) {
            case PLACE -> "Placing "; case ADJUST -> "Configuring "; case BREAK -> "Clearing "; case FLUID -> "Adding fluid at "; case TOOL -> "Preparing soil at "; case REFILL -> "Refilling water bucket at ";
        } + target.pos().toShortString();
        return steer;
    }

    private int delay() {
        double min = Double.isFinite(cfg.builderDelayMin) ? Math.clamp(cfg.builderDelayMin, .1, 10) : .2;
        double max = Double.isFinite(cfg.builderDelayMax) ? Math.clamp(cfg.builderDelayMax, min, 10) : min;
        return Math.max(2, Rng.ticks(min, max));
    }
    private void select(Minecraft mc) {
        // A bounded sweep retries dependencies on subsequent passes: supports before attachments,
        // doors/beds can fill companion cells, and fluids/connections are checked after updates settle.
        phase = Phase.VERIFYING;
        for (int budget = 0; budget < 256 && cursor < cells.size(); budget++) {
            var cell = cells.get(cursor++);
            if (!cfg.builderClearAir && cell.state().isAir()) { matches++; continue; }
            if (world.hasChunkAt(cell.pos()) && world.getBlockState(cell.pos()) == cell.state()) { matches++; issues.remove(cell.pos()); continue; }
            target = cell; targetTicks = attempts = 0; aim = null; rejectedStands.clear();
            return;
        }
        status = "Verifying " + cursor + " / " + cells.size();
        if (cursor < cells.size()) return;
        verified = matches;
        if (matches == cells.size()) {
            for (BlockPos pos : List.copyOf(temporarySupports)) {
                if (planned.containsKey(pos) && !planned.get(pos).isAir()) { temporarySupports.remove(pos); continue; }
                if (world.hasChunkAt(pos) && world.getBlockState(pos).isAir()) { temporarySupports.remove(pos); continue; }
                target = new LitematicPlan.Cell(pos, Blocks.AIR.defaultBlockState());
                targetTicks = attempts = 0; aim = null; rejectedStands.clear();
                return;
            }
            if (++stablePasses >= 2) {
                phase = Phase.DONE;
                status = "Block states verified" + (cfg.builderClearAir ? " including air" : " (air ignored)")
                        + (plan.entities() + plan.blockEntities() > 0 ? "; saved entity/NBT data needs manual setup" : "");
                nav.reset(); return;
            }
        } else {
            stablePasses = 0;
            if (!changed && ++idlePasses >= 2) {
                block((cells.size() - matches) + " unresolved cells. " + obstacle + " · Fix the cause and retry."); return;
            }
        }
        if (changed) idlePasses = 0;
        cursor = matches = 0; changed = false; cooldown = 20;
    }
    private void defer(String reason) {
        if (refilling) { failedWaterSources.add(target.pos()); refilling = false; }
        if (cfg.verboseLogging) MovRand.LOG.info("[builder] {}", reason);
        if (target != null && (issues.size() < 128 || issues.containsKey(target.pos()))) issues.put(target.pos(), reason);
        if (target != null && target.state().isAir() && temporarySupports.contains(target.pos())) { block("Support cleanup failed: " + reason); return; }
        obstacle = reason; status = reason; nav.reset(); target = null; aim = null; mining = false;
    }
    private boolean temporarySupport(Minecraft mc) {
        if (!cfg.builderScaffold || action != Action.PLACE || suspended.size() >= 16 || temporarySupports.size() >= 256
                || target.state().getBlock() instanceof FallingBlock || !target.state().canSurvive(world, target.pos())) return false;
        // A neighbour in the plan is not yet a support. Floating groups (for example
        // paired chests with hoppers above) otherwise wait on each other forever.
        int slot = -1;
        if (!creativeMaterials(mc) && Bot.buildingBlockCount(mc.player, cfg, true) <= cfg.bridgeKeepBlocks) return false;
        for (int i = 0; i < 36; i++) if (!cfg.slotProtected(i) && Bot.usableBuildingStack(mc.player.getInventory().getItem(i), cfg)) { slot = i; break; }
        if (slot < 0 && !creativeMaterials(mc)) return false;
        BlockState support = slot < 0 ? Blocks.COBBLESTONE.defaultBlockState()
                : ((BlockItem) mc.player.getInventory().getItem(slot).getItem()).getBlock().defaultBlockState();
        Direction[] directions = target.state().getBlock() instanceof HopperBlock
                ? new Direction[]{target.state().getValue(HopperBlock.FACING)}
                : new Direction[]{Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST, Direction.UP};
        for (Direction side : directions) {
            BlockPos pos = target.pos().relative(side);
            if (!world.hasChunkAt(pos) || !world.getBlockState(pos).isAir() || temporarySupports.contains(pos)
                    || planned.containsKey(pos) && !planned.get(pos).isAir()
                    || !world.getWorldBorder().isWithinBounds(pos) || pos.getY() < world.getMinY() || pos.getY() >= world.getMaxY()
                    || Storage.protectedWorldBlock(cfg, world, pos)) continue;
            suspended.push(target);
            target = new LitematicPlan.Cell(pos, support);
            temporarySupports.add(pos);
            targetTicks = attempts = 0; rejectedStands.clear(); nav.reset();
            return true;
        }
        return false;
    }
    private boolean selectItem(Minecraft mc, Item item) {
        var player = mc.player;
        for (int i = 0; i < 36; i++) {
            if (cfg.slotProtected(i) || !player.getInventory().getItem(i).is(item)) continue;
            if (i < 9) { player.getInventory().setSelectedSlot(i); return true; }
            int dest = -1;
            for (int j = 0; j < 9; j++) if (!cfg.slotProtected(j) && !player.getInventory().getItem(j).isEmpty()) { dest = j; break; }
            // Keep an empty hotbar slot available for repeater/comparator/lever interactions.
            if (dest < 0) for (int j = 0; j < 9; j++) if (!cfg.slotProtected(j)) { dest = j; break; }
            if (dest < 0) break;
            int menuSlot = Backpack.menuSlotFor(player.inventoryMenu, player, i);
            if (menuSlot >= 0) mc.gameMode.handleContainerInput(player.inventoryMenu.containerId, menuSlot, dest, ContainerInput.SWAP, player);
            cooldown = 5; return false;
        }
        if (creativeMaterials(mc)) return supplyCreativeItem(mc, item);
        if (item == Items.WATER_BUCKET && startRefill(mc)) return false;
        defer("Missing unprotected material: " + new ItemStack(item).getHoverName().getString()); return false;
    }

    private int inventorySlot(Minecraft mc, Item item) {
        for (int i = 0; i < 36; i++) if (!cfg.slotProtected(i) && mc.player.getInventory().getItem(i).is(item)) return i;
        return -1;
    }

    private int itemCount(Minecraft mc, Item item) {
        int count = 0;
        for (int i = 0; i < 36; i++) if (!cfg.slotProtected(i) && mc.player.getInventory().getItem(i).is(item)) count += mc.player.getInventory().getItem(i).getCount();
        return count;
    }

    private boolean wetNeighbours(BlockPos pos) {
        for (Direction side : Direction.values()) if (world.getFluidState(pos.relative(side)).is(net.minecraft.tags.FluidTags.WATER)) return true;
        return false;
    }

    private boolean renewableWater(BlockPos pos) {
        if (!world.hasChunkAt(pos) || !world.getBlockState(pos).is(Blocks.WATER) || !world.getFluidState(pos).isSource()
                || Storage.protectedWorldBlock(cfg, world, pos)) return false;
        int sources = 0;
        for (Direction side : Direction.Plane.HORIZONTAL) {
            var fluid = world.getFluidState(pos.relative(side));
            if (fluid.is(net.minecraft.tags.FluidTags.WATER) && fluid.isSource()) sources++;
        }
        var below = world.getBlockState(pos.below());
        return sources >= 2 && (below.isSolid() || below.getFluidState().is(net.minecraft.tags.FluidTags.WATER) && below.getFluidState().isSource());
    }

    private boolean startRefill(Minecraft mc) {
        if (inventorySlot(mc, Items.BUCKET) < 0 || suspended.size() >= 16) return false;
        BlockPos best = null;
        double distance = Double.POSITIVE_INFINITY;
        for (BlockPos pos : BlockPos.betweenClosed(mc.player.blockPosition().offset(-32, -12, -32), mc.player.blockPosition().offset(32, 8, 32))) {
            double d = pos.distToCenterSqr(mc.player.position());
            if (d < distance && !failedWaterSources.contains(pos) && renewableWater(pos)) { best = pos.immutable(); distance = d; }
        }
        if (best == null) return false;
        suspended.push(target); target = new LitematicPlan.Cell(best, Blocks.WATER.defaultBlockState());
        refilling = true; refillCollected = 0; targetTicks = attempts = 0; readyTicks = 0; aim = null; rejectedStands.clear(); nav.reset();
        return true;
    }

    private boolean navigationBlocks(Minecraft mc) {
        if (Bot.buildingBlockCount(mc.player, cfg) > cfg.bridgeKeepBlocks) return true;
        var inventory = mc.player.getInventory();
        for (int i = 9; i < 36; i++) if (!cfg.slotProtected(i) && Bot.usableBuildingStack(inventory.getItem(i), cfg)) {
            for (int j = 8; j >= 0; j--) if (!cfg.slotProtected(j) && j != inventory.getSelectedSlot()) {
                mc.gameMode.handleContainerInput(mc.player.inventoryMenu.containerId,
                        Backpack.menuSlotFor(mc.player.inventoryMenu, mc.player, i), j, ContainerInput.SWAP, mc.player);
                cooldown = 5; return false;
            }
        }
        return true; // No expendable material: the normal no-route/material report still applies.
    }

    /** Immutable schematic reservation, also read by Baritone's search worker. */
    public boolean navigationMayPlace(BlockPos pos) { return !navigationReserved.contains(pos); }

    public boolean expectNavigationPlacement(ClientLevel level, BlockPos pos, Block block) {
        if (!cfg.builderEnabled || !nav.active() || level != world) return true;
        if (!navigationMayPlace(pos) || !level.getBlockState(pos).isAir() || temporarySupports.size() >= 256
                || Storage.protectedWorldBlock(cfg, level, pos)) return false;
        navigationEdits.put(pos.immutable(), block);
        return true;
    }

    private boolean creativeMaterials(Minecraft mc) {
        return cfg.builderCreativeMaterials && mc.player.isCreative();
    }

    private boolean supplyCreativeItem(Minecraft mc, Item item) {
        var inventory = mc.player.getInventory();
        if (creativeSlot < 0 || cfg.slotProtected(creativeSlot)
                || !ItemStack.matches(inventory.getItem(creativeSlot), creativeStack)) {
            creativeSlot = -1;
            for (int i = 0; i < 9; i++) if (!cfg.slotProtected(i) && inventory.getItem(i).isEmpty()) { creativeSlot = i; break; }
        }
        if (creativeSlot < 0) {
            // Move an existing hotbar item into an empty bag slot before supplying anything.
            for (int i = 9; i < 36; i++) if (!cfg.slotProtected(i) && inventory.getItem(i).isEmpty()) {
                for (int j = 0; j < 9; j++) if (!cfg.slotProtected(j)) {
                    int menuSlot = Backpack.menuSlotFor(mc.player.inventoryMenu, mc.player, i);
                    mc.gameMode.handleContainerInput(mc.player.inventoryMenu.containerId, menuSlot, j, ContainerInput.SWAP, mc.player);
                    cooldown = 5;
                    return false;
                }
            }
            defer("Free an unprotected inventory slot and hotbar slot for creative materials");
            return false;
        }
        ItemStack stack = new ItemStack(item);
        stack.setCount(stack.getMaxStackSize());
        inventory.setItem(creativeSlot, stack);
        creativeStack = stack.copy();
        mc.gameMode.handleCreativeModeItemAdd(stack, Backpack.menuSlotFor(mc.player.inventoryMenu, mc.player, creativeSlot));
        inventory.setSelectedSlot(creativeSlot);
        cooldown = 5;
        return false;
    }

    /** Called after the controller's real smoothed camera has been applied and raycast. */
    public void interact(Minecraft mc) {
        if (!cfg.builderEnabled || target == null || aim == null || pending || cooldown > 0 || mc.gameMode == null) return;
        HitResult actualHit = action == Action.REFILL ? world.clip(new ClipContext(mc.player.getEyePosition(),
                mc.player.getEyePosition().add(mc.player.getViewVector(1).scale(mc.player.blockInteractionRange())),
                ClipContext.Block.OUTLINE, ClipContext.Fluid.SOURCE_ONLY, mc.player)) : mc.hitResult;
        if (!(actualHit instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) { interactionWait = "Crosshair missed"; return; }
        if (!hit.getBlockPos().equals(aim.hit.getBlockPos()) || hit.getDirection() != aim.hit.getDirection()) { interactionWait = "Crosshair on " + hit.getBlockPos().toShortString() + " " + hit.getDirection() + ", needs " + aim.hit.getBlockPos().toShortString() + " " + aim.hit.getDirection(); return; }
        var player = mc.player;
        if (action == Action.PLACE && !placement(mc, hit, player.getYRot(), player.getXRot(), false)) { interactionWait = "Vanilla placement does not match the required state/position"; return; }
        if (action == Action.ADJUST && !adjustment(world.getBlockState(target.pos()), target.state())) return;
        if (action == Action.REFILL && !renewableWater(target.pos())) return;
        if (sneakForAction() != player.isShiftKeyDown()) { interactionWait = "Changing sneak state"; return; }
        // Server players use their head rotation for six-way blocks. Let it catch up over
        // several normal ticks, and restart the settling window whenever aim is interrupted.
        if (readyTick != player.tickCount - 1 || Math.abs(Human.wrap(player.getYRot() - readyYaw)) > 2
                || Math.abs(player.getXRot() - readyPitch) > 2) readyTicks = 0;
        readyTick = player.tickCount; readyYaw = player.getYRot(); readyPitch = player.getXRot();
        if (++readyTicks < 4) { interactionWait = "Settling aim " + readyTicks; return; }
        interactionWait = "Waiting for server";
        if (++attempts > 32) { defer("Repeated interactions did not produce " + targetDescription()); return; }
        before = world.getBlockState(target.pos());
        beginPending();
        // Vanilla's next movement tick has not sent this tick's smoothed camera yet.
        // Send the actual visible rotation before useItemOn; useItem includes it in its packet.
        player.connection.send(new net.minecraft.network.protocol.game.ServerboundMovePlayerPacket.Rot(
                player.getYRot(), player.getXRot(), player.onGround(), player.horizontalCollision));
        var result = action == Action.FLUID || action == Action.REFILL ? mc.gameMode.useItem(player, InteractionHand.MAIN_HAND)
                : mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit);
        if (result.consumesAction()) { player.swing(InteractionHand.MAIN_HAND); interactions++; }
        else { pending = false; cooldown = delay(); }
    }
    private void beginPending() { pending = true; acknowledged = false; pendingTicks = 0; }
    private boolean sneakForAction() {
        // In 26.2, sneaking with a bucket deliberately bypasses waterlogging and pours
        // into the neighbouring cell. Only use that behaviour for a standalone source.
        return action == Action.PLACE && !world.getBlockState(target.pos()).is(target.state().getBlock())
                || action == Action.FLUID && (target.state().is(Blocks.WATER) || target.state().is(Blocks.LAVA));
    }
    public void confirmEdit(ClientLevel level, BlockPos pos, BlockState state) {
        if (level == world && navigationEdits.containsKey(pos) && state.is(navigationEdits.get(pos))) {
            navigationEdits.remove(pos); temporarySupports.add(pos.immutable()); changed = true;
        }
        if (pending && level == world && target != null && pos.equals(target.pos())) {
            acknowledged = true;
            if (action == Action.PLACE && state.getBlock() instanceof CoralBlock) placedCoral.add(pos.immutable());
        }
        if (mining && level == world && target != null && pos.equals(target.pos()) && state != before) { mining = false; changed = true; }
    }

    private Aim findAim(Minecraft mc, Vec3 eyes) {
        BlockPos pos = target.pos();
        if (eyes.distanceToSqr(Vec3.atCenterOf(pos)) > 49) return null;
        if (action == Action.REFILL) {
            Vec3 point = Vec3.atCenterOf(pos);
            if (eyes.distanceToSqr(point) > Math.pow(mc.player.blockInteractionRange() - .15, 2)) return null;
            BlockHitResult hit = world.clip(new ClipContext(eyes, point, ClipContext.Block.OUTLINE, ClipContext.Fluid.SOURCE_ONLY, mc.player));
            if (hit.getType() != HitResult.Type.BLOCK || !hit.getBlockPos().equals(pos)) return null;
            Vec3 diff = point.subtract(eyes);
            return new Aim(hit, Math.toDegrees(Math.atan2(-diff.x, diff.z)), -Math.toDegrees(Math.atan2(diff.y, Math.hypot(diff.x, diff.z))));
        }
        if (action == Action.TOOL) return hitAim(mc, eyes, pos, Direction.UP, false);
        if (action == Action.BREAK || action == Action.ADJUST || action == Action.FLUID && !target.state().is(Blocks.WATER) && !target.state().is(Blocks.LAVA))
            return hitAim(mc, eyes, pos, null, false);
        // Trying the target itself supports slabs, snow layers, candles, pickles and other stackable blocks.
        Aim self = hitAim(mc, eyes, pos, null, action == Action.PLACE);
        if (self != null) return self;
        for (Direction d : Direction.values()) {
            Aim result = hitAim(mc, eyes, pos.relative(d), d.getOpposite(), action == Action.PLACE);
            if (result != null) return result;
        }
        return null;
    }
    private Aim hitAim(Minecraft mc, Vec3 eyes, BlockPos anchor, Direction required, boolean placing) {
        // Candidate standing positions must leave the placement cell free. The real player's
        // current body is excluded only during prediction, then checked by vanilla before use.
        double eyeHeight = mc.player.getEyeHeight(sneakForAction() ? net.minecraft.world.entity.Pose.CROUCHING : net.minecraft.world.entity.Pose.STANDING);
        AABB body = new AABB(eyes.x - .3, eyes.y - eyeHeight, eyes.z - .3, eyes.x + .3, eyes.y + .23, eyes.z + .3);
        if (placing && new AABB(target.pos()).intersects(body)) return null;
        if (!world.hasChunkAt(anchor)) return null;
        var shape = world.getBlockState(anchor).getShape(world, anchor);
        if (shape.isEmpty()) return null;
        for (AABB box : shape.toAabbs()) for (Direction face : Direction.values()) {
            if (required != null && face != required) continue;
            // Never prefer the exact half-height boundary: a tiny humanised pitch change
            // there flips a slab/stair from bottom to top despite an otherwise correct ray.
            for (double u : new double[]{.5, .2, .8}) for (double v : new double[]{.25, .75, .5}) {
                double x = box.minX + box.getXsize() * u, y = box.minY + box.getYsize() * v, z = box.minZ + box.getZsize() * u;
                switch (face) {
                    case WEST -> x = box.minX; case EAST -> x = box.maxX;
                    case DOWN -> y = box.minY; case UP -> y = box.maxY;
                    case NORTH -> z = box.minZ; case SOUTH -> z = box.maxZ;
                }
                Vec3 point = new Vec3(anchor.getX() + x, anchor.getY() + y, anchor.getZ() + z);
                if (eyes.distanceToSqr(point) > Math.pow(mc.player.blockInteractionRange() - .15, 2)) continue;
                BlockHitResult hit = world.clip(new ClipContext(eyes, point.subtract(face.getUnitVec3().scale(.002)), ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc.player));
                if (hit.getType() != HitResult.Type.BLOCK || !hit.getBlockPos().equals(anchor) || hit.getDirection() != face) continue;
                Vec3 diff = point.subtract(eyes);
                double yaw = Math.toDegrees(Math.atan2(-diff.x, diff.z)), pitch = -Math.toDegrees(Math.atan2(diff.y, Math.sqrt(diff.x * diff.x + diff.z * diff.z)));
                if (placing && !placement(mc, hit, yaw, pitch, true)) continue;
                if (action == Action.FLUID && (target.state().is(Blocks.WATER) || target.state().is(Blocks.LAVA))
                        && !anchor.relative(face).equals(target.pos())) continue;
                return new Aim(hit, yaw, pitch);
            }
        }
        return null;
    }
    private boolean placement(Minecraft mc, BlockHitResult hit, double yaw, double pitch, boolean simulated) {
        ItemStack stack = mc.player.getMainHandItem();
        if (!(stack.getItem() instanceof BlockItem item)) return false;
        BlockPlaceContext context = simulated ? new PreviewContext(mc.player, stack, hit, yaw, pitch, sneakForAction())
                : new BlockPlaceContext(mc.player, InteractionHand.MAIN_HAND, stack, hit);
        if (!context.canPlace()) return false;
        context = item.updatePlacementContext(context);
        if (context == null || !context.canPlace() || !context.getClickedPos().equals(target.pos())) return false;
        BlockState state = ((com.damia.movrand.mixin.BlockItemPlacementAccessor) item).movrand$placementState(context);
        if (state != null && target.state().getBlock() instanceof ChestBlock
                && target.state().getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
            BlockState partner = world.getBlockState(ChestBlock.getConnectedBlockPos(target.pos(), target.state()));
            if (partner.is(target.state().getBlock()) && state.getValue(ChestBlock.TYPE) != target.state().getValue(ChestBlock.TYPE)) return false;
        }
        return state != null && compatible(state, placementState(target.state())) && state.canSurvive(world, target.pos())
                && (simulated || world.isUnobstructed(state, target.pos(), CollisionContext.placementContext(mc.player)));
    }

    /** Predict candidate angles without moving the real player's camera, even briefly. */
    private static final class PreviewContext extends BlockPlaceContext {
        private final double yaw, pitch;
        private final boolean secondary;
        PreviewContext(LocalPlayer player, ItemStack stack, BlockHitResult hit, double yaw, double pitch, boolean secondary) {
            super(player, InteractionHand.MAIN_HAND, stack, hit); this.yaw = yaw; this.pitch = pitch; this.secondary = secondary;
        }
        @Override public Direction getHorizontalDirection() { return Direction.fromYRot(yaw); }
        @Override public float getRotation() { return (float) yaw; }
        @Override public boolean isSecondaryUseActive() { return secondary; }
        @Override public Direction getNearestLookingDirection() { return ordered()[0]; }
        @Override public Direction getNearestLookingVerticalDirection() { return pitch < 0 ? Direction.UP : Direction.DOWN; }
        private Direction[] ordered() {
            Vec3 view = Vec3.directionFromRotation((float) pitch, (float) yaw);
            Direction[] dirs = Direction.values();
            Arrays.sort(dirs, Comparator.comparingDouble((Direction d) -> -view.dot(d.getUnitVec3())));
            return dirs;
        }
        @Override public Direction[] getNearestLookingDirections() {
            Direction[] dirs = ordered();
            if (!replacingClickedOnBlock()) {
                Direction first = getClickedFace().getOpposite();
                int index = Arrays.asList(dirs).indexOf(first);
                System.arraycopy(dirs, 0, dirs, 1, index); dirs[0] = first;
            }
            return dirs;
        }
    }

    private static BlockState placementState(BlockState want) {
        return want.is(Blocks.FARMLAND) || want.is(Blocks.DIRT_PATH) ? Blocks.DIRT.defaultBlockState() : want;
    }
    private static boolean soilTransform(BlockState current, BlockState want) {
        return (want.is(Blocks.FARMLAND) || want.is(Blocks.DIRT_PATH))
                && (current.is(Blocks.DIRT) || current.is(Blocks.GRASS_BLOCK) || current.is(Blocks.COARSE_DIRT)
                || current.is(Blocks.ROOTED_DIRT) || current.is(Blocks.DIRT_PATH) && want.is(Blocks.FARMLAND));
    }
    private static boolean canAdd(BlockState current, BlockState want) {
        if (want.isAir()) return false;
        if (current.canBeReplaced()) return true;
        if (!current.is(want.getBlock())) return false;
        if (current.hasProperty(BlockStateProperties.SLAB_TYPE)) return current.getValue(BlockStateProperties.SLAB_TYPE) != SlabType.DOUBLE && want.getValue(BlockStateProperties.SLAB_TYPE) == SlabType.DOUBLE;
        for (String name : List.of("layers", "candles", "pickles", "eggs", "flower_amount", "segment_amount")) {
            Property<?> p = current.getBlock().getStateDefinition().getProperty(name);
            if (p instanceof IntegerProperty count && current.getValue(count) < want.getValue(count)) return true;
        }
        return false;
    }
    private static boolean needsFluid(BlockState current, BlockState want) {
        return (want.is(Blocks.WATER) || want.is(Blocks.LAVA)) && current.canBeReplaced()
                || current.is(want.getBlock()) && current.hasProperty(BlockStateProperties.WATERLOGGED)
                && !current.getValue(BlockStateProperties.WATERLOGGED) && want.getValue(BlockStateProperties.WATERLOGGED);
    }
    static boolean adjustment(BlockState current, BlockState want) {
        if (!current.is(want.getBlock()) || current == want) return false;
        String property = current.getBlock() instanceof RepeaterBlock ? "delay"
                : current.getBlock() instanceof ComparatorBlock ? "mode"
                : current.getBlock() instanceof NoteBlock ? "note"
                : current.getBlock() instanceof LeverBlock ? "powered"
                : current.getBlock() instanceof TrapDoorBlock || current.getBlock() instanceof FenceGateBlock || current.getBlock() instanceof DoorBlock ? "open" : "";
        Property<?> p = current.getBlock().getStateDefinition().getProperty(property);
        return p != null && !current.getValue(p).equals(want.getValue(p)) && compatible(current, want);
    }
    static boolean compatible(BlockState placed, BlockState want) {
        if (!placed.is(want.getBlock())) return false;
        for (Property<?> p : want.getProperties()) {
            if (placed.getValue(p).equals(want.getValue(p))) continue;
            String name = p.getName();
            // Placement cannot freeze these values: neighbours, ticks, fluids and subsequent clicks decide them.
            if (Set.of("powered", "power", "lit", "locked", "enabled", "waterlogged", "open", "occupied", "triggered", "extended",
                    "age", "moisture", "distance", "persistent", "instrument", "berries", "stage").contains(name)) continue;
            if (placed.getBlock() instanceof RepeaterBlock && name.equals("delay")
                    || placed.getBlock() instanceof ComparatorBlock && name.equals("mode")
                    || placed.getBlock() instanceof NoteBlock && name.equals("note")) continue;
            if (Set.of("north", "south", "east", "west", "up", "down").contains(name)
                    || name.equals("shape") && !(placed.getBlock() instanceof BaseRailBlock)
                    || name.equals("type") && placed.getBlock() instanceof ChestBlock) continue;
            if (p instanceof IntegerProperty count && Set.of("layers", "candles", "pickles", "eggs", "flower_amount", "segment_amount").contains(name)
                    && placed.getValue(count) <= want.getValue(count)) continue;
            if (p == BlockStateProperties.SLAB_TYPE && want.getValue(BlockStateProperties.SLAB_TYPE) == SlabType.DOUBLE) continue;
            return false;
        }
        return true;
    }
}
