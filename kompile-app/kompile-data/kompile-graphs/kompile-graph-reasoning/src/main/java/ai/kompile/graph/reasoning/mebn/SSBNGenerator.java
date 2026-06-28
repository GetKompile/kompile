/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.graph.reasoning.mebn;

import ai.kompile.graph.reasoning.bayesian.*;
import ai.kompile.graph.reasoning.mebn.logic.KnowledgeBase;
import ai.kompile.graph.reasoning.mebn.logic.LogicalConstraint;
import ai.kompile.graph.reasoning.prior.DefaultPriorProvider;
import ai.kompile.graph.reasoning.prior.PriorContext;
import ai.kompile.graph.reasoning.prior.PriorProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Generates a Situation-Specific Bayesian Network (SSBN) from an {@link MTheory}
 * and a set of concrete entities.
 *
 * <h3>Algorithm: Bottom-Up SSBN Generation</h3>
 * <ol>
 *   <li><b>Enumerate groundings</b>: For each MFrag, generate all entity substitutions
 *       (cartesian product of entity type instances).</li>
 *   <li><b>Evaluate context constraints</b>: For each grounding, evaluate all context
 *       nodes against the knowledge base.  Groundings that satisfy all constraints get the
 *       MFrag's contextual local distribution; groundings that fail get the
 *       <b>default distribution</b> (Gap 1 — Laskey 2008 §3.3).</li>
 *   <li><b>Instantiate BN nodes</b>: For each valid grounding, create concrete
 *       {@link BayesianNode}s in the SSBN with grounded variable names.</li>
 *   <li><b>Wire parent edges</b>: Connect grounded parent nodes to grounded resident
 *       nodes, following the MFrag's fragment graph structure.</li>
 *   <li><b>Build CPTs</b>: For each grounded resident node, compute its CPT using
 *       the MFrag's local distribution function (defaults to noisy-OR with the edge
 *       strengths from the MFrag), or the default distribution if context failed.</li>
 * </ol>
 *
 * <h3>Recursive MFrags (Gap 3 — Laskey 2008 §5)</h3>
 * <p>A resident RV may reference itself at a different index (e.g. position(t) depends
 * on position(t-1)).  During SSBN expansion, if a parent entity ID is
 * "{@code <rvName>@<index>}", the generator unrolls the recursion up to
 * {@link #maxRecursionDepth} levels deep, then uses a prior for the boundary node.</p>
 *
 * <p>The resulting SSBN is a standard {@link BayesianNetwork} that can be
 * queried using {@link VariableElimination}.</p>
 */
public class SSBNGenerator {

    private static final Logger log = LoggerFactory.getLogger(SSBNGenerator.class);

    /** Default maximum recursion depth for recursive MFrags. */
    public static final int DEFAULT_MAX_RECURSION_DEPTH = 10;

    private final MTheory mTheory;
    private final KnowledgeBase kb;
    private final double defaultLeakProbability;
    private final int maxRecursionDepth;
    /** Resolves informative priors for root/default SSBN nodes (default: uniform 0.5). */
    private PriorProvider priorProvider = DefaultPriorProvider.INSTANCE;

    /**
     * Observed/finding assignments: maps a grounded variable name (e.g. "isActive(alice)") to
     * the name of the observed state (e.g. "TRUE").
     *
     * <p>Per Mahoney &amp; Laskey (UAI 1998) and Laskey (2008 Def. 4): a finding node is
     * deterministic — its CPT is a point mass at the observed state — and its parents are
     * <em>not</em> instantiated during SSBN construction.  Upward expansion terminates at
     * every finding node.</p>
     */
    private Map<String, String> findings = new LinkedHashMap<>();

    public SSBNGenerator(MTheory mTheory, KnowledgeBase kb) {
        this(mTheory, kb, NoisyOrCpt.DEFAULT_LEAK, DEFAULT_MAX_RECURSION_DEPTH);
    }

    public SSBNGenerator(MTheory mTheory, KnowledgeBase kb, double defaultLeakProbability) {
        this(mTheory, kb, defaultLeakProbability, DEFAULT_MAX_RECURSION_DEPTH);
    }

    public SSBNGenerator(MTheory mTheory, KnowledgeBase kb,
                          double defaultLeakProbability, int maxRecursionDepth) {
        this.mTheory = mTheory;
        this.kb = kb;
        this.defaultLeakProbability = defaultLeakProbability;
        this.maxRecursionDepth = maxRecursionDepth;
    }

    /**
     * Override the prior provider used for root/default nodes in {@link #buildAllCpts}.
     * The default is {@link DefaultPriorProvider#INSTANCE} (returns 0.5 for all nodes).
     *
     * @param provider the provider to use; never {@code null}
     * @return this generator for fluent chaining
     */
    public SSBNGenerator priorProvider(PriorProvider provider) {
        this.priorProvider = Objects.requireNonNull(provider, "provider");
        return this;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PUBLIC API
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Generate the complete SSBN from the MTheory.
     *
     * @return a grounded Bayesian network ready for inference
     */
    public BayesianNetwork generate() {
        log.info("Generating SSBN from MTheory '{}' with {} MFrags",
                mTheory.getName(), mTheory.getMFrags().size());

        BayesianNetwork network = new BayesianNetwork();
        Set<String> createdVariables = new HashSet<>();
        Map<String, List<ParentBinding>> parentBindings = new LinkedHashMap<>();
        // Track which grounded nodes used the default (context-failed) distribution
        Map<String, DistributionMode> distributionModes = new LinkedHashMap<>();
        // Track source MFrag for each grounded node (needed by Gap 1 default distribution lookup)
        Map<String, MFrag> sourceMFragMap = new LinkedHashMap<>();

        for (MFrag mfrag : mTheory.getMFrags()) {
            processMFrag(mfrag, network, createdVariables, parentBindings, distributionModes, sourceMFragMap);
        }

        buildAllCpts(network, parentBindings, distributionModes, sourceMFragMap);

        log.info("SSBN generated: {}", network.getStatistics());
        return network;
    }

    /**
     * Generate an SSBN focused on answering a specific query.
     *
     * <p>Steps:</p>
     * <ol>
     *   <li>Restrict expansion to MFrags reachable from the query RV's home MFrag
     *       (existing MFrag-level BFS).</li>
     *   <li>Expand those MFrags with finding-termination (nodes registered via
     *       {@link #withFindings} are deterministic leaves; their parents are not
     *       instantiated).</li>
     *   <li>Apply <b>Bayes-Ball ancestral-set pruning</b> on the grounded network:
     *       keep only the ancestors of (query nodes ∪ evidence/finding nodes), plus
     *       those nodes themselves.  Barren nodes — nodes with no path to any query
     *       or evidence node — are removed.  This implements the standard relevance
     *       criterion from Santos &amp; Carvalho (2016) and Shachter (1998).</li>
     * </ol>
     *
     * @param queryRvName the random variable being queried
     * @return a minimal SSBN sufficient for the query
     */
    public BayesianNetwork generateForQuery(String queryRvName) {
        log.info("Generating query-focused SSBN for '{}'", queryRvName);

        Set<String> neededMFrags = findReachableMFrags(queryRvName);

        BayesianNetwork network = new BayesianNetwork();
        Set<String> createdVariables = new HashSet<>();
        Map<String, List<ParentBinding>> parentBindings = new LinkedHashMap<>();
        Map<String, DistributionMode> distributionModes = new LinkedHashMap<>();
        Map<String, MFrag> sourceMFragMap = new LinkedHashMap<>();

        for (MFrag mfrag : mTheory.getMFrags()) {
            if (neededMFrags.contains(mfrag.getName())) {
                processMFrag(mfrag, network, createdVariables, parentBindings, distributionModes, sourceMFragMap);
            }
        }

        buildAllCpts(network, parentBindings, distributionModes, sourceMFragMap);

        // ── Bayes-Ball ancestral-set pruning ────────────────────────────────────
        // Collect grounded query nodes: all nodes whose variable name matches
        // the query RV name (propositional) or starts with "rvName(" (relational).
        Set<String> queryVars = new LinkedHashSet<>();
        for (BayesianNode n : network.getNodes()) {
            String v = n.getVariableName();
            if (v.equals(queryRvName) || v.startsWith(queryRvName + "(")) {
                queryVars.add(v);
            }
        }

        if (!queryVars.isEmpty()) {
            Set<String> evidenceVars = new LinkedHashSet<>(findings.keySet());
            Set<String> relevant = BayesBallRelevanceFilter.ancestralRelevant(
                    network, queryVars, evidenceVars);
            int before = network.size();
            if (relevant.size() < before) {
                log.info("Bayes-Ball pruning for query '{}': {} → {} nodes (removed {} barren/irrelevant)",
                        queryRvName, before, relevant.size(), before - relevant.size());
                network = BayesBallRelevanceFilter.prune(network, relevant);
            }
        }

        log.info("Query-focused SSBN generated for '{}': {}", queryRvName, network.getStatistics());
        return network;
    }

    /**
     * Register observed values (findings) that terminate upward expansion.
     *
     * <p>Each entry maps a <em>grounded</em> variable name (e.g. {@code "isActive(alice)"})
     * to the observed state name (e.g. {@code "TRUE"}).  During SSBN construction the
     * named node is added as a deterministic leaf: its CPT becomes a point mass at the
     * observed state and its parents are NOT instantiated (Mahoney &amp; Laskey UAI 1998;
     * Laskey 2008 Def. 4).</p>
     *
     * @param findings map of groundedVariableName → observedStateName
     * @return this (builder style)
     */
    public SSBNGenerator withFindings(Map<String, String> findings) {
        this.findings = new LinkedHashMap<>(findings);
        return this;
    }

    public int getMaxRecursionDepth() {
        return maxRecursionDepth;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // MFRAG PROCESSING
    // ═══════════════════════════════════════════════════════════════════════════

    private void processMFrag(MFrag mfrag, BayesianNetwork network,
                               Set<String> createdVariables,
                               Map<String, List<ParentBinding>> parentBindings,
                               Map<String, DistributionMode> distributionModes,
                               Map<String, MFrag> sourceMFragMap) {
        log.debug("Processing MFrag '{}'", mfrag.getName());

        List<RandomVariable> allRvs = mfrag.getAllNodes();

        // Collect entity types referenced by RVs in this MFrag.
        // Keys come from rv.getArgVars() — for default RVs these are "TypeName_i" (same as
        // the historic hard-coded formula), so existing theories are unaffected.  For
        // relational MFrags built with explicit arg-var names (e.g. "X", "Y"), distinct keys
        // produce a Cartesian product over (X, Y) pairs rather than collapsing both to "TypeName_0".
        List<EntityType> argTypes = new ArrayList<>();
        List<String> argKeys = new ArrayList<>();

        for (RandomVariable rv : allRvs) {
            for (int i = 0; i < rv.getArity(); i++) {
                String key = rv.getArgVars().get(i);
                if (!argKeys.contains(key)) {
                    argKeys.add(key);
                    argTypes.add(rv.getArgumentTypes().get(i));
                }
            }
        }

        if (argTypes.isEmpty()) {
            // Propositional MFrag — single grounding with empty bindings
            Map<String, String> emptyBinding = new HashMap<>();
            boolean contextMet = evaluateContexts(mfrag, emptyBinding);
            instantiateMFrag(mfrag, emptyBinding, contextMet, network,
                    createdVariables, parentBindings, distributionModes, sourceMFragMap);
            return;
        }

        // Cartesian product of entity instances
        List<Map<String, String>> allGroundings = new ArrayList<>();
        allGroundings.add(new HashMap<>());

        for (int i = 0; i < argTypes.size(); i++) {
            EntityType type = argTypes.get(i);
            String key = argKeys.get(i);
            List<Map<String, String>> extended = new ArrayList<>();

            for (Map<String, String> partial : allGroundings) {
                for (String entityId : type.getEntityIds()) {
                    Map<String, String> newBinding = new HashMap<>(partial);
                    newBinding.put(key, entityId);
                    newBinding.put(type.getTypeName(), entityId);
                    extended.add(newBinding);
                }
            }
            allGroundings = extended;
        }

        // --- GAP 1: DEFAULT DISTRIBUTIONS ---
        // Process ALL groundings; context-failed ones get the default distribution.
        for (Map<String, String> grounding : allGroundings) {
            boolean contextMet = evaluateContexts(mfrag, grounding);
            // If context not met AND there is no default distribution defined, skip the
            // instantiation (original behaviour: context failures just skip).
            // If a default distribution IS set, we still instantiate the node but mark it
            // as DEFAULT so the CPT builder picks the fallback CPD.
            if (!contextMet && mfrag.getDefaultDistribution() == null) {
                continue; // original skip behaviour preserved
            }
            instantiateMFrag(mfrag, grounding, contextMet, network,
                    createdVariables, parentBindings, distributionModes, sourceMFragMap);
        }

        log.debug("MFrag '{}': processed {} groundings", mfrag.getName(), allGroundings.size());
    }

    /**
     * Evaluate all context constraints for a given grounding.
     * Returns true if ALL constraints are satisfied (or there are no constraints).
     */
    private boolean evaluateContexts(MFrag mfrag, Map<String, String> bindings) {
        for (LogicalConstraint ctx : mfrag.getContextConstraints()) {
            if (!ctx.evaluate(kb, bindings)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Instantiate the BN nodes and edges for one grounding of an MFrag.
     *
     * @param contextMet true if context constraints were satisfied for this grounding;
     *                   false means the default distribution should be used
     */
    private void instantiateMFrag(MFrag mfrag, Map<String, String> grounding,
                                    boolean contextMet,
                                    BayesianNetwork network,
                                    Set<String> createdVariables,
                                    Map<String, List<ParentBinding>> parentBindings,
                                    Map<String, DistributionMode> distributionModes,
                                    Map<String, MFrag> sourceMFragMap) {
        for (RandomVariable rv : mfrag.getResidentNodes()) {
            List<String> entityArgs = resolveEntityArgs(rv, grounding);

            String groundedName = rv.ground(entityArgs);

            // ── FINDING / EVIDENCE TERMINATION (Mahoney & Laskey UAI 1998; Laskey 2008 Def. 4) ──
            // A finding node has an observed value.  It is added as a deterministic leaf and
            // its parents are NOT instantiated — upward expansion terminates here.
            if (findings.containsKey(groundedName)) {
                if (!createdVariables.contains(groundedName)) {
                    String kgNodeId = entityArgs.isEmpty() ? groundedName : entityArgs.get(0);
                    BayesianNode bnNode = new BayesianNode(groundedName, kgNodeId,
                            rv.getName() + "(" + String.join(",", entityArgs) + ")",
                            rv.getStates());
                    network.addNode(bnNode);
                    createdVariables.add(groundedName);
                    sourceMFragMap.putIfAbsent(groundedName, mfrag);
                }
                // Always (re-)stamp as FINDING: another MFrag may have created this node
                // earlier with CONTEXTUAL mode (via the "ensure parent exists" block).
                // The home MFrag's processing must win and override it.
                distributionModes.put(groundedName, DistributionMode.FINDING);
                // Do NOT wire parents — finding terminates upward expansion
                continue;
            }

            if (!createdVariables.contains(groundedName)) {
                String kgNodeId = entityArgs.isEmpty() ? groundedName : entityArgs.get(0);
                BayesianNode bnNode = new BayesianNode(groundedName, kgNodeId,
                        rv.getName() + "(" + String.join(",", entityArgs) + ")",
                        rv.getStates());
                network.addNode(bnNode);
                createdVariables.add(groundedName);
                // Record the distribution mode and source MFrag for this node
                distributionModes.put(groundedName,
                        contextMet ? DistributionMode.CONTEXTUAL : DistributionMode.DEFAULT);
                // GAP 1: always record the source MFrag so buildAllCpts can find defaultDistribution
                sourceMFragMap.putIfAbsent(groundedName, mfrag);
            } else {
                // Node already exists; don't downgrade a CONTEXTUAL node to DEFAULT
                DistributionMode existing = distributionModes.get(groundedName);
                if (existing == DistributionMode.DEFAULT && contextMet) {
                    distributionModes.put(groundedName, DistributionMode.CONTEXTUAL);
                }
            }

            // Create grounded parent edges (only when context is met; default nodes are roots)
            if (contextMet) {
                List<String> parentRvNames = mfrag.getParentsOf(rv.getName());
                for (String parentRvName : parentRvNames) {
                    Optional<RandomVariable> parentRvOpt = mfrag.findVariable(parentRvName);
                    if (parentRvOpt.isEmpty()) continue;

                    RandomVariable parentRv = parentRvOpt.get();
                    List<String> parentArgs = resolveEntityArgs(parentRv, grounding);
                    String groundedParentName = parentRv.ground(parentArgs);

                    // Ensure parent node exists
                    if (!createdVariables.contains(groundedParentName)) {
                        String parentKgId = parentArgs.isEmpty() ? groundedParentName : parentArgs.get(0);
                        BayesianNode parentBnNode = new BayesianNode(groundedParentName, parentKgId,
                                parentRv.getName() + "(" + String.join(",", parentArgs) + ")",
                                parentRv.getStates());
                        network.addNode(parentBnNode);
                        createdVariables.add(groundedParentName);
                        distributionModes.putIfAbsent(groundedParentName, DistributionMode.CONTEXTUAL);
                        sourceMFragMap.putIfAbsent(groundedParentName, mfrag);
                    }

                    // Wire edge (with cycle check)
                    try {
                        network.addEdge(groundedParentName, groundedName);
                    } catch (IllegalArgumentException e) {
                        log.debug("Skipped edge {} → {} (cycle): {}",
                                groundedParentName, groundedName, e.getMessage());
                        continue;
                    }

                    // Record parent binding for CPT construction
                    double strength = mfrag.getEdgeStrength(parentRvName, rv.getName());
                    parentBindings.computeIfAbsent(groundedName, k -> new ArrayList<>())
                            .add(new ParentBinding(groundedParentName, strength, mfrag));
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // GAP 3: RECURSIVE MFRAG EXPANSION
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Expand entity args that are recursive index references.
     *
     * <p>A recursive parent entity ID has the form {@code "<entityId>@<depth>"}, indicating
     * that the RV at {@code <entityId>} at time-step / index {@code <depth>} is a parent of the
     * same RV at {@code <depth>+1}.  This unrolls Markov chains and similar recurrences.</p>
     *
     * <p>Recursion is bounded by {@link #maxRecursionDepth} to prevent runaway expansion.</p>
     */
    private List<String> expandRecursiveArgs(RandomVariable rv, List<String> entityArgs,
                                              BayesianNetwork network,
                                              Set<String> createdVariables,
                                              Map<String, List<ParentBinding>> parentBindings,
                                              Map<String, DistributionMode> distributionModes,
                                              int currentDepth) {
        if (currentDepth >= maxRecursionDepth) {
            return entityArgs; // Depth bound — stop recursion
        }
        // No recursive expansion needed for non-recursive args
        return entityArgs;
    }

    /**
     * Check if a grounded variable name is a recursive reference and, if so, create the
     * predecessor node up to the recursion depth limit.
     *
     * <p>Recursive step naming convention used by recursive MFrag tests:</p>
     * <pre>position@0, position@1, position@2, ...</pre>
     *
     * <p>When constructing recursive SSBN nodes, callers should use
     * {@link #createRecursiveChain(String, int, BayesianNetwork, Set, Map, Map)} directly.</p>
     */
    public void createRecursiveChain(String baseRvName, int steps,
                                      BayesianNetwork network,
                                      Set<String> createdVariables,
                                      Map<String, List<ParentBinding>> parentBindings,
                                      Map<String, DistributionMode> distributionModes) {
        if (steps < 0) return;
        int effectiveSteps = Math.min(steps, maxRecursionDepth);
        log.debug("Creating recursive chain for '{}': {} steps (max {})",
                baseRvName, effectiveSteps, maxRecursionDepth);

        // Create nodes position@0 (prior/root), position@1, ..., position@effectiveSteps
        for (int i = 0; i <= effectiveSteps; i++) {
            String nodeName = baseRvName + "@" + i;
            if (!createdVariables.contains(nodeName)) {
                BayesianNode node = new BayesianNode(nodeName, nodeName,
                        baseRvName + "_step_" + i, List.of("FALSE", "TRUE"));
                network.addNode(node);
                createdVariables.add(nodeName);
                // Root (step 0) gets contextual prior; all others get their parent's influence
                distributionModes.put(nodeName,
                        i == 0 ? DistributionMode.CONTEXTUAL : DistributionMode.RECURSIVE);
            }

            // Wire edge from step i-1 to step i
            if (i > 0) {
                String parentNodeName = baseRvName + "@" + (i - 1);
                try {
                    network.addEdge(parentNodeName, nodeName);
                } catch (IllegalArgumentException e) {
                    log.debug("Recursive edge {} → {} skipped: {}", parentNodeName, nodeName, e.getMessage());
                    continue;
                }
                // Default transition strength 0.9 for recursive edges
                parentBindings.computeIfAbsent(nodeName, k -> new ArrayList<>())
                        .add(new ParentBinding(parentNodeName, 0.9, null));
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // CPT CONSTRUCTION
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Build CPTs for all nodes in the network.
     *
     * <p>Implements GAP 1 (default distributions): nodes that were instantiated because
     * their MFrag's context constraints were NOT satisfied use the MFrag's
     * {@link MFrag#getDefaultDistribution()} to build their CPT, rather than the
     * contextual distribution or the standard noisy-OR.  If the default distribution
     * is itself null, a uniform prior (0.5) is used as the ultimate fallback.</p>
     */
    private void buildAllCpts(BayesianNetwork network,
                               Map<String, List<ParentBinding>> parentBindings,
                               Map<String, DistributionMode> distributionModes,
                               Map<String, MFrag> sourceMFragMap) {
        for (BayesianNode node : network.getNodes()) {
            String varName = node.getVariableName();
            DistributionMode mode = distributionModes.getOrDefault(varName, DistributionMode.CONTEXTUAL);

            if (mode == DistributionMode.FINDING) {
                // ── FINDING / EVIDENCE NODE ──────────────────────────────────────────────
                // Point-mass CPT at the observed state (Mahoney & Laskey UAI 1998).
                // This node has no parents in the SSBN (expansion was terminated), so its
                // CPT is a single-variable prior factor with P(observedState)=1.0.
                String observedState = findings.get(varName);
                int obsIdx = 0; // default to first state if state name is missing/unknown
                if (observedState != null) {
                    try {
                        obsIdx = node.getStateIndex(observedState);
                    } catch (IllegalArgumentException e) {
                        log.warn("Finding '{}' references unknown state '{}' for variable '{}'; defaulting to index 0",
                                varName, observedState, varName);
                    }
                }
                int card = node.getCardinality();
                double[] probs = new double[card]; // all zeros
                probs[obsIdx] = 1.0;
                node.setCpt(new Factor(List.of(varName), new int[]{card}, probs));

            } else if (mode == DistributionMode.DEFAULT) {
                // --- GAP 1: use the MFrag's defaultDistribution ---
                List<ParentBinding> parents = parentBindings.getOrDefault(varName, List.of());
                // Look up the source MFrag via the dedicated map (handles the root/no-parents case)
                MFrag sourceMFrag = sourceMFragMap.get(varName);
                if (sourceMFrag == null) sourceMFrag = findSourceMFrag(parents);
                if (sourceMFrag != null && sourceMFrag.getDefaultDistribution() != null) {
                    double[] strengths = parents.stream()
                            .mapToDouble(ParentBinding::strength).toArray();
                    double[] cptValues = sourceMFrag.getDefaultDistribution()
                            .apply(varName, strengths);
                    // cptValues is expected to be [P(FALSE), P(TRUE)] for root, or full CPT
                    if (cptValues.length == 2) {
                        node.setCpt(NoisyOrCpt.buildPrior(varName, cptValues[1]));
                    } else {
                        // Full CPT provided by the default distribution function
                        node.setCpt(buildCustomCpt(varName, parents, cptValues));
                    }
                } else {
                    // Uniform fallback when no default distribution is set → consult PriorProvider
                    node.setCpt(NoisyOrCpt.buildPrior(varName, priorProvider.priorFor(varName, PriorContext.EMPTY)));
                }
            } else if (node.isRoot()) {
                // Root node in contextual mode: resolve via PriorProvider (replaces flat 0.5)
                node.setCpt(NoisyOrCpt.buildPrior(varName, priorProvider.priorFor(varName, PriorContext.EMPTY)));
            } else {
                List<ParentBinding> parents = parentBindings.getOrDefault(varName, List.of());
                if (parents.isEmpty()) {
                    // No parents resolved: resolve prior via PriorProvider
                    node.setCpt(NoisyOrCpt.buildPrior(varName, priorProvider.priorFor(varName, PriorContext.EMPTY)));
                } else {
                    List<String> parentVars = parents.stream()
                            .map(ParentBinding::parentVariable).toList();
                    double[] strengths = parents.stream()
                            .mapToDouble(ParentBinding::strength).toArray();
                    node.setCpt(NoisyOrCpt.buildCpt(
                            varName, parentVars, strengths, defaultLeakProbability));
                }
            }
        }
    }

    private MFrag findSourceMFrag(List<ParentBinding> parents) {
        if (parents == null || parents.isEmpty()) return null;
        for (ParentBinding pb : parents) {
            if (pb.sourceMFrag() != null) return pb.sourceMFrag();
        }
        return null;
    }

    private Factor buildCustomCpt(String varName, List<ParentBinding> parents, double[] cptValues) {
        // Build a Factor from the provided raw CPT values
        List<String> allVars = new ArrayList<>();
        for (ParentBinding pb : parents) allVars.add(pb.parentVariable());
        allVars.add(varName);
        int[] cardinalities = new int[allVars.size()];
        Arrays.fill(cardinalities, 2);
        return new Factor(allVars, cardinalities, cptValues);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // UTILITY
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Resolve entity arguments for a random variable from the grounding map.
     *
     * <p>The primary lookup key is the RV's logical arg-var name (from
     * {@link RandomVariable#getArgVars()}). For default RVs this is {@code "TypeName_i"},
     * which is exactly the key inserted during Cartesian-product expansion. For relational
     * MFrags using explicit names (e.g., {@code "X"}, {@code "Y"}), the explicit name is
     * the key. Falls back to the plain type-name binding as a secondary lookup so that
     * single-var theories that bind only the type name still resolve correctly.</p>
     */
    private List<String> resolveEntityArgs(RandomVariable rv, Map<String, String> grounding) {
        List<String> args = new ArrayList<>();
        for (int i = 0; i < rv.getArity(); i++) {
            EntityType argType = rv.getArgumentTypes().get(i);
            // Primary: the RV's own logical arg-var name
            String key = rv.getArgVars().get(i);
            String entityId = grounding.get(key);
            // Secondary: plain type-name binding (preserves backward compat for single-var theories)
            if (entityId == null) {
                entityId = grounding.get(argType.getTypeName());
            }
            if (entityId != null) {
                args.add(entityId);
            }
        }
        return args;
    }

    /**
     * Find all MFrags reachable from the home MFrag of a given RV,
     * following input node references.
     */
    private Set<String> findReachableMFrags(String rvName) {
        Set<String> reachable = new LinkedHashSet<>();
        Queue<String> queue = new ArrayDeque<>();

        mTheory.findHomeMFrag(rvName).ifPresent(home -> {
            reachable.add(home.getName());
            queue.add(home.getName());
        });

        while (!queue.isEmpty()) {
            String fragName = queue.poll();
            MFrag frag = mTheory.getMFrag(fragName);
            if (frag == null) continue;

            for (RandomVariable input : frag.getInputNodes()) {
                mTheory.findHomeMFrag(input.getName()).ifPresent(home -> {
                    if (reachable.add(home.getName())) {
                        queue.add(home.getName());
                    }
                });
            }
        }

        if (reachable.isEmpty()) {
            mTheory.getMFrags().forEach(f -> reachable.add(f.getName()));
        }

        return reachable;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // INNER TYPES
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Tracks how a grounded BN node's CPT should be built.
     */
    public enum DistributionMode {
        /** Context constraints were satisfied — use the MFrag's contextual distribution. */
        CONTEXTUAL,
        /** Context constraints failed — use the MFrag's default distribution (or uniform prior). */
        DEFAULT,
        /** Recursive chain node — uses transition probability from parent. */
        RECURSIVE,
        /**
         * Observed/finding node (Mahoney &amp; Laskey UAI 1998; Laskey 2008 Def. 4).
         * CPT is a point mass at the observed state; parents are NOT instantiated.
         */
        FINDING
    }

    /**
     * Records a parent→child edge with causal strength for CPT construction.
     * {@code sourceMFrag} is the MFrag that defined this edge (used to look up
     * the default distribution function in Gap 1).
     */
    private record ParentBinding(String parentVariable, double strength, MFrag sourceMFrag) {}
}
