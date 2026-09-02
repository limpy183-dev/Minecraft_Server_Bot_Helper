package com.damia.movrand;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.EnderMan;

import java.util.Locale;

/**
 * The brain. Ticked once per client tick, before the player's own tick, so the key
 * states it writes are the ones vanilla movement reads.
 */
public final class MovementController {

	public enum State {
		IDLE("Idle"), RUNNING("Running"), STRAFING("Strafing"), PAUSED("Pausing"),
		TURNING("Turning"), LOOKING("Looking around"), UNSTICKING("Unsticking"),
		EATING("Eating"), BLOCKED("Held"), WORKING("Working"), FLEEING("Leaving");

		public final String label;

		State(String label) {
			this.label = label;
		}
	}

	private final Config cfg;
	public final Journal journal;
	public final AreaCoverage area;
	public final SafeStop safeStop;
	public final AutoEat autoEat;
	/** The job. Null steer means it has nothing to say and the wandering takes over. */
	public final BaseDestroyer destroyer;
	/** The camera's hand: the wobble and the easing that keep rotation off a fixed curve. */
	private final Human human = new Human();
	/** Looks a few blocks ahead and steers round whatever is there. */
	public final Avoidance avoid = new Avoidance();

	// ------------------------------------------------------------- runtime

	public State state = State.IDLE;
	public String lastReason = "";
	public String lastAlertReason = "";
	private long tickCount;
	private long startedAtTick = -1;
	private boolean keysHeld;
	private boolean eating;

	// scheduler
	private int segmentTicksLeft;
	private int eventTicksLeft;
	private int strafeDir;

	// aiming
	private double baseYaw;
	private double wanderOffset;
	/** The heading being walked, dodge included, as opposed to where the camera has got to. */
	private double walkYaw;
	/** Whether forward was actually asked for, so the input-blocked check knows what to expect. */
	private boolean askedForward;
	private double targetPitch;
	/** The pitch before the wobble is added, so the wobble never feeds back into itself. */
	// a turn is spent along an eased curve rather than at a constant rate
	private double turnTotalDeg;
	private int turnTicksTotal;
	private int turnTick;
	private double turnDone;

	// jumping
	private int jumpTicks;
	private int jumpCooldown;

	// stuck detection
	private double progressX, progressZ;
	private int ticksSinceProgress;
	private int inputBlockedTicks;
	private int unstickTried;

	// guards
	private float lastHealth = -1;
	// NOT Long.MIN_VALUE: `tickCount - lastAlertTick` would overflow to a negative
	// number, which is always below the cooldown, so no alert would ever play.
	private long lastAlertTick = -1_000_000;
	private volatile String pendingChatTrigger;
	private boolean wasDead;
	private boolean loggedOutOfFood;

	// threats — one scan a tick, read by the hostile guard, the camera and the retreat
	private Entity nearestHostile;
	private double nearestHostileDistance = -1;
	/** How many hostiles are inside the retreat radius. Counted in the one sweep, not a second. */
	private int hostilesNear;
	private boolean endermanNear;
	/** The mob the retreat is watching, so the closure below compares like with like. */
	private int threatId = -1;
	/** The furthest that mob has been since it was picked up. Ground it has made up since. */
	private double threatMaxDistance = -1;
	private int fleeTicks;
	private double fleeYaw;
	// a stop that has been decided on but not acted on yet
	private String pendingStopReason;
	private int pendingStopTicks;

	// navigation — area coverage, then the manual go-to, then free wandering
	private boolean navActive;
	private double navX, navZ;
	private String navLabel = "";
	public double gotoDistance = -1;
	private final NavProgress navProgress = new NavProgress();

	// container scan
	public ContainerScanner.Result lastScan = ContainerScanner.Result.EMPTY;
	private final Deaths deaths = new Deaths();
	/** Ticks left to wait for the server to say what killed you. -1 when not dying. */
	private int deathLogIn = -1;
	private int scanCooldown;
	/** What the guards worked out this tick, so the destroyer does not compute it twice. */
	private float lastDamage;
	/** The block the destroyer is on, kept so its disappearance can be counted. */
	private BlockPos watchedBlock;
	private String watchedName = "";

	public MovementController(Config cfg) {
		this.cfg = cfg;
		this.journal = new Journal(cfg);
		this.area = new AreaCoverage(cfg);
		this.safeStop = new SafeStop(cfg);
		this.autoEat = new AutoEat(cfg);
		this.destroyer = new BaseDestroyer(cfg, journal);
	}

	// -------------------------------------------------------- public control

	public void start(Minecraft mc) {
		if (cfg.reseedOnStart) Rng.reseed();
		cfg.movementEnabled = true;
		startedAtTick = tickCount;
		unstickTried = 0;
		ticksSinceProgress = 0;
		inputBlockedTicks = 0;
		navProgress.reset();
		turnTicksTotal = 0;
		wanderOffset = 0;
		pendingStopReason = null;
		pendingStopTicks = 0;
		loggedOutOfFood = false;
		fleeTicks = 0;
		threatId = -1;
		threatMaxDistance = -1;
		endermanNear = false;
		lastHealth = mc.player != null ? mc.player.getHealth() : -1;
		if (mc.player != null) {
			baseYaw = mc.player.getYRot();
			human.syncCamera(mc.player.getYRot(), mc.player.getXRot());
			progressX = mc.player.getX();
			progressZ = mc.player.getZ();
		}
		safeStop.reset(mc.player);
		avoid.reset();
		destroyer.reset(mc.player);
		newSegment();
		state = State.RUNNING;
		lastReason = "Started";
		if (mc.player != null) {
			journal.log(Journal.Kind.SESSION, mc.level, mc.player.blockPosition(), "Movement started");
		}
		say(mc, "§aMovement on");
	}

	public void stop(Minecraft mc, String reason) {
		cfg.movementEnabled = false;
		pendingStopReason = null;
		pendingStopTicks = 0;
		state = State.IDLE;
		lastReason = reason;
		destroyer.stop();
		release(mc);
		if (mc.player != null) {
			journal.log(Journal.Kind.SESSION, mc.level, mc.player.blockPosition(), "Stopped: " + reason);
		}
		area.save();
		journal.save();
		if (reason != null && !reason.isBlank()) say(mc, "§cStopped: §7" + reason);
	}

	public void toggle(Minecraft mc) {
		if (cfg.movementEnabled) stop(mc, "Toggled off");
		else start(mc);
	}

	public double runtimeSeconds() {
		return startedAtTick < 0 ? 0 : (tickCount - startedAtTick) / 20.0;
	}

	public double nextEventSeconds() {
		return (eventTicksLeft > 0 ? eventTicksLeft : segmentTicksLeft) / 20.0;
	}

	public void onChatMessage(String text) {
		pendingChatTrigger = text;
	}

	public String navigationLabel() {
		return navActive ? navLabel : "free";
	}

	// ------------------------------------------------------------- main tick

