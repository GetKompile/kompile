/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.cli.main.build.config;

import ai.kompile.cli.main.build.config.Nd4jBuildConfig.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the Nd4jBuildConfig class.
 * Verifies that build configurations are correctly generated for various scenarios.
 */
class Nd4jBuildConfigTest {

    // =============================================
    // Builder Default Values Tests
    // =============================================

    @Test
    @DisplayName("Builder should have correct default values")
    void testBuilderDefaults() {
        Nd4jBuildConfig config = Nd4jBuildConfig.builder().build();

        assertEquals(Backend.CPU, config.getBackend());
        assertFalse(config.isHelperOnednn());
        assertFalse(config.isHelperCudnn());
        assertFalse(config.isHelperArmcompute());
        assertFalse(config.isHelperMps());
        assertFalse(config.isHelperAccelerate());
        assertFalse(config.isHelperMlir());
        assertFalse(config.isHelperPjrt());
        assertFalse(config.isHelperMiopen());
        assertFalse(config.isHelperLlamacpp());
        assertFalse(config.isHelperVlm());
        assertTrue(config.isDynamicKernelSelection());
        assertEquals(KernelStrategy.FASTEST, config.getKernelStrategy());
        assertFalse(config.isKernelAutotuning());
        assertTrue(config.isKernelCaching());
        assertEquals(BuildType.RELEASE, config.getBuildType());
        assertEquals(3, config.getOptimizationLevel());
        assertFalse(config.isUseLto());
        assertFalse(config.isCheckVectorization());
        assertFalse(config.isNativeOptimization());
        assertTrue(config.isSemanticFiltering());
        assertFalse(config.isAggressiveSemanticFiltering());
        assertEquals(1000, config.getMaxTemplateCombinations());
        assertTrue(config.getParallelCompileJobs() > 0);
        assertEquals("./nd4j-build", config.getOutputDirectory());
        assertTrue(config.isSkipTests());
        assertTrue(config.isSkipJavadoc());
        assertTrue(config.isInstallToLocalRepo());
    }

    // =============================================
    // Backend Selection Tests
    // =============================================

    @ParameterizedTest
    @EnumSource(Backend.class)
    @DisplayName("All backend types should be supported")
    void testAllBackendsSupported(Backend backend) {
        Nd4jBuildConfig config = Nd4jBuildConfig.builder()
                .backend(backend)
                .build();

        assertEquals(backend, config.getBackend());
    }

    @Test
    @DisplayName("CPU backend should map to cpu Maven profile")
    void testCpuBackendProfile() {
        Nd4jBuildConfig config = Nd4jBuildConfig.builder()
                .backend(Backend.CPU)
                .build();

        assertEquals("cpu", config.getEffectiveMavenProfile());
    }

    @Test
    @DisplayName("CUDA backend should map to cuda Maven profile")
    void testCudaBackendProfile() {
        Nd4jBuildConfig config = Nd4jBuildConfig.builder()
                .backend(Backend.CUDA)
                .build();

        assertEquals("cuda", config.getEffectiveMavenProfile());
    }

    @Test
    @DisplayName("TPU backend should map to tpu Maven profile")
    void testTpuBackendProfile() {
        Nd4jBuildConfig config = Nd4jBuildConfig.builder()
                .backend(Backend.TPU)
                .build();

        assertEquals("tpu", config.getEffectiveMavenProfile());
    }

    @Test
    @DisplayName("ZLUDA with AMD target should map to zluda-amd profile")
    void testZludaAmdProfile() {
        Nd4jBuildConfig config = Nd4jBuildConfig.builder()
                .backend(Backend.ZLUDA)
                .zludaTarget(ZludaTarget.AMD)
                .build();

        assertEquals("zluda-amd", config.getEffectiveMavenProfile());
    }

    @Test
    @DisplayName("ZLUDA with Intel target should map to zluda-intel profile")
    void testZludaIntelProfile() {
        Nd4jBuildConfig config = Nd4jBuildConfig.builder()
                .backend(Backend.ZLUDA)
                .zludaTarget(ZludaTarget.INTEL)
                .build();

        assertEquals("zluda-intel", config.getEffectiveMavenProfile());
    }

    @Test
    @DisplayName("Custom Maven profile should override backend-derived profile")
    void testCustomMavenProfileOverride() {
        Nd4jBuildConfig config = Nd4jBuildConfig.builder()
                .backend(Backend.CPU)
                .mavenProfile("custom-profile")
                .build();

        assertEquals("custom-profile", config.getEffectiveMavenProfile());
    }

