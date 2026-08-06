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
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.langdetect;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LanguageDetectionConfigServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void persistedConfigRoundTripsWithoutDtoReflection() {
        Path configPath = tempDir.resolve("language-detection-config.json");
        LanguageDetectionConfigService service = new LanguageDetectionConfigService(configPath);
        service.loadConfig();

        LanguageDetectionConfig config = LanguageDetectionConfig.defaults();
        config.setEnabled(false);
        config.setMinConfidenceThreshold(0.73);
        config.setDetectOnCrawl(false);
        config.setDetectOnIngest(true);
        config.setMaxCharsForDetection(3210);
        config.setFallbackLanguage("ja");
        config.setMultilingualEmbeddingModel("multi-test");
        config.setEnglishEmbeddingModel("english-test");
        config.setAutoSwitchEmbeddingModel(true);
        service.updateConfig(config);

        LanguageDetectionConfigService reloaded = new LanguageDetectionConfigService(configPath);
        reloaded.loadConfig();
        LanguageDetectionConfig actual = reloaded.getConfig();

        assertFalse(actual.isEnabled());
        assertEquals(0.73, actual.getMinConfidenceThreshold());
        assertFalse(actual.isDetectOnCrawl());
        assertTrue(actual.isDetectOnIngest());
        assertEquals(3210, actual.getMaxCharsForDetection());
        assertEquals("ja", actual.getFallbackLanguage());
        assertEquals("multi-test", actual.getMultilingualEmbeddingModel());
        assertEquals("english-test", actual.getEnglishEmbeddingModel());
        assertTrue(actual.isAutoSwitchEmbeddingModel());
    }

    @Test
    void missingFieldsRetainDefaultsAndUnknownFieldsAreIgnored() throws Exception {
        Path configPath = tempDir.resolve("language-detection-config.json");
        Files.writeString(configPath, "{\"enabled\":false,\"unknownFutureField\":42}");

        LanguageDetectionConfigService service = new LanguageDetectionConfigService(configPath);
        service.loadConfig();

        assertFalse(service.getConfig().isEnabled());
        assertEquals(0.50, service.getConfig().getMinConfidenceThreshold());
        assertTrue(service.getConfig().isDetectOnCrawl());
        assertEquals("und", service.getConfig().getFallbackLanguage());
    }
}
