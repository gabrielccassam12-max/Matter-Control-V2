package com.mattercontrol.network;

import com.mattercontrol.MatterControl;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Ids das entidades selecionadas pelo jogador (para destacar no cliente). */
public record SelectionPayload(int[] ids) implements CustomPacketPayload {
    public static final Type<SelectionPayload> TYPE = new Type<>(MatterControl.id("selection"));
    public static final StreamCodec<FriendlyByteBuf, SelectionPayload> CODEC = CustomPacketPayload.codec(
            (p, buf) -> buf.writeVarIntArray(p.ids),
            buf -> new SelectionPayload(buf.readVarIntArray()));

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
