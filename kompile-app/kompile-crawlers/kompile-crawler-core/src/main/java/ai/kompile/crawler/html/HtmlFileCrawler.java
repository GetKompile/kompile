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

package ai.kompile.crawler.html;

import ai.kompile.core.graphrag.GraphConstants;
import ai.kompile.core.crawler.*;
import ai.kompile.core.loaders.DocumentSourceDescriptor;
import ai.kompile.core.loaders.DocumentSourceDescriptor.SourceType;
import ai.kompile.crawler.AbstractCrawlJob;
import ai.kompile.crawler.AbstractCrawler;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Crawler that discovers HTML files by walking a filesystem directory tree.
 *
 * <p>Features:</p>
 * <ul>
 *   <li>Discovers .html, .htm, .xhtml, .shtml files</li>
 *   <li>Extracts links between local HTML files for cross-referencing</li>
 *   <li>Extracts HTML metadata (title, meta tags) via Jsoup</li>
 *   <li>Incremental crawling based on content hash and modification time</li>
 *   <li>Glob and regex pattern filtering (include/exclude)</li>
 *   <li>Pause/resume/cancel support</li>
 * </ul>
 *
 * <p>Crawler-specific properties:</p>
 * <ul>
 *   <li>{@code followLinks} (boolean, default: false) — follow href links to discover additional HTML files within the root</li>
 *   <li>{@code includeHidden} (boolean, default: false) — include hidden files/directories</li>
 *   <li>{@code extractMetadata} (boolean, default: true) — extract HTML title and meta tags</li>
 * </ul>
 */
@Component
public class HtmlFileCrawler extends AbstractCrawler {

    private static final Logger log = LoggerFactory.getLogger(HtmlFileCrawler.class);

    private static final Set<String> HTML_EXTENSIONS = Set.of(
            ".html", ".htm", ".xhtml", ".shtml"
    );

    private static final long MAX_FILE_SIZE = 10 * 1024 * 1024; // 10MB

    @Override
    public String getId() {
        return "html-file";
    }

    @Override
    public String getName() {
        return "HTML File Crawler";
    }

    @Override
    public String getDescription() {
        return "Discovers HTML files by scanning directories, extracting metadata and cross-references between pages";
    }

    @Override
    public Set<SourceType> getSupportedSourceTypes() {
        return Set.of(SourceType.FILE, SourceType.DIRECTORY);
    }

    @Override
    protected List<String> validateSpecific(CrawlConfig config) {
        List<String> errors = new ArrayList<>();
        Path root = Path.of(config.getSeed());
        if (!Files.exists(root)) {
            errors.add("Path does not exist: " + config.getSeed());
        } else if (!Files.isReadable(root)) {
            errors.add("Path is not readable: " + config.getSeed());
        }
        // Allow single file or directory
        if (Files.exists(root) && !Files.isDirectory(root) && !isHtmlFile(root)) {
            errors.add("Path is not an HTML file or directory: " + config.getSeed());
        }
        return errors;
    }

    @Override
    protected AbstractCrawlJob createJob(String jobId, CrawlConfig config, CrawlEventListener listener) {
        return new HtmlFileCrawlJob(jobId, config, listener);
    }

