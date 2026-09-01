package com.damia.movrand;

import java.security.SecureRandom;
import java.util.random.RandomGenerator;

/**
 * The randomness source. Seeded from the OS entropy pool, so two sessions never
 * produce the same walk — a plain {@code new Random()} would be seeded from the
 * clock and is guessable.
 */
public final class Rng {

	private static RandomGenerator gen = reseeded();

	private Rng() {
	}

	private static RandomGenerator reseeded() {
		// SplittableRandom is a good generator; the point here is the seed, which comes
		// from the OS entropy pool rather than the clock.
		return new java.util.SplittableRandom(new SecureRandom().nextLong());
	}

	public static void reseed() {
		gen = reseeded();
	}

	public static double nextDouble() {
		return gen.nextDouble();
	}

	public static boolean chance(double p) {
		return gen.nextDouble() < p;
	}

	public static int nextInt(int boundExclusive) {
		return gen.nextInt(Math.max(1, boundExclusive));
	}

	public static boolean coinFlip() {
		return gen.nextBoolean();
	}

	/** Uniform value in [min, max]. */
	public static double range(double min, double max) {
		if (max <= min) return min;
		return min + gen.nextDouble() * (max - min);
	}

	/** Draws from [min, max] using the requested shape. */
	public static double range(double min, double max, Config.Distribution d) {
		if (max <= min) return min;
		double t = switch (d) {
			case UNIFORM -> gen.nextDouble();
			// three-sigma bell folded into 0..1, so ~99.7% of draws land inside the range
			case GAUSSIAN -> clamp01(0.5 + gen.nextGaussian() / 6.0);
			case SKEW_LOW -> {
				double u = gen.nextDouble();
				yield u * u;
			}
			case SKEW_HIGH -> {
				double u = gen.nextDouble();
				yield 1.0 - u * u;
			}
		};
		return min + t * (max - min);
	}

	public static int ticks(double min, double max, Config.Distribution d) {
		return Math.max(1, (int) Math.round(range(min, max, d) * 20.0));
	}

	public static int ticks(double min, double max) {
		return ticks(min, max, Config.Distribution.UNIFORM);
	}

	private static double clamp01(double v) {
		return v < 0 ? 0 : v > 1 ? 1 : v;
	}

	/**
	 * Self-check — needs nothing from Minecraft:
	 * {@code java -ea -cp build/classes/java/main com.damia.movrand.Rng}
	 */
	public static void main(String[] args) {
		int n = 200_000;
		for (Config.Distribution d : Config.Distribution.values()) {
			double sum = 0, lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
			for (int i = 0; i < n; i++) {
				double v = range(10, 20, d);
				assert v >= 10 && v <= 20 : d + " left the range: " + v;
				sum += v;
				lo = Math.min(lo, v);
				hi = Math.max(hi, v);
			}
			double mean = sum / n;
			boolean shaped = switch (d) {
				case UNIFORM, GAUSSIAN -> Math.abs(mean - 15) < 0.2;
				case SKEW_LOW -> mean < 14;
				case SKEW_HIGH -> mean > 16;
			};
			assert shaped : d + " has the wrong shape, mean was " + mean;
			System.out.printf("%-10s mean %.2f  span %.2f–%.2f%n", d, mean, lo, hi);
		}

		// an inverted or degenerate range must not hang or throw
		assert range(5, 5, Config.Distribution.GAUSSIAN) == 5;
		assert range(9, 3, Config.Distribution.UNIFORM) == 9;
		assert ticks(0.0, 0.0, Config.Distribution.UNIFORM) == 1 : "ticks must never be zero";

		// two reseeds must not produce the same stream
		reseed();
		double a = nextDouble();
		reseed();
		assert a != nextDouble() : "reseeding produced the same value twice";

		System.out.println("Rng self-check passed");
	}
}
