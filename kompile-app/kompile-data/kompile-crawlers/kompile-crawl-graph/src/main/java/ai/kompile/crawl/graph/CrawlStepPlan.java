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

package ai.kompile.crawl.graph;

import ai.kompile.core.crawl.graph.UnifiedCrawlRequest;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Declarative per-step execution plan (RUN / SKIP / ARCHIVE) computed from a {@link UnifiedCrawlRequest}.
 *
 * <p>Pure value object — unit-testable without Spring. It is the heart of the "modular crawl": it lets
 * a caller pick which steps run, archive steps to run later, and keep the rest skipped, while keeping
 * the existing pipeline's behavior intact when nothing is specified (every step defaults to RUN).</p>
 *
 * <p>Two selection modes:</p>
 * <ul>
 *   <li><b>Legacy / default</b> — {@code enabledSteps} and {@code archivedSteps} both empty: every step
 *       RUNs, with vector indexing and preprocessing remaining optional. Graph construction always runs.</li>
 *   <li><b>Explicit RUN</b> — {@code enabledSteps} non-empty: those steps, their transitive hard
 *       dependencies, all foundational steps, and the mandatory graph spine RUN; everything else is SKIP.
 *       Dependency-safe.</li>
 *   <li><b>Strict explicit RUN</b> — {@code enabledSteps} non-empty AND {@code strictSteps=true}: same
 *       as explicit RUN but the mandatory graph spine is NOT force-added, so a caller can run e.g. only
 *       VECTOR_INDEXING or only GRAPH_EXTRACTION. Dependency closure and validation still apply.</li>
 *   <li><b>Archive</b> — {@code archivedSteps} non-empty while {@code enabledSteps} is empty: the
 *       default "every step RUNs" still holds, MINUS archivable non-mandatory steps. Mandatory graph
 *       steps cannot be archived out. (Archiving + enabling together: explicit RUN of the enabled set,
 *       then the archived non-mandatory steps are removed from it.)</li>
 * </ul>
 */
public final class CrawlStepPlan {

    public enum Action { RUN, SKIP, ARCHIVE }

    private static final Set<String> MANDATORY_GRAPH_STEPS = Set.of(
            "GRAPH_PREP",
            "GRAPH_EXTRACTION",
            "SURFACING",
            "ENTITY_RESOLUTION",
            "EDGE_COMPUTATION"
    );

    private final Map<String, Action> actions;

    private CrawlStepPlan(Map<String, Action> actions) {
        this.actions = actions;
    }

    /** Action for a step; unknown / unspecified steps default to RUN (backward compatible). */
    public Action forStep(String stepId) {
        return actions.getOrDefault(stepId, Action.RUN);
    }

    public boolean isRun(String stepId) {
        return forStep(stepId) == Action.RUN;
    }

    public boolean isSkip(String stepId) {
        return forStep(stepId) == Action.SKIP;
    }

    public boolean isArchive(String stepId) {
        return forStep(stepId) == Action.ARCHIVE;
    }

    /** Immutable view of the resolved plan, keyed by step ID. */
    public Map<String, Action> actions() {
        return Collections.unmodifiableMap(actions);
    }

    /**
     * Build a plan from a request. See the class Javadoc for the two selection modes.
     */
    public static CrawlStepPlan from(UnifiedCrawlRequest request) {
        if (request == null) {
            return new CrawlStepPlan(defaultPlan());
        }

        // Validate and canonicalize before resolving legacy defaults or explicit selection. An invalid
        // request must never be able to fall through to the full-pipeline plan.
        List<String> enabled = normalizeAndValidate("enabledSteps", request.getEnabledSteps());
        List<String> archived = normalizeAndValidate("archivedSteps", request.getArchivedSteps());

        // Sorted so the resolved plan is deterministic for logging / equality in tests.
        Map<String, Action> plan = defaultPlan();

        // Seed of explicitly-ENABLED steps only. Archiving must NOT flip the plan to whitelist mode:
        // archived steps are subtracted from the default-everything-runs below. Seeding `archived` here
        // (the old behaviour) meant archiving ONE step silently SKIPped every unselected step —
        // ENRICHMENT and the rest of the graph pipeline — which is the opposite of a modular opt-out.
        Set<String> selected = new LinkedHashSet<>(enabled);
        boolean explicitSelection = !selected.isEmpty();
        // Strict mode: honor the explicit selection as-is (plus hard deps + foundational steps below)
        // instead of force-seeding the graph spine. Only meaningful with a non-empty selection; legacy
        // requests (strictSteps null/false) keep the spine mandatory, byte-identical to before.
        boolean strictSelection = explicitSelection && Boolean.TRUE.equals(request.getStrictSteps());
        if (!strictSelection) {
            selected.addAll(MANDATORY_GRAPH_STEPS);
        }

        if (explicitSelection) {
            // Explicit selection: keep = selected + their transitive hard deps + all foundational steps.
            Set<String> keep = transitiveClosure(selected);
            for (CrawlPipelineStepRegistry.StepDescriptor d : CrawlPipelineStepRegistry.all()) {
                if (d.foundational() || keep.contains(d.id())) {
                    plan.put(d.id(), Action.RUN);
                } else {
                    plan.put(d.id(), Action.SKIP);
                }
            }
        } else {
            // Legacy mode: vector config toggle + PREPROCESSING opt-in. Graph construction is mandatory.
            if (request.getVectorIndex() == null || !request.getVectorIndex().isEnabled()) {
                plan.put("VECTOR_INDEXING", Action.SKIP);
            }
            if (request.getPreprocessing() == null) {
                plan.put("PREPROCESSING", Action.SKIP);
            }
        }

        // Archive overrides (both modes). Only steps the registry marks archivable can be archived;
        // foundational / pivot steps (load, convert, route, chunk) always run.
        if (archived != null) {
            for (String id : archived) {
                CrawlPipelineStepRegistry.StepDescriptor d = CrawlPipelineStepRegistry.get(id);
                if (d != null && d.archivable() && !d.foundational() && !MANDATORY_GRAPH_STEPS.contains(id)) {
                    plan.put(id, Action.ARCHIVE);
                }
            }
        }

        // A step can only RUN now if all its hard dependencies RUN. Propagate SKIP/ARCHIVE downstream so
        // the plan is self-consistent (e.g. archive CHUNKING-consumers leaves their own dependents skipped).
        cascadeNonRun(plan);
        return new CrawlStepPlan(plan);
    }

