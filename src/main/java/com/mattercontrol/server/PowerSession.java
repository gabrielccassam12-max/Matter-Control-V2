package com.mattercontrol.server;

import com.mattercontrol.core.MatterMode;
import com.mattercontrol.network.InputPayload;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Estado temporário (não salvo) dos poderes de um jogador no servidor. */
public final class PowerSession {
    public boolean active;
    public InputPayload input;
    public int prevFlags;
    public long lastInputTime;

    public final LinkedHashSet<Integer> selection = new LinkedHashSet<>();
    public double holdDistance = 8;
    public boolean moving;
    public final Map<Integer, Vec3> groupOffsets = new HashMap<>();
    public final Set<Integer> compacting = new HashSet<>();
    public int layoutCycle;

    public AbsorbHandler.State absorb;
    public BuildHandler.State build;

    public float lastSyncedMatter = -1;
    public boolean lastStateActive, lastStateAbsorbing;
    public float lastStateFraction = -1;

    private record Deferred(int[] ticks, Runnable task) {}
    private final List<Deferred> deferred = new ArrayList<>();

    public MatterMode mode() {
        return input == null ? MatterMode.MANIPULATE : MatterMode.byId(input.mode());
    }

    public int flags() {
        return input == null ? 0 : input.flags();
    }

    public boolean held(int flag) {
        return (flags() & flag) != 0;
    }

    public boolean pressedNow(int flag) {
        return held(flag) && (prevFlags & flag) == 0;
    }

    public boolean releasedNow(int flag) {
        return !held(flag) && (prevFlags & flag) != 0;
    }

    /** Executa algo depois de alguns ticks (usado para animar transformações em etapas). */
    public void later(int ticks, Runnable task) {
        deferred.add(new Deferred(new int[]{ticks}, task));
    }

    void runDeferred() {
        if (deferred.isEmpty()) return;
        List<Runnable> ready = new ArrayList<>();
        for (Iterator<Deferred> it = deferred.iterator(); it.hasNext(); ) {
            Deferred d = it.next();
            if (--d.ticks[0] <= 0) { ready.add(d.task); it.remove(); }
        }
        ready.forEach(Runnable::run);
    }
}
