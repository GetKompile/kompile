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

package ai.kompile.crawler.remote;

import ai.kompile.core.crawler.*;
import ai.kompile.core.graphrag.GraphConstants;
import ai.kompile.core.loaders.DocumentSourceDescriptor;
import ai.kompile.core.loaders.DocumentSourceDescriptor.SourceType;
import ai.kompile.crawler.AbstractCrawlJob;
import ai.kompile.crawler.AbstractCrawler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Crawler that discovers and downloads documents from remote folders:
 * Amazon S3 buckets, SFTP servers, and SMB/CIFS shares.
 *
 * <p>The crawler lists files from the remote source, downloads them to a
 * temporary local directory, and then produces {@link CrawlItem}s pointing
 * to the local copies. This allows the downstream ingest pipeline to process
 * them identically to local filesystem files.</p>
 *
 * <p>Features:</p>
 * <ul>
 *   <li>S3, SFTP, and SMB/CIFS protocols</li>
 *   <li>Recursive directory traversal up to configurable depth</li>
 *   <li>Glob/regex include/exclude pattern filtering</li>
 *   <li>Incremental crawling via ETag/last-modified tracking</li>
 *   <li>Automatic temp directory cleanup on completion</li>
 *   <li>Pause/resume/cancel support</li>
 * </ul>
 */
@Component
public class RemoteFolderCrawler extends AbstractCrawler {

    private static final Logger log = LoggerFactory.getLogger(RemoteFolderCrawler.class);

    @Override
    public String getId() {
        return "remote-folder";
    }

    @Override
    public String getName() {
        return "Remote Folder Crawler";
    }

    @Override
    public String getDescription() {
        return "Discovers and downloads documents from S3 buckets, SFTP servers, "
                + "and SMB/CIFS shares with incremental change detection";
    }

    @Override
    public Set<SourceType> getSupportedSourceTypes() {
        return Set.of(SourceType.S3, SourceType.SFTP, SourceType.SMB);
    }

    @Override
    protected List<String> validateSpecific(CrawlConfig config) {
        List<String> errors = new ArrayList<>();
        SourceType type = config.getSourceType();

        if (type == null) {
            errors.add("sourceType is required (S3, SFTP, or SMB)");
            return errors;
        }

        if (!getSupportedSourceTypes().contains(type)) {
            errors.add("Unsupported source type for remote folder crawler: " + type);
            return errors;
        }

        Map<String, Object> props = config.getProperties();
        if (props == null) props = Map.of();

        switch (type) {
            case S3:
                requirePropValidation(errors, props, "accessKey", "S3");
                requirePropValidation(errors, props, "secretKey", "S3");
                break;
            case SFTP:
                requirePropValidation(errors, props, "host", "SFTP");
                requirePropValidation(errors, props, "username", "SFTP");
                break;
            case SMB:
                requirePropValidation(errors, props, "host", "SMB");
                requirePropValidation(errors, props, "username", "SMB");
                requirePropValidation(errors, props, "password", "SMB");
                break;
            default:
                break;
        }

        return errors;
    }

    @Override
    protected AbstractCrawlJob createJob(String jobId, CrawlConfig config, CrawlEventListener listener) {
        return new RemoteFolderCrawlJob(jobId, config, listener);
    }

