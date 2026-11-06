package com.mattercontrol.server;

import com.mattercontrol.core.MatterMode;
import com.mattercontrol.entity.ConstructShape;
import com.mattercontrol.entity.MatterConstructEntity;
import com.mattercontrol.entity.MatterHandEntity;
import com.mattercontrol.entity.Motion;
import com.mattercontrol.network.FxPayload;
import com.mattercontrol.network.InputPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Controle contínuo da matéria selecionada, a cada tick:
 *  - botão direito: a seleção segue exatamente o ponto do mouse (distância pela roda);
 *  - Shift/Ctrl: rápido/preciso; em voo: acelera/freia;
 *  - R/Y: compactar/expandir; botão esquerdo (modo Mãos): fecha/abre os dedos.
 */
public final class ManipulationHandler {
    private ManipulationHandler() {}

    public static void tick(ServerPlayer sp, PowerSession s) {
        List<Entity> sel = PowerManager.selected(sp, s);
        MatterMode mode = s.mode();
        boolean fast = s.held(InputPayload.FAST), precise = s.held(InputPayload.PRECISE);

        // ---------------- mover pelo mouse
        boolean right = s.held(InputPayload.RIGHT) && !mode.isBuilder() && !sel.isEmpty();
        if (right) {
            if (!s.moving || s.groupOffsets.size() != sel.size()) beginMove(sp, s, sel);
            Vec3 aim = PowerManager.holdPoint(sp, s);
            double k = fast ? 0.6 : precise ? 0.12 : 0.35;
            double max = fast ? 4.0 : precise ? 0.35 : 1.8;
            for (Entity e : sel) {
                Vec3 off = s.groupOffsets.getOrDefault(e.getId(), Vec3.ZERO);
                Vec3 target = aim.add(off);
                if (e instanceof MatterConstructEntity c) {
                    Motion m = c.motion();
                    if (m == Motion.THROWN || m == Motion.HOMING) {
                        c.steer(target, precise ? 0.06 : fast ? 0.35 : 0.16); // guiar trajetória em voo
                        if (m == Motion.HOMING) c.setMotion(Motion.THROWN);
                    } else {
                        c.setControl(target, k, max);
                    }
                } else if (e instanceof MatterHandEntity h) {
                    h.setControl(target, k, max * 1.2);
                    h.face(sp.getYRot(), sp.getXRot());
                }
            }
        } else {
            s.moving = false;
            s.groupOffsets.clear();
            for (Entity e : sel) {
                if (e instanceof MatterConstructEntity c) {
                    Motion m = c.motion();
                    if (m == Motion.THROWN || m == Motion.HOMING) {
                        if (fast) c.accelerate(1.08);
                        if (precise) c.accelerate(0.88);
                    } else if (m == Motion.HELD && c.captive() == null) {
                        c.hover();
                    }
                }
            }
        }

        // ---------------- compactar / expandir
        boolean compact = s.held(InputPayload.COMPACT), expand = s.held(InputPayload.EXPAND);
        if (!compact) s.compacting.clear();
        if (compact || expand) {
            float rate = fast ? 0.06f : precise ? 0.008f : 0.025f;
            for (Entity e : sel) {
                if (e instanceof MatterConstructEntity c) {
                    if (compact) {
                        if (s.compacting.add(c.getId()) && c.captive() == null) {
                            ConstructShape sh = c.shape().copy();
                            sh.applyLayout(ConstructShape.Layout.SPHERE);
                            c.setShape(sh);
                        }
                        float before = c.scale();
                        c.setScale(before * (1 - rate));
                        if (c.captive() != null) c.crushCaptive(1.5f + (float) Math.cbrt(c.blockCount()) * 0.6f);
                        if (before > MatterConstructEntity.MIN_SCALE && sp.level().getGameTime() % 4 == 0) {
                            FxPayload.send(sp.serverLevel(), c.position(), FxPayload.COMPACT, (float) c.boundRadius(), c.getId());
                            sp.level().playSound(null, c.blockPosition(), SoundEvents.AMETHYST_BLOCK_RESONATE, SoundSource.PLAYERS, 0.6f, 0.5f + (1 - c.scale()));
                        }
                    } else {
                        c.setScale(c.scale() * (1 + rate));
                    }
                } else if (e instanceof MatterHandEntity h && compact) {
                    h.crush();
                }
            }
        }

        // ---------------- dedos das mãos (segurar esquerdo fecha; Ctrl+esquerdo abre)
        if (mode == MatterMode.HAND && s.held(InputPayload.LEFT) && !s.pressedNow(InputPayload.LEFT)) {
            float d = precise ? -0.07f : fast ? 0.15f : 0.07f;
            for (Entity e : sel) if (e instanceof MatterHandEntity h) h.changeGrip(d);
        }
    }

    private static void beginMove(ServerPlayer sp, PowerSession s, List<Entity> sel) {
        Vec3 center = Vec3.ZERO;
        for (Entity e : sel) center = center.add(e.position());
        center = center.scale(1.0 / sel.size());
        if (!s.moving) {
            s.holdDistance = Math.max(1.5, Math.min(PowerManager.reach(), PowerManager.origin(sp, s).distanceTo(center)));
        }
        s.groupOffsets.clear();
        for (Entity e : sel) s.groupOffsets.put(e.getId(), e.position().subtract(center));
        s.moving = true;
    }
}
