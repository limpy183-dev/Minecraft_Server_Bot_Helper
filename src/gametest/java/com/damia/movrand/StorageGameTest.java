package com.damia.movrand;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.entity.ChestBlockEntity;

import java.util.ArrayList;
import java.util.List;

/** Server-backed regressions for exact-slot deposits and portable-container recovery. */
final class StorageGameTest {
    static void run(ClientGameTestContext test, TestSingleplayerContext world) {
        setup(test, world);
        world.getServer().runCommand("setblock 2 1 0 chest");
        world.getServer().runOnServer(server -> {
            var p = server.getPlayerList().getPlayers().getFirst();
            p.getInventory().setItem(9, new ItemStack(Items.DIAMOND, 32));
            p.getInventory().setItem(11, new ItemStack(Items.DIAMOND, 8));
            ((ChestBlockEntity) server.overworld().getBlockEntity(new BlockPos(2, 1, 0)))
                    .setItem(26, new ItemStack(Items.DIAMOND, 60));
            p.inventoryMenu.broadcastChanges();
        });
        world.getConnection().waitForClientboundPackets();
        test.waitTicks(10);
        test.runOnClient(mc -> {
            var t = MovRand.controller().storage.selectWorld(mc, new BlockPos(2, 1, 0));
            t.items.add("minecraft:diamond");
            t.slots = new ArrayList<>(List.of(25, 26));
            t.slotOrder = Storage.SlotOrder.REVERSE_ROWS;
            MovRand.config().protectedSlots.add(11);
            MovRand.controller().start(mc);
            MovRand.controller().storage.storeNow(mc);
        });
        finish(test, "chest exact slots and partial stack", 1200);
        world.getServer().runOnServer(server -> {
            var c = (ChestBlockEntity) server.overworld().getBlockEntity(new BlockPos(2, 1, 0));
            require(c.getItem(26).getCount() == 64 && c.getItem(25).getCount() == 28, "Wrong ordered deposit totals");
            require(server.getPlayerList().getPlayers().getFirst().getInventory().getItem(11).getCount() == 8, "Protected stack moved");
        });

        setup(test, world);
        world.getServer().runOnServer(server -> {
            var p = server.getPlayerList().getPlayers().getFirst();
            p.getInventory().setItem(9, new ItemStack(Items.DYED_SHULKER_BOX.red()));
            p.getInventory().setItem(10, new ItemStack(Items.DIAMOND, 16));
            p.inventoryMenu.broadcastChanges();
        });
        world.getConnection().waitForClientboundPackets(); test.waitTicks(10);
        test.runOnClient(mc -> {
            var t = MovRand.controller().storage.selectShulker(mc, 9, false);
            t.items.add("minecraft:diamond"); t.slots = new ArrayList<>(List.of(4));
            MovRand.controller().start(mc); MovRand.controller().storage.storeNow(mc);
        });
        finish(test, "bag shulker recovery", 1200);
        world.getServer().runOnServer(server -> {
            var inv = server.getPlayerList().getPlayers().getFirst().getInventory();
            require(inv.contains(s -> s.is(Items.DYED_SHULKER_BOX.red()) && Storage.contents(s).get(4).getCount() == 16), "Filled bag shulker not recovered");
        });

        setup(test, world);
        world.getServer().runOnServer(server -> {
            var p = server.getPlayerList().getPlayers().getFirst();
            ItemStack pick = new ItemStack(Items.DIAMOND_PICKAXE);
            pick.enchant(server.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.SILK_TOUCH), 1);
            p.getInventory().setItem(12, pick); // exercise moving a pickaxe from the main inventory
            p.getInventory().setItem(2, new ItemStack(Items.ENDER_CHEST, 2)); // spare chest cannot count as recovery
            p.getInventory().setItem(9, new ItemStack(Items.DIAMOND, 16));
            p.getInventory().setItem(10, new ItemStack(Items.IRON_INGOT, 8));
            ItemStack box = new ItemStack(Items.DYED_SHULKER_BOX.white());
            box.set(DataComponents.CUSTOM_NAME, Component.literal("Diamonds"));
            p.getEnderChestInventory().setItem(7, box);
            p.getEnderChestInventory().setItem(20, new ItemStack(Items.DYED_SHULKER_BOX.blue()));
            p.inventoryMenu.broadcastChanges();
        });
        world.getConnection().waitForClientboundPackets(); test.waitTicks(10);
        test.runOnClient(mc -> {
            MovRand.config().protectedSlots.add(12);
            MovRand.controller().storage.inspect(mc, MovRand.controller().storage.enderTarget(), null);
        });
        finish(test, "ender chest inspection while movement is off", 1200);
        test.runOnClient(mc -> {
            require(MovRand.controller().storage.enderView().size() == 27, "Ender view did not load");
            var a = MovRand.controller().storage.selectShulker(mc, 7, true);
            var b = MovRand.controller().storage.selectShulker(mc, 20, true);
            require(a != null && b != null, "Could not select ender shulkers");
            a.items.add("minecraft:diamond"); a.slots = new ArrayList<>(List.of(5));
            b.items.add("minecraft:iron_ingot"); b.slots = new ArrayList<>(List.of(13));
            MovRand.controller().start(mc); MovRand.controller().storage.storeNow(mc);
        });
        finish(test, "two ender shulkers returned to original slots", 2400);
        world.getServer().runOnServer(server -> {
            var p = server.getPlayerList().getPlayers().getFirst();
            var ec = p.getEnderChestInventory();
            require(ec.getItem(7).is(Items.DYED_SHULKER_BOX.white()) && Storage.contents(ec.getItem(7)).get(5).getCount() == 16, "White shulker not returned to slot 7");
            require(ec.getItem(20).is(Items.DYED_SHULKER_BOX.blue()) && Storage.contents(ec.getItem(20)).get(13).getCount() == 8, "Blue shulker not returned to slot 20");
            int n = 0; for (int i = 0; i < 36; i++) if (p.getInventory().getItem(i).is(Items.ENDER_CHEST)) n += p.getInventory().getItem(i).getCount();
            require(n == 2, "Ender chest was not recovered with Silk Touch");
            require(Storage.silk(p.getInventory().getItem(12)), "Protected Silk Touch tool was not restored to its slot");
            for (BlockPos pos : BlockPos.betweenClosed(-5, 1, -5, 5, 1, 5)) {
                require(!(server.overworld().getBlockState(pos).getBlock() instanceof net.minecraft.world.level.block.ShulkerBoxBlock)
                        && !(server.overworld().getBlockState(pos).getBlock() instanceof net.minecraft.world.level.block.EnderChestBlock), "Portable container left behind");
            }
        });
        // Keep a filled shulker in the bag when return is disabled; future trips must follow it there.
        world.getServer().runOnServer(server -> {
            var p = server.getPlayerList().getPlayers().getFirst();
            p.getInventory().setItem(p.getInventory().getFreeSlot(), new ItemStack(Items.DIAMOND, 3)); p.inventoryMenu.broadcastChanges();
        });
        world.getConnection().waitForClientboundPackets(); test.waitTicks(10);
        test.runOnClient(mc -> {
            MovRand.config().storageReturnShulkers = false;
            MovRand.controller().start(mc); MovRand.controller().storage.storeNow(mc);
        });
        finish(test, "retain filled ender shulker in bag", 1600);
        world.getServer().runOnServer(server -> {
            var p = server.getPlayerList().getPlayers().getFirst();
            require(p.getEnderChestInventory().getItem(7).isEmpty(), "Return-off put the shulker back");
            require(p.getInventory().contains(s -> Storage.shulker(s) && Storage.contents(s).get(5).getCount() == 19), "Return-off lost the filled shulker");
        });
        test.runOnClient(mc -> require(MovRand.config().storageTargets.stream().anyMatch(t -> t.kind == Storage.Kind.INVENTORY_SHULKER
                && t.items.contains("minecraft:diamond")), "Retained shulker route still points at ender chest"));

