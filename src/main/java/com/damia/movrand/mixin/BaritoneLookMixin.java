package com.damia.movrand.mixin;

import baritone.behavior.LookBehavior;
import baritone.api.utils.Rotation;
import baritone.api.BaritoneAPI;
import baritone.api.event.events.PlayerUpdateEvent;
import baritone.api.event.events.type.EventState;
import com.damia.movrand.NativeNavigation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = LookBehavior.class, remap = false)
public abstract class BaritoneLookMixin {
	@Unique private Rotation movrand$before;
	@Unique private boolean movrand$interaction;
	@Inject(method = "updateTarget", at = @At("HEAD"))
	private void movrand$intent(Rotation rotation, boolean blockInteract, CallbackInfo ci) {
		movrand$interaction = blockInteract;
	}

	@Inject(method = "onPlayerUpdate", at = @At("HEAD"))
	private void movrand$before(PlayerUpdateEvent event, CallbackInfo ci) {
		var player = BaritoneAPI.getProvider().getPrimaryBaritone().getPlayerContext().player();
		if (NativeNavigation.controlling() && player != null && event.getState() == EventState.PRE)
			movrand$before = new Rotation(player.getYRot(), player.getXRot());
	}
	@Inject(method = "onPlayerUpdate", at = @At("RETURN"))
	private void movrand$camera(PlayerUpdateEvent event, CallbackInfo ci) {
		if (!NativeNavigation.controlling() || movrand$before == null || event.getState() != EventState.PRE) return;
		var player = BaritoneAPI.getProvider().getPrimaryBaritone().getPlayerContext().player();
		if (player == null) return;
		Rotation actual = NativeNavigation.smoothRotation(movrand$before, new Rotation(player.getYRot(), player.getXRot()), movrand$interaction);
		player.setYRot(actual.getYaw()); player.setXRot(actual.getPitch());
		movrand$before = null;
	}
	// Baritone's RotationMoveEvent must keep its planned heading. Replacing it with
	// the lagging view sent jumps/strafe into the wrong direction at high smoothing.
	@Inject(method = "pig", at = @At("HEAD"), cancellable = true)
	private void movrand$noRidingSnap(CallbackInfo ci) {
		if (NativeNavigation.controlling()) ci.cancel();
	}
}
