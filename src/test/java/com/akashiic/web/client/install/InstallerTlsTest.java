package com.akashiic.web.client.install;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.security.cert.CertPathValidatorException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.X509ExtendedTrustManager;

import org.junit.jupiter.api.Test;

/** The installer-only TLS fallback (ADR-011 "TLS"). */
class InstallerTlsTest {

    @Test
    void bundledRootsLoadAndMatchTheirPins() throws Exception {
        List<X509Certificate> roots = InstallerTls.loadBundledRoots();
        assertEquals(2, roots.size());
        assertTrue(
            roots.get(0)
                .getSubjectX500Principal()
                .getName()
                .contains("ISRG Root X1"));
        assertTrue(
            roots.get(1)
                .getSubjectX500Principal()
                .getName()
                .contains("ISRG Root X2"));
    }

    @Test
    void fallbackFactoryIsBuiltOnceAndNeverInstalledAsDefault() throws Exception {
        SSLSocketFactory before = javax.net.ssl.HttpsURLConnection.getDefaultSSLSocketFactory();
        InstallerTls tls = new InstallerTls();
        SSLSocketFactory f = tls.fallbackFactory();
        assertNotNull(f);
        assertSame(f, tls.fallbackFactory());
        assertSame(before, javax.net.ssl.HttpsURLConnection.getDefaultSSLSocketFactory());
    }

    /** A trust manager stub that accepts or rejects every chain. */
    private static X509ExtendedTrustManager stub(boolean accept, List<String> calls, String name) {
        return new X509ExtendedTrustManager() {

            private void check() throws CertificateException {
                calls.add(name);
                if (!accept) throw new CertificateException(name + " rejects");
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

    @Test
    void eitherTrustManagerTriesTheJreStoreFirstThenTheBundledRoots() throws Exception {
        List<String> calls = new ArrayList<>();
        new InstallerTls.EitherTrustManager(stub(true, calls, "jre"), stub(true, calls, "bundled"))
            .checkServerTrusted(new X509Certificate[0], "ECDHE_ECDSA", (Socket) null);
        assertEquals(Arrays.asList("jre"), calls, "the bundled roots are not consulted when the JRE trusts the chain");

        calls.clear();
        new InstallerTls.EitherTrustManager(stub(false, calls, "jre"), stub(true, calls, "bundled"))
            .checkServerTrusted(new X509Certificate[0], "RSA", (SSLEngine) null);
        assertEquals(Arrays.asList("jre", "bundled"), calls);

        calls.clear();
        CertificateException e = assertThrows(
            CertificateException.class,
            () -> new InstallerTls.EitherTrustManager(stub(false, calls, "jre"), stub(false, calls, "bundled"))
                .checkServerTrusted(new X509Certificate[0], "RSA"));
        assertEquals("jre rejects", e.getMessage(), "the JRE's reason is reported");
        assertEquals("bundled rejects", e.getSuppressed()[0].getMessage());
        assertThrows(
            CertificateException.class,
            () -> new InstallerTls.EitherTrustManager(stub(true, calls, "jre"), stub(true, calls, "bundled"))
                .checkClientTrusted(new X509Certificate[0], "RSA"),
            "the installer never accepts client certificates");
    }

    @Test
    void classifiesTrustFailures() {
        SSLHandshakeException pkix = new SSLHandshakeException("PKIX path building failed");
        pkix.initCause(new CertificateException(new CertPathValidatorException("no trust anchor")));
        assertTrue(InstallerTls.isTrustFailure(pkix));
        assertTrue(InstallerTls.isTlsFailure(pkix));
        SSLHandshakeException protocol = new SSLHandshakeException("Received fatal alert: protocol_version");
        assertFalse(InstallerTls.isTrustFailure(protocol));
        assertTrue(InstallerTls.isTlsFailure(protocol));
        assertFalse(InstallerTls.isTlsFailure(new SocketTimeoutException("Read timed out")));
        assertFalse(InstallerTls.isTlsFailure(new IOException("Connection reset")));
    }
}
