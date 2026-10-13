package com.akashiic.web.client.host;

import static com.akashiic.web.client.host.FakeHostFixture.await;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.akashiic.web.common.SitePolicy;

/** HostConnection over a real loopback socket pair; the test plays the host side. */
@Timeout(30)
class HostConnectionTest {

    private Socket gameSide;
    private Socket hostSide;
    private DataOutputStream hostOut;
    private DataInputStream hostIn;
    private final Recorder inbound = new Recorder();

    /** Inbound that records everything; screens with a pipeline are the "known" ones. */
    static final class Recorder implements HostConnection.Inbound {

        final Map<Integer, FramePipeline> pipelines = new ConcurrentHashMap<>();
        final List<String> got = new CopyOnWriteArrayList<>();
        final List<PcmChunk> pcm = new CopyOnWriteArrayList<>();

        @Override
        public FramePipeline framesFor(int screenId) {
            return pipelines.get(screenId);
        }

        @Override
        public void onPcm(int screenId, PcmChunk chunk) {
            pcm.add(chunk);
        }

        @Override
        public void onState(int screenId, String json) {
            got.add("state " + screenId + " " + json);
        }

        @Override
        public void onMedia(int screenId, String json) {
            got.add("media " + screenId + " " + json);
        }

        @Override
        public void onEvent(int screenId, String json) {
            got.add("event " + screenId + " " + json);
        }
    }

    @BeforeEach
    void connect() throws Exception {
        InetAddress lo = InetAddress.getByAddress(new byte[] { 127, 0, 0, 1 });
        try (ServerSocket ss = new ServerSocket(0, 1, lo)) {
            hostSide = new Socket(lo, ss.getLocalPort());
            gameSide = ss.accept();
        }
        hostOut = new DataOutputStream(hostSide.getOutputStream());
        hostIn = new DataInputStream(new BufferedInputStream(hostSide.getInputStream()));
    }

    @AfterEach
    void close() throws Exception {
        hostSide.close();
        gameSide.close();
    }

    private HostConnection open() throws Exception {
        HostConnection c = new HostConnection(
            gameSide,
            new DataInputStream(new BufferedInputStream(gameSide.getInputStream())),
            inbound);
        c.start();
        return c;
    }

    @Test
    void framesStreamIntoTheScreenRingAndOthersAreSkippedInStep() throws Exception {
        FramePipeline p = new FramePipeline();
        inbound.pipelines.put(3, p);
        HostConnection c = open();
        HostProtocol.write(hostOut, HostProtocol.FRAME, 3, 0, FakeHostMain.frame(3, 4, 2));
        HostProtocol.write(hostOut, HostProtocol.FRAME, 77, 0, FakeHostMain.frame(77, 300, 200)); // unknown: skipped
        byte[] crop = new byte[HostProtocol.FrameHeader.BYTES + 4];
        int[] head = { 4, 2, 2, 1, 1, 1 };
        for (int i = 0; i < head.length; i++) crop[i * 2 + 1] = (byte) head[i];
        crop[12] = 9;
        crop[13] = 8;
        crop[14] = 7;
        crop[15] = 6;
        HostProtocol.write(hostOut, HostProtocol.FRAME, 3, 1, crop);
        HostProtocol.writeJson(hostOut, HostProtocol.MEDIA, 3, 2, "{\"t\":1}");
        hostOut.flush();
        await("MEDIA after the frames", 10_000, () -> !inbound.got.isEmpty());
        assertEquals("media 3 {\"t\":1}", inbound.got.get(0));
        FrameSlot s = p.ring()
            .takeLatest();
        assertNotNull(s);
        assertEquals(4, s.width);
        int at = (1 * 4 + 2) * 4;
        assertArrayEquals(
            new byte[] { 9, 8, 7, 6 },
            new byte[] { s.bgra.get(at), s.bgra.get(at + 1), s.bgra.get(at + 2), s.bgra.get(at + 3) });
        assertEquals((byte) 3, s.bgra.get(0));
        assertEquals((byte) 1, s.bgra.get(5), "pixel (1,0) G = x");
        assertEquals(2, p.framesIn());
        assertFalse(c.isClosed());
    }

