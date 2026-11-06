package com.mattercontrol.server;

import com.mattercontrol.config.MatterConfig;
import com.mattercontrol.core.MatterValues;
import com.mattercontrol.core.PlayerMatter;
import com.mattercontrol.network.FxPayload;
import com.mattercontrol.network.InputPayload;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Absorção: enquanto a tecla está pressionada, os blocos na região mirada são
 * rachados e desmontados progressivamente, do centro para fora, virando matéria.
 */
public final class AbsorbHandler {
    private AbsorbHandler() {}

    public static final class State {
        BlockPos center;
        int radius;
        final ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        final List<BlockPos> cracking = new ArrayList<>();
        int soundCooldown;
        boolean fullWarned;
    }

    public static void tick(ServerPlayer sp, PowerSession s) {
        if (!s.held(InputPayload.ABSORB)) { stop(sp, s); return; }
        if (!MatterConfig.ALLOW_TERRAIN_EDIT.get()) return;
        ServerLevel level = sp.serverLevel();

        BlockHitResult hit = PowerManager.clipBlock(sp, s, PowerManager.reach());
        State st = s.absorb == null ? (s.absorb = new State()) : s.absorb;
        int radius = Mth.clamp(s.input.absorbRadius(), 0, MatterConfig.ABSORB_MAX_RADIUS.get());

        // 1) blocos rachados no tick anterior são desmontados agora
        List<int[]> fx = new ArrayList<>();
        float gained = 0;
        for (BlockPos pos : st.cracking) {
            BlockState state = level.getBlockState(pos);
            level.destroyBlockProgress(breaker(sp, pos), pos, -1);
            if (!WorldEdit.canTake(sp, pos, state)) continue;
            float value = MatterValues.valueOf(state);
            if (PlayerMatter.space(sp) < value) {
                if (!st.fullWarned) { PowerManager.message(sp, "§dBarra de matéria cheia!"); st.fullWarned = true; }
                continue;
            }
            PlayerMatter.add(sp, value);
            gained += value;
            WorldEdit.take(level, pos, state);
            if (fx.size() < 96) fx.add(WorldEdit.fxEntry(pos, state));
            if (st.soundCooldown <= 0) {
                level.playSound(null, pos, state.getSoundType().getBreakSound(), SoundSource.BLOCKS, 0.7f, 0.8f + level.random.nextFloat() * 0.4f);
                st.soundCooldown = 2;
            }
        }
        st.cracking.clear();
        st.soundCooldown--;
        if (!fx.isEmpty()) {
            int[] data = new int[fx.size() * 4];
            for (int i = 0; i < fx.size(); i++) System.arraycopy(fx.get(i), 0, data, i * 4, 4);
            BlockPos c = st.center != null ? st.center : sp.blockPosition();
            FxPayload.send(level, c.getCenter(), FxPayload.ABSORB, gained, sp.getId(), data);
        }

        if (hit.getType() != HitResult.Type.BLOCK) return;
        BlockPos center = hit.getBlockPos();

        // 2) recalcula a fila se a mira andou ou o raio mudou
        if (st.center == null || radius != st.radius || st.center.distSqr(center) > Math.max(1, radius * radius / 4.0)) {
            st.center = center;
            st.radius = radius;
            st.queue.clear();
            st.fullWarned = false;
            List<BlockPos> list = new ArrayList<>();
            int r2 = radius * radius + radius;
            for (int dx = -radius; dx <= radius; dx++)
                for (int dy = -radius; dy <= radius; dy++)
                    for (int dz = -radius; dz <= radius; dz++)
                        if (dx * dx + dy * dy + dz * dz <= r2) list.add(center.offset(dx, dy, dz));
            list.sort((a, b) -> Double.compare(a.distSqr(center), b.distSqr(center)));
            st.queue.addAll(list);
        }

        // 3) racha os próximos blocos (feedback visual antes de sumirem)
        int rate = MatterConfig.ABSORB_BLOCKS_PER_TICK.get();
        if (s.held(InputPayload.FAST)) rate *= 2;
        if (s.held(InputPayload.PRECISE)) rate = Math.max(1, rate / 4);
        int scanned = 0;
        while (st.cracking.size() < rate && !st.queue.isEmpty() && scanned < rate * 64) {
            BlockPos pos = st.queue.poll();
            scanned++;
            BlockState state = level.getBlockState(pos);
            if (state.isAir() || !MatterValues.canTake(level, pos, state)) continue;
            st.cracking.add(pos);
            level.destroyBlockProgress(breaker(sp, pos), pos, 6 + level.random.nextInt(3));
        }
    }

    public static void stop(ServerPlayer sp, PowerSession s) {
        if (s.absorb == null) return;
        for (BlockPos pos : s.absorb.cracking) sp.serverLevel().destroyBlockProgress(breaker(sp, pos), pos, -1);
        s.absorb = null;
    }

    private static int breaker(ServerPlayer sp, BlockPos pos) {
        return 0x4D430000 ^ (sp.getId() * 31 + pos.hashCode());
    }
}
