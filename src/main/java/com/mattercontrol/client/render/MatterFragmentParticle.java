package com.mattercontrol.client.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.TextureSheetParticle;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;

/**
 * Fragmento de bloco com a textura real do bloco. Pode:
 *  - HOME: voar convergindo para uma entidade (absorção);
 *  - POINT: convergir para um ponto fixo (construção / compactação);
 *  - BURST: explodir para fora com gravidade (impacto / divisão);
 *  - ORBIT: orbitar uma entidade (aura de matéria do jogador).
 */
public class MatterFragmentParticle extends TextureSheetParticle {
    public enum Kind { HOME, POINT, BURST, ORBIT }

    private final Kind kind;
    @Nullable private final Entity target;
    private final Vec3 point;
    private final float uo, vo;
    private final float baseSize;
    private float orbitAngle, orbitRadius, orbitHeight, orbitSpeed;

    public MatterFragmentParticle(ClientLevel level, double x, double y, double z, BlockState state, Kind kind,
                                  @Nullable Entity target, Vec3 point, double vx, double vy, double vz) {
        super(level, x, y, z, 0, 0, 0);
        this.kind = kind;
        this.target = target;
        this.point = point;
        setSprite(Minecraft.getInstance().getBlockRenderer().getBlockModelShaper().getParticleIcon(state));
        this.xd = vx; this.yd = vy; this.zd = vz;
        this.uo = random.nextFloat() * 3f;
        this.vo = random.nextFloat() * 3f;
        this.gravity = kind == Kind.BURST ? 0.8f : 0f;
        this.hasPhysics = kind == Kind.BURST;
        this.friction = kind == Kind.BURST ? 0.96f : 1f;
        this.baseSize = 0.08f + random.nextFloat() * 0.12f;
        this.quadSize = baseSize;
        this.lifetime = switch (kind) {
            case HOME -> 60;
            case POINT -> 30;
            case BURST -> 30 + random.nextInt(30);
            case ORBIT -> 30 + random.nextInt(30);
        };
        if (kind == Kind.ORBIT && target != null) {
            orbitAngle = random.nextFloat() * Mth.TWO_PI;
            orbitRadius = 0.9f + random.nextFloat() * 0.9f;
            orbitHeight = random.nextFloat() * target.getBbHeight();
            orbitSpeed = (0.08f + random.nextFloat() * 0.08f) * (random.nextBoolean() ? 1 : -1);
        }
        // matéria brilha levemente em roxo
        float tint = 0.85f + random.nextFloat() * 0.15f;
        this.rCol = tint; this.gCol = tint * 0.9f; this.bCol = 1f;
    }

    @Override
    public void tick() {
        xo = x; yo = y; zo = z;
        if (age++ >= lifetime) { remove(); return; }
        switch (kind) {
            case HOME -> {
                if (target == null || !target.isAlive()) { remove(); return; }
                Vec3 to = target.position().add(0, target.getBbHeight() * 0.6, 0).subtract(x, y, z);
                double d = to.length();
                if (d < 0.6) { remove(); return; }
                Vec3 acc = to.scale(0.09 / Math.max(d, 1) * (1 + age * 0.08));
                xd = (xd + acc.x * d) * 0.86; yd = (yd + acc.y * d) * 0.86; zd = (zd + acc.z * d) * 0.86;
                quadSize = baseSize * (float) Math.min(1.0, d / 3.0 + 0.3);
                move(xd, yd, zd);
            }
            case POINT -> {
                Vec3 to = point.subtract(x, y, z);
                double d = to.length();
                if (d < 0.25) { remove(); return; }
                xd = xd * 0.8 + to.x * 0.12; yd = yd * 0.8 + to.y * 0.12; zd = zd * 0.8 + to.z * 0.12;
                move(xd, yd, zd);
            }
            case BURST -> {
                yd -= 0.04 * gravity;
                move(xd, yd, zd);
                xd *= friction; yd *= 0.98; zd *= friction;
                if (onGround) { xd *= 0.7; zd *= 0.7; }
                quadSize = baseSize * (1f - (float) age / lifetime * 0.6f);
            }
            case ORBIT -> {
                if (target == null || !target.isAlive()) { remove(); return; }
                orbitAngle += orbitSpeed;
                double tx = target.getX() + Mth.cos(orbitAngle) * orbitRadius;
                double tz = target.getZ() + Mth.sin(orbitAngle) * orbitRadius;
                double ty = target.getY() + orbitHeight + Mth.sin(age * 0.15f) * 0.15;
                setPos(Mth.lerp(0.5, x, tx), Mth.lerp(0.5, y, ty), Mth.lerp(0.5, z, tz));
                float life = (float) age / lifetime;
                quadSize = baseSize * 0.7f * (life < 0.2f ? life / 0.2f : life > 0.8f ? (1 - life) / 0.2f : 1f);
            }
        }
    }

    @Override
    public ParticleRenderType getRenderType() {
        return ParticleRenderType.TERRAIN_SHEET;
    }

    @Override protected float getU0() { return sprite.getU((uo + 1f) / 4f); }
    @Override protected float getU1() { return sprite.getU(uo / 4f); }
    @Override protected float getV0() { return sprite.getV(vo / 4f); }
    @Override protected float getV1() { return sprite.getV((vo + 1f) / 4f); }

    @Override
    public int getLightColor(float pt) {
        return Math.max(super.getLightColor(pt), 0x00A000A0);
    }
}
