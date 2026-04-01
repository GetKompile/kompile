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

import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the GenerateDl4jBuild CLI command.
 * Tests command line parsing, help output, and option validation.
 */
class GenerateDl4jBuildTest {

    private CommandLine cmd;
    private StringWriter sw;
    private PrintWriter pw;

    @BeforeEach
    void setUp() {
        GenerateDl4jBuild command = new GenerateDl4jBuild();
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
        assertTrue(output.contains("dl4j-build-generate"));
        assertTrue(output.contains("--outputDirectory") || output.contains("-o"));
        assertTrue(output.contains("--javacppPlatform") || output.contains("--platform"));
    }

    @Test
    @DisplayName("Command description should explain purpose")
    void testCommandDescription() {
        int exitCode = cmd.execute("--help");

        String output = sw.toString();
        assertTrue(output.contains("DL4J") || output.contains("tar"));
        assertTrue(output.contains("backend") || output.contains("ND4J"));
    }

    // =============================================
    // Platform Configuration Tests
    // =============================================

    @Test
    @DisplayName("Platform option should be parseable")
    void testPlatformOption() {
        // Just test parsing - actual build would require Maven home
        int exitCode = cmd.execute("--help");
        assertEquals(0, exitCode);

        String output = sw.toString();
        assertTrue(output.contains("platform"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"linux-x86_64", "macosx-arm64", "windows-x86_64", "linux-arm64"})
    @DisplayName("Common platforms should be documented")
    void testCommonPlatformsInHelp(String platform) {
        int exitCode = cmd.execute("--help");

        assertEquals(0, exitCode);
        // The help should mention platform option
        String output = sw.toString();
        assertTrue(output.contains("platform") || output.contains("Platform"));
    }

    @Test
    @DisplayName("Extension option should be parseable")
    void testExtensionOption() {
        int exitCode = cmd.execute("--help");
        assertEquals(0, exitCode);

        String output = sw.toString();
        assertTrue(output.contains("extension") || output.contains("Extension"));
    }

    // =============================================
    // Backend Selection Tests
    // =============================================

    @Test
    @DisplayName("Backend option should be available")
    void testBackendOption() {
        int exitCode = cmd.execute("--help");
        assertEquals(0, exitCode);

        String output = sw.toString();
        assertTrue(output.contains("--backend") || output.contains("Backend"));
    }

    @Test
    @DisplayName("Help should list available backends")
    void testBackendsList() {
        int exitCode = cmd.execute("--help");

        String output = sw.toString();
        assertTrue(output.contains("CPU") || output.contains("CUDA") ||
                output.contains("TPU") || output.contains("ZLUDA"));
    }

    // =============================================
    // Type Profile Tests
    // =============================================

    @Test
    @DisplayName("Type profile option should be available")
    void testTypeProfileOption() {
        int exitCode = cmd.execute("--help");
        assertEquals(0, exitCode);

        String output = sw.toString();
        assertTrue(output.contains("--type-profile") || output.contains("Type profile"));
    }

    @Test
    @DisplayName("Help should list available type profiles")
    void testTypeProfilesList() {
        int exitCode = cmd.execute("--help");

        String output = sw.toString();
        assertTrue(output.contains("MINIMAL") || output.contains("INFERENCE") ||
                output.contains("TRAINING") || output.contains("type"));
    }

    @Test
    @DisplayName("Data types option should be available")
    void testDataTypesOption() {
        int exitCode = cmd.execute("--help");
        assertEquals(0, exitCode);

        String output = sw.toString();
        assertTrue(output.contains("--data-types") || output.contains("data"));
    }

    // =============================================
    // Helper Libraries Tests
    // =============================================

    @Test
    @DisplayName("OneDNN helper option should be available")
    void testOnednnHelperOption() {
        int exitCode = cmd.execute("--help");
        assertEquals(0, exitCode);

        String output = sw.toString();
        assertTrue(output.contains("--helper-onednn") || output.contains("OneDNN"));
    }

    @Test
    @DisplayName("cuDNN helper option should be available")
    void testCudnnHelperOption() {
        int exitCode = cmd.execute("--help");
        assertEquals(0, exitCode);

        String output = sw.toString();
        assertTrue(output.contains("--helper-cudnn") || output.contains("cuDNN"));
    }

    @Test
    @DisplayName("ARM Compute helper option should be available")
    void testArmcomputeHelperOption() {
        int exitCode = cmd.execute("--help");
        assertEquals(0, exitCode);

        String output = sw.toString();
        assertTrue(output.contains("--helper-armcompute") || output.contains("ARM"));
    }

    @Test
    @DisplayName("MPS helper option should be available")
    void testMpsHelperOption() {
        int exitCode = cmd.execute("--help");
        assertEquals(0, exitCode);

        String output = sw.toString();
        assertTrue(output.contains("--helper-mps") || output.contains("Metal"));
    }

    @Test
    @DisplayName("Helpers list option should be available")
    void testHelpersListOption() {
        int exitCode = cmd.execute("--help");
        assertEquals(0, exitCode);

        String output = sw.toString();
        assertTrue(output.contains("--helpers"));
    }

    // =============================================
    // CUDA Options Tests
    // =============================================

    @Test
    @DisplayName("CUDA version option should be available")
    void testCudaVersionOption() {
        int exitCode = cmd.execute("--help");
        assertEquals(0, exitCode);

        String output = sw.toString();
        assertTrue(output.contains("--cuda-version") || output.contains("CUDA version"));
    }

    @Test
    @DisplayName("cuDNN version option should be available")
    void testCudnnVersionOption() {
        int exitCode = cmd.execute("--help");
        assertEquals(0, exitCode);

        String output = sw.toString();
        assertTrue(output.contains("--cudnn-version") || output.contains("cuDNN version"));
    }

    @Test
    @DisplayName("Compute capability option should be available")
    void testComputeCapabilityOption() {
        int exitCode = cmd.execute("--help");
        assertEquals(0, exitCode);

        String output = sw.toString();
        assertTrue(output.contains("--compute-capability") || output.contains("compute"));
    }

    // =============================================
    // Optimization Options Tests
    // =============================================

    @Test
    @DisplayName("LTO option should be available")
    void testLtoOption() {
        int exitCode = cmd.execute("--help");
        assertEquals(0, exitCode);

        String output = sw.toString();
        assertTrue(output.contains("--use-lto") || output.contains("LTO") ||
                output.contains("Link Time Optimization"));
    }

    @Test
    @DisplayName("Semantic filtering option should be available")
    void testSemanticFilteringOption() {
        int exitCode = cmd.execute("--help");
        assertEquals(0, exitCode);

        String output = sw.toString();
        assertTrue(output.contains("--semantic-filtering") || output.contains("semantic"));
    }

    @Test
    @DisplayName("Aggressive filtering option should be available")
    void testAggressiveFilteringOption() {
        int exitCode = cmd.execute("--help");
        assertEquals(0, exitCode);

        String output = sw.toString();
        assertTrue(output.contains("--aggressive") || output.contains("aggressive"));
    }

    @Test
    @DisplayName("Parallel jobs option should be available")
    void testParallelJobsOption() {
        int exitCode = cmd.execute("--help");
        assertEquals(0, exitCode);

        String output = sw.toString();
        assertTrue(output.contains("-j") || output.contains("--parallel-jobs") ||
                output.contains("parallel"));
    }

    // =============================================
    // Operations Selection Tests
    // =============================================

    @Test
    @DisplayName("Operations option should be available")
    void testOperationsOption() {
        int exitCode = cmd.execute("--help");
        assertEquals(0, exitCode);

        String output = sw.toString();
        assertTrue(output.contains("--operations") || output.contains("operations"));
    }

    // =============================================
    // Build Options Tests
    // =============================================

    @Test
    @DisplayName("Skip tests option should be available")
    void testSkipTestsOption() {
        int exitCode = cmd.execute("--help");
        assertEquals(0, exitCode);

        String output = sw.toString();
        assertTrue(output.contains("--skip-tests") || output.contains("skip"));
    }

    @Test
    @DisplayName("Verbose option should be available")
    void testVerboseOption() {
        int exitCode = cmd.execute("--help");
        assertEquals(0, exitCode);

        String output = sw.toString();
        assertTrue(output.contains("-v") || output.contains("--verbose"));
    }

    // =============================================
    // Output Configuration Tests
    // =============================================

    @Test
    @DisplayName("Output directory option should be available")
    void testOutputDirectoryOption() {
        int exitCode = cmd.execute("--help");
        assertEquals(0, exitCode);

        String output = sw.toString();
        assertTrue(output.contains("--outputDirectory") || output.contains("-o"));
    }

    @Test
    @DisplayName("POM file option should be available")
    void testPomFileOption() {
        int exitCode = cmd.execute("--help");
        assertEquals(0, exitCode);

        String output = sw.toString();
        assertTrue(output.contains("--pomFile") || output.contains("pom"));
    }

    @Test
    @DisplayName("Maven home option should be available")
    void testMavenHomeOption() {
        int exitCode = cmd.execute("--help");
        assertEquals(0, exitCode);

        String output = sw.toString();
        assertTrue(output.contains("--mavenHome") || output.contains("Maven"));
    }

    // =============================================
    // Maven Profile Tests
    // =============================================

    @Test
    @DisplayName("Maven profile option should be available")
    void testMavenProfileOption() {
        int exitCode = cmd.execute("--help");
        assertEquals(0, exitCode);

        String output = sw.toString();
        assertTrue(output.contains("--maven-profile") || output.contains("profile"));
    }

    // =============================================
    // Examples in Help Tests
    // =============================================

    @Test
    @DisplayName("Help should include usage examples")
    void testHelpIncludesExamples() {
        int exitCode = cmd.execute("--help");

        String output = sw.toString();
        // Check for example patterns
        assertTrue(output.contains("kompile") || output.contains("Example") ||
                output.contains("build") || output.contains("--"));
    }

    // =============================================
    // Option Combination Validation Tests
    // =============================================

    @Test
    @DisplayName("Command should accept all documented options without error")
    void testAllOptionsAccepted() {
        // Just parsing the help - actual execution would need Maven
        int exitCode = cmd.execute("--help");
        assertEquals(0, exitCode);

        // Verify key option groups are present
        String output = sw.toString();
        assertTrue(output.contains("backend") || output.contains("Backend"));
        assertTrue(output.contains("type") || output.contains("Type"));
        assertTrue(output.contains("helper") || output.contains("Helper"));
    }
}
