package com.mattercontrol.client;

import com.mattercontrol.client.render.MatterFragmentParticle;
import com.mattercontrol.client.render.MatterFragmentParticle.Kind;
import com.mattercontrol.config.MatterConfig;
import com.mattercontrol.entity.MatterConstructEntity;
import com.mattercontrol.entity.MatterHandEntity;
import com.mattercontrol.entity.Motion;
import com.mattercontrol.network.FxPayload;
import com.mattercontrol.registry.ModRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;

import java.util.Map;

/** Todos os efeitos visuais do mod (somente cliente). */
public final class ClientFx {
    private ClientFx() {}

    private static double mult() {
        return MatterConfig.PARTICLE_MULTIPLIER.get();
    }

    private static int count(double base) {
        double v = base * mult();
        int n = (int) v;
        if (Math.random() < v - n) n++;
        return n;
    }

    private static void frag(ClientLevel level, Vec3 at, BlockState st, Kind kind, Entity target, Vec3 point, Vec3 vel) {
        Minecraft.getInstance().particleEngine.add(new MatterFragmentParticle(level, at.x, at.y, at.z, st, kind, target, point, vel.x, vel.y, vel.z));
    }

    private static BlockState matterState() {
        return ModRegistry.MATTER_BLOCK.get().defaultBlockState();
    }

    // ------------------------------------------------------------------ eventos do servidor

