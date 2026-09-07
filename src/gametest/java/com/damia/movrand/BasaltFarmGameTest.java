package com.damia.movrand;

import baritone.api.BaritoneAPI;
import com.google.gson.Gson;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.impl.client.gametest.world.TestWorldSaveImpl;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.io.BufferedWriter;
import java.lang.reflect.Field;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;

/** Opt-in destructive benchmark. Every case opens a fresh copy of the supplied save. */
public final class BasaltFarmGameTest implements FabricClientGameTest {
    private static final Gson JSON = new Gson();
    private static final AABB BOX = new AABB(1108, 40, 84, 1185, 86, 159);
    private static final Queue<Map<String,Object>> EVENTS = new ConcurrentLinkedQueue<>();
    private static volatile BasaltFarmGameTest active;
    private BufferedWriter trace;
    private int tick;
    private int lastBreakTick;
    private int renderDistance;
    private Path output;
    private final List<Map<String,Object>> events = new ArrayList<>();
    private List<BlockPos> originalTargets=List.of();
    private record Case(String name, BlockPos start, String selection, BlockPos drop, int ticks) {}

    public static void decision(List<?> candidates, BlockPos chosen) {
        if (active != null && chosen != null) {
            var event=new LinkedHashMap<String,Object>();
            event.put("kind","selection"); event.put("pos",xyz(chosen)); event.put("candidates",JSON.toJsonTree(candidates));
            event.put("global_original_position_audit",active.auditCandidates()); EVENTS.add(event);
        }
    }
    public static void failure(String kind, String detail, BlockPos pos) {
        if (active != null) {
            var event=new LinkedHashMap<String,Object>(); event.put("kind",kind); event.put("detail",detail); event.put("pos",xyz(pos)); EVENTS.add(event);
        }
    }

    public static void placement(Level level, BlockPos pos, BlockState state) {
        if (active != null && !level.isClientSide())
            EVENTS.add(Map.of("kind","place","server_tick",level.getGameTime(),"pos",xyz(pos),"block",id(state)));
    }

    @Override public void runTest(ClientGameTestContext test) {
        Path source = Path.of(System.getenv("MOVRAND_BASALT_SOURCE")).toAbsolutePath().normalize();
        output = Path.of(System.getenv("MOVRAND_BASALT_OUTPUT")).toAbsolutePath();
        try {
            Files.createDirectories(output);
            renderDistance=test.computeOnClient(mc -> mc.options.renderDistance().get());
            ClientTickEvents.END_CLIENT_TICK.register(mc -> { if (active == this && mc.player != null) sample(mc); });
            PlayerBlockBreakEvents.AFTER.register((level, player, pos, state, entity) -> {
                if (active == this) EVENTS.add(Map.of("kind","break","server_tick",level.getGameTime(),"pos",xyz(pos),"block",id(state)));
            });
            List<Case> cases = List.of(
                new Case("survey", new BlockPos(1126,65,126), "none", null, 0),
                new Case("west-selected", new BlockPos(1126,65,126), "redstone_block", null, 2400),
                new Case("north-selected", new BlockPos(1137,77,101), "redstone_block", null, 2400),
                new Case("east-selected", new BlockPos(1169,73,118), "redstone_block", null, 2400),
                new Case("south-selected", new BlockPos(1133,65,141), "redstone_block", null, 2400),
                new Case("lower-floor-overhang", new BlockPos(1136,76,103), "none", new BlockPos(1141,65,121), 1800),
                new Case("gap-platforms", new BlockPos(1123,73,117), "none", new BlockPos(1125,71,118), 1800),
                new Case("enclosed-room", new BlockPos(1141,65,121), "redstone_lamp", null, 2400),
                new Case("lava-edge", new BlockPos(1123,69,120), "redstone_block", null, 2400),
                new Case("water-channel", new BlockPos(1136,76,103), "none", new BlockPos(1130,62,102), 2400),
                new Case("overhang-only", new BlockPos(1126,65,126), "none", new BlockPos(1132,65,126), 1200),
                new Case("unloaded-boundary", new BlockPos(1126,65,126), "diamond_block", null, 800),
                new Case("lava-adjacent-pistons", new BlockPos(1129,65,97), "piston", null, 1200),
                new Case("priority-audit", new BlockPos(1126,65,126), "family", null, 2400),
                new Case("shortlist-recheck", new BlockPos(1126,65,126), "family", null, 3000),
                new Case("working-position-control", new BlockPos(1126,65,126), "redstone_block", null, 1200),
                new Case("full-redstone", new BlockPos(1126,65,126), "family", null, 24000));
            String filter = System.getenv().getOrDefault("MOVRAND_BASALT_CASES", "survey");
            for (Case c : cases) if (filter.equals("all") || Arrays.asList(filter.split(",")).contains(c.name)) {
                runCase(test, source, c);
            }
        } catch (Exception e) { throw new RuntimeException(e); }
        finally {
            active = null;
            if (baritone.Baritone.getExecutor() instanceof java.util.concurrent.ExecutorService executor) executor.shutdownNow();
        }
    }

