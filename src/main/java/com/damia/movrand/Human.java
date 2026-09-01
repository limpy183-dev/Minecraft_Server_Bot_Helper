package com.damia.movrand;

/**
 * Makes the camera move like a hand rather than like a function generator.
 *
 * <p>The obvious way to "humanise" a view is to add a sine wave to the yaw. It looks fine on
 * screen and it is the worst possible choice: a sum of fixed sines has a line spectrum, so a
 * few minutes of rotation samples run through an FFT show two razor-sharp peaks at exactly
 * the same two frequencies, at the same ratio, forever. A hand on a mouse produces broadband
 * noise with no peak at all. The sine is a fingerprint, not a disguise.
 *
 * <p>So the wobble here is a first-order autoregressive walk instead — {@code x = φx + ε}.
 * Its spectrum is a smooth roll-off with nothing to lock onto, it never repeats, and because
 * the stationary standard deviation of that process is known exactly, the kick can be scaled
 * so the amplitude the user asked for is the amplitude they get.
 *
 * <p>Turns get the same treatment in the other direction. A constant number of degrees per
 * tick until the budget runs out is a perfect rectangular velocity pulse — instantly at full
 * speed, instantly back to zero. Real aiming accelerates and decelerates, so a turn is spent
 * along a smoothstep curve instead.
 */
public final class Human {

	/** Independent walks: the two axes of a real hand are not the same signal. */
	private double yawState;
	private double pitchState;

	// ------------------------------------------------------------ the walk

	/**
	 * How strongly one tick's offset is carried into the next. Slower "speed" means a longer
	 * memory, which reads as a slow drift rather than a shake.
	 */
	private static double phi(double speed) {
		return Math.exp(-Math.max(0.0005, Math.min(1.0, speed)));
	}

	/**
	 * Kick size that makes the walk's stationary standard deviation exactly 1.
	 * For {@code x = φx + U(-k,k)}: var(x) = (k²/3)/(1-φ²), so k = √(3(1-φ²)).
	 */
	private static double kick(double phi) {
		return Math.sqrt(3 * (1 - phi * phi));
	}

	private static double step(double state, double speed) {
		double p = phi(speed);
		double next = p * state + Rng.range(-kick(p), kick(p));
		// a random walk cannot run away, but clamp anyway: one NaN or one absurd config
		// value should not be able to spin the camera
		return Math.max(-4, Math.min(4, next));
	}

	/** Degrees to add to the yaw this tick. Zero when the user has jitter switched off. */
	public double yaw(Config cfg) {
		if (!cfg.yawJitterEnabled) {
			yawState = 0;
			return 0;
		}
		yawState = step(yawState, cfg.yawJitterSpeed);
		return yawState * cfg.yawJitterAmplitudeDeg * SCALE;
	}

	/**
	 * Degrees to add to the pitch this tick. Smaller than the yaw — a hand wanders sideways
	 * more than it wanders up and down — but never zero while jitter is on, because a pitch
	 * that holds one value bit-for-bit for an hour is not something a person can do.
	 */
	public double pitch(Config cfg) {
		if (!cfg.yawJitterEnabled) {
			pitchState = 0;
			return 0;
		}
		pitchState = step(pitchState, cfg.yawJitterSpeed * 0.8);
		return pitchState * cfg.yawJitterAmplitudeDeg * SCALE * 0.6;
	}

	/**
	 * Matches the root-mean-square of the two-sine wobble this replaced, so the amplitude
	 * slider means the same thing it did before.
	 */
	private static final double SCALE = 0.55;

	// ------------------------------------------------------- the smoothing

	/** Where the camera actually is, as opposed to where it is being asked to point. */
	private double yawOut, pitchOut;
	private boolean synced;

	/**
	 * Tells the filter where the camera is right now, so the first tick after a start does not
	 * sweep across from wherever the view was left the last time the bot ran.
	 */
	public void syncCamera(double yaw, double pitch) {
		yawOut = yaw;
		pitchOut = pitch;
		synced = true;
	}

	/** Degrees into -180..180, so 350 to 10 is a 20° turn the short way, not 340° the long way. */
	public static double wrap(double deg) {
		double d = deg % 360;
		if (d >= 180) d -= 360;
		if (d < -180) d += 360;
		return d;
	}

	/**
	 * One-pole low-pass on a heading. {@code smoothing} is how much of the previous value is
	 * kept: 0 points straight at the target, 0.95 takes about a second to get there.
	 *
	 * <p>Every input to the camera is a step function. A turn's ease is sampled once a tick,
	 * a dodge appears the instant a wall does, the leash correction snaps on at a boundary.
	 * Steps are what a filter is for, and rounding them off is also what a mouse does on its
	 * own: no hand puts the crosshair somewhere and stops it dead.
	 */
	public static double smooth(double current, double target, double smoothing) {
		double alpha = 1 - Math.max(0, Math.min(0.95, smoothing));
		return current + wrap(target - current) * alpha;
	}

