/*
 * Copyright 2025 Kompile Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.kompile.staging.pipeline;

import ai.kompile.staging.config.StagingAssetLimits;
import ai.kompile.staging.http.SafeHttpTransport;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PipelineServiceTest {

    @TempDir
    Path tempDirectory;

    private final List<HttpServer> servers = new ArrayList<>();
    private final List<SafeHttpTransport> transports = new ArrayList<>();

    @AfterEach
    void tearDown() throws IOException {
        for (HttpServer server : servers) {
            server.stop(0);
        }
        for (SafeHttpTransport transport : transports) {
            transport.close();
        }
    }

    @Test
    void downloadsAtomicallyAndPreservesSignedQuery() throws Exception {
        byte[] model = "model-body".getBytes(StandardCharsets.UTF_8);
        AtomicReference<String> query = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        HttpServer server = server();
        server.createContext("/model.gguf", exchange -> {
            query.set(exchange.getRequestURI().getRawQuery());
            authorization.set(
                    exchange.getRequestHeaders().getFirst("Authorization"));
            send(exchange, 200, model);
        });
        server.start();

        PipelineService service = service();
        Path destination = tempDirectory.resolve("model.gguf");
        long downloaded = service.downloadFile(
                url(server, "/model.gguf?X-Amz-Signature=top-secret"),
                destination,
                "hf-secret",
                64);

        assertEquals(model.length, downloaded);
        assertEquals("X-Amz-Signature=top-secret", query.get());
        assertEquals("Bearer hf-secret", authorization.get());
        assertEquals("model-body", Files.readString(destination));
        assertFalse(Files.exists(tempDirectory.resolve("model.gguf.part")));
    }

    @Test
    void stripsAuthorizationAcrossOriginRedirect() throws Exception {
        AtomicReference<String> forwardedAuthorization = new AtomicReference<>();
        HttpServer target = server();
        target.createContext("/model.gguf", exchange -> {
            forwardedAuthorization.set(
                    exchange.getRequestHeaders().getFirst("Authorization"));
            send(exchange, 200, "new-model".getBytes(StandardCharsets.UTF_8));
        });
        target.start();

        HttpServer source = server();
        source.createContext("/model.gguf", exchange -> {
            exchange.getResponseHeaders().add(
                    "Location",
                    url(target, "/model.gguf?download-token=top-secret"));
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        source.start();

        PipelineService service = service();
        Path destination = tempDirectory.resolve("redirected.gguf");
        Files.writeString(destination, "old-model");

        service.downloadFile(
                url(source, "/model.gguf"), destination, "hf-secret", 64);

        assertNull(forwardedAuthorization.get());
        assertEquals("new-model", Files.readString(destination));
    }

    @Test
    void rejectsOversizedAssetWithoutReplacingExistingFile() throws Exception {
        HttpServer server = server();
        server.createContext("/model.gguf", exchange ->
                send(exchange, 200, new byte[32]));
        server.start();

        PipelineService service = service();
        Path destination = tempDirectory.resolve("limited.gguf");
        Files.writeString(destination, "keep-me");

        IOException error = assertThrows(IOException.class, () ->
                service.downloadFile(
                        url(server, "/model.gguf"), destination, null, 16));

        assertTrue(error.getMessage().contains("byte limit"));
        assertEquals("keep-me", Files.readString(destination));
        assertFalse(Files.exists(tempDirectory.resolve("limited.gguf.part")));
    }

    @Test
    void classifiesRepositoryAssetsUsingSharedLimits() {
        StagingAssetLimits limits = new StagingAssetLimits();
        limits.setModelBytes(11);
        limits.setTokenizerBytes(12);
        limits.setConfigBytes(13);

        assertEquals(11, limits.maxBytesForFileName("model-00001-of-00002.safetensors"));
        assertEquals(11, limits.maxBytesForFileName("pytorch_model-00001-of-00002.bin"));
        assertEquals(12, limits.maxBytesForFileName("tokenizer.json"));
        assertEquals(12, limits.maxBytesForFileName("nested/merges.txt"));
        assertEquals(13, limits.maxBytesForFileName("config.json"));
    }

    private PipelineService service() {
        SafeHttpTransport transport = SafeHttpTransport.loopbackForTests();
        transports.add(transport);
        return new PipelineService(
                tempDirectory.resolve("cache").toString(),
                new StagingAssetLimits(),
                transport);
    }

    private HttpServer server() throws IOException {
        HttpServer server = HttpServer.create(
                new InetSocketAddress("127.0.0.1", 0), 0);
        servers.add(server);
        return server;
    }

    private static String url(HttpServer server, String path) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }

    private static void send(HttpExchange exchange, int status, byte[] body)
            throws IOException {
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }
}
