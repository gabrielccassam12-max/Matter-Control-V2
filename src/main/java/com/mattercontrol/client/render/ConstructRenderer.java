package com.mattercontrol.client.render;

import com.mattercontrol.client.ClientState;
import com.mattercontrol.config.MatterConfig;
import com.mattercontrol.entity.ConstructShape;
import com.mattercontrol.entity.MatterConstructEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Desenha cada bloco real da estrutura. Otimizações:
 *  - só blocos de superfície (internos ficam ocultos);
 *  - LOD por distância e limite de blocos por frame (configuráveis).
 */
public class ConstructRenderer extends EntityRenderer<MatterConstructEntity> {
    private final BlockRenderDispatcher blocks;

    public ConstructRenderer(EntityRendererProvider.Context ctx) {
        super(ctx);
        this.blocks = ctx.getBlockRenderDispatcher();
        this.shadowRadius = 0f;
    }

    @Override
    public void render(MatterConstructEntity e, float entityYaw, float pt, PoseStack pose, MultiBufferSource buffers, int light) {
        ConstructShape sh = e.shape();
        int n = sh.size;
        if (n == 0 || e.animX.length != n) return;

        float sc = Mth.lerp(pt, e.prevScale, e.scale());
        float sp = Mth.lerp(pt, e.prevSpread, e.spread());
        float yaw = Mth.rotLerp(pt, e.prevYaw, e.rotYaw());
        float pitch = Mth.rotLerp(pt, e.prevPitch, e.rotPitch());
        float roll = Mth.rotLerp(pt, e.prevRoll, e.rotRoll());
        Quaternionf q = new Quaternionf().rotationYXZ(yaw * Mth.DEG_TO_RAD, pitch * Mth.DEG_TO_RAD, roll * Mth.DEG_TO_RAD);
        float bs = MatterConstructEntity.blockSize(sc, sp);
        float time = e.tickCount + pt;

        // índices: superfície quando estável; todos quando disperso/animando
        boolean settled = sp < 1.02f && isSettled(e);
        int[] idx = settled ? sh.surface() : null;
        int count = idx != null ? idx.length : n;

        double dist = Math.sqrt(entityRenderDispatcher.distanceToSqr(e));
        int maxBlocks = MatterConfig.MAX_RENDERED_BLOCKS.get();
        int lod = MatterConfig.LOD_DISTANCE.get();
        double budget = maxBlocks * (dist > lod ? (lod / dist) * (lod / dist) : 1.0);
        int stride = Math.max(1, (int) Math.ceil(count / Math.max(64, budget)));
        float strideScale = stride > 1 ? (float) Math.cbrt(stride) : 1f; // compensa buracos com blocos maiores

        boolean selected = ClientState.isSelected(e.getId());
        int lit = selected ? LightTexture.pack(15, 15) : Math.max(light, LightTexture.pack(6, 0));

        pose.pushPose();
        pose.mulPose(q);
        for (int k = 0; k < count; k += stride) {
            int i = idx != null ? idx[k] : k;
            float lx = Mth.lerp(pt, e.prevX[i], e.animX[i]);
            float ly = Mth.lerp(pt, e.prevY[i], e.animY[i]);
            float lz = Mth.lerp(pt, e.prevZ[i], e.animZ[i]);
            Vector3f v = MatterConstructEntity.localOf(i, lx, ly, lz, sc, sp);
            BlockState st = sh.states[i];
            pose.pushPose();
            pose.translate(v.x, v.y, v.z);
            float s = bs * strideScale;
            if (sp > 1.02f) {
                // fragmentos dispersos giram e flutuam individualmente
                float wob = (sp - 1f) * 0.25f;
                pose.translate(0, Mth.sin(time * 0.08f + i) * wob, 0);
                pose.mulPose(Axis.YP.rotationDegrees(time * 2f + i * 37));
                pose.mulPose(Axis.XP.rotationDegrees(time * 1.3f + i * 53));
            }
            pose.scale(s, s, s);
            pose.translate(-0.5f, -0.5f, -0.5f);
            blocks.renderSingleBlock(st, pose, buffers, lit, OverlayTexture.NO_OVERLAY);
            pose.popPose();
        }
        pose.popPose();
        super.render(e, entityYaw, pt, pose, buffers, light);
    }

    private static boolean isSettled(MatterConstructEntity e) {
        ConstructShape sh = e.shape();
        int step = Math.max(1, sh.size / 32);
        for (int i = 0; i < sh.size; i += step) {
            if (Math.abs(e.animX[i] - sh.x[i]) > 0.05f || Math.abs(e.animY[i] - sh.y[i]) > 0.05f || Math.abs(e.animZ[i] - sh.z[i]) > 0.05f) return false;
        }
        return true;
    }

    @Override
    public ResourceLocation getTextureLocation(MatterConstructEntity e) {
        return InventoryMenu.BLOCK_ATLAS;
    }
}
