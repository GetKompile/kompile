/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat.tools.grounding;

import ai.kompile.cli.main.chat.tools.ToolContext;
import ai.kompile.cli.main.chat.tools.ToolResult;
import ai.kompile.cli.main.chat.tools.grounding.ProjectLocalLearningSubprocessExecutor.Plan;
import ai.kompile.cli.main.codeindex.CodeGraphIdentity;
import ai.kompile.cli.main.codeindex.IndexDatabase;
import ai.kompile.cli.main.codeindex.LocalCodeIndexer;
import ai.kompile.cli.main.project.LocalCrawlCliAgentRunner;
import ai.kompile.cli.main.project.LocalCrawlServingSession;
import ai.kompile.cli.main.project.NativeChatModels;
import ai.kompile.core.crawl.graph.GraphExtractionConfig;
import ai.kompile.core.crawl.graph.NativeChatCompletion;
import ai.kompile.core.crawl.graph.ProcessingRouteConfig;
import ai.kompile.core.crawl.graph.ProcessingRouteConfig.ProcessingBackend;
import ai.kompile.core.crawl.graph.ProcessingRouteConfig.ProcessingBackendType;
import ai.kompile.core.crawl.graph.UnifiedCrawlJob.LlmCallRecord;
import ai.kompile.core.crawl.graph.UnifiedCrawlRequest;
import ai.kompile.core.crawl.graph.UnifiedCrawlRequest.RuntimeConfig;
import ai.kompile.core.embeddings.EmbeddingModel;
import ai.kompile.core.graphrag.model.Entity;
import ai.kompile.core.graphrag.model.Graph;
import ai.kompile.core.graphrag.model.Relationship;
import ai.kompile.core.graphrag.model.schema.GraphSchema;
import ai.kompile.crawl.graph.CrawlStepPlan;
import ai.kompile.crawl.graph.CanonicalGraphSchemaArtifact;
import ai.kompile.crawl.graph.HeadlessUnifiedCorpusExtractor;
import ai.kompile.graph.reasoning.debug.UnifiedGraphDebugRenderer;
import ai.kompile.graph.reasoning.bayesian.BayesianNetwork;
import ai.kompile.graph.reasoning.bayesian.Factor;
import ai.kompile.graph.reasoning.bayesian.GraphBayesianNetworkBuilder;
import ai.kompile.graph.reasoning.bayesian.VariableElimination;
import ai.kompile.graph.reasoning.fol.MebnInferenceService;
import ai.kompile.graph.reasoning.hybrid.HybridReasoner.Structural;
import ai.kompile.graph.reasoning.lifecycle.FinalGraphLearningResolutionPipeline;
import ai.kompile.graph.reasoning.lifecycle.FinalGraphLearningResolutionPipeline.LearningOutcome;
import ai.kompile.graph.reasoning.lifecycle.FinalGraphLearningResolutionPipeline.ResolutionOutcome;
import ai.kompile.graph.reasoning.lifecycle.FinalGraphLearningResolutionPipeline.Result;
import ai.kompile.graph.reasoning.lifecycle.UnifiedGraphReasoningLifecycle;
import ai.kompile.graph.reasoning.lifecycle.UnifiedGraphReasoningLifecycle.Config;
import ai.kompile.graph.reasoning.lifecycle.UnifiedGraphReasoningLifecycle.Summary;
import ai.kompile.graph.reasoning.mebn.MTheory;
import ai.kompile.graph.reasoning.mebn.RelationalMTheoryArtifactCodec;
import ai.kompile.graph.reasoning.mebn.type.owl.OwlOntology;
import ai.kompile.graph.reasoning.mebn.type.owl.OwlClass;
import ai.kompile.graph.reasoning.mebn.type.owl.OwlObjectProperty;
import ai.kompile.graph.reasoning.mebn.type.owl.OwlRlReasoner;
import ai.kompile.graph.reasoning.mebn.type.owl.OwlRlResult;
import ai.kompile.graph.reasoning.mebn.type.owl.TableMemberOntologyBridge;
import ai.kompile.graph.reasoning.model.GraphEntity;
import ai.kompile.graph.reasoning.model.GraphRelation;
import ai.kompile.graph.reasoning.model.SimpleGraphEntity;
import ai.kompile.graph.reasoning.model.SimpleGraphRelation;
import ai.kompile.graph.reasoning.query.GraphQueryEngine;
import ai.kompile.graph.reasoning.query.GraphQueryEngine.Capability;
import ai.kompile.graph.reasoning.query.GraphQueryEngine.Direction;
import ai.kompile.graph.reasoning.query.GraphQueryEngine.Intent;
import ai.kompile.graph.reasoning.query.GraphQueryEngine.Query;
import ai.kompile.graph.reasoning.query.GraphQueryEngine.Status;
import ai.kompile.graph.reasoning.resolution.UnifiedGraphEntityResolver;
import ai.kompile.graph.reasoning.unified.GraphArchiveMigrator;
import ai.kompile.graph.reasoning.unified.KGraphCompatibilityPolicy;
import ai.kompile.graph.reasoning.unified.UnifiedGraph;
import ai.kompile.graph.reasoning.unified.UnifiedGraphArchive;
import ai.kompile.graph.reasoning.unified.UnifiedGraphArchiveEditor;
import ai.kompile.graph.reasoning.unified.UnifiedGraphArchiveQueryEngine;
import ai.kompile.graph.reasoning.unified.UnifiedGraphMutationJournal;
import ai.kompile.graph.reasoning.unified.VectorLayer;
import ai.kompile.graph.reasoning.unified.VectorLayer.Target;
import ai.kompile.graph.reasoning.uncertainty.SensitivityAnalyzer;
import ai.kompile.project.KompileProjectStore;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.springframework.ai.document.Document;

public final class LocalProjectGraphBackend {
  public static final String GRAPH_FILE = "graph.kgraph";
  public static final String ENTITY_LAYER = "kge";
  public static final String RELATION_LAYER = "kge-relations";
  public static final String MODEL_ARTIFACT = "models/kge.json";
  public static final int CODE_PROJECTION_VERSION = 4;
  private static final String PROJECTION_OWNER_KEY = "_kompileProjectionOwner";
  private static final String CODE_INDEX_PROJECTION_OWNER = "local-code-index";
  private static final int DEFAULT_DIM = 32;
  private static final int DEFAULT_EPOCHS = 8;
  private static final double DEFAULT_LEARNING_RATE = 0.05;
  private static final int MAX_DIM = 256;
  private static final int MAX_EPOCHS = 500;
  /**
   * Project-local crawl names that need a shared lifecycle equivalent only when the local graph
   * backend projects its request into the shared step planner. Keep this adapter local: the shared
   * registry must continue rejecting unknown IDs, rather than growing a second vocabulary.
   */
  private static final Map<String, String> LOCAL_TO_SHARED_LIFECYCLE_STEPS = Map.of(
      "MARKDOWN_EXTRACTION", "CONVERTING",
      // Local-only lexical completion must not make strict selection fall back to the shared
      // default plan; SURFACING is the shared non-enrichment marker for an already-produced local
      // crawl surface. LEARNING is the local name for the shared reasoning/enrichment lifecycle.
      "LEXICAL_INDEX", "SURFACING",
      "LEARNING", "ENRICHMENT");

  /**
   * Translate the documented project-local aliases before invoking the shared step planner.
   * Canonical shared IDs and unknown IDs pass through unchanged so typos still fail in the
   * shared validator instead of silently selecting a different pipeline.
   */
  static String sharedLifecycleStepId(String rawId) {
    if (rawId == null) {
      return null;
    }
    String id = rawId.trim().toUpperCase(Locale.ROOT);
    return LOCAL_TO_SHARED_LIFECYCLE_STEPS.getOrDefault(id, id);
  }

  /**
   * Resolve the project-local step selection through the shared crawl planner. Keeping this
   * boundary in the local backend means aliases are translated once, while validation and strict /
   * archive semantics remain owned by {@link CrawlStepPlan}.
   */
  static CrawlStepPlan localStepPlan(JsonNode request) {
    UnifiedCrawlRequest planRequest = new UnifiedCrawlRequest();
    planRequest.setEnabledSteps(stepIds(request, "steps", "enabledSteps"));
    planRequest.setArchivedSteps(stepIds(request, "archivedSteps"));
    planRequest.setStrictSteps(
        request != null && request.has("strictSteps")
            ? request.path("strictSteps").asBoolean()
            : null);
    return CrawlStepPlan.from(planRequest);
  }

  private static List<String> stepIds(JsonNode request, String... fields) {
    LinkedHashSet<String> ids = new LinkedHashSet<>();
    if (request != null) {
      for (String field : fields) {
        JsonNode values = request.get(field);
        if (values != null && values.isArray()) {
          for (JsonNode value : values) {
            String rawId = value == null || value.isNull() ? null : value.asText("");
            // Keep invalid blank/null IDs in the request. CrawlStepPlan owns validation; dropping
            // them here would silently turn a malformed explicit selection into the default plan.
            ids.add(sharedLifecycleStepId(rawId));
          }
        }
      }
    }
    return Collections.unmodifiableList(new ArrayList<>(ids));
  }

  private static final List<Capability> QUERY_CAPABILITIES =
      GraphQueryEngine.capabilityContract().stream()
          .filter(
              capability -> {
                return switch (Intent.valueOf(capability.intent())) {
                  case MODELS, CALCULATE, SCENARIO, SOLVE_TARGET -> false;
                  default -> true;
                };
              })
          .toList();
  private static final Set<Intent> QUERY_INTENTS =
      Set.copyOf(QUERY_CAPABILITIES.stream().map(Capability::intent).map(Intent::valueOf).toList());
  private final ObjectMapper mapper;
  private final KompileProjectStore projectStore;
  private final ProjectLocalLearningSubprocessExecutor learningExecutor;
  private final NativeChatCompletion nativeChatOverride;
  private static final AtomicLong LOCAL_KB_VERSION = new AtomicLong();
  private static final Map<Path, ReentrantLock> GRAPH_WRITE_LOCKS = new ConcurrentHashMap<>();
  private static final Map<String, LocalProjectGraphBackend.LocalSubscription> LOCAL_SUBSCRIPTIONS =
      new ConcurrentHashMap<>();
  private static final Map<String, ObjectNode> LOCAL_SIMULATION_RUNS = new ConcurrentHashMap<>();

  public LocalProjectGraphBackend(ObjectMapper mapper) {
    this(mapper, new ProjectLocalLearningSubprocessExecutor(mapper));
  }

  LocalProjectGraphBackend(
      ObjectMapper mapper, ProjectLocalLearningSubprocessExecutor learningExecutor) {
    this(mapper, learningExecutor, null);
  }

  LocalProjectGraphBackend(ObjectMapper mapper,
      ProjectLocalLearningSubprocessExecutor learningExecutor, NativeChatCompletion nativeChatOverride) {
    this.mapper = Objects.requireNonNull(mapper, "mapper");
    this.projectStore = new KompileProjectStore();
    this.learningExecutor = Objects.requireNonNull(learningExecutor, "learningExecutor");
    this.nativeChatOverride = nativeChatOverride;
  }

  public LocalProjectGraphBackend.CodeProjectionUpdate projectIndexedCodeProject(
      Path projectRoot,
      String knowledgeBaseId,
      String knowledgeBaseName,
      Long factSheetId,
      String projectId,
      LocalProjectGraphBackend.CodeProjectSource codeProject,
      String indexGeneration)
      throws Exception {
    Path normalizedRoot = projectRoot.toAbsolutePath().normalize();
    Path directory = this.safeGraphDirectory(normalizedRoot, knowledgeBaseId);
    Path graphPath = directory.resolve("graph.kgraph");
    Files.createDirectories(directory);
    Path lockPath = directory.resolve(".graph.kgraph.lock");
    ReentrantLock processLock =
        GRAPH_WRITE_LOCKS.computeIfAbsent(
            graphPath.toAbsolutePath().normalize(), ignoredx -> new ReentrantLock());
    processLock.lock();

    LocalProjectGraphBackend.CodeProjectionUpdate var25;
    try (FileChannel channel =
            FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        FileLock ignored = channel.lock(); ) {
      UnifiedGraph additions = new UnifiedGraph();
      additions
          .graphId("local:" + projectId + ":" + knowledgeBaseId)
          .meta("backend", "project-local")
          .meta("projectId", projectId)
          .meta("knowledgeBaseId", knowledgeBaseId)
          .meta("knowledgeBaseName", knowledgeBaseName)
          .meta("updatedAt", Instant.now().toString())
          .meta("codeProjectionVersion", CODE_PROJECTION_VERSION);
      if (factSheetId != null) {
        additions.factSheetId(factSheetId);
      }

      String projectNode = "project:" + stableId(normalizedRoot.toString());
      String knowledgeBaseNode = "knowledge-base:" + stableId(projectId + "\n" + knowledgeBaseId);
      additions.addEntity(
          GraphEntity.builder(projectNode)
              .type("KOMPILE_PROJECT")
              .label(projectId)
              .tag("project")
              .attribute("projectId", projectId)
              .attribute("projectRoot", normalizedRoot.toString())
              .build());
      Map<String, Object> knowledgeBaseAttributes = new LinkedHashMap<>();
      knowledgeBaseAttributes.put("knowledgeBaseId", knowledgeBaseId);
      if (factSheetId != null) {
        knowledgeBaseAttributes.put("factSheetId", factSheetId);
      }

      additions.addEntity(
          GraphEntity.builder(knowledgeBaseNode)
              .type("KNOWLEDGE_BASE")
              .label(knowledgeBaseName)
              .tag("crawl")
              .attributes(knowledgeBaseAttributes)
              .build());
      this.addRelation(
          additions,
          projectNode,
          knowledgeBaseNode,
          "HAS_KNOWLEDGE_BASE",
          Map.of("backend", "project-local"));
      String codeProjectId = codeProject.codeProjectId();
      int codeEntities =
          this.addCodeProjects(
              additions, knowledgeBaseNode, Map.of(), projectId, factSheetId, List.of(codeProject));
      Map<String, Object> metadata = new LinkedHashMap<>();
      metadata.put("backend", "project-local");
      metadata.put("projectId", projectId);
      metadata.put("knowledgeBaseId", knowledgeBaseId);
      metadata.put("knowledgeBaseName", knowledgeBaseName);
      metadata.put("updatedAt", Instant.now().toString());
      metadata.put("codeProjectionVersion", CODE_PROJECTION_VERSION);
      Object snapshotGeneration = additions.meta().get("codeIndexGeneration." + codeProjectId);
      if (snapshotGeneration == null) {
        throw new IllegalStateException("Code index has no committed generation; reindex before projection");
      }
      if (indexGeneration != null && !indexGeneration.equals(snapshotGeneration)) {
        throw new IllegalStateException("Code index changed before projection snapshot; retry publication");
      }
      metadata.put("codeIndexGeneration." + codeProjectId, snapshotGeneration);
      metadata.put(
          "codeIndexRoot." + codeProjectId,
          codeProject.root().toAbsolutePath().normalize().toString());
      metadata.put("phase.codeProjection." + codeProjectId, "COMPLETED");
      // A changed structural generation invalidates code-specific learned layers until the
      // optional learning pass records this exact generation as completed.
      metadata.put("phase.codeLearning." + codeProjectId, "STALE");
      metadata.put("learning.reasoningStale", true);
      metadata.put("learning.kgeStale", true);
      int entities;
      int relations;
      if (Files.isRegularFile(graphPath)) {
        GraphArchiveMigrator.migrateInPlace(graphPath);
        UnifiedGraphArchiveEditor.Result edited =
            UnifiedGraphArchiveEditor.rewrite(
                graphPath,
                graphPath,
                additions,
                record -> !this.isCodeProjectionOwned(record.entity(), codeProjectId),
                link -> !this.isCodeProjectionOwned(link.attributes(), codeProjectId),
                metadata);
        entities = edited.entities();
        relations = edited.relations();
      } else {
        metadata.forEach(additions::meta);
        this.saveAtomic(additions, graphPath);
        entities = additions.entityCount();
        relations = additions.relationCount();
      }
      this.writeCodeProjectionSummary(
          directory, knowledgeBaseName, graphPath, entities, relations, codeEntities, factSheetId);
      LOCAL_KB_VERSION.incrementAndGet();
      var25 =
          new LocalProjectGraphBackend.CodeProjectionUpdate(
              graphPath, entities, relations, codeEntities, knowledgeBaseId, factSheetId);
    } finally {
      processLock.unlock();
    }

    return var25;
  }

  public LocalProjectGraphBackend.GraphUpdate updateCrawlGraph(
      Path projectRoot,
      String knowledgeBaseId,
      String knowledgeBaseName,
      Long factSheetId,
      String projectId,
      List<LocalProjectGraphBackend.CodeProjectSource> codeProjects,
      String crawlJobId,
      JsonNode request)
      throws Exception {
    preflightNativeChat(projectRoot, request);
    List<LocalProjectGraphBackend.CodeProjectSource> selectedCodeProjects =
        codeProjects == null ? List.of() : codeProjects;
    this.refreshCodeProjectIndexes(selectedCodeProjects, projectId);
    Path graphPath =
        this.safeGraphDirectory(projectRoot.toAbsolutePath().normalize(), knowledgeBaseId)
            .resolve("graph.kgraph");
    return this.withGraphWriteLock(graphPath,
        () -> this.updateCrawlGraphUnlocked(projectRoot, knowledgeBaseId, knowledgeBaseName,
            factSheetId, projectId, selectedCodeProjects, crawlJobId, request));
  }

  private int preserveUnselectedCode(Path graphPath, UnifiedGraph graph,
      List<CodeProjectSource> selected) throws IOException {
    if (!Files.isRegularFile(graphPath)) return 0;
    GraphArchiveMigrator.migrateInPlace(graphPath);
    Set<String> refreshed = selected.stream().map(CodeProjectSource::codeProjectId)
        .collect(java.util.stream.Collectors.toSet());
    try (UnifiedGraphArchive previous = UnifiedGraphArchive.open(graphPath)) {
      Map<?, ?> previousMeta = previous.manifest().get("meta") instanceof Map<?, ?> values ? values : Map.of();
      Set<String> retainedProjects = new HashSet<>();
      Set<String> retainedEntities = new HashSet<>();
      int count = 0;
      try (var entities = previous.openEntityRecords()) {
        UnifiedGraphArchive.EntityRecord record;
        while ((record = entities.next()) != null) {
          GraphEntity entity = record.entity();
          String project = string(entity.attributes().get("codeProjectId"));
          if (project != null && !refreshed.contains(project) && isCodeProjectionOwned(entity, project)) {
            graph.addEntity(entity);
            retainedProjects.add(project);
            retainedEntities.add(entity.id());
            if (entity.attributes().containsKey("declarationKey")) count++;
          }
        }
      }
      // Retain links only when both endpoints survive; never restore dangling crawl links.
      try (var links = previous.openLinks()) {
        UnifiedGraphArchive.Link link;
        while ((link = links.next()) != null) {
          if ((retainedEntities.contains(link.sourceId()) || retainedEntities.contains(link.targetId()))
              && graph.entity(link.sourceId()).isPresent() && graph.entity(link.targetId()).isPresent()
              && graph.relation(link.id()).isEmpty()) {
            graph.addRelation(new ai.kompile.graph.reasoning.model.SimpleGraphRelation(
                link.id(), link.sourceId(), link.targetId(), link.type(), link.weight(),
                link.confidence(), link.directed(), link.tags(), null, link.timestamp(), link.attributes()));
          }
        }
      }
      for (String project : retainedProjects) {
        for (String prefix : List.of("codeIndexGeneration.", "codeIndexRoot.", "phase.codeProjection.")) {
          Object value = previousMeta.get(prefix + project);
          if (value != null) graph.meta(prefix + project, value);
        }
        // The current crawl's learning pass did not include these preserved slices.
        graph.meta("phase.codeLearning." + project, "STALE");
        graph.meta("learning.reasoningStale", true).meta("learning.kgeStale", true);
      }
      if (!retainedProjects.isEmpty()) {
        Object version = previousMeta.get("codeProjectionVersion");
        graph.meta("codeProjectionVersion", version == null ? 0 : version);
      }
      return count;
    }
  }

  private LocalProjectGraphBackend.GraphUpdate updateCrawlGraphUnlocked(
      Path projectRoot,
      String knowledgeBaseId,
      String knowledgeBaseName,
      Long factSheetId,
      String projectId,
      List<LocalProjectGraphBackend.CodeProjectSource> codeProjects,
      String crawlJobId,
      JsonNode request)
      throws Exception {
    Path directory = projectRoot.resolve("data/crawls").resolve(knowledgeBaseId).normalize();
    Path graphPath = directory.resolve("graph.kgraph");
    // This method runs under the graph write lock. Snapshot the old schema before semantic
    // extraction, because the fresh graph below intentionally starts without prior analysis assets.
    GraphSchema persistedSchemaSeed = this.loadPersistedSchemaSeed(graphPath);
    UnifiedGraph graph =
        new UnifiedGraph()
            .graphId("local:" + projectId + ":" + knowledgeBaseId)
            .meta("backend", "project-local")
            .meta("projectId", projectId)
            .meta("knowledgeBaseId", knowledgeBaseId)
            .meta("knowledgeBaseName", knowledgeBaseName)
            .meta("updatedAt", Instant.now().toString());
    if (factSheetId != null) {
      graph.factSheetId(factSheetId);
    }

    String projectNode = "project:" + stableId(projectRoot.toAbsolutePath().normalize().toString());
    String knowledgeBaseNode = "knowledge-base:" + stableId(projectId + "\n" + knowledgeBaseId);
    graph.addEntity(
        GraphEntity.builder(projectNode)
            .type("KOMPILE_PROJECT")
            .label(projectId)
            .tag("project")
            .attribute("projectId", projectId)
            .attribute("projectRoot", projectRoot.toAbsolutePath().normalize().toString())
            .build());
    Map<String, Object> knowledgeBaseAttributes = new LinkedHashMap<>();
    knowledgeBaseAttributes.put("knowledgeBaseId", knowledgeBaseId);
    if (factSheetId != null) {
      knowledgeBaseAttributes.put("factSheetId", factSheetId);
    }

    graph.addEntity(
        GraphEntity.builder(knowledgeBaseNode)
            .type("KNOWLEDGE_BASE")
            .label(knowledgeBaseName)
            .tag("crawl")
            .attributes(knowledgeBaseAttributes)
            .build());
    this.addRelation(
        graph,
        projectNode,
        knowledgeBaseNode,
        "HAS_KNOWLEDGE_BASE",
        Map.of("backend", "project-local"));
    Map<String, String> documentNodes =
        this.addDocuments(
            graph, directory, knowledgeBaseNode, projectId, knowledgeBaseId, factSheetId);
    int codeEntityCount =
        this.addCodeProjects(
            graph, knowledgeBaseNode, documentNodes, projectId, factSheetId, codeProjects);
    LocalProjectGraphBackend.SemanticExtractionSummary semanticExtraction =
        this.addSemanticExtraction(
            graph,
            projectRoot,
            directory,
            knowledgeBaseNode,
            knowledgeBaseId,
            factSheetId,
            crawlJobId,
            request,
            persistedSchemaSeed);
    if (Files.isRegularFile(graphPath)) {
      try (UnifiedGraphArchive previous = UnifiedGraphArchive.open(graphPath)) {
        if (previous.hasCompactTopology()) {
          previous.copyCompatibleAnalysisAssetsTo(graph);
        } else {
          this.retainCompatibleAssets(UnifiedGraph.load(graphPath), graph);
        }
      }
    }
    LocalProjectGraphBackend.TrainingRequest training =
        LocalProjectGraphBackend.TrainingRequest.from(request);
    LocalProjectGraphBackend.ReasoningLearningRequest reasoningLearning =
        LocalProjectGraphBackend.ReasoningLearningRequest.from(request);
    LocalProjectGraphBackend.ResolutionRequest resolution =
        LocalProjectGraphBackend.ResolutionRequest.from(request);
    boolean learningEnabled =
        !graph.relations().isEmpty() && (training.enabled() || reasoningLearning.enabled());
    Result<
            UnifiedGraph,
            LocalProjectGraphBackend.FinalLearningSummary,
            LocalProjectGraphBackend.ResolutionSummary>
        lifecycle =
            FinalGraphLearningResolutionPipeline.run(
                graph,
                learningEnabled,
                (scope, phase) -> {
                  ai.kompile.cli.main.chat.tools.grounding.ProjectLocalLearningSubprocessExecutor
                          .Result
                      execution =
                          this.learningExecutor.learn(
                              scope,
                              new Plan(
                                  training.toConfig(), reasoningLearning.toConfig(), phase.name()),
                              directory,
                              crawlJobId);
                  UnifiedGraph learnedGraph = execution.graph();
                  LocalProjectGraphBackend.TrainingSummary learned =
                      training.enabled() ? this.trainingSummary(learnedGraph) : null;
                  Summary reasoning =
                      reasoningLearning.enabled()
                          ? this.reasoningSummary(learnedGraph)
                          : Summary.disabled();
                  return new LearningOutcome(
                      learnedGraph,
                      new LocalProjectGraphBackend.FinalLearningSummary(learned, reasoning));
                },
                scope -> {
                  if (!resolution.enabled()) {
                    return new ResolutionOutcome(
                        scope, 0, false, LocalProjectGraphBackend.ResolutionSummary.disabled());
                  } else {
                    ai.kompile.graph.reasoning.resolution.UnifiedGraphEntityResolver.Result
                        resolved =
                            new UnifiedGraphEntityResolver()
                                .resolve(scope, resolution.toResolverConfig());
                    return new ResolutionOutcome(
                        resolved.graph(),
                        resolved.entitiesMerged(),
                        resolved.graphChanged(),
                        LocalProjectGraphBackend.ResolutionSummary.from(resolved));
                  }
                });
    graph = (UnifiedGraph) lifecycle.scope();
    // The prior-graph analysis copy and any learning/resolution adapters are complete now. Publish
    // last so neither a stale copied artifact nor a learner returning a fresh graph can drop the
    // schema receipt that belongs with this atomic facts snapshot.
    GraphSchema schemaToPublish = semanticExtraction.canonicalGraphSchema() != null
        ? semanticExtraction.canonicalGraphSchema() : persistedSchemaSeed;
    if (schemaToPublish != null) {
      graph.putArtifact(CanonicalGraphSchemaArtifact.ARTIFACT_NAME,
          CanonicalGraphSchemaArtifact.encode(this.mapper, schemaToPublish));
    }
    LocalProjectGraphBackend.FinalLearningSummary finalLearning =
        lifecycle.canonicalLearning() != null
            ? (LocalProjectGraphBackend.FinalLearningSummary) lifecycle.canonicalLearning()
            : (LocalProjectGraphBackend.FinalLearningSummary) lifecycle.preResolutionLearning();
    LocalProjectGraphBackend.TrainingSummary trainingSummary =
        finalLearning != null ? finalLearning.embedding() : null;
    Summary reasoningSummary =
        finalLearning != null ? finalLearning.reasoning() : Summary.disabled();
    LocalProjectGraphBackend.ResolutionSummary resolutionSummary =
        (LocalProjectGraphBackend.ResolutionSummary) lifecycle.resolution();
    graph
        .meta("enrichment.requested", reasoningLearning.enrichmentRequested())
        .meta("enrichment.engine", "UnifiedGraphReasoningLifecycle")
        .meta(
            "enrichment.status",
            !reasoningLearning.enrichmentRequested()
                ? "SKIPPED_BY_STEP_PLAN"
                : (reasoningSummary.enabled()
                    ? "COMPLETED"
                    : (graph.relations().isEmpty()
                        ? "SKIPPED_EMPTY_GRAPH"
                        : "SKIPPED_BY_CONFIGURATION")));
    if (training.enabled() && trainingSummary != null) stampCodeGenerations(graph, "codeKgeGeneration.");
    if (reasoningSummary.enabled()) stampCodeGenerations(graph, "codeLearningGeneration.");
    // Preserve the already-published code slices before the single atomic replacement.
    // Do not re-read a newer SQLite generation and label it with an older archive receipt.
    codeEntityCount += preserveUnselectedCode(graphPath, graph, codeProjects);
    Files.createDirectories(directory);
    this.saveAtomic(graph, graphPath);
    this.updateCrawlSummary(
        directory,
        graph,
        graphPath,
        codeEntityCount,
        trainingSummary,
        reasoningSummary,
        resolutionSummary,
        semanticExtraction);
    return new LocalProjectGraphBackend.GraphUpdate(
        graphPath,
        graph.entities().size(),
        graph.relations().size(),
        codeEntityCount,
        graph.vectorLayers().values().stream().mapToInt(VectorLayer::size).sum(),
        trainingSummary != null ? trainingSummary.algorithm() : null,
        reasoningLearning.enrichmentRequested(),
        reasoningSummary.enabled(),
        reasoningSummary.folPslLearned(),
        reasoningSummary.mebnLearned(),
        reasoningSummary.modelsTrained(),
        reasoningSummary.pslRuleCount(),
        reasoningSummary.mebnFragmentCount(),
        resolutionSummary.enabled(),
        resolutionSummary.entitiesMerged(),
        resolutionSummary.typesCorrected(),
        resolutionSummary.identifierLinksCreated(),
        semanticExtraction.entities(),
        semanticExtraction.relations(),
        semanticExtraction.errors());
  }

  public JsonNode reasoningQuery(JsonNode params, ToolContext context) throws Exception {
    Query query = this.toQuery(params);
    LocalProjectGraphBackend.ArchiveQuerySelection archiveSelection =
        this.selectArchiveQuery(context, params, query);
    boolean archiveNative = archiveSelection != null;
    ai.kompile.graph.reasoning.query.GraphQueryEngine.Result result;
    String graphPath;
    if (archiveSelection != null) {
      result = archiveSelection.result();
      graphPath = archiveSelection.path().toString();
    } else {
      LocalProjectGraphBackend.GraphSelection selection =
          this.selectQueryGraph(context, params, query);
      result = new GraphQueryEngine().query(selection.graph(), query);
      if (Boolean.TRUE.equals(selection.graph().meta().get("truncated"))) {
        result = partialResult(result, selection.graph());
      }

      graphPath = selection.pathDescription();
    }

    if (query.intent() == Intent.CAPABILITIES) {
      result =
          new ai.kompile.graph.reasoning.query.GraphQueryEngine.Result(
              result.status(),
              result.intent(),
              "Supports the project-local read-only graph query contract.",
              result.entities(),
              result.relations(),
              result.path(),
              QUERY_CAPABILITIES,
              result.guidance(),
              result.data(),
              result.resolutions(),
              result.trace());
    }

    ObjectNode json = (ObjectNode) this.mapper.valueToTree(result);
    json.put("backend", "project-local");
    json.put("graphPath", graphPath);
    json.put("archiveNative", archiveNative);
    return json;
  }

  private static ai.kompile.graph.reasoning.query.GraphQueryEngine.Result partialResult(
      ai.kompile.graph.reasoning.query.GraphQueryEngine.Result result, UnifiedGraph graph) {
    Map<String, Object> data = new LinkedHashMap<>(result.data());
    data.put("boundedGraphTruncated", true);
    data.put("materializedNodes", graph.meta().get("materializedNodes"));
    data.put("materializedEdges", graph.meta().get("materializedEdges"));
    List<String> guidance = new ArrayList<>(result.guidance());
    guidance.add(
        "The compact archive neighborhood reached its materialization budget; absence is not"
            + " conclusive.");
    return new ai.kompile.graph.reasoning.query.GraphQueryEngine.Result(
        Status.PARTIAL,
        result.intent(),
        result.summary() + " Results are partial because the archive query budget was reached.",
        result.entities(),
        result.relations(),
        result.path(),
        result.capabilities(),
        guidance,
        data,
        result.resolutions(),
        result.trace());
  }

  public ToolResult reason(JsonNode params, ToolContext context) {
    String target = params.path("target").asText("").trim();
    if (target.isEmpty()) {
      return ToolResult.error("target is required");
    } else {
      try {
        ObjectNode query = this.mapper.createObjectNode();
        this.copySelector(params, query);
        String normalized = target.startsWith("causal:") ? target.substring(7).trim() : target;
        Matcher fact = Pattern.compile("^([^\\s(]+)\\(([^,]+),\\s*([^)]+)\\)$").matcher(normalized);
        if (fact.matches()) {
          query.put("operation", "VERIFY");
          query.put("entityId", fact.group(2).trim());
          query.put("targetId", fact.group(3).trim());
          query.putArray("relationTypes").add(fact.group(1).trim());
        } else {
          query.put("operation", "DESCRIBE");
          query.put("entityId", normalized);
        }

        JsonNode result = this.reasoningQuery(query, context);
        String status = result.path("status").asText("UNKNOWN");
        String summary = result.path("summary").asText("No explanation was produced.");
        StringBuilder output =
            new StringBuilder()
                .append("**")
                .append(status)
                .append("**\n")
                .append("Target: ")
                .append(target)
                .append("\n\n")
                .append("Answer:\n")
                .append(summary);
        JsonNode relations = result.path("relations");
        if (relations.isArray() && !relations.isEmpty()) {
          output.append("\n\nSupporting graph relations:");

          for (JsonNode relation : relations) {
            output
                .append("\n  - ")
                .append(relation.path("sourceLabel").asText(relation.path("sourceId").asText("?")))
                .append(" -[")
                .append(relation.path("type").asText("?"))
                .append("]-> ")
                .append(relation.path("targetLabel").asText(relation.path("targetId").asText("?")));
          }
        }

        return ToolResult.success(
            "graph_reason: " + target,
            output.toString(),
            Map.of("target", target, "verdict", status, "backend", "project-local"));
      } catch (Exception var14) {
        return ToolResult.error("graph_reason local error: " + this.message(var14));
      }
    }
  }

