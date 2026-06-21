# Temporal Reasoning and Temporal Attribution — Design

**Module:** `kompile-graph-reasoning` (infra-free)
**Status:** Design only — no code changes, no Maven runs
**Audience:** Engineer implementing phase T1 next

---

## 1. Temporal Model: Bitemporal or Valid-Time?

### Decision: Valid-Time Only (with explicit open for Bitemporal later)

**Justification.**
The library already carries point timestamps on every entity and relation (`GraphEntity.timestamp()` at `model/GraphEntity.java:92`, `GraphRelation.timestamp()` at `model/GraphRelation.java:87`). These represent *event-occurrence time* — when the modelled fact held in the world. This is valid-time (VT). Transaction-time (TT, "when was this fact recorded in the system") is owned by clients: the JPA `GraphNode.createdAt`, the vector-store's indexing timestamp, the process-mining `IndexingJobHistory`. Pulling TT into the library would create a dependency on store-layer concepts.

Full bitemporality (storing both VT and TT as first-class intervals on every element) would double the temporal payload and bloat the serialization contract for a benefit — querying "what did the system believe at time T?" — that is primarily a client concern (audit/provenance, not reasoning). The library's reasoning engines need to know *when something happened in the world* (VT), not when it was ingested.

**Chosen model:** Add a `TemporalInterval` value object representing a half-open valid-time interval `[start, end)` where `end == null` means "still valid". Attach it additively to `GraphEntity` and `GraphRelation` via new optional default methods, keeping the existing `timestamp()` point-time as the canonical event-occurrence time. The interval expresses *duration of validity*; the `timestamp()` continues to mean *when the event/fact was observed* and is used for causal ordering.

### The `TemporalInterval` Value Object

New file: `model/TemporalInterval.java`

```
record TemporalInterval(Instant start, Instant end) {
    // end == null  →  open-ended ("until further notice")
    boolean contains(Instant t)
    boolean overlaps(TemporalInterval other)
    boolean isEmpty()
    static TemporalInterval point(Instant t)      // [t, t+1ns)
    static TemporalInterval since(Instant start)  // [start, null)
    static TemporalInterval of(Instant s, Instant e)
}
```

This is a pure `java.time` value; no deps, hand-rolled JSON serialization (same pattern as `InferredFact.toJson()` at `fol/InferredFact.java:159`).

### Additive Interface Extensions (Backward-Compatible)

`GraphEntity.java` — add two new optional default methods below `timestampOpt()` at line 98:

```java
/** The valid-time interval over which this entity is/was valid. Null = no interval declared. */
default TemporalInterval validTime() { return null; }

/** Whether this entity is valid at instant t (null validTime = always valid if no timestamp). */
default boolean isValidAt(Instant t) {
    TemporalInterval vt = validTime();
    if (vt != null) return vt.contains(t);
    Instant ts = timestamp();
    return ts == null || !ts.isAfter(t);  // point-stamped: valid from ts onward
}
```

`GraphRelation.java` — identical pair of methods.

`SimpleGraphEntity.java` is a record (`model/SimpleGraphEntity.java:30`). It already carries `Instant timestamp` as a record component. Add `TemporalInterval validTime` as a new optional record component. Since records cannot have optional components, the cleanest additive approach is to add it as an additional field with a `null` default in the builder (`GraphEntityBuilder`) rather than adding it to the record canonical constructor — the builder at `model/GraphEntityBuilder.java:40` already sets all other fields. Adding `.validInterval(TemporalInterval vi)` to `GraphEntityBuilder` and a corresponding field to `SimpleGraphEntity` is a non-breaking addition (all existing construction sites use named-argument builder calls or the short `SimpleGraphEntity.of(...)` factories, which gain a new overload leaving existing overloads unchanged).

**Builder change:** `GraphEntityBuilder.java:40` — add `private TemporalInterval validTime;` + `.validTime(TemporalInterval vi)` setter. `GraphRelationBuilder.java:40` — same. The `SimpleGraphEntity` record gains a new component at the end; the three short `of(...)` factories at `model/SimpleGraphEntity.java:50–63` remain unmodified (they leave `validTime` null).

