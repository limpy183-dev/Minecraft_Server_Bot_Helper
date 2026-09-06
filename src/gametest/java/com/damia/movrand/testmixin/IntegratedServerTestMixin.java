package com.damia.movrand.testmixin;

import net.minecraft.client.server.IntegratedServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Test-only compatibility for 26.2's blocking player cleanup before the server halt. */
@Mixin(IntegratedServer.class)
public abstract class IntegratedServerTestMixin {
	@Redirect(method = "halt", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/server/IntegratedServer;executeBlocking(Ljava/lang/Runnable;)V"))
	private void movrand$queueCleanup(IntegratedServer server, Runnable cleanup) {
		// Fabric parks the server at a tick barrier. Blocking the render thread here prevents
		// its disconnect busy-wait hook from advancing that barrier. Queue cleanup instead;
		// the normal server shutdown still removes and saves all players.
		server.execute(cleanup);
	}
}
