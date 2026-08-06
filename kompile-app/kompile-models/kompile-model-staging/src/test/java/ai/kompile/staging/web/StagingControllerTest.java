package ai.kompile.staging.web;

import ai.kompile.core.staging.StagingModelInfo;
import ai.kompile.core.staging.StagingStatus;
import ai.kompile.modelmanager.registry.ModelType;
import ai.kompile.modelmanager.registry.RegistryService;
import ai.kompile.staging.archive.ArchiveModelManager;
import ai.kompile.staging.catalog.CatalogModel;
import ai.kompile.staging.catalog.CatalogService;
import ai.kompile.staging.config.ModelSourceConfiguration;
import ai.kompile.staging.config.StagingSettings;
import ai.kompile.staging.config.StagingSettingsService;
import ai.kompile.staging.download.DownloadRequest;
import ai.kompile.staging.download.ComponentUrlDownloader;
import ai.kompile.staging.download.TextModelAssetMap;
import ai.kompile.staging.download.TextModelAssetUrlMap;
import ai.kompile.staging.export.ExportService;
import ai.kompile.staging.export.ImportService;
import ai.kompile.staging.staging.StagingService;
import ai.kompile.staging.web.dto.StageModelRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StagingControllerTest {

    @TempDir
    Path temp;

    @Mock
    private RegistryService registryService;
    @Mock
    private StagingService stagingService;
    @Mock
    private ExportService exportService;
    @Mock
    private ImportService importService;
    @Mock
    private CatalogService catalogService;
    @Mock
    private ArchiveModelManager archiveModelManager;
    @Mock
    private ModelSourceConfiguration modelSourceConfig;
    @Mock
    private StagingSettingsService stagingSettingsService;

    private StagingController controller;
    private Method resolveModelType;

    @BeforeEach
    void setUp() throws Exception {
        controller = new StagingController(
                registryService,
                stagingService,
                exportService,
                importService,
                catalogService,
                archiveModelManager,
                modelSourceConfig,
                stagingSettingsService);
        resolveModelType = StagingController.class.getDeclaredMethod("resolveModelType", CatalogModel.class);
        resolveModelType.setAccessible(true);
    }

    @Test
    void resolveModelType_routesLlmCatalogModelsToLlmGgml() throws Exception {
        CatalogModel llm = CatalogModel.builder()
                .id("lfm2.5-1.2b-instruct")
                .format("gguf")
                .build();
        when(catalogService.getLlm()).thenReturn(List.of(llm));

        assertEquals(ModelType.LLM_GGML, resolve(llm));
    }

    @Test
    void resolveModelType_routesGgufFormatToLlmGgmlWhenCatalogBucketIsMissing() throws Exception {
        CatalogModel llm = CatalogModel.builder()
                .id("local-gguf")
                .format("gguf")
                .build();
        when(catalogService.getLlm()).thenReturn(List.of());
        when(catalogService.getVlm()).thenReturn(List.of());
        when(catalogService.getCrossEncoders()).thenReturn(List.of());
        when(catalogService.getEncoders()).thenReturn(List.of());

        assertEquals(ModelType.LLM_GGML, resolve(llm));
    }

    @Test
    void stageFromCatalog_forwardsPinnedRunnableCompanionUrls() {
        CatalogModel llm = CatalogModel.builder()
                .id("catalog-llm")
                .source("huggingface")
                .repo("owner/quantized-model")
                .format("gguf")
                .files(Map.of("model", "model-q4.gguf"))
                .assetUrls(Map.of(
                        "tokenizer", "https://huggingface.co/owner/base/resolve/abc/tokenizer.json",
                        "tokenizer_config", "https://huggingface.co/owner/base/resolve/abc/tokenizer_config.json",
                        "model_config", "https://huggingface.co/owner/base/resolve/abc/config.json"))
                .build();
        when(catalogService.getModel("catalog-llm")).thenReturn(Optional.of(llm));

        ResponseEntity<?> response = controller.stageFromCatalog("catalog-llm", false);

        ArgumentCaptor<DownloadRequest> captured = ArgumentCaptor.forClass(DownloadRequest.class);
        verify(stagingService).stageModelAsync(captured.capture());
        DownloadRequest forwarded = captured.getValue();
        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        assertEquals("model-q4.gguf", forwarded.getFiles().get("model"));
        assertEquals(
                "https://huggingface.co/owner/base/resolve/abc/tokenizer.json",
                forwarded.getTextAssetUrls().getTokenizer());
        assertEquals(
                "https://huggingface.co/owner/base/resolve/abc/tokenizer_config.json",
                forwarded.getTextAssetUrls().getTokenizerConfig());
        assertEquals(
                "https://huggingface.co/owner/base/resolve/abc/config.json",
                forwarded.getTextAssetUrls().getModelConfig());
    }

    @Test
    void stageFromCatalog_autoPromotesCompletedModels() {
        CatalogModel llm = CatalogModel.builder()
                .id("catalog-llm")
                .source("huggingface")
                .repo("owner/quantized-model")
                .format("gguf")
                .files(Map.of("model", "model-q4.gguf"))
                .build();
        StagingModelInfo completed = StagingModelInfo.builder()
                .modelId("catalog-llm")
                .status(StagingStatus.COMPLETED)
                .build();
        when(catalogService.getModel("catalog-llm")).thenReturn(Optional.of(llm));
        when(stagingService.stageModelAsync(any()))
                .thenReturn(CompletableFuture.completedFuture(completed));

        ResponseEntity<?> response = controller.stageFromCatalog("catalog-llm", true);

        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        verify(stagingService).promoteModel("catalog-llm", null);
    }

    @Test
    void stageModel_acceptsLegacyAliasesAndForwardsCanonicalSdxRequest() throws Exception {
        StageModelRequest request = new ObjectMapper().readValue(
                """
                {
                  "source": "huggingface",
                  "repository": "Qwen/Qwen2.5-0.5B-Instruct",
                  "modelId": "qwen-mobile",
                  "modelType": "llm_ggml",
                  "format": "gguf",
                  "token": "secret",
                  "revision": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                  "textAssets": {
                    "model": "qwen2.5-q4.gguf",
                    "tokenizer": "tokenizer.json",
                    "tokenizerConfig": "tokenizer_config.json",
                    "modelConfig": "config.json"
                  },
                  "outputFormat": "kproject",
                  "targetProfile": "pixel-8a",
                  "quantizationProfile": "int8",
                  "targetSoc": "Tensor_G3"
                }
                """,
                StageModelRequest.class);

        controller.stageModel(request);

        ArgumentCaptor<DownloadRequest> captured =
                ArgumentCaptor.forClass(DownloadRequest.class);
        verify(stagingService).stageModelAsync(captured.capture());
        DownloadRequest forwarded = captured.getValue();
        assertEquals(ModelType.LLM_GGML, forwarded.getModelType());
        assertEquals("secret", forwarded.getAuthToken());
        assertEquals("kproject", forwarded.getOutputFormat());
        assertEquals(
                "android-arm64-nnapi-accelerator",
                forwarded.getTargetProfile());
        assertEquals("int8", forwarded.getQuantizationProfile());
        assertEquals("Tensor_G3", forwarded.getTargetSoc());
        assertEquals("qwen2.5-q4.gguf", forwarded.getTextAssets().getModel());
        assertEquals(
                "tokenizer_config.json",
                forwarded.getTextAssets().getTokenizerConfig());
    }

    @Test
    void stageModel_forwardsMutableHuggingFaceRevisionForDownloaderPinning() {
        StageModelRequest request = StageModelRequest.builder()
                .source("huggingface")
                .repository("https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF")
                .modelId("mutable")
                .type("llm_ggml")
                .format("gguf")
                .revision("main")
                .textAssets(completeAssets("model.gguf"))
                .outputFormat("kproject")
                .targetProfile("pixel-8a")
                .targetSoc("Tensor_G3")
                .build();

        controller.stageModel(request);

        ArgumentCaptor<DownloadRequest> captured =
                ArgumentCaptor.forClass(DownloadRequest.class);
        verify(stagingService).stageModelAsync(captured.capture());
        DownloadRequest forwarded = captured.getValue();
        assertEquals(
                "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF",
                forwarded.getRepository());
        assertEquals("main", forwarded.getRevision());
        assertEquals("model.gguf", forwarded.getTextAssets().getModel());
    }

    @Test
    void stageModel_normalizesTargetedModelAsCompleteDownloadableSdz() {
        StageModelRequest request = StageModelRequest.builder()
                .source("huggingface")
                .repository("Qwen/Qwen2.5-0.5B-Instruct-GGUF")
                .modelId("mobile-sdz")
                .type("llm_ggml")
                .format("gguf")
                .revision("main")
                .textAssets(completeAssets("model.gguf"))
                .outputFormat("model")
                .targetProfile("pixel-8a")
                .quantizationProfile("int8")
                .build();

        controller.stageModel(request);

        ArgumentCaptor<DownloadRequest> captured =
                ArgumentCaptor.forClass(DownloadRequest.class);
        verify(stagingService).stageModelAsync(captured.capture());
        DownloadRequest forwarded = captured.getValue();
        assertEquals("model", forwarded.getOutputFormat());
        assertEquals(
                "android-arm64-nnapi-accelerator",
                forwarded.getTargetProfile());
        assertEquals("Tensor_G3", forwarded.getTargetSoc());
        assertEquals("int8", forwarded.getQuantizationProfile());
        assertEquals("tokenizer.json", forwarded.getTextAssets().getTokenizer());
        assertEquals(
                "tokenizer_config.json",
                forwarded.getTextAssets().getTokenizerConfig());
    }

    @Test
    void stageModel_rejectsTargetedLocalModelMissingRunnableChatAssets() {
        StageModelRequest request = StageModelRequest.builder()
                .source("local")
                .repository("incomplete-upload")
                .modelId("incomplete-mobile-sdz")
                .type("llm_ggml")
                .format("gguf")
                .outputFormat("model")
                .targetProfile("pixel-8a")
                .build();

        ResponseStatusException failure = assertThrows(
                ResponseStatusException.class,
                () -> controller.stageModel(request));

        assertEquals(HttpStatus.BAD_REQUEST, failure.getStatusCode());
        assertTrue(failure.getReason().contains("tokenizer.json"));
        verify(stagingService, never()).stageModelAsync(any());
    }

    @Test
    void stageModel_acceptsCompleteRepositoryFreeHttpsComponentBundle() {
        StageModelRequest request = componentRequest(completeComponentUrls());

        controller.stageModel(request);

        ArgumentCaptor<DownloadRequest> captured =
                ArgumentCaptor.forClass(DownloadRequest.class);
        verify(stagingService).stageModelAsync(captured.capture());
        DownloadRequest forwarded = captured.getValue();
        assertEquals(ComponentUrlDownloader.SOURCE, forwarded.getSource());
        assertNull(forwarded.getRepository());
        assertEquals(
                "https://models.example/chat.gguf",
                forwarded.getTextAssetUrls().getModel());
        assertEquals("kproject", forwarded.getOutputFormat());
    }

    @Test
    void stageModel_rejectsIncompleteOrMixedHttpsComponentsBeforeAsyncWork() {
        StageModelRequest incomplete = componentRequest(TextModelAssetUrlMap.builder()
                .model("https://models.example/chat.gguf")
                .tokenizer("https://models.example/tokenizer.json")
                .build());
        ResponseStatusException incompleteFailure = assertThrows(
                ResponseStatusException.class,
                () -> controller.stageModel(incomplete));
        assertEquals(HttpStatus.BAD_REQUEST, incompleteFailure.getStatusCode());
        assertTrue(incompleteFailure.getReason().contains("tokenizer_config.json"));

        StageModelRequest mixed = componentRequest(completeComponentUrls());
        mixed.setRepository("owner/repo");
        ResponseStatusException mixedFailure = assertThrows(
                ResponseStatusException.class,
                () -> controller.stageModel(mixed));
        assertEquals(HttpStatus.BAD_REQUEST, mixedFailure.getStatusCode());
        assertTrue(mixedFailure.getReason().contains("repository-free"));
        verify(stagingService, never()).stageModelAsync(any());
    }

    @Test
    void multipartTextBundleForwardsTokenizerConfigThroughLocalDownloaderContract()
            throws Exception {
        when(stagingService.getStagingDirectory()).thenReturn(temp.resolve("staging"));
        when(stagingService.stageModelAsync(any()))
                .thenReturn(CompletableFuture.completedFuture(null));
        StageModelRequest request = mobileLocalRequest("local-gguf");

        controller.stageTextBundle(
                request,
                part("phone.gguf", "GGUF"),
                part("tokenizer.json", "{\"model\":{}}"),
                part("tokenizer_config.json", "{\"chat_template\":\"{{ messages }}\"}"),
                null,
                null,
                null,
                null,
                part("config.json", "{\"eos_token_id\":2}"),
                null);

        ArgumentCaptor<DownloadRequest> captured =
                ArgumentCaptor.forClass(DownloadRequest.class);
        verify(stagingService).stageModelAsync(captured.capture());
        DownloadRequest forwarded = captured.getValue();
        assertEquals("local", forwarded.getSource());
        assertEquals("model.gguf", forwarded.getTextAssets().getModel());
        assertEquals(
                "tokenizer_config.json",
                forwarded.getTextAssets().getTokenizerConfig());
        assertEquals("config.json", forwarded.getTextAssets().getModelConfig());
    }

    @Test
    void multipartTextBundleAcceptsStandaloneChatTemplateAndAuthoredContract()
            throws Exception {
        when(stagingService.getStagingDirectory()).thenReturn(temp.resolve("staging"));
        when(stagingService.stageModelAsync(any()))
                .thenReturn(CompletableFuture.completedFuture(null));

        controller.stageTextBundle(
                mobileLocalRequest("local-ggml"),
                part("phone.ggml", "GGML"),
                part("tokenizer.json", "{\"model\":{}}"),
                null,
                null,
                null,
                part("chat_template.jinja", "{{ messages }}"),
                null,
                null,
                part("text-generation.json", "{\"formatVersion\":1}"));

        ArgumentCaptor<DownloadRequest> captured =
                ArgumentCaptor.forClass(DownloadRequest.class);
        verify(stagingService).stageModelAsync(captured.capture());
        assertEquals(
                "chat_template.jinja",
                captured.getValue().getTextAssets().getChatTemplate());
        assertEquals(
                "text-generation.json",
                captured.getValue().getTextAssets().getTextGeneration());
    }

    @Test
    void multipartTextBundleRejectsMissingTokenizerConfigurationBeforeAsyncWork()
            throws Exception {
        when(stagingService.getStagingDirectory()).thenReturn(temp.resolve("staging"));

        ResponseStatusException failure = assertThrows(
                ResponseStatusException.class,
                () -> controller.stageTextBundle(
                        mobileLocalRequest("missing-config"),
                        part("phone.gguf", "GGUF"),
                        part("tokenizer.json", "{\"model\":{}}"),
                        null,
                        null,
                        null,
                        null,
                        null,
                        part("config.json", "{}"),
                        null));

        assertEquals(HttpStatus.BAD_REQUEST, failure.getStatusCode());
        assertTrue(failure.getReason().contains("tokenizer_config.json"));
        verify(stagingService, never()).stageModelAsync(any());
    }

    @Test
    void stageModel_rejectsUnknownTargetBeforeStartingAsyncWork() {
        StageModelRequest request = StageModelRequest.builder()
                .source("http")
                .repository("https://example.invalid/model.sdz")
                .modelId("bad-target")
                .type("llm_ggml")
                .format("sdz")
                .outputFormat("kproject")
                .targetProfile("cpu")
                .build();

        ResponseStatusException failure = assertThrows(
                ResponseStatusException.class,
                () -> controller.stageModel(request));

        assertEquals(HttpStatus.BAD_REQUEST, failure.getStatusCode());
    }

    @Test
    void downloadStagedOutput_streamsCompletedKproject() throws Exception {
        Path output = temp.resolve("qwen-mobile.kproject");
        Files.writeString(output, "project");
        when(stagingService.getStagedOutput("qwen-mobile"))
                .thenReturn(Optional.of(output));

        ResponseEntity<Resource> response =
                controller.downloadStagedOutput("qwen-mobile");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(
                "application/vnd.kompile.project+zip",
                response.getHeaders().getContentType().toString());
        assertTrue(response.getHeaders().getFirst("Content-Disposition")
                .contains("qwen-mobile.kproject"));
        assertEquals(Files.size(output), response.getHeaders().getContentLength());
    }

    @Test
    void downloadStagedOutput_streamsCompletedTargetSdz() throws Exception {
        Path output = temp.resolve("qwen-mobile.sdz");
        Files.writeString(output, "complete target model");
        when(stagingService.getStagedOutput("qwen-mobile"))
                .thenReturn(Optional.of(output));

        ResponseEntity<Resource> response =
                controller.downloadStagedOutput("qwen-mobile");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(
                "application/vnd.kompile.sdx+zip",
                response.getHeaders().getContentType().toString());
        assertTrue(response.getHeaders().getFirst("Content-Disposition")
                .contains("qwen-mobile.sdz"));
        assertEquals(Files.size(output), response.getHeaders().getContentLength());
    }

    @Test
    void downloadStagedOutput_hidesIncompleteOutput() {
        when(stagingService.getStagedOutput("pending"))
                .thenReturn(Optional.empty());

        assertEquals(
                HttpStatus.NOT_FOUND,
                controller.downloadStagedOutput("pending").getStatusCode());
    }

    @Test
    void exposesBoundedImportDiagnosticHistoryAndAttemptTimeline() {
        when(stagingService.getImportDiagnostics(17)).thenReturn(List.of());
        when(stagingService.getImportDiagnostics("attempt-123")).thenReturn(List.of());

        assertTrue(controller.getImportDiagnostics(17).isEmpty());
        assertTrue(controller.getImportDiagnostics("attempt-123").isEmpty());

        verify(stagingService).getImportDiagnostics(17);
        verify(stagingService).getImportDiagnostics("attempt-123");
    }

    @Test
    void updateSettingsNormalizesAndPersistsManagedCallbackEndpoint() {
        StagingSettings settings = StagingSettings.defaults();
        settings.setCallbackUrl("http://localhost:18080/");
        when(stagingSettingsService.updateSettings(any(StagingSettings.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        StagingSettings updated = controller.updateSettings(settings);

        assertEquals("http://localhost:18080", updated.getCallbackUrl());
        verify(stagingSettingsService).updateSettings(settings);
    }

    @Test
    void updateSettingsRejectsMalformedCallbackEndpoint() {
        StagingSettings settings = StagingSettings.defaults();
        settings.setCallbackUrl("not-a-url");

        ResponseStatusException error = assertThrows(
                ResponseStatusException.class, () -> controller.updateSettings(settings));

        assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
        verify(stagingSettingsService, never()).updateSettings(any());
    }

    @Test
    void testSettingsCallbackDelegatesToManagedSettingsService() {
        StagingSettingsService.CallbackTestResult expected =
                new StagingSettingsService.CallbackTestResult(true, "Connection successful");
        when(stagingSettingsService.testCallback()).thenReturn(expected);

        assertEquals(expected, controller.testSettingsCallback());
        verify(stagingSettingsService).testCallback();
    }

    private static TextModelAssetMap completeAssets(String model) {
        return TextModelAssetMap.builder()
                .model(model)
                .tokenizer("tokenizer.json")
                .tokenizerConfig("tokenizer_config.json")
                .modelConfig("config.json")
                .build();
    }

    private static TextModelAssetUrlMap completeComponentUrls() {
        return TextModelAssetUrlMap.builder()
                .model("https://models.example/chat.gguf")
                .tokenizer("https://models.example/tokenizer.json")
                .tokenizerConfig("https://models.example/tokenizer_config.json")
                .modelConfig("https://models.example/config.json")
                .build();
    }

    private static StageModelRequest componentRequest(TextModelAssetUrlMap urls) {
        return StageModelRequest.builder()
                .modelId("component-mobile-chat")
                .source(ComponentUrlDownloader.SOURCE)
                .type("llm_ggml")
                .format("gguf")
                .textAssetUrls(urls)
                .outputFormat("kproject")
                .targetProfile("pixel-8a")
                .targetSoc("Tensor_G3")
                .build();
    }

    private static StageModelRequest mobileLocalRequest(String modelId) {
        return StageModelRequest.builder()
                .modelId(modelId)
                .source("local")
                .repository("replaced-by-upload")
                .type("llm_ggml")
                .format(modelId.contains("ggml") ? "ggml" : "gguf")
                .outputFormat("kproject")
                .targetProfile("pixel-8a")
                .quantizationProfile("int8")
                .targetSoc("Tensor_G3")
                .build();
    }

    private static MockMultipartFile part(String name, String content) {
        return new MockMultipartFile(
                name,
                name,
                "application/octet-stream",
                content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private ModelType resolve(CatalogModel model) throws Exception {
        return (ModelType) resolveModelType.invoke(controller, model);
    }
}
