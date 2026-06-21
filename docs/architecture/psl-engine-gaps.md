# PSL Engine Gap Analysis

**Date**: 2026-06-21  
**Scope**: PSL engine classes in `kompile-graph-reasoning` (`psl/` package) plus the event-attribution
copy (`kompile-event-attribution/algorithm/psl/`).  
**Relation to sibling doc**: `psl-mebn-knowledge-base-gaps.md` covers the *KB-level* gaps (findings,
truth maintenance, weight learning, persistence). This document drills into the **PSL engine itself** —
inference algorithm fidelity, rule-language completeness, and grounding — so that a follow-up
implementation agent can flesh out the engine without redoing KB-level design.  
**Excluded from scope** (already in progress elsewhere): weight learning, marginal inference,
incremental grounding.

---

## 1. Canonical PSL Reference Summary

### 1.1 Source Papers

| Paper | Key contribution |
|---|---|
| Bach, Broecheler, Huang, Getoor. *Hinge-Loss Markov Random Fields and Probabilistic Soft Logic.* JMLR 18(109):1–67, 2017. [PDF](https://jmlr.org/papers/volume18/15-631/15-631.pdf) [arXiv:1505.04406](https://arxiv.org/abs/1505.04406) | Definitive HL-MRF reference: density function, ADMM MAP inference, arithmetic rules, open/closed predicates, weight learning. |
| Bach, Huang, London, Getoor. *Hinge-loss Markov Random Fields: Convex Inference for Structured Prediction.* UAI 2013. [arXiv:1309.6813](https://arxiv.org/abs/1309.6813) | Consensus-ADMM MAP inference derivation; convergence proof; local squared-hinge subproblem solution. |
| Broecheler, Mihalkova, Getoor. *Probabilistic Similarity Logic.* UAI 2010. | Original PSL: Łukasiewicz soft-truth operators, similarity/external function atoms, rule-syntax lineage. |
| Pujara, Miao, Getoor, Cohen. *Knowledge Graph Identification.* ISWC 2013. [PDF](https://www.jaypujara.org/pubs/2013/pujara:iswc13/pujara_iswc13.pdf) | PSL for KG tasks: functional/inverse-functional/mutual-exclusion constraints, collective entity resolution, link prediction, collective classification — all jointly in one program. |
| PSL reference docs. [psl.linqs.org](https://psl.linqs.org) | Rule syntax, arithmetic rules, open/closed predicate API, external-function interface, ADMM configuration. |

### 1.2 Canonical HL-MRF and MAP Inference

The HL-MRF defines a probability density over target atom values `y ∈ [0,1]^n`:

```
P(y | x) = (1/Z(x)) · exp( -∑_r w_r · ϕ_r(x, y) )
```

where each potential `ϕ_r(x, y) = max(ℓ_r(x, y), 0)^{d_r}` with `d_r ∈ {1, 2}`.

MAP inference minimises the (negative log) energy:

```
min_{y ∈ [0,1]^n}  ∑_r w_r · max(ℓ_r(y), 0)^{d_r}
```

This is a **convex** program (non-negative weights, hinge over affine functions).

#### 1.2.1 Canonical MAP Solver: Consensus ADMM (Bach/Huang/London/Getoor, UAI 2013)

The canonical PSL solver decomposes the MAP problem via consensus-ADMM:

1. **For each ground rule r**, introduce local copies `x_r` of the atoms that appear in it.
2. **Global consensus variables** `z` (one per atom) must agree with all local copies: `x_r = z` for the atoms in rule `r`.
3. **Augmented Lagrangian**:  
   `L_ρ = ∑_r w_r · ϕ_r(x_r) + (ρ/2) ‖x_r - z + u_r‖²`  
   where `u_r` are the scaled dual variables.
4. **Iteration** (until primal/dual residuals ε_abs, ε_rel converge):
   - **x-update** (per rule, parallelisable): `x_r ← argmin_{x_r ∈ [0,1]} w_r ϕ_r(x_r) + (ρ/2)‖x_r - z + u_r‖²`  
     For squared-hinge this is a quadratic; for linear-hinge it is a soft-threshold. Both have **closed-form solutions**.
   - **z-update** (global average): `z ← mean(x_r + u_r)`, projected back to `[0,1]` for target atoms; fixed at observed value for observed atoms.
   - **Dual update**: `u_r ← u_r + x_r - z`
5. **Stopping**: primal residual `‖x - z‖` and dual residual `ρ‖z_new - z_old‖` both below `ε_abs + ε_rel · …`.

The ADMM solver used by PSL (psl.linqs.org) defaults to `ρ=1.0`, `maxIterations=25000`, `ε_abs=1e-5`, `ε_rel=1e-3`.

**Key property**: each x-update is a *closed-form* proximal step, not a line-search. Updates run in parallel across ground rules. The method scales linearly in the number of ground rules.

#### 1.2.2 Logical Rule → Distance-to-Satisfaction

For a rule `body → head` under Łukasiewicz logic:

```
bodyTruth = max(0, ∑_{i∈body+} v_i - ∑_{i∈body-}(1-v_i) - (|body| - 1))
headTruth = min(1, ∑_{i∈head+} v_i - ∑_{i∈head-}(1-v_i))
d = max(0, bodyTruth - headTruth)
ϕ = w · d^p   (p=1 linear, p=2 squared)
```

**Our implementation** (`GroundRule`) matches this formula exactly. ✓

#### 1.2.3 Arithmetic Rule → Potential

An arithmetic rule `LHS op RHS` (op ∈ {=, ≤, ≥}) becomes:

```
ℓ(y) = LHS(y) - RHS(y)    (rearranged to ℓ ≤ 0)
d = max(0, ℓ(y))
ϕ = w · d^p
```

This is the canonical form used by PSL for functional constraints, partial-functional, inverse-functional, and any linear combination constraint. **Our implementation does not support this form at all.**

### 1.3 Open vs Closed Predicates (Canonical PSL)

| Type | Meaning | CWA |
|---|---|---|
| **Closed** | All groundings of this predicate are observed. Unobserved groundings default to 0.0. | Yes |
| **Open** | Some groundings are observed; unobserved ones are target variables to be inferred. | No |

In canonical PSL, the open/closed distinction is declared at the predicate level, not per-atom. It governs which atoms are targets vs. observations during grounding. When grounding a rule, unobserved atoms of a closed predicate take value 0.0 and are not optimised over; only atoms of open predicates become target variables.

**Our implementation** manages open/closed at the atom level (`PslProgram.observed` set), which achieves the same semantics but lacks predicate-level declaration.

### 1.4 Arithmetic Rules and Functional Constraints (Canonical PSL)

PSL supports two complementary rule types:

**Logical rules** (what we implement):
```
w: Body(X) & HasLink(X, Y) -> Target(Y) ^2
```

**Arithmetic rules** (not implemented):
```
Foo(A, +B) = 1 .          // Functional: for each A, sum over B of Foo(A,B) = 1
Foo(A, +B) <= 1 .         // Partial functional: sum ≤ 1
Foo(+A, B) = 1 .          // Inverse functional: for each B, sum over A of Foo(A,B) = 1
Friends(A, B) = Friends(B, A) .  // Symmetry
w: Friends(A, +B) / |B| <= 1 {B: Nice(B)} .  // Filtered average
```

Arithmetic rules use:
- `+X` **summation variables**: aggregate over all constants binding to X
- Filter clauses `{X: Predicate(X)}`: restrict the summation domain
- Cardinality `|X|`: count of constants in the summation
- `@Min[A,B]`, `@Max[A,B]`: min/max operators
- Operators: `=`, `<=`, `>=` in the relational position
- Coefficients: `*`, `/` on terms

**Hard arithmetic constraints** (unweighted, trailing `.`) are the PSL mechanism for:
- Functional dependencies: `HasBoss(E, +M) = 1 .`
- Mutual exclusion: `HasType(X, +C) <= 1 .`
- Ontological uniqueness

Without arithmetic rules, PSL **cannot** express any of these canonical KGI constraints.

### 1.5 External Functions / Similarity Predicates

PSL supports **external function atoms** — user-defined predicates backed by Java code (implementing `ExternalFunction.getValue()`, returning `[0,1]`). These appear in rule bodies like ordinary predicates:

```
w: TextSim(A, B) & HasLabel(A, +X) -> HasLabel(B, X) ^2
```

Built-in similarity functions: Cosine, Jaro-Winkler, Levenshtein, Jaccard, Dice. These are critical for entity resolution (the `TextSim` above is a standard KGI pattern).

**Our implementation** has no external-function predicate mechanism.

---

## 2. What We Have — Engine Inventory

All classes are in:  
`kompile-app/kompile-data/kompile-graphs/kompile-graph-reasoning/src/main/java/ai/kompile/graph/reasoning/psl/`

| Class | What it does | Canonical fidelity |
|---|---|---|
| `Term` | Variable vs constant distinction by upper-case-first convention | Matches PSL convention ✓ |
| `PslAtom` | Ground/template atom; negation; `key()` identity; `parse()` | Core atom model ✓ |
| `PslRule` | Weighted/hard record; `^1`/`^2` exponent; body/head parse; `distinct` guards; `parse()` | Logical rule syntax ✓; arithmetic rules ✗ |
| `PslProgram` | Observed + target atom registry; backtracking conjunctive join grounding; 500k-rule cap | CWA at atom level ✓; predicate-level closed/open ✗; arithmetic constraints ✗ |
| `GroundRule` | Łukasiewicz `bodyTruth`/`headTruth`/`distanceToSatisfaction`; `potential()` with `p ∈ {1,2}` | HL-MRF potential formula exactly ✓ |
| `HlMrfMapInference` | Router: scalar / tensor / SGD by problem size | Routing ✓; solver algorithm ADMM ✗ |
| `ScalarHlMrfInference` | Projected gradient descent + backtracking line search | Convex solver ✓; not ADMM |
| `TensorHlMrfInference` | Same algorithm vectorised with ND4J (GPU) | Dense matrix PGD ✓; not ADMM |
| `SgdHlMrfInference` | Mini-batch SGD with decaying LR and patience | Non-canonical; sparse, fast at scale |
| `GraphPslProgramBuilder` | Builds `State/Link/Prior` atoms from `ReasoningGraph`; default propagation + abduction rules | Domain-specific builder; no arithmetic rules |

**Summary of what works correctly**:
- Łukasiewicz body/head truth formula (exact match to the canonical formula).
- Both hinge potential types (`p=1` linear, `p=2` squared) per rule.
- Hard constraints (via finite `hardWeight` penalty — a valid approximation).
- Observed vs target atom partition at atom level.
- `!=` inequality guards in rule bodies.
- Three solver backends, all converging to the global convex optimum.
- MAX_GROUND_RULES safety cap.

---

## 3. PSL Engine Gap Analysis

### GAP E-1 (H): Inference Algorithm — No ADMM; Gradient Solvers Only

**Canonical**: The MAP solver in PSL (UAI 2013, JMLR 2017) is **consensus ADMM** with closed-form
proximal x-updates, parallel execution over ground rules, and primal/dual residual stopping criteria.
Each x-update for a squared-hinge rule is a QP with a single variable — solved in O(1). For linear-hinge
it is a soft-threshold. The ADMM approach achieves **linear scalability** in the number of rules and
does not require a line search.

**We have**: Three solvers, all variants of **projected gradient descent**:
- `ScalarHlMrfInference`: full-batch PGD with backtracking line search (Armijo rule).
- `TensorHlMrfInference`: same algorithm with ND4J matrix operations.
- `SgdHlMrfInference`: mini-batch SGD with decaying learning rate and patience.

**Gap**:
1. No ADMM implementation. The gradient solvers are correct (the problem is convex, PGD also converges
   to the global optimum), but they differ from the canonical PSL algorithm in:
   - **Convergence speed**: ADMM typically converges in 200–2000 iterations; gradient descent with line
     search can take 2000+ iterations and the line search is expensive per step.
   - **Parallelism**: ADMM x-updates are naturally per-rule-parallel; the gradient solvers are sequential
     (or dense-matrix-parallel for TensorHlMrfInference).
   - **Stopping criteria**: ADMM uses principled primal/dual residuals (ε_abs, ε_rel); our solvers use
     gradient-norm and progress checks — valid but less interpretable.
   - **Hard constraints**: ADMM handles them as per-rule equality constraints with a dedicated subproblem;
     we approximate them with a large penalty weight (DEFAULT_HARD_WEIGHT = 1e6), which can cause
     ill-conditioning near the hard-constraint boundary.

**Impact**: H — The hard-constraint approximation is the main practical fidelity issue. At `hardWeight = 1e6`,
the Hessian condition number near a hard constraint can degrade convergence substantially.
Arithmetic rule constraints (Gap E-2) cannot be added without an ADMM or proximal framework that
supports linear-equality subproblems natively.

**Fix**:
- Implement `AdmmHlMrfInference` implementing `HlMrfSolver`.
- Per ground rule: analytical x-update for squared hinge (`x = clip01((z - u + w·d_0) / (ρ + 2w))`-style
  proximal step); for linear hinge, soft-threshold.
- Z-update: weighted average of local copies for each consensus variable.
- Dual update: standard `u += x - z`.
- Stopping: primal residual `r = ‖x - z‖`, dual residual `s = ρ‖z_new - z_old‖`.
- Route via `HlMrfMapInference.chooseSolver()`: use ADMM as the default for medium+ problems;
  keep `ScalarHlMrfInference` for small problems (< DEFAULT_TENSOR_THRESHOLD) where startup cost dominates.

---

### GAP E-2 (H): Arithmetic Rules — No Support

**Canonical**: PSL has two rule classes: **logical** (body→head) and **arithmetic** (linear combination
op linear combination). Arithmetic rules are the exclusive mechanism for:
- Functional constraints: `Predicate(A, +B) = 1 .` (sum over B equals 1 for each A)
- Partial-functional: `Predicate(A, +B) <= 1 .`
- Inverse-functional: `Predicate(+A, B) = 1 .`
- Symmetry: `Pred(A, B) = Pred(B, A) .`
- Mutual exclusion: `HasType(X, TypeA) + HasType(X, TypeB) <= 1 .`
- Normalisation: `∑_j Prob(Entity, j) = 1 .`

These are central to the KGI (Pujara 2013) use case: functional = "each entity has exactly one value
for this relation"; mutual exclusion = "an entity cannot simultaneously be of two contradictory types";
normalisation = "confidence scores sum to 1". **Without arithmetic rules, a PSL program cannot enforce
any of these KG-standard constraints.**

**We have**: None. `PslRule.parse()` supports only the `body -> head` implication form. The parser does
not recognise `=`, `<=`, `>=` as relational operators, summation variables (`+X`), filter clauses
(`{X: Predicate(X)}`), cardinality (`|X|`), or coefficient arithmetic.

**Gap**:
1. No `ArithmeticRule` type (or a unified `PslRule` that covers both logical and arithmetic forms).
2. No summation variable (`+X`) concept in `Term` / `PslAtom`.
3. No filter clause representation.
4. No arithmetic grounding: the grounding engine must enumerate all bindings of summation variables,
   compute the linear combination, and emit one `GroundRule` per (non-summation) binding tuple.
5. No arithmetic potential in `GroundRule`: `d = max(0, LHS - RHS)` where LHS/RHS are linear combinations
   of multiple ground atom values.
6. No `ArithmeticGroundRule` in the solver: the x-update for a linear constraint `Σ c_i x_i ≤ b` is
   a projection onto a halfspace (closed form for ADMM), very different from the logical body→head form.

**Impact**: H — Without this, PSL programs cannot express functional dependencies, mutual exclusion, or
normalisation. These are required for knowledge-graph identification and collective entity resolution.

**Fix** (build in this order):
1. Add `ArithmeticRule` record (summation-var terms, relational operator, LHS/RHS linear coefficients,
   filter predicate list, hard/soft, exponent).
2. Extend `Term` to carry a `summation` boolean and an optional `coefficient` double.
3. Add `ArithmeticGroundRule` record: stores the grounded linear combination + constant + operator.
4. Add arithmetic grounding in `PslProgram.ground()`: for each summation-variable binding combination,
   materialise the linear combination sum.
5. Add arithmetic potential: `d = max(0, ℓ(y))` where `ℓ = Σ c_i y_i - b`.
6. Extend all three solvers to handle `ArithmeticGroundRule` (gradient = linear coefficient vector when
   d > 0; ADMM x-update is a halfspace projection).
7. Parser extension in `PslRule.parse()`: recognise `=`, `<=`, `>=`; parse `+X` summation variables;
   parse `{X: Pred(X)}` filter clauses.

---

### GAP E-3 (H): Hard Constraints — Penalty Approximation vs Exact Enforcement

**Canonical**: PSL hard constraints are rules with infinite weight: they must be exactly satisfied at the
MAP solution. In the ADMM framework this is handled naturally: hard-constraint subproblems are equality
constraints that the ADMM dual variables enforce exactly in the limit.

In our implementation, hard constraints are approximated by a finite `hardWeight = 1e6`. This has two
problems:
1. **Near-constraint behaviour**: the objective is poorly conditioned near the constraint boundary,
   making gradient descent slow and potentially oscillatory.
2. **Exact satisfaction is not guaranteed**: a hard constraint whose distance-to-satisfaction is
   `1e-7` is treated as satisfied by the solver tolerance but may violate the semantic requirement.

**We have**: `GroundRule.hard()` is distinguished; `potential()` uses `hardWeight` in place of infinity;
`ScalarHlMrfInference` and `SgdHlMrfInference` treat it identically to a high-weight soft constraint.

**Gap**:
- No exact constraint projection step (e.g., project onto the feasible set defined by all hard constraints
  before/after each gradient step).
- No separate phase for hard constraints vs soft rules.
- No post-convergence feasibility check: after MAP, hard constraints with d > ε are not flagged as violations.

**Impact**: H for correctness; M for most current use cases (default rules do not use hard constraints
in practice). Becomes critical once arithmetic rules (functional, mutual-exclusion) are added, because
those will all be hard constraints.

**Fix** (partial, without full ADMM):
- Add a `HardConstraintProjector` that, after each gradient step in `ScalarHlMrfInference`/`TensorHlMrfInference`,
  projects the target atom values onto the feasible set of hard constraint inequalities (sequential least
  squares / Dykstra's algorithm for multiple constraints).
- Full fix: implement ADMM (Gap E-1), which handles hard constraints exactly as dual-enforced equalities.
- Post-inference: add `HlMrfMapInference.Result.hardConstraintViolations()` reporting ground rules where
  `hard == true && distanceToSatisfaction(finalValues) > tolerance`.

---

### GAP E-4 (M): Predicate-Level Open/Closed Declaration

**Canonical**: In PSL, open/closed is a **predicate-level** declaration, not per-atom. All atoms of a
closed predicate are observed (CWA); all atoms of an open predicate may be targets. This matters for
grounding: when a rule body references an atom of a closed predicate, any unobserved grounding is
treated as false (value 0.0) and can be skipped; when a rule references an open predicate, unobserved
groundings become new target atoms.

**We have**: `PslProgram.observed` is a per-atom set. There is no predicate-level
`closedPredicates: Set<String>` concept. Users must manually call `observe()` for every atom of a
closed predicate — if they miss one, it silently becomes a target.

**Gap**:
- No `PslProgram.declareClosed(predicateName)` method.
- No automatic CWA enforcement: when grounding a rule, atoms of closed predicates that are not in
  `atomsByKey` should be auto-added with value 0.0 and marked observed; currently they cause the
  grounding to fail (the backtracking join finds no candidates for the predicate).
- No predicate arity declaration (catches arity mismatches at rule-parse time).

**Impact**: M — The current atom-level approach works correctly if callers populate all atoms, but
it is error-prone at scale (missing a few atoms silently changes semantics).

**Fix**:
- Add `PredicateDeclaration(String name, int arity, boolean open)` to `PslProgram`.
- `declareClosed(name, arity)` adds the predicate to a `closedPredicates` set.
- During grounding, when a closed predicate atom is not found in `atomsByKey`, auto-register it with
  value 0.0 and mark observed.
- Validate atom arities at `observe()`/`target()` time against declarations.

---

### GAP E-5 (M): External Function Predicates (Similarity Atoms)

**Canonical**: PSL supports **external function atoms** — ground predicates whose value is computed
by a user-supplied function (implementing `ExternalFunction.getValue(args) -> [0,1]`) rather than
stored in the database. They are used in rule bodies as similarity features:

```
w: TextSim(A, B) & HasCandidate(A) & HasCandidate(B) -> SameEntity(A, B) ^2
```

The `TextSim(A, B)` atom is not stored; it is computed on demand during grounding.

**We have**: No `ExternalFunctionAtom` concept. All atoms must be pre-registered in `PslProgram` via
`observe()` or `target()`. There is no way to use a function call as a rule atom.

**Gap**:
- No `FunctionAtom` or `ComputedAtom` type in `PslAtom`.
- No `ExternalFunctionRegistry` in `PslProgram`.
- No hook in `PslProgram.register()` to compute function values on the fly.
- No built-in similarity functions (Cosine, Jaccard, Jaro-Winkler).

**Impact**: M — Required for collective entity resolution, the primary PSL KGI use case. Without
similarity atoms, entity resolution must be done as a pre-processing step outside PSL, losing the
benefit of joint inference.

**Fix**:
- Add `ExternalFunction` interface: `double evaluate(String... args)`.
- Add `FunctionPredicate(String name, ExternalFunction fn)` registered in `PslProgram`.
- In `PslProgram.ground()`: when a template atom's predicate is a `FunctionPredicate`, evaluate
  `fn.evaluate(binding values)` for each candidate binding and register the result as a computed
  observed atom (skipping if value = 0.0 and below a threshold).
- Provide `BuiltinSimilarityFunctions` with Cosine (on embedding vectors from `GraphEntity.embedding()`),
  Jaccard (on tag sets), and Levenshtein (on labels).

---

### GAP E-6 (M): Closed-World Assumption for Unobserved Atoms

**Canonical**: In PSL, observed (closed) predicates follow a **closed-world assumption**: any ground
atom of a closed predicate that is not explicitly asserted is treated as having value 0.0. This is how
"we have no evidence of X" becomes "X is false" during inference, without requiring explicit
registration of every false atom.

**We have**: `PslProgram.value(atomKey)` defaults to 0.0 for any unregistered key, which is
effectively CWA. However, the grounding engine (`groundInto`) skips any predicate with no registered
atoms (`if (candidates == null) return`), so an entirely absent closed predicate means the rule
silently does not ground — rather than grounding with the CWA default.

**Gap**:
- The grounding skip-on-no-candidates is correct for open predicates but wrong for closed predicates:
  if the rule body references `ClosedPred(X)` and no atoms of `ClosedPred` are registered, the rule
  should ground with `ClosedPred(x) = 0` for every candidate `x` from the other body literals.
- No distinction: both open and closed predicates are currently treated identically in grounding.

**Impact**: M — Silently wrong when a closed predicate has zero evidence (common at program startup or
for sparsely populated relations).

**Fix**: Implement in conjunction with Gap E-4 (predicate declarations). During grounding, for a body
atom whose predicate is declared closed, if no stored atom matches the binding, auto-register the atom
as observed with value 0.0 and proceed rather than pruning the branch.

---

### GAP E-7 (L): Disjunction in Rule Heads — Multiple Head Atoms

**Canonical**: PSL rule heads are **disjunctions**: `head1 | head2 | head3` — the Łukasiewicz
disjunction `min(1, Σ v_i)`. This is used for "one of these must hold" soft implication, and the
grounding emits a single `GroundRule` with multiple head literals.

**We have**: `PslRule` stores `head` as a `List<PslAtom>` and `GroundRule.headTruth()` computes
`min(1, Σ lit.value())` — the disjunction is correctly implemented. The parser recognises `|` in the
head position (`splitTop(headStr, '|')`). **This gap is actually implemented correctly.**

**Status**: No gap. ✓ (Noted here to confirm it is not missing.)

---

### GAP E-8 (L): Grounding Scalability — Join-Order Optimisation

**Canonical**: PSL uses database-style grounding with join-order optimisation: the rule body is treated
as a relational join query, and atoms are matched in an order that minimises intermediate result size
(most selective predicate first). Large PSL deployments (millions of atoms) use PostgreSQL as the
backing database and push grounding joins to the DB engine.

**We have**: `PslProgram.groundInto()` processes body atoms in the order they appear in the rule
(fixed order backtracking join). The predicate-to-atoms index is rebuilt on every `ground()` call.
`MAX_GROUND_RULES = 500_000` provides a safety cap.

**Gap**:
- No join-order optimisation (rule atoms are joined in textual order, which may be highly non-selective).
- Predicate index rebuilt on each `ground()` call (O(atoms) overhead avoidable with caching).
- No blocking: no mechanism to scope grounding to type-compatible entities (e.g., "only ground
  `HasType(X, Person)` for entities whose type tag includes `Person`").

**Impact**: L at current KG scale (< 100k atoms). Becomes M at 1M+ atoms.

**Fix**:
- Cache the predicate-to-atoms index across `ground()` calls and invalidate on `observe()`/`target()` calls.
- Add a selectivity-based join-order heuristic: sort body atoms by `|candidates_for_predicate|` ascending
  before the backtracking join.
- Add an optional `blocking` predicate declaration: `PslProgram.declareBlocking(ruleName, atomIndex, blockingSet)`.

---

### GAP E-9 (L): Negation in Rule Bodies — Correctness of Gradient Accounting

**Canonical**: Negated body literals (`~A(X)`) contribute `1 - v(X)` to the Łukasiewicz body
conjunction. The gradient of `bodyTruth` w.r.t. `v(X)` for a negated literal is `-1` (not `+1`).

**We have**:  
`GroundRule.Lit.value()` correctly returns `1 - v` for negated literals.  
`ScalarHlMrfInference.gradient()` uses `coef * (l.negated() ? -1.0 : 1.0)` for body literals. ✓  
`SgdHlMrfInference.Compiled` uses `bodySign[j] = lit.negated() ? -1.0 : 1.0`. ✓  

**Status**: No gap. Negation in body and head is correctly handled in all three solvers. ✓

---

### GAP E-10 (L): Rule Symmetry Sugar and Predicate Aliases

**Canonical PSL** supports shorthand operators:
- `A >> B` is equivalent to `A -> B` (implication).
- `A << B` is equivalent to `B -> A` (reverse implication).
- `~` and `!` are both valid negation prefixes (PSL accepts both).
- `==` and `=` in arithmetic rules are both valid for equality.
- `!=` and `~=` are both valid for inequality guards.

**We have**: `PslAtom.parse()` accepts both `~` and `!` for negation. `PslRule.parse()` uses `->` only.

**Gap**:
- `<<` (reverse implication) is not parsed.
- `>>` alias for `->` is not parsed.
- `~=` alias for `!=` is not parsed (arithmetic rule gaps subsumed in E-2).

**Impact**: L — Minor parse ergonomics. Not a semantic gap.

---

## 4. Gap Table

| ID | Canonical Feature | What We Have | Gap | Impact | Priority |
|---|---|---|---|---|---|
| E-1 | Consensus ADMM MAP inference (closed-form per-rule x-update, parallel, primal/dual stopping) | Projected gradient descent (scalar/tensor) + mini-batch SGD | No ADMM; hard constraints approximated by penalty; no parallel per-rule updates | Slow on large programs; ill-conditioned near hard constraints; diverges from canonical | **H** |
| E-2 | Arithmetic rules: summation variables (+X), filter clauses, =, <=, >= operators, cardinality | Only logical body→head rules | No arithmetic rules, no functional/inverse-functional/mutual-exclusion/normalisation constraints | Cannot express KGI constraints; cannot enforce ontological uniqueness | **H** |
| E-3 | Hard constraints: exact enforcement (infinite weight, dual enforcement in ADMM) | Finite penalty approximation (hardWeight=1e6) | Inexact; ill-conditioned; no post-convergence violation reporting | Correctness risk for KGI constraints; magnified once arithmetic rules added | **H** |
| E-4 | Predicate-level open/closed declaration; CWA auto-applied to absent closed atoms | Per-atom observed/target; absent atoms skip grounding | Missing atoms of closed predicates silently prevent rule grounding instead of applying CWA | Error-prone at scale; semantically wrong for absent closed predicates | **M** |
| E-5 | External function predicates (similarity atoms; ExternalFunction interface) | All atom values must be pre-registered | No function-backed atoms; no built-in similarity functions | Cannot do entity resolution by similarity without pre-materialising similarities | **M** |
| E-6 | CWA for closed predicates during grounding | value() defaults 0.0 but missing atoms skip grounding | Missing closed atoms prune rule groundings instead of contributing value 0.0 | Silent wrong semantics for absent evidence | **M** |
| E-7 | Disjunctive rule heads (A\|B\|C) | Correctly implemented as `min(1, Σ)` | No gap | — | ✓ |
| E-8 | Join-order optimisation; blocking; DB-backed grounding | Fixed-order backtracking; index rebuilt every call | No selectivity-based join ordering; no blocking | Quadratic blow-up at 100k+ atoms | **L** |
| E-9 | Negation in body/head with correct gradient sign | Correctly implemented in all 3 solvers | No gap | — | ✓ |
| E-10 | Parser aliases (<<, >>, ~=) | `->` and `!=` only | Minor parse ergonomics | L | **L** |

---

## 5. Prioritised Implementation Plan

Excludes weight learning, marginal inference, and incremental grounding (already in progress).

### Step 1: Arithmetic Rules + Summation Variables (Gap E-2) — First Priority

**Why first**: Arithmetic rules unblock functional constraints and mutual exclusion, which are the
missing building blocks for KGI-style PSL programs. They also define the `ArithmeticGroundRule`
abstraction that the ADMM solver (Step 2) needs to handle.

**Classes to create / modify**:

1. **`PslRule` — extend or replace with a sealed interface**:
   ```java
   sealed interface PslRule permits LogicalRule, ArithmeticRule {}
   ```
   Or add an `arithmetic` boolean + `lhsTerms`, `rhsConstant`, `relOp` fields to the existing record.
   Recommended: keep the existing `PslRule` record for logical rules and add a new `ArithmeticRule` record.

2. **`ArithmeticRule` record** (new):
   ```java
   record ArithmeticRule(double weight, boolean hard, boolean squared,
                         List<ArithmeticTerm> lhs, List<ArithmeticTerm> rhs,
                         RelOp op,                // EQ, LEQ, GEQ
                         List<FilterClause> filters) { }
   record ArithmeticTerm(String predicate, List<Term> args, double coefficient, boolean summation) { }
   record FilterClause(String variable, PslAtom condition) { }
   enum RelOp { EQ, LEQ, GEQ }
   ```

3. **`Term` — add `summation` flag**:
   ```java
   record Term(String name, boolean variable, boolean summation) { }
   ```
   Parser: `+X` → `new Term("X", true, true)`.

4. **`ArithmeticGroundRule` record** (new):
   ```java
   record ArithmeticGroundRule(double weight, boolean hard, boolean squared,
                                double[] coefficients, String[] atomKeys,
                                double rhs, RelOp op) {
       double distanceToSatisfaction(Map<String,Double> truth) { ... }
       double potential(Map<String,Double> truth, double hardWeight) { ... }
   }
   ```

5. **`PslProgram.ground()`**: handle arithmetic rules by:
   - Enumerating all bindings of non-summation variables (the outer join).
   - For each binding, collecting all groundings of summation variables.
   - Emitting one `ArithmeticGroundRule` per outer binding.

6. **Solver updates** (`ScalarHlMrfInference`, `SgdHlMrfInference`, `TensorHlMrfInference`):
   - Accept `List<ArithmeticGroundRule>` alongside `List<GroundRule>`.
   - Gradient for arithmetic rule: `coef[i]` if `d > 0` (linear constraint gradient is constant).

7. **`PslRule.parse()` extension**: recognise `+X` summation variables; recognise `=`, `<=`, `>=`
   as relational operators when the rule has no `->`.

**Estimated scope**: 4 new files + extensions to `PslRule`, `Term`, `PslProgram`, 3 solver classes.

---

### Step 2: ADMM MAP Inference (Gap E-1) — Second Priority

**Why second**: Once arithmetic rules exist, the hard-constraint approximation (Gap E-3) becomes
a real correctness issue (functional constraints must be exactly satisfied). ADMM is the correct
fix for both.

**Classes to create / modify**:

1. **`AdmmHlMrfInference` implements `HlMrfSolver`** (new):
   - Initialise: local copies `x_r` (one per atom per ground rule), consensus `z`, dual `u_r`.
   - Per iteration:
     - **x-update** per logical rule: proximal step on `w_r · d_r(x_r)^{p_r} + (ρ/2)‖x_r - z + u_r‖²`.
       For squared hinge (`p=2`): the proximal operator is an elementwise quadratic → closed-form.
       For linear hinge (`p=1`): soft-threshold → closed-form.
     - **x-update** per arithmetic rule: projection onto the halfspace `{x : Σ c_i x_i ≤/=/≥ b}` → closed-form.
     - **z-update**: for each atom, average over all local copies that reference it; clip to [0,1];
       fix to observed value for observed atoms.
     - **Dual update**: `u_r += x_r - z` for atoms in rule r.
   - **Stopping**: primal residual `‖x - z‖_F`, dual residual `ρ‖z_new - z_old‖_F` vs `ε_abs`, `ε_rel`.
   - Parameters: `rho = 1.0`, `maxIterations = 25000`, `epsilonAbs = 1e-5`, `epsilonRel = 1e-3`.

2. **`HlMrfMapInference.chooseSolver()`**: add ADMM as the default for mid- and large-scale problems;
   keep `ScalarHlMrfInference` only for very small programs (< 100 ground rules) where startup cost matters.

3. **Post-convergence violation check** in `HlMrfMapInference.Result`:
   - Add `List<GroundRule> hardViolations()` — ground rules where `hard == true && d > tolerance`.

**Estimated scope**: 1 new file (AdmmHlMrfInference, ~200 lines); minor changes to `HlMrfMapInference`,
`HlMrfSolver`, `HlMrfMapInference.Result`.

---

### Step 3: Predicate Declarations + CWA Grounding Fix (Gaps E-4, E-6)

**Why third**: Enables correct grounding semantics for absent closed predicates; prerequisite for
correctly expressing "absence of evidence" as evidence of absence in entity resolution rules.

**Classes to modify**:

1. **`PslProgram`** — add:
   ```java
   record PredicateDeclaration(String name, int arity, boolean open) {}
   private final Map<String, PredicateDeclaration> predicates = new LinkedHashMap<>();
   
   public PslProgram declareOpen(String name, int arity) { ... }
   public PslProgram declareClosed(String name, int arity) { ... }
   ```

2. **`PslProgram.groundInto()`** — when a body atom's predicate is declared closed and has no
   registered atoms matching the binding, instead of pruning the branch, auto-register a `0.0` observed
   atom and continue.

3. **Arity validation**: at `observe()`/`target()` time, if the predicate is declared, verify
   `args.length == declaration.arity`.

**Estimated scope**: ~50 lines in `PslProgram`.

---

### Step 4: External Function Predicates (Gap E-5)

**Why fourth**: Required for entity resolution and similarity-based rules.

**Classes to create / modify**:

1. **`ExternalFunction` interface** (new):
   ```java
   public interface ExternalFunction {
       int arity();
       double evaluate(String... args);  // returns [0,1]
   }
   ```

2. **`PslProgram.registerFunction(String name, ExternalFunction fn)`**.

3. **`PslProgram.groundInto()`**: when template atom predicate is a registered function, iterate over
   all candidate constant tuples (from other bound variables in the rule body), compute
   `fn.evaluate(constants)`, and if > 0, register as an observed atom and proceed.

4. **`BuiltinSimilarityFunctions`** (new):
   - `LabelSimilarity`: Jaro-Winkler on `GraphEntity.label()`.
   - `TagJaccard`: Jaccard on `GraphEntity.tags()`.
   - `EmbeddingCosine`: cosine on `GraphEntity.embedding()`.

**Estimated scope**: 1 interface + 1 utility class + ~40 lines in `PslProgram.groundInto()`.

---

### Deferred (already in progress or low priority)

| Gap | Status |
|---|---|
| Weight learning (structured perceptron, pseudolikelihood) | In progress in worktree `agent-abfe1a2aed17806bb` |
| Marginal inference (Gibbs over HL-MRF) | In progress (PslMarginalInference in worktree) |
| Incremental grounding (IncrementalGrounder) | In progress (IncrementalGrounder in worktree) |
| Collective entity resolution builder | In progress (CollectiveEntityResolution in worktree) |
| Ontology constraint emitter | In progress (OntologyConstraintEmitter in worktree) |
| Join-order optimisation (E-8) | Low; revisit at 100k+ atoms |
| Parser aliases `<<`, `>>`, `~=` (E-10) | Low ergonomic fix |

---

## 6. References

1. Bach, S., Broecheler, M., Huang, B., Getoor, L. *Hinge-Loss Markov Random Fields and Probabilistic Soft Logic.* JMLR 18(109):1–67, 2017.  
   [https://jmlr.org/papers/volume18/15-631/15-631.pdf](https://jmlr.org/papers/volume18/15-631/15-631.pdf) | [arXiv:1505.04406](https://arxiv.org/abs/1505.04406)

2. Bach, S., Huang, B., London, B., Getoor, L. *Hinge-loss Markov Random Fields: Convex Inference for Structured Prediction.* UAI 2013.  
   [arXiv:1309.6813](https://arxiv.org/abs/1309.6813)

3. Broecheler, M., Mihalkova, L., Getoor, L. *Probabilistic Similarity Logic.* UAI 2010.

4. Pujara, J., Miao, H., Getoor, L., Cohen, W. *Knowledge Graph Identification.* ISWC 2013.  
   [https://www.jaypujara.org/pubs/2013/pujara:iswc13/pujara_iswc13.pdf](https://www.jaypujara.org/pubs/2013/pujara:iswc13/pujara_iswc13.pdf)

5. PSL open-source project documentation.  
   [https://psl.linqs.org](https://psl.linqs.org) | [Rule Specification](https://psl.linqs.org/wiki/Rule-Specification.html) | [Constraints](https://psl.linqs.org/wiki/Constraints.html) | [External Functions](https://psl.linqs.org/wiki/External-Functions.html) | [Configuration Options](https://psl.linqs.org/wiki/Configuration-Options.html)

6. PSL KGI example (Python implementation with 26 rules).  
   [https://github.com/linqs/psl-examples/blob/master/knowledge-graph-identification/python/knowledge-graph-identification.py](https://github.com/linqs/psl-examples/blob/master/knowledge-graph-identification/python/knowledge-graph-identification.py)
