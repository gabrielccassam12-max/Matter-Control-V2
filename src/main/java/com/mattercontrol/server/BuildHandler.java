package com.mattercontrol.server;

import com.mattercontrol.config.MatterConfig;
import com.mattercontrol.core.MatterMode;
import com.mattercontrol.core.PlayerMatter;
import com.mattercontrol.entity.ConstructShape;
import com.mattercontrol.entity.MatterConstructEntity;
import com.mattercontrol.network.FxPayload;
import com.mattercontrol.network.InputPayload;
import com.mattercontrol.registry.ModRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Construção em tempo real: enquanto o botão direito estiver pressionado a estrutura cresce;
 * soltar interrompe instantaneamente. Tudo é posicionado pela mira.
 */
public final class BuildHandler {
    private BuildHandler() {}

    public static final class State {
        MatterMode mode;
        int variant, primary, secondary;
        Vec3 anchor;
        Vec3 right, forward;
        Vec3 playerPos;
        int layer;
        final ArrayDeque<BlockPos> pending = new ArrayDeque<>();
        final Set<BlockPos> done = new HashSet<>();
        BlockPos stairPos;
        int stairTimer;
        float platformRadius;
        int sphereId = -1;
        boolean outOfMatter;
    }

    private static BlockState matter() {
        return ModRegistry.MATTER_BLOCK.get().defaultBlockState();
    }

    public static void tick(ServerPlayer sp, PowerSession s) {
        MatterMode mode = s.mode();
        if (!mode.isBuilder() || !s.held(InputPayload.RIGHT)) {
            if (s.build != null) finish(sp, s);
            return;
        }
        State st = s.build;
        if (st == null || st.mode != mode) {
            st = start(sp, s, mode);
            if (st == null) return;
            s.build = st;
        }
        // parâmetros podem ser ajustados durante a construção
        st.primary = Mth.clamp(s.input.primary(), 1, mode.maxPrimary());
        st.secondary = Mth.clamp(s.input.secondary(), 1, mode.maxSecondary());

        if (mode == MatterMode.SPHERE) { growSphere(sp, s, st); return; }
        if (mode == MatterMode.PLATFORM && st.variant == 1) refillPath(sp, s, st);
        if (mode == MatterMode.PLATFORM && st.variant == 2) refillStairs(sp, s, st);
        if (mode == MatterMode.PLATFORM && st.variant == 0 && st.pending.isEmpty()) refillArea(st);
        if ((mode == MatterMode.TOWER || mode == MatterMode.WALL || mode == MatterMode.PILLAR) && st.pending.isEmpty()) {
            if (st.layer >= MatterConfig.MAX_BUILD_HEIGHT.get()) return;
            refillLayer(sp, st);
            st.layer++;
        }
        placePending(sp, s, st);
    }

    private static State start(ServerPlayer sp, PowerSession s, MatterMode mode) {
        State st = new State();
        st.mode = mode;
        st.variant = Math.floorMod(s.input.variant(), mode.variants.length);
        st.primary = s.input.primary();
        st.secondary = s.input.secondary();
        st.playerPos = sp.position();
        float yaw = sp.getYRot() * Mth.DEG_TO_RAD;
        st.forward = new Vec3(-Mth.sin(yaw), 0, Mth.cos(yaw));
        st.right = new Vec3(Mth.cos(yaw), 0, Mth.sin(yaw));

        BlockHitResult hit = PowerManager.clipBlock(sp, s, PowerManager.reach());
        if (hit.getType() == HitResult.Type.BLOCK) {
            BlockPos base = hit.getBlockPos().relative(hit.getDirection());
            if (mode == MatterMode.PILLAR || mode == MatterMode.TOWER || mode == MatterMode.WALL) {
                // nasce do chão: se mirou numa parede, desce até o chão
                if (hit.getDirection() != Direction.UP) base = groundBelow(sp.serverLevel(), base);
            }
            st.anchor = Vec3.atBottomCenterOf(base);
            if (mode == MatterMode.SPHERE) st.anchor = hit.getLocation().subtract(PowerManager.dir(sp, s).scale(st.primary + 1));
        } else {
            st.anchor = PowerManager.holdPoint(sp, s);
        }
        st.stairPos = BlockPos.containing(st.anchor);
        s.holdDistance = Math.max(2, PowerManager.origin(sp, s).distanceTo(st.anchor));
        return st;
    }

    private static BlockPos groundBelow(ServerLevel level, BlockPos from) {
        BlockPos.MutableBlockPos p = from.mutable();
        for (int i = 0; i < 24 && p.getY() > level.getMinBuildHeight(); i++) {
            if (!level.getBlockState(p.below()).canBeReplaced()) return p.immutable();
            p.move(Direction.DOWN);
        }
        return from;
    }

    private static void finish(ServerPlayer sp, PowerSession s) {
        State st = s.build;
        s.build = null;
        if (st != null && st.sphereId >= 0 && sp.level().getEntity(st.sphereId) instanceof MatterConstructEntity c) {
            PowerManager.select(sp, s, c, false);
        }
    }

    // ------------------------------------------------------------------ camadas

