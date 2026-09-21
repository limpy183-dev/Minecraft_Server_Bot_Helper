package com.damia.movrand;

import baritone.api.BaritoneAPI;
import baritone.api.event.events.PacketEvent;
import baritone.api.event.events.RotationMoveEvent;
import baritone.api.event.events.type.EventState;
import baritone.api.event.listener.AbstractGameEventListener;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;

/** Observe actual physics and outgoing packets without changing either. */
final class NavigationRotationCheck implements AbstractGameEventListener {
    private int movementTick = -1;
    private float movementYaw;
    int samples;
    String failure;
    boolean enabled;

    @Override public void onPlayerRotationMove(RotationMoveEvent event) {
        var player = BaritoneAPI.getProvider().getPrimaryBaritone().getPlayerContext().player();
        if (!enabled || !NativeNavigation.controlling() || player == null) return;
        movementTick = player.tickCount;
        movementYaw = event.getYaw();
    }

    @Override public void onSendPacket(PacketEvent event) {
        var player = BaritoneAPI.getProvider().getPrimaryBaritone().getPlayerContext().player();
        if (!enabled || !NativeNavigation.controlling() || player == null
                || player.tickCount != movementTick || event.getState() != EventState.PRE
                || !(event.getPacket() instanceof ServerboundMovePlayerPacket packet)) return;
        double difference = Math.abs(Human.wrap(packet.getYRot(player.getYRot()) - movementYaw));
        samples++;
        // Allow one mouse-quantization step at maximum sensitivity (0.6144 degrees).
        if (difference > 0.62 && failure == null)
            failure = "Movement yaw and outgoing yaw disagree by " + difference + " degrees";
    }

    void verify() {
        if (failure != null) throw new AssertionError(failure);
    }
}
