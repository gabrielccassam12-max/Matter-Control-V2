package com.mattercontrol;

import com.mattercontrol.config.MatterConfig;
import com.mattercontrol.network.ModNetwork;
import com.mattercontrol.registry.ModRegistry;
import com.mattercontrol.server.PowerManager;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.CreativeModeTabs;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;

@Mod(MatterControl.MODID)
public class MatterControl {
    public static final String MODID = "mattercontrol";

    public MatterControl(IEventBus modBus, ModContainer container) {
        ModRegistry.register(modBus);
        modBus.addListener(ModNetwork::register);
        modBus.addListener(this::creativeTabs);

        container.registerConfig(ModConfig.Type.SERVER, MatterConfig.SERVER_SPEC);
        container.registerConfig(ModConfig.Type.CLIENT, MatterConfig.CLIENT_SPEC);

        NeoForge.EVENT_BUS.register(PowerManager.class);
    }

    private void creativeTabs(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.BUILDING_BLOCKS) {
            event.accept(ModRegistry.MATTER_BLOCK_ITEM.get());
        }
    }

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MODID, path);
    }
}
