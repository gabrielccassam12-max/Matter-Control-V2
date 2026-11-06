package com.mattercontrol.server;

import com.mattercontrol.config.MatterConfig;
import com.mattercontrol.core.PlayerMatter;
import com.mattercontrol.entity.MatterConstructEntity;
import com.mattercontrol.entity.MatterHandEntity;
import com.mattercontrol.network.ActionPayload;
import com.mattercontrol.network.InputPayload;
import com.mattercontrol.network.PlayerStatePayload;
import com.mattercontrol.network.SelectionPayload;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Ponto central do servidor: recebe entradas, mantém sessões e distribui para os handlers. */
public final class PowerManager {
    private PowerManager() {}

    private static final Map<UUID, PowerSession> SESSIONS = new HashMap<>();

    public static PowerSession session(ServerPlayer p) {
        return SESSIONS.computeIfAbsent(p.getUUID(), k -> new PowerSession());
    }

    // ------------------------------------------------------------------ rede

    public static void onInput(ServerPlayer sp, InputPayload in) {
        PowerSession s = session(sp);
        boolean wasActive = s.active;
        s.input = in;
        s.active = in.active();
        s.lastInputTime = sp.level().getGameTime();
        if (in.distanceDelta() != 0) {
            s.holdDistance = Mth.clamp(s.holdDistance + in.distanceDelta(), 1.5, MatterConfig.MAX_REACH.get());
        }
        if (in.rotYaw() != 0 || in.rotPitch() != 0 || in.rotRoll() != 0) {
            for (Entity e : selected(sp, s)) {
                if (e instanceof MatterConstructEntity c) c.addRotation(in.rotYaw(), in.rotPitch(), in.rotRoll());
            }
        }
        if (wasActive && !s.active) deactivate(sp, s);
    }

    public static void onAction(ServerPlayer sp, ActionPayload a) {
        PowerSession s = session(sp);
        if (!s.active || sp.isSpectator()) return;
        ActionHandler.handle(sp, s, a);
        PlayerMatter.sync(sp);
    }

