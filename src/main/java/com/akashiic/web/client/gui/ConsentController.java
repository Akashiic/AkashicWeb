package com.akashiic.web.client.gui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

import com.akashiic.web.client.host.HostStatus;
import com.akashiic.web.client.install.ConsentInfo;
import com.akashiic.web.client.install.EngineLocator;
import com.akashiic.web.client.install.EngineState;
import com.akashiic.web.common.ReasonCode;
import com.akashiic.web.common.TextSanitizer;

/**
 * The install prompt's logic, without any GUI (ADR-006 "Consent screen"; D-017 moves the view to ModularUI2 in wave 3,
 * which then only replaces {@link ConsentGui}). It turns the installer's state into lines (lang key + arguments) and
 * buttons, and a button press into the installer call M3 asks for:
 * <ul>
 * <li>CONSENT_NEEDED: the facts (component, publisher, download size and host, disk size, folder) and the manual
 * import hint; Install calls {@code requestInstall()} and the prompt stays open to show progress; Not now calls
 * {@code cancelInstall()} and right-clicking a screen stops asking for the rest of the session.</li>
 * <li>DOWNLOADING: progress; Cancel calls {@code cancelInstall()} (also "do not ask again this session").</li>
 * <li>FAILED: the reason with the installer's detail, Repair ({@code repair()}) and the import and download
 * hints.</li>
 * <li>READY: "installed"; while the host is DOWN or DISABLED, its reason and Repair, which re-checks the engine and
 * then lets the host relaunch (offered only while the host is not running: Windows cannot replace files it has
 * open).</li>
 * <li>UNSUPPORTED (Android/Pojav, macOS, 32-bit): the reason; nothing to install.</li>
 * </ul>
 * Client thread only. Pure; tested in ConsentControllerTest.
 */
public final class ConsentController {

    public enum Button {

        INSTALL(LangKeys.CONSENT_ACCEPT),
        NOT_NOW(LangKeys.CONSENT_DECLINE),
        CANCEL(LangKeys.INSTALL_CANCEL),
        REPAIR(LangKeys.INSTALL_REPAIR),
        CLOSE(LangKeys.GUI_CLOSE);

        public final String langKey;

        Button(String langKey) {
            this.langKey = langKey;
        }
    }

    /** One text line: a lang key and its arguments. */
    public static final class Line {

        public final String key;
        public final Object[] args;

        Line(String key, Object... args) {
            this.key = key;
            this.args = args == null ? new Object[0] : args.clone();
        }

        @Override
        public String toString() {
            return key + java.util.Arrays.toString(args);
        }
    }

    /** What the prompt shows now. */
    public static final class View {

        public final String titleKey;
        public final List<Line> lines;
        public final List<Button> buttons;

        View(String titleKey, List<Line> lines, List<Button> buttons) {
            this.titleKey = titleKey;
            this.lines = Collections.unmodifiableList(lines);
            this.buttons = Collections.unmodifiableList(buttons);
        }
    }

    /** Session decisions the rest of the client reads ("Not now" stops the right-click prompt). */
    public interface Session {

        void postpone(boolean postponed);
    }

    /** Installer details are one line for logs; on the prompt they are a reason's detail, so keep them short. */
    static final int MAX_DETAIL = 160;

    private final Supplier<EngineLocator> locator;
    private final Supplier<HostStatus> hostStatus;
    private final Session session;

    /**
     * @param locator    M3's locator (null when the installer could not be wired)
     * @param hostStatus the host's status, or null while no host was started
     */
    public ConsentController(Supplier<EngineLocator> locator, Supplier<HostStatus> hostStatus, Session session) {
        this.locator = locator;
        this.hostStatus = hostStatus;
        this.session = session;
    }

