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

package ai.kompile.crawler.fs;

import ai.kompile.core.graphrag.GraphConstants;
import ai.kompile.core.crawler.*;
import ai.kompile.core.loaders.DocumentSourceDescriptor;
import ai.kompile.core.loaders.DocumentSourceDescriptor.SourceType;
import ai.kompile.crawler.AbstractCrawlJob;
import ai.kompile.crawler.AbstractCrawler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Crawler that discovers documents by walking a filesystem directory tree.
 *
 * <p>Features:</p>
 * <ul>
 *   <li>Recursive directory traversal up to configurable depth</li>
 *   <li>Glob and regex pattern filtering (include/exclude)</li>
 *   <li>Incremental crawling based on file modification timestamps</li>
 *   <li>Symbolic link handling (configurable via properties)</li>
 *   <li>Pause/resume/cancel support</li>
 * </ul>
 *
 * <p>Crawler-specific properties:</p>
 * <ul>
 *   <li>{@code followSymlinks} (boolean, default: false)</li>
 *   <li>{@code includeHidden} (boolean, default: false)</li>
 * </ul>
 */
@Component
public class FileSystemCrawler extends AbstractCrawler {

    private static final Logger log = LoggerFactory.getLogger(FileSystemCrawler.class);

    @Override
    public String getId() {
        return "filesystem";
    }

    @Override
    public String getName() {
        return "File System Crawler";
    }

