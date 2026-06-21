# Incremental-Cascade Reasoning Algorithm — L3 Design

**Status:** Design (pre-implementation)  
**Scope:** L3 layer of the agent-grounding infrastructure  
**Last updated:** 2026-06-21  
**Related:** `agent-grounding-infrastructure-design.md` §L3 (line 421), `temporal-reasoning-attribution-design.md`

---

## 1. Problem Statement

The agent's grounded knowledge base (`InferredFactStore`) must stay current with a graph that mutates from three distinct sources:

| Source | Spring event | Example |
|---|---|---|
| Crawl/extraction | `GraphChangesetCompletedEvent` | Batch of nodes+edges after `UnifiedCrawlJob` |
| Channel/email | `ChannelMessageReceivedEvent` → `GraphChangesetCompletedEvent` | Single email parsed into person+mentions edges |
| Agent assert | `AgentFactAssertedEvent` (planned) | `ask_graph_assert("trusts(Alice, Bob)", 0.9)` |

A naive approach — re-running full inference over the entire fact sheet after every delta — is prohibitively expensive once a fact sheet accumulates thousands of atoms. The incremental-cascade algorithm solves this by restricting re-inference to the **affected neighborhood** of the delta, propagating results forward until convergence, and writing only new `InferredFact` versions rather than recomputing everything.

---

## 2. Algorithm Choice: Hybrid Semi-Naive + Justification-Based TMS

### 2.1 The options

**Option A — Pure semi-naive Datalog-delta evaluation:** On a delta `∆EDB`, compute `∆IDB` by evaluating only rules whose body intersects `∆EDB`. Repeat with `∆IDB` as the new delta until no new facts are derived. Standard for Datalog; convergence in at most `depth(ruleset)` rounds.

**Option B — Pure justification-based TMS:** Track, for every inferred fact, the set of base facts and rules that justify it (`JustificationIndex`). On delta, find all inferences that are directly or transitively affected, retract them via `BeliefReviser`, and re-run inference only over the now-unsupported atoms.

**Option C — Hybrid (chosen):** Use semi-naive delta propagation for the **forward chain** (additions and updates) and `JustificationIndex`-based retraction for the **backward invalidation** (deletions and corrections). This is the correct choice here because:

- The PSL MAP inference (`ScalarHlMrfInference`) is not a Datalog engine — it is a continuous optimization problem over soft-truth values. Pure Datalog semantics do not apply.
- `JustificationIndex` (`:tms/JustificationIndex.java:55`) already encodes the dependency structure; it is the right structure to identify which inferences are affected.
- `IncrementalGrounder.regroundForPredicate` (`:psl/IncrementalGrounder.java:195`) already implements predicate-scoped re-grounding — the mechanism for semi-naive delta propagation within the PSL layer.
- For additions/updates, the forward propagation is bounded by the depth-2 subgraph of the changed atoms (controlled by `KnowledgeGraphReasoningAdapter.maxDepth`).
- For deletions/corrections, `BeliefReviser.retract` (`:tms/BeliefReviser.java:51`) uses `JustificationIndex.solelyDependentOn` to identify the minimal set of inferences to invalidate.

### 2.2 Decision

> **Use hybrid semi-naive forward + justification-based retraction, with the PSL MAP solver as the re-inference step on each affected atom cluster.**

---

## 3. Incremental Algorithm — What Exactly Is Recomputed

### 3.1 Definitions

- **Delta `∆`**: the set of atom keys added, updated, or deleted in a single changeset or assert event.
- **Affected atom set `A(∆)`**: the union of (a) the delta atoms themselves and (b) all `InferredFact` atom keys that depend on any delta atom, as reported by `JustificationIndex.atomsDependingOnFact(key)` for each key in `∆`. This is the **dependency frontier**.
- **Unsupported set `U(∆)`**: the subset of `A(∆)` for which `JustificationIndex.solelyDependentOn(key)` returns truthy for any deleted/retracted key in `∆` — i.e., inferences that become vacuously unsupported when the delta removes a base fact they depended on exclusively.
- **Weakened set `W(∆)`**: `A(∆) \ U(∆)` — inferences that retain at least one alternative justification but whose confidence value must be recomputed.

### 3.2 Full algorithm (per delta event)