---

## 2. Temporal Graph Views

### Design: Computed Delegating Views (No Copy)

Mirror the `TypeHierarchy.fromGraph(ReasoningGraph)` computed-view pattern at `mebn/type/TypeHierarchy.java:95`. A `TemporalView` wraps an existing `ReasoningGraph` and delegates all reads through a time filter. No entity or relation is copied; only iteration produces filtered results.

New file: `model/TemporalView.java`

```java
public final class TemporalView implements ReasoningGraph {

    private final ReasoningGraph source;
    private final Instant asOf;          // null = no upper bound
    private final Instant from;          // null = no lower bound

    // Factory
    public static TemporalView asOf(ReasoningGraph g, Instant t) { ... }
    public static TemporalView between(ReasoningGraph g, Instant from, Instant to) { ... }

    // All ReasoningGraph methods delegate to source, filtering:
    //   entity.isValidAt(asOf)  AND  entity.timestamp() [from, to]
    //   relation.isValidAt(asOf) AND relation.timestamp() [from, to]

    @Override
    public Collection<GraphEntity> entities() {
        return source.entities().stream()
            .filter(e -> matchesWindow(e.timestamp(), e.validTime()))
            .toList();
    }

    // outgoing/incoming/relationsOf must also filter relations
    // and are lazy (no pre-index rebuild) — acceptable for view semantics
}
```

**Semantics:**
- `asOf(Instant t)` — returns entities/relations where `isValidAt(t)` is true. This covers both point-stamped elements (`timestamp() <= t`) and interval-stamped elements.
- `between(Instant from, Instant to)` — returns elements where `timestamp()` or `validTime()` overlaps `[from, to)`. Useful for "what happened in this window?" queries.

The view is a snapshot in the sense that it holds a reference to the live graph; if the graph is a `MutableReasoningGraph` and mutates after the view is created, the view reflects the mutation (same as `TypeHierarchy` snapshot caveat). Document this clearly.

**Performance note:** `TemporalView` does not maintain adjacency indices; `outgoing(entityId)` iterates the delegate's outgoing list and filters. For large graphs this is O(|neighbours|). If callers use the view in tight reasoning loops, they should materialize it into a fresh `MutableReasoningGraph` using the `TemporalView`'s filtered entity/relation collections. The view is intentionally lazy.

---

## 3. Temporal Relations / Interval Algebra (Allen Relations)

### `AllenRelation` Enum

New file: `model/AllenRelation.java`

Allen's interval algebra defines 13 exhaustive mutually-exclusive relations between two intervals A and B. All 13 are computable from `TemporalInterval.start` / `end` pairs using pure `Instant.compareTo` — no dependencies required. The enum provides a static `compute(TemporalInterval a, TemporalInterval b)` method.

```java
public enum AllenRelation {
    PRECEDES,         // a.end < b.start
    IS_PRECEDED_BY,
    MEETS,            // a.end == b.start
    IS_MET_BY,
    OVERLAPS,         // a.start < b.start < a.end < b.end
    IS_OVERLAPPED_BY,
    STARTS,           // a.start == b.start, a.end < b.end
    IS_STARTED_BY,
    DURING,           // b.start < a.start, a.end < b.end
    CONTAINS,
    FINISHES,         // a.end == b.end, b.start < a.start
    IS_FINISHED_BY,
    EQUALS;           // a.start == b.start, a.end == b.end

    public static AllenRelation compute(TemporalInterval a, TemporalInterval b) { ... }

    /** True when A's interval ends before B begins — the strict temporal precedence used by attribution. */
    public boolean isPrecedence() {
        return this == PRECEDES || this == MEETS;
    }
}
```

**How this is used downstream:** the attribution engine calls `AllenRelation.compute(causeInterval, effectInterval).isPrecedence()` to enforce the temporal causal constraint (section 5a).

