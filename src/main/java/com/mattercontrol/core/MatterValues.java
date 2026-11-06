package com.mattercontrol.core;

import com.mattercontrol.block.MatterBlock;
import com.mattercontrol.config.MatterConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Valores de matéria por bloco e filtro de absorção (lidos da config com cache). */
public final class MatterValues {
    private MatterValues() {}

    private static List<? extends String> cachedValuesSrc;
    private static final Map<Block, Float> VALUES = new HashMap<>();
    private static List<? extends String> cachedFilterSrc;
    private static final List<Block> FILTER_BLOCKS = new ArrayList<>();
    private static final List<TagKey<Block>> FILTER_TAGS = new ArrayList<>();

    private static void refresh() {
        List<? extends String> vals = MatterConfig.BLOCK_VALUES.get();
        if (vals != cachedValuesSrc) {
            cachedValuesSrc = vals;
            VALUES.clear();
            for (String s : vals) {
                int eq = s.indexOf('=');
                if (eq <= 0) continue;
                ResourceLocation id = ResourceLocation.tryParse(s.substring(0, eq).trim());
                if (id == null) continue;
                try {
                    float v = Float.parseFloat(s.substring(eq + 1).trim());
                    BuiltInRegistries.BLOCK.getOptional(id).ifPresent(b -> VALUES.put(b, v));
                } catch (NumberFormatException ignored) {}
            }
        }
        List<? extends String> filt = MatterConfig.ABSORB_LIST.get();
        if (filt != cachedFilterSrc) {
            cachedFilterSrc = filt;
            FILTER_BLOCKS.clear();
            FILTER_TAGS.clear();
            for (String s : filt) {
                s = s.trim();
                if (s.startsWith("#")) {
                    ResourceLocation id = ResourceLocation.tryParse(s.substring(1));
                    if (id != null) FILTER_TAGS.add(TagKey.create(Registries.BLOCK, id));
                } else {
                    ResourceLocation id = ResourceLocation.tryParse(s);
                    if (id != null) BuiltInRegistries.BLOCK.getOptional(id).ifPresent(FILTER_BLOCKS::add);
                }
            }
        }
    }

    /** Matéria fornecida ao absorver/dissolver este bloco. */
    public static float valueOf(BlockState state) {
        refresh();
        if (state.isAir()) return 0;
        if (state.getBlock() instanceof MatterBlock) {
            return (float) (MatterBlock.unitsOf(state) * MatterConfig.BUILD_COST.get());
        }
        Float v = VALUES.get(state.getBlock());
        if (v != null) return v;
        float hardness = state.getBlock().defaultDestroyTime();
        if (hardness < 0) return 0;
        return Math.max(0.2f, Math.min(20f, 0.5f + hardness * 0.6f));
    }

    private static boolean inList(BlockState state) {
        if (FILTER_BLOCKS.contains(state.getBlock())) return true;
        for (TagKey<Block> t : FILTER_TAGS) if (state.is(t)) return true;
        return false;
    }

    /** Pode ser absorvido / levantado? */
    public static boolean canTake(Level level, BlockPos pos, BlockState state) {
        refresh();
        if (state.isAir()) return false;
        if (!state.getFluidState().isEmpty() && state.getBlock() == state.getFluidState().createLegacyBlock().getBlock()) {
            return false; // fluido puro não é matéria sólida
        }
        if (state.getDestroySpeed(level, pos) < 0) return false;
        if (state.hasBlockEntity() && !MatterConfig.ABSORB_BLOCK_ENTITIES.get()) return false;
        return switch (MatterConfig.ABSORB_FILTER.get()) {
            case ANY -> !inList(state) || state.getBlock() instanceof MatterBlock;
            case WHITELIST -> inList(state);
            case BLACKLIST -> !inList(state);
        };
    }
}
