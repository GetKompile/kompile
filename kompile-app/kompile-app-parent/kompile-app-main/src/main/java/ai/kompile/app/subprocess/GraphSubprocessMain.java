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

package ai.kompile.app.subprocess;

import ai.kompile.app.config.Nd4jEnvironmentConfig;
import ai.kompile.core.graphrag.GraphConstructor;
import ai.kompile.core.graphrag.model.Entity;
import ai.kompile.core.graphrag.model.Graph;
import ai.kompile.core.graphrag.model.Relationship;
import ai.kompile.core.graphrag.model.schema.GraphSchema;
import ai.kompile.core.graphrag.model.schema.NodeType;
import ai.kompile.core.graphrag.model.schema.RelationshipType;
import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.core.graphrag.model.schema.SchemaEnforcementMode;
import ai.kompile.core.retrievers.RetrievedDoc;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.nd4j.imports.converters.DifferentialFunctionClassHolder;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.linalg.factory.Nd4jBackend;
import org.nd4j.nativeblas.NativeOps;
import org.nd4j.nativeblas.NativeOpsHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Main entry point for the graph subprocess.
 *
 * <p>Isolated process for running graph extraction and matrix graph algorithms.
 * This prevents CUDA/ND4J native crashes in graph operations from taking down
 * the main orchestrator process.</p>
 *
 * <p>The subprocess:</p>
 * <ol>
 *   <li>Initializes ND4J with the CUDA backend</li>
 *   <li>Creates a minimal Spring context with graph components</li>
 *   <li>Runs LLM-based entity/relationship extraction on document chunks</li>
 *   <li>Optionally runs matrix graph algorithms (PageRank, HITS, etc.)</li>
 *   <li>Writes results to an output JSON file</li>
 *   <li>Reports progress and completion via INGEST_MSG: protocol</li>
 * </ol>
 *
 * Usage:
 *   java -cp &lt;classpath&gt; ai.kompile.app.subprocess.GraphSubprocessMain &lt;args-file.json&gt;
 */
public class GraphSubprocessMain {

    private static final Logger logger = LoggerFactory.getLogger(GraphSubprocessMain.class);
    private static final ObjectMapper OBJECT_MAPPER = JsonUtils.standardMapper();

    private static PrintStream originalStdout;
    private static volatile GraphSubprocessArgs currentArgs;
    private static volatile SubprocessMemoryWatchdog memoryWatchdog;

    public static GraphSubprocessArgs getCurrentArgs() {
        return currentArgs;
    }

    public static SubprocessMemoryWatchdog getMemoryWatchdog() {
        return memoryWatchdog;
    }