    @Override
    public String getDescription() {
        return "Discovers documents by scanning directories on the local filesystem with incremental change detection";
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
        } else if (!Files.isDirectory(root)) {
            errors.add("Path is not a directory: " + config.getSeed());
        } else if (!Files.isReadable(root)) {
            errors.add("Path is not readable: " + config.getSeed());
        }
        return errors;
    }

    @Override
    protected AbstractCrawlJob createJob(String jobId, CrawlConfig config, CrawlEventListener listener) {
        return new FileSystemCrawlJob(jobId, config, listener);
    }

    @Override
    protected void executeCrawl(AbstractCrawlJob job) throws Exception {
        FileSystemCrawlJob fsJob = (FileSystemCrawlJob) job;
        CrawlConfig config = job.getConfig();
        Path root = Path.of(config.getSeed());

        boolean followSymlinks = Boolean.TRUE.equals(config.getProperties().get("followSymlinks"));
        boolean includeHidden = Boolean.TRUE.equals(config.getProperties().get("includeHidden"));

        List<Pattern> includes = compilePatterns(config.getIncludePatterns());
        List<Pattern> excludes = compilePatterns(config.getExcludePatterns());

        // Build FileVisitor options
        Set<FileVisitOption> visitOptions = followSymlinks
                ? EnumSet.of(FileVisitOption.FOLLOW_LINKS)
                : EnumSet.noneOf(FileVisitOption.class);

        int maxDepth = config.getMaxDepth() > 0 ? config.getMaxDepth() : Integer.MAX_VALUE;

        log.info("[{}] Starting file walk: root={}, maxDepth={}, exists={}, isDir={}, readable={}",
                job.getJobId(), root, maxDepth, Files.exists(root), Files.isDirectory(root), Files.isReadable(root));

        Files.walkFileTree(root, visitOptions, maxDepth, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (fsJob.shouldStop()) return FileVisitResult.TERMINATE;

                log.debug("[{}] Visiting directory: {}", job.getJobId(), dir);

                // Skip hidden directories unless configured
                if (!includeHidden && isHidden(dir)) {
                    log.debug("[{}] Skipping hidden directory: {}", job.getJobId(), dir);
                    return FileVisitResult.SKIP_SUBTREE;
                }

                // Track depth
                int depth = root.relativize(dir).getNameCount();
                fsJob.setCurrentDepth(depth);

                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (fsJob.shouldStop()) return FileVisitResult.TERMINATE;
                if (!fsJob.checkPauseAndContinue()) return FileVisitResult.TERMINATE;

                String filePath = file.toAbsolutePath().toString();
                fsJob.setCurrentItem(filePath);

                // Skip hidden files unless configured
                if (!includeHidden && isHidden(file)) {
                    log.info("[{}] SKIP hidden: {}", job.getJobId(), file.getFileName());
                    fsJob.incrementSkipped();
                    fsJob.getListener().onDocumentSkipped(filePath, "hidden file");
                    return FileVisitResult.CONTINUE;
                }

                // Apply include/exclude patterns against filename and path
                if (!matchesPatterns(filePath, file.getFileName().toString(), includes, excludes)) {
                    log.info("[{}] SKIP pattern: {}", job.getJobId(), file.getFileName());
                    fsJob.incrementSkipped();
                    fsJob.getListener().onDocumentSkipped(filePath, "filtered by include/exclude patterns");
                    return FileVisitResult.CONTINUE;
                }

                // Content type filtering
                if (!isAcceptableFile(file, config.getAllowedContentTypes())) {
                    log.info("[{}] SKIP content-type: {} (allowed={})", job.getJobId(), file.getFileName(), config.getAllowedContentTypes());
                    fsJob.incrementSkipped();
                    fsJob.getListener().onDocumentSkipped(filePath, "content type not accepted");
                    return FileVisitResult.CONTINUE;
                }

                // Incremental crawl: skip unchanged files
                long lastModified = attrs.lastModifiedTime().toMillis();
                CrawlState prevState = config.getPreviousState();
                if (prevState != null && !prevState.isModifiedSince(filePath, lastModified)) {
                    log.info("[{}] SKIP unchanged: {}", job.getJobId(), file.getFileName());
                    fsJob.incrementSkipped();
                    fsJob.getListener().onDocumentSkipped(filePath, "unchanged since last crawl");
                    return FileVisitResult.CONTINUE;
                }

                // Record this file
                fsJob.visitedPaths.add(filePath);
                fsJob.lastModifiedTimes.put(filePath, lastModified);

                int depth = root.relativize(file).getNameCount();

                Map<String, Object> fsItemMetadata = new LinkedHashMap<>();
                fsItemMetadata.put(GraphConstants.META_FILE_SIZE, attrs.size());
                fsItemMetadata.put(GraphConstants.META_LAST_MODIFIED, lastModified);
                fsItemMetadata.put(GraphConstants.META_FILE_NAME, file.getFileName().toString());
                fsItemMetadata.put(GraphConstants.META_SOURCE_PATH, filePath);
                fsItemMetadata.put(GraphConstants.META_LOADER, "File System Crawler");
                fsItemMetadata.put(GraphConstants.META_DOCUMENT_TYPE, resolveFileDocType(file.getFileName().toString()));
                fsItemMetadata.put(GraphConstants.META_SOURCE_TYPE, "FILE");
                fsItemMetadata.put("crawlJobId", job.getJobId());

                CrawlItem item = CrawlItem.builder()
                        .url(filePath)
                        .parentUrl(file.getParent().toAbsolutePath().toString())
                        .depth(depth)
                        .contentType(probeContentType(file))
                        .contentLength(attrs.size())
                        .discoveredAt(Instant.now())
                        .sourceDescriptor(DocumentSourceDescriptor.builder()
                                .type(SourceType.FILE)
                                .pathOrUrl(filePath)
                                .sourceId(filePath)
                                .originalFileName(file.getFileName().toString())
                                .collectionName(config.getCollectionName())
                                .build())
                        .metadata(fsItemMetadata)
                        .build();

                fsJob.incrementDiscovered();
                fsJob.getListener().onDocumentDiscovered(item);
                fsJob.incrementProcessed();
                fsJob.getListener().onDocumentProcessed(item);

                // Report progress periodically
                if (fsJob.getDiscoveredCount() % 50 == 0) {
                    fsJob.getListener().onProgress(fsJob.getProgress());
                }

                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                log.warn("[{}] Failed to visit {}: {}", job.getJobId(), file, exc.getMessage());
                fsJob.recordError(file.toString(), exc);
                return FileVisitResult.CONTINUE;
            }
        });

        log.info("[{}] File walk complete: discovered={}, visitedPaths={}",
                job.getJobId(), fsJob.getDiscoveredCount(), fsJob.visitedPaths.size());
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
                // Support simple glob patterns by converting * to .*
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

    private boolean isAcceptableFile(Path file, List<String> allowedTypes) {
        if (allowedTypes == null || allowedTypes.isEmpty()) return true;
        String contentType = probeContentType(file);
        if (contentType == null) return true; // unknown type → allow
        for (String allowed : allowedTypes) {
            if (contentType.startsWith(allowed.trim())) return true;
        }
        return false;
    }

    private String probeContentType(Path file) {
        try {
            return Files.probeContentType(file);
        } catch (IOException e) {
            return null;
        }
    }

    private static String resolveFileDocType(String fileName) {
        if (fileName == null) return "file";
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".pdf")) return "pdf";
        if (lower.endsWith(".xlsx") || lower.endsWith(".xls") || lower.endsWith(".csv")) return "spreadsheet";
        if (lower.endsWith(".docx") || lower.endsWith(".doc") || lower.endsWith(".rtf")) return "document";
        if (lower.endsWith(".pptx") || lower.endsWith(".ppt")) return "presentation";
        if (lower.endsWith(".html") || lower.endsWith(".htm")) return "HTML Document";
        if (lower.endsWith(".eml") || lower.endsWith(".msg") || lower.endsWith(".mbox")) return "email";
        if (lower.endsWith(".txt") || lower.endsWith(".md") || lower.endsWith(".json") || lower.endsWith(".xml")) return "text";
        if (lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".gif")) return "image";
        return "file";
    }
}