    private static void refillLayer(ServerPlayer sp, State st) {
        int h = st.layer;
        LinkedHashSet<BlockPos> layer = new LinkedHashSet<>();
        switch (st.mode) {
            case TOWER -> footprint(layer, st.anchor.add(0, h, 0), st.primary, st.variant);
            case PILLAR -> {
                int[] counts = {1, 3, 5, 8};
                int n = counts[Math.min(st.variant, 3)];
                double ring = n == 1 ? 0 : st.secondary + st.primary * 1.5;
                for (int i = 0; i < n; i++) {
                    double a = Math.PI * 2 * i / n;
                    Vec3 c = st.anchor.add(Math.cos(a) * ring, 0, Math.sin(a) * ring);
                    BlockPos g = groundBelow(sp.serverLevel(), BlockPos.containing(c.x, st.anchor.y + 3, c.z));
                    footprint(layer, Vec3.atBottomCenterOf(g).add(0, h, 0), st.primary, 0);
                }
            }
            case WALL -> {
                double len = st.primary;
                for (double t = -len / 2; t <= len / 2; t += 0.5) {
                    for (double k = 0; k < st.secondary; k += 0.5) {
                        Vec3 p;
                        switch (st.variant) {
                            case 1 -> p = st.anchor.add(st.right.scale(t)).add(st.forward.scale(k + h * 0.6)).add(0, h, 0);
                            case 2 -> {
                                Vec3 rel = st.anchor.subtract(st.playerPos);
                                double radius = Math.max(4, Math.hypot(rel.x, rel.z)) + k;
                                double base = Math.atan2(rel.z, rel.x);
                                double ang = base + t / radius;
                                p = new Vec3(st.playerPos.x + Math.cos(ang) * radius, st.anchor.y + h, st.playerPos.z + Math.sin(ang) * radius);
                            }
                            case 3 -> p = st.anchor.add(st.right.scale(t)).add(st.forward.scale(h)).add(0, k, 0);
                            default -> p = st.anchor.add(st.right.scale(t)).add(st.forward.scale(k)).add(0, h, 0);
                        }
                        layer.add(BlockPos.containing(p));
                    }
                }
            }
            default -> {}
        }
        layer.removeAll(st.done);
        st.pending.addAll(layer);
    }

    private static void footprint(Set<BlockPos> out, Vec3 center, int w, int shape) {
        double r = w / 2.0;
        int ri = (int) Math.ceil(r);
        for (int dx = -ri; dx <= ri; dx++) for (int dz = -ri; dz <= ri; dz++) {
            double fx = dx + (w % 2 == 0 ? 0.5 : 0), fz = dz + (w % 2 == 0 ? 0.5 : 0);
            boolean in = switch (shape) {
                case 1 -> fx * fx + fz * fz <= r * r + 0.3;
                case 2 -> (Math.abs(fx) <= Math.max(0.5, r / 3) && Math.abs(fz) <= r) || (Math.abs(fz) <= Math.max(0.5, r / 3) && Math.abs(fx) <= r);
                default -> Math.abs(fx) <= r && Math.abs(fz) <= r;
            };
            if (in) out.add(BlockPos.containing(center.x + fx, center.y, center.z + fz));
        }
    }

    private static void refillArea(State st) {
        if (st.platformRadius >= st.primary) return;
        st.platformRadius = Math.min(st.primary, st.platformRadius + 1);
        float r = st.platformRadius;
        int ri = (int) Math.ceil(r);
        BlockPos c = BlockPos.containing(st.anchor);
        for (int dx = -ri; dx <= ri; dx++) for (int dz = -ri; dz <= ri; dz++) {
            if (dx * dx + dz * dz > r * r + 0.5) continue;
            for (int t = 0; t < st.secondary; t++) {
                BlockPos p = c.offset(dx, -t, dz);
                if (!st.done.contains(p)) st.pending.add(p);
            }
        }
    }

    /** Caminho/ponte suspensa: pinta matéria exatamente onde o mouse aponta. */
    private static void refillPath(ServerPlayer sp, PowerSession s, State st) {
        Vec3 aim = PowerManager.holdPoint(sp, s);
        BlockHitResult hit = PowerManager.clipBlock(sp, s, s.holdDistance);
        if (hit.getType() == HitResult.Type.BLOCK) aim = Vec3.atCenterOf(hit.getBlockPos().relative(hit.getDirection()));
        double r = st.primary / 2.0;
        int ri = (int) Math.ceil(r);
        BlockPos c = BlockPos.containing(aim);
        for (int dx = -ri; dx <= ri; dx++) for (int dz = -ri; dz <= ri; dz++) {
            if (dx * dx + dz * dz > r * r + 0.5) continue;
            for (int t = 0; t < st.secondary; t++) {
                BlockPos p = c.offset(dx, -t, dz);
                if (!st.done.contains(p) && !st.pending.contains(p)) st.pending.add(p);
            }
        }
    }

