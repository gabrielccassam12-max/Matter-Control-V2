package com.mattercontrol.server;

import com.mattercontrol.block.MatterBlock;
import com.mattercontrol.config.MatterConfig;
import com.mattercontrol.core.MatterMode;
import com.mattercontrol.core.PlayerMatter;
import com.mattercontrol.entity.ConstructShape;
import com.mattercontrol.entity.MatterConstructEntity;
import com.mattercontrol.entity.MatterHandEntity;
import com.mattercontrol.entity.Motion;
import com.mattercontrol.network.ActionPayload;
import com.mattercontrol.network.FxPayload;
import com.mattercontrol.registry.ModRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Ações discretas: seleção, levantar terreno, aprisionar, criar mãos/projéteis, arremessar... */
public final class ActionHandler {
    private ActionHandler() {}

    public static void handle(ServerPlayer sp, PowerSession s, ActionPayload a) {
        switch (a.action()) {
            case LEFT_CLICK -> leftClick(sp, s, a.shift());
            case RIGHT_CLICK -> {
                if (PowerManager.selected(sp, s).isEmpty()) create(sp, s);
            }
            case CREATE -> create(sp, s);
            case THROW -> throwSelection(sp, s, a);
            case DROP -> drop(sp, s);
            case HOMING -> homing(sp, s);
            case DESELECT -> PowerManager.clearSelection(sp, s);
            case SPLIT -> StructureOps.split(sp, s, false);
            case SPLIT_MANY -> StructureOps.split(sp, s, true);
            case MERGE -> StructureOps.merge(sp, s);
            case SCATTER -> StructureOps.scatter(sp, s);
            case SPREAD -> StructureOps.spread(sp, s);
            case REFORM -> StructureOps.reform(sp, s);
            case RESHAPE -> StructureOps.reshape(sp, s);
            case SOLIDIFY -> StructureOps.solidify(sp, s);
            case DISSOLVE -> StructureOps.dissolve(sp, s);
            case RELEASE_CAPTIVE -> StructureOps.releaseCaptives(sp, s);
        }
    }

    public static int countOwned(ServerPlayer sp) {
        return sp.serverLevel().getEntities(ModRegistry.CONSTRUCT.get(), c -> c.isOwnedBy(sp)).size();
    }

    private static int countHands(ServerPlayer sp) {
        return sp.serverLevel().getEntities(ModRegistry.HAND.get(), h -> h.isOwnedBy(sp)).size();
    }

    private static BlockState matter() {
        return ModRegistry.MATTER_BLOCK.get().defaultBlockState();
    }

    // ------------------------------------------------------------------ clique esquerdo

    private static void leftClick(ServerPlayer sp, PowerSession s, boolean add) {
        MatterMode mode = s.mode();
        Entity picked = PowerManager.pickOwned(sp, s);

        if (mode == MatterMode.PRISON) {
            LivingEntity target = PowerManager.pickLiving(sp, s);
            if (target != null && (picked == null || target.distanceToSqr(sp) < picked.distanceToSqr(sp))) {
                imprison(sp, s, target);
                return;
            }
        }
        if (picked != null) {
            PowerManager.select(sp, s, picked, add);
            return;
        }
        if (mode == MatterMode.MANIPULATE) {
            BlockHitResult hit = PowerManager.clipBlock(sp, s, PowerManager.reach());
            if (hit.getType() == HitResult.Type.BLOCK) {
                MatterConstructEntity c = lift(sp, s, hit.getBlockPos());
                if (c != null) PowerManager.select(sp, s, c, add);
                return;
            }
        }
        if (mode != MatterMode.HAND && !add) PowerManager.clearSelection(sp, s);
    }

    // ------------------------------------------------------------------ levantar terreno

