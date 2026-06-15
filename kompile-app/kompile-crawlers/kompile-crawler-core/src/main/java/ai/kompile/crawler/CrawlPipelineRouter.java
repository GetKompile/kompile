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
import ai.kompile.core.crawler.pipeline.*;
import ai.kompile.core.loaders.DocumentSourceDescriptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Routes crawl items to the correct {@link IngestPipelineDefinition} based on
 * content type, file extension, URL patterns, source type, and file size.
 *
 * <p>Built from a {@link CrawlConfig}'s pipeline definitions and route rules.
 * Rules are evaluated in priority order (lowest number first); the first
 * matching rule wins. Items that match no rule go to the default pipeline.</p>
 *
 * <p>This class is constructed per crawl job and is thread-safe for concurrent
 * item routing.</p>
 */
public class CrawlPipelineRouter implements ContentRouter {

    private static final Logger log = LoggerFactory.getLogger(CrawlPipelineRouter.class);

    private final Map<String, IngestPipelineDefinition> pipelinesById;
    private final List<CompiledRule> compiledRules;
    private final IngestPipelineDefinition defaultPipeline;
    private final CrawlLanguageDetector languageDetector;

    /**
     * Creates a router from a CrawlConfig without language detection.
     * If the config has no pipelines defined, a synthetic default pipeline
     * is created from the config's loaderName/chunkerName.
     */
    public CrawlPipelineRouter(CrawlConfig config) {
        this(config, null);
    }

    /**
     * Creates a router from a CrawlConfig with optional language detection.
     * When a {@link CrawlLanguageDetector} is provided, items are enriched
     * with language metadata before route rules are evaluated.
     */
    public CrawlPipelineRouter(CrawlConfig config, CrawlLanguageDetector languageDetector) {
        this.languageDetector = languageDetector;
        // Index pipelines by ID
        Map<String, IngestPipelineDefinition> byId = new LinkedHashMap<>();
        if (config.getPipelines() != null) {
            for (IngestPipelineDefinition p : config.getPipelines()) {
                byId.put(p.getPipelineId(), p);
            }
        }

        // If no pipelines defined, create a default from the config's global settings
        if (byId.isEmpty()) {
            IngestPipelineDefinition fallback = IngestPipelineDefinition.builder()
                    .pipelineId("default")
                    .displayName("Default Pipeline")
                    .pipelineType(IngestPipelineDefinition.PipelineType.STANDARD_TEXT)
                    .loaderName(config.getLoaderName())
                    .chunkerName(config.getChunkerName())
                    .collectionName(config.getCollectionName())
                    .build();
            byId.put("default", fallback);
        }

        this.pipelinesById = Collections.unmodifiableMap(byId);

        // Resolve default pipeline
        String defaultId = config.getDefaultPipelineId();
        if (defaultId != null && byId.containsKey(defaultId)) {
            this.defaultPipeline = byId.get(defaultId);
        } else {
            // First pipeline is the default
            this.defaultPipeline = byId.values().iterator().next();
        }

        // Compile and sort rules by priority
        List<CompiledRule> rules = new ArrayList<>();
        if (config.getRouteRules() != null) {
            for (ContentRouteRule rule : config.getRouteRules()) {
                if (byId.containsKey(rule.getPipelineId())) {
                    rules.add(new CompiledRule(rule, byId.get(rule.getPipelineId())));
                } else {
                    log.warn("Route rule references unknown pipeline '{}', skipping", rule.getPipelineId());
                }
            }
        }
        rules.sort(Comparator.comparingInt(r -> r.rule.getPriority()));
        this.compiledRules = Collections.unmodifiableList(rules);

        log.debug("Router initialized: {} pipelines, {} rules, default='{}'",
                pipelinesById.size(), compiledRules.size(), defaultPipeline.getPipelineId());
    }

    @Override
    public IngestPipelineDefinition route(CrawlItem item) {
        enrichLanguageIfNeeded(item);
        for (CompiledRule compiled : compiledRules) {
            if (compiled.matches(item)) {
                log.trace("Item '{}' matched rule → pipeline '{}'",
                        item.getUrl(), compiled.pipeline.getPipelineId());
                return compiled.pipeline;
            }
        }
        return defaultPipeline;
    }

    @Override
    public IngestPipelineDefinition getDefaultPipeline() {
        return defaultPipeline;
    }

    /** Get a pipeline by ID */
    public Optional<IngestPipelineDefinition> getPipeline(String pipelineId) {
        return Optional.ofNullable(pipelinesById.get(pipelineId));
    }

    /** Get all configured pipelines */
    public Collection<IngestPipelineDefinition> getAllPipelines() {
        return pipelinesById.values();
    }

    /**
     * Routes an item and returns the full routing decision.
     */
    public RoutedCrawlItem routeWithDetails(CrawlItem item) {
        enrichLanguageIfNeeded(item);
        for (CompiledRule compiled : compiledRules) {
            if (compiled.matches(item)) {
                return new RoutedCrawlItem(item, compiled.pipeline, compiled.rule);
            }
        }
        return new RoutedCrawlItem(item, defaultPipeline, null);
    }