	public void tick(Minecraft mc) {
		tickCount++;
		if (jumpCooldown > 0) jumpCooldown--;
		if (jumpTicks > 0) jumpTicks--;

		LocalPlayer player = mc.player;
		ClientLevel level = mc.level;

		// Chat and death matter whether or not we are currently walking — an alert about
		// someone saying your name is useful precisely when the bot has already stopped.
		if (player != null) {
			// Sampled here rather than with the guards: the guards only run while the bot is
			// walking, and dying with it switched off used to leave the record all zeros —
			// which reads as no air and no food, so every idle death was logged as a drowning.
			float health = player.getHealth();
			lastDamage = lastHealth < 0 ? 0 : lastHealth - health;
			lastHealth = health;
			deaths.sample(mc, player, lastDamage, Math.max(16, cfg.hostileMobRadius));
			handleChatTrigger(mc, player, level);
			handleDeath(mc, player, level);
		}

		if (!cfg.movementEnabled) {
			if (keysHeld) release(mc);
			state = State.IDLE;
			eating = false;
			return;
		}

		// A decision was made a few ticks ago; this is the hand finally reaching the key.
		// Everything below carries on as normal until then, which is the point.
		if (pendingStopTicks > 0 && --pendingStopTicks <= 0 && pendingStopReason != null) {
			String why = pendingStopReason;
			pendingStopReason = null;
			stop(mc, why);
			return;
		}

		if (player == null || level == null) {
			if (cfg.stopOnDisconnect) stop(mc, "Left the world");
			else release(mc);
			return;
		}

		// Our own menus never count. The config screen, because the whole point is watching
		// it work while it runs - and the sell menu, because the mod opened it on purpose and
		// pausing on it would leave the sale half finished with the bag still full.
		boolean ourScreen = mc.gui.screen() instanceof com.damia.movrand.gui.ConfigScreen
				|| destroyer.backpack.busy();
		boolean foreignScreen = mc.gui.screen() != null && !ourScreen;
		if (cfg.pauseWhileScreenOpen && foreignScreen) {
			release(mc);
			state = State.BLOCKED;
			resetProgress(player);
			safeStop.grace(20);
			return;
		}
		if (cfg.stopWhenUnfocused && !mc.isWindowActive()) {
			release(mc);
			state = State.BLOCKED;
			resetProgress(player);
			safeStop.grace(20);
			return;
		}

		scanThreats(level, player);
		if (runGuards(mc, player, level)) return;

		// The job, when there is one. It answers with an intent rather than with keys and a
		// rotation, so everything below this - the camera filter, the wobble, the key writer,
		// the handbrake - is the same code a wandering bot runs.
		// Getting clear outranks the job: a retreat that stops to mine is not a retreat.
		Bot.Steer steer = fleeing() ? null : destroyer.tick(mc, player, level, lastDamage);
		// Outside the branch: the job hands the tick back when it has run out of blocks, and
		// that is exactly the moment the count and the reaction have to happen.
		if (cfg.destroyerEnabled) {
			countMined(level);
			if (destroyer.takeFinished() && destroyerFinished(mc, player, level)) return;
		}
		if (steer != null) {
			eating = autoEat.tick(mc, player);
			if (eating) state = State.EATING;
			else state = State.WORKING;
			lastReason = destroyer.describe();
			applySteer(mc, player, steer);
			runSafeStop(mc, player, level);
			if (!cfg.movementEnabled) return;
			// standing still to mine, sell or fight is not being stuck
			if (destroyer.phase == BaseDestroyer.Phase.WALKING
					|| destroyer.phase == BaseDestroyer.Phase.COLLECTING) {
				updateStuck(mc, player);
			} else {
				resetProgress(player);
			}
			return;
		}

		updateSchedule();
		updateNavigation(mc, player, level);
		if (!cfg.movementEnabled) return;
		updateHeading(mc, player);

		eating = autoEat.tick(mc, player);
		if (eating) state = State.EATING;

		handleObstacles(mc, player, level);
		applyKeys(mc, player);
		runSafeStop(mc, player, level);
		if (!cfg.movementEnabled) return;
		updateStuck(mc, player);
	}

	// ------------------------------------------------------------- scheduler

	private void newSegment() {
		segmentTicksLeft = Rng.ticks(cfg.segmentMinSec, cfg.segmentMaxSec, cfg.segmentDistribution);
	}

	private void updateSchedule() {
		// Nothing random while running away — a scheduled pause in front of a zombie is
		// the one bit of humanising nobody needs.
		if (fleeing()) {
			state = State.FLEEING;
			turnTicksTotal = 0;
			return;
		}
		if (eventTicksLeft > 0) {
			eventTicksLeft--;
			if (eventTicksLeft == 0) {
				state = State.RUNNING;
				targetPitch = 0;
				newSegment();
			}
			return;
		}
		if (segmentTicksLeft > 0) {
			segmentTicksLeft--;
			state = State.RUNNING;
			return;
		}
		fireEvent();
	}

	/** Picks one random event, weighted by whatever the user left switched on. */
	private void fireEvent() {
		double total = cfg.totalEventWeight();
		if (total <= 0) {
			state = State.RUNNING;
			newSegment();
			return;
		}
		double roll = Rng.nextDouble() * total;

		if (cfg.strafeEnabled && (roll -= cfg.strafeWeight) < 0) {
			strafeDir = Rng.coinFlip() ? -1 : 1;
			eventTicksLeft = Rng.ticks(cfg.strafeMinSec, cfg.strafeMaxSec);
			state = State.STRAFING;
			return;
		}
		if (cfg.pauseEnabled && (roll -= cfg.pauseWeight) < 0) {
			eventTicksLeft = Rng.ticks(cfg.pauseMinSec, cfg.pauseMaxSec);
			state = State.PAUSED;
			return;
		}
		if (cfg.turnEnabled && (roll -= cfg.turnWeight) < 0) {
			beginTurn(Rng.range(cfg.turnMinDeg, cfg.turnMaxDeg) * (Rng.coinFlip() ? -1 : 1));
			eventTicksLeft = turnTicksTotal;
			state = State.TURNING;
			return;
		}
		if (cfg.hopEnabled && (roll -= cfg.hopWeight) < 0) {
			jumpTicks = 3;
			eventTicksLeft = 6;
			state = State.RUNNING;
			return;
		}
		if (cfg.lookAroundEnabled) {
			targetPitch = Rng.range(cfg.lookPitchMinDeg, cfg.lookPitchMaxDeg);
			eventTicksLeft = Rng.ticks(0.6, 2.0);
			state = State.LOOKING;
			return;
		}
		state = State.RUNNING;
		newSegment();
	}

	/**
	 * Starts a turn of {@code deg} degrees. The rate is not constant: a constant rate is a
	 * rectangular velocity pulse, instantly at full speed and instantly back to zero, which
	 * no hand can produce. {@link Human#ease} spends it along a curve instead, and
	 * {@link Human#turnTicks} lengthens it so the peak still respects the configured limit.
	 */
	private void beginTurn(double deg) {
		turnTotalDeg = deg;
		turnTicksTotal = Human.turnTicks(deg, cfg.turnSpeedDegPerTick);
		turnTick = 0;
		turnDone = 0;
	}

	private boolean turning() {
		return turnTicksTotal > 0 && turnTick < turnTicksTotal;
	}

