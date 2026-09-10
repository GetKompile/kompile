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

package ai.kompile.app.services;

import ai.kompile.app.core.chunking.TextChunker;
import ai.kompile.core.crawler.CrawlConfig;
import ai.kompile.core.crawler.CrawlEventListener;
import ai.kompile.core.crawler.CrawlItem;
import ai.kompile.core.crawler.CrawlJob;
import ai.kompile.core.crawl.graph.GraphExtractionConfig;
import ai.kompile.core.crawl.graph.ProcessingRouteConfig;
import ai.kompile.core.crawl.graph.UnifiedCrawlRequest;
import ai.kompile.core.crawler.CrawlSummary;
import ai.kompile.core.crawler.CrawlStatus;
import ai.kompile.core.crawler.Crawler;
import ai.kompile.core.graphrag.GraphConstants;
import ai.kompile.core.loaders.DocumentLoader;
import ai.kompile.core.loaders.DocumentSourceDescriptor;
import ai.kompile.core.loaders.PdfClassificationResult;
import ai.kompile.core.loaders.PdfContentClassifier;
import ai.kompile.core.retrievers.RetrievedDoc;
import ai.kompile.crawl.graph.GraphExtractionPreviewService;
import ai.kompile.crawler.CrawlerRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Builds a synchronous dry-run preview for a single source add.
 *
 * <p>The preview intentionally stays below the persistent unified-crawl service. It may run the
 * registered crawler to discover candidate items and may load a bounded sample to estimate chunks,
 * but it does not write crawl state, source files, graph nodes, relations, embeddings, or index
 * entries.</p>
 */
@Service
public class SingleSourceCrawlPreviewService {

    private static final Logger log = LoggerFactory.getLogger(SingleSourceCrawlPreviewService.class);

    private static final int DEFAULT_PREVIEW_LIMIT = 25;
    private static final int MAX_PREVIEW_LIMIT = 100;
    private static final int PREVIEW_TEXT_LIMIT = 280;
    private static final int MAX_WARNINGS = 12;
    private static final int DEFAULT_GRAPH_PREVIEW_DOCUMENTS = 3;
    private static final int MAX_GRAPH_PREVIEW_DOCUMENTS = 10;
    private static final int DEFAULT_GRAPH_PREVIEW_CHARS = 12_000;
    private static final int MAX_GRAPH_PREVIEW_CHARS = 40_000;
    private static final Duration DEFAULT_CRAWL_TIMEOUT = Duration.ofSeconds(120);

    private static final Map<String, String> CHUNKER_ALIASES = Map.ofEntries(
            Map.entry("spring_recursive_character", "recursive-character"),
            Map.entry("custom_recursive_character", "recursive-character"),
            Map.entry("recursive-character", "recursive-character"),
            Map.entry("opennlp_sentence", "opennlp_sentence"),
            Map.entry("sentence", "sentence"),
            Map.entry("spring_token", "spring_token"),
            Map.entry("custom_markdown", "custom_markdown"),
            Map.entry("spring_markdown", "spring_markdown"));

    private final CrawlerRegistry crawlerRegistry;
    private final List<DocumentLoader> documentLoaders;
    private final List<TextChunker> textChunkers;
    private final YouTubeTranscriptService youTubeTranscriptService;
    private final GraphExtractionPreviewService graphExtractionPreviewService;
    private final SingleSourceCrawlStarter singleSourceCrawlStarter;
    private final PdfContentClassifier pdfContentClassifier;

    @Autowired
    public SingleSourceCrawlPreviewService(
            @Autowired(required = false) CrawlerRegistry crawlerRegistry,
            @Autowired(required = false) List<DocumentLoader> documentLoaders,
            @Autowired(required = false) List<TextChunker> textChunkers,
            @Autowired(required = false) YouTubeTranscriptService youTubeTranscriptService,
            @Autowired(required = false) GraphExtractionPreviewService graphExtractionPreviewService,
            @Autowired(required = false) SingleSourceCrawlStarter singleSourceCrawlStarter,
            @Autowired(required = false) PdfContentClassifier pdfContentClassifier) {
        this.crawlerRegistry = crawlerRegistry;
        this.documentLoaders = documentLoaders != null ? documentLoaders : List.of();
        this.textChunkers = textChunkers != null ? textChunkers : List.of();
        this.youTubeTranscriptService = youTubeTranscriptService;
        this.graphExtractionPreviewService = graphExtractionPreviewService;
        this.singleSourceCrawlStarter = singleSourceCrawlStarter;
        this.pdfContentClassifier = pdfContentClassifier;
    }

    public SingleSourcePreviewResponse preview(SingleSourcePreviewRequest request) throws Exception {
        if (request == null) {
            throw new IllegalArgumentException("Preview request is required");
        }
        String normalizedType = normalizeSourceType(request.sourceType());
        int previewLimit = resolvePreviewLimit(request.maxDocuments());
        List<String> warnings = new ArrayList<>();

        if ("text".equals(normalizedType)) {
            return previewText(request, warnings);
        }
        if ("youtube".equals(normalizedType)) {
            return previewYouTube(request, warnings);
        }

        DocumentSourceDescriptor.SourceType sourceType = resolveDescriptorSourceType(normalizedType, request);
        DocumentSourceDescriptor descriptor = buildDescriptor(request, sourceType, previewLimit);

        if (shouldUseCrawler(sourceType)) {
            Optional<Crawler> crawler = selectCrawler(sourceType, request.properties());
            if (crawler.isPresent()) {
                CrawlPreviewRun crawlRun = runCrawler(crawler.get(), sourceType, request, previewLimit, warnings);
                PreviewAssembly assembly = previewItemsFromCrawlItems(crawlRun.items(), request, sourceType, warnings,
                        previewLimit);
                List<PreviewItem> items = assembly.items();
                boolean truncated = crawlRun.truncated() || items.size() >= previewLimit;
                if (!items.isEmpty() || sourceType == DocumentSourceDescriptor.SourceType.DIRECTORY
                        || sourceType == DocumentSourceDescriptor.SourceType.WEB_CRAWL) {
                    GraphExtractionPreviewService.PreviewResponse graphExtraction = previewGraphExtraction(
                            request, assembly.documents(), warnings);
                    return new SingleSourcePreviewResponse(
                            UUID.randomUUID().toString(),
                            true,
                            normalizedType,
                            effectiveLabel(request, descriptor),
                            crawlRun.summary() != null ? crawlRun.summary().status().name() : "COMPLETED",
                            true,
                            crawler.get().getName(),
                            firstNonBlank(request.loaderName(), inferLoaderName(items)),
                            selectedChunkerName(request.chunkerName()),
                            crawlRun.discoveredCount(),
                            countDocuments(items),
                            countChunks(items),
                            crawlRun.failedCount(),
                            crawlRun.skippedCount(),
                            truncated,
                            items,
                            warnings,
                            graphExtraction);
                }
                addWarning(warnings, "Crawler found no previewable items; falling back to loader preview.");
            }
        }

        LoadedDocuments loaded = loadDocuments(descriptor, request.loaderName(), warnings, true);
        List<Document> routedDocuments = routePdfDocumentsForPreview(
                loaded.documents(), request, descriptor.getPathOrUrl(), warnings);
        List<PreviewItem> items = previewItemsFromDocuments(
                effectiveLabel(request, descriptor),
                descriptor.getPathOrUrl(),
                sourceType,
                loaded.loaderName(),
                routedDocuments,
                request.chunkerName(),
                warnings,
                previewLimit);
        GraphExtractionPreviewService.PreviewResponse graphExtraction = previewGraphExtraction(
                request, routedDocuments, warnings);
        return new SingleSourcePreviewResponse(
                UUID.randomUUID().toString(),
                true,
                normalizedType,
                effectiveLabel(request, descriptor),
                "COMPLETED",
                false,
                null,
                loaded.loaderName(),
                selectedChunkerName(request.chunkerName()),
                items.size(),
                loaded.documents().size(),
                countChunks(items),
                0,
                0,
                loaded.documents().size() > items.size(),
                items,
                warnings,
                graphExtraction);
    }

