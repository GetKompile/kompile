/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.knowledgegraph.resolution;

import ai.kompile.cli.common.KompileHome;
import ai.kompile.core.embeddings.EmbeddingModel;
import ai.kompile.knowledgegraph.domain.*;
import ai.kompile.knowledgegraph.repository.GraphNodeRepository;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import com.fasterxml.jackson.databind.JsonNode;
import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.bytedeco.javacpp.Pointer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Graph compaction service that finds and merges similar entities in the
 * persisted knowledge graph.
 *
 * <p>Inspired by Senzing's principles-based entity resolution, the pipeline:
 * <ol>
 *   <li><b>Blocking</b> — group ENTITY nodes by type to reduce O(n²) comparisons</li>
 *   <li><b>Candidate matching</b> — within each block, compute pairwise similarity
 *       via normalized title matching, alias overlap, Levenshtein distance,
 *       embedding-based cosine similarity, and attribute-behavior scoring</li>
 *   <li><b>Connected components</b> — build a match graph and find connected components
 *       (cliques of entities that should merge)</li>
 *   <li><b>Merge</b> — for each component, elect a canonical entity, redirect all
 *       edges to it, and merge metadata/descriptions</li>
 *   <li><b>Explainability</b> — each merge decision records why entities matched
 *       (Senzing-style why/why-not/how), including step-by-step assembly traces</li>
 * </ol>
 */
@Service
public class GraphCompactionService {

    private static final Logger log = LoggerFactory.getLogger(GraphCompactionService.class);