  public ToolResult exportGraph(JsonNode params, ToolContext context) {
    String requestedPath = params.path("path").asText("").trim();
    if (requestedPath.isEmpty()) {
      return ToolResult.error("path is required");
    } else {
      String format = params.path("format").asText("kgraph").toLowerCase(Locale.ROOT);

      try {
        LocalProjectGraphBackend.GraphSelection selection = this.selectGraph(context, params);
        Path output = context.resolvePath(requestedPath).toAbsolutePath().normalize();
        if (output.getParent() != null) {
          Files.createDirectories(output.getParent());
        }

        if ("kgraph".equals(format)) {
          this.saveAtomic(selection.graph(), output, KGraphCompatibilityPolicy.PORTABLE_V2);
        } else {
          if (!"ascii".equals(format)) {
            return ToolResult.error(
                "Project-local graph export supports kgraph and ascii; PNG rendering is not"
                    + " available in the in-process archive backend.");
          }

          Files.writeString(
              output, UnifiedGraphDebugRenderer.toAscii(selection.graph()), StandardCharsets.UTF_8);
        }

        long bytes = Files.size(output);
        return ToolResult.success(
            "graph_export: " + output.getFileName(),
            "Exported the project-local graph (" + bytes + " bytes) to " + output + ".",
            Map.of(
                "path",
                output.toString(),
                "bytes",
                bytes,
                "backend",
                "project-local",
                "entities",
                selection.graph().entities().size(),
                "relations",
                selection.graph().relations().size()));
      } catch (Exception var9) {
        return ToolResult.error("graph_export local error: " + this.message(var9));
      }
    }
  }

  public ToolResult importGraph(JsonNode params, ToolContext context) {
    String requestedPath = params.path("path").asText("").trim();
    if (requestedPath.isEmpty()) {
      return ToolResult.error("path is required");
    } else {
      try {
        Path source = context.resolvePath(requestedPath).toAbsolutePath().normalize();
        if (!Files.isRegularFile(source)) {
          return ToolResult.error("No .kgraph file at: " + source);
        } else {
          UnifiedGraph graph = UnifiedGraph.load(source);
          Long requestedFactSheet = this.optionalLong(params, "factSheetId", "fact_sheet_id");
          if (requestedFactSheet != null) {
            graph.factSheetId(requestedFactSheet);
          }

          Path root = this.projectRoot(context.getWorkingDirectory());
          String knowledgeBaseId =
              graph.factSheetId() != null
                  ? "kb-" + graph.factSheetId()
                  : this.slug(
                      this.firstNonBlank(
                          this.stringMeta(graph, "knowledgeBaseId"),
                          graph.graphId(),
                          "imported-graph"));
          Path directory = this.safeGraphDirectory(root, knowledgeBaseId);
          Path target = directory.resolve("graph.kgraph");
          return this.withGraphWriteLock(
              target,
              () -> {
                this.saveAtomic(graph, target);
                this.writeImportedSummary(directory, graph, target);
                int embeddings =
                    graph.vectorLayers().values().stream().mapToInt(VectorLayer::size).sum();
                return ToolResult.success(
                    "graph_import: " + source.getFileName(),
                    "Loaded "
                        + graph.entities().size()
                        + " nodes, "
                        + graph.relations().size()
                        + " edges, and "
                        + embeddings
                        + " vectors into project-local knowledge base "
                        + knowledgeBaseId
                        + ".",
                    Map.of(
                        "nodes",
                        graph.entities().size(),
                        "edges",
                        graph.relations().size(),
                        "embeddings",
                        embeddings,
                        "backend",
                        "project-local",
                        "knowledgeBase",
                        knowledgeBaseId));
              });
        }
      } catch (Exception var11) {
        return ToolResult.error("graph_import local error: " + this.message(var11));
      }
    }
  }

  public ToolResult embeddings(JsonNode params, ToolContext context) {
    String action = params.path("action").asText("").toLowerCase(Locale.ROOT);
    if (action.isEmpty()) {
      return ToolResult.error("action is required");
    } else {
      try {
        if ("algorithms".equals(action)) {
          return ToolResult.success(
              "graph_embeddings.algorithms",
              "Available project-local KGE algorithms:\n\n"
                  + "- **TransE** (id=TRANSE)\n"
                  + "  Translational entity/relation vectors.\n"
                  + "- **RotatE** (id=ROTATE)\n"
                  + "  Complex entity vectors with relation phases.\n",
              Map.of("backend", "project-local"));
        } else {
          LocalProjectGraphBackend.GraphSelection selection = this.selectGraph(context, params);

          return switch (action) {
            case "train" -> this.trainAction(params, selection);
            case "jobs" -> this.jobsAction(selection);
            case "job_status" -> this.jobStatusAction(params, selection);
            case "cancel" ->
                ToolResult.error(
                    "Project-local embedding training is synchronous and cannot be cancelled.");
            case "score" -> this.scoreAction(params, selection);
            case "predict_tails" ->
                this.predictAction(
                    params, selection, LocalProjectGraphBackend.PredictionTarget.TAIL);
            case "predict_heads" ->
                this.predictAction(
                    params, selection, LocalProjectGraphBackend.PredictionTarget.HEAD);
            case "predict_relations" ->
                this.predictAction(
                    params, selection, LocalProjectGraphBackend.PredictionTarget.RELATION);
            case "similar" -> this.similarAction(params, selection);
            default -> ToolResult.error("Unknown action: " + action);
          };
        }
      } catch (Exception var7) {
        return ToolResult.error("graph_embeddings local error: " + this.message(var7));
      }
    }
  }

  public LocalProjectGraphBackend.GraphStats stats(Path workingDirectory, String knowledgeBase)
      throws Exception {
    LocalProjectGraphBackend.GraphSelection selection =
        this.selectGraph(workingDirectory, this.selector(knowledgeBase, null));
    int embeddings =
        selection.graph().vectorLayers().values().stream().mapToInt(VectorLayer::size).sum();
    return new LocalProjectGraphBackend.GraphStats(
        selection.graph().entities().size(),
        selection.graph().relations().size(),
        embeddings,
        selection.pathDescription());
  }

  public ToolResult knowledgeGraph(JsonNode params, ToolContext context) {
    String action = params.path("action").asText("").trim().toLowerCase(Locale.ROOT);
    if (action.isEmpty()) {
      return ToolResult.error("action is required");
    } else {
      try {
        return switch (action) {
          case "extract" -> this.localExtract(params, context);
          case "list_providers" -> this.jsonSuccess(action,
              mapper.valueToTree(Map.of("backend", "native-chat", "providers", NativeChatModels.providers(),
                  "note", "Provider metadata only; not authentication or live inference proof.")),
              Map.of("backend", "native-chat"));
          case "list_models" -> this.localModels(params, context);
          case "capability_probe" -> this.localCapabilityProbe(params, context);
          case "list_fact_sheets" -> this.localFactSheetInventory(context);
          case "list_graphs", "list_snapshots" -> this.localGraphInventory(action, context);
          case "overview",
                  "stats",
                  "graph_health",
                  "report",
                  "get_fact_sheet",
                  "get_active_fact_sheet",
                  "opinions",
                  "facts_by_tier",
                  "reasoning_layers" ->
              this.localOverview(action, params, context);
          case "owl_reasoning", "ontology_conformance" ->
              this.localOwl(action, params, context);
          case "list_nodes", "search_nodes", "search_entity", "find_by_topic" ->
              this.localNodes(action, params, context);
          case "get_node", "node_provenance" -> this.localNode(action, params, context);
          case "list_edges" -> this.localEdges(params, context);
          case "list_predicates" -> this.localPredicates(params, context);
          case "add_node", "add_edge", "delete_node", "delete_edge" ->
              this.localMutate(action, params, context);
          case "find_connected",
                  "related_docs",
                  "source_context",
                  "entities_in_doc",
                  "traverse",
                  "hierarchy",
                  "ancestors",
                  "source_chunks",
                  "shortest_path" ->
              this.localReasoning(action, params, context);
          default ->
              ToolResult.error(
                  "Project-local knowledge graph action '"
                      + action
                      + "' is not implemented by the archive backend yet. Local crawl archives"
                      + " support graph discovery, status, node/edge search, predicate discovery,"
                      + " traversal, algorithms, reporting, and shortest paths.");
        };
      } catch (Exception var6) {
        return ToolResult.error("knowledge_graph local error: " + this.message(var6));
      }
    }
  }

  private ToolResult localModels(JsonNode params, ToolContext context) throws Exception {
    rejectNativeCredentials(params);
    for (String selector : List.of("model_provider", "provider")) {
      if (params.hasNonNull(selector) && (!params.get(selector).isTextual() || params.get(selector).asText().isBlank())) {
        throw new IllegalArgumentException(selector + " must be a non-empty string");
      }
    }
    Map<String, Object> catalog = NativeChatModels.listModels(projectRoot(context.getWorkingDirectory()),
        firstNonBlank(text(params, "model_provider"), text(params, "provider")));
    return jsonSuccess("list_models", mapper.valueToTree(catalog), Map.of("backend", "native-chat"));
  }

  private ToolResult localCapabilityProbe(JsonNode params, ToolContext context) throws Exception {
    rejectNativeCredentials(params);
    String provider = firstNonBlank(text(params, "model_provider"), text(params, "provider"));
    if (ProcessingRouteConfig.isNativeChatProvider(provider)) {
      provider = ProcessingRouteConfig.nativeChatRoute(provider, null).getBackends().get(0).getProvider();
    }
    Map<String, Object> probe = NativeChatModels.probe(projectRoot(context.getWorkingDirectory()),
        provider, firstNonBlank(text(params, "model_name"), text(params, "model")), text(params, "thinking"),
        firstNonBlank(text(params, "probe_operation"), "text"), params.path("live").asBoolean(false),
        Duration.ofSeconds(params.path("timeout_seconds").asInt(30)));
    return jsonSuccess("capability_probe", mapper.valueToTree(probe), Map.of("backend", "native-chat"));
  }

  /** Production extraction without the crawl/archive write lifecycle unless persist=true. */
  private ToolResult localExtract(JsonNode params, ToolContext context) throws Exception {
    String input = text(params, "text");
    if (input == null || input.isBlank()) return ToolResult.error("text is required for extract");
    if (input.length() > 200_000) return ToolResult.error("extract text exceeds 200000 characters; use crawl_documents");
    if (params.hasNonNull("agents") || params.hasNonNull("merge_strategy")) {
      return ToolResult.error("Local extract uses one production orchestrator route, not multi-agent merging; "
          + "use model_provider=chat[:provider] or processingRoute, without agents/merge_strategy");
    }
    Path root = projectRoot(context.getWorkingDirectory());
    ObjectNode request = mapper.createObjectNode();
    ObjectNode config = params.path("graphExtraction").isObject()
        ? params.path("graphExtraction").deepCopy() : mapper.createObjectNode();
    String provider = firstNonBlank(text(params, "model_provider"), text(params, "provider"),
        text(config, "llmProvider"), params.hasNonNull("processingRoute") ? "default" : "chat");
    config.put("llmProvider", provider);
    String model = firstNonBlank(text(params, "model_name"), text(params, "model"), text(config, "modelName"));
    if (model != null) config.put("modelName", model);
    String thinking = firstNonBlank(text(params, "thinking"), text(config, "thinking"));
    if (thinking != null) config.put("thinking", thinking);
    config.put("entityResolution", false);
    if (!config.hasNonNull("extractionMode")) config.put("extractionMode", "SINGLE_PASS");
    if (params.hasNonNull("min_confidence")) config.put("minConfidence", params.path("min_confidence").asDouble());
    if (params.hasNonNull("entity_types")) {
      ArrayNode types = config.putArray("entityTypes");
      for (String type : params.path("entity_types").asText().split(",")) {
        if (!type.isBlank()) types.add(type.trim());
      }
    }
    request.set("graphExtraction", config);
    if (params.hasNonNull("processingRoute")) request.set("processingRoute", params.get("processingRoute"));
    if (usesNativeChat(request)) rejectNativeCredentials(params);
    preflightNativeChat(root, request);
    GraphExtractionConfig extraction = mapper.treeToValue(config, GraphExtractionConfig.class);
    ProcessingRouteConfig route = configuredProcessingRoute(request, extraction);
    if (route == null) return ToolResult.error("No extraction route; use model_provider=chat[:provider], a CLI alias, or serving");
    RuntimeConfig runtime = new RuntimeConfig();
    runtime.setLlmCallTimeoutSeconds(Math.max(10, Math.min(300, params.path("timeout_seconds").asInt(60))));
    HeadlessUnifiedCorpusExtractor.Result result;
    try (LocalCrawlServingSession serving = requiresLocalServing(route)
            ? LocalCrawlServingSession.start(root, servingModel(extraction, route), Map.of(), servingStartupTimeout(runtime)) : null;
        HeadlessUnifiedCorpusExtractor extractor = new HeadlessUnifiedCorpusExtractor(
            new LocalCrawlCliAgentRunner(root, model, mapper), serving, nativeChatCompletion(root),
            null, null, 1, null, null)) {
      result = extractor.extract(List.of(new Document("inline-extract", input,
              Map.of("source_path", "inline-extract"))), extraction, route, runtime,
          1, "extract-" + UUID.randomUUID(), null);
    }
    if (result.failed()) {
      return ToolResult.error("Production graph extraction failed; nothing persisted: " + String.join("; ", result.errors()));
    }
    boolean persist = params.path("persist").asBoolean(false);
    String execution = extractionRuntime(route, result.llmCalls());
    ObjectNode response = mapper.createObjectNode().put("backend", "project-local")
        .put("engine", "GraphExtractionOrchestrator").put("runtime", execution).put("persisted", persist);
    response.set("entities", mapper.valueToTree(result.graph().getEntities()));
    response.set("relationships", mapper.valueToTree(result.graph().getRelationships()));
    if (persist) {
      Long factSheetId = optionalLong(params, "factSheetId", "fact_sheet_id");
      String kb = slug(firstNonBlank(text(params, "knowledgeBase"), text(params, "knowledge_base"),
          factSheetId == null ? null : "kb-" + factSheetId,
          new LocalProjectCrawlBackend(mapper, this).defaultKnowledgeBaseId(root)));
      Path target = safeGraphDirectory(root, kb).resolve(GRAPH_FILE);
      UnifiedGraph additions = new UnifiedGraph().graphId("local:" + kb)
          .meta("knowledgeBaseId", kb).meta("knowledgeBaseName", kb)
          .meta("semanticExtractionEngine", "GraphExtractionOrchestrator")
          .meta("semanticExtractionRuntime", execution);
      if (factSheetId != null) additions.factSheetId(factSheetId);
      String kbNode = "knowledge-base:" + stableId(kb);
      additions.addEntity(GraphEntity.builder(kbNode).type("KNOWLEDGE_BASE").label(kb).build());
      mergeSemanticGraph(additions, kbNode, kb, result.graph(), result.errors());
      withGraphWriteLock(target, () -> {
        if (Files.isRegularFile(target) && compactArchive(target)) {
          UnifiedGraphArchiveEditor.rewrite(target, target, additions, entity -> true, relation -> true,
              Map.of("updatedAt", Instant.now().toString(),
                    "learning.reasoningStale", true, "learning.kgeStale", true));
        } else {
          UnifiedGraph graph = Files.isRegularFile(target) ? UnifiedGraph.load(target) : additions;
          if (graph != additions) {
            for (GraphEntity entity : additions.entities()) {
              graph.removeEntityById(entity.id());
              graph.addEntity(entity);
            }
            for (GraphRelation relation : additions.relations()) {
              graph.removeRelationById(relation.id());
              graph.addRelation(relation);
            }
          }
          graph.meta("learning.reasoningStale", true).meta("learning.kgeStale", true);
          saveAtomic(graph, target);
          if (!Files.exists(target.getParent().resolve("crawl-result.json"))) {
            writeImportedSummary(target.getParent(), graph, target);
          }
        }
        LOCAL_KB_VERSION.incrementAndGet();
        return null;
      });
      response.put("knowledgeBase", kb).put("graphPath", target.toString());
    }
    return jsonSuccess("extract", response, Map.of("backend", "project-local", "runtime", execution,
        "persisted", persist, "entityCount", result.graph().getEntities().size(),
        "relationCount", result.graph().getRelationships().size()));
  }

  /**
   * Manual graph CRUD (knowledge_graph add_node/add_edge/delete_node/delete_edge) over the
   * project-local archive: compact KGraph v3 goes through the bounded-heap copy-on-write editor,
   * legacy layouts load-mutate-save atomically. Every path takes the per-graph write lock.
   */
  private ToolResult localMutate(String action, JsonNode params, ToolContext context)
      throws Exception {
    LocalProjectGraphBackend.GraphSelection selected = this.writableSelection(context, params);
    UnifiedGraph additions = new UnifiedGraph();
    Set<String> deleteEntityIds = new LinkedHashSet<>();
    Set<String> deleteRelationIds = new LinkedHashSet<>();

    switch (action) {
      case "add_node" -> {
        String title = this.text(params, "title");
        if (title == null || title.isBlank()) {
          return ToolResult.error("title is required for add_node");
        }
        String id = this.firstNonBlank(this.text(params, "id"), "node:" + this.stableId(title));
        Map<String, Object> meta = Map.of();
        String metaJson = this.text(params, "metadata_json");
        if (metaJson != null && !metaJson.isBlank()) {
          try {
            meta = this.mapper.readValue(metaJson, new TypeReference<Map<String, Object>>() {});
          } catch (IOException e) {
            return ToolResult.error("metadata_json must be a JSON object");
          }
        }
        additions.addEntity(GraphEntity.builder(id)
            .type(this.firstNonBlank(this.text(params, "node_type"), entityMeta(meta, "type", "ENTITY")))
            .label(title)
            .confidence(entityConfidence(meta))
            .attributes(meta)
            .tag("stdio-local")
            .attribute("createdAt", Instant.now().toString())
            .build());
      }
      case "add_edge" -> {
        String from = this.firstNonBlank(
            this.text(params, "from_node_id"), this.text(params, "from"));
        String to = this.firstNonBlank(this.text(params, "to_node_id"), this.text(params, "to"));
        if (from == null || to == null) {
          return ToolResult.error("from_node_id and to_node_id are required for add_edge");
        }
        String type = this.firstNonBlank(
            this.text(params, "edge_type"), this.text(params, "relationship_type"), "RELATED_TO");
        Map<String, GraphEntity> resolved =
            this.archiveEntities(selected.path(), List.of(from, to));
        GraphEntity source = resolved.get(from) != null ? resolved.get(from) : null;
        GraphEntity target = resolved.get(to) != null ? resolved.get(to) : null;
        if (source == null) {
          additions.addEntity(GraphEntity.builder("node:" + this.stableId(from))
              .type("ENTITY").label(from).tag("stdio-local")
              .attribute("createdAt", Instant.now().toString()).build());
          source = GraphEntity.builder("node:" + this.stableId(from))
              .type("ENTITY").label(from).tag("stdio-local")
              .attribute("createdAt", Instant.now().toString()).build();
        }
        if (target == null) {
          additions.addEntity(GraphEntity.builder("node:" + this.stableId(to))
              .type("ENTITY").label(to).tag("stdio-local")
              .attribute("createdAt", Instant.now().toString()).build());
          target = GraphEntity.builder("node:" + this.stableId(to))
              .type("ENTITY").label(to).tag("stdio-local")
              .attribute("createdAt", Instant.now().toString()).build();
        }
        String weightText = this.text(params, "weight");
        double weight = 1.0;
        if (weightText != null) {
          try {
            weight = Double.parseDouble(weightText);
          } catch (NumberFormatException ignored) {
            return ToolResult.error("weight must be a number");
          }
        }
        String relationId = this.firstNonBlank(
            this.text(params, "id"), type + ":" + this.stableId(from) + ":" + this.stableId(to));
        additions.addRelation(new SimpleGraphRelation(
            relationId, source.id(), target.id(), type, weight, 1.0, true,
            Set.of("stdio-local"), null, Instant.now(), Map.of("createdAt", Instant.now().toString())));
      }
      case "delete_node" -> {
        String nodeId = this.text(params, "node_id");
        if (nodeId == null || nodeId.isBlank()) {
          return ToolResult.error("node_id is required for delete_node");
        }
        deleteEntityIds.add(nodeId);
      }
      case "delete_edge" -> {
        String edgeId = this.text(params, "edge_id");
        if (edgeId == null || edgeId.isBlank()) {
          return ToolResult.error("edge_id is required for delete_edge");
        }
        deleteRelationIds.add(edgeId);
      }
      default -> {
        return ToolResult.error("Unsupported mutation action: " + action);
      }
    }

    return this.withGraphWriteLock(
        selected.path(),
        () -> {
          final UnifiedGraph finalAdditions = additions;
          int entities;
          int relations;
          int removedEntities = 0;
          int removedRelations = 0;
          if (this.compactArchive(selected.path())) {
            UnifiedGraphArchiveEditor.Result edited = UnifiedGraphArchiveEditor.rewrite(
                selected.path(),
                selected.path(),
                finalAdditions,
                record -> !deleteEntityIds.contains(record.entity().id()),
                link -> !deleteRelationIds.contains(link.id()),
                Map.of("updatedAt", Instant.now().toString(),
                    "learning.reasoningStale", true, "learning.kgeStale", true));
            entities = edited.entities();
            relations = edited.relations();
            removedEntities = edited.removedEntities();
            removedRelations = edited.removedRelations();
          } else {
            UnifiedGraph graph = UnifiedGraph.load(selected.path());
            for (GraphEntity entity : finalAdditions.entities()) {
              graph.removeEntityById(entity.id());
              graph.addEntity(entity);
            }
            for (GraphRelation relation : finalAdditions.relations()) {
              graph.removeRelationById(relation.id());
              graph.addRelation(relation);
            }
            removedEntities = 0;
            for (String id : deleteEntityIds) {
              if (graph.removeEntityById(id) != null) {
                removedEntities++;
              }
            }
            for (String id : deleteRelationIds) {
              if (graph.removeRelationById(id) != null) {
                removedRelations++;
              }
            }
            graph.meta("learning.reasoningStale", true).meta("learning.kgeStale", true);
            this.saveAtomic(graph, selected.path());
            entities = graph.entityCount();
            relations = graph.relationCount();
          }
          long version = LOCAL_KB_VERSION.incrementAndGet();
          ObjectNode result = this.localEnvelope(selected);
          ((ObjectNode) result.path("meta")).put("stale", true);
          result.put("status", "OK");
          result.put("action", action);
          result.put("entities", entities);
          result.put("relations", relations);
          result.put("removedEntities", removedEntities);
          result.put("removedRelations", removedRelations);
          result.put("version", version);
          return this.jsonSuccess(
              "knowledge_graph " + action,
              result,
              Map.of("backend", "project-local", "status", "OK", "version", version));
        });
  }

  private static String entityMeta(Map<String, Object> meta, String key, String fallback) {
    Object value = meta.get(key);
    return value != null ? String.valueOf(value) : fallback;
  }

  private static double entityConfidence(Map<String, Object> meta) {
    Object value = meta.get("confidence");
    return value instanceof Number number ? number.doubleValue() : 1.0;
  }

  /**
   * Local OWL 2 RL reasoning snapshot and ontology conformance: the TBox comes from a stored
   * ontology.ttl artifact when present, otherwise it is derived from the graph itself (the same
   * bridge crawls use). owl_reasoning returns class/property/sameAs counts, inferred-type and
   * inferred-relation entailments, and the consistency verdict; ontology_conformance adds the
   * conformanceScore (share of entities consistent with the ontology).
   */
  private ToolResult localOwl(String action, JsonNode params, ToolContext context)
      throws Exception {
    LocalProjectGraphBackend.GraphSelection selection = this.selectGraph(context, params);
    UnifiedGraph graph = selection.graph();
    Optional<OwlOntology> declared = TableMemberOntologyBridge.declaredOntology(graph);
    OwlOntology ontology = declared.orElseGet(() -> TableMemberOntologyBridge.ontologyFromGraph(graph));
    OwlRlResult result = new OwlRlReasoner().reason(graph, ontology);

    ObjectNode json = this.mapper.createObjectNode();
    json.put("backend", "project-local");
    json.put("graphId", graph.graphId());
    json.put("ontologySource", declared.isPresent() ? "declared-artifact" : "derived-from-graph");
    json.put("classCount", ontology.classes().size());
    json.put("objectPropertyCount", ontology.objectProperties().size());
    json.put("dataPropertyCount", ontology.dataProperties().size());
    json.put("sameAsCount", ontology.sameAs().size());
    json.put("inferredTypeCount", result.inferredTypeCount());
    json.put("inferredRelationCount", result.inferredRelations().size());
    json.put("consistent", result.isConsistent());
    ArrayNode inconsistencies = json.putArray("inconsistencies");
    result.inconsistencies().forEach(item -> inconsistencies.add(String.valueOf(item)));

    if ("ontology_conformance".equals(action)) {
      long total = graph.entities().size();
      long conforming = total - result.inferredTypeCandidates().size();
      double score = total == 0 ? 1.0 : (double) conforming / total;
      json.put("conformanceScore", score);
      json.put("totalEntities", total);
      json.put("entitiesNeedingTypeResolution", result.inferredTypeCandidates().size());
    }
    return this.localSuccess(action, json);
  }

  private ToolResult localFactSheetInventory(ToolContext context) throws Exception {
    Path root = this.projectRoot(context.getWorkingDirectory());
    ArrayNode factSheets = this.mapper.createArrayNode();
    ArrayNode warnings = this.mapper.createArrayNode();
    Set<String> seen = new LinkedHashSet<>();

    for (JsonNode candidate :
        new LocalProjectCrawlBackend(this.mapper, this)
            .knowledgeBaseInventory(context.getWorkingDirectory())) {
      if (candidate.isObject()) {
        ObjectNode item = (ObjectNode) candidate.deepCopy();
        String id = item.path("id").asText("");
        if (!id.isBlank()) {
          seen.add(id);
        }

        item.put("inventorySource", "crawl-summary");
        factSheets.add(item);
      }
    }

    Path crawls = root.resolve("data/crawls");
    if (Files.isDirectory(crawls)) {
      List<Path> paths;
      try (Stream<Path> directories = Files.list(crawls)) {
        paths =
            directories
                .<Path>map(pathx -> pathx.resolve("graph.kgraph"))
                .filter(x$0 -> Files.isRegularFile(x$0))
                .sorted()
                .toList();
      }

      for (Path path : paths) {
        String folderId = path.getParent().getFileName().toString();
        if (!seen.contains(folderId)) {
          try {
            UnifiedGraph graph = UnifiedGraph.load(path);
            ObjectNode item = factSheets.addObject();
            String id = this.firstNonBlank(this.stringMeta(graph, "knowledgeBaseId"), folderId);
            item.put("id", id);
            item.put(
                "name",
                this.firstNonBlank(
                    this.stringMeta(graph, "knowledgeBaseName"), graph.graphId(), id));
            item.put("graphId", graph.graphId());
            if (graph.factSheetId() != null) {
              item.put("factSheetId", graph.factSheetId());
            }

            item.put("graphEntityCount", graph.entities().size());
            item.put("graphRelationCount", graph.relations().size());
            item.put("graphPath", path.toString());
            item.put("backend", "project-local");
            item.put("inventorySource", "legacy-graph");
            seen.add(id);
          } catch (Exception var16) {
            warnings
                .addObject()
                .put("id", folderId)
                .put("graphPath", path.toString())
                .put("message", "Skipped unreadable legacy graph: " + this.message(var16));
          }
        }
      }
    }

    ObjectNode result = this.mapper.createObjectNode();
    result.put("backend", "project-local");
    result.put("projectRoot", root.toString());
    result.put("count", factSheets.size());
    result.put("skippedUnreadableGraphs", warnings.size());
    result.set("factSheets", factSheets);
    result.set("warnings", warnings);
    return this.localSuccess("list_fact_sheets", result);
  }

  private ToolResult localGraphInventory(String action, ToolContext context) throws Exception {
    ToolResult bootstrap =
        new LocalProjectCrawlBackend(this.mapper, this).ensureFolderKnowledgeBase(context);
    if (bootstrap.isError()) {
      return bootstrap;
    } else {
      Path root = this.projectRoot(context.getWorkingDirectory());
      Path crawls = root.resolve("data/crawls");
      ArrayNode graphs = this.mapper.createArrayNode();
      if (Files.isDirectory(crawls)) {
        List<Path> paths;
        try (Stream<Path> directories = Files.list(crawls)) {
          paths =
              directories
                  .<Path>map(pathx -> pathx.resolve("graph.kgraph"))
                  .filter(x$0 -> Files.isRegularFile(x$0))
                  .sorted()
                  .toList();
        }

        for (Path path : paths) {
          UnifiedGraph graph = UnifiedGraph.load(path);
          ObjectNode item = graphs.addObject();
          item.put(
              "id",
              this.firstNonBlank(
                  this.stringMeta(graph, "knowledgeBaseId"),
                  path.getParent().getFileName().toString()));
          item.put(
              "name",
              this.firstNonBlank(this.stringMeta(graph, "knowledgeBaseName"), graph.graphId()));
          item.put("graphId", graph.graphId());
          if (graph.factSheetId() != null) {
            item.put("factSheetId", graph.factSheetId());
          }

          item.put("entities", graph.entities().size());
          item.put("relations", graph.relations().size());
          item.put("path", path.toString());
        }
      }

      ObjectNode result = this.mapper.createObjectNode();
      result.put("backend", "project-local");
      result.put("projectRoot", root.toString());
      result.put("count", graphs.size());
      result.set(action.equals("list_fact_sheets") ? "factSheets" : "graphs", graphs);
      return this.localSuccess(action, result);
    }
  }

  private ToolResult localOverview(String action, JsonNode params, ToolContext context)
      throws Exception {
    LocalProjectGraphBackend.GraphSelection selection = this.selectGraph(context, params);
    UnifiedGraph graph = selection.graph();
    ObjectNode result = this.mapper.createObjectNode();
    result.put("backend", "project-local");
    result.put("graphId", graph.graphId());
    if (graph.factSheetId() != null) {
      result.put("factSheetId", graph.factSheetId());
    }

    result.put("entities", graph.entities().size());
    result.put("relations", graph.relations().size());
    result.put(
        "embeddingVectors",
        graph.vectorLayers().values().stream().mapToInt(VectorLayer::size).sum());
    result.put("graphPath", selection.pathDescription());
    ArrayNode types = result.putArray("entityTypes");
    new TreeSet<String>(graph.types()).forEach(types::add);
    Map<String, Integer> predicates = this.predicateCounts(graph);
    ObjectNode predicateJson = result.putObject("predicates");
    predicates.forEach(predicateJson::put);
    result.set("metadata", this.mapper.valueToTree(graph.meta()));
    return this.localSuccess(action, result);
  }

  private ToolResult localNodes(String action, JsonNode params, ToolContext context)
      throws Exception {
    LocalProjectGraphBackend.GraphSelection selection = this.selectGraph(context, params);
    String query =
        this.firstNonBlank(
            this.text(params, "query"),
            this.text(params, "entity_name"),
            this.text(params, "topic"));
    String type = this.text(params, "node_type");
    int limit = Math.max(1, Math.min(500, params.path("limit").asInt(50)));
    List<GraphEntity> entities = new ArrayList<>(selection.graph().entities());
    entities.sort(
        Comparator.comparing(
            entityx -> this.firstNonBlank(entityx.label(), entityx.id()).toLowerCase(Locale.ROOT)));
    ArrayNode nodes = this.mapper.createArrayNode();

    for (GraphEntity entity : entities) {
      if (type == null || type.equalsIgnoreCase(entity.type())) {
        if (query != null) {
          String haystack =
              (entity.id()
                      + "\n"
                      + entity.label()
                      + "\n"
                      + entity.type()
                      + "\n"
                      + entity.attributes())
                  .toLowerCase(Locale.ROOT);
          if (!haystack.contains(query.toLowerCase(Locale.ROOT))) {
            continue;
          }
        }

        nodes.add(this.localNodeJson(entity));
        if (nodes.size() >= limit) {
          break;
        }
      }
    }

    ObjectNode result = this.mapper.createObjectNode();
    result.put("backend", "project-local");
    result.put("count", nodes.size());
    result.put("graphPath", selection.pathDescription());
    result.set("nodes", nodes);
    return this.localSuccess(action, result);
  }

  private ToolResult localNode(String action, JsonNode params, ToolContext context)
      throws Exception {
    LocalProjectGraphBackend.GraphSelection selection = this.selectGraph(context, params);
    String requested =
        this.firstNonBlank(this.text(params, "node_id"), this.text(params, "entity_name"));
    if (requested == null) {
      return ToolResult.error("node_id is required");
    } else {
      GraphEntity entity =
          selection
              .graph()
              .entity(requested)
              .orElseGet(
                  () ->
                      selection.graph().entities().stream()
                          .filter(candidate -> requested.equalsIgnoreCase(candidate.label()))
                          .findFirst()
                          .orElse(null));
      if (entity == null) {
        return ToolResult.error("Project-local graph node not found: " + requested);
      } else {
        ObjectNode result = this.mapper.createObjectNode();
        result.put("backend", "project-local");
        result.set("node", this.localNodeJson(entity));
        result.put("incoming", selection.graph().incoming(entity.id()).size());
        result.put("outgoing", selection.graph().outgoing(entity.id()).size());
        result.put("graphPath", selection.pathDescription());
        return this.localSuccess(action, result);
      }
    }
  }

  private ToolResult localEdges(JsonNode params, ToolContext context) throws Exception {
    LocalProjectGraphBackend.GraphSelection selection = this.selectGraph(context, params);
    String type =
        this.firstNonBlank(this.text(params, "edge_type"), this.text(params, "relationship_type"));
    String node = this.text(params, "node_id");
    String source = this.text(params, "from_node_id");
    String target = this.text(params, "to_node_id");
    int limit = Math.max(1, Math.min(1000, params.path("limit").asInt(100)));
    ArrayNode edges = this.mapper.createArrayNode();

    for (GraphRelation relation : selection.graph().relations()) {
      if ((type == null || type.equalsIgnoreCase(relation.type()))
          && (node == null || node.equals(relation.sourceId()) || node.equals(relation.targetId()))
          && (source == null || source.equals(relation.sourceId()))
          && (target == null || target.equals(relation.targetId()))) {
        ObjectNode edge = edges.addObject();
        edge.put("id", relation.id());
        edge.put("sourceId", relation.sourceId());
        edge.put("targetId", relation.targetId());
        edge.put("type", relation.type());
        edge.set("attributes", this.mapper.valueToTree(relation.attributes()));
        if (edges.size() >= limit) {
          break;
        }
      }
    }

    ObjectNode result = this.mapper.createObjectNode();
    result.put("backend", "project-local");
    result.put("count", edges.size());
    result.put("graphPath", selection.pathDescription());
    result.set("edges", edges);
    return this.localSuccess("list_edges", result);
  }