    private void runCase(ClientGameTestContext test, Path source, Case c) throws Exception {
        if(Files.exists(output.resolve(c.name).resolve("end.json"))) throw new IllegalStateException("Completed case exists; use a fresh MOVRAND_BASALT_OUTPUT directory: "+c.name);
        Path save = test.computeOnClient(mc -> mc.gameDirectory.toPath().resolve("saves/basalt-" + c.name));
        if (save.toAbsolutePath().normalize().startsWith(source)) throw new IllegalArgumentException("test save overlaps source");
        try (var paths = Files.walk(source)) {
            for (Path p : paths.toList()) {
                Path rel = source.relativize(p);
                if (rel.startsWith("baritone") || p.getFileName().toString().equals("session.lock")) continue;
                Path dest = save.resolve(rel.toString());
                if (Files.isDirectory(p)) Files.createDirectories(dest); else Files.copy(p, dest, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        Path dir = output.resolve(c.name); Files.createDirectories(dir);
        try (var world = new TestWorldSaveImpl(test, save).open()) {
            test.runOnClient(mc -> {
                MovRand.controller().stop(mc,"basalt setup"); mc.options.pauseOnLostFocus = false;
                mc.options.inactivityFpsLimit().set(net.minecraft.client.InactivityFpsLimit.MINIMIZED);
                mc.options.renderDistance().set(c.name.equals("unloaded-boundary")?2:renderDistance); mc.options.broadcastOptions();
            });
            world.getServer().runCommand("execute in minecraft:the_nether run tp @p " + (c.start.getX()+0.5) + " " + c.start.getY() + " " + (c.start.getZ()+0.5) + " -90 0");
            world.getServer().runCommand("gamemode survival @p");
            world.getServer().runCommand("clear @p");
            world.getServer().runCommand("give @p diamond_pickaxe");
            world.getServer().runCommand("give @p diamond_axe");
            world.getServer().runCommand("give @p diamond_sword");
            world.getServer().runCommand("give @p cobblestone 256");
            world.getServer().runCommand("give @p cooked_beef 64");
            world.getServer().runOnServer(server -> {
                var player = server.getPlayerList().getPlayers().getFirst();
                player.setHealth(20); player.getFoodData().setFoodLevel(20);
            });
            world.getConnection().waitForClientboundPackets(); test.waitTicks(40);
            test.runOnClient(mc -> {
                if (!mc.level.dimension().equals(Level.NETHER)) throw new AssertionError("not in Nether");
                Config cfg = new Config(); cfg.terrainDefaults(); cfg.fastDestroyerTuning();
                cfg.destroyerEnabled = true; cfg.destroyStopWhenDone = true;
                cfg.containerScanEnabled = false; cfg.stopOnChatKeyword = false; cfg.stopWhenUnfocused = false;
                cfg.alertEnabled = false; cfg.destroyLoadedChunks = false;
                cfg.destroyRadius = 80; cfg.destroyVerticalRadius = 40;
                if(c.name.equals("lava-adjacent-pistons")) { cfg.destroyRadius=6;cfg.destroyVerticalRadius=6; }
                if(c.drop != null) { cfg.collectRadius=48; cfg.coverLiquids=false; }
                for (BlockTargets.Family f : BlockTargets.Family.values()) cfg.setDestroyFamily(f, false);
                cfg.destroyBlocks.clear();
                if (c.selection.equals("family")) cfg.setDestroyFamily(BlockTargets.Family.REDSTONE,true);
                else if (!c.selection.equals("none")) cfg.destroyBlocks.add(c.selection);
                cfg.clampAll(); MovRand.replaceConfig(cfg);
                if(c.name.equals("priority-audit")) cfg.destroyTargetRandomness=0;
                if(c.name.equals("working-position-control")) cfg.protectMiningDrops=false;
                var selected=new BlockTargets().blocks(cfg);
                var initial=new ArrayList<BlockPos>();
                for(int x=1108;x<=1184;x++) for(int z=84;z<=158;z++) if(mc.level.hasChunk(x>>4,z>>4)) for(int y=40;y<=85;y++) {
                    var p=new BlockPos(x,y,z); if(selected.contains(mc.level.getBlockState(p).getBlock())) initial.add(p);
                }
                originalTargets=initial;
                write(dir.resolve("config.json"), cfg);
                write(dir.resolve("start.json"), Map.of("position",vec(mc.player.position()),"on_ground",mc.player.onGround(),"health",mc.player.getHealth(),"case",c));
                if (mc.player.position().distanceTo(Vec3.atBottomCenterOf(c.start)) > 2 || mc.player.getHealth() < 20)
                    throw new AssertionError("unsafe initial position " + mc.player.position());
            });
            survey(world, dir.resolve("before.json"));
            test.runOnClient(mc -> write(dir.resolve("client-before.json"), clientSurvey(mc)));
            test.runOnClient(mc -> workCells(mc,dir.resolve("working-cells.json")));
            Files.copy(test.takeScreenshot("basalt-" + c.name + "-before"),dir.resolve("before.png"),StandardCopyOption.REPLACE_EXISTING);
            if (c.ticks == 0) return;
            if (c.drop != null) {
                world.getServer().runOnServer(server -> {
                    var level = server.getLevel(Level.NETHER);
                    var item = new ItemEntity(level,c.drop.getX()+0.5,c.drop.getY()+0.2,c.drop.getZ()+0.5,new ItemStack(Items.DIAMOND));
                    item.setDeltaMovement(Vec3.ZERO); level.addFreshEntity(item);
                });
                world.getConnection().waitForClientboundPackets();
            }
            tick = 0; lastBreakTick=0; events.clear(); EVENTS.clear();
            trace = Files.newBufferedWriter(dir.resolve("ticks.jsonl"));
            test.runOnClient(mc -> { MovRand.controller().start(mc); active = this; });
            try {
                for (int n = 0; n < c.ticks; n += 20) {
                    test.waitTicks(20);
                    boolean done = test.computeOnClient(mc -> mc.player.isDeadOrDying()
                        || (c.drop != null && mc.player.getInventory().contains(s -> s.is(Items.DIAMOND)))
                        || MovRand.controller().destroyer.phase == BaseDestroyer.Phase.OFF);
                    if (done) break;
                    if(c.name.equals("full-redstone") && tick-lastBreakTick>=3600) {
                        write(dir.resolve("stopped-for-stagnation.json"),Map.of("tick",tick,"last_confirmed_break_tick",lastBreakTick,"reason","180 seconds without a confirmed player break across multiple retry windows"));
                        break;
                    }
                }
            } finally {
                test.runOnClient(mc -> {
                    active = null;
                    write(dir.resolve("end.json"), Map.of("ticks",tick,"position",vec(mc.player.position()),"health",mc.player.getHealth(),"inventory",inventory(mc),"state",MovRand.controller().destroyer.describe(),"reason",MovRand.controller().lastReason));
                    var journal=MovRand.controller().journal;
                    write(dir.resolve("journal.json"),journal.all().stream().filter(e -> e.session().equals(journal.sessionId())).toList());
                    MovRand.controller().stop(mc,"basalt case finished");
                });
                trace.close();
                write(dir.resolve("events.json"), events);
                survey(world, dir.resolve("after.json"));
                Files.copy(test.takeScreenshot("basalt-" + c.name + "-after"),dir.resolve("after.png"),StandardCopyOption.REPLACE_EXISTING);
                MovRand.LOG.info("BASALT CASE {} completed {} ticks",c.name,tick);
            }
        }
        Path retained=output.resolve("worlds").resolve(c.name);
        try(var paths=Files.walk(save)) {
            for(Path p:paths.toList()) {
                var dest=retained.resolve(save.relativize(p).toString());
                if(Files.isDirectory(p)) Files.createDirectories(dest); else Files.copy(p,dest,StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private void sample(Minecraft mc) {
        try {
            var job = MovRand.controller().destroyer;
            Map<String,Object> row = new LinkedHashMap<>();
            row.put("tick", tick++); row.put("server_tick",mc.level.getGameTime()); row.put("position",vec(mc.player.position()));
            row.put("target",xyz(job.target())); row.put("phase",job.phase); row.put("detail",job.detail);
            row.put("health",mc.player.getHealth()); row.put("inventory",inventory(mc));
            row.put("food",mc.player.getFoodData().getFoodLevel()); row.put("air",mc.player.getAirSupply());
            row.put("on_ground",mc.player.onGround()); row.put("water",mc.player.isInWater()); row.put("lava",mc.player.isInLava());
            row.put("yaw",mc.player.getYRot()); row.put("pitch",mc.player.getXRot());
            row.put("mined_counter",job.mined); row.put("placed_counter",job.placed); row.put("collected_counter",job.collected);
            row.put("retries",String.valueOf(field(job,"retries"))); row.put("target_ticks",field(job,"targetTicks"));
            Object drops=field(job,"drops"); int itemId=(int)field(drops,"itemId");
            row.put("drop_id",itemId);row.put("drop_retries",String.valueOf(field(drops,"retries")));
            var dropEntity=mc.level.getEntity(itemId);row.put("drop_position",dropEntity==null?null:vec(dropEntity.position()));
            var prep=field(job,"preparation");row.put("protection_block",xyz((BlockPos)field(prep,"protecting")));row.put("placement_target",xyz((BlockPos)field(prep,"placing")));
            var scan=(BlockTargets.ScanResult)field(job,"lastScan");
            row.put("scan",Map.of("matching",scan.matching(),"eligible",scan.eligible(),"hidden",scan.hidden(),"scanned_chunks",scan.scannedChunks(),"unloaded_chunks",scan.unloadedChunks(),"complete",scan.complete()));
            var owner = field(null,NativeNavigation.class,"owner");
            row.put("route_state",owner == null ? "none" : ((NativeNavigation)owner).status);
            row.put("route_failures",owner == null ? 0 : field(owner,"failures"));
            var engine = BaritoneAPI.getProvider().getPrimaryBaritone();
            var path = engine.getPathingBehavior().getCurrent();
            row.put("path_nodes",path == null ? List.of() : path.getPath().positions().stream().map(BasaltFarmGameTest::xyz).toList());
            row.put("path_length",path == null ? 0 : path.getPath().length());
            row.put("path_step",path == null ? 0 : path.getPosition());
            row.put("nodes_considered",path == null ? 0 : path.getPath().getNumNodesConsidered());
            row.put("path_cost_ticks",path == null ? 0 : path.getPath().ticksRemainingFrom(Math.min(path.getPosition(),path.getPath().length()-1)));
            row.put("controller_reason",MovRand.controller().lastReason);
            List<Map<String,Object>> batch = new ArrayList<>();
            for (Map<String,Object> event; (event = EVENTS.poll()) != null;) { var copy = new LinkedHashMap<>(event); copy.put("observed_tick",tick-1); batch.add(copy); events.add(copy); if(event.get("kind").equals("break")) lastBreakTick=tick-1; }
            row.put("events",batch);
            trace.write(JSON.toJson(row)); trace.newLine(); if (tick % 100 == 0) trace.flush();
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private static Object field(Object obj, String name) { return field(obj,obj.getClass(),name); }
    private Map<String,Object> auditCandidates() {
        try {
            var mc=Minecraft.getInstance(); var cfg=MovRand.config(); var job=MovRand.controller().destroyer;
            var selected=new BlockTargets().blocks(cfg);
            var eligible=BaseDestroyer.class.getDeclaredMethod("eligibleForScan",net.minecraft.client.multiplayer.ClientLevel.class,net.minecraft.client.player.LocalPlayer.class,BlockPos.class);
            eligible.setAccessible(true);
            BlockPos best=null; double bestDistance=Double.POSITIVE_INFINITY; boolean bestReach=false; int count=0;
            for(var p:originalTargets) {
                if(!mc.level.hasChunk(p.getX()>>4,p.getZ()>>4) || !selected.contains(mc.level.getBlockState(p).getBlock())
                    || !BlockTargets.breakable(mc.level.getBlockState(p),mc.level,p) || Storage.protectedWorldBlock(cfg,mc.level,p)
                    || !(boolean)eligible.invoke(job,mc.level,mc.player,p)) continue;
                double dx=p.getX()+0.5-mc.player.getX(),dz=p.getZ()+0.5-mc.player.getZ();
                if(dx*dx+dz*dz>cfg.destroyRadius*cfg.destroyRadius || Math.abs(p.getY()-mc.player.getY())>cfg.destroyVerticalRadius) continue;
                count++;
                double d=Bot.blockCentre(mc,p).distanceTo(mc.player.getEyePosition());
                double[] look=Bot.aimAt(mc.player,Bot.blockCentre(mc,p));
                boolean reach=d<=mc.player.blockInteractionRange() && (Bot.visibleFace(mc,mc.player,p,null)!=null || Bot.rotationHits(mc,mc.player,p,look[0],look[1]));
                if(best==null || (reach && !bestReach) || (reach==bestReach && d<bestDistance)) { best=p;bestDistance=d;bestReach=reach; }
            }
            var result=new LinkedHashMap<String,Object>();result.put("eligible_count",count);result.put("nearest",xyz(best));result.put("distance",best==null?null:bestDistance);result.put("reachable",bestReach);result.put("block",best==null?null:mc.level.getBlockState(best).toString());return result;
        } catch(ReflectiveOperationException e) { throw new RuntimeException(e); }
    }
    private static Object field(Object obj, Class<?> type, String name) {
        try { Field f = type.getDeclaredField(name); f.setAccessible(true); return f.get(obj); }
        catch (ReflectiveOperationException e) { throw new RuntimeException(e); }
    }
    private static Map<String,Integer> inventory(Minecraft mc) {
        Map<String,Integer> out = new TreeMap<>();
        for (int i=0; i<mc.player.getInventory().getContainerSize(); i++) {
            var s=mc.player.getInventory().getItem(i); if (!s.isEmpty()) out.merge(BuiltInRegistries.ITEM.getKey(s.getItem()).toString(),s.getCount(),Integer::sum);
        }
        return out;
    }
    private static Map<String,Object> clientSurvey(Minecraft mc) {
        List<List<Integer>> loaded=new ArrayList<>(), unloaded=new ArrayList<>();
        for(int x=69;x<=74;x++) for(int z=5;z<=9;z++) (mc.level.hasChunk(x,z)?loaded:unloaded).add(List.of(x,z));
        return Map.of("loaded_chunks",loaded,"unloaded_chunks",unloaded,"drops",mc.level.getEntitiesOfClass(ItemEntity.class,BOX).stream().map(e -> Map.of("position",vec(e.position()),"item",e.getItem().toString())).toList());
    }
    private static void workCells(Minecraft mc,Path file) {
        List<Map<String,Object>> result=new ArrayList<>();
        for(var target:List.of(new BlockPos(1118,70,129),new BlockPos(1122,70,117),new BlockPos(1146,62,121))) {
            List<List<Integer>> reachable=new ArrayList<>(),pickup=new ArrayList<>();
            for(int x=target.getX()-6;x<=target.getX()+6;x++) for(int z=target.getZ()-6;z<=target.getZ()+6;z++) for(int y=target.getY()-6;y<=target.getY()+6;y++) {
                if(Bot.canWorkFrom(mc.level,mc.player,x,y,z,target,Bot.blockCentre(mc,target),mc.player.blockInteractionRange()-0.8)) {
                    reachable.add(List.of(x,y,z));
                    if(DropCollector.pickupOverlap(new AABB(x+0.2,y,z+0.2,x+0.8,y+1.8,z+0.8),new AABB(target).deflate(0.25))) pickup.add(List.of(x,y,z));
                }
            }
            var ctx=new PathMove.Ctx(mc,mc.player,mc.level,MovRand.config());
            var assessment=MineSafety.inspect(ctx,target);var placements=new ArrayList<Map<String,Object>>();
            for(var cover:assessment.cover()) {
                int working=0;
                for(int x=cover.getX()-6;x<=cover.getX()+6;x++) for(int z=cover.getZ()-6;z<=cover.getZ()+6;z++) for(int y=cover.getY()-6;y<=cover.getY()+6;y++)
                    if(NativeNavigation.placementAim(ctx,new Vec3(x+0.5,y+1.27,z+0.5),cover)!=null) working++;
                placements.add(Map.of("cover",xyz(cover),"state",mc.level.getBlockState(cover).toString(),"placement_work_cells",working));
            }
            result.add(Map.of("target",xyz(target),"ray_reachable_cells",reachable,"pickup_overlap_cells",pickup,"safety",assessment,"protection",placements));
        }
        write(file,result);
    }
    private static void survey(TestSingleplayerContext world, Path file) {
        world.getServer().runOnServer(server -> {
            var level=server.getLevel(Level.NETHER); Map<String,Integer> counts=new TreeMap<>();
            List<Map<String,Object>> selected=new ArrayList<>(); List<List<Integer>> missing=new ArrayList<>();
            Config cfg=new Config(); for(var f:BlockTargets.Family.values()) cfg.setDestroyFamily(f,f==BlockTargets.Family.REDSTONE);
            var redstone=new BlockTargets().blocks(cfg);
            for(int cx=1108>>4;cx<=1184>>4;cx++) for(int cz=84>>4;cz<=158>>4;cz++) if(!level.hasChunk(cx,cz)) missing.add(List.of(cx,cz));
            for(int x=1108;x<=1184;x++) for(int z=84;z<=158;z++) {
                if (!level.hasChunk(x>>4,z>>4)) continue;
                for(int y=40;y<=85;y++) {
                    var p=new BlockPos(x,y,z); var state=level.getBlockState(p); counts.merge(id(state),1,Integer::sum);
                    if(redstone.contains(state.getBlock())) selected.add(Map.of("pos",xyz(p),"block",id(state)));
                }
            }
            var drops=level.getEntitiesOfClass(ItemEntity.class,BOX).stream().map(e -> Map.of("position",vec(e.position()),"item",e.getItem().toString())).toList();
            write(file,Map.of("bounds",List.of(1108,40,84,1184,85,158),"counts",counts,"redstone",selected,"drops",drops,"unloaded_chunks",missing,"server_tick",level.getGameTime()));
        });
    }
    private static String id(BlockState s) { return BuiltInRegistries.BLOCK.getKey(s.getBlock()).toString(); }
    private static List<Integer> xyz(BlockPos p) { return p == null ? null : List.of(p.getX(),p.getY(),p.getZ()); }
    private static List<Double> vec(Vec3 p) { return List.of(p.x,p.y,p.z); }
    private static void write(Path path,Object data) { try { Files.writeString(path,JSON.toJson(data)); } catch(Exception e) { throw new RuntimeException(e); } }
}