```
INPUTS:
  factSheetId    — identifies the FactSheetKbState to update
  ∆_add          — set of (atomKey, value, validTime) being asserted/updated
  ∆_del          — set of atomKey being retracted

PRECONDITION: caller holds write lock on FactSheetKbState for factSheetId.

STEP 1 — RETRACTION (deletions and corrections):
  for each key in ∆_del:
    revisionResult = BeliefReviser.retract(key, factStore, justificationIndex)
    // BeliefReviser.retract (:tms/BeliefReviser.java:51):
    //   - removes key from FactStore
    //   - identifies solelyDependentOn atoms → these MUST be retracted from InferredFactStore
    //   - identifies weakenedAtoms → these need re-inference
    mark U ← U ∪ revisionResult.unsupported()
    mark W ← W ∪ revisionResult.weakened()

  for each atomKey in U:
    inferredFactStore.purge(atomKey)    // :fol/InferredFactStore.java:78

STEP 2 — ASSERTION (additions and updates):
  for each (atomKey, value, validTime) in ∆_add:
    factStore.assertFact(new Fact(atomKey, value, true, validTime))
    IncrementalGrounder.addAtom(toPslAtom(atomKey), value)
    // IncrementalGrounder.addAtom (:psl/IncrementalGrounder.java:96):
    //   calls program.observe() + regroundForPredicate(atom.predicate())
    //   regroundForPredicate only creates new ground rules for predicates touched by this atom
    add atomKey to affectedPredicates set

STEP 3 — SCOPE COMPUTATION:
  // Map changed atom keys to graph node IDs for subgraph extraction
  seedNodes = atomKeysToNodeIds(∆_add ∪ W, factSheetId)
  // Pull depth-2 BFS neighbourhood (the ACTIVATION_THRESHOLD = 0.1 in EntailmentEngine
  // means facts with body-truth < 0.1 never activate a rule, so depth-2 is sufficient
  // to capture all transitively affected rules for typical PSL programs)
  subgraph = KnowledgeGraphReasoningAdapter.subgraph(seedNodes, maxDepth=2)
  affectedAtomKeys = ∆_add ∪ ∆_del ∪ W ∪ atomKeysFromSubgraph(subgraph)

STEP 4 — RE-INFERENCE (MAP solve, scoped):
  // Only the ground rules that reference affectedAtomKeys need solving.
  // IncrementalGrounder.groundRules() already returns the minimal set
  // after steps 2 has updated the grounding via regroundForPredicate.
  result = ScalarHlMrfInference.solve(state.pslProgram())
  // This is a full MAP solve over the UPDATED program, but the program's ground rules
  // are now scoped by IncrementalGrounder to only those touching affected predicates.
  // For most deltas (single email, single assert), ground rule count is O(|subgraph|²),
  // not O(|factSheet|²).

STEP 5 — MATERIALIZATION:
  runId = UUID.randomUUID()
  newFacts = EntailmentEngine.entailFromPslResult(
                 program, result, factStore, runId)
  // :fol/EntailmentEngine.java:203
  // Produces InferredFact records with supportingFactKeys + supportingRuleIds

  for each fact in newFacts where fact.atomKey() ∈ affectedAtomKeys:
    inferredFactStore.store(fact)
    // :fol/InferredFactStore.java:39
    // MUST assign version > any existing version for same atomKey (SPI contract line 22)
    // Implementation: InMemoryInferredFactStore uses AtomicLong per atomKey

STEP 6 — JUSTIFICATION INDEX UPDATE:
  // Rebuild JustificationIndex from the new result, scoped to affectedAtomKeys
  // The full index can be expensive; use the incremental form:
  // For each fact in newFacts, update atomToRules + atomToFacts + factToAtoms
  // for only the atoms in affectedAtomKeys.
  justificationIndex = JustificationIndex.build(result, factStore)
  // :tms/JustificationIndex.java:55
  // For now: full rebuild (acceptable for subgraph-scoped MAP result);
  // future: incremental merge (see Open Questions §9.1)

STEP 7 — CONTRADICTION SCAN:
  contradictions = ContradictionDetector.detect(result, 1e-6)
  // :tms/ContradictionDetector.java:59
  if contradictions is non-empty:
    publishContradictionEvent(factSheetId, contradictions)
    // Does NOT halt cascade — contradictions are flagged for review
    // (ContradictionResolutionStrategy in kompile-knowledge-graph handles resolution separately)

STEP 8 — GROUNDING REFRESH:
  // Notify KbGroundingService that the InferredFactStore for factSheetId is now at
  // a new epoch. Any in-flight verify() calls that started before this epoch
  // are still valid (they saw a consistent snapshot via ConcurrentFactStore.snapshot()).
  kbGroundingService.markEpoch(factSheetId, runId)
```

### 3.3 Scope classification per event type

| Event | ∆_add | ∆_del | Cascade scope | MAP solver scope |
|---|---|---|---|---|
| Crawl (`GraphChangesetCompletedEvent`) | All new node/edge atoms from changeset | None (crawl only adds) | `FULL_FACTSHEET` (large delta) | Full program after full re-grounding |
| Channel/email (`ChannelMessageReceivedEvent` → changeset) | New atoms from extraction | None | `FULL_FACTSHEET` (treated as a crawl) | Full program |
| Agent assert (`AgentFactAssertedEvent`) | Single atom + value | Superseded prior version | `DELTA_ATOMS` | Scoped to depth-2 neighbourhood |
| Agent retraction | None | Single atom key | `DELTA_ATOMS` | Scoped to weakened set |

---

## 4. Cascade Propagation and Convergence

### 4.1 Why a multi-round cascade is needed