	/** The yaw to set this tick: smoothed towards {@code target}, wobble added afterwards. */
	public double yawFor(Config cfg, double target) {
		return yawFor(cfg, target, cfg.cameraSmoothYaw, 1);
	}

	/**
	 * The same filter with the knobs supplied rather than read.
	 *
	 * <p>Precision work needs a tighter camera than wandering does. A crosshair that takes
	 * half a second to settle is fine when the destination is a chunk and useless when it is
	 * one face of one block: vanilla resets mining progress the moment the crosshair leaves
	 * the block, so a lazy filter does not mine slowly, it mines never.
	 *
	 * @param wobbleScale how much of the view wobble to keep. Never zero — a rotation stream
	 *                    with no noise in it at all is the one thing that is genuinely easy
	 *                    to pick out.
	 */
	public double yawFor(Config cfg, double target, double smoothing, double wobbleScale) {
		if (!synced) syncCamera(target, pitchOut);
		yawOut = wrap(smooth(yawOut, target, smoothing));
		return yawOut + yaw(cfg) * wobbleScale;
	}

	/**
	 * The pitch to set this tick. The wobble goes on after the filter, never before it —
	 * feeding noise back through a low-pass just averages it away and the pitch settles on a
	 * constant, which is the one thing a real player's pitch never does.
	 */
	public double pitchFor(Config cfg, double target) {
		return pitchFor(cfg, target, cfg.cameraSmoothPitch, 1);
	}

	public double pitchFor(Config cfg, double target, double smoothing, double wobbleScale) {
		if (!synced) syncCamera(yawOut, target);
		pitchOut = smooth(pitchOut, Math.max(-90, Math.min(90, target)), smoothing);
		return Math.max(-90, Math.min(90, pitchOut + pitch(cfg) * wobbleScale));
	}

	// ----------------------------------------------------------- the turns

	/** Smoothstep: zero velocity at both ends, so a turn eases in and eases out. */
	public static double ease(double t) {
		t = Math.max(0, Math.min(1, t));
		return t * t * (3 - 2 * t);
	}

	/**
	 * Ticks to spend on a turn of {@code degrees} without ever exceeding {@code perTick}.
	 * Smoothstep peaks at 1.5× its mean rate, so the whole thing has to last half again as
	 * long as a constant-rate turn of the same size.
	 */
	public static int turnTicks(double degrees, double perTick) {
		double rate = Math.max(0.05, perTick);
		return (int) Math.max(1, Math.ceil(1.5 * Math.abs(degrees) / rate));
	}

