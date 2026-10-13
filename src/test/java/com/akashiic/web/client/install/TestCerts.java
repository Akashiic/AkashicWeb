package com.akashiic.web.client.install;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.text.SimpleDateFormat;
import java.util.Base64;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;

/**
 * Self-made certificate authorities and server certificates for the TLS tests, built in memory with a minimal DER
 * encoder and the public JCA API only (no internal JDK classes, no keys committed to the repository). EC P-256 keys
 * keep generation fast.
 */
final class TestCerts {

    /** A certificate and its private key. */
    static final class Issued {

        final X509Certificate cert;
        final PrivateKey key;

        Issued(X509Certificate cert, PrivateKey key) {
            this.cert = cert;
            this.key = key;
        }

        String pem() {
            return "-----BEGIN CERTIFICATE-----\n" + Base64.getMimeEncoder(64, new byte[] { '\n' })
                .encodeToString(encoded()) + "\n-----END CERTIFICATE-----\n";
        }

        byte[] encoded() {
            try {
                return cert.getEncoded();
            } catch (GeneralSecurityException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String ECDSA_SHA256 = "1.2.840.10045.4.3.2";
    private static final long DAY = 24L * 3600 * 1000;

    private TestCerts() {}

    /** A self-signed root CA. */
    static Issued ca(String commonName) throws GeneralSecurityException {
        KeyPair kp = keyPair();
        byte[] ext = seq(
            extension("2.5.29.19", true, seq(bool(true))), // basicConstraints: CA
            extension("2.5.29.15", true, bits(1, new byte[] { 0x06 }))); // keyUsage: keyCertSign, cRLSign
        return sign(name(commonName), name(commonName), kp, kp.getPrivate(), ext, -DAY, 30 * DAY);
    }

    /** A server certificate for {@code 127.0.0.1} (and {@code localhost}) issued by {@code ca}. */
    static Issued server(Issued ca) throws GeneralSecurityException {
        return server(ca, "127.0.0.1", true);
    }

    /** A server certificate whose only name is {@code dnsName} (to test host-name checks). */
    static Issued serverFor(Issued ca, String dnsName) throws GeneralSecurityException {
        return server(ca, dnsName, false);
    }

    private static Issued server(Issued ca, String nameOrIp, boolean ip) throws GeneralSecurityException {
        KeyPair kp = keyPair();
        byte[] san;
        try {
            san = ip ? seq(
                tlv(
                    0x87,
                    InetAddress.getByName(nameOrIp)
                        .getAddress()),
                tlv(0x82, "localhost".getBytes(StandardCharsets.US_ASCII)))
                : seq(tlv(0x82, nameOrIp.getBytes(StandardCharsets.US_ASCII)));
        } catch (java.net.UnknownHostException e) {
            throw new IllegalArgumentException(e);
        }
        byte[] ext = seq(
            extension("2.5.29.19", true, seq()),
            extension("2.5.29.15", true, bits(7, new byte[] { (byte) 0x80 })), // digitalSignature
            extension("2.5.29.17", false, san));
        return sign(
            ca.cert.getSubjectX500Principal()
                .getEncoded(), // the issuer's subject, byte for byte
            name(nameOrIp),
            kp,
            ca.key,
            ext,
            -DAY,
            30 * DAY);
    }

    /** A server-side TLS context presenting {@code leaf} (and its issuer) for an HttpsServer. */
    static SSLContext serverContext(Issued leaf, Issued issuer) throws Exception {
        char[] pw = "test".toCharArray();
        KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
        ks.load(null, null);
        ks.setKeyEntry("server", leaf.key, pw, new Certificate[] { leaf.cert, issuer.cert });
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, pw);
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), null, null);
        return ctx;
    }

    // ---------------------------------------------------------------- X.509 v3

