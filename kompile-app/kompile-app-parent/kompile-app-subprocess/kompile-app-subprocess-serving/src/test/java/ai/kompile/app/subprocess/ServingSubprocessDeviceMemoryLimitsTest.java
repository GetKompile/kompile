package ai.kompile.app.subprocess;

import ai.kompile.app.config.Nd4jEnvironmentConfig;
import ai.kompile.app.llm.pipeline.SameDiffLanguageModelImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.nd4j.common.config.ND4JSystemProperties;
import org.nd4j.linalg.factory.Environment;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Startup contract tests without initializing a native backend or loading a model. */
@ResourceLock(Resources.SYSTEM_PROPERTIES)
class ServingSubprocessDeviceMemoryLimitsTest {
    @Test
    void explicitMalformedConfigIsNeverReplacedWithDefaults() throws Exception {
        for (String json : List.of("", " ", "{", "null", "[]", "true", "42", "\"{}\"", "{} {}",
                "{\"maxDeviceMemory\":1.5}", "{\"maxDeviceMemory\":\"1024\"}",
                "{\"maxDeviceMemory\":9223372036854775808}",
                "{\"maxPrimaryMemory\":-1}", "{\"maxSpecialMemory\":-1}", "{\"maxDeviceMemory\":-1}",
                "{\"optimizerEnabled\":\"false\"}", "{\"optimizerEnabled\":0}", "{\"optimizerFp16\":1}")) {
            for (boolean withCaps : List.of(false, true)) {
                var root = SubprocessArgsIo.mapper().createObjectNode().put("nd4jConfigJson", json);
                if (withCaps) root.putArray("deviceMemoryLimitsBytes").add(4096L);
                ServingSubprocessArgs args = SubprocessArgsIo.mapper().treeToValue(root, ServingSubprocessArgs.class);
                assertThrows(IOException.class,
                        () -> ServingSubprocessMain.readNd4jEnvironmentConfig(args.nd4jConfigJson()), json);
            }
        }
    }

    @Test
    void missingConfigUsesDefaultsAndZeroGlobalLimitsRemainValid() throws Exception {
        assertEquals(Nd4jEnvironmentConfig.defaults(), ServingSubprocessMain.readNd4jEnvironmentConfig(null));
        Nd4jEnvironmentConfig config = ServingSubprocessMain.readNd4jEnvironmentConfig(
                "{\"maxPrimaryMemory\":0,\"maxSpecialMemory\":0,\"maxDeviceMemory\":0}");
        assertEquals(Long.valueOf(0), config.maxDeviceMemory());
        Environment environment = mock(Environment.class);
        ServingSubprocessMain.applyNd4jEnvironmentConfig(environment, config);
        verifyNoInteractions(environment);
    }

    @Test
    void environmentConfigRejectionPropagatesInsteadOfServingUncapped() throws Exception {
        Nd4jEnvironmentConfig config = ServingSubprocessMain.readNd4jEnvironmentConfig(
                "{\"maxDeviceMemory\":4096}");
        Environment environment = mock(Environment.class);
        IllegalStateException rejection = new IllegalStateException("config memory limit rejected");
        doThrow(rejection).when(environment).setMaxDeviceMemory(4096L);
        assertSame(rejection, assertThrows(IllegalStateException.class,
                () -> ServingSubprocessMain.applyNd4jEnvironmentConfig(environment, config)));
    }

    @Test
    void jsonOptimizerFlagsReachPropertiesAndModelPreload() throws Exception {
        for (boolean enabled : List.of(false, true)) {
            ServingSubprocessArgs args = optimizerArgs(
                    "{\"optimizerEnabled\":" + enabled + ",\"optimizerFp16\":" + !enabled + "}", null, null);
            assertOptimizerSettings(args, Boolean.toString(enabled), Boolean.toString(!enabled), enabled);
        }
    }

    @Test
    void explicitOptimizerArgumentsOverrideJsonInBothDirections() throws Exception {
        for (boolean enabled : List.of(false, true)) {
            ServingSubprocessArgs args = optimizerArgs(
                    "{\"optimizerEnabled\":" + !enabled + ",\"optimizerFp16\":" + enabled + "}", enabled, !enabled);
            assertOptimizerSettings(args, Boolean.toString(enabled), Boolean.toString(!enabled), enabled);
        }
    }

    @Test
    void absentOptimizerSettingsPreserveExistingPropertiesAndModelDefaults() throws Exception {
        for (String json : Arrays.asList(null, "{}", "{\"optimizerEnabled\":null,\"optimizerFp16\":null}")) {
            assertOptimizerSettings(optimizerArgs(json, null, null), "unchanged", "unchanged", null);
        }
    }

    private static ServingSubprocessArgs optimizerArgs(String json, Boolean enabled, Boolean fp16) throws IOException {
        var root = SubprocessArgsIo.mapper().createObjectNode()
                .put("modelId", "test-model").put("modelPath", "test-model.sdnb")
                .put("tokenizerPath", "tokenizer.json").put("nd4jConfigJson", json)
                .put("optimizerEnabled", enabled).put("optimizerFp16", fp16);
        return SubprocessArgsIo.mapper().treeToValue(root, ServingSubprocessArgs.class);
    }