A single round of re-inference may produce new `InferredFact`s whose atom keys are themselves body atoms in other PSL rules — i.e., derived facts that derive further facts. This is the cascade. In Datalog this is well-understood; in PSL/HL-MRF it is more subtle because soft-truth values propagate continuously.

**Example:** Rule `0.8: knows(A,B) ∧ mentions(A,X) → relevant(X, FactSheet)`. If `mentions` is newly derived (not a base fact), the rule can only fire after `mentions` is materialized in the `InferredFactStore`.

### 4.2 Cascade rounds

The algorithm runs in **synchronous rounds within a single MAP solve** (not across multiple MAP solves), because `ScalarHlMrfInference` operates on the full (scoped) ground program at once. The "cascade" across derived predicates is handled by the ground rules that the `IncrementalGrounder` has already assembled from the PSL program structure.

However, if the grounding itself is **stratified** — i.e., some rules depend on the output of prior rules (e.g., `InferredFact`s produced by MEBN used as PSL body atoms) — then a second MAP solve round over the MEBN-derived atoms is required. This is the **inter-engine cascade**:

```
Round 1: MEBN inference over base graph facts
  → produces InferredFacts (e.g., posterior P(topic|evidence))
Round 2: PSL MAP solve with MEBN posteriors as observed atoms
  → produces InferredFacts (e.g., relevance scores incorporating posteriors)
Round 3: Contradiction check + epoch bump
```

The number of rounds equals the **stratum depth** of the rule program, which is bounded by the number of distinct predicate classes (MEBN, PSL, FOL entailment) that the program uses. In the current architecture this is at most 3 (MEBN → PSL → FOL).

### 4.3 Termination guarantee

**Claim:** The cascade terminates in at most `S` rounds, where `S` is the stratum depth of the rule program (currently S ≤ 3).

**Proof sketch:**
1. Base facts are in stratum 0 (never derived; only asserted).
2. MEBN posteriors are in stratum 1 (derived from stratum-0 facts; `MebnInferenceService` terminates by construction — it is a belief propagation pass over a finite graph).
3. PSL MAP solution is in stratum 2 (derived from strata 0+1; `ScalarHlMrfInference` solves a convex projected-gradient problem; convergence guaranteed by convexity of HL-MRF objective).
4. FOL entailment (`EntailmentEngine`) is in stratum 3 (derived from strata 0+1+2; it is a threshold-scan over the MAP result, not iterative).
5. No rule in any stratum references facts in a higher stratum (enforced by the rule-compilation step; violation = compile-time error).

Therefore the cascade is strictly monotone across strata and terminates without a fixed-point loop.

**Monotonicity under soft truth:** PSL values in [0,1] are not monotone in the classical Datalog sense (a fact with value 0.3 is "partially true"). However, the version-bump contract in `InferredFactStore` (`store` assigns version > any prior version for the same key; `latest` returns highest-version) ensures that readers always see the most recent MAP solution. There is no oscillation: each MAP solve replaces prior values atomically when the write lock is released.

### 4.4 Cascade boundary: when to stop propagating

The cascade boundary is controlled by two parameters:

- `maxDepth` on `KnowledgeGraphReasoningAdapter.subgraph` (default 2): limits how far the BFS neighbourhood extends from the changed atom's graph node. Atoms outside this radius are not re-grounded.
- `ACTIVATION_THRESHOLD = 0.1` in `EntailmentEngine` (`:fol/EntailmentEngine.java:58`): rules with `distanceToSatisfaction >= 0.1` are not activated and do not contribute to new derived facts. This naturally prunes the propagation frontier.

Together these two parameters guarantee that a single-atom delta recomputes at most `O(|ball(seed, depth=2)|²)` ground rules — quadratic in the neighbourhood size, not in the full fact sheet.

---

## 5. Worked Example: Email → Person Mentions X → Cascade

### 5.1 Scenario

An email arrives from `alice@example.com` to `bob@example.com` mentioning the entity `AcmeCorp`. The graph currently contains no edges connecting Alice, Bob, or AcmeCorp. The PSL program contains the following rules (illustrative):

```
// R1 (weight 0.9): Person receives email → Person is reachable
0.9: receivesEmail(P, Email) → reachable(P)

// R2 (weight 0.8): Person mentions entity in email → Person knows entity
0.8: receivesEmail(P, Email) ∧ mentions(Email, Entity) → knows(P, Entity)

// R3 (weight 0.7): Two persons share entity knowledge → they may be connected
0.7: knows(P1, Entity) ∧ knows(P2, Entity) → connected(P1, P2)

// R4 (weight 0.6): Reachable + connected → relevant to factSheet
0.6: reachable(P) ∧ connected(P1, P) → relevant(P1)
```

### 5.2 Step-by-step cascade

**T=0: Email arrives**

```
EmailChannelAdapter.receive(rawMimeMessage)
→ publishes ChannelMessageReceivedEvent(channelName="email-inbound",
                                         messageId="msg-001",
                                         sender="alice@example.com",
                                         content="...AcmeCorp...",
                                         targetFactSheetId=42)
```