  private ToolResult localPredicates(JsonNode params, ToolContext context) throws Exception {
    LocalProjectGraphBackend.GraphSelection selection = this.selectGraph(context, params);
    ObjectNode result = this.mapper.createObjectNode();
    result.put("backend", "project-local");
    result.put("graphPath", selection.pathDescription());
    ObjectNode predicates = result.putObject("predicates");
    this.predicateCounts(selection.graph()).forEach(predicates::put);
    result.put("count", predicates.size());
    return this.localSuccess("list_predicates", result);
  }

  private ToolResult localReasoning(String action, JsonNode params, ToolContext context)
      throws Exception {
    ObjectNode query =
        params.isObject() ? (ObjectNode) params.deepCopy() : this.mapper.createObjectNode();
    if ("shortest_path".equals(action)) {
      query.put("operation", "PATH");
      query.put(
          "entityId",
          this.firstNonBlank(this.text(params, "from_node_id"), this.text(params, "node_id")));
      query.put("targetId", this.text(params, "to_node_id"));
    } else {
      query.put("operation", "NEIGHBORS");
      query.put(
          "entityId",
          this.firstNonBlank(
              this.text(params, "node_id"),
              this.text(params, "source_id"),
              this.text(params, "document_id")));
      query.put("maxDepth", Math.max(1, params.path("depth").asInt(1)));
      if ("ancestors".equals(action)) {
        query.put("direction", "INCOMING");
      }
    }

    JsonNode result = this.reasoningQuery(query, context);
    return this.localSuccess(action, result);
  }

  private ObjectNode localNodeJson(GraphEntity entity) {
    ObjectNode node = this.mapper.createObjectNode();
    node.put("id", entity.id());
    node.put("type", entity.type());
    node.put("label", entity.label());
    node.set("tags", this.mapper.valueToTree(entity.tags()));
    node.set("attributes", this.mapper.valueToTree(entity.attributes()));
    return node;
  }

  private Map<String, Integer> predicateCounts(UnifiedGraph graph) {
    Map<String, Integer> predicates = new TreeMap<>();

    for (GraphRelation relation : graph.relations()) {
      predicates.merge(relation.type(), 1, Integer::sum);
    }

    return predicates;
  }

  private ToolResult localSuccess(String action, JsonNode result) throws Exception {
    return ToolResult.success(
        "knowledge_graph." + action,
        this.mapper.writerWithDefaultPrettyPrinter().writeValueAsString(result),
        Map.of("backend", "project-local", "action", action));
  }

  public ToolResult executeOfflineTool(String toolId, JsonNode params, ToolContext context) {
    try {
      return switch (toolId) {
        case "graph_search" -> this.offlineGraphSearch(params, context);
        case "graph_aggregate" -> this.offlineAggregate(params, context);
        case "graph_forecast" -> this.offlineForecast(params, context);
        case "graph_centrality" -> this.offlineCentrality(params, context);
        case "ask_graph_query" -> this.offlineQuery(params, context);
        case "ask_graph_verify" -> this.offlineVerify(params, context);
        case "ask_graph_assert" -> this.offlineAssert(params, context);
        case "ask_graph_retract" -> this.offlineRetract(params, context);
        case "ask_graph_subscribe" -> this.offlineSubscribe(params, context);
        case "ask_graph_mebn" -> this.offlineMebn(params, context);
        case "ask_graph_explain" -> this.offlineExplain(params, context);
        case "ask_graph_explain_fused" -> this.offlineFused(params, context);
        case "ask_graph_synthesize" -> this.offlineSynthesize(params, context);
        case "ask_graph_claim" -> this.offlineClaim(params, context);
        case "graph_bayes" -> this.offlineBayes(params, context);
        case "graph_simulate" -> this.offlineSimulate(params, context);
        case "process_mining" -> this.offlineProcessMining(params, context);
        default -> ToolResult.error("No project-local implementation for tool: " + toolId);
      };
    } catch (Exception var6) {
      return ToolResult.error(toolId + " local error: " + this.message(var6));
    }
  }

  private ToolResult offlineGraphSearch(JsonNode params, ToolContext context) throws Exception {
    LocalProjectGraphBackend.GraphSelection selection = this.selectGraph(context, params);
    UnifiedGraph graph = selection.graph();
    String query = this.text(params, "query");
    if (query == null) {
      return ToolResult.error("query is required");
    }

    String searchType = normalizeGraphEnum(params.path("search_type").asText("LOCAL"));
    if (!Set.of("LOCAL", "HYBRID", "GLOBAL").contains(searchType)) {
      return ToolResult.error("search_type must be LOCAL, HYBRID, or GLOBAL");
    }
    String codeProjectId =
        this.firstNonBlank(
            this.text(params, "code_project_id"), this.text(params, "codeProjectId"));
    String entityType =
        this.firstNonBlank(this.text(params, "entity_type"), this.text(params, "entityType"));
    int limit = Math.max(1, Math.min(500, params.path("max_results").asInt(5)));
    int expansionDepth = switch (searchType) {
      case "HYBRID" -> 1;
      case "GLOBAL" -> 2;
      default -> 0;
    };

    // Use the same retrieval-only lexical + stored entity-prior resolver as graph_reasoning_query instead of
    // requiring the complete natural-language query to occur as one literal substring.
    // Rank within the requested scope, not a globally truncated candidate list.
    UnifiedGraph searchScope = new UnifiedGraph();
    for (GraphEntity entity : graph.entities()) {
      if (matchesGraphSearchScope(entity, codeProjectId, entityType)
          && matchesGraphSearchTerms(entity, query)) searchScope.addEntity(entity);
    }
    int candidateLimit = Math.max(limit, limit * (expansionDepth + 2));
    GraphQueryEngine.Result ranked =
        new GraphQueryEngine()
            .query(
                searchScope,
                new Query(
                    Intent.SEARCH,
                    null,
                    null,
                    Direction.BOTH,
                    List.of(),
                    null,
                    candidateLimit,
                    null,
                    null,
                    query));

    int seedLimit = expansionDepth == 0 ? limit : Math.max(1, limit / (expansionDepth + 1));
    Map<String, Double> scores = new LinkedHashMap<>();
    Map<String, Integer> hops = new LinkedHashMap<>();
    Deque<String> frontier = new ArrayDeque<>();
    ranked.entities().stream()
        .filter(view ->
            graph.entity(view.id())
                .filter(entity -> this.matchesGraphSearchScope(entity, codeProjectId, entityType))
                .filter(entity -> this.matchesGraphSearchTerms(entity, query))
                .isPresent())
        .limit(seedLimit)
        .forEach(
            view -> {
              scores.put(view.id(), view.score());
              hops.put(view.id(), 0);
              frontier.addLast(view.id());
            });

    while (!frontier.isEmpty() && scores.size() < limit) {
      String current = frontier.removeFirst();
      int hop = hops.getOrDefault(current, 0);
      if (hop >= expansionDepth) {
        continue;
      }
      for (GraphRelation relation : graph.relationsOf(current)) {
        String neighborId =
            relation.sourceId().equals(current) ? relation.targetId() : relation.sourceId();
        GraphEntity neighbor = graph.entity(neighborId).orElse(null);
        if (neighbor == null
            || scores.containsKey(neighborId)
            || !this.matchesGraphSearchScope(neighbor, codeProjectId, entityType)) {
          continue;
        }
        scores.put(neighborId, scores.getOrDefault(current, 1.0) * 0.5);
        hops.put(neighborId, hop + 1);
        frontier.addLast(neighborId);
        if (scores.size() >= limit) {
          break;
        }
      }
    }

    ArrayNode entities = this.mapper.createArrayNode();
    scores.forEach(
        (id, score) ->
            graph.entity(id)
                .ifPresent(
                    entity -> {
                      ObjectNode row = entities.addObject();
                      row.put("id", entity.id());
                      row.put("name", this.firstNonBlank(entity.label(), entity.id()));
                      row.put("type", entity.type());
                      row.put(
                          "description",
                          this.firstNonBlank(entity.stringAttribute("description"), ""));
                      row.put("score", score);
                      row.put("hop", hops.getOrDefault(id, 0));
                      row.put("matchKind", hops.getOrDefault(id, 0) == 0 ? "ranked" : "neighbor");
                      row.set("attributes", this.mapper.valueToTree(entity.attributes()));
                    }));

    int relationLimit = Math.min(2000, limit * (expansionDepth + 4));
    Map<String, GraphRelation> related = new LinkedHashMap<>();
    for (String id : scores.keySet()) {
      for (GraphRelation relation : graph.relationsOf(id)) {
        related.putIfAbsent(relation.id(), relation);
      }
    }
    List<GraphRelation> rankedRelations =
        related.values().stream()
            .sorted(
                Comparator.comparingDouble(
                        (GraphRelation relation) ->
                            Math.max(
                                scores.getOrDefault(relation.sourceId(), 0.0),
                                scores.getOrDefault(relation.targetId(), 0.0)))
                    .reversed()
                    .thenComparing(Comparator.comparingDouble(GraphRelation::confidence).reversed())
                    .thenComparing(GraphRelation::id))
            .limit(relationLimit)
            .toList();
    ArrayNode relations = this.mapper.createArrayNode();
    rankedRelations.forEach(
        relation -> {
          ObjectNode row = relations.addObject();
          row.put("id", relation.id());
          row.put("source", relation.sourceId());
          row.put("sourceName", this.graphEntityLabel(graph, relation.sourceId()));
          row.put("target", relation.targetId());
          row.put("targetName", this.graphEntityLabel(graph, relation.targetId()));
          row.put("type", relation.type());
          row.put("confidence", relation.confidence());
          if (!authoritativeRelation(relation)) {
            row.put("description", "Heuristic source-pattern call; not compiler-verified");
          }
        });

    ObjectNode result = this.localEnvelope(selection);
    result.put("query", query);
    result.put("searchType", searchType);
    result.put("expansionDepth", expansionDepth);
    JsonNode rankingData = this.mapper.valueToTree(ranked.data());
    result.put("ranking", rankingData.path("scoreBasis").asText("lexical + stored entity prior"));
    result.put("inferenceInvoked", rankingData.path("inferenceInvoked").asBoolean(false));
    result.set("data", rankingData);
    if (codeProjectId != null) {
      result.put("codeProjectId", codeProjectId);
    }
    if (entityType != null) {
      result.put("entityType", entityType);
    }
    if ("GLOBAL".equals(searchType)) {
      result.put(
          "modeNote",
          "Folder-local GLOBAL search expands ranked seeds two hops; generated community summaries require a configured managed GraphRAG service.");
    }
    result.set("entities", entities);
    result.set("relationships", relations);
    result.putArray("communities");
    result.put(
        "summary",
        entities.size() + " ranked entities and " + relations.size() + " related edges");
    return this.jsonSuccess(
        "graph_search: " + query,
        result,
        Map.of(
            "backend",
            "project-local",
            "searchType",
            searchType,
            "entityCount",
            entities.size(),
            "relationshipCount",
            relations.size()));
  }

  private boolean matchesGraphSearchScope(
      GraphEntity entity, String codeProjectId, String entityType) {
    if (codeProjectId != null
        && !codeProjectId.equals(this.string(entity.attributes().get("codeProjectId")))) {
      return false;
    }
    return entityType == null || entity.hasTypeMembership(entityType);
  }

  private boolean matchesGraphSearchTerms(GraphEntity entity, String query) {
    String normalized = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
    if (normalized.isBlank()) {
      return false;
    }
    if (entity.id().equalsIgnoreCase(normalized)
        || (normalized.contains(":") && entity.id().toLowerCase(Locale.ROOT).contains(normalized))) {
      return true;
    }
    Set<String> stopWords =
        Set.of("a", "an", "and", "do", "does", "find", "how", "is", "me", "of", "show", "the", "what", "where", "who");
    List<String> terms =
        Stream.of(normalized.split("[^a-z0-9_]+"))
            .filter(term -> !term.isBlank() && !stopWords.contains(term))
            .distinct()
            .toList();
    if (terms.isEmpty()) {
      return false;
    }
    String attributeValues = entity.attributes().values().toString().toLowerCase(Locale.ROOT);
    String searchable =
        (entity.label()
                + "\n"
                + entity.type()
                + "\n"
                + attributeValues)
            .toLowerCase(Locale.ROOT);
    long matchedTerms = terms.stream().filter(searchable::contains).count();
    return matchedTerms > 0;
  }

  private String graphEntityLabel(UnifiedGraph graph, String entityId) {
    return graph.entity(entityId)
        .map(entity -> this.firstNonBlank(entity.label(), entity.id()))
        .orElse(entityId);
  }

  private ToolResult offlineAggregate(JsonNode params, ToolContext context) throws Exception {
    LocalProjectGraphBackend.GraphSelection selection = this.selectGraph(context, params);
    String rootType = this.text(params, "root_type");
    if (rootType == null) {
      return ToolResult.error("root_type is required");
    } else {
      String attribute = this.text(params, "numeric_attribute");
      String aggregation = params.path("aggregation").asText("COUNT").toUpperCase(Locale.ROOT);
      List<GraphEntity> matches =
          selection.graph().entities().stream()
              .filter(entityx -> entityx.hasTypeMembership(rootType))
              .toList();
      List<Double> values = new ArrayList<>();
      Map<String, List<Double>> byType = new LinkedHashMap<>();
      int skipped = 0;

      for (GraphEntity entity : matches) {
        Double value =
            "COUNT".equals(aggregation) ? 1.0 : this.number(entity.attributes().get(attribute));
        if (value == null) {
          skipped++;
        } else {
          values.add(value);
          byType.computeIfAbsent(entity.type(), ignored -> new ArrayList<>()).add(value);
        }
      }

      double total = this.aggregate(values, aggregation);
      ObjectNode result = this.localEnvelope(selection);
      result.put("total", total);
      result.put("matchedNodeCount", values.size());
      result.put("skippedNodeCount", skipped);
      ArrayNode contributors = result.putArray("contributingNodeIds");
      matches.forEach(entityx -> contributors.add(entityx.id()));
      ObjectNode breakdown = result.putObject("perSubtypeBreakdown");
      if (params.path("group_by_subtype").asBoolean(false)) {
        byType.forEach(
            (type, typeValues) ->
                breakdown.put(type, this.aggregate((List<Double>) typeValues, aggregation)));
      }

      return this.jsonSuccess(
          "graph_aggregate: " + aggregation + "(" + rootType + ")",
          result,
          Map.of("backend", "project-local", "total", total, "matchedNodeCount", values.size()));
    }
  }

  private ToolResult offlineForecast(JsonNode params, ToolContext context) throws Exception {
    LocalProjectGraphBackend.GraphSelection selection = this.selectGraph(context, params);
    String rootType = this.text(params, "root_type");
    if (rootType == null) {
      return ToolResult.error("root_type is required");
    } else {
      String attribute = this.text(params, "numeric_attribute");
      String aggregation = params.path("aggregation").asText("SUM").toUpperCase(Locale.ROOT);
      String bucketSize = params.path("bucket_size").asText("QUARTER").toUpperCase(Locale.ROOT);
      int horizon = Math.max(1, Math.min(100, params.path("horizon_buckets").asInt(4)));
      Map<String, List<Double>> buckets = new TreeMap<>();
      int matched = 0;

      for (GraphEntity entity : selection.graph().entities()) {
        if (entity.hasTypeMembership(rootType)) {
          Double value =
              "COUNT".equals(aggregation) ? 1.0 : this.number(entity.attributes().get(attribute));
          Instant instant = this.entityInstant(entity);
          if (value != null && instant != null) {
            matched++;
            buckets
                .computeIfAbsent(this.timeBucket(instant, bucketSize), ignored -> new ArrayList<>())
                .add(value);
          }
        }
      }

      ObjectNode result = this.localEnvelope(selection);
      ArrayNode history = result.putArray("historicalSeries");
      List<Double> series = new ArrayList<>();
      buckets.forEach(
          (bucket, values) -> {
            double value = this.aggregate((List<Double>) values, aggregation);
            series.add(value);
            ObjectNode pointx = history.addObject();
            pointx.put("bucket", bucket);
            pointx.put("value", value);
            pointx.put("nodeCount", values.size());
          });
      result.put("historicalBucketCount", history.size());
      result.put("totalMatchedNodes", matched);
      boolean insufficient = series.size() < 2;
      result.put("insufficientHistory", insufficient);
      result.put("missingTemporalData", series.isEmpty());
      result.put("projectionMethod", "LOCAL_LINEAR_TREND");
      result.put(
          "caveat",
          "Project-local linear extrapolation is an estimate based only on indexed graph history.");
      ArrayNode projected = result.putArray("projectedBuckets");
      if (!insufficient) {
        double slope = this.linearSlope(series);
        double last = series.get(series.size() - 1);

        for (int i = 1; i <= horizon; i++) {
          ObjectNode point = projected.addObject();
          point.put("bucket", "future+" + i + " " + bucketSize.toLowerCase(Locale.ROOT));
          point.put("value", last + slope * i);
          point.put("estimate", true);
        }
      }

      return this.jsonSuccess(
          "graph_forecast: " + rootType,
          result,
          Map.of(
              "backend",
              "project-local",
              "historicalBucketCount",
              history.size(),
              "insufficientHistory",
              insufficient));
    }
  }

  private ToolResult offlineCentrality(JsonNode params, ToolContext context) throws Exception {
    LocalProjectGraphBackend.GraphSelection selection = this.selectGraph(context, params);
    UnifiedGraph graph = selection.graph();
    String algorithm = params.path("algorithm").asText("degree").toLowerCase(Locale.ROOT);
    String degreeType = params.path("degree_type").asText("total").toLowerCase(Locale.ROOT);
    int topK = Math.max(1, Math.min(500, params.path("top_k").asInt(20)));

    Map<String, Double> scores =
        switch (algorithm) {
          case "pagerank" -> this.pageRank(graph);
          case "betweenness" -> this.betweenness(graph);
          default -> this.degree(graph, degreeType);
        };
    ArrayNode ranked = this.mapper.createArrayNode();
    scores.entrySet().stream()
        .sorted(Entry.<String, Double>comparingByValue().reversed())
        .limit(topK)
        .forEach(
            entry -> {
              ObjectNode row = ranked.addObject();
              row.put("nodeId", entry.getKey());
              row.put("score", entry.getValue());
            });
    ObjectNode result = this.localEnvelope(selection);
    result.put("algorithm", algorithm);
    result.put("totalNodes", scores.size());
    result.set("ranked", ranked);
    return this.jsonSuccess(
        "graph_centrality: " + algorithm,
        result,
        Map.of("backend", "project-local", "algorithm", algorithm, "count", ranked.size()));
  }

  private record ConfidenceBinding(Map<String, String> variables, double confidence, boolean heuristic) {}

  private static void rejectHistoricalQuery(JsonNode params) {
    if (params.hasNonNull("asOf")) {
      throw new IllegalArgumentException("asOf is not supported by the project-local graph; "
          + "deleted/overwritten history is not retained. Use validAt to filter retained valid-time facts, "
          + "or a temporal-capable managed backend for historical reconstruction.");
    }
  }

  private GraphSelection selectEvidenceGraph(ToolContext context, JsonNode params) throws Exception {
    Instant validAt = null;
    if (params.hasNonNull("validAt")) {
      if (!params.path("validAt").isTextual()) throw new IllegalArgumentException("validAt must be an ISO-8601 instant");
      try {
        validAt = Instant.parse(params.path("validAt").asText());
      } catch (java.time.format.DateTimeParseException invalid) {
        throw new IllegalArgumentException("validAt must be an ISO-8601 instant", invalid);
      }
    }
    GraphSelection selected = selectGraph(context, params);
    if (validAt == null) return selected;
    UnifiedGraph filtered = UnifiedGraph.of(
        ai.kompile.graph.reasoning.model.TemporalView.asOf(selected.graph(), validAt));
    filtered.graphId(selected.graph().graphId());
    selected.graph().meta().forEach(filtered::meta);
    // Retain only the graph-resident ontology artifact for the filtered ABox. Learned model
    // artifacts do not describe this temporal view and must not leak into verification.
    byte[] ontologyArtifact = selected.graph().artifact(TableMemberOntologyBridge.ONTOLOGY_ARTIFACT);
    if (ontologyArtifact != null) {
      filtered.putArtifact(TableMemberOntologyBridge.ONTOLOGY_ARTIFACT, ontologyArtifact);
    }
    filtered.meta("query.validAt", validAt.toString());
    return new GraphSelection(filtered, selected.path());
  }

  private static double confidenceThreshold(JsonNode params, double fallback) {
    JsonNode value = params.get("minConfidence");
    if (value == null) return fallback;
    if (!value.isNumber() || !Double.isFinite(value.asDouble())
        || value.asDouble() < 0.0 || value.asDouble() > 1.0) {
      throw new IllegalArgumentException("minConfidence must be a finite number in [0,1]");
    }
    return value.asDouble();
  }

  private boolean authoritativeRelation(GraphRelation relation) {
    // A source-pattern observation is not compiler-verified evidence of a call.
    return !("CALLS".equals(relation.type())
        && "local-code-index".equals(relation.attributes().get("_kompileProjectionOwner")));
  }

  private static boolean staleCodeLearning(UnifiedGraph graph, String receiptPrefix) {
    String flag = "codeKgeGeneration.".equals(receiptPrefix) ? "learning.kgeStale" : "learning.reasoningStale";
    if (Boolean.TRUE.equals(graph.meta().get(flag))) return true;
    for (Map.Entry<String, Object> entry : graph.meta().entrySet()) {
      if (!entry.getKey().startsWith("codeIndexGeneration.")) continue;
      String project = entry.getKey().substring("codeIndexGeneration.".length());
      Object receipt = graph.meta().get(receiptPrefix + project);
      if (entry.getValue() == null || !entry.getValue().equals(receipt)) return true;
    }
    return false;
  }

  private static void stampCodeGenerations(UnifiedGraph graph, String receiptPrefix) {
    graph.meta("codeKgeGeneration.".equals(receiptPrefix) ? "learning.kgeStale" : "learning.reasoningStale", false);
    new LinkedHashMap<>(graph.meta()).forEach((key, value) -> {
      if (key.startsWith("codeIndexGeneration.")) {
        graph.meta(receiptPrefix + key.substring("codeIndexGeneration.".length()), value);
      }
    });
  }

  private ToolResult offlineVerify(JsonNode params, ToolContext context) throws Exception {
    rejectHistoricalQuery(params);
    double threshold = confidenceThreshold(params, 0.5);
    String atomText = this.text(params, "atom");
    if (atomText == null) {
      return ToolResult.error("atom is required");
    } else {
      LocalProjectGraphBackend.GraphSelection selection = this.selectEvidenceGraph(context, params);
      LocalProjectGraphBackend.Atom atom = this.atom(atomText);
      UnifiedGraph graph = selection.graph();
      List<GraphRelation> evidence = this.matchingRelations(graph, atom, Map.of());
      boolean entitiesKnown =
          atom.args().stream().allMatch(arg -> this.resolveEntity(graph, arg) != null);
      double confidence =
          evidence.stream().mapToDouble(GraphRelation::confidence).filter(Double::isFinite).max().orElse(0.0);
      List<GraphRelation> authoritative = evidence.stream().filter(this::authoritativeRelation).toList();
      double authoritativeConfidence = authoritative.stream().mapToDouble(GraphRelation::confidence)
          .filter(Double::isFinite).max().orElse(0.0);

      Optional<OwlOntology> declaredOntology = TableMemberOntologyBridge.declaredOntology(graph);
      OwlEvidence owlEvidence = declaredOntology.isPresent()
          ? this.owlEvidence(graph, atom, declaredOntology.get())
          : OwlEvidence.empty();
      double inferredConfidence = owlEvidence.confidence();
      double supportConfidence = Math.max(authoritativeConfidence, inferredConfidence);
      String verdict = authoritative.isEmpty() && !owlEvidence.supported()
          ? "UNKNOWN"
          : supportConfidence <= 0.0
              ? "REFUTED"
              : supportConfidence >= threshold ? "SUPPORTED" : "UNKNOWN";
      // Learned PSL/MEBN posteriors are not evidence for an OWL entailment and are deliberately
      // excluded whenever a declared ontology was evaluated.
      Double posterior = declaredOntology.isPresent() ? null : this.learnedPosterior(graph, atom);
      ObjectNode result = this.localEnvelope(selection);
      result.put("atom", atomText);
      result.put("verdict", verdict);
      result.put("confidence", supportConfidence);
      result.put("recordedEvidenceConfidence", confidence);
      result.put("authoritativeConfidence", authoritativeConfidence);
      result.put("inferredConfidence", inferredConfidence);
      result.put("confidenceBasis", declaredOntology.isPresent()
          ? "OWL RL support confidence from direct facts or minimum supporting fact confidence"
          : "recorded edge confidence; heuristic calls are not verified");
      result.put("calibratedConfidence", posterior != null ? posterior : supportConfidence);
      if (posterior != null) {
        result.put("learnedPosterior", posterior);
      }
      result.put("entityKnown", entitiesKnown);
      result.put("openWorld", true);
      if ("UNKNOWN".equals(verdict)) {
        result.put("unknownReason", !entitiesKnown ? "entity-not-in-graph"
            : evidence.isEmpty() && !owlEvidence.supported() ? "no-evidence"
            : evidence.stream().noneMatch(this::authoritativeRelation) && !owlEvidence.supported()
                ? "heuristic-code-evidence"
                : owlEvidence.attempted() && !owlEvidence.supported()
                    ? "no-owl-entailment" : "below-confidence-threshold");
      }
      ArrayNode evidenceAtoms = result.putArray("evidenceAtoms");
      evidence.forEach(relation -> evidenceAtoms.add(this.relationAtom(relation)));
      owlEvidence.evidenceAtoms().forEach(evidenceAtoms::add);
      result.put("evidenceCount", evidence.size() + owlEvidence.evidenceAtoms().size());
      result.put("derivationDepth", Math.max(evidence.isEmpty() ? 0 : 1, owlEvidence.depth()));
      ArrayNode supportingFactKeys = result.putArray("supportingFactKeys");
      owlEvidence.supportingFactKeys().forEach(supportingFactKeys::add);
      ArrayNode activatedRules = result.putArray("activatedRules");
      owlEvidence.activatedRules().forEach(activatedRules::add);
      result.put("inferenceEngine", declaredOntology.isPresent() ? "OwlRlReasoner" : "direct-evidence");
      result.put("owlEntailment", owlEvidence.supported());
      if (declaredOntology.isPresent()) {
        result.put("ontologySource", "declared-artifact");
      }
      return this.jsonSuccess(
          "ask_graph_verify: " + atomText,
          result,
          Map.of("backend", "project-local", "verdict", verdict,
              "confidence", posterior != null ? posterior : supportConfidence));
    }
  }

  private ToolResult offlineQuery(JsonNode params, ToolContext context) throws Exception {
    rejectHistoricalQuery(params);
    double threshold = confidenceThreshold(params, 0.3);
    int maxWork = 100_000;
    if (params.hasNonNull("maxWork")) {
      JsonNode budget = params.get("maxWork");
      if (!budget.isIntegralNumber() || !budget.canConvertToInt()
          || budget.intValue() < 1 || budget.intValue() > 1_000_000) {
        throw new IllegalArgumentException("maxWork must be an integer in [1,1000000]");
      }
      maxWork = budget.intValue();
    }
    int work = 0;
    JsonNode conjuncts = params.path("conjuncts");
    if (conjuncts.isArray() && conjuncts.size() > 64) {
      return ToolResult.error("Local graph queries support at most 64 conjuncts per request");
    }
    if (conjuncts.isArray() && !conjuncts.isEmpty()) {
      LocalProjectGraphBackend.GraphSelection selection = this.selectEvidenceGraph(context, params);
      List<ConfidenceBinding> bindings = new ArrayList<>();
      bindings.add(new ConfidenceBinding(Map.of(), 1.0, false));

      for (JsonNode conjunct : conjuncts) {
        List<String> args = new ArrayList<>();
        conjunct.path("args").forEach(arg -> args.add(arg.asText()));
        LocalProjectGraphBackend.Atom pattern =
            new LocalProjectGraphBackend.Atom(conjunct.path("predicate").asText(""), args);
        if (args.isEmpty() || args.size() > 2 || pattern.predicate().isBlank()) {
          return ToolResult.error("Each conjunct requires a predicate and one or two arguments");
        }
        List<ConfidenceBinding> next = new ArrayList<>();

        for (ConfidenceBinding existing : bindings) {
          for (GraphRelation relation : selection.graph().relations()) {
            if (++work > maxWork) {
              return ToolResult.error("Local graph query exceeded maxWork=" + maxWork
                  + " relation examinations; no partial bindings returned. Narrow the conjuncts "
                  + "or increase maxWork explicitly (maximum 1000000).");
            }
            if (!relation.type().equals(pattern.predicate())
                || !this.matchesArgument(selection.graph(), pattern.args().get(0), relation.sourceId(), existing.variables())
                || (pattern.args().size() == 2 && !this.matchesArgument(selection.graph(),
                    pattern.args().get(1), relation.targetId(), existing.variables()))) continue;
            if (!Double.isFinite(relation.confidence()) || relation.confidence() <= 0.0) continue;
            double confidence = Math.max(0.0, existing.confidence() + relation.confidence() - 1.0);
            if (confidence < threshold || confidence <= 0.0) continue;
            Map<String, String> joined = this.bind(pattern, relation, existing.variables());
            if (joined != null) {
              next.add(new ConfidenceBinding(joined, confidence,
                  existing.heuristic() || !authoritativeRelation(relation)));
            }
          }
        }

        bindings = next;
        if (next.isEmpty()) {
          break;
        }
      }

      int max = Math.max(1, Math.min(500, params.path("maxResults").asInt(50)));
      boolean truncated = bindings.size() > max;
      ObjectNode result = this.localEnvelope(selection);
      ArrayNode rows = result.putArray("bindings");
      bindings.stream()
          .limit(max)
          .forEach(
              binding -> {
                ObjectNode row = rows.addObject();
                row.put("confidence", binding.confidence());
                row.put("heuristicEvidence", binding.heuristic());
                ObjectNode variables = row.putObject("variables");
                binding.variables().forEach((key, value) -> variables.put(this.stripVariable(key), value));
                row.set("displayVariables", variables.deepCopy());
              });
      result.put("total", rows.size());
      result.put("work", work);
      result.put("maxWork", maxWork);
      result.put("confidenceBasis", "Lukasiewicz conjunction of recorded edge confidences; "
          + "rows marked heuristicEvidence are source-pattern matches, not verified calls");
      result.put("truncated", truncated);
      return this.jsonSuccess(
          "ask_graph_query: " + rows.size() + " binding(s)",
          result,
          Map.of("backend", "project-local", "total", rows.size(), "truncated", truncated));
    } else {
      return ToolResult.error("conjuncts array is required and must not be empty");
    }
  }

  private ToolResult offlineAssert(JsonNode params, ToolContext context) throws Exception {
    String atomText = this.text(params, "atom");
    if (atomText == null) {
      return ToolResult.error("atom is required");
    } else {
      double value = params.path("value").asDouble(Double.NaN);
      if (!Double.isNaN(value) && !(value < 0.0) && !(value > 1.0)) {
        LocalProjectGraphBackend.GraphSelection selected = this.writableSelection(context, params);
        return this.withGraphWriteLock(
            selected.path(),
            () -> {
              LocalProjectGraphBackend.Atom atom = this.atom(atomText);
              LocalProjectGraphBackend.GraphSelection selection;
              UnifiedGraphMutationJournal.AppendResult journalResult = null;
              if (this.compactArchive(selected.path())) {
                List<GraphEntity> newEntities = new ArrayList<>();
                String targetText =
                    atom.args().size() == 2 ? atom.args().get(1) : "predicate:" + atom.predicate();
                Map<String, GraphEntity> resolved = this.archiveEntities(
                    selected.path(), List.of(atom.args().get(0), targetText));
                GraphEntity source = resolved.get(atom.args().get(0));
                if (source == null) {
                  source = this.assertedEntity(atom.args().get(0));
                  newEntities.add(source);
                }
                GraphEntity target = resolved.get(targetText);
                if (target == null) {
                  target = this.assertedEntity(targetText);
                  newEntities.add(target);
                }
                GraphRelation asserted = this.relationForResolvedAtom(source, target, atom, value, params);
                UnifiedGraphArchive.Link assertedLink = this.archiveLink(asserted);
                UnifiedGraphArchive.Link previous = null;
                if (newEntities.isEmpty()) {
                  try (UnifiedGraphArchive archive = UnifiedGraphArchive.open(selected.path())) {
                    for (UnifiedGraphArchive.Link link : archive.incidentLinks(
                        source.id(), GraphQueryEngine.Direction.OUTGOING, 1_000_000)) {
                      if (link.id().equals(asserted.id())) {
                        previous = link;
                        break;
                      }
                    }
                  }
                }
                journalResult = UnifiedGraphMutationJournal.appendAssertion(
                    selected.path(), newEntities, assertedLink, previous);
                selection = selected;
              } else {
                selection =
                    new LocalProjectGraphBackend.GraphSelection(
                        UnifiedGraph.load(selected.path()), selected.path());
                GraphRelation relation =
                    this.relationForAtom(selection.graph(), atom, value, params);
                selection.graph().removeRelationById(relation.id()).addRelation(relation);
                selection.graph().meta("learning.reasoningStale", true).meta("learning.kgeStale", true);
                this.saveAtomic(selection.graph(), selection.path());
              }
              long version = LOCAL_KB_VERSION.incrementAndGet();
              this.publish(selection.path(), atom.predicate(), "ASSERT", atomText, version);
              ObjectNode result = this.localEnvelope(selection);
              ((ObjectNode) result.path("meta")).put("stale", true);
              result.put("status", "ASSERTED");
              result.put("atom", atomText);
              result.put("value", value);
              result.put("version", version);
              result.put("cascadeTriggered", false);
              if (journalResult != null) {
                result.put("mutationJournalRecords", journalResult.recordCount());
                result.put("compactionRecommended", journalResult.compactionRecommended());
              }
              return this.jsonSuccess(
                  "ask_graph_assert: " + atomText,
                  result,
                  Map.of("backend", "project-local", "status", "ASSERTED", "version", version));
            });
      } else {
        return ToolResult.error("value must be a number in [0,1]");
      }
    }
  }

