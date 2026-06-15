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

package ai.kompile.crawler;

import ai.kompile.core.crawler.CrawlConfig;
import ai.kompile.core.crawler.CrawlItem;
import ai.kompile.core.crawler.pipeline.ContentRouteRule;
import ai.kompile.core.crawler.pipeline.IngestPipelineDefinition;
import ai.kompile.core.crawler.pipeline.RoutedCrawlItem;
import ai.kompile.core.loaders.DocumentSourceDescriptor;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CrawlPipelineRouterTest {

    // ---- Content type routing ----

    @Test
    void routesByExactContentType() {
        CrawlConfig config = buildConfig(
                List.of(
                        pipeline("html", IngestPipelineDefinition.PipelineType.STANDARD_TEXT),
                        pipeline("pdf-vlm", IngestPipelineDefinition.PipelineType.VLM)
                ),
                List.of(
                        rule("pdf-vlm", List.of("application/pdf"), null, null, null, 10),
                        rule("html", List.of("text/html"), null, null, null, 20)
                ),
                "html"
        );

        CrawlPipelineRouter router = new CrawlPipelineRouter(config);

        CrawlItem pdfItem = itemWithContentType("https://example.com/doc.pdf", "application/pdf");
        assertEquals("pdf-vlm", router.route(pdfItem).getPipelineId());

        CrawlItem htmlItem = itemWithContentType("https://example.com/page", "text/html");
        assertEquals("html", router.route(htmlItem).getPipelineId());
    }

    @Test
    void routesByContentTypeWithCharsetParameter() {
        CrawlConfig config = buildConfig(
                List.of(pipeline("html", IngestPipelineDefinition.PipelineType.STANDARD_TEXT)),
                List.of(rule("html", List.of("text/html"), null, null, null, 10)),
                "html"
        );

        CrawlPipelineRouter router = new CrawlPipelineRouter(config);

        // Content-Type with charset parameter should still match
        CrawlItem item = itemWithContentType("https://example.com/page", "text/html; charset=utf-8");
        assertEquals("html", router.route(item).getPipelineId());
    }

    @Test
    void routesByContentTypeWildcard() {
        CrawlConfig config = buildConfig(
                List.of(
                        pipeline("images", IngestPipelineDefinition.PipelineType.VLM),
                        pipeline("default-text", IngestPipelineDefinition.PipelineType.STANDARD_TEXT)
                ),
                List.of(rule("images", List.of("image/*"), null, null, null, 10)),
                "default-text"
        );

        CrawlPipelineRouter router = new CrawlPipelineRouter(config);

        assertEquals("images", router.route(itemWithContentType("a.png", "image/png")).getPipelineId());
        assertEquals("images", router.route(itemWithContentType("a.jpg", "image/jpeg")).getPipelineId());
        assertEquals("images", router.route(itemWithContentType("a.tiff", "image/tiff")).getPipelineId());

        // text/plain should NOT match image/*
        assertEquals("default-text", router.route(itemWithContentType("a.txt", "text/plain")).getPipelineId());
    }

    @Test
    void noContentTypeOnItemFallsToDefault() {
        CrawlConfig config = buildConfig(
                List.of(pipeline("html", IngestPipelineDefinition.PipelineType.STANDARD_TEXT)),
                List.of(rule("html", List.of("text/html"), null, null, null, 10)),
                "html"
        );

        CrawlPipelineRouter router = new CrawlPipelineRouter(config);

        CrawlItem item = CrawlItem.builder().url("https://example.com/unknown").build();
        // No content type on the item → rule doesn't match → default pipeline
        assertEquals("html", router.route(item).getPipelineId());
    }

    // ---- File extension routing ----

    @Test
    void routesByFileExtension() {
        CrawlConfig config = buildConfig(
                List.of(
                        pipeline("code", IngestPipelineDefinition.PipelineType.CODE),
                        pipeline("default", IngestPipelineDefinition.PipelineType.STANDARD_TEXT)
                ),
                List.of(rule("code", null, List.of(".py", ".java", ".ts", ".go"), null, null, 10)),
                "default"
        );

        CrawlPipelineRouter router = new CrawlPipelineRouter(config);

        assertEquals("code", router.route(itemWithUrl("https://github.com/repo/main.py")).getPipelineId());
        assertEquals("code", router.route(itemWithUrl("https://github.com/repo/App.java")).getPipelineId());
        assertEquals("code", router.route(itemWithUrl("https://github.com/repo/index.ts")).getPipelineId());
        assertEquals("code", router.route(itemWithUrl("/home/user/code/main.go")).getPipelineId());

        // .html should NOT match code extensions
        assertEquals("default", router.route(itemWithUrl("https://example.com/page.html")).getPipelineId());
    }

    @Test
    void fileExtensionMatchingIgnoresQueryString() {
        CrawlConfig config = buildConfig(
                List.of(
                        pipeline("pdf", IngestPipelineDefinition.PipelineType.VLM),
                        pipeline("default", IngestPipelineDefinition.PipelineType.STANDARD_TEXT)
                ),
                List.of(rule("pdf", null, List.of(".pdf"), null, null, 10)),
                "default"
        );

        CrawlPipelineRouter router = new CrawlPipelineRouter(config);

        // URL with query string
        assertEquals("pdf", router.route(itemWithUrl("https://example.com/doc.pdf?token=abc")).getPipelineId());

        // URL with fragment
        assertEquals("pdf", router.route(itemWithUrl("https://example.com/doc.pdf#page=3")).getPipelineId());
    }

    // ---- URL pattern routing ----

    @Test
    void routesByUrlPattern() {
        CrawlConfig config = buildConfig(
                List.of(
                        pipeline("api-docs", IngestPipelineDefinition.PipelineType.CODE),
                        pipeline("default", IngestPipelineDefinition.PipelineType.STANDARD_TEXT)
                ),
                List.of(rule("api-docs", null, null, List.of(".*/api/docs/.*"), null, 10)),
                "default"
        );

        CrawlPipelineRouter router = new CrawlPipelineRouter(config);

        assertEquals("api-docs",
                router.route(itemWithUrl("https://example.com/api/docs/v2/endpoints")).getPipelineId());
        assertEquals("default",
                router.route(itemWithUrl("https://example.com/blog/post-1")).getPipelineId());
    }

    @Test
    void routesByMultipleUrlPatterns() {
        CrawlConfig config = buildConfig(
                List.of(
                        pipeline("docs", IngestPipelineDefinition.PipelineType.STANDARD_TEXT),
                        pipeline("default", IngestPipelineDefinition.PipelineType.STANDARD_TEXT)
                ),
                List.of(rule("docs", null, null, List.of(".*/docs/.*", ".*/wiki/.*"), null, 10)),
                "default"
        );

        CrawlPipelineRouter router = new CrawlPipelineRouter(config);

        assertEquals("docs", router.route(itemWithUrl("https://example.com/docs/guide")).getPipelineId());
        assertEquals("docs", router.route(itemWithUrl("https://example.com/wiki/Home")).getPipelineId());
        assertEquals("default", router.route(itemWithUrl("https://example.com/about")).getPipelineId());
    }

    // ---- Source type routing ----

    @Test
    void routesBySourceType() {
        CrawlConfig config = buildConfig(
                List.of(
                        pipeline("web", IngestPipelineDefinition.PipelineType.STANDARD_TEXT),
                        pipeline("files", IngestPipelineDefinition.PipelineType.STANDARD_TEXT)
                ),
                List.of(
                        rule("web", null, null, null, List.of(DocumentSourceDescriptor.SourceType.URL), 10),
                        rule("files", null, null, null, List.of(DocumentSourceDescriptor.SourceType.FILE), 10)
                ),
                "web"
        );

        CrawlPipelineRouter router = new CrawlPipelineRouter(config);

        CrawlItem webItem = CrawlItem.builder()
                .url("https://example.com")
                .sourceDescriptor(DocumentSourceDescriptor.builder()
                        .type(DocumentSourceDescriptor.SourceType.URL)
                        .build())
                .build();
        assertEquals("web", router.route(webItem).getPipelineId());

        CrawlItem fileItem = CrawlItem.builder()
                .url("/home/user/doc.txt")
                .sourceDescriptor(DocumentSourceDescriptor.builder()
                        .type(DocumentSourceDescriptor.SourceType.FILE)
                        .build())
                .build();
        assertEquals("files", router.route(fileItem).getPipelineId());
    }

    // ---- Size range routing ----

    @Test
    void routesBySizeRange() {
        ContentRouteRule smallRule = ContentRouteRule.builder()
                .pipelineId("small")
                .maxSizeBytes(1024L)
                .priority(10)
                .build();
        ContentRouteRule largeRule = ContentRouteRule.builder()
                .pipelineId("large")
                .minSizeBytes(1025L)
                .priority(20)
                .build();

        CrawlConfig config = buildConfig(
                List.of(
                        pipeline("small", IngestPipelineDefinition.PipelineType.STANDARD_TEXT),
                        pipeline("large", IngestPipelineDefinition.PipelineType.VLM),
                        pipeline("default", IngestPipelineDefinition.PipelineType.STANDARD_TEXT)
                ),
                List.of(smallRule, largeRule),
                "default"
        );

        CrawlPipelineRouter router = new CrawlPipelineRouter(config);

        CrawlItem smallItem = CrawlItem.builder().url("small.txt").contentLength(512L).build();
        assertEquals("small", router.route(smallItem).getPipelineId());

        CrawlItem largeItem = CrawlItem.builder().url("big.bin").contentLength(10_000L).build();
        assertEquals("large", router.route(largeItem).getPipelineId());

        // Unknown size should not be filtered by size constraints → falls through to default
        CrawlItem unknownSize = CrawlItem.builder().url("unknown.dat").build();
        // Unknown size passes size check (treated as "don't filter"), so the small rule matches
        assertEquals("small", router.route(unknownSize).getPipelineId());
    }

    // ---- Priority ordering ----

    @Test
    void lowerPriorityNumberEvaluatedFirst() {
        CrawlConfig config = buildConfig(
                List.of(
                        pipeline("high-priority", IngestPipelineDefinition.PipelineType.VLM),
                        pipeline("low-priority", IngestPipelineDefinition.PipelineType.STANDARD_TEXT)
                ),
                List.of(
                        // Both rules match application/pdf, but high-priority has lower number
                        rule("low-priority", List.of("application/pdf"), null, null, null, 100),
                        rule("high-priority", List.of("application/pdf"), null, null, null, 5)
                ),
                "low-priority"
        );

        CrawlPipelineRouter router = new CrawlPipelineRouter(config);

        CrawlItem pdfItem = itemWithContentType("doc.pdf", "application/pdf");
        assertEquals("high-priority", router.route(pdfItem).getPipelineId());
    }

    // ---- Default pipeline fallback ----

    @Test
    void fallsBackToDefaultPipelineWhenNoRuleMatches() {
        CrawlConfig config = buildConfig(
                List.of(
                        pipeline("pdf", IngestPipelineDefinition.PipelineType.VLM),
                        pipeline("html", IngestPipelineDefinition.PipelineType.STANDARD_TEXT)
                ),
                List.of(rule("pdf", List.of("application/pdf"), null, null, null, 10)),
                "html"
        );

        CrawlPipelineRouter router = new CrawlPipelineRouter(config);

        CrawlItem xmlItem = itemWithContentType("data.xml", "application/xml");
        assertEquals("html", router.route(xmlItem).getPipelineId());
    }

    @Test
    void defaultPipelineIsFirstWhenDefaultPipelineIdNotSet() {
        CrawlConfig config = CrawlConfig.builder()
                .seed("https://example.com")
                .pipelines(List.of(
                        pipeline("first", IngestPipelineDefinition.PipelineType.STANDARD_TEXT),
                        pipeline("second", IngestPipelineDefinition.PipelineType.VLM)
                ))
                .build();

        CrawlPipelineRouter router = new CrawlPipelineRouter(config);

        assertEquals("first", router.getDefaultPipeline().getPipelineId());
    }

    // ---- Backward compatibility ----

    @Test
    void createsSyntheticDefaultWhenNoPipelinesDefined() {
        CrawlConfig config = CrawlConfig.builder()
                .seed("https://example.com")
                .loaderName("myLoader")
                .chunkerName("myChunker")
                .collectionName("myCollection")
                .build();

        CrawlPipelineRouter router = new CrawlPipelineRouter(config);

        IngestPipelineDefinition defaultPipeline = router.getDefaultPipeline();
        assertEquals("default", defaultPipeline.getPipelineId());
        assertEquals("myLoader", defaultPipeline.getLoaderName());
        assertEquals("myChunker", defaultPipeline.getChunkerName());
        assertEquals("myCollection", defaultPipeline.getCollectionName());
        assertEquals(IngestPipelineDefinition.PipelineType.STANDARD_TEXT, defaultPipeline.getPipelineType());
    }

    @Test
    void syntheticDefaultRoutesEverything() {
        CrawlConfig config = CrawlConfig.builder()
                .seed("https://example.com")
                .build();

        CrawlPipelineRouter router = new CrawlPipelineRouter(config);

        // Everything should go to the synthetic default
        assertEquals("default",
                router.route(itemWithContentType("a.pdf", "application/pdf")).getPipelineId());
        assertEquals("default",
                router.route(itemWithUrl("https://example.com/page.html")).getPipelineId());
    }

    // ---- AND logic across conditions ----

    @Test
    void allConditionsMustMatchForRule() {
        // Rule requires BOTH content type AND file extension to match
        ContentRouteRule strictRule = ContentRouteRule.builder()
                .pipelineId("strict")
                .contentTypes(List.of("application/pdf"))
                .fileExtensions(List.of(".pdf"))
                .priority(10)
                .build();

        CrawlConfig config = buildConfig(
                List.of(
                        pipeline("strict", IngestPipelineDefinition.PipelineType.VLM),
                        pipeline("default", IngestPipelineDefinition.PipelineType.STANDARD_TEXT)
                ),
                List.of(strictRule),
                "default"
        );

        CrawlPipelineRouter router = new CrawlPipelineRouter(config);

        // Both conditions met → matches
        CrawlItem fullMatch = CrawlItem.builder()
                .url("https://example.com/report.pdf")
                .contentType("application/pdf")
                .build();
        assertEquals("strict", router.route(fullMatch).getPipelineId());

        // Content type matches but extension doesn't → falls to default
        CrawlItem partialMatch = CrawlItem.builder()
                .url("https://example.com/report.docx")
                .contentType("application/pdf")
                .build();
        assertEquals("default", router.route(partialMatch).getPipelineId());
    }

    // ---- routeWithDetails ----

    @Test
    void routeWithDetailsReturnsMatchedRule() {
        ContentRouteRule pdfRule = rule("pdf", List.of("application/pdf"), null, null, null, 10);

        CrawlConfig config = buildConfig(
                List.of(
                        pipeline("pdf", IngestPipelineDefinition.PipelineType.VLM),
                        pipeline("default", IngestPipelineDefinition.PipelineType.STANDARD_TEXT)
                ),
                List.of(pdfRule),
                "default"
        );

        CrawlPipelineRouter router = new CrawlPipelineRouter(config);

        RoutedCrawlItem routed = router.routeWithDetails(
                itemWithContentType("doc.pdf", "application/pdf"));

        assertEquals("pdf", routed.pipeline().getPipelineId());
        assertNotNull(routed.matchedRule());
        assertEquals("pdf", routed.matchedRule().getPipelineId());
    }

    @Test
    void routeWithDetailsReturnsNullRuleForDefaultFallback() {
        CrawlConfig config = buildConfig(
                List.of(pipeline("default", IngestPipelineDefinition.PipelineType.STANDARD_TEXT)),
                List.of(),
                "default"
        );

        CrawlPipelineRouter router = new CrawlPipelineRouter(config);

        RoutedCrawlItem routed = router.routeWithDetails(
                itemWithContentType("anything.xyz", "application/octet-stream"));

        assertEquals("default", routed.pipeline().getPipelineId());
        assertNull(routed.matchedRule());
    }

    // ---- Pipeline lookup ----

    @Test
    void getAllPipelinesReturnsAll() {
        CrawlConfig config = buildConfig(
                List.of(
                        pipeline("a", IngestPipelineDefinition.PipelineType.STANDARD_TEXT),
                        pipeline("b", IngestPipelineDefinition.PipelineType.VLM),
                        pipeline("c", IngestPipelineDefinition.PipelineType.CODE)
                ),
                List.of(),
                "a"
        );

        CrawlPipelineRouter router = new CrawlPipelineRouter(config);
        assertEquals(3, router.getAllPipelines().size());
    }

    @Test
    void getPipelineByIdReturnsCorrectPipeline() {
        CrawlConfig config = buildConfig(
                List.of(
                        pipeline("html", IngestPipelineDefinition.PipelineType.STANDARD_TEXT),
                        pipeline("pdf", IngestPipelineDefinition.PipelineType.VLM)
                ),
                List.of(),
                "html"
        );

        CrawlPipelineRouter router = new CrawlPipelineRouter(config);

        assertTrue(router.getPipeline("pdf").isPresent());
        assertEquals(IngestPipelineDefinition.PipelineType.VLM,
                router.getPipeline("pdf").get().getPipelineType());

        assertTrue(router.getPipeline("nonexistent").isEmpty());
    }

    // ---- Invalid rule handling ----

    @Test
    void skipsRulesReferencingUnknownPipeline() {
        CrawlConfig config = buildConfig(
                List.of(pipeline("html", IngestPipelineDefinition.PipelineType.STANDARD_TEXT)),
                List.of(
                        rule("nonexistent-pipeline", List.of("application/pdf"), null, null, null, 10),
                        rule("html", List.of("text/html"), null, null, null, 20)
                ),
                "html"
        );

        // Should not throw; the bad rule is silently skipped
        CrawlPipelineRouter router = new CrawlPipelineRouter(config);

        // PDF has no valid rule → falls to default
        assertEquals("html",
                router.route(itemWithContentType("doc.pdf", "application/pdf")).getPipelineId());
        // HTML rule still works
        assertEquals("html",
                router.route(itemWithContentType("page.html", "text/html")).getPipelineId());
    }

    // ---- Language routing ----

    @Test
    void routesByLanguage() {
        ContentRouteRule germanRule = ContentRouteRule.builder()
                .pipelineId("german")
                .languages(List.of("de"))
                .priority(10)
                .build();
        ContentRouteRule frenchRule = ContentRouteRule.builder()
                .pipelineId("french")
                .languages(List.of("fr"))
                .priority(10)
                .build();

        CrawlConfig config = buildConfig(
                List.of(
                        pipeline("german", IngestPipelineDefinition.PipelineType.STANDARD_TEXT),
                        pipeline("french", IngestPipelineDefinition.PipelineType.STANDARD_TEXT),
                        pipeline("default", IngestPipelineDefinition.PipelineType.STANDARD_TEXT)
                ),
                List.of(germanRule, frenchRule),
                "default"
        );

        CrawlPipelineRouter router = new CrawlPipelineRouter(config);

        CrawlItem germanItem = itemWithLanguage("https://example.com/seite", "de", 0.95);
        assertEquals("german", router.route(germanItem).getPipelineId());

        CrawlItem frenchItem = itemWithLanguage("https://example.com/page", "fr", 0.90);
        assertEquals("french", router.route(frenchItem).getPipelineId());

        // English item has no matching language rule → falls to default
        CrawlItem englishItem = itemWithLanguage("https://example.com/page", "en", 0.99);
        assertEquals("default", router.route(englishItem).getPipelineId());
    }

    @Test
    void routesByLanguageMultipleCodes() {
        ContentRouteRule multilingualRule = ContentRouteRule.builder()
                .pipelineId("multilingual")
                .languages(List.of("de", "fr", "es", "zh"))
                .priority(5)
                .build();

        CrawlConfig config = buildConfig(
                List.of(
                        pipeline("multilingual", IngestPipelineDefinition.PipelineType.STANDARD_TEXT),
                        pipeline("default", IngestPipelineDefinition.PipelineType.STANDARD_TEXT)
                ),
                List.of(multilingualRule),
                "default"
        );

        CrawlPipelineRouter router = new CrawlPipelineRouter(config);

        assertEquals("multilingual",
                router.route(itemWithLanguage("a", "de", 0.90)).getPipelineId());
        assertEquals("multilingual",
                router.route(itemWithLanguage("b", "zh", 0.85)).getPipelineId());
        assertEquals("default",
                router.route(itemWithLanguage("c", "en", 0.99)).getPipelineId());
    }

    @Test
    void languageMatchingIsCaseInsensitive() {
        ContentRouteRule rule = ContentRouteRule.builder()
                .pipelineId("german")
                .languages(List.of("DE"))
                .priority(10)
                .build();

        CrawlConfig config = buildConfig(
                List.of(
                        pipeline("german", IngestPipelineDefinition.PipelineType.STANDARD_TEXT),
                        pipeline("default", IngestPipelineDefinition.PipelineType.STANDARD_TEXT)
                ),
                List.of(rule),
                "default"
        );

        CrawlPipelineRouter router = new CrawlPipelineRouter(config);

        // Item has lowercase "de", rule has uppercase "DE" → should still match
        assertEquals("german",
                router.route(itemWithLanguage("a", "de", 0.90)).getPipelineId());
    }

    @Test
    void languageConfidenceBelowThresholdDoesNotMatch() {
        ContentRouteRule rule = ContentRouteRule.builder()
                .pipelineId("german")
                .languages(List.of("de"))
                .minLanguageConfidence(0.80)
                .priority(10)
                .build();

        CrawlConfig config = buildConfig(
                List.of(
                        pipeline("german", IngestPipelineDefinition.PipelineType.STANDARD_TEXT),
                        pipeline("default", IngestPipelineDefinition.PipelineType.STANDARD_TEXT)
                ),
                List.of(rule),
                "default"
        );

        CrawlPipelineRouter router = new CrawlPipelineRouter(config);

        // High confidence → matches
        CrawlItem highConf = itemWithLanguage("a", "de", 0.95);
        assertEquals("german", router.route(highConf).getPipelineId());

        // Low confidence → doesn't match language rule, falls to default
        CrawlItem lowConf = itemWithLanguage("b", "de", 0.50);
        assertEquals("default", router.route(lowConf).getPipelineId());

        // Exactly at threshold → matches (not strictly less than)
        CrawlItem atThreshold = itemWithLanguage("c", "de", 0.80);
        assertEquals("german", router.route(atThreshold).getPipelineId());
    }

    @Test
    void noLanguageOnItemFallsToDefaultWhenLanguageRuleSet() {
        ContentRouteRule rule = ContentRouteRule.builder()
                .pipelineId("german")
                .languages(List.of("de"))
                .priority(10)
                .build();

        CrawlConfig config = buildConfig(
                List.of(
                        pipeline("german", IngestPipelineDefinition.PipelineType.STANDARD_TEXT),
                        pipeline("default", IngestPipelineDefinition.PipelineType.STANDARD_TEXT)
                ),
                List.of(rule),
                "default"
        );

        CrawlPipelineRouter router = new CrawlPipelineRouter(config);

        // Item has no language → language rule requires one → doesn't match → default
        CrawlItem noLang = CrawlItem.builder().url("https://example.com/page").build();
        assertEquals("default", router.route(noLang).getPipelineId());
    }

    @Test
    void languageRuleCombinesWithContentType() {
        // Rule requires BOTH language=de AND content-type=text/html
        ContentRouteRule strictRule = ContentRouteRule.builder()
                .pipelineId("german-html")
                .languages(List.of("de"))
                .contentTypes(List.of("text/html"))
                .priority(10)
                .build();

        CrawlConfig config = buildConfig(
                List.of(
                        pipeline("german-html", IngestPipelineDefinition.PipelineType.STANDARD_TEXT),
                        pipeline("default", IngestPipelineDefinition.PipelineType.STANDARD_TEXT)
                ),
                List.of(strictRule),
                "default"
        );

        CrawlPipelineRouter router = new CrawlPipelineRouter(config);

        // Both match → routes to german-html
        CrawlItem germanHtml = CrawlItem.builder()
                .url("https://example.de/seite")
                .contentType("text/html")
                .language("de")
                .languageConfidence(0.95)
                .build();
        assertEquals("german-html", router.route(germanHtml).getPipelineId());

        // Language matches, content type doesn't → default
        CrawlItem germanPdf = CrawlItem.builder()
                .url("https://example.de/doc.pdf")
                .contentType("application/pdf")
                .language("de")
                .languageConfidence(0.95)
                .build();
        assertEquals("default", router.route(germanPdf).getPipelineId());

        // Content type matches, language doesn't → default
        CrawlItem englishHtml = CrawlItem.builder()
                .url("https://example.com/page")
                .contentType("text/html")
                .language("en")
                .languageConfidence(0.99)
                .build();
        assertEquals("default", router.route(englishHtml).getPipelineId());
    }

    @Test
    void nullLanguageListMatchesAnyLanguage() {
        // Rule with no language constraint matches items regardless of language
        ContentRouteRule rule = ContentRouteRule.builder()
                .pipelineId("catch-all")
                .contentTypes(List.of("text/html"))
                .priority(10)
                .build();

        CrawlConfig config = buildConfig(
                List.of(
                        pipeline("catch-all", IngestPipelineDefinition.PipelineType.STANDARD_TEXT),
                        pipeline("default", IngestPipelineDefinition.PipelineType.STANDARD_TEXT)
                ),
                List.of(rule),
                "default"
        );

        CrawlPipelineRouter router = new CrawlPipelineRouter(config);

        // German HTML → matches (no language filter on rule)
        CrawlItem germanHtml = CrawlItem.builder()
                .url("a").contentType("text/html")
                .language("de").languageConfidence(0.95)
                .build();
        assertEquals("catch-all", router.route(germanHtml).getPipelineId());

        // No language HTML → still matches (null language constraint = match any)
        CrawlItem noLangHtml = CrawlItem.builder()
                .url("b").contentType("text/html")
                .build();
        assertEquals("catch-all", router.route(noLangHtml).getPipelineId());
    }

    // ---- Helpers ----

    private static IngestPipelineDefinition pipeline(String id, IngestPipelineDefinition.PipelineType type) {
        return IngestPipelineDefinition.builder()
                .pipelineId(id)
                .displayName(id)
                .pipelineType(type)
                .build();
    }

    private static ContentRouteRule rule(
            String pipelineId,
            List<String> contentTypes,
            List<String> fileExtensions,
            List<String> urlPatterns,
            List<DocumentSourceDescriptor.SourceType> sourceTypes,
            int priority) {
        return ContentRouteRule.builder()
                .pipelineId(pipelineId)
                .contentTypes(contentTypes)
                .fileExtensions(fileExtensions)
                .urlPatterns(urlPatterns)
                .sourceTypes(sourceTypes)
                .priority(priority)
                .build();
    }

    private static CrawlConfig buildConfig(
            List<IngestPipelineDefinition> pipelines,
            List<ContentRouteRule> rules,
            String defaultPipelineId) {
        return CrawlConfig.builder()
                .seed("https://example.com")
                .pipelines(pipelines)
                .routeRules(rules)
                .defaultPipelineId(defaultPipelineId)
                .build();
    }

    private static CrawlItem itemWithContentType(String url, String contentType) {
        return CrawlItem.builder()
                .url(url)
                .contentType(contentType)
                .build();
    }

    private static CrawlItem itemWithUrl(String url) {
        return CrawlItem.builder()
                .url(url)
                .build();
    }

    private static CrawlItem itemWithLanguage(String url, String language, double confidence) {
        return CrawlItem.builder()
                .url(url)
                .language(language)
                .languageConfidence(confidence)
                .languageSource("detected")
                .build();
    }
}
