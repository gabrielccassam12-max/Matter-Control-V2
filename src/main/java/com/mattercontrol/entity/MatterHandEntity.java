package com.mattercontrol.entity;

import com.mattercontrol.config.MatterConfig;
import com.mattercontrol.network.FxPayload;
import com.mattercontrol.registry.ModRegistry;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Mão gigante de matéria controlada diretamente: move, abre/fecha dedos, agarra, esmaga, arremessa. */
public class MatterHandEntity extends Entity {
    private static final EntityDataAccessor<Float> GRIP = SynchedEntityData.defineId(MatterHandEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> HAND_SCALE = SynchedEntityData.defineId(MatterHandEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> FACE_YAW = SynchedEntityData.defineId(MatterHandEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> FACE_PITCH = SynchedEntityData.defineId(MatterHandEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Integer> OWNER_ID = SynchedEntityData.defineId(MatterHandEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Boolean> CRUSHING = SynchedEntityData.defineId(MatterHandEntity.class, EntityDataSerializers.BOOLEAN);

    private record Grab(UUID id, Vec3 offset) {}

    @Nullable private UUID owner;
    private final List<Grab> grabbed = new ArrayList<>();
    private Vec3 controlTarget = Vec3.ZERO;
    private double stiffness = 0.4, maxSpeed = 2.5;
    private int controlTicks;
    private int crushTicks;

    // cliente
    public float prevGrip, prevYawFace, prevPitchFace;
    private float lastGrip, lastYaw, lastPitch;
    private int lerpSteps;
    private double lerpX, lerpY, lerpZ;

    public MatterHandEntity(EntityType<? extends MatterHandEntity> type, Level level) {
        super(type, level);
        noPhysics = true;
    }

    public static MatterHandEntity create(Level level, Vec3 pos, Player owner, float scale) {
        MatterHandEntity h = new MatterHandEntity(ModRegistry.HAND.get(), level);
        h.entityData.set(HAND_SCALE, scale);
        h.owner = owner.getUUID();
        h.entityData.set(OWNER_ID, owner.getId());
        h.entityData.set(FACE_YAW, owner.getYRot());
        h.entityData.set(FACE_PITCH, owner.getXRot());
        h.setPos(pos);
        return h;
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder b) {
        b.define(GRIP, 0f);
        b.define(HAND_SCALE, 2f);
        b.define(FACE_YAW, 0f);
        b.define(FACE_PITCH, 0f);
        b.define(OWNER_ID, -1);
        b.define(CRUSHING, false);
    }

    public float grip() { return entityData.get(GRIP); }
    public float handScale() { return entityData.get(HAND_SCALE); }
    public float faceYaw() { return entityData.get(FACE_YAW); }
    public float facePitch() { return entityData.get(FACE_PITCH); }
    public int ownerEntityId() { return entityData.get(OWNER_ID); }
    public boolean crushing() { return entityData.get(CRUSHING); }
    public boolean isOwnedBy(Player p) { return p.getUUID().equals(owner); }
    public int grabbedCount() { return grabbed.size(); }

    public static float costFor(float scale) {
        return (float) (MatterConfig.HAND_COST.get() * scale * scale);
    }

    @Override
    protected AABB makeBoundingBox() {
        double r = 1.8 * (entityData == null ? 2f : handScale());
        return new AABB(getX() - r, getY() - r, getZ() - r, getX() + r, getY() + r, getZ() + r);
    }

    public Vec3 forward() {
        return Vec3.directionFromRotation(facePitch(), faceYaw());
    }

    /** Ponto onde os objetos ficam presos (dentro da palma fechada). */
    public Vec3 grabPoint() {
        return position().add(forward().scale(1.1 * handScale()));
    }

    // ------------------------------------------------------------------ comandos

    public void setControl(Vec3 target, double stiffness, double maxSpeed) {
        this.controlTarget = target;
        this.stiffness = stiffness;
        this.maxSpeed = maxSpeed;
        this.controlTicks = 3;
    }

    public void face(float yaw, float pitch) {
        float y = faceYaw(), p = facePitch();
        entityData.set(FACE_YAW, y + Mth.wrapDegrees(yaw - y) * 0.35f);
        entityData.set(FACE_PITCH, p + (pitch - p) * 0.35f);
    }

    /** Fecha (delta > 0) ou abre (delta < 0) os dedos de forma contínua. */
    public void changeGrip(float delta) {
        float old = grip();
        float g = Mth.clamp(old + delta, 0f, 1f);
        entityData.set(GRIP, g);
        if (old < 0.55f && g >= 0.55f) grabAll();
        if (old >= 0.4f && g < 0.4f) releaseAll(Vec3.ZERO);
    }

    public void crush() {
        crushTicks = 3;
        entityData.set(CRUSHING, true);
    }

    public void throwGrabbed(Vec3 velocity) {
        releaseAll(velocity);
        entityData.set(GRIP, 0.2f);
        playSound(SoundEvents.PLAYER_ATTACK_SWEEP, 1.5f, 0.6f);
    }

    private void grabAll() {
        if (!(level() instanceof ServerLevel sl)) return;
        Vec3 gp = grabPoint();
        double r = 1.9 * handScale();
        Entity ownerEnt = level().getEntity(ownerEntityId());
        for (Entity e : level().getEntities(this, new AABB(gp, gp).inflate(r))) {
            if (e == ownerEnt || e instanceof MatterHandEntity || e.isSpectator()) continue;
            if (e instanceof Player && !MatterConfig.AFFECT_PLAYERS.get()) continue;
            if (e instanceof MatterConstructEntity c && c.captive() == null && c.boundRadius() > r * 2.5) continue;
            Vec3 center = e.getBoundingBox().getCenter();
            if (center.distanceTo(gp) > r + e.getBbWidth()) continue;
            Vec3 off = e.position().subtract(gp).scale(0.35);
            grabbed.add(new Grab(e.getUUID(), off));
            if (grabbed.size() >= 16) break;
        }
        if (!grabbed.isEmpty()) {
            playSound(SoundEvents.AMETHYST_BLOCK_HIT, 2f, 0.5f);
            FxPayload.send(sl, gp, FxPayload.GRAB, handScale(), getId());
        }
    }

    private void releaseAll(Vec3 velocity) {
        if (!(level() instanceof ServerLevel sl)) { grabbed.clear(); return; }
        for (Grab g : grabbed) {
            Entity e = sl.getEntity(g.id);
            if (e == null) continue;
            if (e instanceof MatterConstructEntity c) {
                if (velocity.lengthSqr() > 0.01) c.launch(velocity); else c.drop();
            } else {
                e.setDeltaMovement(velocity);
                e.hurtMarked = true;
                e.fallDistance = 0;
            }
        }
        grabbed.clear();
    }

    public void releaseEverything() {
        releaseAll(Vec3.ZERO);
    }

    // ------------------------------------------------------------------ tick

    @Override
    public void tick() {
        if (level().isClientSide) { clientTick(); return; }
        super.tick();
        Vec3 vel = getDeltaMovement();
        if (controlTicks-- > 0) {
            Vec3 want = controlTarget.subtract(position()).scale(stiffness);
            if (want.length() > maxSpeed) want = want.normalize().scale(maxSpeed);
            vel = vel.lerp(want, 0.6);
        } else {
            vel = vel.scale(0.7);
        }
        setDeltaMovement(vel);
        setPos(position().add(vel));

        if (crushTicks-- <= 0 && crushing()) entityData.set(CRUSHING, false);

        Vec3 gp = grabPoint();
        ServerLevel sl = (ServerLevel) level();
        grabbed.removeIf(g -> {
            Entity e = sl.getEntity(g.id);
            if (e == null || !e.isAlive()) return true;
            Vec3 t = gp.add(g.offset.scale(1.0 - grip() * 0.6));
            if (e instanceof MatterConstructEntity c) {
                c.setControl(t, 0.7, 6.0);
                if (crushing() && c.scale() > MatterConstructEntity.MIN_SCALE) {
                    c.setScale(c.scale() * 0.97f);
                    if (c.captive() != null) c.crushCaptive(2f * handScale());
                }
            } else {
                Vec3 at = t.subtract(0, e.getBbHeight() * 0.5, 0);
                e.setDeltaMovement(Vec3.ZERO);
                e.fallDistance = 0;
                if (e.position().distanceToSqr(at) > 0.0025) {
                    if (e instanceof ServerPlayer sp) sp.connection.teleport(at.x, at.y, at.z, sp.getYRot(), sp.getXRot());
                    else e.teleportTo(at.x, at.y, at.z);
                }
                if (crushing() && tickCount % 5 == 0 && e instanceof LivingEntity le) {
                    le.hurt(damageSource(), (float) (2.5 * handScale() * MatterConfig.CRUSH_DAMAGE.get()));
                    FxPayload.send(sl, t, FxPayload.CRUSH, handScale(), getId());
                    playSound(SoundEvents.ANVIL_LAND, 0.6f, 1.6f);
                }
            }
            return false;
        });

        Player p = owner == null ? null : level().getPlayerByUUID(owner);
        if (p == null && tickCount > 40) {
            // dono offline: mão se desfaz soltando tudo
            releaseAll(Vec3.ZERO);
        }
    }

    public DamageSource damageSource() {
        Entity ownerEntity = level().getEntity(ownerEntityId());
        return level().damageSources().thrown(this, ownerEntity);
    }

    @Override
    public void lerpTo(double x, double y, double z, float yRot, float xRot, int steps) {
        lerpX = x; lerpY = y; lerpZ = z;
        lerpSteps = Math.max(1, Math.min(steps, 3));
    }

    private void clientTick() {
        xo = getX(); yo = getY(); zo = getZ();
        prevGrip = lastGrip; prevYawFace = lastYaw; prevPitchFace = lastPitch;
        lastGrip = grip(); lastYaw = faceYaw(); lastPitch = facePitch();
        if (lerpSteps > 0) {
            double f = 1.0 / lerpSteps;
            setPos(getX() + (lerpX - getX()) * f, getY() + (lerpY - getY()) * f, getZ() + (lerpZ - getZ()) * f);
            lerpSteps--;
        }
        tickCount++;
        com.mattercontrol.client.ClientFx.handTick(this);
    }

    @Override public boolean isPickable() { return !isRemoved(); }
    @Override public boolean isPushable() { return false; }
    @Override public boolean hurt(DamageSource source, float amount) { return false; }
    @Override public boolean shouldRenderAtSqrDistance(double d) { return d < 160 * 160; }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        if (tag.hasUUID("Owner")) owner = tag.getUUID("Owner");
        entityData.set(HAND_SCALE, tag.contains("Scale") ? tag.getFloat("Scale") : 2f);
        entityData.set(FACE_YAW, tag.getFloat("FYaw"));
        entityData.set(FACE_PITCH, tag.getFloat("FPitch"));
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        if (owner != null) tag.putUUID("Owner", owner);
        tag.putFloat("Scale", handScale());
        tag.putFloat("FYaw", faceYaw());
        tag.putFloat("FPitch", facePitch());
    }

    @Override
    public void onSyncedDataUpdated(EntityDataAccessor<?> key) {
        super.onSyncedDataUpdated(key);
        if (HAND_SCALE.equals(key)) setBoundingBox(makeBoundingBox());
    }
}
