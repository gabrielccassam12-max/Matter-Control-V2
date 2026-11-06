package com.mattercontrol.client.render;

import com.mattercontrol.MatterControl;
import com.mattercontrol.client.ClientState;
import com.mattercontrol.client.Keys;
import com.mattercontrol.config.MatterConfig;
import com.mattercontrol.core.MatterMode;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/** Pré-visualizações no mundo: seleção, raio de absorção, área de construção, ponto de controle. */
@EventBusSubscriber(modid = MatterControl.MODID, value = Dist.CLIENT)
public final class WorldOverlay {
    private WorldOverlay() {}

    @SubscribeEvent
    public static void onRender(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || !ClientState.active || !MatterConfig.SHOW_PREVIEWS.get()) return;

        float pt = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        Camera cam = event.getCamera();
        Vec3 cp = cam.getPosition();
        PoseStack pose = event.getPoseStack();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        VertexConsumer lines = buffers.getBuffer(RenderType.lines());
        float pulse = 0.6f + 0.4f * Mth.sin((mc.player.tickCount + pt) * 0.25f);

        pose.pushPose();
        pose.translate(-cp.x, -cp.y, -cp.z);

        // seleção
        for (int id : ClientState.selection) {
            Entity e = mc.level.getEntity(id);
            if (e == null) continue;
            Vec3 lerp = new Vec3(Mth.lerp(pt, e.xo, e.getX()) - e.getX(), Mth.lerp(pt, e.yo, e.getY()) - e.getY(), Mth.lerp(pt, e.zo, e.getZ()) - e.getZ());
            AABB box = e.getBoundingBox().move(lerp).inflate(0.05);
            LevelRenderer.renderLineBox(pose, lines, box, 0.8f, 0.4f, 1f, pulse);
        }

        MatterMode mode = ClientState.mode();
        HitResult aim = ClientState.aim;
        BlockHitResult bhit = aim instanceof BlockHitResult b && b.getType() == HitResult.Type.BLOCK ? b : null;

        // ponto controlado pela roda do mouse
        boolean moving = mc.options.keyUse.isDown() && ClientState.selection.length > 0 && !mode.isBuilder();
        if (moving) {
            Vec3 p = mc.player.getEyePosition(pt).add(mc.player.getViewVector(pt).scale(ClientState.holdDistanceEstimate));
            LevelRenderer.renderLineBox(pose, lines, new AABB(p, p).inflate(0.15), 1f, 1f, 1f, 0.9f);
        }

        if (bhit != null) {
            BlockPos bp = bhit.getBlockPos();
            Vec3 c = Vec3.atCenterOf(bp);
            if (Keys.ABSORB.isDown()) {
                ring(pose, lines, c, ClientState.absorbRadius + 0.5, 0.9f, 0.3f, 1f, pulse);
                LevelRenderer.renderLineBox(pose, lines, new AABB(bp).inflate(0.02), 1f, 0.5f, 1f, 1f);
            } else if (!mc.options.keyUse.isDown()) {
                BlockPos base = bp.relative(bhit.getDirection());
                Vec3 b = Vec3.atBottomCenterOf(base);
                int w = ClientState.primary();
                switch (mode) {
                    case MANIPULATE -> ring(pose, lines, c, w + 0.5, 0.5f, 0.8f, 1f, 0.7f);
                    case TOWER -> LevelRenderer.renderLineBox(pose, lines, new AABB(b.x - w / 2.0, b.y, b.z - w / 2.0, b.x + w / 2.0, b.y + 1, b.z + w / 2.0), 0.6f, 0.3f, 1f, 0.8f);
                    case PILLAR -> LevelRenderer.renderLineBox(pose, lines, new AABB(b.x - w / 2.0, b.y, b.z - w / 2.0, b.x + w / 2.0, b.y + ClientState.secondary(), b.z + w / 2.0), 0.6f, 0.3f, 1f, 0.8f);
                    case PLATFORM, SPHERE -> ring(pose, lines, b, w, 0.6f, 0.3f, 1f, 0.8f);
                    case WALL -> {
                        float yaw = mc.player.getYRot() * Mth.DEG_TO_RAD;
                        Vec3 right = new Vec3(Mth.cos(yaw), 0, Mth.sin(yaw)).scale(w / 2.0);
                        line(pose, lines, b.subtract(right), b.add(right), 0.6f, 0.3f, 1f, 0.9f);
                        line(pose, lines, b.subtract(right).add(0, 2, 0), b.add(right).add(0, 2, 0), 0.6f, 0.3f, 1f, 0.5f);
                    }
                    default -> {}
                }
            }
        }
        pose.popPose();
        buffers.endBatch(RenderType.lines());
    }

    private static void ring(PoseStack pose, VertexConsumer vc, Vec3 c, double r, float red, float g, float b, float a) {
        int seg = Math.max(24, (int) (r * 8));
        for (int axis = 0; axis < 3; axis++) {
            Vec3 prev = null;
            for (int i = 0; i <= seg; i++) {
                double t = Math.PI * 2 * i / seg;
                double x = Math.cos(t) * r, y = Math.sin(t) * r;
                Vec3 p = switch (axis) {
                    case 0 -> c.add(x, 0, y);
                    case 1 -> c.add(x, y, 0);
                    default -> c.add(0, x, y);
                };
                if (prev != null) line(pose, vc, prev, p, red, g, b, axis == 0 ? a : a * 0.5f);
                prev = p;
            }
        }
    }

    private static void line(PoseStack pose, VertexConsumer vc, Vec3 a, Vec3 b, float r, float g, float bl, float al) {
        Vec3 n = b.subtract(a).normalize();
        PoseStack.Pose last = pose.last();
        vc.addVertex(last, (float) a.x, (float) a.y, (float) a.z).setColor(r, g, bl, al).setNormal(last, (float) n.x, (float) n.y, (float) n.z);
        vc.addVertex(last, (float) b.x, (float) b.y, (float) b.z).setColor(r, g, bl, al).setNormal(last, (float) n.x, (float) n.y, (float) n.z);
    }
}
