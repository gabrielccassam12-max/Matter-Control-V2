package com.mattercontrol.server;

import com.mattercontrol.block.MatterBlock;
import com.mattercontrol.config.MatterConfig;
import com.mattercontrol.core.MatterValues;
import com.mattercontrol.core.PlayerMatter;
import com.mattercontrol.entity.ConstructShape;
import com.mattercontrol.entity.MatterConstructEntity;
import com.mattercontrol.entity.MatterHandEntity;
import com.mattercontrol.network.FxPayload;
import com.mattercontrol.registry.ModRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Transformações de estruturas: dividir, juntar, dispersar, remodelar, solidificar, dissolver. */
public final class StructureOps {
    private StructureOps() {}

    private static final ConstructShape.Layout[] CYCLE = {
            ConstructShape.Layout.SPHERE, ConstructShape.Layout.WALL, ConstructShape.Layout.TOWER,
            ConstructShape.Layout.PLATFORM, ConstructShape.Layout.CUBE};
    private static final String[] CYCLE_NAMES = {"Esfera", "Parede", "Torre", "Plataforma", "Cubo"};

    private static List<MatterConstructEntity> constructs(ServerPlayer sp, PowerSession s) {
        List<MatterConstructEntity> out = new ArrayList<>();
        for (Entity e : PowerManager.selected(sp, s)) if (e instanceof MatterConstructEntity c) out.add(c);
        return out;
    }

    // ------------------------------------------------------------------ dividir

    public static void split(ServerPlayer sp, PowerSession s, boolean many) {
        List<MatterConstructEntity> list = constructs(sp, s);
        if (list.isEmpty()) return;
        List<Entity> newSel = new ArrayList<>();
        for (MatterConstructEntity c : list) {
            newSel.addAll(splitInto(sp, c, many ? 3 : 0, 0.25, false));
        }
        PowerManager.clearSelection(sp, s);
        for (Entity e : newSel) PowerManager.select(sp, s, e, true);
    }

    /**
     * Divide uma estrutura. cell == 0 → duas metades (plano perpendicular à direita do jogador);
     * cell > 0 → pedaços cúbicos desse tamanho.
     */
    static List<MatterConstructEntity> splitInto(ServerPlayer sp, MatterConstructEntity c, int cell, double speed, boolean launch) {
        List<MatterConstructEntity> out = new ArrayList<>();
        ConstructShape sh = c.shape();
        if (sh.size < 2) { out.add(c); return out; }
        ServerLevel level = sp.serverLevel();
        Quaternionf q = c.rotation();
        float sc = c.scale();
        float yaw = sp.getYRot() * Mth.DEG_TO_RAD;
        Vec3 right = new Vec3(Mth.cos(yaw), 0, Mth.sin(yaw));

        int available = MatterConfig.MAX_CONSTRUCTS.get() - ActionHandler.countOwned(sp) + 1;
        Map<Long, ConstructShape> parts;
        while (true) {
            parts = new LinkedHashMap<>();
            float[] dots = new float[sh.size];
            float median = 0;
            if (cell == 0) {
                for (int i = 0; i < sh.size; i++) {
                    Vector3f w = new Vector3f(sh.x[i], sh.y[i], sh.z[i]);
                    q.transform(w);
                    dots[i] = (float) (w.x * right.x + w.z * right.z);
                }
                float[] sorted = dots.clone();
                java.util.Arrays.sort(sorted);
                median = sorted[sorted.length / 2];
            }
            for (int i = 0; i < sh.size; i++) {
                long key = cell == 0 ? (dots[i] >= median ? 1 : 0)
                        : BlockPos.asLong(Mth.floor(sh.x[i] / cell), Mth.floor(sh.y[i] / cell), Mth.floor(sh.z[i] / cell));
                parts.computeIfAbsent(key, k -> new ConstructShape()).add(sh.states[i], sh.x[i], sh.y[i], sh.z[i]);
            }
            if (cell == 0 || parts.size() <= Math.max(2, available) || cell > 64) break;
            cell++;
        }
        if (parts.size() < 2 || parts.size() > Math.max(2, available)) {
            PowerManager.message(sp, "§cNão é possível dividir mais (limite de estruturas)");
            out.add(c);
            return out;
        }
        if (c.captive() != null) c.releaseCaptive();
        Vec3 base = c.position();
        for (ConstructShape part : parts.values()) {
            float[] cen = part.recenter();
            Vector3f off = new Vector3f(cen[0] * sc, cen[1] * sc, cen[2] * sc);
            q.transform(off);
            Vec3 o = new Vec3(off.x, off.y, off.z);
            MatterConstructEntity p = MatterConstructEntity.create(level, part, base.add(o), sp);
            p.setScale(sc);
            p.setRotation(c.rotYaw(), c.rotPitch(), c.rotRoll());
            Vec3 push = o.lengthSqr() < 1e-4 ? new Vec3(level.random.nextGaussian(), 0.5, level.random.nextGaussian()) : o;
            push = push.normalize().scale(speed);
            if (launch) p.launch(push.add(0, speed * 0.4, 0));
            else p.setDeltaMovement(push);
            level.addFreshEntity(p);
            out.add(p);
        }
        c.discard();
        FxPayload.send(level, base, FxPayload.SPLIT, parts.size(), sp.getId());
        level.playSound(null, BlockPos.containing(base), SoundEvents.STONE_BREAK, SoundSource.PLAYERS, 1.5f, 0.6f);
        return out;
    }

