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
package ai.kompile.graph.reasoning.local;

import ai.kompile.graph.reasoning.bayesian.BayesianNetwork;
import ai.kompile.graph.reasoning.bayesian.GraphBayesianNetworkBuilder;
import ai.kompile.graph.reasoning.bayesian.VariableElimination;
import ai.kompile.graph.reasoning.claims.ClaimDossier;
import ai.kompile.graph.reasoning.claims.DossierBuilder;
import ai.kompile.graph.reasoning.claims.DossierItem;
import ai.kompile.graph.reasoning.confidence.Opinion;
import ai.kompile.graph.reasoning.embedding.kge.KgeTripleScorer;
import ai.kompile.graph.reasoning.embedding.learn.EmbeddingTable;
import ai.kompile.graph.reasoning.model.GraphEntity;
import ai.kompile.graph.reasoning.model.GraphRelation;
import ai.kompile.graph.reasoning.synthesis.AnswerSynthesizer;
import ai.kompile.graph.reasoning.unified.MiniJson;
import ai.kompile.graph.reasoning.unified.UnifiedGraph;
import ai.kompile.graph.reasoning.unified.VectorLayer;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Inference tool handlers: Bayesian inference, MEBN/MTheory query, claim dossier, and
 * answer synthesis.
 *
 * <h3>Tools registered</h3>
 * <ul>
 *   <li>{@code ask_graph_mebn} — BFS neighborhood subgraph + Bayesian network variable elimination.
 *       Entity {@link GraphEntity#weight()} is the root prior (default 1.0 → deterministic
 *       posteriors; meaningful results require entities with weights strictly between 0 and 1).</li>
 *   <li>{@code graph_bayes} — four actions: query, mpe, whatif, stats.</li>
 *   <li>{@code ask_graph_claim} — multi-signal claim dossier via {@link DossierBuilder}.</li>
 *   <li>{@code ask_graph_synthesize} — ranked answer synthesis via {@link AnswerSynthesizer}.</li>
 * </ul>
 *
 * <h3>Design notes</h3>
 * <ul>
 *   <li>All handlers catch exceptions and return {@code {"status":"ERROR","message":"..."}}.</li>
 *   <li>{@code factSheetId} is accepted and silently ignored (local sessions are single-graph).</li>
 *   <li>Embedding cosine in {@code ask_graph_claim} is attempted only when a {@code "kge"} layer
 *       exists in the session graph — skipped otherwise so the tool works on graphs without KGE.</li>
 *   <li>{@code ask_graph_mebn} and {@code graph_bayes} both use
 *       {@link GraphBayesianNetworkBuilder} + {@link VariableElimination}: MEBN adds a
 *       neighborhood BFS step first and maps the result back to entity ids.</li>
 * </ul>
 */
public final class InferenceHandlers {

    private InferenceHandlers() {}

