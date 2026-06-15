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

import ai.kompile.app.config.ModelAutoInitializationService;
import ai.kompile.core.crawl.graph.GraphExtractionConfig;
import ai.kompile.core.crawl.graph.UnifiedCrawlJob;
import ai.kompile.core.crawl.graph.UnifiedCrawlRequest;
import ai.kompile.core.crawl.graph.UnifiedCrawlService;
import ai.kompile.core.crawl.graph.UnifiedCrawlSource;
import ai.kompile.core.crawl.graph.VectorIndexConfig;
import ai.kompile.core.graphrag.model.schema.SchemaEnforcementMode;
import ai.kompile.core.loaders.DocumentSourceDescriptor;
import ai.kompile.project.KompileProjectCrawlProfile;
import ai.kompile.project.KompileProjectLifecycleState;
import ai.kompile.project.KompileProjectManifest;
import ai.kompile.project.KompileProjectStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Automatically starts crawl profiles that have {@code graphAutoStart=true}
 * in the project manifest ({@code kompile.project.json}) when the application
 * starts up with an active project.
 *
 * <p>Fires on {@link ApplicationReadyEvent} after the staging server auto-start
 * (which runs at Order(0)). Waits for the embedding model to be initialized
 * before submitting crawl jobs, so that vector indexing doesn't fail.</p>
 *
 * <p>Auto-start is controlled entirely by the managed project configuration:
 * each crawl profile's {@code graphAutoStart} field (set via CLI or web UI)
 * determines whether it runs on startup. No Spring properties needed.</p>
 */
@Service
public class CrawlProfileAutoStartService {

    private static final Logger log = LoggerFactory.getLogger(CrawlProfileAutoStartService.class);

    /** Maximum time to wait for the embedding model to initialize (seconds). */
    private static final int EMBEDDING_WAIT_TIMEOUT_SECONDS = 300;

    /** Poll interval when waiting for the embedding model (seconds). */
    private static final int EMBEDDING_POLL_INTERVAL_SECONDS = 5;

    private final UnifiedCrawlService crawlService;
    private final KompileProjectStore store;
    private final ModelAutoInitializationService modelAutoInit;

    @Value("${kompile.project.root:}")
    private String configuredRoot;