        // A server-side change to the original slot must never be overwritten by the return.
        world.getServer().runOnServer(server -> {
            var p = server.getPlayerList().getPlayers().getFirst();
            p.getInventory().setItem(p.getInventory().getFreeSlot(), new ItemStack(Items.IRON_INGOT, 2));
            p.inventoryMenu.broadcastChanges();
        });
        world.getConnection().waitForClientboundPackets(); test.waitTicks(5);
        test.runOnClient(mc -> {
            MovRand.config().storageReturnShulkers = true;
            MovRand.controller().start(mc); MovRand.controller().storage.storeNow(mc);
        });
        test.waitFor(mc -> {
            require(!MovRand.controller().storage.failed(), MovRand.controller().storage.status);
            return MovRand.controller().storage.status.endsWith(" · return");
        }, 1400);
        world.getServer().runOnServer(server -> {
            var p = server.getPlayerList().getPlayers().getFirst();
            p.getEnderChestInventory().setItem(20, new ItemStack(Items.DIRT));
            p.containerMenu.broadcastChanges();
        });
        world.getConnection().waitForClientboundPackets();
        test.waitFor(mc -> MovRand.controller().storage.failed(), 60);
        world.getServer().runOnServer(server -> {
            var p = server.getPlayerList().getPlayers().getFirst();
            require(p.getEnderChestInventory().getItem(20).is(Items.DIRT), "Occupied return slot was overwritten");
            require(p.getInventory().contains(s -> s.is(Items.DYED_SHULKER_BOX.blue()) && Storage.contents(s).get(13).getCount() == 10),
                    "Filled shulker lost after occupied return slot");
        });

