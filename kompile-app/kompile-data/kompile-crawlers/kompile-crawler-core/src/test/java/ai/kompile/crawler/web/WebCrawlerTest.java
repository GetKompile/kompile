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

package ai.kompile.crawler.web;

import ai.kompile.core.crawler.CrawlConfig;
import ai.kompile.core.loaders.DocumentSourceDescriptor.SourceType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("WebCrawler")
class WebCrawlerTest {

    private WebCrawler crawler;

    @BeforeEach
    void setUp() {
        crawler = new WebCrawler();
    }

    @Test
    @DisplayName("Supports URL and WEB_CRAWL source types")
    void supportsUrlAndWebCrawlSourceTypes() {
        Set<SourceType> supported = crawler.getSupportedSourceTypes();
        assertTrue(supported.contains(SourceType.URL));
        assertTrue(supported.contains(SourceType.WEB_CRAWL));
    }

    @Test
    @DisplayName("Crawler ID is 'web'")
    void crawlerIdIsWeb() {
        assertEquals("web", crawler.getId());
    }

    @Test
    @DisplayName("Validates seed URL requires http/https scheme")
    void validatesSeedUrlScheme() {
        CrawlConfig config = CrawlConfig.builder()
                .seed("ftp://example.com")
                .build();
        assertFalse(crawler.validate(config).isEmpty());
    }

    @Test
    @DisplayName("Validates valid https seed URL passes")
    void validatesHttpsSeedUrl() {
        CrawlConfig config = CrawlConfig.builder()
                .seed("https://example.com")
                .build();
        assertTrue(crawler.validate(config).isEmpty());
    }

    @Test
    @DisplayName("URL normalization removes fragments")
    void normalizeUrlRemovesFragments() {
        String normalized = WebCrawler.normalizeUrl("https://example.com/page#section");
        assertFalse(normalized.contains("#section"));
    }

    @Test
    @DisplayName("URL normalization lowercases scheme and host")
    void normalizeUrlLowercases() {
        String normalized = WebCrawler.normalizeUrl("HTTPS://EXAMPLE.COM/Page");
        assertTrue(normalized.startsWith("https://example.com"));
        assertTrue(normalized.contains("/Page")); // path case preserved
    }

    @Test
    @DisplayName("URL normalization removes trailing slash")
    void normalizeUrlRemovesTrailingSlash() {
        String normalized = WebCrawler.normalizeUrl("https://example.com/path/");
        assertFalse(normalized.endsWith("/"));
    }

    @Test
    @DisplayName("CrawlConfig sameDomainOnly defaults to true")
    void crawlConfigSameDomainOnlyDefaultsTrue() {
        CrawlConfig config = CrawlConfig.builder()
                .seed("https://example.com")
                .build();
        assertTrue(config.isSameDomainOnly());
    }

    @Test
    @DisplayName("CrawlConfig sameDomainOnly can be set to false")
    void crawlConfigSameDomainOnlyCanBeDisabled() {
        CrawlConfig config = CrawlConfig.builder()
                .seed("https://example.com")
                .sameDomainOnly(false)
                .build();
        assertFalse(config.isSameDomainOnly());
    }

    @Test
    @DisplayName("CrawlConfig respectRobotsTxt defaults to true")
    void crawlConfigRespectRobotsTxtDefaultsTrue() {
        CrawlConfig config = CrawlConfig.builder()
                .seed("https://example.com")
                .build();
        assertTrue(config.isRespectRobotsTxt());
    }
}