    /**
     * Register all inference handlers with the dispatcher builder.
     *
     * @param builder the dispatcher builder
     */
    static void register(LocalToolDispatcher.Builder builder) {
        builder.handler("ask_graph_mebn",
                schemaFor("ask_graph_mebn",
                        "Run MEBN/Bayesian belief propagation around a node. BFS extracts a " +
                        "neighborhood subgraph (maxDepth, maxNodes), builds a Bayesian network " +
                        "via noisy-OR CPTs (entity.weight() is the root prior — all-1.0 weights " +
                        "produce degenerate/deterministic posteriors; use meaningful weights for " +
                        "probabilistic results), then runs variable elimination. Returns posteriors " +
                        "and priors keyed by entity id.",
                        List.of("nodeId"),
                        buildMebnSchema()),
                InferenceHandlers::handleMebn);

        builder.handler("graph_bayes",
                schemaFor("graph_bayes",
                        "Bayesian inference over the session graph. action=query: posteriors given " +
                        "evidence (evidence map {entityId:0|1}); action=mpe: most probable " +
                        "explanation assignment; action=whatif: posterior shift under " +
                        "hypothetical_evidence vs baseline; action=stats: network statistics.",
                        List.of("action"),
                        buildBayesSchema()),
                InferenceHandlers::handleBayes);

        builder.handler("ask_graph_claim",
                schemaFor("ask_graph_claim",
                        "Multi-signal claim dossier for (subject, predicate, object). Channels: " +
                        "DIRECT_EDGE (graph relation match), KB verifier (observed + inferred " +
                        "fact stores), Knowledge-Linker PATH (indirect paths when no direct edge), " +
                        "KGE embedding cosine (skipped when no 'kge' layer). Fuses via log-odds " +
                        "linear. Returns verdict (LIKELY_TRUE/LIKELY_FALSE/UNCERTAIN), fusedScore, " +
                        "supporting[], refuting[].",
                        List.of("subject", "predicate", "object"),
                        buildClaimSchema()),
                InferenceHandlers::handleClaim);

        builder.handler("ask_graph_synthesize",
                schemaFor("ask_graph_synthesize",
                        "Answer synthesis over session graph entities. Builds one Candidate per " +
                        "entity (filtered by expectedType when given), adds a RETRIEVAL signal " +
                        "from entity.weight(), and optionally a TYPE signal for type match. Ranks " +
                        "by fused likelihood. Returns answers[] with entityId, likelihood, belief, " +
                        "uncertainty.",
                        List.of("query"),
                        buildSynthesizeSchema()),
                InferenceHandlers::handleSynthesize);
    }

    // ── Handler: ask_graph_mebn ───────────────────────────────────────────────

    private static String handleMebn(LocalReasoningSession session, Map<String, Object> args) {
        try {
            String nodeId = str(args, "nodeId");
            if (nodeId == null || nodeId.isBlank()) {
                return error("ask_graph_mebn requires 'nodeId'");
            }
            int maxDepth = intVal(args, "maxDepth", 3);
            int maxNodes = intVal(args, "maxNodes", 50);

            UnifiedGraph full = session.graph();

            // Validate nodeId exists
            Optional<? extends GraphEntity> rootOpt = full.entity(nodeId);
            if (rootOpt.isEmpty()) {
                return error("Entity not found: " + nodeId);
            }

            // BFS subgraph around nodeId
            UnifiedGraph sub = full.neighborhood(List.of(nodeId), maxDepth);

            // Cap to maxNodes (neighborhood() respects maxNodes via SubgraphSpec when > 0,
            // but we set 0 above for no-cap; apply our own cap by trimming to first maxNodes)
            sub = capGraph(sub, maxNodes);

            long t0 = System.currentTimeMillis();

            // Build Bayesian network from subgraph
            GraphBayesianNetworkBuilder builder = new GraphBayesianNetworkBuilder();
            BayesianNetwork network = builder.build(sub);

            Map<String, String> varToEntityId = builder.variableToEntityId();
            Map<String, String> entityIdToVar = builder.entityIdToVariable();

            // Prior: read CPT root nodes (P(v=TRUE) from CPT[1])
            Map<String, Object> priors = new LinkedHashMap<>();
            Map<String, Object> variableToTitle = new LinkedHashMap<>();

            for (GraphEntity entity : sub.entities()) {
                String var = entityIdToVar.get(entity.id());
                if (var == null) continue;
                String title = entity.label().isEmpty() ? entity.id() : entity.label();
                variableToTitle.put(entity.id(), title);
                // Prior = entity.weight() clamped to [0,1] (used as root prior by builder)
                double prior = Math.max(0.0, Math.min(1.0, entity.weight()));
                priors.put(entity.id(), prior);
            }

            // Run variable elimination: posteriors with no evidence
            Map<String, Double> varPosteriors = VariableElimination.queryAll(network, Map.of());

            // Map back from var names to entity ids
            Map<String, Object> posteriors = new LinkedHashMap<>();
            for (Map.Entry<String, Double> e : varPosteriors.entrySet()) {
                String entityId = varToEntityId.get(e.getKey());
                if (entityId != null) {
                    posteriors.put(entityId, round4(e.getValue()));
                }
            }

            long elapsed = System.currentTimeMillis() - t0;

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("posteriors", posteriors);
            out.put("priors", priors);
            out.put("variableToTitle", variableToTitle);
            out.put("computationTimeMs", elapsed);
            out.put("nodeCount", sub.entityCount());
            out.put("edgeCount", sub.relationCount());
            return MiniJson.write(out);
        } catch (Exception e) {
            return error("ask_graph_mebn failed: " + e.getMessage());
        }
    }

