package com.mattercontrol.block;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;

/**
 * Bloco de matéria sólida. DENSITY (0..3) representa o quanto a matéria foi compactada:
 * cada nível dobra a matéria contida e aumenta muito a resistência.
 */
public class MatterBlock extends Block {
    public static final IntegerProperty DENSITY = IntegerProperty.create("density", 0, 3);

    public MatterBlock(Properties props) {
        super(props);
        registerDefaultState(stateDefinition.any().setValue(DENSITY, 0));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(DENSITY);
    }

    @Override
    protected float getDestroyProgress(BlockState state, Player player, BlockGetter level, BlockPos pos) {
        float base = super.getDestroyProgress(state, player, level, pos);
        int d = state.getValue(DENSITY);
        return base / (1 + d * d * 3);
    }

    @Override
    public float getExplosionResistance(BlockState state, BlockGetter level, BlockPos pos, Explosion explosion) {
        int d = state.getValue(DENSITY);
        return switch (d) {
            case 0 -> 8f;
            case 1 -> 60f;
            case 2 -> 600f;
            default -> 3600000f;
        };
    }

    /** Quantidade de "matéria unitária" contida no bloco. */
    public static int unitsOf(BlockState state) {
        return 1 << state.getValue(DENSITY);
    }
}
