package com.damia.movrand;

import com.mojang.serialization.JsonOps;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.ShulkerBoxMenu;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.*;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.*;

/** Bounded, exclusive storage trips. Every container and cursor transfer is verified before continuing. */
public final class Storage {
    public enum Kind { INVENTORY_SHULKER, WORLD, ENDER, ENDER_SHULKER }
    public enum ItemOrder { INVENTORY, NAME, LARGEST_STACK, FILTER_ORDER }
    public enum SlotOrder { ROWS, REVERSE_ROWS, COLUMNS }

    /** A specific item/slot or a world-bound block, never just “any red shulker”. */
    public static final class Target {
        public Kind kind = Kind.INVENTORY_SHULKER;
        public String name = "Storage", world = "", dimension = "", fingerprint = "", block = "";
        public int inventorySlot = -1, enderSlot = -1, x, y, z, size = 27;
        public boolean enabled = true;
        public List<String> items = new ArrayList<>();
        public List<Integer> slots = new ArrayList<>();
        public ItemOrder itemOrder = ItemOrder.INVENTORY;
        public SlotOrder slotOrder = SlotOrder.ROWS;

        public Target() { for (int i = 0; i < 27; i++) slots.add(i); }
        public BlockPos pos() { return new BlockPos(x, y, z); }
        public void clamp() {
            if (kind == null) { kind = Kind.INVENTORY_SHULKER; enabled = false; }
            if (itemOrder == null) itemOrder = ItemOrder.INVENTORY;
            if (slotOrder == null) slotOrder = SlotOrder.ROWS;
            size = size == 54 ? 54 : 27;
            if (items == null) items = new ArrayList<>();
            items = new ArrayList<>(items.stream().filter(Objects::nonNull).map(Storage::id)
                    .filter(s -> !s.isBlank()).distinct().toList());
            if (slots == null) slots = new ArrayList<>();
            slots = new ArrayList<>(slots.stream().filter(Objects::nonNull)
                    .filter(s -> s >= 0 && s < size).distinct().toList());
            if (name == null) name = "Storage";
            if (world == null) world = "";
            if (dimension == null) dimension = "";
            if (fingerprint == null) fingerprint = "";
            if (block == null) block = "";
        }
        public boolean accepts(String item) { return items.contains(id(item)); }
    }

    private enum Phase { IDLE, SITE, TRAVEL, PREPARE, PLACE_ENDER, OPEN_ENDER, TAKE,
        PLACE_BOX, OPEN_DEST, DEPOSIT, BREAK_BOX, PICK_BOX, REOPEN_ENDER, RETURN,
        BREAK_ENDER, PICK_ENDER, NEXT, FAILED }
    private final Config cfg;
    private final Pathing nav;
    private Phase phase = Phase.IDLE;
    private Target target;
    private final ArrayDeque<Target> queue = new ArrayDeque<>();
    private final Map<Target, List<ItemStack>> views = new IdentityHashMap<>();
    private List<ItemStack> enderView = List.of();
    private String viewWorld = "";
    private BlockPos stand, enderPos, boxPos, boxWork;
    private boolean enderPlaced, boxPlaced, preview, returnBoxes;
	private boolean placementAttempted;
	private int placementCount;
    private int ticks, pause, cooldown, menuId = -1, oldHotbar, handSlot = -1;
    private int boxInventorySlot = -1, enderInventorySlot = -1, pickInventorySlot = -1;
    private ItemStack boxStack = ItemStack.EMPTY;
    private String boxIdentity = "";
    private Set<Integer> oldDrops = Set.of();
	private boolean recoveryArmed;
    private int recoveryBefore;
	private final int[] recoverySlots = new int[36];
	private int swapFrom = -1, swapHotbar, swapState, swapTicks, protectedToolSource = -1, protectedToolHotbar;
	private ItemStack swapSource = ItemStack.EMPTY, swapDest = ItemStack.EMPTY;
    private Transfer transfer;
    private Screen returnScreen;
    private net.minecraft.client.multiplayer.ClientLevel sessionLevel;
    private LocalPlayer sessionPlayer;
    public String status = "Idle";
    public int deposited;

    public Storage(Config cfg) { this.cfg = cfg; nav = new Pathing(cfg); }
    public boolean busy() { return phase != Phase.IDLE && phase != Phase.FAILED; }
    public boolean failed() { return phase == Phase.FAILED; }
    public boolean previewing() { return busy() && preview; }
    public boolean handles(Screen screen) {
        if (!busy() || !(screen instanceof AbstractContainerScreen<?> shown)) return false;
        return menuId >= 0 ? shown.getMenu().containerId == menuId
                : (phase == Phase.OPEN_DEST || phase == Phase.OPEN_ENDER || phase == Phase.REOPEN_ENDER)
                && expectedMenu(shown.getMenu());
    }