  /**
   * Look up the learned PSL/MEBN posterior for an atom in the graph's consensusTargets model
   * (reasoning/consensus-targets.bin, written by the crawl-end learning subprocess). Returns null
   * when no trained program is stored or the atom was not a learning target.
   */
  private Double learnedPosterior(UnifiedGraph graph, LocalProjectGraphBackend.Atom atom) {
    if (staleCodeLearning(graph, "codeLearningGeneration.")) return null;
    Object model = graph.model("reasoning/consensus-targets.bin");
    if (!(model instanceof Map<?, ?> targets)) {
      return null;
    }
    if (atom.args().size() == 2) {
      Object value = targets.get(atom.predicate() + "(" + atom.args().get(0) + ","
          + atom.args().get(1) + ")");
      if (value instanceof Number number) {
        return number.doubleValue();
      }
      // Posterior keys store the ground atom; entity-target entries use State(nX) ids, so also
      // try resolving both arguments through their stored entity ids.
      GraphEntity source = this.resolveEntity(graph, atom.args().get(0));
      GraphEntity target = this.resolveEntity(graph, atom.args().get(1));
      if (source != null && target != null) {
        value = targets.get(atom.predicate() + "(" + source.id() + "," + target.id() + ")");
        if (value instanceof Number number) {
          return number.doubleValue();
        }
      }
    }
    return null;
  }

  private ToolResult offlineRetract(JsonNode params, ToolContext context) throws Exception {
    String atomText = this.firstNonBlank(this.text(params, "atomKey"), this.text(params, "atom"));
    if (atomText == null) {
      return ToolResult.error("atomKey is required");
    } else {
      LocalProjectGraphBackend.GraphSelection selected = this.writableSelection(context, params);
      return this.withGraphWriteLock(
          selected.path(),
          () -> {
            LocalProjectGraphBackend.Atom atom = this.atom(atomText);
            LocalProjectGraphBackend.GraphSelection selection;
            int removed;
            UnifiedGraphMutationJournal.AppendResult journalResult = null;
            if (this.compactArchive(selected.path())) {
              List<String> requested = atom.args().size() == 2
                  ? List.of(atom.args().get(0), atom.args().get(1))
                  : List.of(atom.args().get(0));
              Map<String, GraphEntity> resolved = this.archiveEntities(selected.path(), requested);
              GraphEntity source = resolved.get(atom.args().get(0));
              GraphEntity target = atom.args().size() == 2 ? resolved.get(atom.args().get(1)) : null;
              List<UnifiedGraphArchive.Link> matches =
                  source == null || atom.args().size() == 2 && target == null
                      ? List.of()
                      : this.archiveMatches(
                          selected.path(), atom, source.id(), target == null ? null : target.id());
              removed = matches.size();
              if (removed > 0) {
                journalResult = UnifiedGraphMutationJournal.appendRetractions(selected.path(), matches);
              }
              selection = selected;
            } else {
              selection =
                  new LocalProjectGraphBackend.GraphSelection(
                      UnifiedGraph.load(selected.path()), selected.path());
              List<GraphRelation> matches =
                  this.matchingRelations(selection.graph(), atom, Map.of());
              removed = matches.size();
              matches.forEach(relation -> selection.graph().removeRelationById(relation.id()));
              if (removed > 0) selection.graph().meta("learning.reasoningStale", true).meta("learning.kgeStale", true);
              this.saveAtomic(selection.graph(), selection.path());
            }
            long version = LOCAL_KB_VERSION.incrementAndGet();
            this.publish(selection.path(), atom.predicate(), "RETRACT", atomText, version);
            ObjectNode result = this.localEnvelope(selection);
            if (removed > 0) ((ObjectNode) result.path("meta")).put("stale", true);
            result.put("status", removed == 0 ? "NOT_FOUND" : "RETRACTED");
            result.put("mode", params.path("mode").asText("retract"));
            result.put("removed", removed);
            result.putArray("dependentAtomsUnsupported");
            result.putArray("dependentAtomsWeakened");
            result.put("cascadeTriggered", false);
            if (journalResult != null) {
              result.put("mutationJournalRecords", journalResult.recordCount());
              result.put("compactionRecommended", journalResult.compactionRecommended());
            }
            return this.jsonSuccess(
                "ask_graph_retract: " + atomText,
                result,
                Map.of("backend", "project-local", "removed", removed, "version", version));
          });
    }
  }

  private ToolResult offlineSubscribe(JsonNode params, ToolContext context) throws Exception {
    String id = this.text(params, "subscriptionId");
    if (id == null) {
      JsonNode predicates = params.path("predicates");
      if (predicates.isArray() && !predicates.isEmpty()) {
        LocalProjectGraphBackend.GraphSelection selection = this.selectGraph(context, params);
        Set<String> names = new LinkedHashSet<>();
        predicates.forEach(node -> names.add(node.asText()));
        id = UUID.randomUUID().toString();
        LocalProjectGraphBackend.LocalSubscription subscription =
            new LocalProjectGraphBackend.LocalSubscription(
                selection.pathDescription(), names, new CopyOnWriteArrayList<>());
        LOCAL_SUBSCRIPTIONS.put(id, subscription);
        ObjectNode result = this.localEnvelope(selection);
        result.put("subscriptionId", id);
        result.put("nextCursor", 0);
        ArrayNode snapshot = result.putArray("snapshot");
        selection.graph().relations().stream()
            .filter(rel -> this.containsIgnoreCase(names, rel.type()))
            .forEach(rel -> snapshot.add(this.relationAtom(rel)));
        return this.jsonSuccess(
            "ask_graph_subscribe: subscription created",
            result,
            Map.of(
                "backend",
                "project-local",
                "subscriptionId",
                id,
                "nextCursor",
                0,
                "totalMatches",
                snapshot.size()));
      } else {
        return ToolResult.error(
            "predicates array is required on the first call (when subscriptionId is not set)");
      }
    } else {
      LocalProjectGraphBackend.LocalSubscription subscription = LOCAL_SUBSCRIPTIONS.get(id);
      if (subscription == null) {
        return ToolResult.error("Subscription not found or expired: " + id);
      } else {
        int cursor = Math.max(0, params.path("cursor").asInt(0));
        ArrayNode events = this.mapper.createArrayNode();
        subscription.events().stream().skip(cursor).forEach(events::add);
        ObjectNode result = this.mapper.createObjectNode();
        result.put("backend", "project-local");
        result.put("subscriptionId", id);
        result.put("nextCursor", subscription.events().size());
        result.set("events", events);
        return this.jsonSuccess(
            "ask_graph_subscribe: " + events.size() + " event(s)",
            result,
            Map.of(
                "backend",
                "project-local",
                "subscriptionId",
                id,
                "nextCursor",
                subscription.events().size(),
                "eventCount",
                events.size()));
      }
    }
  }

  private ToolResult offlineMebn(JsonNode params, ToolContext context) throws Exception {
    String nodeId = this.text(params, "nodeId");
    if (nodeId == null) {
      return ToolResult.error("nodeId is required");
    } else {
      LocalProjectGraphBackend.GraphSelection selection = this.selectGraph(context, params);
      GraphEntity anchor = this.resolveEntity(selection.graph(), nodeId);
      if (anchor == null) {
        return ToolResult.error("Project-local graph node not found: " + nodeId);
      } else {
        MTheory theory = UnifiedGraphReasoningLifecycle.learnedMTheory(selection.graph());
        if (theory == null) {
          return ToolResult.error(
              "No learned project-local MEBN theory is stored in this graph. Run crawl_documents"
                  + " with reasoningLearning.enabled=true.");
        } else {
          int depth = Math.max(0, Math.min(10, params.path("maxDepth").asInt(3)));
          int max = Math.max(0, Math.min(1000, params.path("maxNodes").asInt(100)));
          Set<String> nodes = neighborhood(selection.graph(), anchor.id(), depth, max);
          MTheory queryTheory = RelationalMTheoryArtifactCodec.restrictToEntityIds(theory, nodes);
          long started = System.nanoTime();
          Map<String, Double> learnedPosteriors =
              new MebnInferenceService().infer(selection.graph(), queryTheory, Map.of());
          ObjectNode result = this.localEnvelope(selection);
          ObjectNode priors = result.putObject("priors");
          ObjectNode posteriors = result.putObject("posteriors");
          ObjectNode titles = result.putObject("variableToTitle");
          ObjectNode meta = result.putObject("variableToMebnMeta");
          learnedPosteriors.entrySet().stream()
              .sorted(Entry.comparingByKey())
              .forEach(
                  entry -> {
                    String variable = entry.getKey();
                    String rvName =
                        variable.contains("(")
                            ? variable.substring(0, variable.indexOf(40))
                            : variable;
                    priors.put(variable, entry.getValue());
                    posteriors.put(variable, entry.getValue());
                    titles.put(variable, variable);
                    ObjectNode item = meta.putObject(variable);
                    item.put("mTheory", queryTheory.getName());
                    item.put("randomVariable", rvName);
                    item.put(
                        "mfragName",
                        queryTheory
                            .findHomeMFrag(rvName)
                            .map(fragment -> fragment.getName())
                            .orElse("unknown"));
                    item.put("nodeRole", "RESIDENT");
                    item.put("learned", true);
                  });
          result.put("mTheory", queryTheory.getName());
          result.put("learnedTheory", true);
          result.put("scopedEntityCount", nodes.size());
          result.put("computationTimeMs", (System.nanoTime() - started) / 1000000L);
          return this.jsonSuccess(
              "ask_graph_mebn: " + nodeId,
              result,
              Map.of(
                  "backend",
                  "project-local",
                  "nodeId",
                  nodeId,
                  "totalVariables",
                  posteriors.size(),
                  "scopedEntityCount",
                  nodes.size(),
                  "learnedTheory",
                  true));
        }
      }
    }
  }

  private ToolResult offlineExplain(JsonNode params, ToolContext context) throws Exception {
    String target = this.firstNonBlank(this.text(params, "atom"), this.text(params, "target"));
    if (target == null) {
      return ToolResult.error("atom is required");
    } else {
      ObjectNode verifyParams = (ObjectNode) params.deepCopy();
      verifyParams.put("atom", target);
      ToolResult verified = this.offlineVerify(verifyParams, context);
      if (verified.isError()) {
        ObjectNode query = (ObjectNode) params.deepCopy();
        query.put("operation", "DESCRIBE");
        query.put("entityId", target);
        JsonNode result = this.reasoningQuery(query, context);
        return this.jsonSuccess(
            "ask_graph_explain: " + target,
            result,
            Map.of("backend", "project-local", "target", target, "inferenceMode", "DESCRIBE"));
      } else {
        JsonNode evidence = this.mapper.readTree(verified.getOutput());
        String mode = evidence.path("inferenceEngine").asText("direct-evidence");
        StringBuilder derivation = new StringBuilder()
            .append("Project-local grounded derivation (" ).append(mode).append("):\n")
            .append("Verdict: ").append(evidence.path("verdict").asText("UNKNOWN"))
            .append("\nConfidence: ").append(evidence.path("confidence").asDouble(0.0));
        JsonNode supporting = evidence.path("supportingFactKeys");
        if (supporting.isArray() && !supporting.isEmpty()) {
          derivation.append("\nSupporting fact keys:");
          supporting.forEach(item -> derivation.append("\n  - ").append(item.asText()));
        }
        JsonNode rules = evidence.path("activatedRules");
        if (rules.isArray() && !rules.isEmpty()) {
          derivation.append("\nActivated rules:");
          rules.forEach(item -> derivation.append("\n  - ").append(item.asText()));
        }
        JsonNode atoms = evidence.path("evidenceAtoms");
        if (atoms.isArray() && !atoms.isEmpty()) {
          derivation.append("\nEvidence atoms:");
          atoms.forEach(item -> derivation.append("\n  - ").append(item.asText()));
        }
        return ToolResult.success(
            "ask_graph_explain: " + target,
            derivation.toString(),
            Map.of("backend", "project-local", "target", target, "inferenceMode", mode,
                "grounded", true, "verdict", evidence.path("verdict").asText("UNKNOWN")));
      }
    }
  }

  private ToolResult offlineFused(JsonNode params, ToolContext context) throws Exception {
    String target = this.text(params, "target");
    if (target == null) {
      return ToolResult.error("target is required");
    } else {
      ObjectNode searchParams = (ObjectNode) params.deepCopy();
      searchParams.put("query", target);
      ToolResult search = this.offlineGraphSearch(searchParams, context);
      ObjectNode result = this.mapper.createObjectNode();
      result.put("backend", "project-local");
      result.put("target", target);
      if (search.isError()) return search;
      result.putNull("fusedConfidence");
      result.put("status", "RETRIEVAL_ONLY");
      result.put("modalityCount", 1);
      result.put("activeModalityCount", 1);
      result.put("naturalLanguageAnswer", search.getOutput());
      result.put("caveat", "Project-local evidence retrieval only; no concurrent reasoning-engine fusion "
          + "or calibrated fused confidence was computed.");
      ArrayNode summaries = result.putArray("summaries");
      summaries.add("Project-local graph search");
      return this.jsonSuccess(
          "ask_graph_explain_fused: " + target,
          result,
          Map.of("backend", "project-local", "target", target));
    }
  }

  private ToolResult offlineSynthesize(JsonNode params, ToolContext context) throws Exception {
    String query = this.text(params, "query");
    if (query == null) {
      return ToolResult.error("query is required");
    } else {
      LocalProjectGraphBackend.GraphSelection selection = this.selectGraph(context, params);
      String expectedType = this.text(params, "expectedType");
      int max = Math.max(1, Math.min(100, params.path("maxCandidates").asInt(10)));
      Set<String> terms =
          new LinkedHashSet<>(List.of(query.toLowerCase(Locale.ROOT).split("[^a-z0-9_]+")));
      ArrayNode answers = this.mapper.createArrayNode();
      selection.graph().entities().stream()
          .filter(entity -> expectedType == null || entity.hasTypeMembership(expectedType))
          .map(entity -> Map.entry(entity, this.lexicalScore(this.entityText(entity), terms)))
          .filter(entry -> entry.getValue() > 0.0)
          .sorted(
              Comparator.comparingDouble((Map.Entry<GraphEntity, Double> entry) -> entry.getValue())
                  .reversed())
          .limit(max)
          .forEach(
              entry -> {
                ObjectNode answer = answers.addObject();
                answer.put(
                    "answer", this.firstNonBlank(entry.getKey().label(), entry.getKey().id()));
                answer.put("entityId", entry.getKey().id());
                answer.put("likelihood", entry.getValue());
                answer.put("belief", entry.getKey().confidence());
                answer.put("uncertainty", 1.0 - entry.getKey().confidence());
              });
      ObjectNode result = this.localEnvelope(selection);
      result.set("answers", answers);
      result.put("answerCount", answers.size());
      return this.jsonSuccess(
          "ask_graph_synthesize: " + query,
          result,
          Map.of("backend", "project-local", "answerCount", answers.size()));
    }
  }

  private ToolResult offlineClaim(JsonNode params, ToolContext context) throws Exception {
    String subject = this.text(params, "subject");
    String predicate = this.text(params, "predicate");
    String object = this.text(params, "object");
    if (subject == null) {
      return ToolResult.error("subject is required");
    } else if (predicate == null) {
      return ToolResult.error("predicate is required");
    } else if (object == null) {
      return ToolResult.error("object is required");
    } else {
      LocalProjectGraphBackend.GraphSelection selection = this.selectGraph(context, params);
      LocalProjectGraphBackend.Atom claim =
          new LocalProjectGraphBackend.Atom(predicate, List.of(subject, object));
      List<GraphRelation> direct = this.matchingRelations(selection.graph(), claim, Map.of());
      double score = direct.stream().mapToDouble(GraphRelation::confidence).max().orElse(0.0);
      String verdict = direct.isEmpty() ? "UNCERTAIN" : (score <= 0.0 ? "REFUTED" : "SUPPORTED");
      ObjectNode result = this.localEnvelope(selection);
      result.put("verdict", verdict);
      result.put("fusedScore", score);
      result.put("claimAtom", subject + " " + predicate + " " + object);
      ArrayNode supporting = result.putArray("supporting");
      direct.forEach(
          relation -> {
            ObjectNode evidence = supporting.addObject();
            evidence.put("signal", "direct-graph-edge");
            evidence.put("description", this.relationAtom(relation));
            evidence.put("probability", relation.confidence());
          });
      result.putArray("refuting");
      return this.jsonSuccess(
          "ask_graph_claim: " + subject + " " + predicate + " " + object,
          result,
          Map.of("backend", "project-local", "verdict", verdict, "fusedScore", score));
    }
  }

  private ToolResult offlineBayes(JsonNode params, ToolContext context) throws Exception {
    String action = params.path("action").asText("").toLowerCase(Locale.ROOT);
    if (action.isBlank()) {
      return ToolResult.error("action is required");
    } else {
      LocalProjectGraphBackend.GraphSelection selection = this.selectGraph(context, params);
      ObjectNode scopedParams = params.deepCopy();
      String requestedNode = this.firstNonBlank(this.text(params, "node_id"),
          this.text(params, "nodeId"), this.text(params, "target"));
      if ("sensitivity".equals(action) && requestedNode != null
          && !params.has("seed_node_ids") && !params.has("seedNodeIds")) {
        scopedParams.put("node_id", requestedNode);
      }
      LocalProjectGraphBackend.BayesianScope scope = this.bayesianScope(scopedParams, selection);
      BayesianNetwork network = scope.network();
      Map<String, Integer> evidence = switch (action) {
        case "whatif" -> this.bayesianEvidence(scope, params, "hypothetical_evidence", "hypotheticalEvidence");
        default -> this.bayesianEvidence(scope, params, "evidence");
      };
      ObjectNode result = this.localEnvelope(selection);
      result.put("action", action);
      result.put("method", "GraphBayesianNetworkBuilder + VariableElimination");
      result.put("inferenceAlgorithm", "VariableElimination");
      result.put("nodeCount", network.size());
      result.put("edgeCount", network.getStatistics().getOrDefault("edgeCount", 0) instanceof Number n
          ? n.intValue() : 0);
      this.putBayesianMetadata(result, scope, evidence);

      switch (action) {
        case "query" -> {
          if (requestedNode == null) {
            Map<String, Double> posteriors = VariableElimination.queryAll(network, evidence);
            this.putBayesianPosteriors(result, scope, posteriors, evidence);
          } else {
            GraphEntity entity = this.requireBayesianEntity(scope, requestedNode);
            String queryVar = network.getVariableForKgNodeId(entity.id());
            Factor posterior = VariableElimination.query(network, queryVar, evidence);
            double value = evidence.containsKey(queryVar)
                ? (evidence.get(queryVar) == 1 ? 1.0 : 0.0)
                : this.posteriorTrue(posterior);
            result.put("nodeId", entity.id());
            result.put("posterior", value);
            result.put("prior", this.posteriorTrue(VariableElimination.query(network, queryVar, Map.of())));
            this.putBayesianPosteriors(result, scope, Map.of(queryVar, value), evidence);
          }
        }
        case "mpe" -> {
          VariableElimination.JointMpeResult mpe =
              VariableElimination.jointMostProbableExplanation(network, evidence);
          ObjectNode assignment = result.putObject("assignment");
          mpe.assignment().forEach(assignment::put);
          ObjectNode states = result.putObject("mpeStates");
          mpe.assignment().forEach(states::put);
          evidence.forEach((variable, state) -> {
            if (!states.has(variable)) states.put(variable, state);
          });
          if (Double.isFinite(mpe.logScore())) result.put("logScore", mpe.logScore());
          else result.putNull("logScore");
          result.put("jointProbability", Double.isFinite(mpe.logScore()) ? Math.exp(mpe.logScore()) : 0.0);
          if (Double.isFinite(mpe.probabilityGivenEvidence())) {
            result.put("probabilityGivenEvidence", mpe.probabilityGivenEvidence());
          } else {
            result.putNull("probabilityGivenEvidence");
          }
          this.putBayesianPosteriors(result, scope, VariableElimination.queryAll(network, evidence), evidence);
        }
        case "sensitivity" -> {
          if (requestedNode == null) return ToolResult.error("node_id is required for sensitivity");
          GraphEntity entity = this.requireBayesianEntity(scope, requestedNode);
          String targetVar = network.getVariableForKgNodeId(entity.id());
          List<Entry<String, Double>> ranked =
              SensitivityAnalyzer.rankBySensitivity(network, evidence, targetVar);
          int topK = Math.max(1, Math.min(100, params.path("top_k").asInt(10)));
          ArrayNode influences = result.putArray("influences");
          ranked.stream().limit(topK).forEach(entry -> {
            ObjectNode row = influences.addObject();
            row.put("variable", entry.getKey());
            row.put("variableName", scope.variableToEntity().getOrDefault(entry.getKey(), entry.getKey()));
            row.put("impact", entry.getValue());
            row.put("maxImpact", entry.getValue());
          });
          result.put("queryNodeId", entity.id());
          result.put("baselinePosterior", this.posteriorTrue(VariableElimination.query(network, targetVar, evidence)));
          result.put("queryPrior", this.posteriorTrue(VariableElimination.query(network, targetVar, Map.of())));
        }
        case "whatif" -> this.putBayesianPosteriors(result, scope,
            VariableElimination.queryAll(network, evidence), evidence);
        case "stats" -> {
          JsonNode networkStats = this.mapper.valueToTree(network.getStatistics());
          if (networkStats.isObject()) result.setAll((ObjectNode) networkStats);
          result.put("variableCount", network.size());
          result.put("connectedComponents", this.connectedComponents(scope.graph()));
        }
        default -> { return ToolResult.error("Unknown action: " + action);
        }
      }
      return this.jsonSuccess("graph_bayes: " + action, result,
          Map.of("backend", "project-local", "action", action,
              "nodeCount", network.size(), "edgeCount", network.getStatistics().getOrDefault("edgeCount", 0)));
    }
  }

  private BayesianScope bayesianScope(JsonNode params, GraphSelection selection) {
    UnifiedGraph graph = selection.graph();
    int depth = bayesianBound(params, "max_depth", "maxDepth", 3, 10);
    int maxNodes = bayesianBound(params, "max_nodes", "maxNodes", 100, 1_000);
    LinkedHashSet<String> seedIds = new LinkedHashSet<>();
    JsonNode seedArray = params.has("seed_node_ids") ? params.get("seed_node_ids") : params.get("seedNodeIds");
    if (seedArray != null) {
      if (!seedArray.isArray()) throw new IllegalArgumentException("seed_node_ids must be an array");
      seedArray.forEach(seed -> {
        if (!seed.isTextual() || seed.asText().isBlank()) {
          throw new IllegalArgumentException("seed_node_ids entries must be non-blank strings");
        }
        GraphEntity resolved = this.resolveEntity(graph, seed.asText());
        if (resolved == null) throw new IllegalArgumentException("Unknown Bayesian graph node: " + seed.asText());
        seedIds.add(resolved.id());
      });
    }
    String singleSeed = this.firstNonBlank(this.text(params, "node_id"), this.text(params, "nodeId"),
        this.text(params, "target"));
    if (singleSeed != null) {
      GraphEntity resolved = this.resolveEntity(graph, singleSeed);
      if (resolved == null) throw new IllegalArgumentException("Unknown Bayesian graph node: " + singleSeed);
      seedIds.add(resolved.id());
    }

    Set<String> scopedIds;
    if (seedIds.isEmpty()) {
      scopedIds = graph.entities().stream().map(GraphEntity::id).sorted()
          .limit(maxNodes).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    } else {
      scopedIds = boundedNeighborhood(graph, seedIds, depth, maxNodes);
    }
    UnifiedGraph scoped = scopedIds.size() == graph.entities().size()
        ? graph : graph.inducedSubgraph(scopedIds);
    GraphBayesianNetworkBuilder builder = new GraphBayesianNetworkBuilder();
    BayesianNetwork network = builder.build(scoped);
    return new BayesianScope(scoped, network,
        new LinkedHashMap<>(builder.entityIdToVariable()),
        new LinkedHashMap<>(builder.variableToEntityId()));
  }

  private int bayesianBound(JsonNode params, String snake, String camel, int fallback, int max) {
    JsonNode raw = params.has(snake) ? params.get(snake) : params.get(camel);
    if (raw == null) return fallback;
    if (!raw.isIntegralNumber() || !raw.canConvertToInt() || raw.intValue() < 0 || raw.intValue() > max) {
      throw new IllegalArgumentException(snake + " must be an integer in [0," + max + "]");
    }
    return raw.intValue();
  }

  private Map<String, Integer> bayesianEvidence(BayesianScope scope, JsonNode params, String... fields) {
    JsonNode values = null;
    for (String field : fields) {
      if (params.has(field)) {
        values = params.get(field);
        break;
      }
    }
    if (values == null) return Map.of();
    if (!values.isObject()) throw new IllegalArgumentException(fields[0] + " must be an object");
    Map<String, Integer> evidence = new LinkedHashMap<>();
    values.fields().forEachRemaining(entry -> {
      JsonNode state = entry.getValue();
      if (!state.isIntegralNumber() || !state.canConvertToInt() || (state.intValue() != 0 && state.intValue() != 1)) {
        throw new IllegalArgumentException("Bayesian evidence states must be 0 or 1: " + entry.getKey());
      }
      GraphEntity entity = this.resolveEntity(scope.graph(), entry.getKey());
      if (entity == null) throw new IllegalArgumentException("Unknown Bayesian evidence node: " + entry.getKey());
      String variable = scope.network().getVariableForKgNodeId(entity.id());
      if (variable == null) throw new IllegalArgumentException("Bayesian evidence node is outside the bounded network: " + entry.getKey());
      evidence.put(variable, state.intValue());
    });
    return evidence;
  }

  private GraphEntity requireBayesianEntity(BayesianScope scope, String selector) {
    GraphEntity entity = this.resolveEntity(scope.graph(), selector);
    if (entity == null || scope.network().getVariableForKgNodeId(entity.id()) == null) {
      throw new IllegalArgumentException("Unknown Bayesian query node: " + selector);
    }
    return entity;
  }

  private void putBayesianMetadata(ObjectNode result, BayesianScope scope, Map<String, Integer> evidence) {
    ObjectNode evidenceJson = result.putObject("evidence");
    evidence.forEach(evidenceJson::put);
    ObjectNode variableToNode = result.putObject("variableToNodeId");
    ObjectNode variableToTitle = result.putObject("variableToTitle");
    scope.network().getNodes().forEach(node -> {
      variableToNode.put(node.getVariableName(), node.getKgNodeId());
      variableToTitle.put(node.getVariableName(), node.getTitle());
    });
    result.put("scopedNodeCount", scope.network().size());
    result.put("scopedEdgeCount", scope.network().getStatistics().getOrDefault("edgeCount", 0) instanceof Number n ? n.intValue() : 0);
  }

  private void putBayesianPosteriors(ObjectNode result, BayesianScope scope,
                                     Map<String, Double> posteriors, Map<String, Integer> evidence) {
    result.set("posteriors", this.mapper.valueToTree(posteriors));
    result.set("priors", this.mapper.valueToTree(VariableElimination.queryAll(scope.network(), Map.of())));
    this.putBayesianMetadata(result, scope, evidence);
  }

  private double posteriorTrue(Factor factor) {
    double[] values = factor.getValues();
    return values.length > 1 ? values[1] : values[0];
  }

  private int connectedComponents(UnifiedGraph graph) {
    Set<String> remaining = graph.entities().stream().map(GraphEntity::id)
        .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    int components = 0;
    while (!remaining.isEmpty()) {
      components++;
      String start = remaining.iterator().next();
      Deque<String> queue = new ArrayDeque<>();
      queue.add(start);
      remaining.remove(start);
      while (!queue.isEmpty()) {
        String current = queue.removeFirst();
        for (GraphRelation relation : graph.relationsOf(current)) {
          String next = relation.sourceId().equals(current) ? relation.targetId() : relation.sourceId();
          if (remaining.remove(next)) queue.addLast(next);
        }
      }
    }
    return components;
  }

  private static Set<String> boundedNeighborhood(UnifiedGraph graph, Collection<String> seeds,
                                                  int depth, int maxNodes) {
    LinkedHashSet<String> visited = new LinkedHashSet<>();
    Deque<NodeDepth> queue = new ArrayDeque<>();
    // Preserve every explicit seed, matching BayesianNetworkBuilder's contract; maxNodes bounds
    // only the discovered neighborhood beyond the caller's requested seeds.
    seeds.stream().sorted().forEach(seed -> {
      if (visited.add(seed)) queue.addLast(new NodeDepth(seed, 0));
    });
    if (maxNodes == 0) return visited;
    while (!queue.isEmpty() && visited.size() < maxNodes) {
      NodeDepth current = queue.removeFirst();
      if (current.depth() >= depth) continue;
      graph.relationsOf(current.id()).stream().sorted(Comparator.comparing(GraphRelation::id)).forEach(relation -> {
        if (visited.size() >= maxNodes) return;
        String next = relation.sourceId().equals(current.id()) ? relation.targetId() : relation.sourceId();
        if (visited.add(next)) queue.addLast(new NodeDepth(next, current.depth() + 1));
      });
    }
    return visited;
  }

  private OwlEvidence owlEvidence(UnifiedGraph graph, Atom atom, OwlOntology ontology) {
    UnifiedGraph evidenceGraph = new UnifiedGraph().graphId(graph.graphId());
    graph.meta().forEach(evidenceGraph::meta);
    byte[] ontologyArtifact = graph.artifact(TableMemberOntologyBridge.ONTOLOGY_ARTIFACT);
    if (ontologyArtifact != null) evidenceGraph.putArtifact(TableMemberOntologyBridge.ONTOLOGY_ARTIFACT, ontologyArtifact);
    graph.entities().forEach(evidenceGraph::addEntity);
    graph.relations().stream()
        .filter(this::authoritativeRelation)
        .filter(relation -> Double.isFinite(relation.confidence()) && relation.confidence() > 0.0)
        .forEach(evidenceGraph::addRelation);
    OwlRlResult reasoned = new OwlRlReasoner().reason(evidenceGraph, ontology);
    if (atom.args().size() == 2) {
      GraphEntity source = this.resolveEntity(evidenceGraph, atom.args().get(0));
      GraphEntity target = this.resolveEntity(evidenceGraph, atom.args().get(1));
      OwlObjectProperty property = owlProperty(ontology, atom.predicate());
      if (source == null || target == null || property == null || !property.isTransitive()) {
        return new OwlEvidence(true, false, 0.0, 0, List.of(), List.of(), List.of());
      }
      List<GraphRelation> path = owlPath(evidenceGraph, source.id(), target.id(), property.localName());
      boolean inferred = path.size() >= 2 && reasoned.inferredRelations().stream()
          .anyMatch(relation -> relation.sourceId().equals(source.id()) && relation.targetId().equals(target.id())
              && relation.type().equalsIgnoreCase(property.localName()));
      if (!inferred) return new OwlEvidence(true, false, 0.0, 0, List.of(), List.of(), List.of());
      double confidence = path.stream().mapToDouble(GraphRelation::confidence).min().orElse(0.0);
      List<String> facts = path.stream().map(this::relationAtom).toList();
      String rule = "prp-trp-" + property.localName();
      return new OwlEvidence(true, true, confidence, path.size(), facts, List.of(rule),
          List.of(atom.predicate() + "(" + source.id() + ", " + target.id() + ")"));
    }
    GraphEntity entity = this.resolveEntity(evidenceGraph, atom.args().get(0));
    OwlClass targetClass = owlClass(ontology, atom.predicate());
    if (entity == null || targetClass == null) return new OwlEvidence(true, false, 0.0, 0, List.of(), List.of(), List.of());
    if (entity.hasTypeMembership(targetClass.localName()) || entity.hasTypeMembership(targetClass.classIri())) {
      String fact = "Type(" + entity.id() + ", " + targetClass.localName() + ")";
      return new OwlEvidence(true, true, entity.confidence(), 0,
          List.of(fact), List.of(), List.of(atom.predicate() + "(" + entity.id() + ")"));
    }
    String targetIri = targetClass.classIri();
    if (!reasoned.inferredTypeCandidates().getOrDefault(entity.id(), List.of()).contains(targetIri)) {
      return new OwlEvidence(true, false, 0.0, 0, List.of(), List.of(), List.of());
    }
    for (String membership : entity.typeMemberships()) {
      OwlClass asserted = owlClass(ontology, membership);
      if (asserted == null) continue;
      OwlTypePath path = owlTypePath(ontology, asserted.classIri(), targetIri);
      if (path == null) continue;
      String fact = "Type(" + entity.id() + ", " + asserted.localName() + ")";
      return new OwlEvidence(true, true, entity.confidence(), path.rules().size(),
          List.of(fact), path.rules(), List.of(atom.predicate() + "(" + entity.id() + ")"));
    }
    for (GraphRelation relation : evidenceGraph.relations()) {
      if (!relation.sourceId().equals(entity.id()) && !relation.targetId().equals(entity.id())) continue;
      OwlObjectProperty property = owlProperty(ontology, relation.type());
      if (property == null) continue;
      if (relation.sourceId().equals(entity.id()) && targetIri.equals(property.domainClassIri())) {
        return new OwlEvidence(true, true, relation.confidence(), 1,
            List.of(this.relationAtom(relation)), List.of("prp-dom-" + property.localName()),
            List.of(atom.predicate() + "(" + entity.id() + ")"));
      }
      if (relation.targetId().equals(entity.id()) && targetIri.equals(property.rangeClassIri())) {
        return new OwlEvidence(true, true, relation.confidence(), 1,
            List.of(this.relationAtom(relation)), List.of("prp-rng-" + property.localName()),
            List.of(atom.predicate() + "(" + entity.id() + ")"));
      }
    }
    return new OwlEvidence(true, false, 0.0, 0, List.of(), List.of(), List.of());
  }

  private OwlObjectProperty owlProperty(OwlOntology ontology, String predicate) {
    return ontology.objectProperties().values().stream()
        .filter(property -> predicate.equals(property.propertyIri()) || predicate.equals(property.localName()))
        .findFirst().orElse(null);
  }

  private OwlClass owlClass(OwlOntology ontology, String predicate) {
    return ontology.classes().values().stream()
        .filter(owlClass -> predicate.equals(owlClass.classIri()) || predicate.equals(owlClass.localName()))
        .findFirst().orElse(null);
  }

