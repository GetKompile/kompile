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

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Merges {@code nd4j-environment-config.json} with project-over-global precedence.
 *
 * <p>Resolution order (last-writer wins per-key via {@link Nd4jEnvironmentConfig#merge}):
 * <ol>
 *   <li>Built-in {@link Nd4jEnvironmentConfig#defaults()} — always the base.</li>
 *   <li>Global config ({@code ~/.kompile/config/nd4j-environment-config.json}) overlaid on top
 *       when present.</li>
 *   <li>Project config ({@code <kompile.data.dir>/config/nd4j-environment-config.json}) overlaid
 *       last when the path is set and distinct from the global path — project keys win per-key.</li>
 * </ol>
 * A key absent in the project file falls back to the global value; a key absent in both falls back
 * to the built-in default.
 *
 * <p>This is the single shared implementation. Call it from every load site; never duplicate the
 * two-layer load logic in individual load sites.
 */
public final class Nd4jConfigMerger {

    private static final Logger log = LoggerFactory.getLogger(Nd4jConfigMerger.class);

    static final String CONFIG_FILENAME = "nd4j-environment-config.json";

    private Nd4jConfigMerger() {}

    /**
     * Merge global and project config files, with the project layer winning per-key.
     *
     * @param globalPath  absolute path to the global config file
     *                    ({@code ~/.kompile/config/nd4j-environment-config.json}); may be null
     * @param projectPath absolute path to the project config file
     *                    ({@code <dataDir>/config/nd4j-environment-config.json}); may be null or
     *                    equal to {@code globalPath} (treated as no distinct project layer)
     * @param mapper      Jackson mapper used for deserialization
     * @return merged config (never null); falls back to defaults when neither file exists
     */
    public static Nd4jEnvironmentConfig merge(Path globalPath, Path projectPath, ObjectMapper mapper) {
        Nd4jEnvironmentConfig result = Nd4jEnvironmentConfig.defaults();

        // Layer 1: global config
        result = overlay(result, globalPath, "global", mapper);

        // Layer 2: project config — only when distinct (prevents double-loading the same file)
        if (projectPath != null && !projectPath.equals(globalPath)) {
            result = overlay(result, projectPath, "project", mapper);
        }

        return result;
    }

    /**
     * Convenience overload that resolves both paths from a raw {@code kompile.data.dir} value.
     *
     * <p>When {@code dataDirOrNull} is null or blank (the system property is not set), no project
     * layer is applied and only the global config is loaded — preserving the pre-fix single-source
     * behaviour.
     *
     * @param dataDirOrNull value of the {@code kompile.data.dir} system property, or null/blank
     * @param mapper        Jackson mapper used for deserialization
     * @return merged config
     */
    public static Nd4jEnvironmentConfig merge(String dataDirOrNull, ObjectMapper mapper) {
        Path globalPath = Path.of(System.getProperty("user.home"), ".kompile", "config", CONFIG_FILENAME);
        Path projectPath = (dataDirOrNull == null || dataDirOrNull.isBlank())
                ? null
                : Path.of(dataDirOrNull, "config", CONFIG_FILENAME);
        return merge(globalPath, projectPath, mapper);
    }

    // ── private ───────────────────────────────────────────────────────────────

    /**
     * Load {@code path} and overlay it on top of {@code base}; return {@code base} unchanged on
     * any error (missing file, parse failure) so callers always get a usable config.
     */
    private static Nd4jEnvironmentConfig overlay(Nd4jEnvironmentConfig base, Path path,
                                                  String label, ObjectMapper mapper) {
        if (path == null || !Files.exists(path)) {
            log.debug("Nd4j {} config not found at {} — skipping layer", label, path);
            return base;
        }
        try {
            String json = Files.readString(path);
            Nd4jEnvironmentConfig loaded = mapper.readValue(json, Nd4jEnvironmentConfig.class);
            log.info("Applied nd4j {} config from {} ({} bytes)", label, path, json.length());
            return base.merge(loaded);
        } catch (IOException e) {
            log.error("Failed to read nd4j {} config from {}: {} — keeping previous layer",
                    label, path, e.getMessage());
            return base;
        }
    }
}
