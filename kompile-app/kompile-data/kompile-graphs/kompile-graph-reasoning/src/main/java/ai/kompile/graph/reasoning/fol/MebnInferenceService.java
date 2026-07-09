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
package ai.kompile.graph.reasoning.fol;

import ai.kompile.graph.reasoning.bayesian.BayesianNetwork;
import ai.kompile.graph.reasoning.bayesian.Factor;
import ai.kompile.graph.reasoning.bayesian.VariableElimination;
import ai.kompile.graph.reasoning.mebn.EntityType;
import ai.kompile.graph.reasoning.mebn.MFrag;
import ai.kompile.graph.reasoning.mebn.MTheory;
import ai.kompile.graph.reasoning.mebn.RandomVariable;
import ai.kompile.graph.reasoning.mebn.SSBNGenerator;
import ai.kompile.graph.reasoning.mebn.SsbnConstructionLog;
import ai.kompile.graph.reasoning.mebn.SsbnConstructionResult;
import ai.kompile.graph.reasoning.model.ReasoningGraph;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Convenience service for running MEBN/SSBN inference entirely within this library,
 * using a {@link ReasoningGraph} as the knowledge base.
 *
 * <p>This closes the MEBN loop: previously {@link SSBNGenerator} required an external
 * {@link ai.kompile.graph.reasoning.mebn.logic.KnowledgeBase} (only implemented in
 * {@code kompile-event-attribution}). Now callers can go from a {@link ReasoningGraph} to
 * Bayesian posterior probabilities in a single method call, with no external dependencies.</p>
 *
 * <h3>Quickstart</h3>
 * <pre>
 *   MutableReasoningGraph g = new MutableReasoningGraph();
 *   g.addEntity("alice", "Person", "Alice");
 *   g.addEntity("bob",   "Person", "Bob");
 *   g.addRelation("r1", "alice", "bob", "KNOWS", 0.9);
 *
 *   // Build an MTheory with one MFrag
 *   MTheory theory = MebnInferenceService.buildSimpleTheory(g, "Person", "isActive");
 *
 *   // Run SSBN inference
 *   MebnInferenceService svc = new MebnInferenceService();
 *   Map&lt;String,Double&gt; posteriors = svc.infer(g, theory, Map.of());
 * </pre>
 */
public final class MebnInferenceService {

    private static final Logger log = LoggerFactory.getLogger(MebnInferenceService.class);

    /**
     * Run MEBN inference over {@code graph} using {@code theory} and the given evidence.
     *
     * @param graph    the reasoning graph (becomes the KnowledgeBase)
     * @param theory   the MTheory to instantiate
     * @param evidence map from grounded BN variable names to observed state indices (0=FALSE, 1=TRUE)
     * @return map from grounded BN variable name → posterior probability of TRUE (state index 1)
     */
    public Map<String, Double> infer(ReasoningGraph graph, MTheory theory,
                                      Map<String, Integer> evidence) {
        log.info("MEBN inference: graph={} entities, theory={}", graph.entityCount(), theory.getName());

        // Wrap the graph in the in-lib KnowledgeBase
        ReasoningGraphKnowledgeBase kb = new ReasoningGraphKnowledgeBase(graph);

        // Generate the SSBN
        SSBNGenerator generator = new SSBNGenerator(theory, kb);
        BayesianNetwork ssbn = generator.generate();

        // Run variable elimination for all target variables
        Map<String, Double> posteriors = VariableElimination.queryAll(ssbn, evidence);
        log.info("MEBN inference complete: {} posteriors", posteriors.size());
        return posteriors;
    }

    /**
     * Run MEBN inference for only the requested grounded variables.
     *
     * <p>The SSBN is still generated once from the full theory and graph, preserving the same
     * model semantics as {@link #infer}. The posterior pass is narrowed to variables the caller
     * will actually consume, which avoids running exact inference for thousands of unrelated
     * SSBN nodes during online weight learning.</p>
     *
     * @param graph          the reasoning graph (becomes the KnowledgeBase)
     * @param theory         the MTheory to instantiate
     * @param evidence       map from grounded BN variable names to observed state indices
     * @param queryVariables grounded variable names whose posterior is needed
     * @return map from requested grounded variable name -> posterior probability of TRUE
     */
    public Map<String, Double> inferVariables(ReasoningGraph graph, MTheory theory,
                                               Map<String, Integer> evidence,
                                               Collection<String> queryVariables) {
        if (queryVariables == null || queryVariables.isEmpty()) {
            return Map.of();
        }
        log.info("MEBN targeted inference: graph={} entities, theory={}, queries={}",
                graph.entityCount(), theory.getName(), queryVariables.size());

        ReasoningGraphKnowledgeBase kb = new ReasoningGraphKnowledgeBase(graph);
        SSBNGenerator generator = new SSBNGenerator(theory, kb);
        BayesianNetwork ssbn = generator.generate();

        Map<String, Double> posteriors = VariableElimination.querySubset(ssbn, queryVariables, evidence);
        log.info("MEBN targeted inference complete: {} of {} requested posteriors",
                posteriors.size(), queryVariables.size());
        return posteriors;
    }

