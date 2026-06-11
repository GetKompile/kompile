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

import ai.kompile.core.crawler.*;
import ai.kompile.core.graphrag.GraphConstants;
import ai.kompile.core.loaders.DocumentSourceDescriptor;
import ai.kompile.core.loaders.DocumentSourceDescriptor.SourceType;
import ai.kompile.crawler.AbstractCrawlJob;
import ai.kompile.crawler.AbstractCrawler;
import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Recursive web crawler that follows links from a seed URL.
 *
 * <p>Features:</p>
 * <ul>
 *   <li>Breadth-first crawling up to configurable depth</li>
 *   <li>Same-domain restriction (configurable)</li>
 *   <li>Rate limiting between requests</li>
 *   <li>Basic robots.txt compliance</li>
 *   <li>URL normalization and deduplication</li>
 *   <li>Include/exclude pattern filtering</li>
 *   <li>Content-hash-based incremental crawling</li>
 *   <li>Pause/resume/cancel support</li>
 * </ul>
 */
@Component
public class WebCrawler extends AbstractCrawler {

    private static final Logger log = LoggerFactory.getLogger(WebCrawler.class);
    private static final int CONNECTION_TIMEOUT_MS = 30_000;
    private static final int MAX_BODY_SIZE = 10 * 1024 * 1024; // 10MB

    @Override
    public String getId() {
        return "web";
    }

    @Override
    public String getName() {
        return "Web Crawler";
    }

    @Override
    public String getDescription() {
        return "Recursively crawls websites following links from a seed URL, discovering pages for indexing";
    }

    @Override
    public Set<SourceType> getSupportedSourceTypes() {
        return Set.of(SourceType.URL, SourceType.WEB_CRAWL);
    }

    @Override
    protected List<String> validateSpecific(CrawlConfig config) {
        List<String> errors = new ArrayList<>();
        String seed = config.getSeed();
        if (seed != null && !seed.isBlank()) {
            try {
                URI uri = URI.create(seed);
                String scheme = uri.getScheme();
                if (scheme == null || (!scheme.equals("http") && !scheme.equals("https"))) {
                    errors.add("Seed URL must use http or https scheme");
                }
            } catch (IllegalArgumentException e) {
                errors.add("Invalid seed URL: " + e.getMessage());
            }
        }
        return errors;
    }

    @Override
    protected AbstractCrawlJob createJob(String jobId, CrawlConfig config, CrawlEventListener listener) {
        return new WebCrawlJob(jobId, config, listener);
    }

