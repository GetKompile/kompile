# Recursive / Fixpoint Querying — Design Specification

**Date**: 2026-06-21
**Module**: `kompile-app/kompile-data/kompile-graphs/kompile-graph-reasoning`
**Status**: Design doc — no code changes

---

## 0. Problem Statement

`FolInferenceService.buildPairs` (`fol/FolInferenceService.java:310`) caps grounding at 10,000
entity pairs with an `outer: break` label. This hard limit, combined with the fact that the
grounding loop is a one-shot O(N²) scan, means recursive rules such as:

```
path(X,Z) :- path(X,Y), edge(Y,Z).
```

cannot be expressed: the first iteration emits one-hop paths; no second pass applies the rule
to the newly derived paths. Similarly, `ConjunctiveQueryEngine` (`fol/grounding/ConjunctiveQueryEngine.java`)
queries only atoms _already_ in the `InferredFactStore` — it is a lookup engine, not a derivation
engine. Recursive rules that should iterate to a fixpoint simply do not run.

The existing OWL BFS in `OwlRlReasoner.computeTransitiveClosure` (`mebn/type/owl/OwlRlReasoner.java:161`)
is the lone precedent for recursion-by-traversal: it handles one specific recursive case
(`owl:TransitiveProperty`, i.e. `prp-trp`) by bypassing `FolInferenceService` entirely,
running a BFS over `ReasoningGraph.outgoing()`. That approach is O(V+E) per transitive property —
correct and efficient — but it is hard-coded to that one axiom pattern. A general recursive
query engine is needed for everything else.

---

## 1. Background: Semi-Naive Fixpoint Evaluation

Semi-naive evaluation is the standard algorithm for bottom-up Datalog.
Sources consulted: Green et al., "Datalog and Recursive Query Processing" (FTTDB 2013),
available at https://mwhittaker.github.io/papers/html/green2013datalog.html;
Wisc CS838 Lecture 8 https://pages.cs.wisc.edu/~paris/cs838-s16/lecture-notes/lecture8.pdf;
Berkeley CS294-260 Datalog lecture https://inst.eecs.berkeley.edu/~cs294-260/sp24/2024-02-05-datalog;
Soufflé incremental evaluation paper https://souffle-lang.github.io/pdf/ppdp21incremental.pdf.

### 1.1 Delta / Semi-Naive Trick

For each IDB (derived) relation `p`, maintain two sets:
- `p[i]` — all facts accumulated through round `i`
- `δ(p)[i]` — facts newly derived in round `i` (the "delta"): `δ(p)[i] = p[i] \ p[i-1]`

