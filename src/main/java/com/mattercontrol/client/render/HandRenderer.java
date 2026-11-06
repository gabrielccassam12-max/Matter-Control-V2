package com.mattercontrol.client.render;

import com.mattercontrol.block.MatterBlock;
import com.mattercontrol.client.ClientState;
import com.mattercontrol.entity.MatterHandEntity;
import com.mattercontrol.registry.ModRegistry;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
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

/**
 * Mão gigante montada com blocos: palma 4x4, quatro dedos de 3 falanges e polegar de 2.
 * Cada articulação dobra de acordo com o "grip" controlado pelo jogador, com leve atraso
 * entre os dedos e respiração ociosa quando aberta.
 */
public class HandRenderer extends EntityRenderer<MatterHandEntity> {
    private final BlockRenderDispatcher blocks;

    public HandRenderer(EntityRendererProvider.Context ctx) {
        super(ctx);
        this.blocks = ctx.getBlockRenderDispatcher();
        this.shadowRadius = 0f;
    }

    @Override
    public void render(MatterHandEntity h, float entityYaw, float pt, PoseStack pose, MultiBufferSource buf, int light) {
        float grip = Mth.lerp(pt, h.prevGrip, h.grip());
        float yaw = Mth.rotLerp(pt, h.prevYawFace, h.faceYaw());
        float pitch = Mth.lerp(pt, h.prevPitchFace, h.facePitch());
        float s = h.handScale();
        float time = h.tickCount + pt;
        boolean selected = ClientState.isSelected(h.getId());
        int lit = selected ? LightTexture.pack(15, 15) : Math.max(light, LightTexture.pack(8, 0));
        BlockState palmState = ModRegistry.MATTER_BLOCK.get().defaultBlockState();
        BlockState jointState = palmState.setValue(MatterBlock.DENSITY, 2);

        pose.pushPose();
        pose.mulPose(new Quaternionf().rotationYXZ(-yaw * Mth.DEG_TO_RAD, pitch * Mth.DEG_TO_RAD, 0));
        // tremor ao esmagar
        if (h.crushing()) pose.translate(Mth.sin(time * 3f) * 0.06f * s, Mth.cos(time * 2.7f) * 0.06f * s, 0);
        pose.scale(s, s, s);
        // a palma fica um pouco atrás do centro para o ponto de agarrar ficar na frente
        pose.translate(0, 0, -0.4f);

        // palma 4x4 (+ pulso)
        for (int x = 0; x < 4; x++) for (int y = 0; y < 4; y++) {
            cube(pose, buf, palmState, lit, x - 1.5f, y - 2.0f, 0, 1f);
        }
        cube(pose, buf, jointState, lit, -0.5f, -3.1f, -0.2f, 0.95f);
        cube(pose, buf, jointState, lit, 0.5f, -3.1f, -0.2f, 0.95f);

        // dedos
        for (int f = 0; f < 4; f++) {
            float delay = 1f - f * 0.06f;
            float idle = (1f - grip) * Mth.sin(time * 0.12f + f * 0.9f) * 6f;
            float bend = grip * 80f * delay + idle;
            float len = f == 1 || f == 2 ? 1.1f : 0.95f;
            pose.pushPose();
            pose.translate(f - 1.5f, 1.5f, 0);
            for (int seg = 0; seg < 3; seg++) {
                pose.mulPose(Axis.XP.rotationDegrees(bend * (seg == 0 ? 0.8f : 1.1f)));
                cube(pose, buf, seg == 2 ? jointState : palmState, lit, 0, 0.5f * len + 0.05f, 0, 0.88f - seg * 0.05f);
                pose.translate(0, len, 0);
            }
            pose.popPose();
        }

        // polegar
        pose.pushPose();
        pose.translate(-2.1f, -1.2f, 0.1f);
        pose.mulPose(Axis.ZP.rotationDegrees(35f));
        for (int seg = 0; seg < 2; seg++) {
            pose.mulPose(Axis.YP.rotationDegrees(grip * 60f + (1f - grip) * Mth.sin(time * 0.1f) * 4f));
            cube(pose, buf, seg == 1 ? jointState : palmState, lit, -0.5f, 0, 0, 0.9f);
            pose.translate(-1.0f, 0, 0);
        }
        pose.popPose();

        pose.popPose();
        super.render(h, entityYaw, pt, pose, buf, light);
    }

    /** Desenha um bloco de tamanho 'size' centrado em (cx, cy, cz) no espaço atual. */
    private void cube(PoseStack pose, MultiBufferSource buf, BlockState st, int light, float cx, float cy, float cz, float size) {
        pose.pushPose();
        pose.translate(cx, cy, cz);
        pose.scale(size, size, size);
        pose.translate(-0.5f, -0.5f, -0.5f);
        blocks.renderSingleBlock(st, pose, buf, light, OverlayTexture.NO_OVERLAY);
        pose.popPose();
    }

    @Override
    public ResourceLocation getTextureLocation(MatterHandEntity h) {
        return InventoryMenu.BLOCK_ATLAS;
    }
}
