package com.damia.movrand;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

/**
 * Fighting back.
 *
 * <p>The rule this is built around is that the bot defends and does not hunt. A skeleton
 * across the room is not a reason to abandon a job; a skeleton shooting you is. Hunting also
 * happens to be the version that gets you caught — a client that turns to face something the
 * moment it enters render distance is describing itself.
 *
 * <p>Attacks are discrete swings rather than a held button, because that is what vanilla
 * sends for an entity, and they wait for the cooldown, because a swing at forty percent
 * charge does forty percent damage and no person swings that way on purpose.
 */
public final class Combat {

	private final Config cfg;

	private Entity target;
	private int swingCooldown;
	/** Ticks since something last hurt us — the difference between a threat and scenery. */
	private int sinceHurt = 9999;
	private int engagedTicks;
	public String status = "idle";

	public Combat(Config cfg) {
		this.cfg = cfg;
	}

	public Entity target() {
		return target;
	}

	public boolean engaged() {
		return target != null;
	}

	/** Told by the controller, which is already working out how much health went missing. */
	public void onDamage(float lost) {
		if (lost > 0) sinceHurt = 0;
	}

	/**
	 * @return true when a fight is happening and the task above should hold off
	 */
	public boolean tick(Minecraft mc, LocalPlayer player, Bot.Steer steer) {
		if (sinceHurt < 9999) sinceHurt++;
		if (swingCooldown > 0) swingCooldown--;

		if (!cfg.combatEnabled || mc.level == null) {
			target = null;
			status = cfg.combatEnabled ? "no world" : "off";
			return false;
		}

		target = pickTarget(mc, player);
		if (target == null) {
			engagedTicks = 0;
			status = "clear";
			return false;
		}
		engagedTicks++;

		double distance = Math.sqrt(target.distanceToSqr(player));
		String name = target.getName().getString();

		// Losing badly is not a fight, it is a death. Back off and let the guards decide.
		if (cfg.combatRetreat && player.getHealth() <= cfg.combatRetreatHealth) {
			Vec3 away = player.position().subtract(target.position());
			double yaw = Math.toDegrees(Math.atan2(-away.x, away.z));
			steer.lookAt(yaw, 0);
			steer.forward = true;
			steer.sprint = true;
			status = "backing away from " + name;
			return true;
		}

		double[] look = Bot.aimAt(player, aimPointOn(target));
		steer.lookAt(look[0], look[1]);

		// a shield is worth raising while closing the distance, not while swinging
		if (cfg.combatUseShield && hasShield(player) && distance > 2.5) steer.use = true;

		if (distance > player.entityInteractionRange()) {
			steer.forward = cfg.combatChase;
			steer.sprint = cfg.combatChase;
			status = "closing on %s (%.1f blocks)".formatted(name, distance);
			return true;
		}

		int weapon = bestWeaponSlot(player);
		if (weapon >= 0 && weapon != player.getInventory().getSelectedSlot()) {
			player.getInventory().setSelectedSlot(weapon);
		}

		boolean charged = player.getAttackStrengthScale(0) >= cfg.combatMinCharge;
		if (charged && swingCooldown <= 0 && mc.gameMode != null) {
			mc.gameMode.attack(player, target);
			player.swing(InteractionHand.MAIN_HAND);
			// a little scatter on top of the cooldown: a perfectly periodic swing is a
			// signature, and the cooldown alone would produce exactly that
			swingCooldown = Math.max(1, Rng.ticks(cfg.combatSwingMinSec, cfg.combatSwingMaxSec));
		}
		status = "fighting " + name;
		return true;
	}

	/** Chest height, so an arrow-straight aim at the feet does not miss a tall mob. */
	private static Vec3 aimPointOn(Entity e) {
		return e.position().add(0, e.getBbHeight() * 0.6, 0);
	}

