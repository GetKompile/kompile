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

package ai.kompile.app.web.boundary;

import java.util.Set;

/**
 * The base paths each persona web module owns — one definition, shared by the three
 * {@code *PersonaBoundaryTest} classes so they cannot disagree about where a path belongs.
 *
 * <p>These lists mirror {@code docs/architecture/app-persona-boundary.md}. Moving a controller
 * between web modules means editing the list here as part of the same change; that is the point,
 * because the edit is what makes the other two personas' tests re-evaluate the move.</p>
 */
public final class PersonaSurfaces {

    private PersonaSurfaces() {}

    /** Owned by {@code kompile-app-web-chat}. */
    public static final Set<String> CHAT = Set.of(
            "/api/agents",
            "/api/agents/chat",
            "/api/agents/kompile-local",
            "/api/agents/models",
            "/api/agents/passthrough",
            "/api/agents/runtime",
            "/api/chat",
            "/api/chat-sessions",
            "/api/explain",
            "/api/graph-rag",
            "/api/grounding",
            "/api/kb-grounding",
            "/api/prompts",
            "/api/rag",
            "/api/session-metrics",
            "/api/skills",
            "/api/system-prompts",
            "/api/tool-calls");

    /** Owned by {@code kompile-app-web-crawl}. */
    public static final Set<String> CRAWL = Set.of(
            "/api/chunk-manager",
            "/api/cluster",
            "/api/confluence",
            "/api/crawlers",
            "/api/crawl-events",
            "/api/cross-index",
            "/api/distributed-crawl",
            "/api/email/extract-values",
            "/api/enrichment",
            "/api/graph-extraction",
            "/api/graph/extraction-models",
            "/api/graph/hydration",
            "/api/graph/partitions",
            "/api/indexer",
            "/api/indexing",
            "/api/ingest/events",
            "/api/ingest/resume",
            "/api/internal/ingest",
            "/api/schedules",
            "/api/unified-crawl",
            "/api/vector-population");

    /**
     * Reachable on {@code :8080} and nowhere else.
     *
     * <p>Most of these come from {@code kompile-app-web-admin}, but membership is defined by
     * <i>reach</i>, not by module: {@code /api/rag-pipelines}, {@code /api/metrics} and
     * {@code /api/a2a} live in library modules that only the admin console happens to depend on. That
     * is the right criterion, because this set is what the other two personas' forbidden assertions
     * subtract themselves against — a path is dangerous to them precisely when it is admin-only and
     * they start mounting it, regardless of which jar carries the controller.</p>
     *
     * <p>The corollary is that an admin-only path in a library module is admin-only by accident of the
     * dependency graph. Adding that library to a persona's pom moves the path out of this set, and the
     * completeness check in {@code MainPersonaBoundaryTest} is what forces that to be a deliberate
     * edit rather than a silent one.</p>
     */
    public static final Set<String> ADMIN = Set.of(
            "/api/a2a",
            "/api/agents/api-config",
            "/api/agents/cli-config",
            "/api/archives",
            "/api/auto-configure",
            "/api/backup",
            "/api/benchmark",
            "/api/build",
            "/api/config-archives",
            "/api/config/logs",
            "/api/config/mcp-optimization",
            "/api/contextual-rag",
            "/api/device-routing",
            "/api/diagnostics",
            "/api/diff-index",
            "/api/diff-policy",
            "/api/documents/debug",
            "/api/embedding-restart",
            "/api/embeddings/batch-config",
            "/api/enforcer",
            "/api/environment",
            "/api/eval-debugger",
            "/api/eval-sets",
            "/api/evaluation",
            "/api/events/observation",
            "/api/experiments",
            "/api/filterchain",
            "/api/git",
            "/api/gpu-lifecycle",
            "/api/graph-eval",
            "/api/graph-ontology",
            "/api/graph-sim",
            "/api/guardrails",
            "/api/install",
            "/api/kb-config",
            "/api/kb/verify",
            "/api/kb/weights",
            "/api/lifecycle",
            "/api/llm/config",
            "/api/mcp",
            "/api/mcp/action-log",
            "/api/mcp/bridges",
            "/api/mcp/cli-injection",
            "/api/mcp/client",
            "/api/mcp/servers",
            "/api/mcp/tools",
            "/api/memory-pools",
            "/api/metrics",
            "/api/model-admission",
            "/api/model-fallback",
            "/api/model-scheduler",
            "/api/model-warmup",
            "/api/monitor",
            "/api/multi-backend",
            "/api/nd4j/environment",
            "/api/op-timing",
            "/api/pipelines",
            "/api/process/attribution",
            "/api/process/diagrams",
            "/api/process/lineage",
            "/api/process/narration",
            "/api/process/ontology",
            "/api/process/synthesis",
            "/api/process/writeback",
            "/api/process-mining-config",
            "/api/processing",
            "/api/processing-settings",
            "/api/query-transformer",
            "/api/rag-pipelines",
            "/api/rag/test",
            "/api/react-agent",
            "/api/retriever",
            "/api/samediff-llm",
            "/api/scheduler",
            "/api/sdx",
            "/api/settings/processing",
            "/api/staging-config",
            "/api/subprocess-config",
            "/api/subprocess-events",
            "/api/test-milestones",
            "/api/tool-gateway",
            "/api/tool-permissions",
            "/api/tools",
            "/api/triton-cache",
            "/api/vlm",
            "/api/vlm-orchestration",
            "/api/vlm/config",
            "/api/vlm/test",
            "/api/weight-cache");

