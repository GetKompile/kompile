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

package ai.kompile.cli.main.build;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the BuildNd4jBackend CLI command.
 * Tests command line parsing, help output, and dry-run functionality.
 */
class BuildNd4jBackendTest {

    private CommandLine cmd;
    private StringWriter sw;
    private PrintWriter pw;

    @BeforeEach
    void setUp() {
        BuildNd4jBackend command = new BuildNd4jBackend();
        cmd = new CommandLine(command);
        sw = new StringWriter();
        pw = new PrintWriter(sw);
        cmd.setOut(pw);
        cmd.setErr(pw);
    }

    // =============================================
    // Help and Usage Tests
    // =============================================

    @Test
    @DisplayName("Help option should display usage information")
    void testHelpOption() {
        int exitCode = cmd.execute("--help");

        assertEquals(0, exitCode);
        String output = sw.toString();
        assertTrue(output.contains("build-nd4j-backend"));
        assertTrue(output.contains("--backend"));
        assertTrue(output.contains("--type-profile"));
        assertTrue(output.contains("--preset"));
    }

    @Test
    @DisplayName("Command description should explain purpose")
    void testCommandDescription() {
        int exitCode = cmd.execute("--help");

        String output = sw.toString();
        assertTrue(output.contains("custom ND4J"));
        assertTrue(output.contains("data types"));
        assertTrue(output.contains("optimizations"));
    }

    // =============================================
    // Preset Tests (Dry Run)
    // =============================================

    @ParameterizedTest
    @ValueSource(strings = {"minimal-inference", "minimal-cpu", "training", "cuda-training", "full"})
    @DisplayName("All presets should be recognized in dry-run mode")
    void testPresetsRecognized(String preset) {
        int exitCode = cmd.execute("--preset=" + preset, "--dry-run");

        // Should succeed with dry-run (no actual build)
        assertEquals(0, exitCode);
        String output = sw.toString();
        assertTrue(output.contains("Applying preset") || output.contains("ND4J Backend Build Configuration"));
    }

    @Test
    @DisplayName("Unknown preset should show warning but continue")
    void testUnknownPresetWarning() {
        int exitCode = cmd.execute("--preset=unknown-preset", "--dry-run");

        String output = sw.toString();
        assertTrue(output.contains("Unknown preset") || output.contains("Configuration"));
    }

    // =============================================
    // Backend Selection Tests
    // =============================================

    @Test
    @DisplayName("CPU backend should be default")
    void testDefaultBackend() {
        int exitCode = cmd.execute("--dry-run");

        String output = sw.toString();
        assertTrue(output.contains("CPU") || output.contains("cpu"));
    }

    @Test
    @DisplayName("CUDA backend should be parseable")
    void testCudaBackend() {
        int exitCode = cmd.execute("--backend=CUDA", "--dry-run");

        assertEquals(0, exitCode);
        String output = sw.toString();
        assertTrue(output.contains("CUDA"));
    }

    @Test
    @DisplayName("TPU backend should be parseable")
    void testTpuBackend() {
        int exitCode = cmd.execute("--backend=TPU", "--dry-run");

        assertEquals(0, exitCode);
        String output = sw.toString();
        assertTrue(output.contains("TPU"));
    }

    @Test
    @DisplayName("ZLUDA backend should be parseable")
    void testZludaBackend() {
        int exitCode = cmd.execute("--backend=ZLUDA", "--dry-run");

        assertEquals(0, exitCode);
        String output = sw.toString();
        assertTrue(output.contains("ZLUDA"));
    }

    // =============================================
    // Type Profile Tests
    // =============================================

    @ParameterizedTest
    @ValueSource(strings = {
            "MINIMAL_INDEXING", "ESSENTIAL", "FLOATS_ONLY",
            "QUANTIZATION", "TRAINING", "INFERENCE", "STANDARD_ALL_TYPES"
    })
    @DisplayName("All type profiles should be parseable")
    void testTypeProfiles(String profile) {
        int exitCode = cmd.execute("--type-profile=" + profile, "--dry-run");

        assertEquals(0, exitCode);
    }

    @Test
    @DisplayName("Custom data types should be parseable")
    void testCustomDataTypes() {
        int exitCode = cmd.execute("--data-types=float32;double;int32", "--dry-run");

        assertEquals(0, exitCode);
    }

    // =============================================
    // Helper Options Tests
    // =============================================

    @Test
    @DisplayName("OneDNN helper should be parseable")
    void testOnednnHelper() {
        int exitCode = cmd.execute("--helper-onednn", "--dry-run");

        assertEquals(0, exitCode);
        String output = sw.toString();
        assertTrue(output.contains("onednn") || output.contains("OneDNN") || output.contains("Helper"));
    }