	/**
	 * The thing most worth hitting: closest first, but only among things that count as a
	 * threat at all.
	 */
	private Entity pickTarget(Minecraft mc, LocalPlayer player) {
		double radius = Math.max(2, cfg.combatRadius);
		double best = radius * radius;
		Entity found = null;

		for (Entity e : mc.level.entitiesForRendering()) {
			if (e == player || !e.isAlive()) continue;
			if (!(e instanceof LivingEntity living) || living.getHealth() <= 0) continue;
			if (!counts(player, e)) continue;
			double d = e.distanceToSqr(player);
			if (d < best) {
				best = d;
				found = e;
			}
		}
		// once committed, stay committed while it is still in range: swapping target every
		// time something drifts a block closer produces a camera that spins and hits nothing
		if (target != null && target.isAlive() && counts(player, target)
				&& target.distanceToSqr(player) <= best * 2.25) {
			return target;
		}
		return found;
	}

	private boolean counts(LocalPlayer player, Entity e) {
		boolean recentlyHurt = sinceHurt <= cfg.combatMemorySec * 20;
		if (e instanceof Player) {
			if (!cfg.combatFightPlayers) return false;
			return !cfg.combatOnlyWhenAttacked || recentlyHurt;
		}
		if (!(e instanceof Enemy)) return false;
		if (!cfg.combatFightMobs) return false;
		if (cfg.combatOnlyWhenAttacked && !recentlyHurt) {
			// something within arm's reach is attacking whether or not it has landed one yet
			return e.distanceToSqr(player) <= 9;
		}
		return true;
	}

	private static boolean hasShield(LocalPlayer player) {
		return player.getOffhandItem().is(Items.SHIELD);
	}

	/** The hotbar slot that hits hardest, judged by what it does to a zombie's worth of health. */
	private static int bestWeaponSlot(LocalPlayer player) {
		Inventory inv = player.getInventory();
		int best = -1;
		double bestScore = -1;
		for (int slot = 0; slot < Inventory.SELECTION_SIZE; slot++) {
			ItemStack stack = inv.getItem(slot);
			if (stack.isEmpty()) continue;
			double score = weaponScore(stack);
			if (score > bestScore) {
				bestScore = score;
				best = slot;
			}
		}
		return bestScore <= 0 ? -1 : best;
	}

	/**
	 * A rough ordering rather than real damage numbers. Attributes live behind a component
	 * lookup that changes shape between versions, and "sword beats axe beats pickaxe beats
	 * fist" is the whole of the decision being made here.
	 */
	private static double weaponScore(ItemStack stack) {
		return weaponScore(Backpack.itemId(stack));
	}

	static double weaponScore(String id) {
		double material = id.contains("netherite") ? 5 : id.contains("diamond") ? 4
				: id.contains("iron") ? 3 : id.contains("stone") ? 2 : id.contains("golden") ? 1.5 : 1;
		if (id.endsWith("_sword")) return 10 + material;
		if (id.endsWith("_axe")) return 8 + material;
		if (id.contains("trident")) return 9;
		// a mace is a diamond sword that hurts more the further you fell to swing it
		if (id.contains("mace")) return 13.5;
		if (id.endsWith("_pickaxe") || id.endsWith("_shovel")) return 2 + material;
		return 0;
	}

	/**
	 * Self-check on the weapon ordering — the fight needs a world, the ranking does not:
	 * {@code ./gradlew selfCheck -Pcheck=com.damia.movrand.Combat}
	 */
	public static void main(String[] args) {
		assert score("minecraft:diamond_sword") > score("minecraft:diamond_axe")
				: "a sword should outrank an axe of the same material";
		assert score("minecraft:diamond_axe") > score("minecraft:diamond_pickaxe")
				: "an axe should outrank a pickaxe";
		assert score("minecraft:netherite_sword") > score("minecraft:diamond_sword")
				: "netherite should outrank diamond";
		assert score("minecraft:wooden_sword") > score("minecraft:netherite_pickaxe")
				: "any sword should outrank a pickaxe, whatever it is made of";
		assert score("minecraft:mace") > score("minecraft:iron_sword")
				&& score("minecraft:mace") < score("minecraft:netherite_sword")
				: "a mace sits between an iron and a netherite sword";
		assert score("minecraft:bread") == 0 && score("minecraft:cobblestone") == 0
				: "nothing that is not a weapon should score at all";
		assert score("minecraft:golden_sword") < score("minecraft:iron_sword")
				: "gold is soft, and the ranking should say so";

		System.out.println("Combat self-check passed");
	}

	private static double score(String id) {
		return weaponScore(id);
	}
}