    /**
     * Paths that a <i>library</i> module also mounts, and which therefore cannot fence a persona.
     *
     * <p>Not every controller lives in a web module. {@code kompile-knowledge-graph},
     * {@code kompile-data-enrichment} and the process modules carry their own
     * {@code @RestController}s, and those modules are dependencies of the business logic all three
     * personas need — so their endpoints mount on all three by construction, which a live probe
     * confirms ({@code /api/knowledge-graph} and {@code /api/process} answer 200 on :8080, :8081 and
     * :8082 alike).</p>
     *
     * <p>Usually that is invisible here, because library paths do not collide with persona ones.
     * These two do, in the two different ways a collision can happen:</p>
     * <ul>
     *   <li><b>Nesting.</b> {@code KbGroundingController} in {@code kompile-app-web-chat} owns
     *       {@code /api/kb-grounding}; {@code KbGroundingAuditController} and
     *       {@code KbOpinionBrowserController} in {@code kompile-knowledge-graph} own
     *       {@code /api/kb-grounding/{factSheetId\}} — a read and correction surface over fact tiers
     *       that the crawl manager legitimately needs.</li>
     *   <li><b>Exact collision.</b> {@code /api/enrichment} is mapped twice, by
     *       {@code EnrichmentAgentLabelController} in {@code kompile-app-web-crawl} and by
     *       {@code DataEnrichmentController} in {@code kompile-data-enrichment}. The base path is
     *       genuinely on all three ports with a different method set on each, so it cannot serve as
     *       a marker for the crawl persona no matter how the test is written. Splitting the crawl
     *       half onto its own base path is the only thing that would change that.</li>
     * </ul>
     *
     * <p>Adding an entry here is a real decision: it says the path is on all three ports and that
     * this is intended. If a genuinely persona-scoped controller ever ends up in a library module,
     * the fix is to move the controller, not to widen this set.</p>
     */
    public static final Set<String> LIBRARY_OVERLAPS = Set.of(
            "/api/kb-grounding/{factSheetId}",
            "/api/enrichment");

