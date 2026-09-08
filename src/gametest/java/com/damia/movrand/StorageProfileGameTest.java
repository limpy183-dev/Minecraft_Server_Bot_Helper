package com.damia.movrand;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.impl.client.gametest.world.TestWorldSaveImpl;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.Level;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.Collections;

/** Opt-in replay on a copy of a user's save and profile, never the originals. */
final class StorageProfileGameTest {
    static void run(ClientGameTestContext test) {
        Path source = Path.of(System.getenv("MOVRAND_STORAGE_WORLD")).toAbsolutePath().normalize();
        Path profile = Path.of(System.getenv("MOVRAND_STORAGE_PROFILE"));
        Path save = test.computeOnClient(mc -> mc.gameDirectory.toPath().resolve("saves/storage-profile-" + System.currentTimeMillis()));
        try {
            try (var paths = Files.walk(source)) {
                for (Path p : paths.toList()) {
                    if (p.getFileName().toString().equals("session.lock")) continue;
                    Path dest = save.resolve(source.relativize(p).toString());
                    if (Files.isDirectory(p)) Files.createDirectories(dest); else Files.copy(p, dest);
                }
            }
            test.runOnClient(mc -> {
                try {
                    Path dir = mc.gameDirectory.toPath().resolve("config/movrand-profiles");
                    Files.createDirectories(dir);
                    Files.copy(profile, dir.resolve("Main sweep config.json"), StandardCopyOption.REPLACE_EXISTING);
                } catch (Exception e) { throw new RuntimeException(e); }
            });
            try (var world = new TestWorldSaveImpl(test, save).open()) {
                world.getServer().runCommand("execute in minecraft:the_nether run fill 1102 128 102 1150 128 150 stone");
                world.getServer().runCommand("execute in minecraft:the_nether run fill 1102 129 102 1150 136 150 air");
                world.getServer().runCommand("execute in minecraft:the_nether run tp @p 1126.5 129 126.5 0 0");
                world.getServer().runCommand("gamemode survival @p");
                world.getServer().runCommand("clear @p");
                world.getServer().runOnServer(server -> {
                    var p = server.getPlayerList().getPlayers().getFirst();
                    p.removeAllEffects(); p.clearFire(); p.setTicksFrozen(0); p.setAirSupply(p.getMaxAirSupply());
                    p.setHealth(20); p.getFoodData().setFoodLevel(20);
                    ItemStack pick = new ItemStack(Items.DIAMOND_PICKAXE);
                    pick.enchant(server.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.SILK_TOUCH), 1);
                    p.getInventory().setItem(0, pick);
                    p.getInventory().setItem(1, new ItemStack(Items.ENDER_CHEST));
                    p.getInventory().setItem(9, new ItemStack(Items.SHULKER_BOX));
                    p.getInventory().setItem(10, new ItemStack(Items.DIAMOND, 16));
                    p.getEnderChestInventory().setItem(7, new ItemStack(Items.DYED_SHULKER_BOX.blue()));
                    p.inventoryMenu.broadcastChanges();
                });
                world.getConnection().waitForClientboundPackets(); test.waitTicks(40);
                test.runOnClient(mc -> {
                    require(mc.level.dimension().equals(Level.NETHER), "Expected Nether");
                    Config cfg = Config.loadProfile("Main sweep config");
                    require(cfg != null, "Profile missing");
                    cfg.storageEnabled = true; cfg.destroyerEnabled = true;
                    MovRand.replaceConfig(cfg);
                    MovRand.LOG.info("STORAGE PROFILE world={} profile={} protection={} position={} health={}", WorldId.current(), cfg.activeProfile, cfg.protectedSlots, mc.player.position(), mc.player.getHealth());
                    var t = MovRand.controller().storage.selectShulker(mc, 9, false);
                    t.items.add("minecraft:diamond");
                    t.fingerprint = reordered(t.fingerprint);
                    MovRand.controller().start(mc);
                    MovRand.controller().storage.storeNow(mc);
                });
                finish(test, "bag shulker with reordered saved fingerprint");
                world.getServer().runOnServer(server -> require(server.getPlayerList().getPlayers().getFirst().getInventory()
                        .contains(s -> Storage.shulker(s) && Storage.contents(s).getFirst().getCount() == 16), "Bag deposit lost"));
                test.runOnClient(mc -> MovRand.controller().storage.inspect(mc, MovRand.controller().storage.enderTarget(), null));
                finish(test, "ender inspection");
                world.getServer().runCommand("give @p diamond 3");
                world.getConnection().waitForClientboundPackets(); test.waitTicks(10);
                test.runOnClient(mc -> {
                    MovRand.config().storageTargets.clear();
                    var t = MovRand.controller().storage.selectShulker(mc, 7, true);
                    t.items.add("minecraft:diamond"); t.fingerprint = reordered(t.fingerprint);
                    MovRand.controller().start(mc); MovRand.controller().storage.storeNow(mc);
                });
                finish(test, "ender shulker with reordered saved fingerprint");
                world.getServer().runOnServer(server -> {
                    var p = server.getPlayerList().getPlayers().getFirst();
                    require(Storage.contents(p.getEnderChestInventory().getItem(7)).getFirst().getCount() == 3, "Ender deposit/return lost");
                    require(p.getInventory().contains(s -> s.is(Items.ENDER_CHEST)), "Ender chest not recovered");
                });
                test.takeScreenshot("storage-profile-passed");
                MovRand.LOG.info("STORAGE PROFILE PASS: bag deposit, ender inspection, ender shulker deposit and return, Silk Touch recovery");
            }
        } catch (Exception e) { throw new RuntimeException(e); }
        finally {
            if (baritone.Baritone.getExecutor() instanceof java.util.concurrent.ExecutorService executor) executor.shutdownNow();
        }
    }
    private static String reordered(String fingerprint) {
        JsonObject original = JsonParser.parseString(fingerprint).getAsJsonObject(), reversed = new JsonObject();
        var keys = new ArrayList<>(original.keySet()); Collections.reverse(keys);
        for (String key : keys) reversed.add(key, original.get(key));
        require(!original.toString().equals(reversed.toString()), "Regression needs changed field order");
        require(original.equals(reversed), "Regression changed item data");
        return reversed.toString();
    }
    private static void finish(ClientGameTestContext test, String label) {
        int ticks = test.waitFor(mc -> {
            require(!MovRand.controller().storage.failed(), label + ": " + MovRand.controller().storage.status);
            return !MovRand.controller().storage.busy();
        }, 2400);
        test.runOnClient(mc -> {
            MovRand.LOG.info("STORAGE PROFILE {}: {} in {} ticks", label, MovRand.controller().storage.status, ticks);
            MovRand.controller().stop(mc, label);
        });
    }
    private static void require(boolean ok, String reason) { if (!ok) throw new AssertionError(reason); }
}