    /** Arranca blocos reais do mundo e os transforma numa estrutura flutuante controlável. */
    private static MatterConstructEntity lift(ServerPlayer sp, PowerSession s, BlockPos center) {
        ServerLevel level = sp.serverLevel();
        if (countOwned(sp) >= MatterConfig.MAX_CONSTRUCTS.get()) { PowerManager.message(sp, "§cLimite de estruturas atingido"); return null; }
        int cap = MatterConfig.MAX_BLOCKS_PER_CONSTRUCT.get();
        List<BlockPos> picked = new ArrayList<>();
        BlockState first = level.getBlockState(center);

        if (first.getBlock() instanceof MatterBlock) {
            // estruturas construídas com matéria: pega o conjunto conectado inteiro
            ArrayDeque<BlockPos> q = new ArrayDeque<>();
            Set<BlockPos> seen = new HashSet<>();
            q.add(center); seen.add(center);
            while (!q.isEmpty() && picked.size() < cap) {
                BlockPos p = q.poll();
                BlockState st = level.getBlockState(p);
                if (!(st.getBlock() instanceof MatterBlock) || !WorldEdit.canTake(sp, p, st)) continue;
                picked.add(p);
                for (Direction d : Direction.values()) {
                    BlockPos n = p.relative(d);
                    if (seen.add(n) && n.distSqr(center) < 200 * 200) q.add(n);
                }
            }
        } else {
            int r = Mth.clamp(s.input.primary(), 0, 16);
            boolean cube = s.input.variant() % 2 == 1;
            int r2 = r * r + r;
            for (int dx = -r; dx <= r; dx++) for (int dy = -r; dy <= r; dy++) for (int dz = -r; dz <= r; dz++) {
                if (!cube && dx * dx + dy * dy + dz * dz > r2) continue;
                BlockPos p = center.offset(dx, dy, dz);
                BlockState st = level.getBlockState(p);
                if (st.isAir() || !WorldEdit.canTake(sp, p, st)) continue;
                picked.add(p);
                if (picked.size() >= cap) break;
            }
        }
        if (picked.isEmpty()) return null;
        float cost = (float) (picked.size() * MatterConfig.LIFT_COST.get());
        if (!PlayerMatter.consume(sp, cost)) { PowerManager.message(sp, "§cMatéria insuficiente para levantar"); return null; }

        ConstructShape shape = new ConstructShape(picked.size());
        List<int[]> fx = new ArrayList<>();
        for (BlockPos p : picked) {
            BlockState st = level.getBlockState(p);
            shape.add(st, p.getX() + 0.5f - center.getX(), p.getY() + 0.5f - center.getY(), p.getZ() + 0.5f - center.getZ());
            if (fx.size() < 64) fx.add(WorldEdit.fxEntry(p, st));
        }
        for (BlockPos p : picked) WorldEdit.take(level, p, level.getBlockState(p));
        float[] c = shape.recenter();
        Vec3 pos = new Vec3(center.getX() + c[0], center.getY() + c[1], center.getZ() + c[2]);
        MatterConstructEntity e = MatterConstructEntity.create(level, shape, pos, sp);
        e.setDeltaMovement(0, 0.25, 0);
        level.addFreshEntity(e);

        int[] data = new int[fx.size() * 4];
        for (int i = 0; i < fx.size(); i++) System.arraycopy(fx.get(i), 0, data, i * 4, 4);
        FxPayload.send(level, pos, FxPayload.LIFT, picked.size(), e.getId(), data);
        level.playSound(null, center, SoundEvents.ROOTED_DIRT_BREAK, SoundSource.PLAYERS, 1.5f, 0.6f);
        return e;
    }

    // ------------------------------------------------------------------ criação

    private static void create(ServerPlayer sp, PowerSession s) {
        MatterMode mode = s.mode();
        if (mode == MatterMode.HAND) createHand(sp, s);
        else if (mode == MatterMode.PROJECTILE) createProjectile(sp, s);
    }

    private static void createHand(ServerPlayer sp, PowerSession s) {
        if (countHands(sp) >= MatterConfig.MAX_HANDS.get()) { PowerManager.message(sp, "§cLimite de mãos atingido"); return; }
        float scale = 0.5f + Mth.clamp(s.input.primary(), 1, 8) * 0.5f;
        float cost = MatterHandEntity.costFor(scale);
        if (!PlayerMatter.consume(sp, cost)) { PowerManager.message(sp, "§cMatéria insuficiente (" + (int) cost + ")"); return; }
        Vec3 at = PowerManager.aimPoint(sp, s).subtract(PowerManager.dir(sp, s).scale(scale * 1.5));
        MatterHandEntity h = MatterHandEntity.create(sp.level(), at, sp, scale);
        sp.level().addFreshEntity(h);
        s.holdDistance = Math.max(2, PowerManager.origin(sp, s).distanceTo(at));
        PowerManager.select(sp, s, h, false);
        FxPayload.send(sp.serverLevel(), at, FxPayload.COMPACT, scale * 3, h.getId());
        sp.level().playSound(null, BlockPos.containing(at), SoundEvents.AMETHYST_BLOCK_RESONATE, SoundSource.PLAYERS, 2f, 0.5f);
    }

