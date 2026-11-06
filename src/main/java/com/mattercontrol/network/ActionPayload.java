package com.mattercontrol.network;

import com.mattercontrol.MatterControl;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Ações discretas (cliques e teclas de comando). */
public record ActionPayload(Action action, int mods) implements CustomPacketPayload {

    public enum Action {
        LEFT_CLICK, RIGHT_CLICK, CREATE, THROW, DROP, SPLIT, SPLIT_MANY, MERGE,
        SCATTER, SPREAD, REFORM, RESHAPE, SOLIDIFY, DISSOLVE, HOMING, DESELECT, RELEASE_CAPTIVE
    }

    public static final int MOD_SHIFT = 1, MOD_CTRL = 2;

    public static final Type<ActionPayload> TYPE = new Type<>(MatterControl.id("action"));
    public static final StreamCodec<FriendlyByteBuf, ActionPayload> CODEC = CustomPacketPayload.codec(
            (p, buf) -> { buf.writeEnum(p.action); buf.writeVarInt(p.mods); },
            buf -> new ActionPayload(buf.readEnum(Action.class), buf.readVarInt()));

    public boolean shift() { return (mods & MOD_SHIFT) != 0; }
    public boolean ctrl() { return (mods & MOD_CTRL) != 0; }

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