    private static Issued sign(byte[] issuer, byte[] subject, KeyPair subjectKeys, PrivateKey issuerKey,
        byte[] extensions, long notBeforeOffset, long notAfterOffset) throws GeneralSecurityException {
        long now = System.currentTimeMillis();
        byte[] algorithm = seq(oid(ECDSA_SHA256));
        byte[] tbs = seq(
            explicit(0, integer(BigInteger.valueOf(2))), // v3
            integer(new BigInteger(63, RANDOM).add(BigInteger.ONE)),
            algorithm,
            issuer,
            seq(utcTime(new Date(now + notBeforeOffset)), utcTime(new Date(now + notAfterOffset))),
            subject,
            subjectKeys.getPublic()
                .getEncoded(), // SubjectPublicKeyInfo, already DER
            explicit(3, extensions));
        Signature s = Signature.getInstance("SHA256withECDSA");
        s.initSign(issuerKey);
        s.update(tbs);
        byte[] der = seq(tbs, algorithm, bits(0, s.sign()));
        X509Certificate cert = (X509Certificate) CertificateFactory.getInstance("X.509")
            .generateCertificate(new ByteArrayInputStream(der));
        return new Issued(cert, subjectKeys.getPrivate());
    }

    private static KeyPair keyPair() throws GeneralSecurityException {
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new ECGenParameterSpec("secp256r1"), RANDOM);
        return g.generateKeyPair();
    }

    private static byte[] name(String commonName) {
        return seq(set(seq(oid("2.5.4.3"), tlv(0x0C, commonName.getBytes(StandardCharsets.UTF_8)))));
    }

    private static byte[] extension(String oid, boolean critical, byte[] value) {
        return critical ? seq(oid(oid), bool(true), tlv(0x04, value)) : seq(oid(oid), tlv(0x04, value));
    }

    // ---------------------------------------------------------------- DER

    private static byte[] tlv(int tag, byte[]... parts) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        for (byte[] p : parts) body.write(p, 0, p.length);
        int n = body.size();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(tag);
        if (n < 0x80) {
            out.write(n);
        } else if (n < 0x100) {
            out.write(0x81);
            out.write(n);
        } else if (n < 0x10000) {
            out.write(0x82);
            out.write(n >> 8);
            out.write(n);
        } else {
            out.write(0x83);
            out.write(n >> 16);
            out.write(n >> 8);
            out.write(n);
        }
        byte[] b = body.toByteArray();
        out.write(b, 0, b.length);
        return out.toByteArray();
    }

    private static byte[] seq(byte[]... parts) {
        return tlv(0x30, parts);
    }

    private static byte[] set(byte[]... parts) {
        return tlv(0x31, parts);
    }

    private static byte[] explicit(int n, byte[] inner) {
        return tlv(0xA0 | n, inner);
    }

    private static byte[] integer(BigInteger v) {
        return tlv(0x02, v.toByteArray());
    }

    private static byte[] bool(boolean v) {
        return tlv(0x01, new byte[] { (byte) (v ? 0xFF : 0) });
    }

    private static byte[] bits(int unused, byte[] b) {
        byte[] body = new byte[b.length + 1];
        body[0] = (byte) unused;
        System.arraycopy(b, 0, body, 1, b.length);
        return tlv(0x03, body);
    }

    private static byte[] utcTime(Date d) {
        SimpleDateFormat f = new SimpleDateFormat("yyMMddHHmmss'Z'", Locale.ROOT);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return tlv(
            0x17,
            f.format(d)
                .getBytes(StandardCharsets.US_ASCII));
    }

    private static byte[] oid(String dotted) {
        String[] p = dotted.split("\\.");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(Integer.parseInt(p[0]) * 40 + Integer.parseInt(p[1]));
        for (int i = 2; i < p.length; i++) {
            long v = Long.parseLong(p[i]);
            int groups = 1;
            for (long t = v >> 7; t > 0; t >>= 7) groups++;
            for (int g = groups - 1; g >= 0; g--) {
                int b = (int) ((v >> (7 * g)) & 0x7F);
                out.write(g == 0 ? b : b | 0x80);
            }
        }
        return tlv(0x06, out.toByteArray());
    }
}
