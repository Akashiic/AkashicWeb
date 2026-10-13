package com.akashiic.web.client.render;

import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.tileentity.TileEntitySpecialRenderer;
import net.minecraft.client.resources.I18n;
import net.minecraft.tileentity.TileEntity;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import com.akashiic.web.client.gui.LangKeys;
import com.akashiic.web.client.screen.ClientScreen;
import com.akashiic.web.client.screen.ClientScreens;
import com.akashiic.web.client.screen.Placeholder;
import com.akashiic.web.client.screen.ScreenGeometry;
import com.akashiic.web.common.ScreenKey;
import com.akashiic.web.common.ScreenState;
import com.akashiic.web.common.ScreenTile;

/**
 * Draws screen pictures (ADR-005 "TESR"): draw-only (uploads happen in {@link FrameUploader}), a full-bright quad
 * (lightmap 240/240) built with the Tessellator in the face frame of {@link ScreenGeometry}, so rotation per face and
 * per screen comes from the same corners the click mapping uses. Without frames it draws a dark card with the
 * placeholder text; with a stale frame (budget, page error) a banner over it. Skipped entirely in Angelica's shadow
 * pass: a screen casts no shadow and the second draw would cost a frame's worth of state changes for nothing.
 * <p>
 * Bound to {@code ModContent.screenTileClass}; tiles are reached only through the {@link ScreenTile} contract.
 * Partly derived from Web Displays by montoyo (public domain), {@code client/renderers/ScreenRenderer}: lighting off,
 * face culling off, the 2/16 frame and the small lift off the surface.
 */
public final class ScreenRenderer extends TileEntitySpecialRenderer {

    /** 0xF000F0: sky and block light 15, the full-bright lightmap coordinate (240, 240). */
    private static final int FULL_BRIGHT = 15728880;
    private static final int CARD_COLOR = 0x12161C;
    private static final int BANNER_COLOR = 0x000000;
    private static final int TEXT_COLOR = 0xFFFFFF;
    private static final int TITLE_COLOR = 0x8FD3FF;
    /** Text sits just in front of the picture (local z points into the face). */
    private static final double TEXT_LIFT = -0.002;

    private final ClientScreens screens;
    private final FloatBuffer matrix = BufferUtils.createFloatBuffer(16);
    private final Map<ScreenKey, CachedText> textCache = new HashMap<>();

    public ScreenRenderer(ClientScreens screens) {
        this.screens = screens;
    }