    /**
     * Validate dependencies. Throws {@link IllegalArgumentException} when a foundational step is not
     * RUN, a RUN/ARCHIVE step has a non-RUN hard dependency, or a chunk-consumer is RUN/ARCHIVE while
     * CHUNKING is not RUN.
     */
    public void validate() {
        for (CrawlPipelineStepRegistry.StepDescriptor d : CrawlPipelineStepRegistry.all()) {
            Action a = forStep(d.id());
            if (d.foundational() && a != Action.RUN) {
                throw new IllegalArgumentException(
                        "Foundational step " + d.id() + " cannot be " + a + "; it must run.");
            }
            if (a == Action.SKIP) {
                continue;
            }
            for (String dep : d.hardDependsOn()) {
                if (forStep(dep) != Action.RUN) {
                    throw new IllegalArgumentException(
                            "Step " + d.id() + " is " + a + " but its dependency " + dep
                                    + " is " + forStep(dep) + " (it must RUN).");
                }
            }
            if (d.chunkConsumerOnly() && forStep("CHUNKING") != Action.RUN) {
                throw new IllegalArgumentException(
                        "Step " + d.id() + " needs chunked text but CHUNKING is "
                                + forStep("CHUNKING") + " (it must RUN).");
            }
        }
    }

    /**
     * Downgrade any RUN step to SKIP when one of its hard dependencies is not RUN. Iterates to a
     * fixpoint. ARCHIVE steps are left untouched (intentionally not-run-now); {@link #validate()} still
     * flags an ARCHIVE step whose dependency is not RUN as a genuine contradiction.
     */
    private static void cascadeNonRun(Map<String, Action> plan) {
        boolean changed = true;
        while (changed) {
            changed = false;
            for (CrawlPipelineStepRegistry.StepDescriptor d : CrawlPipelineStepRegistry.all()) {
                if (d.foundational() || plan.get(d.id()) != Action.RUN) {
                    continue;
                }
                for (String dep : d.hardDependsOn()) {
                    if (plan.getOrDefault(dep, Action.RUN) != Action.RUN) {
                        plan.put(d.id(), Action.SKIP);
                        changed = true;
                        break;
                    }
                }
            }
        }
    }

    private static Map<String, Action> defaultPlan() {
        Map<String, Action> plan = new TreeMap<>();
        for (CrawlPipelineStepRegistry.StepDescriptor d : CrawlPipelineStepRegistry.all()) {
            plan.put(d.id(), Action.RUN);
        }
        return plan;
    }

    private static List<String> normalizeAndValidate(String fieldName, List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        List<String> normalized = new ArrayList<>(ids.size());
        List<String> invalid = new ArrayList<>();
        for (String rawId : ids) {
            String id = rawId == null ? "" : rawId.trim().toUpperCase(Locale.ROOT);
            if (id.isEmpty() || !CrawlPipelineStepRegistry.isKnown(id)) {
                invalid.add(rawId == null ? "<null>" : rawId.isBlank() ? "<blank>" : rawId);
            } else {
                normalized.add(id);
            }
        }
        if (!invalid.isEmpty()) {
            List<String> validIds = CrawlPipelineStepRegistry.all().stream()
                    .map(CrawlPipelineStepRegistry.StepDescriptor::id)
                    .toList();
            throw new IllegalArgumentException("Unknown " + fieldName + " step ID(s): " + invalid
                    + ". Valid step IDs: " + validIds);
        }
        return normalized;
    }

    /** All selected steps plus every step reachable through their hard-dependency edges. */
    private static Set<String> transitiveClosure(Set<String> seed) {
        Set<String> keep = new LinkedHashSet<>();
        Deque<String> stack = new ArrayDeque<>(seed);
        while (!stack.isEmpty()) {
            String id = stack.pop();
            if (keep.contains(id)) {
                continue;
            }
            CrawlPipelineStepRegistry.StepDescriptor d = CrawlPipelineStepRegistry.get(id);
            if (d == null) {
                continue;
            }
            keep.add(id);
            for (String dep : d.hardDependsOn()) {
                if (!keep.contains(dep)) {
                    stack.push(dep);
                }
            }
        }
        return keep;
    }
}