    @Test
    @DisplayName("MINIMAL_INDEXING type profile should use minimial-cpu Maven profile")
    void testMinimalIndexingProfile() {
        Nd4jBuildConfig config = Nd4jBuildConfig.builder()
                .backend(Backend.CPU)
                .typeProfile(TypeProfile.MINIMAL_INDEXING)
                .build();

        assertEquals("minimial-cpu", config.getEffectiveMavenProfile());
    }

    // =============================================
    // Type Profile Tests
    // =============================================

    @ParameterizedTest
    @CsvSource({
            "MINIMAL_INDEXING, 'float32;double;int32;int64'",
            "ESSENTIAL, 'float32;double;int32;int64;int8;int16'",
            "FLOATS_ONLY, 'float32;double;float16'",
            "INTEGERS_ONLY, 'int8;uint8;int16;uint16;int32;uint32;int64;uint64'",
            "SINGLE_PRECISION, 'float32;int32;int64'",
            "DOUBLE_PRECISION, 'double;int32;int64'",
            "QUANTIZATION, 'int8;uint8;float32;int32'",
            "TRAINING, 'float32;float16;bfloat16;int32;int64;double'",
            "INFERENCE, 'int8;uint8;float16;float32;int32'"
    })
    @DisplayName("Type profiles should produce correct data type strings")
    void testTypeProfileDataTypes(TypeProfile profile, String expectedTypes) {
        Nd4jBuildConfig config = Nd4jBuildConfig.builder()
                .typeProfile(profile)
                .build();

        assertEquals(expectedTypes, config.getEffectiveDataTypes());
    }

    @Test
    @DisplayName("STANDARD_ALL_TYPES should include all supported types")
    void testStandardAllTypes() {
        Nd4jBuildConfig config = Nd4jBuildConfig.builder()
                .typeProfile(TypeProfile.STANDARD_ALL_TYPES)
                .build();

        String dataTypes = config.getEffectiveDataTypes();
        assertNotNull(dataTypes);
        assertTrue(dataTypes.contains("float32"));
        assertTrue(dataTypes.contains("double"));
        assertTrue(dataTypes.contains("int32"));
        assertTrue(dataTypes.contains("int64"));
        assertTrue(dataTypes.contains("bool"));
    }

    @Test
    @DisplayName("Custom data types should override type profile")
    void testCustomDataTypesOverride() {
        List<String> customTypes = Arrays.asList("float32", "int32");
        Nd4jBuildConfig config = Nd4jBuildConfig.builder()
                .typeProfile(TypeProfile.STANDARD_ALL_TYPES)
                .dataTypes(customTypes)
                .build();

        assertEquals("float32;int32", config.getEffectiveDataTypes());
    }

    @Test
    @DisplayName("No type profile should return null effective data types")
    void testNoTypeProfileReturnsNull() {
        Nd4jBuildConfig config = Nd4jBuildConfig.builder().build();

        assertNull(config.getEffectiveDataTypes());
    }

    // =============================================
    // Helper Libraries Tests
    // =============================================

    @Test
    @DisplayName("Individual helper flags should build correct helpers list")
    void testIndividualHelperFlags() {
        Nd4jBuildConfig config = Nd4jBuildConfig.builder()
                .helperOnednn(true)
                .helperCudnn(true)
                .helperArmcompute(true)
                .build();

        String helpers = config.getEffectiveHelpersList();
        assertTrue(helpers.contains("onednn"));
        assertTrue(helpers.contains("cudnn"));
        assertTrue(helpers.contains("armcompute"));
    }

    @Test
    @DisplayName("Explicit helpers list should override individual flags")
    void testExplicitHelpersListOverride() {
        Nd4jBuildConfig config = Nd4jBuildConfig.builder()
                .helperOnednn(true)
                .helperCudnn(true)
                .helpersList("custom;helpers")
                .build();

        assertEquals("custom;helpers", config.getEffectiveHelpersList());
    }

    @Test
    @DisplayName("No helpers should return empty string")
    void testNoHelpersReturnsEmpty() {
        Nd4jBuildConfig config = Nd4jBuildConfig.builder().build();

        assertEquals("", config.getEffectiveHelpersList());
    }

    @Test
    @DisplayName("All helper flags should be properly concatenated")
    void testAllHelpersConcat() {
        Nd4jBuildConfig config = Nd4jBuildConfig.builder()
                .helperOnednn(true)
                .helperCudnn(true)
                .helperArmcompute(true)
                .helperMps(true)
                .helperAccelerate(true)
                .helperMlir(true)
                .helperPjrt(true)
                .helperMiopen(true)
                .helperLlamacpp(true)
                .helperVlm(true)
                .build();

        String helpers = config.getEffectiveHelpersList();
        String[] helperArray = helpers.split(";");
        assertEquals(10, helperArray.length);
    }

