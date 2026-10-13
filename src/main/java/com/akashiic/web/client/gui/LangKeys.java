package com.akashiic.web.client.gui;

/**
 * Every lang key M5 uses besides {@code ReasonCode.langKey()}. Reason keys take exactly one argument (the detail, ""
 * when there is none); the keys here take the arguments their comment states, none otherwise. Keys added in wave 2 are
 * appended to both lang files under "# M5 wave 2"; LangKeysTest checks that every key here exists in both.
 */
public final class LangKeys {

    // Placeholder card on the screen
    public static final String TITLE = "akashicweb.placeholder.title";
    public static final String WAITING_SERVER = "akashicweb.placeholder.waiting_server";
    public static final String ENGINE_ABSENT = "akashicweb.placeholder.engine_absent";
    public static final String CONSENT_NEEDED = "akashicweb.placeholder.consent_needed";
    public static final String CONSENT_HINT = "akashicweb.placeholder.consent_hint";
    /** After "Not now" on the install prompt: right-click no longer asks; /awinstall does. M5 wave 2. */
    public static final String CONSENT_POSTPONED = "akashicweb.placeholder.consent_postponed";
    /** One argument: integer percent. */
    public static final String INSTALLING = "akashicweb.placeholder.installing";
    public static final String ENGINE_FAILED = "akashicweb.placeholder.engine_failed";
    public static final String STARTING = "akashicweb.placeholder.starting";
    public static final String LOADING = "akashicweb.placeholder.loading";
    public static final String NO_URL = "akashicweb.placeholder.no_url";

    // Address bar GUI
    public static final String GUI_ADDRESS_TITLE = "akashicweb.gui.address.title";
    public static final String GUI_GO = "akashicweb.gui.address.go";
    public static final String GUI_BACK = "akashicweb.gui.address.back";
    public static final String GUI_FORWARD = "akashicweb.gui.address.forward";
    public static final String GUI_RELOAD = "akashicweb.gui.address.reload";
    public static final String GUI_HOME = "akashicweb.gui.address.home";
    public static final String GUI_KEYBOARD = "akashicweb.gui.address.keyboard";
    public static final String GUI_TAKE_CONTROL = "akashicweb.gui.address.take_control";
    public static final String GUI_RELEASE_CONTROL = "akashicweb.gui.address.release_control";
    public static final String GUI_CLOSE = "akashicweb.gui.address.close";
    public static final String GUI_INVALID_URL = "akashicweb.gui.address.invalid_url";
    public static final String GUI_READ_ONLY = "akashicweb.gui.address.read_only";
    /** One argument: controller name or "-". */
    public static final String GUI_CONTROLLER = "akashicweb.gui.address.controller";
    public static final String GUI_NOBODY = "akashicweb.gui.address.nobody";
    public static final String GUI_YOU = "akashicweb.gui.address.you";

    // Keyboard GUI
    public static final String GUI_KEYBOARD_HINT = "akashicweb.gui.keyboard.hint";
    public static final String GUI_KEYBOARD_NOT_CONTROLLER = "akashicweb.gui.keyboard.not_controller";

    // Key binding
    public static final String KEY_CATEGORY = "akashicweb.key.category";
    public static final String KEY_KEYBOARD = "akashicweb.key.keyboard";
    public static final String KEY_ADDRESS = "akashicweb.key.address";

    // Chat
    public static final String CHAT_PREFIX = "akashicweb.chat.prefix";
    public static final String CHAT_NO_SCREEN = "akashicweb.chat.no_screen";

    // Install prompt (ConsentGui); wording awaits the owner's approval (P-12)
    public static final String CONSENT_TITLE = "akashicweb.install.consent.title";
    /** Two arguments: component, publisher. */
    public static final String CONSENT_BODY1 = "akashicweb.install.consent.body1";
    /** Three arguments: download MB, source host, disk MB. */
    public static final String CONSENT_BODY2 = "akashicweb.install.consent.body2";
    /** One argument: the AkashicWeb folder. */
    public static final String CONSENT_BODY3 = "akashicweb.install.consent.body3";
    public static final String CONSENT_ACCEPT = "akashicweb.install.consent.accept";
    public static final String CONSENT_DECLINE = "akashicweb.install.consent.decline";
    /** One argument: integer percent. */
    public static final String INSTALL_PROGRESS = "akashicweb.install.progress";
    public static final String INSTALL_REPAIR = "akashicweb.install.repair";
    /** Two arguments: zip file name, import folder. */
    public static final String INSTALL_IMPORT_HINT = "akashicweb.install.import_hint";
    /** M5 wave 2. */
    public static final String INSTALL_CANCEL = "akashicweb.install.cancel";
    /** M5 wave 2. */
    public static final String INSTALL_READY = "akashicweb.install.ready";
    /** M5 wave 2. One argument: the official download URL. */
    public static final String INSTALL_DOWNLOAD_HINT = "akashicweb.install.download_hint";

    private LangKeys() {}
}
