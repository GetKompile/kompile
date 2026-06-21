# MEBN Engine Gaps — Implementation Notes

## Canonical MEBN Summary

Multi-Entity Bayesian Networks (MEBNs) extend classical Bayesian networks to first-order
probabilistic reasoning. The canonical specification is:

- **Laskey, K. B. (2008).** MEBN: A language for first-order Bayesian knowledge bases.
  *Artificial Intelligence, 172*(2–3), 140–178.
- **Costa, P. C. G., & Laskey, K. B. (2006).** PR-OWL: A Bayesian ontology language for
  the semantic web. In *International Workshop on Uncertainty Reasoning for the Semantic Web*.

Key MEBN concepts:

| Concept | Description |
|---------|-------------|
| **MFrag** | A template for a fragment of a Bayesian network — resident nodes (whose CPTs are defined here), input nodes (CPTs elsewhere), and context nodes (FOL constraints) |
| **Resident RV** | A random variable whose local distribution is defined in exactly one MFrag |
| **MTheory** | A consistent collection of MFrags that jointly define a unique probability distribution over arbitrarily many entity instantiations |
| **SSBN** | Situation-Specific Bayesian Network — the grounded Bayesian network obtained by substituting concrete entities and evaluating context constraints |
| **Default distribution** | Per Laskey 2008 §3.3: every resident RV has a designated fallback CPD used when NO context constraint is satisfied for a given entity grounding |
| **isA hierarchy** | Entity types form a lattice; polymorphic MFrag dispatch selects the most-specific MFrag that still applies to a given entity type |
| **Recursive MFrags** | An ordinary variable (OV) in an MFrag can reference itself at a different index, enabling recursive SSBN construction bounded by a depth limit |

---

## What the Kompile Implementation Had (Pre-2026-06)

The library at `kompile-app/kompile-data/kompile-graphs/kompile-graph-reasoning` contained:

- `EntityType` — entity type with `superType` isA pointer and `isSubtypeOf()` transitive check
- `RandomVariable` — parameterized RV with arity, states, argument types
- `MFrag` — resident/input/context nodes, parent map, local distribution BiFunction
- `MTheory` — consistent collection of MFrags with single-home constraint
- `SSBNGenerator` — full enumeration + context evaluation + noisy-OR CPT construction
- `KnowledgeBase` / `Constraints` — FOL constraint evaluation against the reasoning graph
- `BayesianNetwork` / `VariableElimination` — standard BN inference
- `MebnInferenceService` — convenience end-to-end: graph → SSBN → posteriors
- 286 passing tests

**Gaps before this session:**

1. **Default distributions absent**: context-failed groundings were simply skipped. The canonical MEBN specifies that each resident RV must have a designated default CPD for when no MFrag context applies — this was not implemented.
2. **isA polymorphism in MFrag dispatch**: `EntityType.isSubtypeOf()` existed, but `MTheory` had no method to walk the isA chain and return the most-specific MFrag for a given entity type. The single-home consistency check also used bare RV name (preventing typed polymorphic overloads).
3. **Recursive MFrags**: no mechanism for a resident RV to reference itself at a different index, and no recursion depth bound in `SSBNGenerator`.

---

## What Was Implemented in This Session (2026-06-21)

### Gap 1 — Default Distributions (Laskey 2008 §3.3)

**Files changed:**
- `src/main/java/ai/kompile/graph/reasoning/mebn/MFrag.java`
  - Added `defaultDistribution` field (`BiFunction<String, double[], double[]>`)
  - Added `setDefaultDistribution()` and `getDefaultDistribution()` with canonical Javadoc
- `src/main/java/ai/kompile/graph/reasoning/mebn/SSBNGenerator.java`
  - Added `sourceMFragMap` (`Map<String, MFrag>`) threaded through all generation paths
  - `processMFrag()`: context-failed groundings now instantiate the node when `defaultDistribution != null`, marking it `DistributionMode.DEFAULT`
  - `buildAllCpts()`: `DEFAULT` mode nodes look up the source MFrag via `sourceMFragMap` and call `defaultDistribution.apply(rvName, strengths)` to produce the CPT
  - Added `DistributionMode` enum (`CONTEXTUAL`, `DEFAULT`, `RECURSIVE`)