    @Override
    public void renderTileEntityAt(TileEntity te, double x, double y, double z, float partialTicks) {
        if (!(te instanceof ScreenTile) || AngelicaCompat.isShadowPass()) return;
        Collection<ScreenState> states = ((ScreenTile) te).screens();
        if (states == null || states.isEmpty()) return;

        RenderHelper.disableStandardItemLighting();
        OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, 240f, 240f);
        GL11.glDisable(GL11.GL_CULL_FACE);
        boolean blend = GL11.glIsEnabled(GL11.GL_BLEND);
        if (blend) GL11.glDisable(GL11.GL_BLEND);
        try {
            for (ScreenState st : states) {
                if (st == null || st.key == null) continue;
                ClientScreen s = screens.get(st.key);
                if (s == null) {
                    screens.observe(st);
                    s = screens.get(st.key);
                    if (s == null) continue;
                }
                ScreenGeometry g = s.geometry();
                if (g == null) continue;
                draw(s, g, x + (st.key.x - te.xCoord), y + (st.key.y - te.yCoord), z + (st.key.z - te.zCoord));
            }
        } finally {
            GL11.glColor4f(1f, 1f, 1f, 1f);
            if (blend) GL11.glEnable(GL11.GL_BLEND);
            GL11.glEnable(GL11.GL_CULL_FACE);
            RenderHelper.enableStandardItemLighting();
        }
    }

    private void draw(ClientScreen s, ScreenGeometry g, double ox, double oy, double oz) {
        Placeholder p = screens.placeholder(s);
        boolean live = (p == null || p.overFrame) && s.hasFrame && s.texture != null && s.texture.isAllocated();
        GL11.glPushMatrix();
        try {
            GL11.glTranslated(ox, oy, oz);
            matrix.clear();
            matrix.put(g.basisMatrix());
            matrix.flip();
            GL11.glMultMatrix(matrix);
            if (live) {
                s.texture.bind();
                texturedQuad(g);
            } else {
                solidQuad(0, 0, g.quadWidth, g.quadHeight, 0, CARD_COLOR);
            }
            if (p != null) text(s.key, g, p, live);
        } finally {
            GL11.glPopMatrix();
        }
    }

    /** The page, with texture corners rotated as {@link ScreenGeometry#texCoord} says. */
    private static void texturedQuad(ScreenGeometry g) {
        double w = g.quadWidth, h = g.quadHeight;
        double[][] corners = { { 0, 0 }, { w, 0 }, { w, h }, { 0, h } };
        Tessellator t = Tessellator.instance;
        t.startDrawingQuads();
        t.setBrightness(FULL_BRIGHT);
        t.setColorOpaque_F(1f, 1f, 1f);
        for (int i = 0; i < 4; i++) {
            float[] uv = g.texCoord(i);
            t.addVertexWithUV(corners[i][0], corners[i][1], 0, uv[0], uv[1]);
        }
        t.draw();
    }

    private static void solidQuad(double x0, double y0, double x1, double y1, double z, int rgb) {
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        Tessellator t = Tessellator.instance;
        t.startDrawingQuads();
        t.setBrightness(FULL_BRIGHT);
        t.setColorOpaque_I(rgb);
        t.addVertex(x0, y0, z);
        t.addVertex(x1, y0, z);
        t.addVertex(x1, y1, z);
        t.addVertex(x0, y1, z);
        t.draw();
        GL11.glEnable(GL11.GL_TEXTURE_2D);
    }

    private void text(ScreenKey key, ScreenGeometry g, Placeholder p, boolean overFrame) {
        FontRenderer fr = Minecraft.getMinecraft().fontRenderer;
        if (fr == null) return;
        CachedText c = textCache.get(key);
        if (c == null || !c.placeholder.equals(p)) {
            c = new CachedText(p);
            textCache.put(key, c);
        }
        // One text line is about a seventh of the picture height, within sane bounds for tiny and huge screens.
        double lineH = Math.max(0.06, Math.min(0.5, g.quadHeight / 7.0));
        double scale = lineH / 10.0; // 9 px glyphs + 1 px spacing per line
        int widthPx = (int) Math.max(20, g.quadWidth * 0.9 / scale);
        if (overFrame) {
            double bandH = lineH * 1.6;
            // Between the page (z 0) and the text: in front of the frame, so no z-fighting with it.
            solidQuad(0, g.quadHeight - bandH, g.quadWidth, g.quadHeight, TEXT_LIFT / 2, BANNER_COLOR);
            String line = fr.trimStringToWidth(c.main, widthPx);
            drawLines(fr, Collections.singletonList(line), g, scale, g.quadHeight - bandH / 2, TEXT_COLOR);
            return;
        }
        List<String> lines = new ArrayList<>();
        lines.add(c.title);
        if (!c.main.isEmpty()) lines.addAll(wrap(fr, c.main, widthPx));
        if (c.detail != null && !c.detail.isEmpty()) lines.addAll(wrap(fr, c.detail, widthPx));
        int maxLines = Math.max(1, (int) (g.quadHeight * 0.9 / lineH));
        if (lines.size() > maxLines) lines = lines.subList(0, maxLines);
        drawLines(fr, lines, g, scale, g.quadHeight / 2, TEXT_COLOR);
    }

    private static List<String> wrap(FontRenderer fr, String text, int widthPx) {
        List<String> out = new ArrayList<>();
        for (Object o : fr.listFormattedStringToWidth(text, widthPx)) out.add(String.valueOf(o));
        return out;
    }

    /** Centred lines around {@code centerY} (blocks), first line in the title colour when there are several. */
    private static void drawLines(FontRenderer fr, List<String> lines, ScreenGeometry g, double scale, double centerY,
        int color) {
        GL11.glPushMatrix();
        try {
            GL11.glTranslated(g.quadWidth / 2, centerY, TEXT_LIFT);
            GL11.glScaled(scale, scale, scale);
            int total = lines.size() * 10;
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                int c = lines.size() > 1 && i == 0 ? TITLE_COLOR : color;
                fr.drawString(line, -fr.getStringWidth(line) / 2, -total / 2 + i * 10 + 1, c);
            }
        } finally {
            GL11.glPopMatrix();
        }
    }

    /** Translations are looked up once per placeholder change, not every frame. */
    private static final class CachedText {

        final Placeholder placeholder;
        final String title;
        final String main;
        final String detail;

        CachedText(Placeholder p) {
            this.placeholder = p;
            this.title = I18n.format(LangKeys.TITLE);
            this.main = LangKeys.TITLE.equals(p.key) ? "" : I18n.format(p.key, p.args);
            this.detail = p.detailKey == null ? null : I18n.format(p.detailKey);
        }
    }

    /** Drops cached text for screens that are gone (called on world unload). */
    public void clearCache() {
        textCache.clear();
    }
}