    private static void createProjectile(ServerPlayer sp, PowerSession s) {
        if (countOwned(sp) >= MatterConfig.MAX_CONSTRUCTS.get()) { PowerManager.message(sp, "§cLimite de estruturas atingido"); return; }
        int size = Mth.clamp(s.input.primary(), 1, 6);
        double r = size * 0.62;
        int n = Math.max(1, (int) Math.round(4.0 / 3.0 * Math.PI * r * r * r));
        float cost = (float) (n * MatterConfig.BUILD_COST.get());
        if (!PlayerMatter.consume(sp, cost)) { PowerManager.message(sp, "§cMatéria insuficiente (" + (int) cost + ")"); return; }
        ConstructShape shape = new ConstructShape(n);
        for (int i = 0; i < n; i++) shape.add(matter(), 0, 0, 0);
        shape.applyLayout(ConstructShape.Layout.SPHERE);
        Vec3 at = sp.getEyePosition().add(sp.getLookAngle().scale(1.5 + r));
        MatterConstructEntity e = MatterConstructEntity.create(sp.level(), shape, at, sp);
        e.orbitIndex = (int) sp.serverLevel().getEntities(ModRegistry.CONSTRUCT.get(), c -> c.isOwnedBy(sp) && c.motion() == Motion.ORBIT).size();
        e.setMotion(Motion.ORBIT);
        sp.level().addFreshEntity(e);
        FxPayload.send(sp.serverLevel(), at, FxPayload.COMPACT, (float) r * 2, e.getId());
        sp.level().playSound(null, BlockPos.containing(at), SoundEvents.AMETHYST_CLUSTER_PLACE, SoundSource.PLAYERS, 1f, 1.2f - size * 0.1f);
    }

    // ------------------------------------------------------------------ prisão

    private static void imprison(ServerPlayer sp, PowerSession s, LivingEntity target) {
        if (countOwned(sp) >= MatterConfig.MAX_CONSTRUCTS.get()) { PowerManager.message(sp, "§cLimite de estruturas atingido"); return; }
        AABB bb = target.getBoundingBox();
        int pad = Mth.clamp(s.input.primary(), 1, 6);
        double hx = bb.getXsize() / 2 + pad, hy = bb.getYsize() / 2 + pad, hz = bb.getZsize() / 2 + pad;
        boolean sphere = s.input.variant() % 2 == 1;
        ConstructShape shape = new ConstructShape(256);
        int ix = (int) Math.ceil(hx), iy = (int) Math.ceil(hy), iz = (int) Math.ceil(hz);
        for (int x = -ix; x <= ix; x++) for (int y = -iy; y <= iy; y++) for (int z = -iz; z <= iz; z++) {
            boolean shell;
            if (sphere) {
                double d = (x * x) / (hx * hx) + (y * y) / (hy * hy) + (z * z) / (hz * hz);
                double inner = (x * x) / ((hx - 1) * (hx - 1)) + (y * y) / ((hy - 1) * (hy - 1)) + (z * z) / ((hz - 1) * (hz - 1));
                shell = d <= 1.0 && inner > 1.0;
            } else {
                shell = Math.abs(x) == ix || Math.abs(y) == iy || Math.abs(z) == iz;
            }
            if (shell) shape.add(matter(), x, y, z);
        }
        float cost = (float) (shape.size * MatterConfig.PRISON_COST.get());
        if (!PlayerMatter.consume(sp, cost)) { PowerManager.message(sp, "§cMatéria insuficiente (" + (int) cost + ")"); return; }

        Vec3 center = bb.getCenter();
        // nasce "achatada" no chão e depois sobe envolvendo o alvo (animação controlada pela forma)
        ConstructShape ground = shape.copy();
        for (int i = 0; i < ground.size; i++) {
            ground.y[i] = -iy - 1;
            ground.x[i] *= 1.4f;
            ground.z[i] *= 1.4f;
        }
        ground.invalidate();
        MatterConstructEntity e = MatterConstructEntity.create(sp.level(), ground, center, sp);
        sp.level().addFreshEntity(e);
        e.imprison(target);
        s.later(2, () -> { if (e.isAlive()) e.setShape(shape); });
        PowerManager.select(sp, s, e, false);
        s.holdDistance = Math.max(2, PowerManager.origin(sp, s).distanceTo(center));
        FxPayload.send(sp.serverLevel(), center, FxPayload.IMPRISON, shape.size, e.getId());
        sp.level().playSound(null, target.blockPosition(), SoundEvents.ROOTED_DIRT_BREAK, SoundSource.PLAYERS, 2f, 0.5f);
    }

