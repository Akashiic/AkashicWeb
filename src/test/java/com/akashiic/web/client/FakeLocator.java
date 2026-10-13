package com.akashiic.web.client;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import com.akashiic.web.client.install.ConsentInfo;
import com.akashiic.web.client.install.EngineLocator;
import com.akashiic.web.client.install.EngineState;
import com.akashiic.web.common.ReasonCode;

/** An EngineLocator that records the calls M5 makes; the state is set by the test. */
public final class FakeLocator implements EngineLocator {

    public final List<String> calls = new ArrayList<>();
    public EngineState state = new EngineState(EngineState.Kind.CONSENT_NEEDED, 0f, ReasonCode.ENGINE_ABSENT);
    public ConsentInfo info = new ConsentInfo(
        "Electron 44.7.0 (Chromium 152.0.7977.130)",
        "Electron project",
        "github.com",
        120L * 1024 * 1024,
        330L * 1024 * 1024,
        1024L * 1024 * 1024,
        new File("/home/p/.local/share/AkashicWeb"),
        "%LOCALAPPDATA%\\AkashicWeb",
        "electron-v44.7.0-win32-x64.zip",
        "https://github.com/electron/electron/releases/download/v44.7.0/electron-v44.7.0-win32-x64.zip",
        new File("/home/p/.local/share/AkashicWeb/import"),
        "%LOCALAPPDATA%\\AkashicWeb\\import");
    public String detail = "";
    public File userData = new File("/tmp/akw-profile");

    @Override
    public EngineState state() {
        return state;
    }

    @Override
    public File hostExecutable() {
        return null;
    }

    @Override
    public File userDataDir() {
        return userData;
    }

    @Override
    public String engineId() {
        return "electron-44.7.0-test";
    }

    @Override
    public void requestInstall() {
        calls.add("requestInstall");
    }

    @Override
    public String detail() {
        return detail;
    }

    @Override
    public ConsentInfo consentInfo() {
        return info;
    }

    @Override
    public void verifyAfterLoadFailure(String launchError) {
        calls.add("verify " + launchError);
    }

    @Override
    public void cancelInstall() {
        calls.add("cancelInstall");
    }

    @Override
    public void repair() {
        calls.add("repair");
    }
}