    private void enrichLanguageIfNeeded(CrawlItem item) {
        if (languageDetector == null || item.getLanguage() != null) {
            return;
        }
        // Try to extract text from metadata for detection
        String textSample = null;
        if (item.getMetadata() != null) {
            Object sample = item.getMetadata().get("contentSample");
            if (sample instanceof String s && !s.isBlank()) {
                textSample = s;
            }
        }
        if (textSample != null) {
            languageDetector.enrichWithLanguage(item, textSample);
        }
    }

    // ---- Internal ----

    private static class CompiledRule {
        final ContentRouteRule rule;
        final IngestPipelineDefinition pipeline;
        final List<Pattern> urlPatterns;

        CompiledRule(ContentRouteRule rule, IngestPipelineDefinition pipeline) {
            this.rule = rule;
            this.pipeline = pipeline;

            // Pre-compile URL patterns
            if (rule.getUrlPatterns() != null && !rule.getUrlPatterns().isEmpty()) {
                List<Pattern> compiled = new ArrayList<>();
                for (String p : rule.getUrlPatterns()) {
                    try {
                        compiled.add(Pattern.compile(p));
                    } catch (Exception e) {
                        log.warn("Invalid URL pattern '{}' in route rule: {}", p, e.getMessage());
                    }
                }
                this.urlPatterns = compiled;
            } else {
                this.urlPatterns = List.of();
            }
        }

        boolean matches(CrawlItem item) {
            // All specified conditions must match (AND logic)
            // Unset conditions are treated as "match anything"

            if (!matchesContentType(item)) return false;
            if (!matchesFileExtension(item)) return false;
            if (!matchesUrlPattern(item)) return false;
            if (!matchesSourceType(item)) return false;
            if (!matchesSizeRange(item)) return false;
            if (!matchesLanguage(item)) return false;

            return true;
        }

        private boolean matchesContentType(CrawlItem item) {
            List<String> types = rule.getContentTypes();
            if (types == null || types.isEmpty()) return true;
            String itemType = item.getContentType();
            if (itemType == null) return false;
            String normalizedType = itemType.toLowerCase().split(";")[0].trim();

            for (String pattern : types) {
                String normalized = pattern.toLowerCase().trim();
                if (normalized.endsWith("/*")) {
                    // Wildcard: "image/*" matches "image/png"
                    String prefix = normalized.substring(0, normalized.length() - 1);
                    if (normalizedType.startsWith(prefix)) return true;
                } else if (normalizedType.equals(normalized)) {
                    return true;
                }
            }
            return false;
        }

        private boolean matchesFileExtension(CrawlItem item) {
            List<String> extensions = rule.getFileExtensions();
            if (extensions == null || extensions.isEmpty()) return true;
            String url = item.getUrl();
            if (url == null) return false;

            String lower = url.toLowerCase();
            // Strip query string / fragment for extension matching
            int queryIdx = lower.indexOf('?');
            if (queryIdx > 0) lower = lower.substring(0, queryIdx);
            int fragIdx = lower.indexOf('#');
            if (fragIdx > 0) lower = lower.substring(0, fragIdx);

            for (String ext : extensions) {
                if (lower.endsWith(ext.toLowerCase())) return true;
            }
            return false;
        }

        private boolean matchesUrlPattern(CrawlItem item) {
            if (urlPatterns.isEmpty()) return true;
            String url = item.getUrl();
            if (url == null) return false;

            for (Pattern p : urlPatterns) {
                if (p.matcher(url).find()) return true;
            }
            return false;
        }

        private boolean matchesSourceType(CrawlItem item) {
            List<DocumentSourceDescriptor.SourceType> types = rule.getSourceTypes();
            if (types == null || types.isEmpty()) return true;
            if (item.getSourceDescriptor() == null) return false;
            return types.contains(item.getSourceDescriptor().getType());
        }

        private boolean matchesSizeRange(CrawlItem item) {
            Long minSize = rule.getMinSizeBytes();
            Long maxSize = rule.getMaxSizeBytes();
            if (minSize == null && maxSize == null) return true;

            Long itemSize = item.getContentLength();
            if (itemSize == null) return true; // Unknown size → don't filter

            if (minSize != null && itemSize < minSize) return false;
            if (maxSize != null && itemSize > maxSize) return false;
            return true;
        }

        private boolean matchesLanguage(CrawlItem item) {
            List<String> langs = rule.getLanguages();
            if (langs == null || langs.isEmpty()) return true;

            String itemLang = item.getLanguage();
            if (itemLang == null) return false; // Language required but not detected

            // Check confidence threshold if specified
            Double minConf = rule.getMinLanguageConfidence();
            if (minConf != null && item.getLanguageConfidence() != null
                    && item.getLanguageConfidence() < minConf) {
                return false;
            }

            String normalizedLang = itemLang.toLowerCase();
            for (String lang : langs) {
                if (normalizedLang.equals(lang.toLowerCase())) return true;
            }
            return false;
        }
    }
}
