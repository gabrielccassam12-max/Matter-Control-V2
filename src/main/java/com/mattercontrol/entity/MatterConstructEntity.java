package com.mattercontrol.entity;

import com.mattercontrol.config.MatterConfig;
import com.mattercontrol.network.FxPayload;
import com.mattercontrol.network.ShapePayload;
import com.mattercontrol.registry.ModRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
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
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.entity.IEntityWithComplexSpawn;
import net.neoforged.neoforge.network.PacketDistributor;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Uma estrutura móvel feita de blocos reais. Toda a matéria em movimento do mod
 * (terreno levantado, esferas, projéteis, prisões) é uma destas entidades.
 * Um único objeto controla milhares de blocos: barato para rede, física e renderização.
 */
public class MatterConstructEntity extends Entity implements IEntityWithComplexSpawn {
    private static final EntityDataAccessor<Float> SCALE = SynchedEntityData.defineId(MatterConstructEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> SPREAD = SynchedEntityData.defineId(MatterConstructEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> ROT_YAW = SynchedEntityData.defineId(MatterConstructEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> ROT_PITCH = SynchedEntityData.defineId(MatterConstructEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> ROT_ROLL = SynchedEntityData.defineId(MatterConstructEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Byte> MOTION = SynchedEntityData.defineId(MatterConstructEntity.class, EntityDataSerializers.BYTE);
    private static final EntityDataAccessor<Integer> OWNER_ID = SynchedEntityData.defineId(MatterConstructEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> CAPTIVE_ID = SynchedEntityData.defineId(MatterConstructEntity.class, EntityDataSerializers.INT);

    public static final float MIN_SCALE = 0.125f;
    private static final int MAX_SAMPLES = 128;

    private ConstructShape shape = new ConstructShape();
    @Nullable private UUID owner;
    @Nullable private UUID captive;
    private Vec3 captiveOffset = Vec3.ZERO;

    // controle (servidor)
    private Vec3 controlTarget = Vec3.ZERO;
    private double stiffness = 0.35, maxSpeed = 2.0;
    private int controlTicks;
    private float spreadTarget = 1f;
    public int orbitIndex;
    public int homingTarget = -1;
    @Nullable private Vec3 steerPoint;
    private double steerRate;
    private int steerTicks;
    private final Map<Integer, Long> hitCooldown = new HashMap<>();

    // cache de amostras de colisão
    private int[] samples = new int[0];

    // animação (cliente)
    public float[] animX = new float[0], animY = new float[0], animZ = new float[0];
    public float[] prevX = new float[0], prevY = new float[0], prevZ = new float[0];
    public float prevScale = 1, prevSpread = 1, prevYaw, prevPitch, prevRoll;
    private float lastScale = 1, lastSpread = 1, lastYaw, lastPitch, lastRoll;
    private int lerpSteps;
    private double lerpX, lerpY, lerpZ;

    public MatterConstructEntity(EntityType<? extends MatterConstructEntity> type, Level level) {
        super(type, level);
        this.noPhysics = true;
    }

    public static MatterConstructEntity create(Level level, ConstructShape shape, Vec3 center, @Nullable Player owner) {
        MatterConstructEntity e = new MatterConstructEntity(ModRegistry.CONSTRUCT.get(), level);
        e.shape = shape;
        e.rebuildSamples();
        e.setPos(center);
        if (owner != null) {
            e.owner = owner.getUUID();
            e.entityData.set(OWNER_ID, owner.getId());
        }
        return e;
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder b) {
        b.define(SCALE, 1f);
        b.define(SPREAD, 1f);
        b.define(ROT_YAW, 0f);
        b.define(ROT_PITCH, 0f);
        b.define(ROT_ROLL, 0f);
        b.define(MOTION, (byte) Motion.FLOAT.ordinal());
        b.define(OWNER_ID, -1);
        b.define(CAPTIVE_ID, -1);
    }

    // ------------------------------------------------------------------ getters

    public ConstructShape shape() { return shape; }
    public int blockCount() { return shape.size; }
    public float scale() { return entityData.get(SCALE); }
    public float spread() { return entityData.get(SPREAD); }
    public float rotYaw() { return entityData.get(ROT_YAW); }
    public float rotPitch() { return entityData.get(ROT_PITCH); }
    public float rotRoll() { return entityData.get(ROT_ROLL); }
    public Motion motion() { return Motion.byId(entityData.get(MOTION)); }
    public int ownerEntityId() { return entityData.get(OWNER_ID); }
    public int captiveEntityId() { return entityData.get(CAPTIVE_ID); }
    @Nullable public UUID owner() { return owner; }
    @Nullable public UUID captive() { return captive; }
    public boolean isOwnedBy(Player p) { return p.getUUID().equals(owner); }
    public float spreadTarget() { return spreadTarget; }

    public void setOwner(Player p) { owner = p.getUUID(); entityData.set(OWNER_ID, p.getId()); }
    public void setMotion(Motion m) { entityData.set(MOTION, (byte) m.ordinal()); }
    public void setScale(float s) { entityData.set(SCALE, Mth.clamp(s, MIN_SCALE, 1f)); refreshBox(); }
    public void setSpreadTarget(float s) { spreadTarget = Mth.clamp(s, 1f, 6f); }

    public void addRotation(float dy, float dp, float dr) {
        entityData.set(ROT_YAW, Mth.wrapDegrees(rotYaw() + dy));
        entityData.set(ROT_PITCH, Mth.wrapDegrees(rotPitch() + dp));
        entityData.set(ROT_ROLL, Mth.wrapDegrees(rotRoll() + dr));
    }

    public void setRotation(float y, float p, float r) {
        entityData.set(ROT_YAW, y); entityData.set(ROT_PITCH, p); entityData.set(ROT_ROLL, r);
    }

    public Quaternionf rotation() {
        return new Quaternionf().rotationYXZ(rotYaw() * Mth.DEG_TO_RAD, rotPitch() * Mth.DEG_TO_RAD, rotRoll() * Mth.DEG_TO_RAD);
    }

    public double mass() { return shape.size; }

    public double boundRadius() {
        return shape.radius() * scale() + (spread() - 1f) * 3f + 0.5;
    }

    @Override
    protected AABB makeBoundingBox() {
        double r = shape == null ? 0.5 : Math.max(0.5, boundRadius());
        return new AABB(getX() - r, getY() - r, getZ() - r, getX() + r, getY() + r, getZ() + r);
    }

    private void refreshBox() { setBoundingBox(makeBoundingBox()); }

    /** Substitui a forma e sincroniza com os clientes. */
    public void setShape(ConstructShape s) {
        this.shape = s;
        rebuildSamples();
        refreshBox();
        syncShape();
    }

    public void syncShape() {
        if (!level().isClientSide && isAddedToLevel()) {
            PacketDistributor.sendToPlayersTrackingEntity(this, new ShapePayload(getId(), shape));
        }
    }

    public void setShapeClient(ConstructShape s) {
        int old = animX.length;
        float[] ax = new float[s.size], ay = new float[s.size], az = new float[s.size];
        for (int i = 0; i < s.size; i++) {
            if (i < old) { ax[i] = animX[i]; ay[i] = animY[i]; az[i] = animZ[i]; }
            else { ax[i] = s.x[i]; ay[i] = s.y[i]; az[i] = s.z[i]; }
        }
        shape = s;
        animX = ax; animY = ay; animZ = az;
        prevX = ax.clone(); prevY = ay.clone(); prevZ = az.clone();
        refreshBox();
    }

    private void rebuildSamples() {
        int[] surf = shape.surface();
        int n = Math.min(MAX_SAMPLES, surf.length);
        samples = new int[n];
        if (n == 0) return;
        double step = (double) surf.length / n;
        for (int i = 0; i < n; i++) samples[i] = surf[(int) (i * step)];
    }

    // ------------------------------------------------------------------ comandos

    /** Mantém sob controle direto por este tick: a estrutura persegue o alvo. */
    public void setControl(Vec3 target, double stiffness, double maxSpeed) {
        this.controlTarget = target;
        this.stiffness = stiffness;
        this.maxSpeed = maxSpeed;
        this.controlTicks = 3;
        if (motion() != Motion.HELD) setMotion(Motion.HELD);
    }

    public void launch(Vec3 velocity) {
        setDeltaMovement(velocity);
        setMotion(Motion.THROWN);
        controlTicks = 0;
    }

    /** Guiar durante o voo: curva a trajetória na direção do ponto. */
    public void steer(Vec3 point, double rate) {
        this.steerPoint = point;
        this.steerRate = rate;
        this.steerTicks = 2;
    }

    public void accelerate(double factor) {
        Vec3 v = getDeltaMovement();
        double len = v.length();
        if (len < 1e-3) return;
        setDeltaMovement(v.scale(Mth.clamp(len * factor, 0.02, 6.0) / len));
    }

    public void drop() {
        setMotion(Motion.FALLING);
        controlTicks = 0;
    }

    public void hover() {
        setMotion(Motion.FLOAT);
        controlTicks = 0;
    }

    public void imprison(Entity target) {
        this.captive = target.getUUID();
        this.captiveOffset = target.position().subtract(position());
        entityData.set(CAPTIVE_ID, target.getId());
    }

    public void releaseCaptive() {
        this.captive = null;
        entityData.set(CAPTIVE_ID, -1);
    }

    @Nullable
    public Entity captiveEntity() {
        if (captive == null || !(level() instanceof ServerLevel sl)) return null;
        return sl.getEntity(captive);
    }

    /** Dano de esmagamento no prisioneiro (compactar a prisão). */
    public void crushCaptive(float amount) {
        Entity c = captiveEntity();
        if (c instanceof LivingEntity le && le.isAlive() && tickCount % 4 == 0) {
            le.hurt(damageSource(), amount * MatterConfig.CRUSH_DAMAGE.get().floatValue());
            le.setDeltaMovement(Vec3.ZERO);
            FxPayload.send((ServerLevel) level(), le.position().add(0, le.getBbHeight() / 2, 0), FxPayload.CRUSH, amount, getId());
        }
    }

    public DamageSource damageSource() {
        Entity ownerEntity = ownerEntityId() >= 0 ? level().getEntity(ownerEntityId()) : null;
        return level().damageSources().thrown(this, ownerEntity);
    }

    /** Posição de mundo do bloco i (usa a forma-alvo, não a animação). */
    public Vec3 worldPosOf(int i, Vec3 center, Quaternionf q, float scale, float spread) {
        Vector3f v = localOf(i, shape.x[i], shape.y[i], shape.z[i], scale, spread);
        q.transform(v);
        return center.add(v.x, v.y, v.z);
    }

    /** Posição local final (escala + dispersão) — compartilhada com o renderizador. */
    public static Vector3f localOf(int i, float hx, float hy, float hz, float scale, float spread) {
        Vector3f v = new Vector3f(hx * scale, hy * scale, hz * scale);
        if (spread > 1.001f) {
            int h = i * 0x9E3779B1;
            float jx = ((h >>> 8) & 255) / 127.5f - 1f;
            float jy = ((h >>> 16) & 255) / 127.5f - 1f;
            float jz = ((h >>> 24) & 255) / 127.5f - 1f;
            float k = (spread - 1f) * (1.5f + ((h & 255) / 255f) * 2.5f);
            v.add(hx * (spread - 1f) * 0.6f + jx * k, hy * (spread - 1f) * 0.6f + jy * k, hz * (spread - 1f) * 0.6f + jz * k);
        }
        return v;
    }

    /** Tamanho visual de cada bloco. */
    public static float blockSize(float scale, float spread) {
        return scale * (1f - 0.75f * Math.min(1f, (spread - 1f) / 2f));
    }

    // ------------------------------------------------------------------ física

    private boolean collidesAt(Vec3 center) {
        if (spread() > 1.3f || samples.length == 0) return false;
        Quaternionf q = rotation();
        float sc = scale(), sp = spread();
        BlockPos.MutableBlockPos mp = new BlockPos.MutableBlockPos();
        for (int i : samples) {
            if (i >= shape.size) continue;
            Vec3 w = worldPosOf(i, center, q, sc, sp);
            mp.set(Mth.floor(w.x), Mth.floor(w.y), Mth.floor(w.z));
            BlockState st = level().getBlockState(mp);
            if (!st.isAir() && !st.getCollisionShape(level(), mp).isEmpty()) return true;
        }
        return false;
    }

    /** Move respeitando colisões. Retorna true se bateu. */
    private boolean moveCollide(Vec3 vel) {
        if (vel.lengthSqr() < 1e-8) return false;
        Vec3 pos = position();
        if (collidesAt(pos)) { // já está dentro do terreno (ex.: acabou de ser arrancado): atravessa livremente
            setPos(pos.add(vel));
            return false;
        }
        Vec3 next = pos.add(vel);
        if (!collidesAt(next)) { setPos(next); return false; }
        // desliza eixo a eixo
        Vec3 p = pos;
        Vec3 nv = vel;
        Vec3 tx = p.add(vel.x, 0, 0);
        if (!collidesAt(tx)) p = tx; else nv = new Vec3(0, nv.y, nv.z);
        Vec3 ty = p.add(0, vel.y, 0);
        if (!collidesAt(ty)) p = ty; else nv = new Vec3(nv.x, 0, nv.z);
        Vec3 tz = p.add(0, 0, vel.z);
        if (!collidesAt(tz)) p = tz; else nv = new Vec3(nv.x, nv.y, 0);
        setPos(p);
        setDeltaMovement(nv);
        return true;
    }

    // ------------------------------------------------------------------ tick

    @Override
    public void tick() {
        if (level().isClientSide) {
            clientTick();
            return;
        }
        super.tick();
        if (shape.size == 0) { discard(); return; }

        float sp = spread();
        if (Math.abs(sp - spreadTarget) > 0.001f) {
            entityData.set(SPREAD, sp + (spreadTarget - sp) * 0.18f + Math.signum(spreadTarget - sp) * 0.01f);
            if (Math.abs(spread() - spreadTarget) < 0.02f) entityData.set(SPREAD, spreadTarget);
            refreshBox();
        }

        Motion m = motion();
        Vec3 vel = getDeltaMovement();
        double heavy = 1.0 + mass() / 1500.0;
        switch (m) {
            case HELD -> {
                if (--controlTicks <= 0) { hover(); break; }
                Vec3 to = controlTarget.subtract(position());
                Vec3 want = to.scale(stiffness);
                double cap = maxSpeed / Math.sqrt(heavy);
                if (want.length() > cap) want = want.normalize().scale(cap);
                vel = vel.lerp(want, 0.6);
                setDeltaMovement(vel);
                moveCollide(vel);
                touchEntities(vel, false);
            }
            case FLOAT -> {
                vel = vel.scale(0.7);
                setDeltaMovement(vel);
                moveCollide(vel);
            }
            case ORBIT -> {
                Player p = ownerPlayer();
                if (p == null) { hover(); break; }
                double t = (tickCount + orbitIndex * 40) * 0.07;
                double r = 2.2 + boundRadius();
                Vec3 target = p.position().add(Math.cos(t + orbitIndex * 1.3) * r, p.getBbHeight() * 0.75 + Math.sin(t * 1.7 + orbitIndex) * 0.4, Math.sin(t + orbitIndex * 1.3) * r);
                Vec3 want = target.subtract(position()).scale(0.3);
                vel = vel.lerp(want, 0.5);
                setDeltaMovement(vel);
                setPos(position().add(vel));
                addRotation(4f, 2.5f, 0);
            }
            case THROWN, HOMING -> {
                if (m == Motion.HOMING) {
                    Entity t = homingTarget >= 0 ? level().getEntity(homingTarget) : null;
                    if (t == null || !t.isAlive()) { setMotion(Motion.THROWN); }
                    else steerTowards(t.position().add(0, t.getBbHeight() * 0.5, 0), 0.25);
                } else {
                    vel = vel.add(0, -0.03, 0).scale(0.995);
                    setDeltaMovement(vel);
                }
                if (steerPoint != null && steerTicks-- > 0) steerTowards(steerPoint, steerRate);
                vel = getDeltaMovement();
                addRotation((float) vel.length() * 6f, (float) vel.length() * 4f, 0);
                boolean hitEntity = touchEntities(vel, true);
                boolean wasEmbedded = collidesAt(position());
                boolean hit = moveCollide(vel);
                if ((hit && !wasEmbedded) || hitEntity) impact(vel.length());
            }
            case FALLING -> {
                vel = vel.add(0, -0.06, 0).scale(0.98);
                setDeltaMovement(vel);
                touchEntities(vel, false);
                boolean hit = moveCollide(vel);
                if (hit && getDeltaMovement().y == 0) {
                    if (vel.length() > 0.8) impact(vel.length() * 0.6);
                    setDeltaMovement(Vec3.ZERO);
                    hover();
                }
                if (getY() < level().getMinBuildHeight() - 64) discard();
            }
        }
        updateCaptive();
        if (tickCount % 100 == 0) hitCooldown.clear();
    }

    private void steerTowards(Vec3 point, double rate) {
        Vec3 v = getDeltaMovement();
        double speed = Math.max(0.4, v.length());
        Vec3 dir = point.subtract(position()).normalize();
        Vec3 nv = v.normalize().lerp(dir, Mth.clamp(rate, 0, 1)).normalize().scale(speed);
        setDeltaMovement(nv);
    }

    @Nullable
    public Player ownerPlayer() {
        return owner == null ? null : level().getPlayerByUUID(owner);
    }

    private void updateCaptive() {
        if (captive == null) return;
        Entity c = captiveEntity();
        if (c == null || !c.isAlive()) { releaseCaptive(); return; }
        Vec3 target = position().add(captiveOffset);
        c.setDeltaMovement(Vec3.ZERO);
        c.fallDistance = 0;
        if (c.position().distanceToSqr(target) > 0.0025) {
            if (c instanceof ServerPlayer sp) sp.connection.teleport(target.x, target.y, target.z, sp.getYRot(), sp.getXRot());
            else c.teleportTo(target.x, target.y, target.z);
        }
    }

    /** Empurra/fere entidades tocadas. Retorna true se um projétil acertou alguém. */
    private boolean touchEntities(Vec3 vel, boolean projectile) {
        double speed = vel.length();
        if (speed < 0.3 || samples.length == 0 || spread() > 1.5f) return false;
        AABB box = getBoundingBox().inflate(0.5);
        Entity ownerEnt = ownerEntityId() >= 0 ? level().getEntity(ownerEntityId()) : null;
        Quaternionf q = rotation();
        float sc = scale(), sp = spread();
        boolean any = false;
        for (LivingEntity le : level().getEntitiesOfClass(LivingEntity.class, box)) {
            if (le == ownerEnt || le.getUUID().equals(captive) || !le.isAlive()) continue;
            if (le instanceof Player pl && (pl.isSpectator() || !MatterConfig.AFFECT_PLAYERS.get())) continue;
            double reach = 0.5 * sc + le.getBbWidth() * 0.5 + 0.25;
            Vec3 c = le.position().add(0, le.getBbHeight() * 0.5, 0);
            boolean touching = false;
            for (int i : samples) {
                if (i >= shape.size) continue;
                Vec3 w = worldPosOf(i, position(), q, sc, sp);
                double dx = Math.abs(w.x - c.x), dy = Math.abs(w.y - c.y), dz = Math.abs(w.z - c.z);
                if (dx < reach && dz < reach && dy < 0.5 * sc + le.getBbHeight() * 0.5 + 0.25) { touching = true; break; }
            }
            if (!touching) continue;
            long now = level().getGameTime();
            Long last = hitCooldown.get(le.getId());
            if (last != null && now - last < 10) continue;
            hitCooldown.put(le.getId(), now);
            float dmg = (float) (speed * 2.2 * Math.pow(mass(), 0.33) * MatterConfig.IMPACT_DAMAGE.get());
            if (speed > 0.45) le.hurt(damageSource(), Math.min(dmg, 120f));
            le.push(vel.x * 0.8, Math.max(0.15, vel.y * 0.5 + 0.2), vel.z * 0.8);
            le.hurtMarked = true;
            any = true;
        }
        return any && projectile;
    }

    /** Impacto: onda de choque proporcional à massa e velocidade. */
    public void impact(double speed) {
        if (!(level() instanceof ServerLevel sl) || speed < 0.35) {
            setDeltaMovement(Vec3.ZERO);
            drop();
            return;
        }
        double mag = speed * Math.cbrt(mass());
        double radius = Math.min(24, 1.5 + boundRadius() + mag * 0.35);
        Entity ownerEnt = ownerEntityId() >= 0 ? level().getEntity(ownerEntityId()) : null;
        for (LivingEntity le : level().getEntitiesOfClass(LivingEntity.class, getBoundingBox().inflate(radius))) {
            if (le == ownerEnt || !le.isAlive()) continue;
            if (le instanceof Player pl && (pl.isSpectator() || !MatterConfig.AFFECT_PLAYERS.get())) continue;
            double d = le.position().distanceTo(position());
            if (d > radius + boundRadius()) continue;
            double fall = 1.0 - Math.min(1.0, Math.max(0, d - boundRadius()) / radius);
            float dmg = (float) (mag * 2.5 * fall * MatterConfig.IMPACT_DAMAGE.get());
            if (le.getUUID().equals(captive)) dmg *= 0.6f;
            if (dmg > 0.5f) le.hurt(damageSource(), Math.min(dmg, 150f));
            Vec3 away = le.position().subtract(position()).normalize().scale(0.4 + fall * mag * 0.15);
            if (!le.getUUID().equals(captive)) {
                le.push(away.x, 0.25 + fall * 0.4, away.z);
                le.hurtMarked = true;
            }
        }
        sl.playSound(null, getX(), getY(), getZ(), SoundEvents.ANVIL_LAND, SoundSource.PLAYERS,
                (float) Math.min(4.0, 0.6 + mag * 0.15), (float) Mth.clamp(1.4 - mag * 0.05, 0.5, 1.4));
        FxPayload.send(sl, position(), FxPayload.IMPACT, (float) mag, getId());
        setDeltaMovement(getDeltaMovement().scale(-0.15));
        drop();
    }

    // ------------------------------------------------------------------ cliente

    @Override
    public void lerpTo(double x, double y, double z, float yRot, float xRot, int steps) {
        lerpX = x; lerpY = y; lerpZ = z;
        lerpSteps = Math.max(1, Math.min(steps, 3));
    }

    private void clientTick() {
        xo = getX(); yo = getY(); zo = getZ();
        if (lerpSteps > 0) {
            double f = 1.0 / lerpSteps;
            setPos(getX() + (lerpX - getX()) * f, getY() + (lerpY - getY()) * f, getZ() + (lerpZ - getZ()) * f);
            lerpSteps--;
        }
        // valores do tick anterior (para interpolar suavemente no render)
        prevScale = lastScale; prevSpread = lastSpread;
        prevYaw = lastYaw; prevPitch = lastPitch; prevRoll = lastRoll;
        lastScale = scale(); lastSpread = spread();
        lastYaw = rotYaw(); lastPitch = rotPitch(); lastRoll = rotRoll();
        if (animX.length != shape.size) setShapeClient(shape);
        System.arraycopy(animX, 0, prevX, 0, animX.length);
        System.arraycopy(animY, 0, prevY, 0, animY.length);
        System.arraycopy(animZ, 0, prevZ, 0, animZ.length);
        for (int i = 0; i < shape.size; i++) {
            animX[i] += (shape.x[i] - animX[i]) * 0.22f;
            animY[i] += (shape.y[i] - animY[i]) * 0.22f;
            animZ[i] += (shape.z[i] - animZ[i]) * 0.22f;
        }
        tickCount++;
        com.mattercontrol.client.ClientFx.constructTick(this);
    }

    // ------------------------------------------------------------------ misc

    @Override public boolean isPickable() { return !isRemoved(); }
    @Override public boolean isPushable() { return false; }
    @Override public boolean hurt(DamageSource source, float amount) { return false; }
    @Override public boolean shouldRenderAtSqrDistance(double d) { double r = 128 + boundRadius() * 4; return d < r * r; }

    @Override
    public boolean canBeCollidedWith() {
        if (spread() > 1.2f || motion() == Motion.THROWN || motion() == Motion.HOMING) return false;
        AABB b = getBoundingBox();
        double vol = Math.max(1, b.getXsize() * b.getYsize() * b.getZsize());
        double filled = shape.size * Math.pow(scale(), 3);
        return filled / vol > 0.35;
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        shape = ConstructShape.load(tag.getCompound("Shape"), level().holderLookup(Registries.BLOCK));
        rebuildSamples();
        if (tag.hasUUID("Owner")) owner = tag.getUUID("Owner");
        if (tag.hasUUID("Captive")) captive = tag.getUUID("Captive");
        captiveOffset = new Vec3(tag.getDouble("CapX"), tag.getDouble("CapY"), tag.getDouble("CapZ"));
        entityData.set(SCALE, tag.contains("Scale") ? tag.getFloat("Scale") : 1f);
        spreadTarget = tag.contains("Spread") ? tag.getFloat("Spread") : 1f;
        entityData.set(SPREAD, spreadTarget);
        setRotation(tag.getFloat("RYaw"), tag.getFloat("RPitch"), tag.getFloat("RRoll"));
        Motion m = Motion.byId(tag.getByte("Motion"));
        setMotion(m == Motion.FLOAT ? Motion.FLOAT : Motion.FALLING);
        refreshBox();
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        tag.put("Shape", shape.save());
        if (owner != null) tag.putUUID("Owner", owner);
        if (captive != null) tag.putUUID("Captive", captive);
        tag.putDouble("CapX", captiveOffset.x); tag.putDouble("CapY", captiveOffset.y); tag.putDouble("CapZ", captiveOffset.z);
        tag.putFloat("Scale", scale());
        tag.putFloat("Spread", spreadTarget);
        tag.putFloat("RYaw", rotYaw()); tag.putFloat("RPitch", rotPitch()); tag.putFloat("RRoll", rotRoll());
        tag.putByte("Motion", (byte) motion().ordinal());
    }

    @Override
    public void writeSpawnData(RegistryFriendlyByteBuf buf) {
        shape.write(buf);
    }

    @Override
    public void readSpawnData(RegistryFriendlyByteBuf buf) {
        setShapeClient(ConstructShape.read(buf));
    }
}
