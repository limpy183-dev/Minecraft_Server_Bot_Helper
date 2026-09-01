package com.damia.movrand;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * What killed you, and everything the client knew a moment before it did.
 *
 * <p>Dying erases its own evidence. Health is zero, the fall distance is reset, the fire is
 * out, the mob that did it has wandered off and the inventory is on the ground. An entry
 * written at that moment can only say "you died here", which you already knew — so the
 * numbers are sampled while the player is still alive and outlive them by one tick.
 *
 * <p>The cause itself comes from the server. The death screen is handed the exact component
 * the server sent, and that is the only place a client is told what actually did it: the
 * client's own damage handling knows that a number of hearts went missing, never why.
 */
public final class Deaths {

	// sampled every tick — all cheap reads
	private double x, y, z;
	private float health, maxHealth, absorption;
	private int food, air, maxAir, fireTicks, frozenTicks, armour, xpLevel;
	private double fallDistance;
	private boolean inLava, inWater, inPowderSnow, onFire, onGround;

	/** Sampled on a health drop rather than every tick: these cost a scan or an allocation. */
	private String effects = "";
	private String held = "nothing";
	private String nearest = "";
	private double nearestDistance = -1;
	private float lastHit;
	private int ticksSinceHit = -1;

	/** Set at death, so the last living sample is the one that gets reported. */
	private boolean frozen;
	private BlockPos where;

	/**
	 * False until the first tick of readings arrives. An unsampled record is all zeros, and
	 * zero air with zero food is a perfectly plausible-looking drowning that never happened -
	 * so nothing here is allowed to be reported as fact before something has read it.
	 */
	private boolean sampled;

	/**
	 * @param lost   health lost this tick, which the guards already work out
	 * @param radius how far to look for whatever might have done it
	 */
	public void sample(Minecraft mc, LocalPlayer player, float lost, double radius) {
		if (frozen) return;
		sampled = true;
		x = player.getX();
		y = player.getY();
		z = player.getZ();
		health = player.getHealth();
		maxHealth = player.getMaxHealth();
		absorption = player.getAbsorptionAmount();
		food = player.getFoodData().getFoodLevel();
		air = player.getAirSupply();
		maxAir = player.getMaxAirSupply();
		fireTicks = player.getRemainingFireTicks();
		frozenTicks = player.getTicksFrozen();
		armour = player.getArmorValue();
		xpLevel = player.experienceLevel;
		fallDistance = player.fallDistance;
		inLava = player.isInLava();
		inWater = player.isInWater();
		inPowderSnow = player.isInPowderSnow;
		onFire = player.isOnFire();
		onGround = player.onGround();
		if (ticksSinceHit >= 0) ticksSinceHit++;

		// The expensive half, taken when something is actually happening. A death always
		// follows a hit, so the surroundings at the hit are the ones worth keeping — and
		// scanning every entity in render range twenty times a second for a death that may
		// never come is not a trade worth making.
		if (lost > 0 || health <= maxHealth * 0.5f) {
			if (lost > 0) {
				lastHit = lost;
				ticksSinceHit = 0;
			}
			held = describe(player.getMainHandItem());
			effects = describeEffects(player);
			findNearest(mc, player, radius);
		}
	}

	private void findNearest(Minecraft mc, LocalPlayer player, double radius) {
		nearest = "";
		nearestDistance = -1;
		if (mc.level == null) return;
		double best = radius * radius;
		for (Entity e : mc.level.entitiesForRendering()) {
			if (e == player || !(e instanceof Enemy)) continue;
			double d = e.distanceToSqr(player);
			if (d <= best) {
				best = d;
				nearest = e.getName().getString();
				nearestDistance = Math.sqrt(d);
			}
		}
	}

	private static String describe(ItemStack stack) {
		if (stack == null || stack.isEmpty()) return "nothing";
		String name = stack.getHoverName().getString();
		return stack.getCount() > 1 ? stack.getCount() + "x " + name : name;
	}

	private static String describeEffects(LocalPlayer player) {
		List<String> out = new ArrayList<>();
		for (MobEffectInstance e : player.getActiveEffects()) {
			String id = e.getDescriptionId();
			String name = id.substring(id.lastIndexOf('.') + 1).replace('_', ' ');
			out.add(e.getAmplifier() > 0 ? name + " " + (e.getAmplifier() + 1) : name);
		}
		return String.join(", ", out);
	}

	/** Called the moment death is noticed: everything held now describes the last living tick. */
	public void freeze(LocalPlayer player) {
		frozen = true;
		where = player.blockPosition();
	}

	public void thaw() {
		frozen = false;
		ticksSinceHit = -1;
		lastHit = 0;
		where = null;
	}