    private SingleSourcePreviewResponse previewText(SingleSourcePreviewRequest request, List<String> warnings) {
        String content = request.content() != null ? request.content() : "";
        if (content.isBlank()) {
            throw new IllegalArgumentException("Text content is required for preview");
        }
        String label = firstNonBlank(request.label(), "Pasted text");
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("source", label);
        metadata.put("sourceType", "TEXT");
        metadata.put("contentLength", content.length());
        Document document = new Document(content);
        document.getMetadata().putAll(metadata);

        List<PreviewItem> items = previewItemsFromDocuments(
                label,
                label,
                null,
                "Text Input",
                List.of(document),
                request.chunkerName(),
                warnings,
                1);
        GraphExtractionPreviewService.PreviewResponse graphExtraction = previewGraphExtraction(
                request, List.of(document), warnings);
        return new SingleSourcePreviewResponse(
                UUID.randomUUID().toString(),
                true,
                "text",
                label,
                "COMPLETED",
                false,
                null,
                "Text Input",
                selectedChunkerName(request.chunkerName()),
                1,
                1,
                countChunks(items),
                0,
                0,
                false,
                items,
                warnings,
                graphExtraction);
    }

    private SingleSourcePreviewResponse previewYouTube(SingleSourcePreviewRequest request, List<String> warnings) throws Exception {
        if (youTubeTranscriptService == null) {
            throw new IllegalStateException("YouTube transcript service is not available");
        }
        String url = firstNonBlank(request.pathOrUrl(), request.content());
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("YouTube URL is required for preview");
        }
        YouTubeTranscriptService.TranscriptResult transcript = youTubeTranscriptService.fetchTranscript(url,
                firstNonBlank(request.language(), "en"));
        Document document = youTubeTranscriptService.toDocument(transcript);
        String label = firstNonBlank(request.label(), transcript.getTitle(), transcript.getVideoId());
        List<PreviewItem> items = previewItemsFromDocuments(
                label,
                url,
                null,
                "YouTube Transcript",
                List.of(document),
                request.chunkerName(),
                warnings,
                1);
        GraphExtractionPreviewService.PreviewResponse graphExtraction = previewGraphExtraction(
                request, List.of(document), warnings);
        return new SingleSourcePreviewResponse(
                UUID.randomUUID().toString(),
                true,
                "youtube",
                label,
                "COMPLETED",
                false,
                null,
                "YouTube Transcript",
                selectedChunkerName(request.chunkerName()),
                1,
                1,
                countChunks(items),
                0,
                0,
                false,
                items,
                warnings,
                graphExtraction);
    }

    private CrawlPreviewRun runCrawler(
            Crawler crawler,
            DocumentSourceDescriptor.SourceType sourceType,
            SingleSourcePreviewRequest request,
            int previewLimit,
            List<String> warnings) throws Exception {
        List<CrawlItem> items = Collections.synchronizedList(new ArrayList<>());
        AtomicReference<CrawlJob> jobRef = new AtomicReference<>();
        int maxDocuments = previewLimit > 0 ? previewLimit : DEFAULT_PREVIEW_LIMIT;
        Map<String, Object> properties = mutableProperties(request.properties());
        properties.put("dryRun", true);
        properties.put("preview", true);

        CrawlConfig config = CrawlConfig.builder()
                .crawlerId(crawler.getId())
                .seed(resolveSeed(request, sourceType))
                .sourceType(sourceType)
                .maxDepth(resolveMaxDepth(request.maxDepth(), sourceType))
                .maxDocuments(maxDocuments)
                .timeout(DEFAULT_CRAWL_TIMEOUT)
                .requestDelay(Duration.ZERO)
                .loaderName(blankToNull(request.loaderName()))
                .chunkerName(blankToNull(request.chunkerName()))
                .forceRecrawl(true)
                .properties(properties)
                .build();

        CrawlEventListener listener = new CrawlEventListener() {
            @Override
            public void onDocumentDiscovered(CrawlItem item) {
                collect(item);
            }

            @Override
            public void onDocumentProcessed(CrawlItem item) {
                collect(item);
            }

            @Override
            public void onDocumentFailed(CrawlItem item, Exception error) {
                addWarning(warnings, "Preview crawl failed for " + itemUrl(item) + ": " + error.getMessage());
            }

            @Override
            public void onDocumentSkipped(String url, String reason) {
                if (items.size() < previewLimit) {
                    addWarning(warnings, "Skipped " + url + ": " + reason);
                }
            }

            private void collect(CrawlItem item) {
                if (item == null) {
                    return;
                }
                synchronized (items) {
                    if (items.stream().noneMatch(existing -> Objects.equals(itemUrl(existing), itemUrl(item)))) {
                        items.add(item);
                    }
                    if (items.size() >= previewLimit) {
                        CrawlJob job = jobRef.get();
                        if (job != null && job.getStatus() == CrawlStatus.RUNNING) {
                            job.cancel();
                        }
                    }
                }
            }
        };

        CrawlJob job = crawler.start(config, listener);
        jobRef.set(job);
        synchronized (items) {
            if (items.size() >= previewLimit && job.getStatus() == CrawlStatus.RUNNING) {
                job.cancel();
            }
        }

        CrawlSummary summary;
        try {
            summary = job.getCompletionFuture().get(DEFAULT_CRAWL_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            job.cancel();
            addWarning(warnings, "Preview crawl timed out and was cancelled.");
            try {
                summary = job.getCompletionFuture().get(5, TimeUnit.SECONDS);
            } catch (TimeoutException timeoutAfterCancel) {
                summary = null;
            }
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new IllegalStateException("Preview crawl failed: " + cause.getMessage(), cause);
        }

        List<CrawlItem> snapshot;
        synchronized (items) {
            snapshot = new ArrayList<>(items);
        }
        int discovered = summary != null ? summary.totalDiscovered() : snapshot.size();
        int failed = summary != null ? summary.totalFailed() : 0;
        int skipped = summary != null ? summary.totalSkipped() : 0;
        boolean truncated = snapshot.size() >= previewLimit && discovered >= previewLimit;
        if (truncated) {
            addWarning(warnings, "Preview is limited to " + previewLimit + " items.");
        }
        return new CrawlPreviewRun(snapshot, summary, discovered, failed, skipped, truncated);
    }

    private PreviewAssembly previewItemsFromCrawlItems(
            List<CrawlItem> crawlItems,
            SingleSourcePreviewRequest request,
            DocumentSourceDescriptor.SourceType fallbackType,
            List<String> warnings,
            int previewLimit) {
        List<PreviewItem> items = new ArrayList<>();
        List<Document> documents = new ArrayList<>();
        for (CrawlItem item : crawlItems) {
            if (items.size() >= previewLimit) {
                break;
            }
            DocumentSourceDescriptor descriptor = normalizeDescriptorForLoading(item, fallbackType, request.properties());
            LoadedDocuments loaded = loadDocuments(descriptor, request.loaderName(), warnings, false);
            if (!loaded.documents().isEmpty()) {
                List<Document> routedDocuments = routePdfDocumentsForPreview(
                        loaded.documents(), request, descriptor.getPathOrUrl(), warnings);
                documents.addAll(routedDocuments);
                List<PreviewItem> loadedItems = previewItemsFromDocuments(
                        titleForItem(item, descriptor),
                        descriptor.getPathOrUrl(),
                        descriptor.getType(),
                        loaded.loaderName(),
                        routedDocuments,
                        request.chunkerName(),
                        warnings,
                        Math.max(1, previewLimit - items.size()));
                items.addAll(loadedItems);
            } else {
                items.add(previewItemFromCrawlItem(item, descriptor, loaded.loaderName(), request.chunkerName(), warnings));
            }
        }
        return new PreviewAssembly(items, documents);
    }

    private PreviewItem previewItemFromCrawlItem(
            CrawlItem item,
            DocumentSourceDescriptor descriptor,
            String loaderName,
            String chunkerName,
            List<String> warnings) {
        Map<String, Object> metadata = mergedMetadata(descriptor.getMetadata(), item.getMetadata());
        int charCount = 0;
        int chunkCount = 0;
        return new PreviewItem(
                titleForItem(item, descriptor),
                firstNonBlank(descriptor.getPathOrUrl(), item.getUrl()),
                descriptor.getType() != null ? descriptor.getType().name() : null,
                item.getContentType(),
                item.getContentLength(),
                item.getContentHash(),
                item.getDepth(),
                loaderName,
                0,
                chunkCount,
                charCount,
                null,
                sanitizeMetadata(metadata));
    }

    private List<PreviewItem> previewItemsFromDocuments(
            String titleBase,
            String pathOrUrl,
            DocumentSourceDescriptor.SourceType sourceType,
            String loaderName,
            List<Document> documents,
            String chunkerName,
            List<String> warnings,
            int previewLimit) {
        List<PreviewItem> items = new ArrayList<>();
        if (documents == null || documents.isEmpty()) {
            return items;
        }
        int max = Math.min(previewLimit, documents.size());
        for (int i = 0; i < max; i++) {
            Document doc = documents.get(i);
            String text = doc.getText() != null ? doc.getText() : "";
            int estimatedChunks = estimateChunks(doc, chunkerName, warnings);
            Map<String, Object> metadata = doc.getMetadata() != null ? doc.getMetadata() : Map.of();
            String title = firstNonBlank(asString(metadata.get("title")), asString(metadata.get("fileName")), titleBase);
            if (documents.size() > 1 && title != null && title.equals(titleBase)) {
                title = title + " #" + (i + 1);
            }
            items.add(new PreviewItem(
                    title,
                    firstNonBlank(asString(metadata.get("source")), asString(metadata.get("sourcePath")), pathOrUrl),
                    sourceType != null ? sourceType.name() : asString(metadata.get("sourceType")),
                    asString(metadata.get("contentType")),
                    asLong(metadata.get("contentLength")),
                    asString(metadata.get("contentHash")),
                    asInteger(metadata.get("depth"), 0),
                    loaderName,
                    1,
                    estimatedChunks,
                    text.length(),
                    previewText(text),
                    sanitizeMetadata(metadata)));
        }
        return items;
    }

    private GraphExtractionPreviewService.PreviewResponse previewGraphExtraction(
            SingleSourcePreviewRequest request,
            List<Document> documents,
            List<String> warnings) {
        if (!graphPreviewEnabled(request.properties())) {
            return GraphExtractionPreviewService.PreviewResponse.skipped("Graph extraction preview was disabled for this dry run.");
        }
        if (graphExtractionPreviewService == null) {
            return GraphExtractionPreviewService.PreviewResponse.unavailable(
                    "LLM graph extraction preview is not available in this runtime.");
        }

        int documentLimit = intProperty(request.properties(), "graphPreviewMaxDocuments",
                DEFAULT_GRAPH_PREVIEW_DOCUMENTS, MAX_GRAPH_PREVIEW_DOCUMENTS);
        int charLimit = intProperty(request.properties(), "graphPreviewMaxCharsPerDocument",
                DEFAULT_GRAPH_PREVIEW_CHARS, MAX_GRAPH_PREVIEW_CHARS);
        List<Document> extractionDocuments = buildGraphPreviewDocuments(
                documents, request.chunkerName(), documentLimit, warnings);

        GraphExtractionConfig graphConfig = defaultGraphExtractionConfig();
        ProcessingRouteConfig requestedRoute = processingRouteFromProperties(request.properties());
        UnifiedCrawlRequest crawlRequest = UnifiedCrawlRequest.builder()
                .name("Single source graph extraction preview")
                .graphExtraction(graphConfig)
                .processingRoute(singleSourceCrawlStarter != null
                        ? singleSourceCrawlStarter.mergeWithDefaultProcessingRoute(requestedRoute)
                        : requestedRoute)
                .build();
        if (singleSourceCrawlStarter != null) {
            singleSourceCrawlStarter.resolveSchemaPreset(crawlRequest);
            singleSourceCrawlStarter.applyDefaultProcessingRoute(crawlRequest);
            graphConfig = crawlRequest.getGraphExtraction();
        }

        // Per-run model overrides ride in on the same properties channel as the other graphPreview*
        // knobs, so the request record and every existing caller stay untouched.
        String overrideModel = stringProperty(request.properties(), "graphPreviewModelName");
        if (overrideModel != null) {
            graphConfig.setModelName(overrideModel);
        }
        String overrideProvider = stringProperty(request.properties(), "graphPreviewLlmProvider");
        if (overrideProvider != null) {
            graphConfig.setLlmProvider(overrideProvider);
        }

        return graphExtractionPreviewService.preview(
                extractionDocuments,
                graphConfig,
                crawlRequest.getProcessingRoute(),
                documentLimit,
                charLimit);
    }

    private String stringProperty(Map<String, Object> properties, String key) {
        if (properties == null) {
            return null;
        }
        Object value = properties.get(key);
        return value instanceof String string && !string.isBlank() ? string.trim() : null;
    }

    private List<Document> routePdfDocumentsForPreview(
            List<Document> documents,
            SingleSourcePreviewRequest request,
            String fallbackPath,
            List<String> warnings) {
        if (documents == null || documents.isEmpty()) {
            return List.of();
        }

        ProcessingRouteConfig requestedRoute = processingRouteFromProperties(request.properties());
        ProcessingRouteConfig routeConfig = singleSourceCrawlStarter != null
                ? singleSourceCrawlStarter.mergeWithDefaultProcessingRoute(requestedRoute)
                : requestedRoute;
        if (routeConfig == null) {
            routeConfig = ProcessingRouteConfig.builder()
                    .pdfRoutingMode(ProcessingRouteConfig.PdfRoutingMode.AUTO)
                    .extractTablesFromTextPdfs(true)
                    .build();
        }
        ProcessingRouteConfig.PdfRoutingMode mode = routeConfig.getPdfRoutingMode() != null
                ? routeConfig.getPdfRoutingMode()
                : ProcessingRouteConfig.PdfRoutingMode.AUTO;
        if (mode == ProcessingRouteConfig.PdfRoutingMode.DISABLED) {
            return documents;
        }

        Map<String, PdfClassificationResult> classifications = new LinkedHashMap<>();
        Map<String, Boolean> warningEmitted = new LinkedHashMap<>();
        for (Document document : documents) {
            Map<String, Object> metadata = document.getMetadata();
            if (metadata == null) {
                continue;
            }
            String pdfPath = resolvePdfSourcePath(metadata, fallbackPath);
            if (!isPdfPath(pdfPath, metadata)) {
                continue;
            }

            PdfClassificationResult classification = classifications.get(pdfPath);
            if (!classifications.containsKey(pdfPath)) {
                classification = classifyPdfForPreview(pdfPath, warnings, warningEmitted);
                classifications.put(pdfPath, classification);
            }
            applyPdfPreviewRoute(document, pdfPath, routeConfig, classification);
            emitPdfRouteWarning(pdfPath, routeConfig, classification, warnings, warningEmitted);
        }
        return documents;
    }

    private PdfClassificationResult classifyPdfForPreview(
            String pdfPath,
            List<String> warnings,
            Map<String, Boolean> warningEmitted) {
        if (pdfContentClassifier == null) {
            emitOnce(warnings, warningEmitted, pdfPath + ":classifier",
                    "PDF routing preview could not classify " + fileName(pdfPath)
                            + " because the PDF classifier is not available.");
            return null;
        }
        try {
            File file = new File(pdfPath);
            if (!file.exists()) {
                emitOnce(warnings, warningEmitted, pdfPath + ":missing",
                        "PDF routing preview could not find " + fileName(pdfPath) + " for classification.");
                return null;
            }
            return pdfContentClassifier.classify(file);
        } catch (Exception e) {
            emitOnce(warnings, warningEmitted, pdfPath + ":failed",
                    "PDF routing preview failed for " + fileName(pdfPath) + ": " + e.getMessage());
            return null;
        }
    }

    private void applyPdfPreviewRoute(
            Document document,
            String pdfPath,
            ProcessingRouteConfig routeConfig,
            PdfClassificationResult classification) {
        Map<String, Object> metadata = document.getMetadata();
        ProcessingRouteConfig.PdfRoutingMode mode = routeConfig.getPdfRoutingMode() != null
                ? routeConfig.getPdfRoutingMode()
                : ProcessingRouteConfig.PdfRoutingMode.AUTO;
        boolean requiresVlm = requiresVlm(mode, classification);
        String route = effectivePdfRoute(mode, classification);

        metadata.put("pdfRoutingMode", mode.name());
        metadata.put(GraphConstants.META_PDF_PROCESSING_MODE, asString(metadata.getOrDefault(
                GraphConstants.META_PDF_PROCESSING_MODE, mode.name())));
        metadata.put("pdf_route", route);
        metadata.put("pdfRequiresVlm", requiresVlm);
        metadata.put("pdfEffectiveRoute", route);
        metadata.put("pdfRoutingAssessed", classification != null);
        metadata.put(GraphConstants.META_SOURCE_PATH, firstNonBlank(
                asString(metadata.get(GraphConstants.META_SOURCE_PATH)), pdfPath));
        if (blankToNull(routeConfig.getVlmModelId()) != null) {
            metadata.put(GraphConstants.META_VLM_MODEL, routeConfig.getVlmModelId());
        }
        if (requiresVlm) {
            metadata.put(GraphConstants.META_CONTENT_TYPE, "vlm_document");
        } else if (routeConfig.isExtractTablesFromTextPdfs()) {
            metadata.put("extract_tables", true);
        }
        if (classification != null) {
            metadata.put("pdf_classification", classification.contentType().name());
            metadata.put("pdfContentType", classification.contentType().name());
            metadata.put("pdf_page_count", classification.pageCount());
            metadata.put("pdfPageCount", classification.pageCount());
            metadata.put("pdf_image_pages", classification.imagePagesCount());
            metadata.put("pdfImagePagesCount", classification.imagePagesCount());
            metadata.put("pdf_image_page_indices", classification.imagePageIndices());
            metadata.put("pdf_text_chars", classification.textCharCount());
            metadata.put("pdfTextCharCount", classification.textCharCount());
            metadata.put("pdfClassificationTimeMs", classification.classificationTimeMs());
        }
    }

    private void emitPdfRouteWarning(
            String pdfPath,
            ProcessingRouteConfig routeConfig,
            PdfClassificationResult classification,
            List<String> warnings,
            Map<String, Boolean> warningEmitted) {
        ProcessingRouteConfig.PdfRoutingMode mode = routeConfig.getPdfRoutingMode() != null
                ? routeConfig.getPdfRoutingMode()
                : ProcessingRouteConfig.PdfRoutingMode.AUTO;
        String route = effectivePdfRoute(mode, classification);
        String basis = classification != null
                ? classification.contentType().name() + ", " + classification.imagePagesCount()
                        + "/" + classification.pageCount() + " image pages"
                : "classification unavailable";
        emitOnce(warnings, warningEmitted, pdfPath + ":route",
                "PDF routing preview: " + fileName(pdfPath) + " will use " + route.replace('_', ' ')
                        + " (" + basis + ").");
    }

    private ProcessingRouteConfig processingRouteFromProperties(Map<String, Object> properties) {
        if (properties == null || properties.isEmpty()) {
            return null;
        }
        Object routeValue = properties.get("processingRoute");
        if (routeValue instanceof ProcessingRouteConfig routeConfig) {
            return routeConfig;
        }
        Map<?, ?> routeMap = routeValue instanceof Map<?, ?> map ? map : properties;
        String pdfRoutingMode = firstNonBlank(
                asString(routeMap.get("pdfRoutingMode")),
                asString(routeMap.get("pdf_routing_mode")));
        String pdfProcessingMode = asString(routeMap.get("pdfProcessingMode"));
        String vlmModelId = firstNonBlank(
                asString(routeMap.get("vlmModelId")),
                asString(routeMap.get("vlm_model_id")));
        Boolean extractTables = booleanValue(firstNonBlank(
                asString(routeMap.get("extractTablesFromTextPdfs")),
                asString(routeMap.get("extractTables"))));
        int threshold = asInteger(routeMap.get("textThresholdCharsPerPage"),
                asInteger(routeMap.get("autoModeMinCharacters"), 50));

        boolean hasRoute = blankToNull(pdfRoutingMode) != null
                || blankToNull(pdfProcessingMode) != null
                || blankToNull(vlmModelId) != null
                || extractTables != null
                || routeMap.containsKey("textThresholdCharsPerPage")
                || routeMap.containsKey("autoModeMinCharacters");
        if (!hasRoute) {
            return null;
        }

        return ProcessingRouteConfig.builder()
                .pdfRoutingMode(blankToNull(pdfRoutingMode) != null
                        ? parsePdfRoutingMode(pdfRoutingMode)
                        : routeModeFromPdfProcessingMode(pdfProcessingMode))
                .vlmModelId(blankToNull(vlmModelId))
                .extractTablesFromTextPdfs(extractTables == null || extractTables)
                .textThresholdCharsPerPage(threshold > 0 ? threshold : 50)
                .build();
    }

    private ProcessingRouteConfig.PdfRoutingMode routeModeFromPdfProcessingMode(String pdfProcessingMode) {
        if (pdfProcessingMode == null || pdfProcessingMode.isBlank()) {
            return ProcessingRouteConfig.PdfRoutingMode.AUTO;
        }
        return switch (pdfProcessingMode.trim().toUpperCase(Locale.ROOT)) {
            case "VLM" -> ProcessingRouteConfig.PdfRoutingMode.FORCE_VLM;
            case "TEXT_EXTRACTION", "TRADITIONAL_OCR" -> ProcessingRouteConfig.PdfRoutingMode.FORCE_TEXT;
            case "DISABLED" -> ProcessingRouteConfig.PdfRoutingMode.DISABLED;
            default -> ProcessingRouteConfig.PdfRoutingMode.AUTO;
        };
    }

    private ProcessingRouteConfig.PdfRoutingMode parsePdfRoutingMode(String value) {
        try {
            return ProcessingRouteConfig.PdfRoutingMode.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (Exception e) {
            return routeModeFromPdfProcessingMode(value);
        }
    }

    private boolean requiresVlm(ProcessingRouteConfig.PdfRoutingMode mode, PdfClassificationResult classification) {
        return switch (mode) {
            case FORCE_VLM -> true;
            case FORCE_TEXT, DISABLED -> false;
            case AUTO -> classification != null && classification.requiresVlm();
        };
    }

    private String effectivePdfRoute(ProcessingRouteConfig.PdfRoutingMode mode, PdfClassificationResult classification) {
        if (mode == ProcessingRouteConfig.PdfRoutingMode.FORCE_VLM) {
            return "force_vlm";
        }
        if (mode == ProcessingRouteConfig.PdfRoutingMode.FORCE_TEXT) {
            return "force_text";
        }
        if (mode == ProcessingRouteConfig.PdfRoutingMode.DISABLED) {
            return "disabled";
        }
        if (classification == null) {
            return "unknown_fallback";
        }
        return switch (classification.contentType()) {
            case IMAGE_BASED -> "vlm";
            case MIXED -> "vlm_mixed";
            case TEXT_ONLY -> "text_extraction";
            default -> "unknown_fallback";
        };
    }

    private String resolvePdfSourcePath(Map<String, Object> metadata, String fallbackPath) {
        return firstNonBlank(
                asString(metadata.get(GraphConstants.META_SOURCE_PATH)),
                asString(metadata.get("sourcePath")),
                asString(metadata.get(GraphConstants.META_SOURCE)),
                asString(metadata.get("filePath")),
                fallbackPath,
                asString(metadata.get(GraphConstants.META_FILE_NAME)),
                asString(metadata.get("fileName")));
    }

    private boolean isPdfPath(String path, Map<String, Object> metadata) {
        String contentType = metadata != null ? asString(metadata.get("contentType")) : null;
        if (contentType != null && contentType.toLowerCase(Locale.ROOT).contains("pdf")) {
            return true;
        }
        return path != null && path.toLowerCase(Locale.ROOT).endsWith(".pdf");
    }

    private Boolean booleanValue(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if ("true".equals(normalized) || "1".equals(normalized) || "yes".equals(normalized)) {
            return true;
        }
        if ("false".equals(normalized) || "0".equals(normalized) || "no".equals(normalized)) {
            return false;
        }
        return null;
    }

    private void emitOnce(List<String> warnings, Map<String, Boolean> emitted, String key, String warning) {
        if (!Boolean.TRUE.equals(emitted.get(key))) {
            emitted.put(key, true);
            addWarning(warnings, warning);
        }
    }

    private String fileName(String path) {
        if (path == null || path.isBlank()) {
            return "PDF";
        }
        try {
            Path fileName = Path.of(path).getFileName();
            return fileName != null ? fileName.toString() : path;
        } catch (Exception e) {
            return path;
        }
    }

    private GraphExtractionConfig defaultGraphExtractionConfig() {
        if (singleSourceCrawlStarter != null) {
            return singleSourceCrawlStarter.defaultGraphExtractionConfig();
        }
        return GraphExtractionConfig.builder().build();
    }

    private List<Document> buildGraphPreviewDocuments(
            List<Document> documents,
            String requestedChunker,
            int documentLimit,
            List<String> warnings) {
        if (documents == null || documents.isEmpty()) {
            return List.of();
        }
        List<Document> result = new ArrayList<>();
        TextChunker chunker = selectChunker(requestedChunker);
        for (Document document : documents) {
            if (result.size() >= documentLimit) {
                break;
            }
            String text = document.getText() != null ? document.getText() : "";
            if (text.isBlank()) {
                continue;
            }
            if (chunker == null) {
                result.add(document);
                continue;
            }
            try {
                Map<String, Object> metadata = chunkerMetadata(document.getMetadata());
                String sourceDocumentId = firstNonBlank(document.getId(), asString(metadata.get("source")),
                        UUID.randomUUID().toString());
                RetrievedDoc retrievedDoc = new RetrievedDoc(sourceDocumentId, text, metadata);
                List<RetrievedDoc> chunks = chunker.chunk(retrievedDoc, chunker.getDefaultOptions());
                if (chunks == null || chunks.isEmpty()) {
                    result.add(document);
                    continue;
                }
                for (int i = 0; i < chunks.size() && result.size() < documentLimit; i++) {
                    result.add(toPreviewDocument(chunks.get(i), document, i));
                }
            } catch (Exception e) {
                addWarning(warnings, "Graph extraction preview used the loaded document because "
                        + chunker.getName() + " chunking failed: " + e.getMessage());
                result.add(document);
            }
        }
        return result;
    }

    private Document toPreviewDocument(RetrievedDoc chunk, Document sourceDocument, int chunkIndex) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (sourceDocument.getMetadata() != null) {
            metadata.putAll(sourceDocument.getMetadata());
        }
        if (chunk.getMetadata() != null) {
            metadata.putAll(chunk.getMetadata());
        }
        metadata.putIfAbsent("sourceDocumentId", firstNonBlank(sourceDocument.getId(), chunk.getId()));
        metadata.putIfAbsent("chunkIndex", chunkIndex);
        String chunkId = firstNonBlank(chunk.getId(), sourceDocument.getId(), "preview-chunk-" + chunkIndex);
        return new Document(chunkId, chunk.getText(), metadata);
    }

    private Map<String, Object> chunkerMetadata(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> clean = new LinkedHashMap<>();
        metadata.forEach((key, value) -> {
            if (key != null && value != null) {
                clean.put(key, value);
            }
        });
        return clean;
    }

    private boolean graphPreviewEnabled(Map<String, Object> properties) {
        Object value = properties != null ? properties.get("graphPreviewEnabled") : null;
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof String string && !string.isBlank()) {
            return !"false".equalsIgnoreCase(string.trim()) && !"0".equals(string.trim());
        }
        return true;
    }

    private int intProperty(Map<String, Object> properties, String key, int fallback, int max) {
        Object value = properties != null ? properties.get(key) : null;
        int resolved = asInteger(value, fallback);
        if (resolved <= 0) {
            return fallback;
        }
        return Math.min(max, resolved);
    }

    private LoadedDocuments loadDocuments(
            DocumentSourceDescriptor descriptor,
            String requestedLoader,
            List<String> warnings,
            boolean required) {
        DocumentLoader loader = selectLoader(descriptor, requestedLoader, warnings, required);
        if (loader == null) {
            return new LoadedDocuments(List.of(), blankToNull(requestedLoader));
        }
        try {
            List<Document> documents = loader.load(descriptor);
            if (documents == null) {
                documents = List.of();
            }
            for (Document document : documents) {
                if (document.getMetadata() != null) {
                    document.getMetadata().putIfAbsent("source", descriptor.getPathOrUrl());
                    document.getMetadata().putIfAbsent("sourceType",
                            descriptor.getType() != null ? descriptor.getType().name() : null);
                    if (descriptor.getOriginalFileName() != null) {
                        document.getMetadata().putIfAbsent("fileName", descriptor.getOriginalFileName());
                    }
                }
            }
            return new LoadedDocuments(documents, loader.getName());
        } catch (Exception e) {
            String message = "Could not load preview content for " + descriptor.getPathOrUrl()
                    + " with " + loader.getName() + ": " + e.getMessage();
            if (required) {
                throw new IllegalStateException(message, e);
            }
            addWarning(warnings, message);
            return new LoadedDocuments(List.of(), loader.getName());
        }
    }

    private DocumentLoader selectLoader(
            DocumentSourceDescriptor descriptor,
            String requestedLoader,
            List<String> warnings,
            boolean required) {
        if (requestedLoader != null && !requestedLoader.isBlank()) {
            Optional<DocumentLoader> requested = documentLoaders.stream()
                    .filter(loader -> namesMatch(loader, requestedLoader))
                    .findFirst();
            if (requested.isEmpty()) {
                if (required) {
                    throw new IllegalArgumentException("No loader found with name: " + requestedLoader);
                }
                addWarning(warnings, "No loader found with name: " + requestedLoader);
                return null;
            }
            if (!requested.get().supports(descriptor)) {
                if (required) {
                    throw new IllegalArgumentException("Loader '" + requested.get().getName()
                            + "' does not support source: " + descriptor.getPathOrUrl());
                }
                addWarning(warnings, "Loader '" + requested.get().getName()
                        + "' does not support " + descriptor.getPathOrUrl());
                return null;
            }
            return requested.get();
        }

        return documentLoaders.stream()
                .filter(loader -> safeSupports(loader, descriptor))
                .findFirst()
                .orElseGet(() -> {
                    if (required) {
                        throw new IllegalArgumentException("No loader supports source: " + descriptor.getPathOrUrl());
                    }
                    addWarning(warnings, "No loader supports source: " + descriptor.getPathOrUrl());
                    return null;
                });
    }

    private boolean namesMatch(DocumentLoader loader, String requestedLoader) {
        return loader.getName().equalsIgnoreCase(requestedLoader)
                || loader.getClass().getSimpleName().equalsIgnoreCase(requestedLoader)
                || loader.getClass().getName().equalsIgnoreCase(requestedLoader);
    }

    private boolean safeSupports(DocumentLoader loader, DocumentSourceDescriptor descriptor) {
        try {
            return loader.supports(descriptor);
        } catch (Exception e) {
            log.debug("Loader {} failed supports check for {}: {}", loader.getName(), descriptor.getPathOrUrl(),
                    e.getMessage());
            return false;
        }
    }

    private int estimateChunks(Document document, String requestedChunker, List<String> warnings) {
        String text = document.getText() != null ? document.getText() : "";
        if (text.isBlank()) {
            return 0;
        }
        TextChunker chunker = selectChunker(requestedChunker);
        if (chunker == null) {
            return fallbackChunkEstimate(text);
        }
        try {
            RetrievedDoc retrievedDoc = new RetrievedDoc(document.getId(), text,
                    document.getMetadata() != null ? document.getMetadata() : Map.of());
            List<RetrievedDoc> chunks = chunker.chunk(retrievedDoc, chunker.getDefaultOptions());
            return chunks != null ? Math.max(1, chunks.size()) : fallbackChunkEstimate(text);
        } catch (Exception e) {
            addWarning(warnings, "Chunk preview used character estimate because " + chunker.getName()
                    + " failed: " + e.getMessage());
            return fallbackChunkEstimate(text);
        }
    }

    private TextChunker selectChunker(String requestedChunker) {
        if (textChunkers.isEmpty()) {
            return null;
        }
        if (requestedChunker != null && !requestedChunker.isBlank()) {
            String mappedName = CHUNKER_ALIASES.getOrDefault(requestedChunker, requestedChunker);
            Optional<TextChunker> exact = textChunkers.stream()
                    .filter(chunker -> chunker.getName().equalsIgnoreCase(mappedName)
                            || chunker.getClass().getSimpleName().equalsIgnoreCase(mappedName))
                    .findFirst();
            if (exact.isPresent()) {
                return exact.get();
            }
        }
        return textChunkers.stream()
                .filter(chunker -> !isNoOpChunker(chunker))
                .findFirst()
                .orElse(textChunkers.get(0));
    }

    private String selectedChunkerName(String requestedChunker) {
        TextChunker chunker = selectChunker(requestedChunker);
        return chunker != null ? chunker.getName() : null;
    }

    private boolean isNoOpChunker(TextChunker chunker) {
        if (chunker == null || chunker.getName() == null) {
            return true;
        }
        String name = chunker.getName().toLowerCase(Locale.ROOT);
        String className = chunker.getClass().getName().toLowerCase(Locale.ROOT);
        return name.contains("noop") || name.contains("no-op") || name.contains("stub")
                || name.contains("dummy") || name.contains("default") || className.contains("noop");
    }

    private int fallbackChunkEstimate(String text) {
        return Math.max(1, (int) Math.ceil(text.length() / 1000.0));
    }

    private boolean shouldUseCrawler(DocumentSourceDescriptor.SourceType sourceType) {
        return sourceType == DocumentSourceDescriptor.SourceType.DIRECTORY
                || sourceType == DocumentSourceDescriptor.SourceType.WEB_CRAWL;
    }

    private Optional<Crawler> selectCrawler(DocumentSourceDescriptor.SourceType sourceType, Map<String, Object> properties) {
        if (crawlerRegistry == null) {
            return Optional.empty();
        }
        Object requestedCrawler = properties != null ? properties.get("crawlerId") : null;
        if (requestedCrawler instanceof String id && !id.isBlank()) {
            return crawlerRegistry.getCrawler(id);
        }
        return crawlerRegistry.findBySourceType(sourceType).stream().findFirst();
    }

    private DocumentSourceDescriptor normalizeDescriptorForLoading(
            CrawlItem item,
            DocumentSourceDescriptor.SourceType fallbackType,
            Map<String, Object> requestProperties) {
        DocumentSourceDescriptor descriptor = item.getSourceDescriptor();
        DocumentSourceDescriptor.SourceType type = descriptor != null && descriptor.getType() != null
                ? descriptor.getType()
                : fallbackType;
        if (type == DocumentSourceDescriptor.SourceType.WEB_CRAWL) {
            type = DocumentSourceDescriptor.SourceType.URL;
        }
        Map<String, Object> metadata = mergedMetadata(
                descriptor != null ? descriptor.getMetadata() : null,
                item.getMetadata());
        metadata.putAll(mutableProperties(requestProperties));
        return DocumentSourceDescriptor.builder()
                .type(type)
                .pathOrUrl(firstNonBlank(descriptor != null ? descriptor.getPathOrUrl() : null, item.getUrl()))
                .sourceId(firstNonBlank(descriptor != null ? descriptor.getSourceId() : null, item.getUrl()))
                .originalFileName(descriptor != null ? descriptor.getOriginalFileName() : null)
                .metadata(metadata)
                .collectionName(descriptor != null ? descriptor.getCollectionName() : null)
                .build();
    }

    private DocumentSourceDescriptor buildDescriptor(
            SingleSourcePreviewRequest request,
            DocumentSourceDescriptor.SourceType sourceType,
            int previewLimit) {
        String pathOrUrl = resolveSeed(request, sourceType);
        Map<String, Object> metadata = mutableProperties(request.properties());
        metadata.put("dryRun", true);
        metadata.put("preview", true);
        metadata.put("sourceType", sourceType.name());
        if (sourceType == DocumentSourceDescriptor.SourceType.JIRA) {
            metadata.put("maxIssues", Math.min(previewLimit,
                    Math.max(1, asInteger(metadata.get("maxIssues"), previewLimit))));
            metadata.put("commentLimit", Math.min(20,
                    Math.max(0, asInteger(metadata.get("commentLimit"), 20))));
        } else if (sourceType == DocumentSourceDescriptor.SourceType.REDDIT) {
            metadata.put("postLimit", Math.min(previewLimit,
                    Math.max(1, asInteger(metadata.get("postLimit"), previewLimit))));
            metadata.put("commentDepth", Math.min(2,
                    Math.max(1, asInteger(metadata.get("commentDepth"), 2))));
            metadata.put("commentLimit", Math.min(10,
                    Math.max(0, asInteger(metadata.get("commentLimit"), 10))));
        }
        if (request.label() != null && !request.label().isBlank()) {
            metadata.put("label", request.label());
        }
        return DocumentSourceDescriptor.builder()
                .type(sourceType)
                .pathOrUrl(pathOrUrl)
                .sourceId(sourceId(sourceType, pathOrUrl))
                .originalFileName(originalFileName(pathOrUrl))
                .metadata(metadata)
                .build();
    }

    private DocumentSourceDescriptor.SourceType resolveDescriptorSourceType(String normalizedType,
                                                                            SingleSourcePreviewRequest request) {
        return switch (normalizedType) {
            case "url" -> DocumentSourceDescriptor.SourceType.URL;
            case "path" -> resolvePathType(request.pathOrUrl());
            case "file" -> DocumentSourceDescriptor.SourceType.FILE;
            case "directory", "dir" -> DocumentSourceDescriptor.SourceType.DIRECTORY;
            case "web_crawl", "web-crawl", "webcrawl" -> DocumentSourceDescriptor.SourceType.WEB_CRAWL;
            case "slack" -> DocumentSourceDescriptor.SourceType.SLACK;
            case "slack_history", "slack-history" -> DocumentSourceDescriptor.SourceType.SLACK_HISTORY;
            case "confluence" -> DocumentSourceDescriptor.SourceType.CONFLUENCE;
            case "jira" -> DocumentSourceDescriptor.SourceType.JIRA;
            case "reddit" -> DocumentSourceDescriptor.SourceType.REDDIT;
            case "discord" -> DocumentSourceDescriptor.SourceType.DISCORD;
            case "discord_history", "discord-history" -> DocumentSourceDescriptor.SourceType.DISCORD_HISTORY;
            default -> parseSourceType(normalizedType);
        };
    }

    private DocumentSourceDescriptor.SourceType resolvePathType(String pathOrUrl) {
        if (pathOrUrl == null || pathOrUrl.isBlank()) {
            throw new IllegalArgumentException("Path is required for preview");
        }
        try {
            return Files.isDirectory(Path.of(pathOrUrl))
                    ? DocumentSourceDescriptor.SourceType.DIRECTORY
                    : DocumentSourceDescriptor.SourceType.FILE;
        } catch (Exception e) {
            return DocumentSourceDescriptor.SourceType.FILE;
        }
    }

    private DocumentSourceDescriptor.SourceType parseSourceType(String normalizedType) {
        try {
            return DocumentSourceDescriptor.SourceType.valueOf(normalizedType.toUpperCase(Locale.ROOT).replace('-', '_'));
        } catch (Exception e) {
            throw new IllegalArgumentException("Unsupported source type for preview: " + normalizedType);
        }
    }

    private String resolveSeed(SingleSourcePreviewRequest request, DocumentSourceDescriptor.SourceType sourceType) {
        String pathOrUrl = blankToNull(request.pathOrUrl());
        if (pathOrUrl != null) {
            return pathOrUrl;
        }
        if (sourceType == DocumentSourceDescriptor.SourceType.SLACK_HISTORY
                && Boolean.TRUE.equals(request.properties() != null ? request.properties().get("loadAllChannels") : false)) {
            return "all";
        }
        throw new IllegalArgumentException("Path or URL is required for preview");
    }

    private int resolveMaxDepth(Integer requestedDepth, DocumentSourceDescriptor.SourceType sourceType) {
        if (requestedDepth != null && requestedDepth >= 0) {
            return requestedDepth;
        }
        if (sourceType == DocumentSourceDescriptor.SourceType.DIRECTORY) {
            return 3;
        }
        if (sourceType == DocumentSourceDescriptor.SourceType.WEB_CRAWL) {
            return 1;
        }
        return 0;
    }

    private int resolvePreviewLimit(Integer requestedMaxDocuments) {
        if (requestedMaxDocuments == null || requestedMaxDocuments <= 0) {
            return DEFAULT_PREVIEW_LIMIT;
        }
        return Math.min(MAX_PREVIEW_LIMIT, requestedMaxDocuments);
    }

    private String normalizeSourceType(String sourceType) {
        if (sourceType == null || sourceType.isBlank()) {
            throw new IllegalArgumentException("Source type is required for preview");
        }
        return sourceType.trim().toLowerCase(Locale.ROOT);
    }

    private String titleForItem(CrawlItem item, DocumentSourceDescriptor descriptor) {
        Map<String, Object> metadata = mergedMetadata(descriptor.getMetadata(), item.getMetadata());
        return firstNonBlank(
                asString(metadata.get("title")),
                asString(metadata.get("fileName")),
                descriptor.getOriginalFileName(),
                item.getUrl());
    }

    private String effectiveLabel(SingleSourcePreviewRequest request, DocumentSourceDescriptor descriptor) {
        return firstNonBlank(request.label(), descriptor.getOriginalFileName(), descriptor.getPathOrUrl(), request.sourceType());
    }

    private String inferLoaderName(List<PreviewItem> items) {
        if (items == null) {
            return null;
        }
        return items.stream()
                .map(PreviewItem::loaderName)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    private int countDocuments(List<PreviewItem> items) {
        return items == null ? 0 : items.stream().mapToInt(PreviewItem::documentCount).sum();
    }

    private int countChunks(List<PreviewItem> items) {
        return items == null ? 0 : items.stream().mapToInt(PreviewItem::estimatedChunkCount).sum();
    }

    private String sourceId(DocumentSourceDescriptor.SourceType sourceType, String pathOrUrl) {
        String prefix = sourceType != null ? sourceType.name().toLowerCase(Locale.ROOT) : "source";
        String clean = pathOrUrl != null ? pathOrUrl.replaceAll("[^A-Za-z0-9._-]+", "-") : UUID.randomUUID().toString();
        return prefix + "-preview-" + clean;
    }

    private String originalFileName(String pathOrUrl) {
        if (pathOrUrl == null || pathOrUrl.isBlank()) {
            return null;
        }
        try {
            Path fileName = Path.of(pathOrUrl).getFileName();
            if (fileName != null) {
                return fileName.toString();
            }
        } catch (Exception ignored) {
            int slash = Math.max(pathOrUrl.lastIndexOf('/'), pathOrUrl.lastIndexOf('\\'));
            if (slash >= 0 && slash < pathOrUrl.length() - 1) {
                return pathOrUrl.substring(slash + 1);
            }
        }
        return pathOrUrl;
    }

    private Map<String, Object> mutableProperties(Map<String, Object> properties) {
        return properties != null ? new LinkedHashMap<>(properties) : new LinkedHashMap<>();
    }

    private Map<String, Object> mergedMetadata(Map<String, Object> first, Map<String, Object> second) {
        Map<String, Object> merged = new LinkedHashMap<>();
        if (first != null) {
            merged.putAll(first);
        }
        if (second != null) {
            merged.putAll(second);
        }
        return merged;
    }

    private Map<String, Object> sanitizeMetadata(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> sanitized = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : metadata.entrySet()) {
            if (entry.getKey() == null || isSensitiveKey(entry.getKey())) {
                continue;
            }
            sanitized.put(entry.getKey(), sanitizeValue(entry.getValue()));
            if (sanitized.size() >= 16) {
                break;
            }
        }
        return sanitized;
    }

    private boolean isSensitiveKey(String key) {
        String lower = key.toLowerCase(Locale.ROOT);
        return lower.contains("token") || lower.contains("password") || lower.contains("secret")
                || lower.contains("credential") || lower.contains("authorization");
    }

    private Object sanitizeValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number || value instanceof Boolean) {
            return value;
        }
        if (value instanceof CharSequence sequence) {
            String text = sequence.toString();
            return text.length() > 240 ? text.substring(0, 240) + "..." : text;
        }
        if (value instanceof Iterable<?> iterable) {
            int count = 0;
            for (Object ignored : iterable) {
                count++;
                if (count > 1000) {
                    break;
                }
            }
            return count + " items";
        }
        if (value instanceof Map<?, ?> map) {
            return map.size() + " fields";
        }
        return value.toString();
    }

    private void addWarning(List<String> warnings, String warning) {
        if (warnings != null && warning != null && warnings.size() < MAX_WARNINGS) {
            warnings.add(warning);
        }
    }

    private String previewText(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String normalized = text.replaceAll("\\s+", " ").trim();
        return normalized.length() > PREVIEW_TEXT_LIMIT
                ? normalized.substring(0, PREVIEW_TEXT_LIMIT) + "..."
                : normalized;
    }

    private String itemUrl(CrawlItem item) {
        return item != null ? item.getUrl() : null;
    }

    private String firstNonBlank(String... values) {
        return Arrays.stream(values)
                .filter(value -> value != null && !value.isBlank())
                .findFirst()
                .orElse(null);
    }

    private String blankToNull(String value) {
        return value != null && !value.isBlank() ? value.trim() : null;
    }

    private String asString(Object value) {
        return value != null ? value.toString() : null;
    }

    private Long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String string) {
            try {
                return Long.parseLong(string);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private int asInteger(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String string) {
            try {
                return Integer.parseInt(string);
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    public record SingleSourcePreviewRequest(
            String sourceType,
            String label,
            String pathOrUrl,
            String content,
            String loaderName,
            String chunkerName,
            Integer maxDepth,
            Integer maxDocuments,
            String language,
            Map<String, Object> properties) {
    }

    public record SingleSourcePreviewResponse(
            String previewId,
            boolean dryRun,
            String sourceType,
            String label,
            String status,
            boolean crawlerUsed,
            String crawlerName,
            String loaderName,
            String chunkerName,
            int discoveredCount,
            int documentCount,
            int estimatedChunkCount,
            int failedCount,
            int skippedCount,
            boolean truncated,
            List<PreviewItem> items,
            List<String> warnings,
            GraphExtractionPreviewService.PreviewResponse graphExtraction) {
    }

    public record PreviewItem(
            String title,
            String pathOrUrl,
            String sourceType,
            String contentType,
            Long contentLength,
            String contentHash,
            int depth,
            String loaderName,
            int documentCount,
            int estimatedChunkCount,
            int characterCount,
            String previewText,
            Map<String, Object> metadata) {
    }

    private record LoadedDocuments(List<Document> documents, String loaderName) {
    }

    private record PreviewAssembly(List<PreviewItem> items, List<Document> documents) {
    }

    private record CrawlPreviewRun(
            List<CrawlItem> items,
            CrawlSummary summary,
            int discoveredCount,
            int failedCount,
            int skippedCount,
            boolean truncated) {
    }
}
