package com.akashiic.web.client.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.akashiic.web.client.FakeLocator;
import com.akashiic.web.client.LangText;
import com.akashiic.web.client.host.HostStatus;
import com.akashiic.web.client.install.EngineLocator;
import com.akashiic.web.client.install.EngineState;
import com.akashiic.web.common.ReasonCode;

/** The install prompt's logic: lines, buttons and installer calls per state, all text formatting cleanly. */
class ConsentControllerTest {

    private FakeLocator locator;
    private HostStatus[] host;
    private List<Boolean> postponed;
    private ConsentController c;

    @BeforeEach
    void setUp() {
        locator = new FakeLocator();
        host = new HostStatus[1];
        postponed = new ArrayList<>();
        c = new ConsentController(() -> locator, () -> host[0], postponed::add);
    }

    private List<String> keys(ConsentController.View v) {
        List<String> out = new ArrayList<>();
        for (ConsentController.Line l : v.lines) {
            out.add(l.key);
            LangText.assertFormats(l.key, l.args); // never "Format error" in either language
        }
        LangText.assertFormats(v.titleKey);
        for (ConsentController.Button b : v.buttons) LangText.assertFormats(b.langKey);
        return out;
    }

    @Test
    void consentShowsTheFactsAndTheImportHint() {
        ConsentController.View v = c.view();
        assertEquals(LangKeys.CONSENT_TITLE, v.titleKey);
        assertEquals(
            Arrays.asList(
                LangKeys.CONSENT_BODY1,
                LangKeys.CONSENT_BODY2,
                LangKeys.CONSENT_BODY3,
                LangKeys.INSTALL_IMPORT_HINT),
            keys(v));
        assertEquals(Arrays.asList(ConsentController.Button.INSTALL, ConsentController.Button.NOT_NOW), v.buttons);
        Object[] sizes = v.lines.get(1).args;
        assertEquals("120", sizes[0]);
        assertEquals("github.com", sizes[1]);
        assertEquals("330", sizes[2]);
        assertEquals("%LOCALAPPDATA%\\AkashicWeb", v.lines.get(2).args[0], "the folder as players see it");
        assertEquals("electron-v44.7.0-win32-x64.zip", v.lines.get(3).args[0]);
        assertEquals("%LOCALAPPDATA%\\AkashicWeb\\import", v.lines.get(3).args[1]);
        assertEquals(
            "AkashicWeb screens use a separate browser: Electron 44.7.0 (Chromium 152.0.7977.130), by Electron project.",
            String.format(LangText.EN.get(LangKeys.CONSENT_BODY1), v.lines.get(0).args));
    }

    @Test
    void installStartsTheDownloadAndStaysOpenForTheProgress() {
        assertFalse(c.press(ConsentController.Button.INSTALL));
        assertEquals(Arrays.asList("requestInstall"), locator.calls);
        assertEquals(Arrays.asList(false), postponed);
        locator.state = new EngineState(EngineState.Kind.DOWNLOADING, 0.426f, null);
        ConsentController.View v = c.view();
        assertEquals(Arrays.asList(LangKeys.INSTALL_PROGRESS), keys(v));
        assertEquals(43, v.lines.get(0).args[0]);
        assertEquals(Arrays.asList(ConsentController.Button.CANCEL, ConsentController.Button.CLOSE), v.buttons);
        assertFalse(c.press(ConsentController.Button.CANCEL));
        assertEquals(Arrays.asList("requestInstall", "cancelInstall"), locator.calls);
        assertEquals(Arrays.asList(false, true), postponed, "a cancelled download stops asking too");
    }

    @Test
    void notNowCancelsAndStopsAskingThisSession() {
        assertTrue(c.press(ConsentController.Button.NOT_NOW), "closes");
        assertEquals(Arrays.asList("cancelInstall"), locator.calls);
        assertEquals(Arrays.asList(true), postponed);
        assertFalse(ConsentController.opensOnUse(locator.state, true), "right-click no longer asks");
        assertTrue(ConsentController.opensOnUse(locator.state, false));
    }

    @Test
    void failedOffersRepairWithTheReasonDetailAndBothHints() {
        locator.state = new EngineState(EngineState.Kind.FAILED, 0f, ReasonCode.HASH_MISMATCH);
        locator.detail = "§cengine damaged: resources/app.asar";
        ConsentController.View v = c.view();
        assertEquals(
            Arrays.asList(
                ReasonCode.HASH_MISMATCH.langKey(),
                LangKeys.INSTALL_IMPORT_HINT,
                LangKeys.INSTALL_DOWNLOAD_HINT),
            keys(v));
        assertEquals("engine damaged: resources/app.asar", v.lines.get(0).args[0], "formatting codes dropped");
        assertEquals(Arrays.asList(ConsentController.Button.REPAIR, ConsentController.Button.CLOSE), v.buttons);
        assertFalse(c.press(ConsentController.Button.REPAIR));
        assertEquals(Arrays.asList("repair"), locator.calls);
        assertTrue(ConsentController.opensOnUse(locator.state, true), "Repair is offered on right-click");
    }

    @Test
    void unsupportedPlatformsOnlyExplain() {
        locator.state = new EngineState(EngineState.Kind.UNSUPPORTED, 0f, ReasonCode.UNSUPPORTED_OS);
        locator.info = null;
        locator.detail = "Android (Pojav)";
        ConsentController.View v = c.view();
        assertEquals(Arrays.asList(ReasonCode.UNSUPPORTED_OS.langKey()), keys(v));
        assertEquals(Arrays.asList(ConsentController.Button.CLOSE), v.buttons);
        assertFalse(c.press(ConsentController.Button.INSTALL), "not offered: nothing happens");
        assertTrue(locator.calls.isEmpty());
        assertFalse(ConsentController.opensOnUse(locator.state, false));
    }

    @Test
    void readyOffersRepairOnlyWhileTheHostIsNotRunning() {
        locator.state = new EngineState(EngineState.Kind.READY, 1f, null);
        assertEquals(Arrays.asList(LangKeys.INSTALL_READY), keys(c.view()));
        host[0] = HostStatus.READY;
        assertFalse(
            c.view().buttons.contains(ConsentController.Button.REPAIR),
            "Windows cannot replace files the running host has open");
        host[0] = HostStatus.disabled(ReasonCode.HOST_LAUNCH_FAILED);
        ConsentController.View v = c.view();
        assertEquals(Arrays.asList(ReasonCode.HOST_LAUNCH_FAILED.langKey()), keys(v));
        assertTrue(v.buttons.contains(ConsentController.Button.REPAIR));
        c.press(ConsentController.Button.REPAIR);
        assertEquals(Arrays.asList("repair"), locator.calls);
    }

    @Test
    void anUnwiredInstallerSaysSoAndCloses() {
        ConsentController none = new ConsentController(() -> (EngineLocator) null, () -> null, p -> {});
        assertEquals(Arrays.asList(ReasonCode.HOST_UNAVAILABLE.langKey()), keys(none.view()));
        assertTrue(none.press(ConsentController.Button.INSTALL));
    }

    @Test
    void megabytesAreWholeAndNeverZero() {
        assertEquals("1", ConsentController.megabytes(10));
        assertEquals("128", ConsentController.megabytes(128L * 1024 * 1024));
        assertEquals("?", ConsentController.megabytes(0));
    }
}