    // ------------------------------------------------------------------ juntar

    public static void merge(ServerPlayer sp, PowerSession s) {
        List<MatterConstructEntity> list = constructs(sp, s);
        if (list.size() < 2) { PowerManager.message(sp, "§7Selecione 2+ pedaços (Shift+clique) para juntar"); return; }
        int total = 0;
        double mass = 0;
        Vec3 center = Vec3.ZERO;
        for (MatterConstructEntity c : list) {
            total += c.blockCount();
            center = center.add(c.position().scale(c.blockCount()));
            mass += c.blockCount();
        }
        if (total > MatterConfig.MAX_BLOCKS_PER_CONSTRUCT.get()) { PowerManager.message(sp, "§cGrande demais para uma única estrutura"); return; }
        center = center.scale(1.0 / mass);
        ConstructShape union = new ConstructShape(total);
        for (MatterConstructEntity c : list) {
            Quaternionf q = c.rotation();
            float sc = c.scale();
            ConstructShape sh = c.shape();
            Vec3 rel = c.position().subtract(center);
            for (int i = 0; i < sh.size; i++) {
                Vector3f w = new Vector3f(sh.x[i] * sc, sh.y[i] * sc, sh.z[i] * sc);
                q.transform(w);
                union.add(sh.states[i], (float) (w.x + rel.x), (float) (w.y + rel.y), (float) (w.z + rel.z));
            }
            if (c.captive() != null) c.releaseCaptive();
            c.discard();
        }
        ServerLevel level = sp.serverLevel();
        MatterConstructEntity merged = MatterConstructEntity.create(level, union, center, sp);
        level.addFreshEntity(merged);
        PowerManager.select(sp, s, merged, false);
        // os pedaços voam e se encaixam numa massa compacta
        s.later(2, () -> {
            if (!merged.isAlive()) return;
            ConstructShape packed = merged.shape().copy();
            packed.applyLayout(ConstructShape.Layout.SPHERE);
            merged.setShape(packed);
        });
        FxPayload.send(level, center, FxPayload.COMPACT, (float) merged.boundRadius(), merged.getId());
        level.playSound(null, BlockPos.containing(center), SoundEvents.AMETHYST_BLOCK_RESONATE, SoundSource.PLAYERS, 2f, 0.4f);
    }

    // ------------------------------------------------------------------ dispersar / espalhar / reconstruir

    public static void scatter(ServerPlayer sp, PowerSession s) {
        for (MatterConstructEntity c : constructs(sp, s)) {
            c.setSpreadTarget(c.spreadTarget() > 1.5f ? 6f : 4f);
            FxPayload.send(sp.serverLevel(), c.position(), FxPayload.SPLIT, c.blockCount(), c.getId());
        }
        sp.level().playSound(null, sp.blockPosition(), SoundEvents.AMETHYST_BLOCK_BREAK, SoundSource.PLAYERS, 1.5f, 0.5f);
    }

    public static void spread(ServerPlayer sp, PowerSession s) {
        List<Entity> newSel = new ArrayList<>();
        for (MatterConstructEntity c : constructs(sp, s)) newSel.addAll(splitInto(sp, c, 2, 1.1, true));
        PowerManager.clearSelection(sp, s);
        for (Entity e : newSel) PowerManager.select(sp, s, e, true);
    }

    public static void reform(ServerPlayer sp, PowerSession s) {
        List<MatterConstructEntity> list = constructs(sp, s);
        if (list.size() >= 2) { merge(sp, s); list = constructs(sp, s); }
        for (MatterConstructEntity c : list) c.setSpreadTarget(1f);
    }

    // ------------------------------------------------------------------ remodelar

    public static void reshape(ServerPlayer sp, PowerSession s) {
        List<MatterConstructEntity> list = constructs(sp, s);
        if (list.isEmpty()) return;
        int idx = Math.floorMod(s.layoutCycle++, CYCLE.length);
        for (MatterConstructEntity c : list) {
            if (c.captive() != null) continue;
            ConstructShape sh = c.shape().copy();
            sh.applyLayout(CYCLE[idx]);
            c.setShape(sh);
            c.setRotation(-sp.getYRot(), 0, 0);
        }
        PowerManager.message(sp, "§dForma: " + CYCLE_NAMES[idx]);
    }

