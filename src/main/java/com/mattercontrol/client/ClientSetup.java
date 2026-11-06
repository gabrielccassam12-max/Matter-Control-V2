package com.mattercontrol.client;

import com.mattercontrol.MatterControl;
import com.mattercontrol.client.render.ConstructRenderer;
import com.mattercontrol.client.render.HandRenderer;
import com.mattercontrol.registry.ModRegistry;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;

@EventBusSubscriber(modid = MatterControl.MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class ClientSetup {
    private ClientSetup() {}

    @SubscribeEvent
    public static void keys(RegisterKeyMappingsEvent e) {
        Keys.register(e);
    }

    @SubscribeEvent
    public static void renderers(EntityRenderersEvent.RegisterRenderers e) {
        e.registerEntityRenderer(ModRegistry.CONSTRUCT.get(), ConstructRenderer::new);
        e.registerEntityRenderer(ModRegistry.HAND.get(), HandRenderer::new);
    }

    @SubscribeEvent
    public static void gui(RegisterGuiLayersEvent e) {
        e.registerAboveAll(MatterControl.id("matter_hud"), MatterHud::render);
    }
}