  private OwlTypePath owlTypePath(OwlOntology ontology, String sourceIri, String targetIri) {
    Deque<String> queue = new ArrayDeque<>();
    Map<String, String> parent = new LinkedHashMap<>();
    Map<String, String> rules = new LinkedHashMap<>();
    queue.add(sourceIri);
    parent.put(sourceIri, null);
    while (!queue.isEmpty()) {
      String current = queue.removeFirst();
      if (current.equals(targetIri)) break;
      OwlClass currentClass = ontology.classes().get(current);
      if (currentClass == null) continue;
      for (String next : currentClass.subClassOfIris()) {
        if (ontology.classes().containsKey(next) && !parent.containsKey(next)) {
          parent.put(next, current);
          rules.put(next, "cax-sco-" + currentClass.localName() + "-" + ontology.classes().get(next).localName());
          queue.addLast(next);
        }
      }
      for (String equivalent : currentClass.equivalentClassIris()) {
        if (ontology.classes().containsKey(equivalent) && !parent.containsKey(equivalent)) {
          parent.put(equivalent, current);
          rules.put(equivalent, "cls-oo-" + currentClass.localName() + "-" + ontology.classes().get(equivalent).localName());
          queue.addLast(equivalent);
        }
      }
      for (OwlClass candidate : ontology.classes().values()) {
        if (candidate.equivalentClassIris().contains(current) && !parent.containsKey(candidate.classIri())) {
          parent.put(candidate.classIri(), current);
          rules.put(candidate.classIri(), "cls-oo-sym-" + candidate.localName() + "-" + currentClass.localName());
          queue.addLast(candidate.classIri());
        }
      }
    }
    if (!parent.containsKey(targetIri) || sourceIri.equals(targetIri)) return null;
    List<String> pathRules = new ArrayList<>();
    for (String at = targetIri; at != null && !at.equals(sourceIri); at = parent.get(at)) {
      pathRules.add(rules.get(at));
    }
    Collections.reverse(pathRules);
    return new OwlTypePath(pathRules);
  }

  private List<GraphRelation> owlPath(UnifiedGraph graph, String source, String target, String property) {
    Map<String, List<GraphRelation>> paths = new LinkedHashMap<>();
    Deque<String> queue = new ArrayDeque<>();
    queue.add(source);
    paths.put(source, List.of());
    while (!queue.isEmpty()) {
      String current = queue.removeFirst();
      List<GraphRelation> currentPath = paths.get(current);
      for (GraphRelation relation : graph.outgoing(current)) {
        if (!relation.type().equalsIgnoreCase(property)) continue;
        String next = relation.targetId();
        if (paths.containsKey(next)) continue;
        List<GraphRelation> candidate = new ArrayList<>(currentPath);
        candidate.add(relation);
        paths.put(next, candidate);
        if (next.equals(target)) return candidate;
        queue.addLast(next);
      }
    }
    return List.of();
  }

  private record BayesianScope(UnifiedGraph graph, BayesianNetwork network,
                               Map<String, String> entityToVariable,
                               Map<String, String> variableToEntity) {
  }

  private record OwlEvidence(boolean attempted, boolean supported, double confidence, int depth,
                             List<String> supportingFactKeys, List<String> activatedRules,
                             List<String> evidenceAtoms) {
    static OwlEvidence empty() { return new OwlEvidence(false, false, 0.0, 0, List.of(), List.of(), List.of()); }
  }

  private record OwlTypePath(List<String> rules) {
  }

  private ToolResult offlineSimulate(JsonNode params, ToolContext context) throws Exception {
    String action = params.path("action").asText("").toLowerCase(Locale.ROOT);
    if (action.isBlank()) {
      return ToolResult.error("action is required");
    } else if ("scenarios".equals(action)) {
      LocalProjectGraphBackend.GraphSelection selection = this.selectGraph(context, params);
      ObjectNode result = this.localEnvelope(selection);
      ArrayNode scenarios = result.putArray("scenarios");
      ObjectNode scenario = scenarios.addObject();
      scenario.put("id", selection.graph().graphId());
      scenario.put("name", selection.graph().graphId());
      scenario.put("nodeCount", selection.graph().entities().size());
      return this.jsonSuccess(
          "graph_simulate: scenarios",
          result,
          Map.of("backend", "project-local", "count", scenarios.size()));
    } else if ("create_run".equals(action)) {
      String scenarioId = this.text(params, "scenario_id");
      if (scenarioId == null) {
        return ToolResult.error("scenario_id is required for action=create_run");
      } else {
        String runId = UUID.randomUUID().toString();
        ObjectNode run = this.mapper.createObjectNode();
        run.put("runId", runId);
        run.put("scenarioId", scenarioId);
        run.put("status", "PAUSED");
        run.put("step", 0);
        run.put("backend", "project-local");
        LOCAL_SIMULATION_RUNS.put(runId, run);
        return this.jsonSuccess(
            "graph_simulate: create_run", run, Map.of("backend", "project-local", "runId", runId));
      }
    } else if ("runs".equals(action)) {
      ObjectNode result = this.mapper.createObjectNode();
      result.put("backend", "project-local");
      result.set("runs", this.mapper.valueToTree(LOCAL_SIMULATION_RUNS.values()));
      return this.jsonSuccess(
          "graph_simulate: runs",
          result,
          Map.of("backend", "project-local", "count", LOCAL_SIMULATION_RUNS.size()));
    } else {
      String runId = this.text(params, "run_id");
      if (runId == null) {
        return ToolResult.error("run_id is required for action=" + action);
      } else {
        ObjectNode run = LOCAL_SIMULATION_RUNS.get(runId);
        if (run == null) {
          return ToolResult.error("Project-local simulation run not found: " + runId);
        } else {
          switch (action) {
            case "step":
              run.put("step", run.path("step").asInt() + 1);
              break;
            case "play":
            case "run":
              run.put("status", "RUNNING");
              break;
            case "pause":
              run.put("status", "PAUSED");
              break;
            case "promote":
              run.put("status", "PROMOTED");
              break;
            case "delete":
              LOCAL_SIMULATION_RUNS.remove(runId);
              run.put("status", "DELETED");
              break;
            case "reason":
              run.put("reasoning", "Project-local graph state is internally consistent.");
              break;
            case "ground_truth":
              run.put("groundTruth", "Compared with current project-local graph archive.");
          }

          return this.jsonSuccess(
              "graph_simulate: " + action,
              run,
              Map.of("backend", "project-local", "runId", runId, "action", action));
        }
      }
    }
  }

  private ToolResult offlineProcessMining(JsonNode params, ToolContext context) throws Exception {
    String action = params.path("action").asText("").toLowerCase(Locale.ROOT);
    if (action.isBlank()) {
      return ToolResult.error("action is required");
    } else {
      Path root = this.projectRoot(context.getWorkingDirectory());
      Path configPath = root.resolve("data/process-mining-config.json");
      if ("config_update".equals(action)) {
        JsonNode rawConfig = params.path("config_json");
        JsonNode config =
            rawConfig.isTextual() ? this.mapper.readTree(rawConfig.asText()) : rawConfig;
        if (!config.isObject()) {
          return ToolResult.error(
              "config_json is required for config_update (JSON object with mining* keys)");
        }

        Files.createDirectories(configPath.getParent());
        this.mapper.writerWithDefaultPrettyPrinter().writeValue(configPath.toFile(), config);
      }

      if (!"config_get".equals(action) && !"config_update".equals(action)) {
        LocalProjectGraphBackend.GraphSelection selection = this.selectGraph(context, params);
        List<GraphRelation> flows =
            selection.graph().relations().stream()
                .filter(
                    relation ->
                        relation.type().equalsIgnoreCase("DIRECTLY_FOLLOWS")
                            || relation.type().equalsIgnoreCase("NEXT_CHUNK"))
                .toList();
        ObjectNode result = this.localEnvelope(selection);
        result.put("action", action);
        result.put("processCount", flows.isEmpty() ? 0 : 1);
        result.put("transitionCount", flows.size());
        ArrayNode transitions = result.putArray("transitions");
        flows.forEach(
            relation -> {
              ObjectNode row = transitions.addObject();
              row.put("source", relation.sourceId());
              row.put("target", relation.targetId());
              row.put("type", relation.type());
            });
        if ("suggestions".equals(action)) {
          result.putArray("suggestions");
        }

        if ("bpmn".equals(action)) {
          result.put("bpmn", this.localBpmn(flows));
        }

        return this.jsonSuccess(
            "process_mining: " + action,
            result,
            Map.of("backend", "project-local", "action", action, "transitionCount", flows.size()));
      } else {
        JsonNode config =
            (JsonNode)
                (Files.isRegularFile(configPath)
                    ? this.mapper.readTree(configPath.toFile())
                    : this.mapper.createObjectNode());
        ObjectNode resultx = this.mapper.createObjectNode();
        resultx.put("backend", "project-local");
        resultx.put("path", configPath.toString());
        resultx.set("config", config);
        return this.jsonSuccess(
            "process_mining: " + action,
            resultx,
            Map.of("backend", "project-local", "action", action));
      }
    }
  }

  private LocalProjectGraphBackend.GraphSelection writableSelection(
      ToolContext context, JsonNode params) throws Exception {
    JsonNode selector = this.effectiveQuerySelector(context, params);
    List<Path> paths = this.selectedGraphPaths(context.getWorkingDirectory(), selector);
    if (paths.size() != 1) {
      throw new IllegalArgumentException(
          "No single project-local graph is selected — select one explicit knowledgeBase "
              + "before mutating a merged multi-graph view.");
    } else {
      Path path = paths.get(0);
      UnifiedGraph descriptor = new UnifiedGraph().graphId("project-local-archive");
      try (UnifiedGraphArchive archive = UnifiedGraphArchive.open(path)) {
        Object rawMeta = archive.manifest().get("meta");
        if (rawMeta instanceof Map<?, ?> meta && meta.get("graphId") != null) {
          descriptor.graphId(String.valueOf(meta.get("graphId")));
        }
      }
      return new LocalProjectGraphBackend.GraphSelection(descriptor, path);
    }
  }

  private ObjectNode localEnvelope(LocalProjectGraphBackend.GraphSelection selection) {
    ObjectNode result = this.mapper.createObjectNode();
    result.put("backend", "project-local");
    result.put("graphId", selection.graph().graphId());
    result.put("graphPath", selection.pathDescription());
    Object validAt = selection.graph().meta().get("query.validAt");
    if (validAt != null) {
      ObjectNode temporal = result.putObject("temporal");
      temporal.put("axis", "valid-time");
      temporal.put("validAt", validAt.toString());
      temporal.put("historicalReconstruction", false);
      temporal.put("timelessPolicy", "included as temporally unspecified");
      temporal.put("learnedPosteriors", "not used for filtered topology");
    }
    ObjectNode meta = result.putObject("meta");
    meta.put("backend", "project-local");
    meta.put("stale", staleCodeLearning(selection.graph(), "codeLearningGeneration."));
    meta.put("freshnessBasis", "projected-code-versus-learning-generation; source-tree-not-checked");
    meta.put("kbVersion", LOCAL_KB_VERSION.get());
    return result;
  }

  private ToolResult jsonSuccess(String title, JsonNode result, Map<String, Object> metadata)
      throws Exception {
    return ToolResult.success(
        title, this.mapper.writerWithDefaultPrettyPrinter().writeValueAsString(result), metadata);
  }

  private String entityText(GraphEntity entity) {
    return (entity.id() + "\n" + entity.label() + "\n" + entity.type() + "\n" + entity.attributes())
        .toLowerCase(Locale.ROOT);
  }

  private String relationText(GraphRelation relation) {
    return (relation.id()
            + "\n"
            + relation.sourceId()
            + "\n"
            + relation.targetId()
            + "\n"
            + relation.type()
            + "\n"
            + relation.attributes())
        .toLowerCase(Locale.ROOT);
  }

  private Double number(Object value) {
    if (value instanceof Number number) {
      return number.doubleValue();
    } else if (value instanceof String text) {
      try {
        return Double.valueOf(text);
      } catch (NumberFormatException var4) {
        return null;
      }
    } else {
      return null;
    }
  }

  private double aggregate(List<Double> values, String operation) {
    if (values.isEmpty()) {
      return 0.0;
    } else {
      return switch (operation) {
        case "COUNT" -> values.size();
        case "AVG" -> values.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        case "MIN" -> values.stream().mapToDouble(Double::doubleValue).min().orElse(0.0);
        case "MAX" -> values.stream().mapToDouble(Double::doubleValue).max().orElse(0.0);
        default -> values.stream().mapToDouble(Double::doubleValue).sum();
      };
    }
  }

  private Instant entityInstant(GraphEntity entity) {
    if (entity.timestamp() != null) {
      return entity.timestamp();
    } else {
      for (String key :
          List.of("event_time", "eventTime", "timestamp", "date", "created_at", "createdAt")) {
        String raw = entity.stringAttribute(key);
        if (raw != null && !raw.isBlank()) {
          try {
            return Instant.parse(raw);
          } catch (DateTimeParseException var7) {
            try {
              return LocalDate.parse(raw).atStartOfDay().toInstant(ZoneOffset.UTC);
            } catch (DateTimeParseException var6) {
            }
          }
        }
      }

      return null;
    }
  }

  private String timeBucket(Instant instant, String bucketSize) {
    ZonedDateTime value = instant.atZone(ZoneOffset.UTC);

    return switch (bucketSize) {
      case "YEAR" -> Integer.toString(value.getYear());
      case "MONTH" -> String.format("%04d-%02d", value.getYear(), value.getMonthValue());
      default -> value.getYear() + "-Q" + ((value.getMonthValue() - 1) / 3 + 1);
    };
  }

  private double linearSlope(List<Double> values) {
    int n = values.size();
    double sumX = n * (n - 1) / 2.0;
    double sumY = values.stream().mapToDouble(Double::doubleValue).sum();
    double sumXX = (n - 1) * n * (2.0 * n - 1.0) / 6.0;
    double sumXY = 0.0;

    for (int i = 0; i < n; i++) {
      sumXY += i * values.get(i);
    }

    double denominator = n * sumXX - sumX * sumX;
    return denominator == 0.0 ? 0.0 : (n * sumXY - sumX * sumY) / denominator;
  }

  private Map<String, Double> degree(UnifiedGraph graph, String type) {
    Map<String, Double> scores = new LinkedHashMap<>();
    graph.entities().forEach(entity -> scores.put(entity.id(), 0.0));

    for (GraphRelation relation : graph.relations()) {
      if (!"in".equals(type)) {
        scores.computeIfPresent(relation.sourceId(), (id, score) -> score + 1.0);
      }

      if (!"out".equals(type)) {
        scores.computeIfPresent(relation.targetId(), (id, score) -> score + 1.0);
      }
    }

    return scores;
  }

  private Map<String, Double> pageRank(UnifiedGraph graph) {
    List<String> ids = graph.entities().stream().<String>map(GraphEntity::id).sorted().toList();
    if (ids.isEmpty()) {
      return Map.of();
    } else {
      int n = ids.size();
      // Snapshot adjacency once: repeated graph.outgoing(id) calls cost a map lookup plus list
      // copy per node per iteration on large graphs.
      Map<String, List<String>> adjacency = new LinkedHashMap<>(n * 2);
      double[] outWeight = new double[n];
      double danglingMass = 0.0;
      for (int i = 0; i < n; i++) {
        String id = ids.get(i);
        List<String> targets =
            graph.outgoing(id).stream().map(GraphRelation::targetId).distinct().toList();
        adjacency.put(id, targets);
        outWeight[i] = targets.isEmpty() ? 0.0 : 1.0 / targets.size();
        if (targets.isEmpty()) {
          danglingMass += 1.0 / n;
        }
      }

      // Rank arrays in id order: array reads beat LinkedHashMap.get inside the hot loop.
      double[] rank = new double[n];
      java.util.Arrays.fill(rank, 1.0 / n);
      double[] next = new double[n];
      Map<String, Integer> index = new HashMap<>(n * 2);
      for (int i = 0; i < n; i++) {
        index.put(ids.get(i), i);
      }

      for (int iteration = 0; iteration < 30; iteration++) {
        java.util.Arrays.fill(next, 0.15 / n);
        // Dangling mass is redistributed uniformly in ONE pass instead of a per-dangling-node
        // full sweep, which made the previous implementation O(dangling * N) per iteration.
        double danglingShare = 0.85 * danglingMass / n;
        if (danglingShare > 0.0) {
          for (int i = 0; i < n; i++) {
            next[i] += danglingShare;
          }
        }

        for (int i = 0; i < n; i++) {
          List<String> targets = adjacency.get(ids.get(i));
          if (targets.isEmpty()) {
            continue;
          }
          double share = 0.85 * rank[i] * outWeight[i];
          for (String target : targets) {
            next[index.get(target)] += share;
          }
        }

        double[] swap = rank;
        rank = next;
        next = swap;
      }

      Map<String, Double> result = new LinkedHashMap<>(n * 2);
      for (int i = 0; i < n; i++) {
        result.put(ids.get(i), rank[i]);
      }
      return result;
    }
  }

  private Map<String, Double> betweenness(UnifiedGraph graph) {
    // Brandes (2001): exact unweighted betweenness in O(V + E) instead of one BFS per node pair
    // (the previous O(V^2 * (V + E)) formulation never completed on six-figure graphs).
    List<String> ids = graph.entities().stream().<String>map(GraphEntity::id).sorted().toList();
    int n = ids.size();
    if (n == 0) {
      return Map.of();
    }
    Map<String, Integer> index = new HashMap<>(n * 2);
    for (int i = 0; i < n; i++) {
      index.put(ids.get(i), i);
    }
    List<List<Integer>> neighbors = new ArrayList<>(n);
    for (String id : ids) {
      List<Integer> adjacency = new ArrayList<>();
      for (GraphRelation relation : graph.relationsOf(id)) {
        String other = relation.sourceId().equals(id) ? relation.targetId() : relation.sourceId();
        Integer target = index.get(other);
        if (target != null && !adjacency.contains(target)) {
          adjacency.add(target);
        }
      }
      neighbors.add(adjacency);
    }

    double[] centrality = new double[n];
    Deque<Integer> stack = new ArrayDeque<>();
    ArrayDeque<Integer> queue = new ArrayDeque<>();
    List<List<Integer>> predecessors = new ArrayList<>(n);
    double[] sigma = new double[n];
    int[] distance = new int[n];
    double[] delta = new double[n];

    for (int source = 0; source < n; source++) {
      stack.clear();
      queue.clear();
      predecessors.clear();
      for (int i = 0; i < n; i++) {
        predecessors.add(new ArrayList<>());
        sigma[i] = 0.0;
        distance[i] = -1;
        delta[i] = 0.0;
      }
      sigma[source] = 1.0;
      distance[source] = 0;
      queue.add(source);

      while (!queue.isEmpty()) {
        int current = queue.removeFirst();
        stack.push(current);
        for (int neighbor : neighbors.get(current)) {
          if (distance[neighbor] < 0) {
            distance[neighbor] = distance[current] + 1;
            queue.add(neighbor);
          }
          if (distance[neighbor] == distance[current] + 1) {
            sigma[neighbor] += sigma[current];
            predecessors.get(neighbor).add(current);
          }
        }
      }

      while (!stack.isEmpty()) {
        int current = stack.pop();
        for (int predecessor : predecessors.get(current)) {
          delta[predecessor] += sigma[predecessor] / sigma[current] * (1.0 + delta[current]);
        }
        if (current != source) {
          centrality[current] += delta[current];
        }
      }
    }

    Map<String, Double> scores = new LinkedHashMap<>(n * 2);
    for (int i = 0; i < n; i++) {
      scores.put(ids.get(i), centrality[i]);
    }
    return scores;
  }

  private List<String> shortestPath(UnifiedGraph graph, String source, String target) {
    Deque<String> queue = new ArrayDeque<>();
    Map<String, String> parent = new HashMap<>();
    queue.add(source);
    parent.put(source, null);

    while (!queue.isEmpty()) {
      String current = queue.removeFirst();
      if (current.equals(target)) {
        break;
      }

      for (GraphRelation relation : graph.relationsOf(current)) {
        String next =
            relation.sourceId().equals(current) ? relation.targetId() : relation.sourceId();
        if (!parent.containsKey(next)) {
          parent.put(next, current);
          queue.addLast(next);
        }
      }
    }

    if (!parent.containsKey(target)) {
      return List.of();
    } else {
      List<String> path = new ArrayList<>();

      for (String at = target; at != null; at = parent.get(at)) {
        path.add(at);
      }

      Collections.reverse(path);
      return path;
    }
  }

  private LocalProjectGraphBackend.Atom atom(String value) {
    int open = value.indexOf(40);
    int close = value.lastIndexOf(41);
    if (open > 0 && close > open) {
      String predicate = value.substring(0, open).trim();
      List<String> args =
          Stream.of(value.substring(open + 1, close).split(","))
              .map(String::trim)
              .filter(arg -> !arg.isEmpty())
              .toList();
      if (!args.isEmpty() && args.size() <= 2) {
        return new LocalProjectGraphBackend.Atom(predicate, args);
      } else {
        throw new IllegalArgumentException("project-local atoms support one or two arguments");
      }
    } else {
      throw new IllegalArgumentException("atom must use predicate(arg1, arg2) syntax");
    }
  }

  private List<GraphRelation> matchingRelations(
      UnifiedGraph graph, LocalProjectGraphBackend.Atom atom, Map<String, String> bindings) {
    return graph.relations().stream()
        .filter(relation -> relation.type().equals(atom.predicate()))
        .filter(
            relation ->
                this.matchesArgument(graph, atom.args().get(0), relation.sourceId(), bindings))
        .filter(
            relation ->
                atom.args().size() == 1
                    || this.matchesArgument(
                        graph, atom.args().get(1), relation.targetId(), bindings))
        .toList();
  }

  private boolean matchesArgument(
      UnifiedGraph graph, String pattern, String entityId, Map<String, String> bindings) {
    if (!pattern.startsWith("?")) {
      return this.sameEntity(graph, pattern, entityId);
    } else {
      String bound = bindings.get(pattern);
      return bound == null || this.sameEntity(graph, bound, entityId);
    }
  }

  private boolean sameEntity(UnifiedGraph graph, String requested, String actualId) {
    if (requested.equals(actualId)) return true;
    GraphEntity actual = graph.entity(actualId).orElse(null);
    if (actual == null || !(requested.equalsIgnoreCase(actual.label())
        || requested.equals(actual.attributes().get("fullyQualifiedName")))) return false;
    GraphEntity resolved = resolveEntity(graph, requested);
    return resolved != null && resolved.id().equals(actualId);
  }

  private Map<String, String> bind(
      LocalProjectGraphBackend.Atom pattern, GraphRelation relation, Map<String, String> existing) {
    Map<String, String> result = new LinkedHashMap<>(existing);
    if (!this.bindArgument(pattern.args().get(0), relation.sourceId(), result)) {
      return null;
    } else {
      return pattern.args().size() > 1
              && !this.bindArgument(pattern.args().get(1), relation.targetId(), result)
          ? null
          : result;
    }
  }

  private boolean bindArgument(String pattern, String value, Map<String, String> bindings) {
    if (!pattern.startsWith("?")) {
      return true;
    } else {
      String previous = bindings.putIfAbsent(pattern, value);
      return previous == null || previous.equals(value);
    }
  }

  private String stripVariable(String variable) {
    return variable.startsWith("?") ? variable.substring(1) : variable;
  }

  private GraphRelation relationForAtom(
      UnifiedGraph graph, LocalProjectGraphBackend.Atom atom, double confidence, JsonNode params) {
    GraphEntity source = this.ensureEntity(graph, atom.args().get(0));
    GraphEntity target =
        atom.args().size() == 2
            ? this.ensureEntity(graph, atom.args().get(1))
            : this.ensureEntity(graph, "predicate:" + atom.predicate());
    return this.relationForResolvedAtom(source, target, atom, confidence, params);
  }

  private GraphRelation relationForResolvedAtom(
      GraphEntity source,
      GraphEntity target,
      LocalProjectGraphBackend.Atom atom,
      double confidence,
      JsonNode params) {
    String id = "asserted:" + stableId(source.id() + "\n" + atom.predicate() + "\n" + target.id());
    Map<String, Object> attributes = new LinkedHashMap<>();
    attributes.put("atom", atom.predicate() + "(" + String.join(", ", atom.args()) + ")");
    attributes.put("source", this.firstNonBlank(this.text(params, "source"), "stdio-local"));
    attributes.put("assertedAt", Instant.now().toString());
    return GraphRelation.builder(id, source.id(), target.id())
        .type(atom.predicate())
        .weight(confidence)
        .confidence(confidence)
        .directed(true)
        .attributes(attributes)
        .build();
  }

  private boolean compactArchive(Path path) throws IOException {
    try (UnifiedGraphArchive archive = UnifiedGraphArchive.open(path)) {
      return archive.hasCompactTopology();
    }
  }

  private Map<String, GraphEntity> archiveEntities(Path path, List<String> requested)
      throws IOException {
    Map<String, GraphEntity> resolved = new LinkedHashMap<>();
    Map<String, Integer> rank = new HashMap<>();
    Set<String> ambiguous = new HashSet<>();
    try (UnifiedGraphArchive archive = UnifiedGraphArchive.open(path);
        UnifiedGraphArchive.EntityCursor cursor = archive.openEntities()) {
      GraphEntity entity;
      while ((entity = cursor.next()) != null) {
        for (String selector : requested) {
          int candidateRank = selector.equals(entity.id()) ? 3
              : selector.equals(entity.attributes().get("fullyQualifiedName")) ? 2
              : selector.equalsIgnoreCase(entity.label()) ? 1 : 0;
          int previousRank = rank.getOrDefault(selector, 0);
          if (candidateRank > previousRank) {
            rank.put(selector, candidateRank);
            resolved.put(selector, entity);
            ambiguous.remove(selector);
          } else if (candidateRank > 0 && candidateRank == previousRank
              && !entity.id().equals(resolved.get(selector).id())) {
            ambiguous.add(selector);
          }
        }
      }
    }
    if (!ambiguous.isEmpty()) {
      throw new IllegalArgumentException("Ambiguous graph entities: " + ambiguous + ". Use exact entity ids.");
    }
    return resolved;
  }

  private GraphEntity assertedEntity(String requested) {
    return GraphEntity.builder("asserted-entity:" + stableId(requested))
        .type("ASSERTED_ENTITY")
        .label(requested)
        .tag("stdio-local")
        .attribute("createdAt", Instant.now().toString())
        .build();
  }

  private UnifiedGraphArchive.Link archiveLink(GraphRelation relation) {
    return new UnifiedGraphArchive.Link(
        -1,
        relation.id(),
        relation.sourceId(),
        relation.targetId(),
        relation.type(),
        relation.weight(),
        relation.confidence(),
        relation.directed(),
        !relation.tags().isEmpty() || relation.timestamp() != null || !relation.attributes().isEmpty(),
        relation.tags(),
        relation.timestamp(),
        relation.attributes(),
        null);
  }

  private List<UnifiedGraphArchive.Link> archiveMatches(
      Path path, LocalProjectGraphBackend.Atom atom, String sourceId, String targetId)
      throws IOException {
    try (UnifiedGraphArchive archive = UnifiedGraphArchive.open(path)) {
      return archive.incidentLinks(sourceId, GraphQueryEngine.Direction.OUTGOING, 1_000_000)
          .stream()
          .filter(link -> this.matchesAtom(link, atom, sourceId, targetId))
          .toList();
    }
  }

  private boolean matchesAtom(
      UnifiedGraphArchive.Link link,
      LocalProjectGraphBackend.Atom atom,
      String sourceId,
      String targetId) {
    return link.type().equals(atom.predicate())
        && link.sourceId().equals(sourceId)
        && (targetId == null || link.targetId().equals(targetId));
  }

  private GraphEntity ensureEntity(UnifiedGraph graph, String requested) {
    GraphEntity existing = this.resolveEntity(graph, requested);
    if (existing != null) {
      return existing;
    } else {
      GraphEntity created = this.assertedEntity(requested);
      graph.addEntity(created);
      return created;
    }
  }

  private GraphEntity resolveEntity(UnifiedGraph graph, String requested) {
    GraphEntity exact = graph.entity(requested).orElse(null);
    if (exact != null) return exact;
    List<GraphEntity> matches = graph.entities().stream()
        .filter(entity -> requested.equals(entity.attributes().get("fullyQualifiedName")))
        .toList();
    if (matches.isEmpty()) {
      matches = graph.entities().stream()
          .filter(entity -> requested.equalsIgnoreCase(entity.label())).toList();
    }
    if (matches.size() > 1) {
      throw new IllegalArgumentException("Ambiguous graph entity: " + requested
          + ". Use an exact entity id: "
          + matches.stream().limit(8).map(GraphEntity::id).toList());
    }
    return matches.isEmpty() ? null : matches.get(0);
  }

  private String relationAtom(GraphRelation relation) {
    return relation.type() + "(" + relation.sourceId() + ", " + relation.targetId() + ")";
  }

  private void publish(
      Path graphPath, String predicate, String operation, String atom, long version) {
    LOCAL_SUBSCRIPTIONS.values().stream()
        .filter(subscription -> this.containsIgnoreCase(subscription.predicates(), predicate))
        .filter(
            subscription ->
                graphPath == null || subscription.graphPath().equals(graphPath.toString()))
        .forEach(
            subscription -> {
              ObjectNode event = this.mapper.createObjectNode();
              event.put("operation", operation);
              event.put("predicate", predicate);
              event.put("atom", atom);
              event.put("version", version);
              event.put("timestamp", Instant.now().toString());
              subscription.events().add(event);
            });
  }

  private boolean containsIgnoreCase(Collection<String> values, String needle) {
    return values.stream().anyMatch(value -> value.equalsIgnoreCase(needle));
  }

  private static Set<String> neighborhood(UnifiedGraph graph, String start, int depth, int max) {
    Set<String> visited = new LinkedHashSet<>();
    if (max <= 0) {
      return visited;
    } else {
      Deque<LocalProjectGraphBackend.NodeDepth> queue = new ArrayDeque<>();
      visited.add(start);
      queue.add(new LocalProjectGraphBackend.NodeDepth(start, 0));

      while (!queue.isEmpty()) {
        LocalProjectGraphBackend.NodeDepth current = queue.removeFirst();
        if (current.depth() < depth && visited.size() < max) {
          for (GraphRelation relation : graph.relationsOf(current.id())) {
            if (visited.size() >= max) {
              break;
            }

            String next =
                relation.sourceId().equals(current.id())
                    ? relation.targetId()
                    : relation.sourceId();
            if (visited.add(next)) {
              queue.addLast(new LocalProjectGraphBackend.NodeDepth(next, current.depth() + 1));
            }
          }
        }
      }

      return visited;
    }
  }

  private double lexicalScore(String text, Set<String> terms) {
    if (terms.isEmpty()) {
      return 0.0;
    } else {
      long matches = terms.stream().filter(term -> !term.isBlank() && text.contains(term)).count();
      return Math.min(1.0, (double) matches / Math.max(1, terms.size()));
    }
  }

  private String localBpmn(List<GraphRelation> flows) {
    StringBuilder xml = new StringBuilder("<definitions><process id=\"project-local\">");
    Set<String> ids = new LinkedHashSet<>();
    flows.forEach(
        flow -> {
          ids.add(flow.sourceId());
          ids.add(flow.targetId());
        });
    ids.forEach(id -> xml.append("<task id=\"").append(id.replace("\"", "&quot;")).append("\"/>"));
    flows.forEach(
        flow ->
            xml.append("<sequenceFlow sourceRef=\"")
                .append(flow.sourceId().replace("\"", "&quot;"))
                .append("\" targetRef=\"")
                .append(flow.targetId().replace("\"", "&quot;"))
                .append("\"/>"));
    return xml.append("</process></definitions>").toString();
  }

  private Map<String, String> addDocuments(
      UnifiedGraph graph,
      Path directory,
      String knowledgeBaseNode,
      String projectId,
      String knowledgeBaseId,
      Long factSheetId)
      throws IOException {
    Map<String, String> documentNodes = new LinkedHashMap<>();
    this.forEachJsonLine(
        directory.resolve("documents.jsonl"),
        document -> {
          String documentId = document.path("documentId").asText("");
          if (!documentId.isBlank()) {
            String nodeId = "document:" + stableId(knowledgeBaseId + "\n" + documentId);
            String label =
                this.firstNonBlank(
                    document.path("title").asText(null),
                    document.path("relativePath").asText(null),
                    documentId);
            Map<String, Object> attributes = this.jsonAttributes(document);
            attributes.put("projectId", projectId);
            attributes.put("knowledgeBaseId", knowledgeBaseId);
            if (factSheetId != null) {
              attributes.put("factSheetId", factSheetId);
            }

            attributes.put("provenance", "local-crawl:documents.jsonl");
            graph.addEntity(
                GraphEntity.builder(nodeId)
                    .type("DOCUMENT")
                    .label(label)
                    .tag("document")
                    .attributes(attributes)
                    .build());
            this.addRelation(
                graph,
                knowledgeBaseNode,
                nodeId,
                "CONTAINS_DOCUMENT",
                Map.of("source", document.path("source").asText("")));
            documentNodes.put(documentId, nodeId);
          }
        });
    Map<String, String> previousChunk = new HashMap<>();
    this.forEachJsonLine(
        directory.resolve("chunks.jsonl"),
        chunk -> {
          String chunkId = chunk.path("chunkId").asText("");
          String documentId = chunk.path("documentId").asText("");
          String documentNode = documentNodes.get(documentId);
          if (!chunkId.isBlank() && documentNode != null) {
            String nodeId = "chunk:" + stableId(knowledgeBaseId + "\n" + chunkId);
            String text = chunk.path("text").asText("");
            Map<String, Object> attributes = this.jsonAttributes(chunk);
            attributes.put("content", text);
            attributes.put("projectId", projectId);
            attributes.put("knowledgeBaseId", knowledgeBaseId);
            if (factSheetId != null) {
              attributes.put("factSheetId", factSheetId);
            }

            attributes.put("provenance", "local-crawl:chunks.jsonl");
            graph.addEntity(
                GraphEntity.builder(nodeId)
                    .type("CHUNK")
                    .label(this.abbreviate(text, 96))
                    .tag("chunk")
                    .attributes(attributes)
                    .build());
            this.addRelation(
                graph,
                documentNode,
                nodeId,
                "HAS_CHUNK",
                Map.of("index", chunk.path("index").asInt()));
            String prior = previousChunk.put(documentId, nodeId);
            if (prior != null) {
              this.addRelation(graph, prior, nodeId, "NEXT_CHUNK", Map.of());
            }
          }
        });
    return documentNodes;
  }

