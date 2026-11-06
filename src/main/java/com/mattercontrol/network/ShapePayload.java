package com.mattercontrol.network;

import com.mattercontrol.MatterControl;
import com.mattercontrol.entity.ConstructShape;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Nova forma de uma estrutura (após compactar, dividir, juntar, remodelar...). */
public record ShapePayload(int entityId, ConstructShape shape) implements CustomPacketPayload {
    public static final Type<ShapePayload> TYPE = new Type<>(MatterControl.id("shape"));
    public static final StreamCodec<FriendlyByteBuf, ShapePayload> CODEC = CustomPacketPayload.codec(
            (p, buf) -> { buf.writeVarInt(p.entityId); p.shape.write(buf); },
            buf -> new ShapePayload(buf.readVarInt(), ConstructShape.read(buf)));

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