	public BlockPos where(LocalPlayer player) {
		return where != null ? where : player.blockPosition();
	}

	/**
	 * The server's own words for it, or empty while the death screen has not arrived — the
	 * health update and the kill message are two packets, and they land in that order, which
	 * is why the caller waits a moment before writing the entry.
	 */
	public static String cause(Minecraft mc, LocalPlayer player) {
		try {
			if (mc.gui != null && mc.gui.screen() instanceof DeathScreen death) {
				java.lang.reflect.Field f = DeathScreen.class.getDeclaredField("causeOfDeath");
				f.setAccessible(true);
				if (f.get(death) instanceof Component c && !c.getString().isBlank()) {
					return c.getString();
				}
			}
		} catch (Exception | LinkageError e) {
			// a renamed field or a locked-down JVM: the inferred cause below still stands
		}
		try {
			Component c = player.getCombatTracker().getDeathMessage();
			if (c != null && !c.getString().isBlank()) return c.getString();
		} catch (Exception | LinkageError e) {
			// nothing recorded client-side, which is the usual case
		}
		return "";
	}

	/** Everything, on one line, ordered so the half worth reading comes first. */
	public String note(Minecraft mc, LocalPlayer player) {
		List<String> parts = new ArrayList<>();

		String said = cause(mc, player);
		String guess = guess();
		parts.add(said.isBlank() ? "Died — " + guess : said);
		// The server's message is authoritative but sometimes vague ("Steve died"); the
		// inference is neither, so it goes in beside it rather than instead of it - unless
		// the two agree, because "looks like zombie" next to "was slain by Zombie" is noise.
		if (!said.isBlank() && !guess.equals("cause unknown")
				&& !said.toLowerCase(Locale.ROOT).contains(guess.toLowerCase(Locale.ROOT))) {
			parts.add("looks like " + guess);
		}
		// Everything past here is a reading, and an unsampled record has none - printing its
		// zeros would report full health at the origin holding nothing, which is a lie
		// dressed as evidence. Say there is nothing to say instead.
		if (!sampled) {
			parts.add("no readings \u2014 nothing was watching when it happened");
			return String.join(" \u00b7 ", parts);
		}

		if (lastHit > 0) {
			parts.add(ticksSinceHit <= 0
					? "%.1f damage on the last tick".formatted(lastHit)
					: "last hit %.1f, %.1fs before".formatted(lastHit, ticksSinceHit / 20.0));
		}
		if (fallDistance >= 1) parts.add("falling %.1f blocks".formatted(fallDistance));
		if (inLava) parts.add("in lava");
		else if (inWater) parts.add("in water");
		if (inPowderSnow) parts.add("in powder snow");
		if (onFire) parts.add("on fire (%.1fs left)".formatted(fireTicks / 20.0));
		if (frozenTicks > 0) parts.add("freezing (%d ticks)".formatted(frozenTicks));
		if (air < maxAir) parts.add("air %d/%d".formatted(air, maxAir));
		if (!nearest.isEmpty()) parts.add("nearest %s %.1f blocks away".formatted(nearest, nearestDistance));

		parts.add("health %.1f/%.1f%s".formatted(health, maxHealth,
				absorption > 0 ? " +%.1f absorbed".formatted(absorption) : ""));
		parts.add("food " + food);
		parts.add("armour " + armour);
		parts.add("holding " + held);
		if (!effects.isEmpty()) parts.add("effects " + effects);
		parts.add("xp level " + xpLevel);
		parts.add(onGround ? "on the ground" : "in the air");
		parts.add("at %.1f, %.1f, %.1f".formatted(x, y, z));
		parts.add("items dropped here");
		return String.join(" · ", parts);
	}

	/**
	 * What the numbers say, for when the server's message is missing or is the vague kind.
	 * Ordered by how sure each one is: being in lava explains a death on its own, being near
	 * a zombie does not.
	 */
	public String guess() {
		if (!sampled) return "cause unknown";
		if (inLava) return "lava";
		if (fallDistance >= 3.5) return "a %.0f block fall".formatted(fallDistance);
		// A mob that just landed a hit outranks the slow causes it happened to be standing
		// in: air and hunger take half a heart at a time, and a zombie does not.
		if (struck()) return nearest.toLowerCase(Locale.ROOT);
		// and drowning needs water. Air reads zero on an unsampled record too, which is how
		// every death with the bot switched off used to come out as one.
		if (inWater && air <= 0) return "drowning";
		if (frozenTicks >= 140) return "freezing";
		if (onFire) return "burning";
		if (inPowderSnow) return "powder snow";
		if (food <= 0) return "starvation";
		if (!nearest.isEmpty() && nearestDistance <= 5) return nearest.toLowerCase(Locale.ROOT);
		if (lastHit > 0) return "%.1f damage from something out of reach".formatted(lastHit);
		return "cause unknown";
	}

