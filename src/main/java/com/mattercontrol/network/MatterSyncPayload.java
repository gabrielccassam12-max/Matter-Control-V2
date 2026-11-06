package com.mattercontrol.network;

import com.mattercontrol.MatterControl;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record MatterSyncPayload(float matter, float max, boolean infinite) implements CustomPacketPayload {
    public static final Type<MatterSyncPayload> TYPE = new Type<>(MatterControl.id("matter_sync"));
    public static final StreamCodec<FriendlyByteBuf, MatterSyncPayload> CODEC = CustomPacketPayload.codec(
            (p, buf) -> { buf.writeFloat(p.matter); buf.writeFloat(p.max); buf.writeBoolean(p.infinite); },
            buf -> new MatterSyncPayload(buf.readFloat(), buf.readFloat(), buf.readBoolean()));

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
