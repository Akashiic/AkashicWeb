package com.akashiic.web.client.install;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.cert.CertPathBuilderException;
import java.security.cert.CertPathValidatorException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.X509ExtendedTrustManager;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The installer's own TLS trust (ADR-011 "TLS", M3_FIX item 2): JRE store, then OS store, then pinned roots; the next
 * store only on a certificate-path failure. Each layer is exercised with a real handshake against a loopback HTTPS
 * server whose certificate comes from a self-made CA that only that layer knows.
 */
class InstallerTlsTest {

    /** caJre is known to the "JRE" layer, caOs to the "OS" bundle, caPinned to the pinned layer; caNone to none. */
    private static TestCerts.Issued caJre;
    private static TestCerts.Issued caOs;
    private static TestCerts.Issued caPinned;
    private static TestCerts.Issued caNone;

    @TempDir
    File tmp;

    @BeforeAll
    static void makeCas() throws Exception {
        caJre = TestCerts.ca("AkashicWeb Test JRE Root");
        caOs = TestCerts.ca("AkashicWeb Test OS Root");
        caPinned = TestCerts.ca("AkashicWeb Test Pinned Root");
        caNone = TestCerts.ca("AkashicWeb Test Unknown Root");
    }

    /** Counts how often each layer is loaded, to prove the stores are read only when needed. */
    private final AtomicInteger osLoads = new AtomicInteger();
    private final AtomicInteger pinnedLoads = new AtomicInteger();

    private InstallerTls layered() throws Exception {
        File bundle = new File(tmp, "ca-certificates.crt");
        // Like a distribution bundle: comments between blocks, an unrelated root first.
        String text = "# Unrelated root\n" + TestCerts.ca("Unrelated")
            .pem() + "\n# AkashicWeb Test OS Root\n" + caOs.pem();
        Files.write(bundle.toPath(), text.getBytes(StandardCharsets.US_ASCII));
        String pinnedPem = caPinned.pem();
        String pin = Sha256.of(caPinned.encoded());
        return new InstallerTls(
            () -> InstallerTls.pkix(InstallerTls.keyStore(Collections.singletonList(caJre.cert))),
            () -> {
                osLoads.incrementAndGet();
                return InstallerTls.linuxBundle(new File(tmp, "missing.crt").getPath(), bundle.getPath());
            },
            () -> {
                pinnedLoads.incrementAndGet();
                return InstallerTls.pkix(
                    InstallerTls.keyStore(
                        InstallerTls.pinnedRoots(
                            name -> new ByteArrayInputStream(pinnedPem.getBytes(StandardCharsets.US_ASCII)),
                            new String[] { "test-root.pem" },
                            new String[] { pin })));
            });
    }

    /** One GET over the installer's TLS context; returns the HTTP status or throws the handshake failure. */
    private static int get(InstallerTls tls, TestCerts.Issued leaf, TestCerts.Issued issuer) throws Exception {
        try (TestServer server = new TestServer(TestCerts.serverContext(leaf, issuer))) {
            server.body = new byte[] { 1, 2, 3 };
            HttpFetcher http = new HttpFetcher("test", tls, false, 5000, 5000);
            HttpFetcher.Response r = http.open(server.directUrl("x.zip"), 0, null);
            r.conn.disconnect();
            return r.code;
        }
    }

    @Test
    void eachStoreIsTriedInOrderAndOnlyWhenNeeded() throws Exception {
        InstallerTls tls = layered();
        assertEquals(200, get(tls, TestCerts.server(caJre), caJre));
        assertEquals(InstallerTls.Layer.JRE, tls.lastTrustedBy());
        assertEquals(0, osLoads.get(), "the OS store is not read while the JRE store suffices");
        assertEquals(0, pinnedLoads.get());

        assertEquals(200, get(tls, TestCerts.server(caOs), caOs));
        assertEquals(InstallerTls.Layer.OS, tls.lastTrustedBy());
        assertEquals(0, pinnedLoads.get(), "the pinned roots are not read while the OS store suffices");

        assertEquals(200, get(tls, TestCerts.server(caPinned), caPinned));
        assertEquals(InstallerTls.Layer.PINNED, tls.lastTrustedBy());

        SSLHandshakeException e = assertThrows(
            SSLHandshakeException.class,
            () -> get(tls, TestCerts.server(caNone), caNone));
        assertTrue(InstallerTls.isTrustFailure(e));
        assertTrue(
            String.valueOf(e.getMessage())
                .contains("no trusted root")
                || String.valueOf(e.getCause())
                    .contains("no trusted root"),
            String.valueOf(e));
        assertEquals(1, osLoads.get(), "each store is loaded once");
        assertEquals(1, pinnedLoads.get());
    }