    @Override
    protected void executeCrawl(AbstractCrawlJob job) throws Exception {
        RemoteFolderCrawlJob remoteJob = (RemoteFolderCrawlJob) job;
        CrawlConfig config = job.getConfig();
        SourceType sourceType = config.getSourceType();
        Map<String, Object> props = config.getProperties() != null
                ? config.getProperties() : Map.of();

        // Create temp directory for downloads
        Path downloadDir = Files.createTempDirectory("kompile-remote-crawl-" + job.getJobId());
        remoteJob.downloadDir = downloadDir;

        log.info("[{}] Starting remote crawl: type={}, seed={}", job.getJobId(), sourceType, config.getSeed());

        RemoteFolderClient client = createClient(sourceType);
        try {
            client.connect(config.getSeed(), props);

            // List remote files
            int maxDepth = config.getMaxDepth() > 0 ? config.getMaxDepth() : 0;
            List<RemoteFileEntry> remoteFiles = client.listFiles(maxDepth);
            log.info("[{}] Discovered {} remote files", job.getJobId(), remoteFiles.size());

            // Compile patterns
            List<Pattern> includes = compilePatterns(config.getIncludePatterns());
            List<Pattern> excludes = compilePatterns(config.getExcludePatterns());

            int processed = 0;
            for (RemoteFileEntry entry : remoteFiles) {
                if (remoteJob.shouldStop()) break;
                if (!remoteJob.checkPauseAndContinue()) break;

                String remoteKey = entry.key();
                String fileName = entry.effectiveFileName();

                // Pattern filtering
                if (!matchesPatterns(remoteKey, fileName, includes, excludes)) {
                    remoteJob.incrementSkipped();
                    remoteJob.getListener().onDocumentSkipped(remoteKey, "filtered by include/exclude patterns");
                    continue;
                }

                // Content type filtering
                if (config.getAllowedContentTypes() != null && !config.getAllowedContentTypes().isEmpty()) {
                    String ct = entry.contentType();
                    if (ct == null) {
                        ct = probeContentTypeByExtension(fileName);
                    }
                    if (ct != null && !matchesContentType(ct, config.getAllowedContentTypes())) {
                        remoteJob.incrementSkipped();
                        remoteJob.getListener().onDocumentSkipped(remoteKey, "content type not accepted");
                        continue;
                    }
                }

                // Incremental: skip unchanged files
                CrawlState prevState = config.getPreviousState();
                if (prevState != null && entry.lastModifiedMs() > 0) {
                    if (!prevState.isModifiedSince(remoteKey, entry.lastModifiedMs())) {
                        remoteJob.incrementSkipped();
                        remoteJob.getListener().onDocumentSkipped(remoteKey, "unchanged since last crawl");
                        continue;
                    }
                }

                // Download to temp directory
                Path localFile = downloadDir.resolve(sanitizeFileName(remoteKey));
                try {
                    remoteJob.setCurrentItem(remoteKey);
                    client.download(remoteKey, localFile);
                } catch (IOException e) {
                    remoteJob.recordError(remoteKey, e);
                    continue;
                }

                // Track for incremental
                remoteJob.visitedKeys.add(remoteKey);
                if (entry.lastModifiedMs() > 0) {
                    remoteJob.lastModifiedTimes.put(remoteKey, entry.lastModifiedMs());
                }

                // Build CrawlItem pointing to the local download
                String localPath = localFile.toAbsolutePath().toString();
                Map<String, Object> metadata = new LinkedHashMap<>();
                metadata.put(GraphConstants.META_FILE_SIZE, entry.sizeBytes());
                metadata.put(GraphConstants.META_FILE_NAME, fileName);
                metadata.put(GraphConstants.META_SOURCE_PATH, remoteKey);
                metadata.put(GraphConstants.META_LOADER, "Remote Folder Crawler");
                metadata.put(GraphConstants.META_DOCUMENT_TYPE, resolveDocType(fileName));
                metadata.put(GraphConstants.META_SOURCE_TYPE, sourceType.name());
                metadata.put("remoteProtocol", sourceType.name().toLowerCase());
                metadata.put("remoteKey", remoteKey);
                metadata.put("crawlJobId", job.getJobId());
                if (entry.etag() != null) {
                    metadata.put("remoteEtag", entry.etag());
                }
                if (entry.lastModifiedMs() > 0) {
                    metadata.put(GraphConstants.META_LAST_MODIFIED, entry.lastModifiedMs());
                }

                CrawlItem item = CrawlItem.builder()
                        .url(localPath)
                        .parentUrl(config.getSeed())
                        .depth(remoteKey.chars().filter(c -> c == '/').count() > 0
                                ? (int) remoteKey.chars().filter(c -> c == '/').count() : 0)
                        .contentType(entry.contentType() != null
                                ? entry.contentType()
                                : probeContentTypeByExtension(fileName))
                        .contentLength(entry.sizeBytes())
                        .discoveredAt(Instant.now())
                        .sourceDescriptor(DocumentSourceDescriptor.builder()
                                .type(SourceType.FILE) // local copy is a file
                                .pathOrUrl(localPath)
                                .sourceId(remoteKey)
                                .originalFileName(fileName)
                                .collectionName(config.getCollectionName())
                                .build())
                        .metadata(metadata)
                        .build();

                remoteJob.incrementDiscovered();
                remoteJob.getListener().onDocumentDiscovered(item);
                remoteJob.incrementProcessed();
                remoteJob.getListener().onDocumentProcessed(item);

                processed++;
                if (processed % 50 == 0) {
                    remoteJob.getListener().onProgress(remoteJob.getProgress());
                }
            }
        } finally {
            try {
                client.close();
            } catch (IOException e) {
                log.debug("[{}] Error closing remote client: {}", job.getJobId(), e.getMessage());
            }
        }

        log.info("[{}] Remote crawl complete: discovered={}, downloaded to {}",
                job.getJobId(), remoteJob.getDiscoveredCount(), downloadDir);
    }

