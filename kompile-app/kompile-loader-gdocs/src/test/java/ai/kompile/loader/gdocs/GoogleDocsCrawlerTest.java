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

package ai.kompile.loader.gdocs;

import ai.kompile.core.crawler.CrawlConfig;
import ai.kompile.core.loaders.DocumentSourceDescriptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class GoogleDocsCrawlerTest {

    private GoogleDocsCrawler crawler;

    @BeforeEach
    void setUp() {
        crawler = new GoogleDocsCrawler();
    }

    // ── Identity and capabilities ──────────────────────────────────────

    @Test
    void getIdReturnsGdocs() {
        assertEquals("gdocs", crawler.getId());
    }

    @Test
    void getNameReturnsGoogleDocsCrawler() {
        assertEquals("Google Docs Crawler", crawler.getName());
    }

    @Test
    void getDescriptionIsNotEmpty() {
        assertNotNull(crawler.getDescription());
        assertFalse(crawler.getDescription().isBlank());
    }

    @Test
    void supportedSourceTypesContainsGdocs() {
        Set<DocumentSourceDescriptor.SourceType> types = crawler.getSupportedSourceTypes();
        assertTrue(types.contains(DocumentSourceDescriptor.SourceType.GDOCS));
        assertEquals(1, types.size());
    }

    @Test
    void supportsGdocsSourceType() {
        assertTrue(crawler.supports(DocumentSourceDescriptor.SourceType.GDOCS));
    }

    @Test
    void doesNotSupportOtherSourceTypes() {
        assertFalse(crawler.supports(DocumentSourceDescriptor.SourceType.GDRIVE));
        assertFalse(crawler.supports(DocumentSourceDescriptor.SourceType.GMAIL));
        assertFalse(crawler.supports(DocumentSourceDescriptor.SourceType.URL));
        assertFalse(crawler.supports(DocumentSourceDescriptor.SourceType.GOOGLE_WORKSPACE));
    }

    // ── validate() ─────────────────────────────────────────────────────

    @Test
    void validationFailsWithoutAccessToken() {
        CrawlConfig config = CrawlConfig.builder()
                .seed("gdocs://")
                .properties(Map.of())
                .build();

        List<String> errors = crawler.validate(config);
        assertFalse(errors.isEmpty());
        assertTrue(errors.stream().anyMatch(e -> e.contains("accessToken")));
    }

    @Test
    void validationFailsWithNullProperties() {
        CrawlConfig config = CrawlConfig.builder()
                .seed("gdocs://")
                .build();

        List<String> errors = crawler.validate(config);
        assertFalse(errors.isEmpty());
    }

    @Test
    void validationPassesWithAccessToken() {
        CrawlConfig config = CrawlConfig.builder()
                .seed("gdocs://")
                .properties(Map.of("accessToken", "test-token"))
                .build();

        List<String> errors = crawler.validate(config);
        assertTrue(errors.stream().noneMatch(e -> e.contains("accessToken")));
    }

    @Test
    void validationFailsWithNullSeed() {
        Map<String, Object> props = new HashMap<>();
        props.put("accessToken", "test-token");
        CrawlConfig config = CrawlConfig.builder()
                .seed(null)
                .properties(props)
                .build();

        List<String> errors = crawler.validate(config);
        assertFalse(errors.isEmpty());
    }

    @Test
    void validationFailsWithBlankSeed() {
        Map<String, Object> props = new HashMap<>();
        props.put("accessToken", "test-token");
        CrawlConfig config = CrawlConfig.builder()
                .seed("   ")
                .properties(props)
                .build();

        List<String> errors = crawler.validate(config);
        assertFalse(errors.isEmpty());
    }

    @Test
    void validationFailsWithNegativeMaxDepth() {
        Map<String, Object> props = new HashMap<>();
        props.put("accessToken", "test-token");
        CrawlConfig config = CrawlConfig.builder()
                .seed("gdocs://")
                .maxDepth(-1)
                .properties(props)
                .build();

        List<String> errors = crawler.validate(config);
        assertFalse(errors.isEmpty());
    }

    @Test
    void validationFailsWithNegativeMaxDocuments() {
        Map<String, Object> props = new HashMap<>();
        props.put("accessToken", "test-token");
        CrawlConfig config = CrawlConfig.builder()
                .seed("gdocs://")
                .maxDocuments(-1)
                .properties(props)
                .build();

        List<String> errors = crawler.validate(config);
        assertFalse(errors.isEmpty());
    }

    // ── start() — validation guard ──────────────────────────────────────

    @Test
    void startThrowsOnInvalidConfig() {
        CrawlConfig config = CrawlConfig.builder()
                .seed("gdocs://")
                .properties(Map.of())
                .build();

        assertThrows(IllegalArgumentException.class,
                () -> crawler.start(config, null));
    }
}