    /**
     * Mounted by a <i>library</i> module that no persona web module owns.
     *
     * <p>{@link #LIBRARY_OVERLAPS} exists to stop a collision from wrongly failing a persona's
     * forbidden assertion. This set exists for the opposite direction: the completeness check needs
     * somewhere to account for a path that is mounted but owned by nobody. Without it every library
     * endpoint would read as unclassified and the check would be too noisy to keep.</p>
     *
     * <p>The name is deliberate — these are <i>unscoped</i>, not shared. {@link #SHARED} is a
     * decision: every persona needs those families, and each persona asserts it still has them.
     * These are a consequence: which ports they answer on falls out of which personas happen to
     * depend on the library, and nothing re-evaluates that when a pom changes. Most land on all
     * three. These do not, and the split is arbitrary rather than designed:</p>
     * <ul>
     *   <li>{@code kompile-graph-change-tracking} puts {@code /api/graph/changes},
     *       {@code /api/graph/hooks}, {@code /api/graph/pipelines} and {@code /api/graph/rules} on
     *       the chat app and the admin console, but not the crawl manager — which is the one persona
     *       that writes to the graph.</li>
     *   <li>{@code kompile-kclaw} puts the agent-task gateway ({@code /api/kclaw} and its channel,
     *       OAuth and task sub-resources) on the crawl manager and the admin console, but not chat.
     *       Live probe: {@code /api/kclaw/tasks} answers 200 on :8080 and :8082, 404 on :8081.</li>
     *   <li>{@code kompile-compute-graph-core} puts {@code /api/compute-graph} and
     *       {@code /api/workflows} on the crawl manager and the admin console; {@code
     *       kompile-process-discovery} does the same for {@code /api/process/discovery} and
     *       {@code /api/process/mining}.</li>
     * </ul>
     *
     * <p>Listing a path here records where it currently lands; it does not bless the placement. The
     * fix for one that is genuinely persona-scoped is to move the controller into that persona's web
     * module, which takes it out of this set.</p>
     */
    public static final Set<String> LIBRARY_UNSCOPED = Set.of(
            "/api/agent-logs",
            "/api/api/graph/{factSheetId}",
            "/api/attribution",
            "/api/attribution/bayesian",
            "/api/attribution/psl",
            "/api/chat-history",
            "/api/chat-history/cli",
            "/api/code-indexer",
            "/api/code-projects",
            "/api/compute-graph",
            "/api/entity-resolution",
            "/api/folders",
            "/api/graph/{factSheetId}",
            "/api/graph/{factSheetId}/communities",
            "/api/graph/{factSheetId}/rules",
            "/api/graph/algorithms",
            "/api/graph/changes",
            "/api/graph-health",
            "/api/graph/hooks",
            "/api/graph/io",
            "/api/graph-maintenance",
            "/api/graph/maintenance",
            "/api/graph/multi-agent",
            "/api/graph/pipelines",
            "/api/graph/report",
            "/api/graph/rules",
            "/api/kclaw",
            "/api/kclaw/channels",
            "/api/kclaw/oauth",
            "/api/kclaw/tasks",
            "/api/knowledge-graph",
            "/api/knowledge-graph/builder",
            "/api/knowledge-graph/embeddings",
            "/api/knowledge-graph/inferred-facts",
            "/api/kvcache",
            "/api/oauth",
            "/api/oauth/settings",
            "/api/ocr/debug",
            "/api/ocr/pipeline-config",
            "/api/orchestrator",
            "/api/orchestrator/{instanceId}/audit",
            "/api/orchestrator/{instanceId}/classifiers",
            "/api/orchestrator/{instanceId}/llm",
            "/api/orchestrator/{instanceId}/state-machine",
            "/api/orchestrator/{instanceId}/tasks",
            "/api/orchestrator/{instanceId}/workflows",
            "/api/orchestrator/hooks",
            "/api/process",
            "/api/process/discovery",
            "/api/process/mining",
            "/api/process/releases",
            "/api/subprocess-logs",
            "/api/workflows");

    /**
     * Mounted by every persona through {@code kompile-app-web-shared} and the library modules it
     * pulls in. Every persona test asserts this set is intact — losing one of these is how an
     * end-user app quietly stops being able to pick a fact sheet.
     *
     * <p>Sub-paths are listed alongside their parent rather than folded into it, because the
     * completeness check matches exactly: {@code /api/fact-sheets} and
     * {@code /api/fact-sheets/{factSheetId\}/notes} are separate controllers that can go missing
     * separately, and only listing the parent would let the notes surface disappear unnoticed.</p>
     *
     * <p>Two families are split across the persona line rather than owned outright.
     * {@code /api/models} is the read-only status and discovery surface in
     * {@code kompile-app-web-shared}; {@code ModelRegistryController} and
     * {@code Nd4jProfilingController} mount admin-only sub-paths on the same base path from
     * {@code kompile-app-web-admin}. {@code /api/documents} is the same shape, with
     * {@code DocumentIngestDebugController} contributing {@code /api/documents/debug}, which is
     * listed in {@link #ADMIN}. Base-path granularity cannot express that, so the split lives in the
     * poms and in {@code docs/architecture/app-persona-boundary.md}.</p>
     */
    public static final Set<String> SHARED = Set.of(
            "/api/config",
            "/api/config/k-app",
            "/api/documents",
            "/api/fact-sheets",
            "/api/fact-sheets/{factSheetId}/graph",
            "/api/fact-sheets/{factSheetId}/notes",
            "/api/fact-sheets/{factSheetId}/notes/promote",
            "/api/fact-sheets/{factSheetId}/notes/search",
            "/api/facts",
            "/api/index-browser",
            "/api/knowledge",
            "/api/models",
            "/api/notes/{noteId}",
            "/api/project-store",
            "/api/projects",
            "/api/projects/current/portability",
            "/api/sdk",
            "/api/services",
            "/api/setup",
            "/api/source-providers",
            "/api/sources",
            "/api/sync",
            "/api/sync/config",
            "/api/sync/webhook/notion",
            "/api/system",
            "/api/tables");
}