    // ------------------------------------------------------------------ solidificar

    /** Fixa a estrutura no mundo como blocos. Matéria compactada vira blocos densos e resistentes. */
    public static void solidify(ServerPlayer sp, PowerSession s) {
        ServerLevel level = sp.serverLevel();
        float unitValue = MatterConfig.BUILD_COST.get().floatValue();
        for (MatterConstructEntity c : constructs(sp, s)) {
            Quaternionf q = c.rotation();
            float sc = c.scale();
            Map<BlockPos, List<BlockState>> cells = new HashMap<>();
            ConstructShape sh = c.shape();
            for (int i = 0; i < sh.size; i++) {
                Vec3 w = c.worldPosOf(i, c.position(), q, sc, 1f);
                cells.computeIfAbsent(BlockPos.containing(w), k -> new ArrayList<>()).add(sh.states[i]);
            }
            float refund = 0;
            List<int[]> fx = new ArrayList<>();
            for (Map.Entry<BlockPos, List<BlockState>> en : cells.entrySet()) {
                BlockPos p = en.getKey();
                List<BlockState> states = en.getValue();
                if (!WorldEdit.canPlace(sp, p)) {
                    for (BlockState st : states) refund += MatterValues.valueOf(st);
                    continue;
                }
                BlockState place;
                if (states.size() == 1) {
                    place = states.get(0);
                } else {
                    int units = 0;
                    for (BlockState st : states) units += st.getBlock() instanceof MatterBlock ? MatterBlock.unitsOf(st) : 1;
                    int d = Math.min(3, 31 - Integer.numberOfLeadingZeros(units));
                    place = ModRegistry.MATTER_BLOCK.get().defaultBlockState().setValue(MatterBlock.DENSITY, d);
                    refund += (units - (1 << d)) * unitValue;
                }
                WorldEdit.place(level, p, place, false);
                if (fx.size() < 96) fx.add(WorldEdit.fxEntry(p, place));
            }
            if (refund > 0) PlayerMatter.add(sp, refund);
            if (c.captive() != null) c.releaseCaptive();
            int[] data = new int[fx.size() * 4];
            for (int i = 0; i < fx.size(); i++) System.arraycopy(fx.get(i), 0, data, i * 4, 4);
            FxPayload.send(level, c.position(), FxPayload.BUILD, cells.size(), sp.getId(), data);
            level.playSound(null, c.blockPosition(), SoundEvents.ANVIL_PLACE, SoundSource.PLAYERS, 0.8f, 0.7f);
            c.discard();
        }
        PowerManager.clearSelection(sp, s);
    }

    // ------------------------------------------------------------------ dissolver

    /** Desintegra em partículas que voltam para o jogador como matéria. */
    public static void dissolve(ServerPlayer sp, PowerSession s) {
        ServerLevel level = sp.serverLevel();
        float total = 0;
        for (Entity e : PowerManager.selected(sp, s)) {
            if (e instanceof MatterConstructEntity c) {
                ConstructShape sh = c.shape();
                Quaternionf q = c.rotation();
                List<int[]> fx = new ArrayList<>();
                int step = Math.max(1, sh.size / 96);
                for (int i = 0; i < sh.size; i++) {
                    total += MatterValues.valueOf(sh.states[i]);
                    if (i % step == 0) fx.add(WorldEdit.fxEntry(BlockPos.containing(c.worldPosOf(i, c.position(), q, c.scale(), c.spread())), sh.states[i]));
                }
                int[] data = new int[fx.size() * 4];
                for (int i = 0; i < fx.size(); i++) System.arraycopy(fx.get(i), 0, data, i * 4, 4);
                FxPayload.send(level, c.position(), FxPayload.DISSOLVE, sh.size, sp.getId(), data);
                if (c.captive() != null) c.releaseCaptive();
                c.discard();
            } else if (e instanceof MatterHandEntity h) {
                total += MatterHandEntity.costFor(h.handScale()) * 0.9f;
                h.releaseEverything();
                FxPayload.send(level, h.position(), FxPayload.DISSOLVE, h.handScale() * 20, sp.getId());
                h.discard();
            }
        }
        float got = PlayerMatter.add(sp, total);
        if (got + 0.01f < total) PowerManager.message(sp, "§eBarra cheia: " + (int) (total - got) + " de matéria se perdeu");
        level.playSound(null, sp.blockPosition(), SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 1.5f, 0.6f);
        PowerManager.clearSelection(sp, s);
    }

    public static void releaseCaptives(ServerPlayer sp, PowerSession s) {
        for (MatterConstructEntity c : constructs(sp, s)) c.releaseCaptive();
        for (Entity e : PowerManager.selected(sp, s)) if (e instanceof MatterHandEntity h) h.releaseEverything();
    }
}
