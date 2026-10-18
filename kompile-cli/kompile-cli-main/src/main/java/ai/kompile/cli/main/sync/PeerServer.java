package ai.kompile.cli.main.sync;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import javax.net.ssl.*;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Set;
import java.util.concurrent.*;
import java.util.function.Function;
import java.util.function.Supplier;

/** Bounded, serialized authenticated sessions prevent overlapping sync writes within one listener. */
public final class PeerServer implements AutoCloseable {
    private final SSLServerSocket server;
    private final Thread acceptor;
    private final ThreadPoolExecutor worker;
    private final Set<SSLSocket> clients = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;
    public PeerServer(String bind, int port, SSLContext context, String token,
                      Supplier<Function<JsonNode, ObjectNode>> handlers) throws IOException {
        if (token == null || !token.matches("[A-Fa-f0-9]{64}")) throw new IllegalArgumentException("Invalid listener token.");
        server = (SSLServerSocket) context.getServerSocketFactory().createServerSocket();
        server.setEnabledProtocols(new String[]{"TLSv1.3", "TLSv1.2"});
        server.bind(new InetSocketAddress(bind, port), 8);
        worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(8), runnable -> {
            Thread thread = new Thread(runnable, "peer-session"); thread.setDaemon(true); return thread;
        });
        byte[] secret = token.getBytes(StandardCharsets.UTF_8);
        acceptor = new Thread(() -> {
            while (!closed) {
                try {
                    SSLSocket socket = (SSLSocket) server.accept();
                    socket.setSoTimeout(10_000); clients.add(socket);
                    try { worker.execute(() -> serve(socket, secret, handlers)); }
                    catch (RejectedExecutionException busy) { clients.remove(socket); socket.close(); }
                } catch (IOException e) { if (!closed) close(); }
            }
        }, "peer-accept");
        acceptor.setDaemon(true); acceptor.start();
    }
    public int port() { return server.getLocalPort(); }
    private void serve(SSLSocket socket, byte[] secret, Supplier<Function<JsonNode, ObjectNode>> handlers) {
        try (socket) {
            socket.startHandshake();
            InputStream input = new BufferedInputStream(PeerConnection.limitedInput(socket.getInputStream()));
            OutputStream output = socket.getOutputStream();
            String first = PeerConnection.readLine(input, 4096);
            if (first == null) return;
            JsonNode auth = SyncProtocol.mapper().readTree(first);
            boolean allowed = auth != null && "auth".equals(auth.path("op").asText()) && auth.path("id").asInt(-1) == 0
                    && auth.path("token").isTextual()
                    && MessageDigest.isEqual(secret, auth.path("token").asText().getBytes(StandardCharsets.UTF_8));
            send(output, allowed ? SyncProtocol.ok(0) : SyncProtocol.error(0, "Authentication rejected."));
            if (!allowed) return;
            socket.setSoTimeout(60_000);
            // No inventory scans/handler construction until authentication has succeeded.
            Function<JsonNode, ObjectNode> handler = handlers.get();
            for (int count = 0; count < 10_000; count++) {
                String line = PeerConnection.readLine(input); if (line == null) return;
                JsonNode request = SyncProtocol.mapper().readTree(line);
                if (request == null || !request.isObject() || !request.path("id").isIntegralNumber() || !request.path("id").canConvertToInt()) return;
                send(output, handler.apply(request));
            }
        } catch (Exception ignored) {
            // Never log credentials, payloads, or backend output on a network parsing failure.
        } finally { clients.remove(socket); }
    }
    private static void send(OutputStream output, ObjectNode response) throws IOException {
        byte[] bytes = (SyncProtocol.toLine(response) + "\n").getBytes(StandardCharsets.UTF_8);
        if (bytes.length > PeerConnection.MAX_LINE) throw new IOException("Peer response too large.");
        output.write(bytes); output.flush();
    }
    @Override public void close() {
        closed = true;
        try { server.close(); } catch (IOException ignored) { }
        for (SSLSocket client : clients) try { client.close(); } catch (IOException ignored) { }
        worker.shutdownNow();
    }
}