	// ----------------------------------------------------------- navigation

	private void updateNavigation(Minecraft mc, LocalPlayer player, ClientLevel level) {
		navActive = false;
		if (fleeing()) return;

		if (cfg.areaEnabled) {
			int cx = player.blockPosition().getX() >> 4;
			int cz = player.blockPosition().getZ() >> 4;
			// walking through a chunk counts; so does the ring the container scan already read.
			// The scan's own radius, not the manual slider: with the automatic radius on — which
			// is the default — the slider is not what it read, and the scout route spaces its
			// stops by exactly this number.
			int covered = cfg.areaUseScanRadius && cfg.containerScanEnabled
					? ContainerScanner.effectiveRadius(cfg) : 0;
			area.markCovered(cx, cz, covered);

			if (area.isComplete()) {
				areaFinished(mc, player, level);
				return;
			}
			AreaCoverage.Target t = area.nextTarget(cx, cz, covered);
			if (t != null) {
				navActive = true;
				navX = t.x();
				navZ = t.z();
				navLabel = "chunk %d, %d".formatted(t.chunkX(), t.chunkZ());
			}
			return;
		}

		if (cfg.gotoEnabled) {
			navActive = true;
			navX = cfg.gotoX;
			navZ = cfg.gotoZ;
			navLabel = "%.0f, %.0f".formatted(cfg.gotoX, cfg.gotoZ);
		}
	}

	private void areaFinished(Minecraft mc, LocalPlayer player, ClientLevel level) {
		String reason = "Area covered — %d chunks".formatted(area.totalChunks());
		journal.log(Journal.Kind.AREA_DONE, level, player.blockPosition(), reason);
		area.save();
		cfg.areaEnabled = false;
		cfg.save();
		react(mc, cfg.areaDoneReaction, reason);
		if (cfg.areaStopWhenDone && cfg.movementEnabled) stop(mc, reason);
	}

	// --------------------------------------------------------------- aiming

	private void updateHeading(Minecraft mc, LocalPlayer player) {
		if (fleeing()) {
			baseYaw = fleeYaw;
			wanderOffset = 0;
		}
		if (navActive) {
			double dx = navX - player.getX();
			double dz = navZ - player.getZ();
			gotoDistance = Math.sqrt(dx * dx + dz * dz);

			if (gotoDistance <= cfg.gotoArriveRadius) {
				if (cfg.areaEnabled) {
					// close enough: tick the chunk off and let the next target be chosen
					area.markCovered((int) Math.floor(navX) >> 4, (int) Math.floor(navZ) >> 4, 0);
				} else {
					arrive(mc, player);
					return;
				}
			}
			if (navProgress.update(navX, navZ, gotoDistance, cfg.gotoNoProgressSec)) {
				react(mc, Config.Reaction.ALERT, "Not getting any closer to " + navLabel);
			}
			// yaw 0 faces +Z, 90 faces -X
			baseYaw = Math.toDegrees(Math.atan2(-dx, dz));
		} else {
			gotoDistance = -1;
		}

		// spend the turn budget a few degrees per tick so it reads as a hand movement
		double maxWander = navActive ? cfg.gotoMaxWanderDeg : Double.MAX_VALUE;
		if (navActive && outsideArea(player)) maxWander = Math.min(maxWander, 8); // head straight back in

		if (turning()) {
			turnTick++;
			double want = turnTotalDeg * Human.ease(turnTick / (double) turnTicksTotal);
			double step = want - turnDone;
			turnDone = want;
			if (navActive) wanderOffset = Mth.clamp((float) (wanderOffset + step), (float) -maxWander, (float) maxWander);
			else baseYaw += step;
			if (turnTick >= turnTicksTotal) turnTicksTotal = 0;
		}

		// while navigating, always drift back onto the bearing
		if (navActive && wanderOffset != 0 && !turning()) {
			double pull = Math.min(Math.abs(wanderOffset), Math.max(0.05, cfg.gotoCorrectionDegPerTick));
			wanderOffset -= Math.copySign(pull, wanderOffset);
		}

		// Steering is deliberately not folded into baseYaw or the wander: those are where the
		// bot means to go, and a dodge is only where it is pointed for the moment. Keeping
		// them apart is what makes "carry on afterwards" free - the correction decays to zero
		// and the original heading is still sitting there underneath it.
		double intended = baseYaw + wanderOffset;
		double dodge = mc.level == null ? 0
				: avoid.update(cfg, intended, Avoidance.worldProbe(mc.level, player, cfg));
		walkYaw = intended + dodge;

		// The camera is filtered towards the heading rather than snapped onto it, so a turn
		// arriving in eased steps, a dodge appearing the instant a wall does, and the leash
		// correction snapping on at a boundary all come out as one continuous movement.
		float finalYaw = (float) Mth.wrapDegrees(human.yawFor(cfg, intended + dodge));
		player.setYRot(finalYaw);
		safeStop.expectYaw(finalYaw);

		if (!eating) {
			player.setXRot((float) human.pitchFor(cfg, aimPitch(targetPitch)));
		} else {
			human.syncCamera(finalYaw, player.getXRot());
		}
	}

	private boolean outsideArea(LocalPlayer player) {
		if (!cfg.areaEnabled) return false;
		double leash = cfg.areaLeashBlocks;
		return player.getX() < Math.min(cfg.areaX1, cfg.areaX2) - leash
				|| player.getX() > Math.max(cfg.areaX1, cfg.areaX2) + leash
				|| player.getZ() < Math.min(cfg.areaZ1, cfg.areaZ2) - leash
				|| player.getZ() > Math.max(cfg.areaZ1, cfg.areaZ2) + leash;
	}

	private void arrive(Minecraft mc, LocalPlayer player) {
		String reason = "Arrived at %.0f, %.0f".formatted(cfg.gotoX, cfg.gotoZ);
		journal.log(Journal.Kind.ARRIVED, player.level() instanceof ClientLevel cl ? cl : null,
				player.blockPosition(), reason);
		cfg.gotoEnabled = false;
		cfg.save();
		react(mc, cfg.gotoArriveReaction, reason);
		if (cfg.gotoStopOnArrive && cfg.movementEnabled) stop(mc, reason);
	}

	// ------------------------------------------------------------ the job

