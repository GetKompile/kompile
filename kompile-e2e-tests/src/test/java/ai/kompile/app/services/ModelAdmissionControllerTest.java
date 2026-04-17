package ai.kompile.app.services;

import ai.kompile.app.config.ModelAdmissionConfig;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.support.GenericApplicationContext;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ModelAdmissionController")
class ModelAdmissionControllerTest {

    @TempDir Path tempDir;

    private ModelAdmissionConfigService createConfigService() {
        return new ModelAdmissionConfigService(tempDir.toString());
    }

    private ModelAdmissionController createController() {
        return new ModelAdmissionController(
                createConfigService(), null, null, new GenericApplicationContext());
    }

    private ModelAdmissionController createController(ModelAdmissionConfigService configService) {
        return new ModelAdmissionController(
                configService, null, null, new GenericApplicationContext());
    }

    @Nested @DisplayName("Initialization")
    class Initialization {
        @Test void createsWithDefaults() {
            var controller = createController();
            var status = controller.getStatus();
            assertNotNull(status);
            assertTrue((Boolean) status.get("enabled"));
            assertEquals(0, status.get("activeLoads"));
            assertEquals(0, status.get("totalModelsLoaded"));
        }
    }

    @Nested @DisplayName("Admission decisions")
    class AdmissionDecisions {
        @Test void admitsWhenUnderLimit() {
            var controller = createController();
            var decision = controller.canAdmit("my-model");
            assertTrue(decision.admitted());
        }

        @Test void admitsWhenDisabled() throws Exception {
            var configService = createConfigService();
            configService.saveConfiguration(ModelAdmissionConfig.builder().enabled(false).build());
            var controller = createController(configService);

            var decision = controller.canAdmit("any-model");
            assertTrue(decision.admitted());
            assertTrue(decision.reason().contains("disabled"));
        }

        @Test void rejectsWhenMaxModelsReachedAndNoneToEvict() throws Exception {
            var configService = createConfigService();
            configService.saveConfiguration(ModelAdmissionConfig.builder()
                    .enabled(true).maxLoadedModels(1).build());
            var controller = createController(configService);

            // Load first model
            controller.requestLoad("model-1").get();

            // Second should suggest evicting the first
            var decision = controller.canAdmit("model-2");
            assertTrue(decision.admitted());
            assertTrue(decision.modelsToEvict().contains("model-1"));
        }
    }

    @Nested @DisplayName("Model lifecycle")
    class ModelLifecycle {
        @Test void loadAndUnload() throws Exception {
            var controller = createController();
            controller.requestLoad("test-model").get();

            var models = controller.getLoadedModels();
            assertEquals(1, models.size());
            assertEquals("test-model", models.get(0).modelId());
            assertEquals(ModelAdmissionController.ModelState.GPU_HOT, models.get(0).state());

            controller.unload("test-model");
            assertTrue(controller.getLoadedModels().isEmpty());
        }

        @Test void demoteAndPromote() throws Exception {
            var controller = createController();
            controller.requestLoad("test-model").get();

            controller.demoteToCpu("test-model");
            var models = controller.getLoadedModels();
            assertEquals(ModelAdmissionController.ModelState.CPU_WARM, models.get(0).state());

            controller.promoteToGpu("test-model");
            models = controller.getLoadedModels();
            assertEquals(ModelAdmissionController.ModelState.GPU_HOT, models.get(0).state());
        }

        @Test void touchUpdatesLastUsedAt() throws Exception {
            var controller = createController();
            controller.requestLoad("test-model").get();

            var before = controller.getLoadedModels().get(0).lastUsedAt();
            Thread.sleep(10);
            controller.touch("test-model");
            var after = controller.getLoadedModels().get(0).lastUsedAt();

            assertTrue(after.isAfter(before));
        }
    }

    @Nested @DisplayName("Memory estimation")
    class MemoryEstimation {
        @Test void usesDefaultEstimate() {
            var controller = createController();
            long estimate = controller.estimateMemory("unknown-model");
            assertEquals(2L * 1024 * 1024 * 1024, estimate); // 2GB default
        }

        @Test void usesRegisteredEstimate() {
            var controller = createController();
            controller.registerMemoryEstimate("small-model", 500_000_000L);
            assertEquals(500_000_000L, controller.estimateMemory("small-model"));
        }
    }

    @Nested @DisplayName("Status")
    class Status {
        @Test void statusShowsStateCounts() throws Exception {
            var controller = createController();
            controller.requestLoad("m1").get();
            controller.requestLoad("m2").get();
            controller.demoteToCpu("m2");

            var status = controller.getStatus();
            @SuppressWarnings("unchecked")
            var counts = (Map<String, Long>) status.get("stateCounts");
            assertEquals(1L, counts.get("GPU_HOT"));
            assertEquals(1L, counts.get("CPU_WARM"));
        }
    }
}
