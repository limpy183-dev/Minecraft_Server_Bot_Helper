package com.damia.movrand;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.*;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Strict, bounded Litematic reader. Coordinates remain relative to the saved origin. */
public record LitematicPlan(List<Cell> cells, int entities, int blockEntities) {
    public record Cell(BlockPos pos, BlockState state) {}
    private static final int MAX_CELLS = 1_000_000;

    public static boolean accepts(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".litematic") || name.endsWith(".litematica");
    }

    public static LitematicPlan read(Path path) throws IOException {
        if (!accepts(path) || !Files.isRegularFile(path)) throw new IOException("Select a .litematic or .litematica file");
        if (Files.size(path) > 64L * 1024 * 1024) throw new IOException("Schematic exceeds the 64 MiB file limit");
        try {
            return decode(NbtIo.readCompressed(path, NbtAccounter.create(128L * 1024 * 1024)));
        } catch (RuntimeException e) {
            throw new IOException("Invalid schematic: " + e.getMessage(), e);
        }
    }

    static LitematicPlan decode(CompoundTag root) {
        int version = integer(root, "Version");
        if (version < 4 || version > 7) throw new IllegalArgumentException("Unsupported Litematic version " + version + " (supported: 4–7)");
        CompoundTag regions = compound(root, "Regions");
        if (regions.isEmpty()) throw new IllegalArgumentException("No regions");
        Map<BlockPos, BlockState> cells = new LinkedHashMap<>();
        long total = 0;
        int entities = 0, blockEntities = 0;
        for (String name : new TreeSet<>(regions.keySet())) {
            CompoundTag region = compound(regions, name), position = compound(region, "Position"), size = compound(region, "Size");
            int sx = dimension(size, "x"), sy = dimension(size, "y"), sz = dimension(size, "z");
            long volume = (long) Math.abs(sx) * Math.abs(sy) * Math.abs(sz);
            total += volume;
            if (total > MAX_CELLS) throw new IllegalArgumentException("Schematic exceeds 1,000,000 region cells");
            int x0 = minimum(integer(position, "x"), sx), y0 = minimum(integer(position, "y"), sy), z0 = minimum(integer(position, "z"), sz);
            ListTag paletteTag = region.getList("BlockStatePalette").orElseThrow(() -> new IllegalArgumentException("Missing block palette"));
            if (paletteTag.isEmpty() || paletteTag.size() > MAX_CELLS) throw new IllegalArgumentException("Invalid palette size");
            List<BlockState> palette = new ArrayList<>();
            for (Tag tag : paletteTag) {
                if (!(tag instanceof CompoundTag entry)) throw new IllegalArgumentException("Invalid palette entry");
                palette.add(state(entry));
            }
            int bits = Math.max(2, 32 - Integer.numberOfLeadingZeros(palette.size() - 1));
            long[] data = region.getLongArray("BlockStates").orElseThrow(() -> new IllegalArgumentException("Missing BlockStates"));
            if (data.length != (volume * bits + 63) / 64) throw new IllegalArgumentException("Truncated or oversized BlockStates in " + name);
            int index = 0;
            for (int y = 0; y < Math.abs(sy); y++) for (int z = 0; z < Math.abs(sz); z++) for (int x = 0; x < Math.abs(sx); x++) {
                int id = paletteIndex(data, bits, index++);
                if (id >= palette.size()) throw new IllegalArgumentException("Palette index outside palette");
                BlockPos pos = new BlockPos(Math.addExact(x0, x), Math.addExact(y0, y), Math.addExact(z0, z));
                BlockState want = palette.get(id), previous = cells.putIfAbsent(pos, want);
                if (previous != null && previous != want) throw new IllegalArgumentException("Conflicting overlapping regions at " + pos.toShortString());
            }
            entities = Math.addExact(entities, region.getListOrEmpty("Entities").size());
            blockEntities = Math.addExact(blockEntities, region.getListOrEmpty("TileEntities").size());
        }
        return new LitematicPlan(cells.entrySet().stream().map(e -> new Cell(e.getKey(), e.getValue())).toList(), entities, blockEntities);
    }

    public List<Cell> placed(BlockPos origin, Rotation rotation, Mirror mirror) {
        return cells.stream().map(c -> new Cell(transform(c.pos, rotation, mirror).offset(origin), transformState(c.state, rotation, mirror)))
                .sorted(Comparator.comparingInt((Cell c) -> c.pos.getY()).thenComparingInt(c -> c.pos.getZ()).thenComparingInt(c -> c.pos.getX())).toList();
    }

    static BlockState transformState(BlockState state, Rotation rotation, Mirror mirror) {
        BlockState transformed = state.mirror(mirror).rotate(rotation);
        // Vanilla ChestBlock.mirror rotates the facing but leaves handedness unchanged.
        // Reflecting both cells of a double chest must also exchange left and right.
        if (mirror != Mirror.NONE && state.getBlock() instanceof ChestBlock && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE)
            transformed = transformed.setValue(ChestBlock.TYPE, state.getValue(ChestBlock.TYPE) == ChestType.LEFT ? ChestType.RIGHT : ChestType.LEFT);
        return transformed;
    }

    static BlockPos transform(BlockPos p, Rotation rotation, Mirror mirror) {
        int x = p.getX(), z = p.getZ();
        if (mirror == Mirror.FRONT_BACK) x = -x;
        if (mirror == Mirror.LEFT_RIGHT) z = -z;
        return switch (rotation) {
            case NONE -> new BlockPos(x, p.getY(), z);
            case CLOCKWISE_90 -> new BlockPos(-z, p.getY(), x);
            case CLOCKWISE_180 -> new BlockPos(-x, p.getY(), -z);
            case COUNTERCLOCKWISE_90 -> new BlockPos(z, p.getY(), -x);
        };
    }

    static int minimum(int position, int size) { return size < 0 ? Math.addExact(position, size + 1) : position; }
    static int paletteIndex(long[] data, int bits, int index) {
        long bit = (long) index * bits;
        int word = (int) (bit >>> 6), shift = (int) (bit & 63);
        long value = data[word] >>> shift;
        if (shift + bits > 64) value |= data[word + 1] << (64 - shift);
        return (int) (value & ((1L << bits) - 1));
    }
    private static int dimension(CompoundTag tag, String key) {
        int n = integer(tag, key);
        if (n == 0 || n < -MAX_CELLS || n > MAX_CELLS) throw new IllegalArgumentException("Invalid region dimension " + n);
        return n;
    }
    private static int integer(CompoundTag tag, String key) {
        return tag.getInt(key).orElseThrow(() -> new IllegalArgumentException("Missing integer " + key));
    }
    private static CompoundTag compound(CompoundTag tag, String key) {
        return tag.getCompound(key).orElseThrow(() -> new IllegalArgumentException("Missing compound " + key));
    }
    private static BlockState state(CompoundTag tag) {
        String name = tag.getString("Name").orElseThrow(() -> new IllegalArgumentException("Missing block name"));
        Identifier id = Identifier.tryParse(name);
        if (id == null) throw new IllegalArgumentException("Invalid block id " + name);
        Block block = BuiltInRegistries.BLOCK.get(id).orElseThrow(() -> new IllegalArgumentException("Unknown block " + name)).value();
        BlockState state = block.defaultBlockState();
        CompoundTag properties = tag.getCompound("Properties").orElse(new CompoundTag());
        for (String key : properties.keySet()) {
            Property<?> property = block.getStateDefinition().getProperty(key);
            if (property == null) throw new IllegalArgumentException("Unknown property " + name + "." + key);
            state = property(state, property, properties.getString(key).orElseThrow(() -> new IllegalArgumentException("Invalid property " + key)));
        }
        return state;
    }
    private static <T extends Comparable<T>> BlockState property(BlockState state, Property<T> property, String value) {
        return state.setValue(property, property.getValue(value).orElseThrow(() -> new IllegalArgumentException("Invalid " + property.getName() + "=" + value)));
    }

    public static void main(String[] args) {
        assert minimum(4, -3) == 2 && minimum(-4, 3) == -4;
        assert transform(new BlockPos(2, 3, -4), Rotation.CLOCKWISE_90, Mirror.NONE).equals(new BlockPos(4, 3, 2));
        assert transform(new BlockPos(2, 3, -4), Rotation.CLOCKWISE_90, Mirror.FRONT_BACK).equals(new BlockPos(4, 3, -2));
        for (int bits = 2; bits < 17; bits++) {
            long[] data = new long[(257 * bits + 63) / 64];
            for (int i = 0; i < 257; i++) {
                long n = (i * 7L) & ((1L << bits) - 1), bit = (long) i * bits;
                int word = (int) (bit >>> 6), shift = (int) (bit & 63);
                data[word] |= n << shift;
                if (shift + bits > 64) data[word + 1] |= n >>> (64 - shift);
            }
            for (int i = 0; i < 257; i++) assert paletteIndex(data, bits, i) == ((i * 7) & ((1 << bits) - 1));
        }
        System.out.println("Litematic coordinates, transforms and cross-word palette packing passed");
    }
}
