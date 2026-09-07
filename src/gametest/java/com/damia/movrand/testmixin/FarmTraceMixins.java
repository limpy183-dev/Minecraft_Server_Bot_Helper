package com.damia.movrand.testmixin;

import com.damia.movrand.*;
import net.minecraft.core.BlockPos;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import java.util.List;

/** Observers only: capture the production decision, without rerunning its random choice. */
public final class FarmTraceMixins {
    @Mixin(BaseDestroyer.class)
    public static abstract class Choices {
        @Inject(method="chooseCandidate",at=@At("RETURN"))
        private static void basalt$choice(List<?> candidates, Config cfg, CallbackInfoReturnable<BlockPos> cir) {
            BasaltFarmGameTest.decision(candidates,cir.getReturnValue());
        }
        @Inject(method="writeOff",at=@At("HEAD"))
        private void basalt$defer(BlockPos block, LocalPlayer player, CallbackInfo ci) {
            BasaltFarmGameTest.failure("retry",((BaseDestroyer)(Object)this).detail,block);
        }
    }
    @Mixin(NativeNavigation.class)
    public static abstract class Stalls {
        @Inject(method="checkProgress",at=@At("RETURN"))
        private void basalt$stall(PathMove.Ctx ctx, CallbackInfoReturnable<String> cir) {
            if(cir.getReturnValue()!=null) BasaltFarmGameTest.failure("stuck",cir.getReturnValue(),ctx.player().blockPosition());
        }
    }
}
