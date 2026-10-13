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
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Arrays;
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
        final List<String> rejected = new CopyOnWriteArrayList<>();
        /** Thrown from framesFor / onState when set, to simulate an Error escaping the frame or listener path. */
        volatile Error frameError;
        volatile Error stateError;

        @Override
        public FramePipeline framesFor(int screenId) {
            Error e = frameError;
            if (e != null) throw e;
            return pipelines.get(screenId);
        }

        @Override
        public void onPcm(int screenId, PcmChunk chunk) {
            pcm.add(chunk);
        }

        @Override
        public void onState(int screenId, String json) {
            Error e = stateError;
            if (e != null) throw e;
            got.add("state " + screenId + " " + json);
        }

        @Override
        public void onFrameRejected(int screenId, int width, int height) {
            rejected.add(screenId + " " + width + "x" + height);
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

    /**
     * Review finding (medium): a page too large to hold used to close the whole connection, taking every screen down
     * and feeding the crash-loop guard. Now its frames are skipped (their length is valid), only that screen is
     * reported, once per page size, and the stream stays in step for everything after it.
     */
    @Test
    void oversizedPagesAreSkippedAndFailOnlyTheirScreen() throws Exception {
        FramePipeline one = new FramePipeline();
        FramePipeline two = new FramePipeline();
        inbound.pipelines.put(1, one);
        inbound.pipelines.put(2, two);
        HostConnection c = open();
        // A 1920x1080 screen painted at 150% display scale: 2880x1620 is over the pixel cap, the dirty rect is small.
        HostProtocol.write(hostOut, HostProtocol.FRAME, 1, 0, frame(2880, 1620, 0, 0, 10, 10));
        HostProtocol.write(hostOut, HostProtocol.FRAME, 1, 1, frame(2880, 1620, 5, 5, 10, 10)); // same size: no report
        HostProtocol.write(hostOut, HostProtocol.FRAME, 1, 2, frame(65535, 64, 0, 0, 1, 1)); // wider than any texture
        HostProtocol.write(hostOut, HostProtocol.FRAME, 1, 3, frame(4097, 16, 0, 0, 1, 1)); // just over 4096
        HostProtocol.write(hostOut, HostProtocol.FRAME, 2, 0, FakeHostMain.frame(2, 4, 2)); // another screen
        HostProtocol.write(hostOut, HostProtocol.FRAME, 1, 4, FakeHostMain.frame(1, 3, 3)); // screen 1 recovers
        HostProtocol.writeJson(hostOut, HostProtocol.MEDIA, 1, 5, "{\"t\":2}");
        hostOut.flush();
        await("MEDIA after the frames", 10_000, () -> !inbound.got.isEmpty());
        assertFalse(c.isClosed(), c.closeDetail());
        assertEquals(Arrays.asList("1 2880x1620", "1 65535x64", "1 4097x16"), inbound.rejected);
        assertEquals(4, one.framesRejected());
        assertEquals(1, one.framesIn());
        assertNull(one.rejectedSize(), "a frame of a valid size ends the rejection");
        FrameSlot s1 = one.ring()
            .takeLatest();
        assertEquals(3, s1.width);
        assertEquals((byte) 1, s1.bgra.get(0));
        FrameSlot s2 = two.ring()
            .takeLatest();
        assertEquals(4, s2.width);
        assertEquals(7, c.messagesIn(), "all six FRAMEs and the MEDIA were read in step");
    }

    /**
     * Review finding (high): an Error escaping the listener path (a NoClassDefFoundError in game code) used to kill
     * the reader without closing the connection, so the supervisor waited forever behind a READY status.
     */
    @Test
    void anErrorFromAListenerClosesTheConnection() throws Exception {
        inbound.stateError = new NoClassDefFoundError("simulated listener linkage error");
        HostConnection c = open();
        HostProtocol.writeJson(hostOut, HostProtocol.STATE, 1, 0, "{\"url\":\"x\"}");
        hostOut.flush();
        assertTrue(c.awaitClosed(10_000), "the connection must close when its reader dies");
        assertEquals(HostFailure.INTERNAL_ERROR, c.closeReason());
        assertTrue(
            c.closeDetail()
                .contains("NoClassDefFoundError"),
            c.closeDetail());
        assertFalse(c.sendPing(), "nothing is queued on a dead connection");
    }

    /** Review finding (high), the frame path: the direct-memory OutOfMemoryError FrameRing3 anticipates. */
    @Test
    void anOutOfMemoryErrorInTheFramePathClosesTheConnection() throws Exception {
        inbound.frameError = new OutOfMemoryError("Direct buffer memory (simulated)");
        HostConnection c = open();
        HostProtocol.write(hostOut, HostProtocol.FRAME, 1, 0, FakeHostMain.frame(1, 4, 2));
        hostOut.flush();
        assertTrue(c.awaitClosed(10_000));
        assertEquals(HostFailure.INTERNAL_ERROR, c.closeReason());
        assertTrue(
            c.closeDetail()
                .contains("OutOfMemoryError"),
            c.closeDetail());
    }

    /** Review finding (high), the writer side: an Error while writing ends the connection instead of the thread. */
    @Test
    void anErrorOnTheWriterThreadClosesTheConnection() throws Exception {
        Socket broken = new Socket() {

            @Override
            public OutputStream getOutputStream() {
                return new OutputStream() {

                    @Override
                    public void write(int b) {
                        throw new NoClassDefFoundError("simulated writer linkage error");
                    }

                    @Override
                    public void write(byte[] b, int off, int len) {
                        throw new NoClassDefFoundError("simulated writer linkage error");
                    }
                };
            }
        };
        // The reader uses the real loopback stream (idle); the writer gets the broken one.
        HostConnection c = new HostConnection(
            broken,
            new DataInputStream(new BufferedInputStream(gameSide.getInputStream())),
            inbound);
        c.start();
        assertTrue(c.sendPing());
        assertTrue(c.awaitClosed(10_000), "the connection must close when its writer dies");
        assertEquals(HostFailure.INTERNAL_ERROR, c.closeReason());
        assertTrue(
            c.closeDetail()
                .contains("writer"),
            c.closeDetail());
    }

    /**
     * Review finding (low): a PCM header announcing more than any valid chunk (u16 frames x 8 channels x 2 bytes) is
     * refused before anything is allocated. The old reader allocated the announced 16 MiB and then waited for it.
     */
    @Test
    void pcmLongerThanAnyValidChunkIsRefusedBeforeAllocating() throws Exception {
        assertEquals(1 << 20, PcmChunk.MAX_PAYLOAD);
        HostConnection c = open();
        short[] most = new short[0xFFFF * PcmChunk.MAX_CHANNELS];
        HostProtocol.write(hostOut, HostProtocol.PCM, 5, 0, PcmChunkTest.encode(0, 48000, 8, 0xFFFF, most));
        hostOut.writeInt(HostProtocol.MAGIC); // header only: the payload never comes
        hostOut.writeShort(HostProtocol.PCM);
        hostOut.writeShort(0);
        hostOut.writeInt(5);
        hostOut.writeInt(1);
        hostOut.writeInt(HostProtocol.MAX_PAYLOAD);
        hostOut.flush();
        assertTrue(c.awaitClosed(10_000), "refused at the header, not after waiting for 16 MiB");
        assertEquals(HostFailure.PROTOCOL_ERROR, c.closeReason());
        assertTrue(
            c.closeDetail()
                .contains("PCM payload of " + HostProtocol.MAX_PAYLOAD),
            c.closeDetail());
        assertEquals(1, inbound.pcm.size(), "the largest valid chunk still passes");
        assertEquals(0xFFFF, inbound.pcm.get(0).frames);
    }

    /** FRAME payload: page {@code width x height}, dirty rect (x, y, w, h), w*h*4 bytes of 0x11. */
    private static byte[] frame(int width, int height, int x, int y, int w, int h) {
        byte[] p = new byte[HostProtocol.FrameHeader.BYTES + w * h * 4];
        int[] head = { width, height, x, y, w, h };
        for (int i = 0; i < head.length; i++) {
            p[i * 2] = (byte) (head[i] >> 8);
            p[i * 2 + 1] = (byte) head[i];
        }
        Arrays.fill(p, HostProtocol.FrameHeader.BYTES, p.length, (byte) 0x11);
        return p;
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
