package com.mattercontrol.server;

import com.mattercontrol.config.MatterConfig;
import com.mattercontrol.core.MatterValues;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.BlockEvent;

/** Remoção/colocação de blocos respeitando proteções (spawn, claims via BreakEvent). */
public final class WorldEdit {
    private WorldEdit() {}

    public static boolean canTake(ServerPlayer sp, BlockPos pos, BlockState state) {
        ServerLevel level = sp.serverLevel();
        if (!MatterConfig.ALLOW_TERRAIN_EDIT.get()) return false;
        if (!level.isLoaded(pos) || !level.mayInteract(sp, pos) || sp.blockActionRestricted(level, pos, sp.gameMode.getGameModeForPlayer())) return false;
        if (!MatterValues.canTake(level, pos, state)) return false;
        BlockEvent.BreakEvent ev = new BlockEvent.BreakEvent(level, pos, state, sp);
        NeoForge.EVENT_BUS.post(ev);
        return !ev.isCanceled();
    }

    /** Remove o bloco sem drops (a matéria vai para o jogador ou para a estrutura). */
    public static void take(ServerLevel level, BlockPos pos, BlockState state) {
        BlockState replacement = state.getFluidState().isEmpty() ? Blocks.AIR.defaultBlockState() : state.getFluidState().createLegacyBlock();
        level.setBlock(pos, replacement, Block.UPDATE_ALL);
    }

    public static boolean canPlace(ServerPlayer sp, BlockPos pos) {
        ServerLevel level = sp.serverLevel();
        if (!MatterConfig.ALLOW_TERRAIN_EDIT.get()) return false;
        if (level.isOutsideBuildHeight(pos) || !level.isLoaded(pos) || !level.mayInteract(sp, pos)) return false;
        if (!level.getWorldBorder().isWithinBounds(pos)) return false;
        return level.getBlockState(pos).canBeReplaced();
    }

    /** Coloca o bloco e empurra para cima entidades que estavam no caminho (efeito de "emergir"). */
    public static void place(ServerLevel level, BlockPos pos, BlockState state, boolean launchUp) {
        level.setBlock(pos, state, Block.UPDATE_ALL);
        AABB box = new AABB(pos);
        for (Entity e : level.getEntities((Entity) null, box, x -> !(x instanceof com.mattercontrol.entity.MatterConstructEntity) && !(x instanceof com.mattercontrol.entity.MatterHandEntity))) {
            e.setPos(e.getX(), pos.getY() + 1.01, e.getZ());
            if (launchUp && e instanceof LivingEntity) {
                e.setDeltaMovement(e.getDeltaMovement().add(0, 0.35, 0));
                e.hurtMarked = true;
            }
        }
    }

    public static int[] fxEntry(BlockPos pos, BlockState state) {
        return new int[]{pos.getX(), pos.getY(), pos.getZ(), Block.getId(state)};
    }
}
