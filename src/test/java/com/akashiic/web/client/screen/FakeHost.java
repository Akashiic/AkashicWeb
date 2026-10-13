package com.akashiic.web.client.screen;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.akashiic.web.client.host.BrowserHost;
import com.akashiic.web.client.host.CreateSpec;
import com.akashiic.web.client.host.FrameRing;
import com.akashiic.web.client.host.HostListener;
import com.akashiic.web.client.host.HostStatus;
import com.akashiic.web.client.host.InputEvent;
import com.akashiic.web.client.host.SyncCommand;
import com.akashiic.web.client.install.EngineState;
import com.akashiic.web.common.ReasonCode;
import com.akashiic.web.common.SitePolicy;

/** Records every call as a short string so tests can assert order (e.g. mute before destroy). */
public final class FakeHost implements BrowserHost, HostAccess {

    /** Frame rings by screen id, set by tests that exercise the uploader. */
    public final Map<Integer, FrameRing> rings = new HashMap<>();
    public final List<String> calls = new ArrayList<>();
    final List<HostListener> listeners = new ArrayList<>();
    final List<CreateSpec> created = new ArrayList<>();
    final List<SyncCommand> syncs = new ArrayList<>();
    final List<InputEvent> inputs = new ArrayList<>();
    HostStatus status = HostStatus.READY;
    EngineState engine = new EngineState(EngineState.Kind.READY, 1, ReasonCode.OK);
    boolean available = true;
    boolean started;
    int nextId = 1;

    // ---------------------------------------------------------------- HostAccess

    @Override
    public boolean available() {
        return available;
    }

    @Override
    public BrowserHost started() {
        return started ? this : null;
    }

    @Override
    public BrowserHost obtain() {
        if (!available) return null;
        started = true;
        return this;
    }

    @Override
    public EngineState engine() {
        return engine;
    }

    // ---------------------------------------------------------------- BrowserHost

    @Override
    public HostStatus status() {
        return status;
    }

    @Override
    public int createScreen(CreateSpec spec) {
        int id = nextId++;
        created.add(spec);
        calls.add("create " + id + " " + spec.url);
        return id;
    }

    @Override
    public void destroyScreen(int screenId) {
        calls.add("destroy " + screenId);
    }

    @Override
    public void navigate(int screenId, String url) {
        calls.add("navigate " + screenId + " " + url);
    }

    @Override
    public void action(int screenId, String action) {
        calls.add("action " + screenId + " " + action);
    }

    @Override
    public void input(int screenId, InputEvent event) {
        inputs.add(event);
        calls.add("input " + screenId + " " + event.type);
    }

    @Override
    public void audio(int screenId, boolean muted, float volume) {
        calls.add(String.format(Locale.ROOT, "audio %d %s %.2f", screenId, muted ? "muted" : "on", volume));
    }

    @Override
    public void render(int screenId, int fps, boolean painting) {
        calls.add("render " + screenId + " " + fps + " " + painting);
    }

    @Override
    public void resize(int screenId, int width, int height) {
        calls.add("resize " + screenId + " " + width + "x" + height);
    }

    @Override
    public void sync(int screenId, SyncCommand cmd) {
        syncs.add(cmd);
        calls.add("sync " + screenId);
    }

    @Override
    public void policy(SitePolicy policy) {
        calls.add("policy " + policy.mode);
    }

    @Override
    public FrameRing frames(int screenId) {
        return rings.get(screenId);
    }

    @Override
    public void addListener(HostListener listener) {
        listeners.add(listener);
        calls.add("listener");
    }

    @Override
    public void removeListener(HostListener listener) {
        listeners.remove(listener);
    }

    @Override
    public void shutdown() {
        calls.add("shutdown");
    }

    boolean saw(String prefix) {
        for (String c : calls) if (c.startsWith(prefix)) return true;
        return false;
    }

    int indexOf(String prefix) {
        for (int i = 0; i < calls.size(); i++) if (calls.get(i)
            .startsWith(prefix)) return i;
        return -1;
    }
}