**Open-ended interval handling:** when `end == null` (entity/relation is still ongoing), `PRECEDES` and `MEETS` cannot hold against it (a still-ongoing interval cannot precede anything). The `compute` method should treat `null` end as `Instant.MAX` for comparison purposes, which is consistent with the semantics "valid until further notice".

---

## 4. Temporal Rules: Expressing Temporal Constraints in the Existing Engines

### Decision: Extend `Constraints` with Temporal Predicates — No Separate Temporal Logic Engine

The existing `Constraints` factory at `mebn/logic/Constraints.java:42` already provides `entityExists`, `edgeOfType`, `metadataEquals`, `weightAbove`, `notEqual`, etc. The approach is to add temporal predicates to this same factory so they compose with `and`, `or`, `not`, and `implies` in exactly the same way. This keeps one constraint language and avoids a parallel temporal-logic system.

**New predicates to add to `Constraints.java`:**

```java
/** True when entity X has a timestamp that is strictly before entity Y's timestamp. */
public static LogicalConstraint precedes(String varX, String varY)

/** True when entity X's valid-time interval starts before or at instant t. */
public static LogicalConstraint validBefore(String varX, Instant t)

/** True when entity X's valid-time interval contains instant t. */
public static LogicalConstraint validAt(String varX, Instant t)

/** True when the duration between X's timestamp and Y's timestamp is at most deltaMillis. */
public static LogicalConstraint withinWindow(String varX, String varY, long deltaMillis)

/** True when AllenRelation.compute(X.validTime, Y.validTime) is one of the allowed relations. */
public static LogicalConstraint allenRelation(String varX, String varY,
                                               Set<AllenRelation> allowed)
```

These predicates resolve against the `KnowledgeBase` interface. The existing `KnowledgeBase` at `mebn/logic/KnowledgeBase.java:23` has `getMetadata(entityId, key)` but not a typed timestamp accessor. We add one new method:

```java
/** Timestamp of entity (from GraphEntity.timestamp()); empty if none. */
Optional<Instant> getTimestamp(String entityId);

/** Valid-time interval of entity; empty if none. */
Optional<TemporalInterval> getValidTime(String entityId);
```

`ReasoningGraphKnowledgeBase` at `fol/ReasoningGraphKnowledgeBase.java:56` implements `KnowledgeBase` against a `ReasoningGraph`. It gains these two implementations trivially — `graph.entity(entityId).map(GraphEntity::timestamp)` etc.

### Expressing Temporal FOL/PSL Rules

A rule like "activity A must precede activity B" is written as:

```java
FolRule.of("activity-order",
    2.5,
    Constraints.and(
        Constraints.edgeOfType("A", "B", "DIRECTLY_FOLLOWS"),
        Constraints.not(Constraints.precedes("A", "B"))  // violation: A doesn't precede B
    ),
    Constraints.alwaysTrue()  // hard violation: always flag when antecedent holds
)
```

Or the positive form ("A causes B only if A precedes B"):

```java
FolRule.of("temporal-causality",
    3.0,
    Constraints.and(
        Constraints.edgeOfType("A", "B", "CAUSES"),
        Constraints.precedes("A", "B")
    ),
    Constraints.entityExists("B")  // consequent: B is reachable via valid causal path
)
```

These rules pass through `FolInferenceService.translateRule()` at `fol/FolInferenceService.java:221` unchanged — the grounding loop evaluates every pair and the new predicates are evaluated via `ReasoningGraphKnowledgeBase.getTimestamp()`.

### On MTL/LTL

We explicitly do not add a Metric Temporal Logic (MTL) or Linear Temporal Logic (LTL) sub-language. The use cases here — "cause precedes effect", "within a time window", "interval overlaps" — are fully expressible with the predicate extensions above. LTL path quantifiers (`G`, `F`, `U`) would require automata-based model checking over a temporal state sequence, which implies a fundamentally different graph representation (timed automaton / Kripke structure). The added complexity is not justified. If future work on process discovery (which already uses `PRECEDES` edges in `kompile-process-discovery`) requires path-level temporal properties, that can be revisited as a client-layer extension.

