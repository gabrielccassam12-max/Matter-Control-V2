package com.mattercontrol.core;

import com.mattercontrol.config.MatterConfig;
import com.mattercontrol.network.MatterSyncPayload;
import com.mattercontrol.registry.ModRegistry;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.network.PacketDistributor;

/** Acesso à barra de matéria do jogador. Toda alteração passa por aqui. */
public final class PlayerMatter {
    private PlayerMatter() {}

    public static float max() {
        return MatterConfig.MAX_MATTER.get().floatValue();
    }

    public static boolean infinite(Player p) {
        return p.isCreative() && MatterConfig.CREATIVE_INFINITE.get();
    }

    public static float get(Player p) {
        if (infinite(p)) return max();
        return p.getData(ModRegistry.MATTER);
    }

    public static float space(Player p) {
        return infinite(p) ? Float.MAX_VALUE : max() - get(p);
    }

    /** Adiciona (limitado à capacidade). Retorna quanto realmente entrou. */
    public static float add(Player p, float amount) {
        if (amount <= 0 || infinite(p)) return amount;
        float cur = p.getData(ModRegistry.MATTER);
        float next = Math.min(max(), cur + amount);
        p.setData(ModRegistry.MATTER, next);
        return next - cur;
    }

    public static boolean has(Player p, float amount) {
        return infinite(p) || p.getData(ModRegistry.MATTER) >= amount - 1e-4f;
    }

    /** Consome se houver o suficiente. */
    public static boolean consume(Player p, float amount) {
        if (amount <= 0 || infinite(p)) return true;
        float cur = p.getData(ModRegistry.MATTER);
        if (cur < amount - 1e-4f) return false;
        p.setData(ModRegistry.MATTER, Math.max(0, cur - amount));
        return true;
    }

    public static void sync(ServerPlayer p) {
        PacketDistributor.sendToPlayer(p, new MatterSyncPayload(get(p), max(), infinite(p)));
    }
}