    @Override
    protected void executeCrawl(AbstractCrawlJob job) throws Exception {
        WebCrawlJob webJob = (WebCrawlJob) job;
        CrawlConfig config = job.getConfig();
        String seed = config.getSeed();

        URI seedUri = URI.create(seed);
        String seedDomain = seedUri.getHost();
        Set<String> disallowedPaths = config.isRespectRobotsTxt()
                ? fetchRobotsTxt(seedUri, config.getUserAgent())
                : Collections.emptySet();

        // Compile include/exclude patterns
        List<Pattern> includes = compilePatterns(config.getIncludePatterns());
        List<Pattern> excludes = compilePatterns(config.getExcludePatterns());

        // BFS frontier: use the job's shared deque so checkpoints can snapshot it
        Deque<Map.Entry<String, Integer>> frontier = webJob.pendingFrontier;
        // Only seed the frontier if it's empty (not resuming from checkpoint)
        String normalizedSeed = normalizeUrl(seed);
        if (frontier.isEmpty()) {
            frontier.add(new AbstractMap.SimpleImmutableEntry<>(normalizedSeed, 0));
        }
        webJob.visitedUrls.add(normalizedSeed);

        while (!frontier.isEmpty()) {
            if (webJob.shouldStop()) break;
            if (!webJob.checkPauseAndContinue()) break;

            Map.Entry<String, Integer> entry = frontier.poll();
            String url = entry.getKey();
            int depth = entry.getValue();
            webJob.setCurrentDepth(depth);
            webJob.setCurrentItem(url);

            // Apply filters
            if (!matchesPatterns(url, includes, excludes)) {
                webJob.incrementSkipped();
                webJob.getListener().onDocumentSkipped(url, "filtered by include/exclude patterns");
                continue;
            }

            // Check robots.txt
            if (isDisallowedByRobots(url, seedUri, disallowedPaths)) {
                webJob.incrementSkipped();
                webJob.getListener().onDocumentSkipped(url, "disallowed by robots.txt");
                continue;
            }

            try {
                // Rate limiting
                if (config.getRequestDelay() != null && !config.getRequestDelay().isZero()) {
                    Thread.sleep(config.getRequestDelay().toMillis());
                }

                // Fetch the page
                Connection.Response response = Jsoup.connect(url)
                        .userAgent(config.getUserAgent())
                        .timeout(CONNECTION_TIMEOUT_MS)
                        .maxBodySize(MAX_BODY_SIZE)
                        .followRedirects(true)
                        .ignoreHttpErrors(true)
                        .execute();

                int statusCode = response.statusCode();
                if (statusCode >= 400) {
                    webJob.recordError(url, new RuntimeException("HTTP " + statusCode));
                    continue;
                }

                String contentType = response.contentType();
                if (contentType != null && !isAcceptableContentType(contentType, config.getAllowedContentTypes())) {
                    webJob.incrementSkipped();
                    webJob.getListener().onDocumentSkipped(url, "content type not accepted: " + contentType);
                    continue;
                }

                Document doc = response.parse();
                String bodyText = doc.body() != null ? doc.body().text() : "";

                // Content hash for incremental crawling
                String contentHash = computeHash(bodyText);
                CrawlState prevState = config.getPreviousState();
                if (prevState != null && !prevState.hasChanged(url, contentHash)) {
                    webJob.incrementSkipped();
                    webJob.getListener().onDocumentSkipped(url, "content unchanged since last crawl");
                    continue;
                }
                webJob.contentHashes.put(url, contentHash);

                // Build CrawlItem
                Map<String, Object> itemMetadata = new LinkedHashMap<>();
                itemMetadata.put(GraphConstants.META_TITLE, doc.title() != null ? doc.title() : "");
                itemMetadata.put(GraphConstants.META_SOURCE_PATH, url);
                itemMetadata.put(GraphConstants.META_FILE_NAME, extractFileName(url));
                itemMetadata.put(GraphConstants.META_LOADER, "Web Crawler");
                itemMetadata.put(GraphConstants.META_DOCUMENT_TYPE, "web_page");
                itemMetadata.put(GraphConstants.META_SOURCE_TYPE, "WEB_CRAWL");
                itemMetadata.put("depth", depth);
                itemMetadata.put("crawlJobId", job.getJobId());

                CrawlItem item = CrawlItem.builder()
                        .url(url)
                        .parentUrl(depth > 0 ? seed : null)
                        .depth(depth)
                        .contentHash(contentHash)
                        .contentType(contentType)
                        .discoveredAt(Instant.now())
                        .sourceDescriptor(DocumentSourceDescriptor.builder()
                                .type(config.getSourceType() != null ? config.getSourceType() : SourceType.WEB_CRAWL)
                                .pathOrUrl(url)
                                .sourceId(url)
                                .originalFileName(extractFileName(url))
                                .collectionName(config.getCollectionName())
                                .build())
                        .metadata(itemMetadata)
                        .build();

                webJob.incrementDiscovered();
                webJob.getListener().onDocumentDiscovered(item);
                webJob.incrementProcessed();
                webJob.getListener().onDocumentProcessed(item);

                // Report progress periodically
                if (webJob.getDiscoveredCount() % 10 == 0) {
                    webJob.getListener().onProgress(webJob.getProgress());
                }

                // Extract links for next depth level
                if (depth < config.getMaxDepth()) {
                    Elements links = doc.select("a[href]");
                    for (Element link : links) {
                        String href = link.absUrl("href");
                        if (href.isEmpty()) continue;

                        String normalized = normalizeUrl(href);
                        if (normalized.isEmpty()) continue;

                        // Domain check
                        if (config.isSameDomainOnly()) {
                            try {
                                String linkDomain = URI.create(normalized).getHost();
                                if (!seedDomain.equalsIgnoreCase(linkDomain)) continue;
                            } catch (Exception e) {
                                continue;
                            }
                        }

                        // Dedup
                        if (webJob.visitedUrls.add(normalized)) {
                            frontier.add(new AbstractMap.SimpleImmutableEntry<>(normalized, depth + 1));
                        }
                    }
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.warn("Error crawling {}: {}", url, e.getMessage());
                webJob.recordError(url, e);
            }
        }
    }

    /**
     * Normalizes a URL by removing fragments, normalizing scheme/host case,
     * and removing trailing slashes.
     */
    static String normalizeUrl(String url) {
        try {
            URI uri = URI.create(url);
            String normalized = new URI(
                    uri.getScheme() != null ? uri.getScheme().toLowerCase() : "http",
                    uri.getUserInfo(),
                    uri.getHost() != null ? uri.getHost().toLowerCase() : "",
                    uri.getPort(),
                    uri.getPath(),
                    uri.getQuery(),
                    null  // drop fragment
            ).toString();
            // Remove trailing slash for consistency
            if (normalized.endsWith("/") && normalized.length() > 1) {
                normalized = normalized.substring(0, normalized.length() - 1);
            }
            return normalized;
        } catch (Exception e) {
            return url;
        }
    }

    /**
     * Fetches and parses robots.txt, returning the set of disallowed paths
     * for the given user agent.
     */
    private Set<String> fetchRobotsTxt(URI seedUri, String userAgent) {
        Set<String> disallowed = new HashSet<>();
        try {
            String robotsUrl = seedUri.getScheme() + "://" + seedUri.getHost()
                    + (seedUri.getPort() > 0 ? ":" + seedUri.getPort() : "")
                    + "/robots.txt";
            URL url = URI.create(robotsUrl).toURL();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(url.openStream(), StandardCharsets.UTF_8))) {
                boolean relevantSection = false;
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) continue;

                    if (line.toLowerCase().startsWith("user-agent:")) {
                        String agent = line.substring("user-agent:".length()).trim();
                        relevantSection = agent.equals("*") || userAgent.toLowerCase().contains(agent.toLowerCase());
                    } else if (relevantSection && line.toLowerCase().startsWith("disallow:")) {
                        String path = line.substring("disallow:".length()).trim();
                        if (!path.isEmpty()) {
                            disallowed.add(path);
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Could not fetch robots.txt: {}", e.getMessage());
        }
        return disallowed;
    }

    private boolean isDisallowedByRobots(String url, URI seedUri, Set<String> disallowedPaths) {
        if (disallowedPaths.isEmpty()) return false;
        try {
            String path = URI.create(url).getPath();
            if (path == null) return false;
            for (String disallowed : disallowedPaths) {
                if (path.startsWith(disallowed)) return true;
            }
        } catch (Exception e) {
            // Ignore malformed URLs
        }
        return false;
    }

    private List<Pattern> compilePatterns(List<String> patterns) {
        if (patterns == null || patterns.isEmpty()) return List.of();
        List<Pattern> compiled = new ArrayList<>();
        for (String p : patterns) {
            try {
                compiled.add(Pattern.compile(p));
            } catch (Exception e) {
                log.warn("Invalid pattern '{}': {}", p, e.getMessage());
            }
        }
        return compiled;
    }

    private boolean matchesPatterns(String url, List<Pattern> includes, List<Pattern> excludes) {
        // If includes are specified, URL must match at least one
        if (!includes.isEmpty()) {
            boolean matched = false;
            for (Pattern p : includes) {
                if (p.matcher(url).find()) {
                    matched = true;
                    break;
                }
            }
            if (!matched) return false;
        }
        // If excludes are specified, URL must not match any
        for (Pattern p : excludes) {
            if (p.matcher(url).find()) return false;
        }
        return true;
    }

    private boolean isAcceptableContentType(String contentType, List<String> allowed) {
        if (allowed == null || allowed.isEmpty()) return true;
        String ct = contentType.toLowerCase().split(";")[0].trim();
        for (String a : allowed) {
            if (ct.startsWith(a.toLowerCase().trim())) return true;
        }
        return false;
    }

    private String computeHash(String content) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(content.hashCode());
        }
    }

    private String extractFileName(String url) {
        try {
            String path = URI.create(url).getPath();
            if (path == null || path.isEmpty() || path.equals("/")) return "index.html";
            int lastSlash = path.lastIndexOf('/');
            String name = lastSlash >= 0 ? path.substring(lastSlash + 1) : path;
            return name.isEmpty() ? "index.html" : name;
        } catch (Exception e) {
            return "page.html";
        }
    }
}
