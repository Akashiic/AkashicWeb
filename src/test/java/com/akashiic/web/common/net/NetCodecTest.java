package com.akashiic.web.common.net;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;

import com.akashiic.web.common.BlockSide;
import com.akashiic.web.common.ReasonCode;
import com.akashiic.web.common.Rights;
import com.akashiic.web.common.ScreenKey;
import com.akashiic.web.common.ScreenMode;
import com.akashiic.web.common.ScreenState;
import com.akashiic.web.common.SitePolicy;
import com.akashiic.web.common.TestScreens;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

class NetCodecTest {

    private static final ScreenKey KEY = new ScreenKey(-1, 100, 64, -200, 3);

    /** Encodes {@code m} and decodes it into a fresh instance. */
    private static <T extends AkwMessage> T roundTrip(T m, Supplier<T> fresh) {
        ByteBuf b = Unpooled.buffer();
        m.toBytes(b);
        T out = fresh.get();
        out.fromBytes(b);
        assertNull(
            out.malformed,
            out.getClass()
                .getSimpleName() + ": "
                + out.malformed);
        return out;
    }

    private static <T extends AkwMessage> T decode(ByteBuf b, Supplier<T> fresh) {
        T out = fresh.get();
        out.fromBytes(b);
        return out;
    }

    // ---- round trips of every packet in PROTOCOL.md section 2 ----

    @Test
    void hello() {
        SitePolicy p = SitePolicy
            .of(SitePolicy.Mode.BLOCKLIST, Arrays.asList("a.com"), Arrays.asList("b.com", "c.org"));
        S2CHello h = roundTrip(new S2CHello(1, 3, true, p, 123456789L), S2CHello::new);
        assertEquals(1, h.protoMajor);
        assertEquals(3, h.protoMinor);
        assertTrue(h.killSwitch);
        assertEquals(p, h.policy);
        assertEquals(123456789L, h.serverTimeMs);
    }

    @Test
    void screenState() {
        ScreenState s = TestScreens.state(BlockSide.EAST, 1, 2, 3, 16, 9, 90);
        s.epoch = -5;
        s.controller = TestScreens.FRIEND;
        s.mode = ScreenMode.PRIVATE;
        s.locked = true;
        s.otherRights = Rights.CLICK_TYPE;
        ScreenState r = roundTrip(new S2CScreenState(s), S2CScreenState::new).state;
        assertEquals(s.key, r.key);
        assertEquals(16, r.widthBlocks);
        assertEquals(9, r.heightBlocks);
        assertEquals(90, r.rotation);
        assertEquals(1280, r.resolutionW);
        assertEquals(s.url, r.url);
        assertEquals(-5, r.epoch);
        assertEquals(s.owner, r.owner);
        assertEquals(s.friends, r.friends);
        assertEquals("Friend", r.friendNames.get(TestScreens.FRIEND));
        assertEquals(TestScreens.FRIEND, r.controller);
        assertEquals(ScreenMode.PRIVATE, r.mode);
        assertTrue(r.locked);
        assertEquals(Rights.CLICK_TYPE, r.otherRights);
    }

    @Test
    void urlInputClockPongNotice() {
        S2CUrl u = roundTrip(new S2CUrl(KEY, "https://x.com/a?b=c", 7), S2CUrl::new);
        assertEquals(KEY, u.key);
        assertEquals("https://x.com/a?b=c", u.url);
        assertEquals(7, u.epoch);

        S2CInput in = roundTrip(
            new S2CInput(KEY, 9, ScreenInput.pointer(ScreenInput.Kind.DOWN, 0.25f, 0.75f, 1, 2, ScreenInput.MOD_SHIFT)),
            S2CInput::new);
        assertEquals(ScreenInput.Kind.DOWN, in.input.kind);
        assertEquals(0.25f, in.input.u);
        assertEquals(0.75f, in.input.v);
        assertEquals(1, in.input.button);
        assertEquals(2, in.input.clickCount);
        assertEquals(ScreenInput.MOD_SHIFT, in.input.modifiers);

        S2CMediaClock c = roundTrip(new S2CMediaClock(KEY, 3, 61.5, 1.05f, true, 99L), S2CMediaClock::new);
        assertEquals(61.5, c.mediaTime);
        assertEquals(1.05f, c.rate);
        assertTrue(c.paused);
        assertEquals(99L, c.serverTimeMs);

        S2CClockPong pong = roundTrip(new S2CClockPong(5L, 6L, 7L), S2CClockPong::new);
        assertEquals(5L, pong.nonce);
        assertEquals(6L, pong.clientSendMs);
        assertEquals(7L, pong.serverTimeMs);

        S2CNotice n = roundTrip(new S2CNotice(ReasonCode.URL_BLOCKED, "example.com"), S2CNotice::new);
        assertEquals(ReasonCode.URL_BLOCKED, n.code);
        assertArrayEquals(new String[] { "example.com" }, n.args);
    }