---

## 5. Temporal Attribution

This is the primary deliverable. Attribution already carries temporal fields: `AttributionQuery.temporalStart/End` at `domain/AttributionQuery.java:78`, `CausalHop.causeTimestamp/effectTimestamp` at `domain/CausalHop.java:67–71`. The existing `TemporalChainExtractor` in `kompile-event-attribution` at `algorithm/TemporalChainExtractor.java:47` demonstrates the pattern (BFS, proximity scoring, decay) but is coupled to the JPA `KnowledgeGraphService`. The library must host a reimplemented, infra-free version and extend the attribution domain model.

### 5a. Precedence Constraint (Causal Filtering)

An effect can only be attributed to causes that temporally precede it. This is the hardest constraint — not soft, not weighted.

**In attribution chain assembly:** the chain-traversal algorithm (to be hosted in a new `attribution/TemporalAttributionService.java`) walks incoming causal edges (`ReasoningGraph.incoming(targetId)`) and for each candidate cause node `C`:
1. Retrieve `C.timestamp()` and `effect.timestamp()`.
2. If both are non-null: keep `C` only if `C.timestamp().isBefore(effect.timestamp())`. Ties (`equals`) are kept (simultaneous events may still causally contribute).
3. If only the effect is stamped: keep `C` (we cannot rule it out; flag it as `TEMPORAL_UNKNOWN` in the `EvidenceType`).
4. If only `C` is stamped: keep and flag similarly.
5. If neither is stamped: keep (timeless entities are always eligible).

This filter replaces the BFS-over-TEMPORAL-edge approach in `TemporalChainExtractor` with a direct timestamp comparison at graph query time. It uses `GraphEntity.timestamp()` directly — no KG store needed.

**Interval-aware version:** if entities carry `validTime()`, the Allen relation `compute(cause.validTime(), effect.validTime()).isPrecedence()` is used instead. Point timestamps are wrapped as `TemporalInterval.point(ts)` for uniform treatment.

### 5b. Time-Windowed Attribution

`AttributionQuery` already has `temporalStart` and `temporalEnd` at lines 78–79. The attribution service converts the query's `ReasoningGraph` to a `TemporalView` before traversal:

```java
ReasoningGraph view = TemporalView.between(graph,
    query.getTemporalStart(), query.getTemporalEnd());
```

All traversal then operates on the filtered view. This is a two-line change to the entry point of the traversal — the rest of the algorithm is unchanged.

The `AttributionQuery` gains two new optional fields:

```java
/** Only attribute among causes whose timestamps fall within [t-windowDuration, targetTime]. */
private Duration attributionWindow;   // null = no window constraint (use temporalStart/End if set)
```

When `attributionWindow` is set, `temporalStart` is computed as `targetTime.minus(attributionWindow)` at query execution time.

### 5c. Time-Decay Weighting

`TemporalChainExtractor` already implements exponential decay with 1-hour half-life at `algorithm/TemporalChainExtractor.java:184`:

```java
double decay = Math.exp(-diffMs / 3_600_000.0);
```

This moves into the library. The decay parameters are externalized:

**New value object:** `attribution/TemporalDecayConfig.java`

```java
public record TemporalDecayConfig(
    TemporalDecayFunction function,   // EXPONENTIAL, LINEAR, NONE
    Duration halfLife,                // for EXPONENTIAL (default: 1 hour)
    Duration decayWindow              // for LINEAR (full-zero at this distance)
) {
    public static TemporalDecayConfig exponential(Duration halfLife) { ... }
    public static TemporalDecayConfig none() { ... }
    public double decayFactor(Instant cause, Instant effect) { ... }
}
```

The default half-life of 1 hour is appropriate for event-attribution (server crashes, process events). Process-mining callers (activity durations in days) should pass `Duration.ofDays(1)` or `NONE` depending on use case. The `AttributionQuery` gains a nullable `TemporalDecayConfig decayConfig` field; null means `TemporalDecayConfig.none()`.

