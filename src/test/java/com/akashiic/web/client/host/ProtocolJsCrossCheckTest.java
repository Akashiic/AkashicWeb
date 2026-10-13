package com.akashiic.web.client.host;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import com.akashiic.web.common.SitePolicy;

/**
 * Review finding (medium, last part): HostProtocolTest compares Java with a frozen hex snapshot of protocol.js, so
 * neither suite ran host/src/protocol.js against Java and drift on either side went unnoticed. When node is on the
 * PATH this runs the host app's own encoder and decoder against HostProtocol, in both directions; skipped otherwise.
 */
@Timeout(60)
class ProtocolJsCrossCheckTest {

    /** Encodes with protocol.js ("encode") or decodes hex from stdin with its Decoder ("decode"). */
    private static final String SCRIPT = "'use strict';\n" + "const P = require(process.argv[2]);\n"
        + "if (process.argv[3] === 'encode') {\n"
        + "  const out = [];\n"
        + "  out.push(...P.encode(P.T.NAVIGATE, 7, 0xFFFFFFFE, { url: 'https://example.com/x' }));\n"
        + "  out.push(...P.encodeParts(P.T.FRAME, 3, 1, [P.frameHeader(1280, 720, 10, 20, 2, 1),"
        + " Buffer.from([1, 2, 3, 4, 5, 6, 7, 8])]));\n"
        + "  out.push(...P.encodeParts(P.T.PCM, 5, 2, [P.pcmHeader(123456, 48000, 2, 1), Buffer.from([1, 0, 255, 255])]));\n"
        + "  out.push(...P.encode(P.T.PONG, 0, 3, { nonce: 42, hostTimeMs: 7 }));\n"
        + "  process.stdout.write(Buffer.concat(out).toString('hex'));\n"
        + "} else {\n"
        + "  const hex = require('fs').readFileSync(0, 'utf8').trim();\n"
        + "  const got = [];\n"
        + "  const d = new P.Decoder(m => got.push({ type: m.type, screenId: m.screenId, seq: m.seq,\n"
        + "    json: m.payload.length ? JSON.parse(m.payload.toString('utf8')) : null }));\n"
        + "  d.feed(Buffer.from(hex, 'hex'));\n"
        + "  process.stdout.write(JSON.stringify(got));\n"
        + "}\n";

    @TempDir
    File dir;

    @Test
    void javaAndTheHostAppsProtocolJsAgreeInBothDirections() throws Exception {
        File host = RealHostEndToEndTest.findHostDir();
        assumeTrue(host != null, "host/ not found: cross-check skipped");
        File protocolJs = new File(host, "src/protocol.js");
        assumeTrue(protocolJs.isFile(), "host/src/protocol.js not found: cross-check skipped");
        assumeTrue(nodeAvailable(), "node is not on the PATH: cross-check skipped");
        File script = new File(dir, "crosscheck.js");
        Files.write(script.toPath(), SCRIPT.getBytes(StandardCharsets.UTF_8));

        // protocol.js encodes, Java decodes.
        byte[] js = unhex(run(script, protocolJs, "encode", null));
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(js));
        HostProtocol.Header nav = HostProtocol.readHeader(in);
        assertEquals(HostProtocol.NAVIGATE, nav.type);
        assertEquals(7, nav.screenId);
        assertEquals(0xFFFFFFFE, nav.seq);
        byte[] navPayload = new byte[nav.length];
        in.readFully(navPayload);
        String navJson = HostMessages.navigate("https://example.com/x");
        assertEquals(navJson, new String(navPayload, StandardCharsets.UTF_8));
        ByteArrayOutputStream javaNav = new ByteArrayOutputStream();
        HostProtocol.writeJson(new DataOutputStream(javaNav), HostProtocol.NAVIGATE, 7, 0xFFFFFFFE, navJson);
        assertArrayEquals(
            javaNav.toByteArray(),
            Arrays.copyOfRange(js, 0, HostProtocol.HEADER_BYTES + nav.length),
            "byte for byte, as Java writes it");

        HostProtocol.Header frame = HostProtocol.readHeader(in);
        assertEquals(HostProtocol.FRAME, frame.type);
        assertEquals(3, frame.screenId);
        byte[] head = new byte[HostProtocol.FrameHeader.BYTES];
        in.readFully(head);
        HostProtocol.FrameHeader f = HostProtocol.FrameHeader.parse(head, frame.length);
        assertArrayEquals(new int[] { 1280, 720, 10, 20, 2, 1 }, new int[] { f.width, f.height, f.x, f.y, f.w, f.h });
        byte[] pixels = new byte[8];
        in.readFully(pixels);
        assertArrayEquals(new byte[] { 1, 2, 3, 4, 5, 6, 7, 8 }, pixels);