    // ── Handler: graph_bayes ─────────────────────────────────────────────────

    private static String handleBayes(LocalReasoningSession session, Map<String, Object> args) {
        try {
            String action = str(args, "action");
            if (action == null || action.isBlank()) {
                return error("graph_bayes requires 'action' (query|mpe|whatif|stats)");
            }
            action = action.trim().toLowerCase(Locale.ROOT);

            return switch (action) {
                case "query"  -> handleBayesQuery(session, args);
                case "mpe"    -> handleBayesMpe(session, args);
                case "whatif" -> handleBayesWhatif(session, args);
                case "stats"  -> handleBayesStats(session, args);
                default -> error("Unknown graph_bayes action '" + action +
                                 "'. Use: query, mpe, whatif, stats");
            };
        } catch (Exception e) {
            return error("graph_bayes failed: " + e.getMessage());
        }
    }

    private static String handleBayesQuery(LocalReasoningSession session, Map<String, Object> args) {
        UnifiedGraph sub = extractBayesSubgraph(session, args);
        GraphBayesianNetworkBuilder builder = new GraphBayesianNetworkBuilder();
        BayesianNetwork network = builder.build(sub);
        Map<String, String> entityIdToVar = builder.entityIdToVariable();
        Map<String, String> varToEntityId = builder.variableToEntityId();

        Map<String, Integer> evidence = parseEvidenceMap(args, "evidence", entityIdToVar);
        Map<String, Double> varPosteriors = VariableElimination.queryAll(network, evidence);

        Map<String, Object> posteriors = new LinkedHashMap<>();
        for (Map.Entry<String, Double> e : varPosteriors.entrySet()) {
            String entityId = varToEntityId.get(e.getKey());
            if (entityId != null) posteriors.put(entityId, round4(e.getValue()));
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("action", "query");
        out.put("posteriors", posteriors);
        out.put("evidenceApplied", evidence.size());
        return MiniJson.write(out);
    }

    private static String handleBayesMpe(LocalReasoningSession session, Map<String, Object> args) {
        UnifiedGraph sub = extractBayesSubgraph(session, args);
        GraphBayesianNetworkBuilder builder = new GraphBayesianNetworkBuilder();
        BayesianNetwork network = builder.build(sub);
        Map<String, String> entityIdToVar = builder.entityIdToVariable();
        Map<String, String> varToEntityId = builder.variableToEntityId();

        Map<String, Integer> evidence = parseEvidenceMap(args, "evidence", entityIdToVar);
        VariableElimination.JointMpeResult mpe =
                VariableElimination.jointMostProbableExplanation(network, evidence);

        Map<String, Object> assignment = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : mpe.assignment().entrySet()) {
            String entityId = varToEntityId.get(e.getKey());
            String key = entityId != null ? entityId : e.getKey();
            assignment.put(key, e.getValue() == 1 ? "TRUE" : "FALSE");
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("action", "mpe");
        out.put("assignment", assignment);
        out.put("logScore", Double.isNaN(mpe.logScore()) ? "NaN" : round4(mpe.logScore()));
        out.put("probabilityGivenEvidence",
                Double.isNaN(mpe.probabilityGivenEvidence()) ? "NaN"
                        : round4(mpe.probabilityGivenEvidence()));
        return MiniJson.write(out);
    }

    private static String handleBayesWhatif(LocalReasoningSession session, Map<String, Object> args) {
        UnifiedGraph sub = extractBayesSubgraph(session, args);
        GraphBayesianNetworkBuilder builder = new GraphBayesianNetworkBuilder();
        BayesianNetwork network = builder.build(sub);
        Map<String, String> entityIdToVar = builder.entityIdToVariable();
        Map<String, String> varToEntityId = builder.variableToEntityId();

        // Baseline: no evidence
        Map<String, Double> baseline = VariableElimination.queryAll(network, Map.of());

        // Hypothetical evidence
        Map<String, Integer> hypEvidence = parseEvidenceMap(args, "hypothetical_evidence", entityIdToVar);
        if (hypEvidence.isEmpty()) {
            // Fall back to 'evidence' key
            hypEvidence = parseEvidenceMap(args, "evidence", entityIdToVar);
        }

        Map<String, Double> hypothetical = VariableElimination.queryAll(network, hypEvidence);

        Map<String, Object> deltas = new LinkedHashMap<>();
        for (Map.Entry<String, Double> e : hypothetical.entrySet()) {
            String entityId = varToEntityId.get(e.getKey());
            String key = entityId != null ? entityId : e.getKey();
            double baseVal = baseline.getOrDefault(e.getKey(), Double.NaN);
            double delta = Double.isNaN(baseVal) ? Double.NaN : (e.getValue() - baseVal);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("baseline", Double.isNaN(baseVal) ? "NaN" : round4(baseVal));
            item.put("hypothetical", round4(e.getValue()));
            item.put("delta", Double.isNaN(delta) ? "NaN" : round4(delta));
            deltas.put(key, item);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("action", "whatif");
        out.put("deltas", deltas);
        out.put("hypotheticalEvidenceApplied", hypEvidence.size());
        return MiniJson.write(out);
    }

    private static String handleBayesStats(LocalReasoningSession session, Map<String, Object> args) {
        UnifiedGraph full = session.graph();
        String seedNodeId = str(args, "node_id");
        List<String> seedIds = parseStringList(args, "seed_node_ids");

        // Determine seeds for neighborhood extraction
        List<String> seeds = new ArrayList<>();
        if (seedNodeId != null && !seedNodeId.isBlank()) seeds.add(seedNodeId.trim());
        for (String s : seedIds) {
            if (!seeds.contains(s)) seeds.add(s);
        }

        int maxDepth = intVal(args, "max_depth", 3);
        int maxNodes = intVal(args, "max_nodes", 100);

        UnifiedGraph sub;
        if (seeds.isEmpty()) {
            sub = full;
        } else {
            sub = capGraph(full.neighborhood(seeds, maxDepth), maxNodes);
        }

        GraphBayesianNetworkBuilder builder = new GraphBayesianNetworkBuilder();
        BayesianNetwork network = builder.build(sub);

        Map<String, Object> stats = network.getStatistics();
        boolean maxDepthReached = !seeds.isEmpty() && sub.entityCount() >= maxNodes;

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("action", "stats");
        out.put("nodes",  stats.getOrDefault("nodeCount", 0));
        out.put("edges",  stats.getOrDefault("edgeCount", 0));
        out.put("maxDepthReached", maxDepthReached);
        out.put("rootNodes", stats.getOrDefault("rootNodes", 0));
        out.put("leafNodes", stats.getOrDefault("leafNodes", 0));
        return MiniJson.write(out);
    }

    // ── Handler: ask_graph_claim ─────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private static String handleClaim(LocalReasoningSession session, Map<String, Object> args) {
        try {
            String subject   = str(args, "subject");
            String predicate = str(args, "predicate");
            String object    = str(args, "object");

            if (subject == null || subject.isBlank()) return error("ask_graph_claim requires 'subject'");
            if (predicate == null || predicate.isBlank()) return error("ask_graph_claim requires 'predicate'");
            if (object == null || object.isBlank()) return error("ask_graph_claim requires 'object'");

            subject   = subject.trim();
            predicate = predicate.trim();
            object    = object.trim();

            UnifiedGraph graph = session.graph();
            LocalKbState kb    = session.kbState();

            // Build a KGE triple scorer from the graph's embedding layers if available.
            // Layer selection mirrors AnalyticsHandlers.pickDefaultEntityLayer():
            //   prefer the first ENTITY-target non-empty layer, fall back to any non-empty layer.
            // A scorer is only constructed when BOTH subject and object have vectors in the layer.
            // Without vectors the scorer stays null and DossierBuilder skips the KGE channel.
            KgeTripleScorer kgeScorer = buildEmbeddingScorer(graph, subject, object);

            DossierBuilder dossierBuilder = new DossierBuilder();
            ClaimDossier dossier = dossierBuilder.assess(
                    graph, subject, predicate, object,
                    kb.factStore(), kb.inferredFactStore(),
                    null,       // no mined rule set locally
                    kgeScorer); // null if graph has no usable vectors for this triple

            // Determine verdict from fusedScore
            double score = dossier.fusedScore();
            String verdict;
            if (score >= 0.65) {
                verdict = "LIKELY_TRUE";
            } else if (score <= 0.35) {
                verdict = "LIKELY_FALSE";
            } else {
                verdict = "UNCERTAIN";
            }

            // Build supporting / refuting lists
            List<Object> supporting = serializeDossierItems(dossier.supporting());
            List<Object> refuting   = serializeDossierItems(dossier.refuting());

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("verdict",   verdict);
            out.put("fusedScore", round4(score));
            out.put("claimAtom", dossier.claimAtom());
            out.put("supporting", supporting);
            out.put("refuting",   refuting);
            return MiniJson.write(out);
        } catch (Exception e) {
            return error("ask_graph_claim failed: " + e.getMessage());
        }
    }

    // ── Handler: ask_graph_synthesize ────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private static String handleSynthesize(LocalReasoningSession session, Map<String, Object> args) {
        try {
            String query = str(args, "query");
            if (query == null || query.isBlank()) return error("ask_graph_synthesize requires 'query'");

            String expectedType = blankToNull(str(args, "expectedType"));
            int maxCandidates   = intVal(args, "maxCandidates", 20);

            UnifiedGraph graph = session.graph();
            Collection<GraphEntity> allEntities = graph.entities();

            List<AnswerSynthesizer.Candidate> candidates = new ArrayList<>();
            int count = 0;
            for (GraphEntity entity : allEntities) {
                if (count >= maxCandidates) break;

                // Type filter
                if (expectedType != null && !entity.hasTypeMembership(expectedType)) {
                    continue;
                }

                List<AnswerSynthesizer.CandidateSignal> signals = new ArrayList<>();

                // RETRIEVAL signal: entity weight as reliability of retrieval
                double retrieval = Math.max(0.0, Math.min(1.0, entity.weight()));
                signals.add(AnswerSynthesizer.CandidateSignal.of(
                        AnswerSynthesizer.SignalGroup.RETRIEVAL,
                        "entity_weight",
                        Opinion.fromObservedValue(retrieval)));

                // TYPE signal: 1.0 if type matches expected (or no filter), else 0.3
                if (expectedType != null) {
                    boolean typeMatch = entity.hasTypeMembership(expectedType);
                    signals.add(AnswerSynthesizer.CandidateSignal.of(
                            AnswerSynthesizer.SignalGroup.TYPE,
                            "type_match",
                            typeMatch ? Opinion.fromObserved() : Opinion.vacuous(0.3)));
                }

                // CONSISTENCY: check if label/id contains query terms (lightweight text match)
                String lq = query.toLowerCase(Locale.ROOT);
                String ll = (entity.label() + " " + entity.id()).toLowerCase(Locale.ROOT);
                boolean textMatch = ll.contains(lq) || lq.contains(entity.id().toLowerCase(Locale.ROOT));
                if (textMatch) {
                    signals.add(AnswerSynthesizer.CandidateSignal.of(
                            AnswerSynthesizer.SignalGroup.CONSISTENCY,
                            "text_match",
                            Opinion.fromObserved()));
                }

                candidates.add(new AnswerSynthesizer.Candidate(entity.id(), signals));
                count++;
            }

            List<AnswerSynthesizer.SynthesizedAnswer> answers =
                    AnswerSynthesizer.synthesize(candidates);

            List<Object> answerList = new ArrayList<>();
            for (AnswerSynthesizer.SynthesizedAnswer a : answers) {
                // Look up entity label
                String label = graph.entity(a.entityId())
                        .map(e -> e.label().isEmpty() ? e.id() : e.label())
                        .orElse(a.entityId());
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("entityId",    a.entityId());
                item.put("answer",      label);
                item.put("likelihood",  round4(a.likelihood()));
                item.put("belief",      round4(a.opinion().belief()));
                item.put("uncertainty", round4(a.opinion().uncertainty()));
                answerList.add(item);
            }

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("answers",     answerList);
            out.put("answerCount", answerList.size());
            return MiniJson.write(out);
        } catch (Exception e) {
            return error("ask_graph_synthesize failed: " + e.getMessage());
        }
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    /**
     * Extract a neighborhood subgraph for Bayesian tools, respecting seed node ids and depth/size
     * caps from args. If no seed is specified the full session graph is used.
     */
    private static UnifiedGraph extractBayesSubgraph(LocalReasoningSession session,
                                                      Map<String, Object> args) {
        UnifiedGraph full = session.graph();
        String seedNodeId  = str(args, "node_id");
        List<String> seeds = parseStringList(args, "seed_node_ids");

        List<String> allSeeds = new ArrayList<>();
        if (seedNodeId != null && !seedNodeId.isBlank()) allSeeds.add(seedNodeId.trim());
        for (String s : seeds) {
            if (!allSeeds.contains(s)) allSeeds.add(s);
        }

        int maxDepth = intVal(args, "max_depth", 3);
        int maxNodes = intVal(args, "max_nodes", 100);

        if (allSeeds.isEmpty()) {
            return capGraph(full, maxNodes);
        }
        return capGraph(full.neighborhood(allSeeds, maxDepth), maxNodes);
    }

    /**
     * Cap a graph to {@code maxNodes} by taking the first N entities and their induced relations.
     * Returns the original graph when maxNodes is 0 or it already fits.
     */
    private static UnifiedGraph capGraph(UnifiedGraph g, int maxNodes) {
        if (maxNodes <= 0 || g.entityCount() <= maxNodes) return g;
        List<String> keepIds = new ArrayList<>(maxNodes);
        for (GraphEntity e : g.entities()) {
            if (keepIds.size() >= maxNodes) break;
            keepIds.add(e.id());
        }
        return g.inducedSubgraph(keepIds);
    }

    /**
     * Parse an evidence map: input is a JSON object {entityId: 0|1} (0=FALSE, 1=TRUE).
     * Translates entity ids to Bayesian variable names via {@code entityIdToVar}.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Integer> parseEvidenceMap(Map<String, Object> args, String key,
                                                          Map<String, String> entityIdToVar) {
        Object v = args.get(key);
        if (!(v instanceof Map<?, ?> m)) return Map.of();
        Map<String, Integer> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : m.entrySet()) {
            String entityId = e.getKey().toString();
            String varName  = entityIdToVar.get(entityId);
            if (varName == null) continue; // entity not in subgraph
            int stateIdx;
            if (e.getValue() instanceof Number n) {
                stateIdx = n.intValue();
            } else {
                String sv = e.getValue().toString().trim();
                stateIdx  = ("1".equals(sv) || "true".equalsIgnoreCase(sv)) ? 1 : 0;
            }
            result.put(varName, stateIdx);
        }
        return Collections.unmodifiableMap(result);
    }

    private static List<Object> serializeDossierItems(List<DossierItem> items) {
        List<Object> list = new ArrayList<>();
        for (DossierItem item : items) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("signal",      item.kind().name());
            m.put("description", item.description());
            m.put("probability", round4(item.probability()));
            list.add(m);
        }
        return list;
    }

    // ── JSON schema helpers (mirrors CoreHandlers pattern) ───────────────────

    private static LocalToolCatalog.Entry schemaFor(String name, String description,
                                                      List<String> required,
                                                      Map<String, Object> properties) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        if (!required.isEmpty()) schema.put("required", required);
        return new LocalToolCatalog.Entry(name, description, schema);
    }

    private static Map<String, Object> stringProp(String desc) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("type", "string");
        p.put("description", desc);
        return p;
    }

    private static Map<String, Object> intProp(String desc) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("type", "integer");
        p.put("description", desc);
        return p;
    }

    private static Map<String, Object> objectProp(String desc) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("type", "object");
        p.put("description", desc);
        return p;
    }

    private static Map<String, Object> enumProp(String desc, List<String> values) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("type", "string");
        p.put("description", desc);
        p.put("enum", values);
        return p;
    }

    private static Map<String, Object> buildMebnSchema() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("nodeId",    stringProp("Entity id to use as the root for BFS subgraph extraction"));
        props.put("maxDepth",  intProp("BFS radius (default 3)"));
        props.put("maxNodes",  intProp("Maximum subgraph nodes (default 50)"));
        props.put("factSheetId", stringProp("Accepted and ignored (local session is single-graph)"));
        return props;
    }

    private static Map<String, Object> buildBayesSchema() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("action", enumProp("Inference action", List.of("query", "mpe", "whatif", "stats")));
        props.put("node_id",      stringProp("Single seed entity id for neighborhood subgraph extraction"));
        props.put("seed_node_ids", Map.of(
                "type", "array",
                "description", "Multiple seed entity ids for subgraph extraction",
                "items", Map.of("type", "string")));
        props.put("evidence",      objectProp("Observed evidence map {entityId: 0|1} (0=FALSE, 1=TRUE)"));
        props.put("hypothetical_evidence", objectProp("Hypothetical evidence for whatif comparison"));
        props.put("max_depth",     intProp("BFS depth for neighborhood subgraph (default 3)"));
        props.put("max_nodes",     intProp("Max subgraph nodes (default 100)"));
        props.put("top_k",         intProp("Unused placeholder for API parity"));
        props.put("factSheetId",   stringProp("Accepted and ignored"));
        return props;
    }

    private static Map<String, Object> buildClaimSchema() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("subject",   stringProp("Subject entity id"));
        props.put("predicate", stringProp("Relation type / predicate name (e.g. WORKS_AT)"));
        props.put("object",    stringProp("Object entity id"));
        props.put("factSheetId", stringProp("Accepted and ignored"));
        return props;
    }

    private static Map<String, Object> buildSynthesizeSchema() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("query",         stringProp("Free-text or entity id query"));
        props.put("expectedType",  stringProp("Filter candidates to this entity type (optional)"));
        props.put("maxCandidates", intProp("Maximum candidate entities to consider (default 20)"));
        props.put("factSheetId",   stringProp("Accepted and ignored"));
        return props;
    }

    // ── Primitive helpers ────────────────────────────────────────────────────

    private static String str(Map<String, Object> args, String key) {
        Object v = args.get(key);
        return v == null ? null : v.toString();
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    private static int intVal(Map<String, Object> args, String key, int defaultValue) {
        Object v = args.get(key);
        if (v == null) return defaultValue;
        if (v instanceof Number n) return n.intValue();
        try { return Integer.parseInt(v.toString().trim()); }
        catch (NumberFormatException e) { return defaultValue; }
    }

    @SuppressWarnings("unchecked")
    private static List<String> parseStringList(Map<String, Object> args, String key) {
        Object v = args.get(key);
        if (v == null) return List.of();
        if (v instanceof List<?> list) {
            List<String> result = new ArrayList<>();
            for (Object item : list) {
                if (item != null) result.add(item.toString());
            }
            return Collections.unmodifiableList(result);
        }
        if (v instanceof String s && !s.isBlank()) return List.of(s.trim());
        return List.of();
    }

    private static double round4(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }

    private static String error(String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", "ERROR");
        m.put("message", Objects.requireNonNullElse(message, "Unknown error"));
        return MiniJson.write(m);
    }

    // ── KGE scorer construction ──────────────────────────────────────────────

    /**
     * Build a {@link KgeTripleScorer} from the graph's vector layers for use in
     * {@code ask_graph_claim}. Layer selection mirrors {@code AnalyticsHandlers}:
     * prefer the first non-empty ENTITY-target layer; fall back to any non-empty layer.
     *
     * <p>The scorer uses cosine similarity between entity embedding vectors as the
     * plausibility signal: {@code score(h, r, t) = (cosine(vec_h, vec_t) + 1) / 2}
     * (rescaled from [−1, 1] to [0, 1]). This is relation-type-agnostic — adequate for
     * surface-level plausibility when no relation embeddings are available. If a
     * {@link VectorLayer.Target#GLOBAL} layer exists with the same name + "_rel" suffix it
     * is treated as a relation embedding layer (unused in this version — extension point).
     *
     * <p>Returns {@code null} when no usable vector layer exists or when EITHER the subject
     * or the object is absent from the chosen layer. A {@code null} scorer is safe to pass
     * to {@link ai.kompile.graph.reasoning.claims.DossierBuilder#assess} (KGE channel is
     * skipped).</p>
     */
    static KgeTripleScorer buildEmbeddingScorer(UnifiedGraph graph,
                                                String subjectId,
                                                String objectId) {
        // Pick the default entity layer (same logic as AnalyticsHandlers.pickDefaultEntityLayer)
        String layerName = pickDefaultEntityLayer(graph);
        if (layerName == null) return null;

        EmbeddingTable table = graph.embeddingTable(layerName);
        if (table == null) return null;

        double[] subVec = table.vector(subjectId);
        double[] objVec = table.vector(objectId);
        if (subVec == null || objVec == null) return null;
        if (subVec.length == 0 || objVec.length == 0) return null;

        // Freeze the two vectors captured for scoring
        final double[] sv = subVec;
        final double[] ov = objVec;
        final EmbeddingTable capturedTable = table;

        return new KgeTripleScorer() {
            @Override
            public double scoreTriple(String headId, String relationType, String tailId) {
                double[] hv = capturedTable.vector(headId);
                double[] tv = capturedTable.vector(tailId);
                if (hv == null || tv == null || hv.length == 0 || tv.length != hv.length) {
                    return 0.0;
                }
                double cosine = cosine(hv, tv);
                // Rescale cosine from [−1, 1] to [0, 1]
                return (cosine + 1.0) / 2.0;
            }

            @Override
            public boolean knows(String headId, String relationType, String tailId) {
                return capturedTable.vector(headId) != null
                        && capturedTable.vector(tailId) != null;
            }
        };
    }

    /** Pick the first non-empty ENTITY-target layer; fall back to any non-empty layer. */
    private static String pickDefaultEntityLayer(UnifiedGraph graph) {
        Map<String, VectorLayer> layers = graph.vectorLayers();
        if (layers.isEmpty()) return null;
        for (Map.Entry<String, VectorLayer> e : layers.entrySet()) {
            if (e.getValue().target() == VectorLayer.Target.ENTITY && !e.getValue().isEmpty()) {
                return e.getKey();
            }
        }
        for (Map.Entry<String, VectorLayer> e : layers.entrySet()) {
            if (!e.getValue().isEmpty()) return e.getKey();
        }
        return null;
    }

    /** Cosine similarity between two vectors; returns 0 if either is zero-magnitude. */
    private static double cosine(double[] a, double[] b) {
        if (a.length != b.length) return 0.0;
        double dot = 0.0, na = 0.0, nb = 0.0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na  += a[i] * a[i];
            nb  += b[i] * b[i];
        }
        double denom = Math.sqrt(na) * Math.sqrt(nb);
        return denom < 1e-12 ? 0.0 : dot / denom;
    }
}