	/**
	 * One tick of intent, turned into a camera and a set of held keys.
	 *
	 * <p>The heading is remembered in {@code baseYaw} on the way through, so the moment the
	 * job finishes the wandering picks up facing the way the work left it rather than
	 * snapping back to whatever it was pointing at when the job started.
	 */
	private void applySteer(Minecraft mc, LocalPlayer player, Bot.Steer steer) {
		boolean precise = destroyer.phase == BaseDestroyer.Phase.MINING
				|| destroyer.phase == BaseDestroyer.Phase.CLEARING
				|| destroyer.phase == BaseDestroyer.Phase.BRIDGING
				|| destroyer.phase == BaseDestroyer.Phase.COVERING;

		boolean sprint = steer.sprint;
		boolean stopped = false;
		// The local steering does not run on top of a plan, and putting it there was a
		// mistake. It answers "is there a wall within three blocks" — which inside a building
		// is always yes — so it spent every corridor deflecting the bot off a route that had
		// already been searched round the wall it was objecting to, and pulling the camera
		// off whatever the job was looking at. A route is the obstacle avoidance. What is
		// still worth asking every tick is whether the ground has changed under the plan,
		// because this is a job that changes it.
		avoid.reset();
		if (!precise && steer.hasMove && mc.level != null
				&& Avoidance.stepIsDeadly(mc.level, player, steer.moveYaw, cfg, cfg.pathMaxFall)) {
			stopped = true;
			destroyer.blocked("something that hurts, straight ahead");
		}

		if (steer.hasLook) {
			// Whatever the job asked to look at, it gets. Overwriting this with the walking
			// heading is how the bot ended up facing away from the item it was walking to.
			double smoothYaw = precise ? cfg.taskAimSmoothing : cfg.cameraSmoothYaw;
			double smoothPitch = precise ? cfg.taskAimSmoothing : cfg.cameraSmoothPitch;
			double wobble = precise ? cfg.taskAimWobbleScale : 1;

			float finalYaw = (float) Mth.wrapDegrees(human.yawFor(cfg, steer.yaw, smoothYaw, wobble));
			player.setYRot(finalYaw);
			safeStop.expectYaw(finalYaw);
			// A precise phase is already aiming at a specific block; overriding that would not
			// avoid an enderman, it would just stop the mining.
			double pitch = precise ? steer.pitch : aimPitch(steer.pitch);
			if (!eating) player.setXRot((float) human.pitchFor(cfg, pitch, smoothPitch, wobble));
			else human.syncCamera(finalYaw, player.getXRot());
			baseYaw = steer.yaw;
			wanderOffset = 0;
		}

		Options o = mc.options;
		boolean still = eating && cfg.autoEatHoldStill;

		// Which keys carry us along that heading from wherever the camera has got to. This is
		// the whole reason the camera is allowed to be slow: the walking does not wait for it.
		boolean forward = steer.forward, back = steer.back, left = steer.left, right = steer.right;
		if (steer.hasMove && !stopped) {
			boolean[] keys = Bot.keysFor(player.getYRot(), steer.moveYaw);
			forward = keys[0];
			back = keys[1];
			left = keys[2];
			right = keys[3];
		} else if (stopped) {
			forward = back = left = right = false;
		}

		// using an item cancels a sprint, so never ask for both at once
		sprint = sprint && forward && !eating && !steer.use;
		// only on the walk between jobs: hopping on the spot at a wall mines nothing
		if (destroyer.phase == BaseDestroyer.Phase.WALKING) updateJumpSprint(player, sprint && !still);
		if (forward && !still && !precise && mc.level != null) autoJump(player, mc.level, steer.moveYaw);
		askedForward = forward && !still;
		setKey(o.keyUp, askedForward);
		setKey(o.keyDown, back && !still);
		setKey(o.keyLeft, left && !still);
		setKey(o.keyRight, right && !still);
		setKey(o.keyJump, (steer.jump || jumpTicks > 0) && !still);
		setKey(o.keyShift, steer.sneak || cfg.holdSneak);
		setKey(o.keySprint, sprint);
		setKey(o.keyAttack, steer.attack && !eating);
		setKey(o.keyUse, steer.use && !eating);
		keysHeld = true;
	}

	/** @return true if the job ending also ended the walk. */
	private boolean destroyerFinished(Minecraft mc, LocalPlayer player, ClientLevel level) {
		String reason = "Nothing left to break — %d blocks mined".formatted(destroyer.mined);
		journal.log(Journal.Kind.MINED, level, player.blockPosition(), reason);
		react(mc, cfg.destroyDoneReaction, reason);
		if (cfg.destroyStopWhenDone && cfg.movementEnabled) {
			stop(mc, reason);
			return true;
		}
		return false;
	}

	/** Notices a target block turning to air, which is the only honest way to count one. */
	private void countMined(ClientLevel level) {
		BlockPos now = destroyer.target();
		if (watchedBlock != null && !watchedBlock.equals(now)) {
			if (level.getBlockState(watchedBlock).isAir()) destroyer.noteMined(level, watchedBlock, watchedName);
			watchedBlock = null;
		}
		if (now != null && !now.equals(watchedBlock)) {
			watchedBlock = now;
			watchedName = level.getBlockState(now).getBlock().getName().getString();
		}
	}

	// ------------------------------------------------------------- key state

	private void applyKeys(Minecraft mc, LocalPlayer player) {
		Options o = mc.options;
		boolean holdingStillToEat = eating && cfg.autoEatHoldStill;
		boolean forward = !holdingStillToEat
				&& state != State.PAUSED
				&& (state != State.STRAFING || cfg.strafeKeepsForward);
		// a retreat sprints whether or not the walk normally does
		// using an item cancels a sprint, so never ask for both at once
		boolean sprint = (cfg.holdSprint || fleeing()) && forward && !eating;
		askedForward = forward;
		updateJumpSprint(player, sprint);

		// Free wandering walks where it is looking, which is both what it means and what looks
		// right. Travelling somewhere does not: the camera takes the better part of half a
		// second to come round, and a leash correction, a dodge round a tree or a run from a
		// zombie that only takes effect after that is a correction that arrives too late.
		boolean back = false, left = false, right = false;
		if (forward && (navActive || fleeing() || avoid.steering())) {
			boolean[] keys = Bot.keysFor(player.getYRot(), walkYaw);
			forward = keys[0];
			back = keys[1];
			left = keys[2];
			right = keys[3];
		}
		setKey(o.keyUp, forward);
		setKey(o.keyDown, back);
		setKey(o.keyLeft, left || (!holdingStillToEat && state == State.STRAFING && strafeDir < 0));
		setKey(o.keyRight, right || (!holdingStillToEat && state == State.STRAFING && strafeDir > 0));
		setKey(o.keySprint, sprint);
		setKey(o.keyShift, cfg.holdSneak);
		setKey(o.keyJump, jumpTicks > 0);
		keysHeld = true;
	}

	/**
	 * Bunny-hopping. A sprint-jump carries further than a sprint does, so someone actually
	 * going somewhere does it on nearly every landing - which makes a bot whose feet never
	 * once leave the floor the easier of the two to pick out of a crowd.
	 *
	 * <p>Shares {@code jumpCooldown} with the auto-jump on purpose: without that the two
	 * would take turns and produce a hop rate no pair of legs could manage.
	 */
	private void updateJumpSprint(LocalPlayer player, boolean sprinting) {
		if (!cfg.jumpSprintEnabled || !sprinting || eating) return;
		if (jumpTicks > 0 || jumpCooldown > 0 || !player.onGround()) return;
		// vanilla stops sprinting below 7 food anyway, and a hop in liquid is just a splash
		if (player.getFoodData().getFoodLevel() <= 6 || player.isInWater() || player.isInLava()) return;
		if (!Rng.chance(cfg.jumpSprintChance)) {
			jumpCooldown = Rng.ticks(0.25, 1.5); // sat this one out, as people do
			return;
		}
		jumpTicks = 3;
		jumpCooldown = Math.max(1, cfg.autoJumpCooldownTicks);
	}

