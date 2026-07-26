/*
 * Copyright 2025 Kompile Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.kompile.staging.transfer;

import ai.kompile.staging.archive.ArchiveImporter;
import ai.kompile.staging.auth.AuthProviderChain;
import ai.kompile.staging.config.StagingAssetLimits;
import ai.kompile.staging.http.SafeHttpTransport;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ArchiveDownloaderTest {

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
    void downloadsThroughPinnedTransportAndKeepsSignedQuery() throws Exception {
        byte[] archive = "archive-body".getBytes(StandardCharsets.UTF_8);
        AtomicReference<String> query = new AtomicReference<>();
        HttpServer server = server();
        server.createContext("/model.karch", exchange -> {
            query.set(exchange.getRequestURI().getRawQuery());
            send(exchange, 200, archive);
        });
        server.start();

        ArchiveDownloader downloader = downloader(Map.of(), false, new StagingAssetLimits());
        Path destination = tempDirectory.resolve("model.karch");
        String url = url(server, "/model.karch?X-Amz-Signature=secret");

        ArchiveDownloader.DownloadResult result = downloader.download(
                url, options(destination, false));

        assertTrue(result.isSuccess(), result.getErrorMessage());
        assertEquals("X-Amz-Signature=secret", query.get());
        assertArrayEquals(archive, Files.readAllBytes(destination));
    }

    @Test
    void stripsAuthorizationWhenRedirectChangesOrigin() throws Exception {
        AtomicReference<String> forwardedAuthorization = new AtomicReference<>();
        HttpServer target = server();
        target.createContext("/target.karch", exchange -> {
            forwardedAuthorization.set(
                    exchange.getRequestHeaders().getFirst("Authorization"));
            send(exchange, 200, "ok".getBytes(StandardCharsets.UTF_8));
        });
        target.start();

        HttpServer source = server();
        source.createContext("/source.karch", exchange -> {
            exchange.getResponseHeaders().add(
                    "Location",
                    url(target, "/target.karch?download-token=secret"));
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        source.start();

        ArchiveDownloader downloader = downloader(
                Map.of("Authorization", "Bearer top-secret"),
                false,
                new StagingAssetLimits());
        Path destination = tempDirectory.resolve("redirected.karch");

        ArchiveDownloader.DownloadResult result = downloader.download(
                url(source, "/source.karch"), options(destination, false));

        assertTrue(result.isSuccess(), result.getErrorMessage());
        assertNull(forwardedAuthorization.get());
        assertEquals("ok", Files.readString(destination));
    }

    @Test
    void resumesOnlyFromMatchingContentRange() throws Exception {
        byte[] suffix = " world".getBytes(StandardCharsets.UTF_8);
        AtomicReference<String> range = new AtomicReference<>();
        HttpServer server = server();
        server.createContext("/resume.karch", exchange -> {
            range.set(exchange.getRequestHeaders().getFirst("Range"));
            exchange.getResponseHeaders().add("Accept-Ranges", "bytes");
            exchange.getResponseHeaders().add("Content-Range", "bytes 5-10/11");
            send(exchange, 206, suffix);
        });
        server.start();

        Path destination = tempDirectory.resolve("resume.karch");
        Files.writeString(destination, "hello");
        ResumeableDownload state = ResumeableDownload.create(
                url(server, "/resume.karch"), destination);
        state.setBytesDownloaded(5);
        state.setTotalBytes(11);
        state.setSupportsResume(true);
        state.save();

        ArchiveDownloader downloader = downloader(Map.of(), true, new StagingAssetLimits());
        ArchiveDownloader.DownloadResult result = downloader.download(
                url(server, "/resume.karch"), options(destination, true));

        assertTrue(result.isSuccess(), result.getErrorMessage());
        assertEquals("bytes=5-", range.get());
        assertEquals(11, result.getBytesDownloaded());
        assertEquals("hello world", Files.readString(destination));
    }

    @Test
    void rejectsMismatchedResumeContentRange() throws Exception {
        HttpServer server = server();
        server.createContext("/resume.karch", exchange -> {
            exchange.getResponseHeaders().add("Content-Range", "bytes 4-9/10");
            send(exchange, 206, " world".getBytes(StandardCharsets.UTF_8));
        });
        server.start();

        Path destination = tempDirectory.resolve("resume-invalid.karch");
        Files.writeString(destination, "hello");
        ResumeableDownload state = ResumeableDownload.create(
                url(server, "/resume.karch"), destination);
        state.setBytesDownloaded(5);
        state.setTotalBytes(11);
        state.setSupportsResume(true);
        state.save();

        ArchiveDownloader downloader = downloader(Map.of(), true, new StagingAssetLimits());
        ArchiveDownloader.DownloadResult result = downloader.download(
                url(server, "/resume.karch"), options(destination, true));

        assertFalse(result.isSuccess());
        assertTrue(result.getErrorMessage().contains("requested byte range"));
        assertEquals("hello", Files.readString(destination));
    }

    @Test
    void rejectsContentLengthAboveConfiguredLimit() throws Exception {
        HttpServer server = server();
        server.createContext("/large.karch", exchange ->
                send(exchange, 200, new byte[32]));
        server.start();

        StagingAssetLimits limits = new StagingAssetLimits();
        limits.setTotalBytes(16);
        ArchiveDownloader downloader = downloader(Map.of(), false, limits);
        Path destination = tempDirectory.resolve("large.karch");

        ArchiveDownloader.DownloadResult result = downloader.download(
                url(server, "/large.karch"), options(destination, false));

        assertFalse(result.isSuccess());
        assertTrue(result.getErrorMessage().contains("byte limit"));
        assertFalse(Files.exists(destination));
    }

    private ArchiveDownloader downloader(
            Map<String, String> authHeaders,
            boolean resumeEnabled,
            StagingAssetLimits limits) {
        AuthProviderChain authProviderChain = mock(AuthProviderChain.class);
        when(authProviderChain.getAuthHeaders(anyString())).thenReturn(authHeaders);
        SafeHttpTransport transport = SafeHttpTransport.loopbackForTests();
        transports.add(transport);
        ArchiveDownloader downloader = new ArchiveDownloader(
                authProviderChain,
                mock(ArchiveImporter.class),
                limits,
                transport);
        ReflectionTestUtils.setField(downloader, "resumeEnabled", resumeEnabled);
        return downloader;
    }

    private HttpServer server() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        servers.add(server);
        return server;
    }

    private static ArchiveDownloader.DownloadOptions options(
            Path destination,
            boolean allowResume) {
        return ArchiveDownloader.DownloadOptions.builder()
                .destinationPath(destination)
                .allowResume(allowResume)
                .verifyChecksum(false)
                .build();
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