    public View view() {
        EngineLocator l = locator.get();
        List<Line> lines = new ArrayList<>();
        List<Button> buttons = new ArrayList<>();
        if (l == null) {
            lines.add(new Line(ReasonCode.HOST_UNAVAILABLE.langKey(), ""));
            buttons.add(Button.CLOSE);
            return new View(LangKeys.TITLE, lines, buttons);
        }
        EngineState e = l.state();
        ConsentInfo info = l.consentInfo();
        if (e == null) e = new EngineState(EngineState.Kind.ABSENT, 0f, ReasonCode.ENGINE_ABSENT);
        switch (e.kind) {
            case CONSENT_NEEDED:
                if (info != null) {
                    lines.add(new Line(LangKeys.CONSENT_BODY1, text(info.component), text(info.publisher)));
                    lines.add(
                        new Line(
                            LangKeys.CONSENT_BODY2,
                            megabytes(info.downloadBytes),
                            text(info.sourceHost),
                            megabytes(info.diskBytes)));
                    lines.add(new Line(LangKeys.CONSENT_BODY3, folder(info.installDirDisplay, info.installDir)));
                    importHint(info, lines);
                } else {
                    lines.add(new Line(LangKeys.CONSENT_NEEDED));
                }
                buttons.add(Button.INSTALL);
                buttons.add(Button.NOT_NOW);
                return new View(LangKeys.CONSENT_TITLE, lines, buttons);
            case DOWNLOADING:
                lines.add(new Line(LangKeys.INSTALL_PROGRESS, Math.round(Math.max(0, Math.min(1, e.progress)) * 100)));
                buttons.add(Button.CANCEL);
                buttons.add(Button.CLOSE);
                return new View(LangKeys.CONSENT_TITLE, lines, buttons);
            case FAILED:
                lines.add(new Line(e.reason.langKey(), detail(l)));
                if (info != null) {
                    importHint(info, lines);
                    if (info.downloadUrl != null) lines.add(new Line(LangKeys.INSTALL_DOWNLOAD_HINT, info.downloadUrl));
                }
                buttons.add(Button.REPAIR);
                buttons.add(Button.CLOSE);
                return new View(LangKeys.TITLE, lines, buttons);
            case UNSUPPORTED:
                lines.add(new Line(e.reason.langKey(), detail(l)));
                buttons.add(Button.CLOSE);
                return new View(LangKeys.TITLE, lines, buttons);
            case READY: {
                HostStatus h = hostStatus.get();
                if (hostStopped(h)) {
                    lines.add(new Line(h.reason.langKey(), ""));
                    buttons.add(Button.REPAIR);
                } else {
                    lines.add(new Line(LangKeys.INSTALL_READY));
                }
                buttons.add(Button.CLOSE);
                return new View(LangKeys.TITLE, lines, buttons);
            }
            case ABSENT:
            default:
                lines.add(new Line(LangKeys.ENGINE_ABSENT));
                buttons.add(Button.CLOSE);
                return new View(LangKeys.TITLE, lines, buttons);
        }
    }

    /**
     * Applies a button; true when the prompt should close. A button the current view does not offer does nothing
     * (the state may have changed between drawing and the click).
     */
    public boolean press(Button b) {
        EngineLocator l = locator.get();
        if (b == Button.CLOSE || l == null) return true;
        if (!view().buttons.contains(b)) return false;
        switch (b) {
            case INSTALL:
                session.postpone(false);
                l.requestInstall();
                return false; // stay open: the progress is the answer
            case NOT_NOW:
                l.cancelInstall();
                session.postpone(true);
                return true;
            case CANCEL:
                l.cancelInstall();
                session.postpone(true);
                return false;
            case REPAIR:
                // Offered only while the host is not running (view()); when the engine comes back READY the client
                // re-enables the host (ServiceWatcher).
                l.repair();
                return false;
            default:
                return true;
        }
    }

    /**
     * Whether right-clicking a screen opens the prompt: while consent is needed (until "Not now" this session), and
     * after a failed install, where it offers Repair. Never for UNSUPPORTED: nothing can be installed there.
     */
    public static boolean opensOnUse(EngineState e, boolean postponed) {
        if (e == null) return false;
        return (e.kind == EngineState.Kind.CONSENT_NEEDED && !postponed) || e.kind == EngineState.Kind.FAILED;
    }

    private static boolean hostStopped(HostStatus h) {
        return h != null && (h.kind == HostStatus.Kind.DOWN || h.kind == HostStatus.Kind.DISABLED);
    }

    private static void importHint(ConsentInfo info, List<Line> lines) {
        String folder = folder(info.importDirDisplay, info.importDir);
        if (info.zipName != null && !folder.isEmpty()) {
            lines.add(new Line(LangKeys.INSTALL_IMPORT_HINT, text(info.zipName), folder));
        }
    }

    private static String detail(EngineLocator l) {
        return TextSanitizer.clean(l.detail(), MAX_DETAIL);
    }

    private static String folder(String display, java.io.File dir) {
        if (display != null && !display.isEmpty()) return text(display);
        return dir == null ? "" : text(dir.getPath());
    }

    private static String text(String s) {
        return TextSanitizer.clean(s, 256);
    }

    /** Whole megabytes (MiB, as Windows Explorer shows them), at least 1 for anything non-empty. */
    static String megabytes(long bytes) {
        if (bytes <= 0) return "?";
        return String.format(Locale.ROOT, "%d", Math.max(1, Math.round(bytes / (1024.0 * 1024.0))));
    }
}