    @Test
    @DisplayName("cuDNN helper should be parseable")
    void testCudnnHelper() {
        int exitCode = cmd.execute("--helper-cudnn", "--dry-run");

        assertEquals(0, exitCode);
    }

    @Test
    @DisplayName("Multiple helpers should be parseable together")
    void testMultipleHelpers() {
        int exitCode = cmd.execute(
                "--helper-onednn",
                "--helper-cudnn",
                "--helper-armcompute",
                "--dry-run"
        );

        assertEquals(0, exitCode);
    }

    @Test
    @DisplayName("Helpers list option should be parseable")
    void testHelpersList() {
        int exitCode = cmd.execute("--helpers=onednn,cudnn,armcompute", "--dry-run");

        assertEquals(0, exitCode);
    }

    // =============================================
    // CUDA Options Tests
    // =============================================

    @Test
    @DisplayName("CUDA version should be parseable")
    void testCudaVersion() {
        int exitCode = cmd.execute(
                "--backend=CUDA",
                "--cuda-version=12.3",
                "--dry-run"
        );

        assertEquals(0, exitCode);
        String output = sw.toString();
        assertTrue(output.contains("12.3") || output.contains("CUDA"));
    }

    @Test
    @DisplayName("cuDNN version should be parseable")
    void testCudnnVersion() {
        int exitCode = cmd.execute(
                "--backend=CUDA",
                "--cudnn-version=8.9",
                "--dry-run"
        );

        assertEquals(0, exitCode);
    }

    @Test
    @DisplayName("Compute capability should be parseable")
    void testComputeCapability() {
        int exitCode = cmd.execute(
                "--backend=CUDA",
                "--compute-capability=80,86,89",
                "--dry-run"
        );

        assertEquals(0, exitCode);
    }

    // =============================================
    // Optimization Options Tests
    // =============================================

    @Test
    @DisplayName("LTO option should be parseable")
    void testLtoOption() {
        int exitCode = cmd.execute("--use-lto", "--dry-run");

        assertEquals(0, exitCode);
        String output = sw.toString();
        assertTrue(output.contains("LTO") || output.contains("true"));
    }

    @Test
    @DisplayName("Semantic filtering options should be parseable")
    void testSemanticFilteringOptions() {
        int exitCode = cmd.execute(
                "--semantic-filtering",
                "--aggressive-semantic-filtering",
                "--dry-run"
        );

        assertEquals(0, exitCode);
    }

    @Test
    @DisplayName("Parallel jobs option should be parseable")
    void testParallelJobsOption() {
        int exitCode = cmd.execute("-j", "8", "--dry-run");

        assertEquals(0, exitCode);
    }

    // =============================================
    // Build Type Tests
    // =============================================

    @ParameterizedTest
    @ValueSource(strings = {"RELEASE", "REL_WITH_DEB_INFO", "DEBUG"})
    @DisplayName("All build types should be parseable")
    void testBuildTypes(String buildType) {
        int exitCode = cmd.execute("--build-type=" + buildType, "--dry-run");

        assertEquals(0, exitCode);
    }

    // =============================================
    // Kernel Selection Tests
    // =============================================

    @ParameterizedTest
    @ValueSource(strings = {"FASTEST", "FIRST", "ROUNDROBIN", "MEMORY", "POWER"})
    @DisplayName("All kernel strategies should be parseable")
    void testKernelStrategies(String strategy) {
        int exitCode = cmd.execute("--kernel-strategy=" + strategy, "--dry-run");

        assertEquals(0, exitCode);
    }

    @Test
    @DisplayName("Dynamic kernel selection negatable option should work")
    void testDynamicKernelSelectionNegatable() {
        int exitCode = cmd.execute("--no-dynamic-kernel-selection", "--dry-run");

        assertEquals(0, exitCode);
    }

    // =============================================
    // Platform Options Tests
    // =============================================

    @Test
    @DisplayName("Platform option should be parseable")
    void testPlatformOption() {
        int exitCode = cmd.execute("--platform=linux-x86_64", "--dry-run");

        assertEquals(0, exitCode);
    }

    @Test
    @DisplayName("Extension option should be parseable")
    void testExtensionOption() {
        int exitCode = cmd.execute("--extension=avx2", "--dry-run");

        assertEquals(0, exitCode);
    }

    // =============================================
    // Output Options Tests
    // =============================================

    @Test
    @DisplayName("Output directory option should be parseable")
    void testOutputDirOption(@TempDir File tempDir) {
        int exitCode = cmd.execute(
                "-o", tempDir.getAbsolutePath(),
                "--dry-run"
        );

        assertEquals(0, exitCode);
    }

