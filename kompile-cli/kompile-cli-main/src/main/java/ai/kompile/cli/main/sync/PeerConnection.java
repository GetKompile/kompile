package ai.kompile.cli.main.sync;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import javax.net.ssl.SSLSocket;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;

/** Pinned TLS JSON-lines connection, also usable by the existing SyncSession. */
public final class PeerConnection implements SyncTransport {
    static final int MAX_LINE = 24 * 1024 * 1024;
    private final URI address;
    private final String fingerprint;
    private final String token;
    private SSLSocket socket;
    private InputStream input;
    private OutputStream output;
    private BufferedReader reader;
    public PeerConnection(String address, String fingerprint, String token) {
        this.address = validateAddress(address);
        if (fingerprint == null || !fingerprint.matches("[A-Fa-f0-9]{64}")) throw new IllegalArgumentException("Expected a 64-hex SHA-256 fingerprint.");
        if (token == null || !token.matches("[A-Fa-f0-9]{64}")) throw new IllegalArgumentException("Invalid peer token (expected 32 random bytes in hex).");
        this.fingerprint = fingerprint; this.token = token;
    }
    public static URI validateAddress(String address) {
        URI uri = URI.create(address);
        if (!"tls".equals(uri.getScheme()) || uri.getHost() == null || uri.getPort() < 1 || uri.getPort() > 65535
                || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                || (uri.getRawPath() != null && !uri.getRawPath().isEmpty()))
            throw new IllegalArgumentException("Peer address must be tls://host:port (no path, userinfo, query or fragment).");
        return uri;
    }
    private synchronized void ensure() throws IOException {
        if (socket != null) return;
        SSLSocket connected = null;
        try {
            connected = (SSLSocket) PeerTls.clientContext(fingerprint).getSocketFactory().createSocket();
            connected.setEnabledProtocols(new String[]{"TLSv1.3", "TLSv1.2"});
            connected.connect(new InetSocketAddress(address.getHost(), address.getPort()), 10_000);
            connected.setSoTimeout(60_000); connected.startHandshake(); // Verify pin BEFORE transmitting token.
            InputStream in = limitedInput(connected.getInputStream());
            OutputStream out = connected.getOutputStream();
            ObjectNode auth = SyncProtocol.request(0, "auth").put("token", token);
            out.write((SyncProtocol.toLine(auth) + "\n").getBytes(StandardCharsets.UTF_8)); out.flush();
            JsonNode response = SyncProtocol.mapper().readTree(readLine(in));
            if (response == null || !response.path("ok").asBoolean(false) || response.path("id").asInt(-1) != 0)
                throw new IOException("Peer authentication rejected.");
            socket = connected; input = in; output = out;
        } catch (Exception e) {
            if (connected != null) try { connected.close(); } catch (IOException ignored) { }
            throw new IOException("Peer connection/authentication failed (check address, certificate fingerprint and token).", e);
        }
    }
    @Override public OutputStream stdin() throws IOException { ensure(); return output; }
    @Override public InputStream stdout() throws IOException { ensure(); return input; }
    @Override public String describe() { return address.toString(); }
    public synchronized JsonNode request(ObjectNode request) throws IOException {
        ensure(); if (reader == null) reader = SyncProtocol.reader(input);
        return SyncProtocol.exchange(reader, line -> { output.write((line + "\n").getBytes(StandardCharsets.UTF_8)); output.flush(); }, request);
    }
    @Override public synchronized void close() { if (socket != null) try { socket.close(); } catch (IOException ignored) { } }
    /** Count bytes before UTF-8 decode, including when SyncSession wraps the stream in a BufferedReader. */
    static InputStream limitedInput(InputStream input) {
        return new FilterInputStream(input) {
            private int lineBytes;
            private void count(int b) throws IOException {
                if (b == '\n') lineBytes = 0;
                else if (++lineBytes > MAX_LINE) throw new IOException("Peer frame exceeds limit.");
            }
            @Override public int read() throws IOException { int b = in.read(); if (b >= 0) count(b); return b; }
            @Override public int read(byte[] b, int off, int len) throws IOException {
                int n = in.read(b, off, len); for (int i = off; i < off + n; i++) count(b[i] & 255); return n;
            }
        };
    }
    public static String readLine(InputStream input) throws IOException { return readLine(input, MAX_LINE); }
    static String readLine(InputStream input, int maximum) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int b;
        while ((b = input.read()) != -1) {
            if (b == '\n') return buffer.toString(StandardCharsets.UTF_8);
            if (buffer.size() == maximum) throw new IOException("Peer frame exceeds limit.");
            buffer.write(b);
        }
        if (buffer.size() != 0) throw new EOFException("Truncated peer frame.");
        return null;
    }
}