	private void release(Minecraft mc) {
		Options o = mc.options;
		for (KeyMapping key : new KeyMapping[]{o.keyUp, o.keyDown, o.keyLeft, o.keyRight, o.keySprint,
				o.keyShift, o.keyJump, o.keyUse, o.keyAttack}) {
			setKey(key, false);
		}
		keysHeld = false;
		jumpTicks = 0;
	}

	/**
	 * Sprint and sneak are {@code ToggleKeyMapping}s: with "toggle sprint" on, every
	 * {@code setDown(true)} flips the state, so calling it each tick would strobe at 20Hz,
	 * and {@code setDown(false)} does nothing at all. Only write when the state is wrong,
	 * then flip if the write did not take.
	 */
	private static void setKey(KeyMapping key, boolean down) {
		if (key.isDown() == down) return;
		key.setDown(down);
		if (key.isDown() != down) key.setDown(true);
	}

	// ------------------------------------------------------------ obstacles

	private void handleObstacles(Minecraft mc, LocalPlayer player, ClientLevel level) {
		double yawRad = Math.toRadians(player.getYRot());
		double fx = -Math.sin(yawRad);
		double fz = Math.cos(yawRad);
		double probe = Math.max(0.1, cfg.autoJumpProbeDistance);
		BlockPos feet = BlockPos.containing(player.getX() + fx * probe, player.getY() + 0.1, player.getZ() + fz * probe);

		// A ledge the steering has already found a way round is not worth stopping for -
		// that is the whole point of it. Only a drop with nowhere to go still counts.
		boolean handled = cfg.avoidEnabled && avoid.steering() && !avoid.trapped();
		if (cfg.stopAtLedge && !handled && player.onGround() && checkLedge(level, feet)) {
			String why = "Drop of more than %d blocks straight ahead".formatted(cfg.ledgeDropBlocks);
			journal.log(Journal.Kind.LEDGE, level, player.blockPosition(), why);
			if (react(mc, cfg.ledgeReaction, why, false)) return;
		}

		autoJump(player, level, walkYaw);
	}

	/**
	 * A hop over whatever is one block high in front of us, and a kick off the bottom when
	 * that turns out to be water.
	 *
	 * <p>Shared with the job rather than left to the wandering, which is where it used to
	 * live. A route is a list of blocks to stand on; it says nothing about the stair, the
	 * fence post or the half-mined lip between two of them, and walking into one of those is
	 * the commonest way a bot stops moving without anything obviously being wrong.
	 */
	private void autoJump(LocalPlayer player, ClientLevel level, double heading) {
		if (!cfg.autoJumpEnabled || jumpCooldown > 0) return;
		// The heading walked, not the one looked at. Since the two came apart, probing along
		// the camera checks for a step in front of whatever the bot happens to be watching.
		double yawRad = Math.toRadians(heading);
		double probe = Math.max(0.1, cfg.autoJumpProbeDistance);
		BlockPos feet = BlockPos.containing(player.getX() - Math.sin(yawRad) * probe,
				player.getY() + 0.1, player.getZ() + Math.cos(yawRad) * probe);

		if (cfg.autoJumpSwimUp && player.isInWater()) {
			if (solid(level, feet)) jumpTicks = 2;
			return;
		}
		if (!player.onGround()) return;

		int height = 0;
		while (height < 4 && solid(level, feet.above(height))) height++;
		if (height == 0 || height > cfg.autoJumpMaxHeight) return;

		BlockPos head = BlockPos.containing(player.getX(), player.getY(), player.getZ()).above(2);
		if (solid(level, feet.above(height)) || solid(level, feet.above(height + 1)) || solid(level, head)) return;

		jumpTicks = 3;
		jumpCooldown = Math.max(1, cfg.autoJumpCooldownTicks);
	}

	private boolean checkLedge(ClientLevel level, BlockPos ahead) {
		if (Avoidance.holdsWeight(level, ahead)) return false;
		for (int d = 1; d <= cfg.ledgeDropBlocks + 1; d++) {
			// anything that holds weight is a floor, whether or not it fills its block
			if (Avoidance.holdsWeight(level, ahead.below(d))) return false;
		}
		return true;
	}

	/** In the way rather than underfoot: a slab is something to walk onto, not to jump over. */
	private boolean solid(ClientLevel level, BlockPos pos) {
		return Avoidance.fillsSpace(level, pos);
	}

	// -------------------------------------------------------- the handbrake

	private void runSafeStop(Minecraft mc, LocalPlayer player, ClientLevel level) {
		if (!cfg.safeStopEnabled) return;
		boolean wantsForward = mc.options.keyUp.isDown() && state != State.PAUSED && !eating;
		String failure = safeStop.check(mc, player, wantsForward, mc.options.keySprint.isDown(), cfg.holdSneak);
		if (failure == null) return;
		journal.log(Journal.Kind.SAFE_STOP, level, player.blockPosition(), failure);
		react(mc, cfg.safeStopReaction, "Safe stop — " + failure);
	}

	// -------------------------------------------------------------- stuck

	private void resetProgress(LocalPlayer player) {
		progressX = player.getX();
		progressZ = player.getZ();
		ticksSinceProgress = 0;
		inputBlockedTicks = 0;
	}

	private void updateStuck(Minecraft mc, LocalPlayer player) {
		if (!cfg.stuckDetectEnabled) return;
		// a deliberate pause, or a meal, is not being stuck
		if (state == State.PAUSED || state == State.BLOCKED || state == State.EATING) {
			resetProgress(player);
			return;
		}

		double dx = player.getX() - progressX;
		double dz = player.getZ() - progressZ;
		if (dx * dx + dz * dz >= cfg.stuckMinDistance * cfg.stuckMinDistance) {
			resetProgress(player);
		} else {
			ticksSinceProgress++;
		}

		if (cfg.detectInputBlocked && state != State.UNSTICKING) {
			// Only when forward was asked for. Strafing round a corner is a tick with the key
			// legitimately up, and counting those reports the mod's own steering as sabotage.
			boolean actual = player.input != null && player.input.keyPresses != null && player.input.keyPresses.forward();
			inputBlockedTicks = actual || !askedForward ? 0 : inputBlockedTicks + 1;
		}

		boolean noProgress = ticksSinceProgress > cfg.stuckWindowSec * 20;
		boolean inputEaten = inputBlockedTicks > 20;
		if (!noProgress && !inputEaten) return;

		String why = inputEaten ? "Movement input is being blocked" : "Stuck — no progress for %.1fs".formatted(cfg.stuckWindowSec);
		resetProgress(player);

		if (cfg.stuckAutoUnstick && unstickTried < cfg.stuckUnstickAttempts) {
			unstickTried++;
			state = State.UNSTICKING;
			jumpTicks = 6;
			beginTurn(Rng.range(55, 140) * (Rng.coinFlip() ? -1 : 1));
			eventTicksLeft = Math.max(40, turnTicksTotal);
			safeStop.grace(60);
			if (cfg.verboseLogging) say(mc, "§e" + why + " — unstick attempt " + unstickTried);
			return;
		}
		unstickTried = 0;
		journal.log(Journal.Kind.STUCK, mc.level, player.blockPosition(), why);
		react(mc, cfg.stuckReaction, why);
	}

