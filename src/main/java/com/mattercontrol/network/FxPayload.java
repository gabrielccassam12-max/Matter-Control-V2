package com.mattercontrol.network;

import com.mattercontrol.MatterControl;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Efeitos visuais disparados pelo servidor e vistos por todos por perto.
 * data: para ABSORB/BUILD/LIFT = trios (posição compactada long em 2 ints + id do estado).
 */
public record FxPayload(int fx, double x, double y, double z, float magnitude, int entityId, int[] data) implements CustomPacketPayload {
    public static final int ABSORB = 0, IMPACT = 1, CRUSH = 2, GRAB = 3, BUILD = 4, LIFT = 5, COMPACT = 6, DISSOLVE = 7, SPLIT = 8, IMPRISON = 9;

    public static final Type<FxPayload> TYPE = new Type<>(MatterControl.id("fx"));
    public static final StreamCodec<FriendlyByteBuf, FxPayload> CODEC = CustomPacketPayload.codec(FxPayload::write, FxPayload::read);

    private void write(FriendlyByteBuf buf) {
        buf.writeVarInt(fx);
        buf.writeDouble(x); buf.writeDouble(y); buf.writeDouble(z);
        buf.writeFloat(magnitude);
        buf.writeVarInt(entityId);
        buf.writeVarIntArray(data);
    }

    private static FxPayload read(FriendlyByteBuf buf) {
        return new FxPayload(buf.readVarInt(), buf.readDouble(), buf.readDouble(), buf.readDouble(),
                buf.readFloat(), buf.readVarInt(), buf.readVarIntArray());
    }

    public static void send(ServerLevel level, Vec3 pos, int fx, float magnitude, int entityId) {
        send(level, pos, fx, magnitude, entityId, new int[0]);
    }

    public static void send(ServerLevel level, Vec3 pos, int fx, float magnitude, int entityId, int[] data) {
        PacketDistributor.sendToPlayersNear(level, null, pos.x, pos.y, pos.z, 128,
                new FxPayload(fx, pos.x, pos.y, pos.z, magnitude, entityId, data));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
