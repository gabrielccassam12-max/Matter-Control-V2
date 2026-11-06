package com.mattercontrol.entity;

import net.minecraft.core.HolderGetter;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Conjunto de blocos reais que formam uma estrutura de matéria.
 * Posições locais (sem rotação e sem escala) relativas ao centro da entidade.
 */
public final class ConstructShape {
    public enum Layout { ORIGINAL, SPHERE, CUBE, WALL, TOWER, PLATFORM }

    public BlockState[] states;
    public float[] x, y, z;
    public int size;
    /** Raio de contorno (cache). */
    private float radius = -1;
    /** Índices que precisam ser desenhados (blocos de superfície). */
    private int[] surface;

    public ConstructShape() { this(16); }

    public ConstructShape(int cap) {
        states = new BlockState[Math.max(cap, 1)];
        x = new float[states.length];
        y = new float[states.length];
        z = new float[states.length];
    }

    public void add(BlockState s, float px, float py, float pz) {
        if (size == states.length) {
            int n = states.length * 2;
            states = Arrays.copyOf(states, n);
            x = Arrays.copyOf(x, n);
            y = Arrays.copyOf(y, n);
            z = Arrays.copyOf(z, n);
        }
        states[size] = s; x[size] = px; y[size] = py; z[size] = pz;
        size++;
        invalidate();
    }

    public void invalidate() { radius = -1; surface = null; }

    public float radius() {
        if (radius < 0) {
            float m = 0;
            for (int i = 0; i < size; i++) m = Math.max(m, x[i] * x[i] + y[i] * y[i] + z[i] * z[i]);
            radius = (float) Math.sqrt(m) + 0.87f;
        }
        return radius;
    }

    /** Move a origem para o centróide. Retorna o deslocamento aplicado (centróide antigo). */
    public float[] recenter() {
        if (size == 0) return new float[3];
        double cx = 0, cy = 0, cz = 0;
        for (int i = 0; i < size; i++) { cx += x[i]; cy += y[i]; cz += z[i]; }
        float fx = (float) (cx / size), fy = (float) (cy / size), fz = (float) (cz / size);
        for (int i = 0; i < size; i++) { x[i] -= fx; y[i] -= fy; z[i] -= fz; }
        invalidate();
        return new float[]{fx, fy, fz};
    }

    public ConstructShape copy() {
        ConstructShape c = new ConstructShape(size);
        for (int i = 0; i < size; i++) c.add(states[i], x[i], y[i], z[i]);
        return c;
    }

    // ------------------------------------------------------------------ layouts

    /** Gera n posições em formato de layout, ordenadas do centro para fora. */
    public static float[][] layoutPositions(Layout layout, int n) {
        List<float[]> pts = new ArrayList<>(n + 64);
        switch (layout) {
            case SPHERE, ORIGINAL -> {
                int r = (int) Math.ceil(Math.cbrt(3.0 * n / (4 * Math.PI))) + 1;
                for (int i = -r; i <= r; i++) for (int j = -r; j <= r; j++) for (int k = -r; k <= r; k++)
                    pts.add(new float[]{i, j, k, i * i + j * j + k * k});
            }
            case CUBE -> {
                int r = (int) Math.ceil(Math.cbrt(n) / 2.0) + 1;
                for (int i = -r; i <= r; i++) for (int j = -r; j <= r; j++) for (int k = -r; k <= r; k++)
                    pts.add(new float[]{i, j, k, Math.max(Math.abs(i), Math.max(Math.abs(j), Math.abs(k))) * 1000 + i * i + j * j + k * k});
            }
            case WALL -> {
                int w = Math.max(1, (int) Math.ceil(Math.sqrt(n * 2.0)));
                int h = (int) Math.ceil((double) n / w);
                for (int i = 0; i < w; i++) for (int j = 0; j < h; j++) {
                    float px = i - (w - 1) / 2f, py = j - (h - 1) / 2f;
                    pts.add(new float[]{px, py, 0, py * py * 0.3f + px * px});
                }
            }
            case PLATFORM -> {
                int w = Math.max(1, (int) Math.ceil(Math.sqrt(n)));
                for (int i = 0; i < w; i++) for (int j = 0; j < w; j++) {
                    float px = i - (w - 1) / 2f, pz = j - (w - 1) / 2f;
                    pts.add(new float[]{px, 0, pz, px * px + pz * pz});
                }
            }
            case TOWER -> {
                int k = Math.max(1, (int) Math.round(Math.cbrt(n) / 2.5));
                int h = (int) Math.ceil((double) n / (k * k));
                for (int j = 0; j < h; j++) for (int i = 0; i < k; i++) for (int l = 0; l < k; l++) {
                    float px = i - (k - 1) / 2f, py = j - (h - 1) / 2f, pz = l - (k - 1) / 2f;
                    pts.add(new float[]{px, py, pz, j * 100 + px * px + pz * pz});
                }
            }
        }
        pts.sort((a, b) -> Float.compare(a[3], b[3]));
        float[][] out = new float[n][3];
        for (int i = 0; i < n && i < pts.size(); i++) { out[i][0] = pts.get(i)[0]; out[i][1] = pts.get(i)[1]; out[i][2] = pts.get(i)[2]; }
        // recentra
        double cx = 0, cy = 0, cz = 0;
        for (float[] p : out) { cx += p[0]; cy += p[1]; cz += p[2]; }
        if (n > 0) for (float[] p : out) { p[0] -= cx / n; p[1] -= cy / n; p[2] -= cz / n; }
        return out;
    }

