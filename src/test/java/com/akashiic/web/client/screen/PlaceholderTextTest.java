package com.akashiic.web.client.screen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.akashiic.web.client.LangText;
import com.akashiic.web.client.gui.ClientNotices;
import com.akashiic.web.client.gui.LangKeys;
import com.akashiic.web.client.host.HostStatus;
import com.akashiic.web.client.install.EngineState;
import com.akashiic.web.common.ReasonCode;
import com.akashiic.web.common.SitePolicy;
import com.akashiic.web.protocol.Protocol;

/**
 * Regression for the "Format error: ... %s" cards seen in the 2026-10-13 dev-client run: every placeholder the model
 * can produce formats cleanly in both shipped languages, the way Minecraft formats it.
 */
class PlaceholderTextTest {

    private static PlaceholderModel.Inputs live() {
        PlaceholderModel.Inputs in = new PlaceholderModel.Inputs();
        in.session = new ServerSession();
        in.session.onHello(Protocol.MAJOR, 0, false, SitePolicy.blocklist(Collections.<String>emptyList()));
        in.engine = new EngineState(EngineState.Kind.READY, 1, ReasonCode.OK);
        in.hostAvailable = true;
        in.hostStatus = HostStatus.READY;
        in.browserLive = true;
        in.granted = true;
        in.hasFrame = true;
        in.painting = true;
        in.hasUrl = true;
        return in;
    }

    /** Every branch of the model, with every reason code in every reason slot. */
    private static List<Placeholder> everyPlaceholder() {
        List<PlaceholderModel.Inputs> all = new ArrayList<>();
        PlaceholderModel.Inputs in = live();
        in.session.onHello(Protocol.MAJOR + 1, 0, false, null);
        all.add(in);
        in = live();
        in.session.onHello(Protocol.MAJOR, 0, true, null);
        all.add(in);
        in = live();
        in.session.reset();
        all.add(in);
        in = live();
        in.engine = null;
        all.add(in);
        in = live();
        in.hostAvailable = false;
        all.add(in);
        for (EngineState.Kind k : EngineState.Kind.values()) {
            for (ReasonCode r : ReasonCode.values()) {
                for (boolean postponed : new boolean[] { false, true }) {
                    in = live();
                    in.engine = new EngineState(k, 0.5f, r);
                    in.consentPostponed = postponed;
                    all.add(in);
                }
            }
        }
        for (HostStatus.Kind k : HostStatus.Kind.values()) {
            for (ReasonCode r : ReasonCode.values()) {
                in = live();
                in.hostStatus = new HostStatus(k, r);
                all.add(in);
            }
        }
        for (ReasonCode r : ReasonCode.values()) {
            for (boolean frame : new boolean[] { false, true }) {
                for (String detail : new String[] { "", "example.com" }) {
                    in = live();
                    in.failure = r;
                    in.failureDetail = detail;
                    in.hasFrame = frame;
                    all.add(in);
                }
            }
        }
        for (boolean frame : new boolean[] { false, true }) {
            for (boolean url : new boolean[] { false, true }) {
                for (HostStatus status : new HostStatus[] { null, HostStatus.STARTING, HostStatus.READY }) {
                    in = live();
                    in.granted = false;
                    in.browserLive = false;
                    in.hasFrame = frame;
                    in.hasUrl = url;
                    in.hostStatus = status;
                    all.add(in);
                    in = live();
                    in.hasFrame = frame;
                    in.hasUrl = url;
                    in.hostStatus = status;
                    all.add(in);
                }
            }
        }
        List<Placeholder> out = new ArrayList<>();
        for (PlaceholderModel.Inputs i : all) {
            Placeholder p = PlaceholderModel.resolve(i);
            if (p != null) out.add(p);
        }
        return out;
    }

    @Test
    void everyPlaceholderFormatsWithoutAFormatError() {
        Set<String> seen = new LinkedHashSet<>();
        for (Placeholder p : everyPlaceholder()) {
            if (!LangKeys.TITLE.equals(p.key) || p.args.length > 0) LangText.assertFormats(p.key, p.args);
            if (p.detailKey != null) LangText.assertFormats(p.detailKey, p.detailArgs);
            if (p.key.startsWith("akashicweb.reason.")) assertEquals(1, p.args.length, p.toString());
            if (p.detailKey != null && p.detailKey.startsWith("akashicweb.reason."))
                assertEquals(1, p.detailArgs.length, p.toString());
            seen.add(p.key);
            if (p.detailKey != null) seen.add(p.detailKey);
        }
        for (ReasonCode r : ReasonCode.values()) {
            if (r != ReasonCode.OK) assertTrue(seen.contains(r.langKey()), "reachable: " + r);
        }
        assertTrue(seen.contains(LangKeys.CONSENT_POSTPONED));
        assertTrue(seen.contains(LangKeys.NO_URL));
    }

    @Test
    void theDevClientCardsFromTheEvidenceFileNowFormat() {
        // Unwired client: "Browser unavailable on this client. %s" was printed as "Format error: ...".
        PlaceholderModel.Inputs in = live();
        in.engine = null;
        Placeholder unwired = PlaceholderModel.resolve(in);
        assertEquals(ReasonCode.HOST_UNAVAILABLE.langKey(), unwired.detailKey);
        assertEquals(1, unwired.detailArgs.length);
        assertEquals(
            "Browser unavailable on this client. ",
            String.format(LangText.EN.get(unwired.detailKey), unwired.detailArgs));
        // The budget banner.
        in = live();
        in.granted = false;
        in.browserLive = false;
        Placeholder banner = PlaceholderModel.resolve(in);
        assertTrue(banner.overFrame);
        LangText.assertFormats(banner.key, banner.args);
    }

    @Test
    void chatNoticesForReasonsAlwaysCarryOneArgument() {
        for (ReasonCode r : ReasonCode.values()) {
            Object[] none = ClientNotices.safeArgs(r.langKey());
            assertEquals(1, none.length, r.name());
            LangText.assertChatFormats(r.langKey(), none);
            LangText.assertChatFormats(r.langKey(), ClientNotices.safeArgs(r.langKey(), "x.example"));
        }
        assertEquals(0, ClientNotices.safeArgs(LangKeys.CHAT_NO_SCREEN).length, "other keys keep their own count");
    }

    @Test
    void everyLangKeyM5UsesExistsInBothLanguages() throws Exception {
        for (Field f : LangKeys.class.getDeclaredFields()) {
            if (!Modifier.isStatic(f.getModifiers()) || f.getType() != String.class) continue;
            String key = (String) f.get(null);
            assertTrue(LangText.EN.containsKey(key), "en_US lacks " + key);
            assertTrue(LangText.PT.containsKey(key), "pt_BR lacks " + key);
        }
    }
}