	// -------------------------------------------------------------- guards

	private void handleChatTrigger(Minecraft mc, LocalPlayer player, ClientLevel level) {
		String chat = pendingChatTrigger;
		if (chat == null) return;
		pendingChatTrigger = null;
		if (!cfg.stopOnChatKeyword) return;
		journal.log(Journal.Kind.CHAT, level, player.blockPosition(), "Chat mentioned \"" + chat + "\"");
		react(mc, cfg.chatKeywordReaction, "Chat mentioned \"" + chat + "\"");
	}

	private void handleDeath(Minecraft mc, LocalPlayer player, ClientLevel level) {
		boolean dead = player.isDeadOrDying() || player.getHealth() <= 0;
		if (dead && !wasDead) {
			wasDead = true;
			// stop sampling: from here on everything Deaths holds describes the last tick alive
			deaths.freeze(player);
			alert(mc, "You died — " + deaths.guess(), cfg.alertRepeatCount);
			if (cfg.movementEnabled) stop(mc, "Died");
			deathLogIn = 40;
		} else if (!dead) {
			wasDead = false;
			deaths.thaw();
		}

		// The entry waits for the cause. Health reaching zero and the server saying what did
		// it are two different packets, so writing the entry on the tick the health arrives
		// would record every death as "cause unknown".
		if (deathLogIn > 0) {
			boolean known = !Deaths.cause(mc, player).isBlank();
			if (known || --deathLogIn <= 0) {
				journal.log(Journal.Kind.DEATH, level, deaths.where(player), deaths.note(mc, player));
				lastReason = deaths.headline(mc, player);
				deathLogIn = -1;
			}
		}
	}

	/**
	 * Whether damage is being answered rather than merely suffered.
	 *
	 * <p>"Stop the moment anything hits me" and "fight back when something hits me" are
	 * directly contradictory instructions, and with both on the first is the only one that
	 * ever runs — the stop lands on the same tick as the hit, before the fight can start. So
	 * with the job running and fighting back switched on, this guard stands down and the
	 * combat retreat and the low-health guard are what keep the bot alive.
	 */
	private boolean fightingBack() {
		return cfg.destroyerEnabled && cfg.combatEnabled;
	}

	/**
	 * The same contradiction as {@link #fightingBack()}, from the other direction: "walk away
	 * from what is chasing me" and "stand still the moment it lands a hit" cannot both run,
	 * and the stop would always win because the hit and the retreat land on the same tick.
	 * With a retreat under way the damage stop and the hostile stop both stand down; the
	 * low-health guard does not, because that one is the alarm for a retreat that is losing.
	 */
	private boolean walkingAway() {
		return cfg.fleeFromHostiles && fleeing();
	}

	/** @return true if a guard already stopped movement this tick. */
	private boolean runGuards(Minecraft mc, LocalPlayer player, ClientLevel level) {
		float health = player.getHealth();
		float lost = lastDamage; // worked out in tick(), which samples whether walking or not
		if (cfg.stopOnDamage && lost >= cfg.damageThreshold && !fightingBack() && !walkingAway()) {
			journal.log(Journal.Kind.DAMAGE, level, player.blockPosition(), "Took %.1f damage".formatted(lost));
			return react(mc, cfg.damageReaction, "Took %.1f damage".formatted(lost));
		}

		if (cfg.stopOnLowHealth && health <= cfg.lowHealthThreshold) {
			journal.log(Journal.Kind.LOW_HEALTH, level, player.blockPosition(), "Health at %.1f".formatted(health));
			return react(mc, cfg.lowHealthReaction, "Health down to %.1f".formatted(health));
		}
		if (cfg.stopWhenOutOfFood && autoEat.isOutOfFood(player)) {
			if (!loggedOutOfFood) {
				loggedOutOfFood = true;
				journal.log(Journal.Kind.HUNGER, level, player.blockPosition(), "Hotbar has no food left");
			}
			return react(mc, cfg.outOfFoodReaction, "Hungry with no food on the hotbar");
		}
		if (cfg.stopOnLowHunger && player.getFoodData().getFoodLevel() <= cfg.lowHungerThreshold) {
			return react(mc, cfg.hungerReaction, "Hunger down to " + player.getFoodData().getFoodLevel());
		}
		if (cfg.stopInLiquid && (player.isInWater() || player.isInLava())) {
			return react(mc, cfg.liquidReaction, "Standing in liquid", false);
		}
		if (cfg.stopAfterMaxRuntime && runtimeSeconds() >= cfg.maxRuntimeMinutes * 60) {
			return react(mc, cfg.maxRuntimeReaction, "Ran for %.0f minutes".formatted(cfg.maxRuntimeMinutes));
		}
		if (cfg.stopOnNearbyPlayer) {
			AbstractClientPlayer other = nearestOtherPlayer(level, player);
			if (other != null) return onPlayerSpotted(mc, player, level, other);
		}
		if (cfg.stopOnHostileMob) {
			Entity mob = nearestHostileDistance >= 0 && nearestHostileDistance <= cfg.hostileMobRadius
					? nearestHostile : null;
			if (mob != null) {
				String why = "%s is %.0f blocks away"
						.formatted(mob.getName().getString(), Math.sqrt(mob.distanceToSqr(player)));
				journal.log(Journal.Kind.HOSTILE, level, mob.blockPosition(), why);
				boolean visible = !cfg.hostileStopOnlyIfVisible || player.hasLineOfSight(mob);
				// walking away is already the answer to this one, so only its alert half is left
				return react(mc, gate(cfg.hostileMobReaction, visible && !cfg.fleeFromHostiles), why);
			}
		}

		if (cfg.containerScanEnabled && --scanCooldown <= 0) {
			scanCooldown = cfg.containerScanIntervalTicks;
			lastScan = ContainerScanner.scan(level, player, cfg);

			for (ContainerScanner.Landmark landmark : lastScan.landmarks()) {
				journal.log(Journal.Kind.LANDMARK, level, landmark.pos(), landmark.name());
			}
			if (lastScan.grouped() >= cfg.containerThreshold) {
				BlockPos where = lastScan.densestChunkCentre() != null
						? lastScan.densestChunkCentre() : player.blockPosition();
				String scope = lastScan.grouped() < lastScan.total()
						? " (%d of %d in one place)".formatted(lastScan.grouped(), lastScan.total()) : "";
				String note = lastScan.nearestDistance() >= 0
						? "%d storage blocks — %s%s (nearest %.0f blocks away)"
						.formatted(lastScan.grouped(), lastScan.summary(), scope, lastScan.nearestDistance())
						: "%d storage blocks — %s%s".formatted(lastScan.grouped(), lastScan.summary(), scope);
				// A cluster already in the log is not news. Without this the reaction fires on
				// every scan for as long as it is in range - an alert every few seconds, and
				// with a stop reaction a stop you cannot walk away from. When nothing is being
				// recorded there is no "already found" to consult, so it behaves as it did.
				boolean fresh = journal.log(Journal.Kind.CONTAINER_CLUSTER, level, where, note);
				if (fresh || !journal.records(Journal.Kind.CONTAINER_CLUSTER)) {
					return react(mc, cfg.containerReaction, note);
				}
			}
		}
		return false;
	}

