package ai.kompile.staging.web;

import ai.kompile.modelmanager.registry.ModelType;
import ai.kompile.modelmanager.registry.RegistryService;
import ai.kompile.staging.archive.ArchiveModelManager;
import ai.kompile.staging.catalog.CatalogModel;
import ai.kompile.staging.catalog.CatalogService;
import ai.kompile.staging.config.ModelSourceConfiguration;
import ai.kompile.staging.config.StagingSettingsService;
import ai.kompile.staging.export.ExportService;
import ai.kompile.staging.export.ImportService;
import ai.kompile.staging.staging.StagingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StagingControllerTest {

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

    private ModelType resolve(CatalogModel model) throws Exception {
        return (ModelType) resolveModelType.invoke(controller, model);
    }
}
