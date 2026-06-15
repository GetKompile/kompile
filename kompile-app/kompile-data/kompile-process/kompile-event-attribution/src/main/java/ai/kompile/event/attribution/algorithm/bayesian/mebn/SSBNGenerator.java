/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.event.attribution.algorithm.bayesian.mebn;

import ai.kompile.event.attribution.algorithm.bayesian.*;
import ai.kompile.event.attribution.algorithm.bayesian.mebn.logic.KnowledgeBase;
import ai.kompile.event.attribution.algorithm.bayesian.mebn.logic.LogicalConstraint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Generates a Situation-Specific Bayesian Network (SSBN) from an {@link MTheory}
 * and a set of concrete entities.
 *
 * <h3>Algorithm: Bottom-Up SSBN Generation</h3>
 * <ol>
 *   <li><b>Enumerate groundings</b>: For each MFrag, generate all valid entity
 *       substitutions for its random variable arguments by computing the cartesian
 *       product of entity type instances.</li>
 *   <li><b>Evaluate context constraints</b>: For each grounding, evaluate all
 *       context nodes against the knowledge base. Only groundings where ALL
 *       context constraints evaluate to TRUE proceed.</li>
 *   <li><b>Instantiate BN nodes</b>: For each valid grounding, create concrete
 *       {@link BayesianNode}s in the SSBN with grounded variable names.</li>
 *   <li><b>Wire parent edges</b>: Connect grounded parent nodes to grounded
 *       resident nodes, following the MFrag's fragment graph structure.</li>
 *   <li><b>Build CPTs</b>: For each grounded resident node, compute its CPT
 *       using the MFrag's local distribution function (defaults to noisy-OR
 *       with the edge strengths from the MFrag).</li>
 * </ol>
 *
 * <p>The resulting SSBN is a standard {@link BayesianNetwork} that can be
 * queried using {@link VariableElimination}.</p>
 */
public class SSBNGenerator {

    private static final Logger log = LoggerFactory.getLogger(SSBNGenerator.class);

    private final MTheory mTheory;
    private final KnowledgeBase kb;
    private final double defaultLeakProbability;

    public SSBNGenerator(MTheory mTheory, KnowledgeBase kb) {
        this(mTheory, kb, NoisyOrCpt.DEFAULT_LEAK);
    }

    public SSBNGenerator(MTheory mTheory, KnowledgeBase kb, double defaultLeakProbability) {
        this.mTheory = mTheory;
        this.kb = kb;
        this.defaultLeakProbability = defaultLeakProbability;
    }

    /**
     * Generate the complete SSBN from the MTheory.
     *
     * @return a grounded Bayesian network ready for inference
     */
    public BayesianNetwork generate() {
        log.info("Generating SSBN from MTheory '{}' with {} MFrags",
                mTheory.getName(), mTheory.getMFrags().size());

        BayesianNetwork network = new BayesianNetwork();

        // Track which grounded variables have been created (to avoid duplicates across MFrags)
        Set<String> createdVariables = new HashSet<>();

        // Track parent relationships for CPT construction
        Map<String, List<ParentBinding>> parentBindings = new LinkedHashMap<>();

        // Process each MFrag
        for (MFrag mfrag : mTheory.getMFrags()) {
            processMFrag(mfrag, network, createdVariables, parentBindings);
        }

        // Build CPTs for all nodes
        buildAllCpts(network, parentBindings);

        log.info("SSBN generated: {}", network.getStatistics());
        return network;
    }

    /**
     * Generate an SSBN focused on answering a specific query.
     * Only includes MFrags reachable from the query variable's home MFrag.
     *
     * @param queryRvName the random variable being queried
     * @return a minimal SSBN sufficient for the query
     */
    public BayesianNetwork generateForQuery(String queryRvName) {
        log.info("Generating query-focused SSBN for '{}'", queryRvName);

        // Find which MFrags are needed
        Set<String> neededMFrags = findReachableMFrags(queryRvName);

        BayesianNetwork network = new BayesianNetwork();
        Set<String> createdVariables = new HashSet<>();
        Map<String, List<ParentBinding>> parentBindings = new LinkedHashMap<>();

        for (MFrag mfrag : mTheory.getMFrags()) {
            if (neededMFrags.contains(mfrag.getName())) {
                processMFrag(mfrag, network, createdVariables, parentBindings);
            }
        }

        buildAllCpts(network, parentBindings);

        log.info("Query-focused SSBN generated for '{}': {}", queryRvName, network.getStatistics());
        return network;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // MFRAG PROCESSING
    // ═══════════════════════════════════════════════════════════════════════════

    private void processMFrag(MFrag mfrag, BayesianNetwork network,
                               Set<String> createdVariables,
                               Map<String, List<ParentBinding>> parentBindings) {
        log.debug("Processing MFrag '{}'", mfrag.getName());

        // Collect all RVs in this MFrag (resident + input)
        List<RandomVariable> allRvs = mfrag.getAllNodes();

        // Find all entity types referenced by RVs in this MFrag
        Set<String> argNames = collectArgumentNames(allRvs);

        // Generate all valid groundings
        List<Map<String, String>> validGroundings = generateValidGroundings(mfrag, argNames);

        log.debug("MFrag '{}': {} valid groundings", mfrag.getName(), validGroundings.size());

        for (Map<String, String> grounding : validGroundings) {
            instantiateMFrag(mfrag, grounding, network, createdVariables, parentBindings);
        }
    }

    /**
     * Collect unique argument position names across all RVs.
     */
    private Set<String> collectArgumentNames(List<RandomVariable> rvs) {
        Set<String> names = new LinkedHashSet<>();
        for (RandomVariable rv : rvs) {
            for (int i = 0; i < rv.getArity(); i++) {
                names.add(rv.getArgumentTypes().get(i).getTypeName() + "_" + i);
            }
        }
        return names;
    }

    /**
     * Generate all entity substitutions that satisfy context constraints.
     */
    private List<Map<String, String>> generateValidGroundings(MFrag mfrag,
                                                                Set<String> argNames) {
        // Collect all entity types used across this MFrag's RVs
        List<EntityType> argTypes = new ArrayList<>();
        List<String> argKeys = new ArrayList<>();

        for (RandomVariable rv : mfrag.getAllNodes()) {
            for (int i = 0; i < rv.getArity(); i++) {
                String key = rv.getArgumentTypes().get(i).getTypeName() + "_" + i;
                if (!argKeys.contains(key)) {
                    argKeys.add(key);
                    argTypes.add(rv.getArgumentTypes().get(i));
                }
            }
        }

        if (argTypes.isEmpty()) {
            // Propositional MFrag — one grounding with empty bindings
            Map<String, String> emptyBinding = new HashMap<>();
            if (evaluateContexts(mfrag, emptyBinding)) {
                return List.of(emptyBinding);
            }
            return List.of();
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
                    // Also bind by type name for constraint evaluation
                    newBinding.put(type.getTypeName(), entityId);
                    extended.add(newBinding);
                }
            }
            allGroundings = extended;
        }

