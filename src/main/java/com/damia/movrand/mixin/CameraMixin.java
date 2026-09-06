package com.damia.movrand.mixin;

import com.damia.movrand.CameraSmoothing;
import net.minecraft.client.Camera;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(Camera.class)
public abstract class CameraMixin {
	@Redirect(method = "alignWithEntity", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/world/entity/Entity;getViewYRot(F)F"))
	private float movrand$yaw(Entity entity, float partialTick) {
		return CameraSmoothing.view(entity, partialTick, true);
	}

	@Redirect(method = "alignWithEntity", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/world/entity/Entity;getViewXRot(F)F"))
	private float movrand$pitch(Entity entity, float partialTick) {
		return CameraSmoothing.view(entity, partialTick, false);
	}
}