    /**
     * Reorganiza os blocos em um layout. Os blocos mais próximos do centro vão para os
     * espaços mais centrais, então a transformação parece "fluir" em vez de embaralhar.
     */
    public void applyLayout(Layout layout) {
        if (size == 0 || layout == Layout.ORIGINAL) return;
        recenter();
        float[][] target = layoutPositions(layout, size);
        Integer[] order = new Integer[size];
        for (int i = 0; i < size; i++) order[i] = i;
        Arrays.sort(order, (a, b) -> Float.compare(x[a] * x[a] + y[a] * y[a] + z[a] * z[a], x[b] * x[b] + y[b] * y[b] + z[b] * z[b]));
        float[] nx = new float[states.length], ny = new float[states.length], nz = new float[states.length];
        for (int rank = 0; rank < size; rank++) {
            int i = order[rank];
            nx[i] = target[rank][0]; ny[i] = target[rank][1]; nz[i] = target[rank][2];
        }
        x = nx; y = ny; z = nz;
        invalidate();
    }

    /** Índices dos blocos visíveis (têm algum vizinho vazio). Otimização de renderização. */
    public int[] surface() {
        if (surface != null) return surface;
        Set<Long> occ = new HashSet<>(size * 2);
        for (int i = 0; i < size; i++) occ.add(key(Math.round(x[i]), Math.round(y[i]), Math.round(z[i])));
        int[] tmp = new int[size];
        int c = 0;
        for (int i = 0; i < size; i++) {
            int bx = Math.round(x[i]), by = Math.round(y[i]), bz = Math.round(z[i]);
            boolean onGrid = Math.abs(x[i] - bx) < 0.05f && Math.abs(y[i] - by) < 0.05f && Math.abs(z[i] - bz) < 0.05f;
            if (!onGrid || !states[i].canOcclude()
                    || !occ.contains(key(bx + 1, by, bz)) || !occ.contains(key(bx - 1, by, bz))
                    || !occ.contains(key(bx, by + 1, bz)) || !occ.contains(key(bx, by - 1, bz))
                    || !occ.contains(key(bx, by, bz + 1)) || !occ.contains(key(bx, by, bz - 1))) {
                tmp[c++] = i;
            }
        }
        surface = Arrays.copyOf(tmp, c);
        return surface;
    }

    private static long key(int a, int b, int c) {
        return ((long) (a & 0x1FFFFF) << 42) | ((long) (b & 0x1FFFFF) << 21) | (c & 0x1FFFFF);
    }

    // ------------------------------------------------------------------ serialização

    public void write(FriendlyByteBuf buf) {
        buf.writeVarInt(size);
        for (int i = 0; i < size; i++) {
            buf.writeVarInt(Block.getId(states[i]));
            buf.writeFloat(x[i]); buf.writeFloat(y[i]); buf.writeFloat(z[i]);
        }
    }

    public static ConstructShape read(FriendlyByteBuf buf) {
        int n = buf.readVarInt();
        ConstructShape s = new ConstructShape(n);
        for (int i = 0; i < n; i++) {
            BlockState st = Block.stateById(buf.readVarInt());
            s.add(st, buf.readFloat(), buf.readFloat(), buf.readFloat());
        }
        return s;
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        ListTag palette = new ListTag();
        Map<BlockState, Integer> idx = new HashMap<>();
        int[] ids = new int[size];
        int[] pos = new int[size * 3];
        for (int i = 0; i < size; i++) {
            Integer id = idx.get(states[i]);
            if (id == null) {
                id = palette.size();
                idx.put(states[i], id);
                palette.add(NbtUtils.writeBlockState(states[i]));
            }
            ids[i] = id;
            pos[i * 3] = Float.floatToIntBits(x[i]);
            pos[i * 3 + 1] = Float.floatToIntBits(y[i]);
            pos[i * 3 + 2] = Float.floatToIntBits(z[i]);
        }
        tag.put("Palette", palette);
        tag.putIntArray("Ids", ids);
        tag.putIntArray("Pos", pos);
        return tag;
    }

    public static ConstructShape load(CompoundTag tag, HolderGetter<Block> blocks) {
        ListTag palette = tag.getList("Palette", Tag.TAG_COMPOUND);
        BlockState[] pal = new BlockState[palette.size()];
        for (int i = 0; i < pal.length; i++) pal[i] = NbtUtils.readBlockState(blocks, palette.getCompound(i));
        int[] ids = tag.getIntArray("Ids");
        int[] pos = tag.getIntArray("Pos");
        ConstructShape s = new ConstructShape(ids.length);
        for (int i = 0; i < ids.length && i * 3 + 2 < pos.length; i++) {
            if (ids[i] < 0 || ids[i] >= pal.length) continue;
            s.add(pal[ids[i]], Float.intBitsToFloat(pos[i * 3]), Float.intBitsToFloat(pos[i * 3 + 1]), Float.intBitsToFloat(pos[i * 3 + 2]));
        }
        return s;
    }
}