        // Filter by context constraints
        return allGroundings.stream()
                .filter(g -> evaluateContexts(mfrag, g))
                .toList();
    }

    /**
     * Evaluate all context constraints for a given grounding.
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
     * Instantiate the BN nodes and edges for one valid grounding of an MFrag.
     */
    private void instantiateMFrag(MFrag mfrag, Map<String, String> grounding,
                                    BayesianNetwork network,
                                    Set<String> createdVariables,
                                    Map<String, List<ParentBinding>> parentBindings) {
        // Create grounded nodes for resident RVs
        for (RandomVariable rv : mfrag.getResidentNodes()) {
            List<String> entityArgs = resolveEntityArgs(rv, grounding);
            String groundedName = rv.ground(entityArgs);

            if (!createdVariables.contains(groundedName)) {
                String kgNodeId = entityArgs.isEmpty() ? groundedName : entityArgs.get(0);
                BayesianNode bnNode = new BayesianNode(groundedName, kgNodeId,
                        rv.getName() + "(" + String.join(",", entityArgs) + ")",
                        rv.getStates());
                network.addNode(bnNode);
                createdVariables.add(groundedName);
            }

            // Create grounded parent edges
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
                        .add(new ParentBinding(groundedParentName, strength));
            }
        }
    }

    /**
     * Resolve entity arguments for a random variable from the grounding map.
     */
    private List<String> resolveEntityArgs(RandomVariable rv, Map<String, String> grounding) {
        List<String> args = new ArrayList<>();
        for (int i = 0; i < rv.getArity(); i++) {
            EntityType argType = rv.getArgumentTypes().get(i);
            String key = argType.getTypeName() + "_" + i;
            String entityId = grounding.get(key);
            if (entityId == null) {
                // Fallback: try type name directly
                entityId = grounding.get(argType.getTypeName());
            }
            if (entityId != null) {
                args.add(entityId);
            }
        }
        return args;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // CPT CONSTRUCTION
    // ═══════════════════════════════════════════════════════════════════════════

    private void buildAllCpts(BayesianNetwork network,
                               Map<String, List<ParentBinding>> parentBindings) {
        for (BayesianNode node : network.getNodes()) {
            if (node.isRoot()) {
                // Root node: uniform prior (no parents in the SSBN)
                node.setCpt(NoisyOrCpt.buildPrior(node.getVariableName(), 0.5));
            } else {
                List<ParentBinding> parents = parentBindings.getOrDefault(
                        node.getVariableName(), List.of());
                if (parents.isEmpty()) {
                    node.setCpt(NoisyOrCpt.buildPrior(node.getVariableName(), 0.5));
                } else {
                    List<String> parentVars = parents.stream()
                            .map(ParentBinding::parentVariable)
                            .toList();
                    double[] strengths = parents.stream()
                            .mapToDouble(ParentBinding::strength)
                            .toArray();
                    node.setCpt(NoisyOrCpt.buildCpt(
                            node.getVariableName(), parentVars, strengths, defaultLeakProbability));
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // MFRAG REACHABILITY
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Find all MFrags reachable from the home MFrag of a given RV,
     * following input node references.
     */
    private Set<String> findReachableMFrags(String rvName) {
        Set<String> reachable = new LinkedHashSet<>();
        Queue<String> queue = new ArrayDeque<>();

        // Start from the home MFrag
        mTheory.findHomeMFrag(rvName).ifPresent(home -> {
            reachable.add(home.getName());
            queue.add(home.getName());
        });

        while (!queue.isEmpty()) {
            String fragName = queue.poll();
            MFrag frag = mTheory.getMFrag(fragName);
            if (frag == null) continue;

            // Follow input nodes to their home MFrags
            for (RandomVariable input : frag.getInputNodes()) {
                mTheory.findHomeMFrag(input.getName()).ifPresent(home -> {
                    if (reachable.add(home.getName())) {
                        queue.add(home.getName());
                    }
                });
            }
        }

        // If no home found, include all MFrags
        if (reachable.isEmpty()) {
            mTheory.getMFrags().forEach(f -> reachable.add(f.getName()));
        }

        return reachable;
    }

    private record ParentBinding(String parentVariable, double strength) {}
}
