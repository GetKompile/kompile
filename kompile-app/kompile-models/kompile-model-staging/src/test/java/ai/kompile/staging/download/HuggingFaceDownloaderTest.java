package ai.kompile.staging.download;

import ai.kompile.modelmanager.registry.ModelType;
import ai.kompile.staging.config.StagingAssetLimits;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HuggingFaceDownloaderTest {

    @TempDir
    Path tempDir;

    @Test
    void ggufTokenizerSidecarsAreOptionalButModelIsMandatory() {
        DownloadRequest request = DownloadRequest.builder()
                .modelId("lfm2.5-1.2b-instruct")
                .source("huggingface")
                .repository("LiquidAI/LFM2.5-1.2B-Instruct-GGUF")
                .format("gguf")
                .build();

        assertTrue(HuggingFaceDownloader.isOptionalAuxiliaryFile(request, "vocab"));
        assertTrue(HuggingFaceDownloader.isOptionalAuxiliaryFile(request, "tokenizer"));
        assertTrue(HuggingFaceDownloader.isOptionalAuxiliaryFile(request, "tokenizer_config"));
        assertFalse(HuggingFaceDownloader.isOptionalAuxiliaryFile(request, "model"));
    }

    @Test
    void kprojectDownloadAddsEveryRunnableTextModelCompanion() {
        DownloadRequest request = DownloadRequest.builder()
                .modelId("qwen-mobile")
                .source("huggingface")
                .repository("Qwen/Qwen2.5-0.5B-Instruct")
                .format("onnx")
                .modelType(ModelType.LLM_GGML)
                .outputFormat("kproject")
                .textAssets(TextModelAssetMap.builder()
                        .model("exports/model.onnx")
                        .build())
                .build();

        Map<String, String> files = HuggingFaceDownloader.filesForRequest(request);

        assertEquals("exports/model.onnx", files.get("model"));
        assertEquals("tokenizer.json", files.get("tokenizer"));
        assertEquals("tokenizer_config.json", files.get("tokenizer_config"));
        assertEquals("config.json", files.get("model_config"));
        assertEquals("generation_config.json", files.get("generation_config"));
        assertEquals("special_tokens_map.json", files.get("special_tokens_map"));
        assertEquals("added_tokens.json", files.get("added_tokens"));
        assertEquals("chat_template.jinja", files.get("chat_template"));
        assertFalse(HuggingFaceDownloader.isOptionalAuxiliaryFile(request, "tokenizer"));
        assertTrue(HuggingFaceDownloader.isOptionalAuxiliaryFile(request, "tokenizer_config"));
        assertFalse(HuggingFaceDownloader.isOptionalAuxiliaryFile(request, "model_config"));
        assertTrue(HuggingFaceDownloader.isOptionalAuxiliaryFile(request, "generation_config"));
        assertTrue(HuggingFaceDownloader.isOptionalAuxiliaryFile(request, "special_tokens_map"));
        assertTrue(HuggingFaceDownloader.isOptionalAuxiliaryFile(request, "added_tokens"));
        assertTrue(HuggingFaceDownloader.isOptionalAuxiliaryFile(request, "chat_template"));
    }

    @Test
    void runnableProjectNeverGuessesTheModelFile() {
        DownloadRequest request = DownloadRequest.builder()
                .modelId("qwen-mobile")
                .source("huggingface")
                .repository("Qwen/Qwen2.5-0.5B-Instruct-GGUF")
                .format("gguf")
                .modelType(ModelType.LLM_GGML)
                .outputFormat("kproject")
                .build();

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> HuggingFaceDownloader.filesForRequest(request));
        assertTrue(failure.getMessage().contains("model"));
    }

    @Test
    void encoderTokenizerSidecarsRemainMandatory() {
        DownloadRequest request = DownloadRequest.builder()
                .modelId("bge-base-en-v1.5")
                .source("huggingface")
                .repository("BAAI/bge-base-en-v1.5")
                .format("onnx")
                .build();

        assertFalse(HuggingFaceDownloader.isOptionalAuxiliaryFile(request, "vocab"));
        assertFalse(HuggingFaceDownloader.isOptionalAuxiliaryFile(request, "tokenizer_config"));
    }

    @Test
    void sameOriginRedirectRetainsAuthorization() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        HttpServer server = startServer();
        try {
            server.createContext("/owner/repo/resolve/main/model.bin", exchange -> {
                exchange.getResponseHeaders().add("Location", "/asset");
                exchange.sendResponseHeaders(302, -1);
                exchange.close();
            });
            server.createContext("/asset", exchange -> {
                authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
                respond(exchange, 200, "model");
            });
            server.start();

            HuggingFaceDownloader downloader = testDownloader(server);
            DownloadResult result = downloader.download(
                    simpleRequest("token"), tempDir.resolve("same-origin"));

            assertTrue(result.isSuccess(), result.getErrorMessage());
            assertEquals("Bearer token", authorization.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void crossOriginRedirectStripsAuthorization() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        HttpServer origin = startServer();
        HttpServer storage = startServer();
        try {
            storage.createContext("/blob", exchange -> {
                authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
                respond(exchange, 200, "model");
            });
            storage.start();
            origin.createContext("/owner/repo/resolve/main/model.bin", exchange -> {
                exchange.getResponseHeaders().add(
                        "Location",
                        "http://127.0.0.1:" + storage.getAddress().getPort() + "/blob");
                exchange.sendResponseHeaders(302, -1);
                exchange.close();
            });
            origin.start();

            DownloadResult result = testDownloader(origin).download(
                    simpleRequest("secret"), tempDir.resolve("cross-origin"));

            assertTrue(result.isSuccess(), result.getErrorMessage());
            assertNull(authorization.get(), "credentials must not cross origins");
        } finally {
            origin.stop(0);
            storage.stop(0);
        }
    }

    @Test
    void standaloneChatTemplateSatisfiesTokenizerConfigurationAlternative() throws Exception {
        HttpServer server = startServer();
        try {
            server.createContext("/", exchange -> {
                String path = exchange.getRequestURI().getPath();
                if (path.equals("/api/models/owner/repo/revision/main")) {
                    respond(exchange, 200,
                            "{\"sha\":\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\",\"siblings\":["
                                    + "{\"rfilename\":\"model.bin\",\"size\":5},"
                                    + "{\"rfilename\":\"tokenizer.json\",\"size\":12},"
                                    + "{\"rfilename\":\"chat_template.jinja\",\"size\":14},"
                                    + "{\"rfilename\":\"config.json\",\"size\":21}]}");
                } else if (path.endsWith("/model.bin")) {
                    respond(exchange, 200, "model");
                } else if (path.endsWith("/tokenizer.json")) {
                    respond(exchange, 200, "{\"model\":{}}");
                } else if (path.endsWith("/chat_template.jinja")) {
                    respond(exchange, 200, "{{ messages }}");
                } else if (path.endsWith("/config.json")) {
                    respond(exchange, 200, "{\"model_type\":\"test\"}");
                } else {
                    respond(exchange, 404, "missing");
                }
            });
            server.start();
            DownloadRequest request = DownloadRequest.builder()
                    .modelId("chat-template-only")
                    .source("huggingface")
                    .repository("owner/repo")
                    .format("gguf")
                    .modelType(ModelType.LLM_GGML)
                    .outputFormat("kproject")
                    .textAssets(TextModelAssetMap.builder()
                            .model("model.bin")
                            .tokenizer("tokenizer.json")
                            .chatTemplate("chat_template.jinja")
                            .modelConfig("config.json")
                            .build())
                    .build();

            DownloadResult result = testDownloader(server).download(
                    request, tempDir.resolve("chat-template"));

            assertTrue(result.isSuccess(), result.getErrorMessage());
            assertTrue(result.getDownloadedFiles().containsKey(TextModelAssetMap.CHAT_TEMPLATE));
            assertFalse(result.getDownloadedFiles().containsKey(TextModelAssetMap.TOKENIZER_CONFIG));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void discoveryPinsCommitAndFindsRunnableGgufCompanions() throws Exception {
        AtomicReference<String> apiAuthorization = new AtomicReference<>();
        HttpServer server = startServer();
        try {
            server.createContext("/api/models/owner/repo/revision/main", exchange -> {
                apiAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
                respond(exchange, 200,
                        "{\"sha\":\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\",\"siblings\":["
                                + "{\"rfilename\":\"models/chat-Q4_K_M.gguf\",\"lfs\":{\"size\":1234}},"
                                + "{\"rfilename\":\"tokenizer.json\",\"size\":12},"
                                + "{\"rfilename\":\"tokenizer_config.json\",\"size\":14},"
                                + "{\"rfilename\":\"config.json\",\"size\":21}]}");
            });
            server.start();

            HuggingFaceDiscovery discovery = testDownloader(server).discover(
                    "https://huggingface.co/owner/repo", null, "secret");

            assertEquals("owner/repo", discovery.getRepository());
            assertEquals("main", discovery.getRequestedRevision());
            assertEquals("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", discovery.getResolvedRevision());
            assertFalse(discovery.isRequiresModelSelection());
            assertEquals("models/chat-Q4_K_M.gguf", discovery.getDiscoveredAssets().getModel());
            assertEquals("tokenizer.json", discovery.getDiscoveredAssets().getTokenizer());
            assertEquals("tokenizer_config.json",
                    discovery.getDiscoveredAssets().getTokenizerConfig());
            assertEquals("config.json", discovery.getDiscoveredAssets().getModelConfig());
            assertEquals(1, discovery.getModelCandidates().size());
            assertEquals(1234L, discovery.getModelCandidates().get(0).getSize());
            assertEquals("gguf", discovery.getModelCandidates().get(0).getFormat());
            assertEquals("Q4_K_M", discovery.getModelCandidates().get(0).getQuantizationHint());
            assertEquals("Bearer secret", apiAuthorization.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void multipleGgufsRequireExplicitSelection() throws Exception {
        HttpServer server = startServer();
        try {
            server.createContext("/api/models/owner/repo/revision/main", exchange ->
                    respond(exchange, 200,
                            "{\"sha\":\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\",\"siblings\":["
                                    + "{\"rfilename\":\"chat-Q4_K_M.gguf\",\"size\":100},"
                                    + "{\"rfilename\":\"chat-Q8_0.gguf\",\"size\":200},"
                                    + "{\"rfilename\":\"tokenizer.json\",\"size\":12},"
                                    + "{\"rfilename\":\"tokenizer_config.json\",\"size\":14},"
                                    + "{\"rfilename\":\"config.json\",\"size\":21}]}"));
            server.start();

            HuggingFaceDownloader downloader = testDownloader(server);
            HuggingFaceDiscovery discovery = downloader.discover("owner/repo", null, null);
            assertTrue(discovery.isRequiresModelSelection());
            assertNull(discovery.getDiscoveredAssets().getModel());
            assertEquals(2, discovery.getModelCandidates().size());

            DownloadRequest request = DownloadRequest.builder()
                    .modelId("ambiguous-mobile-chat")
                    .source("huggingface")
                    .repository("owner/repo")
                    .modelType(ModelType.LLM_GGML)
                    .outputFormat("kproject")
                    .build();
            DownloadResult result = downloader.download(
                    request, tempDir.resolve("ambiguous-model"));

            assertFalse(result.isSuccess());
            assertTrue(result.getErrorMessage().contains("select one exact model path"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void explicitComponentUrlNeverReceivesHuggingFaceBearerToken() throws Exception {
        AtomicReference<String> apiAuthorization = new AtomicReference<>();
        AtomicReference<String> storageAuthorization = new AtomicReference<>();
        HttpServer origin = startServer();
        HttpServer storage = startServer();
        try {
            storage.createContext("/selected-Q4_K_M.gguf", exchange -> {
                storageAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
                respond(exchange, 200, "model");
            });
            storage.start();

            origin.createContext("/", exchange -> {
                String path = exchange.getRequestURI().getPath();
                if (path.equals("/api/models/owner/repo/revision/main")) {
                    apiAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
                    respond(exchange, 200,
                            "{\"sha\":\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\",\"siblings\":["
                                    + "{\"rfilename\":\"tokenizer.json\",\"size\":12},"
                                    + "{\"rfilename\":\"tokenizer_config.json\",\"size\":14},"
                                    + "{\"rfilename\":\"config.json\",\"size\":21}]}");
                } else if (path.endsWith("/tokenizer.json")) {
                    respond(exchange, 200, "{\"model\":{}}");
                } else if (path.endsWith("/tokenizer_config.json")) {
                    respond(exchange, 200, "{\"chat_template\":\"{{ messages }}\"}");
                } else if (path.endsWith("/config.json")) {
                    respond(exchange, 200, "{\"model_type\":\"test\"}");
                } else {
                    respond(exchange, 404, "missing");
                }
            });
            origin.start();

            String modelUrl = "http://127.0.0.1:" + storage.getAddress().getPort()
                    + "/selected-Q4_K_M.gguf";
            DownloadRequest request = DownloadRequest.builder()
                    .modelId("component-url-mobile-chat")
                    .source("huggingface")
                    .repository("owner/repo")
                    .authToken("secret")
                    .modelType(ModelType.LLM_GGML)
                    .outputFormat("kproject")
                    .textAssetUrls(TextModelAssetUrlMap.builder().model(modelUrl).build())
                    .build();

            DownloadResult result = testDownloader(origin).download(
                    request, tempDir.resolve("component-url"));

            assertTrue(result.isSuccess(), result.getErrorMessage());
            assertEquals("Bearer secret", apiAuthorization.get());
            assertNull(storageAuthorization.get(), "credentials must stay on the HF origin");
            assertEquals("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", request.getRevision());
            assertEquals("main", request.getRequestedRevision());
            assertEquals("gguf", request.getFormat());
            assertEquals(modelUrl,
                    request.effectiveSourceAssetProvenance().get(TextModelAssetMap.MODEL));
        } finally {
            origin.stop(0);
            storage.stop(0);
        }
    }

    @Test
    void repositoryMustRemainOwnerAndRepositoryOnly() {
        assertThrows(IllegalArgumentException.class,
                () -> HuggingFaceDownloader.requireRepositoryId(
                        "https://huggingface.co/owner/repo?token=secret"));
        assertThrows(IllegalArgumentException.class,
                () -> HuggingFaceDownloader.requireRepositoryId("owner/repo/extra"));
        assertEquals("owner/repo", HuggingFaceDownloader.requireRepositoryId("owner/repo"));
    }

    private DownloadRequest simpleRequest(String token) {
        return DownloadRequest.builder()
                .modelId("download-test")
                .source("huggingface")
                .repository("owner/repo")
                .format("bin")
                .authToken(token)
                .files(Map.of(TextModelAssetMap.MODEL, "model.bin"))
                .build();
    }

    private HuggingFaceDownloader testDownloader(HttpServer server) {
        URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
        return new HuggingFaceDownloader(base, new StagingAssetLimits(), true);
    }

    private static HttpServer startServer() throws Exception {
        return HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    }

    private static void respond(HttpExchange exchange, int status, String body) throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
