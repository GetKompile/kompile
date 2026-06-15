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

package ai.kompile.crawler.excel;

import ai.kompile.core.graphrag.GraphConstants;
import ai.kompile.core.crawler.*;
import ai.kompile.core.loaders.DocumentSourceDescriptor;
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
 * Crawler that discovers spreadsheet files (.xls, .xlsx, .xlsm, .csv, .ods) in a
 * directory tree and emits them as {@link CrawlItem}s with rich metadata for downstream
 * processing by the ExcelLoaderImpl pipeline.
 *
 * <p>Each spreadsheet file produces a CrawlItem with {@code content_type=spreadsheet}
 * metadata. The loader pipeline then extracts sheets as first-class table documents
 * and formula graphs as graph documents.
 *
 * <p>Supports incremental crawling via content hash / last-modified-time tracking,
 * include/exclude patterns, and respects the standard crawl depth and document limits.
 *
 * <h3>Crawler properties (via {@code config.getProperties()}):</h3>
 * <ul>
 *   <li>{@code includeHidden} (boolean, default false) — include hidden files/dirs</li>
 *   <li>{@code followSymlinks} (boolean, default false) — follow symbolic links</li>
 *   <li>{@code includeCsv} (boolean, default true) — include .csv files</li>
 * </ul>
 */
@Component
public class ExcelCrawler extends AbstractCrawler {

    private static final Logger logger = LoggerFactory.getLogger(ExcelCrawler.class);

    private static final Set<String> SPREADSHEET_EXTENSIONS = Set.of(
            ".xls", ".xlsx", ".xlsm", ".ods", ".xlsb"
    );
    private static final Set<String> CSV_EXTENSIONS = Set.of(".csv", ".tsv");
    private static final long MAX_FILE_SIZE = 500 * 1024 * 1024; // 500 MB

    @Override
    public String getId() {
        return "excel";
    }

    @Override
    public String getName() {
        return "Excel & Spreadsheet Crawler";
    }

    @Override
    public String getDescription() {
        return "Crawls directories for spreadsheet files (Excel, CSV, ODS) and extracts " +
                "sheets as table documents and formula dependency graphs as graph documents.";
    }

    @Override
    public Set<DocumentSourceDescriptor.SourceType> getSupportedSourceTypes() {
        return Set.of(DocumentSourceDescriptor.SourceType.FILE, DocumentSourceDescriptor.SourceType.DIRECTORY);
    }

    @Override
    protected List<String> validateSpecific(CrawlConfig config) {
        List<String> errors = new ArrayList<>();
        Path seed = Paths.get(config.getSeed());
        if (!Files.exists(seed)) {
            errors.add("Path does not exist: " + config.getSeed());
        } else if (!Files.isDirectory(seed) && !isSpreadsheetFile(seed, true)) {
            errors.add("Path is not a directory or spreadsheet file: " + config.getSeed());
        }
        return errors;
    }

    @Override
    protected AbstractCrawlJob createJob(String jobId, CrawlConfig config, CrawlEventListener listener) {
        return new ExcelCrawlJob(jobId, config, listener);
    }