    static RemoteFolderClient createClient(SourceType type) {
        return switch (type) {
            case S3 -> new S3FolderClient();
            case SFTP -> new SftpFolderClient();
            case SMB -> new SmbFolderClient();
            default -> throw new IllegalArgumentException("Unsupported remote source type: " + type);
        };
    }

    private static void requirePropValidation(List<String> errors, Map<String, Object> props,
                                               String key, String protocol) {
        Object v = props.get(key);
        if (v == null || v.toString().isBlank()) {
            errors.add(protocol + " requires property '" + key + "'");
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

    private boolean matchesContentType(String contentType, List<String> allowed) {
        for (String a : allowed) {
            if (contentType.startsWith(a.trim())) return true;
        }
        return false;
    }

    /**
     * Sanitize a remote key into a safe local filename,
     * preserving directory structure under the temp dir.
     */
    private static String sanitizeFileName(String remoteKey) {
        // Replace backslashes with forward slashes and strip leading slashes
        String normalized = remoteKey.replace("\\", "/");
        if (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        return normalized;
    }

    private static String probeContentTypeByExtension(String fileName) {
        if (fileName == null) return null;
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".pdf")) return "application/pdf";
        if (lower.endsWith(".html") || lower.endsWith(".htm")) return "text/html";
        if (lower.endsWith(".json")) return "application/json";
        if (lower.endsWith(".xml")) return "application/xml";
        if (lower.endsWith(".csv")) return "text/csv";
        if (lower.endsWith(".txt") || lower.endsWith(".md")) return "text/plain";
        if (lower.endsWith(".docx")) return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        if (lower.endsWith(".xlsx")) return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        if (lower.endsWith(".pptx")) return "application/vnd.openxmlformats-officedocument.presentationml.presentation";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        return null;
    }

    private static String resolveDocType(String fileName) {
        if (fileName == null) return "file";
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".pdf")) return "pdf";
        if (lower.endsWith(".xlsx") || lower.endsWith(".xls") || lower.endsWith(".csv")) return "spreadsheet";
        if (lower.endsWith(".docx") || lower.endsWith(".doc") || lower.endsWith(".rtf")) return "document";
        if (lower.endsWith(".pptx") || lower.endsWith(".ppt")) return "presentation";
        if (lower.endsWith(".html") || lower.endsWith(".htm")) return "HTML Document";
        if (lower.endsWith(".eml") || lower.endsWith(".msg") || lower.endsWith(".mbox")) return "email";
        if (lower.endsWith(".json")) return "json";
        if (lower.endsWith(".xml")) return "xml";
        if (lower.endsWith(".yaml") || lower.endsWith(".yml")) return "yaml";
        if (lower.endsWith(".txt") || lower.endsWith(".md")) return "text";
        if (lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".gif")) return "image";
        return "file";
    }
}