    private static final double DEFAULT_SIMILARITY_THRESHOLD = 0.85;
    private static final double DEFAULT_EMBEDDING_THRESHOLD = 0.88;
    private static final String GRAPH_EXTRACTION_CONFIG_FILENAME = "graph-extraction-config.json";
    private static final int DEFAULT_MAX_NODES_PER_RESOLUTION_BLOCK = 500;
    private static final int DEFAULT_MAX_CANDIDATES_PER_RESOLUTION_BLOCK = 10_000;
    private static final int DEFAULT_MAX_TOTAL_RESOLUTION_CANDIDATES = 50_000;
    private static final int DEFAULT_MAX_CROSS_TYPE_RESOLUTION_CANDIDATES = 10_000;
    private static final int DEFAULT_MAX_NORMALIZED_TITLE_FREQUENCY = 200;
    private static final Pattern SUFFIX_PATTERN = Pattern.compile(
            "\\b(Inc\\.?|Corp\\.?|Corporation|Ltd\\.?|Limited|LLC|Co\\.?|Company|Group|Plc\\.?)$",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern CELL_REFERENCE_PATTERN = Pattern.compile(
            "^(?:'[^']+'|[A-Za-z0-9_ .-]+)?!?.*\\b[A-Za-z]{1,4}\\$?\\d{1,7}\\b.*$"
    );
    private static final Pattern NUMERIC_VALUE_PATTERN = Pattern.compile(
            "^[($+-]?\\d[\\d,]*(?:\\.\\d+)?%?\\)?$"
    );

    /**
     * CJK Unicode blocks and interpunct separators to strip during normalization.
     * Covers CJK Unified Ideographs, Hiragana, Katakana, and common interpuncts.
     */
    private static final Pattern CJK_AND_INTERPUNCT_PATTERN = Pattern.compile(
            "[\u4e00-\u9fff\u3040-\u309f\u30a0-\u30ff\u00b7\u2022\u30fb]"
    );

    /**
     * Cross-type pairs that should be compared even though they live in different
     * type blocks. E.g. the same person extracted as both CELL and PERSON.
     */
    public static final Set<String> COMPATIBLE_TYPE_PAIRS = Set.of(
            "CELL|PERSON", "PERSON|CELL",
            "CELL|APPROVAL_ROLE", "APPROVAL_ROLE|CELL",
            "PERSON|APPROVAL_ROLE", "APPROVAL_ROLE|PERSON"
    );


    private static final Set<String> GENERIC_SPREADSHEET_ENTITY_TYPES = Set.of(
            "CELL",
            "CELL_VALUE",
            "SPREADSHEET_CELL",
            "TABLE_CELL",
            "FORMULA_CELL",
            "HEADER_CELL",
            "CELL_COMMENT",
            "DATA_VALIDATION",
            "NUMERIC_VALUE",
            "NUMBER",
            "VALUE",
            "ROW",
            "COLUMN",
            "RANGE",
            "TABLE",
            "SPREADSHEET",
            "SPREADSHEET_SHEET",
            "DOCUMENT_SECTION",
            "CODE_BLOCK",
            "WEB_PAGE",
            "OFFICE_DOCUMENT",
            "MARKDOWN_DOCUMENT",
            "EMAIL_MESSAGE",
            "EMAIL_THREAD",
            "EXTERNAL_LINK",
            "HYPERLINK",
            "DATE",
            "ATTACHMENT",
            "FILE_ATTACHMENT",
            "DATA_QUALITY_REPORT"
    );

    private static final Set<String> GENERIC_TITLE_VALUES = Set.of(
            "",
            "-",
            "--",
            "n/a",
            "na",
            "none",
            "null",
            "total",
            "subtotal",
            "value",
            "amount"
    );

    private static final Set<String> EXPLICIT_RESOLUTION_SKIP_KEYS = Set.of(
            "skip_entity_resolution",
            "entity_resolution_skip",
            "resolution_skip",
            "skipEntityResolution"
    );

    private static final Set<String> SPREADSHEET_COORDINATE_KEYS = Set.of(
            "cell_reference",
            "cellReference",
            "cell_address",
            "cellAddress",
            "row_index",
            "rowIndex",
            "column_index",
            "columnIndex"
    );

    /**
     * Abbreviation pattern: "X." or "X. Y." where X/Y are single uppercase letters.
     * Matches initial(s) at the start of a name.
     */
    private static final Pattern INITIAL_PATTERN = Pattern.compile(
            "^([A-Za-z])\\.\\s+"
    );

    /**
     * Entity type correction patterns — titles that are clearly mistyped as PERSON.
     */
    private static final Pattern EMAIL_TITLE_PATTERN = Pattern.compile(
            "^[\\w.+-]+@[\\w.-]+\\.[a-zA-Z]{2,}$"
    );

    /**
     * Extracts parenthetical translations: "München (Munich)" → ["münchen", "munich"].
     * Also handles CJK parentheticals: "東京 (Tokyo)" and reversed "Tokyo (東京)".
     */
    private static final Pattern PARENTHETICAL_PATTERN = Pattern.compile(
            "^(.+?)\\s*[\\(（](.+?)[\\)）]\\s*$"
    );

    /**
     * Slash or pipe separator between language variants: "München/Munich", "Москва | Moscow".
     */
    private static final Pattern LANG_SEPARATOR_PATTERN = Pattern.compile(
            "\\s*[/|]\\s*"
    );

    /**
     * CJK character class — used to detect whether a string contains CJK script.
     * Covers CJK Unified Ideographs, Hiragana, Katakana, Hangul, and CJK extensions.
     */
    private static final Pattern CJK_CHAR_PATTERN = Pattern.compile(
            "[\u4e00-\u9fff\u3040-\u309f\u30a0-\u30ff\uac00-\ud7af\u3400-\u4dbf]"
    );

    /**
     * Cyrillic character class — for detecting Russian/Ukrainian/etc. script.
     */
    private static final Pattern CYRILLIC_CHAR_PATTERN = Pattern.compile(
            "[\u0400-\u04ff]"
    );

    /**
     * Latin character class — for detecting Latin-script portions of mixed names.
     */
    private static final Pattern LATIN_CHAR_PATTERN = Pattern.compile(
            "[a-zA-ZÀ-ÖØ-öø-ÿ]"
    );

    /**
     * Attribute behaviors modeled after Senzing — each attribute type has a
     * frequency/exclusivity weight. Exclusive attributes (email, URL) are strong
     * match signals; frequent attributes (country, industry) are weak.
     */
    static final Map<String, AttributeBehavior> ATTRIBUTE_BEHAVIORS = Map.ofEntries(
            Map.entry("email", new AttributeBehavior(0.95, "EXCLUSIVE")),
            Map.entry("url", new AttributeBehavior(0.90, "EXCLUSIVE")),
            Map.entry("website", new AttributeBehavior(0.90, "EXCLUSIVE")),
            Map.entry("phone", new AttributeBehavior(0.85, "EXCLUSIVE")),
            Map.entry("address", new AttributeBehavior(0.80, "CLOSE_EXCLUSIVE")),
            Map.entry("location", new AttributeBehavior(0.50, "FREQUENT")),
            Map.entry("country", new AttributeBehavior(0.20, "VERY_FREQUENT")),
            Map.entry("industry", new AttributeBehavior(0.30, "FREQUENT")),
            Map.entry("founded", new AttributeBehavior(0.60, "STABLE")),
            Map.entry("ticker", new AttributeBehavior(0.90, "EXCLUSIVE")),
            Map.entry("doi", new AttributeBehavior(0.95, "EXCLUSIVE")),
            Map.entry("isbn", new AttributeBehavior(0.95, "EXCLUSIVE")),
            Map.entry("sku", new AttributeBehavior(0.92, "EXCLUSIVE")),
            Map.entry("sku_id", new AttributeBehavior(0.92, "EXCLUSIVE")),
            Map.entry("sku_code", new AttributeBehavior(0.92, "EXCLUSIVE")),
            Map.entry("product_id", new AttributeBehavior(0.90, "EXCLUSIVE")),
            Map.entry("product_code", new AttributeBehavior(0.90, "EXCLUSIVE")),
            Map.entry("forecast_id", new AttributeBehavior(0.92, "EXCLUSIVE")),
            Map.entry("control_id", new AttributeBehavior(0.90, "EXCLUSIVE")),
            Map.entry("assertion_id", new AttributeBehavior(0.90, "EXCLUSIVE")),
            Map.entry("account_code", new AttributeBehavior(0.88, "EXCLUSIVE")),
            Map.entry("currency_code", new AttributeBehavior(0.88, "EXCLUSIVE")),
            Map.entry("iso_code", new AttributeBehavior(0.88, "EXCLUSIVE")),
            Map.entry("channel_code", new AttributeBehavior(0.85, "EXCLUSIVE")),
            Map.entry("region", new AttributeBehavior(0.35, "FREQUENT")),
            Map.entry("channel", new AttributeBehavior(0.40, "FREQUENT")),
            Map.entry("period", new AttributeBehavior(0.55, "STABLE")),
            Map.entry("forecast_period", new AttributeBehavior(0.55, "STABLE"))
    );

    private final KnowledgeGraphService knowledgeGraphService;

    @Autowired(required = false)
    private EmbeddingModel embeddingModel;

    @Autowired(required = false)
    private GraphNodeRepository nodeRepository;

    private int embeddingCacheSize = 128;

    private int embeddingNativeMemoryThresholdPercent = 80;

    private int maxEmbeddingBlockSize = 64;

    private int nativeMemoryCleanupPasses = 3;

    private int maxNodesPerResolutionBlock = DEFAULT_MAX_NODES_PER_RESOLUTION_BLOCK;

    private int maxCandidatesPerResolutionBlock = DEFAULT_MAX_CANDIDATES_PER_RESOLUTION_BLOCK;

    private int maxTotalResolutionCandidates = DEFAULT_MAX_TOTAL_RESOLUTION_CANDIDATES;

    private int maxCrossTypeResolutionCandidates = DEFAULT_MAX_CROSS_TYPE_RESOLUTION_CANDIDATES;

    private int maxNormalizedTitleFrequency = DEFAULT_MAX_NORMALIZED_TITLE_FREQUENCY;

    private boolean skipGenericSpreadsheetEntities = true;

    private final ObjectMapper configObjectMapper = JsonUtils.standardMapper();
    private final Path graphExtractionConfigPath =
            KompileHome.dataDir().toPath().resolve("config").resolve(GRAPH_EXTRACTION_CONFIG_FILENAME);
    private volatile long graphExtractionConfigLastModified = Long.MIN_VALUE;
    private volatile CompactionRuntimeConfig compactionRuntimeConfig = CompactionRuntimeConfig.defaults();

    // Bounded per-run cache stores heap float arrays only. Keeping entity-resolution
    // vectors out of INDArray prevents the app JVM from loading/retaining ND4J state;
    // all model execution remains isolated in the embedding subprocess.
    private final ThreadLocal<BoundedEmbeddingCache> embeddingCache =
            ThreadLocal.withInitial(() -> new BoundedEmbeddingCache(Math.max(0, embeddingCacheSize)));
    private final ThreadLocal<Boolean> embeddingMatchingDisabledForRun =
            ThreadLocal.withInitial(() -> false);
    private final ThreadLocal<Boolean> nativeEmbeddingsTouchedForRun =
            ThreadLocal.withInitial(() -> false);

    public GraphCompactionService(KnowledgeGraphService knowledgeGraphService) {
        this.knowledgeGraphService = knowledgeGraphService;
    }

    private synchronized CompactionRuntimeConfig refreshRuntimeConfig() {
        try {
            CompactionRuntimeConfig config;
            if (!Files.exists(graphExtractionConfigPath)) {
                graphExtractionConfigLastModified = Long.MIN_VALUE;
                config = CompactionRuntimeConfig.defaults();
            } else {
                long lastModified = Files.getLastModifiedTime(graphExtractionConfigPath).toMillis();
                if (lastModified != graphExtractionConfigLastModified) {
                    JsonNode root = configObjectMapper.readTree(graphExtractionConfigPath.toFile());
                    compactionRuntimeConfig = CompactionRuntimeConfig.from(root);
                    graphExtractionConfigLastModified = lastModified;
                    log.debug("Loaded graph compaction runtime config from {}", graphExtractionConfigPath);
                }
                config = compactionRuntimeConfig;
            }
            applyRuntimeConfig(config);
            return config;
        } catch (Exception e) {
            log.warn("Failed to read graph compaction runtime config from {}: {}",
                    graphExtractionConfigPath, e.getMessage());
            applyRuntimeConfig(compactionRuntimeConfig);
            return compactionRuntimeConfig;
        }
    }

    private void applyRuntimeConfig(CompactionRuntimeConfig config) {
        embeddingCacheSize = config.embeddingCacheSize;
        embeddingNativeMemoryThresholdPercent = config.embeddingNativeMemoryThresholdPercent;
        maxEmbeddingBlockSize = config.maxEmbeddingBlockSize;
        nativeMemoryCleanupPasses = config.nativeMemoryCleanupPasses;
        maxNodesPerResolutionBlock = config.maxNodesPerResolutionBlock;
        maxCandidatesPerResolutionBlock = config.maxCandidatesPerResolutionBlock;
        maxTotalResolutionCandidates = config.maxTotalResolutionCandidates;
        maxCrossTypeResolutionCandidates = config.maxCrossTypeResolutionCandidates;
        maxNormalizedTitleFrequency = config.maxNormalizedTitleFrequency;
        skipGenericSpreadsheetEntities = config.skipGenericSpreadsheetEntities;
    }

    private static class CompactionRuntimeConfig {
        private int embeddingCacheSize = 128;
        private int embeddingNativeMemoryThresholdPercent = 80;
        private int maxEmbeddingBlockSize = 64;
        private int nativeMemoryCleanupPasses = 3;
        private int maxNodesPerResolutionBlock = DEFAULT_MAX_NODES_PER_RESOLUTION_BLOCK;
        private int maxCandidatesPerResolutionBlock = DEFAULT_MAX_CANDIDATES_PER_RESOLUTION_BLOCK;
        private int maxTotalResolutionCandidates = DEFAULT_MAX_TOTAL_RESOLUTION_CANDIDATES;
        private int maxCrossTypeResolutionCandidates = DEFAULT_MAX_CROSS_TYPE_RESOLUTION_CANDIDATES;
        private int maxNormalizedTitleFrequency = DEFAULT_MAX_NORMALIZED_TITLE_FREQUENCY;
        private boolean skipGenericSpreadsheetEntities = true;

        static CompactionRuntimeConfig defaults() {
            return new CompactionRuntimeConfig();
        }

        static CompactionRuntimeConfig from(JsonNode root) {
            CompactionRuntimeConfig config = defaults();
            if (root == null) {
                return config;
            }
            config.embeddingCacheSize = intField(root, "compactionEmbeddingCacheSize", config.embeddingCacheSize, 0, 4096);
            config.embeddingNativeMemoryThresholdPercent = intField(root, "compactionEmbeddingNativeMemoryThresholdPercent", config.embeddingNativeMemoryThresholdPercent, 1, 100);
            config.maxEmbeddingBlockSize = intField(root, "compactionMaxEmbeddingBlockSize", config.maxEmbeddingBlockSize, 1, 4096);
            config.nativeMemoryCleanupPasses = intField(root, "compactionNativeMemoryCleanupPasses", config.nativeMemoryCleanupPasses, 1, 10);
            config.maxNodesPerResolutionBlock = intField(root, "compactionMaxNodesPerResolutionBlock", config.maxNodesPerResolutionBlock, 2, 100_000);
            config.maxCandidatesPerResolutionBlock = intField(root, "compactionMaxCandidatesPerResolutionBlock", config.maxCandidatesPerResolutionBlock, 1, 1_000_000);
            config.maxTotalResolutionCandidates = intField(root, "compactionMaxTotalResolutionCandidates", config.maxTotalResolutionCandidates, 1, 5_000_000);
            config.maxCrossTypeResolutionCandidates = intField(root, "compactionMaxCrossTypeResolutionCandidates", config.maxCrossTypeResolutionCandidates, 0, 1_000_000);
            config.maxNormalizedTitleFrequency = intField(root, "compactionMaxNormalizedTitleFrequency", config.maxNormalizedTitleFrequency, 1, 100_000);
            config.skipGenericSpreadsheetEntities = booleanField(root, "compactionSkipGenericSpreadsheetEntities", config.skipGenericSpreadsheetEntities);
            return config;
        }

        private static int intField(JsonNode root, String name, int fallback, int min, int max) {
            JsonNode node = root.get(name);
            if (node == null || !node.canConvertToInt()) {
                return fallback;
            }
            return Math.max(min, Math.min(max, node.asInt()));
        }

        private static boolean booleanField(JsonNode root, String name, boolean fallback) {
            JsonNode node = root.get(name);
            if (node == null || !node.isBoolean()) {
                return fallback;
            }
            return node.asBoolean();
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PUBLIC API
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Run graph compaction on all ENTITY nodes, merging similar entities.
     *
     * @param config resolution configuration
     * @return result summary with merge decisions and explainability
     */
    public CompactionResult compact(CompactionConfig config) {
        return compact(null, config);
    }

    /**
     * Run graph compaction on ENTITY nodes in a single fact sheet. A null
     * factSheetId preserves the legacy all-graph behavior for administrative tools.
     */
    public CompactionResult compact(Long factSheetId, CompactionConfig config) {
        refreshRuntimeConfig();
        long start = System.currentTimeMillis();
        embeddingMatchingDisabledForRun.set(false);
        nativeEmbeddingsTouchedForRun.set(false);
        try {
            List<GraphNode> entityNodes = loadEntityNodes(factSheetId);

            if (entityNodes.size() < 2) {
                notifyProgress(config, new CompactionProgress(
                        "COMPLETED",
                        entityNodes.size(),
                        Math.max(1, entityNodes.size()),
                        null,
                        0,
                        0,
                        "Compaction skipped: fewer than two entity nodes",
                        entityNodes.size(),
                        0,
                        System.currentTimeMillis() - start));
                return CompactionResult.empty();
            }

            log.info("Starting graph compaction on {} ENTITY nodes (factSheetId={}, threshold={})",
                    entityNodes.size(), factSheetId, config.similarityThreshold());

            // Phase 0: Entity type correction pre-pass (optional)
            if (config.entityTypeCorrection()) {
                correctEntityTypes(entityNodes);
            }

            // Phase 1: filter generic artifacts and block by narrow resolution keys.
            ResolutionInput resolutionInput = prepareResolutionInput(entityNodes, config);
            List<GraphNode> resolvableNodes = resolutionInput.nodes();
            Map<String, List<GraphNode>> blocks = blockByResolutionKey(resolvableNodes, config);

            notifyProgress(config, new CompactionProgress(
                    "LOADED",
                    0,
                    entityNodes.size(),
                    null,
                    0,
                    blocks.size(),
                    "Loaded " + entityNodes.size() + " entity nodes for compaction ("
                            + resolvableNodes.size() + " resolvable, "
                            + resolutionInput.skippedGenericArtifacts() + " generic artifact(s) skipped)",
                    resolutionInput.skippedGenericArtifacts(),
                    0,
                    System.currentTimeMillis() - start));

            if (resolvableNodes.size() < 2 || blocks.isEmpty()) {
                log.info("No resolvable entity pairs after filtering generic artifacts");
                notifyProgress(config, new CompactionProgress(
                        "COMPLETED",
                        entityNodes.size(),
                        entityNodes.size(),
                        null,
                        0,
                        Math.max(1, blocks.size()),
                        "Compaction complete: no resolvable entity pairs found",
                        entityNodes.size(),
                        0,
                        System.currentTimeMillis() - start));
                return CompactionResult.empty();
            }

            // Phase 2: Find candidate matches within each block
            List<MatchCandidate> allCandidates = new ArrayList<>();
            Set<String> candidatePairKeys = new HashSet<>();
            int blocksProcessed = 0;
            for (Map.Entry<String, List<GraphNode>> block : blocks.entrySet()) {
                checkInterrupted();
                notifyProgress(config, new CompactionProgress(
                        "BLOCK_STARTED",
                        blocksProcessed,
                        blocks.size(),
                        block.getKey(),
                        blocksProcessed + 1,
                        blocks.size(),
                        "Resolving " + block.getValue().size() + " " + block.getKey() + " candidate entities",
                        block.getValue().size(),
                        0,
                        System.currentTimeMillis() - start));
                List<MatchCandidate> blockCandidates = findCandidates(block.getValue(), config);
                int added = addCandidatesCapped(allCandidates, blockCandidates, candidatePairKeys, maxTotalResolutionCandidates);
                clearEmbeddingCache();
                trimNativeMemoryPools("graph compaction block " + block.getKey());
                blocksProcessed++;
                notifyProgress(config, new CompactionProgress(
                        "BLOCK_COMPLETED",
                        blocksProcessed,
                        blocks.size(),
                        block.getKey(),
                        blocksProcessed,
                        blocks.size(),
                        "Resolved " + block.getKey() + " block: " + added + " new candidate(s)",
                        block.getValue().size(),
                        added,
                        System.currentTimeMillis() - start));
                if (allCandidates.size() >= maxTotalResolutionCandidates) {
                    log.warn("Entity compaction candidate cap reached at {} candidate(s); remaining blocks skipped",
                            maxTotalResolutionCandidates);
                    notifyProgress(config, new CompactionProgress(
                            "CANDIDATE_CAP_REACHED",
                            blocksProcessed,
                            blocks.size(),
                            block.getKey(),
                            blocksProcessed,
                            blocks.size(),
                            "Candidate cap reached at " + maxTotalResolutionCandidates + " candidate(s)",
                            block.getValue().size(),
                            allCandidates.size(),
                            System.currentTimeMillis() - start));
                    break;
                }
            }

            // Phase 2b: Cross-type matching for compatible pairs (optional)
            if (config.crossTypeMerging() && blocks.size() > 1
                    && allCandidates.size() < maxTotalResolutionCandidates) {
                List<MatchCandidate> crossTypeCandidates = findCrossTypeCandidates(blocks, config);
                if (!crossTypeCandidates.isEmpty()) {
                    int added = addCandidatesCapped(allCandidates, crossTypeCandidates, candidatePairKeys,
                            maxTotalResolutionCandidates);
                    log.info("Found {} cross-type merge candidates ({} added after dedupe/cap)",
                            crossTypeCandidates.size(), added);
                }
            }

            if (allCandidates.isEmpty()) {
                log.info("No merge candidates found");
                notifyProgress(config, new CompactionProgress(
                        "COMPLETED",
                        blocks.size(),
                        Math.max(1, blocks.size()),
                        null,
                        blocks.size(),
                        blocks.size(),
                        "Compaction complete: no merge candidates found",
                        entityNodes.size(),
                        0,
                        System.currentTimeMillis() - start));
                return CompactionResult.empty();
            }

            log.info("Found {} merge candidates across {} type blocks",
                    allCandidates.size(), blocks.size());

            // Phase 3: Build connected components from match edges
            List<List<GraphNode>> components = findConnectedComponents(allCandidates, resolvableNodes);
            notifyProgress(config, new CompactionProgress(
                    "COMPONENTS_FOUND",
                    blocks.size(),
                    blocks.size(),
                    null,
                    blocks.size(),
                    blocks.size(),
                    "Found " + components.size() + " merge component(s)",
                    entityNodes.size(),
                    allCandidates.size(),
                    System.currentTimeMillis() - start));

            // Phase 4: Merge entities within each component
            List<MergeDecision> decisions = new ArrayList<>();
            int entitiesMerged = 0;
            int edgesRedirected = 0;
            int componentIndex = 0;
            int totalComponents = components.size();
            long lastMergeProgressMs = System.currentTimeMillis();

            for (List<GraphNode> component : components) {
                checkInterrupted();
                componentIndex++;
                if (component.size() < 2) continue;

                MergeDecision decision = mergeComponent(component, allCandidates, config);
                decisions.add(decision);
                entitiesMerged += decision.mergedNodeIds().size();
                edgesRedirected += decision.edgesRedirected();

                // Report merge progress periodically (every 10 components or every 15s)
                long now = System.currentTimeMillis();
                if (componentIndex % 10 == 0 || (now - lastMergeProgressMs) > 15_000 || componentIndex == totalComponents) {
                    lastMergeProgressMs = now;
                    notifyProgress(config, new CompactionProgress(
                            "MERGING",
                            componentIndex,
                            totalComponents,
                            null,
                            componentIndex,
                            totalComponents,
                            "Merging component " + componentIndex + "/" + totalComponents
                                    + " (" + entitiesMerged + " entities merged, "
                                    + edgesRedirected + " edges redirected)",
                            entityNodes.size(),
                            allCandidates.size(),
                            now - start));
                }
            }

            long elapsed = System.currentTimeMillis() - start;

            log.info("Graph compaction complete: {} components merged, {} entities removed, " +
                            "{} edges redirected ({}ms)",
                    decisions.size(), entitiesMerged, edgesRedirected, elapsed);
            notifyProgress(config, new CompactionProgress(
                    "COMPLETED",
                    entityNodes.size(),
                    entityNodes.size(),
                    null,
                    blocks.size(),
                    blocks.size(),
                    "Compaction complete: " + entitiesMerged + " entities merged, "
                            + edgesRedirected + " edges redirected",
                    entityNodes.size(),
                    allCandidates.size(),
                    elapsed));

            return new CompactionResult(
                    entityNodes.size(),
                    entityNodes.size() - entitiesMerged,
                    entitiesMerged,
                    edgesRedirected,
                    decisions.size(),
                    decisions,
                    elapsed
            );
        } finally {
            clearEmbeddingCache();
            if (Boolean.TRUE.equals(nativeEmbeddingsTouchedForRun.get())) {
                trimNativeMemoryPools("graph compaction");
            }
            embeddingMatchingDisabledForRun.remove();
            nativeEmbeddingsTouchedForRun.remove();
        }
    }

    /**
     * Merge two specific entity nodes. Elects a canonical (higher confidence,
     * longer description), redirects edges, merges metadata, and deletes the
     * non-canonical node.
     *
     * @param nodeIdA first entity node ID
     * @param nodeIdB second entity node ID
     * @param config  compaction config (deleteAfterMerge etc.)
     * @return result with exactly one merge decision
     */
    public CompactionResult mergePair(String nodeIdA, String nodeIdB, CompactionConfig config) {
        long start = System.currentTimeMillis();

        Optional<GraphNode> optA = knowledgeGraphService.getNode(nodeIdA);
        Optional<GraphNode> optB = knowledgeGraphService.getNode(nodeIdB);

        if (optA.isEmpty() || optB.isEmpty()) {
            throw new IllegalArgumentException("One or both nodes not found: " + nodeIdA + ", " + nodeIdB);
        }

        GraphNode a = optA.get();
        GraphNode b = optB.get();
        List<GraphNode> component = List.of(a, b);

        // Build a synthetic candidate for the assembly trace
        MatchCandidate syntheticCandidate = new MatchCandidate(
                nodeIdA, nodeIdB, a.getTitle(), b.getTitle(),
                extractEntityType(a), 1.0, List.of("MANUAL_MERGE"));

        MergeDecision decision = mergeComponent(component, List.of(syntheticCandidate), config);

        long elapsed = System.currentTimeMillis() - start;
        log.info("Manual merge: {} + {} -> {} ({}ms)",
                a.getTitle(), b.getTitle(), decision.canonicalTitle(), elapsed);

        return new CompactionResult(2, 1, 1,
                decision.edgesRedirected(), 1,
                List.of(decision), elapsed);
    }

    /**
     * Preview merge candidates without executing merges.
     * Returns the candidate pairs and their match reasons.
     */
    public List<MatchCandidate> previewCandidates(CompactionConfig config) {
        return previewCandidates(null, config);
    }

    /**
     * Preview merge candidates without executing merges within one fact sheet.
     * A null factSheetId preserves the legacy all-graph behavior.
     */
    public List<MatchCandidate> previewCandidates(Long factSheetId, CompactionConfig config) {
        refreshRuntimeConfig();
        List<GraphNode> entityNodes = loadEntityNodes(factSheetId);
        if (entityNodes.size() < 2) return List.of();

        try {
            ResolutionInput resolutionInput = prepareResolutionInput(entityNodes, config);
            Map<String, List<GraphNode>> blocks = blockByResolutionKey(resolutionInput.nodes(), config);
            List<MatchCandidate> allCandidates = new ArrayList<>();
            Set<String> candidatePairKeys = new HashSet<>();
            for (Map.Entry<String, List<GraphNode>> block : blocks.entrySet()) {
                addCandidatesCapped(allCandidates, findCandidates(block.getValue(), config),
                        candidatePairKeys, maxTotalResolutionCandidates);
                clearEmbeddingCache();
                trimNativeMemoryPools("graph compaction preview block " + block.getKey());
                if (allCandidates.size() >= maxTotalResolutionCandidates) {
                    break;
                }
            }
            if (config.crossTypeMerging() && blocks.size() > 1
                    && allCandidates.size() < maxTotalResolutionCandidates) {
                addCandidatesCapped(allCandidates, findCrossTypeCandidates(blocks, config),
                        candidatePairKeys, maxTotalResolutionCandidates);
            }
            return allCandidates;
        } finally {
            clearEmbeddingCache();
            trimNativeMemoryPools("graph compaction preview");
        }
    }

    private List<GraphNode> loadEntityNodes(Long factSheetId) {
        if (factSheetId != null && nodeRepository != null) {
            return nodeRepository.findByFactSheetIdAndNodeType(factSheetId, NodeLevel.ENTITY);
        }
        return knowledgeGraphService.searchNodes("", NodeLevel.ENTITY, 100_000);
    }

    /**
     * Explain why two specific entities would or would not merge.
     * Senzing-style "why" / "why not" explainability.
     */
    public MatchExplanation explain(String nodeIdA, String nodeIdB) {
        refreshRuntimeConfig();
        try {
        Optional<GraphNode> optA = knowledgeGraphService.getNode(nodeIdA);
        Optional<GraphNode> optB = knowledgeGraphService.getNode(nodeIdB);

        if (optA.isEmpty() || optB.isEmpty()) {
            return new MatchExplanation(nodeIdA, nodeIdB, false, 0.0,
                    List.of("One or both nodes not found"), List.of());
        }

        GraphNode a = optA.get();
        GraphNode b = optB.get();

        List<String> reasons = new ArrayList<>();
        List<String> blockers = new ArrayList<>();
        double score = 0.0;

        // Type check
        String typeA = extractEntityType(a);
        String typeB = extractEntityType(b);
        if (!typeA.equalsIgnoreCase(typeB)) {
            blockers.add("Different entity types: " + typeA + " vs " + typeB);
            return new MatchExplanation(nodeIdA, nodeIdB, false, 0.0, blockers, reasons);
        }
        reasons.add("Same entity type: " + typeA);

        // Normalized title comparison
        String normA = normalize(a.getTitle());
        String normB = normalize(b.getTitle());

        if (normA.equals(normB)) {
            score = 1.0;
            reasons.add("Exact normalized title match: \"" + normA + "\"");
        } else {
            double sim = levenshteinSimilarity(normA, normB);
            score = sim;
            if (sim >= DEFAULT_SIMILARITY_THRESHOLD) {
                reasons.add(String.format("Levenshtein similarity: %.3f (threshold: %.2f) " +
                        "— \"%s\" vs \"%s\"", sim, DEFAULT_SIMILARITY_THRESHOLD, normA, normB));
            } else {
                blockers.add(String.format("Levenshtein similarity too low: %.3f < %.2f " +
                        "— \"%s\" vs \"%s\"", sim, DEFAULT_SIMILARITY_THRESHOLD, normA, normB));
            }
        }

        // Alias check
        Set<String> aliasesA = extractAliases(a);
        Set<String> aliasesB = extractAliases(b);
        if (!aliasesA.isEmpty() || !aliasesB.isEmpty()) {
            if (aliasesA.contains(normB) || aliasesB.contains(normA)) {
                score = Math.max(score, 0.95);
                reasons.add("Name matches an alias of the other entity");
            }
            Set<String> overlap = new HashSet<>(aliasesA);
            overlap.retainAll(aliasesB);
            if (!overlap.isEmpty()) {
                score = Math.max(score, 0.9);
                reasons.add("Shared aliases: " + overlap);
            }
        }

        // Embedding-based semantic similarity
        if (embeddingModel != null && score < DEFAULT_SIMILARITY_THRESHOLD) {
            try {
                double cosineSim = computeEmbeddingSimilarity(a.getTitle(), b.getTitle());
                if (cosineSim >= DEFAULT_EMBEDDING_THRESHOLD) {
                    score = Math.max(score, cosineSim);
                    reasons.add(String.format("Embedding cosine similarity: %.3f (threshold: %.2f)",
                            cosineSim, DEFAULT_EMBEDDING_THRESHOLD));
                } else {
                    reasons.add(String.format("Embedding cosine similarity: %.3f (below %.2f threshold)",
                            cosineSim, DEFAULT_EMBEDDING_THRESHOLD));
                }
            } catch (Exception e) {
                reasons.add("Embedding comparison unavailable: " + e.getMessage());
            }
        }

        // Attribute-behavior scoring
        Map<String, String> propsA = extractProperties(a);
        Map<String, String> propsB = extractProperties(b);
        if (!propsA.isEmpty() && !propsB.isEmpty()) {
            List<String> attrReasons = scoreAttributes(propsA, propsB);
            if (!attrReasons.isEmpty()) {
                reasons.addAll(attrReasons);
                // Exclusive attribute match boosts score
                double attrScore = computeAttributeScore(propsA, propsB);
                if (attrScore > 0) {
                    score = Math.max(score, attrScore);
                }
            }
        }

        // Abbreviation / initial expansion
        if (score < DEFAULT_SIMILARITY_THRESHOLD) {
            double abbrScore = scoreAbbreviationMatch(normA, normB);
            if (abbrScore > 0) {
                score = Math.max(score, abbrScore);
                reasons.add("Abbreviation expansion match: \"" + normA + "\" ↔ \"" + normB + "\"");
            }
        }

        // Partial first-name match
        if (score < DEFAULT_SIMILARITY_THRESHOLD) {
            double partialScore = scorePartialNameMatch(normA, normB);
            if (partialScore > 0) {
                score = Math.max(score, partialScore);
                reasons.add("Partial first-name match: \"" + normA + "\" ↔ \"" + normB + "\"");
            }
        }

        // Cross-language match
        if (score < DEFAULT_SIMILARITY_THRESHOLD) {
            Set<String> crossAliasesA = extractAliases(a);
            Set<String> crossAliasesB = extractAliases(b);
            double crossLangScore = scoreCrossLanguageMatch(
                    a.getTitle(), crossAliasesA, b.getTitle(), crossAliasesB);
            if (crossLangScore > 0) {
                score = Math.max(score, crossLangScore);
                reasons.add("Cross-language match: \"" + a.getTitle() + "\" ↔ \"" + b.getTitle() + "\"");
            }
        }

        boolean wouldMerge = score >= DEFAULT_SIMILARITY_THRESHOLD && blockers.isEmpty();
        return new MatchExplanation(nodeIdA, nodeIdB, wouldMerge, score, blockers, reasons);
        } finally {
            clearEmbeddingCache();
            trimNativeMemoryPools("graph compaction explanation");
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // BLOCKING
    // ═══════════════════════════════════════════════════════════════════════════

    private ResolutionInput prepareResolutionInput(List<GraphNode> nodes) {
        return prepareResolutionInput(nodes, null);
    }

    private ResolutionInput prepareResolutionInput(List<GraphNode> nodes, CompactionConfig config) {
        Map<String, Long> normalizedTitleFrequency = nodes.stream()
                .map(node -> normalize(node.getTitle()))
                .filter(value -> !value.isBlank())
                .collect(Collectors.groupingBy(value -> value, LinkedHashMap::new, Collectors.counting()));

        Set<String> eligibleTypes = (config != null && config.crossTypeMerging())
                ? config.effectiveCrossTypeEligibleTypes() : Set.of();

        List<GraphNode> resolvable = new ArrayList<>(nodes.size());
        int skippedGenericArtifacts = 0;
        for (GraphNode node : nodes) {
            if (shouldSkipResolutionNode(node, normalizedTitleFrequency, eligibleTypes)) {
                skippedGenericArtifacts++;
            } else {
                resolvable.add(node);
            }
        }
        return new ResolutionInput(resolvable, skippedGenericArtifacts);
    }

    Map<String, List<GraphNode>> blockByResolutionKey(List<GraphNode> nodes) {
        return blockByResolutionKey(nodes, null);
    }

    Map<String, List<GraphNode>> blockByResolutionKey(List<GraphNode> nodes, CompactionConfig config) {
        Map<String, LinkedHashSet<GraphNode>> blockSets = new LinkedHashMap<>();
        for (GraphNode node : nodes) {
            String type = normalizeEntityType(extractEntityType(node));
            for (String signature : resolutionSignatures(node)) {
                String blockKey = type + "|" + signature;
                blockSets.computeIfAbsent(blockKey, k -> new LinkedHashSet<>()).add(node);
            }
        }

        Set<String> eligibleTypes = (config != null && config.crossTypeMerging())
                ? config.effectiveCrossTypeEligibleTypes() : Set.of();

        Map<String, List<GraphNode>> blocks = new LinkedHashMap<>();
        for (Map.Entry<String, LinkedHashSet<GraphNode>> entry : blockSets.entrySet()) {
            List<GraphNode> block = new ArrayList<>(entry.getValue());
            // When crossTypeMerging is enabled, keep singleton blocks whose type
            // participates in a compatible pair (built-in or custom) —
            // findCrossTypeCandidates needs them.
            if (block.size() < 2) {
                if (eligibleTypes.contains(blockType(entry.getKey()))) {
                    blocks.put(entry.getKey(), block);
                }
                continue;
            }
            if (block.size() > maxNodesPerResolutionBlock) {
                block = trimResolutionBlock(entry.getKey(), block);
            }
            if (block.size() >= 2) {
                blocks.put(entry.getKey(), block);
            }
        }
        return blocks;
    }

    private List<GraphNode> trimResolutionBlock(String blockKey, List<GraphNode> block) {
        int limit = Math.max(2, maxNodesPerResolutionBlock);
        log.warn("Resolution block {} has {} nodes; limiting to {} highest-signal nodes",
                blockKey, block.size(), limit);
        return block.stream()
                .sorted(Comparator
                        .<GraphNode, Integer>comparing(node -> hasExclusiveResolutionAttribute(node) ? 1 : 0)
                        .thenComparing(node -> node.getConfidence() != null ? node.getConfidence() : 0.0)
                        .thenComparing(node -> node.getEdgeCount() != null ? node.getEdgeCount() : 0)
                        .thenComparing(node -> node.getDescription() != null ? node.getDescription().length() : 0)
                        .reversed())
                .limit(limit)
                .toList();
    }

    private Set<String> resolutionSignatures(GraphNode node) {
        LinkedHashSet<String> signatures = new LinkedHashSet<>();
        Map<String, String> props = extractProperties(node);
        for (Map.Entry<String, String> prop : props.entrySet()) {
            AttributeBehavior behavior = ATTRIBUTE_BEHAVIORS.get(prop.getKey());
            String normalizedValue = normalizeAttributeValue(prop.getValue());
            if (behavior != null && behavior.exclusivity().contains("EXCLUSIVE")
                    && !normalizedValue.isBlank()) {
                signatures.add("attr:" + prop.getKey() + ":" + normalizedValue);
            }
        }

        addTitleSignature(signatures, normalize(node.getTitle()));
        for (String alias : extractAliases(node)) {
            addTitleSignature(signatures, alias);
        }
        return signatures;
    }

    private void addTitleSignature(Set<String> signatures, String normalizedTitle) {
        if (normalizedTitle == null || normalizedTitle.isBlank()) {
            return;
        }
        signatures.add("exact:" + normalizedTitle);
        String firstToken = normalizedTitle.split("\\s+")[0];
        if (firstToken.length() >= 4) {
            signatures.add("prefix:" + firstToken.substring(0, 4));
        } else if (!firstToken.isBlank()) {
            signatures.add("prefix:" + firstToken);
        }
    }

    private boolean shouldSkipResolutionNode(GraphNode node, Map<String, Long> normalizedTitleFrequency,
                                              Set<String> crossTypeEligibleTypes) {
        Map<String, Object> metadata = readMetadata(node);
        for (String key : EXPLICIT_RESOLUTION_SKIP_KEYS) {
            Object value = metadata.get(key);
            if (value instanceof Boolean bool && bool) {
                return true;
            }
            if (value instanceof String text && Boolean.parseBoolean(text)) {
                return true;
            }
        }

        String normalizedTitle = normalize(node.getTitle());
        if (normalizedTitle.isBlank()) {
            return true;
        }
        if (hasExclusiveResolutionAttribute(node)) {
            return false;
        }
        if (normalizedTitleFrequency.getOrDefault(normalizedTitle, 0L) > maxNormalizedTitleFrequency) {
            return true;
        }
        if (!skipGenericSpreadsheetEntities) {
            return false;
        }

        String title = node.getTitle() != null ? node.getTitle() : "";
        String entityType = normalizeEntityType(extractEntityType(node));

        // When crossTypeMerging is enabled, don't filter out entities whose type
        // participates in a compatible cross-type pair (built-in or custom).
        // They need to survive filtering so findCrossTypeCandidates can match them.
        if (crossTypeEligibleTypes.contains(entityType)) {
            return false;
        }

        if (GENERIC_SPREADSHEET_ENTITY_TYPES.contains(entityType)) {
            return true;
        }

        boolean spreadsheetCoordinate = hasSpreadsheetCoordinate(metadata) || CELL_REFERENCE_PATTERN.matcher(title).matches();
        if (spreadsheetCoordinate && (isGenericTitleValue(normalizedTitle) || NUMERIC_VALUE_PATTERN.matcher(normalizedTitle).matches())) {
            return true;
        }
        if ("CELL".equals(entityType) && spreadsheetCoordinate && normalizedTitle.length() <= 2) {
            return true;
        }
        return "CELL".equals(entityType)
                && spreadsheetCoordinate
                && normalizedTitleFrequency.getOrDefault(normalizedTitle, 0L) > Math.max(10, maxNormalizedTitleFrequency / 4);
    }

    private boolean hasSpreadsheetCoordinate(Map<String, Object> metadata) {
        for (String key : SPREADSHEET_COORDINATE_KEYS) {
            Object value = metadata.get(key);
            if (value != null && !String.valueOf(value).isBlank()) {
                return true;
            }
        }
        Object sourceType = metadata.get("sourceType");
        Object contentType = metadata.get("contentType");
        String source = ((sourceType != null ? sourceType : "") + " " + (contentType != null ? contentType : ""))
                .toLowerCase(Locale.ROOT);
        return source.contains("spreadsheet") || source.contains("excel") || source.contains("worksheet");
    }

    private boolean isGenericTitleValue(String normalizedTitle) {
        return GENERIC_TITLE_VALUES.contains(normalizedTitle)
                || normalizedTitle.length() <= 1
                || NUMERIC_VALUE_PATTERN.matcher(normalizedTitle).matches();
    }

    private boolean hasExclusiveResolutionAttribute(GraphNode node) {
        return extractProperties(node).entrySet().stream()
                .anyMatch(entry -> {
                    AttributeBehavior behavior = ATTRIBUTE_BEHAVIORS.get(entry.getKey());
                    return behavior != null
                            && behavior.exclusivity().contains("EXCLUSIVE")
                            && !normalizeAttributeValue(entry.getValue()).isBlank();
                });
    }

    private String normalizeAttributeValue(String value) {
        return value != null ? value.trim().toLowerCase(Locale.ROOT) : "";
    }

    private String normalizeEntityType(String type) {
        return type != null && !type.isBlank()
                ? type.trim().toUpperCase(Locale.ROOT)
                : "UNKNOWN";
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // CANDIDATE MATCHING
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Test-visible accessor for {@link #findCandidates(List, CompactionConfig)}.
     */
    public List<MatchCandidate> testFindCandidatesForBlock(List<GraphNode> block, CompactionConfig config) {
        return findCandidates(block, config);
    }

    private List<MatchCandidate> findCandidates(List<GraphNode> block, CompactionConfig config) {
        List<MatchCandidate> candidates = new ArrayList<>();
        CompactionConfig effectiveConfig = config;

        // For large blocks, pre-compute embeddings in sub-batches instead of
        // disabling embedding-assisted resolution entirely.
        if (config.useEmbeddings() && embeddingModel != null && block.size() > maxEmbeddingBlockSize
                && maxEmbeddingBlockSize > 0) {
            int batchSize = effectiveEmbeddingBatchSize(maxEmbeddingBlockSize);
            log.info("Large entity block ({} nodes, requestedMax={}, effectiveBatch={}): pre-computing embeddings in bounded sub-batches",
                    block.size(), maxEmbeddingBlockSize, batchSize);
            precomputeBlockEmbeddings(block, batchSize, config);
        }

        for (int i = 0; i < block.size(); i++) {
            checkInterrupted();
            GraphNode a = block.get(i);
            String normA = normalize(a.getTitle());
            Set<String> aliasesA = extractAliases(a);

            for (int j = i + 1; j < block.size(); j++) {
                checkInterrupted();
                GraphNode b = block.get(j);
                String normB = normalize(b.getTitle());

                MatchCandidate candidate = scorePair(a, normA, aliasesA, b, normB, effectiveConfig);
                if (candidate != null) {
                    candidates.add(candidate);
                    if (candidates.size() >= maxCandidatesPerResolutionBlock) {
                        log.warn("Resolution block candidate cap reached at {} candidate(s)",
                                maxCandidatesPerResolutionBlock);
                        return candidates;
                    }
                }
            }
        }
        return candidates;
    }

    private int addCandidatesCapped(List<MatchCandidate> target,
                                    List<MatchCandidate> candidates,
                                    Set<String> seenPairKeys,
                                    int maxCandidates) {
        int added = 0;
        int limit = Math.max(1, maxCandidates);
        for (MatchCandidate candidate : candidates) {
            if (target.size() >= limit) {
                break;
            }
            if (seenPairKeys.add(candidatePairKey(candidate))) {
                target.add(candidate);
                added++;
            }
        }
        return added;
    }

    private String candidatePairKey(MatchCandidate candidate) {
        String a = String.valueOf(candidate.nodeIdA());
        String b = String.valueOf(candidate.nodeIdB());
        return a.compareTo(b) <= 0 ? a + "|" + b : b + "|" + a;
    }

    /**
     * Find cross-type match candidates between compatible type blocks.
     * E.g. CELL entities matched against PERSON entities when titles overlap.
     */
    private List<MatchCandidate> findCrossTypeCandidates(
            Map<String, List<GraphNode>> blocks, CompactionConfig config) {
        List<MatchCandidate> candidates = new ArrayList<>();
        if (maxCrossTypeResolutionCandidates <= 0) {
            return candidates;
        }
        List<String> blockKeys = new ArrayList<>(blocks.keySet());

        for (int i = 0; i < blockKeys.size(); i++) {
            for (int j = i + 1; j < blockKeys.size(); j++) {
                String blockKeyA = blockKeys.get(i);
                String blockKeyB = blockKeys.get(j);
                String typeA = blockType(blockKeyA);
                String typeB = blockType(blockKeyB);
                if (!Objects.equals(blockSignature(blockKeyA), blockSignature(blockKeyB))) {
                    continue;
                }
                String pairKey = typeA + "|" + typeB;
                if (!config.effectiveCompatibleTypePairs().contains(pairKey)) continue;

                List<GraphNode> blockA = blocks.get(blockKeyA);
                List<GraphNode> blockB = blocks.get(blockKeyB);
                log.info("Cross-type matching: {} ({}) × {} ({})",
                        blockKeyA, blockA.size(), blockKeyB, blockB.size());

                for (GraphNode a : blockA) {
                    checkInterrupted();
                    String normA = normalize(a.getTitle());
                    Set<String> aliasesA = extractAliases(a);

                    for (GraphNode b : blockB) {
                        checkInterrupted();
                        String normB = normalize(b.getTitle());
                        MatchCandidate candidate = scorePair(a, normA, aliasesA, b, normB, config);
                        if (candidate != null) {
                            candidates.add(candidate);
                            if (candidates.size() >= maxCrossTypeResolutionCandidates) {
                                log.warn("Cross-type resolution candidate cap reached at {} candidate(s)",
                                        maxCrossTypeResolutionCandidates);
                                return candidates;
                            }
                        }
                    }
                }
            }
        }
        return candidates;
    }

    private String blockType(String blockKey) {
        int separator = blockKey.indexOf('|');
        return separator >= 0 ? blockKey.substring(0, separator) : blockKey;
    }

    private String blockSignature(String blockKey) {
        int separator = blockKey.indexOf('|');
        return separator >= 0 ? blockKey.substring(separator + 1) : "";
    }

    /**
     * Pre-pass: correct clearly mistyped entity types before blocking.
     * Detects email addresses, known software libraries, and workflow phases
     * that were incorrectly tagged as PERSON during extraction.
     */
    public void correctEntityTypes(List<GraphNode> nodes) {
        for (GraphNode node : nodes) {
            String title = node.getTitle();
            if (title == null || title.isBlank()) continue;

            String currentType = extractEntityType(node);
            String correctedType = detectCorrectType(title, currentType);

            if (correctedType != null && !correctedType.equalsIgnoreCase(currentType)) {
                log.info("Entity type correction: \"{}\" {} → {}", title, currentType, correctedType);
                try {
                    Map<String, Object> meta = new LinkedHashMap<>(readMetadata(node));
                    meta.put("entity_type", correctedType);
                    meta.put("original_entity_type", currentType);
                    meta.put("type_correction_reason", "auto-detected");
                    knowledgeGraphService.updateNode(node.getNodeId(), null, null, meta);
                    // Update the cached metadataJson so blockByType sees the corrected type
                    node.setMetadataJson(configObjectMapper.writeValueAsString(meta));
                } catch (Exception e) {
                    log.debug("Failed to correct entity type for {}: {}", node.getNodeId(), e.getMessage());
                }
            }
        }
    }

    /**
     * Detect if an entity title suggests a different type than what was assigned.
     * Returns the corrected type, or null if no correction needed.
     */
    public static String detectCorrectType(String title, String currentType) {
        if (title == null) return null;

        // Email addresses tagged as PERSON → EMAIL_ADDRESS
        if ("PERSON".equalsIgnoreCase(currentType) && EMAIL_TITLE_PATTERN.matcher(title.trim()).matches()) {
            return "EMAIL_ADDRESS";
        }

        // Known software library/tool patterns tagged as PERSON → TECHNOLOGY
        if ("PERSON".equalsIgnoreCase(currentType)) {
            String lower = title.trim().toLowerCase();
            // Camel-case or all-lowercase library names with version numbers
            if (lower.matches(".*\\d+\\.\\d+.*")) {
                return "TECHNOLOGY";
            }
            // Names that look like Python/Java packages (contain dots or underscores mid-word)
            if (lower.matches("[a-z][a-z0-9]*([._][a-z][a-z0-9]*)+")) {
                return "TECHNOLOGY";
            }
        }

        return null;
    }

    /**
     * Pre-compute embeddings for all entities in a block, processing in sub-batches
     * of the given size. Results are stored in the thread-local embedding cache
     * so that scorePair/computeEmbeddingSimilarity can find them without re-computing.
     */
    private void precomputeBlockEmbeddings(List<GraphNode> block, int subBatchSize, CompactionConfig config) {
        BoundedEmbeddingCache cache = embeddingCache.get();
        // Temporarily expand cache to hold the entire block
        int originalCapacity = cache.capacity();
        cache.resize(Math.max(originalCapacity, block.size()));

        // Collect unique titles that aren't already cached
        List<String> uncachedTitles = new ArrayList<>();
        for (GraphNode node : block) {
            String title = node.getTitle();
            if (title != null && !title.isBlank() && !cache.contains(title)) {
                uncachedTitles.add(title);
            }
        }

        if (uncachedTitles.isEmpty()) {
            log.info("All {} entity titles already cached", block.size());
            return;
        }

        int effectiveSubBatchSize = effectiveEmbeddingBatchSize(subBatchSize);
        log.info("Pre-computing embeddings for {} unique titles in sub-batches of {}",
                uncachedTitles.size(), effectiveSubBatchSize);
        notifyProgress(config, new CompactionProgress(
                "EMBEDDING_PRECOMPUTE_STARTED",
                0,
                uncachedTitles.size(),
                null,
                0,
                0,
                "Pre-computing " + uncachedTitles.size() + " entity embeddings in batches of "
                        + effectiveSubBatchSize,
                block.size(),
                0,
                0));

        int computed = 0;
        long start = System.currentTimeMillis();
        for (int i = 0; i < uncachedTitles.size(); i += effectiveSubBatchSize) {
            checkInterrupted();
            if (nativeMemoryTooHighForEmbeddings()) {
                String msg = String.format("Native memory pressure during pre-computation at %d/%d titles",
                        computed, uncachedTitles.size());
                log.error(msg);
                notifyProgress(config, new CompactionProgress(
                        "EMBEDDING_MEMORY_PRESSURE",
                        computed,
                        uncachedTitles.size(),
                        null,
                        0,
                        0,
                        msg,
                        block.size(),
                        0,
                        System.currentTimeMillis() - start));
                throw new RuntimeException(msg);
            }

            int end = Math.min(i + effectiveSubBatchSize, uncachedTitles.size());
            List<String> batch = uncachedTitles.subList(i, end);
            try {
                List<float[]> embeddings = embeddingModel.embedBatch(batch);
                if (embeddings != null && embeddings.size() == batch.size()) {
                    for (int j = 0; j < batch.size(); j++) {
                        float[] emb = embeddings.get(j);
                        if (emb != null && emb.length > 0) {
                            cache.put(batch.get(j), emb);
                            computed++;
                        }
                    }
                }
            } catch (Exception e) {
                String msg = String.format("Embedding sub-batch failed at %d/%d: %s",
                        computed, uncachedTitles.size(), e.getMessage());
                log.error(msg, e);
                notifyProgress(config, new CompactionProgress(
                        "EMBEDDING_BATCH_FAILED",
                        computed,
                        uncachedTitles.size(),
                        null,
                        0,
                        0,
                        msg,
                        block.size(),
                        0,
                        System.currentTimeMillis() - start));
                throw new RuntimeException(msg, e);
            }

            if (computed % 100 == 0 || computed == uncachedTitles.size()) {
                log.info("Pre-computed {}/{} entity embeddings", computed, uncachedTitles.size());
                notifyProgress(config, new CompactionProgress(
                        "EMBEDDING_PRECOMPUTE_PROGRESS",
                        computed,
                        uncachedTitles.size(),
                        null,
                        0,
                        0,
                        "Pre-computed " + computed + "/" + uncachedTitles.size() + " entity embeddings",
                        block.size(),
                        0,
                        System.currentTimeMillis() - start));
            }
        }

        log.info("Pre-computed {} entity embeddings for block of {} nodes (cache capacity: {})",
                computed, block.size(), cache.capacity());
        notifyProgress(config, new CompactionProgress(
                "EMBEDDING_PRECOMPUTE_COMPLETED",
                computed,
                uncachedTitles.size(),
                null,
                0,
                0,
                "Pre-computed " + computed + " entity embeddings for block of " + block.size() + " nodes",
                block.size(),
                0,
                System.currentTimeMillis() - start));
    }

    private void notifyProgress(CompactionConfig config, CompactionProgress progress) {
        if (config == null || config.progressListener() == null || progress == null) {
            return;
        }
        try {
            config.progressListener().onProgress(progress);
        } catch (Exception e) {
            log.debug("Compaction progress listener failed: {}", e.getMessage());
        }
    }

    private MatchCandidate scorePair(GraphNode a, String normA, Set<String> aliasesA,
                                      GraphNode b, String normB, CompactionConfig config) {
        List<String> reasons = new ArrayList<>();
        double score = 0.0;
        double threshold = config.similarityThreshold();

        // Signal 1: Exact normalized title match
        if (normA.equals(normB)) {
            score = 1.0;
            reasons.add("EXACT_TITLE_MATCH");
        }

        // Signal 2: Alias overlap
        if (score < 1.0) {
            Set<String> aliasesB = extractAliases(b);
            if (aliasesA.contains(normB)) {
                score = Math.max(score, 0.95);
                reasons.add("TITLE_IN_ALIAS");
            }
            if (aliasesB.contains(normA)) {
                score = Math.max(score, 0.95);
                reasons.add("TITLE_IN_ALIAS");
            }
            Set<String> overlap = new HashSet<>(aliasesA);
            overlap.retainAll(aliasesB);
            if (!overlap.isEmpty()) {
                score = Math.max(score, 0.9);
                reasons.add("SHARED_ALIASES:" + overlap.size());
            }
        }

        // Signal 3: Levenshtein similarity
        if (score < threshold) {
            double sim = levenshteinSimilarity(normA, normB);
            if (sim >= threshold) {
                score = Math.max(score, sim);
                reasons.add(String.format("LEVENSHTEIN:%.3f", sim));
            }
        }

        // Signal 4: Embedding-based cosine similarity (catches "IBM" vs "Big Blue")
        if (score < threshold && config.useEmbeddings() && embeddingModel != null
                && !Boolean.TRUE.equals(embeddingMatchingDisabledForRun.get())) {
            if (nativeMemoryTooHighForEmbeddings()) {
                disableEmbeddingMatchingForRun("native memory is above "
                        + embeddingNativeMemoryThresholdPercent + "% of JavaCPP max physical memory");
                clearEmbeddingCache();
                trimNativeMemoryPools("embedding resolution memory backpressure");
                reasons.add("EMBEDDING_DISABLED_MEMORY_PRESSURE");
            } else {
                try {
                    checkInterrupted();
                    double cosineSim = computeEmbeddingSimilarity(a.getTitle(), b.getTitle());
                    double embThreshold = config.embeddingThreshold();
                    if (cosineSim >= embThreshold) {
                        score = Math.max(score, cosineSim);
                        reasons.add(String.format("EMBEDDING_COSINE:%.3f", cosineSim));
                    }
                } catch (OutOfMemoryError oom) {
                    disableEmbeddingMatchingForRun("OutOfMemoryError during embedding comparison: "
                            + oom.getMessage());
                    clearEmbeddingCache();
                    trimNativeMemoryPools("embedding resolution OOM");
                    reasons.add("EMBEDDING_DISABLED_MEMORY_PRESSURE");
                } catch (Exception e) {
                    log.warn("Embedding similarity failed for {}/{}: {}", a.getNodeId(), b.getNodeId(), e.getMessage());
                }
            }
        }

        // Signal 5: Attribute-behavior scoring (email, URL, phone — Senzing-style)
        if (score < 1.0) {
            Map<String, String> propsA = extractProperties(a);
            Map<String, String> propsB = extractProperties(b);
            if (!propsA.isEmpty() && !propsB.isEmpty()) {
                double attrScore = computeAttributeScore(propsA, propsB);
                if (attrScore > 0) {
                    score = Math.max(score, attrScore);
                    List<String> attrReasons = scoreAttributes(propsA, propsB);
                    reasons.addAll(attrReasons);
                }
            }
        }

        // Signal 6: Abbreviation / initial expansion ("R. Thompson" ↔ "Robert Thompson")
        if (score < threshold) {
            double abbrScore = scoreAbbreviationMatch(normA, normB);
            if (abbrScore > 0) {
                score = Math.max(score, abbrScore);
                reasons.add("ABBREVIATION_EXPANSION");
            }
        }

        // Signal 7: Partial first-name match ("Sarah" ↔ "Sarah Chen") with shared context
        if (score < threshold) {
            double partialScore = scorePartialNameMatch(normA, normB);
            if (partialScore > 0) {
                score = Math.max(score, partialScore);
                reasons.add("PARTIAL_NAME_MATCH");
            }
        }

        // Signal 8: Cross-language alias match
        // Compares raw titles (pre-normalization) so that CJK/Cyrillic variants
        // extracted from parentheticals or mixed-script titles can be matched.
        if (score < threshold && config.crossLanguageResolution()) {
            double crossLangScore = scoreCrossLanguageMatch(a.getTitle(), aliasesA,
                    b.getTitle(), extractAliases(b));
            if (crossLangScore > 0) {
                score = Math.max(score, crossLangScore);
                reasons.add("CROSS_LANGUAGE_MATCH");
            }
        }

        if (score >= threshold) {
            return new MatchCandidate(
                    a.getNodeId(), b.getNodeId(),
                    a.getTitle(), b.getTitle(),
                    extractEntityType(a),
                    score, reasons
            );
        }
        return null;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // CONNECTED COMPONENTS
    // ═══════════════════════════════════════════════════════════════════════════

    private List<List<GraphNode>> findConnectedComponents(List<MatchCandidate> candidates,
                                                           List<GraphNode> allNodes) {
        // Build adjacency from match candidates
        Map<String, Set<String>> adjacency = new HashMap<>();
        for (MatchCandidate c : candidates) {
            adjacency.computeIfAbsent(c.nodeIdA(), k -> new HashSet<>()).add(c.nodeIdB());
            adjacency.computeIfAbsent(c.nodeIdB(), k -> new HashSet<>()).add(c.nodeIdA());
        }

        // Node lookup
        Map<String, GraphNode> nodeMap = new HashMap<>();
        for (GraphNode n : allNodes) {
            nodeMap.put(n.getNodeId(), n);
        }

        // BFS to find connected components
        Set<String> visited = new HashSet<>();
        List<List<GraphNode>> components = new ArrayList<>();

        for (String nodeId : adjacency.keySet()) {
            if (visited.contains(nodeId)) continue;

            List<GraphNode> component = new ArrayList<>();
            Queue<String> queue = new LinkedList<>();
            queue.add(nodeId);
            visited.add(nodeId);

            while (!queue.isEmpty()) {
                String current = queue.poll();
                GraphNode node = nodeMap.get(current);
                if (node != null) {
                    component.add(node);
                }
                for (String neighbor : adjacency.getOrDefault(current, Set.of())) {
                    if (visited.add(neighbor)) {
                        queue.add(neighbor);
                    }
                }
            }

            if (component.size() >= 2) {
                components.add(component);
            }
        }

        return components;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // MERGE
    // ═══════════════════════════════════════════════════════════════════════════

    private MergeDecision mergeComponent(List<GraphNode> component,
                                          List<MatchCandidate> allCandidates,
                                          CompactionConfig config) {
        // Elect canonical: highest confidence, then longest description, then first
        GraphNode canonical = electCanonical(component);
        List<String> mergedIds = new ArrayList<>();
        int edgesRedirected = 0;

        // Collect match reasons for explainability
        Set<String> componentNodeIds = component.stream()
                .map(GraphNode::getNodeId)
                .collect(Collectors.toSet());
        List<MatchCandidate> relevantCandidates = allCandidates.stream()
                .filter(c -> componentNodeIds.contains(c.nodeIdA()) && componentNodeIds.contains(c.nodeIdB()))
                .toList();

        // Build Senzing-style "How" step trace — records the assembly sequence
        List<String> assemblySteps = new ArrayList<>();
        assemblySteps.add(String.format("STEP 1: Elected canonical entity: \"%s\" [%s] " +
                        "(confidence=%.2f, edges=%d, descriptionLen=%d)",
                canonical.getTitle(), canonical.getNodeId(),
                canonical.getConfidence() != null ? canonical.getConfidence() : 0.0,
                canonical.getEdgeCount() != null ? canonical.getEdgeCount() : 0,
                canonical.getDescription() != null ? canonical.getDescription().length() : 0));

        int stepNum = 2;

        // Merge each non-canonical node into the canonical
        for (GraphNode node : component) {
            if (node.getNodeId().equals(canonical.getNodeId())) continue;

            // Find the match candidate that links this node
            MatchCandidate linkingCandidate = relevantCandidates.stream()
                    .filter(c -> c.nodeIdA().equals(node.getNodeId()) || c.nodeIdB().equals(node.getNodeId()))
                    .max(Comparator.comparingDouble(MatchCandidate::score))
                    .orElse(null);

            String matchInfo = linkingCandidate != null
                    ? String.format("score=%.3f, signals=%s", linkingCandidate.score(), linkingCandidate.reasons())
                    : "transitive link";

            // Redirect all edges from the merged node to the canonical
            int redirected = redirectEdges(node.getNodeId(), canonical.getNodeId());
            edgesRedirected += redirected;

            // Merge metadata: aliases, description
            mergeNodeMetadata(canonical, node);

            assemblySteps.add(String.format("STEP %d: Merged \"%s\" [%s] → canonical \"%s\" — %s, " +
                            "%d edges redirected",
                    stepNum++, node.getTitle(), node.getNodeId(),
                    canonical.getTitle(), matchInfo, redirected));

            // Delete the merged node
            if (config.deleteAfterMerge()) {
                try {
                    knowledgeGraphService.deleteNode(node.getNodeId());
                } catch (Exception e) {
                    log.warn("Failed to delete merged node {}: {}", node.getNodeId(), e.getMessage());
                }
            }

            mergedIds.add(node.getNodeId());
        }

        int duplicateEdgesCollapsed = deduplicateEdgesAround(canonical.getNodeId());
        edgesRedirected += duplicateEdgesCollapsed;
        if (duplicateEdgesCollapsed > 0) {
            assemblySteps.add(String.format("STEP %d: Collapsed %d duplicate relation edge(s) around canonical \"%s\"",
                    stepNum++, duplicateEdgesCollapsed, canonical.getTitle()));
        }

        assemblySteps.add(String.format("STEP %d: Final entity: \"%s\" [%s] with %d merged aliases",
                stepNum, canonical.getTitle(), canonical.getNodeId(), mergedIds.size()));

        return new MergeDecision(
                canonical.getNodeId(),
                canonical.getTitle(),
                mergedIds,
                edgesRedirected,
                relevantCandidates.stream().map(MatchCandidate::reasons).flatMap(List::stream).distinct().toList(),
                relevantCandidates.stream().mapToDouble(MatchCandidate::score).max().orElse(0.0),
                assemblySteps
        );
    }

    private GraphNode electCanonical(List<GraphNode> component) {
        return component.stream()
                .max(Comparator
                        .<GraphNode, Double>comparing(n -> n.getConfidence() != null ? n.getConfidence() : 0.0)
                        .thenComparing(n -> n.getDescription() != null ? n.getDescription().length() : 0)
                        .thenComparing(n -> n.getEdgeCount() != null ? n.getEdgeCount() : 0))
                .orElse(component.get(0));
    }

    private int redirectEdges(String fromNodeId, String toNodeId) {
        // Fetch all edges with eager-loaded sourceNode/targetNode (single query, no N+1)
        List<GraphEdge> edges = knowledgeGraphService.getEdgesForNode(fromNodeId);
        if (edges == null || edges.isEmpty()) return 0;

        int count = 0;
        List<String> edgeIdsToDelete = new ArrayList<>();

        // Phase 1: classify edges — which to delete, which to redirect
        record RedirectAction(GraphEdge edge, String newSource, String newTarget) {}
        List<RedirectAction> redirects = new ArrayList<>();

        for (GraphEdge edge : edges) {
            try {
                String sourceId = edge.getSourceNode().getNodeId();
                String targetId = edge.getTargetNode().getNodeId();

                String newSource = sourceId.equals(fromNodeId) ? toNodeId : sourceId;
                String newTarget = targetId.equals(fromNodeId) ? toNodeId : targetId;

                // Self-loop after redirect — just delete
                if (newSource.equals(newTarget)) {
                    edgeIdsToDelete.add(edge.getEdgeId());
                    continue;
                }

                // Edge already exists at destination — delete only an equivalent semantic duplicate.
                if (knowledgeGraphService.edgeExists(newSource, newTarget,
                        edge.getEdgeType(), firstNonBlank(edge.getLabel(), edge.getDescription()), edge.getFactSheetId())) {
                    edgeIdsToDelete.add(edge.getEdgeId());
                    continue;
                }

                redirects.add(new RedirectAction(edge, newSource, newTarget));
            } catch (Exception e) {
                log.debug("Failed to classify edge {}: {}", edge.getEdgeId(), e.getMessage());
            }
        }

        // Phase 2: create redirected edges
        for (RedirectAction action : redirects) {
            try {
                EdgeProvenance prov = action.edge.getProvenance() != null
                        ? EdgeProvenance.valueOf(action.edge.getProvenance()) : EdgeProvenance.EXTRACTED;
                knowledgeGraphService.createEdgeWithMetadata(action.newSource, action.newTarget,
                        action.edge.getEdgeType(), action.edge.getWeight(), action.edge.getLabel(),
                        action.edge.getDescription(), action.edge.getMetadataJson(),
                        prov, action.edge.getFactSheetId());
                edgeIdsToDelete.add(action.edge.getEdgeId());
                count++;
            } catch (Exception e) {
                log.debug("Failed to redirect edge {}: {}", action.edge.getEdgeId(), e.getMessage());
            }
        }

        // Phase 3: bulk delete all old edges (single batch query, no per-edge edgeCount updates)
        if (!edgeIdsToDelete.isEmpty()) {
            knowledgeGraphService.deleteEdgesBulk(edgeIdsToDelete);
        }

        return count;
    }

    private int deduplicateEdgesAround(String nodeId) {
        List<GraphEdge> edges = knowledgeGraphService.getEdgesForNode(nodeId);
        if (edges == null || edges.size() < 2) {
            return 0;
        }

        List<String> edgeIdsToDelete = new ArrayList<>();
        Map<String, List<GraphEdge>> groups = new LinkedHashMap<>();
        for (GraphEdge edge : edges) {
            String key = edgeDedupKey(edge);
            if (key == null) {
                continue;
            }
            groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(edge);
        }

        for (List<GraphEdge> group : groups.values()) {
            if (group.size() < 2) {
                continue;
            }
            GraphEdge keep = group.stream()
                    .max(Comparator
                            .<GraphEdge, Double>comparing(edge -> edge.getWeight() != null ? edge.getWeight() : 0.0)
                            .thenComparing(edge -> edge.getConfidence() != null ? edge.getConfidence() : 0.0)
                            .thenComparing(edge -> edge.getMetadataJson() != null && !edge.getMetadataJson().isBlank() ? 1 : 0)
                            .thenComparing(edge -> edge.getDescription() != null ? edge.getDescription().length() : 0))
                    .orElse(group.get(0));

            Double strongestWeight = group.stream()
                    .map(GraphEdge::getWeight)
                    .filter(Objects::nonNull)
                    .max(Double::compareTo)
                    .orElse(keep.getWeight());
            String bestDescription = group.stream()
                    .map(edge -> firstNonBlank(edge.getDescription(), edge.getLabel()))
                    .filter(Objects::nonNull)
                    .max(Comparator.comparingInt(String::length))
                    .orElse(keep.getDescription());
            try {
                knowledgeGraphService.updateEdge(keep.getEdgeId(), strongestWeight, bestDescription);
            } catch (Exception e) {
                log.debug("Failed to update canonical edge {} during deduplication: {}",
                        keep.getEdgeId(), e.getMessage());
            }

            for (GraphEdge edge : group) {
                if (!Objects.equals(edge.getEdgeId(), keep.getEdgeId())) {
                    edgeIdsToDelete.add(edge.getEdgeId());
                }
            }
        }

        if (!edgeIdsToDelete.isEmpty()) {
            knowledgeGraphService.deleteEdgesBulk(edgeIdsToDelete);
        }
        return edgeIdsToDelete.size();
    }

    private String edgeDedupKey(GraphEdge edge) {
        if (edge == null || edge.getSourceNode() == null || edge.getTargetNode() == null
                || edge.getSourceNode().getNodeId() == null || edge.getTargetNode().getNodeId() == null) {
            return null;
        }
        String sourceId = edge.getSourceNode().getNodeId();
        String targetId = edge.getTargetNode().getNodeId();
        if (sourceId.equals(targetId)) {
            return "self|" + sourceId + "|" + edge.getEdgeId();
        }
        if (Boolean.TRUE.equals(edge.getBidirectional()) && sourceId.compareTo(targetId) > 0) {
            String tmp = sourceId;
            sourceId = targetId;
            targetId = tmp;
        }
        String relationLabel = normalize(firstNonBlank(edge.getLabel(), edge.getDescription()));
        String canonicalEdgeType = canonicalEdgeTypeForDedup(edge.getEdgeType(), relationLabel);
        String canonicalRelationLabel = canonicalRelationLabelForDedup(edge.getEdgeType(), relationLabel);
        return String.valueOf(edge.getFactSheetId())
                + "|" + canonicalEdgeType
                + "|" + sourceId
                + "|" + targetId
                + "|" + canonicalRelationLabel
                + "|" + Boolean.TRUE.equals(edge.getBidirectional());
    }

    private String canonicalEdgeTypeForDedup(EdgeType edgeType, String relationLabel) {
        if (edgeType == EdgeType.CONTAINS || "contains".equals(relationLabel)) {
            return EdgeType.CONTAINS.name();
        }
        return edgeType != null ? edgeType.name() : "";
    }

    private String canonicalRelationLabelForDedup(EdgeType edgeType, String relationLabel) {
        if (edgeType == EdgeType.CONTAINS || "contains".equals(relationLabel)) {
            return "contains";
        }
        return relationLabel;
    }

    private void mergeNodeMetadata(GraphNode canonical, GraphNode merged) {
        try {
            // Merge description: keep longer
            String newDesc = canonical.getDescription();
            if (merged.getDescription() != null &&
                    (newDesc == null || merged.getDescription().length() > newDesc.length())) {
                newDesc = merged.getDescription();
            }

            Map<String, Object> metadata = new LinkedHashMap<>(readMetadata(canonical));
            Map<String, Object> mergedMetadata = readMetadata(merged);
            mergedMetadata.forEach(metadata::putIfAbsent);

            LinkedHashSet<String> aliases = new LinkedHashSet<>(extractAliases(canonical));
            aliases.addAll(extractAliases(merged));
            if (merged.getTitle() != null && !merged.getTitle().isBlank()) {
                aliases.add(normalize(merged.getTitle()));
            }
            if (!aliases.isEmpty()) {
                metadata.put("aliases", new ArrayList<>(aliases));
            }

            Map<String, Object> mergedFrom = new LinkedHashMap<>();
            if (metadata.get("merged_from") instanceof Map<?, ?> existing) {
                existing.forEach((key, value) -> {
                    if (key != null) {
                        mergedFrom.put(String.valueOf(key), value);
                    }
                });
            }
            mergedFrom.put(merged.getNodeId(), merged.getTitle());
            metadata.put("merged_from", mergedFrom);

            knowledgeGraphService.updateNode(canonical.getNodeId(), null, newDesc, metadata);
        } catch (Exception e) {
            log.debug("Failed to merge metadata: {}", e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // EMBEDDING SIMILARITY
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Compute cosine similarity between two entity titles using the text
     * embedding model. Results are cached per compaction run.
     */
    double computeEmbeddingSimilarity(String titleA, String titleB) {
        checkInterrupted();
        nativeEmbeddingsTouchedForRun.set(true);
        BoundedEmbeddingCache cache = embeddingCache.get();
        float[] embA = cache.get(titleA);
        float[] embB = cache.get(titleB);

        if (embA == null || embB == null) {
            List<String> missing = new ArrayList<>(2);
            if (embA == null && titleA != null && !titleA.isBlank()) {
                missing.add(titleA);
            }
            if (embB == null && titleB != null && !titleB.isBlank()
                    && (titleA == null || !titleB.equals(titleA))) {
                missing.add(titleB);
            }

            if (!missing.isEmpty()) {
                List<float[]> embeddings = embeddingModel.embedBatch(missing);
                if (embeddings == null || embeddings.size() != missing.size()) {
                    throw new IllegalStateException("Embedding model returned "
                            + (embeddings == null ? "null" : embeddings.size())
                            + " vectors for " + missing.size() + " titles");
                }
                for (int i = 0; i < missing.size(); i++) {
                    float[] emb = embeddings.get(i);
                    if (emb == null || emb.length == 0) {
                        throw new IllegalStateException("Embedding model returned empty vector for title: " + missing.get(i));
                    }
                    cache.put(missing.get(i), emb);
                }
            }

            embA = cache.get(titleA);
            embB = cache.get(titleB);
        }

        checkInterrupted();
        return cosineSimilarity(embA, embB);
    }

    private void checkInterrupted() {
        if (Thread.currentThread().isInterrupted()) {
            throw new CancellationException("Graph compaction interrupted");
        }
    }

    private void clearEmbeddingCache() {
        BoundedEmbeddingCache cache = embeddingCache.get();
        int closed = cache.closeAll();
        embeddingCache.remove();
        Pointer.deallocateReferences();
        if (closed > 0) {
            log.info("Closed {} cached entity-resolution embeddings", closed);
        }
    }

    private void trimNativeMemoryPools(String context) {
        int passes = Math.max(1, nativeMemoryCleanupPasses);
        for (int pass = 0; pass < passes; pass++) {
            try {
                Pointer.deallocateReferences();
            } catch (Throwable t) {
                log.debug("JavaCPP reference deallocation skipped after {}: {}", context, t.getMessage());
            }
            System.runFinalization();
            System.gc();
            try {
                Pointer.deallocateReferences();
            } catch (Throwable t) {
                log.debug("JavaCPP post-GC reference deallocation skipped after {}: {}", context, t.getMessage());
            }
        }
        log.debug("Released JavaCPP references after {} (passes={})", context, passes);
    }

    private int effectiveEmbeddingBatchSize(int requestedBatchSize) {
        if (embeddingModel == null) {
            return Math.max(1, requestedBatchSize > 0 ? requestedBatchSize : 64);
        }

        // When single DSP plan is enabled, the DSP plan batch size is authoritative.
        // Exceeding it causes plan recreation on every call (the "slow path" that
        // re-resolves all 621+ model constants). Going below it wastes padding but
        // is safe — the encoder pads up to the DSP shape automatically.
        try {
            if (embeddingModel.isSingleDspPlan()) {
                int dspBatch = embeddingModel.getDspPlanBatchSize();
                if (dspBatch > 0) {
                    log.info("Entity resolution embedding batch size: {} (from DSP plan config, singleDspPlan=true)",
                            dspBatch);
                    return dspBatch;
                }
            }
        } catch (Throwable t) {
            log.debug("Unable to read DSP plan config from embedding model: {}", t.getMessage());
        }

        // Fallback: cap at optimal/max from the model
        int batchSize = requestedBatchSize > 0 ? requestedBatchSize : 64;
        try {
            int optimal = embeddingModel.getOptimalBatchSize();
            if (optimal > 0) {
                batchSize = Math.min(batchSize, optimal);
            }
        } catch (Throwable t) {
            log.debug("Unable to read embedding optimal batch size: {}", t.getMessage());
        }
        try {
            int max = embeddingModel.getMaxBatchSize();
            if (max > 0) {
                batchSize = Math.min(batchSize, max);
            }
        } catch (Throwable t) {
            log.debug("Unable to read embedding max batch size: {}", t.getMessage());
        }
        log.info("Entity resolution embedding batch size: {} (from model optimal/max, singleDspPlan=false)",
                batchSize);
        return Math.max(1, batchSize);
    }

    private static double cosineSimilarity(float[] a, float[] b) {
        if (a == null || b == null || a.length == 0 || b.length == 0) {
            return 0.0;
        }
        int len = Math.min(a.length, b.length);
        double dot = 0.0;
        double normA = 0.0;
        double normB = 0.0;
        for (int i = 0; i < len; i++) {
            float av = a[i];
            float bv = b[i];
            if (!Float.isFinite(av) || !Float.isFinite(bv)) {
                return 0.0;
            }
            dot += (double) av * bv;
            normA += (double) av * av;
            normB += (double) bv * bv;
        }
        if (normA == 0.0 || normB == 0.0) {
            return 0.0;
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    private boolean nativeMemoryTooHighForEmbeddings() {
        if (embeddingNativeMemoryThresholdPercent <= 0) {
            return false;
        }
        try {
            long maxPhysicalBytes = Math.max(0L, Pointer.maxPhysicalBytes());
            if (maxPhysicalBytes <= 0) {
                return false;
            }
            long physicalBytes = Math.max(0L, Pointer.physicalBytes());
            double usagePercent = (physicalBytes * 100.0) / maxPhysicalBytes;
            return usagePercent >= embeddingNativeMemoryThresholdPercent;
        } catch (Throwable t) {
            log.debug("Unable to read JavaCPP native memory usage: {}", t.getMessage());
            return false;
        }
    }

    private void disableEmbeddingMatchingForRun(String reason) {
        if (!Boolean.TRUE.equals(embeddingMatchingDisabledForRun.get())) {
            embeddingMatchingDisabledForRun.set(true);
            log.warn("Embedding-assisted entity resolution disabled for this compaction run: {}", reason);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // ATTRIBUTE-BEHAVIOR SCORING (Senzing-style)
    // ═══════════════════════════════════════════════════════════════════════════

    @SuppressWarnings("unchecked")
    public Map<String, String> extractProperties(GraphNode node) {
        Map<String, Object> meta = readMetadata(node);
        if (meta.isEmpty()) return Map.of();

        Map<String, String> result = new LinkedHashMap<>();
        Object propsObj = meta.get("properties");
        if (propsObj instanceof Map<?, ?>) {
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) propsObj).entrySet()) {
                if (entry.getKey() instanceof String && entry.getValue() instanceof String value) {
                    result.put(((String) entry.getKey()).toLowerCase(Locale.ROOT), value);
                }
            }
        }
        for (String key : ATTRIBUTE_BEHAVIORS.keySet()) {
            Object value = meta.get(key);
            if (value != null) {
                result.putIfAbsent(key.toLowerCase(Locale.ROOT), String.valueOf(value));
            }
        }
        return result;
    }

    /**
     * Compute a weighted attribute match score. Exclusive attributes (email,
     * URL, ticker) that match are strong identity signals. Frequent attributes
     * (country, industry) contribute less weight.
     */
    public double computeAttributeScore(Map<String, String> propsA, Map<String, String> propsB) {
        double maxScore = 0.0;
        for (Map.Entry<String, String> entryA : propsA.entrySet()) {
            String key = entryA.getKey();
            String valB = propsB.get(key);
            if (valB != null && valB.equalsIgnoreCase(entryA.getValue())) {
                AttributeBehavior behavior = ATTRIBUTE_BEHAVIORS.get(key);
                double weight = behavior != null ? behavior.weight() : 0.40;
                maxScore = Math.max(maxScore, weight);
            }
        }
        return maxScore;
    }

    /**
     * Produce human-readable match reasons for attribute comparisons.
     */
    List<String> scoreAttributes(Map<String, String> propsA, Map<String, String> propsB) {
        List<String> reasons = new ArrayList<>();
        for (Map.Entry<String, String> entryA : propsA.entrySet()) {
            String key = entryA.getKey();
            String valB = propsB.get(key);
            if (valB == null) continue;

            AttributeBehavior behavior = ATTRIBUTE_BEHAVIORS.getOrDefault(key,
                    new AttributeBehavior(0.40, "UNKNOWN"));

            if (valB.equalsIgnoreCase(entryA.getValue())) {
                reasons.add(String.format("ATTR_MATCH:%s(%s)=\"%s\"",
                        key, behavior.exclusivity(), entryA.getValue()));
            }
        }
        return reasons;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // STRING UTILITIES
    // ═══════════════════════════════════════════════════════════════════════════

    public static String normalize(String name) {
        if (name == null) return "";
        String result = name.trim().toLowerCase();
        result = SUFFIX_PATTERN.matcher(result).replaceAll("").trim();
        // Strip CJK characters and interpunct separators (e.g. "李明·Li Ming" → "li ming")
        result = CJK_AND_INTERPUNCT_PATTERN.matcher(result).replaceAll("").trim();
        result = result.replaceAll("\\s+", " ");
        return result;
    }

    @SuppressWarnings("unchecked")
    private Set<String> extractAliases(GraphNode node) {
        Set<String> aliases = new HashSet<>();
        Map<String, Object> meta = readMetadata(node);
        Object aliasObj = meta.get("aliases");
        if (aliasObj instanceof List<?>) {
            for (Object a : (List<?>) aliasObj) {
                if (a instanceof String) {
                    aliases.add(normalize((String) a));
                }
            }
        } else if (aliasObj instanceof String aliasText) {
            for (String alias : aliasText.split("[,;|]")) {
                String normalized = normalize(alias);
                if (!normalized.isBlank()) {
                    aliases.add(normalized);
                }
            }
        }
        // Cross-language aliases from the title itself
        if (node.getTitle() != null) {
            aliases.addAll(extractCrossLanguageAliases(node.getTitle()));
        }
        return aliases;
    }

    /**
     * Extract cross-language aliases from a title that contains multiple script
     * variants of the same name.
     *
     * <p>Handles three patterns:
     * <ol>
     *   <li><b>Parenthetical</b>: "München (Munich)" → ["münchen", "munich"]</li>
     *   <li><b>Slash/pipe separator</b>: "Москва / Moscow" → ["москва", "moscow"]</li>
     *   <li><b>Mixed-script</b>: "田中太郎 Tanaka Taro" → ["田中太郎", "tanaka taro"]</li>
     * </ol>
     *
     * <p>Each extracted variant is normalized independently so that the CJK-only
     * form and the Latin-only form both appear as matchable aliases.
     */
    public static Set<String> extractCrossLanguageAliases(String title) {
        if (title == null || title.isBlank()) return Set.of();

        Set<String> aliases = new LinkedHashSet<>();

        // Pattern 1: Parenthetical — "München (Munich)" or "東京 (Tokyo)"
        java.util.regex.Matcher parenMatcher = PARENTHETICAL_PATTERN.matcher(title.trim());
        if (parenMatcher.matches()) {
            String outside = parenMatcher.group(1).trim();
            String inside = parenMatcher.group(2).trim();
            addNonBlank(aliases, outside.toLowerCase());
            addNonBlank(aliases, inside.toLowerCase());
            // Also normalize both (strips CJK/suffixes)
            addNonBlank(aliases, normalize(outside));
            addNonBlank(aliases, normalize(inside));
            return aliases;
        }

        // Pattern 2: Slash/pipe separator — "Москва / Moscow" or "München/Munich"
        if (LANG_SEPARATOR_PATTERN.matcher(title).find() && !title.contains("://")) {
            String[] parts = LANG_SEPARATOR_PATTERN.split(title);
            if (parts.length >= 2 && parts.length <= 4) {
                boolean hasMultipleScripts = false;
                for (int i = 1; i < parts.length; i++) {
                    if (detectScript(parts[0]) != detectScript(parts[i])) {
                        hasMultipleScripts = true;
                        break;
                    }
                }
                // Only treat as cross-language if different scripts OR both look like names
                if (hasMultipleScripts || parts.length == 2) {
                    for (String part : parts) {
                        String trimmed = part.trim();
                        addNonBlank(aliases, trimmed.toLowerCase());
                        addNonBlank(aliases, normalize(trimmed));
                    }
                    if (!aliases.isEmpty()) return aliases;
                }
            }
        }

        // Pattern 3: Mixed-script in a single string — "田中太郎 Tanaka Taro"
        boolean hasCjk = CJK_CHAR_PATTERN.matcher(title).find();
        boolean hasLatin = LATIN_CHAR_PATTERN.matcher(title).find();
        boolean hasCyrillic = CYRILLIC_CHAR_PATTERN.matcher(title).find();

        if ((hasCjk && hasLatin) || (hasCyrillic && hasLatin)) {
            // Split into script-homogeneous runs
            List<String> runs = splitByScript(title);
            for (String run : runs) {
                String trimmed = run.trim();
                if (!trimmed.isEmpty()) {
                    addNonBlank(aliases, trimmed.toLowerCase());
                    addNonBlank(aliases, normalize(trimmed));
                }
            }
        }

        return aliases;
    }

    /**
     * Detect the primary script of a string.
     */
    public static Script detectScript(String text) {
        if (text == null || text.isBlank()) return Script.UNKNOWN;
        if (CJK_CHAR_PATTERN.matcher(text).find()) return Script.CJK;
        if (CYRILLIC_CHAR_PATTERN.matcher(text).find()) return Script.CYRILLIC;
        if (LATIN_CHAR_PATTERN.matcher(text).find()) return Script.LATIN;
        return Script.UNKNOWN;
    }

    public enum Script { LATIN, CJK, CYRILLIC, UNKNOWN }

    /**
     * Split a mixed-script string into contiguous script runs.
     * "田中太郎 Tanaka Taro" → ["田中太郎", "Tanaka Taro"]
     * Interpuncts and whitespace between scripts are treated as boundaries.
     */
    public static List<String> splitByScript(String text) {
        List<String> runs = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        Script currentScript = Script.UNKNOWN;

        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            String s = String.valueOf(ch);
            Script charScript;
            if (CJK_CHAR_PATTERN.matcher(s).find()) {
                charScript = Script.CJK;
            } else if (CYRILLIC_CHAR_PATTERN.matcher(s).find()) {
                charScript = Script.CYRILLIC;
            } else if (LATIN_CHAR_PATTERN.matcher(s).find()) {
                charScript = Script.LATIN;
            } else {
                // whitespace, interpuncts, digits — append to current run
                current.append(ch);
                continue;
            }

            if (currentScript != Script.UNKNOWN && charScript != currentScript) {
                // Script boundary — flush current run
                String run = current.toString().trim();
                if (!run.isEmpty()) runs.add(run);
                current = new StringBuilder();
            }
            currentScript = charScript;
            current.append(ch);
        }
        String run = current.toString().trim();
        if (!run.isEmpty()) runs.add(run);
        return runs;
    }

    private static void addNonBlank(Set<String> set, String value) {
        if (value != null && !value.isBlank()) {
            set.add(value);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readMetadata(GraphNode node) {
        if (node == null || node.getMetadataJson() == null || node.getMetadataJson().isBlank()) {
            return Map.of();
        }
        try {
            return configObjectMapper.readValue(node.getMetadataJson(), Map.class);
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private String extractEntityType(GraphNode node) {
        Map<String, Object> meta = readMetadata(node);
        Object category = firstMetadataString(meta,
                "entity_category", "entityCategory",
                "custom_category", "customCategory",
                "resolution_category", "resolutionCategory");
        if (category instanceof String text && !text.isBlank()) {
            return text;
        }
        Object nestedCategory = firstNestedMetadataString(meta, "properties",
                "entity_category", "entityCategory",
                "custom_category", "customCategory",
                "resolution_category", "resolutionCategory");
        if (nestedCategory instanceof String text && !text.isBlank()) {
            return text;
        }
        Object type = firstMetadataString(meta, "entity_type", "entityType");
        if (type instanceof String text && !text.isBlank()) {
            return text;
        }
        Object subtype = firstMetadataString(meta, "entity_subtype", "entitySubtype");
        if (subtype instanceof String text && !text.isBlank()) {
            return entityTypeFromSubtype(text);
        }
        if (hasSpreadsheetCoordinate(meta)) {
            return "CELL";
        }
        return node.getNodeType() != null ? node.getNodeType().name() : "UNKNOWN";
    }

    private Object firstMetadataString(Map<String, Object> metadata, String... keys) {
        for (String key : keys) {
            Object value = metadata.get(key);
            if (value instanceof String text && !text.isBlank()) {
                return text;
            }
        }
        return null;
    }

    private Object firstNestedMetadataString(Map<String, Object> metadata, String parentKey, String... keys) {
        Object parent = metadata.get(parentKey);
        if (!(parent instanceof Map<?, ?> nested)) {
            return null;
        }
        for (String key : keys) {
            Object value = nested.get(key);
            if (value instanceof String text && !text.isBlank()) {
                return text;
            }
        }
        return null;
    }

    private String entityTypeFromSubtype(String subtype) {
        String normalizedSubtype = normalizeEntityType(subtype);
        return switch (normalizedSubtype) {
            case "CELL", "FORMULA_CELL", "CELL_COMMENT", "DATA_VALIDATION" -> normalizedSubtype;
            default -> normalizedSubtype;
        };
    }

    /**
     * Score abbreviation/initial expansion match.
     * "r. thompson" matches "robert thompson" because R matches Robert and last names match.
     * Also handles "j. p. morgan" matching "john paul morgan" (multiple initials).
     *
     * @return 0.90 if abbreviation match found, 0.0 otherwise
     */
    public static double scoreAbbreviationMatch(String normA, String normB) {
        return scoreAbbreviationOneSided(normA, normB) > 0
                ? 0.90
                : scoreAbbreviationOneSided(normB, normA);
    }

    private static double scoreAbbreviationOneSided(String abbreviated, String full) {
        // Check if one has an initial pattern like "r. thompson"
        java.util.regex.Matcher m = INITIAL_PATTERN.matcher(abbreviated);
        if (!m.find()) return 0.0;

        char initial = Character.toLowerCase(m.group(1).charAt(0));
        String abbrRemainder = abbreviated.substring(m.end()).trim();

        // The full name must have at least 2 parts
        String[] fullParts = full.split("\\s+");
        if (fullParts.length < 2) return 0.0;

        // Check: first letter of first word in full matches the initial
        if (Character.toLowerCase(fullParts[0].charAt(0)) != initial) return 0.0;

        // Check: remaining parts match
        String fullRemainder = String.join(" ", java.util.Arrays.copyOfRange(fullParts, 1, fullParts.length));
        if (abbrRemainder.equals(fullRemainder)) {
            return 0.90;
        }

        // Also check levenshtein on remainder for fuzzy match
        double sim = levenshteinSimilarity(abbrRemainder, fullRemainder);
        return sim >= 0.85 ? 0.90 : 0.0;
    }

    /**
     * Score partial first-name match.
     * "sarah" matches "sarah chen" — a single first name matching the first word of a full name.
     * Only triggers when the shorter name is a single word that exactly matches the first
     * word of the longer name, and the longer name has at least 2 words.
     *
     * @return 0.85 if partial match, 0.0 otherwise
     */
    public static double scorePartialNameMatch(String normA, String normB) {
        String shorter, longer;
        if (normA.length() < normB.length()) {
            shorter = normA;
            longer = normB;
        } else if (normB.length() < normA.length()) {
            shorter = normB;
            longer = normA;
        } else {
            return 0.0;
        }

        // Shorter must be a single word (first name only)
        if (shorter.contains(" ")) return 0.0;
        // Must be at least 2 chars to avoid matching initials
        if (shorter.length() < 2) return 0.0;

        String[] longerParts = longer.split("\\s+");
        if (longerParts.length < 2) return 0.0;

        // First word of longer must exactly match shorter
        if (longerParts[0].equals(shorter)) {
            return 0.85;
        }
        return 0.0;
    }

    /**
     * Score cross-language match between two entities.
     *
     * <p>Extracts cross-language aliases from raw (pre-normalized) titles and checks
     * for overlap. Handles:
     * <ul>
     *   <li>"München (Munich)" matching "Munich" — parenthetical extraction</li>
     *   <li>"東京" matching "Tokyo (東京)" — CJK form in alias</li>
     *   <li>"Москва / Moscow" matching "Moscow" — slash-separated variants</li>
     *   <li>"田中太郎 Tanaka Taro" matching "Tanaka Taro" — mixed-script split</li>
     * </ul>
     *
     * @return 0.92 if cross-language alias overlap found, 0.0 otherwise
     */
    public static double scoreCrossLanguageMatch(String rawTitleA, Set<String> aliasesA,
                                                  String rawTitleB, Set<String> aliasesB) {
        // Extract cross-language aliases from raw titles
        Set<String> crossAliasesA = extractCrossLanguageAliases(rawTitleA);
        Set<String> crossAliasesB = extractCrossLanguageAliases(rawTitleB);

        // Merge with existing aliases
        Set<String> allAliasesA = new HashSet<>(aliasesA);
        allAliasesA.addAll(crossAliasesA);

        Set<String> allAliasesB = new HashSet<>(aliasesB);
        allAliasesB.addAll(crossAliasesB);

        // Check: normalized title of A appears in cross-language aliases of B
        String normA = normalize(rawTitleA);
        String normB = normalize(rawTitleB);

        if (!normA.isBlank() && allAliasesB.contains(normA)) return 0.92;
        if (!normB.isBlank() && allAliasesA.contains(normB)) return 0.92;

        // Check: any cross-language alias of A matches any alias of B
        for (String crossAlias : crossAliasesA) {
            if (allAliasesB.contains(crossAlias)) return 0.92;
        }
        for (String crossAlias : crossAliasesB) {
            if (allAliasesA.contains(crossAlias)) return 0.92;
        }

        // Check: raw lowercased title of one matches a cross-language alias of the other
        // This catches "東京" matching a cross-alias extracted from "Tokyo (東京)"
        String rawLowerA = rawTitleA != null ? rawTitleA.trim().toLowerCase() : "";
        String rawLowerB = rawTitleB != null ? rawTitleB.trim().toLowerCase() : "";

        if (!rawLowerA.isBlank() && allAliasesB.contains(rawLowerA)) return 0.92;
        if (!rawLowerB.isBlank() && allAliasesA.contains(rawLowerB)) return 0.92;

        return 0.0;
    }

    public static double levenshteinSimilarity(String a, String b) {
        if (a.equals(b)) return 1.0;
        if (a.isEmpty() || b.isEmpty()) return 0.0;
        int maxLen = Math.max(a.length(), b.length());
        return 1.0 - (double) levenshteinDistance(a, b) / maxLen;
    }

    static int levenshteinDistance(String a, String b) {
        int lenA = a.length(), lenB = b.length();
        int[] prev = new int[lenB + 1];
        int[] curr = new int[lenB + 1];
        for (int j = 0; j <= lenB; j++) prev[j] = j;
        for (int i = 1; i <= lenA; i++) {
            curr[0] = i;
            for (int j = 1; j <= lenB; j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                curr[j] = Math.min(Math.min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev;
            prev = curr;
            curr = tmp;
        }
        return prev[lenB];
    }

    /**
     * Bounded per-run embedding cache backed by heap arrays. The embedding model
     * may execute in a native subprocess, but this cache intentionally avoids
     * INDArray so graph compaction does not allocate native vectors in app-main.
     */
    private static final class BoundedEmbeddingCache extends LinkedHashMap<String, float[]> {
        private int maxEntries;

        private BoundedEmbeddingCache(int maxEntries) {
            super(Math.max(16, maxEntries + 1), 0.75f, true);
            this.maxEntries = maxEntries;
        }

        private boolean isEnabled() {
            return maxEntries > 0;
        }

        private int capacity() {
            return maxEntries;
        }

        private void resize(int newMax) {
            this.maxEntries = Math.max(0, newMax);
        }

        private boolean contains(String key) {
            return containsKey(key);
        }

        private float[] getOrCompute(String key, Supplier<float[]> supplier) {
            if (!isEnabled()) {
                return supplier.get();
            }
            float[] existing = get(key);
            if (existing != null) {
                return existing;
            }
            float[] computed = supplier.get();
            put(key, computed);
            return computed;
        }

        private int closeAll() {
            int closed = size();
            clear();
            return closed;
        }

        @Override
        protected boolean removeEldestEntry(Map.Entry<String, float[]> eldest) {
            if (maxEntries > 0 && size() > maxEntries) {
                return true;
            }
            return false;
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // DATA RECORDS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Configuration for graph compaction.
     */
    public record CompactionConfig(
            double similarityThreshold,
            boolean deleteAfterMerge,
            boolean useEmbeddings,
            double embeddingThreshold,
            boolean crossTypeMerging,
            boolean entityTypeCorrection,
            boolean crossLanguageResolution,
            Set<String> customCompatibleTypePairs,
            Map<String, String> typeHierarchy,
            ProgressListener progressListener
    ) {
        /**
         * Built-in type hierarchy. Subtypes are automatically treated as compatible
         * with their parent type for cross-type merging.
         * Key = subtype, Value = parent type.
         */
        public static final Map<String, String> DEFAULT_TYPE_HIERARCHY = Map.of(
                "EMPLOYEE", "PERSON",
                "CONTACT", "PERSON",
                "STAKEHOLDER", "PERSON",
                "AUTHOR", "PERSON",
                "CONTRIBUTOR", "PERSON",
                "SUBSIDIARY", "ORGANIZATION",
                "DEPARTMENT", "ORGANIZATION",
                "DIVISION", "ORGANIZATION"
        );

        /**
         * Returns the effective type hierarchy (built-in merged with custom).
         */
        public Map<String, String> effectiveTypeHierarchy() {
            if (typeHierarchy == null || typeHierarchy.isEmpty()) {
                return DEFAULT_TYPE_HIERARCHY;
            }
            Map<String, String> merged = new HashMap<>(DEFAULT_TYPE_HIERARCHY);
            merged.putAll(typeHierarchy);
            return Map.copyOf(merged);
        }

        /**
         * Check whether childType is-a parentType in the hierarchy,
         * supporting multi-level chains (e.g. INTERN → EMPLOYEE → PERSON).
         */
        public boolean isSubtypeOf(String childType, String parentType) {
            if (childType == null || parentType == null) return false;
            Map<String, String> hierarchy = effectiveTypeHierarchy();
            String current = childType;
            int depth = 0;
            while (current != null && depth < 10) {
                String parent = hierarchy.get(current);
                if (parentType.equals(parent)) return true;
                current = parent;
                depth++;
            }
            return false;
        }

        /**
         * Returns the effective compatible type pairs for cross-type merging.
         * Merges: built-in pairs + custom explicit pairs + pairs derived from type hierarchy
         * (including transitive ancestors and sibling subtypes).
         */
        public Set<String> effectiveCompatibleTypePairs() {
            Set<String> merged = new HashSet<>(COMPATIBLE_TYPE_PAIRS);
            if (customCompatibleTypePairs != null) {
                merged.addAll(customCompatibleTypePairs);
            }
            Map<String, String> hierarchy = effectiveTypeHierarchy();

            // For each type, walk up to all ancestors and generate pairs.
            // E.g. INTERN → EMPLOYEE → PERSON generates:
            //   INTERN|EMPLOYEE, INTERN|PERSON, EMPLOYEE|PERSON (and reverses)
            for (String type : hierarchy.keySet()) {
                List<String> ancestors = new ArrayList<>();
                String current = type;
                int depth = 0;
                while (current != null && depth < 10) {
                    String parent = hierarchy.get(current);
                    if (parent != null) {
                        ancestors.add(parent);
                    }
                    current = parent;
                    depth++;
                }
                for (String ancestor : ancestors) {
                    merged.add(type + "|" + ancestor);
                    merged.add(ancestor + "|" + type);
                }
            }

            // Generate pairs between sibling subtypes that share any ancestor.
            // Collect all types that resolve to the same root.
            Map<String, Set<String>> rootToDescendants = new HashMap<>();
            for (String type : hierarchy.keySet()) {
                // Find root ancestor
                String root = type;
                String parent = hierarchy.get(root);
                int depth = 0;
                while (parent != null && depth < 10) {
                    root = parent;
                    parent = hierarchy.get(root);
                    depth++;
                }
                rootToDescendants.computeIfAbsent(root, k -> new HashSet<>()).add(type);
            }
            for (Set<String> descendants : rootToDescendants.values()) {
                List<String> list = new ArrayList<>(descendants);
                for (int i = 0; i < list.size(); i++) {
                    for (int j = i + 1; j < list.size(); j++) {
                        merged.add(list.get(i) + "|" + list.get(j));
                        merged.add(list.get(j) + "|" + list.get(i));
                    }
                }
            }
            return Set.copyOf(merged);
        }

        /**
         * Returns all entity types that participate in any compatible pair
         * (built-in + custom + hierarchy-derived). Used to exempt these types
         * from generic filtering.
         */
        public Set<String> effectiveCrossTypeEligibleTypes() {
            Set<String> pairs = effectiveCompatibleTypePairs();
            Set<String> types = new HashSet<>();
            for (String pair : pairs) {
                String[] parts = pair.split("\\|");
                types.add(parts[0]);
                if (parts.length > 1) types.add(parts[1]);
            }
            return Set.copyOf(types);
        }

        public CompactionConfig(double similarityThreshold, boolean deleteAfterMerge,
                                boolean useEmbeddings, double embeddingThreshold,
                                boolean crossTypeMerging, boolean entityTypeCorrection,
                                boolean crossLanguageResolution,
                                Set<String> customCompatibleTypePairs,
                                ProgressListener progressListener) {
            this(similarityThreshold, deleteAfterMerge, useEmbeddings, embeddingThreshold,
                    crossTypeMerging, entityTypeCorrection, crossLanguageResolution,
                    customCompatibleTypePairs, null, progressListener);
        }

        public CompactionConfig(double similarityThreshold, boolean deleteAfterMerge,
                                boolean useEmbeddings, double embeddingThreshold,
                                boolean crossTypeMerging, boolean entityTypeCorrection,
                                boolean crossLanguageResolution,
                                ProgressListener progressListener) {
            this(similarityThreshold, deleteAfterMerge, useEmbeddings, embeddingThreshold,
                    crossTypeMerging, entityTypeCorrection, crossLanguageResolution,
                    null, null, progressListener);
        }

        public CompactionConfig(double similarityThreshold, boolean deleteAfterMerge,
                                boolean useEmbeddings, double embeddingThreshold,
                                boolean crossTypeMerging, boolean entityTypeCorrection,
                                ProgressListener progressListener) {
            this(similarityThreshold, deleteAfterMerge, useEmbeddings, embeddingThreshold,
                    crossTypeMerging, entityTypeCorrection, false, null, null, progressListener);
        }

        public CompactionConfig(double similarityThreshold, boolean deleteAfterMerge,
                                boolean useEmbeddings, double embeddingThreshold,
                                ProgressListener progressListener) {
            this(similarityThreshold, deleteAfterMerge, useEmbeddings, embeddingThreshold,
                    false, false, false, null, null, progressListener);
        }

        public CompactionConfig(double similarityThreshold, boolean deleteAfterMerge,
                                boolean useEmbeddings, double embeddingThreshold) {
            this(similarityThreshold, deleteAfterMerge, useEmbeddings, embeddingThreshold, null);
        }

        public CompactionConfig(double similarityThreshold, boolean deleteAfterMerge) {
            this(similarityThreshold, deleteAfterMerge, true, DEFAULT_EMBEDDING_THRESHOLD);
        }

        public CompactionConfig() {
            this(DEFAULT_SIMILARITY_THRESHOLD, true, true, DEFAULT_EMBEDDING_THRESHOLD);
        }

        public static CompactionConfig withThreshold(double threshold) {
            return new CompactionConfig(threshold, true, true, DEFAULT_EMBEDDING_THRESHOLD);
        }

        public static CompactionConfig previewOnly(double threshold) {
            return new CompactionConfig(threshold, false, true, DEFAULT_EMBEDDING_THRESHOLD);
        }

        public static CompactionConfig withEmbeddings(double threshold, double embeddingThreshold) {
            return new CompactionConfig(threshold, true, true, embeddingThreshold);
        }

        public static CompactionConfig withoutEmbeddings(double threshold) {
            return new CompactionConfig(threshold, true, false, 0);
        }

        public static CompactionConfig withCrossTypeMerging(double threshold) {
            return new CompactionConfig(threshold, true, true, DEFAULT_EMBEDDING_THRESHOLD,
                    true, false, false, null, null, null);
        }

        public static CompactionConfig withCrossTypeMerging(double threshold, Set<String> customPairs) {
            return new CompactionConfig(threshold, true, true, DEFAULT_EMBEDDING_THRESHOLD,
                    true, false, false, customPairs, null, null);
        }

        public static CompactionConfig withCrossTypeMerging(double threshold,
                                                             Set<String> customPairs,
                                                             Map<String, String> typeHierarchy) {
            return new CompactionConfig(threshold, true, true, DEFAULT_EMBEDDING_THRESHOLD,
                    true, false, false, customPairs, typeHierarchy, null);
        }

        public static CompactionConfig withTypeHierarchy(double threshold,
                                                          Map<String, String> typeHierarchy) {
            return new CompactionConfig(threshold, true, true, DEFAULT_EMBEDDING_THRESHOLD,
                    true, false, false, null, typeHierarchy, null);
        }

        public static CompactionConfig withEntityTypeCorrection(double threshold) {
            return new CompactionConfig(threshold, true, true, DEFAULT_EMBEDDING_THRESHOLD,
                    false, true, false, null, null, null);
        }

        public static CompactionConfig withCrossLanguageResolution(double threshold) {
            return new CompactionConfig(threshold, true, false, DEFAULT_EMBEDDING_THRESHOLD,
                    false, false, true, null, null, null);
        }

        public static CompactionConfig withAllFeatures(double threshold) {
            return new CompactionConfig(threshold, true, true, DEFAULT_EMBEDDING_THRESHOLD,
                    true, true, true, null, null, null);
        }

        public static CompactionConfig withAllFeatures(double threshold, Set<String> customPairs) {
            return new CompactionConfig(threshold, true, true, DEFAULT_EMBEDDING_THRESHOLD,
                    true, true, true, customPairs, null, null);
        }

        public static CompactionConfig withAllFeatures(double threshold,
                                                        Set<String> customPairs,
                                                        Map<String, String> typeHierarchy) {
            return new CompactionConfig(threshold, true, true, DEFAULT_EMBEDDING_THRESHOLD,
                    true, true, true, customPairs, typeHierarchy, null);
        }
    }

    public interface ProgressListener {
        void onProgress(CompactionProgress progress);
    }

    public record CompactionProgress(
            String stage,
            int processed,
            int total,
            String blockType,
            int blockIndex,
            int blockCount,
            String message,
            int blockSize,
            int candidates,
            long elapsedMs
    ) {}

    record ResolutionInput(
            List<GraphNode> nodes,
            int skippedGenericArtifacts
    ) {}

    /**
     * Result of a compaction run.
     */
    public record CompactionResult(
            int originalEntityCount,
            int finalEntityCount,
            int entitiesMerged,
            int edgesRedirected,
            int componentsFound,
            List<MergeDecision> decisions,
            long elapsedMs
    ) {
        public static CompactionResult empty() {
            return new CompactionResult(0, 0, 0, 0, 0, List.of(), 0);
        }
    }

    /**
     * A candidate match between two entities.
     */
    public record MatchCandidate(
            String nodeIdA,
            String nodeIdB,
            String titleA,
            String titleB,
            String entityType,
            double score,
            List<String> reasons
    ) {
    }

    /**
     * Explains why two entities would or would not merge.
     * Senzing-style "why" / "why not".
     */
    public record MatchExplanation(
            String nodeIdA,
            String nodeIdB,
            boolean wouldMerge,
            double score,
            List<String> blockers,
            List<String> matchReasons
    ) {
    }

    /**
     * A merge decision for a connected component. Includes Senzing-style
     * "How" step-by-step assembly trace showing the sequence of merges.
     */
    public record MergeDecision(
            String canonicalNodeId,
            String canonicalTitle,
            List<String> mergedNodeIds,
            int edgesRedirected,
            List<String> matchReasons,
            double highestScore,
            List<String> assemblySteps
    ) {
        /** Backwards-compatible constructor without assemblySteps. */
        public MergeDecision(String canonicalNodeId, String canonicalTitle,
                             List<String> mergedNodeIds, int edgesRedirected,
                             List<String> matchReasons, double highestScore) {
            this(canonicalNodeId, canonicalTitle, mergedNodeIds, edgesRedirected,
                    matchReasons, highestScore, List.of());
        }
    }

    /**
     * Senzing-style attribute behavior: maps an attribute type to its
     * frequency/exclusivity weight for scoring.
     */
    public record AttributeBehavior(
            double weight,
            String exclusivity
    ) {
    }
}
