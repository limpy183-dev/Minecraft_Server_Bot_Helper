package com.damia.movrand;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;

/** Reconstruct the tick-based view at render frequency, without changing raycasts or physics. */
public final class CameraSmoothing {
	private static final Samples yaw = new Samples(), pitch = new Samples(false);
	private static Entity tracked;

	private CameraSmoothing() {}

	public static void beginTick(Minecraft mc) {
		if (mc.player == null || !MovRand.controller().controlsCamera() || mc.player.isDeadOrDying()) {
			tracked = null;
		} else if (tracked != mc.player) {
			tracked = mc.player;
			yaw.sync(tracked.getYRot());
			pitch.sync(tracked.getXRot());
		}
	}

	public static void endTick(Minecraft mc) {
		if (tracked == mc.player && tracked != null && !mc.isPaused()) {
			yaw.add(tracked.getYRot());
			pitch.add(tracked.getXRot());
		}
	}

	public static float view(Entity entity, float partialTick, boolean horizontal) {
		MovementController controller = MovRand.controller();
		if (entity != tracked || controller == null || !controller.controlsCamera())
			return horizontal ? entity.getViewYRot(partialTick) : entity.getViewXRot(partialTick);
		double strength = controller.cameraSmoothing(horizontal);
		if (strength <= 0) return horizontal ? entity.getViewYRot(partialTick) : entity.getViewXRot(partialTick);
		return (float) (horizontal ? yaw : pitch).at(partialTick, strength);
	}

	/** One extra tick of lookahead gives both sides of a tick boundary the same velocity. */
	static final class Samples {
		private final boolean angular;
		private double a, b, c, d;
		Samples() { this(true); }
		Samples(boolean angular) { this.angular = angular; }
		void sync(double angle) { a = b = c = d = angle; }
		void add(double angle) {
			a = b; b = c; c = d;
			d += angular ? Human.wrap(angle - d) : angle - d;
		}
		double at(double t, double strength) {
			t = Math.max(0, Math.min(1, t));
			strength = Math.max(0, Math.min(1, strength));
			double delta = c - b;
			double start = tangent(b - a, delta), end = tangent(delta, d - c);
			double curve = b + delta * Human.ease(t)
					+ start * t * (1 - t) * (1 - t) - end * t * t * (1 - t);
			return b + delta * t + strength * (curve - b - delta * t);
		}
		private static double tangent(double before, double after) {
			return before * after <= 0 ? 0 : 2 * before * after / (before + after);
		}
	}

	static void selfCheck() {
		Samples samples = new Samples();
		samples.sync(170);
		double oldEnd = 170, oldVelocity = 0;
		for (double target : new double[]{179, -179, -150, -150, -150, 90, 45, 44, 44, 44}) {
			samples.add(target);
			double start = samples.at(0, 1), end = samples.at(1, 1);
			double velocity = (samples.at(0.00001, 1) - start) / 0.00001;
			assert Math.abs(start - oldEnd) < 1e-9 : "frame boundary snapped";
			assert Math.abs(velocity - oldVelocity) < 0.01 : "frame velocity discontinuity";
			for (int fps : new int[]{30, 60, 144, 240}) {
				for (int frame = 0; frame <= fps; frame++) {
					double angle = samples.at(frame / (double) fps, 1);
					assert angle >= Math.min(start, end) - 1e-9 && angle <= Math.max(start, end) + 1e-9
							: "render interpolation overshot a target";
				}
			}
			oldEnd = end;
			oldVelocity = (end - samples.at(0.99999, 1)) / 0.00001;
		}
		assert Math.abs(samples.at(1, 1) - 44) < 1e-9 : "render never settled";
		Samples vertical = new Samples(false);
		vertical.sync(-90);
		vertical.add(90); vertical.add(90); vertical.add(90);
		assert vertical.at(1, 1) == 90 : "rendered pitch wrapped through the floor";
	}
}