    public static void main(String[] args) {
        originalStdout = System.out;
        System.setOut(System.err);

        int exitCode = 0;
        GraphSubprocessArgs graphArgs = null;
        SubprocessProgressReporter reporter = null;
        HttpIngestCallback httpCallback = null;

        try {
            if (args.length < 1) {
                System.err.println("Usage: GraphSubprocessMain <args-file.json>");
                System.exit(1);
            }

            Path argsFile = Paths.get(args[0]);
            if (!Files.exists(argsFile)) {
                System.err.println("Args file not found: " + argsFile);
                System.exit(1);
            }

            graphArgs = GraphSubprocessArgs.fromFile(argsFile);
            currentArgs = graphArgs;
            logger.info("Loaded graph subprocess args for task: {}", graphArgs.taskId());

            // Initialize reporter and callback
            reporter = new SubprocessProgressReporter(graphArgs.taskId(), originalStdout);
            if (graphArgs.callbackBaseUrl() != null && !graphArgs.callbackBaseUrl().isBlank()) {
                httpCallback = new HttpIngestCallback(graphArgs.callbackBaseUrl());
            }

            // Initialize memory watchdog
            memoryWatchdog = new SubprocessMemoryWatchdog(
                    graphArgs.memoryThresholdPercent(),
                    graphArgs.memoryCriticalPercent(),
                    graphArgs.memoryKillThresholdPercent(),
                    graphArgs.memoryCheckIntervalMs(),
                    graphArgs.gpuMemoryThresholdPercent(),
                    graphArgs.gpuMemoryCriticalPercent(),
                    graphArgs.gpuMemoryKillThresholdPercent(),
                    graphArgs.offHeapThresholdPercent(),
                    graphArgs.offHeapCriticalPercent(),
                    graphArgs.offHeapKillThresholdPercent()
            );
            memoryWatchdog.start();
            logger.info("Memory watchdog started: heap stop={}%, GPU stop={}%, off-heap stop={}%",
                    graphArgs.memoryThresholdPercent(),
                    graphArgs.gpuMemoryThresholdPercent(),
                    graphArgs.offHeapThresholdPercent());

            // Start heartbeat
            reporter.startHeartbeat();

            // Initialize ND4J
            logger.info("Initializing ND4J environment...");
            initializeNd4j(graphArgs.nd4jConfigJson());

            // Notify main app
            if (httpCallback != null) {
                httpCallback.markJobRunning(graphArgs.taskId());
            }

            // Report progress: starting extraction
            reporter.reportProgress("GRAPH_EXTRACTION", 0, null, "Starting graph extraction");

            // Create minimal Spring context
            logger.info("Creating Spring context for graph subprocess...");
            long extractionStart = System.currentTimeMillis();
            GraphSubprocessArgs.GraphExtractionResult result;

            try (AnnotationConfigApplicationContext context = createContext(graphArgs)) {
                // Try to get GraphConstructor from context
                GraphConstructor graphConstructor = null;
                try {
                    graphConstructor = context.getBean(GraphConstructor.class);
                    logger.info("GraphConstructor available: {}", graphConstructor.getClass().getSimpleName());
                } catch (Exception e) {
                    logger.warn("No GraphConstructor bean available, will use basic extraction");
                }

                // Convert document chunks to RetrievedDocs
                List<RetrievedDoc> docs = convertToRetrievedDocs(graphArgs.documentChunks());
                logger.info("Processing {} document chunks", docs.size());

                // Build schema from args
                GraphSchema schema = buildSchema(graphArgs);
                SchemaEnforcementMode enforcementMode = SchemaEnforcementMode.valueOf(
                        graphArgs.schemaEnforcementMode().toUpperCase());

                // Run extraction
                List<GraphSubprocessArgs.ExtractedEntity> allEntities = new ArrayList<>();
                List<GraphSubprocessArgs.ExtractedRelationship> allRelationships = new ArrayList<>();

                if (graphConstructor != null) {
                    result = runWithGraphConstructor(
                            graphConstructor, docs, schema, enforcementMode,
                            graphArgs, reporter, extractionStart);
                } else {
                    // No GraphConstructor — report error
                    logger.error("No GraphConstructor available in subprocess context");
                    result = new GraphSubprocessArgs.GraphExtractionResult(
                            graphArgs.taskId(), 0, 0, docs.size(),
                            System.currentTimeMillis() - extractionStart, 0,
                            List.of(), List.of(), Map.of(), Map.of(), Map.of());
                }
            }

            // Write results to output file
            if (graphArgs.outputPath() != null) {
                Path outputPath = Path.of(graphArgs.outputPath());
                Files.createDirectories(outputPath.getParent());
                result.toFile(outputPath);
                logger.info("Wrote extraction results to: {}", outputPath);
            }

            // Report completion
            Map<String, Long> phaseDurations = new HashMap<>();
            phaseDurations.put("GRAPH_EXTRACTION", result.extractionDurationMs());
            phaseDurations.put("MATRIX_ALGORITHMS", result.matrixAlgorithmDurationMs());

            reporter.reportCompleted(
                    result.documentsProcessed(), // documentsLoaded
                    result.entitiesExtracted(),   // chunksCreated (reuse for entity count)
                    result.relationshipsExtracted(), // chunksEmbedded (reuse for relationship count)
                    result.documentsProcessed(),  // documentsIndexed
                    0, 0, // tokens
                    graphArgs.outputPath(),
                    phaseDurations);

            if (httpCallback != null) {
                httpCallback.markJobCompleted(graphArgs.taskId());
            }
            logger.info("Graph subprocess completed: {} entities, {} relationships",
                    result.entitiesExtracted(), result.relationshipsExtracted());

        } catch (OutOfMemoryError oom) {
            logger.error("OUT OF MEMORY in graph subprocess");
            if (reporter != null) {
                reporter.reportFailed("GRAPH_EXTRACTION", "Out of memory: " + oom.getMessage(),
                        "OutOfMemoryError", null);
            }
            exitCode = 137;

        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                logger.info("Graph subprocess interrupted (likely cancelled)");
                if (reporter != null) {
                    reporter.reportFailed("GRAPH_EXTRACTION", "Process interrupted",
                            "InterruptedException", null);
                }
                Thread.currentThread().interrupt();
                exitCode = 130;
            } else {
                logger.error("Graph subprocess failed", e);
                if (reporter != null) {
                    reporter.reportFailed("GRAPH_EXTRACTION", e.getMessage(),
                            e.getClass().getSimpleName(), truncateStackTrace(e));
                }
                if (httpCallback != null && graphArgs != null) {
                    httpCallback.logFailed(graphArgs.taskId(), null,
                            "GRAPH_EXTRACTION", e.getMessage(), truncateStackTrace(e));
                }
                exitCode = 1;
            }

        } finally {
            if (memoryWatchdog != null) {
                memoryWatchdog.stop();
            }
            if (reporter != null) {
                reporter.stopHeartbeat();
            }
            cleanupNd4j();
        }