**Test coverage** (`MebnCanonicalGapsTest.DefaultDistributionTests`):
- Context-met groundings use contextual CPD; context-failed use default distribution (3 nodes created)
- Posterior probability reflects the default CPD value (0.30 ≈ returned by default distribution)
- Without default distribution, context-failed groundings are still skipped (original behaviour preserved)
- Mixed scenario: alice satisfies context, dave does not; both get nodes when defaultDistribution is set

### Gap 2 — Entity-type isA Hierarchy + Polymorphic MFrag Selection (Laskey 2008 §4.2)

**Files changed:**
- `src/main/java/ai/kompile/graph/reasoning/mebn/MTheory.java`
  - Changed `residentHomeMap` key from bare RV name to typed signature `"rvName(ArgType1,ArgType2,...)"`, allowing isA-polymorphic MFrags with the same base name but different argument types to coexist
  - Added `getMostSpecificMFrag(String residentNodeName, EntityType entityType)` — walks the isA chain from most-specific to most-general, returning the first matching MFrag; falls back to bare-name lookup if no typed match
  - Added `rvSignatureKey(RandomVariable)` helper

**Test coverage** (`MebnCanonicalGapsTest.IsaHierarchyTests`):
- Dog→Dog MFrag, GoldenRetriever→Dog MFrag (most-specific via isA), Cat→Animal MFrag
- `isSubtypeOf()` is transitive (GoldenRetriever isA Animal isA Organism)
- Returns empty when no MFrag defines the RV
- Single MFrag for Animal used for all subtypes when no sub-specific MFrag exists

### Gap 3 — Recursive MFrags with Bounded Depth (Laskey 2008 §5)

**Files changed:**
- `src/main/java/ai/kompile/graph/reasoning/mebn/SSBNGenerator.java`
  - Added `maxRecursionDepth` field (default 10) and a 4-argument constructor
  - Added `createRecursiveChain(String, int, BayesianNetwork, Set, Map, Map)`: creates `baseRvName@0` through `baseRvName@effectiveSteps` where `effectiveSteps = min(steps, maxRecursionDepth)`, wires parent edges, marks root `CONTEXTUAL` and rest `RECURSIVE`
  - Added `DistributionMode.RECURSIVE` for recursive chain nodes
  - Added `getMaxRecursionDepth()` accessor

**Test coverage** (`MebnCanonicalGapsTest.RecursiveMFragTests`):
- 3-step Markov chain: `position@0→@1→@2` with correct edge structure
- All posteriors in [0,1] after inference
- Recursion bound: requesting 100 steps caps at `maxRecursionDepth` (5 in test → 6 nodes)
- Step=0: single root node created (no edges)
- Evidence propagation: observing `pos@2=TRUE` is handled without exception

**Combined test** (`MebnCanonicalGapsTest.CombinedTests`):
- isA hierarchy + default distribution together: Employee isA Person; getMostSpecificMFrag resolves correctly; SSBN generates and posteriors are valid

---

## What Remains Deferred

| Item | Notes |
|------|-------|
| **MFrag-level recursive OVs** | Gap 3 implements the chain via a public helper (`createRecursiveChain`). True MEBN recursive OVs would embed the self-reference in the MFrag's parent map using an index argument (e.g., `position(t)` depends on `position(t-1)`). Full integration into `processMFrag` is deferred. |
| **Multiple findings during SSBN construction** | Current `SSBNGenerator.generate()` does not take an initial evidence map during construction; evidence is passed separately to `VariableElimination`. Laskey 2008 §6 describes evidence integration during SSBN construction for efficiency. |
| **SSBN query-driven expansion** | `generateForQuery()` prunes unreachable MFrags but does not do demand-driven parent expansion (Laskey 2008 Algorithm 1, step: expand only parents needed for the query variable). |
| **Typed MFrag dispatch in `processMFrag`** | `processMFrag` iterates the cartesian product over all entity instances. True polymorphic dispatch would call `getMostSpecificMFrag()` per entity during grounding, using the isA hierarchy to pick the right local distribution per entity. |
| **Edge provenance + per-MFrag weights** | Each grounded CPT entry currently uses a fixed noisy-OR causal strength. PR-OWL allows per-entity strength parameters derived from the KG edge weights. |
| **RDF/OWL serialization of MTheory** | PR-OWL defines an OWL 2 DL serialization format for MTheories; no export/import for that format exists. |

---

## Test Summary

| Before session | After session | New tests |
|---|---|---|
| 286 | 300 | +14 |

All 300 tests pass. BUILD SUCCESS.