    @Test
    void aWrongHostNameIsNotRetriedAgainstOtherStores() throws Exception {
        InstallerTls tls = layered();
        // Trusted by the first layer, but issued for another host.
        SSLHandshakeException e = assertThrows(
            SSLHandshakeException.class,
            () -> get(tls, TestCerts.serverFor(caJre, "example.invalid"), caJre));
        assertTrue(InstallerTls.isTrustFailure(e));
        assertFalse(InstallerTls.isPathFailure(e), "a host-name mismatch is not a path failure");
        assertEquals(0, osLoads.get(), "no other store was consulted");
        assertEquals(0, pinnedLoads.get());
        assertNull(tls.lastTrustedBy());
    }

    @Test
    void aPermissiveDefaultHostnameVerifierDoesNotWeakenTheInstaller() throws Exception {
        // Some mods install an accept-everything default verifier for their own downloads.
        HostnameVerifier before = HttpsURLConnection.getDefaultHostnameVerifier();
        HttpsURLConnection.setDefaultHostnameVerifier((host, session) -> true);
        try {
            InstallerTls tls = layered();
            assertThrows(
                SSLHandshakeException.class,
                () -> get(tls, TestCerts.serverFor(caJre, "evil.invalid"), caJre));
        } finally {
            HttpsURLConnection.setDefaultHostnameVerifier(before);
        }
    }

    @Test
    void bundledRootsLoadAndMatchTheirPins() throws Exception {
        List<X509Certificate> roots = InstallerTls.loadBundledRoots();
        List<String> names = new ArrayList<>();
        for (X509Certificate c : roots) {
            names.add(
                c.getSubjectX500Principal()
                    .getName());
        }
        assertEquals(6, roots.size(), names.toString());
        for (String cn : new String[] { "ISRG Root X1", "ISRG Root X2", "USERTrust RSA Certification Authority",
            "USERTrust ECC Certification Authority", "Sectigo Public Server Authentication Root R46",
            "Sectigo Public Server Authentication Root E46" }) {
            assertTrue(
                names.stream()
                    .anyMatch(n -> n.contains("CN=" + cn + ",")),
                cn + " missing from " + names);
        }
    }

    @Test
    void aPinnedRootWithAnotherFingerprintIsRefused() throws Exception {
        String pem = caPinned.pem();
        InstallException e = assertThrows(
            InstallException.class,
            () -> InstallerTls.pinnedRoots(
                name -> new ByteArrayInputStream(pem.getBytes(StandardCharsets.US_ASCII)),
                new String[] { "x.pem" },
                new String[] { InstallerTls.ROOT_SHA256[0] }));
        assertEquals(InstallFailure.TLS_FAILED, e.failure);
        assertTrue(e.detail.contains("not the pinned one"), e.detail);
    }

    @Test
    void aMissingOsStoreIsSkipped() throws Exception {
        assertNull(InstallerTls.linuxBundle(new File(tmp, "none.crt").getPath()));
        if (!System.getProperty("os.name")
            .toLowerCase(java.util.Locale.ROOT)
            .startsWith("windows")) {
            assertNull(InstallerTls.windowsRoot(), "no SunMSCAPI here: the OS layer is absent, not an error");
        }
        InstallerTls tls = new InstallerTls(
            () -> InstallerTls.pkix(InstallerTls.keyStore(Collections.singletonList(caJre.cert))),
            () -> null,
            () -> InstallerTls.pkix(InstallerTls.keyStore(Collections.singletonList(caPinned.cert))));
        assertEquals(200, get(tls, TestCerts.server(caPinned), caPinned));
        assertEquals(InstallerTls.Layer.PINNED, tls.lastTrustedBy());
    }

    @Test
    void pemParsingIgnoresTextBetweenBlocks() throws Exception {
        String text = "garbage\n" + caJre.pem() + "## comment\n\n" + caOs.pem() + "trailing";
        assertEquals(
            2,
            InstallerTls.parsePem(text.getBytes(StandardCharsets.US_ASCII))
                .size());
        assertEquals(
            0,
            InstallerTls.parsePem("no certificates".getBytes(StandardCharsets.US_ASCII))
                .size());
    }

    @Test
    void theFactoryIsBuiltOnceAndNeverInstalledAsDefault() throws Exception {
        SSLSocketFactory before = HttpsURLConnection.getDefaultSSLSocketFactory();
        InstallerTls tls = new InstallerTls();
        SSLSocketFactory f = tls.socketFactory();
        assertNotNull(f);
        assertSame(f, tls.socketFactory());
        assertSame(before, HttpsURLConnection.getDefaultSSLSocketFactory());
    }