    public static String id(String s) { return s == null || s.isBlank() ? "" : s.contains(":") ? s : "minecraft:" + s; }
    public static boolean shulker(ItemStack s) {
        return !s.isEmpty() && s.getItem() instanceof BlockItem b && b.getBlock() instanceof ShulkerBoxBlock;
    }
    public static boolean supported(Block b) {
        return b instanceof ChestBlock || b instanceof BarrelBlock || b instanceof ShulkerBoxBlock;
    }
    /** A chosen storage item must survive the sale/junk/scaffolding passes between trips. */
    public static boolean reserved(Config cfg, ItemStack s) {
        if (!cfg.storageEnabled || s.isEmpty()) return false;
        if (shulker(s) || s.is(Items.ENDER_CHEST) || silk(s)) return true;
        String item = Backpack.itemId(s);
        return cfg.storageTargets.stream().anyMatch(t -> t.enabled && !t.slots.isEmpty()
                && t.world.equals(WorldId.current()) && t.accepts(item));
    }
    public static boolean protectedWorldBlock(Config cfg, net.minecraft.client.multiplayer.ClientLevel level, BlockPos p) {
        if (!cfg.storageEnabled || cfg.storageTargets.isEmpty()) return false;
        String world = WorldId.current(), dimension = level.dimension().identifier().toString();
        for (Target t : cfg.storageTargets) {
            if (!t.enabled || t.kind != Kind.WORLD || !t.world.equals(world) || !t.dimension.equals(dimension)) continue;
            if (t.pos().equals(p)) return true;
            var state = level.getBlockState(t.pos());
            if (state.getBlock() instanceof ChestBlock && state.getValue(ChestBlock.TYPE) != net.minecraft.world.level.block.state.properties.ChestType.SINGLE
                    && t.pos().relative(ChestBlock.getConnectedDirection(state)).equals(p)) return true;
        }
        return false;
    }
    public static List<ItemStack> contents(ItemStack stack) {
        NonNullList<ItemStack> out = NonNullList.withSize(27, ItemStack.EMPTY);
        stack.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY).copyInto(out);
        return out;
    }
    public List<ItemStack> view(Target t) { return views.getOrDefault(t, Collections.nCopies(t.size, ItemStack.EMPTY)); }
    public List<ItemStack> enderView() {
        return viewWorld.equals(WorldId.current()) ? enderView : List.of();
    }
    public static String fingerprint(ItemStack stack, LocalPlayer p) {
        if (stack.isEmpty()) return "";
        return ItemStack.CODEC.encodeStart(p.registryAccess().createSerializationContext(JsonOps.INSTANCE),
                stack.copyWithCount(1)).getOrThrow().toString();
    }
    private static String identity(ItemStack stack, LocalPlayer p) {
        ItemStack copy = stack.copy();
        copy.remove(DataComponents.CONTAINER);
        return fingerprint(copy, p);
    }

    public Target selectShulker(Minecraft mc, int slot, boolean fromEnder) {
        if (busy() || mc.player == null || slot < 0 || slot >= (fromEnder ? 27 : 36)) return null;
        ItemStack stack = fromEnder ? (slot < enderView().size() ? enderView().get(slot) : ItemStack.EMPTY)
                : mc.player.getInventory().getItem(slot);
        if (!shulker(stack)) return null;
        Kind kind = fromEnder ? Kind.ENDER_SHULKER : Kind.INVENTORY_SHULKER;
        Target t = cfg.storageTargets.stream().filter(v -> v.kind == kind && v.world.equals(WorldId.current())
                && (fromEnder ? v.enderSlot == slot : v.inventorySlot == slot)).findFirst().orElse(null);
        if (t == null) { t = new Target(); cfg.storageTargets.add(t); }
        t.kind = kind;
        t.world = WorldId.current();
        t.name = stack.getHoverName().getString() + (fromEnder ? " · ender " : " · bag ") + slot;
        t.inventorySlot = fromEnder ? -1 : slot;
        t.enderSlot = fromEnder ? slot : -1;
        t.fingerprint = fingerprint(stack, mc.player);
        views.put(t, contents(stack));
        return t;
    }

    public Target selectWorld(Minecraft mc, BlockPos pos) {
        if (busy() || mc.level == null || !supported(mc.level.getBlockState(pos).getBlock())) return null;
        String dimension = mc.level.dimension().identifier().toString();
        Target t = cfg.storageTargets.stream().filter(v -> v.kind == Kind.WORLD && v.pos().equals(pos)
                && v.world.equals(WorldId.current()) && v.dimension.equals(dimension)).findFirst().orElse(null);
        if (t == null) { t = new Target(); cfg.storageTargets.add(t); }
        t.kind = Kind.WORLD; t.world = WorldId.current(); t.dimension = dimension;
        t.x = pos.getX(); t.y = pos.getY(); t.z = pos.getZ();
        t.block = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(mc.level.getBlockState(pos).getBlock()).toString();
        t.name = mc.level.getBlockState(pos).getBlock().getName().getString() + " · " + pos.toShortString();
        return t;
    }

    public Target enderTarget() {
        return cfg.storageTargets.stream().filter(t -> t.kind == Kind.ENDER && t.world.equals(WorldId.current()))
                .findFirst().orElseGet(() -> {
                    Target t = new Target(); t.kind = Kind.ENDER; t.world = WorldId.current();
                    t.name = "Ender chest"; cfg.storageTargets.add(t); return t;
                });
    }

    public void inspect(Minecraft mc, Target t, Screen back) {
        if (t != null && begin(mc, List.of(t), true)) { returnScreen = back; mc.setScreenAndShow(null); }
    }
    public void storeNow(Minecraft mc) { begin(mc, eligibleTargets(mc), false); }
    public void acknowledge() {
        if (failed()) { phase = Phase.IDLE; status = "Idle — check any containers left in the world before restarting"; }
    }
    public void cancel(Minecraft mc, String why) { if (busy()) fail(mc, why); }

    /** Runs before selling/tidying; storage owns the hands and navigation until cleanup finishes. */
    public Bot.Steer tick(Minecraft mc) {
        if (!busy()) {
            if (cooldown > 0) cooldown--;
            if (!failed() && cfg.storageEnabled && cfg.movementEnabled && (cooldown == 0 || needsRoom(mc))
                    && mc.gui.screen() == null && !MovRand.controller().destroyer.backpack.busy()) {
                cooldown = 100;
                begin(mc, eligibleTargets(mc), false);
            }
            if (!busy()) return null;
        }
        Bot.Steer steer = new Bot.Steer();
        if (mc.level == null || mc.player == null || mc.level != sessionLevel || mc.player != sessionPlayer
                || !mc.player.isAlive()) { fail(mc, "World, dimension or player changed"); return steer; }
        if (!preview && !cfg.storageEnabled) { fail(mc, "Storage disabled during a trip"); return steer; }
        if (mc.gui.screen() != null && !handles(mc.gui.screen())) {
            fail(mc, "Another screen interrupted storage"); return steer;
        }
        PathMove.Ctx ctx = new PathMove.Ctx(mc, mc.player, mc.level, cfg);
        if (++ticks > (phase == Phase.TRAVEL ? 1200 : 600)) {
            fail(mc, "Timed out: " + phase); return steer;
        }
        if (phase != Phase.SITE && phase != Phase.TRAVEL && phase != Phase.NEXT
                && !safe(ctx, mc.player.blockPosition())) {
            fail(mc, "Storage area became unsafe"); return steer;
        }
        if ((enderPlaced && !safe(ctx, enderPos)) || (boxPlaced && !safe(ctx, boxPos))) {
            fail(mc, "A player or liquid approached the containers"); return steer;
        }
        if (pause > 0) { pause--; return steer; }
        if (swapFrom >= 0) { confirmSwap(mc); return steer; }
        if (transfer != null) {
            if (transfer.tick(mc)) { transfer = null; ticks = 0; pause = 5; }
            return steer;
        }
        switch (phase) {
            case SITE -> findSite(ctx);
            case TRAVEL -> travel(ctx, steer);
            case PREPARE -> prepare(ctx);
            case PLACE_ENDER -> {
                if (!at(ctx, steer, stand)) break;
                if (place(ctx, steer, enderPos, Items.ENDER_CHEST.getDefaultInstance(), enderInventorySlot)) {
                    enderPlaced = true; go(Phase.OPEN_ENDER);
                }
            }
            case OPEN_ENDER -> {
                if (open(ctx, steer, enderPos)) {
                    snapshotEnder(mc);
                    if (preview) go(Phase.BREAK_ENDER);
                    else go(target.kind == Kind.ENDER_SHULKER ? Phase.TAKE : Phase.DEPOSIT);
                }
            }
            case TAKE -> takeShulker(mc);
            case PLACE_BOX -> {
                if (!at(ctx, steer, boxWork)) break;
                if (place(ctx, steer, boxPos, boxStack, boxInventorySlot)) {
                    boxPlaced = true; go(Phase.OPEN_DEST);
                }
            }
            case OPEN_DEST -> {
                BlockPos pos = target.kind == Kind.WORLD ? target.pos() : boxPos;
                if (open(ctx, steer, pos)) {
                    snapshot(mc, target);
                    go(preview ? (boxPlaced ? Phase.BREAK_BOX : Phase.NEXT) : Phase.DEPOSIT);
                }
            }
            case DEPOSIT -> deposit(mc);
            case BREAK_BOX -> {
                if (!close(mc) && breakBlock(ctx, steer, boxPos, false)) go(Phase.PICK_BOX);
            }
            case PICK_BOX -> {
                int slot = collect(ctx, steer, boxPos, boxIdentity, false);
                if (slot >= 0) {
                    nav.reset();
                    boxPlaced = false; boxInventorySlot = slot;
                    target.fingerprint = fingerprint(mc.player.getInventory().getItem(slot), mc.player);
                    target.inventorySlot = slot;
                    if (target.kind == Kind.ENDER_SHULKER && returnBoxes) go(Phase.REOPEN_ENDER);
                    else {
                        if (target.kind == Kind.ENDER_SHULKER) {
                            target.kind = Kind.INVENTORY_SHULKER;
                            target.enderSlot = -1;
                            target.name = mc.player.getInventory().getItem(slot).getHoverName().getString() + " · bag " + slot;
                        }
                        cfg.save(); go(enderPlaced ? Phase.BREAK_ENDER : Phase.NEXT);
                    }
                }
            }
            case REOPEN_ENDER -> { if (open(ctx, steer, enderPos)) go(Phase.RETURN); }
            case RETURN -> returnShulker(mc);
            case BREAK_ENDER -> {
                if (!close(mc) && breakBlock(ctx, steer, enderPos, true)) go(Phase.PICK_ENDER);
            }
            case PICK_ENDER -> {
                if (collect(ctx, steer, enderPos, "", true) >= 0) { nav.reset(); enderPlaced = false; go(Phase.NEXT); }
            }
            case NEXT -> next(mc);
            default -> { }
        }
        steer.status = status;
        return steer;
    }

    private List<Target> eligibleTargets(Minecraft mc) {
        if (mc.player == null || mc.level == null) return List.of();
        return cfg.storageTargets.stream().filter(t -> t.enabled && !t.slots.isEmpty()
                && t.world.equals(WorldId.current()) && (t.kind != Kind.WORLD
                || t.dimension.equals(mc.level.dimension().identifier().toString())) && source(mc.player, t) >= 0).toList();
    }
    private boolean needsRoom(Minecraft mc) {
        if (mc.player == null) return false;
        int empty = 0;
        for (int i = 0; i < 36; i++) if (!cfg.slotProtected(i) && mc.player.getInventory().getItem(i).isEmpty()) empty++;
        return empty <= 3;
    }
    private boolean begin(Minecraft mc, List<Target> targets, boolean inspecting) {
        if (busy() || failed() || targets.isEmpty() || mc.player == null || mc.level == null || mc.gameMode == null) return false;
        if (mc.player.containerMenu != mc.player.inventoryMenu || !mc.player.inventoryMenu.getCarried().isEmpty()
                || MovRand.controller().destroyer.backpack.busy()) { status = "Close the current inventory transaction first"; return false; }
        NativeNavigation.stopAll(); nav.reset();
        sessionLevel = mc.level; sessionPlayer = mc.player;
        queue.clear(); queue.addAll(targets);
        preview = inspecting; returnBoxes = cfg.storageReturnShulkers; returnScreen = null;
        oldHotbar = mc.player.getInventory().getSelectedSlot();
        deposited = 0;
        startTarget(queue.removeFirst());
        return true;
    }
    private void startTarget(Target t) {
        target = t; stand = enderPos = boxPos = boxWork = null;
        enderPlaced = boxPlaced = false; menuId = -1; handSlot = -1;
        boxInventorySlot = enderInventorySlot = pickInventorySlot = -1;
        boxStack = ItemStack.EMPTY; transfer = null;
        go(Phase.SITE);
    }
    private boolean needsEnder() { return target.kind == Kind.ENDER || target.kind == Kind.ENDER_SHULKER; }
    private void go(Phase next) {
        if (failed()) return;
        phase = next; ticks = 0; pause = 6;
        if (next == Phase.BREAK_BOX || next == Phase.BREAK_ENDER) recoveryArmed = false;
        if (next == Phase.PLACE_BOX || next == Phase.PLACE_ENDER) placementAttempted = false;
        status = target.name + " · " + next.name().toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    private void findSite(PathMove.Ctx ctx) {
        if (needsEnder() && silkPick(ctx.player()) < 0) { fail(ctx.mc(), "A Silk Touch pickaxe with durability is required"); return; }
        BlockPos origin = target.kind == Kind.WORLD ? target.pos() : ctx.player().blockPosition();
        if (target.kind == Kind.WORLD && !worldMatches(ctx)) { fail(ctx.mc(), "Selected container is missing or in another dimension"); return; }
        // ponytail: bounded loaded-area search; distant safe sites need a separate user journey.
        for (int r = 0; r <= 16; r++) for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) {
            if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
            for (int dy : new int[]{0, 1, -1, 2, -2}) {
                BlockPos p = origin.offset(dx, dy, dz);
                if (!floor(ctx, p) || !safe(ctx, p)) continue;
                if (target.kind == Kind.WORLD) {
                    if (!Bot.canWorkFrom(ctx.level(), ctx.player(), p.getX(), p.getY(), p.getZ(), target.pos(),
                            Bot.blockCentre(ctx.mc(), target.pos()), Math.min(4, ctx.player().blockInteractionRange()))) continue;
                    stand = p; go(Phase.TRAVEL); return;
                }
                for (Direction d : Direction.Plane.HORIZONTAL) {
                    BlockPos e = p.relative(d), b = e.relative(d.getClockWise()), work = p.relative(d.getClockWise());
                    if (!floor(ctx, e) || !safe(ctx, e) || !floor(ctx, b) || !safe(ctx, b)
                            || !floor(ctx, work) || !safe(ctx, work)) continue;
                    stand = p;
                    enderPos = needsEnder() ? e : null;
                    boxPos = needsEnder() ? b : e;
                    boxWork = needsEnder() ? work : p;
                    go(Phase.TRAVEL); return;
                }
            }
        }
        fail(ctx.mc(), "No loaded safe spot within 16 blocks (players, hazards or liquid within 5 blocks)");
    }
    private void travel(PathMove.Ctx ctx, Bot.Steer steer) {
        if (!safe(ctx, stand) || !floor(ctx, stand)) { nav.reset(); go(Phase.SITE); return; }
        Vec3 centre = Vec3.atBottomCenterOf(stand);
        if (ctx.player().position().distanceToSqr(centre) < 0.16 && ctx.player().onGround()) {
            nav.reset(); go(Phase.PREPARE); return;
        }
        if (ctx.player().position().distanceToSqr(centre) < 4 && DropCollector.directWalk(ctx, centre)) {
            DropCollector.aimAndWalk(ctx, steer, centre); return;
        }
        Pathing.Nav result = nav.tick(ctx, steer, stand,
                (x, y, z) -> x == stand.getX() && y == stand.getY() && z == stand.getZ(), Set.of(), false);
        if (result == Pathing.Nav.NO_ROUTE) fail(ctx.mc(), "Cannot reach the safe storage spot without editing terrain");
    }
    /** Stand alongside the shulker's square so the ender chest cannot occlude its floor. */
    private boolean at(PathMove.Ctx ctx, Bot.Steer steer, BlockPos destination) {
        if (!floor(ctx, destination) || !safe(ctx, destination)) { fail(ctx.mc(), "Container working position became unsafe"); return false; }
        Vec3 centre = Vec3.atBottomCenterOf(destination);
        if (ctx.player().position().distanceToSqr(centre) < 0.04 && ctx.player().onGround()) { nav.reset(); return true; }
        if (DropCollector.directWalk(ctx, centre)) DropCollector.aimAndWalk(ctx, steer, centre);
        else if (nav.tick(ctx, steer, destination, (x, y, z) -> x == destination.getX() && y == destination.getY()
                && z == destination.getZ(), Set.of(), false) == Pathing.Nav.NO_ROUTE) fail(ctx.mc(), "Cannot reach the container working position");
        return false;
    }
    private void prepare(PathMove.Ctx ctx) {
        LocalPlayer p = ctx.player();
        if (!p.onGround() || !safe(ctx, stand)) { fail(ctx.mc(), "Not standing safely"); return; }
        if (target.kind == Kind.WORLD) { go(Phase.OPEN_DEST); return; }
        int empty = 0;
        for (int i = 0; i < 36; i++) if (!cfg.slotProtected(i) && p.getInventory().getItem(i).isEmpty()) empty++;
        if (empty < (needsEnder() ? 2 : 1)) { fail(ctx.mc(), "Keep " + (needsEnder() ? 2 : 1) + " unprotected bag slots empty for container recovery"); return; }
        for (int i = 0; i < 9; i++) if (!cfg.slotProtected(i)) { handSlot = i; break; }
        if (handSlot < 0) { fail(ctx.mc(), "Storage needs one unprotected hotbar slot"); return; }
        if (needsEnder()) {
            pickInventorySlot = silkPick(p);
            for (int i = 0; i < 36; i++) if (!cfg.slotProtected(i) && p.getInventory().getItem(i).is(Items.ENDER_CHEST)) { enderInventorySlot = i; break; }
            if (enderInventorySlot < 0 || pickInventorySlot < 0) { fail(ctx.mc(), "Need an unprotected ender chest and a Silk Touch pickaxe"); return; }
            go(Phase.PLACE_ENDER);
        } else {
            boxInventorySlot = locate(p, target);
            if (boxInventorySlot < 0 || cfg.slotProtected(boxInventorySlot)) { fail(ctx.mc(), "Selected shulker changed, is ambiguous, or is protected; select it again"); return; }
            boxStack = p.getInventory().getItem(boxInventorySlot).copy();
            boxIdentity = identity(boxStack, p);
            go(Phase.PLACE_BOX);
        }
    }

    private boolean place(PathMove.Ctx ctx, Bot.Steer steer, BlockPos pos, ItemStack expected, int source) {
        Block b = ((BlockItem) expected.getItem()).getBlock();
        if (ctx.level().getBlockState(pos).is(b)) {
            if (!placementAttempted) { fail(ctx.mc(), "Placement square was occupied before our placement"); return false; }
            ItemStack now = ctx.player().getInventory().getItem(handSlot);
            return now.getCount() == placementCount - 1 && (now.isEmpty() || ItemStack.isSameItemSameComponents(now, expected));
        }
        if (!floor(ctx, pos) || !safe(ctx, pos)) { fail(ctx.mc(), "Placement square is no longer safe or empty"); return false; }
        if (!equip(ctx.mc(), source, expected)) return false;
        Direction face = Bot.placeAgainst(ctx.mc(), ctx.player(), pos, Direction.DOWN);
        if (face != Direction.DOWN) { fail(ctx.mc(), "Cannot reach the floor for an upright container placement"); return false; }
        if (!placementAttempted) {
            placementCount = ctx.player().getInventory().getItem(handSlot).getCount();
            placementAttempted = true;
        }
        double[] look = Bot.aimAt(ctx.player(), Bot.placePoint(ctx.mc(), pos, face));
        steer.lookAt(look[0], look[1]); steer.precise = true; steer.sneak = true;
        steer.placeInto(pos);
        return false;
    }
    private boolean equip(Minecraft mc, int source, ItemStack expected) {
        LocalPlayer p = mc.player;
        if (ItemStack.isSameItemSameComponents(p.getInventory().getItem(handSlot), expected)) {
            p.getInventory().setSelectedSlot(handSlot); return true;
        }
        if (source < 0 || source >= 36 || !ItemStack.isSameItemSameComponents(p.getInventory().getItem(source), expected)) {
            fail(mc, "Required container or tool moved during storage"); return false;
        }
        // A protected tool can be held directly, but never swapped out of its protected slot.
        if (source < 9) { handSlot = source; p.getInventory().setSelectedSlot(source); return true; }
        if (cfg.slotProtected(handSlot) || (cfg.slotProtected(source) && !silk(expected))) {
            fail(mc, "Required item is in a protected slot"); return false;
        }
        if (cfg.slotProtected(source)) { protectedToolSource = source; protectedToolHotbar = handSlot; }
        swap(mc, source, handSlot);
        return false;
    }
    private void swap(Minecraft mc, int source, int hotbar) {
        AbstractContainerMenu m = mc.player.inventoryMenu;
        int slot = Backpack.menuSlotFor(m, mc.player, source);
        if (mc.player.containerMenu != m || !m.getCarried().isEmpty() || slot < 0) { fail(mc, "Cannot equip during a container transaction"); return; }
        swapFrom = source; swapHotbar = hotbar; swapTicks = 0; swapState = m.getStateId();
        swapSource = mc.player.getInventory().getItem(source).copy(); swapDest = mc.player.getInventory().getItem(hotbar).copy();
        clickServer(mc, m, slot, hotbar, ContainerInput.SWAP);
        pause = 6;
    }
    private void confirmSwap(Minecraft mc) {
        if (mc.player.containerMenu != mc.player.inventoryMenu) { fail(mc, "Another menu interrupted equipping"); return; }
        if (++swapTicks > 200) { fail(mc, "Server did not confirm equipping"); return; }
        if (mc.player.inventoryMenu.getStateId() == swapState) return;
        if (!ItemStack.matches(mc.player.getInventory().getItem(swapFrom), swapDest)
                || !ItemStack.matches(mc.player.getInventory().getItem(swapHotbar), swapSource)) {
            fail(mc, "Server refused the inventory swap"); return;
        }
        swapFrom = -1;
    }

    private static void clickServer(Minecraft mc, AbstractContainerMenu m, int slot, int button, ContainerInput input) {
        // Request vanilla's full-state resynchronisation on this real click. Do not predict
        // changes locally: a correctly predicted vanilla click may receive NO reply at all.
        // The mismatching state id makes the server return its actual slots and cursor.
        mc.player.connection.send(new net.minecraft.network.protocol.game.ServerboundContainerClickPacket(
                m.containerId, -1, (short) slot, (byte) button, input,
                new it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap<>(), net.minecraft.network.HashedStack.EMPTY));
    }
    private boolean expectedMenu(AbstractContainerMenu menu) {
        boolean box = phase == Phase.OPEN_DEST && (target.kind != Kind.WORLD
                || target.block.endsWith("shulker_box"));
        if (box) return menu instanceof ShulkerBoxMenu;
        return menu instanceof ChestMenu chest && (target.kind == Kind.WORLD || chest.getRowCount() == 3);
    }
    private boolean open(PathMove.Ctx ctx, Bot.Steer steer, BlockPos pos) {
        AbstractContainerMenu menu = ctx.player().containerMenu;
        if (menu != ctx.player().inventoryMenu) {
            if (!expectedMenu(menu) || (menuId >= 0 && menu.containerId != menuId)) {
                fail(ctx.mc(), "Unexpected container menu"); return false;
            }
            menuId = menu.containerId;
            // Client menus start at state zero. Vanilla's initial contents packet supplies
            // the first server state id, including for a completely empty container.
            if (menu.getStateId() == 0) return false;
            return true;
        }
        if (target.kind == Kind.WORLD && !worldMatches(ctx)) { fail(ctx.mc(), "The selected world container changed"); return false; }
        if (!safe(ctx, pos) || !Bot.inReach(ctx.mc(), ctx.player(), pos)) { fail(ctx.mc(), "Container is unsafe or out of reach"); return false; }
        double[] look = Bot.aimAt(ctx.player(), Bot.aimPoint(ctx.mc(), ctx.player(), pos));
        steer.lookAt(look[0], look[1]); steer.precise = true; steer.useAt(pos);
        return false;
    }
    private boolean worldMatches(PathMove.Ctx ctx) {
        BlockPos p = target.pos();
        return target.world.equals(WorldId.current()) && target.dimension.equals(ctx.level().dimension().identifier().toString())
                && ctx.level().hasChunk(p.getX() >> 4, p.getZ() >> 4)
                && target.block.equals(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(ctx.level().getBlockState(p).getBlock()).toString());
    }
    private AbstractContainerMenu menu(Minecraft mc) {
        AbstractContainerMenu m = mc.player.containerMenu;
        if (menuId < 0 || m == mc.player.inventoryMenu || m.containerId != menuId || !handles(mc.gui.screen())) {
            fail(mc, "Storage menu closed or was replaced"); return null;
        }
        return m;
    }
    private void snapshot(Minecraft mc, Target t) {
        AbstractContainerMenu m = menu(mc); if (m == null) return;
        int n = m.slots.size() - 36;
        if (n != 27 && n != 54) { fail(mc, "Unsupported storage layout"); return; }
        t.size = n;
        views.put(t, java.util.stream.IntStream.range(0, n).mapToObj(i -> m.getSlot(i).getItem().copy()).toList());
    }
    private void snapshotEnder(Minecraft mc) {
        AbstractContainerMenu m = menu(mc); if (m == null) return;
        enderView = java.util.stream.IntStream.range(0, 27).mapToObj(i -> m.getSlot(i).getItem().copy()).toList();
        viewWorld = WorldId.current();
        if (target.kind == Kind.ENDER) { target.size = 27; views.put(target, enderView); }
    }
    private void takeShulker(Minecraft mc) {
        AbstractContainerMenu m = menu(mc); if (m == null) return;
        if (!boxStack.isEmpty()) { close(mc); go(Phase.PLACE_BOX); return; }
        int origin = target.enderSlot;
        if (origin < 0 || origin >= 27) { fail(mc, "Invalid original ender-chest slot"); return; }
        ItemStack s = m.getSlot(origin).getItem();
        if (!shulker(s) || !target.fingerprint.equals(fingerprint(s, mc.player))) {
            fail(mc, "The selected ender-chest shulker changed; inspect and select it again"); return;
        }
        int dest = emptySlot(mc.player);
        if (dest < 0) { fail(mc, "No empty bag slot for the selected shulker"); return; }
        boxStack = s.copy(); boxIdentity = identity(s, mc.player); boxInventorySlot = dest;
        transfer(mc, m, origin, Backpack.menuSlotFor(m, mc.player, dest), 1, false);
    }
    private void returnShulker(Minecraft mc) {
        AbstractContainerMenu m = menu(mc); if (m == null) return;
        ItemStack original = m.getSlot(target.enderSlot).getItem();
        if (!original.isEmpty()) {
            if (target.fingerprint.equals(fingerprint(original, mc.player))
                    && mc.player.getInventory().getItem(boxInventorySlot).isEmpty()) {
                snapshotEnder(mc); cfg.save(); go(Phase.BREAK_ENDER); return;
            }
            fail(mc, "Original ender-chest slot is occupied; filled shulker kept in your inventory"); return;
        }
        ItemStack s = mc.player.getInventory().getItem(boxInventorySlot);
        if (!target.fingerprint.equals(fingerprint(s, mc.player))) { fail(mc, "Filled shulker moved before return"); return; }
        transfer(mc, m, Backpack.menuSlotFor(m, mc.player, boxInventorySlot), target.enderSlot, 1, false);
    }

    private int source(LocalPlayer p, Target t) {
        return sources(p, t).stream().findFirst().orElse(-1);
    }
    private List<Integer> sources(LocalPlayer p, Target t) {
        List<Integer> candidates = new ArrayList<>();
        for (int i = 0; i < 36; i++) {
            ItemStack s = p.getInventory().getItem(i);
            if (!cfg.slotProtected(i) && !s.isEmpty() && !shulker(s) && !s.is(Items.ENDER_CHEST)
                    && !silk(s) && t.accepts(Backpack.itemId(s))) candidates.add(i);
        }
        Comparator<Integer> cmp = switch (t.itemOrder) {
            case INVENTORY -> Comparator.naturalOrder();
            case NAME -> Comparator.comparing(i -> p.getInventory().getItem(i).getHoverName().getString());
            case LARGEST_STACK -> Comparator.<Integer>comparingInt(i -> p.getInventory().getItem(i).getCount()).reversed();
            case FILTER_ORDER -> Comparator.comparingInt(i -> t.items.indexOf(Backpack.itemId(p.getInventory().getItem(i))));
        };
        return candidates.stream().sorted(cmp.thenComparingInt(i -> i)).toList();
    }
    static List<Integer> orderedSlots(Target t) {
        Comparator<Integer> order = switch (t.slotOrder) {
            case ROWS -> Comparator.naturalOrder();
            case REVERSE_ROWS -> Comparator.reverseOrder();
            case COLUMNS -> Comparator.<Integer>comparingInt(i -> i % 9).thenComparingInt(i -> i / 9);
        };
        return t.slots.stream().filter(i -> i >= 0 && i < t.size).sorted(order).toList();
    }
    private void deposit(Minecraft mc) {
        AbstractContainerMenu m = menu(mc); if (m == null) return;
        // Try all eligible stacks: a full diamond slot must not block the iron route.
        List<Integer> destinations = orderedSlots(target);
        for (int src : sources(mc.player, target)) {
            ItemStack s = mc.player.getInventory().getItem(src);
            for (int dest : destinations) {
                if (dest >= m.slots.size() - 36) continue;
                var slot = m.getSlot(dest);
                ItemStack there = slot.getItem();
                if (!slot.mayPlace(s) || (!there.isEmpty() && !ItemStack.isSameItemSameComponents(s, there))) continue;
                int n = Math.min(s.getCount(), slot.getMaxStackSize(s) - there.getCount());
                if (n > 0) { transfer(mc, m, Backpack.menuSlotFor(m, mc.player, src), dest, n, true); return; }
            }
        }
        snapshot(mc, target);
        if (needsEnder() && target.kind == Kind.ENDER) snapshotEnder(mc);
        go(boxPlaced ? Phase.BREAK_BOX : enderPlaced ? Phase.BREAK_ENDER : Phase.NEXT);
    }

    /** Pickup -> deposit -> return remainder. One click per tick gap, with server state-id acknowledgement. */
    private void transfer(Minecraft mc, AbstractContainerMenu m, int from, int to, int count, boolean deposit) {
        if (from < 0 || to < 0 || from >= m.slots.size() || to >= m.slots.size()
                || from == to || !m.getSlot(from).mayPickup(mc.player) || !m.getCarried().isEmpty()) {
            fail(mc, "Invalid or locked container slots"); return;
        }
        transfer = new Transfer(m, from, to, count, deposit);
    }

    private final class Transfer {
        final AbstractContainerMenu owner;
        final int from, to, count, initialSource, initialDest;
        final ItemStack expected;
        final boolean deposit;
        int step, sent, age, wait, state;
        boolean awaiting;
        Transfer(AbstractContainerMenu menu, int from, int to, int count, boolean deposit) {
            this.owner = menu; this.from = from; this.to = to; this.count = count; this.deposit = deposit;
            expected = menu.getSlot(from).getItem().copy();
            initialSource = expected.getCount(); initialDest = menu.getSlot(to).getItem().getCount();
        }
        boolean tick(Minecraft mc) {
            if (mc.player.containerMenu != owner || owner.containerId != menuId) { fail(mc, "Transfer menu changed"); return false; }
            if (++age > 400) { fail(mc, "Server did not confirm storage transfer"); return false; }
            if (wait > 0) { wait--; return false; }
            if (awaiting) {
                if (owner.getStateId() == state) return false;
                awaiting = false;
                ItemStack cursor = owner.getCarried();
                int remaining = initialSource - sent;
                if ((remaining == 0 || step == 3) ? !cursor.isEmpty()
                        : cursor.getCount() != remaining || !ItemStack.isSameItemSameComponents(cursor, expected)) {
                    fail(mc, "Server refused or changed a storage click"); return false;
                }
            }
            if (step == 0) {
                if (!owner.getCarried().isEmpty() || !ItemStack.matches(owner.getSlot(from).getItem(), expected)) {
                    fail(mc, "Source stack or cursor changed"); return false;
                }
                step = 1; click(mc, from, 0); return false;
            }
            if (step == 1) {
                ItemStack dest = owner.getSlot(to).getItem();
                if (dest.getCount() != initialDest + sent || (!dest.isEmpty() && !ItemStack.isSameItemSameComponents(dest, expected))) {
                    fail(mc, "Destination slot changed"); return false;
                }
                if (count == initialSource) { sent = count; step = 3; click(mc, to, 0); return false; }
                if (sent < count) { sent++; click(mc, to, 1); return false; }
                step = 2;
            }
            if (step == 2) {
                if (!owner.getSlot(from).getItem().isEmpty()) { fail(mc, "Source slot was occupied during transfer"); return false; }
                step = 3; click(mc, from, 0); return false;
            }
            ItemStack dest = owner.getSlot(to).getItem();
            ItemStack source = owner.getSlot(from).getItem();
            if (!owner.getCarried().isEmpty() || dest.getCount() != initialDest + count
                    || source.getCount() != initialSource - count || !ItemStack.isSameItemSameComponents(dest, expected)
                    || (!source.isEmpty() && !ItemStack.isSameItemSameComponents(source, expected))) {
                fail(mc, "Transfer totals did not match; stopped to preserve items"); return false;
            }
            if (deposit) deposited += count;
            return true;
        }
        private void click(Minecraft mc, int slot, int button) {
            state = owner.getStateId(); awaiting = true;
            clickServer(mc, owner, slot, button, ContainerInput.PICKUP);
            wait = Rng.ticks(0.2, 0.4);
        }
    }

    private boolean breakBlock(PathMove.Ctx ctx, Bot.Steer steer, BlockPos pos, boolean ender) {
        if (ctx.level().getBlockState(pos).isAir()) {
            if (!recoveryArmed) { fail(ctx.mc(), "Container disappeared before recovery began"); return false; }
            return true;
        }
        Block b = ctx.level().getBlockState(pos).getBlock();
        if (ender ? !(b instanceof EnderChestBlock) : !(b instanceof ShulkerBoxBlock)) {
            fail(ctx.mc(), "Placed container was replaced; refusing to break another block"); return false;
        }
        if (!safe(ctx, pos) || emptySlot(ctx.player()) < 0) { fail(ctx.mc(), "No safe recovery space for the container"); return false; }
        if (ender) {
            int pick = silkPick(ctx.player());
            if (pick < 0) { fail(ctx.mc(), "Silk Touch pickaxe missing or nearly broken; ender chest left intact"); return false; }
            if (!equip(ctx.mc(), pick, ctx.player().getInventory().getItem(pick).copy())) return false;
        } else ctx.player().getInventory().setSelectedSlot(Bot.bestToolSlot(ctx.player(), ctx.level().getBlockState(pos)));
        if (!recoveryArmed) {
            oldDrops = dropIds(ctx);
            recoveryBefore = recoveredCount(ctx.player(), ender);
            for (int i = 0; i < 36; i++) recoverySlots[i] = ctx.player().getInventory().getItem(i).getCount();
            recoveryArmed = true;
        }
        double[] look = Bot.aimAt(ctx.player(), Bot.aimPoint(ctx.mc(), ctx.player(), pos));
        steer.lookAt(look[0], look[1]); steer.precise = true; steer.attackAt(pos);
        return false;
    }
    private int collect(PathMove.Ctx ctx, Bot.Steer steer, BlockPos pos, String identity, boolean ender) {
        // Placement consumed this item. Recovery requires the updated contents for a shulker.
        for (int i = 0; recoveredCount(ctx.player(), ender) > recoveryBefore && i < 36; i++) {
            ItemStack s = ctx.player().getInventory().getItem(i);
            if (s.getCount() <= recoverySlots[i]) continue;
            if (ender ? s.is(Items.ENDER_CHEST) : shulker(s) && identity.equals(identity(s, ctx.player()))
                    && contentsEqual(s, view(target))) return i;
        }
        for (var e : ctx.level().entitiesForRendering()) {
            if (!(e instanceof ItemEntity drop) || oldDrops.contains(e.getId()) || e.blockPosition().distSqr(pos) > 9) continue;
            ItemStack s = drop.getItem();
            if (!(ender ? s.is(Items.ENDER_CHEST) : shulker(s) && identity.equals(identity(s, ctx.player()))
                    && contentsEqual(s, view(target)))) continue;
            Vec3 goal = new Vec3(drop.getX(), ctx.player().getY(), drop.getZ());
            if (DropCollector.directWalk(ctx, goal)) DropCollector.aimAndWalk(ctx, steer, goal);
            else {
                Pathing.Nav result = nav.tick(ctx, steer, pos,
                        (x, y, z) -> x == pos.getX() && y == pos.getY() && z == pos.getZ(), Set.of(), false);
                if (result == Pathing.Nav.NO_ROUTE) fail(ctx.mc(), "Cannot safely walk around the ender chest to recover the shulker");
            }
            return -1;
        }
        if (ticks > 160) fail(ctx.mc(), "Container drop was not recovered at " + pos.toShortString());
        return -1;
    }
    private int recoveredCount(LocalPlayer p, boolean ender) {
        int n = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack s = p.getInventory().getItem(i);
            if (ender ? s.is(Items.ENDER_CHEST) : shulker(s) && boxIdentity.equals(identity(s, p))
                    && contentsEqual(s, view(target))) n += s.getCount();
        }
        return n;
    }
    private static boolean contentsEqual(ItemStack s, List<ItemStack> expected) {
        List<ItemStack> actual = contents(s);
        if (expected.size() != actual.size()) return false;
        for (int i = 0; i < actual.size(); i++) if (!ItemStack.matches(actual.get(i), expected.get(i))) return false;
        return true;
    }
    private static Set<Integer> dropIds(PathMove.Ctx ctx) {
        Set<Integer> out = new HashSet<>();
        for (var e : ctx.level().entitiesForRendering()) if (e instanceof ItemEntity) out.add(e.getId());
        return out;
    }
    private int locate(LocalPlayer p, Target t) {
        if (t.inventorySlot >= 0 && t.inventorySlot < 36
                && t.fingerprint.equals(fingerprint(p.getInventory().getItem(t.inventorySlot), p))) return t.inventorySlot;
        int found = -1;
        for (int i = 0; i < 36; i++) if (t.fingerprint.equals(fingerprint(p.getInventory().getItem(i), p))) {
            if (found >= 0) return -1;
            found = i;
        }
        return found;
    }
    private int emptySlot(LocalPlayer p) {
        for (int i = 9; i < 45; i++) {
            int slot = i % 36;
            if (!cfg.slotProtected(slot) && p.getInventory().getItem(slot).isEmpty()) return slot;
        }
        return -1;
    }
    public static boolean silk(ItemStack stack) {
        if (!stack.is(ItemTags.PICKAXES) || (stack.isDamageableItem() && stack.getMaxDamage() - stack.getDamageValue() <= 2)) return false;
        var enchants = stack.getEnchantments();
        return enchants.keySet().stream().anyMatch(e -> e.is(Enchantments.SILK_TOUCH) && enchants.getLevel(e) > 0);
    }
    private static int silkPick(LocalPlayer p) {
        for (int i = 0; i < 36; i++) if (silk(p.getInventory().getItem(i))) return i;
        return -1;
    }
    static boolean liquidTooClose(int dx, int dy, int dz) {
        // Five blocks from the whole standing body, even when it straddles a cell edge.
        double x = Math.max(0, Math.abs(dx) - 1.5), z = Math.max(0, Math.abs(dz) - 1.5);
        double y = Math.max(0, dy > 0 ? dy - 2 : -dy - 1);
        return x * x + y * y + z * z < 25;
    }
    boolean safe(PathMove.Ctx ctx, BlockPos p) {
        for (var player : ctx.level().players()) if (player != ctx.player() && player.isAlive()
                && player.position().distanceToSqr(Vec3.atCenterOf(p)) < cfg.storagePlayerRadius * cfg.storagePlayerRadius) return false;
        for (int dx = -6; dx <= 6; dx++) for (int dz = -6; dz <= 6; dz++) for (int dy = -6; dy <= 6; dy++) {
            if (!liquidTooClose(dx, dy, dz)) continue;
            BlockPos q = p.offset(dx, dy, dz);
            if (!ctx.level().hasChunk(q.getX() >> 4, q.getZ() >> 4) || !ctx.level().getFluidState(q).isEmpty()) return false;
        }
        return !Avoidance.hazardAt(ctx.level(), p, cfg) && !Avoidance.hazardAt(ctx.level(), p.below(), cfg)
                && !ctx.player().isOnFire() && !ctx.player().isInWater() && !ctx.player().isInLava();
    }
    private static boolean floor(PathMove.Ctx ctx, BlockPos p) {
        return ctx.level().hasChunk(p.getX() >> 4, p.getZ() >> 4) && ctx.level().getWorldBorder().isWithinBounds(p)
                && ctx.level().getBlockState(p).isAir() && ctx.level().getBlockState(p.above()).isAir()
                && Bot.fullPlacementSupport(ctx.level(), p.below())
                && ctx.level().noCollision(ctx.player(), new AABB(p));
    }
    private boolean close(Minecraft mc) {
        boolean closed = mc.player != null && mc.player == sessionPlayer && mc.level == sessionLevel
                && menuId >= 0 && mc.player.containerMenu.containerId == menuId;
        if (closed) {
            mc.player.closeContainer();
            // Vanilla's menu attack lock clears on a released-attack tick, not merely on closing the GUI.
            pause = 6;
        }
        menuId = -1;
        return closed;
    }
    private void next(Minecraft mc) {
        close(mc); nav.reset();
        if (protectedToolSource >= 0) {
            int source = protectedToolSource; protectedToolSource = -1;
            swap(mc, source, protectedToolHotbar); return;
        }
        if (!queue.isEmpty()) { startTarget(queue.removeFirst()); return; }
        mc.player.getInventory().setSelectedSlot(oldHotbar);
        phase = Phase.IDLE; cooldown = 600;
        status = preview ? "Contents inspected; placed containers recovered" : "Stored " + deposited + " items";
        cfg.save();
        if (returnScreen != null) { Screen back = returnScreen; returnScreen = null; mc.setScreenAndShow(back); }
    }
    private void fail(Minecraft mc, String reason) {
        nav.reset(); NativeNavigation.stopAll();
        transfer = null; swapFrom = -1; queue.clear();
        Screen foreign = mc.gui.screen() instanceof com.damia.movrand.gui.ConfigScreen ? mc.gui.screen() : null;
        close(mc);
        phase = Phase.FAILED; cfg.movementEnabled = false;
        status = "Storage stopped: " + reason + (boxPos != null ? " · shulker site " + boxPos.toShortString() : "")
                + (enderPos != null ? " · ender site " + enderPos.toShortString() : "");
        if (protectedToolSource >= 0) status += " · borrowed pickaxe may be in hotbar slot " + protectedToolHotbar;
        protectedToolSource = -1;
        MovRand.print(mc, status);
        if (returnScreen != null && mc.player == sessionPlayer && mc.level == sessionLevel) {
            Screen back = returnScreen; returnScreen = null; mc.setScreenAndShow(back);
        } else if (foreign != null && mc.gui.screen() != foreign) mc.setScreenAndShow(foreign);
    }

    public static void main(String[] args) {
        Target t = new Target();
        t.slots = new ArrayList<>(List.of(0, 1, 8, 9, 10, 18, 26));
        assert orderedSlots(t).equals(List.of(0, 1, 8, 9, 10, 18, 26));
        t.slotOrder = SlotOrder.COLUMNS;
        assert orderedSlots(t).equals(List.of(0, 9, 18, 1, 10, 8, 26));
        t.slotOrder = SlotOrder.REVERSE_ROWS;
        assert orderedSlots(t).equals(List.of(26, 18, 10, 9, 8, 1, 0));
        t.items = new ArrayList<>(Arrays.asList(null, "diamond", "minecraft:diamond", ""));
        t.slots.add(-1); t.slots.add(54); t.slots.add(0); t.clamp();
        assert t.items.equals(List.of("minecraft:diamond"));
        assert t.accepts("diamond") && !t.accepts("iron_ingot");
        assert t.slots.size() == 7;
        assert liquidTooClose(6, 0, 0) && !liquidTooClose(7, 0, 0);
        assert liquidTooClose(0, 6, 0) && !liquidTooClose(0, 7, 0);
        assert liquidTooClose(0, -5, 0) && liquidTooClose(4, 4, 0);
        Config cfg = new Config(); cfg.storagePlayerRadius = Double.NaN; cfg.storageTargets = null; cfg.clampAll();
        assert cfg.storagePlayerRadius == 32 && cfg.storageTargets.isEmpty();
        System.out.println("Storage self-check passed");
    }
}