    @Test
    void clientToServer() {
        C2SSetUrl set = roundTrip(new C2SSetUrl(KEY, 4, "https://youtube.com/"), C2SSetUrl::new);
        assertEquals("https://youtube.com/", set.url);
        assertEquals(4, set.epoch);

        assertEquals("back", roundTrip(new C2SNav(KEY, 1, "back"), C2SNav::new).action);

        C2SPointer w = roundTrip(new C2SPointer(KEY, ScreenInput.wheel(0.5f, 0.5f, 0f, -120f, 0)), C2SPointer::new);
        assertEquals(ScreenInput.Kind.WHEEL, w.input.kind);
        assertEquals(-120f, w.input.wheelY);

        C2SKey k = roundTrip(new C2SKey(KEY, ScreenInput.key(true, "Enter", ScreenInput.MOD_CTRL)), C2SKey::new);
        assertEquals(ScreenInput.Kind.KEY_DOWN, k.input.kind);
        assertEquals("Enter", k.input.key);
        C2SKey ch = roundTrip(new C2SKey(KEY, ScreenInput.chars("ação $%&")), C2SKey::new);
        assertEquals("ação $%&", ch.input.text);

        MediaSample ms = new MediaSample(2, 12.5, 1f, false, true, true, 3.25, 1000L);
        C2SMediaSample m = roundTrip(new C2SMediaSample(KEY, ms), C2SMediaSample::new);
        assertEquals(12.5, m.sample.mediaTime);
        assertTrue(m.sample.adShowing);
        assertTrue(m.sample.live);
        assertEquals(3.25, m.sample.behindLiveEdge);
        assertEquals(1000L, m.sample.clientServerTimeMs);

        assertTrue(roundTrip(new C2SControl(KEY, true), C2SControl::new).take);

        RightsChange rc = new RightsChange();
        rc.addFriends.add("Alice");
        rc.removeFriends.add("Bob");
        rc.otherRights = Rights.CLICK_TYPE;
        rc.rotation = 180;
        RightsChange r = roundTrip(new C2SRights(KEY, rc), C2SRights::new).change;
        assertEquals(Arrays.asList("Alice"), r.addFriends);
        assertEquals(Arrays.asList("Bob"), r.removeFriends);
        assertEquals(Rights.CLICK_TYPE, r.otherRights);
        assertEquals(RightsChange.UNCHANGED, r.friendRights);
        assertEquals(180, r.rotation);

        C2SClockPing ping = roundTrip(new C2SClockPing(11L, 22L), C2SClockPing::new);
        assertEquals(11L, ping.nonce);
        assertEquals(22L, ping.clientSendMs);

        Map<String, String> info = new LinkedHashMap<>();
        info.put("os", "Windows 11");
        info.put("java", "1.8.0_51");
        assertEquals(info, roundTrip(new C2SPreflight(info), C2SPreflight::new).info);
    }

    // ---- caps and malformed input ----

    @Test
    void writersApplyTheSameCaps() {
        StringBuilder huge = new StringBuilder("https://a.com/");
        while (huge.length() < 5000) huge.append('x');
        C2SSetUrl m = roundTrip(new C2SSetUrl(KEY, 0, huge.toString()), C2SSetUrl::new);
        assertEquals(NetCodec.MAX_URL, m.url.length());
    }

    @Test
    void truncatedPacketsAreMalformedNotExceptions() {
        ByteBuf b = Unpooled.buffer();
        new C2SSetUrl(KEY, 1, "https://a.com/").toBytes(b);
        ByteBuf cut = b.copy(0, b.readableBytes() - 3);
        assertNotNull(decode(cut, C2SSetUrl::new).malformed);
        assertNotNull(decode(Unpooled.buffer(), C2SClockPing::new).malformed);
    }

    @Test
    void trailingBytesAreMalformed() {
        ByteBuf b = Unpooled.buffer();
        new C2SControl(KEY, true).toBytes(b);
        b.writeByte(0);
        assertNotNull(decode(b, C2SControl::new).malformed);
    }

    @Test
    void oversizedStringLengthIsRefusedBeforeAllocating() {
        ByteBuf b = Unpooled.buffer();
        NetCodec.writeKey(b, KEY);
        b.writeInt(0);
        NetCodec.writeVarInt(b, Integer.MAX_VALUE);
        assertNotNull(decode(b, C2SSetUrl::new).malformed);
        ByteBuf longName = Unpooled.buffer();
        byte[] name = new byte[40];
        Arrays.fill(name, (byte) 'a');
        NetCodec.writeVarInt(longName, name.length);
        longName.writeBytes(name);
        assertTrue(threw(() -> NetCodec.readString(longName, 32)));
    }

