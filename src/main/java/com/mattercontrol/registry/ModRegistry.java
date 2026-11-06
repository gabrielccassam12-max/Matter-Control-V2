package com.mattercontrol.registry;

import com.mattercontrol.MatterControl;
import com.mattercontrol.block.MatterBlock;
import com.mattercontrol.entity.MatterConstructEntity;
import com.mattercontrol.entity.MatterHandEntity;
import com.mojang.serialization.Codec;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

public final class ModRegistry {
    private ModRegistry() {}

    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MatterControl.MODID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MatterControl.MODID);
    public static final DeferredRegister<EntityType<?>> ENTITIES = DeferredRegister.create(Registries.ENTITY_TYPE, MatterControl.MODID);
    public static final DeferredRegister<AttachmentType<?>> ATTACHMENTS = DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, MatterControl.MODID);

    public static final DeferredBlock<MatterBlock> MATTER_BLOCK = BLOCKS.register("matter_block",
            () -> new MatterBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_PURPLE)
                    .strength(2.0f, 8.0f)
                    .sound(SoundType.AMETHYST)
                    .lightLevel(s -> 4 + s.getValue(MatterBlock.DENSITY) * 2)
                    .requiresCorrectToolForDrops()));

    public static final DeferredItem<BlockItem> MATTER_BLOCK_ITEM = ITEMS.registerSimpleBlockItem(MATTER_BLOCK);

    public static final DeferredHolder<EntityType<?>, EntityType<MatterConstructEntity>> CONSTRUCT =
            ENTITIES.register("matter_construct", () -> EntityType.Builder
                    .<MatterConstructEntity>of(MatterConstructEntity::new, MobCategory.MISC)
                    .sized(1.0f, 1.0f)
                    .clientTrackingRange(16)
                    .updateInterval(1)
                    .fireImmune()
                    .build("matter_construct"));

    public static final DeferredHolder<EntityType<?>, EntityType<MatterHandEntity>> HAND =
            ENTITIES.register("matter_hand", () -> EntityType.Builder
                    .<MatterHandEntity>of(MatterHandEntity::new, MobCategory.MISC)
                    .sized(2.0f, 2.0f)
                    .clientTrackingRange(16)
                    .updateInterval(1)
                    .fireImmune()
                    .build("matter_hand"));

    /** Matéria armazenada pelo jogador (persistente, mantida ao morrer). */
    public static final DeferredHolder<AttachmentType<?>, AttachmentType<Float>> MATTER =
            ATTACHMENTS.register("matter", () -> AttachmentType.builder(() -> 0f)
                    .serialize(Codec.FLOAT)
                    .copyOnDeath()
                    .build());

    public static void register(IEventBus bus) {
        BLOCKS.register(bus);
        ITEMS.register(bus);
        ENTITIES.register(bus);
        ATTACHMENTS.register(bus);
    }
}