**T=1: Graph update via channel bridge**

```
GraphUpdateChannelBridge.onChannelMessage(event)         // :channel/GraphUpdateChannelBridge.java:42
→ pipelineConfigService.getEnabledForChannel("email-inbound") → [pipeline-cfg-7]
→ hookRegistry.executeChannelMessage(ctx)
  → MultiAgentExtractionService.runExtraction(content, targetFactSheetId=42)
    → LLM extraction identifies:
        Person(alice, id=node-101)
        Person(bob, id=node-102)
        Org(AcmeCorp, id=node-103)
        Edge: alice -[RECEIVES_EMAIL]→ email-001
        Edge: bob   -[RECEIVES_EMAIL]→ email-001
        Edge: email-001 -[MENTIONS]→ AcmeCorp
    → graphService.createNode(6-arg, factSheetId=42) × 4 nodes
    → graphService.createEdge(...) × 3 edges
```

After extraction, `GraphChangeTrackingAutoConfiguration` fires:

```
GraphChangesetCompletedEvent(
    changesetId="cs-2026-001",
    factSheetId=42,
    nodesCreated=4,
    edgesCreated=3,
    nodesUpdated=0, nodesDeleted=0, edgesDeleted=0
)
```

**T=2: Cascade hook picks up the changeset**

```
GroundingCascadeHook.onChangeset(event)     // @Async("groundingCascadeExecutor")
→ scope = FULL_FACTSHEET (nodesCreated=4 > threshold)
→ orchestrator.runFull(factSheetId=42)
```

**T=3: STEP 1 — No retractions** (crawl-only changeset; `∆_del = ∅`)

**T=4: STEP 2 — Assert new base atoms into FactStore**

```
∆_add = {
    "receivesEmail(alice,email-001)" → value=1.0,
    "receivesEmail(bob,email-001)"   → value=1.0,
    "mentions(email-001,AcmeCorp)"   → value=1.0,
    "Person(alice)"                  → value=1.0,
    "Person(bob)"                    → value=1.0,
    "Org(AcmeCorp)"                  → value=1.0,
}

factStore.assertFact("receivesEmail(alice,email-001)", 1.0)
IncrementalGrounder.addAtom(PslAtom("receivesEmail","alice","email-001"), 1.0)
→ regroundForPredicate("receivesEmail")
  → groundSingleRule(R1): new GroundRule "0.9: receivesEmail(alice,email-001) → reachable(alice)"
  → groundSingleRule(R1): new GroundRule "0.9: receivesEmail(bob,email-001)   → reachable(bob)"
  → groundSingleRule(R2): new GroundRule "0.8: receivesEmail(alice,email-001) ∧ mentions(email-001,AcmeCorp) → knows(alice,AcmeCorp)"
  → groundSingleRule(R2): new GroundRule "0.8: receivesEmail(bob,email-001)   ∧ mentions(email-001,AcmeCorp) → knows(bob,AcmeCorp)"

IncrementalGrounder.addAtom(PslAtom("mentions","email-001","AcmeCorp"), 1.0)
→ regroundForPredicate("mentions")
  → R2 already grounded above; regroundForPredicate deduplicates via groundRuleDisplays set
```

**T=5: STEP 3 — Scope computation**

```
seedNodes = {node-101 (alice), node-102 (bob), node-103 (AcmeCorp)}
subgraph = KnowledgeGraphReasoningAdapter.subgraph(seedNodes, maxDepth=2)
  → includes: alice, bob, AcmeCorp, email-001, plus any depth-1/depth-2 neighbours
affectedAtomKeys = all atoms whose predicate is in
  {"receivesEmail", "mentions", "knows", "reachable", "connected", "relevant"}
  intersected with atoms grounded above
```

**T=6: STEP 4 — MAP solve (Round 1: PSL)**

`ScalarHlMrfInference.solve(program)` runs over the ground rules assembled by `IncrementalGrounder`. With all body atoms at truth=1.0, the MAP solution assigns:

```
reachable(alice)          = 0.90  (from R1, weight 0.9)
reachable(bob)            = 0.90
knows(alice, AcmeCorp)    = 0.80  (from R2, weight 0.8)
knows(bob, AcmeCorp)      = 0.80
connected(alice, bob)     = 0.70  (from R3: knows(alice,AcmeCorp) ∧ knows(bob,AcmeCorp))
relevant(alice)           = 0.54  (from R4: reachable(bob=0.90) ∧ connected(alice,bob=0.70) → 0.6 × min(0.90,0.70))
relevant(bob)             = 0.54
```

(Exact values depend on HL-MRF objective function; these are illustrative MAP approximations.)

**T=7: STEP 5 — Materialization**

