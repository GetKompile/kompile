package ai.kompile.app.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Nd4jEnvironmentConfig")
class Nd4jEnvironmentConfigTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Nested @DisplayName("Defaults")
    class Defaults {
        @Test void hasAllCoreDefaults() {
            var cfg = Nd4jEnvironmentConfig.defaults();
            assertEquals(4, cfg.maxThreads());
            assertEquals(4, cfg.maxMasterThreads());
            assertFalse(cfg.debug());
            assertFalse(cfg.verbose());
            assertFalse(cfg.profiling());
            assertTrue(cfg.enableBlas());
            assertTrue(cfg.helpersAllowed());
            assertFalse(cfg.leaksDetector());
            assertEquals(8, cfg.tadThreshold());
            assertEquals(8, cfg.elementwiseThreshold());
        }

        @Test void lifecycleTrackingDefaults() {
            var cfg = Nd4jEnvironmentConfig.defaults();
            assertFalse(cfg.lifecycleTracking());
            assertFalse(cfg.trackViews());
            assertFalse(cfg.trackDeletions());
            assertFalse(cfg.snapshotFiles());
            assertFalse(cfg.trackOperations());
            assertEquals(16, cfg.stackDepth());
            assertEquals(120, cfg.reportInterval());
            assertEquals(1000, cfg.maxDeletionHistory());
        }

        @Test void trackerDefaults() {
            var cfg = Nd4jEnvironmentConfig.defaults();
            assertFalse(cfg.ndArrayTracking());
            assertFalse(cfg.dataBufferTracking());
            assertFalse(cfg.tadCacheTracking());
            assertFalse(cfg.shapeCacheTracking());
            assertFalse(cfg.opContextTracking());
        }

        @Test void blasDefaults() {
            var cfg = Nd4jEnvironmentConfig.defaults();
            assertTrue(cfg.blasSerializationEnabled());
            assertEquals(1, cfg.openBlasThreads());
            assertEquals(4, cfg.ompNumThreads());
        }

        @Test void javacppDefaults() {
            var cfg = Nd4jEnvironmentConfig.defaults();
            assertTrue(cfg.javacppLoggerDebug());
            assertTrue(cfg.javacppPathsFirst());
        }

        @Test void advancedDebugDefaults() {
            var cfg = Nd4jEnvironmentConfig.defaults();
            assertFalse(cfg.funcTracePrintAllocate());
            assertFalse(cfg.funcTracePrintDeallocate());
            assertFalse(cfg.funcTracePrintJavaOnly());
            assertFalse(cfg.logNativeNDArrayCreation());
            assertFalse(cfg.logNDArrayEvents());
            assertFalse(cfg.truncateNDArrayLogStrings());
            assertFalse(cfg.checkInputChange());
            assertFalse(cfg.checkOutputChange());
            assertFalse(cfg.trackWorkspaceOpenClose());
            assertTrue(cfg.deleteShapeInfo());
            assertTrue(cfg.deletePrimary());
            assertTrue(cfg.deleteSpecial());
            assertFalse(cfg.variableTracingEnabled());
        }

        @Test void memoryLimitsZeroByDefault() {
            var cfg = Nd4jEnvironmentConfig.defaults();
            assertEquals(0L, cfg.maxPrimaryMemory());
            assertEquals(0L, cfg.maxSpecialMemory());
            assertEquals(0L, cfg.maxDeviceMemory());
        }

        @Test void cudaDefaultsNull() {
            var cfg = Nd4jEnvironmentConfig.defaults();
            assertNull(cfg.cudaCurrentDevice());
            assertNull(cfg.cudaMemoryPinned());
            assertNull(cfg.cudaMaxBlocks());
        }

        @Test void tritonDefaultsNull() {
            var cfg = Nd4jEnvironmentConfig.defaults();
            assertNull(cfg.tritonBuildThreads());
            assertNull(cfg.tritonCacheEnabled());
            assertNull(cfg.tritonVerbose());
        }

        @Test void optimizerDefaults() {
            var cfg = Nd4jEnvironmentConfig.defaults();
            assertTrue(cfg.optimizerEnabled());
            assertTrue(cfg.optimizerFp16());
            assertFalse(cfg.dspNoFreeze());
            assertFalse(cfg.dspNoNativeDecode());
            assertFalse(cfg.tritonSkipKernels());
            assertFalse(cfg.tritonTf32());
            assertFalse(cfg.cublasDisableWorkspace());
            assertFalse(cfg.opTiming());
        }
    }

    @Nested @DisplayName("Builder")
    class BuilderTests {
        @Test void partialBuild() {
            var cfg = Nd4jEnvironmentConfig.builder()
                    .maxThreads(8)
                    .debug(true)
                    .build();
            assertEquals(8, cfg.maxThreads());
            assertTrue(cfg.debug());
            // Non-set fields are null
            assertNull(cfg.maxMasterThreads());
            assertNull(cfg.verbose());
        }

        @Test void allCoreFields() {
            var cfg = Nd4jEnvironmentConfig.builder()
                    .maxThreads(16)
                    .maxMasterThreads(16)
                    .debug(true)
                    .verbose(true)
                    .profiling(true)
                    .enableBlas(false)
                    .helpersAllowed(false)
                    .leaksDetector(true)
                    .lifecycleTracking(true)
                    .trackViews(true)
                    .stackDepth(32)
                    .ompNumThreads(8)
                    .openBlasThreads(4)
                    .build();

            assertEquals(16, cfg.maxThreads());
            assertTrue(cfg.debug());
            assertTrue(cfg.verbose());
            assertTrue(cfg.profiling());
            assertFalse(cfg.enableBlas());
            assertTrue(cfg.leaksDetector());
            assertTrue(cfg.lifecycleTracking());
            assertEquals(32, cfg.stackDepth());
            assertEquals(8, cfg.ompNumThreads());
            assertEquals(4, cfg.openBlasThreads());
        }
    }

    @Nested @DisplayName("Merge")
    class Merge {
        @Test void nullMergeReturnsSelf() {
            var cfg = Nd4jEnvironmentConfig.defaults();
            assertSame(cfg, cfg.merge(null));
        }

        @Test void partialOverride() {
            var base = Nd4jEnvironmentConfig.defaults();
            var override = Nd4jEnvironmentConfig.builder()
                    .maxThreads(16)
                    .debug(true)
                    .ompNumThreads(12)
                    .build();

            var merged = base.merge(override);

            // Overridden fields
            assertEquals(16, merged.maxThreads());
            assertTrue(merged.debug());
            assertEquals(12, merged.ompNumThreads());

            // Non-overridden fields retain defaults
            assertEquals(4, merged.maxMasterThreads());
            assertFalse(merged.verbose());
            assertFalse(merged.profiling());
            assertTrue(merged.enableBlas());
            assertEquals(1, merged.openBlasThreads());
        }

        @Test void fullOverride() {
            var base = Nd4jEnvironmentConfig.defaults();
            var override = Nd4jEnvironmentConfig.builder()
                    .maxThreads(8)
                    .maxMasterThreads(8)
                    .debug(true)
                    .verbose(true)
                    .profiling(true)
                    .lifecycleTracking(true)
                    .trackViews(true)
                    .trackDeletions(true)
                    .snapshotFiles(true)
                    .trackOperations(true)
                    .stackDepth(64)
                    .ompNumThreads(8)
                    .openBlasThreads(4)
                    .build();

            var merged = base.merge(override);
            assertEquals(8, merged.maxThreads());
            assertTrue(merged.debug());
            assertTrue(merged.lifecycleTracking());
            assertTrue(merged.trackViews());
            assertEquals(64, merged.stackDepth());
        }

        @Test void mergePreservesNullCudaDefaults() {
            var base = Nd4jEnvironmentConfig.defaults();
            var override = Nd4jEnvironmentConfig.builder().maxThreads(8).build();
            var merged = base.merge(override);
            // CUDA fields should remain null (from defaults)
            assertNull(merged.cudaCurrentDevice());
            assertNull(merged.cudaMemoryPinned());
        }

        @Test void mergeCudaOverride() {
            var base = Nd4jEnvironmentConfig.defaults();
            var override = Nd4jEnvironmentConfig.builder()
                    .cudaCurrentDevice(1)
                    .cudaMemoryPinned(true)
                    .cudaTensorCoreEnabled(true)
                    .build();
            var merged = base.merge(override);
            assertEquals(1, merged.cudaCurrentDevice());
            assertTrue(merged.cudaMemoryPinned());
            assertTrue(merged.cudaTensorCoreEnabled());
        }

        @Test void mergeTritonOverride() {
            var base = Nd4jEnvironmentConfig.defaults();
            var override = Nd4jEnvironmentConfig.builder()
                    .tritonBuildThreads(8)
                    .tritonCacheEnabled(true)
                    .tritonNumWarps(4)
                    .build();
            var merged = base.merge(override);
            assertEquals(8, merged.tritonBuildThreads());
            assertTrue(merged.tritonCacheEnabled());
            assertEquals(4, merged.tritonNumWarps());
        }

        @Test void mergeOptimizerOverride() {
            var base = Nd4jEnvironmentConfig.defaults();
            var override = Nd4jEnvironmentConfig.builder()
                    .optimizerEnabled(false)
                    .optimizerFp16(false)
                    .opTiming(true)
                    .build();
            var merged = base.merge(override);
            assertFalse(merged.optimizerEnabled());
            assertFalse(merged.optimizerFp16());
            assertTrue(merged.opTiming());
        }
    }

    @Nested @DisplayName("JSON serialization")
    class JsonSerialization {
        @Test void defaultsRoundTrip() throws Exception {
            var original = Nd4jEnvironmentConfig.defaults();
            String json = MAPPER.writeValueAsString(original);
            assertNotNull(json);
            assertTrue(json.contains("maxThreads"));
            assertTrue(json.contains("ompNumThreads"));

            var restored = MAPPER.readValue(json, Nd4jEnvironmentConfig.class);
            assertEquals(original.maxThreads(), restored.maxThreads());
            assertEquals(original.maxMasterThreads(), restored.maxMasterThreads());
            assertEquals(original.debug(), restored.debug());
            assertEquals(original.verbose(), restored.verbose());
            assertEquals(original.ompNumThreads(), restored.ompNumThreads());
            assertEquals(original.openBlasThreads(), restored.openBlasThreads());
            assertEquals(original.lifecycleTracking(), restored.lifecycleTracking());
            assertEquals(original.stackDepth(), restored.stackDepth());
            assertEquals(original.enableBlas(), restored.enableBlas());
        }

        @Test void partialConfigRoundTrip() throws Exception {
            var original = Nd4jEnvironmentConfig.builder()
                    .maxThreads(8)
                    .debug(true)
                    .ompNumThreads(12)
                    .openBlasThreads(4)
                    .lifecycleTracking(true)
                    .build();

            String json = MAPPER.writeValueAsString(original);
            var restored = MAPPER.readValue(json, Nd4jEnvironmentConfig.class);
            assertEquals(8, restored.maxThreads());
            assertTrue(restored.debug());
            assertEquals(12, restored.ompNumThreads());
            assertEquals(4, restored.openBlasThreads());
            assertTrue(restored.lifecycleTracking());
        }

        @Test void ignoresUnknownProperties() throws Exception {
            String json = "{\"maxThreads\":8,\"unknownField\":\"value\",\"anotherUnknown\":42}";
            var cfg = MAPPER.readValue(json, Nd4jEnvironmentConfig.class);
            assertEquals(8, cfg.maxThreads());
        }

        @Test void cudaFieldsSurviveRoundTrip() throws Exception {
            var original = Nd4jEnvironmentConfig.builder()
                    .cudaCurrentDevice(2)
                    .cudaMemoryPinned(true)
                    .cudaMaxBlocks(1024)
                    .cudaTensorCoreEnabled(true)
                    .build();
            String json = MAPPER.writeValueAsString(original);
            var restored = MAPPER.readValue(json, Nd4jEnvironmentConfig.class);
            assertEquals(2, restored.cudaCurrentDevice());
            assertTrue(restored.cudaMemoryPinned());
            assertEquals(1024, restored.cudaMaxBlocks());
            assertTrue(restored.cudaTensorCoreEnabled());
        }

        @Test void tritonFieldsSurviveRoundTrip() throws Exception {
            var original = Nd4jEnvironmentConfig.builder()
                    .tritonBuildThreads(4)
                    .tritonCacheEnabled(true)
                    .tritonNumWarps(8)
                    .tritonNumStages(3)
                    .build();
            String json = MAPPER.writeValueAsString(original);
            var restored = MAPPER.readValue(json, Nd4jEnvironmentConfig.class);
            assertEquals(4, restored.tritonBuildThreads());
            assertTrue(restored.tritonCacheEnabled());
            assertEquals(8, restored.tritonNumWarps());
        }

        @Test void optimizerFieldsSurviveRoundTrip() throws Exception {
            var original = Nd4jEnvironmentConfig.builder()
                    .optimizerEnabled(false)
                    .optimizerFp16(false)
                    .dspNoFreeze(true)
                    .dspNoNativeDecode(true)
                    .tritonSkipKernels(true)
                    .opTiming(true)
                    .build();
            String json = MAPPER.writeValueAsString(original);
            var restored = MAPPER.readValue(json, Nd4jEnvironmentConfig.class);
            assertFalse(restored.optimizerEnabled());
            assertFalse(restored.optimizerFp16());
            assertTrue(restored.dspNoFreeze());
            assertTrue(restored.dspNoNativeDecode());
            assertTrue(restored.tritonSkipKernels());
            assertTrue(restored.opTiming());
        }
    }

    @Nested @DisplayName("Subprocess propagation")
    class SubprocessPropagation {

        @Test @DisplayName("Config survives serialize -> SubprocessArgs -> deserialize cycle")
        void configThroughSubprocessArgs() throws Exception {
            // Simulate parent capturing config
            var parentConfig = Nd4jEnvironmentConfig.builder()
                    .maxThreads(8)
                    .maxMasterThreads(8)
                    .debug(false)
                    .verbose(false)
                    .ompNumThreads(6)
                    .openBlasThreads(2)
                    .lifecycleTracking(true)
                    .trackViews(true)
                    .stackDepth(32)
                    .enableBlas(true)
                    .helpersAllowed(true)
                    .build();

            // Parent serializes config to JSON
            String nd4jConfigJson = MAPPER.writeValueAsString(
                    Nd4jEnvironmentConfig.defaults().merge(parentConfig));

            // Config JSON is embedded in subprocess args
            var subArgs = ai.kompile.app.subprocess.SubprocessArgs.builder()
                    .taskId("test-task")
                    .filePath("/test.pdf")
                    .callbackBaseUrl("http://localhost:8080")
                    .nd4jConfigJson(nd4jConfigJson)
                    .build();

            // Subprocess args round-trip through JSON (file write/read)
            var restoredArgs = ai.kompile.app.subprocess.SubprocessArgs.fromJson(subArgs.toJson());

            // Subprocess deserializes config
            var subprocessConfig = MAPPER.readValue(
                    restoredArgs.nd4jConfigJson(), Nd4jEnvironmentConfig.class);

            // Verify all settings survived the propagation chain
            assertEquals(8, subprocessConfig.maxThreads());
            assertEquals(8, subprocessConfig.maxMasterThreads());
            assertFalse(subprocessConfig.debug());
            assertEquals(6, subprocessConfig.ompNumThreads());
            assertEquals(2, subprocessConfig.openBlasThreads());
            assertTrue(subprocessConfig.lifecycleTracking());
            assertTrue(subprocessConfig.trackViews());
            assertEquals(32, subprocessConfig.stackDepth());
            assertTrue(subprocessConfig.enableBlas());
        }

        @Test @DisplayName("Config propagates through VectorPopulationSubprocessArgs")
        void configThroughVectorPopulationArgs() throws Exception {
            var config = Nd4jEnvironmentConfig.defaults().merge(
                    Nd4jEnvironmentConfig.builder()
                            .maxThreads(4)
                            .ompNumThreads(4)
                            .lifecycleTracking(false)
                            .build()
            );
            String configJson = MAPPER.writeValueAsString(config);

            var args = ai.kompile.app.subprocess.VectorPopulationSubprocessArgs.builder()
                    .taskId("vp-task")
                    .keywordIndexPath("/kw")
                    .vectorIndexPath("/vec")
                    .callbackBaseUrl("http://localhost:8080")
                    .nd4jConfigJson(configJson)
                    .build();

            // Simulate file round-trip
            var tempFile = java.nio.file.Files.createTempFile("vp-args-", ".json");
            try {
                args.toFile(tempFile);
                var restored = ai.kompile.app.subprocess.VectorPopulationSubprocessArgs.fromFile(tempFile);
                var restoredConfig = MAPPER.readValue(restored.nd4jConfigJson(), Nd4jEnvironmentConfig.class);

                assertEquals(4, restoredConfig.maxThreads());
                assertEquals(4, restoredConfig.ompNumThreads());
                assertFalse(restoredConfig.lifecycleTracking());
            } finally {
                java.nio.file.Files.deleteIfExists(tempFile);
            }
        }

        @Test @DisplayName("Config propagates through VlmTestSubprocessArgs")
        void configThroughVlmTestArgs() throws Exception {
            var config = Nd4jEnvironmentConfig.builder()
                    .maxThreads(16)
                    .optimizerEnabled(true)
                    .optimizerFp16(true)
                    .tritonSkipKernels(false)
                    .build();
            String configJson = MAPPER.writeValueAsString(
                    Nd4jEnvironmentConfig.defaults().merge(config));

            var args = ai.kompile.app.subprocess.VlmTestSubprocessArgs.builder()
                    .taskId("vlm-task")
                    .filePath("/test.pdf")
                    .modelId("gotocr2")
                    .callbackBaseUrl("http://localhost:8080")
                    .nd4jConfigJson(configJson)
                    .build();

            var tempFile = java.nio.file.Files.createTempFile("vlm-args-", ".json");
            try {
                args.toFile(tempFile);
                var restored = ai.kompile.app.subprocess.VlmTestSubprocessArgs.fromFile(tempFile);
                var restoredConfig = MAPPER.readValue(restored.nd4jConfigJson(), Nd4jEnvironmentConfig.class);

                assertEquals(16, restoredConfig.maxThreads());
                assertTrue(restoredConfig.optimizerEnabled());
                assertTrue(restoredConfig.optimizerFp16());
                assertFalse(restoredConfig.tritonSkipKernels());
            } finally {
                java.nio.file.Files.deleteIfExists(tempFile);
            }
        }

        @Test @DisplayName("Null nd4jConfigJson is handled gracefully")
        void nullConfigJson() {
            var args = ai.kompile.app.subprocess.SubprocessArgs.builder()
                    .taskId("t").filePath("/f").callbackBaseUrl("http://x")
                    .nd4jConfigJson(null)
                    .build();
            assertNull(args.nd4jConfigJson());
        }

        @Test @DisplayName("Empty nd4jConfigJson is handled gracefully")
        void emptyConfigJson() {
            var args = ai.kompile.app.subprocess.SubprocessArgs.builder()
                    .taskId("t").filePath("/f").callbackBaseUrl("http://x")
                    .nd4jConfigJson("")
                    .build();
            assertEquals("", args.nd4jConfigJson());
        }

        @Test @DisplayName("Merged config preserves all fields through propagation")
        void mergedConfigPreservesAllFields() throws Exception {
            // Start with defaults
            var defaults = Nd4jEnvironmentConfig.defaults();

            // Apply a partial override (simulating UI config change)
            var override = Nd4jEnvironmentConfig.builder()
                    .maxThreads(12)
                    .ompNumThreads(12)
                    .openBlasThreads(6)
                    .debug(true)
                    .lifecycleTracking(true)
                    .trackOperations(true)
                    .stackDepth(64)
                    .optimizerFp16(false)
                    .build();

            var merged = defaults.merge(override);
            String json = MAPPER.writeValueAsString(merged);
            var restored = MAPPER.readValue(json, Nd4jEnvironmentConfig.class);

            // Overridden
            assertEquals(12, restored.maxThreads());
            assertEquals(12, restored.ompNumThreads());
            assertEquals(6, restored.openBlasThreads());
            assertTrue(restored.debug());
            assertTrue(restored.lifecycleTracking());
            assertTrue(restored.trackOperations());
            assertEquals(64, restored.stackDepth());
            assertFalse(restored.optimizerFp16());

            // Preserved from defaults
            assertEquals(4, restored.maxMasterThreads());
            assertFalse(restored.verbose());
            assertFalse(restored.profiling());
            assertTrue(restored.enableBlas());
            assertTrue(restored.helpersAllowed());
            assertTrue(restored.deleteShapeInfo());
            assertTrue(restored.deletePrimary());
            assertTrue(restored.deleteSpecial());
            assertTrue(restored.optimizerEnabled());
        }
    }

    // Note: captureFromEnvironment tests are skipped - requires live ND4J backend
}