    // =============================================
    // Build Type Tests
    // =============================================

    @ParameterizedTest
    @CsvSource({
            "RELEASE, Release",
            "REL_WITH_DEB_INFO, RelWithDebInfo",
            "DEBUG, Debug"
    })
    @DisplayName("Build types should map to correct CMake strings")
    void testBuildTypeCmakeMapping(BuildType buildType, String expectedCmakeType) {
        Nd4jBuildConfig config = Nd4jBuildConfig.builder()
                .buildType(buildType)
                .build();

        assertEquals(expectedCmakeType, config.getCmakeBuildType());
    }

    // =============================================
    // Preset Configuration Tests
    // =============================================

    @Test
    @DisplayName("minimalInference preset should have correct configuration")
    void testMinimalInferencePreset() {
        Nd4jBuildConfig config = Nd4jBuildConfig.minimalInference();

        assertEquals(Backend.CPU, config.getBackend());
        assertEquals(TypeProfile.INFERENCE, config.getTypeProfile());
        assertTrue(config.isHelperOnednn());
        assertTrue(config.isDynamicKernelSelection());
        assertTrue(config.isSemanticFiltering());
        assertTrue(config.isAggressiveSemanticFiltering());
        assertTrue(config.isUseLto());
    }

    @Test
    @DisplayName("training preset should have correct configuration")
    void testTrainingPreset() {
        Nd4jBuildConfig config = Nd4jBuildConfig.training();

        assertEquals(Backend.CPU, config.getBackend());
        assertEquals(TypeProfile.TRAINING, config.getTypeProfile());
        assertTrue(config.isHelperOnednn());
        assertTrue(config.isDynamicKernelSelection());
        assertTrue(config.isSemanticFiltering());
    }

    @Test
    @DisplayName("cudaTraining preset should have correct configuration")
    void testCudaTrainingPreset() {
        Nd4jBuildConfig config = Nd4jBuildConfig.cudaTraining("12.3", "80,86");

        assertEquals(Backend.CUDA, config.getBackend());
        assertEquals(TypeProfile.TRAINING, config.getTypeProfile());
        assertTrue(config.isHelperCudnn());
        assertEquals("12.3", config.getCudaVersion());
        assertEquals("80,86", config.getComputeCapability());
        assertTrue(config.isDynamicKernelSelection());
        assertTrue(config.isSemanticFiltering());
    }

    @Test
    @DisplayName("minimalCpu preset should use minimal profile and aggressive filtering")
    void testMinimalCpuPreset() {
        Nd4jBuildConfig config = Nd4jBuildConfig.minimalCpu();

        assertEquals(Backend.CPU, config.getBackend());
        assertEquals("minimial-cpu", config.getMavenProfile());
        assertEquals(TypeProfile.MINIMAL_INDEXING, config.getTypeProfile());
        assertTrue(config.isSemanticFiltering());
        assertTrue(config.isAggressiveSemanticFiltering());
    }

    // =============================================
    // CUDA Configuration Tests
    // =============================================

    @Test
    @DisplayName("CUDA configuration should include all CUDA-specific settings")
    void testCudaConfiguration() {
        Nd4jBuildConfig config = Nd4jBuildConfig.builder()
                .backend(Backend.CUDA)
                .cudaVersion("12.3")
                .cudnnVersion("8.9")
                .computeCapability("80,86,89")
                .helperCudnn(true)
                .build();

        assertEquals(Backend.CUDA, config.getBackend());
        assertEquals("12.3", config.getCudaVersion());
        assertEquals("8.9", config.getCudnnVersion());
        assertEquals("80,86,89", config.getComputeCapability());
        assertTrue(config.isHelperCudnn());
        assertEquals("cuda", config.getEffectiveMavenProfile());
    }

    // =============================================
    // Platform Configuration Tests
    // =============================================

    @Test
    @DisplayName("Platform configuration should be properly set")
    void testPlatformConfiguration() {
        Nd4jBuildConfig config = Nd4jBuildConfig.builder()
                .platform("linux-x86_64")
                .extension("avx2")
                .arch("x86_64")
                .build();

        assertEquals("linux-x86_64", config.getPlatform());
        assertEquals("avx2", config.getExtension());
        assertEquals("x86_64", config.getArch());
    }

    // =============================================
    // Optimization Options Tests
    // =============================================

