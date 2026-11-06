package com.mattercontrol.client;

import com.mattercontrol.MatterControl;
import com.mattercontrol.core.MatterMode;
import com.mattercontrol.network.ActionPayload;
import com.mattercontrol.network.ActionPayload.Action;
import com.mattercontrol.network.InputPayload;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/** Lê teclado/mouse a cada tick e envia para o servidor. Bloqueia as ações vanilla enquanto os poderes estão ativos. */
@EventBusSubscriber(modid = MatterControl.MODID, value = Dist.CLIENT)
public final class ClientController {
    private ClientController() {}

    private static boolean prevLeft, prevRight;
    public static final double CLIENT_REACH = 96;

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) return;

        ClientState.displayMatter += (ClientState.matter - ClientState.displayMatter) * 0.25f;
        if (ClientState.hudFlash > 0) ClientState.hudFlash--;
        ClientState.shake *= 0.8f;
        ClientFx.playersTick(mc);

        while (Keys.TOGGLE.consumeClick()) {
            ClientState.active = !ClientState.active;
            player.playSound(ClientState.active ? SoundEvents.BEACON_ACTIVATE : SoundEvents.BEACON_DEACTIVATE, 0.6f, 1.6f);
            player.displayClientMessage(Component.literal(ClientState.active ? "§dControle de Matéria: ATIVO" : "§7Controle de Matéria: desligado"), true);
            if (!ClientState.active) sendInput(player, 0);
        }
        if (!ClientState.active) {
            for (KeyMapping k : Keys.COMMANDS) while (k.consumeClick()) { /* descarta */ }
            prevLeft = prevRight = false;
            return;
        }
        boolean inGame = mc.screen == null;
        boolean fast = Keys.FAST.isDown(), precise = Keys.PRECISE.isDown();
        int mods = (fast ? ActionPayload.MOD_SHIFT : 0) | (precise ? ActionPayload.MOD_CTRL : 0);

        // ---------------- teclas de comando
        while (Keys.CYCLE_MODE.consumeClick()) {
            ClientState.mode = Math.floorMod(ClientState.mode + (fast ? -1 : 1), MatterMode.values().length);
            ClientState.hudFlash = 40;
            player.playSound(SoundEvents.AMETHYST_BLOCK_CHIME, 0.5f, 1.4f);
        }
        while (Keys.VARIANT.consumeClick()) {
            MatterMode m = ClientState.mode();
            if (m == MatterMode.HAND || m == MatterMode.PROJECTILE) send(Action.CREATE, mods);
            else ClientState.cycleVariant();
        }
        while (Keys.THROW.consumeClick()) send(precise ? Action.DROP : Action.THROW, mods);
        while (Keys.SPLIT.consumeClick()) send(fast ? Action.SPLIT_MANY : Action.SPLIT, mods);
        while (Keys.MERGE.consumeClick()) send(Action.MERGE, mods);
        while (Keys.SCATTER.consumeClick()) send(fast ? Action.SPREAD : Action.SCATTER, mods);
        while (Keys.REFORM.consumeClick()) send(Action.REFORM, mods);
        while (Keys.RESHAPE.consumeClick()) send(Action.RESHAPE, mods);
        while (Keys.SOLIDIFY.consumeClick()) send(Action.SOLIDIFY, mods);
        while (Keys.HOMING.consumeClick()) send(Action.HOMING, mods);
        while (Keys.DISSOLVE.consumeClick()) send(precise ? Action.RELEASE_CAPTIVE : Action.DISSOLVE, mods);

        // ---------------- mouse
        boolean left = inGame && mc.options.keyAttack.isDown();
        boolean right = inGame && mc.options.keyUse.isDown();
        if (left && !prevLeft) send(Action.LEFT_CLICK, mods);
        if (right && !prevRight) send(Action.RIGHT_CLICK, mods);
        prevLeft = left;
        prevRight = right;

        int flags = 0;
        if (left) flags |= InputPayload.LEFT;
        if (right) flags |= InputPayload.RIGHT;
        if (fast) flags |= InputPayload.FAST;
        if (precise) flags |= InputPayload.PRECISE;
        if (inGame && Keys.ABSORB.isDown()) flags |= InputPayload.ABSORB;
        if (inGame && Keys.COMPACT.isDown()) flags |= InputPayload.COMPACT;
        if (inGame && Keys.EXPAND.isDown()) flags |= InputPayload.EXPAND;
        sendInput(player, flags);

        ClientState.aim = player.pick(CLIENT_REACH, 1f, false);
    }

    private static void sendInput(LocalPlayer player, int flags) {
        Vec3 origin = player.getEyePosition(1f);
        Vec3 dir = player.getViewVector(1f);
        PacketDistributor.sendToServer(new InputPayload(ClientState.active, flags, origin, dir,
                ClientState.pendingDistance, ClientState.pendingYaw, ClientState.pendingPitch, ClientState.pendingRoll,
                ClientState.mode, ClientState.primary(), ClientState.secondary(), ClientState.variant(), ClientState.absorbRadius));
        ClientState.holdDistanceEstimate = Mth.clamp(ClientState.holdDistanceEstimate + ClientState.pendingDistance, 1.5, CLIENT_REACH);
        ClientState.pendingDistance = 0;
        ClientState.pendingYaw = ClientState.pendingPitch = ClientState.pendingRoll = 0;
    }

    private static void send(Action a, int mods) {
        PacketDistributor.sendToServer(new ActionPayload(a, mods));
    }

    /** Roda do mouse: distância, tamanho, raio de absorção ou rotação, conforme a tecla segurada. */
    @SubscribeEvent
    public static void onScroll(InputEvent.MouseScrollingEvent event) {
        Minecraft mc = Minecraft.getInstance();
        if (!ClientState.active || mc.screen != null || mc.player == null) return;
        double delta = event.getScrollDeltaY();
        if (delta == 0) return;
        int sign = delta > 0 ? 1 : -1;
        boolean fast = Keys.FAST.isDown(), precise = Keys.PRECISE.isDown(), sizeMod = Keys.SIZE_MOD.isDown();
        MatterMode m = ClientState.mode();

        if (Keys.ABSORB.isDown()) {
            ClientState.absorbRadius = Mth.clamp(ClientState.absorbRadius + sign * (fast ? 3 : 1), 0, 64);
            ClientState.hudFlash = 20;
        } else if (Keys.ROTATE.isDown()) {
            float step = precise ? 3f : 15f;
            if (sizeMod) ClientState.pendingPitch += sign * step;
            else if (fast) ClientState.pendingRoll += sign * step;
            else ClientState.pendingYaw += sign * step;
        } else if (m.isBuilder() && !sizeMod || !m.isBuilder() && sizeMod) {
            if (precise) ClientState.addSecondary(sign);
            else ClientState.addPrimary(sign * (fast ? 3 : 1));
        } else {
            float step = fast ? 4f : precise ? 0.25f : 1f;
            ClientState.pendingDistance += sign * step;
        }
        event.setCanceled(true);
    }

    /** Impede ataque/uso/quebra vanilla enquanto o controle de matéria está ativo. */
    @SubscribeEvent
    public static void onInteract(InputEvent.InteractionKeyMappingTriggered event) {
        if (!ClientState.active) return;
        if (event.isAttack() || event.isUseItem()) {
            event.setSwingHand(false);
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onCamera(ViewportEvent.ComputeCameraAngles event) {
        if (ClientState.shake > 0.01f) {
            float t = (float) (Minecraft.getInstance().level == null ? 0 : Minecraft.getInstance().level.getGameTime() + event.getPartialTick());
            event.setPitch(event.getPitch() + Mth.sin(t * 2.1f) * ClientState.shake);
            event.setRoll(event.getRoll() + Mth.cos(t * 1.7f) * ClientState.shake * 0.7f);
        }
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        ClientState.reset();
    }
}
