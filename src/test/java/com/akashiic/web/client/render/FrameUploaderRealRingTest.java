package com.akashiic.web.client.render;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.akashiic.web.client.host.FramePipeline;
import com.akashiic.web.client.host.HostProtocol;
import com.akashiic.web.client.screen.ClientScreen;
import com.akashiic.web.client.screen.ClientScreens;
import com.akashiic.web.client.screen.FakeHost;
import com.akashiic.web.client.screen.PlayerView;
import com.akashiic.web.client.screen.RecordingServer;
import com.akashiic.web.common.ScreenKey;
import com.akashiic.web.common.ScreenState;
import com.akashiic.web.common.SitePolicy;
import com.akashiic.web.protocol.Protocol;

/**
 * Property test over M2's real frame path (FramePipeline: FrameCanvas and FrameRing3) with a budget so tight that
 * screens skip most frames: after the frames stop, every texture must equal its page. The review's mutation
 * (forgetting the carried damage on a budget skip) passed the fake-ring tests and left stale pixels here.
 */
class FrameUploaderRealRingTest {

    private static final int W = 64;
    private static final int H = 48;

    /** A texture that keeps its pixels, so the test can compare them with the page. */
    static final class PixelTexture implements TextureTarget {

        byte[] pixels = new byte[0];
        int w;
        int h;

        @Override
        public int width() {
            return w;
        }

        @Override
        public int height() {
            return h;
        }

        @Override
        public boolean isAllocated() {
            return w > 0;
        }

        @Override
        public void allocate(int width, int height) {
            w = width;
            h = height;
            pixels = new byte[width * height * 4];
            Arrays.fill(pixels, (byte) 0x5A); // undefined contents: never what a page paints below
        }

        @Override
        public void upload(ByteBuffer canvas, int canvasW, int canvasH, Rect r) {
            for (int y = r.y; y < r.y + r.h; y++) {
                for (int x = r.x * 4; x < (r.x + r.w) * 4; x++) {
                    pixels[(y * w * 4) + x] = canvas.get(y * canvasW * 4 + x);
                }
            }
        }

        @Override
        public void bind() {}

        @Override
        public void delete() {}
    }

    @Test
    void everyChangedPixelReachesTheTextureUnderATightBudget() throws Exception {
        for (long seed = 1; seed <= 20; seed++) run(new Random(seed));
    }

    private void run(Random rnd) throws Exception {
        FakeHost host = new FakeHost();
        long[] now = { 1_000 };
        ClientScreens screens = new ClientScreens(
            host,
            RecordingServer::new,
            () -> now[0],
            Runnable::run,
            ClientScreens.Notifier.NONE,
            4,
            2L);
        screens.onHello(Protocol.MAJOR, Protocol.MINOR, false, SitePolicy.blocklist(Collections.<String>emptyList()));
        // Room for about one full page per render frame across three screens.
        FrameUploader uploader = new FrameUploader(
            screens,
            () -> now[0],
            PixelTexture::new,
            () -> 4096,
            W * H * 4L,
            UploadScope.NONE);
        List<ClientScreen> list = new ArrayList<>();
        List<FramePipeline> pipes = new ArrayList<>();
        List<byte[]> pages = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            ScreenState s = new ScreenState();
            s.key = new ScreenKey(0, i * 4, 64, 0, 3);
            s.widthBlocks = 2;
            s.heightBlocks = 2;
            s.resolutionW = W;
            s.resolutionH = H;
            s.url = "https://a.example/" + i;
            s.owner = new UUID(1, 1);
            screens.onScreenState(s);
        }
        screens.tick(new PlayerView(0, 4, 65, 6, 0, 0, -1, new UUID(1, 1), false, 1f, 1f));
        for (ClientScreen s : screens.all()) {
            assertTrue(s.hostScreenId() > 0);
            FramePipeline p = new FramePipeline();
            host.rings.put(s.hostScreenId(), p.ring());
            list.add(s);
            pipes.add(p);
            byte[] page = new byte[W * H * 4];
            pages.add(page);
            publish(p, page, 0, 0, W, H, rnd); // the first paint is a full frame
        }
        for (int frame = 0; frame < 300; frame++) {
            for (int i = 0; i < 3; i++) {
                int paints = rnd.nextInt(3);
                for (int k = 0; k < paints; k++) {
                    int x = rnd.nextInt(W), y = rnd.nextInt(H);
                    int w = 1 + rnd.nextInt(W - x), h = 1 + rnd.nextInt(H - y);
                    publish(pipes.get(i), pages.get(i), x, y, w, h, rnd);
                }
            }
            uploader.uploadFrame();
        }
        for (int frame = 0; frame < 20; frame++) uploader.uploadFrame(); // the page stops changing
        for (int i = 0; i < 3; i++) {
            PixelTexture t = (PixelTexture) list.get(i).texture;
            assertArrayEquals(pages.get(i), t.pixels, "screen " + i + ": a stale pixel survived");
        }
    }

    /** Paints a random rectangle into the page and sends it as a FRAME through M2's pipeline. */
    private static void publish(FramePipeline p, byte[] page, int x, int y, int w, int h, Random rnd)
        throws HostProtocol.ProtocolException {
        byte[] payload = new byte[HostProtocol.FrameHeader.BYTES + w * h * 4];
        byte value = (byte) rnd.nextInt(0x50); // never the texture's fill value
        for (int r = 0; r < h; r++) {
            for (int c = 0; c < w * 4; c++) {
                byte b = (byte) (value + (c & 3));
                payload[HostProtocol.FrameHeader.BYTES + r * w * 4 + c] = b;
                page[((y + r) * W + x) * 4 + c] = b;
            }
        }
        p.receive(new HostProtocol.FrameHeader(W, H, x, y, w, h), payload);
    }
}
