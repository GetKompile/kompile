package ai.kompile.cli.main.sync;

import javax.net.ssl.*;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.*;
import java.security.cert.X509Certificate;
import java.util.HexFormat;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** Private on-disk identity and certificate pinning. Never uses a trust-all TLS context. */
public final class PeerTls {
    private PeerTls() { }
    public static void init(Path directory) throws Exception {
        Path dir = directory.toAbsolutePath().normalize();
        SyncPaths.rejectLinks(dir);
        if (Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Identity directory already exists; refusing to replace it.");
        Files.createDirectories(dir.getParent());
        if (!Files.getFileStore(dir.getParent()).supportsFileAttributeView("posix")) throw new IOException("Private POSIX storage is required for peer identities.");
        Files.createDirectory(dir, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        privateWrite(dir.resolve("password"), randomSecret());
        privateWrite(dir.resolve("token"), randomSecret());
        Path keytool = Path.of(System.getProperty("java.home"), "bin", "keytool");
        String executable = Files.isExecutable(keytool) ? keytool.toString() : "keytool";
        Process process = new ProcessBuilder(executable, "-genkeypair", "-alias", "kompile-peer", "-keyalg", "EC",
                "-groupname", "secp256r1", "-dname", "CN=Kompile Peer", "-validity", "3650", "-storetype", "PKCS12",
                "-keystore", dir.resolve("identity.p12").toString(), "-storepass:file", dir.resolve("password").toString(), "-noprompt")
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        boolean finished;
        try { finished = process.waitFor(30, TimeUnit.SECONDS); }
        catch (InterruptedException e) { process.destroyForcibly(); Thread.currentThread().interrupt(); throw e; }
        if (!finished) { process.destroyForcibly(); throw new IOException("Peer identity keytool timed out."); }
        if (process.exitValue() != 0) throw new IOException("Peer identity generation failed; check keytool availability. Partial identity directory retained for inspection.");
        Files.setPosixFilePermissions(dir.resolve("identity.p12"), PosixFilePermissions.fromString("rw-------"));
    }
    private static String randomSecret() {
        byte[] bytes = new byte[32]; new SecureRandom().nextBytes(bytes); return HexFormat.of().formatHex(bytes);
    }
    private static void privateWrite(Path file, String text) throws IOException {
        try (var channel = Files.newByteChannel(file, Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))) {
            var buffer = java.nio.ByteBuffer.wrap(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            while (buffer.hasRemaining()) channel.write(buffer);
        }
    }
    public static String readSecret(Path path) throws IOException {
        String value = new String(privateBytes(path, 4096), java.nio.charset.StandardCharsets.UTF_8).trim();
        if (value.isBlank() || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) throw new IOException("Invalid peer secret file.");
        return value;
    }
    private static byte[] privateBytes(Path file, int maximum) throws IOException {
        Path target = file.toAbsolutePath().normalize(); SyncPaths.rejectLinks(target);
        var permissions = Files.getPosixFilePermissions(target, LinkOption.NOFOLLOW_LINKS);
        if (!PosixFilePermissions.fromString("rw-------").containsAll(permissions)) throw new IOException("Peer credential files must be owner-only (chmod 600).");
        if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Peer credential is not a regular file.");
        try (var parent = SyncPaths.secureParent(target); var channel = parent.newByteChannel(target.getFileName(),
                Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
            byte[] data = java.nio.channels.Channels.newInputStream(channel).readNBytes(maximum + 1);
            if (data.length > maximum) throw new IOException("Peer credential file too large."); return data;
        }
    }
    private static KeyStore store(Path file, Path passwordFile) throws Exception {
        KeyStore store = KeyStore.getInstance("PKCS12");
        store.load(new ByteArrayInputStream(privateBytes(file, 1024 * 1024)), readSecret(passwordFile).toCharArray()); return store;
    }
    public static String fingerprint(Path file, Path passwordFile) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(store(file, passwordFile).getCertificate("kompile-peer").getEncoded()));
    }
    public static SSLContext serverContext(Path file, Path passwordFile) throws Exception {
        KeyManagerFactory factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        factory.init(store(file, passwordFile), readSecret(passwordFile).toCharArray());
        SSLContext context = SSLContext.getInstance("TLS"); context.init(factory.getKeyManagers(), null, new SecureRandom()); return context;
    }
    static SSLContext clientContext(String fingerprint) throws Exception {
        if (fingerprint == null || !fingerprint.matches("[A-Fa-f0-9]{64}")) throw new IllegalArgumentException("Expected SHA-256 certificate fingerprint (64 hex digits).");
        byte[] pin = HexFormat.of().parseHex(fingerprint);
        X509TrustManager trust = new X509TrustManager() {
            public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            public void checkClientTrusted(X509Certificate[] chain, String auth) throws java.security.cert.CertificateException { throw new java.security.cert.CertificateException("Client certificates not supported."); }
            public void checkServerTrusted(X509Certificate[] chain, String auth) throws java.security.cert.CertificateException {
                try {
                    if (chain == null || chain.length == 0) throw new java.security.cert.CertificateException("Missing certificate.");
                    chain[0].checkValidity();
                    if (!MessageDigest.isEqual(pin, MessageDigest.getInstance("SHA-256").digest(chain[0].getEncoded())))
                        throw new java.security.cert.CertificateException("Peer certificate pin mismatch.");
                } catch (GeneralSecurityException e) { throw new java.security.cert.CertificateException("Peer certificate rejected.", e); }
            }
        };
        SSLContext context = SSLContext.getInstance("TLS"); context.init(null, new TrustManager[]{trust}, new SecureRandom()); return context;
    }
}
