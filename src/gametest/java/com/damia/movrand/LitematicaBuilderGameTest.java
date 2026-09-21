package com.damia.movrand;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Real survival placement, camera smoothing, multi-region file loading and exact server states. */
final class LitematicaBuilderGameTest {
    static void run(ClientGameTestContext test, TestSingleplayerContext world) {
        test.runOnClient(mc -> checkTransforms());
        creativeMaterials(test, world);
        if (Boolean.parseBoolean(System.getenv("MOVRAND_BUILDER_CREATIVE_ONLY"))) return;
        if (Boolean.parseBoolean(System.getenv("MOVRAND_BUILDER_EXTRA_ONLY"))) {
            try {
                Path file = Files.createTempFile("movrand-extra-", ".litematic");
                try { extraMechanics(test, world, file); } finally { Files.deleteIfExists(file); }
            } catch (java.io.IOException e) { throw new RuntimeException(e); }
            return;
        }
        TerrainGameTest.setup(test, world);
        var server = world.getServer();
        server.runCommand("tp @p 0.5 1 0.5 -90 0");
        server.runCommand("clear @p");
        for (String item : List.of("diamond_pickaxe", "cobblestone 64", "repeater 8", "comparator 8", "observer 8",
                "oak_log 8", "oak_stairs 8", "oak_slab 8", "oak_door 8", "red_bed 8", "hopper 8", "lever 8", "note_block 8", "torch 8"))
            server.runCommand("give @p " + item);
        // Keep one empty hotbar slot, and require bag-to-hotbar material transfers.
        server.runCommand("item replace entity @p hotbar.8 with air");
        server.runCommand("give @p oak_door 8");
        world.getConnection().waitForClientboundPackets();
        Map<BlockPos, BlockState> desired = new LinkedHashMap<>();
        desired.put(new BlockPos(4, 1, 0), Blocks.REPEATER.defaultBlockState().setValue(RepeaterBlock.FACING, Direction.NORTH).setValue(RepeaterBlock.DELAY, 4));
        desired.put(new BlockPos(6, 1, 0), Blocks.COMPARATOR.defaultBlockState().setValue(ComparatorBlock.FACING, Direction.EAST).setValue(ComparatorBlock.MODE, ComparatorMode.SUBTRACT));
        desired.put(new BlockPos(8, 1, 0), Blocks.OBSERVER.defaultBlockState().setValue(ObserverBlock.FACING, Direction.WEST));
        desired.put(new BlockPos(10, 1, 0), Blocks.OAK_LOG.defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.X));
        desired.put(new BlockPos(11, 1, 0), Blocks.COBBLESTONE.defaultBlockState());
        desired.put(new BlockPos(12, 1, 0), Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.SOUTH).setValue(StairBlock.HALF, Half.TOP));
        desired.put(new BlockPos(3, 1, 3), Blocks.COBBLESTONE.defaultBlockState());
        desired.put(new BlockPos(4, 1, 3), Blocks.OAK_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.TOP));
        desired.put(new BlockPos(6, 1, 3), Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING, Direction.NORTH));
        desired.put(new BlockPos(6, 2, 3), Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING, Direction.NORTH).setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
        desired.put(new BlockPos(8, 1, 3), Blocks.BED.red().defaultBlockState().setValue(BedBlock.FACING, Direction.NORTH));
        desired.put(new BlockPos(8, 1, 2), Blocks.BED.red().defaultBlockState().setValue(BedBlock.FACING, Direction.NORTH).setValue(BedBlock.PART, BedPart.HEAD));
        desired.put(new BlockPos(10, 1, 3), Blocks.HOPPER.defaultBlockState().setValue(HopperBlock.FACING, Direction.WEST));
        desired.put(new BlockPos(14, 1, 3), Blocks.LEVER.defaultBlockState().setValue(LeverBlock.FACE, AttachFace.FLOOR).setValue(LeverBlock.FACING, Direction.NORTH).setValue(LeverBlock.POWERED, true));
        desired.put(new BlockPos(2, 1, 5), Blocks.NOTE_BLOCK.defaultBlockState().setValue(NoteBlock.NOTE, 7).setValue(NoteBlock.INSTRUMENT, NoteBlockInstrument.BASEDRUM));
        desired.put(new BlockPos(3, 2, 3), Blocks.COBBLESTONE.defaultBlockState());
        desired.put(new BlockPos(3, 2, 4), Blocks.WALL_TORCH.defaultBlockState().setValue(WallTorchBlock.FACING, Direction.SOUTH));
        Path[] fixture = {null};
        test.runOnClient(mc -> {
            try {
                fixture[0] = Files.createTempFile("movrand-builder-", ".litematic");
                CompoundTag root = schematic(desired);
                NbtIo.writeCompressed(root, fixture[0]);
                require(LitematicPlan.decode(root).cells().size() == desired.size(), "Lost regions");
                CompoundTag negative = root.copy();
                negative.getCompoundOrEmpty("Regions").getCompoundOrEmpty("r0").getCompoundOrEmpty("Size").putInt("x", -3);
                require(LitematicPlan.decode(negative).cells().stream().anyMatch(c -> c.pos().equals(new BlockPos(2, 1, 0))), "Negative-size region lost its saved origin");
                require(LitematicPlan.decode(root).placed(new BlockPos(100, 2, -50), Rotation.CLOCKWISE_90, Mirror.NONE).stream()
                        .anyMatch(c -> c.pos().equals(new BlockPos(100, 3, -46)) && c.state().getValue(RepeaterBlock.FACING) == Direction.EAST), "Position/state rotation disagree");
                CompoundTag bad = root.copy();
                bad.getCompoundOrEmpty("Regions").getCompoundOrEmpty("r0").getListOrEmpty("BlockStatePalette").getCompoundOrEmpty(0).putString("Name", "movrand:missing");
                try { LitematicPlan.decode(bad); throw new AssertionError("Unknown block silently accepted"); }
                catch (IllegalArgumentException expected) { }
                bad = root.copy();
                bad.getCompoundOrEmpty("Regions").getCompoundOrEmpty("r0").putLongArray("BlockStates", new long[0]);
                try { LitematicPlan.decode(bad); throw new AssertionError("Truncated palette accepted"); }
                catch (IllegalArgumentException expected) { }
                bad = root.copy();
                bad.getCompoundOrEmpty("Regions").getCompoundOrEmpty("r0").getListOrEmpty("BlockStatePalette").getCompoundOrEmpty(0).getCompoundOrEmpty("Properties").putString("delay", "5");
                try { LitematicPlan.decode(bad); throw new AssertionError("Invalid repeater delay accepted"); }
                catch (IllegalArgumentException expected) { }
                var c = MovRand.config();
                c.destroyerEnabled = false; c.stopOnDamage = false; c.stopWhenInventoryFull = false;
                c.builderFile = fixture[0].toString(); c.builderDelayMin = .1; c.builderDelayMax = .2;
                c.taskAimWobbleScale = .15;
                MovRand.controller().builder.load();
                require(MovRand.controller().builder.loaded(), MovRand.controller().builder.status);
                c.builderEnabled = true; MovRand.controller().start(mc);
            } catch (java.io.IOException e) { throw new RuntimeException(e); }
        });
        try {
            int[] elapsed = {0};
            test.waitFor(mc -> {
                var b = MovRand.controller().builder;
                if (++elapsed[0] % 100 == 0) MovRand.LOG.info("BUILDER {} pos {} yaw {} pitch {} target {} status {}", elapsed[0], mc.player.position(), mc.player.getYRot(), mc.player.getXRot(), b.targetDescription(), b.status);
                require(b.phase != LitematicaBuilder.Phase.BLOCKED, b.status);
                require(MovRand.config().movementEnabled, "Movement stopped: " + MovRand.controller().lastReason);
                return b.phase == LitematicaBuilder.Phase.DONE;
            }, 5000);
            server.runOnServer(s -> desired.forEach((pos, state) -> require(s.overworld().getBlockState(pos) == state,
                    "Server mismatch " + pos + ": " + s.overworld().getBlockState(pos) + " wanted " + state)));
            server.runOnServer(s -> require(s.overworld().getBlockState(new BlockPos(9, 1, 3)).isAir(), "Temporary hopper support was not removed"));
            test.runOnClient(mc -> {
                MovRand.controller().stop(mc, "Litematica survival fixture verified");
                var screen = new com.damia.movrand.gui.ConfigScreen();
                mc.gui.setScreen(screen);
                for (int i = 0; i < 21; i++) screen.keyPressed(new net.minecraft.client.input.KeyEvent(org.lwjgl.glfw.GLFW.GLFW_KEY_TAB, 0, 0));
            });
            test.takeScreenshot("litematica-builder-menu");
            test.runOnClient(mc -> mc.gui.setScreen(null));
            extraMechanics(test, world, fixture[0]);
            MovRand.LOG.info("LITEMATICA BUILDER PASS: exact survival states, repeaters, comparator, observer, axis, stairs, slab, door, bed, hopper, lever, note block, wall torch, inventory and Baritone");
        } catch (Throwable failure) {
            server.runOnServer(s -> desired.forEach((pos, state) -> {
                if (s.overworld().getBlockState(pos) != state) MovRand.LOG.error("MISMATCH {} actual {} desired {}", pos, s.overworld().getBlockState(pos), state);
            }));
            test.runOnClient(mc -> MovRand.LOG.error("BUILDER FAILED: {} target {} issues {} support {} slot {}", MovRand.controller().builder.status, MovRand.controller().builder.targetDescription(), MovRand.controller().builder.issues(), mc.level.getBlockState(new BlockPos(9,1,3)), Bot.buildingSlot(mc.player, MovRand.config())));
            test.takeScreenshot("litematica-builder-failure");
            throw failure;
        } finally {
            test.runOnClient(mc -> MovRand.controller().stop(mc, "Builder test cleanup"));
            try { if (fixture[0] != null) Files.deleteIfExists(fixture[0]); } catch (java.io.IOException ignored) { }
        }
    }
    private static void creativeMaterials(ClientGameTestContext test, TestSingleplayerContext world) {
        TerrainGameTest.setup(test, world);
        var server = world.getServer();
        server.runCommand("clear @p");
        server.runCommand("tp @p 0.5 1 0.5 -90 0");
        Path file;
        Map<BlockPos, BlockState> desired = new LinkedHashMap<>();
        desired.put(new BlockPos(3, 1, 0), Blocks.COBBLESTONE.defaultBlockState());
        desired.put(new BlockPos(4, 1, 0), Blocks.OAK_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.DOUBLE));
        desired.put(new BlockPos(5, 1, 0), Blocks.FARMLAND.defaultBlockState());
        desired.put(new BlockPos(3, 2, 4), Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.NORTH).setValue(ChestBlock.TYPE, ChestType.LEFT));
        desired.put(new BlockPos(4, 2, 4), Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.NORTH).setValue(ChestBlock.TYPE, ChestType.RIGHT));
        desired.put(new BlockPos(3, 3, 4), Blocks.HOPPER.defaultBlockState());
        try {
            file = Files.createTempFile("movrand-creative-", ".litematic");
            NbtIo.writeCompressed(schematic(desired), file);
        } catch (java.io.IOException e) { throw new RuntimeException(e); }
        try {
            for (int scenario = 0; scenario < 4; scenario++) {
                final int mode = scenario;
                server.runCommand("gamemode " + (mode == 0 ? "survival" : "creative") + " @p");
                world.getConnection().waitForClientboundPackets();
                test.runOnClient(mc -> {
                    var c = MovRand.config();
                    require(new Config().builderCreativeMaterials, "Creative materials must default on");
                    c.destroyerEnabled = false; c.stopOnDamage = false; c.stopWhenInventoryFull = false;
                    c.builderCreativeMaterials = mode != 1;
                    c.protectedSlots.clear();
                    if (mode == 2) for (int i = 0; i < 36; i++) c.protectedSlots.add(i);
                    c.builderFile = file.toString(); c.builderDelayMin = c.builderDelayMax = .1;
                    MovRand.controller().builder.load(); c.builderEnabled = true; MovRand.controller().start(mc);
                });
                test.waitFor(mc -> {
                    var b = MovRand.controller().builder;
                    if (mode == 3) require(b.phase != LitematicaBuilder.Phase.BLOCKED, b.status + " " + b.issues());
                    return b.phase == (mode == 3 ? LitematicaBuilder.Phase.DONE : LitematicaBuilder.Phase.BLOCKED);
                }, mode == 3 ? 1800 : 200);
                server.runOnServer(s -> {
                    if (mode == 3) desired.forEach((pos, state) -> require(s.overworld().getBlockState(pos) == state, "Creative server mismatch " + pos));
                    else {
                        require(s.overworld().getBlockState(new BlockPos(3, 1, 0)).isAir(), "Creative fetch ignored mode/toggle/protected slots");
                        require(s.getPlayerList().getPlayers().getFirst().getInventory().isEmpty(), "Unexpected generated material");
                    }
                });
                test.runOnClient(mc -> MovRand.controller().stop(mc, "Creative material scenario complete"));
            }
            MovRand.LOG.info("BUILDER CREATIVE PASS: default on, survival/off/protected guards, empty inventory, material switching, double slab and soil tool, exact server states");
        } finally {
            test.runOnClient(mc -> MovRand.controller().stop(mc, "Creative material cleanup"));
            try { Files.deleteIfExists(file); } catch (java.io.IOException ignored) { }
        }
    }
    private static void extraMechanics(ClientGameTestContext test, TestSingleplayerContext world, Path file) {
        TerrainGameTest.setup(test, world);
        var server = world.getServer();
        server.runCommand("tp @p 0.5 1 0.5 -90 0");
        server.runCommand("clear @p");
        server.runCommand("gamerule minecraft:random_tick_speed 0");
        for (String item : List.of("diamond_pickaxe", "cobblestone 64", "oak_slab 8", "snow 8", "water_bucket", "dirt 8", "diamond_hoe", "diamond_shovel", "sea_pickle 8")) server.runCommand("give @p " + item);
        server.runCommand("fill 15 1 1 18 1 4 stone");
        server.runCommand("fill 16 1 2 17 1 3 water");
        for (String pos : List.of("8 1 3", "8 1 5", "7 1 4", "9 1 4")) server.runCommand("setblock " + pos + " stone");
        server.runCommand("setblock 6 1 4 stone");
        for (String pos : List.of("4 1 3", "4 1 5", "3 1 4", "5 1 4")) server.runCommand("setblock " + pos + " stone");
        world.getConnection().waitForClientboundPackets();
        Map<BlockPos, BlockState> desired = new LinkedHashMap<>();
        desired.put(new BlockPos(4, 1, 0), Blocks.OAK_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.DOUBLE));
        desired.put(new BlockPos(6, 1, 0), Blocks.SNOW.defaultBlockState().setValue(SnowLayerBlock.LAYERS, 3));
        desired.put(new BlockPos(8, 1, 0), Blocks.FARMLAND.defaultBlockState());
        desired.put(new BlockPos(10, 1, 0), Blocks.DIRT_PATH.defaultBlockState());
        desired.put(new BlockPos(4, 1, 4), Blocks.OAK_SLAB.defaultBlockState().setValue(SlabBlock.WATERLOGGED, true));
        desired.put(new BlockPos(8, 1, 4), Blocks.OAK_SLAB.defaultBlockState().setValue(SlabBlock.WATERLOGGED, true));
        desired.put(new BlockPos(17, 1, 3), Blocks.SEA_PICKLE.defaultBlockState().setValue(SeaPickleBlock.PICKLES, 4));
        desired.put(new BlockPos(6, 1, 4), Blocks.AIR.defaultBlockState());
        test.runOnClient(mc -> {
            try { NbtIo.writeCompressed(schematic(desired), file); } catch (java.io.IOException e) { throw new RuntimeException(e); }
            Config c = MovRand.config(); c.destroyerEnabled = false; c.stopOnDamage = false;
            c.builderFile = file.toString(); c.builderReplace = c.builderClearAir = true;
            c.builderDelayMin = .1; c.builderDelayMax = .2;
            MovRand.controller().builder.load(); c.builderEnabled = true; MovRand.controller().start(mc);
        });
        int[] tick = {0};
        test.waitFor(mc -> {
            var b = MovRand.controller().builder;
            if (++tick[0] % 100 == 0) MovRand.LOG.info("BUILDER EXTRA {} {} {} position {} rotation {},{} wait {}", tick[0], b.targetDescription(), b.status, mc.player.position(), mc.player.getYRot(), mc.player.getXRot(), b.interactionWait);
            require(b.phase != LitematicaBuilder.Phase.BLOCKED, b.status + " " + b.issues());
            return b.phase == LitematicaBuilder.Phase.DONE;
        }, 1800);
        server.runOnServer(s -> desired.forEach((pos, state) -> require(s.overworld().getBlockState(pos) == state, "Extra mechanics mismatch " + pos)));
        server.runOnServer(s -> require(s.overworld().getBlockState(new BlockPos(4, 2, 4)).isAir(), "Bucket poured above the slab instead of waterlogging it"));
        test.runOnClient(mc -> {
            MovRand.controller().stop(mc, "Extra builder mechanics passed");
            try { NbtIo.writeCompressed(schematic(Map.of(new BlockPos(16, 1, 0), Blocks.COBBLESTONE.defaultBlockState())), file); }
            catch (java.io.IOException e) { throw new RuntimeException(e); }
            for (int i = 0; i < 36; i++) MovRand.config().protectedSlots.add(i);
            MovRand.controller().builder.load(); MovRand.config().builderEnabled = true; MovRand.controller().start(mc);
        });
        test.waitFor(mc -> MovRand.controller().builder.phase == LitematicaBuilder.Phase.BLOCKED, 150);
        server.runOnServer(s -> require(s.overworld().getBlockState(new BlockPos(16, 1, 0)).isAir(), "Protected inventory was consumed"));
        test.runOnClient(mc -> {
            require(MovRand.controller().builder.status.contains("Missing unprotected material"), MovRand.controller().builder.status);
            MovRand.controller().stop(mc, "Protected material and incomplete-build checks passed");
        });
        MovRand.LOG.info("BUILDER EXTRA PASS: double slabs, snow layers, farmland, paths, waterlogging, clearing, protected inventory and incomplete build reporting");
    }
    private static CompoundTag schematic(Map<BlockPos, BlockState> cells) {
        CompoundTag root = new CompoundTag(), regions = new CompoundTag();
        root.putInt("Version", 6); root.put("Regions", regions);
        int index = 0;
        for (var cell : cells.entrySet()) {
            CompoundTag region = new CompoundTag(), position = new CompoundTag(), size = new CompoundTag();
            position.putInt("x", cell.getKey().getX()); position.putInt("y", cell.getKey().getY()); position.putInt("z", cell.getKey().getZ());
            size.putInt("x", 1); size.putInt("y", 1); size.putInt("z", 1);
            region.put("Position", position); region.put("Size", size);
            ListTag palette = new ListTag();
            CompoundTag state = new CompoundTag(), properties = new CompoundTag();
            state.putString("Name", BuiltInRegistries.BLOCK.getKey(cell.getValue().getBlock()).toString());
            for (var p : cell.getValue().getProperties()) writeProperty(properties, cell.getValue(), p);
            state.put("Properties", properties); palette.add(state);
            region.put("BlockStatePalette", palette); region.putLongArray("BlockStates", new long[]{0});
            regions.put("r" + index++, region);
        }
        return root;
    }
    static void checkTransforms() {
        for (Direction facing : Direction.Plane.HORIZONTAL) for (Mirror mirror : Mirror.values()) for (Rotation rotation : Rotation.values()) {
            BlockState left = Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, facing).setValue(ChestBlock.TYPE, ChestType.LEFT);
            BlockPos partner = ChestBlock.getConnectedBlockPos(BlockPos.ZERO, left);
            BlockState transformed = LitematicPlan.transformState(left, rotation, mirror);
            require(ChestBlock.getConnectedBlockPos(BlockPos.ZERO, transformed).equals(LitematicPlan.transform(partner, rotation, mirror)),
                    "Mirrored chest points away from its transformed partner: " + facing + " " + mirror + " " + rotation);
        }
        require(!LitematicaBuilder.compatible(Blocks.POWERED_RAIL.defaultBlockState(),
                Blocks.POWERED_RAIL.defaultBlockState().setValue(PoweredRailBlock.SHAPE, RailShape.EAST_WEST)), "Rail orientation ignored");
    }
    private static <T extends Comparable<T>> void writeProperty(CompoundTag tag, BlockState state, Property<T> p) { tag.putString(p.getName(), p.getName(state.getValue(p))); }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