    @Autowired
    public CrawlProfileAutoStartService(
            @Autowired(required = false) UnifiedCrawlService crawlService,
            KompileProjectStore store,
            @Autowired(required = false) ModelAutoInitializationService modelAutoInit) {
        this.crawlService = crawlService;
        this.store = store;
        this.modelAutoInit = modelAutoInit;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Order(10) // After staging auto-start (Order 0) and other init
    public void onApplicationReady() {
        if (crawlService == null) {
            log.debug("UnifiedCrawlService not available, skipping crawl auto-start");
            return;
        }

        Path projectRoot = resolveProjectRoot();
        if (projectRoot == null) {
            log.debug("No project root found, skipping crawl auto-start");
            return;
        }

        KompileProjectManifest manifest;
        try {
            manifest = store.load(projectRoot);
        } catch (Exception e) {
            log.debug("Could not load project manifest at {}: {}", projectRoot, e.getMessage());
            return;
        }

        if (manifest == null) {
            log.debug("No project manifest found at {}", projectRoot);
            return;
        }

        List<KompileProjectCrawlProfile> profiles = manifest.getCrawlProfiles();
        if (profiles == null || profiles.isEmpty()) {
            log.debug("No crawl profiles configured in project");
            return;
        }

        // Filter to auto-start profiles that are active
        List<KompileProjectCrawlProfile> autoStartProfiles = profiles.stream()
                .filter(KompileProjectCrawlProfile::isGraphAutoStart)
                .filter(p -> p.getLifecycle() == null
                        || p.getLifecycle() == KompileProjectLifecycleState.ACTIVE)
                .toList();

        if (autoStartProfiles.isEmpty()) {
            log.debug("No crawl profiles have graphAutoStart=true");
            return;
        }

        // Schedule crawls on a background thread that waits for embedding readiness
        final Path root = projectRoot;
        Thread crawlThread = new Thread(() -> {
            waitForEmbeddingModel();
            submitCrawlJobs(autoStartProfiles, root);
        }, "crawl-auto-start");
        crawlThread.setDaemon(true);
        crawlThread.start();
    }

    private void waitForEmbeddingModel() {
        if (modelAutoInit == null) {
            log.debug("ModelAutoInitializationService not available, proceeding without embedding wait");
            return;
        }

        if (modelAutoInit.isEmbeddingInitialized()) {
            log.debug("Embedding model already initialized");
            return;
        }

        log.info("Waiting for embedding model to initialize before starting auto-crawls...");
        long deadline = System.currentTimeMillis() + (EMBEDDING_WAIT_TIMEOUT_SECONDS * 1000L);

        while (System.currentTimeMillis() < deadline) {
            if (modelAutoInit.isEmbeddingInitialized()) {
                log.info("Embedding model ready — proceeding with auto-crawls");
                return;
            }
            try {
                Thread.sleep(EMBEDDING_POLL_INTERVAL_SECONDS * 1000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.debug("Crawl auto-start thread interrupted while waiting for embedding model");
                return;
            }
        }

        log.warn("Embedding model did not initialize within {}s — proceeding with auto-crawls anyway "
                + "(vector indexing may fail until the model loads)",
                EMBEDDING_WAIT_TIMEOUT_SECONDS);
    }

    private void submitCrawlJobs(List<KompileProjectCrawlProfile> profiles, Path projectRoot) {
        log.info("Auto-starting {} crawl profile(s)", profiles.size());

        for (KompileProjectCrawlProfile profile : profiles) {
            try {
                UnifiedCrawlRequest request = buildRequestFromProfile(profile, projectRoot);
                UnifiedCrawlJob job = crawlService.startJob(request);
                log.info("Auto-started crawl profile '{}' (id={}) as job {}",
                        profile.getName(), profile.getId(), job.getJobId());
            } catch (Exception e) {
                log.warn("Failed to auto-start crawl profile '{}': {}",
                        profile.getName(), e.getMessage());
            }
        }
    }

    private UnifiedCrawlRequest buildRequestFromProfile(
            KompileProjectCrawlProfile profile, Path projectRoot) {
        List<UnifiedCrawlSource> sources = new ArrayList<>();

        DocumentSourceDescriptor.SourceType sourceType = resolveSourceType(profile);

        for (String source : profile.getSources()) {
            String resolvedPath = source;
            // Resolve relative paths against project root for local sources
            if (sourceType == DocumentSourceDescriptor.SourceType.DIRECTORY
                    || sourceType == DocumentSourceDescriptor.SourceType.FILE) {
                Path sourcePath = Path.of(source);
                if (!sourcePath.isAbsolute()) {
                    resolvedPath = projectRoot.resolve(sourcePath).toString();
                }
            }

            UnifiedCrawlSource crawlSource = UnifiedCrawlSource.builder()
                    .label(profile.getName() + " - " + source)
                    .sourceType(sourceType)
                    .pathOrUrl(resolvedPath)
                    .maxDepth(profile.getMaxDepth())
                    .maxDocuments(profile.getMaxDocuments())
                    .includePatterns(profile.getIncludePatterns())
                    .excludePatterns(profile.getExcludePatterns())
                    .build();
            sources.add(crawlSource);
        }

        UnifiedCrawlRequest.UnifiedCrawlRequestBuilder builder = UnifiedCrawlRequest.builder()
                .name("Auto-start: " + profile.getName())
                .sources(sources);

        if (profile.getFactSheetName() != null && !profile.getFactSheetName().isBlank()) {
            builder.factSheetName(profile.getFactSheetName());
        }

        // Configure graph extraction if enabled on the profile
        if (profile.isGraphExtraction()) {
            GraphExtractionConfig graphConfig = GraphExtractionConfig.builder()
                    .enabled(true)
                    .build();
            if (profile.getGraphModelProvider() != null) {
                graphConfig.setLlmProvider(profile.getGraphModelProvider());
            }
            if (profile.getGraphModelName() != null) {
                graphConfig.setModelName(profile.getGraphModelName());
            }
            if (profile.getGraphMinConfidence() != null) {
                graphConfig.setMinConfidence(profile.getGraphMinConfidence());
            }
            if (profile.getGraphEntityTypes() != null && !profile.getGraphEntityTypes().isEmpty()) {
                graphConfig.setEntityTypes(profile.getGraphEntityTypes());
            }
            if (profile.getGraphRelationTypes() != null && !profile.getGraphRelationTypes().isEmpty()) {
                graphConfig.setRelationshipTypes(profile.getGraphRelationTypes());
            }
            if (profile.getSchemaPresetId() != null) {
                graphConfig.setSchemaPresetId(profile.getSchemaPresetId());
            }
            if (profile.getGraphSchemaMode() != null) {
                try {
                    graphConfig.setSchemaMode(
                            SchemaEnforcementMode.valueOf(profile.getGraphSchemaMode().toUpperCase()));
                } catch (IllegalArgumentException ignored) {
                    // Keep default LENIENT
                }
            }
            builder.graphExtraction(graphConfig);
        }

        // Always enable vector indexing for auto-start crawls
        builder.vectorIndex(VectorIndexConfig.builder()
                .enabled(true)
                .build());

        return builder.build();
    }

    private DocumentSourceDescriptor.SourceType resolveSourceType(KompileProjectCrawlProfile profile) {
        if (profile.getSourceType() != null && !profile.getSourceType().isBlank()) {
            try {
                return DocumentSourceDescriptor.SourceType.valueOf(profile.getSourceType());
            } catch (IllegalArgumentException e) {
                log.warn("Unknown source type '{}' in crawl profile '{}', defaulting to DIRECTORY",
                        profile.getSourceType(), profile.getName());
            }
        }
        // Default: if sources look like URLs, use WEB_CRAWL; otherwise DIRECTORY
        if (profile.getSources() != null && !profile.getSources().isEmpty()) {
            String first = profile.getSources().get(0);
            if (first.startsWith("http://") || first.startsWith("https://")) {
                return DocumentSourceDescriptor.SourceType.WEB_CRAWL;
            }
        }
        return DocumentSourceDescriptor.SourceType.DIRECTORY;
    }

    private Path resolveProjectRoot() {
        Path start = (configuredRoot != null && !configuredRoot.isBlank())
                ? Path.of(configuredRoot)
                : Path.of(System.getProperty("user.dir"));
        Optional<Path> manifestRoot = store.findProjectRoot(start);
        return manifestRoot.orElse(null);
    }
}