	/** Another player is the one sighting worth its own alert volume and a pin on the map. */
	private boolean onPlayerSpotted(Minecraft mc, LocalPlayer player, ClientLevel level, AbstractClientPlayer other) {
		String name = other.getName().getString();
		double distance = Math.sqrt(other.distanceToSqr(player));
		String reason = "%s is %.0f blocks away".formatted(name, distance);

		if (cfg.playerLogCoords) {
			journal.log(Journal.Kind.PLAYER_SPOTTED, level, other.blockPosition(),
					"%s, %.0f blocks from %s".formatted(name, distance, player.blockPosition().toShortString()));
		}
		if (cfg.playerAlertEnabled) alert(mc, reason, cfg.playerAlertRepeats);

		// the alert above already played, so only the stop half of the reaction is left
		lastReason = reason;
		if (cfg.nearbyPlayerReaction.alerts() && !cfg.playerAlertEnabled) alert(mc, reason, cfg.alertRepeatCount);

		boolean visible = !cfg.playerStopOnlyIfVisible || player.hasLineOfSight(other);
		if (cfg.nearbyPlayerReaction.stops()) {
			if (visible) return requestStop(mc, reason);
			if (cfg.verboseLogging) say(mc, "§7" + name + " is close but out of sight - still walking");
		}
		return false;
	}

	private AbstractClientPlayer nearestOtherPlayer(ClientLevel level, LocalPlayer self) {
		double best = cfg.nearbyPlayerRadius * cfg.nearbyPlayerRadius;
		AbstractClientPlayer found = null;
		for (AbstractClientPlayer p : level.players()) {
			if (p == self || p.isSpectator()) continue;
			double d = p.distanceToSqr(self);
			if (d <= best) {
				best = d;
				found = p;
			}
		}
		return found;
	}

	/**
	 * One pass over the entity list per tick, feeding three separate things: the hostile
	 * guard, the enderman look-away and the retreat. Three of them each running their own
	 * scan would be three times the work for the same answer.
	 */
	private void scanThreats(ClientLevel level, LocalPlayer self) {
		endermanNear = false;
		hostilesNear = 0;
		Entity nearest = null;
		double best = Double.MAX_VALUE;
		double stare = cfg.endermanLookRadius * cfg.endermanLookRadius;
		double crowd = cfg.fleeRadius * cfg.fleeRadius;
		for (Entity e : level.entitiesForRendering()) {
			double d = e.distanceToSqr(self);
			if (cfg.endermanAvoidLook && d <= stare && e instanceof EnderMan) endermanNear = true;
			if (!(e instanceof Enemy)) continue;
			if (e.isAlive() && d <= crowd) hostilesNear++;
			if (d < best) {
				best = d;
				nearest = e;
			}
		}
		nearestHostile = nearest;
		nearestHostileDistance = nearest == null ? -1 : Math.sqrt(best);
		updateFlee(level, self, nearest);
	}

	/**
	 * Whether to be somewhere else, and which way.
	 *
	 * <p>"Coming towards the bot" is read off the distance rather than off the mob's own
	 * motion, because a client is told where a mob <em>is</em>, not where it is going - the
	 * motion field only arrives on knockback. How much of that distance counts as an approach
	 * is {@link #charging}, which is where the care went.
	 *
	 * <p>The countdown out of a retreat only runs while the radius is empty, so "until it is
	 * safe" means exactly that rather than "for four seconds".
	 */
	private void updateFlee(ClientLevel level, LocalPlayer player, Entity mob) {
		if (!cfg.fleeFromHostiles || !outmatched(player)) {
			fleeTicks = 0;
			threatId = -1;
			threatMaxDistance = -1;
			return;
		}
		double d = nearestHostileDistance;
		boolean inRange = mob != null && d <= cfg.fleeRadius;

		if (!inRange || mob.getId() != threatId) threatMaxDistance = inRange ? d : -1;
		else threatMaxDistance = Math.max(threatMaxDistance, d);
		threatId = mob == null ? -1 : mob.getId();

		// something already within arm's reach counts without waiting to watch it close
		if (inRange && (charging(d, threatMaxDistance) || d <= MELEE_BLOCKS)) {
			if (fleeTicks == 0) {
				journal.log(Journal.Kind.HOSTILE, level, player.blockPosition(),
						"Leaving - %s closing from %.0f blocks".formatted(mob.getName().getString(), d));
			}
			fleeTicks = Math.max(1, (int) Math.round(cfg.fleeSafeSec * 20));
			double dx = player.getX() - mob.getX();
			double dz = player.getZ() - mob.getZ();
			// yaw 0 faces +Z, 90 faces -X - the same convention the navigation uses
			if (dx * dx + dz * dz > 1e-6) fleeYaw = Math.toDegrees(Math.atan2(-dx, dz));
		} else if (fleeTicks > 0 && !inRange) {
			// the all-clear only counts down while the radius is actually clear
			fleeTicks--;
		}
	}

	public boolean fleeing() {
		return fleeTicks > 0;
	}

	/**
	 * Whether this is a fight worth leaving rather than having.
	 *
	 * <p>Running from one zombie while holding a diamond sword is not caution. It is a bot
	 * that finishes nothing, because the retreat outranks the job and every wandering mob on
	 * the server gets to cancel the work — and it is not what a person does either. So the
	 * retreat asks first whether the odds are actually bad: hurt, outnumbered, or with
	 * nothing on the bar to fight with. Fighting back being switched off makes every fight a
	 * bad one, which is the honest reading of that setting.
	 */
	private boolean outmatched(LocalPlayer player) {
		if (!cfg.combatEnabled || !cfg.combatFightMobs) return true;
		if (player.getHealth() <= Math.max(cfg.combatRetreatHealth, cfg.lowHealthThreshold)) return true;
		if (Combat.bestWeaponScore(player) <= 0) return true;
		return hostilesNear >= CROWD;
	}

	/** More hostiles than one person swinging one weapon can hold off. */
	static final int CROWD = 3;

	/** Net ground a threat has to make up before it counts as coming at us. */
	static final double CLOSING_BLOCKS = 2.0;
	/** Close enough that how it got there stops mattering. */
	static final double MELEE_BLOCKS = 3.5;

	/**
	 * Whether a threat has made up ground, measured against the furthest it has been rather
	 * than against where it was last tick.
	 *
	 * <p>The per-tick version of this is the obvious one and it does not work. A mob standing
	 * still is lerped between position packets, so it reads a hair closer on about half of
	 * all ticks — and any counter that rewards those ticks more than it punishes the other
	 * half climbs to its trigger on a coin flip and calls a fencepost an ambush. Net closure
	 * has no such failure: two blocks of jitter is two blocks of actual approach.
	 *
	 * @param furthest the greatest distance seen since this threat was picked up, or -1
	 */
	static boolean charging(double distance, double furthest) {
		return furthest >= 0 && furthest - distance >= CLOSING_BLOCKS;
	}