    // ------------------------------------------------------------------ arremesso

    private static void throwSelection(ServerPlayer sp, PowerSession s, ActionPayload a) {
        if (a.ctrl()) { drop(sp, s); return; }
        List<Entity> sel = PowerManager.selected(sp, s);
        double speed = a.shift() ? 3.4 : 1.9;
        Vec3 dir = PowerManager.dir(sp, s);
        if (sel.isEmpty()) {
            // sem seleção: dispara os projéteis em órbita na direção da mira
            List<MatterConstructEntity> orbit = new ArrayList<>(sp.serverLevel().getEntities(ModRegistry.CONSTRUCT.get(), c -> c.isOwnedBy(sp) && c.motion() == Motion.ORBIT));
            if (orbit.isEmpty()) return;
            MatterConstructEntity c = orbit.get(0);
            launchToward(sp, s, c, speed);
            return;
        }
        for (Entity e : sel) {
            if (e instanceof MatterConstructEntity c) launchToward(sp, s, c, speed);
            else if (e instanceof MatterHandEntity h) h.throwGrabbed(dir.scale(speed * 1.2).add(0, 0.2, 0));
        }
        s.moving = false;
        sp.level().playSound(null, sp.blockPosition(), SoundEvents.PLAYER_ATTACK_SWEEP, SoundSource.PLAYERS, 1f, 0.5f);
    }

    private static void launchToward(ServerPlayer sp, PowerSession s, MatterConstructEntity c, double speed) {
        Vec3 aim = PowerManager.clipBlock(sp, s, PowerManager.reach()).getLocation();
        Vec3 dir = aim.subtract(c.position());
        if (dir.lengthSqr() < 1) dir = PowerManager.dir(sp, s);
        double heavy = Math.pow(1 + c.blockCount() / 4000.0, 0.25);
        c.launch(dir.normalize().scale(speed / heavy));
    }

    private static void drop(ServerPlayer sp, PowerSession s) {
        for (Entity e : PowerManager.selected(sp, s)) {
            if (e instanceof MatterConstructEntity c) c.drop();
            else if (e instanceof MatterHandEntity h) h.releaseEverything();
        }
        PowerManager.clearSelection(sp, s);
    }

    // ------------------------------------------------------------------ perseguição

    /** Cada projétil escolhe um alvo diferente dentro do cone da mira. */
    private static void homing(ServerPlayer sp, PowerSession s) {
        List<MatterConstructEntity> shots = new ArrayList<>();
        for (Entity e : PowerManager.selected(sp, s)) if (e instanceof MatterConstructEntity c) shots.add(c);
        if (shots.isEmpty()) shots = new ArrayList<>(sp.serverLevel().getEntities(ModRegistry.CONSTRUCT.get(), c -> c.isOwnedBy(sp) && c.motion() == Motion.ORBIT));
        if (shots.isEmpty()) { PowerManager.message(sp, "§7Nenhum projétil"); return; }

        Vec3 o = PowerManager.origin(sp, s), d = PowerManager.dir(sp, s);
        double range = Math.min(96, PowerManager.reach());
        List<LivingEntity> targets = new ArrayList<>(sp.level().getEntitiesOfClass(LivingEntity.class, sp.getBoundingBox().inflate(range), le -> {
            if (le == sp || !le.isAlive() || le.isSpectator()) return false;
            if (le instanceof net.minecraft.world.entity.player.Player && !MatterConfig.AFFECT_PLAYERS.get()) return false;
            Vec3 to = le.getBoundingBox().getCenter().subtract(o);
            return to.length() < range && to.normalize().dot(d) > Math.cos(Math.toRadians(35)) && sp.hasLineOfSight(le);
        }));
        if (targets.isEmpty()) { PowerManager.message(sp, "§7Nenhum alvo na mira"); return; }
        targets.sort(Comparator.comparingDouble(le -> -le.getBoundingBox().getCenter().subtract(o).normalize().dot(d)));
        for (int i = 0; i < shots.size(); i++) {
            MatterConstructEntity c = shots.get(i);
            LivingEntity t = targets.get(i % targets.size());
            c.homingTarget = t.getId();
            c.launch(t.getBoundingBox().getCenter().subtract(c.position()).normalize().scale(1.4));
            c.setMotion(Motion.HOMING);
        }
        PowerManager.message(sp, "§d" + shots.size() + " projéteis → " + Math.min(shots.size(), targets.size()) + " alvos");
    }
}
