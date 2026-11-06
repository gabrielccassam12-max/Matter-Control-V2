package com.mattercontrol.network;

import com.mattercontrol.MatterControl;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.phys.Vec3;

/**
 * Estado contínuo dos controles, enviado a cada tick enquanto os poderes estão ativos.
 * O servidor usa a mira exata do cliente (origem + direção) para que tudo siga o mouse.
 */
public record InputPayload(boolean active, int flags, Vec3 origin, Vec3 dir,
                           float distanceDelta, float rotYaw, float rotPitch, float rotRoll,
                           int mode, int primary, int secondary, int variant, int absorbRadius) implements CustomPacketPayload {

    public static final int LEFT = 1, RIGHT = 2, FAST = 4, PRECISE = 8, ABSORB = 16, COMPACT = 32, EXPAND = 64;

    public static final Type<InputPayload> TYPE = new Type<>(MatterControl.id("input"));
    public static final StreamCodec<FriendlyByteBuf, InputPayload> CODEC = CustomPacketPayload.codec(InputPayload::write, InputPayload::read);

    public boolean has(int flag) { return (flags & flag) != 0; }

    private void write(FriendlyByteBuf buf) {
        buf.writeBoolean(active);
        buf.writeVarInt(flags);
        buf.writeDouble(origin.x); buf.writeDouble(origin.y); buf.writeDouble(origin.z);
        buf.writeFloat((float) dir.x); buf.writeFloat((float) dir.y); buf.writeFloat((float) dir.z);
        buf.writeFloat(distanceDelta);
        buf.writeFloat(rotYaw); buf.writeFloat(rotPitch); buf.writeFloat(rotRoll);
        buf.writeVarInt(mode); buf.writeVarInt(primary); buf.writeVarInt(secondary); buf.writeVarInt(variant);
        buf.writeVarInt(absorbRadius);
    }

    private static InputPayload read(FriendlyByteBuf buf) {
        return new InputPayload(buf.readBoolean(), buf.readVarInt(),
                new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble()),
                new Vec3(buf.readFloat(), buf.readFloat(), buf.readFloat()),
                buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat(),
                buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