**Application point:** in the traversal hop scorer, the `CausalHop.strength` is multiplied by `decayConfig.decayFactor(hop.causeTimestamp, hop.effectTimestamp)`. The existing `AttributionChain.overallConfidence` (product of hop strengths at `domain/AttributionChain.java:70`) then naturally propagates decay across the full chain.

### 5d. Temporal Causal Chains

A temporal causal chain `A → B → C` must satisfy:
- `A.timestamp < B.timestamp < C.timestamp` (strict temporal ordering, from 5a)
- All three entities fall within the query's temporal window (from 5b)
- Contribution decays as distance from the target grows (from 5c)

The existing `AttributionChain` and `CausalHop` domain objects already support this: `CausalHop.causeTimestamp` and `CausalHop.effectTimestamp` at `domain/CausalHop.java:67–71` are already declared. They just need to be populated by the traversal.

**Add a validation method to `AttributionChain`:**

```java
/** True if all hops are temporally ordered (no hop has cause timestamp after effect timestamp). */
public boolean isTemporallyConsistent() {
    return hops.stream().allMatch(h ->
        h.getCauseTimestamp() == null || h.getEffectTimestamp() == null ||
        !h.getCauseTimestamp().isAfter(h.getEffectTimestamp()));
}
```

Chains that fail this check should be dropped or demoted to `AttributionConfidence.INSUFFICIENT`.

### 5e. The New `TemporalAttributionService` in the Library

New package: `ai.kompile.graph.reasoning.attribution`

```
attribution/
  TemporalAttributionService.java   — main entry point
  TemporalDecayConfig.java          — decay configuration
  TemporalAttributionQuery.java     — extends AttributionQuery with temporal fields
  TemporalAttributionResult.java    — extends AttributionResult with temporal metadata
```

`TemporalAttributionService.attribute(ReasoningGraph, TemporalAttributionQuery)`:
1. Apply `TemporalView.between(graph, query.temporalStart, query.temporalEnd)` to scope the graph.
2. Walk incoming causal edges from `targetNodeId`, depth-limited by `query.maxDepth`.
3. At each hop: enforce 5a precedence, apply 5c decay, build a `CausalHop` with timestamps.
4. Assemble `AttributionChain`s; validate 5d consistency; rank by `overallConfidence`.
5. Return `TemporalAttributionResult` extending `AttributionResult` with:
   - `TemporalInterval queryInterval` — the effective window used.
   - `TemporalDecayConfig decayConfig` — the decay configuration used.
   - A map `Map<String, Double> temporalInfluenceScores` — like `AttributionResult.influenceScores` but after decay has been applied.

**Infra-free contract:** `TemporalAttributionService` takes only `ReasoningGraph` and the query — no `KnowledgeGraphService`, no Spring, no JPA. Clients (event-attribution, process-mining) wrap their store's data into a `ReasoningGraph` via their existing adapters (the same path that PSL/Bayesian/MEBN inference uses).

### 5f. Granger-Style Precedence (Optional, Deferred)

Granger causality ("does X's past help predict Y?") requires time-series regression over repeated observations. The library has no time-series store, no regression — this is a client-layer concern. If process-mining callers want Granger tests they should compute them over their raw event logs and inject the resulting directed influence weights as `GraphRelation.weight()` values, which the attribution engine then uses directly. No separate Granger engine in the library.

### 5g. Temporal Evidence Type Extension

The existing `EvidenceType` enum at `domain/EvidenceType.java:16` already has `TEMPORAL_PROXIMITY`. Add:

```java
TEMPORAL_PRECEDENCE,   // cause provably precedes effect (hard constraint satisfied)
TEMPORAL_WINDOW,       // cause falls within the declared attribution window
TEMPORAL_DECAY_SCORED, // cause's contribution discounted by decay function
TEMPORAL_ALLEN_RELATION // cause/effect validated by specific Allen interval relation
```