    private static void assertOptimizerSettings(ServingSubprocessArgs args, String enabled,
                                                String fp16, Boolean modelEnabled) throws Exception {
        String enabledKey = ND4JSystemProperties.OPTIMIZER_ENABLED;
        String fp16Key = ND4JSystemProperties.OPTIMIZER_FP16;
        String oldEnabled = System.getProperty(enabledKey);
        String oldFp16 = System.getProperty(fp16Key);
        try {
            System.setProperty(enabledKey, "unchanged");
            System.setProperty(fp16Key, "unchanged");
            Nd4jEnvironmentConfig config = ServingSubprocessMain.readNd4jEnvironmentConfig(args.nd4jConfigJson());
            ServingSubprocessMain.applyOptimizerProperties(args, config);
            assertEquals(enabled, System.getProperty(enabledKey));
            assertEquals(fp16, System.getProperty(fp16Key));
            SameDiffLanguageModelImpl model = mock(SameDiffLanguageModelImpl.class);
            ServingSubprocessMain.preloadModel(model, args, config);
            verify(model).loadModel(eq("test-model"), eq(Path.of("test-model.sdnb")), eq(Path.of("tokenizer.json")),
                    argThat(options -> modelEnabled == null ? !options.containsKey("graphOptimizerEnabled")
                            : modelEnabled.equals(options.get("graphOptimizerEnabled"))));
        } finally {
            restoreProperty(enabledKey, oldEnabled);
            restoreProperty(fp16Key, oldFp16);
        }
    }

    private static void restoreProperty(String key, String value) {
        if (value == null) System.clearProperty(key);
        else System.setProperty(key, value);
    }

    @Test
    void absentCapsLeaveBackendUntouched() {
        Environment environment = mock(Environment.class);
        ServingSubprocessMain.applyDeviceMemoryLimits(environment, 2, null);
        verifyNoInteractions(environment);
    }

    @Test
    void appliesLogicalDeviceOrderAndPreservesTighterExistingLimit() {
        Environment environment = mock(Environment.class);
        long[] limits = {0, 2_147_483_648L};
        when(environment.getDeviceLimit(anyInt())).thenAnswer(call -> limits[call.getArgument(0, Integer.class)]);
        doAnswer(call -> {
            limits[call.getArgument(0, Integer.class)] = call.getArgument(1, Long.class);
            return null;
        }).when(environment).setDeviceLimit(anyInt(), anyLong());
        when(environment.getDeviceCounter(0)).thenReturn(15_032_385_536L); // exact ceiling is valid

        ServingSubprocessMain.applyDeviceMemoryLimits(environment, 2,
                List.of(15_032_385_536L, 4_294_967_296L));

        assertEquals(15_032_385_536L, limits[0]);
        assertEquals(2_147_483_648L, limits[1]);
        verify(environment).setDeviceLimit(0, 15_032_385_536L);
        verify(environment).setDeviceLimit(1, 2_147_483_648L);
    }

    @Test
    void rejectsWrongDeviceCountOrInvalidCapsBeforeMutation() {
        Environment environment = mock(Environment.class);
        for (List<Long> limits : List.of(List.<Long>of(), List.of(1L), List.of(1L, 2L, 3L),
                List.of(1L, 0L), List.of(1L, -1L), Arrays.asList(1L, null))) {
            assertThrows(IllegalArgumentException.class,
                    () -> ServingSubprocessMain.applyDeviceMemoryLimits(environment, 2, limits));
        }
        verify(environment, never()).setDeviceLimit(anyInt(), anyLong());
    }

    @Test
    void rejectsAlreadyExceededSecondDeviceWithoutChangingFirst() {
        Environment environment = mock(Environment.class);
        when(environment.getDeviceCounter(1)).thenReturn(4097L);
        assertThrows(IllegalStateException.class,
                () -> ServingSubprocessMain.applyDeviceMemoryLimits(environment, 2, List.of(8192L, 4096L)));
        verify(environment, never()).setDeviceLimit(anyInt(), anyLong());
    }

    @Test
    void failsClosedWhenBackendIgnoresSetter() {
        Environment environment = mock(Environment.class);
        assertThrows(IllegalStateException.class,
                () -> ServingSubprocessMain.applyDeviceMemoryLimits(environment, 2, List.of(8192L, 4096L)));
        verify(environment, never()).setDeviceLimit(1, 4096L);
    }

    @Test
    void propagatesBackendRejectionWithoutLoadingUncapped() {
        Environment environment = mock(Environment.class);
        IllegalStateException rejection = new IllegalStateException("backend rejected limit");
        doThrow(rejection).when(environment).setDeviceLimit(0, 8192L);
        assertEquals(rejection, assertThrows(IllegalStateException.class,
                () -> ServingSubprocessMain.applyDeviceMemoryLimits(environment, 1, List.of(8192L))));
    }
}