  /**
   * Read the previous crawl's schema while the caller still owns the graph write lock. Missing
   * artifacts are a supported legacy cold-start; a declared artifact that is malformed or stale
   * is not silently ignored.
   */
  GraphSchema loadPersistedSchemaSeed(Path graphPath) throws IOException {
    if (!Files.isRegularFile(graphPath)) {
      return null;
    }
    try (UnifiedGraphArchive previous = UnifiedGraphArchive.open(graphPath)) {
      UnifiedGraphArchive.ArtifactContent content = previous.readArtifact(
          CanonicalGraphSchemaArtifact.ARTIFACT_NAME, 16 * 1024 * 1024);
      if (content == null) {
        return null;
      }
      if (content.truncated()) {
        throw new IOException("Canonical graph schema artifact exceeds the 16 MiB safety limit: "
            + graphPath);
      }
      try {
        return CanonicalGraphSchemaArtifact.decode(this.mapper, content.bytes()).schema();
      } catch (IllegalStateException invalid) {
        throw new IOException("Invalid canonical graph schema artifact in " + graphPath + ": "
            + invalid.getMessage(), invalid);
      }
    }
  }

  private LocalProjectGraphBackend.SemanticExtractionSummary addSemanticExtraction(
      UnifiedGraph graph,
      Path projectRoot,
      Path directory,
      String knowledgeBaseNode,
      String knowledgeBaseId,
      Long factSheetId,
      String crawlJobId,
      JsonNode request,
      GraphSchema persistedSchemaSeed) {
    if (!this.semanticExtractionRequested(request)) {
      return LocalProjectGraphBackend.SemanticExtractionSummary.none();
    } else {
      try {
        JsonNode configuredExtraction = request.get("graphExtraction");
        GraphExtractionConfig extraction =
            configuredExtraction != null && configuredExtraction.isObject()
                ? (GraphExtractionConfig)
                    this.mapper.treeToValue(configuredExtraction, GraphExtractionConfig.class)
                : GraphExtractionConfig.builder().build();
        extraction.setEntityResolution(false);
        preflightNativeChat(projectRoot, request);
        ProcessingRouteConfig route = this.configuredProcessingRoute(request, extraction);
        if (route != null && route.getBackends() != null && !route.getBackends().isEmpty()) {
          List<Document> corpus = new ArrayList<>();
          this.forEachJsonLine(
              directory.resolve("chunks.jsonl"),
              chunk -> {
                String chunkId = chunk.path("chunkId").asText("").trim();
                String text = stripGeneratedCrawlFrontMatter(chunk.path("text").asText(""));
                if (!chunkId.isEmpty() && !text.isBlank()) {
                  Map<String, Object> metadata = this.jsonAttributes(chunk);
                  metadata.remove("text");
                  String sourcePath =
                      this.firstNonBlank(
                          chunk.path("relativePath").asText(null),
                          chunk.path("source").asText(null),
                          chunk.path("documentId").asText(null),
                          chunkId);
                  metadata.put("source_path", sourcePath);
                  metadata.put("knowledgeBaseId", knowledgeBaseId);
                  corpus.add(new Document(chunkId, text, metadata));
                }
              });
          if (corpus.isEmpty()) {
            return LocalProjectGraphBackend.SemanticExtractionSummary.failed(
                "No non-empty crawl chunks were available for semantic extraction");
          } else {
            JsonNode configuredRuntime = request.path("runtimeConfig");
            RuntimeConfig runtimeConfig =
                configuredRuntime.isObject()
                    ? (RuntimeConfig)
                        this.mapper.treeToValue(configuredRuntime, RuntimeConfig.class)
                    : null;
            int parallelism = 4;
            if (runtimeConfig != null) {
              int local =
                  runtimeConfig.getGraphExtractionParallelism() != null
                      ? runtimeConfig.getGraphExtractionParallelism()
                      : 4;
              int remote =
                  runtimeConfig.getGraphExtractionRemoteParallelism() != null
                      ? runtimeConfig.getGraphExtractionRemoteParallelism()
                      : local;
              parallelism = Math.max(1, Math.min(32, Math.max(local, remote)));
            }

            LocalCrawlCliAgentRunner runner =
                new LocalCrawlCliAgentRunner(projectRoot, extraction.getModelName(), this.mapper);
            Map<String, Object> modelRuntime =
                request.path("modelRuntime").isObject()
                    ? new LinkedHashMap<>(
                        (Map<? extends String, ? extends Object>)
                            this.mapper.convertValue(
                                request.path("modelRuntime"),
                                new TypeReference<Map<String, Object>>() {}))
                    : new LinkedHashMap<>();
            modelRuntime.put("projectRoot", projectRoot.toAbsolutePath().normalize().toString());
            if (LocalCrawlJobRegistry.isJobId(crawlJobId)) {
              modelRuntime.put("crawlJobId", crawlJobId);
              modelRuntime.put("knowledgeBaseId", knowledgeBaseId);
            }

            LocalProjectGraphBackend.SemanticExtractionSummary var23;
            try (LocalCrawlServingSession servingSession =
                this.requiresLocalServing(route)
                    ? LocalCrawlServingSession.start(
                        projectRoot,
                        this.servingModel(extraction, route),
                        modelRuntime,
                        this.servingStartupTimeout(runtimeConfig))
                    : null) {
              EmbeddingModel topicEmbeddingModel = null;
              String topicEmbeddingWarning = null;
              if (LocalEmbeddingRuntime.hasConfiguredEncoder(projectRoot, this.mapper)) {
                try {
                  topicEmbeddingModel =
                      LocalEmbeddingRuntime.openEmbeddingModel(
                          projectRoot, this.mapper, modelRuntime);
                } catch (Exception topicFailure) {
                  // The corpus topic pre-pass is an optional enrichment: an encoder that is
                  // configured but not usable (artifact not staged in this spin/project) must
                  // degrade to topic-less extraction instead of failing the whole crawl.
                  topicEmbeddingWarning = this.message(topicFailure);
                }
              }
              if (topicEmbeddingWarning != null && LocalCrawlJobRegistry.isJobId(crawlJobId)) {
                ObjectNode warningEvent = this.mapper.createObjectNode();
                warningEvent.put("eventType", "CORPUS_TOPIC_MODEL_UNAVAILABLE");
                warningEvent.put("crawlJobId", crawlJobId);
                warningEvent.put("knowledgeBaseId", knowledgeBaseId);
                warningEvent.put("error", topicEmbeddingWarning);
                LocalCrawlJobStore.appendTrace(projectRoot, crawlJobId, warningEvent);
              }
              if (topicEmbeddingWarning != null) {
                graph.meta("corpusTopicModelUnavailable", topicEmbeddingWarning);
              }
              if (LocalCrawlJobRegistry.isJobId(crawlJobId)) {
                ObjectNode rtEvent = this.mapper.createObjectNode();
                rtEvent.put("eventType", "RUNTIME_STATUS");
                rtEvent.put("crawlJobId", crawlJobId);
                rtEvent.set("embedding", this.mapper.valueToTree(
                        LocalEmbeddingRuntime.runtimeStatus(projectRoot)));
                LocalCrawlJobStore.appendTrace(projectRoot, crawlJobId, rtEvent);
              }

              try {
                HeadlessUnifiedCorpusExtractor extractor =
                    new HeadlessUnifiedCorpusExtractor(
                        runner,
                        servingSession,
                        nativeChatCompletion(projectRoot),
                        topicEmbeddingModel,
                        null,
                        parallelism,
                        call -> this.persistLlmTrace(projectRoot, crawlJobId, call),
                        trace -> this.persistExtractionTrace(projectRoot, crawlJobId, trace));

                try {
                  ai.kompile.crawl.graph.HeadlessUnifiedCorpusExtractor.Result result =
                      extractor.extract(
                          corpus,
                          extraction,
                          route,
                          runtimeConfig,
                          request.path("maxValidationRetries").asInt(2),
                          LocalCrawlJobRegistry.isJobId(crawlJobId)
                              ? crawlJobId
                              : "local-" + knowledgeBaseId + "-" + UUID.randomUUID(),
                          factSheetId,
                          persistedSchemaSeed);
                  LocalProjectGraphBackend.SemanticExtractionSummary merged =
                      this.mergeSemanticGraph(
                          graph,
                          knowledgeBaseNode,
                          knowledgeBaseId,
                          result.graph(),
                          result.errors(),
                          result.failed() ? null : result.canonicalGraphSchema(),
                          result.failed() ? null : result.schemaFingerprint());
                  result.traceEvents().stream()
                      .filter(eventx -> "CORPUS_TOPIC_EVIDENCE".equals(eventx.get("eventType")))
                      .map(eventx -> eventx.get("payload"))
                      .filter(Objects::nonNull)
                      .findFirst()
                      .ifPresent(evidence -> graph.meta("corpusTopicEvidence", evidence));
                  graph.meta("semanticExtractionEngine", "GraphExtractionOrchestrator");
                  graph.meta(
                      "semanticExtractionRuntime",
                      extractionRuntime(route, result.llmCalls()));
                  if (topicEmbeddingModel != null) {
                    graph.meta(
                        "corpusTopicEmbeddingModel", topicEmbeddingModel.getModelIdentifier());
                    graph.meta("corpusTopicEmbeddingDimension", topicEmbeddingModel.dimensions());
                  }

                  if (servingSession != null) {
                    graph.meta("semanticExtractionModel", servingSession.modelId());
                    graph.meta("semanticExtractionExecutable", servingSession.runtimePath());
                  }

                  graph.meta("semanticExtractionErrors", merged.errors());
                  var23 = merged;
                } catch (Throwable var27) {
                  try {
                    extractor.close();
                  } catch (Throwable var26) {
                    var27.addSuppressed(var26);
                  }

                  throw var27;
                }

                extractor.close();
              } catch (Throwable var28) {
                if (topicEmbeddingModel != null) {
                  try {
                    topicEmbeddingModel.close();
                  } catch (Throwable var25) {
                    var28.addSuppressed(var25);
                  }
                }

                throw var28;
              }

              if (topicEmbeddingModel != null) {
                topicEmbeddingModel.close();
              }
            }

            return var23;
          }
        } else {
          return LocalProjectGraphBackend.SemanticExtractionSummary.failed(
              "Local semantic extraction requires either processingRoute.backends or a"
                  + " graphExtraction.llmProvider=chat[:provider], a CLI agent or serving subprocess");
        }
      } catch (Exception var30) {
        if (LocalCrawlJobRegistry.isJobId(crawlJobId)) {
          ObjectNode event = this.mapper.createObjectNode();
          event.put("eventType", "SEMANTIC_EXTRACTION_FAILURE");
          event.put("crawlJobId", crawlJobId);
          event.put("knowledgeBaseId", knowledgeBaseId);
          event.put("error", this.message(var30));
          LocalCrawlJobStore.appendTrace(projectRoot, crawlJobId, event);
        }

        return LocalProjectGraphBackend.SemanticExtractionSummary.failed(
            "Unified-corpus semantic extraction failed: " + this.message(var30));
      }
    }
  }

  /** Pure, detached selection validation. Never authenticates, calls a model or writes a file. */
  public List<Map<String, Object>> preflightNativeChat(Path root, JsonNode request) throws IOException {
    if (request == null || request.isNull()) return List.of();
    if (usesNativeChat(request)) rejectNativeCredentials(request);
    JsonNode graphConfig = request.path("graphExtraction");
    GraphExtractionConfig extraction = graphConfig.isObject()
        ? mapper.treeToValue(graphConfig, GraphExtractionConfig.class)
        : GraphExtractionConfig.builder().build();
    // Reject raw unknown fields as well: @JsonIgnoreProperties must not erase credentials before
    // validation and leave them in persisted request snapshots.
    for (JsonNode backend : request.path("processingRoute").path("backends")) {
      if ("CHAT_MODEL".equalsIgnoreCase(backend.path("type").asText())) {
        Set<String> allowed = Set.of("id", "displayName", "type", "provider", "modelName", "thinking",
            "priority", "maxConcurrent", "requestsPerMinute", "enabled", "capabilities",
            "backupBackendId", "rateLimitCooldownMultiplier", "disableOnQuotaExhaustion");
        var fields = backend.fieldNames();
        while (fields.hasNext()) {
          String field = fields.next();
          if (!allowed.contains(field)) {
            throw new IOException("CHAT_MODEL rejects request field '" + field
                + "'; credentials/endpoints belong in the host chat configuration");
          }
        }
      }
    }
    if (usesNativeChat(request)) {
      rejectNativeCredentials(graphConfig);
      for (JsonNode capability : graphConfig.path("capabilities")) {
        if (!Set.of("llm", "text").contains(capability.asText())) {
          throw new IOException("Native graph chat supports only text/llm capabilities");
        }
      }
      if (graphConfig.hasNonNull("tools") || graphConfig.hasNonNull("toolChoice")
          || graphConfig.hasNonNull("requiredToolChoice")) {
        throw new IOException("Native graph chat is text-only and does not accept tool requests");
      }
    }
    ProcessingRouteConfig route = configuredProcessingRoute(request, extraction);
    if (route == null || route.getBackends() == null) return List.of();
    ProcessingBackend pinned = route.isFallbackEnabled() ? null : route.getBackends().stream()
        .filter(Objects::nonNull).filter(ProcessingBackend::isEnabled)
        .filter(LocalProjectGraphBackend::textCapable)
        .min(Comparator.comparingInt(ProcessingBackend::getPriority)).orElse(null);
    List<Map<String, Object>> selections = new ArrayList<>();
    for (ProcessingBackend backend : route.getBackends()) {
      if (backend == null) throw new IOException("processingRoute.backends cannot contain null");
      try {
        backend.validateChatModel();
      } catch (IllegalArgumentException invalid) {
        throw new IOException(invalid.getMessage(), invalid);
      }
      if (backend.getType() != ProcessingBackendType.CHAT_MODEL || !backend.isEnabled()) continue;
      if (!route.isFallbackEnabled() && backend != pinned) continue;
      if (backend.getId() == null || backend.getId().isBlank()) {
        throw new IOException("CHAT_MODEL requires a non-empty backend id");
      }
      NativeChatModels.Selection selection = NativeChatModels.resolve(
          root, backend.getProvider(), backend.getModelName(), backend.getThinking());
      selection.requireSupported("text");
      Map<String, Object> preview = new LinkedHashMap<>(selection.preview());
      preview.put("backendId", backend.getId());
      preview.put("execution", "native-chat");
      preview.put("operation", "text");
      preview.put("live", false);
      preview.put("note", "Pure configuration/capability preview; not authentication or inference proof.");
      selections.add(preview);
    }
    return List.copyOf(selections);
  }

  private static void rejectNativeCredentials(JsonNode config) throws IOException {
    var fields = config.fieldNames();
    while (fields.hasNext()) {
      String key = fields.next();
      String normalized = key.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
      if (Set.of("apikey", "endpoint", "endpointurl", "baseurl", "token", "accesstoken",
          "credentials", "authorization", "headers", "password", "secret").contains(normalized)
          || normalized.endsWith("secret") || normalized.endsWith("password")
          || normalized.endsWith("apikey") || normalized.endsWith("token")
          || normalized.contains("credential")) {
        throw new IOException("Native chat rejects request credentials/endpoints; use the host chat configuration");
      }
    }
  }

  /** The CHAT_MODEL route is owned by the MCP host even when a managed URL is configured. */
  public static boolean usesNativeChat(JsonNode request) {
    if (request == null || !request.isObject()) return false;
    if (ProcessingRouteConfig.isNativeChatProvider(request.path("graphExtraction").path("llmProvider").asText(null))
        || ProcessingRouteConfig.isNativeChatProvider(request.path("llmProvider").asText(null))
        || ProcessingRouteConfig.isNativeChatProvider(request.path("provider").asText(null))
        || ProcessingRouteConfig.isNativeChatProvider(request.path("model_provider").asText(null))) return true;
    for (JsonNode backend : request.path("processingRoute").path("backends")) {
      if ("CHAT_MODEL".equalsIgnoreCase(backend.path("type").asText())) return true;
    }
    return usesNativeChat(request.get("config"));
  }

  private NativeChatCompletion nativeChatCompletion(Path root) {
    if (nativeChatOverride != null) return nativeChatOverride;
    return new NativeChatCompletion() {
      @Override
      public String complete(String provider, String model, String prompt, String system, Duration timeout)
          throws Exception {
        return NativeChatModels.complete(root, provider, model, prompt, system, timeout);
      }

      @Override
      public String complete(String provider, String model, String thinking, String prompt,
                             String system, Duration timeout) throws Exception {
        return NativeChatModels.complete(root, provider, model, thinking, prompt, system, timeout);
      }

      @Override
      public boolean supportsStructuredChat(String provider, String model, String thinking) {
        try {
          NativeChatModels.Selection selection = NativeChatModels.resolve(root, provider, model, thinking);
          selection.requireSupported("json_schema");
          return true;
        } catch (Exception unsupported) {
          return false;
        }
      }

      @Override
      public String completeStructuredJson(String provider, String model, String thinking,
                                            String prompt, String system,
                                            Map<String, Object> schema,
                                            Duration timeout) throws Exception {
        NativeChatModels.Selection selection = NativeChatModels.resolve(root, provider, model, thinking);
        selection.requireSupported("json_schema");
        return NativeChatModels.call(root, selection, prompt, system, List.of(),
            mapper.valueToTree(schema), timeout, 1_048_576);
      }
    };
  }

  private static String extractionRuntime(ProcessingRouteConfig route, List<LlmCallRecord> calls) {
    Set<String> successful = calls.stream().filter(LlmCallRecord::isSuccess)
        .map(LlmCallRecord::getBackendId).collect(java.util.stream.Collectors.toSet());
    Set<String> runtimes = new LinkedHashSet<>();
    for (ProcessingBackend backend : route.getBackends()) {
      boolean servingCall = backend.getType() == ProcessingBackendType.LOCAL_MODEL
          && successful.stream().anyMatch(id -> id != null && (id.equals("serving") || id.startsWith("serving:")));
      if (!successful.contains(backend.getId()) && !servingCall) continue;
      runtimes.add(switch (backend.getType()) {
        case CHAT_MODEL -> "native-chat";
        case CLI_AGENT -> "cli-agent-subprocess";
        case API_AGENT -> "api-agent";
        case LOCAL_MODEL -> "kompile-serving-subprocess";
      });
    }
    if (runtimes.isEmpty()) return "unavailable";
    return runtimes.size() == 1 ? runtimes.iterator().next() : String.join("+", runtimes);
  }

  private void persistLlmTrace(Path projectRoot, String crawlJobId, LlmCallRecord call) {
    if (LocalCrawlJobRegistry.isJobId(crawlJobId) && call != null) {
      ObjectNode event = this.mapper.createObjectNode();
      event.put("eventType", "LLM_CALL");
      event.put("crawlJobId", crawlJobId);
      event.set("payload", this.mapper.valueToTree(call));
      LocalCrawlJobStore.appendTrace(projectRoot, crawlJobId, event);
    }
  }

  private void persistExtractionTrace(
      Path projectRoot, String crawlJobId, Map<String, Object> trace) {
    if (LocalCrawlJobRegistry.isJobId(crawlJobId) && trace != null) {
      ObjectNode event = (ObjectNode) this.mapper.valueToTree(trace);
      if (!event.hasNonNull("eventType")) {
        event.put("eventType", "EXTRACTION_TRACE");
      }

      event.put("crawlJobId", crawlJobId);
      LocalCrawlJobStore.appendTrace(projectRoot, crawlJobId, event);
    }
  }

  ProcessingRouteConfig configuredProcessingRoute(
      JsonNode request, GraphExtractionConfig extraction) throws IOException {
    if (ProcessingRouteConfig.isNativeChatProvider(extraction.getLlmProvider())) {
      if (request.path("processingRoute").path("backends").isArray()
          && !request.path("processingRoute").path("backends").isEmpty()) {
        throw new IOException("Select native graph chat with either chat[:provider] or "
            + "processingRoute CHAT_MODEL backends, not both");
      }
      return ProcessingRouteConfig.nativeChatRoute(extraction.getLlmProvider(), extraction.getModelName(),
          extraction.getThinking());
    }
    JsonNode configuredRoute = request.get("processingRoute");
    if (configuredRoute != null && configuredRoute.isObject()) {
      ProcessingRouteConfig route =
          (ProcessingRouteConfig)
              this.mapper.treeToValue(configuredRoute, ProcessingRouteConfig.class);
      if (route.getBackends() != null && !route.getBackends().isEmpty()) {
        for (ProcessingBackend backend : route.getBackends()) {
          if (backend != null && (backend.getModelName() == null || backend.getModelName().isBlank())) {
            backend.setModelName(extraction.getModelName());
          }
          if (backend != null && (backend.getThinking() == null || backend.getThinking().isBlank())) {
            backend.setThinking(extraction.getThinking());
          }
        }
        return route;
      }
    }

    String provider = extraction.getLlmProvider();
    if (this.isServingProvider(provider)) {
      return ProcessingRouteConfig.builder()
          .fallbackEnabled(false)
          .servingLaneEnabled(true)
          .backends(
              List.of(
                  ProcessingBackend.builder()
                      .id("serving")
                      .displayName("Kompile local serving subprocess")
                      .type(ProcessingBackendType.LOCAL_MODEL)
                      .agentName("serving")
                      .modelName(extraction.getModelName())
                      .priority(1)
                      .capabilities(List.of("llm"))
                      .build()))
          .build();
    } else {
      String agent = cliAgentForProvider(provider);
      return agent == null
          ? null
          : ProcessingRouteConfig.builder()
              .fallbackEnabled(true)
              .servingLaneEnabled(false)
              .backends(
                  List.of(
                      ProcessingBackend.builder()
                          .id(agent)
                          .displayName(agent)
                          .type(ProcessingBackendType.CLI_AGENT)
                          .agentName(agent)
                          .modelName(extraction.getModelName())
                          .priority(1)
                          .capabilities(List.of("llm"))
                          .build()))
              .build();
    }
  }

  private boolean requiresLocalServing(ProcessingRouteConfig route) {
    if (route == null || route.getBackends() == null) return false;
    Stream<ProcessingBackend> candidates = route.getBackends().stream()
        .filter(Objects::nonNull).filter(ProcessingBackend::isEnabled)
        .filter(LocalProjectGraphBackend::textCapable)
        .sorted(Comparator.comparingInt(ProcessingBackend::getPriority));
    if (!route.isFallbackEnabled()) candidates = candidates.limit(1);
    return candidates.anyMatch(backend -> backend.getType() == ProcessingBackendType.LOCAL_MODEL);
  }

  private static boolean textCapable(ProcessingBackend backend) {
    return backend.getType() == ProcessingBackendType.CHAT_MODEL
        || backend.getCapabilities() == null || backend.getCapabilities().isEmpty()
        || backend.getCapabilities().contains("llm");
  }

  private String servingModel(GraphExtractionConfig extraction, ProcessingRouteConfig route) {
    if (extraction.getModelName() != null && !extraction.getModelName().isBlank()) {
      return extraction.getModelName().trim();
    } else {
      if (route != null && route.getBackends() != null) {
        for (ProcessingBackend backend : route.getBackends()) {
          if (backend != null
              && backend.getType() == ProcessingBackendType.LOCAL_MODEL
              && backend.getModelName() != null
              && !backend.getModelName().isBlank()) {
            return backend.getModelName().trim();
          }
        }
      }

      return null;
    }
  }

  private int servingStartupTimeout(RuntimeConfig runtimeConfig) {
    Integer configured = runtimeConfig == null ? null : runtimeConfig.getLlmCallTimeoutSeconds();
    return configured == null ? 300 : Math.max(30, configured);
  }

  private boolean isServingProvider(String provider) {
    return provider == null
        ? false
        : Set.of("serving", "kompile-local", "local-serving")
            .contains(provider.trim().toLowerCase(Locale.ROOT));
  }

  static String cliAgentForProvider(String provider) {
    if (ProcessingRouteConfig.isNativeChatProvider(provider)) return null;
    if (provider != null && !provider.isBlank() && !"default".equalsIgnoreCase(provider)) {
      String normalized = provider.trim().toLowerCase(Locale.ROOT);
      if (normalized.endsWith("-cli")) {
        return normalized;
      } else {
        return switch (normalized) {
          case "anthropic", "claude", "claude-code" -> "claude-cli";
          case "openai", "openai-codex", "codex" -> "codex-cli";
          case "google", "gemini" -> "gemini-cli";
          case "opencode" -> "opencode-cli";
          case "qwen" -> "qwen-cli";
          case "pi" -> "pi-cli";
          default -> null;
        };
      }
    } else {
      return null;
    }
  }

  private boolean semanticExtractionRequested(JsonNode request) {
    if (request == null || request.isNull()) {
      return false;
    } else if (request.hasNonNull("graphExtraction") || request.hasNonNull("processingRoute")) {
      return true;
    } else {
      JsonNode steps = request.get("steps");
      if (steps != null && steps.isArray()) {
        for (JsonNode step : steps) {
          if ("GRAPH_EXTRACTION".equalsIgnoreCase(step.asText(""))) {
            return true;
          }
        }
      }

      return false;
    }
  }

  private LocalProjectGraphBackend.SemanticExtractionSummary mergeSemanticGraph(
      UnifiedGraph target,
      String knowledgeBaseNode,
      String knowledgeBaseId,
      Graph extracted,
      List<String> extractionErrors,
      GraphSchema canonicalGraphSchema,
      String schemaFingerprint) {
    if (extracted == null) {
      return new LocalProjectGraphBackend.SemanticExtractionSummary(
          0,
          0,
          extractionErrors == null
              ? List.of("Extraction returned no graph")
              : List.copyOf(extractionErrors),
          canonicalGraphSchema,
          schemaFingerprint);
    } else {
      Map<String, String> localIds = new HashMap<>();
      int entities = 0;

      for (Entity entity :
          extracted.getEntities() == null ? List.<Entity>of() : extracted.getEntities()) {
        if (entity != null) {
          String sourceId = this.firstNonBlank(entity.getId(), entity.getTitle());
          if (sourceId != null) {
            String localId = "semantic:" + stableId(knowledgeBaseId + "\n" + sourceId);
            String title = this.firstNonBlank(entity.getTitle(), sourceId);
            String type = this.firstNonBlank(entity.getType(), "ENTITY");
            double confidence = entity.getConfidence() != null ? entity.getConfidence() : 1.0;
            Map<String, Object> attributes = new LinkedHashMap<>();
            if (entity.getMetadata() != null) {
              attributes.putAll(entity.getMetadata());
            }

            if (entity.getDescription() != null) {
              attributes.put("description", entity.getDescription());
            }

            if (entity.getAliases() != null) {
              attributes.put("aliases", entity.getAliases());
            }

            if (entity.getTextUnits() != null) {
              attributes.put("textUnits", entity.getTextUnits());
            }

            attributes.put("extractionId", sourceId);
            attributes.put("provenance", "unified-corpus-extraction");
            target.addEntity(
                GraphEntity.builder(localId)
                    .type(type)
                    .label(title)
                    .weight(confidence)
                    .confidence(confidence)
                    .tag("semantic")
                    .attributes(this.nonNullAttributes(attributes))
                    .build());
            this.addRelation(
                target,
                knowledgeBaseNode,
                localId,
                "CONTAINS_ENTITY",
                Map.of("provenance", "unified-corpus-extraction"));
            localIds.put(sourceId, localId);
            localIds.put(sourceId.toLowerCase(Locale.ROOT), localId);
            localIds.put(title, localId);
            localIds.put(title.toLowerCase(Locale.ROOT), localId);
            entities++;
          }
        }
      }

      int relations = 0;

      for (Relationship relationship :
          extracted.getRelationships() == null
              ? List.<Relationship>of()
              : extracted.getRelationships()) {
        if (relationship != null) {
          String source = this.semanticLocalId(localIds, relationship.getSource());
          String targetId = this.semanticLocalId(localIds, relationship.getTarget());
          if (source != null && targetId != null) {
            Map<String, Object> attributesx = new LinkedHashMap<>();
            if (relationship.getMetadata() != null) {
              attributesx.putAll(relationship.getMetadata());
            }

            if (relationship.getDescription() != null) {
              attributesx.put("description", relationship.getDescription());
            }

            if (relationship.getOccurredAt() != null) {
              attributesx.put("occurredAt", relationship.getOccurredAt());
            }

            attributesx.put("provenance", "unified-corpus-extraction");
            this.addWeightedRelation(
                target,
                source,
                targetId,
                this.firstNonBlank(relationship.getType(), "RELATED_TO"),
                relationship.getWeight() != null ? relationship.getWeight() : 1.0,
                relationship.getConfidence() != null ? relationship.getConfidence() : 1.0,
                attributesx);
            relations++;
          }
        }
      }

      return new LocalProjectGraphBackend.SemanticExtractionSummary(
          entities,
          relations,
          extractionErrors == null ? List.of() : List.copyOf(extractionErrors),
          canonicalGraphSchema,
          schemaFingerprint);
    }
  }

  private LocalProjectGraphBackend.SemanticExtractionSummary mergeSemanticGraph(
      UnifiedGraph target,
      String knowledgeBaseNode,
      String knowledgeBaseId,
      Graph extracted,
      List<String> extractionErrors) {
    return mergeSemanticGraph(target, knowledgeBaseNode, knowledgeBaseId, extracted,
        extractionErrors, null, null);
  }

  private String semanticLocalId(Map<String, String> localIds, String sourceId) {
    if (sourceId == null) {
      return null;
    } else {
      String direct = localIds.get(sourceId);
      return direct != null ? direct : localIds.get(sourceId.toLowerCase(Locale.ROOT));
    }
  }

  private void refreshCodeProjectIndexes(
      List<LocalProjectGraphBackend.CodeProjectSource> codeProjects, String projectId)
      throws Exception {
    for (LocalProjectGraphBackend.CodeProjectSource source : codeProjects) {
      if (Files.isDirectory(source.root())) {
        String indexProjectId =
            this.firstNonBlank(
                source.codeProjectId(),
                projectId + "-" + stableId(source.root().toString()).substring(0, 12));

        try (PrintStream quiet =
            new PrintStream(OutputStream.nullOutputStream(), false, StandardCharsets.UTF_8)) {
          new LocalCodeIndexer()
              .index(
                  source.root(),
                  indexProjectId,
                  this.csv(source.includePatterns()),
                  this.csv(source.excludePatterns()),
                  quiet);
        }
      }
    }
  }

  private int addCodeProjects(
      UnifiedGraph graph,
      String knowledgeBaseNode,
      Map<String, String> documentNodes,
      String projectId,
      Long factSheetId,
      List<LocalProjectGraphBackend.CodeProjectSource> codeProjects)
      throws Exception {
    return this.addCodeProjects(
        graph, knowledgeBaseNode, documentNodes, projectId, factSheetId, codeProjects, null);
  }