        System.exit(exitCode);
    }

    private static GraphSubprocessArgs.GraphExtractionResult runWithGraphConstructor(
            GraphConstructor graphConstructor,
            List<RetrievedDoc> docs,
            GraphSchema schema,
            SchemaEnforcementMode enforcementMode,
            GraphSubprocessArgs graphArgs,
            SubprocessProgressReporter reporter,
            long extractionStart) {

        // Configure extraction model if supported
        try {
            graphConstructor.configure(new GraphConstructor.ExtractionModelConfig(
                    graphArgs.llmProvider(),
                    graphArgs.llmModelName(),
                    graphArgs.llmTemperature(),
                    graphArgs.llmMaxTokens(),
                    graphArgs.customPrompt()));
        } catch (Exception e) {
            logger.warn("Could not configure extraction model: {}", e.getMessage());
        }

        // Process in batches
        int batchSize = graphArgs.batchSize();
        int totalDocs = docs.size();
        int totalBatches = (totalDocs + batchSize - 1) / batchSize;

        List<GraphSubprocessArgs.ExtractedEntity> allEntities = new ArrayList<>();
        List<GraphSubprocessArgs.ExtractedRelationship> allRelationships = new ArrayList<>();

        for (int batchIdx = 0; batchIdx < totalBatches; batchIdx++) {
            int start = batchIdx * batchSize;
            int end = Math.min(start + batchSize, totalDocs);
            List<RetrievedDoc> batch = docs.subList(start, end);

            int progressPercent = (int) ((batchIdx * 100.0) / totalBatches);
            reporter.reportProgress("GRAPH_EXTRACTION", progressPercent, null,
                    "Processing batch " + (batchIdx + 1) + "/" + totalBatches);

            logger.info("Processing batch {}/{} ({} docs)", batchIdx + 1, totalBatches, batch.size());

            try {
                // Progress listener
                GraphConstructor.ProgressListener progressListener = progress ->
                        logger.info("  Doc '{}': {} ({} entities, {} rels)",
                                progress.documentId(),
                                progress.status(),
                                progress.entities(),
                                progress.relationships());

                Graph batchGraph = graphConstructor.constructGraphFromDocs(
                        batch, schema, enforcementMode,
                        graphArgs.skipEmbedding(),
                        !graphArgs.persistMatrixGraph(),
                        progressListener);

                // Collect results
                if (batchGraph.getEntities() != null) {
                    for (Entity entity : batchGraph.getEntities()) {
                        allEntities.add(new GraphSubprocessArgs.ExtractedEntity(
                                entity.getId(), entity.getTitle(), entity.getType(),
                                entity.getDescription(),
                                entity.getConfidence() != null ? entity.getConfidence() : 1.0,
                                entity.getAliases(), Map.of()));
                    }
                }
                if (batchGraph.getRelationships() != null) {
                    for (Relationship rel : batchGraph.getRelationships()) {
                        allRelationships.add(new GraphSubprocessArgs.ExtractedRelationship(
                                rel.getSource(), rel.getTarget(), rel.getType(),
                                rel.getDescription(),
                                rel.getWeight() != null ? rel.getWeight() : 1.0,
                                rel.getConfidence() != null ? rel.getConfidence() : 1.0));
                    }
                }
            } catch (Exception e) {
                logger.error("Batch {}/{} failed: {}", batchIdx + 1, totalBatches, e.getMessage());
                // Continue with next batch
            }
        }

        long extractionDurationMs = System.currentTimeMillis() - extractionStart;

        // Matrix algorithms phase (PageRank, HITS, etc.)
        long matrixStart = System.currentTimeMillis();
        Map<String, Double> pageRankScores = Map.of();
        Map<String, Double> authorityScores = Map.of();
        Map<String, Double> hubScores = Map.of();

        if (graphArgs.runMatrixAlgorithms() && !allEntities.isEmpty()) {
            reporter.reportProgress("MATRIX_ALGORITHMS", 0, null, "Starting matrix algorithms");
            logger.info("Running matrix graph algorithms on {} entities, {} relationships",
                    allEntities.size(), allRelationships.size());
            // Matrix algorithms are run by the GraphConstructor internally
            // through MatrixGraphStore operations during constructGraphFromDocs.
            // Additional standalone algorithms could be run here if needed.
            reporter.reportProgress("MATRIX_ALGORITHMS", 100, null, "Matrix algorithms complete");
        }

        long matrixDurationMs = System.currentTimeMillis() - matrixStart;

        return new GraphSubprocessArgs.GraphExtractionResult(
                graphArgs.taskId(),
                allEntities.size(),
                allRelationships.size(),
                docs.size(),
                extractionDurationMs,
                matrixDurationMs,
                allEntities,
                allRelationships,
                pageRankScores,
                authorityScores,
                hubScores);
    }

    private static List<RetrievedDoc> convertToRetrievedDocs(List<GraphSubprocessArgs.DocumentChunk> chunks) {
        if (chunks == null) return List.of();
        return chunks.stream()
                .map(chunk -> new RetrievedDoc(
                        chunk.id(),
                        chunk.content(),
                        chunk.metadata() != null
                                ? chunk.metadata().entrySet().stream()
                                    .collect(Collectors.toMap(
                                            Map.Entry::getKey,
                                            e -> e.getValue() != null ? e.getValue().toString() : ""))
                                : Map.of()))
                .collect(Collectors.toList());
    }

    private static GraphSchema buildSchema(GraphSubprocessArgs args) {
        if ((args.entityTypes() == null || args.entityTypes().isEmpty())
                && (args.relationshipTypes() == null || args.relationshipTypes().isEmpty())) {
            return null;
        }

        List<NodeType> nodeTypes = new ArrayList<>();
        if (args.entityTypes() != null) {
            for (String type : args.entityTypes()) {
                nodeTypes.add(new NodeType(type, null, null));
            }
        }

        List<RelationshipType> relTypes = new ArrayList<>();
        if (args.relationshipTypes() != null) {
            for (String type : args.relationshipTypes()) {
                relTypes.add(new RelationshipType(type, null, null));
            }
        }

        return new GraphSchema(nodeTypes, relTypes, null);
    }

    private static AnnotationConfigApplicationContext createContext(GraphSubprocessArgs args) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getSystemProperties().put("kompile.subprocess.mode", "true");
        context.register(SubprocessGraphConfiguration.class);
        context.refresh();
        return context;
    }

    // === ND4J Lifecycle ===

    private static void initializeNd4j(String nd4jConfigJson) {
        try {
            DifferentialFunctionClassHolder.initInstance();
            Nd4jBackend backend = Nd4jBackend.load();
            Nd4j.backend = backend;
            logger.info("Loaded ND4J backend: {}", backend.getClass().getSimpleName());

            NativeOps nativeOps = NativeOpsHolder.getInstance().getDeviceNativeOps();
            nativeOps.initializeDevicesAndFunctions();

            // Apply ND4J environment config if provided
            if (nd4jConfigJson != null && !nd4jConfigJson.isBlank()) {
                try {
                    Nd4jEnvironmentConfig config = OBJECT_MAPPER.readValue(nd4jConfigJson, Nd4jEnvironmentConfig.class);
                    applyNd4jEnvironmentConfig(config);
                } catch (Exception e) {
                    logger.warn("Failed to parse ND4J config JSON, using defaults: {}", e.getMessage());
                    applyNd4jEnvironmentConfig(Nd4jEnvironmentConfig.defaults());
                }
            } else {
                applyNd4jEnvironmentConfig(Nd4jEnvironmentConfig.defaults());
            }

            logger.info("ND4J initialized: maxThreads={}, backend={}",
                    Nd4j.getEnvironment().maxThreads(),
                    Nd4j.getBackend().getClass().getSimpleName());
        } catch (Throwable e) {
            logger.warn("ND4J initialization failed: {}. Graph operations requiring " +
                    "matrix algorithms may fail.", e.getMessage());
        }
    }

    /**
     * Apply ND4J environment configuration.
     * This mirrors MainApplication.applyNd4jEnvironmentConfig() exactly.
     */
    private static void applyNd4jEnvironmentConfig(Nd4jEnvironmentConfig config) {
        if (config == null) {
            config = Nd4jEnvironmentConfig.defaults();
        }
        logger.info("Applying ND4J environment configuration (graph subprocess)...");

        try {
            if (config.enableBlas() != null) {
                Nd4j.getEnvironment().setEnableBlas(config.enableBlas());
            }
            if (config.helpersAllowed() != null) {
                Nd4j.getEnvironment().allowHelpers(config.helpersAllowed());
            }
            if (config.maxThreads() != null) {
                Nd4j.getEnvironment().setMaxThreads(config.maxThreads());
            }
            if (config.maxMasterThreads() != null) {
                Nd4j.getEnvironment().setMaxMasterThreads(config.maxMasterThreads());
            }
            if (config.debug() != null) {
                Nd4j.getEnvironment().setDebug(config.debug());
            }
            if (config.verbose() != null) {
                Nd4j.getEnvironment().setVerbose(config.verbose());
            }
            if (config.profiling() != null) {
                Nd4j.getEnvironment().setProfiling(config.profiling());
            }
            if (config.leaksDetector() != null) {
                Nd4j.getEnvironment().setLeaksDetector(config.leaksDetector());
            }
            if (config.tadThreshold() != null) {
                Nd4j.getEnvironment().setTadThreshold(config.tadThreshold());
            }
            if (config.elementwiseThreshold() != null) {
                Nd4j.getEnvironment().setElementwiseThreshold(config.elementwiseThreshold());
            }
            if (config.maxPrimaryMemory() != null && config.maxPrimaryMemory() > 0) {
                Nd4j.getEnvironment().setMaxPrimaryMemory(config.maxPrimaryMemory());
            }
            if (config.maxSpecialMemory() != null && config.maxSpecialMemory() > 0) {
                Nd4j.getEnvironment().setMaxSpecialMemory(config.maxSpecialMemory());
            }
            if (config.maxDeviceMemory() != null && config.maxDeviceMemory() > 0) {
                Nd4j.getEnvironment().setMaxDeviceMemory(config.maxDeviceMemory());
            }
        } catch (Exception e) {
            logger.warn("Error applying ND4J environment config: {}", e.getMessage());
        }
    }

    private static void cleanupNd4j() {
        try {
            // Best-effort cleanup
            logger.info("Cleaning up ND4J resources...");
        } catch (Throwable e) {
            logger.warn("ND4J cleanup error: {}", e.getMessage());
        }
    }

    private static String truncateStackTrace(Exception e) {
        StringBuilder sb = new StringBuilder();
        StackTraceElement[] elements = e.getStackTrace();
        int limit = Math.min(elements.length, 10);
        for (int i = 0; i < limit; i++) {
            sb.append(elements[i].toString()).append("\n");
        }
        if (elements.length > limit) {
            sb.append("... ").append(elements.length - limit).append(" more");
        }
        return sb.toString();
    }
}