    @Test
    void pcmStateEventUnknownAndOversizedMessages() throws Exception {
        HostConnection c = open();
        HostProtocol.write(hostOut, HostProtocol.PCM, 5, 0, FakeHostMain.pcm());
        HostProtocol.write(hostOut, 200, 5, 1, new byte[] { 1, 2, 3 }); // future type: ignored
        HostProtocol.writeJson(hostOut, HostProtocol.HELLO, 0, 2, "{\"token\":\"again\"}"); // repeated HELLO: ignored
        char[] big = new char[HostConnection.MAX_TEXT_PAYLOAD + 10];
        java.util.Arrays.fill(big, 'x');
        HostProtocol.writeJson(hostOut, HostProtocol.STATE, 5, 3, "\"" + new String(big) + "\""); // skipped
        HostProtocol.writeJson(hostOut, HostProtocol.STATE, 5, 4, "{\"title\":\"ação\"}");
        HostProtocol.writeJson(hostOut, HostProtocol.EVENT, 5, 5, "{\"kind\":\"renderer-gone\"}");
        hostOut.flush();
        await("two messages", 10_000, () -> inbound.got.size() == 2);
        assertEquals("state 5 {\"title\":\"ação\"}", inbound.got.get(0));
        assertEquals("event 5 {\"kind\":\"renderer-gone\"}", inbound.got.get(1));
        assertEquals(1, inbound.pcm.size());
        assertArrayEquals(new short[] { 1, -1, 300, -300 }, inbound.pcm.get(0).interleaved);
        assertFalse(c.isClosed());
        assertEquals(6, c.messagesIn());
    }

    @Test
    void outgoingMessagesCarryPerScreenSequenceNumbers() throws Exception {
        HostConnection c = open();
        c.sendWelcome(null, "pt-BR", null);
        c.sendCreate(
            5,
            new CreateSpec(64, 32, 30, "https://a.example/", CreateSpec.Session.SHARED, CreateSpec.Audio.OS, 1f));
        c.sendNavigate(5, "https://b.example/");
        c.sendCreate(
            6,
            new CreateSpec(64, 32, 30, "https://c.example/", CreateSpec.Session.SHARED, CreateSpec.Audio.OS, 1f));
        c.sendInput(5, new InputEvent(InputEvent.Type.CHAR, 0, 0, null, 0, 0, 0, null, 0, "é"));
        c.sendPolicy(SitePolicy.blocklist(new ArrayList<String>()));
        c.sendDestroy(5);
        c.sendCreate(
            5,
            new CreateSpec(64, 32, 30, "https://d.example/", CreateSpec.Session.SHARED, CreateSpec.Audio.OS, 1f));
        c.sendSync(6, new SyncCommand(Double.NaN, 1f, false, false)); // nothing sensible: not sent
        c.sendQuit();
        String[] expected = { "32 0 0", "33 5 0", "35 5 1", "33 6 0", "36 5 2", "37 0 1", "34 5 3", "33 5 0",
            "43 0 2" };
        for (String e : expected) {
            HostProtocol.Message m = HostProtocol.read(hostIn);
            assertEquals(e, m.type + " " + m.screenId + " " + m.seq, m.text());
        }
        assertEquals(expected.length, c.messagesOut());
    }

    @Test
    void pingPongMeasuresTheRoundTrip() throws Exception {
        HostConnection c = open();
        assertEquals(-1, c.lastRttMillis());
        c.sendPing();
        HostProtocol.Message ping = HostProtocol.read(hostIn);
        assertEquals(HostProtocol.PING, ping.type);
        long nonce = JsonParser.integer(JsonParser.parseObject(ping.text()), "nonce", -1);
        Thread.sleep(5);
        assertTrue(c.pongOverdue(0), "unanswered ping");
        HostProtocol.writeJson(hostOut, HostProtocol.PONG, 0, 0, "{\"nonce\":" + (nonce + 1) + ",\"hostTimeMs\":5}");
        HostProtocol.writeJson(hostOut, HostProtocol.PONG, 0, 1, "not json");
        HostProtocol.writeJson(hostOut, HostProtocol.PONG, 0, 2, "{\"nonce\":" + nonce + ",\"hostTimeMs\":5}");
        hostOut.flush();
        await("rtt", 10_000, () -> c.lastRttMillis() >= 0);
        assertFalse(c.pongOverdue(0));
    }