  private int addCodeProjects(
      UnifiedGraph graph,
      String knowledgeBaseNode,
      Map<String, String> documentNodes,
      String projectId,
      Long factSheetId,
      List<LocalProjectGraphBackend.CodeProjectSource> codeProjects,
      Set<String> projectedEntityIds)
      throws Exception {
    int codeEntities = 0;
    Map<String, String> documentByRelativePath = new HashMap<>();

    for (GraphEntity entity : graph.entities()) {
      if ("DOCUMENT".equals(entity.type())) {
        Object relative = entity.attributes().get("relativePath");
        if (relative != null) {
          documentByRelativePath.put(this.normalizePath(String.valueOf(relative)), entity.id());
        }
      }
    }

    for (LocalProjectGraphBackend.CodeProjectSource source : codeProjects) {
      if (Files.isDirectory(source.root())) {
        String indexProjectId =
            this.firstNonBlank(
                source.codeProjectId(),
                projectId + "-" + stableId(source.root().toString()).substring(0, 12));
        String codeProjectNode = "code-project:" + stableId(indexProjectId);
        Map<String, Object> codeProjectAttributes = new LinkedHashMap<>();
        codeProjectAttributes.put("projectId", projectId);
        codeProjectAttributes.put("codeProjectId", indexProjectId);
        codeProjectAttributes.put("_kompileProjectionOwner", "local-code-index");
        codeProjectAttributes.put("rootPath", source.root().toString());
        if (factSheetId != null) {
          codeProjectAttributes.put("factSheetId", factSheetId);
        }

        graph.addEntity(
            GraphEntity.builder(codeProjectNode)
                .type("CODE_PROJECT")
                .label(source.name())
                .tag("code")
                .attributes(codeProjectAttributes)
                .build());
        if (projectedEntityIds != null) {
          projectedEntityIds.add(codeProjectNode);
        }

        this.addRelation(graph, knowledgeBaseNode, codeProjectNode, "HAS_CODE_PROJECT", Map.of());
        Path indexDirectory = LocalCodeIndexer.getIndexDir(indexProjectId);
        Map<String, String> idsByFqn = new LinkedHashMap<>();
        int[] sourceEntityCount = new int[] {0};

        try (IndexDatabase database = IndexDatabase.openReadOnly(indexDirectory)) {
          database.beginTransaction();
          String snapshotGeneration = database.getIndexGeneration();
          database.visitGraphSnapshot(
              entityx -> {
                String fqn = this.string(entityx.get("fullyQualifiedName"));
                if (fqn != null && !fqn.isBlank()) {
                  String type =
                      this.firstNonBlank(this.string(entityx.get("entityType")), "CODE_SYMBOL");
                  String declarationIdentity =
                      this.codeDeclarationIdentity(
                          type, fqn, this.string(entityx.get("signature")));
                  String entityId = this.codeEntityId(indexProjectId, type, declarationIdentity);
                  // Imports are real searchable entities, but they must not displace the actual
                  // declaration used as a relation endpoint. Multiple declaration ids for one FQN
                  // (for example overloads) remain intentionally ambiguous and use a neutral ref.
                  if (!"IMPORT".equals(type)) {
                    String existingId = idsByFqn.putIfAbsent(fqn, entityId);
                    if (existingId != null && !existingId.equals(entityId)) {
                      idsByFqn.put(
                          fqn, this.addCodeReference(graph, indexProjectId, fqn, projectedEntityIds));
                    }
                  }

                  Map<String, Object> attributes = new LinkedHashMap<>();
                  attributes.put("codeProjectId", indexProjectId);
                  attributes.put("_kompileProjectionOwner", "local-code-index");
                  attributes.put("fullyQualifiedName", fqn);
                  attributes.put(
                      "metadataRef",
                      "code-index:"
                          + indexProjectId
                          + ":entity:"
                          + stableId(type + "\n" + declarationIdentity));
                  attributes.put("declarationKey", stableId(type + "\n" + declarationIdentity));
                  for (String key :
                      List.of("filePath", "startLine", "endLine", "signature", "language", "visibility")) {
                    Object value = entityx.get(key);
                    if (value != null && (!(value instanceof String text) || !text.isBlank())) {
                      attributes.put(key, value);
                    }
                  }
                  graph.addEntity(
                      GraphEntity.builder(entityId)
                          .type(type)
                          .label(this.firstNonBlank(this.string(entityx.get("name")), fqn))
                          .tag("code")
                          .attributes(attributes)
                          .build());
                  if (projectedEntityIds != null) {
                    projectedEntityIds.add(entityId);
                  }

                  sourceEntityCount[0]++;
                  this.addRelation(
                      graph,
                      codeProjectNode,
                      entityId,
                      "FILE".equals(type) ? "CONTAINS_FILE" : "DECLARES_SYMBOL",
                      Map.of());
                  if ("FILE".equals(type)) {
                    String filePath =
                        this.normalizePath(
                            this.firstNonBlank(this.string(entityx.get("filePath")), fqn));
                    String document = documentByRelativePath.get(filePath);
                    if (document != null) {
                      this.addRelation(graph, entityId, document, "REPRESENTS_DOCUMENT", Map.of());
                    }
                  }
                }
              },
              relation -> {
                String sourceFqn = this.string(relation.get("sourceFqn"));
                String targetFqn =
                    this.firstNonBlank(
                        this.string(relation.get("targetFqn")),
                        this.string(relation.get("targetName")));
                String type =
                    this.firstNonBlank(this.string(relation.get("relationType")), "REFERENCES");
                if (sourceFqn != null && targetFqn != null) {
                  String sourceId =
                      idsByFqn.computeIfAbsent(
                          sourceFqn,
                          value ->
                              this.addCodeReference(
                                  graph, indexProjectId, value, projectedEntityIds));
                  String targetId =
                      idsByFqn.computeIfAbsent(
                          targetFqn,
                          value ->
                              this.addCodeReference(
                                  graph, indexProjectId, value, projectedEntityIds));
                  Map<String, Object> provenance = new LinkedHashMap<>();
                  provenance.put("codeProjectId", indexProjectId);
                  provenance.put("_kompileProjectionOwner", "local-code-index");
                  provenance.put("evidenceKind", "CALLS".equals(type) ? "heuristic-source-pattern" : "source-structure");
                  for (String key : List.of("filePath", "line", "targetHint")) {
                    if (relation.get(key) != null) provenance.put(key, relation.get(key));
                  }
                  // Keep separate call sites as evidence instead of collapsing them into a triple.
                  String relationId = "relation:" + stableId(sourceId + "\n" + type + "\n" + targetId
                      + "\n" + relation.get("filePath") + "\n" + relation.get("line"));
                  graph.addRelation(GraphRelation.builder(relationId, sourceId, targetId)
                      .type(type).weight(1.0).confidence(1.0).directed(true)
                      .attributes(provenance).build());
                }
              });
          database.commit();
          if (snapshotGeneration != null) graph.meta("codeIndexGeneration." + indexProjectId, snapshotGeneration);
          graph.meta("codeIndexRoot." + indexProjectId, source.root().toAbsolutePath().normalize().toString());
          graph.meta("phase.codeProjection." + indexProjectId, "COMPLETED");
          graph.meta("phase.codeLearning." + indexProjectId, "STALE");
          graph.meta("codeProjectionVersion", CODE_PROJECTION_VERSION);
        }

        codeEntities += sourceEntityCount[0];
      }
    }

    return codeEntities;
  }

  private String addCodeReference(UnifiedGraph graph, String codeProjectId, String fqn) {
    return this.addCodeReference(graph, codeProjectId, fqn, null);
  }

  private String addCodeReference(
      UnifiedGraph graph, String codeProjectId, String fqn, Set<String> projectedEntityIds) {
    String id = this.codeEntityId(codeProjectId, "REFERENCE", fqn);
    graph.addEntity(
        GraphEntity.builder(id)
            .type("CODE_SYMBOL_REFERENCE")
            .label(fqn)
            .tag("code")
            .attribute("fullyQualifiedName", fqn)
            .attribute("codeProjectId", codeProjectId)
            .attribute("_kompileProjectionOwner", "local-code-index")
            .attribute("metadataRef", "code-index:" + codeProjectId + ":reference:" + stableId(fqn))
            .attribute("resolved", false)
            .build());
    if (projectedEntityIds != null) {
      projectedEntityIds.add(id);
    }

    return id;
  }

  private boolean isCodeProjectionOwned(GraphEntity entity, String codeProjectId) {
    if (!codeProjectId.equals(this.string(entity.attributes().get("codeProjectId")))) {
      return false;
    } else {
      String owner = this.string(entity.attributes().get("_kompileProjectionOwner"));
      if ("local-code-index".equals(owner)) {
        return true;
      } else {
        return "local-code-index".equals(this.string(entity.attributes().get("provenance")))
            ? true
            : "CODE_SYMBOL_REFERENCE".equals(entity.type())
                && Boolean.FALSE.equals(entity.attributes().get("resolved"))
                && entity.attributes().containsKey("fullyQualifiedName");
      }
    }
  }

  private boolean isCodeProjectionOwned(GraphRelation relation, String codeProjectId) {
    return this.isCodeProjectionOwned(relation.attributes(), codeProjectId);
  }

  private boolean isCodeProjectionOwned(Map<String, Object> attributes, String codeProjectId) {
    if (!codeProjectId.equals(this.string(attributes.get("codeProjectId")))) {
      return false;
    } else {
      String owner = this.string(attributes.get("_kompileProjectionOwner"));
      return "local-code-index".equals(owner)
          ? true
          : "local-code-index".equals(this.string(attributes.get("provenance")));
    }
  }

  private UnifiedGraph captureAnalysisAssets(UnifiedGraph source) {
    UnifiedGraph assets = new UnifiedGraph();
    source.entities().stream().filter(GraphEntity::hasEmbedding).forEach(assets::addEntity);
    source.relations().stream().filter(GraphRelation::hasEmbedding).forEach(assets::addRelation);

    for (VectorLayer layer : source.vectorLayers().values()) {
      VectorLayer copy = new VectorLayer(layer.name(), layer.target(), layer.dim(), layer.dtype());
      layer.rows().forEach((id, values) -> copy.put(id, (double[]) values.clone()));
      if (!copy.isEmpty()) {
        assets.putVectorLayer(copy);
      }
    }

    source.entityOpinions().forEach(assets::putEntityOpinion);
    source.relationOpinions().forEach(assets::putRelationOpinion);
    source.weightMaps().forEach(assets::putWeightMap);
    source.artifacts().forEach(assets::putArtifact);
    return assets;
  }

  private void retainCompatibleAssets(UnifiedGraph previous, UnifiedGraph current) {
    if (previous != null) {
      Set<String> entityIds = new LinkedHashSet<>();
      current.entities().forEach(entity -> entityIds.add(entity.id()));
      Set<String> relationIds = new LinkedHashSet<>();
      current.relations().forEach(relation -> relationIds.add(relation.id()));

      for (GraphEntity old : previous.entities()) {
        if (old.hasEmbedding()) {
          current
              .entity(old.id())
              .ifPresent(
                  entity ->
                      current.addEntity(
                          new SimpleGraphEntity(
                              entity.id(),
                              entity.type(),
                              entity.label(),
                              entity.weight(),
                              entity.confidence(),
                              entity.tags(),
                              (double[]) old.embedding().clone(),
                              entity.timestamp(),
                              entity.attributes())));
        }
      }

      for (GraphRelation oldx : previous.relations()) {
        if (oldx.hasEmbedding()) {
          current
              .relation(oldx.id())
              .ifPresent(
                  relation ->
                      current.addRelation(
                          new SimpleGraphRelation(
                              relation.id(),
                              relation.sourceId(),
                              relation.targetId(),
                              relation.type(),
                              relation.weight(),
                              relation.confidence(),
                              relation.directed(),
                              relation.tags(),
                              (double[]) oldx.embedding().clone(),
                              relation.timestamp(),
                              relation.attributes())));
        }
      }

      for (VectorLayer oldxx : previous.vectorLayers().values()) {
        VectorLayer retained =
            new VectorLayer(oldxx.name(), oldxx.target(), oldxx.dim(), oldxx.dtype());

        for (Entry<String, double[]> row : oldxx.rows().entrySet()) {
          boolean keep =
              oldxx.target() == Target.GLOBAL
                  || oldxx.target() == Target.ENTITY && entityIds.contains(row.getKey())
                  || oldxx.target() == Target.RELATION && relationIds.contains(row.getKey());
          if (keep) {
            retained.put(row.getKey(), (double[]) row.getValue().clone());
          }
        }

        if (!retained.isEmpty()) {
          current.putVectorLayer(retained);
        }
      }

      previous
          .entityOpinions()
          .forEach(
              (id, opinion) -> {
                if (entityIds.contains(id)) {
                  current.putEntityOpinion(id, opinion);
                }
              });
      previous
          .relationOpinions()
          .forEach(
              (id, opinion) -> {
                if (relationIds.contains(id)) {
                  current.putRelationOpinion(id, opinion);
                }
              });
      previous.weightMaps().forEach(current::putWeightMap);
      previous.artifacts().forEach(current::putArtifact);
    }
  }

  private ToolResult trainAction(JsonNode params, LocalProjectGraphBackend.GraphSelection selection)
      throws Exception {
    String algorithm = params.path("algorithm").asText("ROTATE").toUpperCase(Locale.ROOT);
    int dim = this.bounded(params.path("embedding_dim").asInt(32), 2, 256);
    int epochs = this.bounded(params.path("epochs").asInt(8), 1, 500);
    double learningRate = params.path("learning_rate").asDouble(0.05);
    LocalProjectGraphBackend.TrainingRequest request =
        new LocalProjectGraphBackend.TrainingRequest(
            true,
            algorithm,
            dim,
            epochs,
            Math.max(1.0E-4, Math.min(1.0, learningRate)),
            epochs,
            1234L);
    return this.withGraphWriteLock(
        selection.path(),
        () -> {
          UnifiedGraph graph = UnifiedGraph.load(selection.path());
          ai.kompile.cli.main.chat.tools.grounding.ProjectLocalLearningSubprocessExecutor.Result
              execution =
                  this.learningExecutor.learn(
                      graph,
                      new Plan(
                          request.toConfig(), new Config(false, 1, 1, 1, 0.35, 25), "EXPLICIT_KGE"),
                      selection.path().getParent(),
                      "graph-embeddings-train");
          LocalProjectGraphBackend.TrainingSummary summary =
              this.trainingSummary(execution.graph());
          stampCodeGenerations(execution.graph(), "codeKgeGeneration.");
          this.saveAtomic(execution.graph(), selection.path());
          return ToolResult.success(
              "graph_embeddings.train",
              "Project-local embedding training completed in the bounded learning process.\n\n"
                  + "Job ID: "
                  + summary.jobId()
                  + "\nStatus: COMPLETED\nAlgorithm: "
                  + summary.algorithm()
                  + "\nEntities embedded: "
                  + summary.entities()
                  + "\nRelation types embedded: "
                  + summary.relationTypes()
                  + "\n",
              Map.of(
                  "jobId",
                  summary.jobId(),
                  "status",
                  "COMPLETED",
                  "algorithm",
                  summary.algorithm(),
                  "backend",
                  "project-local",
                  "execution",
                  execution.execution(),
                  "entitiesEmbedded",
                  summary.entities()));
        });
  }

  private ToolResult jobsAction(LocalProjectGraphBackend.GraphSelection selection) {
    JsonNode model = this.modelMetadata(selection.graph());
    return model == null
        ? ToolResult.success(
            "graph_embeddings.jobs",
            "No project-local embedding training jobs found. Run action=train.",
            Map.of("count", 0, "backend", "project-local"))
        : ToolResult.success(
            "graph_embeddings.jobs",
            "Embedding Jobs\n\n- Job: "
                + model.path("jobId").asText("")
                + "\n  Status: COMPLETED\n  Algorithm: "
                + model.path("algorithm").asText("")
                + "\n  Epochs: "
                + model.path("epochs").asInt()
                + "\n",
            Map.of("count", 1, "backend", "project-local"));
  }

  private ToolResult jobStatusAction(
      JsonNode params, LocalProjectGraphBackend.GraphSelection selection) {
    JsonNode model = this.modelMetadata(selection.graph());
    String requested = params.path("job_id").asText("");
    return model != null && (requested.isBlank() || requested.equals(model.path("jobId").asText()))
        ? ToolResult.success(
            "graph_embeddings.job_status",
            "Embedding Job: "
                + model.path("jobId").asText("")
                + "\n\nStatus: COMPLETED\nAlgorithm: "
                + model.path("algorithm").asText("")
                + "\nEpochs: "
                + model.path("epochs").asInt()
                + "\nEntities embedded: "
                + model.path("entities").asInt()
                + "\n",
            Map.of(
                "jobId",
                model.path("jobId").asText(),
                "status",
                "COMPLETED",
                "backend",
                "project-local"))
        : ToolResult.error("Project-local embedding job not found: " + requested);
  }

  private ToolResult scoreAction(
      JsonNode params, LocalProjectGraphBackend.GraphSelection selection) {
    String head = this.requiredText(params, "head");
    String relation = this.requiredText(params, "relation");
    String tail = this.requiredText(params, "tail");
    LocalProjectGraphBackend.ModelView model = this.model(selection.graph());
    String headId = this.resolveEntityId(selection.graph(), head);
    String tailId = this.resolveEntityId(selection.graph(), tail);
    double score = this.plausibility(model, headId, relation, tailId);
    return ToolResult.success(
        "graph_embeddings.score",
        String.format(
            Locale.ROOT,
            "Triple Plausibility Score\n\n  %s —[%s]→ %s\n  Plausibility score: %.4f\n",
            head,
            relation,
            tail,
            score),
        Map.of(
            "head",
            head,
            "relation",
            relation,
            "tail",
            tail,
            "score",
            score,
            "backend",
            "project-local"));
  }

  private ToolResult predictAction(
      JsonNode params,
      LocalProjectGraphBackend.GraphSelection selection,
      LocalProjectGraphBackend.PredictionTarget target) {
    LocalProjectGraphBackend.ModelView model = this.model(selection.graph());
    int topK = this.bounded(params.path("top_k").asInt(10), 1, 100);
    String head = params.path("head").asText("");
    String relation = params.path("relation").asText("");
    String tail = params.path("tail").asText("");
    List<LocalProjectGraphBackend.Scored> scored = new ArrayList<>();
    if (target == LocalProjectGraphBackend.PredictionTarget.TAIL) {
      String headId = this.resolveEntityId(selection.graph(), this.required(head, "head"));
      this.required(relation, "relation");

      for (GraphEntity candidate : selection.graph().entities()) {
        scored.add(
            new LocalProjectGraphBackend.Scored(
                candidate.label(), this.plausibility(model, headId, relation, candidate.id())));
      }
    } else if (target == LocalProjectGraphBackend.PredictionTarget.HEAD) {
      String tailId = this.resolveEntityId(selection.graph(), this.required(tail, "tail"));
      this.required(relation, "relation");

      for (GraphEntity candidate : selection.graph().entities()) {
        scored.add(
            new LocalProjectGraphBackend.Scored(
                candidate.label(), this.plausibility(model, candidate.id(), relation, tailId)));
      }
    } else {
      String headId = this.resolveEntityId(selection.graph(), this.required(head, "head"));
      String tailId = this.resolveEntityId(selection.graph(), this.required(tail, "tail"));

      for (String candidate : model.relations().keySet()) {
        scored.add(
            new LocalProjectGraphBackend.Scored(
                candidate, this.plausibility(model, headId, candidate, tailId)));
      }
    }

    scored.sort(
        Comparator.comparingDouble(LocalProjectGraphBackend.Scored::score)
            .reversed()
            .thenComparing(LocalProjectGraphBackend.Scored::label));
    if (scored.size() > topK) {
      scored = new ArrayList<>(scored.subList(0, topK));
    }

    StringBuilder output = new StringBuilder("Project-local KGE predictions\n\n");

    for (LocalProjectGraphBackend.Scored item : scored) {
      output.append(String.format(Locale.ROOT, "  %.4f  %s%n", item.score(), item.label()));
    }

    return ToolResult.success(
        "graph_embeddings.predict_" + target.name().toLowerCase(Locale.ROOT),
        output.toString(),
        Map.of("count", scored.size(), "backend", "project-local"));
  }

  private ToolResult similarAction(
      JsonNode params, LocalProjectGraphBackend.GraphSelection selection) {
    String name =
        this.firstNonBlank(
            params.path("entity_name").asText(null), params.path("head").asText(null));
    String entityId = this.resolveEntityId(selection.graph(), this.required(name, "entity_name"));
    VectorLayer entityVectors = selection.graph().vectorLayer(ENTITY_LAYER);
    double[] source = entityVectors == null ? null : entityVectors.rows().get(entityId);
    if (source == null) {
      GraphEntity entity = selection.graph().entity(entityId).orElse(null);
      String codeProjectId =
          entity == null ? null : this.string(entity.attributes().get("codeProjectId"));
      String learningStatus =
          codeProjectId == null
              ? this.string(selection.graph().meta().get("reasoningLearning.codeGraphStatus"))
              : this.string(selection.graph().meta().get("phase.codeLearning." + codeProjectId));
      String message =
          "No learned KGE vector exists for \""
              + name
              + "\". Run graph_embeddings action=train or enable code-graph KGE learning"
              + (learningStatus == null ? "." : " (code learning status: " + learningStatus + ").");
      Map<String, Object> metadata = new LinkedHashMap<>();
      metadata.put("entityName", name);
      metadata.put("entityId", entityId);
      metadata.put("count", 0);
      metadata.put("vectorAvailable", false);
      metadata.put("backend", "project-local");
      if (learningStatus != null) metadata.put("codeLearningStatus", learningStatus);
      return ToolResult.success("graph_embeddings.similar", message, metadata);
    }
    LocalProjectGraphBackend.ModelView model = this.model(selection.graph());
    int topK = this.bounded(params.path("top_k").asInt(10), 1, 100);
    List<LocalProjectGraphBackend.Scored> scored = new ArrayList<>();

    for (GraphEntity candidate : selection.graph().entities()) {
      if (!candidate.id().equals(entityId)) {
        double[] vector = model.entities().get(candidate.id());
        if (vector != null) {
          scored.add(
              new LocalProjectGraphBackend.Scored(candidate.label(), this.cosine(source, vector)));
        }
      }
    }

    scored.sort(
        Comparator.comparingDouble(LocalProjectGraphBackend.Scored::score)
            .reversed()
            .thenComparing(LocalProjectGraphBackend.Scored::label));
    if (scored.size() > topK) {
      scored = new ArrayList<>(scored.subList(0, topK));
    }

    StringBuilder output =
        new StringBuilder("Entities most similar to \"").append(name).append("\":\n\n");

    for (LocalProjectGraphBackend.Scored item : scored) {
      output.append(String.format(Locale.ROOT, "  %.4f  %s%n", item.score(), item.label()));
    }

    return ToolResult.success(
        "graph_embeddings.similar",
        output.toString(),
        Map.of("entityName", name, "count", scored.size(), "backend", "project-local"));
  }

  private LocalProjectGraphBackend.ModelView model(UnifiedGraph graph) {
    if (staleCodeLearning(graph, "codeKgeGeneration.")) {
      throw new IllegalStateException("Code graph embeddings are stale or have no generation receipt. "
          + "Run code_graph action=learn or graph_embeddings action=train before using them.");
    }
    VectorLayer entities = graph.vectorLayer("kge");
    VectorLayer relations = graph.vectorLayer("kge-relations");
    JsonNode metadata = this.modelMetadata(graph);
    if (entities != null && relations != null && metadata != null) {
      return new LocalProjectGraphBackend.ModelView(
          metadata.path("algorithm").asText("TRANSE"),
          metadata.path("embeddingDim").asInt(entities.dim()),
          entities.rows(),
          relations.rows());
    } else {
      throw new IllegalStateException(
          "No trained embeddings in this project-local graph. Run graph_embeddings action=train"
              + " first.");
    }
  }

  private JsonNode modelMetadata(UnifiedGraph graph) {
    byte[] bytes = graph.artifact("models/kge.json");
    if (bytes == null) {
      return null;
    } else {
      try {
        return this.mapper.readTree(bytes);
      } catch (IOException var4) {
        return null;
      }
    }
  }

  private LocalProjectGraphBackend.TrainingSummary trainingSummary(UnifiedGraph graph) {
    JsonNode metadata = this.modelMetadata(graph);
    if (metadata == null) {
      throw new IllegalStateException("Learning completed without a KGE model artifact");
    } else {
      return new LocalProjectGraphBackend.TrainingSummary(
          metadata.path("jobId").asText("local-kge"),
          metadata.path("algorithm").asText("TRANSE"),
          metadata.path("embeddingDim").asInt(32),
          metadata.path("epochs").asInt(0),
          metadata.path("entities").asInt(0),
          metadata.path("relationTypes").asInt(0));
    }
  }

  private Summary reasoningSummary(UnifiedGraph graph) {
    boolean completed =
        "COMPLETED".equalsIgnoreCase(String.valueOf(graph.meta().get("reasoningLearning.status")));
    return !completed
        ? Summary.disabled()
        : new Summary(
            true,
            this.booleanMeta(graph, "reasoningLearning.folPsl"),
            this.booleanMeta(graph, "reasoningLearning.mebn"),
            this.intMeta(graph, "reasoningLearning.pslRules"),
            this.intMeta(graph, "reasoningLearning.mebnFragments"),
            this.intMeta(graph, "reasoningLearning.observedTargets"),
            this.intMeta(graph, "reasoningLearning.modelsTrained"),
            this.intMeta(graph, "reasoningLearning.consensusRounds"));
  }

  private boolean booleanMeta(UnifiedGraph graph, String key) {
    Object value = graph.meta().get(key);
    return value instanceof Boolean bool ? bool : Boolean.parseBoolean(String.valueOf(value));
  }

  private int intMeta(UnifiedGraph graph, String key) {
    Object value = graph.meta().get(key);
    if (value instanceof Number number) {
      return number.intValue();
    } else {
      try {
        return Integer.parseInt(String.valueOf(value));
      } catch (NumberFormatException var5) {
        return 0;
      }
    }
  }

  private double plausibility(
      LocalProjectGraphBackend.ModelView model, String headId, String relation, String tailId) {
    double[] head = model.entities().get(headId);
    double[] rel = model.relations().get(relation);
    if (rel == null) {
      for (Entry<String, double[]> entry : model.relations().entrySet()) {
        if (entry.getKey().equalsIgnoreCase(relation)) {
          rel = entry.getValue();
          break;
        }
      }
    }

    double[] tail = model.entities().get(tailId);
    if (head != null && rel != null && tail != null) {
      double distance = 0.0;
      if ("ROTATE".equalsIgnoreCase(model.algorithm())) {
        int dim = model.dim();

        for (int i = 0; i < dim; i++) {
          double cos = Math.cos(rel[i]);
          double sin = Math.sin(rel[i]);
          double rotatedReal = head[i] * cos - head[i + dim] * sin;
          double rotatedImag = head[i] * sin + head[i + dim] * cos;
          double realError = rotatedReal - tail[i];
          double imaginaryError = rotatedImag - tail[i + dim];
          distance += realError * realError + imaginaryError * imaginaryError;
        }
      } else {
        for (int i = 0; i < Math.min(head.length, rel.length); i++) {
          double error = head[i] + rel[i] - tail[i];
          distance += error * error;
        }
      }

      return 1.0 / (1.0 + Math.sqrt(distance));
    } else {
      return 0.0;
    }
  }

  private Query toQuery(JsonNode params) {
    String operation = params.path("operation").asText("").trim();
    String question =
        this.firstNonBlank(
            params.path("queryText").asText(null), params.path("question").asText(null));
    if (operation.isBlank()) {
      operation = question != null ? "SEARCH" : "CAPABILITIES";
    }

    Intent intent;
    try {
      intent = Intent.valueOf(normalizeGraphEnum(operation));
    } catch (IllegalArgumentException var16) {
      throw new IllegalArgumentException("Unknown graph operation: " + operation);
    }

    if (!QUERY_INTENTS.contains(intent)) {
      throw new IllegalArgumentException(
          "Graph operation "
              + intent
              + " is not supported by the project-local graph query transport. Use"
              + " operation=CAPABILITIES.");
    } else {
      Direction direction = null;
      String rawDirection = params.path("direction").asText("");
      if (!rawDirection.isBlank()) {
        String normalizedDirection = normalizeGraphEnum(rawDirection);
        if ("FORWARD".equals(normalizedDirection)) {
          normalizedDirection = "OUTGOING";
        }

        if ("REVERSE".equals(normalizedDirection) || "BACKWARD".equals(normalizedDirection)) {
          normalizedDirection = "INCOMING";
        }

        try {
          direction = Direction.valueOf(normalizedDirection);
        } catch (IllegalArgumentException var15) {
          throw new IllegalArgumentException(
              "direction must be OUTGOING, INCOMING, or BOTH (forward/reverse aliases are"
                  + " accepted)");
        }
      }

      List<String> relationTypes = new ArrayList<>();
      JsonNode types = params.path("relationTypes");
      if (types.isArray()) {
        types.forEach(value -> relationTypes.add(value.asText()));
      }

      double[] embedding = null;
      JsonNode vector = params.path("queryEmbedding");
      if (vector.isArray()) {
        embedding = new double[vector.size()];

        for (int i = 0; i < vector.size(); i++) {
          embedding[i] = vector.get(i).asDouble();
        }
      }

      Structural structural = null;
      String rawStructural = params.path("structural").asText("");
      if (!rawStructural.isBlank()) {
        try {
          structural = Structural.valueOf(normalizeGraphEnum(rawStructural));
        } catch (IllegalArgumentException var14) {
          throw new IllegalArgumentException("structural must be PSL or BAYESIAN");
        }
      }

      return new Query(
          intent,
          this.text(params, "entityId"),
          this.text(params, "targetId"),
          direction,
          relationTypes,
          params.hasNonNull("maxDepth") ? params.path("maxDepth").asInt() : null,
          params.hasNonNull("topK") ? params.path("topK").asInt() : null,
          embedding,
          structural,
          question);
    }
  }

  private static String normalizeGraphEnum(String value) {
    return value.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
  }

  private LocalProjectGraphBackend.GraphSelection selectGraph(ToolContext context, JsonNode params)
      throws Exception {
    Long factSheetId = this.optionalLong(params, "factSheetId", "fact_sheet_id");
    String knowledgeBase =
        this.firstNonBlank(this.text(params, "knowledgeBase"), this.text(params, "knowledge_base"));
    if (factSheetId == null && knowledgeBase == null) {
      LocalProjectCrawlBackend crawlBackend = new LocalProjectCrawlBackend(this.mapper, this);
      ToolResult bootstrap = crawlBackend.ensureFolderKnowledgeBase(context);
      if (bootstrap.isError()) {
        throw new IllegalStateException(
            "Could not initialize the folder knowledge base: " + bootstrap.getOutput());
      } else {
        return this.selectGraph(
            context.getWorkingDirectory(),
            this.selector(
                crawlBackend.defaultKnowledgeBaseId(context.getWorkingDirectory()), null));
      }
    } else {
      return this.selectGraph(context.getWorkingDirectory(), params);
    }
  }

  private LocalProjectGraphBackend.GraphSelection selectQueryGraph(
      ToolContext context, JsonNode params, Query query) throws Exception {
    if (boundedArchiveIntent(query) && query.entityId() != null && !query.entityId().isBlank()) {
      JsonNode effectiveSelector = params;
      Long factSheetId = this.optionalLong(params, "factSheetId", "fact_sheet_id");
      String knowledgeBase =
          this.firstNonBlank(
              this.text(params, "knowledgeBase"), this.text(params, "knowledge_base"));
      if (factSheetId == null && knowledgeBase == null) {
        LocalProjectCrawlBackend crawlBackend = new LocalProjectCrawlBackend(this.mapper, this);
        ToolResult bootstrap = crawlBackend.ensureFolderKnowledgeBase(context);
        if (bootstrap.isError()) {
          throw new IllegalStateException(
              "Could not initialize the folder knowledge base: " + bootstrap.getOutput());
        }

        effectiveSelector =
            this.selector(crawlBackend.defaultKnowledgeBaseId(context.getWorkingDirectory()), null);
      }

      List<Path> paths = this.selectedGraphPaths(context.getWorkingDirectory(), effectiveSelector);
      if (paths.size() != 1) {
        return this.selectGraph(context.getWorkingDirectory(), effectiveSelector);
      } else {
        Path path = paths.get(0);
        UnifiedGraphArchive archive = UnifiedGraphArchive.open(path);

        LocalProjectGraphBackend.GraphSelection var21;
        label98:
        {
          LocalProjectGraphBackend.GraphSelection var16;
          try {
            if (!archive.hasCompactTopology()) {
              var21 = new LocalProjectGraphBackend.GraphSelection(UnifiedGraph.load(path), path);
              break label98;
            }

            List<String> seeds = new ArrayList<>();
            seeds.add(query.entityId());
            if (query.targetId() != null && !query.targetId().isBlank()) {
              seeds.add(query.targetId());
            }
            int depth =
                switch (query.intent()) {
                  case PATH -> {
                    if (query.maxDepth() != null && query.maxDepth() > 0) {
                      yield Math.min(12, query.maxDepth());
                    }

                    yield 4;
                  }
                  case WHY_NOT -> 3;
                  default -> 1;
                };

            Direction direction =
                switch (query.intent()) {
                  case PATH -> query.direction() == null ? Direction.OUTGOING : query.direction();
                  case WHY_NOT -> Direction.BOTH;
                  case VERIFY, WHY -> Direction.OUTGOING;
                  case NEIGHBORS -> query.direction() == null ? Direction.BOTH : query.direction();
                  default -> Direction.BOTH;
                };
            int maxNodes =
                Math.max(1, Integer.getInteger("kompile.graph.query.maxMaterializedNodes", 10000));
            int maxEdges =
                Math.max(1, Integer.getInteger("kompile.graph.query.maxMaterializedEdges", 50000));
            UnifiedGraph graph =
                archive.materializeNeighborhood(
                    seeds, List.of(query.entityId()), direction, depth, maxNodes, maxEdges);
            var16 = new LocalProjectGraphBackend.GraphSelection(graph, path);
          } catch (Throwable var18) {
            if (archive != null) {
              try {
                archive.close();
              } catch (Throwable var17) {
                var18.addSuppressed(var17);
              }
            }

            throw var18;
          }

          if (archive != null) {
            archive.close();
          }

          return var16;
        }

        if (archive != null) {
          archive.close();
        }

        return var21;
      }
    } else {
      return this.selectGraph(context, params);
    }
  }

  private LocalProjectGraphBackend.ArchiveQuerySelection selectArchiveQuery(
      ToolContext context, JsonNode params, Query query) throws Exception {
    UnifiedGraphArchiveQueryEngine engine = new UnifiedGraphArchiveQueryEngine();
    if (!engine.supports(query)) {
      return null;
    } else {
      JsonNode selector = this.effectiveQuerySelector(context, params);
      List<Path> paths = this.selectedGraphPaths(context.getWorkingDirectory(), selector);
      if (paths.size() != 1) {
        return null;
      } else {
        Path path = paths.get(0);
        UnifiedGraphArchive archive = UnifiedGraphArchive.open(path);

        LocalProjectGraphBackend.ArchiveQuerySelection var13;
        label51:
        {
          try {
            if (!archive.hasCompactTopology()) {
              var13 = null;
              break label51;
            }

            var13 =
                new LocalProjectGraphBackend.ArchiveQuerySelection(
                    engine.query(archive, query), path);
          } catch (Throwable var12) {
            if (archive != null) {
              try {
                archive.close();
              } catch (Throwable var11) {
                var12.addSuppressed(var11);
              }
            }

            throw var12;
          }

          if (archive != null) {
            archive.close();
          }

          return var13;
        }

        if (archive != null) {
          archive.close();
        }

        return var13;
      }
    }
  }

  private JsonNode effectiveQuerySelector(ToolContext context, JsonNode params) throws Exception {
    Long factSheetId = this.optionalLong(params, "factSheetId", "fact_sheet_id");
    String knowledgeBase =
        this.firstNonBlank(this.text(params, "knowledgeBase"), this.text(params, "knowledge_base"));
    if (factSheetId == null && knowledgeBase == null) {
      LocalProjectCrawlBackend crawlBackend = new LocalProjectCrawlBackend(this.mapper, this);
      ToolResult bootstrap = crawlBackend.ensureFolderKnowledgeBase(context);
      if (bootstrap.isError()) {
        throw new IllegalStateException(
            "Could not initialize the folder knowledge base: " + bootstrap.getOutput());
      } else {
        return this.selector(
            crawlBackend.defaultKnowledgeBaseId(context.getWorkingDirectory()), null);
      }
    } else {
      return params;
    }
  }

  private static boolean boundedArchiveIntent(Query query) {
    return switch (query.intent()) {
      case PATH, WHY_NOT, VERIFY, WHY, NEIGHBORS, DESCRIBE -> true;
      case RELATIONS, TIMELINE, FACTS -> query.entityId() != null;
      default -> false;
    };
  }

  /**
   * Reject a request that names both the project-local and remote/legacy graph selectors.
   * The selectors identify different graph namespaces and silently preferring one would make
   * inference, scoring, and training appear to succeed against the wrong graph.
   */
  public static String selectorConflict(JsonNode params) {
    if (params == null) {
      return null;
    }
    boolean hasFactSheetId = params.hasNonNull("factSheetId") || params.hasNonNull("fact_sheet_id");
    boolean hasKnowledgeBase = false;
    for (String field : List.of("knowledgeBase", "knowledge_base")) {
      JsonNode value = params.get(field);
      if (value != null && !value.isNull() && !value.asText("").trim().isBlank()) {
        hasKnowledgeBase = true;
        break;
      }
    }
    return hasFactSheetId && hasKnowledgeBase
        ? "knowledgeBase and factSheetId are mutually exclusive graph selectors"
        : null;
  }

