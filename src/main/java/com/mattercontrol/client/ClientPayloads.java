package com.mattercontrol.client;

import com.mattercontrol.entity.MatterConstructEntity;
import com.mattercontrol.network.FxPayload;
import com.mattercontrol.network.MatterSyncPayload;
import com.mattercontrol.network.PlayerStatePayload;
import com.mattercontrol.network.SelectionPayload;
import com.mattercontrol.network.ShapePayload;
import net.minecraft.client.Minecraft;

/** Recebimento de pacotes do servidor (só carregada no cliente). */
public final class ClientPayloads {
    private ClientPayloads() {}

    public static void matter(MatterSyncPayload p) {
        ClientState.matter = p.matter();
        ClientState.maxMatter = Math.max(1, p.max());
        ClientState.infinite = p.infinite();
    }

    public static void selection(SelectionPayload p) {
        ClientState.selection = p.ids();
    }

    public static void shape(ShapePayload p) {
        var level = Minecraft.getInstance().level;
        if (level != null && level.getEntity(p.entityId()) instanceof MatterConstructEntity c) {
            c.setShapeClient(p.shape());
        }
    }

    public static void fx(FxPayload p) {
        ClientFx.handle(p);
    }

    public static void playerState(PlayerStatePayload p) {
        ClientState.others.put(p.playerId(), new ClientState.OtherState(p.active(), p.absorbing(), p.matterFraction()));
    }
}
