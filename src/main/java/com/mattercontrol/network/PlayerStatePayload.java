package com.mattercontrol.network;

import com.mattercontrol.MatterControl;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Estado visível de um jogador com poderes (para que outros vejam a aura/órbita/absorção). */
public record PlayerStatePayload(int playerId, boolean active, boolean absorbing, float matterFraction) implements CustomPacketPayload {
    public static final Type<PlayerStatePayload> TYPE = new Type<>(MatterControl.id("player_state"));
    public static final StreamCodec<FriendlyByteBuf, PlayerStatePayload> CODEC = CustomPacketPayload.codec(
            (p, buf) -> { buf.writeVarInt(p.playerId); buf.writeBoolean(p.active); buf.writeBoolean(p.absorbing); buf.writeFloat(p.matterFraction); },
            buf -> new PlayerStatePayload(buf.readVarInt(), buf.readBoolean(), buf.readBoolean(), buf.readFloat()));

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