	/**
	 * Self-check on that one judgement - the rest of the retreat is lookups:
	 * {@code ./gradlew selfCheck -Pcheck=com.damia.movrand.MovementController}
	 */
	public static void main(String[] args) {
		// a zombie walking in at its actual 0.115 blocks a tick is noticed inside a second
		double d = 14, furthest = 14;
		int ticks = 0;
		while (!charging(d, furthest) && ticks < 400) {
			d -= 0.115;
			furthest = Math.max(furthest, d);
			ticks++;
		}
		assert charging(d, furthest) : "a zombie walking straight at us was never noticed";
		assert ticks <= 20 : "took %.1fs to notice an approach".formatted(ticks / 20.0);

		// a mob standing still is lerped, so it reads closer on about half of all ticks. An
		// hour of that must not add up to anything - this is the bug the counter version had.
		java.util.random.RandomGenerator noise = new java.util.SplittableRandom(1);
		d = 8;
		furthest = 8;
		for (int i = 0; i < 72_000; i++) {
			d = 8 + (noise.nextDouble() - 0.5) * 0.08;
			furthest = Math.max(furthest, d);
			assert !charging(d, furthest) : "jitter alone read as a charge at tick " + i;
		}
		// and the pathological version of the same thing: a perfect alternation
		d = 8;
		furthest = 8;
		for (int i = 0; i < 72_000; i++) {
			d = 8 + (i % 2 == 0 ? 0.04 : -0.04);
			furthest = Math.max(furthest, d);
			assert !charging(d, furthest) : "an alternating hair read as a charge at tick " + i;
		}

		// a mob that wanders two blocks nearer counts, however slowly it got there
		assert charging(6, 8.0) : "two blocks of net approach was not a charge";
		assert !charging(6.5, 8.0) : "a block and a half was";
		// and once the ground is given back the reference moves out with it, so the same mob
		// has to earn its charge again rather than the retreat latching on forever
		double away = 6, mark = 8;
		while (charging(away, mark) && away < 100) {
			away += 0.25;
			mark = Math.max(mark, away);
		}
		assert away <= 8.5 : "the retreat latched until %.1f blocks".formatted(away);
		assert !charging(away, mark) : "ground already given back still counted";
		// a threat never seen has no reference to measure against
		assert !charging(1, -1) : "an unmeasured threat counted as charging";

		System.out.println("MovementController self-check passed");
	}

	/**
	 * The pitch to actually ask for. An enderman aggros on being stared at, and a sweep that
	 * keeps turning towards its next chunk will sooner or later turn towards one - so while
	 * there is one in range the view goes to the floor, which no stare check can hit.
	 */
	private double aimPitch(double want) {
		return endermanNear ? Math.max(want, cfg.endermanLookDownDeg) : want;
	}

	// ------------------------------------------------------------ reactions

	/** @return true if this tick's work is over, either stopped or committed to stopping. */
	private boolean react(Minecraft mc, Config.Reaction reaction, String reason) {
		return react(mc, reaction, reason, true);
	}

	/**
	 * @param human whether to take a moment before acting. False for the guards that exist to
	 *              keep you alive rather than to keep you unremarkable: waiting a fifth of a
	 *              second before a ledge means walking off it, and nothing is watching the
	 *              timing of a fall anyway.
	 */
	private boolean react(Minecraft mc, Config.Reaction reaction, String reason, boolean human) {
		lastReason = reason;
		if (reaction.alerts()) alert(mc, reason, cfg.alertRepeatCount);
		if (reaction.stops()) {
			if (!human) {
				stop(mc, reason);
				return true;
			}
			return requestStop(mc, reason);
		}
		if (reaction == Config.Reaction.NOTHING && cfg.verboseLogging) say(mc, "§7" + reason);
		return false;
	}

	/**
	 * Decides to stop, then waits a beat before doing it.
	 *
	 * <p>A stop landing on the exact tick its trigger fired is the one thing in this mod no
	 * reaction time explains, and the constant zero is far easier to notice than any single
	 * stop would be. The alert has already played by now, which costs nothing: a sound on
	 * this machine is not something anyone else can observe.
	 *
	 * @return true once the decision is made, so the caller stops working on this tick.
	 */
	private boolean requestStop(Minecraft mc, String reason) {
		if (!cfg.movementEnabled) return true; // nothing left to stop
		if (!cfg.reactionDelayEnabled) {
			stop(mc, reason);
			return true;
		}
		if (pendingStopReason == null) {
			pendingStopReason = reason;
			pendingStopTicks = Math.max(1, (int) Math.round(
					Rng.range(cfg.reactionDelayMinMs, cfg.reactionDelayMaxMs) / 50.0));
			lastReason = reason;
		}
		// already committed - carry on walking for the rest of the beat rather than re-deciding
		return true;
	}

	/**
	 * Drops a reaction to its alert half when the thing that triggered it was never visible.
	 *
	 * <p>The client is told about players and mobs through walls. Hearing about them is
	 * invisible; walking away from one you could not possibly have seen is not, and is
	 * exactly what someone in vanish is checking for. So the sound still plays and the
	 * coordinate is still logged - only the part anyone else could see is withheld.
	 */
	private static Config.Reaction gate(Config.Reaction reaction, boolean visible) {
		if (visible) return reaction;
		return reaction.alerts() ? Config.Reaction.ALERT : Config.Reaction.NOTHING;
	}

	public void alert(Minecraft mc, String reason, int repeats) {
		if (!cfg.alertEnabled) return;
		if (tickCount - lastAlertTick < cfg.alertCooldownSec * 20) return;
		lastAlertTick = tickCount;
		lastAlertReason = reason;
		AlertSound.play(cfg, repeats);
		if (cfg.alertInGameSound && mc.player != null) {
			mc.player.playSound(SoundEvents.NOTE_BLOCK_PLING.value(), 1.0f, 1.4f);
		}
		if (cfg.alertChatMessage) say(mc, "§6⚠ §f" + reason);
	}

	private void say(Minecraft mc, String message) {
		MovRand.print(mc, message);
	}

	public String describeState() {
		if (!cfg.movementEnabled) return "Off";
		if (fleeing()) {
			return nearestHostile != null
					? "Leaving - %s %.0fm".formatted(nearestHostile.getName().getString(), nearestHostileDistance)
					: "Leaving";
		}
		if (cfg.destroyerEnabled && destroyer.phase != BaseDestroyer.Phase.OFF) {
			return destroyer.phase.label;
		}
		String suffix = "";
		if (cfg.areaEnabled) suffix = " · %.0f%%".formatted(area.progress() * 100);
		else if (navActive && gotoDistance >= 0) suffix = String.format(Locale.ROOT, " → %.0fm", gotoDistance);
		if (cfg.avoidEnabled && avoid.steering()) {
			return (avoid.trapped() ? "Boxed in" : "Going round") + suffix;
		}
		return state.label + suffix;
	}
}