	/**
	 * Self-check:
	 * {@code ./gradlew selfCheck -Pcheck=com.damia.movrand.Human}
	 */
	public static void main(String[] args) {
		Config cfg = new Config();
		cfg.clampAll();

		// the amplitude slider has to mean something
		for (double amp : new double[]{0.2, 0.55, 2.0}) {
			cfg.yawJitterAmplitudeDeg = amp;
			Human h = new Human();
			double sum = 0, peak = 0, maxStep = 0, last = 0;
			int n = 200_000;
			for (int i = 0; i < n; i++) {
				double v = h.yaw(cfg);
				sum += v * v;
				peak = Math.max(peak, Math.abs(v));
				if (i > 0) maxStep = Math.max(maxStep, Math.abs(v - last));
				last = v;
			}
			double rms = Math.sqrt(sum / n);
			double want = amp * SCALE;
			assert Math.abs(rms - want) < want * 0.08
					: "rms was %.4f, wanted %.4f (amplitude %.2f)".formatted(rms, want, amp);
			// nothing the walk produces may look like a flick
			assert maxStep < amp * 0.6
					: "a single tick moved %.3f° at amplitude %.2f".formatted(maxStep, amp);
			assert peak < amp * 3 : "peak excursion %.3f is too wild".formatted(peak);
			System.out.printf("amplitude %.2f  ->  rms %.4f (want %.4f)  peak %.3f  max step %.4f%n",
					amp, rms, want, peak, maxStep);
		}

		// slower speed must mean a longer memory, not a smaller wobble
		assert phi(0.002) > phi(0.3) : "speed did not map to persistence";
		cfg.yawJitterAmplitudeDeg = 0.55;
		cfg.yawJitterSpeed = 0.002;
		Human slow = new Human();
		double slowStep = 0, prev = 0;
		for (int i = 0; i < 50_000; i++) {
			double v = slow.yaw(cfg);
			if (i > 0) slowStep = Math.max(slowStep, Math.abs(v - prev));
			prev = v;
		}
		cfg.yawJitterSpeed = 0.3;
		Human fast = new Human();
		double fastStep = 0;
		prev = 0;
		for (int i = 0; i < 50_000; i++) {
			double v = fast.yaw(cfg);
			if (i > 0) fastStep = Math.max(fastStep, Math.abs(v - prev));
			prev = v;
		}
		assert fastStep > slowStep * 3
				: "a fast wobble (%.4f) should move further per tick than a slow one (%.4f)"
				.formatted(fastStep, slowStep);

		// switched off means exactly off, not almost off
		cfg.yawJitterEnabled = false;
		Human quiet = new Human();
		for (int i = 0; i < 100; i++) {
			assert quiet.yaw(cfg) == 0 && quiet.pitch(cfg) == 0 : "jitter leaked while disabled";
		}

		// two instances must not walk in step
		cfg.yawJitterEnabled = true;
		Human a = new Human(), b = new Human();
		boolean differs = false;
		for (int i = 0; i < 200; i++) if (a.yaw(cfg) != b.yaw(cfg)) differs = true;
		assert differs : "two walks produced the same sequence";

		// the easing curve
		assert ease(0) == 0 && ease(1) == 1 : "ease does not span 0..1";
		assert Math.abs(ease(0.5) - 0.5) < 1e-9 : "ease is not symmetric";
		double last2 = -1;
		for (int i = 0; i <= 100; i++) {
			double v = ease(i / 100.0);
			assert v >= last2 : "ease went backwards at " + i;
			last2 = v;
		}
		// it must start and finish slowly - that is the whole point
		assert ease(0.02) < 0.02 && ease(0.98) > 0.98 : "ease has no ramp at the ends";

		// and a turn spent along it must never exceed the rate the user asked for
		for (double deg : new double[]{3, 22, 90, 180}) {
			double perTick = 2.5;
			int ticks = turnTicks(deg, perTick);
			double done = 0, fastest = 0;
			for (int i = 1; i <= ticks; i++) {
				double want = deg * ease(i / (double) ticks);
				fastest = Math.max(fastest, Math.abs(want - done));
				done = want;
			}
			assert Math.abs(done - deg) < 1e-9 : "a %.0f° turn finished at %.4f°".formatted(deg, done);
			assert fastest <= perTick + 1e-6
					: "a %.0f° turn peaked at %.3f°/tick, over the %.1f limit".formatted(deg, fastest, perTick);
		}

		// --- the smoothing ---
		assert wrap(350) == -10 && wrap(-350) == 10 && wrap(180) == -180 && wrap(0) == 0
				: "wrap does not take the short way round";
		assert Math.abs(smooth(350, 10, 0.5) - 360) < 1e-9
				: "smoothing crossed zero the long way: " + smooth(350, 10, 0.5);

		cfg.yawJitterEnabled = false; // measure the filter, not the wobble
		for (double sm : new double[]{0, 0.5, 0.7, 0.95}) {
			cfg.cameraSmoothYaw = sm;
			cfg.cameraSmoothPitch = sm;
			Human cam = new Human();
			cam.syncCamera(0, 0);
			double was = 0, biggest = 0;
			for (int i = 0; i < 400; i++) {
				double v = cam.yawFor(cfg, 90);
				assert v >= was - 1e-9 : "the camera went backwards at smoothing " + sm;
				assert v <= 90 + 1e-9 : "the camera overshot to %.4f at smoothing %.2f".formatted(v, sm);
				biggest = Math.max(biggest, v - was);
				was = v;
			}
			assert Math.abs(was - 90) < 0.01 : "smoothing %.2f stalled at %.4f".formatted(sm, was);
			// more smoothing must mean a smaller first step, which is the whole point
			double firstStep = 90 * (1 - Math.min(0.95, sm));
			assert Math.abs(biggest - firstStep) < 1e-6
					: "smoothing %.2f stepped %.4f, expected %.4f".formatted(sm, biggest, firstStep);
		}

		// zero smoothing has to be genuinely off, not nearly off
		cfg.cameraSmoothYaw = 0;
		Human instant = new Human();
		instant.syncCamera(0, 0);
		assert instant.yawFor(cfg, 137) == 137 : "smoothing 0 did not arrive in one tick";

		// and the pitch can never be filtered past the vertical
		cfg.cameraSmoothPitch = 0.5;
		Human look = new Human();
		look.syncCamera(0, 0);
		for (int i = 0; i < 200; i++) {
			double p = look.pitchFor(cfg, 400); // an absurd request
			assert p <= 90 && p >= -90 : "pitch left the legal range at " + p;
		}

		// a fresh camera adopts the first heading rather than sweeping to it from zero
		Human fresh = new Human();
		assert fresh.yawFor(cfg, -170) == -170 : "an unsynced camera swept in from nowhere";

		System.out.println("Human self-check passed");
	}
}
