package ai.kompile.staging.download;

import ai.kompile.modelmanager.registry.ModelType;
import ai.kompile.staging.config.StagingAssetLimits;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ComponentUrlDownloaderTest {

    @TempDir
    Path tempDir;

    @Test
    void downloadsCompleteRepositoryFreeBundleWithHardenedTransfer() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        HttpServer server = startServer();
        try {
            server.createContext("/", exchange -> {
                authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
                String path = exchange.getRequestURI().getPath();
                if (path.endsWith(".gguf")) {
                    respond(exchange, 200, "GGUF-model");
                } else if (path.endsWith("tokenizer.json")) {
                    respond(exchange, 200, "{\"model\":{}}");
                } else if (path.endsWith("tokenizer_config.json")) {
                    respond(exchange, 200, "{\"chat_template\":\"{{ messages }}\"}");
                } else if (path.endsWith("config.json")) {
                    respond(exchange, 200, "{\"model_type\":\"test\"}");
                } else if (path.endsWith("generation_config.json")) {
                    respond(exchange, 200, "{\"max_new_tokens\":32}");
                } else {
                    respond(exchange, 404, "missing");
                }
            });
            server.start();

            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            DownloadRequest request = request(TextModelAssetUrlMap.builder()
                    .model(base + "/chat-Q4_K_M.gguf")
                    .tokenizer(base + "/tokenizer.json")
                    .tokenizerConfig(base + "/tokenizer_config.json")
                    .modelConfig(base + "/config.json")
                    .generationConfig(base + "/generation_config.json")
                    .build());
            ComponentUrlDownloader downloader = new ComponentUrlDownloader(testAssetDownloader(server));

            DownloadResult result = downloader.download(request, tempDir.resolve("complete"));

            assertTrue(downloader.canHandle(ComponentUrlDownloader.SOURCE));
            assertFalse(downloader.canHandle("huggingface"));
            assertTrue(result.isSuccess(), result.getErrorMessage());
            assertEquals("gguf", request.getFormat());
            assertNull(request.getRepository());
            assertNull(authorization.get(), "component mode must never send authorization");
            assertEquals(ComponentUrlDownloader.SOURCE, request.getSourceReference());
            assertEquals(base + "/chat-Q4_K_M.gguf",
                    request.effectiveSourceAssetProvenance().get(TextModelAssetMap.MODEL));
            assertTrue(Files.isRegularFile(result.getDownloadedFiles().get(TextModelAssetMap.MODEL)));
            assertFalse(result.getChecksum().isBlank());
            try (Stream<Path> stagedFiles = Files.list(tempDir.resolve("complete"))) {
                assertTrue(stagedFiles.noneMatch(
                        path -> path.getFileName().toString().contains(".part-")));
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsIncompleteMixedOrSignedComponentInputsBeforeTransfer() throws Exception {
        HttpServer server = startServer();
        try {
            server.start();
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            ComponentUrlDownloader downloader = new ComponentUrlDownloader(testAssetDownloader(server));

            DownloadResult incomplete = downloader.download(
                    request(TextModelAssetUrlMap.builder()
                            .model(base + "/chat.gguf")
                            .tokenizer(base + "/tokenizer.json")
                            .build()),
                    tempDir.resolve("incomplete"));
            assertFalse(incomplete.isSuccess());
            assertTrue(incomplete.getErrorMessage().contains("tokenizer_config.json"));

            DownloadRequest mixed = request(TextModelAssetUrlMap.builder()
                    .model(base + "/chat.gguf")
                    .tokenizer(base + "/tokenizer.json")
                    .tokenizerConfig(base + "/tokenizer_config.json")
                    .modelConfig(base + "/config.json")
                    .build());
            mixed.setRepository("owner/repo");
            DownloadResult mixedResult = downloader.download(mixed, tempDir.resolve("mixed"));
            assertFalse(mixedResult.isSuccess());
            assertTrue(mixedResult.getErrorMessage().contains("repository-free"));

            DownloadRequest signed = request(TextModelAssetUrlMap.builder()
                    .model(base + "/chat.gguf?token=secret")
                    .tokenizer(base + "/tokenizer.json")
                    .tokenizerConfig(base + "/tokenizer_config.json")
                    .modelConfig(base + "/config.json")
                    .build());
            DownloadResult signedResult = downloader.download(signed, tempDir.resolve("signed"));
            assertFalse(signedResult.isSuccess());
            assertTrue(signedResult.getErrorMessage().contains("query parameters"));
        } finally {
            server.stop(0);
        }
    }

    private DownloadRequest request(TextModelAssetUrlMap urls) {
        return DownloadRequest.builder()
                .modelId("component-mobile-chat")
                .source(ComponentUrlDownloader.SOURCE)
                .modelType(ModelType.LLM_GGML)
                .outputFormat("kproject")
                .textAssetUrls(urls)
                .build();
    }

    private HuggingFaceDownloader testAssetDownloader(HttpServer server) {
        URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
        return new HuggingFaceDownloader(base, new StagingAssetLimits(), true);
    }

    private static HttpServer startServer() throws Exception {
        return HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    }

    private static void respond(HttpExchange exchange, int status, String body)
            throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
