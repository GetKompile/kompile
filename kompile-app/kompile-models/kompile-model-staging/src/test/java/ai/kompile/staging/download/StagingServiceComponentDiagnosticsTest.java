package ai.kompile.staging.download;

import ai.kompile.core.staging.StagingModelInfo;
import ai.kompile.core.staging.StagingStatus;
import ai.kompile.modelmanager.registry.ModelType;
import ai.kompile.modelmanager.registry.RegistryService;
import ai.kompile.staging.config.StagingAssetLimits;
import ai.kompile.staging.conversion.ConversionArtifact;
import ai.kompile.staging.conversion.ConversionResult;
import ai.kompile.staging.conversion.ConversionService;
import ai.kompile.staging.diagnostics.ImportDiagnosticCode;
import ai.kompile.staging.diagnostics.ImportDiagnosticEvent;
import ai.kompile.staging.diagnostics.ImportDiagnosticJournal;
import ai.kompile.staging.diagnostics.ImportPhase;
import ai.kompile.staging.optimization.OptimizationService;
import ai.kompile.staging.staging.StagingService;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StagingServiceComponentDiagnosticsTest {

    @TempDir
    Path tempDir;

    @Test
    void stagesRepositoryFreeComponentBundleAndJournalsEveryPipelinePhase() throws Exception {
        HttpServer server = componentServer();
        try {
            server.start();
            ImportDiagnosticJournal journal = new ImportDiagnosticJournal(100);
            ConversionService conversion = successfulConversion();
            StagingService service = service(server, conversion, journal);

            StagingModelInfo result = service.stageModel(componentRequest(
                    "component-service-chat",
                    server));

            assertEquals(StagingStatus.COMPLETED, result.getStatus(), result.getError());
            assertTrue(Files.isRegularFile(
                    tempDir.resolve(".staging/verified/component-service-chat/model.sdz")));
            List<ImportDiagnosticEvent> events = service.getImportDiagnostics(100);
            assertTrue(events.stream().anyMatch(event -> event.phase() == ImportPhase.RESOLVE));
            assertTrue(events.stream().anyMatch(event -> event.phase() == ImportPhase.SELECT));
            assertTrue(events.stream().anyMatch(event -> event.phase() == ImportPhase.DOWNLOAD));
            assertTrue(events.stream().anyMatch(event -> event.phase() == ImportPhase.VALIDATE));
            assertTrue(events.stream().anyMatch(event -> event.phase() == ImportPhase.COMPILE));
            assertTrue(events.stream().anyMatch(event -> event.phase() == ImportPhase.CACHE));
            assertTrue(events.stream().anyMatch(
                    event -> event.code() == ImportDiagnosticCode.IMPORT_COMPLETE));
            assertTrue(events.stream().allMatch(
                    event -> !event.toString().contains("?token=")));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void recordsCompileFailureWithoutLeakingComponentCredentialsOrStack() throws Exception {
        HttpServer server = componentServer();
        try {
            server.start();
            ImportDiagnosticJournal journal = new ImportDiagnosticJournal(100);
            ConversionService conversion = mock(ConversionService.class);
            when(conversion.convert(any(Path.class), any(Path.class), anyString(),
                    any(StagingCancellation.class)))
                    .thenReturn(ConversionResult.failure(
                            "compiler failed for https://user:secret@example.test/model?token=hf_bad_secret"));
            StagingService service = service(server, conversion, journal);

            StagingModelInfo result = service.stageModel(componentRequest(
                    "component-compile-failure",
                    server));

            assertEquals(StagingStatus.FAILED, result.getStatus());
            ImportDiagnosticEvent failure = service.getImportDiagnostics(100).stream()
                    .filter(event -> event.code() == ImportDiagnosticCode.COMPILE_FAILED)
                    .findFirst()
                    .orElseThrow();
            String view = failure.toString() + result.getError();
            assertFalse(view.contains("user:secret"));
            assertFalse(view.contains("?token="));
            assertFalse(view.contains("hf_bad_secret"));
            assertFalse(failure.details().containsKey("stackTrace"));
        } finally {
            server.stop(0);
        }
    }

    private StagingService service(
            HttpServer server,
            ConversionService conversion,
            ImportDiagnosticJournal journal) {
        StagingAssetLimits limits = new StagingAssetLimits();
        URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
        ComponentUrlDownloader downloader =
                new ComponentUrlDownloader(new HuggingFaceDownloader(base, limits, true));
        return new StagingService(
                new RegistryService(tempDir),
                conversion,
                List.of(downloader),
                mock(OptimizationService.class),
                null,
                limits,
                journal);
    }

    private ConversionService successfulConversion() throws Exception {
        ConversionService conversion = mock(ConversionService.class);
        when(conversion.convert(any(Path.class), any(Path.class), anyString(),
                any(StagingCancellation.class))).thenAnswer(invocation -> {
            Path output = invocation.getArgument(1);
            Files.writeString(output, "canonical-sdz");
            ConversionArtifact artifact = ConversionArtifact.canonicalSdz(output);
            return ConversionResult.success(artifact, "sha256:test", 1, 1, 1L);
        });
        when(conversion.validate(any(Path.class)))
                .thenReturn(ConversionService.ValidationResult.success(1, 1));
        return conversion;
    }

    private DownloadRequest componentRequest(String modelId, HttpServer server) {
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        return DownloadRequest.builder()
                .modelId(modelId)
                .source(ComponentUrlDownloader.SOURCE)
                .modelType(ModelType.LLM_GGML)
                .textAssetUrls(TextModelAssetUrlMap.builder()
                        .model(base + "/chat-Q4_K_M.gguf")
                        .tokenizer(base + "/tokenizer.json")
                        .tokenizerConfig(base + "/tokenizer_config.json")
                        .modelConfig(base + "/config.json")
                        .build())
                .build();
    }

    private HttpServer componentServer() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            if (path.endsWith(".gguf")) {
                respond(exchange, 200, "GGUF-model");
            } else if (path.endsWith("tokenizer.json")) {
                respond(exchange, 200,
                        "{\"model\":{\"type\":\"WordLevel\"},\"padding\":\""
                                + "x".repeat(160) + "\"}");
            } else if (path.endsWith("tokenizer_config.json")) {
                respond(exchange, 200, "{\"chat_template\":\"{{ messages }}\"}");
            } else if (path.endsWith("config.json")) {
                respond(exchange, 200, "{\"model_type\":\"test\"}");
            } else {
                respond(exchange, 404, "missing");
            }
        });
        return server;
    }

    private static void respond(HttpExchange exchange, int status, String body)
            throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
