package com.mattercontrol.client;

import com.mattercontrol.core.MatterMode;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.HitResult;

import java.util.HashMap;
import java.util.Map;

/** Estado local do cliente (modo, tamanhos, barra exibida, seleção, efeitos de outros jogadores). */
public final class ClientState {
    private ClientState() {}

    public static boolean active;
    public static int mode;
    public static final int[] primary = new int[MatterMode.values().length];
    public static final int[] secondary = new int[MatterMode.values().length];
    public static final int[] variant = new int[MatterMode.values().length];
    public static int absorbRadius = 2;
    public static double holdDistanceEstimate = 8;

    public static float matter, maxMatter = 1, displayMatter;
    public static boolean infinite;
    public static int[] selection = new int[0];

    public static float pendingDistance, pendingYaw, pendingPitch, pendingRoll;
    public static HitResult aim;
    public static float shake;
    public static int hudFlash;

    public record OtherState(boolean active, boolean absorbing, float fraction) {}
    public static final Map<Integer, OtherState> others = new HashMap<>();

    static {
        for (MatterMode m : MatterMode.values()) {
            primary[m.ordinal()] = m.defaultPrimary();
            secondary[m.ordinal()] = m.defaultSecondary();
        }
    }

    public static MatterMode mode() {
        return MatterMode.byId(mode);
    }

    public static int primary() { return primary[mode]; }
    public static int secondary() { return secondary[mode]; }
    public static int variant() { return variant[mode]; }

    public static void addPrimary(int d) {
        MatterMode m = mode();
        int min = m == MatterMode.MANIPULATE ? 0 : 1;
        primary[mode] = Mth.clamp(primary[mode] + d, min, m.maxPrimary());
        hudFlash = 20;
    }

    public static void addSecondary(int d) {
        MatterMode m = mode();
        secondary[mode] = Mth.clamp(secondary[mode] + d, 1, m.maxSecondary());
        hudFlash = 20;
    }

    public static void cycleVariant() {
        MatterMode m = mode();
        variant[mode] = (variant[mode] + 1) % m.variants.length;
        hudFlash = 20;
    }

    public static boolean isSelected(int id) {
        for (int s : selection) if (s == id) return true;
        return false;
    }

    public static void reset() {
        active = false;
        selection = new int[0];
        others.clear();
        matter = displayMatter = 0;
    }
}
