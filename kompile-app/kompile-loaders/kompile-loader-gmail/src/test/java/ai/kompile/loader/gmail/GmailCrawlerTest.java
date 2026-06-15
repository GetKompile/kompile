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

package ai.kompile.loader.gmail;

import ai.kompile.core.crawler.CrawlConfig;
import ai.kompile.core.loaders.DocumentSourceDescriptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class GmailCrawlerTest {

    private GmailCrawler crawler;

    @BeforeEach
    void setUp() {
        crawler = new GmailCrawler();
    }

    // ── Identity and capabilities ───────────────────────────────────────

    @Test
    void getIdReturnsGmail() {
        assertEquals("gmail", crawler.getId());
    }

    @Test
    void getNameReturnsGmailCrawler() {
        assertEquals("Gmail Crawler", crawler.getName());
    }

    @Test
    void getDescriptionIsNotEmpty() {
        assertNotNull(crawler.getDescription());
        assertFalse(crawler.getDescription().isBlank());
    }

    @Test
    void supportedSourceTypesContainsGmail() {
        Set<DocumentSourceDescriptor.SourceType> types = crawler.getSupportedSourceTypes();
        assertTrue(types.contains(DocumentSourceDescriptor.SourceType.GMAIL));
        assertEquals(1, types.size());
    }

    @Test
    void supportsGmailSourceType() {
        assertTrue(crawler.supports(DocumentSourceDescriptor.SourceType.GMAIL));
    }

    @Test
    void doesNotSupportOtherSourceTypes() {
        assertFalse(crawler.supports(DocumentSourceDescriptor.SourceType.EMAIL));
        assertFalse(crawler.supports(DocumentSourceDescriptor.SourceType.IMAP));
        assertFalse(crawler.supports(DocumentSourceDescriptor.SourceType.GOOGLE_WORKSPACE));
        assertFalse(crawler.supports(DocumentSourceDescriptor.SourceType.URL));
    }

    // ── validate() ──────────────────────────────────────────────────────

    @Test
    void validationFailsWithoutAccessToken() {
        CrawlConfig config = CrawlConfig.builder()
                .seed("gmail://inbox")
                .properties(Map.of())
                .build();

        List<String> errors = crawler.validate(config);
        assertFalse(errors.isEmpty(), "Should have validation errors");
        assertTrue(errors.stream().anyMatch(e -> e.contains("accessToken")),
                "Error should mention accessToken");
    }

    @Test
    void validationFailsWithNullProperties() {
        CrawlConfig config = CrawlConfig.builder()
                .seed("gmail://inbox")
                .build();
        // properties defaults to empty HashMap via @Builder.Default

        List<String> errors = crawler.validate(config);
        assertFalse(errors.isEmpty());
    }

    @Test
    void validationPassesWithAccessToken() {
        CrawlConfig config = CrawlConfig.builder()
                .seed("gmail://inbox")
                .properties(Map.of("accessToken", "test-token"))
                .build();

        List<String> errors = crawler.validate(config);

        // Should not have the accessToken error
        assertTrue(errors.stream().noneMatch(e -> e.contains("accessToken")),
                "Should not have accessToken error when token is present");
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
        assertFalse(errors.isEmpty(), "Should fail with null seed");
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
        assertFalse(errors.isEmpty(), "Should fail with blank seed");
    }

    @Test
    void validationFailsWithNegativeMaxDepth() {
        Map<String, Object> props = new HashMap<>();
        props.put("accessToken", "test-token");
        CrawlConfig config = CrawlConfig.builder()
                .seed("gmail://inbox")
                .maxDepth(-1)
                .properties(props)
                .build();

        List<String> errors = crawler.validate(config);
        assertFalse(errors.isEmpty(), "Should fail with negative maxDepth");
    }

    @Test
    void validationFailsWithNegativeMaxDocuments() {
        Map<String, Object> props = new HashMap<>();
        props.put("accessToken", "test-token");
        CrawlConfig config = CrawlConfig.builder()
                .seed("gmail://inbox")
                .maxDocuments(-1)
                .properties(props)
                .build();

        List<String> errors = crawler.validate(config);
        assertFalse(errors.isEmpty(), "Should fail with negative maxDocuments");
    }

    // ── start() — validation guard ──────────────────────────────────────

    @Test
    void startThrowsOnInvalidConfig() {
        CrawlConfig config = CrawlConfig.builder()
                .seed("gmail://inbox")
                .properties(Map.of()) // missing accessToken
                .build();

        assertThrows(IllegalArgumentException.class,
                () -> crawler.start(config, null));
    }
}