```
EntailmentEngine.entailFromPslResult(program, result, factStore, runId="run-007")
→ for each activated ground rule (distanceToSatisfaction < 0.1):
    produce InferredFact records:

inferredFactStore.store(InferredFact(
    atomKey="reachable(alice)",
    value=0.90,
    confidence=0.90,
    supportingFactKeys=["receivesEmail(alice,email-001)"],
    supportingRuleIds=["0.9: receivesEmail(P,E) → reachable(P)"],
    runId="run-007",
    version=1,           // first time this atom is inferred
    inferredAt=now()
))
// ... similarly for bob, knows(alice,AcmeCorp), knows(bob,AcmeCorp),
//     connected(alice,bob), relevant(alice), relevant(bob)
```

**T=8: STEP 6 — JustificationIndex rebuild**

```
JustificationIndex.build(result, factStore)
// After build:
index.atomsDependingOnFact("receivesEmail(alice,email-001)")
  → {"reachable(alice)", "knows(alice,AcmeCorp)"}
index.solelyDependentOn("receivesEmail(alice,email-001)")
  → {"reachable(alice)"}  // knows(alice,AcmeCorp) also depends on mentions(email-001,AcmeCorp)
```

**T=9: STEP 7 — Contradiction scan**

```
ContradictionDetector.detect(result, 1e-6)
→ [] (no hard constraints violated; all soft rules satisfied)
```

**T=10: STEP 8 — Epoch bump + agent re-grounding**

```
kbGroundingService.markEpoch(factSheetId=42, runId="run-007")
```

**T=11: Agent query hits fresh grounding**

```
ask_graph_verify("knows(alice, AcmeCorp)")
→ KbVerifier.verify("knows(alice,AcmeCorp)")
→ inferredFactStore.latest("knows(alice,AcmeCorp)")
→ InferredFact(value=0.80, version=1, runId="run-007")
→ returns SUPPORTED (0.80 > KbVerifier.DEFAULT_THRESHOLD=0.5)
```

The agent's context now includes: "Alice knows AcmeCorp (confidence 0.80), inferred from email-001 via rule R2."

### 5.3 Follow-on cascade: agent asserts a correction

Two minutes later, the agent determines that Alice does NOT actually receive that email (it was a CC artifact). It calls `ask_graph_assert("receivesEmail(alice,email-001)", 0.0)`.

```
AgentFactAssertedEvent(atomKey="receivesEmail(alice,email-001)", value=0.0,
                       factSheetId=42, sessionId="sess-XYZ")

GroundingCascadeHook.onAgentAssert(event)
→ scope = DELTA_ATOMS
→ orchestrator.runDelta(42, {"receivesEmail(alice,email-001)"})

STEP 1 — RETRACTION:
  BeliefReviser.retract("receivesEmail(alice,email-001)", factStore, index)
  → solelyDependentOn = {"reachable(alice)"}  → purge from InferredFactStore
  → weakened = {"knows(alice,AcmeCorp)"}       → needs re-inference (still supported by R2 if
                                                  mentions is true, but body now partially false)

STEP 2 — ASSERTION:
  factStore.assertFact("receivesEmail(alice,email-001)", 0.0)  // overwrite with 0.0
  IncrementalGrounder.addAtom(PslAtom("receivesEmail","alice","email-001"), 0.0)

STEPS 3-4 — MAP solve (scoped to {alice, email-001, AcmeCorp} neighbourhood):
  New MAP result:
    reachable(alice)       = 0.10  (body truth = 0.0 → rule barely activates)
    knows(alice,AcmeCorp)  = 0.08  (body partially false)
    connected(alice,bob)   = 0.35  (knows(alice) degraded → R3 weakens)
    relevant(alice)        = 0.21  (reachable+connected both degraded)

STEP 5 — MATERIALIZATION:
  inferredFactStore.store(InferredFact(
      atomKey="reachable(alice)", value=0.10, version=2, runId="run-008", ...))
  inferredFactStore.store(InferredFact(
      atomKey="knows(alice,AcmeCorp)", value=0.08, version=2, runId="run-008", ...))
  // etc.

STEP 6 — Index update:
  // "reachable(alice)" now has only one remaining support: receivesEmail(alice,*) at 0.0
  // → it is effectively unsupported (value < ACTIVATION_THRESHOLD=0.1)

Next verify("knows(alice, AcmeCorp)"):
  inferredFactStore.latest("knows(alice,AcmeCorp)")
  → InferredFact(value=0.08, version=2)
  → UNSUPPORTED (0.08 < threshold 0.5)
```

This demonstrates the backward correction cascade: a single atom correction propagates to 4 dependent inferences, all updated in one MAP solve round, in O(depth-2 neighbourhood) work.

---

## 6. Per-FactSheet Serialization and Concurrency Model

### 6.1 State container: `FactSheetKbState`

Each fact sheet owns an isolated reasoning state. The container (to be implemented as an inner class or standalone record in `KbGroundingService`) holds:

```
FactSheetKbState {
    Long factSheetId
    PslProgram pslProgram               // mutable; updated by IncrementalGrounder
    IncrementalGrounder grounder         // NOT thread-safe (documented :psl/IncrementalGrounder.java:42)
    FactStore factStore                  // NOT thread-safe (:fol/FactStore.java — LinkedHashMap)
    ConcurrentFactStore concurrentStore  // thread-safe; used for snapshot reads
    InferredFactStore inferredFactStore  // SPI; InMemoryInferredFactStore uses AtomicLong per key
    JustificationIndex justificationIndex // rebuilt after each MAP solve
    ReentrantReadWriteLock lock          // write: cascade; read: verify/query
    AtomicReference<String> currentEpoch // runId of last completed cascade
}
```

`ConcurrentFactStore` (`:fol/grounding/ConcurrentFactStore.java:66`) provides the MVCC snapshot mechanism: `snapshot()` (line 163) returns an immutable `Snapshot(facts, version)` at the moment it is called. Verify calls (read path) operate on this snapshot and are therefore non-blocking even while a cascade write is in progress.

### 6.2 Write serialization

The cascade executor (`groundingCascadeExecutor`) is a **per-factSheet single-threaded executor** — one `ExecutorService` per `factSheetId` stored in a `ConcurrentHashMap<Long, ExecutorService>` in `GroundingCascadeHook`. This gives serialization without a global lock.

```
GroundingCascadeHook.schedule(factSheetId, scope):
  executor = executors.computeIfAbsent(factSheetId,
                 id -> Executors.newSingleThreadExecutor(
                           namedThread("grounding-cascade-" + id)))
  executor.submit(() -> {
      state.lock.writeLock().lock()
      try {
          orchestrator.run(factSheetId, scope, delta)
      } finally {
          state.lock.writeLock().unlock()
      }
  })
```

If a second cascade arrives while one is running (e.g., two emails arrive within milliseconds), the second task is queued in the executor and runs after the first completes. Because each cascade produces a **full MAP solve over the current state** (not an incremental patch on the previous result), the second cascade automatically incorporates the first cascade's changes via `FactStore`. No merging logic is needed.

**Rejection policy:** If the per-factSheet executor queue depth exceeds a configurable bound (default: 8), new cascade requests are **merged into the pending task** (if the pending task has not yet started) by enlarging `∆_add` / `∆_del`. If the pending task has already started, the overflow request is dropped and rescheduled for the next natural event (lazy convergence). This is acceptable because the cascade is self-correcting: the next incoming event will trigger a fresh cascade that covers the missed delta.

### 6.3 Read parallelism

`KbVerifier.verify` and `ConjunctiveQueryEngine.query` both acquire `state.lock.readLock()`. Many reads can proceed concurrently while no write is running. Because `inferredFactStore.latest(atomKey)` reads a `ConcurrentHashMap` under a snapshot key, it is safe without the read lock in most cases; the read lock is held only to ensure the `currentEpoch` reference is stable during a compound query (multiple `verify` calls that must see a consistent state).

### 6.4 Composition with `ConcurrentFactStore`

`ConcurrentFactStore` (`:fol/grounding/ConcurrentFactStore.java`) serves a **different purpose** from `FactStore`: it is the write-side buffer for **concurrent agent asserts** that arrive before the cascade executor picks them up. When `AgentFactAssertedEvent` fires, the fact is first written to `ConcurrentFactStore.assertFact(fact, expectedVersion)` using optimistic MVCC (line 102). If two agents assert conflicting values for the same atom simultaneously, one gets `CONFLICT = -1L` and must retry. The winning assert then flows into the cascade as a single delta.

```
Lifecycle:
  Agent assert → ConcurrentFactStore (optimistic write, MVCC)
                      ↓ snapshot()
  GroundingCascadeHook reads snapshot → runs cascade → writes FactStore + InferredFactStore
                      ↓
  ConcurrentFactStore.applyTo(factStore)    // :fol/grounding/ConcurrentFactStore.java:199
                      ↓
  FactStore is now authoritative for MAP solve
```

This two-phase design separates **concurrent agent writes** (handled by `ConcurrentFactStore`) from **sequential cascade execution** (handled by `FactStore` + `IncrementalGrounder`, neither of which is thread-safe).

---

## 7. New Fact Versions: Superseding Without Full Re-Inference

### 7.1 Version contract

The `InferredFactStore` SPI mandates (`:fol/InferredFactStore.java:22–27`):

> `store(InferredFact)` MUST assign a version strictly greater than any existing version for the same `atomKey`. `latest(atomKey)` returns the highest-versioned fact.

The `InferredFact` record (`:fol/InferredFact.java:47`) carries `long version` and `Instant inferredAt`. The `runId` field identifies which cascade produced the fact.

### 7.2 Version emission rules

When STEP 5 (materialization) stores a new `InferredFact`:

1. **If `inferredFactStore.latest(atomKey)` returns `Optional.empty()`** → this is a new atom; version=1.
2. **If `latest(atomKey).value()` differs from the new value by more than `ε=0.001`** → store new version (version = prior + 1). This records a meaningful confidence change.
3. **If `latest(atomKey).value()` is within `ε` of the new value** → **do not store a new version**. The atom is stable; no version bump is emitted. This is critical for convergence: it means that a cascade that produces no meaningful value changes terminates immediately at STEP 5 without any writes.

