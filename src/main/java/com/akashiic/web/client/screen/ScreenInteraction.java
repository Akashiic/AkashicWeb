package com.akashiic.web.client.screen;

import net.minecraft.client.Minecraft;
import net.minecraft.util.MovingObjectPosition;
import net.minecraftforge.client.event.MouseEvent;

import com.akashiic.web.client.gui.AddressBarGui;
import com.akashiic.web.client.install.EngineState;
import com.akashiic.web.common.net.ScreenInput;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;

/**
 * In-world mouse on a screen picture (Forge MouseEvent, fired before vanilla turns the button into attack/use, so
 * cancelling it keeps the click from also breaking or using the block):
 * <ul>
 * <li>use (right button) or attack (left button) on the picture: a page click at the crosshair's (u, v);</li>
 * <li>sneak + use: the address bar; sneak + attack is left alone so screens can still be broken;</li>
 * <li>wheel while aiming at a screen you may click (not sneaking): scrolls the page instead of the hotbar;</li>
 * <li>while the engine needs consent: use opens M3's install prompt.</li>
 * </ul>
 * The server re-checks rights, reach and the ray-plane hit (ADR-010); the local rights check only avoids swallowing
 * clicks on screens the player cannot use.
 */
public final class ScreenInteraction {

    private final ClientScreens screens;

    public ScreenInteraction(ClientScreens screens) {
        this.screens = screens;
    }

    @SubscribeEvent
    public void onMouse(MouseEvent e) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.currentScreen != null || mc.theWorld == null || mc.thePlayer == null) return;
        ClientScreens.Hit hit = crosshairHit(mc, screens);
        if (hit == null) return;
        boolean sneaking = mc.thePlayer.isSneaking();

        if (e.button == -1) {
            if (e.dwheel != 0 && !sneaking
                && screens.wheel(
                    hit.screen,
                    hit.u,
                    hit.v,
                    Integer.signum(e.dwheel) * Math.max(1, Math.abs(e.dwheel) / 120),
                    0))
                e.setCanceled(true);
            return;
        }
        if (!e.buttonstate) return; // act on press; the release reaches vanilla untouched
        boolean use = e.button == mc.gameSettings.keyBindUseItem.getKeyCode() + 100;
        boolean attack = e.button == mc.gameSettings.keyBindAttack.getKeyCode() + 100;
        if (!use && !attack) return;

        if (use && consentNeeded()) {
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
        if (screens.click(hit.screen, hit.u, hit.v, ScreenInput.BUTTON_LEFT, 0)) e.setCanceled(true);
    }

    /** The screen picture under the crosshair, or null. Shared with the key bindings. */
    public static ClientScreens.Hit crosshairHit(Minecraft mc, ClientScreens screens) {
        MovingObjectPosition mop = mc.objectMouseOver;
        if (mop == null || mop.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK || mop.hitVec == null)
            return null;
        return screens.hit(
            mc.theWorld.provider.dimensionId,
            mop.hitVec.xCoord,
            mop.hitVec.yCoord,
            mop.hitVec.zCoord,
            mop.sideHit);
    }

    private static boolean consentNeeded() {
        EngineState s = HostAccess.SERVICES.engine();
        return s != null && s.kind == EngineState.Kind.CONSENT_NEEDED;
    }
}