    @Test
    void byeThenEofEndsAsConnectionLostWithTheReason() throws Exception {
        HostConnection c = open();
        HostProtocol.writeJson(hostOut, HostProtocol.BYE, 0, 0, "{\"reason\":\"parent-gone\"}");
        hostOut.flush();
        hostSide.shutdownOutput();
        assertTrue(c.awaitClosed(10_000));
        assertEquals(HostFailure.CONNECTION_LOST, c.closeReason());
        assertTrue(
            c.closeDetail()
                .contains("BYE parent-gone"),
            c.closeDetail());
        assertFalse(c.sendNavigate(1, "https://x.example/"), "sends after close are refused");
    }

    @Test
    void brokenFramingIsAProtocolError() throws Exception {
        HostConnection c = open();
        hostOut.writeInt(0x12345678);
        hostOut.write(new byte[16]);
        hostOut.flush();
        assertTrue(c.awaitClosed(10_000));
        assertEquals(HostFailure.PROTOCOL_ERROR, c.closeReason());
    }

    @Test
    void oversizedPageIsAProtocolError() throws Exception {
        inbound.pipelines.put(1, new FramePipeline());
        HostConnection c = open();
        byte[] f = new byte[HostProtocol.FrameHeader.BYTES + 4];
        int[] head = { 65535, 65535, 0, 0, 1, 1 };
        for (int i = 0; i < head.length; i++) {
            f[i * 2] = (byte) (head[i] >> 8);
            f[i * 2 + 1] = (byte) head[i];
        }
        HostProtocol.write(hostOut, HostProtocol.FRAME, 1, 0, f);
        hostOut.flush();
        assertTrue(c.awaitClosed(10_000));
        assertEquals(HostFailure.PROTOCOL_ERROR, c.closeReason());
        assertTrue(
            c.closeDetail()
                .contains("too large"),
            c.closeDetail());
    }

    @Test
    void aFullQueueMeansTheHostStalled() throws Exception {
        // Writer never started: nothing drains, as when the host stops reading and the socket buffer is full.
        HostConnection c = new HostConnection(gameSide, new DataInputStream(gameSide.getInputStream()), inbound, 3);
        assertTrue(c.sendPing());
        assertTrue(c.sendPing());
        assertTrue(c.sendPing());
        assertFalse(c.sendPing());
        assertTrue(c.isClosed());
        assertEquals(HostFailure.WRITE_STALLED, c.closeReason());
    }

    @Test
    void flushAndCloseDeliversQuitBeforeEof() throws Exception {
        HostConnection c = open();
        c.sendQuit();
        c.flushAndClose(2000);
        assertEquals(HostProtocol.QUIT, HostProtocol.read(hostIn).type);
        assertNull(HostProtocol.read(hostIn));
        assertEquals(HostFailure.SHUTDOWN, c.closeReason());
        c.close(HostFailure.PROTOCOL_ERROR, "later"); // first reason wins
        assertEquals(HostFailure.SHUTDOWN, c.closeReason());
    }

    @Test
    void threadsAreNamedDaemons() throws Exception {
        open();
        await(
            "threads",
            5000,
            () -> named(HostConnection.READER_THREAD) != null && named(HostConnection.WRITER_THREAD) != null);
        assertTrue(named(HostConnection.READER_THREAD).isDaemon());
        assertTrue(named(HostConnection.WRITER_THREAD).isDaemon());
    }

    private static Thread named(String name) {
        for (Thread t : Thread.getAllStackTraces()
            .keySet())
            if (t.getName()
                .equals(name) && t.isAlive()) return t;
        return null;
    }
}