    /**
     * Query SSBN inference for a specific variable.
     *
     * @param graph       the reasoning graph (becomes the KnowledgeBase)
     * @param theory      the MTheory to instantiate
     * @param queryRvName the random variable name to query (e.g. "isActive")
     * @param evidence    map from grounded variable name → observed state index
     * @return posterior probability of TRUE for the query variable (all groundings, keyed by grounded name)
     */
    public Map<String, Double> inferQuery(ReasoningGraph graph, MTheory theory,
                                           String queryRvName, Map<String, Integer> evidence) {
        log.info("MEBN query inference: variable='{}', graph={} entities", queryRvName, graph.entityCount());

        ReasoningGraphKnowledgeBase kb = new ReasoningGraphKnowledgeBase(graph);
        SSBNGenerator generator = new SSBNGenerator(theory, kb);
        BayesianNetwork ssbn = generator.generateForQuery(queryRvName);

        Map<String, Double> posteriors = new LinkedHashMap<>();
        for (var node : ssbn.getNodes()) {
            String var = node.getVariableName();
            if (var.startsWith(queryRvName)) {
                try {
                    Factor f = VariableElimination.query(ssbn, var, evidence);
                    f = f.normalize();
                    int trueIdx = node.getStateIndex("TRUE");
                    if (trueIdx >= 0 && trueIdx < f.getValues().length) {
                        posteriors.put(var, f.getValues()[trueIdx]);
                    }
                } catch (Exception e) {
                    log.debug("Skipping variable '{}': {}", var, e.getMessage());
                }
            }
        }

        log.info("MEBN query '{}' complete: {} results", queryRvName, posteriors.size());
        return posteriors;
    }

    /**
     * Run MEBN inference and return its posteriors as probabilistic {@link InferredFact}s — one per
     * grounded random-variable instance (atomKey {@code rv(entityId)}, value = posterior P(TRUE)). This
     * feeds the <em>same</em> fact sink as the PSL path ({@code FolInferenceService.inferFacts}), so a
     * MEBN run's conclusions persist and materialize into the graph as INFERRED edges/attributes. The
     * grounded atom keys ({@code isActive(node_123)}) are already in {@code Pred(entityId)} form, so the
     * downstream materializer parses them unchanged.
     *
     * @param graph    the reasoning graph
     * @param theory   the MTheory to instantiate
     * @param findings findings used as evidence (may be empty for prior-only inference)
     */
    public List<InferredFact> inferFacts(ReasoningGraph graph, MTheory theory, FindingStore findings) {
        List<EntailmentRecord> records = EntailmentEngine.entailFromMebn(graph, findings, theory);
        List<InferredFact> facts = new ArrayList<>(records.size());
        for (EntailmentRecord record : records) {
            facts.add(InferredFact.fromEntailment(record, 1L));
        }
        log.info("MEBN inference emitted {} probabilistic facts", facts.size());
        return facts;
    }

    /**
     * As {@link #inferFacts(ReasoningGraph, MTheory, FindingStore)} but also persists each fact into
     * {@code store} (which assigns the canonical monotonic version per atom key).
     */
    public List<InferredFact> inferFacts(ReasoningGraph graph, MTheory theory, FindingStore findings,
                                         InferredFactStore store) {
        List<InferredFact> facts = inferFacts(graph, theory, findings);
        for (InferredFact fact : facts) {
            store.store(fact);
        }
        return facts;
    }

    // ─── Log-aware inference methods (additive — do not alter existing signatures) ──

    /**
     * Paired result returned by {@link #inferWithLog} and {@link #inferQueryWithLog}.
     *
     * @param posteriors the posterior map (identical to what the corresponding non-log method returns)
     * @param log        the SSBN construction log for this inference pass
     */
    public record InferenceWithLog(Map<String, Double> posteriors, SsbnConstructionLog log) {}

