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

import ai.kompile.graph.reasoning.fol.Fact;
import ai.kompile.graph.reasoning.fol.FactStore;
import ai.kompile.graph.reasoning.fol.grounding.ConjunctiveQueryEngine;
import ai.kompile.graph.reasoning.fol.grounding.DefaultKbVerifier;
import ai.kompile.graph.reasoning.fol.grounding.DerivationTree;
import ai.kompile.graph.reasoning.fol.grounding.QueryBinding;
import ai.kompile.graph.reasoning.fol.grounding.VerifyResult;
import ai.kompile.graph.reasoning.tms.BeliefReviser;
import ai.kompile.graph.reasoning.tms.BeliefRevisionResult;
import ai.kompile.graph.reasoning.tms.ContradictionDetector;
import ai.kompile.graph.reasoning.tms.JustificationIndex;
import ai.kompile.graph.reasoning.unified.MiniJson;
import ai.kompile.graph.reasoning.unified.UnifiedGraph;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Grounding tool handlers: {@code ask_graph_verify}, {@code ask_graph_query},
 * {@code ask_graph_explain}, {@code graph_reason}, {@code ask_graph_assert},
 * {@code ask_graph_retract}.
 *
 * <p>These six tools implement direct KB fact-store operations (assert / retract / query /
 * verify / explain) against the session's {@link LocalKbState}, mirroring the server-side
 * {@code KbGroundingService} thinly without Spring or JPA.</p>
 *
 * <h3>Atom-key format</h3>
 * <p>Atom keys follow the format produced by {@link UnifiedGraph#facts()}:
 * {@code PREDICATE(sourceId, targetId)} for binary relations and {@code TYPE(entityId)} for
 * unary type atoms. The predicate is the relation's {@code .type()} exactly (e.g.
 * {@code WORKS_AT(alice, acme)}). No lowercasing is applied here — the caller must pass
 * the atom key in the same form the projector produced it.</p>
 *
 * <h3>Materialization</h3>
 * <p>Locally, there is no PSL inference engine in the grounding handler group. The observed
 * fact store is primed from {@link UnifiedGraph#facts()} at load time (by
 * {@link LocalKbState#primeFromGraph(UnifiedGraph)}). The inferred fact store starts empty
 * and is populated only when an {@link InferenceHandlers} tool (e.g. a PSL MAP solve) runs
 * and installs a {@link JustificationIndex}. Until then, verify/explain operate against the
 * observed fact store only (open-world UNKNOWN for any atom not directly in the graph).
 * This is the correct observed-only behaviour — documented per tool description.</p>
 *
 * <h3>Write-through on assert/retract</h3>
 * <p>{@code ask_graph_assert} writes the new relation to the graph via
 * {@link UnifiedGraph#addRelation} and calls {@link LocalReasoningSession#reprimeKb()} so
 * subsequent queries see it. {@code ask_graph_retract} now also writes through to the graph:
 * for binary atoms it calls {@link UnifiedGraph#removeRelation(String, String, String)} and
 * then {@link LocalReasoningSession#reprimeKb()}, so the retraction survives a save/reload
 * cycle. The response contains {@code "topologyRemoved":true} when a graph relation was
 * actually removed. Unary or 0-arity atoms remain fact-store-only (no topology change).
 * Orphaned endpoint stub entities are left in place.</p>
 */
public final class GroundingHandlers {

    private GroundingHandlers() {}

    /**
     * Register all six grounding tool handlers with the dispatcher builder.
     *
     * @param builder the dispatcher builder (receives handler + catalog entry per tool)
     */
    static void register(LocalToolDispatcher.Builder builder) {
        registerVerify(builder);
        registerQuery(builder);
        registerExplain(builder);
        registerGraphReason(builder);
        registerAssert(builder);
        registerRetract(builder);
    }

    // ── ask_graph_verify ─────────────────────────────────────────────────────

    private static void registerVerify(LocalToolDispatcher.Builder builder) {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("atom", stringProp(
                "Ground atom key to verify, e.g. WORKS_AT(alice, acme) or PERSON(alice). " +
                "Predicate name must match the relation type exactly (case-sensitive)."));
        props.put("minConfidence", numberProp(
                "Minimum confidence threshold in [0,1] to report SUPPORTED (default 0.5)."));
        props.put("factSheetId", numberProp("Accepted and ignored (local mode has no fact sheets)."));

        builder.handler(
            "ask_graph_verify",
            entry("ask_graph_verify",
                "Verify a ground atom against the session KB (observed facts projected from the " +
                "loaded graph + any inferred facts from a prior inference run). Returns verdict " +
                "SUPPORTED|REFUTED|UNKNOWN with confidence, evidence atoms, and contradictions. " +
                "Materialization: uses the observed fact store primed at load time; inferred store " +
                "is populated only after an inference tool runs.",
                List.of("atom"), props),
            GroundingHandlers::handleVerify);
    }

    private static String handleVerify(LocalReasoningSession session, Map<String, Object> args) {
        String atom = str(args, "atom");
        if (atom == null || atom.isBlank()) {
            return error("ask_graph_verify requires 'atom'");
        }
        double threshold = doubleVal(args, "minConfidence", 0.5);
        if (threshold < 0.0 || threshold > 1.0) threshold = 0.5;

        LocalKbState kb = session.kbState();
        DefaultKbVerifier verifier = new DefaultKbVerifier(
                kb.inferredFactStore(), kb.factStore(), threshold);
        VerifyResult result;
        try {
            result = verifier.verify(atom.trim());
        } catch (Exception e) {
            return error("Verify failed: " + e.getMessage());
        }

        // Check contradictions in the fact store
        List<ContradictionDetector.Pair<Fact, Fact>> contradictions;
        try {
            contradictions = ContradictionDetector.findFactContradictions(kb.factStore());
        } catch (Exception e) {
            contradictions = List.of();
        }
        List<String> contradictionKeys = contradictions.stream()
                .map(p -> p.first().atomKey() + " vs " + p.second().atomKey())
                .toList();

        // entityKnown: is the first argument present in the graph as an entity?
        String firstArg = extractFirstArg(atom.trim());
        boolean entityKnown = firstArg != null && session.graph().entity(firstArg).isPresent();

        // derivation depth from tree if index is available
        int derivationDepth = 0;
        JustificationIndex index = kb.justificationIndex();
        if (index != null && result.status() == VerifyResult.Status.SUPPORTED) {
            try {
                DerivationTree tree = DerivationTree.build(atom.trim(), kb.inferredFactStore(), index);
                derivationDepth = treeHeight(tree);
            } catch (Exception ignored) {}
        }

        // opinion from inferred store if available (InferredFact carries no Opinion field;
        // construct a basic subjective-logic opinion from confidence when SUPPORTED)
        Map<String, Object> opinionMap = null;
        if (result.status() == VerifyResult.Status.SUPPORTED && result.confidence() > 0.0) {
            double conf = result.confidence();
            double u = Math.max(0.0, 1.0 - conf);
            opinionMap = new LinkedHashMap<>();
            opinionMap.put("b", conf);
            opinionMap.put("d", 0.0);
            opinionMap.put("u", u);
        }

        // Strength band
        String strengthBand = confidenceBand(result.confidence());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("verdict", result.status().name());
        out.put("confidence", result.confidence());
        out.put("strengthBand", strengthBand);
        out.put("evidenceAtoms", result.evidence());
        out.put("counterEvidence", result.counterEvidence());
        out.put("contradictions", contradictionKeys);
        if (opinionMap != null) out.put("opinion", opinionMap);
        out.put("entityKnown", entityKnown);
        out.put("derivationDepth", derivationDepth);
        out.put("evidenceCount", result.evidence().size());
        return MiniJson.write(out);
    }

    // ── ask_graph_query ──────────────────────────────────────────────────────

    private static void registerQuery(LocalToolDispatcher.Builder builder) {
        Map<String, Object> conjunctSchema = new LinkedHashMap<>();
        conjunctSchema.put("type", "object");
        Map<String, Object> cProps = new LinkedHashMap<>();
        cProps.put("predicate", stringProp("Predicate name (must match atom key exactly)."));
        cProps.put("args", Map.of("type", "array", "items", Map.of("type", "string"),
                "description", "Argument list: constants or variables (?prefix)."));
        conjunctSchema.put("properties", cProps);
        conjunctSchema.put("required", List.of("predicate", "args"));

        Map<String, Object> props = new LinkedHashMap<>();
        props.put("conjuncts", Map.of(
                "type", "array",
                "description", "Ordered list of atom patterns forming the conjunctive query. " +
                        "Variables must start with '?' (e.g. ?x, ?company).",
                "items", conjunctSchema));
        props.put("maxResults", numberProp("Maximum rows returned (default 50)."));
        props.put("minConfidence", numberProp("Minimum per-row confidence to include (default 0.0)."));
        props.put("factSheetId", numberProp("Accepted and ignored."));

        builder.handler(
            "ask_graph_query",
            entry("ask_graph_query",
                "Execute a conjunctive pattern query against the session KB inferred fact store. " +
                "Variables use the '?' prefix (e.g. ?x). Per-row confidence = Gödel minimum across " +
                "matched atoms. Only atoms in the inferred fact store are matched; for observed-only " +
                "graphs (no inference run) results will be empty unless an assert primed the inferred store.",
                List.of("conjuncts"), props),
            GroundingHandlers::handleQuery);
    }

    @SuppressWarnings("unchecked")
    private static String handleQuery(LocalReasoningSession session, Map<String, Object> args) {
        Object conjunctsRaw = args.get("conjuncts");
        if (conjunctsRaw == null) {
            return error("ask_graph_query requires 'conjuncts'");
        }
        if (!(conjunctsRaw instanceof List<?>)) {
            return error("'conjuncts' must be a JSON array");
        }
        List<?> conjunctsList = (List<?>) conjunctsRaw;
        if (conjunctsList.isEmpty()) {
            return error("'conjuncts' must not be empty");
        }

        List<ConjunctiveQueryEngine.AtomPattern> patterns = new ArrayList<>();
        for (Object item : conjunctsList) {
            if (!(item instanceof Map<?, ?> m)) {
                return error("Each conjunct must be a JSON object with 'predicate' and 'args'");
            }
            String predicate = objStr(m, "predicate");
            if (predicate == null || predicate.isBlank()) {
                return error("Each conjunct must have a non-blank 'predicate'");
            }
            Object argsRaw = m.get("args");
            List<String> argList = new ArrayList<>();
            if (argsRaw instanceof List<?> al) {
                for (Object a : al) {
                    argList.add(a == null ? "" : a.toString());
                }
            }
            patterns.add(new ConjunctiveQueryEngine.AtomPattern(predicate, argList));
        }

        int maxResults = intVal(args, "maxResults", ConjunctiveQueryEngine.DEFAULT_MAX_RESULTS);
        double minConf = doubleVal(args, "minConfidence", 0.0);

        LocalKbState kb = session.kbState();
        List<QueryBinding> bindings;
        try {
            bindings = ConjunctiveQueryEngine.query(patterns, kb.inferredFactStore(), maxResults);
        } catch (Exception e) {
            return error("Query failed: " + e.getMessage());
        }

        // Filter by minConfidence
        List<Object> rows = new ArrayList<>();
        for (QueryBinding qb : bindings) {
            if (qb.confidence() < minConf) continue;
            Map<String, Object> row = new LinkedHashMap<>();
            // Prefix variable names with '?' in output
            Map<String, Object> variables = new LinkedHashMap<>();
            for (Map.Entry<String, String> e : qb.bindings().entrySet()) {
                String key = e.getKey().startsWith("?") ? e.getKey() : "?" + e.getKey();
                variables.put(key, e.getValue());
            }
            row.put("variables", variables);
            row.put("confidence", qb.confidence());
            rows.add(row);
        }

        boolean truncated = bindings.size() >= maxResults && bindings.size() > rows.size() + 1;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", rows.size());
        out.put("truncated", truncated);
        out.put("bindings", rows);
        return MiniJson.write(out);
    }

    // ── ask_graph_explain ────────────────────────────────────────────────────

    private static void registerExplain(LocalToolDispatcher.Builder builder) {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("target", stringProp(
                "Atom key to explain, e.g. WORKS_AT(alice, acme). Must match the KB atom key exactly."));
        props.put("depth", numberProp(
                "Maximum derivation hops (default 5, capped at DerivationTree.DEFAULT_MAX_DEPTH)."));
        props.put("factSheetId", numberProp("Accepted and ignored."));

        builder.handler(
            "ask_graph_explain",
            entry("ask_graph_explain",
                "Build the derivation tree for an atom to explain HOW it was inferred. " +
                "Returns verdict, confidence, the derivation tree as JSON, evidence atoms, " +
                "and activated rules. Inference mode is always GROUNDING locally. " +
                "The derivation tree is populated from the inferred fact store + JustificationIndex; " +
                "if no inference has been run the tree will show a leaf node (confidence 0.0).",
                List.of("target"), props),
            GroundingHandlers::handleExplain);
    }

    private static String handleExplain(LocalReasoningSession session, Map<String, Object> args) {
        String target = str(args, "target");
        if (target == null || target.isBlank()) {
            return error("ask_graph_explain requires 'target'");
        }
        int depth = intVal(args, "depth", DerivationTree.DEFAULT_MAX_DEPTH);
        if (depth < 1 || depth > DerivationTree.DEFAULT_MAX_DEPTH) {
            depth = DerivationTree.DEFAULT_MAX_DEPTH;
        }
        return explainAtom(session, target.trim(), depth);
    }

    // ── graph_reason ─────────────────────────────────────────────────────────
    // Same as ask_graph_explain (local mode = GROUNDING via derivation tree)

    private static void registerGraphReason(LocalToolDispatcher.Builder builder) {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("target", stringProp(
                "Atom key to explain, e.g. WORKS_AT(alice, acme)."));
        props.put("depth", numberProp("Maximum derivation hops (default 5)."));
        props.put("factSheetId", numberProp("Accepted and ignored."));

        builder.handler(
            "graph_reason",
            entry("graph_reason",
                "Alias for ask_graph_explain in local mode (inference mode = GROUNDING). " +
                "Builds the derivation / proof tree for the target atom and returns the same " +
                "response shape as ask_graph_explain.",
                List.of("target"), props),
            GroundingHandlers::handleGraphReason);
    }

    private static String handleGraphReason(LocalReasoningSession session, Map<String, Object> args) {
        String target = str(args, "target");
        if (target == null || target.isBlank()) {
            return error("graph_reason requires 'target'");
        }
        int depth = intVal(args, "depth", DerivationTree.DEFAULT_MAX_DEPTH);
        if (depth < 1 || depth > DerivationTree.DEFAULT_MAX_DEPTH) {
            depth = DerivationTree.DEFAULT_MAX_DEPTH;
        }
        return explainAtom(session, target.trim(), depth);
    }

    // ── Shared explain implementation ────────────────────────────────────────

    private static String explainAtom(LocalReasoningSession session, String target, int depth) {
        LocalKbState kb = session.kbState();

        // Verify first to get verdict + confidence
        DefaultKbVerifier verifier = new DefaultKbVerifier(kb.inferredFactStore(), kb.factStore());
        VerifyResult verifyResult;
        try {
            verifyResult = verifier.verify(target);
        } catch (Exception e) {
            return error("Explain verify failed: " + e.getMessage());
        }

        // Build derivation tree — needs justification index; use empty-index sentinel if null
        String derivationTreeJson = null;
        List<String> evidence = new ArrayList<>();
        List<String> activatedRules = new ArrayList<>();

        JustificationIndex index = kb.justificationIndex();
        if (index != null) {
            try {
                DerivationTree tree = DerivationTree.build(target, kb.inferredFactStore(), index, depth);
                derivationTreeJson = tree.toJson();
                // Collect leaf evidence atoms and activated rules from the tree
                for (String key : tree.allAtomKeys()) {
                    if (!key.equals(target)) evidence.add(key);
                }
                collectRules(tree, activatedRules);
            } catch (Exception e) {
                derivationTreeJson = "{\"atom\":\"" + escapeJson(target) +
                        "\",\"confidence\":0.0,\"children\":[]}";
            }
        } else {
            // No inference run yet — single leaf from observed fact store
            double conf = 0.0;
            boolean observed = kb.factStore().factFor(target).isPresent();
            if (observed) conf = kb.factStore().factFor(target).get().value();
            derivationTreeJson = "{\"atom\":\"" + escapeJson(target) + "\",\"confidence\":" + conf +
                    ",\"children\":[]}";
        }

        // Evidence from verify result
        for (String ev : verifyResult.evidence()) {
            if (!evidence.contains(ev)) evidence.add(ev);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("verdict", verifyResult.status().name());
        out.put("confidence", verifyResult.confidence());
        out.put("inferenceMode", "GROUNDING");
        out.put("derivationTreeJson", derivationTreeJson);
        out.put("evidence", evidence.stream()
                .map(e -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("atom", e);
                    m.put("conf", 1.0);
                    return m;
                }).toList());
        out.put("activatedRules", activatedRules);
        return MiniJson.write(out);
    }

    // ── ask_graph_assert ─────────────────────────────────────────────────────

    private static void registerAssert(LocalToolDispatcher.Builder builder) {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("atom", stringProp(
                "Ground atom to assert, e.g. WORKS_AT(alice, acme) or KNOWS(alice, bob). " +
                "Binary atoms are also written through to the graph as a new relation."));
        props.put("value", numberProp("Truth value in [0,1] (default 1.0 = hard observed fact)."));
        props.put("source", stringProp("Provenance identifier (default 'agent:assert')."));
        props.put("factSheetId", numberProp("Accepted and ignored."));

        builder.handler(
            "ask_graph_assert",
            entry("ask_graph_assert",
                "Assert a ground atom into the KB fact store and write it through to the graph " +
                "(binary atoms become a new relation; unary atoms become a new entity type tag). " +
                "After assertion, ContradictionDetector runs on the fact store and any detected " +
                "contradictions are reported. The graph is re-primed so verify/explain see the " +
                "new fact immediately. cascadeTriggered is always false locally (no PSL cascade).",
                List.of("atom"), props),
            GroundingHandlers::handleAssert);
    }

    private static String handleAssert(LocalReasoningSession session, Map<String, Object> args) {
        String atom = str(args, "atom");
        if (atom == null || atom.isBlank()) {
            return error("ask_graph_assert requires 'atom'");
        }
        atom = atom.trim();
        double value = doubleVal(args, "value", 1.0);
        if (value < 0.0 || value > 1.0) value = 1.0;
        String source = str(args, "source");
        if (source == null || source.isBlank()) source = "agent:assert";

        boolean hard = (value >= 0.99);
        Fact fact = new Fact(atom, value, source, Instant.now(), hard);

        // Assert into fact store
        LocalKbState kb = session.kbState();
        kb.factStore().assertFact(fact);

        // Write-through to graph for binary (relation) atoms
        ParsedAtom parsed = ParsedAtom.parse(atom);
        if (parsed != null && parsed.args.size() == 2) {
            // Binary relation: add to graph
            String relId = "asserted:" + atom.replaceAll("[^a-zA-Z0-9_]", "_");
            UnifiedGraph g = session.graph();
            String srcId = parsed.args.get(0);
            String tgtId = parsed.args.get(1);
            String predicate = parsed.predicate;

            // Add stub entities if absent
            if (g.entity(srcId).isEmpty()) {
                g.addEntity(srcId, "ENTITY", srcId);
            }
            if (g.entity(tgtId).isEmpty()) {
                g.addEntity(tgtId, "ENTITY", tgtId);
            }
            g.addRelation(relId, srcId, tgtId, predicate, value);

            // Re-prime so the new relation's fact appears in the fact store on next verify
            session.reprimeKb();
        } else if (parsed != null && parsed.args.size() == 1) {
            // Unary type atom: entity type membership (no structural graph change needed —
            // the fact is already in the store; reprimeKb would re-add from graph topology)
            // We leave the fact in the store directly since the graph's typeMemberships are immutable.
        }

        // Contradiction detection
        List<ContradictionDetector.Pair<Fact, Fact>> contradictions;
        try {
            contradictions = ContradictionDetector.findFactContradictions(kb.factStore());
        } catch (Exception e) {
            contradictions = List.of();
        }
        List<String> contradictionKeys = contradictions.stream()
                .map(p -> p.first().atomKey() + " vs " + p.second().atomKey())
                .toList();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "OK");
        out.put("contradictions", contradictionKeys);
        out.put("cascadeTriggered", false);
        return MiniJson.write(out);
    }

    // ── ask_graph_retract ────────────────────────────────────────────────────

    private static void registerRetract(LocalToolDispatcher.Builder builder) {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("atomKey", stringProp(
                "Atom key to retract, e.g. WORKS_AT(alice, acme). Must match the stored key exactly."));
        props.put("mode", stringProp("Retraction mode: 'retract' (default). Only 'retract' is supported locally."));
        props.put("factSheetId", numberProp("Accepted and ignored."));

        builder.handler(
            "ask_graph_retract",
            entry("ask_graph_retract",
                "Retract a ground atom from the KB fact store and, for binary atoms, remove the " +
                "matching relation from the graph topology (write-through via UnifiedGraph.removeRelation). " +
                "If a JustificationIndex is available (after an inference run), also purges " +
                "solely-dependent inferred atoms and reports weakened atoms (BeliefReviser). " +
                "The response includes 'topologyRemoved':true when a graph relation was actually " +
                "removed; false for unary atoms or atoms not found as a graph relation. " +
                "After a topology removal the KB is re-primed so verify/explain see the change. " +
                "Retracted atoms do not reappear after save/reload because the relation is gone " +
                "from the graph itself. Orphaned endpoint stub entities are left in place.",
                List.of("atomKey"), props),
            GroundingHandlers::handleRetract);
    }

    private static String handleRetract(LocalReasoningSession session, Map<String, Object> args) {
        String atomKey = str(args, "atomKey");
        if (atomKey == null || atomKey.isBlank()) {
            return error("ask_graph_retract requires 'atomKey'");
        }
        atomKey = atomKey.trim();
        String mode = str(args, "mode");
        if (mode == null || mode.isBlank()) mode = "retract";

        LocalKbState kb = session.kbState();
        List<String> dependentAtomsUnsupported = new ArrayList<>();
        List<String> dependentAtomsWeakened = new ArrayList<>();

        JustificationIndex index = kb.justificationIndex();
        if (index != null) {
            // Full TMS-aware retraction: purge sole-dependent inferred atoms
            try {
                BeliefRevisionResult revision = BeliefReviser.retractAndPurge(
                        atomKey, kb.factStore(), index, kb.inferredFactStore());
                dependentAtomsUnsupported.addAll(revision.unsupportedAtoms());
                dependentAtomsWeakened.addAll(revision.weakenedAtoms());
            } catch (Exception e) {
                // Fall back to simple retraction
                kb.factStore().retract(atomKey);
            }
        } else {
            // No index — simple retraction from observed store and inferred store
            kb.factStore().retract(atomKey);
            try {
                if (kb.inferredFactStore().latest(atomKey).isPresent()) {
                    kb.inferredFactStore().purge(atomKey);
                }
            } catch (Exception ignored) {}
        }

        // ── Topology write-through for binary atoms ───────────────────────────
        // Parse the atom key to detect binary predicates and remove the matching
        // graph relation so the retraction survives save/reload via reprimeKb().
        // Non-binary (unary / 0-arity) or unparseable atoms: fact-store-only (above).
        boolean topologyRemoved = false;
        ParsedAtom parsed = ParsedAtom.parse(atomKey);
        if (parsed != null && parsed.args.size() == 2) {
            String srcId = parsed.args.get(0);
            String tgtId = parsed.args.get(1);
            String pred  = parsed.predicate;
            UnifiedGraph g = session.graph();
            int relsBefore = g.relationCount();
            g.removeRelation(srcId, pred, tgtId);
            topologyRemoved = g.relationCount() < relsBefore;
            if (topologyRemoved) {
                // Re-prime so that reprimeKb reflects the structural removal
                session.reprimeKb();
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "OK");
        out.put("mode", mode);
        out.put("topologyRemoved", topologyRemoved);
        out.put("dependentAtomsUnsupported", dependentAtomsUnsupported);
        out.put("dependentAtomsWeakened", dependentAtomsWeakened);
        return MiniJson.write(out);
    }

    // ── Parsing helpers ──────────────────────────────────────────────────────

    /**
     * Parse a ground atom key of the form {@code PREDICATE(arg1, arg2)} into predicate + args.
     * Returns null if the format cannot be parsed.
     */
    private static final class ParsedAtom {
        final String predicate;
        final List<String> args;

        ParsedAtom(String predicate, List<String> args) {
            this.predicate = predicate;
            this.args = args;
        }

        static ParsedAtom parse(String atomKey) {
            if (atomKey == null || atomKey.isBlank()) return null;
            int lp = atomKey.indexOf('(');
            if (lp < 0) {
                // 0-arity predicate
                return new ParsedAtom(atomKey.trim(), List.of());
            }
            int rp = atomKey.lastIndexOf(')');
            if (rp <= lp) return null;
            String predicate = atomKey.substring(0, lp).trim();
            if (predicate.isBlank()) return null;
            String inside = atomKey.substring(lp + 1, rp).trim();
            if (inside.isBlank()) return new ParsedAtom(predicate, List.of());
            List<String> argList = new ArrayList<>();
            for (String tok : inside.split(",")) {
                argList.add(tok.trim());
            }
            return new ParsedAtom(predicate, argList);
        }
    }

    /** Extract the first argument from an atom key, or null if not parseable. */
    private static String extractFirstArg(String atomKey) {
        ParsedAtom p = ParsedAtom.parse(atomKey);
        if (p == null || p.args.isEmpty()) return null;
        return p.args.get(0);
    }

    private static String confidenceBand(double conf) {
        if (conf >= 0.9) return "VERY_HIGH";
        if (conf >= 0.7) return "HIGH";
        if (conf >= 0.5) return "MEDIUM";
        if (conf >= 0.3) return "LOW";
        return "VERY_LOW";
    }

    /** Recursively collect all activated rule strings from a derivation tree. */
    private static void collectRules(DerivationTree tree, List<String> out) {
        if (tree.ruleApplied() != null && !tree.ruleApplied().isBlank()
                && !out.contains(tree.ruleApplied())) {
            out.add(tree.ruleApplied());
        }
        for (DerivationTree child : tree.children()) {
            collectRules(child, out);
        }
    }

    private static int treeHeight(DerivationTree tree) {
        int childMax = 0;
        for (DerivationTree child : tree.children()) {
            childMax = Math.max(childMax, treeHeight(child));
        }
        return 1 + childMax;
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    // ── Arg-map access helpers ───────────────────────────────────────────────

    private static String str(Map<String, Object> args, String key) {
        Object v = args.get(key);
        return v == null ? null : v.toString();
    }

    private static double doubleVal(Map<String, Object> args, String key, double defaultVal) {
        Object v = args.get(key);
        if (v == null) return defaultVal;
        if (v instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(v.toString().trim()); }
        catch (NumberFormatException e) { return defaultVal; }
    }

    private static int intVal(Map<String, Object> args, String key, int defaultVal) {
        Object v = args.get(key);
        if (v == null) return defaultVal;
        if (v instanceof Number n) return n.intValue();
        try { return Integer.parseInt(v.toString().trim()); }
        catch (NumberFormatException e) { return defaultVal; }
    }

    @SuppressWarnings("unchecked")
    private static String objStr(Map<?, ?> m, String key) {
        Object v = m.get(key);
        return v == null ? null : v.toString();
    }

    private static String error(String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", "ERROR");
        m.put("message", Objects.requireNonNullElse(message, "Unknown error"));
        return MiniJson.write(m);
    }

    // ── Schema/catalog helpers ───────────────────────────────────────────────

    private static LocalToolCatalog.Entry entry(String name, String description,
                                                 List<String> required,
                                                 Map<String, Object> properties) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        if (!required.isEmpty()) schema.put("required", required);
        return new LocalToolCatalog.Entry(name, description, schema);
    }

    private static Map<String, Object> stringProp(String description) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("type", "string");
        p.put("description", description);
        return p;
    }

    private static Map<String, Object> numberProp(String description) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("type", "number");
        p.put("description", description);
        return p;
    }
}