        // Unsafe liquid distances are checked in 3D, and every player counts even if general player alerts are off.
        world.getServer().runCommand("setblock 5 1 0 water");
        world.getConnection().waitForClientboundPackets(); test.waitTicks(5);
        test.runOnClient(mc -> {
            var ctx = new PathMove.Ctx(mc, mc.player, mc.level, MovRand.config());
            require(!MovRand.controller().storage.safe(ctx, new BlockPos(0, 1, 0)), "Water within five blocks accepted");
        });
        world.getServer().runCommand("fill -8 1 -8 8 2 8 air");
        world.getConnection().waitForClientboundPackets(); test.waitTicks(5);
        test.runOnClient(mc -> {
            var other = new net.minecraft.client.player.RemotePlayer(mc.level, new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "StorageTestPlayer"));
            other.setId(999999); other.setPos(3, 1, 3); mc.level.addEntity(other);
            require(!MovRand.controller().storage.safe(new PathMove.Ctx(mc, mc.player, mc.level, MovRand.config()), new BlockPos(0, 1, 0)),
                    "Nearby player accepted with general alerts disabled");
            mc.level.removeEntity(other.getId(), net.minecraft.world.entity.Entity.RemovalReason.DISCARDED);
        });
        // No Silk Touch: fail before placing anything, including during UI inspection while movement is off.
        setup(test, world);
        world.getServer().runCommand("give @p ender_chest");
        world.getConnection().waitForClientboundPackets(); test.waitTicks(5);
        test.runOnClient(mc -> MovRand.controller().storage.inspect(mc, MovRand.controller().storage.enderTarget(), null));
        test.waitFor(mc -> MovRand.controller().storage.failed(), 60);
        test.runOnClient(mc -> require(mc.player.getInventory().contains(s -> s.is(Items.ENDER_CHEST))
                && MovRand.controller().storage.status.contains("Silk Touch"), "Missing Silk Touch consumed a chest"));

        // A player arriving after placement must stop extraction before the shulker leaves its slot.
        setup(test, world);
        world.getServer().runOnServer(server -> {
            var p = server.getPlayerList().getPlayers().getFirst();
            ItemStack pick = new ItemStack(Items.DIAMOND_PICKAXE);
            pick.enchant(server.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.SILK_TOUCH), 1);
            p.getInventory().setItem(0, pick); p.getInventory().setItem(1, new ItemStack(Items.ENDER_CHEST));
            p.getInventory().setItem(9, new ItemStack(Items.DIAMOND));
            p.getEnderChestInventory().setItem(6, new ItemStack(Items.SHULKER_BOX));
            p.inventoryMenu.broadcastChanges();
        });
        world.getConnection().waitForClientboundPackets(); test.waitTicks(5);
        test.runOnClient(mc -> {
            Storage.Target t = new Storage.Target(); t.kind = Storage.Kind.ENDER_SHULKER; t.enderSlot = 6;
            t.world = WorldId.current(); t.fingerprint = Storage.fingerprint(new ItemStack(Items.SHULKER_BOX), mc.player);
            t.items.add("minecraft:diamond"); MovRand.config().storageTargets.add(t);
            MovRand.controller().start(mc); MovRand.controller().storage.storeNow(mc);
        });
        test.waitFor(mc -> MovRand.controller().storage.status.endsWith(" · take"), 500);
        test.runOnClient(mc -> {
            var other = new net.minecraft.client.player.RemotePlayer(mc.level, new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "StorageInterruption"));
            other.setId(999998); other.setPos(mc.player.getX() + 2, mc.player.getY(), mc.player.getZ()); mc.level.addEntity(other);
        });
        test.waitFor(mc -> MovRand.controller().storage.failed(), 40);
        world.getServer().runOnServer(server -> require(server.getPlayerList().getPlayers().getFirst()
                .getEnderChestInventory().getItem(6).is(Items.SHULKER_BOX), "Unsafe extraction took the shulker"));
        test.runOnClient(mc -> mc.level.removeEntity(999998, net.minecraft.world.entity.Entity.RemovalReason.DISCARDED));

        // Render the actual Storage tab at the test client's compact UI size.
        test.runOnClient(mc -> {
            var screen = new com.damia.movrand.gui.ConfigScreen(); mc.setScreenAndShow(screen);
            for (int i = 0; i < 15; i++) screen.keyPressed(new net.minecraft.client.input.KeyEvent(org.lwjgl.glfw.GLFW.GLFW_KEY_TAB, 0, 0));
        });
        test.takeScreenshot("storage-settings");
        test.runOnClient(mc -> mc.gui.screen().onClose());
        MovRand.LOG.info("Storage server-backed checks passed");
    }

    private static void setup(ClientGameTestContext test, TestSingleplayerContext world) {
        test.runOnClient(mc -> {
            MovRand.controller().stop(mc, "Storage test setup");
            Config c = new Config(); c.storageEnabled = true; c.destroyerEnabled = false;
            c.containerScanEnabled = false; c.stopOnChatKeyword = false; c.stopWhenUnfocused = false;
            c.alertEnabled = false; c.safeStopEnabled = false; c.autoEatEnabled = false;
            c.stopOnNearbyPlayer = false; c.stopOnHostileMob = false;
            c.fastDestroyerTuning();
            MovRand.replaceConfig(c);
        });
        var server = world.getServer();
        server.runCommand("kill @e[type=item]"); server.runCommand("gamemode survival @p"); server.runCommand("clear @p");
        server.runCommand("fill -24 1 -24 24 8 24 air"); server.runCommand("fill -24 0 -24 24 0 24 stone");
        server.runCommand("tp @p 0.5 1 0.5 0 0");
        server.runCommand("effect give @p instant_health 1 4 true");
        world.getConnection().waitForClientboundPackets(); test.waitTicks(10);
    }
    private static void finish(ClientGameTestContext test, String name, int max) {
        try {
            java.util.concurrent.atomic.AtomicInteger trace = new java.util.concurrent.atomic.AtomicInteger();
            test.waitFor(mc -> {
                if (trace.incrementAndGet() % 100 == 0) MovRand.LOG.info("STORAGE TRACE {} {} attack {} hit {} screen {} destroying {} grabbed {}", name,
                        MovRand.controller().storage.status, mc.options.keyAttack.isDown(), Bot.hitBlock(mc), mc.gui.screen(), mc.gameMode.isDestroying(), mc.mouseHandler.isMouseGrabbed());
                require(!MovRand.controller().storage.failed(), name + ": " + MovRand.controller().storage.status);
                return !MovRand.controller().storage.busy();
            }, max);
            test.runOnClient(mc -> MovRand.controller().stop(mc, "Storage check passed: " + name));
        } catch (Throwable e) {
            test.runOnClient(mc -> MovRand.LOG.error("Storage test {} at {}: {} menu {}", name,
                    mc.player.position(), MovRand.controller().storage.status, mc.player.containerMenu));
            test.takeScreenshot("storage-failure"); throw e;
        }
    }
    private static void require(boolean ok, String why) { if (!ok) throw new AssertionError(why); }
}