    /** Escada que segue a direção do olhar: olhar para cima sobe, para baixo desce. */
    private static void refillStairs(ServerPlayer sp, PowerSession s, State st) {
        if (!st.pending.isEmpty() || --st.stairTimer > 0) return;
        st.stairTimer = s.held(InputPayload.FAST) ? 1 : s.held(InputPayload.PRECISE) ? 6 : 3;
        Vec3 d = PowerManager.dir(sp, s);
        Direction facing = Direction.getNearest(d.x, 0, d.z);
        float pitch = sp.getXRot();
        int dy = pitch < -12 ? 1 : pitch > 12 ? -1 : 0;
        BlockPos next = st.stairPos.relative(facing).above(dy);
        st.stairPos = next;
        Direction side = facing.getClockWise();
        int w = st.primary;
        for (int i = 0; i < w; i++) {
            BlockPos p = next.relative(side, i - (w - 1) / 2);
            if (!st.done.contains(p)) st.pending.add(p);
        }
    }

    private static void placePending(ServerPlayer sp, PowerSession s, State st) {
        ServerLevel level = sp.serverLevel();
        int rate = MatterConfig.BUILD_BLOCKS_PER_TICK.get();
        if (s.held(InputPayload.FAST)) rate *= 2;
        if (s.held(InputPayload.PRECISE)) rate = Math.max(1, rate / 4);
        float cost = MatterConfig.BUILD_COST.get().floatValue();
        List<int[]> fx = new ArrayList<>();
        int placed = 0;
        boolean launch = st.mode == MatterMode.PILLAR;
        while (placed < rate && !st.pending.isEmpty()) {
            BlockPos p = st.pending.poll();
            st.done.add(p);
            if (!WorldEdit.canPlace(sp, p)) continue;
            if (!PlayerMatter.consume(sp, cost)) {
                if (!st.outOfMatter) PowerManager.message(sp, "§cMatéria insuficiente");
                st.outOfMatter = true;
                st.pending.clear();
                return;
            }
            WorldEdit.place(level, p, matter(), launch);
            placed++;
            if (fx.size() < 96) fx.add(WorldEdit.fxEntry(p, matter()));
        }
        if (!fx.isEmpty()) {
            int[] data = new int[fx.size() * 4];
            for (int i = 0; i < fx.size(); i++) System.arraycopy(fx.get(i), 0, data, i * 4, 4);
            FxPayload.send(level, st.anchor, FxPayload.BUILD, placed, sp.getId(), data);
            if (level.getGameTime() % 3 == 0) {
                level.playSound(null, BlockPos.containing(st.anchor), SoundEvents.AMETHYST_BLOCK_PLACE, SoundSource.PLAYERS, 0.8f, 0.6f + level.random.nextFloat() * 0.3f);
            }
        }
    }

    // ------------------------------------------------------------------ esfera

    private static void growSphere(ServerPlayer sp, PowerSession s, State st) {
        ServerLevel level = sp.serverLevel();
        MatterConstructEntity sphere = st.sphereId >= 0 && level.getEntity(st.sphereId) instanceof MatterConstructEntity c ? c : null;
        int maxBlocks = Math.min(MatterConfig.MAX_BLOCKS_PER_CONSTRUCT.get(), (int) (4.0 / 3.0 * Math.PI * Math.pow(st.primary, 3)));
        int rate = MatterConfig.BUILD_BLOCKS_PER_TICK.get();
        if (s.held(InputPayload.FAST)) rate *= 2;
        if (s.held(InputPayload.PRECISE)) rate = Math.max(1, rate / 4);
        int current = sphere == null ? 0 : sphere.blockCount();
        int add = Math.min(rate, maxBlocks - current);
        float cost = MatterConfig.BUILD_COST.get().floatValue();
        if (add <= 0) {
            if (sphere != null) sphere.setControl(PowerManager.holdPoint(sp, s), 0.35, 2.0);
            return;
        }
        if (!PlayerMatter.has(sp, cost * add)) {
            add = (int) (PlayerMatter.get(sp) / Math.max(cost, 1e-4f));
            if (add <= 0) { PowerManager.message(sp, "§cMatéria insuficiente"); return; }
        }
        PlayerMatter.consume(sp, cost * add);
        ConstructShape shape = sphere == null ? new ConstructShape(add) : sphere.shape().copy();
        for (int i = 0; i < add; i++) shape.add(matter(), 0, 0, 0);
        shape.applyLayout(ConstructShape.Layout.SPHERE);
        if (sphere == null) {
            if (ActionHandler.countOwned(sp) >= MatterConfig.MAX_CONSTRUCTS.get()) {
                PowerManager.message(sp, "§cLimite de estruturas atingido");
                PlayerMatter.add(sp, cost * add);
                s.build = null;
                return;
            }
            sphere = MatterConstructEntity.create(level, shape, st.anchor, sp);
            level.addFreshEntity(sphere);
            st.sphereId = sphere.getId();
            s.holdDistance = Math.max(2, PowerManager.origin(sp, s).distanceTo(st.anchor));
        } else {
            sphere.setShape(shape);
        }
        sphere.setControl(PowerManager.holdPoint(sp, s), 0.35, 2.0);
        if (level.getGameTime() % 2 == 0) {
            FxPayload.send(level, sphere.position(), FxPayload.COMPACT, (float) sphere.boundRadius(), sphere.getId());
        }
    }
}
