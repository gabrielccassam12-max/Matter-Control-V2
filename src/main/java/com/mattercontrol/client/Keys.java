package com.mattercontrol.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import org.lwjgl.glfw.GLFW;

/** Todas as teclas são remapeáveis em Opções > Controles > Matter Control. */
public final class Keys {
    private Keys() {}

    public static final String CATEGORY = "key.categories.mattercontrol";

    public static final KeyMapping TOGGLE = key("toggle", GLFW.GLFW_KEY_V);
    public static final KeyMapping ABSORB = key("absorb", GLFW.GLFW_KEY_G);
    public static final KeyMapping CYCLE_MODE = key("cycle_mode", GLFW.GLFW_KEY_C);
    public static final KeyMapping COMPACT = key("compact", GLFW.GLFW_KEY_R);
    public static final KeyMapping EXPAND = key("expand", GLFW.GLFW_KEY_Y);
    public static final KeyMapping THROW = key("throw", GLFW.GLFW_KEY_X);
    public static final KeyMapping ROTATE = key("rotate", GLFW.GLFW_KEY_Z);
    public static final KeyMapping VARIANT = key("variant", GLFW.GLFW_KEY_B);
    public static final KeyMapping SPLIT = key("split", GLFW.GLFW_KEY_H);
    public static final KeyMapping MERGE = key("merge", GLFW.GLFW_KEY_J);
    public static final KeyMapping SCATTER = key("scatter", GLFW.GLFW_KEY_N);
    public static final KeyMapping REFORM = key("reform", GLFW.GLFW_KEY_U);
    public static final KeyMapping RESHAPE = key("reshape", GLFW.GLFW_KEY_K);
    public static final KeyMapping SOLIDIFY = key("solidify", GLFW.GLFW_KEY_P);
    public static final KeyMapping HOMING = key("homing", GLFW.GLFW_KEY_M);
    public static final KeyMapping DISSOLVE = key("dissolve", GLFW.GLFW_KEY_BACKSPACE);
    public static final KeyMapping FAST = key("fast", GLFW.GLFW_KEY_LEFT_SHIFT);
    public static final KeyMapping PRECISE = key("precise", GLFW.GLFW_KEY_LEFT_CONTROL);
    public static final KeyMapping SIZE_MOD = key("size_modifier", GLFW.GLFW_KEY_LEFT_ALT);

    private static KeyMapping key(String name, int code) {
        return new KeyMapping("key.mattercontrol." + name, KeyConflictContext.IN_GAME, InputConstants.Type.KEYSYM, code, CATEGORY);
    }

    public static void register(RegisterKeyMappingsEvent e) {
        for (KeyMapping k : new KeyMapping[]{TOGGLE, ABSORB, CYCLE_MODE, COMPACT, EXPAND, THROW, ROTATE, VARIANT, SPLIT, MERGE,
                SCATTER, REFORM, RESHAPE, SOLIDIFY, HOMING, DISSOLVE, FAST, PRECISE, SIZE_MOD}) {
            e.register(k);
        }
    }

    /** Teclas de comando: esvaziadas quando os poderes estão desligados para não acumular cliques. */
    public static final KeyMapping[] COMMANDS = {CYCLE_MODE, THROW, VARIANT, SPLIT, MERGE, SCATTER, REFORM, RESHAPE, SOLIDIFY, HOMING, DISSOLVE};
}