    public static void handle(FxPayload p) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) return;
        RandomSource r = level.random;
        Vec3 pos = new Vec3(p.x(), p.y(), p.z());
        Entity ent = p.entityId() >= 0 ? level.getEntity(p.entityId()) : null;
        int[] d = p.data();

        switch (p.fx()) {
            case FxPayload.ABSORB, FxPayload.DISSOLVE -> {
                // blocos desmontados: fragmentos individuais convergem para o jogador
                for (int i = 0; i + 3 < d.length; i += 4) {
                    BlockState st = Block.stateById(d[i + 3]);
                    Vec3 c = new Vec3(d[i] + 0.5, d[i + 1] + 0.5, d[i + 2] + 0.5);
                    int n = count(p.fx() == FxPayload.ABSORB ? 6 : 4);
                    for (int k = 0; k < n; k++) {
                        Vec3 at = c.add(r.nextGaussian() * 0.3, r.nextGaussian() * 0.3, r.nextGaussian() * 0.3);
                        Vec3 v = new Vec3(r.nextGaussian() * 0.12, 0.1 + r.nextFloat() * 0.15, r.nextGaussian() * 0.12);
                        frag(level, at, st, Kind.HOME, ent, Vec3.ZERO, v);
                    }
                    if (p.fx() == FxPayload.ABSORB && r.nextFloat() < 0.35 * mult()) {
                        level.addParticle(ParticleTypes.REVERSE_PORTAL, c.x, c.y, c.z, 0, 0.05, 0);
                    }
                }
                if (ent == mc.player) ClientState.hudFlash = Math.max(ClientState.hudFlash, 6);
            }
            case FxPayload.BUILD -> {
                for (int i = 0; i + 3 < d.length; i += 4) {
                    BlockState st = Block.stateById(d[i + 3]);
                    Vec3 c = new Vec3(d[i] + 0.5, d[i + 1] + 0.5, d[i + 2] + 0.5);
                    Vec3 from = ent != null ? ent.position().add(0, ent.getBbHeight() * 0.7, 0) : c.add(0, -2, 0);
                    if (from.distanceTo(c) > 40) from = c.add(r.nextGaussian() * 3, -3, r.nextGaussian() * 3);
                    int n = count(2);
                    for (int k = 0; k < n; k++) {
                        Vec3 at = from.lerp(c, r.nextFloat() * 0.6).add(r.nextGaussian() * 0.4, r.nextGaussian() * 0.4, r.nextGaussian() * 0.4);
                        frag(level, at, st, Kind.POINT, null, c, Vec3.ZERO);
                    }
                    if (r.nextFloat() < 0.4 * mult()) level.addParticle(ParticleTypes.END_ROD, c.x, c.y, c.z, r.nextGaussian() * 0.02, 0.03, r.nextGaussian() * 0.02);
                }
            }
            case FxPayload.LIFT -> {
                for (int i = 0; i + 3 < d.length; i += 4) {
                    BlockState st = Block.stateById(d[i + 3]);
                    BlockPos bp = new BlockPos(d[i], d[i + 1], d[i + 2]);
                    if (r.nextFloat() < 0.5) level.addDestroyBlockEffect(bp, st);
                    for (int k = 0; k < count(1); k++) {
                        level.addParticle(ParticleTypes.CLOUD, bp.getX() + r.nextFloat(), bp.getY() + 0.2, bp.getZ() + r.nextFloat(), 0, 0.05, 0);
                    }
                }
                shakeNear(pos, Math.min(3f, p.magnitude() / 200f + 0.3f));
            }
            case FxPayload.IMPACT -> impact(level, pos, p.magnitude(), ent);
            case FxPayload.CRUSH -> {
                for (int k = 0; k < count(20); k++) {
                    level.addParticle(ParticleTypes.CRIT, pos.x, pos.y, pos.z, r.nextGaussian() * 0.4, r.nextGaussian() * 0.4, r.nextGaussian() * 0.4);
                }
                for (int k = 0; k < count(8); k++) {
                    frag(level, pos, matterState(), Kind.BURST, null, Vec3.ZERO, new Vec3(r.nextGaussian() * 0.25, r.nextFloat() * 0.3, r.nextGaussian() * 0.25));
                }
                shakeNear(pos, 0.6f);
            }
            case FxPayload.GRAB -> {
                for (int k = 0; k < count(30); k++) {
                    double a = r.nextDouble() * Math.PI * 2, rad = p.magnitude() * 1.5;
                    Vec3 at = pos.add(Math.cos(a) * rad, r.nextGaussian() * rad * 0.5, Math.sin(a) * rad);
                    frag(level, at, matterState(), Kind.POINT, null, pos, Vec3.ZERO);
                }
            }
            case FxPayload.COMPACT -> {
                float rad = Math.max(1.5f, p.magnitude() * 1.4f);
                for (int k = 0; k < count(10 + rad * 2); k++) {
                    Vec3 dir = new Vec3(r.nextGaussian(), r.nextGaussian(), r.nextGaussian()).normalize();
                    Vec3 at = pos.add(dir.scale(rad));
                    Vec3 v = dir.scale(-0.12 * rad / 3);
                    level.addParticle(ParticleTypes.REVERSE_PORTAL, at.x, at.y, at.z, v.x, v.y, v.z);
                    if (k % 3 == 0) frag(level, at, matterState(), Kind.POINT, null, pos, Vec3.ZERO);
                }
            }
            case FxPayload.SPLIT -> {
                for (int k = 0; k < count(25); k++) {
                    Vec3 v = new Vec3(r.nextGaussian(), r.nextGaussian(), r.nextGaussian()).normalize().scale(0.3);
                    level.addParticle(ParticleTypes.ELECTRIC_SPARK, pos.x, pos.y, pos.z, v.x, v.y, v.z);
                    frag(level, pos, matterState(), Kind.BURST, null, Vec3.ZERO, v);
                }
            }
            case FxPayload.IMPRISON -> {
                BlockState ground = level.getBlockState(BlockPos.containing(pos).below(2));
                if (ground.isAir()) ground = matterState();
                for (int k = 0; k < count(40); k++) {
                    double a = r.nextDouble() * Math.PI * 2, rad = 1 + r.nextDouble() * 2;
                    level.addParticle(new BlockParticleOption(ParticleTypes.BLOCK, ground),
                            pos.x + Math.cos(a) * rad, pos.y - 1, pos.z + Math.sin(a) * rad, 0, 0.3 + r.nextDouble() * 0.3, 0);
                }
                shakeNear(pos, 1f);
            }
            default -> {}
        }
    }

    /** Onda de choque: anel expandindo + poeira do chão + tremor de câmera. */
    private static void impact(ClientLevel level, Vec3 pos, float mag, Entity ent) {
        RandomSource r = level.random;
        BlockState ground = level.getBlockState(BlockPos.containing(pos).below());
        if (ground.isAir()) ground = level.getBlockState(BlockPos.containing(pos).below(2));
        BlockState chunk = ent instanceof MatterConstructEntity c && c.blockCount() > 0 ? c.shape().states[0] : matterState();
        int ring = count(Math.min(160, 24 + mag * 6));
        double speed = 0.25 + Math.min(1.2, mag * 0.05);
        for (int k = 0; k < ring; k++) {
            double a = Math.PI * 2 * k / Math.max(1, ring);
            double vx = Math.cos(a) * speed, vz = Math.sin(a) * speed;
            level.addParticle(ParticleTypes.CLOUD, pos.x, pos.y, pos.z, vx, 0.02, vz);
            if (!ground.isAir() && k % 2 == 0) {
                level.addParticle(new BlockParticleOption(ParticleTypes.BLOCK, ground), pos.x + vx * 2, pos.y, pos.z + vz * 2, vx, 0.4, vz);
            }
        }
        for (int k = 0; k < count(Math.min(60, 8 + mag * 2)); k++) {
            Vec3 v = new Vec3(r.nextGaussian() * 0.4, 0.2 + r.nextFloat() * 0.5, r.nextGaussian() * 0.4).scale(0.5 + mag * 0.03);
            frag(level, pos, chunk, Kind.BURST, null, Vec3.ZERO, v);
        }
        if (mag > 6) level.addParticle(ParticleTypes.EXPLOSION_EMITTER, pos.x, pos.y, pos.z, 0, 0, 0);
        else level.addParticle(ParticleTypes.EXPLOSION, pos.x, pos.y, pos.z, 0, 0, 0);
        shakeNear(pos, Math.min(4f, 0.5f + mag * 0.12f));
    }

    private static void shakeNear(Vec3 pos, float amount) {
        Player p = Minecraft.getInstance().player;
        if (p == null) return;
        double d = p.position().distanceTo(pos);
        float s = (float) (amount * Math.max(0, 1 - d / 40));
        ClientState.shake = Math.max(ClientState.shake, s);
    }

    // ------------------------------------------------------------------ efeitos contínuos

    public static void constructTick(MatterConstructEntity c) {
        ClientLevel level = (ClientLevel) c.level();
        RandomSource r = level.random;
        int n = c.blockCount();
        if (n == 0) return;
        Motion m = c.motion();
        double rad = c.boundRadius();

        // partículas flutuando ao redor (proporcional ao tamanho)
        if (r.nextFloat() < Math.min(1.0, 0.1 + Math.sqrt(n) * 0.03) * mult()) {
            Vec3 dir = new Vec3(r.nextGaussian(), r.nextGaussian(), r.nextGaussian()).normalize();
            Vec3 at = c.position().add(dir.scale(rad * (0.7 + r.nextFloat() * 0.5)));
            level.addParticle(ParticleTypes.REVERSE_PORTAL, at.x, at.y, at.z, 0, 0.01, 0);
        }

        // distorção: feixe de energia entre o dono e a matéria controlada
        if (m == Motion.HELD || m == Motion.ORBIT) {
            Entity owner = c.ownerEntityId() >= 0 ? level.getEntity(c.ownerEntityId()) : null;
            if (owner != null && owner.distanceTo(c) < 128) {
                Vec3 from = owner.position().add(0, owner.getBbHeight() * 0.7, 0);
                Vec3 to = c.position();
                int steps = count(m == Motion.HELD ? 4 : 1);
                for (int k = 0; k < steps; k++) {
                    double t = r.nextDouble();
                    Vec3 at = from.lerp(to, t).add(r.nextGaussian() * 0.15, r.nextGaussian() * 0.15, r.nextGaussian() * 0.15);
                    level.addParticle(ParticleTypes.WITCH, at.x, at.y, at.z, 0, 0, 0);
                }
                if (m == Motion.HELD && r.nextFloat() < 0.5 * mult()) {
                    double a = c.tickCount * 0.4;
                    Vec3 at = to.add(Math.cos(a) * rad, Math.sin(a * 0.7) * rad * 0.5, Math.sin(a) * rad);
                    level.addParticle(ParticleTypes.PORTAL, at.x, at.y, at.z, (to.x - at.x) * 0.3, (to.y - at.y) * 0.3, (to.z - at.z) * 0.3);
                }
            }
        }

        // rastro em voo
        if (m == Motion.THROWN || m == Motion.HOMING) {
            for (int k = 0; k < count(3); k++) {
                Vec3 at = c.position().add(r.nextGaussian() * rad * 0.4, r.nextGaussian() * rad * 0.4, r.nextGaussian() * rad * 0.4);
                level.addParticle(m == Motion.HOMING ? ParticleTypes.SOUL_FIRE_FLAME : ParticleTypes.SMOKE, at.x, at.y, at.z, 0, 0, 0);
            }
        }

        // compactando: partículas sugadas para o centro
        if (c.scale() < c.prevScale - 1e-4f) {
            for (int k = 0; k < count(4); k++) {
                Vec3 dir = new Vec3(r.nextGaussian(), r.nextGaussian(), r.nextGaussian()).normalize();
                Vec3 at = c.position().add(dir.scale(rad * 1.6));
                Vec3 v = dir.scale(-rad * 0.12);
                level.addParticle(ParticleTypes.REVERSE_PORTAL, at.x, at.y, at.z, v.x, v.y, v.z);
            }
        }

        // prisão: correntes de energia no prisioneiro
        if (c.captiveEntityId() >= 0 && r.nextFloat() < 0.4 * mult()) {
            Entity cap = level.getEntity(c.captiveEntityId());
            if (cap != null) level.addParticle(ParticleTypes.ENCHANT, cap.getX(), cap.getY() + cap.getBbHeight() * r.nextFloat(), cap.getZ(), r.nextGaussian(), 0.2, r.nextGaussian());
        }
    }

    public static void handTick(MatterHandEntity h) {
        ClientLevel level = (ClientLevel) h.level();
        RandomSource r = level.random;
        if (r.nextFloat() < 0.5 * mult()) {
            double s = h.handScale() * 2;
            level.addParticle(ParticleTypes.REVERSE_PORTAL, h.getX() + r.nextGaussian() * s * 0.6, h.getY() + r.nextGaussian() * s * 0.6,
                    h.getZ() + r.nextGaussian() * s * 0.6, 0, 0.02, 0);
        }
        if (h.crushing() && r.nextFloat() < 0.6) {
            Vec3 gp = h.grabPoint();
            level.addParticle(ParticleTypes.CRIT, gp.x, gp.y, gp.z, r.nextGaussian() * 0.3, r.nextGaussian() * 0.3, r.nextGaussian() * 0.3);
        }
    }

    /** Aura de pedaços de matéria orbitando cada jogador com poderes ativos (visível para todos). */
    public static void playersTick(Minecraft mc) {
        ClientLevel level = mc.level;
        if (level == null) return;
        RandomSource r = level.random;
        if (mc.player != null) {
            ClientState.others.put(mc.player.getId(), new ClientState.OtherState(ClientState.active, ClientState.active && Keys.ABSORB.isDown(),
                    ClientState.infinite ? 1f : ClientState.matter / ClientState.maxMatter));
        }
        for (Map.Entry<Integer, ClientState.OtherState> en : ClientState.others.entrySet()) {
            ClientState.OtherState st = en.getValue();
            if (!st.active()) continue;
            Entity p = level.getEntity(en.getKey());
            if (p == null) continue;
            boolean self = p == mc.player && mc.options.getCameraType().isFirstPerson();
            double chance = (0.15 + st.fraction() * 0.85) * (self ? 0.35 : 1.0) * mult();
            for (int k = 0; k < (int) chance + (r.nextDouble() < chance % 1 ? 1 : 0); k++) {
                BlockState frag = r.nextFloat() < 0.7 ? matterState() : level.getBlockState(p.blockPosition().below());
                if (frag.isAir()) frag = matterState();
                Vec3 at = p.position().add(r.nextGaussian(), p.getBbHeight() * r.nextFloat(), r.nextGaussian());
                frag(level, at, frag, Kind.ORBIT, p, Vec3.ZERO, Vec3.ZERO);
            }
            if (st.absorbing() && r.nextFloat() < 0.6 * mult()) {
                double a = r.nextDouble() * Math.PI * 2;
                level.addParticle(ParticleTypes.REVERSE_PORTAL, p.getX() + Math.cos(a) * 1.5, p.getY() + 1, p.getZ() + Math.sin(a) * 1.5,
                        -Math.cos(a) * 0.1, 0, -Math.sin(a) * 0.1);
            }
        }
    }

    /** Usado pela pré-visualização. */
    public static Quaternionf rot(MatterConstructEntity c, float pt) {
        return new Quaternionf().rotationYXZ(Mth.rotLerp(pt, c.prevYaw, c.rotYaw()) * Mth.DEG_TO_RAD,
                Mth.rotLerp(pt, c.prevPitch, c.rotPitch()) * Mth.DEG_TO_RAD, Mth.rotLerp(pt, c.prevRoll, c.rotRoll()) * Mth.DEG_TO_RAD);
    }
}
