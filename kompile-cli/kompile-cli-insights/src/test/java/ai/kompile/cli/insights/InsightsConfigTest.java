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

package ai.kompile.cli.insights;

import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InsightsConfigTest {

    @TempDir
    Path tempDir;

    @Test
    void defaultsAreTheDocumentedLimits() {
        InsightsConfig config = InsightsConfig.defaults();

        assertEquals(7, config.getDefaultWindowDays());
        assertEquals(10, config.getMaxRows());
        assertEquals(5, config.getMaxExamples());
        assertEquals(200, config.getMaxSessions());
        assertEquals(4L * 1024 * 1024, config.getMaxBytesPerFile());
        assertEquals(256L * 1024 * 1024, config.getMaxToolIndexBytes());
        assertEquals(14, config.getSparklineBuckets());
        assertTrue(config.isSessionPanel());
        assertNull(config.getWarning());
    }

    @Test
    void aMissingFileKeepsTheDefaultsWithoutAWarning() {
        assertEquals(InsightsConfig.defaults(), InsightsConfig.load(tempDir.resolve(InsightsConfig.FILE_NAME)));
        assertEquals(InsightsConfig.defaults(), InsightsConfig.load(null));
    }

    @Test
    void everyKeyCanBeSet() throws IOException {
        Path file = Files.writeString(tempDir.resolve(InsightsConfig.FILE_NAME), """
                {"defaultWindowDays": 3, "maxRows": 20, "maxExamples": 2, "maxSessions": 50,
                 "maxBytesPerFile": 1024, "maxToolIndexBytes": 2048, "sparklineBuckets": 7,
                 "sessionPanel": false}
                """);

        InsightsConfig config = InsightsConfig.load(file);

        assertEquals(3, config.getDefaultWindowDays());
        assertEquals(20, config.getMaxRows());
        assertEquals(2, config.getMaxExamples());
        assertEquals(50, config.getMaxSessions());
        assertEquals(1024, config.getMaxBytesPerFile());
        assertEquals(2048, config.getMaxToolIndexBytes());
        assertEquals(7, config.getSparklineBuckets());
        assertFalse(config.isSessionPanel());
        assertNull(config.getWarning());
    }

    @Test
    void nonPositiveAndNonNumericValuesKeepTheDefault() throws IOException {
        Path file = Files.writeString(tempDir.resolve(InsightsConfig.FILE_NAME),
                "{\"maxRows\": 0, \"maxSessions\": -3, \"maxExamples\": \"many\", \"sparklineBuckets\": \"21\"}");

        InsightsConfig config = InsightsConfig.load(file);

        assertEquals(10, config.getMaxRows());
        assertEquals(200, config.getMaxSessions());
        assertEquals(5, config.getMaxExamples());
        assertEquals(21, config.getSparklineBuckets());
        assertNull(config.getWarning());
    }

    @Test
    void aFileThatIsNotAnObjectIsReported() throws IOException {
        Path file = Files.writeString(tempDir.resolve(InsightsConfig.FILE_NAME), "[1, 2]");

        InsightsConfig config = InsightsConfig.load(file);

        assertEquals(InsightsConfig.defaults().getMaxRows(), config.getMaxRows());
        assertEquals(file + " is not a JSON object; using defaults", config.getWarning());
    }

    @Test
    void anUnparsableFileIsReported() throws IOException {
        Path file = Files.writeString(tempDir.resolve(InsightsConfig.FILE_NAME), "{\"maxRows\": ");

        InsightsConfig config = InsightsConfig.load(file);

        assertEquals(InsightsConfig.defaults().getMaxRows(), config.getMaxRows());
        assertTrue(config.getWarning().startsWith("Could not read " + file + " ("), config.getWarning());
        assertTrue(config.getWarning().endsWith("); using defaults"), config.getWarning());
    }

    @Test
    void savedSettingsLoadBackAndReplaceTheFileWithoutLeavingATemporaryOne() throws IOException {
        Path file = tempDir.resolve("config").resolve(InsightsConfig.FILE_NAME);
        InsightsConfig edited = InsightsConfig.defaults().toBuilder()
                .defaultWindowDays(3).maxRows(20).maxExamples(2).maxSessions(50)
                .maxBytesPerFile(1024).maxToolIndexBytes(3_000_000_000L).sparklineBuckets(7).sessionPanel(false)
                .build();

        edited.save(file);
        assertEquals(edited, InsightsConfig.load(file), "the directory is created and every setting kept");

        InsightsConfig.defaults().save(file);
        assertEquals(InsightsConfig.defaults(), InsightsConfig.load(file), "a second save replaces the first");
        try (var entries = Files.list(file.getParent())) {
            assertEquals(List.of(file), entries.toList(), "no temporary file is left beside it");
        }
    }

    @Test
    void theJsonHoldsEverySettingButNotTheWarning() {
        InsightsConfig warned = InsightsConfig.defaults().toBuilder().maxRows(12).warning("ignored").build();

        assertEquals(JsonUtils.newStandardMapper().createObjectNode()
                .put("defaultWindowDays", 7).put("maxRows", 12).put("maxExamples", 5).put("maxSessions", 200)
                .put("maxBytesPerFile", 4L * 1024 * 1024).put("maxToolIndexBytes", 256L * 1024 * 1024)
                .put("sparklineBuckets", 14).put("sessionPanel", true), warned.toJson());
    }

    @Test
    void settingsChangeOnlyTheKeysNamedAndClearTheWarning() throws IOException {
        InsightsConfig warned = InsightsConfig.defaults().toBuilder().maxRows(12).warning("ignored").build();

        InsightsConfig changed = warned.withSettings(JsonUtils.newStandardMapper()
                .readTree("{\"maxExamples\": 9, \"maxToolIndexBytes\": 3000000000, \"sessionPanel\": false}"));

        assertEquals(12, changed.getMaxRows(), "an unnamed key keeps its value");
        assertEquals(9, changed.getMaxExamples());
        assertEquals(3_000_000_000L, changed.getMaxToolIndexBytes());
        assertFalse(changed.isSessionPanel());
        assertNull(changed.getWarning(), "the settings now hold valid values");
        assertEquals(warned.toBuilder().warning(null).build(), warned.withSettings(warned.toJson()),
                "a config's own JSON changes nothing");
    }

    @Test
    void anInvalidSettingIsRejectedByName() throws IOException {
        var mapper = JsonUtils.newStandardMapper();
        Map<String, String> rejected = new LinkedHashMap<>();
        rejected.put("{\"maxRows\": 0}", "maxRows must be a positive whole number");
        rejected.put("{\"maxSessions\": -3}", "maxSessions must be a positive whole number");
        rejected.put("{\"maxExamples\": \"5\"}", "maxExamples must be a positive whole number");
        rejected.put("{\"sparklineBuckets\": 2.5}", "sparklineBuckets must be a positive whole number");
        rejected.put("{\"defaultWindowDays\": 3000000000}", "defaultWindowDays must be a positive whole number");
        rejected.put("{\"maxBytesPerFile\": null}", "maxBytesPerFile must be a positive whole number");
        rejected.put("{\"sessionPanel\": \"yes\"}", "sessionPanel must be true or false");
        rejected.put("{\"maxRow\": 5}", "Unknown insights setting 'maxRow'");
        rejected.put("[1]", "Insights settings must be a JSON object");
        for (Map.Entry<String, String> change : rejected.entrySet()) {
            JsonNode body = mapper.readTree(change.getKey());
            IllegalArgumentException invalid = assertThrows(IllegalArgumentException.class,
                    () -> InsightsConfig.defaults().withSettings(body), change.getKey());
            assertEquals(change.getValue(), invalid.getMessage(), change.getKey());
        }
        assertThrows(IllegalArgumentException.class, () -> InsightsConfig.defaults().withSettings(null));
    }

    @Test
    void theWarningFollowsATextThatDoesNotAlreadySayIt() {
        InsightsConfig warned = InsightsConfig.defaults().toBuilder().warning("insights.json ignored").build();

        assertEquals("Judge\n\ninsights.json ignored\n", warned.appendWarning("Judge"));
        assertEquals("Judge\n\ninsights.json ignored\n", warned.appendWarning("Judge\n"));
        assertEquals("Judge\n- insights.json ignored\n", warned.appendWarning("Judge\n- insights.json ignored\n"),
                "a topic report already lists it among its notes");
        assertEquals("insights.json ignored\n", warned.appendWarning(null));
        assertEquals("insights.json ignored\n", warned.appendWarning(""));
        assertEquals("Judge", InsightsConfig.defaults().appendWarning("Judge"), "no warning, no change");
        assertEquals("", InsightsConfig.defaults().appendWarning(null));
    }
}