Rule 3 is the **fixed-point termination condition** at the InferredFact level. Combined with the stratum-bound termination guarantee (§4.3), the cascade terminates in at most `S` MAP solve rounds and produces at most `|affectedAtomKeys|` new version writes — only for atoms whose value actually changed.

### 7.3 History and provenance

`inferredFactStore.history(atomKey)` (`:fol/InferredFactStore.java:56`) returns all versions in ascending order. The `supportingFactKeys` and `supportingRuleIds` fields in each `InferredFact` version form a **derivation trace**. The `DerivationTree` utility (`:fol/grounding/DerivationTree.java:87`) reconstructs the full derivation tree from the latest version's support links, with cycle detection via an ancestor set.

When an agent asks "why does the system believe `knows(alice, AcmeCorp)`?", `DerivationTree.build("knows(alice,AcmeCorp)", store, index)` returns:

```
knows(alice,AcmeCorp) [0.80, version=1, run-007]
  ├── receivesEmail(alice,email-001) [1.0, observed, crawl-001]
  │       via rule: "0.8: receivesEmail(P,E) ∧ mentions(E,X) → knows(P,X)"
  └── mentions(email-001,AcmeCorp) [1.0, observed, crawl-001]
```

This trace is available without re-running inference — it is stored in `supportingFactKeys` / `supportingRuleIds` at materialization time.

### 7.4 Temporal validity on versions

Per the temporal-reasoning-attribution design, `InferredFact` will gain optional `@Nullable Instant validFrom` and `@Nullable Instant validUntil` fields (`:fol/InferredFact.java:47`, planned). A version supersedes its predecessor's temporal validity: if v1 has `validFrom=T0, validUntil=null` and v2 is stored at `T1`, the system implicitly sets `v1.validUntil = T1` (closed interval) and `v2.validFrom = T1, validUntil = null` (open). This means `latest(atomKey)` always returns the currently valid version, and `history(atomKey)` gives a complete temporal audit trail without re-inference.

---

## 8. Component Wiring Summary

```
Event Sources:
  UnifiedCrawlJob ─────────────────────────────────────────────┐
  GraphUpdateChannelBridge (email/Slack/etc) → changeset event ─┤
  KbGroundingService.assertFact (agent) → AgentFactAssertedEvent┘

                                    ↓
  GroundingCascadeHook  [@EventListener @Async]
  per-factSheet single-threaded executor

                                    ↓
  IncrementalReasoningOrchestrator.runDelta / runFull
  (new @Service in kompile-knowledge-graph)

        ↓ write lock             ↓ read only
  FactSheetKbState              FactSheetKbState
  IncrementalGrounder           InferredFactStore.latest()
  FactStore                     ConjunctiveQueryEngine.query()
  ScalarHlMrfInference          KbVerifier.verify()
  EntailmentEngine              DerivationTree.build()
  InferredFactStore.store()
  JustificationIndex.build()
  ContradictionDetector.detect()
  kbGroundingService.markEpoch()

                                    ↓
  Agent tool calls: ask_graph_verify / ask_graph_query
  → see fresh InferredFact version after cascade completes
```

### Module placement

| New class | Module | Package |
|---|---|---|
| `AgentFactAssertedEvent` | `kompile-graph-change-tracking` | `ai.kompile.graphchangetracking.event` |
| `GroundingCascadeHook` | `kompile-graph-change-tracking` | `ai.kompile.graphchangetracking.hook` |
| `IncrementalReasoningOrchestrator` | `kompile-knowledge-graph` | `ai.kompile.knowledgegraph.reasoning` |
| `FactSheetKbState` | `kompile-knowledge-graph` | `ai.kompile.knowledgegraph.reasoning` |

`IncrementalGrounder`, `JustificationIndex`, `BeliefReviser`, `ContradictionDetector` (lib), `InferredFactStore`, `InferredFact`, `EntailmentEngine`, `ConcurrentFactStore`, `DerivationTree` — all already exist in `kompile-graph-reasoning`; no changes to their APIs are required for this design.

---

## 9. Open Questions

### 9.1 Incremental JustificationIndex vs full rebuild (STEP 6)

The current design rebuilds the `JustificationIndex` from scratch after each MAP solve (`:tms/JustificationIndex.java:55` factory). For large fact sheets with thousands of ground rules, this is O(|ground rules|). An incremental form — `JustificationIndex.update(previousIndex, changedAtomKeys, newResult)` — that only re-scans rules touching `affectedAtomKeys` would reduce STEP 6 from O(N) to O(|affected|). This requires a new method on `JustificationIndex` and is the primary performance open question.

### 9.2 Inter-engine cascade boundary (MEBN → PSL)