    @Override
    protected void executeCrawl(AbstractCrawlJob job) throws Exception {
        HtmlFileCrawlJob htmlJob = (HtmlFileCrawlJob) job;
        CrawlConfig config = job.getConfig();
        Path root = Path.of(config.getSeed());

        boolean followLinks = Boolean.parseBoolean(
                String.valueOf(config.getProperties().getOrDefault("followLinks", "false")));
        boolean includeHidden = Boolean.parseBoolean(
                String.valueOf(config.getProperties().getOrDefault("includeHidden", "false")));
        boolean extractMetadata = Boolean.parseBoolean(
                String.valueOf(config.getProperties().getOrDefault("extractMetadata", "true")));

        List<Pattern> includes = compilePatterns(config.getIncludePatterns());
        List<Pattern> excludes = compilePatterns(config.getExcludePatterns());

        // If seed is a single HTML file, process just that file
        if (Files.isRegularFile(root) && isHtmlFile(root)) {
            processHtmlFile(root, root.getParent(), htmlJob, config, includes, excludes,
                    includeHidden, extractMetadata);
            return;
        }

        int maxDepth = config.getMaxDepth() > 0 ? config.getMaxDepth() : Integer.MAX_VALUE;

        // Walk the directory tree
        Files.walkFileTree(root, EnumSet.noneOf(FileVisitOption.class), maxDepth, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (htmlJob.shouldStop()) return FileVisitResult.TERMINATE;

                if (!includeHidden && isHidden(dir)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }

                int depth = root.relativize(dir).getNameCount();
                htmlJob.setCurrentDepth(depth);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (htmlJob.shouldStop()) return FileVisitResult.TERMINATE;
                if (!htmlJob.checkPauseAndContinue()) return FileVisitResult.TERMINATE;

                processHtmlFile(file, root, htmlJob, config, includes, excludes,
                        includeHidden, extractMetadata);

                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                log.warn("Failed to visit {}: {}", file, exc.getMessage());
                htmlJob.recordError(file.toString(), new RuntimeException(exc));
                return FileVisitResult.CONTINUE;
            }
        });

        // Follow links discovered during crawl (BFS within the root directory)
        if (followLinks) {
            followDiscoveredLinks(htmlJob, root, config, includes, excludes,
                    includeHidden, extractMetadata);
        }
    }

    private void processHtmlFile(Path file, Path root, HtmlFileCrawlJob job, CrawlConfig config,
                                  List<Pattern> includes, List<Pattern> excludes,
                                  boolean includeHidden, boolean extractMetadata) {
        String filePath = file.toAbsolutePath().toString();
        job.setCurrentItem(filePath);

        // Skip non-HTML files
        if (!isHtmlFile(file)) {
            return;
        }

        // Skip hidden files
        if (!includeHidden && isHidden(file)) {
            job.incrementSkipped();
            job.getListener().onDocumentSkipped(filePath, "hidden file");
            return;
        }

        // Apply include/exclude patterns
        if (!matchesPatterns(filePath, file.getFileName().toString(), includes, excludes)) {
            job.incrementSkipped();
            job.getListener().onDocumentSkipped(filePath, "filtered by include/exclude patterns");
            return;
        }

        // Skip already-visited files (from link following)
        if (job.visitedPaths.contains(filePath)) {
            return;
        }

        // Skip files that are too large
        try {
            if (Files.size(file) > MAX_FILE_SIZE) {
                job.incrementSkipped();
                job.getListener().onDocumentSkipped(filePath, "file too large: " + Files.size(file) + " bytes");
                return;
            }
        } catch (IOException e) {
            job.recordError(filePath, new RuntimeException(e));
            return;
        }

        // Incremental: check content hash
        try {
            BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class);
            long lastModified = attrs.lastModifiedTime().toMillis();

            CrawlState prevState = config.getPreviousState();
            if (prevState != null && !prevState.isModifiedSince(filePath, lastModified)) {
                job.incrementSkipped();
                job.getListener().onDocumentSkipped(filePath, "unchanged since last crawl");
                return;
            }

            // Mark as visited
            job.visitedPaths.add(filePath);
            job.lastModifiedTimes.put(filePath, lastModified);

            // Read and hash content
            String htmlContent = Files.readString(file, StandardCharsets.UTF_8);
            String contentHash = sha256(htmlContent);

            // Check content hash against previous crawl
            if (prevState != null && prevState.getContentHashes() != null) {
                String prevHash = prevState.getContentHashes().get(filePath);
                if (contentHash.equals(prevHash)) {
                    job.incrementSkipped();
                    job.getListener().onDocumentSkipped(filePath, "content unchanged (same hash)");
                    return;
                }
            }

            job.contentHashes.put(filePath, contentHash);

            // Build metadata
            int depth = root.equals(file.getParent()) ? 0 : root.relativize(file).getNameCount();
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put(GraphConstants.META_FILE_SIZE, attrs.size());
            metadata.put(GraphConstants.META_LAST_MODIFIED, lastModified);
            metadata.put(GraphConstants.META_FILE_NAME, file.getFileName().toString());
            metadata.put(GraphConstants.META_SOURCE_PATH, filePath);
            metadata.put(GraphConstants.META_LOADER, "HTML File Crawler");
            metadata.put(GraphConstants.META_DOCUMENT_TYPE, "HTML Document");
            metadata.put(GraphConstants.META_SOURCE_TYPE, "FILE");
            metadata.put("contentHash", contentHash);
            metadata.put("crawlJobId", job.getJobId());

            // Extract HTML-specific metadata
            if (extractMetadata) {
                extractHtmlMetadata(htmlContent, metadata);
            }

            // Extract links for later following
            List<String> localLinks = extractLocalLinks(htmlContent, file, root);
            if (!localLinks.isEmpty()) {
                metadata.put("html.links", localLinks);
                metadata.put("html.linkCount", localLinks.size());
            }

            CrawlItem item = CrawlItem.builder()
                    .url(filePath)
                    .parentUrl(file.getParent().toAbsolutePath().toString())
                    .depth(depth)
                    .contentType("text/html")
                    .contentLength(attrs.size())
                    .discoveredAt(Instant.now())
                    .sourceDescriptor(DocumentSourceDescriptor.builder()
                            .type(SourceType.FILE)
                            .pathOrUrl(filePath)
                            .sourceId(filePath)
                            .originalFileName(file.getFileName().toString())
                            .collectionName(config.getCollectionName())
                            .build())
                    .metadata(metadata)
                    .build();

            job.incrementDiscovered();
            job.getListener().onDocumentDiscovered(item);
            job.incrementProcessed();
            job.getListener().onDocumentProcessed(item);

            if (job.getDiscoveredCount() % 50 == 0) {
                job.getListener().onProgress(job.getProgress());
            }

        } catch (IOException e) {
            log.warn("Failed to process HTML file {}: {}", filePath, e.getMessage());
            job.recordError(filePath, new RuntimeException(e));
        }
    }

    /**
     * BFS follow of href links discovered during the initial walk.
     * Only follows links to files within the root directory.
     */
    private void followDiscoveredLinks(HtmlFileCrawlJob job, Path root, CrawlConfig config,
                                        List<Pattern> includes, List<Pattern> excludes,
                                        boolean includeHidden, boolean extractMetadata) {
        // Gather all unvisited link targets
        Queue<String> queue = new ArrayDeque<>();
        for (String visited : job.visitedPaths) {
            Object links = null;
            // We already stored links in metadata; re-scan from content hashes
            // Instead, just re-read the file to find links (they're lightweight HTML files)
        }

        // Simpler approach: re-scan all visited files for links to unvisited files
        Set<String> toVisit = new LinkedHashSet<>();
        for (String visitedPath : new ArrayList<>(job.visitedPaths)) {
            try {
                Path file = Path.of(visitedPath);
                if (Files.exists(file)) {
                    String html = Files.readString(file, StandardCharsets.UTF_8);
                    List<String> links = extractLocalLinks(html, file, root);
                    for (String link : links) {
                        if (!job.visitedPaths.contains(link) && Files.exists(Path.of(link))) {
                            toVisit.add(link);
                        }
                    }
                }
            } catch (IOException e) {
                log.debug("Could not re-read {} for link extraction: {}", visitedPath, e.getMessage());
            }
        }

        // Process discovered linked files
        for (String linkTarget : toVisit) {
            if (job.shouldStop()) break;
            Path linkFile = Path.of(linkTarget);
            processHtmlFile(linkFile, root, job, config, includes, excludes,
                    includeHidden, extractMetadata);
        }
    }

    /**
     * Extracts HTML metadata (title, meta description, etc.) from parsed HTML.
     */
    private void extractHtmlMetadata(String htmlContent, Map<String, Object> metadata) {
        try {
            org.jsoup.nodes.Document doc = Jsoup.parse(htmlContent);

            String title = doc.title();
            if (title != null && !title.isEmpty()) {
                metadata.put("html.title", title);
                metadata.put(GraphConstants.META_TITLE, title);
            }

            // Meta description
            Element descMeta = doc.selectFirst("meta[name=description]");
            if (descMeta != null) {
                String desc = descMeta.attr("content");
                if (!desc.isEmpty()) {
                    metadata.put("html.description", desc);
                    metadata.put(GraphConstants.META_DESCRIPTION, desc);
                }
            }

            // Meta keywords
            Element keywordsMeta = doc.selectFirst("meta[name=keywords]");
            if (keywordsMeta != null) {
                String keywords = keywordsMeta.attr("content");
                if (!keywords.isEmpty()) {
                    metadata.put("html.keywords", keywords);
                    metadata.put(GraphConstants.META_KEYWORDS, keywords);
                }
            }

            // Author
            Element authorMeta = doc.selectFirst("meta[name=author]");
            if (authorMeta != null) {
                String author = authorMeta.attr("content");
                if (!author.isEmpty()) {
                    metadata.put("html.author", author);
                    metadata.put(GraphConstants.META_AUTHOR, author);
                }
            }

            // Language
            String lang = doc.select("html").attr("lang");
            if (!lang.isEmpty()) {
                metadata.put("html.language", lang);
                metadata.put(GraphConstants.META_LANGUAGE, lang);
            }

            // Charset
            Element charsetMeta = doc.selectFirst("meta[charset]");
            if (charsetMeta != null) {
                metadata.put("html.charset", charsetMeta.attr("charset"));
            }

        } catch (Exception e) {
            log.debug("Could not extract HTML metadata: {}", e.getMessage());
        }
    }

    /**
     * Extracts local file links (href attributes) from HTML content.
     * Only returns links that point to files within the root directory.
     */
    List<String> extractLocalLinks(String htmlContent, Path sourceFile, Path root) {
        List<String> links = new ArrayList<>();
        try {
            org.jsoup.nodes.Document doc = Jsoup.parse(htmlContent);
            Elements anchors = doc.select("a[href]");
            Path sourceDir = sourceFile.getParent();

            for (Element anchor : anchors) {
                String href = anchor.attr("href");
                if (href.isEmpty() || href.startsWith("#") || href.startsWith("mailto:")
                        || href.startsWith("javascript:") || href.startsWith("http://")
                        || href.startsWith("https://") || href.startsWith("ftp://")) {
                    continue;
                }

                // Strip fragment
                int hashIdx = href.indexOf('#');
                if (hashIdx > 0) {
                    href = href.substring(0, hashIdx);
                }

                try {
                    Path linkTarget = sourceDir.resolve(href).normalize().toAbsolutePath();
                    // Only include links within the root directory
                    if (linkTarget.startsWith(root.toAbsolutePath()) && isHtmlFile(linkTarget)) {
                        links.add(linkTarget.toString());
                    }
                } catch (InvalidPathException e) {
                    // Skip invalid paths
                }
            }
        } catch (Exception e) {
            log.debug("Could not extract links from {}: {}", sourceFile, e.getMessage());
        }
        return links;
    }

    static boolean isHtmlFile(Path file) {
        String name = file.getFileName().toString().toLowerCase();
        for (String ext : HTML_EXTENSIONS) {
            if (name.endsWith(ext)) return true;
        }
        return false;
    }

    private boolean isHidden(Path path) {
        try {
            return path.getFileName() != null
                    && path.getFileName().toString().startsWith(".");
        } catch (Exception e) {
            return false;
        }
    }

    private List<Pattern> compilePatterns(List<String> patterns) {
        if (patterns == null || patterns.isEmpty()) return List.of();
        List<Pattern> compiled = new ArrayList<>();
        for (String p : patterns) {
            try {
                String regex = p.contains("*") && !p.contains(".*")
                        ? p.replace(".", "\\.").replace("*", ".*")
                        : p;
                compiled.add(Pattern.compile(regex));
            } catch (Exception e) {
                log.warn("Invalid pattern '{}': {}", p, e.getMessage());
            }
        }
        return compiled;
    }

    private boolean matchesPatterns(String fullPath, String fileName,
                                    List<Pattern> includes, List<Pattern> excludes) {
        if (!includes.isEmpty()) {
            boolean matched = false;
            for (Pattern p : includes) {
                if (p.matcher(fullPath).find() || p.matcher(fileName).find()) {
                    matched = true;
                    break;
                }
            }
            if (!matched) return false;
        }
        for (Pattern p : excludes) {
            if (p.matcher(fullPath).find() || p.matcher(fileName).find()) return false;
        }
        return true;
    }

    private String sha256(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}
