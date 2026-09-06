package com.damia.movrand;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Creeper;
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
 * <p>Attacks are discrete swings rather than a held button, because that is what vanilla sends
 * for an entity, and they wait for the cooldown, because a swing at forty percent charge does
 * forty percent damage and no person swings that way on purpose. The spacing on top of the
 * cooldown is randomised: the cooldown alone would produce a perfectly periodic swing, which
 * is a signature.
 */
public final class Combat {

	private final Config cfg;

	private Entity target;
	private int swingCooldown;
	/** Ticks since something last hurt us — the difference between a threat and scenery. */
	private int sinceHurt = COLD;
	/** Entity vanilla reports as the most recent attacker, not merely the nearest bystander. */
	private int attackerId = -1;
	public String status = "idle";

	/** Longer ago than any memory setting reaches, so a fresh fight starts from "not hurt". */
	private static final int COLD = 9999;

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
	public void onDamage(LocalPlayer player, float lost) {
		if (lost <= 0) return;
		sinceHurt = 0;
		LivingEntity attacker = player.getLastHurtByMob();
		if (attacker != null && attacker.isAlive()) attackerId = attacker.getId();
	}

	/**
	 * @return true when a fight is happening and the task above should hold off
	 */
	public boolean tick(Minecraft mc, LocalPlayer player, Bot.Steer steer) {
		if (sinceHurt < COLD) sinceHurt++;
		if (sinceHurt > cfg.combatMemorySec * 20) attackerId = -1;
		if (swingCooldown > 0) swingCooldown--;

		if (!cfg.combatEnabled || mc.level == null) {
			target = null;
			status = cfg.combatEnabled ? "no world" : "off";
			return false;
		}

		target = pickTarget(mc, player);
		if (target == null) {
			status = "clear";
			return false;
		}

		double distance = Math.sqrt(target.distanceToSqr(player));
		String name = target.getName().getString();

		double[] look = Bot.aimAt(player, aimPointOn(target));
		Vec3 away = player.position().subtract(target.position());
		double awayYaw = away.x * away.x + away.z * away.z > 1e-6
				? Math.toDegrees(Math.atan2(-away.x, away.z)) : player.getYRot();

		// Losing badly is not a fight, it is a death. Back off and let the guards decide -
		// facing it the whole way, because turning your back on something is how you stop seeing
		// whether it is still following, and because a shield only works forwards.
		if (cfg.combatRetreat && player.getHealth() <= cfg.combatRetreatHealth) {
			steer.lookAt(look[0], 0);
			steer.moveTowards(awayYaw);
			steer.sprint = true;
			if (cfg.combatUseShield && hasShield(player)) steer.use = true;
			status = "backing away from " + name;
			return true;
		}

		// Never precise. That flag re-casts the crosshair from this tick's rotation, which is
		// exactly right for a block and exactly wrong here: it would hand this code a block
		// where it is expecting the mob it is swinging at. The aim still goes out through the
		// same filter and the same wobble, and the swing names the entity rather than trusting
		// the crosshair, which is what vanilla itself sends.
		steer.lookAt(look[0], look[1]);

		// a shield is worth raising while closing the distance, not while swinging
		if (cfg.combatUseShield && hasShield(player) && distance > SWINGING_RANGE) steer.use = true;

		// A creeper is not a thing to stand next to between swings. Hit it, back out past the
		// blast while the cooldown runs, come back in — which is also how a person fights one.
		if (target instanceof Creeper && distance < CREEPER_BLAST && swingCooldown > 0) {
			steer.moveTowards(awayYaw);
			status = "backing off %s".formatted(name);
			return true;
		}

		if (distance > player.entityInteractionRange()) {
			if (!cfg.combatChase) {
				// Not going to walk to it, so there is nothing to do about it. Standing in the
				// open staring at a skeleton until it wanders off is not a fight, it is a stall —
				// and every tick spent here is a tick the job does not get.
				steer.clear();
				status = "%s is %.1f blocks off".formatted(name, distance);
				return false;
			}
			steer.moveTowards(look[0]);
			steer.sprint = true;
			status = "closing on %s (%.1f blocks)".formatted(name, distance);
			return true;
		}

		int weapon = bestWeaponSlot(player);
		if (weapon >= 0 && weapon != player.getInventory().getSelectedSlot()) {
			player.getInventory().setSelectedSlot(weapon);
		}

		boolean charged = player.getAttackStrengthScale(0) >= cfg.combatMinCharge;
		if (charged && swingCooldown <= 0 && mc.gameMode != null) {
			// One swing, named at the entity, exactly as vanilla's own click does. Holding the
			// attack key at a mob is not what a client sends and not what a person does.
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
	 * The thing most worth hitting: closest first, but only among things that count as a threat
	 * at all.
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
		// Once committed, stay committed while it is still roughly the nearest thing. Swapping
		// target every time something drifts a block closer produces a camera that spins and a
		// bot that hits nothing.
		if (target != null && target.isAlive() && counts(player, target)
				&& target.distanceToSqr(player) <= best * STICKY) {
			return target;
		}
		return found;
	}

	private boolean counts(LocalPlayer player, Entity e) {
		// Swinging at something through a wall is not a fight. The server refuses the hit, the
		// swing lands on nothing, and because a fight outranks the job the bot stands in the
		// corridor doing that until the mob wanders off. Whatever cannot be seen cannot be
		// fought, and the retreat and the damage guard are what answer it instead.
		if (!player.hasLineOfSight(e)) return false;

		boolean recentlyHurtByThis = sinceHurt <= cfg.combatMemorySec * 20 && e.getId() == attackerId;
		if (e instanceof Player) {
			if (!cfg.combatFightPlayers) return false;
			return !cfg.combatOnlyWhenAttacked || recentlyHurtByThis;
		}
		if (!(e instanceof Enemy)) return false;
		if (!cfg.combatFightMobs) return false;
		if (cfg.combatOnlyWhenAttacked && !recentlyHurtByThis) {
			// something within arm's reach is attacking whether or not it has landed one yet
			return e.distanceToSqr(player) <= ON_TOP_OF_US * ON_TOP_OF_US;
		}
		return true;
	}

	private static boolean hasShield(LocalPlayer player) {
		return player.getOffhandItem().is(Items.SHIELD);
	}

	/** Blast radius plus a step. Inside this, a creeper going off takes most of a health bar. */
	static final double CREEPER_BLAST = 4.0;
	/**
	 * Close enough to be swinging rather than closing, so the shield comes down. Vanilla reach
	 * is three blocks; half a block past it is the moment the swing becomes the point.
	 */
	static final double SWINGING_RANGE = 2.5;
	/** Arm's length. Something this close is attacking whether or not it has landed one yet. */
	static final double ON_TOP_OF_US = 3.0;
	/**
	 * How much further than the nearest threat the one already being fought may be before it is
	 * dropped for it, as a multiple of squared distance. 2.25 is half again as far.
	 */
	static final double STICKY = 2.25;

	/**
	 * The best weapon on the hotbar, scored. Zero means there is nothing here to fight with,
	 * which is one of the few honest reasons to walk away from a fight rather than have it.
	 */
	public static double bestWeaponScore(LocalPlayer player) {
		Inventory inv = player.getInventory();
		double best = 0;
		for (int slot = 0; slot < Inventory.SELECTION_SIZE; slot++) {
			best = Math.max(best, weaponScore(inv.getItem(slot)));
		}
		return best;
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
	 * Self-check on the weapon ordering and the swing spacing — the fight needs a world, neither
	 * of those does: {@code ./gradlew selfCheck -Pcheck=com.damia.movrand.Combat}
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

		// A swing is a discrete click with a randomised gap after it. Zero would be an
		// autoclicker, and a fixed number would be a metronome — the two shapes anybody
		// histogramming click intervals is looking for.
		Config cfg = new Config();
		cfg.combatSwingMinSec = 0;
		cfg.combatSwingMaxSec = 0;
		cfg.clampAll();
		assert cfg.combatSwingMinSec > 0 : "a zero swing gap is an autoclicker";
		assert cfg.combatSwingMaxSec >= cfg.combatSwingMinSec : "the swing range is inverted";

		cfg.combatSwingMinSec = 0.55;
		cfg.combatSwingMaxSec = 0.85;
		cfg.clampAll();
		int low = Integer.MAX_VALUE, high = 0;
		for (int i = 0; i < 20_000; i++) {
			int gap = Math.max(1, Rng.ticks(cfg.combatSwingMinSec, cfg.combatSwingMaxSec));
			assert gap >= 1 : "a swing landed on the same tick as the last one";
			low = Math.min(low, gap);
			high = Math.max(high, gap);
		}
		assert high > low : "every swing gap came out the same, which is a metronome";
		assert low >= 11 && high <= 17
				: "swing gaps left the 0.55-0.85s range: %d to %d ticks".formatted(low, high);

		// A partial charge does partial damage, so the default has to be most of one.
		cfg.combatMinCharge = 0;
		cfg.clampAll();
		assert cfg.combatMinCharge > 0 : "swinging at no charge at all does nothing and looks it";

		// The standoff has to be outside the blast, or backing off from a creeper is standing
		// next to a creeper with extra steps.
		assert CREEPER_BLAST > 3 : "a creeper's blast reaches three blocks; the standoff is inside it";
		assert SWINGING_RANGE < CREEPER_BLAST : "the shield would come down inside the blast";
		assert ON_TOP_OF_US <= CREEPER_BLAST : "something at arm's length is inside the blast";
		assert STICKY > 1 : "the target would be dropped for anything a hair nearer";

		System.out.println("Combat self-check passed");
	}

	private static double score(String id) {
		return weaponScore(id);
	}
}