	/** A hit landed within the last second, with something hostile close enough to have landed it. */
	private boolean struck() {
		return lastHit > 0 && ticksSinceHit >= 0 && ticksSinceHit <= 20
				&& !nearest.isEmpty() && nearestDistance <= 5;
	}

	/** The short version, for the alert and the status line. */
	public String headline(Minecraft mc, LocalPlayer player) {
		String said = cause(mc, player);
		return said.isBlank() ? "Died — " + guess() : said;
	}

	/**
	 * Self-check on the inference ladder — the sampling needs a player, the reasoning does not:
	 * {@code ./gradlew selfCheck -Pcheck=com.damia.movrand.Deaths}
	 */
	public static void main(String[] args) {
		Deaths d = fresh();
		assert d.guess().equals("cause unknown") : "a healthy player has no cause: " + d.guess();

		// each rung on its own
		d = fresh();
		d.lastHit = 4;
		assert d.guess().startsWith("4.0 damage") : d.guess();
		d = fresh();
		d.nearest = "Zombie";
		d.nearestDistance = 2;
		assert d.guess().equals("zombie") : d.guess();
		d = fresh();
		d.nearest = "Zombie";
		d.nearestDistance = 40;
		assert !d.guess().equals("zombie") : "a mob forty blocks away did not do it";
		d = fresh();
		d.food = 0;
		assert d.guess().equals("starvation") : d.guess();
		d = fresh();
		d.inPowderSnow = true;
		assert d.guess().equals("powder snow") : d.guess();
		d = fresh();
		d.onFire = true;
		assert d.guess().equals("burning") : d.guess();
		d = fresh();
		d.frozenTicks = 140;
		assert d.guess().equals("freezing") : d.guess();
		d = fresh();
		d.inWater = true;
		d.air = 0;
		assert d.guess().equals("drowning") : d.guess();
		d = fresh();
		d.fallDistance = 20;
		assert d.guess().equals("a 20 block fall") : d.guess();
		d = fresh();
		d.inLava = true;
		assert d.guess().equals("lava") : d.guess();

		// and the order between them, which is the part worth having a test for: standing in
		// lava while a zombie watches is a lava death, not a zombie one
		d = fresh();
		d.inLava = true;
		d.onFire = true;
		d.fallDistance = 20;
		d.nearest = "Zombie";
		d.nearestDistance = 1;
		d.food = 0;
		assert d.guess().equals("lava") : "lava should outrank everything, got " + d.guess();
		d.inLava = false;
		assert d.guess().equals("a 20 block fall") : "a fall should outrank fire, got " + d.guess();
		d.fallDistance = 0;
		assert d.guess().equals("burning") : "fire should outrank starving, got " + d.guess();

		// the bug this ladder was rebuilt for: a record nothing ever sampled is all zeros,
		// which used to read as no air and no food and came out as a confident drowning
		assert new Deaths().guess().equals("cause unknown")
				: "an unsampled record invented a cause: " + new Deaths().guess();
		d = fresh();
		d.air = 0; // dry land, no readings from a water block anywhere
		assert !d.guess().equals("drowning") : "drowned on dry land: " + d.guess();

		// a zombie that just hit you beats the slow cause it was standing in
		d = fresh();
		d.inWater = true;
		d.air = 0;
		d.nearest = "Zombie";
		d.nearestDistance = 2;
		d.lastHit = 5;
		d.ticksSinceHit = 3;
		assert d.guess().equals("zombie") : "a fresh hit should outrank drowning, got " + d.guess();
		// but one that has not touched you does not explain it
		d.lastHit = 0;
		d.ticksSinceHit = -1;
		assert d.guess().equals("drowning") : "a bystander was blamed: " + d.guess();
		// nor does one that hit you a while ago
		d.lastHit = 5;
		d.ticksSinceHit = 60;
		assert d.guess().equals("drowning") : "a three-second-old hit was blamed: " + d.guess();

		// a short drop is not a fall death
		d = fresh();
		d.fallDistance = 2;
		assert d.guess().equals("cause unknown") : "two blocks killed nobody: " + d.guess();

		System.out.println("Deaths self-check passed");
	}

	/** A player who is alive and well, so each test starts from nothing being wrong. */
	private static Deaths fresh() {
		Deaths d = new Deaths();
		d.sampled = true;
		d.maxAir = 300;
		d.air = 300;
		d.food = 20;
		d.health = 20;
		d.maxHealth = 20;
		return d;
	}
}