  private LocalProjectGraphBackend.GraphSelection selectGraph(
      Path workingDirectory, JsonNode params) throws Exception {
    String selectorError = selectorConflict(params);
    if (selectorError != null) {
      throw new IllegalArgumentException(selectorError);
    }
    Path root = this.projectRoot(workingDirectory);
    List<Path> candidates = this.selectedGraphPaths(workingDirectory, params);
    if (candidates.isEmpty()) {
      throw new IllegalStateException(
          "No project-local graph matches the explicit knowledge-base selector. Omit the selector"
              + " to use and initialize the current folder's knowledge base.");
    } else if (candidates.size() == 1) {
      return new LocalProjectGraphBackend.GraphSelection(
          UnifiedGraph.load(candidates.get(0)), candidates.get(0));
    } else {
      UnifiedGraph merged =
          new UnifiedGraph()
              .graphId("local:" + root.getFileName() + ":all")
              .meta("backend", "project-local")
              .meta("projectRoot", root.toString())
              .meta("mergedGraphs", candidates.size());
      Set<String> relationIds = new LinkedHashSet<>();

      for (Path path : candidates) {
        UnifiedGraph graph = UnifiedGraph.load(path);
        graph.entities().forEach(merged::addEntity);

        for (GraphRelation relation : graph.relations()) {
          if (relationIds.add(relation.id())) {
            merged.addRelation(relation);
          }
        }
      }

      return new LocalProjectGraphBackend.GraphSelection(merged, null);
    }
  }

  private List<Path> selectedGraphPaths(Path workingDirectory, JsonNode params) throws Exception {
    Path root = this.projectRoot(workingDirectory);
    Long factSheetId = this.optionalLong(params, "factSheetId", "fact_sheet_id");
    String knowledgeBase =
        this.firstNonBlank(this.text(params, "knowledgeBase"), this.text(params, "knowledge_base"));
    List<Path> candidates = new ArrayList<>();
    Path crawls = root.resolve("data/crawls");
    if (factSheetId != null) {
      Path path = this.safeGraphDirectory(root, "kb-" + factSheetId).resolve("graph.kgraph");
      if (Files.isRegularFile(path)) {
        candidates.add(path);
      }
    } else if (knowledgeBase != null) {
      Path direct = this.safeGraphDirectory(root, this.slug(knowledgeBase)).resolve("graph.kgraph");
      if (Files.isRegularFile(direct)) {
        candidates.add(direct);
      }

      if (candidates.isEmpty()) {
        Path numeric = this.safeGraphDirectory(root, "kb-" + knowledgeBase).resolve("graph.kgraph");
        if (Files.isRegularFile(numeric)) {
          candidates.add(numeric);
        }
      }
    } else if (Files.isDirectory(crawls)) {
      try (Stream<Path> directories = Files.list(crawls)) {
        candidates.addAll(
            directories
                .<Path>map(path -> path.resolve("graph.kgraph"))
                .filter(x$0 -> Files.isRegularFile(x$0))
                .sorted()
                .toList());
      }
    }

    return candidates;
  }

  private Path projectRoot(Path workingDirectory) {
    Path working = workingDirectory.toAbsolutePath().normalize();
    return this.projectStore.findProjectRoot(working).orElse(working);
  }

  static String stripGeneratedCrawlFrontMatter(String text) {
    if (text != null && !text.isEmpty()) {
      String content = text.charAt(0) == '\ufeff' ? text.substring(1) : text;
      int firstLineEnd = content.indexOf(10);
      if (firstLineEnd >= 0 && "---".equals(content.substring(0, firstLineEnd).trim())) {
        int cursor = firstLineEnd + 1;
        int bodyStart = -1;
        boolean generatedCrawlMetadata = false;

        while (cursor <= content.length()) {
          int lineEnd = content.indexOf(10, cursor);
          if (lineEnd < 0) {
            lineEnd = content.length();
          }

          String line = content.substring(cursor, lineEnd).trim();
          if ("---".equals(line)) {
            bodyStart = lineEnd < content.length() ? lineEnd + 1 : lineEnd;
            break;
          }

          int separator = line.indexOf(58);
          if (separator > 0 && "converter".equalsIgnoreCase(line.substring(0, separator).trim())) {
            String value = line.substring(separator + 1).trim().replace("\"", "").replace("'", "");
            generatedCrawlMetadata = "kompile-project-crawl".equalsIgnoreCase(value);
          }

          if (lineEnd >= content.length()) {
            break;
          }

          cursor = lineEnd + 1;
        }

        return generatedCrawlMetadata && bodyStart >= 0
            ? content.substring(bodyStart).stripLeading()
            : text;
      } else {
        return text;
      }
    } else {
      return "";
    }
  }

  private void updateCrawlSummary(
      Path directory,
      UnifiedGraph graph,
      Path graphPath,
      int codeEntities,
      LocalProjectGraphBackend.TrainingSummary training,
      Summary reasoning,
      LocalProjectGraphBackend.ResolutionSummary resolution,
      LocalProjectGraphBackend.SemanticExtractionSummary semanticExtraction)
      throws IOException {
    Path summaryPath = directory.resolve("crawl-result.json");
    ObjectNode summary =
        Files.isRegularFile(summaryPath)
            ? this.object(this.mapper.readTree(summaryPath.toFile()))
            : this.mapper.createObjectNode();
    summary.put("graphPath", graphPath.toString());
    summary.put("graphEntityCount", graph.entities().size());
    summary.put("graphRelationCount", graph.relations().size());
    summary.put("codeEntityCount", codeEntities);
    if (graph.meta().get("codeProjectionVersion") instanceof Number version) {
      summary.put("codeProjectionVersion", version.intValue());
    }
    summary.put("semanticEntityCount", semanticExtraction.entities());
    summary.put("semanticRelationCount", semanticExtraction.relations());
    summary.set("semanticExtractionErrors", this.mapper.valueToTree(semanticExtraction.errors()));
    summary.put("entityResolutionEnabled", resolution.enabled());
    summary.put("entityResolutionMergedCount", resolution.entitiesMerged());
    summary.put("entityResolutionTypeCorrectionCount", resolution.typesCorrected());
    summary.put("entityResolutionIdentifierLinkCount", resolution.identifierLinksCreated());
    summary.put("entityResolutionCandidateCount", resolution.candidatePairs());
    summary.put(
        "embeddingVectorCount",
        graph.vectorLayers().values().stream().mapToInt(VectorLayer::size).sum());
    if (graph.factSheetId() != null) {
      summary.put("factSheetId", graph.factSheetId());
    }

    if (training != null) {
      summary.put("embeddingAlgorithm", training.algorithm());
      summary.put("embeddingTrainingStatus", "COMPLETED");
    }

    summary.put("reasoningLearningEnabled", reasoning.enabled());
    summary.put("folPslLearned", reasoning.folPslLearned());
    summary.put("mebnLearned", reasoning.mebnLearned());
    summary.put("reasoningModelsTrained", reasoning.modelsTrained());
    summary.put("pslRuleCount", reasoning.pslRuleCount());
    summary.put("mebnFragmentCount", reasoning.mebnFragmentCount());
    summary.put("reasoningObservedTargetCount", reasoning.observedTargetCount());
    summary.put("reasoningConsensusRounds", reasoning.consensusRounds());
    this.mapper.writerWithDefaultPrettyPrinter().writeValue(summaryPath.toFile(), summary);
  }

  private void writeImportedSummary(Path directory, UnifiedGraph graph, Path graphPath)
      throws IOException {
    ObjectNode summary = this.mapper.createObjectNode();
    summary.put("profileId", directory.getFileName().toString());
    summary.put(
        "name",
        this.firstNonBlank(
            this.stringMeta(graph, "knowledgeBaseName"),
            graph.graphId(),
            directory.getFileName().toString()));
    summary.put("status", "COMPLETED");
    summary.put("backend", "project-local");
    summary.put("imported", true);
    summary.put("finishedAt", Instant.now().toString());
    summary.putArray("sources");
    summary.put("documentCount", 0);
    summary.put("chunkCount", 0);
    summary.put("graphPath", graphPath.toString());
    summary.put("graphEntityCount", graph.entities().size());
    summary.put("graphRelationCount", graph.relations().size());
    if (graph.factSheetId() != null) {
      summary.put("factSheetId", graph.factSheetId());
    }

    this.mapper
        .writerWithDefaultPrettyPrinter()
        .writeValue(directory.resolve("crawl-result.json").toFile(), summary);
  }

  private void writeCodeProjectionSummary(Path directory, UnifiedGraph graph, Path graphPath)
      throws IOException {
    Path summaryPath = directory.resolve("crawl-result.json");
    ObjectNode summary =
        Files.isRegularFile(summaryPath)
            ? (ObjectNode) this.mapper.readTree(summaryPath.toFile())
            : this.mapper.createObjectNode();
    summary.put("profileId", directory.getFileName().toString());
    summary.put(
        "name",
        this.firstNonBlank(
            this.stringMeta(graph, "knowledgeBaseName"), directory.getFileName().toString()));
    this.markCodeProjectionCompleted(summary);
    summary.put("backend", "project-local");
    if (!summary.has("sources")) {
      summary.putArray("sources");
    }

    summary.put("graphPath", graphPath.toString());
    summary.put("graphEntityCount", graph.entities().size());
    summary.put("graphRelationCount", graph.relations().size());
    long codeEntities =
        graph.entities().stream()
            .filter(
                entity -> {
                  String owner = this.string(entity.attributes().get("_kompileProjectionOwner"));
                  String legacy = this.string(entity.attributes().get("provenance"));
                  boolean projected =
                      "local-code-index".equals(owner) || "local-code-index".equals(legacy);
                  return projected
                      && !"CODE_PROJECT".equals(entity.type())
                      && !"CODE_SYMBOL_REFERENCE".equals(entity.type());
                })
            .count();
    summary.put("codeEntityCount", codeEntities);
    summary.put("codeProjectionVersion", CODE_PROJECTION_VERSION);
    if (graph.factSheetId() != null) {
      summary.put("factSheetId", graph.factSheetId());
    }

    this.mapper.writerWithDefaultPrettyPrinter().writeValue(summaryPath.toFile(), summary);
  }

  private void writeCodeProjectionSummary(
      Path directory,
      String knowledgeBaseName,
      Path graphPath,
      int entities,
      int relations,
      int codeEntities,
      Long factSheetId)
      throws IOException {
    Path summaryPath = directory.resolve("crawl-result.json");
    ObjectNode summary =
        Files.isRegularFile(summaryPath)
            ? (ObjectNode) this.mapper.readTree(summaryPath.toFile())
            : this.mapper.createObjectNode();
    summary.put("profileId", directory.getFileName().toString());
    summary.put("name", this.firstNonBlank(knowledgeBaseName, directory.getFileName().toString()));
    this.markCodeProjectionCompleted(summary);
    summary.put("backend", "project-local");
    if (!summary.has("sources")) summary.putArray("sources");
    summary.put("graphPath", graphPath.toString());
    summary.put("graphEntityCount", entities);
    summary.put("graphRelationCount", relations);
    summary.put("codeEntityCount", codeEntities);
    summary.put("codeProjectionVersion", CODE_PROJECTION_VERSION);
    if (factSheetId != null) summary.put("factSheetId", factSheetId);
    this.mapper.writerWithDefaultPrettyPrinter().writeValue(summaryPath.toFile(), summary);
  }

  private void markCodeProjectionCompleted(ObjectNode summary) {
    String now = Instant.now().toString();
    if (!summary.hasNonNull("status") || summary.path("status").asText().isBlank()) {
      summary.put("status", "COMPLETED");
      summary.put("finishedAt", now);
    }
    summary.put("codeProjectionStatus", "COMPLETED");
    summary.put("codeProjectionUpdatedAt", now);
  }

  private Path safeGraphDirectory(Path projectRoot, String knowledgeBaseId) throws IOException {
    if (knowledgeBaseId != null
        && !knowledgeBaseId.isBlank()
        && !Path.of(knowledgeBaseId).isAbsolute()
        && Path.of(knowledgeBaseId).getNameCount() == 1
        && !knowledgeBaseId.contains("/")
        && !knowledgeBaseId.contains("\\")
        && !".".equals(knowledgeBaseId)
        && !"..".equals(knowledgeBaseId)) {
      Path normalizedRoot = projectRoot.toAbsolutePath().normalize();
      Path realRoot = normalizedRoot.toRealPath();
      Path data = realRoot.resolve("data");
      Files.createDirectories(data);
      Path realData = data.toRealPath();
      if (!realData.startsWith(realRoot)) {
        throw new IllegalArgumentException("Project data directory resolves outside project root.");
      } else {
        Path crawls = realData.resolve("crawls");
        Files.createDirectories(crawls);
        Path realCrawls = crawls.toRealPath();
        if (!realCrawls.startsWith(realRoot)) {
          throw new IllegalArgumentException(
              "Project-local crawl directory resolves outside project root.");
        } else {
          Path directory = realCrawls.resolve(knowledgeBaseId).normalize();
          if (!directory.startsWith(realCrawls)) {
            throw new IllegalArgumentException(
                "Knowledge-base directory escapes project crawl root.");
          } else {
            Files.createDirectories(directory);
            if (!directory.toRealPath().startsWith(realCrawls)) {
              throw new IllegalArgumentException(
                  "Knowledge-base directory resolves outside project crawl root.");
            } else {
              return directory;
            }
          }
        }
      }
    } else {
      throw new IllegalArgumentException(
          "Invalid project-local knowledge-base id: " + knowledgeBaseId);
    }
  }

  private void saveAtomic(UnifiedGraph graph, Path target) throws IOException {
    this.saveAtomic(graph, target, KGraphCompatibilityPolicy.COMPACT_V3);
  }

  private void saveAtomic(
      UnifiedGraph graph, Path target, KGraphCompatibilityPolicy compatibilityPolicy)
      throws IOException {
    if (target.getParent() != null) {
      Files.createDirectories(target.getParent());
    }

    Path temporary = Files.createTempFile(target.getParent(), target.getFileName() + ".", ".tmp");

    try {
      graph.save(temporary, compatibilityPolicy);

      try {
        Files.move(
            temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      } catch (AtomicMoveNotSupportedException var9) {
        Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
      }
      UnifiedGraphMutationJournal.clear(target);
    } finally {
      Files.deleteIfExists(temporary);
    }
  }

  static <T> T withGraphWriteLock(
      Path graphPath, LocalProjectGraphBackend.GraphWriteOperation<T> operation) throws Exception {
    Path normalized = graphPath.toAbsolutePath().normalize();
    if (normalized.getParent() != null) {
      Files.createDirectories(normalized.getParent());
    }

    ReentrantLock processLock =
        GRAPH_WRITE_LOCKS.computeIfAbsent(normalized, ignoredx -> new ReentrantLock());
    processLock.lock();
    Path lockPath = normalized.resolveSibling("." + normalized.getFileName() + ".lock");

    Object var8;
    try (FileChannel channel =
            FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        FileLock ignored = channel.lock(); ) {
      var8 = operation.run();
    } finally {
      processLock.unlock();
    }

    return (T) var8;
  }

  private void addRelation(
      UnifiedGraph graph,
      String source,
      String target,
      String type,
      Map<String, Object> attributes) {
    String id = "relation:" + stableId(source + "\n" + type + "\n" + target);
    if (graph.relation(id).isEmpty()) {
      graph.addRelation(
          GraphRelation.builder(id, source, target)
              .type(type)
              .weight(1.0)
              .confidence(1.0)
              .directed(true)
              .attributes(this.nonNullAttributes(attributes))
              .build());
    }
  }

  private void addWeightedRelation(
      UnifiedGraph graph,
      String source,
      String target,
      String type,
      double weight,
      double confidence,
      Map<String, Object> attributes) {
    String id = "relation:" + stableId(source + "\n" + type + "\n" + target);
    boolean exists = graph.relations().stream().anyMatch(relation -> relation.id().equals(id));
    if (!exists) {
      graph.addRelation(
          GraphRelation.builder(id, source, target)
              .type(type)
              .weight(weight)
              .confidence(confidence)
              .directed(true)
              .attributes(this.nonNullAttributes(attributes))
              .build());
    }
  }

  private void forEachJsonLine(Path path, LocalProjectGraphBackend.JsonLineConsumer consumer)
      throws IOException {
    if (Files.isRegularFile(path)) {
      String line;
      try (BufferedReader lines = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
        while ((line = lines.readLine()) != null) {
          if (!line.isBlank()) {
            consumer.accept(this.mapper.readTree(line));
          }
        }
      }
    }
  }

  private Map<String, Object> jsonAttributes(JsonNode node) {
    Map<String, Object> attributes = new LinkedHashMap<>();
    node.fields()
        .forEachRemaining(
            entry -> {
              if (!((JsonNode) entry.getValue()).isNull()) {
                Object value = this.mapper.convertValue(entry.getValue(), Object.class);
                if (value != null) {
                  attributes.put((String) entry.getKey(), value);
                }
              }
            });
    return attributes;
  }

  private Map<String, Object> nonNullAttributes(Map<String, Object> source) {
    Map<String, Object> attributes = new LinkedHashMap<>();
    if (source != null) {
      source.forEach(
          (key, value) -> {
            if (key != null && value != null) {
              attributes.put(key, value);
            }
          });
    }

    return attributes;
  }

  private List<Map<String, Object>> maps(Object value) {
    if (value instanceof Collection<?> collection) {
      ArrayList result = new ArrayList();

      for (Object item : collection) {
        if (item instanceof Map<?, ?> raw) {
          Map<String, Object> converted = new LinkedHashMap<>();
          raw.forEach((key, entryValue) -> converted.put(String.valueOf(key), entryValue));
          result.add(converted);
        }
      }

      return result;
    } else {
      return List.of();
    }
  }

  private double cosine(double[] left, double[] right) {
    if (left != null && right != null && left.length == right.length) {
      double dot = 0.0;
      double leftNorm = 0.0;
      double rightNorm = 0.0;

      for (int i = 0; i < left.length; i++) {
        dot += left[i] * right[i];
        leftNorm += left[i] * left[i];
        rightNorm += right[i] * right[i];
      }

      return leftNorm != 0.0 && rightNorm != 0.0 ? dot / Math.sqrt(leftNorm * rightNorm) : 0.0;
    } else {
      return 0.0;
    }
  }

  private String resolveEntityId(UnifiedGraph graph, String selector) {
    GraphEntity resolved = resolveEntity(graph, selector);
    if (resolved == null) throw new IllegalArgumentException("Unknown graph entity: " + selector);
    return resolved.id();
  }

  private String codeEntityId(String projectId, String type, String declarationIdentity) {
    return CodeGraphIdentity.entityId(projectId, type, declarationIdentity, null);
  }

  private String codeDeclarationIdentity(String type, String fqn, String signature) {
    return CodeGraphIdentity.declarationIdentity(type, fqn, signature);
  }

  private static String stableId(String value) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
      StringBuilder result = new StringBuilder(digest.length * 2);

      for (byte item : digest) {
        result.append(String.format(Locale.ROOT, "%02x", item));
      }

      return result.toString();
    } catch (Exception var7) {
      throw new IllegalStateException(var7);
    }
  }

  private ObjectNode selector(String knowledgeBase, Long factSheetId) {
    ObjectNode selector = this.mapper.createObjectNode();
    if (knowledgeBase != null) {
      selector.put("knowledgeBase", knowledgeBase);
    }

    if (factSheetId != null) {
      selector.put("factSheetId", factSheetId);
    }

    return selector;
  }

  private void copySelector(JsonNode source, ObjectNode target) {
    for (String field :
        List.of("factSheetId", "fact_sheet_id", "knowledgeBase", "knowledge_base")) {
      if (source.hasNonNull(field)) {
        target.set(field, source.get(field));
      }
    }
  }

  private Long optionalLong(JsonNode params, String... keys) {
    for (String key : keys) {
      JsonNode value = params.get(key);
      if (value != null && value.canConvertToLong()) {
        return value.asLong();
      }
    }

    return null;
  }

  private String text(JsonNode node, String field) {
    if (node != null && node.hasNonNull(field)) {
      String value = node.path(field).asText("").trim();
      return value.isBlank() ? null : value;
    } else {
      return null;
    }
  }

  private String stringMeta(UnifiedGraph graph, String key) {
    Object value = graph.meta().get(key);
    return value == null ? null : String.valueOf(value);
  }

  private ObjectNode object(JsonNode value) {
    return value != null && value.isObject() ? (ObjectNode) value : this.mapper.createObjectNode();
  }

  private String string(Object value) {
    return value == null ? null : String.valueOf(value);
  }

  private String requiredText(JsonNode params, String field) {
    return this.required(params.path(field).asText(""), field);
  }

  private String required(String value, String field) {
    if (value != null && !value.isBlank()) {
      return value.trim();
    } else {
      throw new IllegalArgumentException(field + " is required");
    }
  }

  private int bounded(int value, int minimum, int maximum) {
    return Math.max(minimum, Math.min(maximum, value));
  }

  private String abbreviate(String value, int max) {
    String normalized = value == null ? "" : value.replaceAll("\\s+", " ").trim();
    return normalized.length() <= max ? normalized : normalized.substring(0, max - 1) + "…";
  }

  private String normalizePath(String value) {
    return value == null ? "" : value.replace('\\', '/');
  }

  private String csv(List<String> values) {
    return values == null ? "" : String.join(",", values);
  }

  private String slug(String value) {
    String result =
        this.firstNonBlank(value, "knowledge")
            .toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9._-]+", "-")
            .replaceAll("^-+|-+$", "");
    return result.isBlank() ? "knowledge" : result;
  }

  private String firstNonBlank(String... values) {
    if (values != null) {
      for (String value : values) {
        if (value != null && !value.isBlank()) {
          return value;
        }
      }
    }

    return null;
  }

  private String message(Exception exception) {
    return exception.getMessage() != null && !exception.getMessage().isBlank()
        ? exception.getMessage()
        : exception.getClass().getSimpleName();
  }

  private record ArchiveQuerySelection(
      ai.kompile.graph.reasoning.query.GraphQueryEngine.Result result, Path path) {}

  private record Atom(String predicate, List<String> args) {}

  public record CodeProjectSource(
      Path root,
      String codeProjectId,
      String name,
      List<String> includePatterns,
      List<String> excludePatterns) {}


  public record CodeProjectionUpdate(
      Path graphPath,
      int entities,
      int relations,
      int codeEntities,
      String knowledgeBaseId,
      Long factSheetId) {}

  private record FinalLearningSummary(
      LocalProjectGraphBackend.TrainingSummary embedding, Summary reasoning) {}

  private record GraphSelection(UnifiedGraph graph, Path path) {
    String pathDescription() {
      return this.path == null ? "merged project-local graphs" : this.path.toString();
    }
  }

  public record GraphStats(int entities, int relations, int embeddingVectors, String graphPath) {}

  public record GraphUpdate(
      Path graphPath,
      int entities,
      int relations,
      int codeEntities,
      int embeddingVectors,
      String embeddingAlgorithm,
      boolean enrichmentRequested,
      boolean reasoningLearningEnabled,
      boolean folPslLearned,
      boolean mebnLearned,
      int reasoningModelsTrained,
      int pslRuleCount,
      int mebnFragmentCount,
      boolean entityResolutionEnabled,
      int entitiesMerged,
      int entityTypesCorrected,
      int identifierLinksCreated,
      int semanticEntities,
      int semanticRelations,
      List<String> semanticExtractionErrors) {}

  @FunctionalInterface
  interface GraphWriteOperation<T> {
    T run() throws Exception;
  }

  @FunctionalInterface
  private interface JsonLineConsumer {
    void accept(JsonNode value) throws IOException;
  }

  private record LocalSubscription(
      String graphPath, Set<String> predicates, List<ObjectNode> events) {}

  private record ModelView(
      String algorithm, int dim, Map<String, double[]> entities, Map<String, double[]> relations) {}

  private record NodeDepth(String id, int depth) {}

  private static enum PredictionTarget {
    HEAD,
    TAIL,
    RELATION;
  }

  private record ReasoningLearningRequest(
      boolean enrichmentRequested,
      boolean enabled,
      int pslSteps,
      int mebnEpochs,
      int consensusRounds,
      double consensusWeight,
      int maxRelationTypes) {
    private static LocalProjectGraphBackend.ReasoningLearningRequest from(JsonNode request) {
      JsonNode explicit = request != null ? request.path("reasoningLearning") : null;
      JsonNode runtime = request != null ? request.path("runtimeConfig") : null;
      if (request != null && request.path("config").isObject()) {
        JsonNode configuredRuntime = request.path("config").path("runtimeConfig");
        if (configuredRuntime.isObject()) {
          runtime = configuredRuntime;
        }
      }

      boolean hasExplicit = explicit != null && explicit.isObject();
      boolean hasRuntime = runtime != null && runtime.isObject();
      boolean enrichmentRequested = enrichmentRequested(request);
      boolean derivationEnabled = hydrationStageEnabled(request, "DERIVATION");
      boolean hydrationDryRun =
          request != null && request.path("hydration").path("dryRun").asBoolean(false);
      boolean configured =
          hasExplicit
              ? explicit.path("enabled").asBoolean(true)
              : (hasRuntime && runtime.has("runReasoningLearning")
                  ? runtime.path("runReasoningLearning").asBoolean(true)
                  : true);
      boolean enabled = enrichmentRequested && derivationEnabled && !hydrationDryRun && configured;
      int pslSteps = hasExplicit ? explicit.path("pslSteps").asInt(1) : 1;
      int mebnEpochs = hasExplicit ? explicit.path("mebnEpochs").asInt(1) : 1;
      int consensusRounds = hasExplicit ? explicit.path("consensusRounds").asInt(1) : 1;
      double consensusWeight = hasExplicit ? explicit.path("consensusWeight").asDouble(0.35) : 0.35;
      int maxRelationTypes = hasExplicit ? explicit.path("maxRelationTypes").asInt(25) : 25;
      return new LocalProjectGraphBackend.ReasoningLearningRequest(
          enrichmentRequested,
          enabled,
          Math.max(1, pslSteps),
          Math.max(1, mebnEpochs),
          Math.max(1, consensusRounds),
          Math.max(0.0, Math.min(1.0, consensusWeight)),
          Math.max(1, maxRelationTypes));
    }

    private static boolean enrichmentRequested(JsonNode request) {
      return LocalProjectGraphBackend.localStepPlan(request).isRun("ENRICHMENT");
    }

    private static boolean hydrationStageEnabled(JsonNode request, String stageId) {
      JsonNode stages = request == null ? null : request.path("hydration").get("enabledStageIds");
      if (stages != null && stages.isArray() && !stages.isEmpty()) {
        for (JsonNode stage : stages) {
          if (stageId.equalsIgnoreCase(stage.asText(""))) {
            return true;
          }
        }

        return false;
      } else {
        return true;
      }
    }

    private Config toConfig() {
      return new Config(
          this.enabled,
          this.pslSteps,
          this.mebnEpochs,
          this.consensusRounds,
          this.consensusWeight,
          this.maxRelationTypes);
    }
  }

  private record ResolutionRequest(
      boolean enabled,
      double mergeThreshold,
      boolean useEmbeddings,
      double embeddingThreshold,
      int maxCandidatePairs) {
    private static LocalProjectGraphBackend.ResolutionRequest from(JsonNode request) {
      JsonNode graphConfig = request == null ? null : request.path("graphExtraction");
      if (graphConfig != null
          && !graphConfig.isObject()
          && request != null
          && request.path("config").isObject()) {
        graphConfig = request.path("config").path("graphExtraction");
      }

      JsonNode explicit = request == null ? null : request.path("entityResolution");
      boolean enabled = true;
      if (explicit != null && explicit.isBoolean()) {
        enabled = explicit.asBoolean();
      } else if (explicit != null && explicit.isObject() && explicit.has("enabled")) {
        enabled = explicit.path("enabled").asBoolean(true);
      }

      if (graphConfig != null && graphConfig.isObject() && graphConfig.has("entityResolution")) {
        enabled = graphConfig.path("entityResolution").asBoolean(enabled);
      }

      double mergeThreshold = 0.86;
      boolean useEmbeddings = true;
      double embeddingThreshold = 0.9;
      int maxCandidatePairs = 50000;
      if (explicit != null && explicit.isObject()) {
        mergeThreshold = explicit.path("similarityThreshold").asDouble(mergeThreshold);
        useEmbeddings = explicit.path("useEmbeddings").asBoolean(useEmbeddings);
        embeddingThreshold = explicit.path("embeddingThreshold").asDouble(embeddingThreshold);
        maxCandidatePairs = explicit.path("maxCandidatePairs").asInt(maxCandidatePairs);
      }

      if (graphConfig != null && graphConfig.isObject()) {
        mergeThreshold =
            graphConfig.path("entityResolutionSimilarityThreshold").asDouble(mergeThreshold);
        useEmbeddings = graphConfig.path("entityResolutionUseEmbeddings").asBoolean(useEmbeddings);
        embeddingThreshold =
            graphConfig.path("entityResolutionEmbeddingThreshold").asDouble(embeddingThreshold);
        maxCandidatePairs =
            graphConfig.path("entityResolutionMaxCandidatePairs").asInt(maxCandidatePairs);
      }

      mergeThreshold = Math.max(0.0, Math.min(1.0, mergeThreshold));
      embeddingThreshold = Math.max(0.0, Math.min(1.0, embeddingThreshold));
      maxCandidatePairs = Math.max(1, maxCandidatePairs);
      return new LocalProjectGraphBackend.ResolutionRequest(
          enabled, mergeThreshold, useEmbeddings, embeddingThreshold, maxCandidatePairs);
    }

    private ai.kompile.graph.reasoning.resolution.UnifiedGraphEntityResolver.Config
        toResolverConfig() {
      return new ai.kompile.graph.reasoning.resolution.UnifiedGraphEntityResolver.Config(
          this.mergeThreshold,
          Math.min(0.8, this.mergeThreshold),
          this.useEmbeddings,
          this.embeddingThreshold,
          this.maxCandidatePairs,
          20.0,
          4.0,
          1.0);
    }
  }

  private record ResolutionSummary(
      boolean enabled,
      int entitiesMerged,
      int typesCorrected,
      int identifierLinksCreated,
      int candidatePairs,
      int acceptedPairs,
      boolean candidateCapReached) {
    private static LocalProjectGraphBackend.ResolutionSummary disabled() {
      return new LocalProjectGraphBackend.ResolutionSummary(false, 0, 0, 0, 0, 0, false);
    }

    private static LocalProjectGraphBackend.ResolutionSummary from(
        ai.kompile.graph.reasoning.resolution.UnifiedGraphEntityResolver.Result result) {
      return new LocalProjectGraphBackend.ResolutionSummary(
          true,
          result.entitiesMerged(),
          result.typesCorrected(),
          result.identifierLinksCreated(),
          result.candidatePairs(),
          result.acceptedPairs(),
          result.candidateCapReached());
    }
  }

  private record Scored(String label, double score) {}

  private record SemanticExtractionSummary(
      int entities,
      int relations,
      List<String> errors,
      GraphSchema canonicalGraphSchema,
      String schemaFingerprint) {
    private static LocalProjectGraphBackend.SemanticExtractionSummary none() {
      return new LocalProjectGraphBackend.SemanticExtractionSummary(0, 0, List.of(), null, null);
    }

    private static LocalProjectGraphBackend.SemanticExtractionSummary failed(String error) {
      return new LocalProjectGraphBackend.SemanticExtractionSummary(0, 0, List.of(error), null, null);
    }
  }

  private record TrainingRequest(
      boolean enabled,
      String algorithm,
      int dim,
      int epochs,
      double learningRate,
      int warmStartEpochs,
      long seed) {
    static LocalProjectGraphBackend.TrainingRequest from(JsonNode request) {
      JsonNode explicit = request != null ? request.path("embeddingTraining") : null;
      JsonNode runtime = request != null ? request.path("runtimeConfig") : null;
      if (request != null && request.path("config").isObject()) {
        JsonNode configuredRuntime = request.path("config").path("runtimeConfig");
        if (configuredRuntime.isObject()) {
          runtime = configuredRuntime;
        }
      }

      boolean hasExplicit = explicit != null && explicit.isObject();
      boolean hasRuntime = runtime != null && runtime.isObject();
      boolean enabled =
          hasExplicit
              ? explicit.path("enabled").asBoolean(true)
              : (hasRuntime && runtime.has("trainEmbeddingsAfterEnrichment")
                  ? runtime.path("trainEmbeddingsAfterEnrichment").asBoolean()
                  : true);
      String algorithm =
          hasExplicit
              ? explicit.path("algorithm").asText("TRANSE")
              : (hasRuntime ? runtime.path("embeddingAlgorithm").asText("TRANSE") : "TRANSE");
      int dim =
          hasExplicit
              ? explicit.path("embeddingDim").asInt(32)
              : (hasRuntime ? runtime.path("embeddingDim").asInt(32) : 32);
      int epochs =
          hasExplicit
              ? explicit.path("epochs").asInt(8)
              : (hasRuntime ? runtime.path("embeddingEpochs").asInt(8) : 8);
      int warmStart =
          hasExplicit
              ? explicit.path("warmStartEpochs").asInt(epochs)
              : (hasRuntime ? runtime.path("embeddingWarmStartEpochs").asInt(epochs) : epochs);
      return new LocalProjectGraphBackend.TrainingRequest(
          enabled, algorithm, dim, epochs, 0.05, warmStart, 1234L);
    }

    private ai.kompile.graph.reasoning.lifecycle.UnifiedGraphKgeLifecycle.Config toConfig() {
      return new ai.kompile.graph.reasoning.lifecycle.UnifiedGraphKgeLifecycle.Config(
          this.enabled,
          this.algorithm,
          this.dim,
          this.epochs,
          this.learningRate,
          this.warmStartEpochs,
          this.seed);
    }
  }

  private record TrainingSummary(
      String jobId, String algorithm, int dim, int epochs, int entities, int relationTypes) {}
}