    @Override
    protected void executeCrawl(AbstractCrawlJob abstractJob) {
        ExcelCrawlJob job = (ExcelCrawlJob) abstractJob;
        CrawlConfig config = job.getConfig();
        Path root = Paths.get(config.getSeed());

        boolean followSymlinks = Boolean.TRUE.equals(config.getProperties().get("followSymlinks"));
        boolean includeHidden = Boolean.TRUE.equals(config.getProperties().get("includeHidden"));
        boolean includeCsv = config.getProperties().get("includeCsv") == null
                || Boolean.TRUE.equals(config.getProperties().get("includeCsv"));

        List<Pattern> includes = compilePatterns(config.getIncludePatterns());
        List<Pattern> excludes = compilePatterns(config.getExcludePatterns());

        int maxDepth = config.getMaxDepth() <= 0 ? Integer.MAX_VALUE : config.getMaxDepth();
        Set<FileVisitOption> visitOptions = followSymlinks
                ? EnumSet.of(FileVisitOption.FOLLOW_LINKS)
                : EnumSet.noneOf(FileVisitOption.class);

        // Handle single-file case
        if (Files.isRegularFile(root)) {
            if (isSpreadsheetFile(root, includeCsv)) {
                processFile(root, root.getParent(), job, config, includes, excludes);
            }
            return;
        }

        try {
            Files.walkFileTree(root, visitOptions, maxDepth, new SimpleFileVisitor<>() {

                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (job.shouldStop()) return FileVisitResult.TERMINATE;
                    if (!includeHidden && isHidden(dir) && !dir.equals(root)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (job.shouldStop()) return FileVisitResult.TERMINATE;
                    if (!job.checkPauseAndContinue()) return FileVisitResult.TERMINATE;

                    if (!includeHidden && isHidden(file)) return FileVisitResult.CONTINUE;
                    if (!isSpreadsheetFile(file, includeCsv)) return FileVisitResult.CONTINUE;
                    if (attrs.size() > MAX_FILE_SIZE) {
                        job.incrementSkipped();
                        job.getListener().onDocumentSkipped(file.toString(), "File too large: " + attrs.size() + " bytes");
                        return FileVisitResult.CONTINUE;
                    }

                    processFile(file, root, job, config, includes, excludes);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    logger.warn("Failed to access file: {}: {}", file, exc.getMessage());
                    job.recordError(file.toString(), exc);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            logger.error("Error walking directory tree: {}", e.getMessage(), e);
        }
    }

    private void processFile(Path file, Path root, ExcelCrawlJob job, CrawlConfig config,
                             List<Pattern> includes, List<Pattern> excludes) {
        String absolutePath = file.toAbsolutePath().toString();
        String relativePath = root.relativize(file).toString();
        String fileName = file.getFileName().toString();

        // Pattern filtering
        if (!matchesPatterns(absolutePath, fileName, includes, excludes)) {
            job.incrementSkipped();
            job.getListener().onDocumentSkipped(absolutePath, "Excluded by pattern");
            return;
        }

        // Incremental: skip unchanged files
        if (job.isAlreadyProcessed(absolutePath, file)) {
            job.incrementSkipped();
            job.getListener().onDocumentSkipped(absolutePath, "Unchanged since last crawl");
            return;
        }

        // Build CrawlItem with spreadsheet-specific metadata
        Map<String, Object> metadata = new LinkedHashMap<>();
        try {
            BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class);
            metadata.put(GraphConstants.META_FILE_SIZE, attrs.size());
            metadata.put(GraphConstants.META_LAST_MODIFIED, attrs.lastModifiedTime().toMillis());
            metadata.put("createdAt", attrs.creationTime().toMillis());
        } catch (IOException e) {
            logger.warn("Could not read file attributes for {}: {}", absolutePath, e.getMessage());
        }

        metadata.put(GraphConstants.META_CONTENT_TYPE, "spreadsheet");
        metadata.put(GraphConstants.META_FILE_NAME, fileName);
        metadata.put(GraphConstants.META_SOURCE_PATH, absolutePath);
        metadata.put(GraphConstants.META_LOADER, "Excel Crawler");
        metadata.put(GraphConstants.META_DOCUMENT_TYPE, "spreadsheet");
        metadata.put(GraphConstants.META_SOURCE_TYPE, "FILE");
        metadata.put("relativePath", relativePath);
        metadata.put("crawlJobId", job.getJobId());

        // Classify the spreadsheet type
        String extension = getExtension(fileName);
        metadata.put("spreadsheet_type", classifySpreadsheetType(extension));
        metadata.put("file_extension", extension);

        DocumentSourceDescriptor descriptor = DocumentSourceDescriptor.builder()
                .type(DocumentSourceDescriptor.SourceType.FILE)
                .pathOrUrl(absolutePath)
                .originalFileName(fileName)
                .build();

        CrawlItem item = CrawlItem.builder()
                .url(absolutePath)
                .parentUrl(root.toAbsolutePath().toString())
                .depth(root.relativize(file).getNameCount())
                .sourceDescriptor(descriptor)
                .metadata(metadata)
                .discoveredAt(Instant.now())
                .contentType(getContentType(extension))
                .contentLength(metadata.containsKey(GraphConstants.META_FILE_SIZE) ? (Long) metadata.get(GraphConstants.META_FILE_SIZE) : null)
                .build();

        job.incrementDiscovered();
        job.getListener().onDocumentDiscovered(item);
        job.markProcessed(absolutePath, file);
        job.incrementProcessed();
        job.getListener().onDocumentProcessed(item);

        // Progress reporting every 10 files
        if (job.getDiscoveredCount() % 10 == 0) {
            job.getListener().onProgress(job.getProgress());
        }
    }

    private static boolean isSpreadsheetFile(Path file, boolean includeCsv) {
        String name = file.getFileName().toString().toLowerCase();
        for (String ext : SPREADSHEET_EXTENSIONS) {
            if (name.endsWith(ext)) return true;
        }
        if (includeCsv) {
            for (String ext : CSV_EXTENSIONS) {
                if (name.endsWith(ext)) return true;
            }
        }
        return false;
    }

    private static boolean isHidden(Path path) {
        String name = path.getFileName() != null ? path.getFileName().toString() : "";
        return name.startsWith(".") && !name.equals(".") && !name.equals("..");
    }

    private static String getExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(dot + 1).toLowerCase() : "";
    }

    private static String classifySpreadsheetType(String extension) {
        switch (extension) {
            case "xls":
                return "excel-legacy";
            case "xlsx":
            case "xlsm":
            case "xlsb":
                return "excel-modern";
            case "ods":
                return "libreoffice-calc";
            case "csv":
                return "csv";
            case "tsv":
                return "tsv";
            default:
                return "unknown";
        }
    }

    private static String getContentType(String extension) {
        switch (extension) {
            case "xls":
                return "application/vnd.ms-excel";
            case "xlsx":
                return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            case "xlsm":
                return "application/vnd.ms-excel.sheet.macroEnabled.12";
            case "xlsb":
                return "application/vnd.ms-excel.sheet.binary.macroEnabled.12";
            case "ods":
                return "application/vnd.oasis.opendocument.spreadsheet";
            case "csv":
                return "text/csv";
            case "tsv":
                return "text/tab-separated-values";
            default:
                return "application/octet-stream";
        }
    }

    private static List<Pattern> compilePatterns(List<String> patterns) {
        if (patterns == null || patterns.isEmpty()) return List.of();
        List<Pattern> compiled = new ArrayList<>();
        for (String p : patterns) {
            String regex = p.replace(".", "\\.").replace("*", ".*").replace("?", ".");
            compiled.add(Pattern.compile(regex, Pattern.CASE_INSENSITIVE));
        }
        return compiled;
    }

    private static boolean matchesPatterns(String fullPath, String fileName,
                                           List<Pattern> includes, List<Pattern> excludes) {
        // If includes are specified, at least one must match
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
        // Check excludes
        for (Pattern p : excludes) {
            if (p.matcher(fullPath).find() || p.matcher(fileName).find()) {
                return false;
            }
        }
        return true;
    }
}