These make the reasoning transparent: an `AttributionEvidence` can carry `EvidenceType.TEMPORAL_PRECEDENCE` with a `summary` like "cause(t=2025-01-01T10:00Z) precedes effect(t=2025-01-01T10:05Z)" — auditable without an LLM.

### 5h. `InferredFact` with Temporal Validity

An `InferredFact` (at `fol/InferredFact.java:47`) currently carries `inferredAt: Instant` — when the inference ran. For temporal reasoning, inferred facts can be valid only over a period. Add two optional fields:

```java
record InferredFact(
    ...,                              // existing fields unchanged
    @Nullable Instant validFrom,      // null = valid indefinitely from inferredAt
    @Nullable Instant validUntil      // null = open-ended
)
```

`InferredFact.fromEntailment()` at line 82 populates these from the antecedent entities' temporal intervals when available. The `toJson()`/`fromJson()` pair (lines 159–333) gains two more optional keys.

---

## 6. Persistence and Integration

### Temporal Fields in the Portable Layer

The existing `GraphEntity.timestamp()` already travels via JSON serialization in the portable graph I/O layer (used by `GraphIOService` and the git-xet sidecar). `TemporalInterval` is serialized as:

```json
{ "validFrom": "2025-01-01T00:00:00Z", "validUntil": null }
```

