package ai.kompile.staging.web;

import ai.kompile.modelmanager.registry.ModelType;
import ai.kompile.modelmanager.registry.RegistryService;
import ai.kompile.staging.archive.ArchiveModelManager;
import ai.kompile.staging.catalog.CatalogModel;
import ai.kompile.staging.catalog.CatalogService;
import ai.kompile.staging.config.ModelSourceConfiguration;
import ai.kompile.staging.config.StagingSettingsService;
import ai.kompile.staging.download.DownloadRequest;
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
import org.springframework.web.server.ResponseStatusException;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
        assertEquals("int8-per-channel", forwarded.getQuantizationProfile());
        assertEquals("Tensor_G3", forwarded.getTargetSoc());
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
    void downloadStagedOutput_hidesIncompleteOutput() {
        when(stagingService.getStagedOutput("pending"))
                .thenReturn(Optional.empty());

        assertEquals(
                HttpStatus.NOT_FOUND,
                controller.downloadStagedOutput("pending").getStatusCode());
    }

    private ModelType resolve(CatalogModel model) throws Exception {
        return (ModelType) resolveModelType.invoke(controller, model);
    }
}