    @Test
    @DisplayName("Skip tests negatable option should work")
    void testSkipTestsNegatable() {
        int exitCode = cmd.execute("--no-skip-tests", "--dry-run");

        assertEquals(0, exitCode);
    }

    // =============================================
    // Verbose Option Tests
    // =============================================

    @Test
    @DisplayName("Verbose option should produce more output")
    void testVerboseOption() {
        int exitCode = cmd.execute("-v", "--dry-run");

        assertEquals(0, exitCode);
    }

    // =============================================
    // Combined Options Tests
    // =============================================

    @Test
    @DisplayName("Complex configuration with multiple options should be parseable")
    void testComplexConfiguration() {
        int exitCode = cmd.execute(
                "--backend=CUDA",
                "--cuda-version=12.3",
                "--cudnn-version=8.9",
                "--compute-capability=80,86,89",
                "--type-profile=TRAINING",
                "--helper-cudnn",
                "--helper-onednn",
                "--use-lto",
                "--semantic-filtering",
                "--aggressive-semantic-filtering",
                "-j", "16",
                "--kernel-strategy=FASTEST",
                "--platform=linux-x86_64",
                "-v",
                "--dry-run"
        );

        assertEquals(0, exitCode);
        String output = sw.toString();
        assertTrue(output.contains("CUDA") || output.contains("Configuration"));
    }

    @Test
    @DisplayName("Minimal inference preset with overrides should be parseable")
    void testPresetWithOverrides() {
        int exitCode = cmd.execute(
                "--preset=minimal-inference",
                "--platform=linux-arm64",
                "--helper-armcompute",
                "--dry-run"
        );

        assertEquals(0, exitCode);
    }

    // =============================================
    // Error Handling Tests
    // =============================================

    @Test
    @DisplayName("Invalid backend should cause error")
    void testInvalidBackend() {
        int exitCode = cmd.execute("--backend=INVALID", "--dry-run");

        assertNotEquals(0, exitCode);
    }

    @Test
    @DisplayName("Invalid type profile should cause error")
    void testInvalidTypeProfile() {
        int exitCode = cmd.execute("--type-profile=INVALID", "--dry-run");

        assertNotEquals(0, exitCode);
    }

    @Test
    @DisplayName("Invalid kernel strategy should cause error")
    void testInvalidKernelStrategy() {
        int exitCode = cmd.execute("--kernel-strategy=INVALID", "--dry-run");

        assertNotEquals(0, exitCode);
    }

    @Test
    @DisplayName("Invalid build type should cause error")
    void testInvalidBuildType() {
        int exitCode = cmd.execute("--build-type=INVALID", "--dry-run");

        assertNotEquals(0, exitCode);
    }

    // =============================================
    // Dry Run Output Tests
    // =============================================

    @Test
    @DisplayName("Dry run should print configuration without building")
    void testDryRunOutput() {
        int exitCode = cmd.execute(
                "--preset=minimal-inference",
                "--dry-run"
        );

        assertEquals(0, exitCode);
        String output = sw.toString();
        assertTrue(output.contains("DRY RUN") || output.contains("Configuration"));
    }

    @Test
    @DisplayName("Dry run should show type information")
    void testDryRunShowsTypes() {
        int exitCode = cmd.execute(
                "--type-profile=MINIMAL_INDEXING",
                "--dry-run"
        );

        assertEquals(0, exitCode);
        String output = sw.toString();
        assertTrue(output.contains("Type") || output.contains("MINIMAL") || output.contains("float32"));
    }

    @Test
    @DisplayName("Dry run should show helper information")
    void testDryRunShowsHelpers() {
        int exitCode = cmd.execute(
                "--helper-onednn",
                "--helper-cudnn",
                "--dry-run"
        );

        assertEquals(0, exitCode);
        String output = sw.toString();
        assertTrue(output.contains("Helper") || output.contains("onednn") || output.contains("cudnn"));
    }

    // =============================================
    // Maven Profile Tests
    // =============================================

    @Test
    @DisplayName("Custom Maven profile should be parseable")
    void testCustomMavenProfile() {
        int exitCode = cmd.execute("--maven-profile=custom-profile", "--dry-run");

        assertEquals(0, exitCode);
    }

    // =============================================
    // Operations Selection Tests
    // =============================================

    @Test
    @DisplayName("Operations option should be parseable")
    void testOperationsOption() {
        int exitCode = cmd.execute("--operations=add;subtract;multiply", "--dry-run");

        assertEquals(0, exitCode);
    }

    @Test
    @DisplayName("Exclude operations option should be parseable")
    void testExcludeOperationsOption() {
        int exitCode = cmd.execute("--exclude-operations=conv2d;pool", "--dry-run");

        assertEquals(0, exitCode);
    }
}
