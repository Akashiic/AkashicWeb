package com.akashiic.web.common.net;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
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
import com.akashiic.web.protocol.Protocol;

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

    private static final UUID PLAYER = UUID.fromString("12345678-0000-0000-0000-00000000abcd");

    @Test
    void hello() {
        SitePolicy p = SitePolicy
            .of(SitePolicy.Mode.BLOCKLIST, Arrays.asList("a.com"), Arrays.asList("b.com", "c.org"));
        S2CHello h = roundTrip(new S2CHello(1, 3, true, p, 123456789L, PLAYER), S2CHello::new);
        assertEquals(1, h.protoMajor);
        assertEquals(3, h.protoMinor);
        assertTrue(h.killSwitch);
        assertEquals(p, h.policy);
        assertEquals(123456789L, h.serverTimeMs);
        assertEquals(PLAYER, h.playerUuid, "D-016: the player's server-side UUID");
        assertFalse(h.foreignMajor);
    }

    /** Review finding: a hello with an appended field was dropped as malformed, so mixed versions showed nothing. */
    @Test
    void helloOfTheSameMajorToleratesAppendedFields() {
        ByteBuf b = Unpooled.buffer();
        new S2CHello(Protocol.MAJOR, 9, false, SitePolicy.blocklist(Arrays.asList("x.com")), 5L, PLAYER).toBytes(b);
        b.writeLong(42L); // a field a newer server appended
        b.writeBoolean(true);
        S2CHello h = decode(b, S2CHello::new);
        assertNull(h.malformed, h.malformed);
        assertEquals(PLAYER, h.playerUuid);
        assertEquals(9, h.protoMinor);
        assertFalse(b.isReadable(), "the unknown bytes are consumed");
    }

    /** Review finding: a hello of another major must reach the client as a mismatch (placeholder), not be dropped. */
    @Test
    void helloOfAnotherMajorIsReportedNotDropped() {
        ByteBuf b = Unpooled.buffer();
        b.writeShort(Protocol.MAJOR + 1);
        b.writeShort(0);
        b.writeByte(26); // whatever the next major puts here: not a valid policy mode for this client
        b.writeInt(0xDEADBEEF);
        S2CHello h = decode(b, S2CHello::new);
        assertNull(h.malformed, h.malformed);
        assertTrue(h.foreignMajor);
        assertEquals(Protocol.MAJOR + 1, h.protoMajor);
        assertNull(h.policy);
        assertNull(h.playerUuid);
    }

    /** A server of the same major built before D-016 sends no playerUuid; the hello still decodes. */
    @Test
    void helloWithoutThePlayerUuidStillDecodes() {
        ByteBuf b = Unpooled.buffer();
        b.writeShort(Protocol.MAJOR);
        b.writeShort(0);
        b.writeBoolean(false);
        NetCodec.writePolicy(b, SitePolicy.blocklist(Arrays.<String>asList()));
        b.writeLong(7L);
        S2CHello h = decode(b, S2CHello::new);
        assertNull(h.malformed, h.malformed);
        assertNull(h.playerUuid);
        assertEquals(7L, h.serverTimeMs);
    }

    /** Client-to-server packets stay strict: the server never guesses. */
    @Test
    void clientToServerPacketsStayStrictAboutTrailingBytes() {
        ByteBuf b = Unpooled.buffer();
        new C2SClockPing(1L, 2L).toBytes(b);
        b.writeByte(0);
        assertNotNull(decode(b, C2SClockPing::new).malformed);
        ByteBuf s2c = Unpooled.buffer();
        new S2CClockPong(1L, 2L, 3L).toBytes(s2c);
        s2c.writeByte(0);
        assertNull(decode(s2c, S2CClockPong::new).malformed);
    }

    /** D-016 retired ids 3, 18 and 19: an old peer's packet is skipped and dropped, never a decode failure. */
    @Test
    void retiredIdsAreTombstones() {
        assertEquals(3, WebNetwork.RETIRED_S2C_INPUT);
        assertEquals(18, WebNetwork.RETIRED_C2S_POINTER);
        assertEquals(19, WebNetwork.RETIRED_C2S_KEY);
        ByteBuf old = Unpooled.buffer();
        NetCodec.writeKey(old, KEY);
        old.writeByte(1);
        old.writeFloat(0.5f);
        RetiredMessage.Pointer18 m = new RetiredMessage.Pointer18();
        m.fromBytes(old);
        assertFalse(old.isReadable(), "payload skipped unread");
        long before = RetiredMessage.dropped();
        assertNull(new RetiredMessage.Pointer18.Handler().onMessage(m, null));
        assertNull(new RetiredMessage.Key19.Handler().onMessage(new RetiredMessage.Key19(), null));
        assertNull(new RetiredMessage.Input3.Handler().onMessage(new RetiredMessage.Input3(), null));
        assertEquals(before + 3, RetiredMessage.dropped());
        ByteBuf out = Unpooled.buffer();
        new RetiredMessage.Input3().toBytes(out);
        assertFalse(out.isReadable(), "nothing is ever written for a retired id");
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
        assertEquals("", r.controllerName, "no name was set");
        s.controllerName = "Friend\u00a7k";
        assertEquals("Friend", roundTrip(new S2CScreenState(s), S2CScreenState::new).state.controllerName);
        s.controller = null;
        assertEquals("", roundTrip(new S2CScreenState(s), S2CScreenState::new).state.controllerName, "no controller");
    }

    /** A state from a server built before D-016 ends at the mode: it still decodes, without a controller name. */
    @Test
    void screenStateWithoutTheControllerNameStillDecodes() {
        ScreenState s = TestScreens.state(BlockSide.NORTH, 1, 2, 3, 2, 2, 0);
        s.controller = TestScreens.FRIEND;
        s.controllerName = "Friend";
        ByteBuf b = Unpooled.buffer();
        new S2CScreenState(s).toBytes(b);
        ByteBuf old = b.copy(0, b.readableBytes() - 7); // varint length 6 + "Friend"
        S2CScreenState r = decode(old, S2CScreenState::new);
        assertNull(r.malformed, r.malformed);
        assertEquals(TestScreens.FRIEND, r.state.controller);
        assertEquals("", r.state.controllerName);
    }

    @Test
    void urlInputClockPongNotice() {
        S2CUrl u = roundTrip(new S2CUrl(KEY, "https://x.com/a?b=c", 7), S2CUrl::new);
        assertEquals(KEY, u.key);
        assertEquals("https://x.com/a?b=c", u.url);
        assertEquals(7, u.epoch);

        S2CMediaClock c = roundTrip(new S2CMediaClock(KEY, 3, 61.5, 1.05f, true, 99L, true, 2500), S2CMediaClock::new);
        assertEquals(61.5, c.mediaTime);
        assertEquals(1.05f, c.rate);
        assertTrue(c.paused);
        assertEquals(99L, c.serverTimeMs);
        assertTrue(c.live);
        assertEquals(2500, c.liveLagMs);
        assertEquals(
            S2CMediaClock.MAX_LIVE_LAG_MS,
            roundTrip(
                new S2CMediaClock(KEY, 3, 1, 1f, false, 1L, true, Integer.MAX_VALUE),
                S2CMediaClock::new).liveLagMs,
            "the writer clamps");
        ByteBuf badLag = Unpooled.buffer();
        new S2CMediaClock(KEY, 3, 1, 1f, false, 1L, true, 0).toBytes(badLag);
        badLag.setInt(badLag.writerIndex() - 4, -1);
        assertNotNull(decode(badLag, S2CMediaClock::new).malformed);
        ByteBuf preD016 = Unpooled.buffer();
        new S2CMediaClock(KEY, 3, 1, 1f, false, 1L, true, 9).toBytes(preD016);
        S2CMediaClock old = decode(preD016.copy(0, preD016.readableBytes() - 5), S2CMediaClock::new);
        assertNull(old.malformed, "a clock without the D-016 fields still decodes");
        assertFalse(old.live);
        assertEquals(0, old.liveLagMs);

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

    /**
     * Review finding: with the byte cap removed this file still passed, because the character check after decoding
     * refused the same strings. Refused on the length alone means the payload was neither copied nor decoded.
     */
    @Test
    void theByteCapIsCheckedBeforeThePayloadIsRead() {
        ByteBuf b = Unpooled.buffer();
        byte[] payload = new byte[32 * 3 + 1];
        Arrays.fill(payload, (byte) 'a');
        NetCodec.writeVarInt(b, payload.length);
        int afterLength = b.writerIndex();
        b.writeBytes(payload); // all of it readable: only the cap can refuse it before reading
        CodecException e = assertThrows(CodecException.class, () -> NetCodec.readString(b, 32));
        assertEquals(afterLength, b.readerIndex(), "nothing read past the length");
        assertEquals("string of 97 bytes", e.getMessage());
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
        new C2SMediaSample(KEY, new MediaSample(1, 2.0, 1f, false, false, false, 0, 5L)).toBytes(nan);
        nan.setDouble(17 + 4, Double.NaN); // key (17 bytes), epoch (4), then mediaTime
        assertNotNull(decode(nan, C2SMediaSample::new).malformed);
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

    /** A complete C2SRights adding one name, written by hand so the name can break the writer's cap. */
    private static ByteBuf rightsAdding(String name) {
        ByteBuf b = Unpooled.buffer();
        NetCodec.writeKey(b, KEY);
        b.writeByte(1);
        NetCodec.writeString(b, name, name.length());
        b.writeByte(0); // no removals
        for (int i = 0; i < 6; i++) b.writeInt(RightsChange.UNCHANGED); // rights, resolution, rotation, lock
        return b;
    }

    /** Review finding (high): only real player names, and few per packet, ever reach the server's name lookup. */
    @Test
    void friendNamesMustBePlayerNames() {
        assertNotNull(decode(rightsAdding("x123456789012345y"), C2SRights::new).malformed, "17 characters");
        assertNull(decode(rightsAdding("x123456789012345"), C2SRights::new).malformed, "16 characters");
        for (String bad : new String[] { "", "Alice!", "a b", "ação", "§kAlice" }) {
            RightsChange c = new RightsChange();
            c.addFriends.add(bad);
            ByteBuf b = Unpooled.buffer();
            new C2SRights(KEY, c).toBytes(b);
            assertNotNull(decode(b, C2SRights::new).malformed, "'" + bad + "'");
        }
        RightsChange ok = new RightsChange();
        ok.addFriends.add("Notch_1234567890");
        assertEquals(
            Arrays.asList("Notch_1234567890"),
            roundTrip(new C2SRights(KEY, ok), C2SRights::new).change.addFriends);
        assertEquals(4, NetCodec.MAX_FRIEND_CHANGES);
        ByteBuf five = Unpooled.buffer();
        NetCodec.writeKey(five, KEY);
        five.writeByte(5);
        for (int i = 0; i < 5; i++) NetCodec.writeString(five, "p" + i, 16);
        assertNotNull(decode(five, C2SRights::new).malformed);
        assertTrue(NetCodec.isPlayerName("_"));
        assertFalse(NetCodec.isPlayerName(null));
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
        assertEquals(4, WebNetwork.S2C_MEDIA_CLOCK);
        assertEquals(6, WebNetwork.S2C_NOTICE);
        assertEquals(20, WebNetwork.C2S_MEDIA_SAMPLE);
        assertEquals(21, WebNetwork.C2S_CONTROL);
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
