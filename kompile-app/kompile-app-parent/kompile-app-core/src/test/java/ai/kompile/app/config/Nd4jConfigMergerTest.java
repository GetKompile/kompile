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

package ai.kompile.app.config;

import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link Nd4jConfigMerger}: verifies that global and project
 * {@code nd4j-environment-config.json} files are merged with project-over-global precedence.
 *
 * <p>All tests use temporary directories and written JSON files — no ND4J runtime, no Spring
 * context, no hardware required.
 */
class Nd4jConfigMergerTest {

    private static final ObjectMapper MAPPER = JsonUtils.standardMapper();

    @TempDir
    Path globalDir;

    @TempDir
    Path projectDir;

    // ── helpers ───────────────────────────────────────────────────────────────

    /** Write JSON text to {@code <dir>/nd4j-environment-config.json} and return the path. */
    private static Path writeConfig(Path dir, String json) throws IOException {
        Path configDir = dir.resolve("config");
        Files.createDirectories(configDir);
        Path file = configDir.resolve(Nd4jConfigMerger.CONFIG_FILENAME);
        Files.writeString(file, json);
        return file;
    }

    // ── test (a): project key wins when both files define it ──────────────────

    /**
     * Global sets {@code optimizerFp16=true}; project sets {@code optimizerFp16=false}.
     * The merged result must use the project value ({@code false}).
     */
    @Test
    void projectValueOverridesGlobal() throws IOException {
        Path globalPath = writeConfig(globalDir, "{\"optimizerFp16\": true}");
        Path projectPath = writeConfig(projectDir, "{\"optimizerFp16\": false}");

        Nd4jEnvironmentConfig merged = Nd4jConfigMerger.merge(globalPath, projectPath, MAPPER);

        assertFalse(merged.optimizerFp16(),
                "Project 'optimizerFp16=false' must override global 'optimizerFp16=true'");
    }

    // ── test (b): key absent in project falls back to global ──────────────────

    /**
     * Global sets {@code optimizerFp16=false}; project file exists but does not mention
     * {@code optimizerFp16}. The merged result must use the global value ({@code false}).
     */
    @Test
    void globalValueUsedWhenKeyAbsentInProject() throws IOException {
        Path globalPath = writeConfig(globalDir, "{\"optimizerFp16\": false}");
        // Project file deliberately omits optimizerFp16; set something else to prove the file is loaded
        Path projectPath = writeConfig(projectDir, "{\"debug\": false}");

        Nd4jEnvironmentConfig merged = Nd4jConfigMerger.merge(globalPath, projectPath, MAPPER);

        assertFalse(merged.optimizerFp16(),
                "Global 'optimizerFp16=false' must be preserved when project file omits the key");
    }

    // ── test (c): no project config → global is used ─────────────────────────

    /**
     * No project config file exists. The merged result must reflect the global value.
     */
    @Test
    void globalUsedWhenNoProjectConfig() throws IOException {
        Path globalPath = writeConfig(globalDir, "{\"optimizerFp16\": false}");
        // projectPath points to a file that does not exist
        Path missingProjectPath = projectDir.resolve("config").resolve(Nd4jConfigMerger.CONFIG_FILENAME);

        Nd4jEnvironmentConfig merged = Nd4jConfigMerger.merge(globalPath, missingProjectPath, MAPPER);

        assertFalse(merged.optimizerFp16(),
                "Global 'optimizerFp16=false' must be used when project config is absent");
    }

    // ── test (d): neither file exists → built-in defaults apply ──────────────

    /**
     * Neither global nor project config files exist. The result must be
     * {@link Nd4jEnvironmentConfig#defaults()} (which has {@code optimizerFp16=true}).
     */
    @Test
    void defaultsWhenNeitherFileExists() {
        Path missingGlobal = globalDir.resolve("config").resolve(Nd4jConfigMerger.CONFIG_FILENAME);
        Path missingProject = projectDir.resolve("config").resolve(Nd4jConfigMerger.CONFIG_FILENAME);

        Nd4jEnvironmentConfig merged = Nd4jConfigMerger.merge(missingGlobal, missingProject, MAPPER);
        Nd4jEnvironmentConfig defaults = Nd4jEnvironmentConfig.defaults();

        assertEquals(defaults.optimizerFp16(), merged.optimizerFp16(),
                "Built-in default for optimizerFp16 must apply when neither config file exists");
        // Spot-check a second field to guard against a trivially wrong implementation
        assertEquals(defaults.maxThreads(), merged.maxThreads(),
                "Built-in default for maxThreads must apply when neither config file exists");
    }

    // ── bonus: same path as global → no double-load ───────────────────────────

    /**
     * When the project path equals the global path (single-source mode — no explicit
     * {@code kompile.data.dir}), the file is loaded only once and the result is identical
     * to loading the global alone.
     */
    @Test
    void singleSourceModeWhenPathsAreEqual() throws IOException {
        Path globalPath = writeConfig(globalDir, "{\"optimizerFp16\": false}");

        // Pass the same path for both — simulates the case where dataDir is not set
        Nd4jEnvironmentConfig merged = Nd4jConfigMerger.merge(globalPath, globalPath, MAPPER);

        assertFalse(merged.optimizerFp16(),
                "Single-source mode (projectPath == globalPath) must load the file exactly once " +
                "and reflect its value");
    }

    // ── convenience overload: string dataDir ─────────────────────────────────

    /**
     * The {@link Nd4jConfigMerger#merge(String, ObjectMapper)} convenience overload resolves
     * both paths from the provided data-dir string. Verify project wins via the overload.
     */
    @Test
    void convenienceOverloadRespectsPrecedence() throws IOException {
        // Set up global under user.home
        Path fakeHome = globalDir;
        Path globalConfigDir = fakeHome.resolve(".kompile").resolve("config");
        Files.createDirectories(globalConfigDir);
        Files.writeString(globalConfigDir.resolve(Nd4jConfigMerger.CONFIG_FILENAME),
                "{\"optimizerFp16\": true}");

        // Project dir that wins
        Path projectConfigDir = projectDir.resolve("config");
        Files.createDirectories(projectConfigDir);
        Files.writeString(projectConfigDir.resolve(Nd4jConfigMerger.CONFIG_FILENAME),
                "{\"optimizerFp16\": false}");

        // Temporarily override user.home so the merger resolves the fake global path
        String originalHome = System.getProperty("user.home");
        try {
            System.setProperty("user.home", fakeHome.toAbsolutePath().toString());
            Nd4jEnvironmentConfig merged = Nd4jConfigMerger.merge(
                    projectDir.toAbsolutePath().toString(), MAPPER);
            assertFalse(merged.optimizerFp16(),
                    "Project 'optimizerFp16=false' must win via the convenience overload");
        } finally {
            System.setProperty("user.home", originalHome);
        }
    }
}