This is a two-field extension on the entity/relation JSON envelope. Existing exporters that project `GraphEntity` to `PortableGraphEntity` must be updated to also project `validTime()` — this is a forward-compatible addition (readers that don't know the field ignore it; existing tests remain green because entities without `validTime` serialize/deserialize identically).

### Client Consumers

**`kompile-event-attribution` (`algorithm/TemporalChainExtractor.java`):** This is a client of the library. It can be refactored to delegate the core temporal scoring to `TemporalAttributionService`, keeping only the JPA adapter (mapping `GraphNode`→`GraphEntity`). The existing `KnowledgeGraphService.getNode()` + `getEdgesForNode()` calls map cleanly to `MutableReasoningGraph.addEntity/addRelation`. This is the T4 phase wire-up; do NOT modify `TemporalChainExtractor` during T1–T3.

**`kompile-process-discovery`:** Process mining already uses `CausalEdgeType.PRECEDES` (referenced in MEMORY.md). The `PerformanceMiner` and `HeuristicsMiner` clients build `ReasoningGraph`s for attribution. They gain temporal views automatically once they populate `GraphEntity.timestamp()` (which they should from activity start times) and call `TemporalView.asOf()` or `TemporalAttributionService`.

### Infra-Free Boundary

Everything in section 1–5 lives in `ai.kompile.graph.reasoning.*`. Nothing in these classes may import:
- `ai.kompile.knowledgegraph.*` (JPA store)
- `ai.kompile.app.*` (Spring app)
- Jackson-databind (only jackson-annotations is in scope)
- Any `@Component` / `@Service` annotation

The existing `InferredFact.toJson()` hand-rolled pattern confirms this is viable. `TemporalInterval.toJson()` follows the same pattern.

---

## 7. Lib/Client Split

| Lives in library (`kompile-graph-reasoning`) | Lives in client module |
|---|---|
| `TemporalInterval` value object | Adapter mapping `GraphNode.createdAt` → `TemporalInterval` |
| `TemporalView` (computed view) | Populating `MutableReasoningGraph` from JPA/vector store |
| `AllenRelation` enum | Granger causality tests |
| Temporal `Constraints` predicates | LLM-synthesized causal narrative |
| `TemporalDecayConfig` | Persistence of `TemporalAttributionResult` (e.g. JSONL) |
| `TemporalAttributionService` | REST endpoint wrapping the service |
| Temporal fields on `AttributionQuery` / `CausalHop` | Process-mining anchor case notion |
| Temporal `EvidenceType` additions | `TemporalChainExtractor` adapter rewrite |
| Temporal `InferredFact` validity fields | Grafana / UI surface |

---

## 8. Phased Implementation Plan

### T1 — Temporal Model and Views (Implement First)

**Goal:** All downstream tests stay green; new temporal API is available but opt-in.

1. Add `TemporalInterval` record to `model/` with hand-rolled JSON, `contains()`, `overlaps()`, `point()`, `since()`, `of()`.
2. Add `validTime()` / `isValidAt()` default methods to `GraphEntity` and `GraphRelation` interfaces.
3. Add `validTime` field to `GraphEntityBuilder` and `GraphRelationBuilder`; update `SimpleGraphEntity` and `SimpleGraphRelation` records with null-safe defaults.
4. Add `TemporalView` implementing `ReasoningGraph` with `asOf()` and `between()` factories.
5. Extend portable JSON serialization to write/read `validFrom`/`validUntil` keys.
6. Add unit tests: `TemporalIntervalTest`, `TemporalViewTest`. All 410+ existing tests unchanged (no breaking changes).

**Backward-compat notes:** `SimpleGraphEntity` is a record; adding a component changes the canonical constructor. To avoid breaking all 410+ construction sites, introduce a **new constructor overload** on `SimpleGraphEntity` (`of(id, type, label, weight, confidence, tags, embedding, timestamp, validTime, attributes)`) rather than changing the existing 9-argument canonical constructor. Alternatively, move `validTime` into the `attributes` map as a serialized string (`"validFrom"`, `"validUntil"` keys) and surface it via the `validTime()` default method reading those keys — this avoids any constructor changes and is the safest approach. **Recommended for T1.**

### T2 — Interval Algebra and Temporal Rule Constraints

1. Add `AllenRelation` enum with `compute()` and `isPrecedence()`.
2. Add `getTimestamp()` and `getValidTime()` to `KnowledgeBase` interface and `ReasoningGraphKnowledgeBase`.
3. Add temporal predicates to `Constraints`: `precedes()`, `validAt()`, `withinWindow()`, `allenRelation()`.
4. Verify the existing `FolInferenceService.translateRule()` loop evaluates the new predicates correctly (it already evaluates arbitrary `LogicalConstraint.evaluate(kb, bindings)` — no structural change needed).
5. Add tests: `AllenRelationTest`, `TemporalConstraintTest` (temporal `FolRule` over a small graph with timestamps).

### T3 — Temporal Attribution

1. Create `attribution/` package. Add `TemporalDecayConfig`, extend `AttributionQuery` with `attributionWindow` and `decayConfig`.
2. Add `TemporalAttributionService.attribute()`: precedence filter (5a), windowed view (5b), decay weighting (5c), chain consistency validation (5d).
3. Add temporal `EvidenceType` entries (`TEMPORAL_PRECEDENCE`, etc.).
4. Add temporal validity fields to `InferredFact` with backward-compatible JSON.
5. Tests: `TemporalAttributionServiceTest` (small graphs with known timestamps, verify chains respect precedence, decay reduces score correctly).

### T4 — Client Wiring

1. Refactor `kompile-event-attribution`'s `TemporalChainExtractor` to delegate to `TemporalAttributionService`. Keep the JPA adapter wrapper.
2. Wire `kompile-process-discovery` to pass activity timestamps through to `GraphEntity.timestamp()` and call `TemporalAttributionService` for bottleneck attribution.
3. Update the REST `AttributionQuery` DTO in `kompile-event-attribution` to expose `attributionWindow` and `decayConfig`.
4. Verify existing `TemporalChainExtractor` tests (at `algorithm/TemporalChainExtractorTest.java`) pass against the new impl.

---

## 9. Open Questions for the User

**Q1. Valid-time-only vs Bitemporal — final call?**
The design recommends valid-time only (when did the fact hold in the world), leaving transaction-time (when was it recorded) to client modules. If there is a use case where the reasoning engine itself needs to answer "what was the graph's state *as believed at time T*?" — e.g. incremental snapshot reasoning across daily crawl runs — then bitemporality is needed and should be decided before T1, because it changes the model's storage footprint significantly.

**Q2. Intervals in the model vs. a side temporal index?**
The design proposes storing `validTime` in the `attributes` map (via `"validFrom"`/`"validUntil"` keys) to avoid breaking `SimpleGraphEntity`'s canonical constructor. The alternative is a separate `TemporalIndex` — a Map from entity/relation id to `TemporalInterval` — passed alongside the `ReasoningGraph` to temporal-aware methods. The side index avoids touching `GraphEntity`/`GraphRelation` entirely but requires threading an extra parameter through all APIs. Which coupling is more acceptable?

**Q3. Decay function default — 1-hour half-life appropriate?**
The `TemporalChainExtractor` uses a 1-hour half-life (`algorithm/TemporalChainExtractor.java:184`). This was tuned for event-attribution (server incidents). Process-mining scenarios (human workflow steps spanning days/weeks) need a much longer half-life. The design makes this configurable via `TemporalDecayConfig`. What should the default be when callers don't specify? Options: 1 hour (preserve existing behavior), 24 hours (process-mining friendly), or `NONE` (force explicit configuration). Recommendation: make `NONE` the default in the library (safe, conservative) and let clients configure it. Confirm.

**Q4. LTL/MTL — permanently out of scope?**
The design explicitly excludes full LTL/MTL. If the process-mining team needs to verify safety properties like "Activity A is never followed by Activity B without C in between" over a trace, that requires path-level quantification over sequences. The current design could express `A → C → B` as a rule pattern but not the negation of the interleaving. Is this use case in scope for any client, and if so, at which layer?

**Q5. Allen relation grounding scale — entity pair explosion?**
`FolInferenceService.buildPairs()` at `fol/FolInferenceService.java:310` caps at 10,000 entity pairs. An `allenRelation()` constraint in a `FolRule` would be evaluated for every pair in that set. For graphs with many timestamped entities this may be expensive. Should temporal FOL rules operate on a temporal-view-scoped subgraph (reducing |E| before grounding) or is the 10k cap sufficient? Decide before T2.

---

## Summary of Concrete Class/File Decisions

| New file | Package | Purpose |
|---|---|---|
| `TemporalInterval.java` | `model` | Value object: `[start, end)` interval |
| `TemporalView.java` | `model` | Delegating `ReasoningGraph` view filtered by time |
| `AllenRelation.java` | `model` | 13-relation enum + `compute()` |
| `TemporalDecayConfig.java` | `attribution` | Configurable decay function |
| `TemporalAttributionService.java` | `attribution` | Infra-free temporal attribution |
| `TemporalAttributionQuery.java` | `attribution` | Extends `AttributionQuery` |
| `TemporalAttributionResult.java` | `attribution` | Extends `AttributionResult` |

| Modified file | Change |
|---|---|
| `model/GraphEntity.java:92` | Add `validTime()`, `isValidAt()` default methods |
| `model/GraphRelation.java:87` | Add `validTime()`, `isValidAt()` default methods |
| `model/GraphEntityBuilder.java:40` | Add `validTime` field + setter |
| `model/GraphRelationBuilder.java:40` | Add `validTime` field + setter |
| `mebn/logic/KnowledgeBase.java:23` | Add `getTimestamp()`, `getValidTime()` |
| `fol/ReasoningGraphKnowledgeBase.java:56` | Implement new `KnowledgeBase` methods |
| `mebn/logic/Constraints.java:42` | Add temporal predicate factory methods |
| `domain/EvidenceType.java:16` | Add 4 temporal evidence types |
| `domain/AttributionQuery.java:78` | Add `attributionWindow`, `decayConfig` fields |
| `domain/CausalHop.java:67` | `causeTimestamp`/`effectTimestamp` already exist — populate them in service |
| `fol/InferredFact.java:47` | Add optional `validFrom`, `validUntil` fields with JSON serde |
| `domain/AttributionChain.java:34` | Add `isTemporallyConsistent()` method |