    @Test
    @DisplayName("Optimization options should be properly set")
    void testOptimizationOptions() {
        Nd4jBuildConfig config = Nd4jBuildConfig.builder()
                .optimizationLevel(2)
                .useLto(true)
                .checkVectorization(true)
                .nativeOptimization(true)
                .build();

        assertEquals(2, config.getOptimizationLevel());
        assertTrue(config.isUseLto());
        assertTrue(config.isCheckVectorization());
        assertTrue(config.isNativeOptimization());
    }

    // =============================================
    // Template Control Tests
    // =============================================

    @Test
    @DisplayName("Template control options should be properly set")
    void testTemplateControlOptions() {
        Nd4jBuildConfig config = Nd4jBuildConfig.builder()
                .semanticFiltering(true)
                .aggressiveSemanticFiltering(true)
                .maxTemplateCombinations(500)
                .parallelCompileJobs(8)
                .extractInstantiations(true)
                .generateFixFiles(true)
                .build();

        assertTrue(config.isSemanticFiltering());
        assertTrue(config.isAggressiveSemanticFiltering());
        assertEquals(500, config.getMaxTemplateCombinations());
        assertEquals(8, config.getParallelCompileJobs());
        assertTrue(config.isExtractInstantiations());
        assertTrue(config.isGenerateFixFiles());
    }

    // =============================================
    // Operations Selection Tests
    // =============================================

    @Test
    @DisplayName("Operations selection should be properly set")
    void testOperationsSelection() {
        Nd4jBuildConfig config = Nd4jBuildConfig.builder()
                .operations("add;subtract;multiply")
                .excludeOperations("conv2d;pool")
                .build();

        assertEquals("add;subtract;multiply", config.getOperations());
        assertEquals("conv2d;pool", config.getExcludeOperations());
    }

    // =============================================
    // Kernel Selection Tests
    // =============================================

    @ParameterizedTest
    @EnumSource(KernelStrategy.class)
    @DisplayName("All kernel strategies should be supported")
    void testAllKernelStrategies(KernelStrategy strategy) {
        Nd4jBuildConfig config = Nd4jBuildConfig.builder()
                .kernelStrategy(strategy)
                .build();

        assertEquals(strategy, config.getKernelStrategy());
    }

    @Test
    @DisplayName("Kernel selection options should be properly set")
    void testKernelSelectionOptions() {
        Nd4jBuildConfig config = Nd4jBuildConfig.builder()
                .dynamicKernelSelection(true)
                .kernelStrategy(KernelStrategy.MEMORY)
                .kernelAutotuning(true)
                .kernelCaching(false)
                .helperPriority("cudnn;onednn;cpu")
                .build();

        assertTrue(config.isDynamicKernelSelection());
        assertEquals(KernelStrategy.MEMORY, config.getKernelStrategy());
        assertTrue(config.isKernelAutotuning());
        assertFalse(config.isKernelCaching());
        assertEquals("cudnn;onednn;cpu", config.getHelperPriority());
    }

    // =============================================
    // Output Configuration Tests
    // =============================================

    @Test
    @DisplayName("Output configuration should be properly set")
    void testOutputConfiguration() {
        Nd4jBuildConfig config = Nd4jBuildConfig.builder()
                .outputDirectory("/custom/output")
                .skipTests(false)
                .skipJavadoc(false)
                .installToLocalRepo(false)
                .build();

        assertEquals("/custom/output", config.getOutputDirectory());
        assertFalse(config.isSkipTests());
        assertFalse(config.isSkipJavadoc());
        assertFalse(config.isInstallToLocalRepo());
    }

    // =============================================
    // Type Count Estimation Tests
    // =============================================

    @Test
    @DisplayName("MINIMAL_INDEXING should produce 4 types")
    void testMinimalIndexingTypeCount() {
        Nd4jBuildConfig config = Nd4jBuildConfig.builder()
                .typeProfile(TypeProfile.MINIMAL_INDEXING)
                .build();

        String types = config.getEffectiveDataTypes();
        int typeCount = types.split(";").length;
        assertEquals(4, typeCount);
    }

    @Test
    @DisplayName("INFERENCE should produce 5 types")
    void testInferenceTypeCount() {
        Nd4jBuildConfig config = Nd4jBuildConfig.builder()
                .typeProfile(TypeProfile.INFERENCE)
                .build();

        String types = config.getEffectiveDataTypes();
        int typeCount = types.split(";").length;
        assertEquals(5, typeCount);
    }

    @Test
    @DisplayName("TRAINING should produce 6 types")
    void testTrainingTypeCount() {
        Nd4jBuildConfig config = Nd4jBuildConfig.builder()
                .typeProfile(TypeProfile.TRAINING)
                .build();

        String types = config.getEffectiveDataTypes();
        int typeCount = types.split(";").length;
        assertEquals(6, typeCount);
    }
}