    // ------------------------------------------------------------------ eventos

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer sp)) return;
        PowerSession s = SESSIONS.get(sp.getUUID());
        if (s == null) return;

        // entrada "velha" (cliente travou / perdeu pacotes): solta tudo por segurança
        if (s.input != null && sp.level().getGameTime() - s.lastInputTime > 20) {
            s.input = new InputPayload(s.active, 0, s.input.origin(), s.input.dir(), 0, 0, 0, 0,
                    s.input.mode(), s.input.primary(), s.input.secondary(), s.input.variant(), s.input.absorbRadius());
        }

        s.runDeferred();
        if (s.active && s.input != null && sp.isAlive() && !sp.isSpectator()) {
            AbsorbHandler.tick(sp, s);
            BuildHandler.tick(sp, s);
            ManipulationHandler.tick(sp, s);
            s.prevFlags = s.flags();
        }

        float m = PlayerMatter.get(sp);
        if (Math.abs(m - s.lastSyncedMatter) > 0.01f) {
            s.lastSyncedMatter = m;
            PlayerMatter.sync(sp);
        }
        broadcastState(sp, s, false);
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent e) {
        if (e.getEntity() instanceof ServerPlayer sp) PlayerMatter.sync(sp);
    }

    @SubscribeEvent
    public static void onRespawn(PlayerEvent.PlayerRespawnEvent e) {
        if (e.getEntity() instanceof ServerPlayer sp) {
            PowerSession s = SESSIONS.get(sp.getUUID());
            if (s != null) { s.selection.clear(); s.absorb = null; s.build = null; }
            PlayerMatter.sync(sp);
        }
    }

    @SubscribeEvent
    public static void onDimension(PlayerEvent.PlayerChangedDimensionEvent e) {
        if (e.getEntity() instanceof ServerPlayer sp) {
            PowerSession s = SESSIONS.get(sp.getUUID());
            if (s != null) { s.selection.clear(); s.absorb = null; s.build = null; syncSelection(sp, s); }
            PlayerMatter.sync(sp);
        }
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent e) {
        if (e.getEntity() instanceof ServerPlayer sp) {
            PowerSession s = SESSIONS.remove(sp.getUUID());
            if (s != null) deactivate(sp, s);
        }
    }

    private static void deactivate(ServerPlayer sp, PowerSession s) {
        AbsorbHandler.stop(sp, s);
        s.build = null;
        s.moving = false;
        for (Entity e : selected(sp, s)) {
            if (e instanceof MatterConstructEntity c && c.motion() == com.mattercontrol.entity.Motion.HELD) c.hover();
        }
        broadcastState(sp, s, true);
    }

    private static void broadcastState(ServerPlayer sp, PowerSession s, boolean force) {
        boolean absorbing = s.absorb != null;
        float frac = PlayerMatter.get(sp) / PlayerMatter.max();
        if (force || s.active != s.lastStateActive || absorbing != s.lastStateAbsorbing || Math.abs(frac - s.lastStateFraction) > 0.04f) {
            s.lastStateActive = s.active;
            s.lastStateAbsorbing = absorbing;
            s.lastStateFraction = frac;
            PacketDistributor.sendToPlayersTrackingEntityAndSelf(sp, new PlayerStatePayload(sp.getId(), s.active, absorbing, frac));
        }
    }

    // ------------------------------------------------------------------ mira

    /** Origem da mira (do cliente, validada contra a posição real). */
    public static Vec3 origin(ServerPlayer sp, PowerSession s) {
        Vec3 eye = sp.getEyePosition();
        if (s.input == null) return eye;
        Vec3 o = s.input.origin();
        return o.distanceToSqr(eye) < 36 ? o : eye; // terceira pessoa / lag aceitos até 6 blocos
    }

    public static Vec3 dir(ServerPlayer sp, PowerSession s) {
        if (s.input == null || s.input.dir().lengthSqr() < 0.5) return sp.getLookAngle();
        return s.input.dir().normalize();
    }

    public static double reach() {
        return MatterConfig.MAX_REACH.get();
    }

    /** Ponto controlado pela roda do mouse: origem + direção * distância. */
    public static Vec3 holdPoint(ServerPlayer sp, PowerSession s) {
        return origin(sp, s).add(dir(sp, s).scale(s.holdDistance));
    }

    public static BlockHitResult clipBlock(ServerPlayer sp, PowerSession s, double range) {
        Vec3 o = origin(sp, s);
        Vec3 end = o.add(dir(sp, s).scale(range));
        return sp.level().clip(new ClipContext(o, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, sp));
    }

    /** Ponto de mira: bloco atingido ou ponto no ar na distância controlada. */
    public static Vec3 aimPoint(ServerPlayer sp, PowerSession s) {
        BlockHitResult hit = clipBlock(sp, s, s.holdDistance);
        if (hit.getType() == HitResult.Type.BLOCK) return hit.getLocation();
        return holdPoint(sp, s);
    }

    /** Estrutura/mão do próprio jogador sob o cursor, com teste preciso bloco a bloco. */
    @Nullable
    public static Entity pickOwned(ServerPlayer sp, PowerSession s) {
        Vec3 o = origin(sp, s);
        Vec3 d = dir(sp, s);
        double range = reach();
        Vec3 end = o.add(d.scale(range));
        AABB area = new AABB(o, end).inflate(8);
        Entity best = null;
        double bestT = Double.MAX_VALUE;
        for (Entity e : sp.level().getEntities(sp, area, x -> x instanceof MatterConstructEntity || x instanceof MatterHandEntity)) {
            if (e instanceof MatterHandEntity h) {
                if (!h.isOwnedBy(sp)) continue;
                var clip = h.getBoundingBox().deflate(h.handScale() * 0.6).clip(o, end);
                if (clip.isPresent()) {
                    double t = clip.get().distanceTo(o);
                    if (t < bestT) { bestT = t; best = h; }
                }
                continue;
            }
            MatterConstructEntity c = (MatterConstructEntity) e;
            if (!c.isOwnedBy(sp) || c.getBoundingBox().clip(o, end).isEmpty()) continue;
            double t = rayConstruct(c, o, d, range);
            if (t < bestT) { bestT = t; best = c; }
        }
        return best;
    }

    private static double rayConstruct(MatterConstructEntity c, Vec3 o, Vec3 d, double range) {
        Quaternionf q = c.rotation();
        float sc = c.scale(), sp = c.spread();
        double r = Math.max(0.6, MatterConstructEntity.blockSize(sc, sp) * 0.75);
        double best = Double.MAX_VALUE;
        int[] idx = c.shape().surface();
        for (int i : idx) {
            Vector3f v = MatterConstructEntity.localOf(i, c.shape().x[i], c.shape().y[i], c.shape().z[i], sc, sp);
            q.transform(v);
            double px = c.getX() + v.x - o.x, py = c.getY() + v.y - o.y, pz = c.getZ() + v.z - o.z;
            double t = px * d.x + py * d.y + pz * d.z;
            if (t < 0 || t > range || t >= best) continue;
            double cx = px - d.x * t, cy = py - d.y * t, cz = pz - d.z * t;
            if (cx * cx + cy * cy + cz * cz < r * r) best = t;
        }
        return best;
    }

    @Nullable
    public static LivingEntity pickLiving(ServerPlayer sp, PowerSession s) {
        Vec3 o = origin(sp, s);
        Vec3 end = o.add(dir(sp, s).scale(reach()));
        BlockHitResult wall = clipBlock(sp, s, reach());
        if (wall.getType() == HitResult.Type.BLOCK) end = wall.getLocation();
        EntityHitResult hit = ProjectileUtil.getEntityHitResult(sp.level(), sp, o, end, new AABB(o, end).inflate(2),
                e -> e instanceof LivingEntity le && le.isAlive() && !e.isSpectator()
                        && (!(e instanceof net.minecraft.world.entity.player.Player) || MatterConfig.AFFECT_PLAYERS.get()), 0.6f);
        return hit != null && hit.getEntity() instanceof LivingEntity le ? le : null;
    }

    // ------------------------------------------------------------------ seleção

    public static List<Entity> selected(ServerPlayer sp, PowerSession s) {
        List<Entity> out = new ArrayList<>();
        boolean changed = s.selection.removeIf(id -> {
            Entity e = sp.level().getEntity(id);
            if (e == null || !e.isAlive()) return true;
            if (e instanceof MatterConstructEntity c && !c.isOwnedBy(sp)) return true;
            if (e instanceof MatterHandEntity h && !h.isOwnedBy(sp)) return true;
            out.add(e);
            return false;
        });
        if (changed) syncSelection(sp, s);
        return out;
    }

    public static void select(ServerPlayer sp, PowerSession s, Entity e, boolean add) {
        if (!add) s.selection.clear();
        if (add && s.selection.contains(e.getId())) s.selection.remove(e.getId());
        else s.selection.add(e.getId());
        s.moving = false;
        syncSelection(sp, s);
    }

    public static void clearSelection(ServerPlayer sp, PowerSession s) {
        s.selection.clear();
        s.moving = false;
        syncSelection(sp, s);
    }

    public static void syncSelection(ServerPlayer sp, PowerSession s) {
        PacketDistributor.sendToPlayer(sp, new SelectionPayload(s.selection.stream().mapToInt(Integer::intValue).toArray()));
    }

    public static void message(ServerPlayer sp, String text) {
        sp.displayClientMessage(Component.literal(text), true);
    }
}