    /**
     * Run MEBN inference over the full theory, returning posteriors AND a detailed
     * SSBN construction log.
     *
     * <p>Semantics of {@code posteriors} are identical to {@link #infer}; the log
     * captures per-node grounding decisions (MFrag, OV substitution, context-constraint
     * outcomes, {@link SSBNGenerator.DistributionMode}).</p>
     *
     * @param graph    the reasoning graph
     * @param theory   the MTheory to instantiate
     * @param evidence map from grounded BN variable names to observed state indices
     * @return posterior map + construction log
     */
    public InferenceWithLog inferWithLog(ReasoningGraph graph, MTheory theory,
                                          Map<String, Integer> evidence) {
        log.info("MEBN inference (with log): graph={} entities, theory={}", graph.entityCount(), theory.getName());
        ReasoningGraphKnowledgeBase kb = new ReasoningGraphKnowledgeBase(graph);
        SSBNGenerator generator = new SSBNGenerator(theory, kb);
        SsbnConstructionResult result = generator.generateWithLog();
        Map<String, Double> posteriors = VariableElimination.queryAll(result.network(), evidence);
        log.info("MEBN inference (with log) complete: {} posteriors", posteriors.size());
        return new InferenceWithLog(posteriors, result.log());
    }

    /**
     * Query SSBN inference for a specific variable, returning posteriors AND a detailed
     * SSBN construction log.
     *
     * <p>Semantics of {@code posteriors} are identical to {@link #inferQuery}; the log
     * captures construction decisions and records nodes removed by Bayes-Ball pruning
     * in {@link SsbnConstructionLog#prunedNodes()}.</p>
     *
     * @param graph       the reasoning graph
     * @param theory      the MTheory to instantiate
     * @param queryRvName the random variable name to query
     * @param evidence    map from grounded variable name → observed state index
     * @return posterior map + construction log
     */
    public InferenceWithLog inferQueryWithLog(ReasoningGraph graph, MTheory theory,
                                               String queryRvName,
                                               Map<String, Integer> evidence) {
        log.info("MEBN query inference (with log): variable='{}', graph={} entities",
                queryRvName, graph.entityCount());
        ReasoningGraphKnowledgeBase kb = new ReasoningGraphKnowledgeBase(graph);
        SSBNGenerator generator = new SSBNGenerator(theory, kb);
        SsbnConstructionResult result = generator.generateForQueryWithLog(queryRvName);

        Map<String, Double> posteriors = new LinkedHashMap<>();
        for (var node : result.network().getNodes()) {
            String var = node.getVariableName();
            if (var.startsWith(queryRvName)) {
                try {
                    Factor f = VariableElimination.query(result.network(), var, evidence);
                    f = f.normalize();
                    int trueIdx = node.getStateIndex("TRUE");
                    if (trueIdx >= 0 && trueIdx < f.getValues().length) {
                        posteriors.put(var, f.getValues()[trueIdx]);
                    }
                } catch (Exception e) {
                    log.debug("Skipping variable '{}': {}", var, e.getMessage());
                }
            }
        }
        log.info("MEBN query '{}' (with log) complete: {} results", queryRvName, posteriors.size());
        return new InferenceWithLog(posteriors, result.log());
    }

    // ─── Theory builder helpers ──────────────────────────────────────────────────

    /**
     * Build a simple MTheory from a graph with a single unary random variable.
     *
     * <p>All entities of the given {@code entityTypeName} become instances of the entity type.
     * A single MFrag is created with one resident variable {@code rvName(EntityType)} and no
     * context constraints. This is a minimal but complete runnable MTheory.</p>
     *
     * @param graph          the source graph
     * @param entityTypeName the entity type to build the theory over
     * @param rvName         the name of the binary (FALSE/TRUE) random variable
     * @return a complete, runnable MTheory
     */
    public static MTheory buildSimpleTheory(ReasoningGraph graph,
                                             String entityTypeName, String rvName) {
        // Register all entities of the given type
        EntityType entityType = EntityType.fromGraph(graph, entityTypeName, entityTypeName + " entities");

        // Build the MFrag
        MFrag mfrag = new MFrag(rvName + "MFrag");
        RandomVariable rv = RandomVariable.unary(rvName, entityType, RandomVariable.NodeRole.RESIDENT);
        mfrag.addResidentNode(rv);
        // No context constraints — applies to all entities of the type

        // Build the MTheory
        MTheory theory = new MTheory(rvName + "Theory");
        theory.addEntityType(entityType);
        theory.addMFrag(mfrag);
        return theory;
    }