For every rule body with `k` IDB atoms, generate `k` delta-rewritten variants.
In each variant, exactly one body atom uses `δ` (the current round's new facts);
all other body atoms use the full accumulated set from the prior round.
Example for `TC(X,Z) :- TC(X,Y), TC(Y,Z)`:

```
δTC(X,Z) :- δTC(X,Y), TC_prev(Y,Z)      // new X→Y, any old Y→Z
δTC(X,Z) :- TC_prev(X,Y), δTC(Y,Z)      // old X→Y, new Y→Z
```

This guarantees every firing uses at least one new fact, eliminating the "old-with-old"
redundant joins that make naive evaluation O(N² × R).

### 1.2 Termination

The fixpoint loop terminates when `δ(p)[i] = ∅` for all IDB relations.
Since the Herbrand base is finite and inference is monotone (no retraction in the
recursive core), termination is guaranteed in at most `|HB|` iterations.
For graphs with V entities and E edges, `|HB|` is bounded by V² (binary predicates),
so the loop runs at most V² rounds in the worst case — but in practice terminates
in O(diameter) rounds for path/ancestor queries.

### 1.3 Complexity

| Method       | Per-fact work                                                      | Total        |
|--------------|--------------------------------------------------------------------|--------------|
| Naive        | Re-derives all known facts every round (old-with-old)              | O(N² × R)   |
| Semi-naive   | Only joins delta atoms; each new fact derived at most once per rule | O(N × E)    |

For linear-recursive rules (one IDB atom in the body), semi-naive is linear in the number
of new facts. For non-linear rules (transitive-closure body has two IDB atoms), the two
delta variants still eliminate old-with-old joins.

### 1.4 Stratified Negation

Stratified negation partitions rules into layers using the predicate dependency graph:
- Positive edge `B → A`: B appears positively in a rule for A  
- Negative edge `B →⁻ A`: `¬B` appears in a rule for A

A stratum assignment satisfies:
- `stratum(B) ≤ stratum(A)` for each positive edge B → A
- `stratum(B) < stratum(A)` for each negative edge B →⁻ A

A program is **stratifiable** iff no cycle in the dependency graph passes through a
negative edge (i.e. no predicate is, directly or transitively, negatively dependent on itself).
This check is O(predicates + edges) via Tarjan SCC. Programs that fail stratifiability
fall back to well-founded semantics (WFS) (Van Gelder, Ross, Schlipf, J. ACM 1991,
https://dl.acm.org/doi/10.5555/107720.107722) — a three-valued logic (true/false/undefined)
where atoms in unresolvable negative cycles get value `undefined`.

For our use cases (transitive closure, ancestor, type propagation), all expected rule sets
are stratifiable and WFS is not required for the initial implementation.

---

## 2. Interaction with PSL Soft-Truth — The Split

### 2.1 Why the Split is Necessary

PSL (Probabilistic Soft Logic) operates on soft truths in [0,1] via Łukasiewicz T-norms.
A Datalog fixpoint engine operates on crisp {true, false} membership — a fact is either
in the derived set or it isn't. These are semantically different:

- **Recursive Datalog** asks: "Is `path(A,C)` derivable, and what are its argument bindings?"
- **PSL** asks: "Given a set of grounded rules, what is the MAP soft-truth assignment
  to all open-world atoms that minimises energy?"

Applying PSL semantics recursively is ill-defined: the Łukasiewicz fixpoint for
`p(X,Z) = max(0, p(X,Y) + p(Y,Z) - 1)` does not converge to a unique solution in general
and does not correspond to path reachability.

The reference PSL literature (Bach et al., JMLR 2017,
https://jmlr.org/papers/volume18/15-631/15-631.pdf) addresses this by grounding first
(crisp Datalog) and then applying PSL MAP over the grounded program. This is exactly the
split recommended here.

### 2.2 Recommended Split

**Tier 1 — Crisp recursive grounding (new `RecursiveQueryEngine`)**

Run semi-naive fixpoint evaluation over materialized and observed facts to produce new
`InferredFact` entries with `confidence = 1.0` (derivable = true under CWA/OWA for crisp
rules). These derived facts are written into a `WorkingFactSet` — a temporary in-memory set
that is the "delta materialization."

The recursive engine consumes:
- `InferredFactStore.allLatest()` — materialized PSL MAP values
- `FactStore` asserted facts
- `ReasoningGraph` entities and relations (translated to atoms by the caller)

It produces `InferredFact` rows with `confidence = 1.0` and `ruleWeights = []` (the fact
is derivable, not probabilistically weighted).

**Tier 2 — PSL MAP over the fully grounded program**

After Tier 1 delivers the materialized derived facts, those facts are added to the
`PslProgram` as observed atoms (confidence = 1.0). The existing `HlMrfMapInference.solve`
then refines all open-world soft-truth atoms as before. This preserves the full PSL
uncertainty model over the grounded + recursively derived facts.

**Result**: Recursive paths are closed under crisp Datalog; PSL MAP then assigns
soft-truth scores to entity-level attributes conditional on those paths.

### 2.3 Recursive Probabilistic Inference (Deferred)

A future extension could apply probability propagation along recursively derived paths
(e.g., `pathConfidence(X,Z) = pathConfidence(X,Y) * edgeWeight(Y,Z)` for Bayesian
chains). This is a different problem (probabilistic path queries) and should be
implemented as a separate service layered on top of Tier 1 derivation. It is **not**
part of this design.

---

## 3. `RecursiveQueryEngine` — Specification

### 3.1 Placement

New file:
```
fol/grounding/RecursiveQueryEngine.java
```
Package: `ai.kompile.graph.reasoning.fol.grounding`
No Spring. Pure Java. No new Maven dependencies.

### 3.2 Inputs

```java
public final class RecursiveQueryEngine {

    /**
     * A Datalog rule: head predicate + body conjuncts (each a predicate name + argument list).
     * Arguments may be variables ("?X") or ground constants.
     * Negated body atoms are represented with a "!" prefix on the predicate name,
     * but only in non-recursive strata (stratified negation).
     */
    public record DatalogRule(String headPredicate, List<String> headArgs,
                              List<RuleAtom> body) {}

    public record RuleAtom(String predicate, List<String> args, boolean negated) {}

    /**
     * A base-relation provider: maps a predicate name to the set of ground tuples
     * currently known for that predicate (EDB + previously materialized IDB facts).
     */
    @FunctionalInterface
    public interface EdbProvider {
        /** Return all ground tuples for the given predicate as lists of constant strings. */
        List<List<String>> tuplesFor(String predicate);
    }
}
```

### 3.3 Semi-Naive Fixpoint Loop

```
Algorithm SemiNaiveEval(rules, edb):
  // Initialize IDB relations
  for each IDB predicate p:
    idb[p] ← {}
    delta[p] ← {} 

  // Seed deltas from EDB + previously materialized facts
  for each IDB predicate p:
    for each rule R where head(R).predicate == p:
      delta[p] ← delta[p] ∪ matchBody(R, edb, idb)
    idb[p] ← delta[p]

  // Fixpoint iteration
  repeat:
    new_delta[p] ← {} for all p
    for each rule R with head predicate p:
      for k in 0..len(body(R))-1:
        // Delta-rewritten variant k: body[k] uses delta, all others use idb
        new_facts ← matchDeltaVariant(R, k, idb, delta, edb)
        new_delta[p] ← new_delta[p] ∪ (new_facts \ idb[p])
    for each p:
      idb[p] ← idb[p] ∪ new_delta[p]
      delta[p] ← new_delta[p]
  until all delta[p] are empty

  return idb
```

`matchBody` / `matchDeltaVariant` use the same backtracking conjunctive join already in
`ConjunctiveQueryEngine.backtrack` (`fol/grounding/ConjunctiveQueryEngine.java:164`) —
that method can be extracted into a shared `JoinKernel` utility so `RecursiveQueryEngine`
reuses it without duplication.

### 3.4 Predicate Index

The critical performance path is the inner join loop. The `RecursiveQueryEngine` maintains
a `Map<String, Set<List<String>>>` per IDB predicate — the "IDB index." On each delta
iteration, only atoms in the current `delta[p]` set are used as the "new" side of the join.
Because Java `HashSet.contains()` is O(1) and we only iterate over delta (not the full IDB),
the per-iteration cost is O(|delta| × join-selectivity), not O(|idb|²).

For the EDB, the `EdbProvider` functional interface allows callers to provide:
- Direct `InferredFactStore` lookup (the primary path)
- `ReasoningGraph.outgoing(entityId)` for edge predicates
- `FactStore` observed facts

This keeps the engine storage-agnostic, consistent with the module's infra-free design.

### 3.5 Indexed Join for Binary Predicates

For binary predicates (the common case: `edge(X,Y)`, `path(X,Z)`), maintain a secondary
index keyed by first argument: `Map<String, List<List<String>>>`. This turns the inner join
`edge(Y,Z)` for a given bound value of `Y` from O(|edge|) to O(|edge restricted to Y|),
which for sparse graphs is O(degree) rather than O(E). This is the standard adjacency-list
representation already used in `ReasoningGraph.outgoing()`.

### 3.6 Termination Guard

The loop terminates when all deltas are empty. Additionally, a hard upper bound:

```java
private static final int MAX_FIXPOINT_ROUNDS = 10_000;
private static final int MAX_DERIVED_FACTS    = 500_000; // same as PslProgram.MAX_GROUND_RULES
```

If either bound is hit, the engine logs a warning at WARN level and returns what it has.
This prevents non-termination for rules that are accidentally non-recursive-stratifiable
or for pathological graphs with very long chains. The caller receives an `isComplete`
flag in the result.

---

## 4. Stratified Negation Implementation

### 4.1 Dependency Graph Analysis

Before evaluation, build a directed predicate dependency graph:

```java
// Positive edge: B appears positively in a rule for A
// Negative edge: B appears as ¬B (negated) in a rule for A
record PredEdge(String from, String to, boolean negative) {}
```

Run Tarjan's SCC (or Kahn's topological sort with negative-edge detection).
If any SCC contains a negative edge within the cycle: log WARN "rule set is not
stratifiable; negation in cycle involving predicate P — treating as undefined (WFS)."
For the initial implementation, treat such atoms as `undefined` (confidence = 0.5)
rather than implementing full WFS three-valued logic.

### 4.2 Stratified Evaluation

For a stratifiable program:
1. Partition predicates into strata (topological order).
2. For stratum 0: evaluate with semi-naive fixpoint using only positive rules and EDB.
3. For stratum `k > 0`: add the fully-evaluated stratum `k-1` predicates as EDB,
   then evaluate stratum `k` rules (which may reference `¬p` for `p` in lower strata)
   with semi-naive fixpoint.

### 4.3 Negation Evaluation

In `matchBody`, when a negated literal `¬p(args)` is encountered:
- All variables in `args` must be bound by the time this literal is evaluated
  (safety condition: every variable in a negated literal must also appear in a
  positive literal in the same rule body — standard Datalog safety).
- Check if the ground atom `p(bound_args)` is in the current IDB or EDB.
- If it IS in the fact set: this binding fails (negation-as-failure).
- If it is NOT in the fact set: this binding succeeds (closed-world assumption).

Safety checking is performed at rule-parse time; unsafe rules are rejected with an
`IllegalArgumentException` rather than silently producing incorrect results.

---

## 5. Termination and Cost Model

### 5.1 Why the 10k-Pair Cap Blows Up

`FolInferenceService.buildPairs` (`fol/FolInferenceService.java:310`) does an
unconditional N×N Cartesian product of all entities. For N=100 entities, this is 10,000
pairs — hitting the cap immediately. For N=316, every pair is the cap. The fix is NOT to
raise the cap: it is to not enumerate pairs at all for recursive predicates, replacing
the cross-product with delta-join.

### 5.2 Cost Model for `RecursiveQueryEngine`

Let:
- `N` = total IDB facts at fixpoint
- `D` = average out-degree in the binary-predicate graph (edges per entity)
- `R` = number of recursive rules
- `K` = number of fixpoint rounds (≤ graph diameter for path queries)

Cost per round (semi-naive, with adjacency-list index):
```
O(|delta| × D × R)   // for each new path endpoint, fan out by degree
```

Total cost:
```
O(N × D × R × K)    // for all rounds
```

For typical knowledge graphs (sparse, D ≈ 10, R ≈ 5, K ≈ log(V)):
```
O(N × 10 × 5 × log(V)) ≈ O(50 × N × log(V))
```

Compare to `buildPairs` naive O(N² × R) = O(N² × R).
For N=10,000 and R=5, this is 500M operations vs 50×10,000×13 ≈ 6.5M.

### 5.3 Memory Model

Per IDB predicate, the `Set<List<String>>` delta stores only new facts per round.
At fixpoint, the IDB set stores all derived facts. With `MAX_DERIVED_FACTS = 500_000`
and binary predicates of average 20-byte key size: ~10 MB peak — acceptable for the
infra-free, in-memory design.

---

## 6. OWL BFS Coverage vs. General Engine Coverage

### 6.1 What `OwlRlReasoner` BFS Already Covers

`OwlRlReasoner.computeTransitiveClosure` (`mebn/type/owl/OwlRlReasoner.java:161`) handles:

| OWL Rule | Pattern | Handled by BFS? |
|----------|---------|-----------------|
| `prp-trp` | `owl:TransitiveProperty` forward closure | YES — full BFS over typed edges |
| `prp-spo1` | subPropertyOf propagation | NO — handled by FOL rules, but capped at 10k pairs |
| `scm-sco` | subClassOf transitivity | NO |
| `cax-sco` | class hierarchy type propagation | NO |
| General recursive Datalog | `path(X,Z) :- path(X,Y), edge(Y,Z)` | NO |

The BFS is O(V+E) per transitive property and correctly handles the transitive closure
without the pair-cap. It calls `graph.outgoing(entityId)` directly (adjacency-list traversal),
which is exactly what the `RecursiveQueryEngine` EdbProvider should also use for edge predicates.

The BFS approach is the **right precedent** for graph-structure-aware recursion. The
`RecursiveQueryEngine` generalises it: instead of hard-coding BFS for one axiom pattern,
it accepts any Datalog rule set and applies the semi-naive delta pattern.

### 6.2 What the General Engine Adds

| Capability | OWL BFS | `RecursiveQueryEngine` |
|---|---|---|
| Transitive property closure | YES | YES (via rule `path(X,Z):-path(X,Y),edge(Y,Z)`) |
| Multi-hop ancestor queries | NO (BFS is per-property) | YES |
| Rules combining multiple predicates | NO | YES |
| Mutual recursion (`a :- b, b :- a`) | NO | YES |
| Stratified negation | NO | YES |
| Rules over PSL-derived soft truths | NO | YES (via EdbProvider over InferredFactStore) |
| Magic-set top-down optimization | NO | Future extension |

The OWL BFS and the `RecursiveQueryEngine` are **complementary**: the BFS remains for
OWL-RL axiom compilation (it is correctly wired into the OwlRlReasoner's result pipeline);
the new engine handles general user-supplied recursive rule sets.

### 6.3 Integration Point

`OwlRlReasoner.reason` currently calls `folService.inferFacts(workingGraph, ruleSet)`
(`mebn/type/owl/OwlRlReasoner.java:125`) for non-transitive OWL rules. For rules that
require recursion (e.g. `scm-sco` for subclass hierarchy propagation), a future step
can route those rules through `RecursiveQueryEngine` before passing results to `HlMrfMapInference`.
The `workingGraph` passed to `inferFacts` already incorporates BFS transitive closure
(step 2 in `OwlRlReasoner.reason`) — so the recursive engine would receive a graph that
already has direct transitive edges materialised.

---

## 7. Interaction with Existing Infrastructure

### 7.1 `ConjunctiveQueryEngine` Relationship

`ConjunctiveQueryEngine` (`fol/grounding/ConjunctiveQueryEngine.java`) is the P0-2 query
primitive: it looks up atoms already in `InferredFactStore` and performs backtracking
conjunctive join over them. It does NOT fire rules.

`RecursiveQueryEngine` is a rule-firing engine: given Datalog rules and an EDB, it derives
new facts to a fixpoint. Its output (derived `InferredFact` rows) is then queryable via
`ConjunctiveQueryEngine`.

The two engines are **cleanly separable**: `RecursiveQueryEngine` writes to a
`WorkingFactSet`, which is merged into `InferredFactStore` after the fixpoint converges.
`ConjunctiveQueryEngine` then operates over the merged store as before.

The `backtrack` method in `ConjunctiveQueryEngine` (`fol/grounding/ConjunctiveQueryEngine.java:164`)
and the join kernel needed by `RecursiveQueryEngine` are identical in algorithmic terms.
Recommended: extract a package-private `JoinKernel` utility class with a single static method:

```java
// fol/grounding/JoinKernel.java
static void backtrack(List<AtomPattern> conjuncts,
                      Map<String, List<InferredFact>> byPredicate,
                      int idx, Map<String, String> binding, double minConf,
                      List<QueryBinding> out, int limit)
```

Both `ConjunctiveQueryEngine` and `RecursiveQueryEngine` delegate to it. The refactor is
a pure internal reorganisation with no public API change.

### 7.2 `IncrementalGrounder` Relationship

`IncrementalGrounder` (`psl/IncrementalGrounder.java:45`) handles incremental PSL grounding
when atoms are added or removed. It operates at the PSL program level, not the Datalog level.
The `RecursiveQueryEngine` runs before PSL grounding: its derived facts are added to the
`PslProgram` as observed atoms, then `IncrementalGrounder.addAtom` triggers a re-ground
of affected PSL rules. The sequence is:

```
1. RecursiveQueryEngine.evaluate(rules, edbProvider) → WorkingFactSet
2. WorkingFactSet.mergeInto(InferredFactStore)
3. IncrementalGrounder.addAtom(new derived atoms)
4. HlMrfMapInference.solve(program)
```

### 7.3 `PslProgram.groundInto` Relationship

`PslProgram.groundInto` (`psl/PslProgram.java:394`) is used for grounding non-recursive
PSL rules. It is NOT changed by this design. The `RecursiveQueryEngine` is a separate
evaluation path that feeds facts into PSL as inputs, not a replacement for PSL grounding.

### 7.4 `FolInferenceService.buildPairs` — The Fix

For non-recursive rules, `buildPairs` can remain as-is (the 10k cap is still valid as
a guard against accidental explosion). For explicitly recursive rules (rules where a head
predicate also appears in the body), the caller routes through `RecursiveQueryEngine`
instead of `FolInferenceService`. A `FolRule.isRecursive()` predicate can detect this:

```java
// FolRule
public boolean isRecursive() {
    // A rule is recursive if its consequent predicate name also appears
    // as a predicate in its antecedent expression.
    return antecedent().predicatesReferenced().contains(consequent().predicateName());
}
```

---

## 8. Public API Shape

```java
package ai.kompile.graph.reasoning.fol.grounding;

/**
 * Semi-naive fixpoint evaluator for recursive Datalog rules over a knowledge base.
 *
 * <p>This is the Tier-1 crisp grounding layer that runs before PSL MAP inference.
 * Recursive rules (rules whose head predicate appears in their body) are routed here
 * rather than through {@link ai.kompile.graph.reasoning.fol.FolInferenceService}
 * to avoid the 10,000-pair grounding cap and enable correct fixpoint convergence.</p>
 *
 * <p>Infra-free: no Spring, no JPA, no external dependencies beyond those already in
 * the {@code kompile-graph-reasoning} module.</p>
 */
public final class RecursiveQueryEngine {

    public static final int DEFAULT_MAX_ROUNDS       = 10_000;
    public static final int DEFAULT_MAX_DERIVED_FACTS = 500_000;

    /**
     * Evaluate a set of Datalog rules to fixpoint using semi-naive bottom-up evaluation.
     *
     * @param rules       the rule set to evaluate (may include recursive rules)
     * @param edb         provider of base-relation tuples (EDB + materialized IDB)
     * @param maxRounds   termination guard on fixpoint iterations
     * @param maxFacts    termination guard on total derived facts
     * @return the set of all derived facts, partitioned by predicate
     */
    public static FixpointResult evaluate(List<DatalogRule> rules,
                                          EdbProvider edb,
                                          int maxRounds,
                                          int maxFacts) { ... }

    /** Convenience overload using default bounds. */
    public static FixpointResult evaluate(List<DatalogRule> rules, EdbProvider edb) {
        return evaluate(rules, edb, DEFAULT_MAX_ROUNDS, DEFAULT_MAX_DERIVED_FACTS);
    }

    /** Result of fixpoint evaluation. */
    public record FixpointResult(
        Map<String, Set<List<String>>> derivedFacts,  // predicate → set of ground tuples
        int roundsCompleted,
        boolean isComplete,   // false if terminated by a guard
        String terminationReason
    ) {}

    /** A Datalog rule: head + body conjuncts. */
    public record DatalogRule(
        String headPredicate,
        List<String> headArgs,  // variables only in head
        List<RuleAtom> body
    ) {}

    /** A body conjunct (atom), optionally negated. */
    public record RuleAtom(String predicate, List<String> args, boolean negated) {}

    /** EDB (base relation) provider. */
    @FunctionalInterface
    public interface EdbProvider {
        List<List<String>> tuplesFor(String predicate);
    }
}
```

---

## 9. Mapping to Existing File Layout

| New / Modified | Path | Action |
|---|---|---|
| `RecursiveQueryEngine.java` | `fol/grounding/RecursiveQueryEngine.java` | NEW |
| `JoinKernel.java` | `fol/grounding/JoinKernel.java` | NEW (extracted from `ConjunctiveQueryEngine.backtrack`) |
| `ConjunctiveQueryEngine.java` | `fol/grounding/ConjunctiveQueryEngine.java` | MODIFY: delegate to `JoinKernel` |
| `FolInferenceService.java:310` | `fol/FolInferenceService.java` | MODIFY: detect recursive rules, route to `RecursiveQueryEngine` |
| `FolRule.java` | `fol/FolRule.java` | MODIFY: add `isRecursive()` predicate |

No changes to PSL engine, Bayesian engine, MEBN, or any Spring layer.

---

## 10. References

1. **Green, Huang, Loo, Nash — "Datalog and Recursive Query Processing" (FTTDB 2013)**
   https://mwhittaker.github.io/papers/html/green2013datalog.html
   _Canonical survey of semi-naive evaluation, stratified negation, magic sets, and
   provenance for Datalog. Primary algorithmic reference for this design._

2. **Wisc CS838 Lecture 8 — Naive and Semi-Naive Evaluation**
   https://pages.cs.wisc.edu/~paris/cs838-s16/lecture-notes/lecture8.pdf
   _Delta-rewriting algorithm and complexity analysis cited in Section 1.3._

3. **Wisc CS784 Lecture 9 — Negation in Datalog**
   https://pages.cs.wisc.edu/~paris/cs784-s17/lectures/lecture9.pdf
   _Stratification conditions and well-founded semantics cited in Section 1.4._

4. **Berkeley CS294-260 Datalog Lecture (2024)**
   https://inst.eecs.berkeley.edu/~cs294-260/sp24/2024-02-05-datalog
   _Termination guarantees and fixpoint conditions cited in Section 1.2._

5. **Van Gelder, Ross, Schlipf — "The Well-Founded Semantics for General Logic Programs" (J. ACM 1991)**
   https://dl.acm.org/doi/10.5555/107720.107722
   _Authority for three-valued WFS fallback for non-stratifiable programs (Section 4.1)._

6. **Soufflé Incremental Evaluation (PPDP 2021)**
   https://souffle-lang.github.io/pdf/ppdp21incremental.pdf
   _Production-scale implementation of semi-naive bottom-up evaluation with delta structures;
   basis for complexity claims in Section 5.2._

7. **Soufflé Documentation — Recursive Datalog**
   https://souffle-lang.github.io/docs.html
   _SCC-based stratification, aggregation in strata, and magic-set transformations._

8. **RDFox Documentation — Reasoning**
   https://docs.oxfordsemantic.tech/reasoning.html
   _Incremental materialization with cascading retraction; basis for understanding
   the production-grade interaction between recursive materialization and PSL inference._

9. **Bach, Broecheler, Huang, Getoor — "Hinge-Loss Markov Random Fields and Probabilistic Soft Logic" (JMLR 2017)**
   https://jmlr.org/papers/volume18/15-631/15-631.pdf
   _Authority for the grounding-first / PSL-MAP-after architecture described in Section 2._

10. **Dodisturb.me — "The Essence of Datalog"**
    https://dodisturb.me/posts/2018-12-25-The-Essence-of-Datalog.html
    _Accessible explanation of semi-naive evaluation and fixpoint semantics._

11. **Existing code — `OwlRlReasoner.computeTransitiveClosure`**
    `kompile-app/kompile-data/kompile-graphs/kompile-graph-reasoning/src/main/java/
    ai/kompile/graph/reasoning/mebn/type/owl/OwlRlReasoner.java:161`
    _Precedent for recursion-by-traversal: BFS over `ReasoningGraph.outgoing()` for `prp-trp`._

12. **Existing code — `FolInferenceService.buildPairs`**
    `kompile-app/kompile-data/kompile-graphs/kompile-graph-reasoning/src/main/java/
    ai/kompile/graph/reasoning/fol/FolInferenceService.java:310`
    _The 10,000-pair cap being replaced for recursive rules._

13. **Existing code — `ConjunctiveQueryEngine.backtrack`**
    `kompile-app/kompile-data/kompile-graphs/kompile-graph-reasoning/src/main/java/
    ai/kompile/graph/reasoning/fol/grounding/ConjunctiveQueryEngine.java:164`
    _Backtracking join kernel to be extracted into `JoinKernel` and reused by
    `RecursiveQueryEngine`._

---

## 11. Open Questions

1. **Magic-set / top-down optimization**: Semi-naive is bottom-up — it derives all
   consequences of all rules, not just those needed for a specific query. For queries
   that only need a small slice of the fixpoint (e.g. `path(Alice, ?X)`), magic-set
   transformation rewrites the rule set to evaluate top-down, materializing only the
   relevant sub-graph. Should `RecursiveQueryEngine` include magic-set as an optional
   mode, or is full materialization acceptable given the `MAX_DERIVED_FACTS` bound?
   Magic-set is O(query + selectivity); full materialization is O(graph). For large
   sparse graphs with selective queries, magic-set could be a 10×–100× speedup.

2. **Interaction with `IncrementalGrounder` on retraction**: The `BeliefReviser`
   (`tms/BeliefReviser.java:96`) retracts facts and triggers re-solve. After retraction,
   derived facts that depended on retracted base facts must also be un-derived. The
   `RecursiveQueryEngine` output is a full materialization — retraction requires
   re-running the fixpoint from scratch over the updated EDB (no incremental retraction
   in the current design). Should the engine maintain provenance (why each fact was
   derived) to support incremental retraction? This is the standard "deletion
   propagation" problem and is significantly harder than forward derivation.

3. **Arity beyond binary**: The current design handles any arity (rule atoms can have
   any number of arguments). The adjacency-list index optimization in Section 3.5
   assumes binary predicates. For ternary+ predicates, the index must key on the first
   argument or use a multi-key structure. Is ternary recursion needed for the known
   use cases (transitive closure, ancestor, type propagation are all binary)?

4. **Thread safety**: The design says `RecursiveQueryEngine` is stateless (like
   `ConjunctiveQueryEngine`). But the `WorkingFactSet` accumulator is not. If multiple
   crawl jobs call `RecursiveQueryEngine` concurrently, each should get a fresh
   `WorkingFactSet`. The `evaluate` method returns a `FixpointResult` (immutable
   value), so concurrent calls to `evaluate` are safe; merging results into
   `InferredFactStore` requires the existing external synchronization contract already
   documented in `IncrementalGrounder`.

5. **PSL recursive rules**: The split in Section 2 routes crisp derivable rules to
   `RecursiveQueryEngine` and soft-truth rules to PSL. But some rules are both
   (e.g., `0.8: path(X,Z) & edge(Y,Z) -> State(X)`). The `isRecursive()` predicate
   in Section 7.4 only checks whether the head predicate appears in the body. The
   interaction between soft-weight PSL rules and crisp recursive derivation needs a
   more precise rule-classification scheme: "does this rule derive IDB facts that
   need to be input to other rules, or does it assign soft-truth scores to open-world
   atoms?" These two purposes should be cleanly separated at the rule authoring level.