    /** A trust manager stub that accepts, or rejects with the given exception. */
    private static X509ExtendedTrustManager stub(CertificateException reject, List<String> calls, String name) {
        return new X509ExtendedTrustManager() {

            private void check() throws CertificateException {
                calls.add(name);
                if (reject != null) throw reject;
            }

            @Override
            public void checkServerTrusted(X509Certificate[] c, String a) throws CertificateException {
                check();
            }

            @Override
            public void checkServerTrusted(X509Certificate[] c, String a, Socket s) throws CertificateException {
                check();
            }

            @Override
            public void checkServerTrusted(X509Certificate[] c, String a, SSLEngine e) throws CertificateException {
                check();
            }

            @Override
            public void checkClientTrusted(X509Certificate[] c, String a) {}

            @Override
            public void checkClientTrusted(X509Certificate[] c, String a, Socket s) {}

            @Override
            public void checkClientTrusted(X509Certificate[] c, String a, SSLEngine e) {}

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        };
    }

    private static CertificateException noPath(String who) {
        return new CertificateException(who + ": PKIX path building failed", new CertPathBuilderException("no anchor"));
    }

    @Test
    void layeredTrustManagerMovesOnOnlyForPathFailures() throws Exception {
        List<String> calls = new ArrayList<>();
        InstallerTls.LayeredTrustManager tm = new InstallerTls.LayeredTrustManager(
            new InstallerTls.Source[] { () -> stub(null, calls, "jre"), () -> stub(null, calls, "os"),
                () -> stub(null, calls, "pinned") });
        tm.checkServerTrusted(new X509Certificate[0], "ECDHE_ECDSA", (Socket) null);
        assertEquals(Arrays.asList("jre"), calls);

        calls.clear();
        tm = new InstallerTls.LayeredTrustManager(
            new InstallerTls.Source[] { () -> stub(noPath("jre"), calls, "jre"), () -> null,
                () -> stub(null, calls, "pinned") });
        tm.checkServerTrusted(new X509Certificate[0], "RSA", (SSLEngine) null);
        assertEquals(Arrays.asList("jre", "pinned"), calls, "an absent OS store is skipped");
        assertEquals(InstallerTls.Layer.PINNED, tm.lastTrustedBy);

        calls.clear();
        CertificateException hostname = new CertificateException("No subject alternative names matching");
        InstallerTls.LayeredTrustManager stops = new InstallerTls.LayeredTrustManager(
            new InstallerTls.Source[] { () -> stub(hostname, calls, "jre"), () -> stub(null, calls, "os"),
                () -> stub(null, calls, "pinned") });
        CertificateException e = assertThrows(
            CertificateException.class,
            () -> stops.checkServerTrusted(new X509Certificate[0], "RSA"));
        assertSame(hostname, e);
        assertEquals(Arrays.asList("jre"), calls, "a non-path failure is final");

        calls.clear();
        InstallerTls.LayeredTrustManager none = new InstallerTls.LayeredTrustManager(
            new InstallerTls.Source[] { () -> stub(noPath("jre"), calls, "jre"), () -> stub(noPath("os"), calls, "os"),
                () -> stub(noPath("pinned"), calls, "pinned") });
        e = assertThrows(CertificateException.class, () -> none.checkServerTrusted(new X509Certificate[0], "RSA"));
        assertEquals(Arrays.asList("jre", "os", "pinned"), calls);
        assertTrue(
            e.getMessage()
                .contains("JRE trust store, operating-system root store, bundled roots"),
            e.getMessage());
        assertEquals(
            2,
            e.getCause()
                .getSuppressed().length,
            "every store's reason is kept");

        InstallerTls.LayeredTrustManager clientAuth = new InstallerTls.LayeredTrustManager(
            new InstallerTls.Source[] { () -> stub(null, calls, "jre"), () -> null, () -> null });
        assertThrows(
            CertificateException.class,
            () -> clientAuth.checkClientTrusted(new X509Certificate[0], "RSA"),
            "the installer never accepts client certificates");
    }

    @Test
    void classifiesTrustFailures() {
        SSLHandshakeException pkix = new SSLHandshakeException("PKIX path building failed");
        pkix.initCause(new CertificateException(new CertPathValidatorException("no trust anchor")));
        assertTrue(InstallerTls.isTrustFailure(pkix));
        assertTrue(InstallerTls.isPathFailure(pkix));
        assertTrue(InstallerTls.isTlsFailure(pkix));
        SSLHandshakeException host = new SSLHandshakeException("No subject alternative DNS name");
        host.initCause(new CertificateException("No subject alternative DNS name matching x found"));
        assertTrue(InstallerTls.isTrustFailure(host));
        assertFalse(InstallerTls.isPathFailure(host));
        SSLHandshakeException protocol = new SSLHandshakeException("Received fatal alert: protocol_version");
        assertFalse(InstallerTls.isTrustFailure(protocol));
        assertTrue(InstallerTls.isTlsFailure(protocol));
        assertFalse(InstallerTls.isTlsFailure(new SocketTimeoutException("Read timed out")));
        assertFalse(InstallerTls.isTlsFailure(new IOException("Connection reset")));
    }
}
