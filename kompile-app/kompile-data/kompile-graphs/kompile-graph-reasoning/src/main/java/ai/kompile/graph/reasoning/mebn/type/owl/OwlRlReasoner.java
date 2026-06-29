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
package ai.kompile.graph.reasoning.mebn.type.owl;

import ai.kompile.graph.reasoning.fol.FolInferenceService;
import ai.kompile.graph.reasoning.fol.FolRuleSet;
import ai.kompile.graph.reasoning.fol.InferredFact;
import ai.kompile.graph.reasoning.mebn.logic.Constraints;
import ai.kompile.graph.reasoning.model.GraphRelation;
import ai.kompile.graph.reasoning.model.MutableReasoningGraph;
import ai.kompile.graph.reasoning.model.ReasoningGraph;
import ai.kompile.graph.reasoning.model.SimpleGraphRelation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * OWL 2 RL forward-chaining reasoner — the Phase O2 implementation of {@link OwlReasoner}.
 *
 * <h2>Approach</h2>
 * <p>Two complementary strategies are combined:</p>
 * <ol>
 *   <li><b>BFS transitive closure ({@code prp-trp})</b>: for each property declared
 *       {@code owl:TransitiveProperty} the reasoner walks the graph's adjacency lists with
 *       a breadth-first traversal and emits new {@link GraphRelation}s for every pair
 *       {@code (a, c)} that is reachable from {@code a} via a non-trivial path of typed edges.
 *       This avoids the O(N²) pair-grounding cap in {@link FolInferenceService#inferFacts}.</li>
 *
 *   <li><b>FOL rule compilation (all other RL rules)</b>: {@link OwlRlRuleCompiler} translates
 *       the TBox axioms into {@link ai.kompile.graph.reasoning.fol.FolRule}s, which are run
 *       through {@link FolInferenceService#inferFacts(ReasoningGraph, FolRuleSet)}.
 *       The resulting {@link InferredFact}s are mapped best-effort, while crisp OWL-RL type
 *       memberships ({@code cax-sco}, {@code prp-dom}, {@code prp-rng}, and {@code cls-oo})
 *       are materialised directly from the ABox/TBox so result extraction does not depend on
 *       PSL constant decoding.
 *   </li>
 * </ol>
 *
 * <h2>Purity</h2>
 * <p>This reasoner does not mutate its arguments. All inferred facts are returned in the
 * {@link OwlRlResult}; materialisation into the source graph is the caller's responsibility.</p>
 *
 * <h2>Thread safety</h2>
 * <p>Stateless — each call to {@link #reason} is independent.</p>
 */
public final class OwlRlReasoner implements OwlReasoner {

    private static final Logger log = LoggerFactory.getLogger(OwlRlReasoner.class);

    /** Minimum soft-truth value for an inferred type atom to be accepted. */
    private static final double TYPE_THRESHOLD = 0.5;

    /**
     * {@link OwlRlRuleCompiler} instance — stateless, reused across calls.
     */
    private final OwlRlRuleCompiler compiler = new OwlRlRuleCompiler();

    /**
     * {@link FolInferenceService} — stateless, reused across calls.
     */
    private final FolInferenceService folService = new FolInferenceService();

    /**
     * Run OWL 2 RL inference over {@code graph} (ABox) given {@code ontology} (TBox).
     *
     * <ol>
     *   <li>Run BFS transitive-closure for each transitive property → collect new
     *       {@link GraphRelation}s.</li>
     *   <li>Add the BFS-derived relations to a working copy of the graph so that downstream
     *       FOL rules can build on them.</li>
     *   <li>Compile the TBox via {@link OwlRlRuleCompiler} and run
     *       {@link FolInferenceService#inferFacts}.</li>
     *   <li>Map {@link InferredFact}s to {@link OwlRlResult} components.</li>
     * </ol>
     *
     * @param graph    ABox (never {@code null})
     * @param ontology TBox (never {@code null})
     * @return a populated, non-{@code null} result
     */
    @Override
    public OwlRlResult reason(ReasoningGraph graph, OwlOntology ontology) {
        log.info("OWL RL reasoning: graph={} entities, {} relations",
                graph.entityCount(), graph.relationCount());

        // ── Step 1: transitive closure (BFS, option c per design decision) ──────
        List<GraphRelation> transitiveRelations = computeTransitiveClosure(graph, ontology);
        log.debug("prp-trp: BFS produced {} inferred transitive relations", transitiveRelations.size());

        // ── Step 2: build a working graph that includes the transitive edges ──────
        MutableReasoningGraph workingGraph = buildWorkingGraph(graph, transitiveRelations);

        // ── Step 3: compile TBox → FolRuleSet and run FOL inference ─────────────
        FolRuleSet ruleSet = compiler.compile(ontology);
        log.debug("OwlRlRuleCompiler produced {} rules", ruleSet.size());

        List<InferredFact> facts;
        if (ruleSet.isEmpty()) {
            facts = List.of();
        } else {
            facts = folService.inferFacts(workingGraph, ruleSet);
        }
        log.debug("FolInferenceService emitted {} inferred facts", facts.size());

        // ── Step 4: map InferredFacts → result components ────────────────────────
        Map<String, List<String>> inferredTypes = new LinkedHashMap<>();
        List<OwlInconsistency> inconsistencies = new ArrayList<>();

        mapFacts(facts, ontology, inferredTypes, inconsistencies);
        mergeInferredTypes(inferredTypes, computeTypeEntailments(workingGraph, ontology));

        // Also scan for cax-dw violations using a direct KB-level check:
        // The FOL engine's grounding may not always surface violated falsehood consequents
        // as Type_ atoms, so we run an independent disjointness scan here.
        detectDisjointViolations(graph, ontology, inferredTypes, inconsistencies);

        log.info("OWL RL result: {} transitive edges, {} inferred types, {} inconsistencies",
                transitiveRelations.size(), inferredTypeCount(inferredTypes), inconsistencies.size());

        return OwlRlResult.ofMultiTypes(transitiveRelations, inferredTypes, inconsistencies);
    }

    // ─── BFS transitive closure ───────────────────────────────────────────────────

    /**
     * For every {@code owl:TransitiveProperty} in the ontology, walk the graph's adjacency
     * lists from each entity using BFS and emit new {@link GraphRelation}s for all reachable
     * targets that are not directly connected.
     *
     * <p>This is option (c) from the design decision: a dedicated BFS pass that avoids the
     * 10,000-pair grounding cap in {@link FolInferenceService}. Complexity is O(V + E) per
     * transitive property (linear in the graph).</p>
     *
     * @param graph    the ABox
     * @param ontology the TBox (used to find transitive properties)
     * @return new relations inferred by transitivity (never {@code null})
     */
    private List<GraphRelation> computeTransitiveClosure(ReasoningGraph graph, OwlOntology ontology) {
        List<GraphRelation> inferred = new ArrayList<>();

        for (OwlObjectProperty prop : ontology.objectProperties().values()) {
            if (!prop.isTransitive()) continue;
            String propName = prop.localName();
            log.debug("prp-trp BFS for transitive property '{}'", propName);

            // For every entity that has at least one outgoing edge of this type,
            // compute the full reachable set via BFS and emit missing closure edges.
            for (var entity : graph.entities()) {
                String sourceId = entity.id();
                Set<String> directTargets = directTargets(graph, sourceId, propName);
                if (directTargets.isEmpty()) continue;

                Set<String> reachable = bfsReachable(graph, sourceId, propName);
                // Emit edges for reachable nodes that are not already direct targets
                for (String targetId : reachable) {
                    if (targetId.equals(sourceId)) continue; // skip self-loops unless reflexive
                    if (directTargets.contains(targetId)) continue; // already exists
                    // Also skip if there is already an existing relation of this type
                    if (hasEdgeOfType(graph, sourceId, targetId, propName)) continue;

                    String relId = "owl-trp-" + propName + "-" + sourceId + "-" + targetId;
                    inferred.add(SimpleGraphRelation.directed(relId, sourceId, targetId, propName, 1.0));
                }
            }
        }

        return inferred;
    }

    /**
     * BFS from {@code startId} following only edges of {@code propType} (case-insensitive).
     * Returns all entities reachable from {@code startId} in one or more hops (excluding
     * {@code startId} itself unless there is a cycle back to it).
     */
    private Set<String> bfsReachable(ReasoningGraph graph, String startId, String propType) {
        Set<String> visited = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(startId);
        visited.add(startId);

        while (!queue.isEmpty()) {
            String current = queue.poll();
            for (GraphRelation rel : graph.outgoing(current)) {
                if (!propType.equalsIgnoreCase(rel.type())) continue;
                String next = rel.targetId();
                if (!visited.contains(next)) {
                    visited.add(next);
                    queue.add(next);
                }
            }
        }
        visited.remove(startId); // exclude the start node itself
        return visited;
    }

    /** Direct targets of {@code sourceId} via edges of {@code propType}. */
    private Set<String> directTargets(ReasoningGraph graph, String sourceId, String propType) {
        Set<String> targets = new HashSet<>();
        for (GraphRelation rel : graph.outgoing(sourceId)) {
            if (propType.equalsIgnoreCase(rel.type())) {
                targets.add(rel.targetId());
            }
        }
        return targets;
    }

    /** Whether a directed edge of {@code type} already exists from {@code src} to {@code tgt}. */
    private boolean hasEdgeOfType(ReasoningGraph graph, String src, String tgt, String type) {
        for (GraphRelation rel : graph.outgoing(src)) {
            if (tgt.equals(rel.targetId()) && type.equalsIgnoreCase(rel.type())) return true;
        }
        return false;
    }

    // ─── Working graph construction ───────────────────────────────────────────────

    /**
     * Build a {@link MutableReasoningGraph} that is a shallow copy of {@code original}
     * with the additional {@code extraRelations} added. The original graph is not mutated.
     */
    private MutableReasoningGraph buildWorkingGraph(ReasoningGraph original,
                                                     List<GraphRelation> extraRelations) {
        MutableReasoningGraph working = new MutableReasoningGraph();
        original.entities().forEach(working::addEntity);
        original.relations().forEach(working::addRelation);
        extraRelations.forEach(working::addRelation);
        return working;
    }

    // ─── InferredFact → result mapping ───────────────────────────────────────────

    /**
     * Map {@link InferredFact}s produced by the FOL engine into result components.
     *
     * <h3>Atom key patterns</h3>
     * <ul>
     *   <li>{@code Type_TYPENAME(nX)} with value ≥ 0.5 → {@link OwlRlResult#inferredTypeCandidates()}
     *       (entityId → class local name)</li>
     *   <li>{@code Cons_cax_dw_*(nX,nY)} with value &lt; 0.5 → the consequent of a
     *       disjointness rule failed, but the antecedent was true → inconsistency.
     *       However, the FOL engine records the consequent value, not the rule violation,
     *       so we cross-check with {@link #detectDisjointViolations} instead.</li>
     * </ul>
     *
     * <p>This mapper remains a best-effort compatibility path for any future FOL output that
     * exposes {@code Type_*} targets. The authoritative OWL-RL type memberships are produced by
     * {@link #computeTypeEntailments(ReasoningGraph, OwlOntology)}.</p>
     */
    private void mapFacts(List<InferredFact> facts, OwlOntology ontology,
                           Map<String, List<String>> inferredTypes,
                           List<OwlInconsistency> inconsistencies) {
        for (InferredFact fact : facts) {
            String key = fact.atomKey();
            if (key == null) continue;

            // Type_TYPENAME(nX) — type inference result
            if (key.startsWith("Type_") && key.contains("(") && key.endsWith(")")) {
                if (fact.value() >= TYPE_THRESHOLD) {
                    // Extract the type name from the predicate and the constant from parens
                    int parenOpen = key.indexOf('(');
                    String typeName = key.substring("Type_".length(), parenOpen);
                    // Decode the entity constant back to an entity id via the supporting rules
                    // The constant is nX (e.g. "n0", "n1"); we need the entity id.
                    // The fact's supportingFactKeys carry the atom keys which include entity ids.
                    // We extract entity candidates from the atom key's constant and look them up.
                    String constant = key.substring(parenOpen + 1, key.length() - 1);
                    // Map constant → entity id (the constant is "nN" per GraphPslProgramBuilder)
                    String entityId = resolveEntityId(fact, constant);
                    if (entityId != null && !typeName.startsWith("_fp_") && !typeName.startsWith("_ifp_")) {
                        // Resolve to class IRI if possible
                        String classIri = resolveClassIri(typeName, ontology);
                        addInferredType(inferredTypes, entityId, classIri);
                    }
                }
            }
        }
    }

    private static void addInferredType(Map<String, List<String>> inferredTypes,
                                        String entityId,
                                        String classIri) {
        if (entityId == null || classIri == null) return;
        List<String> entityTypes = inferredTypes.computeIfAbsent(entityId, ignored -> new ArrayList<>());
        if (!entityTypes.contains(classIri)) {
            entityTypes.add(classIri);
        }
    }

    private static int inferredTypeCount(Map<String, List<String>> inferredTypes) {
        return inferredTypes.values().stream().mapToInt(List::size).sum();
    }

    /**
     * Compute crisp OWL-RL ABox type entailments directly from the graph and ontology.
     *
     * <p>The FOL/PSL path is still useful for shared rule infrastructure, but inferred
     * {@code Type_*} atoms are difficult to map back to entity ids because the PSL builder
     * uses generated constants. OWL-RL type propagation is deterministic, so we materialise it
     * here from asserted entity memberships plus ontology class/property closure.</p>
     */
    private Map<String, List<String>> computeTypeEntailments(ReasoningGraph graph, OwlOntology ontology) {
        Map<String, Set<String>> assertedTypes = new LinkedHashMap<>();
        Map<String, Set<String>> candidateTypes = new LinkedHashMap<>();

        for (var entity : graph.entities()) {
            Set<String> asserted = new LinkedHashSet<>();
            for (String membership : entity.typeMemberships()) {
                String classIri = resolveClassIri(membership, ontology);
                if (classIri != null && !classIri.isBlank()) {
                    asserted.add(classIri);
                }
            }
            assertedTypes.put(entity.id(), asserted);
            candidateTypes.put(entity.id(), new LinkedHashSet<>(asserted));
        }

        addDomainRangeTypeEntailments(graph, ontology, candidateTypes);

        Map<String, List<String>> inferred = new LinkedHashMap<>();
        for (Map.Entry<String, Set<String>> entry : candidateTypes.entrySet()) {
            String entityId = entry.getKey();
            Set<String> asserted = assertedTypes.getOrDefault(entityId, Set.of());
            Set<String> closure = closeClassTypes(entry.getValue(), ontology);
            for (String classIri : closure) {
                if (!asserted.contains(classIri)) {
                    addInferredType(inferred, entityId, classIri);
                }
            }
        }
        return inferred;
    }

    private void addDomainRangeTypeEntailments(ReasoningGraph graph,
                                               OwlOntology ontology,
                                               Map<String, Set<String>> candidateTypes) {
        for (GraphRelation rel : graph.relations()) {
            for (OwlObjectProperty prop : entailedProperties(rel.type(), ontology)) {
                if (prop.domainClassIri() != null) {
                    addCandidateType(candidateTypes, rel.sourceId(),
                            resolveClassIri(prop.domainClassIri(), ontology));
                }
                if (prop.rangeClassIri() != null) {
                    addCandidateType(candidateTypes, rel.targetId(),
                            resolveClassIri(prop.rangeClassIri(), ontology));
                }
            }
        }
    }

    private Set<String> closeClassTypes(Set<String> seedTypes, OwlOntology ontology) {
        Map<String, Set<String>> edges = classEntailmentEdges(ontology);
        Set<String> closure = new LinkedHashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        for (String seed : seedTypes) {
            String classIri = resolveClassIri(seed, ontology);
            if (classIri != null && closure.add(classIri)) {
                queue.add(classIri);
            }
        }

        while (!queue.isEmpty()) {
            String current = queue.poll();
            for (String next : edges.getOrDefault(current, Set.of())) {
                String nextIri = resolveClassIri(next, ontology);
                if (nextIri != null && closure.add(nextIri)) {
                    queue.add(nextIri);
                }
            }
        }
        return closure;
    }

    private Map<String, Set<String>> classEntailmentEdges(OwlOntology ontology) {
        Map<String, Set<String>> edges = new LinkedHashMap<>();
        for (OwlClass owlClass : ontology.classes().values()) {
            String classIri = owlClass.classIri();
            for (String superClassIri : owlClass.subClassOfIris()) {
                addEntailmentEdge(edges, classIri, resolveClassIri(superClassIri, ontology));
            }
            for (String equivalentClassIri : owlClass.equivalentClassIris()) {
                String equivalent = resolveClassIri(equivalentClassIri, ontology);
                addEntailmentEdge(edges, classIri, equivalent);
                addEntailmentEdge(edges, equivalent, classIri);
            }
        }
        return edges;
    }

    private Set<OwlObjectProperty> entailedProperties(String relationType, OwlOntology ontology) {
        Set<String> seeds = new LinkedHashSet<>();
        for (OwlObjectProperty prop : ontology.objectProperties().values()) {
            if (matchesProperty(relationType, prop)) {
                seeds.add(prop.propertyIri());
            }
        }
        if (seeds.isEmpty()) {
            return Set.of();
        }

        Map<String, Set<String>> edges = propertyEntailmentEdges(ontology);
        Set<String> closure = new LinkedHashSet<>();
        Deque<String> queue = new ArrayDeque<>(seeds);
        while (!queue.isEmpty()) {
            String current = queue.poll();
            if (!closure.add(current)) {
                continue;
            }
            for (String next : edges.getOrDefault(current, Set.of())) {
                if (!closure.contains(next)) {
                    queue.add(next);
                }
            }
        }

        Set<OwlObjectProperty> properties = new LinkedHashSet<>();
        for (String propertyIri : closure) {
            OwlObjectProperty prop = ontology.objectProperties().get(propertyIri);
            if (prop != null) {
                properties.add(prop);
            }
        }
        return properties;
    }

    private Map<String, Set<String>> propertyEntailmentEdges(OwlOntology ontology) {
        Map<String, Set<String>> edges = new LinkedHashMap<>();
        for (OwlObjectProperty prop : ontology.objectProperties().values()) {
            String propertyIri = prop.propertyIri();
            for (String superPropertyIri : prop.subPropertyOfIris()) {
                addEntailmentEdge(edges, propertyIri, superPropertyIri);
            }
            for (String equivalentPropertyIri : prop.equivalentPropertyIris()) {
                addEntailmentEdge(edges, propertyIri, equivalentPropertyIri);
                addEntailmentEdge(edges, equivalentPropertyIri, propertyIri);
            }
        }
        return edges;
    }

    private static void mergeInferredTypes(Map<String, List<String>> target,
                                           Map<String, List<String>> source) {
        for (Map.Entry<String, List<String>> entry : source.entrySet()) {
            for (String classIri : entry.getValue()) {
                addInferredType(target, entry.getKey(), classIri);
            }
        }
    }

    private static void addCandidateType(Map<String, Set<String>> candidateTypes,
                                         String entityId,
                                         String classIri) {
        if (entityId == null || classIri == null || classIri.isBlank()) return;
        candidateTypes.computeIfAbsent(entityId, ignored -> new LinkedHashSet<>()).add(classIri);
    }

    private static void addEntailmentEdge(Map<String, Set<String>> edges,
                                          String from,
                                          String to) {
        if (from == null || from.isBlank() || to == null || to.isBlank()) return;
        edges.computeIfAbsent(from, ignored -> new LinkedHashSet<>()).add(to);
    }

    private static boolean matchesProperty(String relationType, OwlObjectProperty prop) {
        if (relationType == null || relationType.isBlank()) return false;
        String normalized = relationType.trim();
        return normalized.equals(prop.propertyIri())
                || normalized.equalsIgnoreCase(prop.localName())
                || rawLocalName(normalized).equalsIgnoreCase(prop.localName());
    }

    /**
     * Attempt to resolve a PSL constant (e.g. "n0") back to an entity id by examining
     * the supporting fact keys. Supporting atoms often carry the constant in their key,
     * and the prior atom {@code Prior(nX)} maps to entity ids recorded by the graph builder.
     *
     * <p>This is a best-effort extraction: if the atom key itself contains the entity id
     * in a recognisable pattern (Ante or Cons atoms contain the constant) we
     * can reverse it from the supporting rules. In practice the most reliable mapping is
     * to scan the supporting fact keys for Type_N(constant) patterns whose entity
     * id is already known.</p>
     *
     * <p>When the mapping cannot be resolved (e.g. the constant is not in the supporting
     * keys), we return {@code null} and skip the fact — the BFS closure and
     * disjointness scan remain the primary result carriers.</p>
     */
    private String resolveEntityId(InferredFact fact, String constant) {
        // Try to find the entity id in supporting fact keys
        for (String supportKey : fact.supportingFactKeys()) {
            if (supportKey == null) continue;
            // Matching pattern: any atom key containing the same constant
            if (supportKey.contains("(" + constant + ")") || supportKey.contains("(" + constant + ",")) {
                // Extract id from prior/type atoms which use the same naming: "Prior(entityId)"
                // or "State(entityId)" atoms sometimes use the entity id directly as the constant
                // when there is only one entity. In practice, for small test graphs this works.
                if (supportKey.startsWith("Prior(") || supportKey.startsWith("State(")) {
                    int s = supportKey.indexOf('(') + 1;
                    int e = supportKey.lastIndexOf(')');
                    if (s > 0 && e > s) {
                        String candidate = supportKey.substring(s, e);
                        if (!candidate.equals(constant)) return candidate;
                    }
                }
            }
        }
        // Fallback: use the constant itself as the entity id (works for single-entity graphs)
        return constant.startsWith("n") ? null : constant;
    }

    /** Resolve a class local name to its IRI using the ontology, or return the name as-is. */
    private static String resolveClassIri(String localName, OwlOntology ontology) {
        if (localName == null) return null;
        String normalized = localName.trim();
        if (normalized.isEmpty()) return normalized;
        OwlClass directClass = ontology.classes().get(normalized);
        if (directClass != null) return directClass.classIri();
        for (OwlClass cls : ontology.classes().values()) {
            if (normalized.equalsIgnoreCase(cls.localName())
                    || normalized.equalsIgnoreCase(cls.classIri())) {
                return cls.classIri();
            }
        }
        return normalized;
    }

    // ─── Direct disjointness scan ─────────────────────────────────────────────────

    /**
     * Scan the graph directly for {@code cax-dw} violations: any entity whose type matches
     * two classes declared {@code owl:disjointWith} each other.
     *
     * <p>This is a direct O(V × D) scan (V = entities, D = disjointness pairs) that bypasses
     * the FOL engine's grounding. It is more reliable than reading the FOL engine's consequent
     * atoms because the PSL grounding assigns soft-truth values that may not precisely reflect
     * the crisp Boolean antecedent being satisfied.</p>
     *
     * <p>Detected violations are added to {@code inconsistencies}; duplicates (same entity +
     * same rule) are suppressed.</p>
     */
    void detectDisjointViolations(ReasoningGraph graph,
                                  OwlOntology ontology,
                                  Map<String, List<String>> inferredTypes,
                                  List<OwlInconsistency> inconsistencies) {
        // Collect disjoint pairs as (localNameA, localNameB) with A < B alphabetically
        List<String[]> disjointPairs = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (OwlClass cls : ontology.classes().values()) {
            String cName = cls.localName();
            for (String dIri : cls.disjointWithIris()) {
                String dName = localNameFromIri(dIri, ontology);
                String key = cName.compareTo(dName) <= 0
                        ? cName + "|" + dName
                        : dName + "|" + cName;
                if (seen.add(key)) {
                    disjointPairs.add(new String[]{
                            cName.compareTo(dName) <= 0 ? cName : dName,
                            cName.compareTo(dName) <= 0 ? dName : cName
                    });
                }
            }
        }

        if (disjointPairs.isEmpty()) return;

        Set<String> addedKeys = new HashSet<>();
        for (var entity : graph.entities()) {
            Set<String> entityTypes = new HashSet<>();
            for (String membership : entity.typeMemberships()) {
                addClassMembership(entityTypes, membership, ontology);
            }
            for (String inferredType : inferredTypes.getOrDefault(entity.id(), List.of())) {
                addClassMembership(entityTypes, inferredType, ontology);
            }
            if (entityTypes.isEmpty()) continue;

            for (String[] pair : disjointPairs) {
                String a = pair[0];
                String b = pair[1];
                if (hasClassMembership(entityTypes, a) && hasClassMembership(entityTypes, b)) {
                    String violKey = "cax-dw-" + entity.id() + "-" + a + "-" + b;
                    if (addedKeys.add(violKey)) {
                        inconsistencies.add(OwlInconsistency.crisp(
                                "cax-dw",
                                entity.id(),
                                "Entity '" + entity.id() + "' is typed as both '" + a
                                        + "' and '" + b + "' which are declared disjoint"));
                    }
                }
            }
        }
    }

    private static void addClassMembership(Set<String> entityTypes, String rawType, OwlOntology ontology) {
        if (rawType == null || rawType.isBlank()) return;
        entityTypes.add(localNameFromIri(rawType.trim(), ontology));
    }

    private static boolean hasClassMembership(Set<String> entityTypes, String className) {
        for (String entityType : entityTypes) {
            if (entityType.equalsIgnoreCase(className)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Resolve a class IRI to its local name using the ontology registry or IRI extraction.
     */
    private static String localNameFromIri(String iri, OwlOntology ontology) {
        OwlClass cls = ontology.classes().get(iri);
        if (cls != null) return cls.localName();
        return rawLocalName(iri);
    }

    private static String rawLocalName(String iri) {
        if (iri == null) return null;
        int hash = iri.lastIndexOf('#');
        if (hash >= 0 && hash < iri.length() - 1) return iri.substring(hash + 1);
        int slash = iri.lastIndexOf('/');
        if (slash >= 0 && slash < iri.length() - 1) return iri.substring(slash + 1);
        return iri;
    }
}
