package com.mattercontrol.client;

import com.mattercontrol.core.MatterMode;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;

/** Barra de matéria + informações do modo atual. */
public final class MatterHud {
    private MatterHud() {}

    private static final int W = 150, H = 9;

    public static void render(GuiGraphics g, DeltaTracker dt) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.hideGui) return;
        if (!ClientState.active && ClientState.displayMatter < 0.5f) return;
        Font font = mc.font;
        int x = 8, y = 8;
        float time = mc.player.tickCount + dt.getGameTimeDeltaPartialTick(false);

        // ---------------- barra
        float frac = ClientState.infinite ? 1f : Mth.clamp(ClientState.displayMatter / ClientState.maxMatter, 0, 1);
        int alpha = ClientState.active ? 0xFF : 0x99;
        g.fill(x - 2, y - 2, x + W + 2, y + H + 2, 0xAA000000);
        g.fill(x - 1, y - 1, x + W + 1, y + H + 1, (alpha << 24) | 0x3B1858);
        int fillW = (int) (W * frac);
        boolean absorbing = Keys.ABSORB.isDown() && ClientState.active;
        float pulse = absorbing ? 0.5f + 0.5f * Mth.sin(time * 0.6f) : 0f;
        int top = lerpColor(0xC266FF, 0xFFFFFF, pulse * 0.4f);
        g.fillGradient(x, y, x + fillW, y + H, (alpha << 24) | top, (alpha << 24) | 0x5E1A9E);
        // brilho percorrendo a barra
        int shine = (int) ((time * 3) % (W + 30)) - 15;
        if (shine > 0 && shine < fillW) g.fill(x + shine, y, Math.min(x + fillW, x + shine + 3), y + H, 0x55FFFFFF);
        for (int i = 1; i < 10; i++) g.fill(x + W * i / 10, y + H - 3, x + W * i / 10 + 1, y + H, 0x66000000);

        String amount = ClientState.infinite ? "∞" : String.format("%,d / %,d", (int) ClientState.displayMatter, (int) ClientState.maxMatter);
        g.drawString(font, "§dMatéria §f" + amount, x, y + H + 4, 0xFFFFFF, true);
        if (!ClientState.active) return;

        // ---------------- modo e parâmetros
        MatterMode m = ClientState.mode();
        int ly = y + H + 16;
        boolean flash = ClientState.hudFlash > 0;
        String variant = m.variants.length > 1 ? " §7[" + m.variants[ClientState.variant()] + "]" : "";
        g.drawString(font, (flash ? "§e" : "§b") + "Modo: " + m.display + variant, x, ly, 0xFFFFFF, true);
        ly += 11;
        g.drawString(font, "§7" + m.primaryName() + ": §f" + ClientState.primary()
                + (m.maxSecondary() > 1 && !"-".equals(m.secondaryName()) ? "   §7" + m.secondaryName() + ": §f" + ClientState.secondary() : ""), x, ly, 0xFFFFFF, true);
        ly += 11;
        if (Keys.ABSORB.isDown()) {
            g.drawString(font, "§dAbsorvendo §7raio: §f" + ClientState.absorbRadius, x, ly, 0xFFFFFF, true);
            ly += 11;
        }
        if (ClientState.selection.length > 0) {
            g.drawString(font, "§aSelecionados: §f" + ClientState.selection.length + "  §7dist: §f" + String.format("%.1f", ClientState.holdDistanceEstimate), x, ly, 0xFFFFFF, true);
            ly += 11;
        }
        String hint = switch (m) {
            case MANIPULATE -> "Esq: levantar/selecionar  Dir: mover  X: arremessar  R: compactar";
            case TOWER, WALL, PILLAR -> "Segure Dir: crescer  Roda: tamanho  B: formato";
            case PLATFORM -> "Segure Dir: construir  B: área/caminho/escada";
            case SPHERE -> "Segure Dir: esfera cresce  Roda: raio máx.";
            case PROJECTILE -> "B/Dir: criar  Esq: selecionar  X: lançar  M: perseguir alvos";
            case PRISON -> "Esq no alvo: aprisionar  R: esmagar  X: arremessar";
            case HAND -> "B: criar mão  Segure Esq: fechar (Ctrl abre)  R: esmagar";
        };
        g.drawString(font, "§8" + hint, x, ly, 0xFFFFFF, false);

        // ---------------- mira
        int cx = g.guiWidth() / 2, cy = g.guiHeight() / 2;
        int col = absorbing ? 0xCCE080FF : 0x99B060FF;
        int r = 6 + (int) (Mth.sin(time * 0.3f) * 1.5f);
        g.fill(cx - r, cy, cx - r + 2, cy + 1, col);
        g.fill(cx + r - 1, cy, cx + r + 1, cy + 1, col);
        g.fill(cx, cy - r, cx + 1, cy - r + 2, col);
        g.fill(cx, cy + r - 1, cx + 1, cy + r + 1, col);
    }

    private static int lerpColor(int a, int b, float t) {
        int r = (int) Mth.lerp(t, (a >> 16) & 255, (b >> 16) & 255);
        int gg = (int) Mth.lerp(t, (a >> 8) & 255, (b >> 8) & 255);
        int bb = (int) Mth.lerp(t, a & 255, b & 255);
        return (r << 16) | (gg << 8) | bb;
    }
}
