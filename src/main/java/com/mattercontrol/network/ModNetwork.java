package com.mattercontrol.network;

import com.mattercontrol.server.PowerManager;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

public final class ModNetwork {
    private ModNetwork() {}

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar r = event.registrar("1");

        // cliente -> servidor
        r.playToServer(InputPayload.TYPE, InputPayload.CODEC, (p, ctx) -> ctx.enqueueWork(() -> {
            if (ctx.player() instanceof ServerPlayer sp) PowerManager.onInput(sp, p);
        }));
        r.playToServer(ActionPayload.TYPE, ActionPayload.CODEC, (p, ctx) -> ctx.enqueueWork(() -> {
            if (ctx.player() instanceof ServerPlayer sp) PowerManager.onAction(sp, p);
        }));

        // servidor -> cliente (handlers ficam numa classe só carregada no cliente)
        r.playToClient(MatterSyncPayload.TYPE, MatterSyncPayload.CODEC,
                (p, ctx) -> ctx.enqueueWork(() -> com.mattercontrol.client.ClientPayloads.matter(p)));
        r.playToClient(SelectionPayload.TYPE, SelectionPayload.CODEC,
                (p, ctx) -> ctx.enqueueWork(() -> com.mattercontrol.client.ClientPayloads.selection(p)));
        r.playToClient(ShapePayload.TYPE, ShapePayload.CODEC,
                (p, ctx) -> ctx.enqueueWork(() -> com.mattercontrol.client.ClientPayloads.shape(p)));
        r.playToClient(FxPayload.TYPE, FxPayload.CODEC,
                (p, ctx) -> ctx.enqueueWork(() -> com.mattercontrol.client.ClientPayloads.fx(p)));
        r.playToClient(PlayerStatePayload.TYPE, PlayerStatePayload.CODEC,
                (p, ctx) -> ctx.enqueueWork(() -> com.mattercontrol.client.ClientPayloads.playerState(p)));
    }
}