    /**
     * Build an MTheory with two MFrags that model propagation: an entity activates another
     * via an edge, subject to a context constraint that an edge exists between them.
     *
     * @param graph     the source graph
     * @param typeName  entity type (both source and target have this type)
     * @param edgeType  edge type that represents the propagation link (e.g. "ACTIVATES")
     * @param rvName    base name of the random variable (e.g. "isActive")
     * @param strength  causal strength of the propagation edge in [0, 1]
     * @return a complete, runnable MTheory with context-constrained propagation
     */
    public static MTheory buildPropagationTheory(ReasoningGraph graph,
                                                   String typeName, String edgeType,
                                                   String rvName, double strength) {
        EntityType entityType = EntityType.fromGraph(graph, typeName, typeName + " nodes");

        // Resident MFrag: isActive(Entity) is the root variable
        MFrag rootFrag = new MFrag(rvName + "RootMFrag");
        RandomVariable rootRv = RandomVariable.unary(rvName, entityType, RandomVariable.NodeRole.RESIDENT);
        rootFrag.addResidentNode(rootRv);

        // Propagation MFrag: isActiveProp(Y) depends on isActive(X) when edge(X,Y) of edgeType exists.
        // arg-var "Y" for the resident and "X" for the input so that the Cartesian product expands
        // over distinct (X, Y) pairs, and the context edgeOfType("X","Y",edgeType) can resolve both.
        MFrag propFrag = new MFrag(rvName + "PropMFrag");
        RandomVariable residentRv = RandomVariable.unary(rvName + "Prop",
                "Y", entityType, RandomVariable.NodeRole.RESIDENT);
        RandomVariable inputRv = RandomVariable.unary(rvName,
                "X", entityType, RandomVariable.NodeRole.INPUT);

        propFrag.addResidentNode(residentRv);
        propFrag.addInputNode(inputRv);
        propFrag.addParentEdge(rvName, rvName + "Prop", strength);

        // Context: edgeOfType(X, Y, edgeType) — MFrag only applies when the link exists
        propFrag.addContextConstraint(
                ai.kompile.graph.reasoning.mebn.logic.Constraints.edgeOfType("X", "Y", edgeType));

        MTheory theory = new MTheory(rvName + "PropTheory");
        theory.addEntityType(entityType);
        theory.addMFrag(rootFrag);
        theory.addMFrag(propFrag);
        return theory;
    }

    /**
     * Build an MTheory with a single learnable noisy-OR edge: per entity {@code X} of {@code typeName},
     * {@code effectRv(X)} depends on {@code causeRv(X)} with the given causal {@code strength}.
     *
     * <p>Unlike {@link #buildPropagationTheory} (which gates a separate downstream RV behind a relational
     * {@code edge(X,Y)} context constraint), here the dependency is <em>per-entity</em> and unconstrained,
     * so the SSBN actually grounds {@code effectRv(X)} as a child of {@code causeRv(X)} for every entity.
     * Consequently {@code effectRv(X)}'s posterior is sensitive to the edge strength, which makes this the
     * canonical theory shape for noisy-OR <b>edge-strength learning</b> ({@code MebnWeightLearner}): fit the
     * strength so the surfaced {@code effectRv} posteriors match observed targets.</p>
     *
     * <p>Two MFrags are produced: a root MFrag giving {@code causeRv(X)} a prior, and a causal MFrag in
     * which {@code effectRv(X)} (resident) has {@code causeRv(X)} (input) as its single noisy-OR parent.</p>
     *
     * @param graph    the source graph (its entities of {@code typeName} become the entity instances)
     * @param typeName the entity type both RVs range over
     * @param causeRv  the parent random-variable name (gets a prior)
     * @param effectRv the child random-variable name (depends on {@code causeRv} via the learnable edge)
     * @param strength initial causal strength of the {@code causeRv → effectRv} edge in [0, 1]
     * @return a complete, runnable MTheory whose single edge strength is learnable from data
     */
    public static MTheory buildCausalTheory(ReasoningGraph graph, String typeName,
                                            String causeRv, String effectRv, double strength) {
        EntityType entityType = EntityType.fromGraph(graph, typeName, typeName + " entities");

        // Root MFrag: causeRv(X) is the prior (parent) variable.
        MFrag causeFrag = new MFrag(causeRv + "RootMFrag");
        causeFrag.addResidentNode(RandomVariable.unary(causeRv, entityType, RandomVariable.NodeRole.RESIDENT));

        // Causal MFrag: effectRv(X) depends on causeRv(X) via a single noisy-OR edge.
        MFrag effectFrag = new MFrag(effectRv + "CausalMFrag");
        effectFrag.addResidentNode(RandomVariable.unary(effectRv, entityType, RandomVariable.NodeRole.RESIDENT));
        effectFrag.addInputNode(RandomVariable.unary(causeRv, entityType, RandomVariable.NodeRole.INPUT));
        effectFrag.addParentEdge(causeRv, effectRv, strength);

        MTheory theory = new MTheory(effectRv + "CausalTheory");
        theory.addEntityType(entityType);
        theory.addMFrag(causeFrag);
        theory.addMFrag(effectFrag);
        return theory;
    }
}