        HostProtocol.Header pcm = HostProtocol.readHeader(in);
        assertEquals(HostProtocol.PCM, pcm.type);
        byte[] pcmPayload = new byte[pcm.length];
        in.readFully(pcmPayload);
        PcmChunk chunk = PcmChunk.decode(pcmPayload, 0, pcmPayload.length);
        assertEquals(123456L, chunk.ptsMicros);
        assertEquals(48000, chunk.sampleRate);
        assertEquals(2, chunk.channels);
        assertArrayEquals(new short[] { 1, -1 }, chunk.interleaved, "big-endian header, little-endian samples");

        HostProtocol.Header pong = HostProtocol.readHeader(in);
        assertEquals(HostProtocol.PONG, pong.type);
        byte[] pongPayload = new byte[pong.length];
        in.readFully(pongPayload);
        assertEquals(
            42L,
            JsonParser.integer(JsonParser.parseObject(new String(pongPayload, StandardCharsets.UTF_8)), "nonce", -1));
        assertEquals(-1, in.read(), "nothing left over");

        // Java encodes, protocol.js decodes.
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        SitePolicy policy = SitePolicy.blocklist(Collections.singletonList("bad.example"));
        HostProtocol.writeJson(out, HostProtocol.WELCOME, 0, 0, HostMessages.welcome(1, policy, "pt-BR", null));
        HostProtocol.writeJson(
            out,
            HostProtocol.CREATE,
            9,
            0,
            HostMessages.create(
                new CreateSpec(
                    640,
                    360,
                    30,
                    "https://example.com/",
                    CreateSpec.Session.PRIVATE,
                    CreateSpec.Audio.OS,
                    0.5f)));
        HostProtocol.writeJson(
            out,
            HostProtocol.INPUT,
            9,
            1,
            HostMessages.input(new InputEvent(InputEvent.Type.MOUSE_DOWN, 3, 4, "right", 2, 0, 0, null, 0, null)));
        HostProtocol.write(out, HostProtocol.QUIT, 0, 1, null);
        Object decoded = JsonParser.parse(run(script, protocolJs, "decode", hex(bytes.toByteArray())));
        assertTrue(decoded instanceof List, String.valueOf(decoded));
        List<?> got = (List<?>) decoded;
        assertEquals(4, got.size(), got.toString());
        Map<?, ?> welcome = (Map<?, ?>) got.get(0);
        assertEquals(32L, welcome.get("type"));
        Map<?, ?> welcomeJson = (Map<?, ?>) welcome.get("json");
        assertEquals("blocklist", ((Map<?, ?>) welcomeJson.get("policy")).get("mode"));
        Map<?, ?> create = (Map<?, ?>) got.get(1);
        assertEquals(9L, create.get("screenId"));
        assertEquals("private", ((Map<?, ?>) create.get("json")).get("session"));
        Map<?, ?> input = (Map<?, ?>) ((Map<?, ?>) got.get(2)).get("json");
        assertEquals("mouseDown", input.get("type"));
        assertEquals("right", input.get("button"));
        Map<?, ?> quit = (Map<?, ?>) got.get(3);
        assertEquals(43L, quit.get("type"));
        assertEquals(1L, quit.get("seq"));
        assertEquals(null, quit.get("json"));
    }

    private static boolean nodeAvailable() {
        try {
            Process p = new ProcessBuilder("node", "--version").redirectErrorStream(true)
                .start();
            drain(p.getInputStream());
            return p.waitFor(20, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    /** Runs the script with node; returns its stdout. */
    private static String run(File script, File protocolJs, String mode, String stdin) throws Exception {
        Process p = new ProcessBuilder("node", script.getAbsolutePath(), protocolJs.getAbsolutePath(), mode)
            .redirectError(ProcessBuilder.Redirect.INHERIT)
            .start();
        try (OutputStream o = p.getOutputStream()) {
            if (stdin != null) o.write(stdin.getBytes(StandardCharsets.US_ASCII));
        }
        String out = new String(drain(p.getInputStream()), StandardCharsets.UTF_8);
        assertTrue(p.waitFor(30, TimeUnit.SECONDS), "node did not finish");
        assertEquals(0, p.exitValue(), "node failed; output: " + out);
        assertNotNull(out);
        return out.trim();
    }

    private static byte[] drain(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
        return b.toByteArray();
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(String.format("%02x", x & 0xFF));
        return sb.toString();
    }

    private static byte[] unhex(String s) {
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        return out;
    }
}