The design specifies that MEBN posteriors (stratum 1) are used as PSL body atoms (stratum 2). This requires that `InferredFact`s produced by `EntailmentEngine.entailFromMebn` are fed back into `factStore` before the PSL MAP solve in the same cascade round. The current `EntailmentEngine.entailFromMebn` signature (`:fol/EntailmentEngine.java:70`) returns `List<EntailmentRecord>` but does not write to `FactStore`. The orchestrator must materialize MEBN results into `FactStore` before calling `entailFromPslResult`. The exact sequencing (and whether this requires a `FactStore.assertFact` call or a direct `program.observe`) needs to be specified in the `IncrementalReasoningOrchestrator` implementation.

### 9.3 Cascade overflow and missed deltas

The per-factSheet executor queue drop policy (§6.2, drop if queue full) means that under very high event rates, some deltas are never cascaded. A durable **cascade ledger** — a persistent queue of pending `(factSheetId, ∆_add, ∆_del)` entries written before the executor submit — would guarantee no delta is lost. This is analogous to the crawl resumability design (`project_crawl_resumability`). Decision needed: is best-effort convergence acceptable, or is exactly-once cascade required?

### 9.4 `ScalarHlMrfInference` scope and the "full program" cost

For `FULL_FACTSHEET` cascades (crawl), STEP 4 runs `ScalarHlMrfInference.solve` over the entire program, which includes all ground rules for the fact sheet — potentially hundreds of thousands. The design assumes this is acceptable because crawls are low-frequency. If crawl changesets become large (10k+ new atoms), a **chunked MAP solve** — partitioning the ground rules by connected component of the dependency graph — would reduce per-solve cost. This is a future optimization; the current design does not require it.

### 9.5 Contradiction resolution integration

STEP 7 publishes a `ContradictionDetectedEvent` but does not block the cascade. The `ContradictionDetector` in `kompile-knowledge-graph` (`:maintenance/ContradictionDetector.java:144`) supports `LLM_JUDGE` resolution strategy, which is asynchronous. The interaction between an in-progress LLM judge resolution and a concurrent cascade (which might overwrite the contradicting atom) is not specified. A **contradiction lock** — preventing new cascades from storing to a contradicting atom until the judge resolves it — may be needed.

### 9.6 Temporal valid-time propagation in cascade

When `InferredFact` gains `validFrom`/`validUntil` fields (§7.4), the cascade must propagate temporal validity through derived facts. If base fact `receivesEmail(alice,email-001)` has `validFrom=T0, validUntil=T1`, then `knows(alice,AcmeCorp)` derived from it should inherit `validFrom=T0, validUntil=T1` (or the intersection of all supporting facts' intervals). The exact temporal propagation rule — min of valid intervals? earliest start + latest end? — is not yet specified and must be decided before implementing `InferredFact` temporal fields.

---

## 10. References

| Symbol | File | Line |
|---|---|---|
| `IncrementalGrounder` | `kompile-graph-reasoning/.../psl/IncrementalGrounder.java` | 45 |
| `IncrementalGrounder.addAtom` | same | 96 |
| `IncrementalGrounder.regroundForPredicate` | same | 195 |
| `JustificationIndex` | `.../tms/JustificationIndex.java` | 35 |
| `JustificationIndex.build` | same | 55 |
| `JustificationIndex.atomsDependingOnFact` | same | 117 |
| `JustificationIndex.solelyDependentOn` | same | 134 |
| `BeliefReviser.retract` | `.../tms/BeliefReviser.java` | 51 |
| `BeliefReviser.retractAndRevise` | same | 95 |
| `ContradictionDetector.detect` | `.../tms/ContradictionDetector.java` | 59 |
| `ContradictionDetector.detectHard` | same | 48 |
| `InferredFactStore` (SPI) | `.../fol/InferredFactStore.java` | 22 |
| `InferredFact` (record) | `.../fol/InferredFact.java` | 47 |
| `EntailmentEngine.entailFromPslResult` | `.../fol/EntailmentEngine.java` | 203 |
| `EntailmentEngine.entailFromMebn` | same | 70 |
| `EntailmentEngine.ACTIVATION_THRESHOLD` | same | 58 |
| `ConcurrentFactStore.assertFact` (MVCC) | `.../fol/grounding/ConcurrentFactStore.java` | 102 |
| `ConcurrentFactStore.snapshot` | same | 163 |
| `ConcurrentFactStore.applyTo` | same | 199 |
| `DerivationTree.build` | `.../fol/grounding/DerivationTree.java` | 87 |
| `GraphChangesetCompletedEvent` | `.../graphchangetracking/event/GraphChangesetCompletedEvent.java` | 7 |
| `GraphUpdateChannelBridge.onChannelMessage` | `.../graphchangetracking/channel/GraphUpdateChannelBridge.java` | 42 |
| `GraphRuleHook.onChangesetComplete` | `.../graphchangetracking/hook/GraphRuleHook.java` | 54 |
| `KbVerifier.DEFAULT_THRESHOLD` | `.../fol/grounding/KbVerifier.java` | 43 |
| L3 section in grounding design | `docs/architecture/agent-grounding-infrastructure-design.md` | 421 |