    @Test
    void badSideKindAndNumbers() {
        ByteBuf side = Unpooled.buffer();
        side.writeInt(0);
        side.writeInt(0);
        side.writeInt(0);
        side.writeInt(0);
        side.writeByte(6);
        side.writeBoolean(true);
        assertNotNull(decode(side, C2SControl::new).malformed);

        ByteBuf nan = Unpooled.buffer();
        new C2SPointer(KEY, ScreenInput.pointer(ScreenInput.Kind.MOVE, 0.5f, 0.5f, 0, 0, 0)).toBytes(nan);
        nan.setFloat(17 + 1, Float.NaN); // key (17 bytes), kind (1), then u
        assertNotNull(decode(nan, C2SPointer::new).malformed);

        // A key event inside a pointer packet and the other way round.
        ByteBuf wrong = Unpooled.buffer();
        new C2SKey(KEY, ScreenInput.key(true, "a", 0)).toBytes(wrong);
        assertNotNull(decode(wrong, C2SPointer::new).malformed);
        ByteBuf wrong2 = Unpooled.buffer();
        new C2SPointer(KEY, ScreenInput.pointer(ScreenInput.Kind.UP, 0, 0, 0, 1, 0)).toBytes(wrong2);
        assertNotNull(decode(wrong2, C2SKey::new).malformed);
    }

    @Test
    void unknownNavActionAndRightsOutOfRange() {
        ByteBuf nav = Unpooled.buffer();
        new C2SNav(KEY, 0, "eval").toBytes(nav);
        assertNotNull(decode(nav, C2SNav::new).malformed);

        RightsChange rc = new RightsChange();
        rc.otherRights = 0xFF;
        ByteBuf r = Unpooled.buffer();
        new C2SRights(KEY, rc).toBytes(r);
        assertNotNull(decode(r, C2SRights::new).malformed);

        RightsChange rot = new RightsChange();
        rot.rotation = 45;
        ByteBuf r2 = Unpooled.buffer();
        new C2SRights(KEY, rot).toBytes(r2);
        assertNotNull(decode(r2, C2SRights::new).malformed);
    }

    @Test
    void listCaps() {
        ByteBuf friends = Unpooled.buffer();
        NetCodec.writeKey(friends, KEY);
        friends.writeByte(NetCodec.MAX_FRIEND_CHANGES + 1);
        assertNotNull(decode(friends, C2SRights::new).malformed);

        ByteBuf pre = Unpooled.buffer();
        pre.writeByte(NetCodec.MAX_PREFLIGHT_ENTRIES + 1);
        assertNotNull(decode(pre, C2SPreflight::new).malformed);

        ByteBuf policy = Unpooled.buffer();
        policy.writeShort(1);
        policy.writeShort(0);
        policy.writeBoolean(false);
        policy.writeByte(0);
        NetCodec.writeVarInt(policy, SitePolicy.MAX_ENTRIES + 1);
        assertNotNull(decode(policy, S2CHello::new).malformed);
    }

    @Test
    void textIsSanitisedOnRead() {
        ByteBuf b = Unpooled.buffer();
        NetCodec.writeKey(b, KEY);
        b.writeInt(0);
        byte[] url = "https://a.com/​ x\n".getBytes(StandardCharsets.UTF_8);
        NetCodec.writeVarInt(b, url.length);
        b.writeBytes(url);
        assertEquals("https://a.com/x", decode(b, C2SSetUrl::new).url);

        ByteBuf n = Unpooled.buffer();
        NetCodec.writeString(n, "FUTURE_CODE", 48);
        n.writeByte(1);
        NetCodec.writeString(n, "§kx\ny", 256);
        S2CNotice notice = decode(n, S2CNotice::new);
        assertNull(notice.malformed);
        assertNull(notice.code, "unknown codes decode as null so old clients ignore them");
        assertEquals("xy", notice.args[0]);
    }

    @Test
    void varIntRoundTrip() {
        for (int v : new int[] { 0, 1, 127, 128, 300, 1 << 21, Integer.MAX_VALUE, -1 }) {
            ByteBuf b = Unpooled.buffer();
            NetCodec.writeVarInt(b, v);
            assertEquals(v, NetCodec.readVarInt(b));
        }
        ByteBuf bad = Unpooled.buffer();
        for (int i = 0; i < 6; i++) bad.writeByte(0xFF);
        assertTrue(threw(() -> NetCodec.readVarInt(bad)));
    }

    @Test
    void protocolIdsMatchTheSpec() {
        assertEquals(0, WebNetwork.S2C_HELLO);
        assertEquals(6, WebNetwork.S2C_NOTICE);
        assertEquals(16, WebNetwork.C2S_SET_URL);
        assertEquals(24, WebNetwork.C2S_PREFLIGHT);
        assertFalse(WebNetwork.CHANNEL_NAME.length() > 20, "FML channel names are at most 20 characters");
    }

    private static boolean threw(Runnable r) {
        try {
            r.run();
            return false;
        } catch (CodecException e) {
            return true;
        }
    }
}
