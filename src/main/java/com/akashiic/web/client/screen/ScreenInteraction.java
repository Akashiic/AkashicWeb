package com.akashiic.web.client.screen;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityClientPlayerMP;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraftforge.client.event.MouseEvent;

import org.lwjgl.input.Mouse;

import com.akashiic.web.client.gui.AddressBarGui;
import com.akashiic.web.client.gui.ConsentController;
import com.akashiic.web.common.net.ScreenInput;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * The in-world pointer on a screen picture (Forge MouseEvent, fired before vanilla turns a button into attack or use,
 * so cancelling it keeps the click from also breaking or using the block). The crosshair is the pointer:
 * <ul>
 * <li>It reaches {@link ClientScreens#POINTER_REACH} blocks (the server's default control reach), not vanilla's 4.5,
 * along the eye ray, unless a block stands in between.</li>
 * <li>Press and release of use or attack are a page left press and release, so drags work (seek bars, sliders, text
 * selection); the crosshair's moves go to the page every frame it lands on another pixel (hover menus, video
 * controls).</li>
 * <li>Only with an empty hand and while the game window has focus: holding something lets the item do its thing (as
 * M4's BlockScreen does), and the click that gives the window focus back is not a page click.</li>
 * <li>Sneak + use opens the address bar; sneak + attack is left alone so screens can still be broken.</li>
 * <li>The wheel scrolls the page (empty hand, not sneaking) instead of the hotbar.</li>
 * <li>While the browser needs consent (until "Not now") or its install failed, use opens the install prompt.</li>
 * </ul>
 * Input stays on this client (D-016); the server only sees the controller lock. Client thread only.
 */
public final class ScreenInteraction {

    /** Stop the block ray trace this short of the picture, so it never hits the screen's own blocks. */
    private static final double OCCLUSION_MARGIN = 0.05;

    private final ClientScreens screens;
    private final PointerPress press = new PointerPress();

    public ScreenInteraction(ClientScreens screens) {
        this.screens = screens;
    }

    @SubscribeEvent
    public void onMouse(MouseEvent e) {
        Minecraft mc = Minecraft.getMinecraft();
        if (e.button >= 0 && press.isReleaseOf(e.button, e.buttonstate)) {
            release(mc);
            e.setCanceled(true); // vanilla never saw the press
            return;
        }
        if (mc.currentScreen != null || mc.theWorld == null || mc.thePlayer == null || !mc.inGameHasFocus) return;
        ClientScreens.Hit hit = aim(mc, 1.0f);
        if (hit == null) return;
        boolean sneaking = mc.thePlayer.isSneaking();
        boolean emptyHand = mc.thePlayer.getHeldItem() == null;

        if (e.button == -1) {
            if (e.dwheel != 0 && !sneaking
                && emptyHand
                && screens.wheel(
                    hit.screen,
                    hit.u,
                    hit.v,
                    Integer.signum(e.dwheel) * Math.max(1, Math.abs(e.dwheel) / 120),
                    0))
                e.setCanceled(true);
            return;
        }
        if (!e.buttonstate) return; // a release of a press vanilla handled stays vanilla's
        boolean use = e.button == mc.gameSettings.keyBindUseItem.getKeyCode() + 100;
        boolean attack = e.button == mc.gameSettings.keyBindAttack.getKeyCode() + 100;
        if (!use && !attack) return;

        if (use && ConsentController.opensOnUse(HostAccess.SERVICES.engine(), ClientServices.consentPostponed())) {
            Runnable opener = ClientServices.consentOpener();
            if (opener != null) {
                opener.run();
                e.setCanceled(true);
            }
            return;
        }
        if (sneaking) {
            if (use) {
                mc.displayGuiScreen(new AddressBarGui(screens, hit.screen.key));
                e.setCanceled(true);
            }
            return;
        }
        if (!emptyHand) return;
        if (screens.pointerDown(hit.screen, hit.u, hit.v, ScreenInput.BUTTON_LEFT, 0)) {
            press.begin(hit.screen, e.button, hit.u, hit.v);
            e.setCanceled(true);
        }
    }

    /**
     * Render frame START (registered on the FML bus by {@link #frameListener()}): the crosshair's position goes to the
     * page as a move. A press whose release we cannot see any more (a GUI opened meanwhile and took the mouse) is
     * ended here, so the page never keeps a button down.
     */
    void onFrame(float partialTicks) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.theWorld == null || mc.thePlayer == null) {
            press.end();
            return;
        }
        if (press.active() && !Mouse.isButtonDown(press.mouseButton())) {
            release(mc);
            return;
        }
        if (mc.currentScreen != null) return;
        ClientScreens.Hit hit = aim(mc, partialTicks);
        if (press.active()) {
            if (press.follow(hit)) screens.pointerMove(press.screen(), press.u(), press.v());
        } else if (hit != null) {
            screens.pointerMove(hit.screen, hit.u, hit.v);
        }
    }

    private void release(Minecraft mc) {
        ClientScreen s = press.screen();
        press.follow(mc.thePlayer == null ? null : aim(mc, 1.0f));
        if (s != null && screens.get(s.key) == s)
            screens.pointerUp(s, press.u(), press.v(), ScreenInput.BUTTON_LEFT, 0);
        press.end();
    }

    /** An FML-bus listener that drives {@link #onFrame} (MouseEvent comes on the Forge bus). */
    public FrameListener frameListener() {
        return new FrameListener(this);
    }

    /** Public: FML's generated event handlers can only call public methods of public classes. */
    public static final class FrameListener {

        private final ScreenInteraction owner;

        FrameListener(ScreenInteraction owner) {
            this.owner = owner;
        }

        @SubscribeEvent
        public void onRenderTick(TickEvent.RenderTickEvent e) {
            if (e.phase == TickEvent.Phase.START) owner.onFrame(e.renderTickTime);
        }
    }

    /**
     * The screen picture under the crosshair within {@link ClientScreens#POINTER_REACH}, or null; shared with the key
     * bindings. The ray is vanilla's crosshair ray (EntityRenderer.getMouseOver: position and look at the partial
     * tick), and a block between the eye and the picture hides it.
     */
    public static ClientScreens.Hit crosshairHit(Minecraft mc, ClientScreens screens) {
        return mc.thePlayer == null || mc.theWorld == null ? null : aim(mc, screens, 1.0f);
    }

    private ClientScreens.Hit aim(Minecraft mc, float partialTicks) {
        return aim(mc, screens, partialTicks);
    }

    private static ClientScreens.Hit aim(Minecraft mc, ClientScreens screens, float partialTicks) {
        EntityClientPlayerMP p = mc.thePlayer;
        Vec3 eye = p.getPosition(partialTicks);
        Vec3 look = p.getLook(partialTicks);
        if (eye == null || look == null) return null;
        ClientScreens.Hit hit = screens.aim(
            mc.theWorld.provider.dimensionId,
            eye.xCoord,
            eye.yCoord,
            eye.zCoord,
            look.xCoord,
            look.yCoord,
            look.zCoord,
            ClientScreens.POINTER_REACH);
        if (hit == null) return null;
        double reach = Math.max(0, hit.distance - OCCLUSION_MARGIN);
        // Fresh vectors: 1.7.10's ray trace moves its start vector along the ray.
        Vec3 from = Vec3.createVectorHelper(eye.xCoord, eye.yCoord, eye.zCoord);
        Vec3 to = Vec3.createVectorHelper(
            eye.xCoord + look.xCoord * reach,
            eye.yCoord + look.yCoord * reach,
            eye.zCoord + look.zCoord * reach);
        MovingObjectPosition block = mc.theWorld.rayTraceBlocks(from, to);
        return block != null && block.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK ? null : hit;
    }
}
