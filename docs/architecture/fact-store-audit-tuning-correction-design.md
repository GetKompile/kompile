# Fact-Store Audit Trail, Strength Layering, and Tuning/Correction Design

**Status:** DESIGN — 2026-06-21
**Scope:** Human-correctable grounding KB: append-only audit log of fact-store mutations,
confidence-band strength layering on `InferredFact`, and a tuning/correction control surface
(adjust weight/strength → re-ground → PIN → feed back into `WeightLearner.updateOnBatch`).
**Complements:**
- `reasoning-trail-explainability-design.md` — the WHY/decision trail; this doc is the WHAT HAPPENED/audit trail and the HOW TO FIX control surface
- `grounding-api-contract-design.md` — per-primitive REST contracts; this doc adds the correction/audit overlay
- `incremental-cascade-reasoning-design.md` — cascade mechanics; corrections enter the cascade as structured `∆_del` / `∆_reweight` events
- `graph-pruning-compaction-health-design.md` (in progress) — owns pruning mechanics; this doc owns the audit event that pruning records into

---

## 0. Core Idea

The grounding KB has two accountability gaps today:

1. **No audit trail.** `InferredFactStore.store()` replaces the latest fact silently. `FileBackedInferredFactStore` (`kompile-knowledge-graph/.../grounding/FileBackedInferredFactStore.java:110`) appends to `factsheet-<id>-history.jsonl`, which records the `InferredFact` versions, but not the *reason for the change* (who caused it, from what prior value, via which mechanism).

2. **No correction surface.** A human who sees that `derived_isActive(alice) = 0.23` (wrong) has no path to say "that should be 0.0 and it should stay 0.0 regardless of what PSL derives next." The only existing path is `KbGroundingService.assertFact` which asserts a new *observed* fact, not a correction/override of a derived one.

The design below adds:
- **Audit events**: append-only JSONL records of every fact-store mutation with actor, before/after state, and a link to the decision trail.
- **Strength layers**: confidence bands (ESTABLISHED / PROBABLE / SPECULATIVE / SUPPRESSED) on `InferredFact` that control how facts surface and how they can be corrected.
- **Correction/PIN API**: a human says "this is wrong" — produces a `FactCorrected` audit event, a PIN record that blocks re-derivation overwriting it, and a training signal fed to `PslWeightLearningService.updateOnBatch`.
- **Composition view**: the `reasoning-trail-explainability-design.md` WHY trail extended with the audit WHAT HAPPENED timeline in a single Angular panel.

---

## 1. Existing Anchors (where new code attaches)

| Existing class | File (repo-relative) | Role in this design |
|---|---|---|
| `InferredFact` | `kompile-graph-reasoning/.../fol/InferredFact.java:47` | Gains `strengthLayer` + `pinned` fields (or parallel audit record — see §2.2 fork) |
| `InferredFactStore` | `.../fol/InferredFactStore.java:29` | Gains `void recordAuditEvent(FactAuditEvent)` method on the SPI; or the audit log is a parallel SPI |
| `FileBackedInferredFactStore` | `.../grounding/FileBackedInferredFactStore.java:67` | Gains `factsheet-<id>-audit.jsonl` alongside the existing `history.jsonl` |
| `ConcurrentFactStore` | `.../fol/grounding/ConcurrentFactStore.java:55` | MVCC version already serves as audit clock; no change |
| `PslWeightLearningService.updateOnBatch` | `.../learning/PslWeightLearningService.java:76` | The human-correction→training-signal path calls this with the atom key + ground-truth value |
| `WeightStore` | `.../learning/WeightStore.java:34` | Correction-tuned weights saved here as a new version; labeled `HUMAN_CORRECTION` in metadata |
| `AgentFactAssertedEvent` | `.../grounding/AgentFactAssertedEvent.java:44` | Pattern for a new `FactCorrectedEvent` (same Spring event bus, same cascade hook fires on it) |
| `IncrementalReasoningOrchestrator.runFullReground` | `.../reasoning/IncrementalReasoningOrchestrator.java:156` | Called after a correction with the PIN set, so re-derivation sees the PIN and skips the atom |
| `KbGroundingService.assertFact` | `.../grounding/KbGroundingService.java:225` | Correction is a parallel entry point; both paths publish to the cascade hook |
| `DerivationTree` | `.../fol/grounding/DerivationTree.java` | Link from audit event to decision trail (via `runId`) |
| `WeightLearner.learn` | `.../learning/WeightLearner.java:34` | Interface signature; corrections feed `updateOnBatch` |

---

## 2. Audit Event Model

### 2.1 Event types

An audit event records a single mutation to the fact store. The log is append-only and written to
`<dataDir>/data/graph/inferred/factsheet-<id>-audit.jsonl` by `FileBackedInferredFactStore`.

```
FactAuditEvent (abstract — all variants share common fields)
  ├─ FactAsserted      — observed/agent assert via KbGroundingService.assertFact
  ├─ FactDerived       — MAP inference round produces a new InferredFact version
  ├─ FactReweighted    — a PSL rule weight changed (indirectly changes all downstream facts)
  ├─ StrengthChanged   — a fact moved bands (see §3) without a value change
  ├─ FactTombstoned    — soft-delete (strength → SUPPRESSED); recorded before the cascade
  ├─ FactCorrected     — human override via the correction API; defaults to PIN=true
  └─ WeightTuned       — human adjusted a PSL rule weight via the tuning API
```

### 2.2 Shared fields (all event types)

```java
// kompile-graph-reasoning/.../audit/FactAuditEvent.java  (new, lib-level)
public record FactAuditEvent(
    String eventId,           // UUID; unique per event
    String eventType,         // "ASSERTED" | "DERIVED" | "REWEIGHTED" | "STRENGTH_CHANGED"
                              //   | "TOMBSTONED" | "CORRECTED" | "WEIGHT_TUNED"
    String atomKey,           // canonical atom key this event is about; null for WeightTuned
    Instant occurredAt,       // wall-clock event time

    // Actor
    String actor,             // "CRAWL" | "AGENT:<sessionId>" | "HUMAN:<userId>" | "CASCADE"
    String sessionId,         // sessionId or runId for traceability

    // Before / after state
    double valueBefore,       // Double.NaN if not applicable (e.g. new fact)
    double valueAfter,        // Double.NaN if tombstone (value undefined after)
    double confidenceBefore,
    double confidenceAfter,
    String strengthLayerBefore, // null if not yet banded
    String strengthLayerAfter,

    // Decision trail link
    String runId,             // InferredFact.runId → links to DerivationTree + ReasoningTrail
    String derivationTrailRef,// "runId:<runId>" | "trailId:<trailId>" for future /api/explain lookup

    // PIN state
    boolean pinnedAfter,      // true when correction locks this atom against re-derivation

    // Variant-specific payload (nullable)
    String ruleId,            // FactDerived: which rule fired; WeightTuned: which rule was adjusted
    double weightBefore,      // WeightTuned only
    double weightAfter,       // WeightTuned only
    String correctionReason   // FactCorrected only: human-supplied justification
) {}
```

**Serialization**: hand-rolled JSON like `InferredFact.toJson()` — no jackson-databind in the lib.
A `FactAuditEvent.toJson()` + `fromJson()` pair follows the exact same pattern as
`InferredFact.java:159-205`.

**Lib vs client split**:
- `FactAuditEvent` (the record + JSON serialization) lives in `kompile-graph-reasoning` under
  `ai.kompile.graph.reasoning.audit` — infra-free, no Spring.
- `FileBackedAuditLog` (writes `factsheet-<id>-audit.jsonl`) lives in `kompile-knowledge-graph`
  alongside `FileBackedInferredFactStore` — same pattern, different file.
- REST endpoints and the correction/tuning API live in `kompile-app-main` controllers.

### 2.3 Event→InferredFact linkage

Every `FactDerived` event carries the `runId` from the `InferredFact` it corresponds to
(`InferredFact.runId()` at `InferredFact.java:53`). This is the join key between the audit trail
("what happened to this atom at time T") and the decision trail ("why was the value T.value derived").
The `ReasoningTrail` design (`reasoning-trail-explainability-design.md §3.1`) already carries `runId`
as a top-level field. The composition in the UI (§6 below) uses this join.

### 2.4 Where events are emitted

| Mutation | Emitting code | Event type |
|---|---|---|
| `KbGroundingService.assertFact` | After write to `ConcurrentFactStore` | `ASSERTED` (actor=`AGENT:<sessionId>`) |
| `IncrementalReasoningOrchestrator.doReground` line 253 | After `inferredStore.store(newFact)` | `DERIVED` (actor=`CASCADE`, runId=MAP runId) |
| `KbCorrectionService.correct` (new) | Before + after tombstone/reweight | `CORRECTED` + optional `TOMBSTONED` |
| `KbCorrectionService.adjustWeight` (new) | After `WeightStore.save` | `WEIGHT_TUNED` |
| Layer-band re-evaluation (see §3.3) | After strength-layer cutoff scan | `STRENGTH_CHANGED` |

The pruning mechanics (`graph-pruning-compaction-health-design.md`) will emit `TOMBSTONED` events via
the same `FileBackedAuditLog`. This doc defines the model; that doc defines when pruning calls it.

### 2.5 Persistence layout

```
<dataDir>/data/graph/inferred/
  factsheet-<id>-latest.jsonl       — existing: current InferredFact per atomKey
  factsheet-<id>-history.jsonl      — existing: all InferredFact versions (append-only)
  factsheet-<id>-audit.jsonl        — NEW: all FactAuditEvents (append-only)
  factsheet-<id>-pins.jsonl         — NEW: PinRecord per atomKey (latest state; rewritten on change)
```

All four files travel on `git clone` (they are under `data/` which is tracked per the graph-as-asset
design). Audit events are never compacted away in Phase 1; retention/rollup is a Phase 4 fork (§8).

---

## 3. Strength Layering

### 3.1 Bands

Facts are banded by confidence into four tiers:

| Layer | Name | Confidence range (default) | Semantics |
|---|---|---|---|
| `ESTABLISHED` | Established | [0.85, 1.0] | High-confidence; surfaced by default; not easily correctable without audit |
| `PROBABLE` | Probable | [0.50, 0.85) | Moderate-confidence; surfaced with caveat; normal correction target |
| `SPECULATIVE` | Speculative | [0.20, 0.50) | Low-confidence; hidden from default views; available for inspection |
| `SUPPRESSED` | Suppressed | [0.0, 0.20) | Effectively inactive; tombstone range; facts here are not used in grounding |

The default cutoffs are global constants but are configurable per-fact-sheet via a `StrengthLayerConfig`
record stored in `factsheet-<id>-config.json` (extend the existing config seam at
`IncrementalReasoningOrchestrator.java:124` / `dataDir`). This resolves the **fork (i)** below as
"default=global, override=per-fact-sheet, never learned" for Phase 1.

### 3.2 Layer field on InferredFact

**Decision**: rather than modifying the `InferredFact` record directly (which would require changing
`InferredFact.toJson/fromJson` and all callers), the strength layer is stored as a field in the
`PinRecord` (see §4.3) and in the `FactAuditEvent`. The `InferredFactStore.allLatest()` results are
enriched at read time by a `StrengthLayerResolver` (new, lib-level) that applies the cutoff bands to
`InferredFact.confidence()`.

This avoids breaking the existing `InferredFact` record contract (252+214+7 tests) while still
exposing the layer to callers. The `InferredFact.confidence()` field already exists at line 50.

```java
// kompile-graph-reasoning/.../audit/StrengthLayerResolver.java  (new, lib-level)
public final class StrengthLayerResolver {
    public StrengthLayer resolve(double confidence) { ... } // applies cutoffs
    public StrengthLayer resolve(InferredFact fact) { return resolve(fact.confidence()); }
    public List<InferredFact> filterByLayer(Collection<InferredFact> facts, StrengthLayer minLayer) { ... }
}

public enum StrengthLayer { SUPPRESSED, SPECULATIVE, PROBABLE, ESTABLISHED }
```

**Alternate**: add `strengthLayer` as a JSON-optional field to `InferredFact` (read with a default
if absent for backward compat). This is simpler but harder to keep in sync when inference re-runs
update confidence without updating the layer. The resolver approach is preferred because it is always
consistent with the current confidence value.

### 3.3 Layer movement and audit

When confidence changes (new `InferredFact` version from a cascade), the resolver recomputes the
layer. If the layer changed, a `STRENGTH_CHANGED` audit event is emitted. This makes layer transitions
visible in the audit trail without requiring the layer to be stored in the fact itself.

### 3.4 Queryability

`InferredFactStore` gains one new read method on the SPI:

```java
// Added to InferredFactStore.java
Collection<InferredFact> byLayer(StrengthLayer layer);   // via StrengthLayerResolver at read time
```

REST endpoint: `GET /api/kb-grounding/{factSheetId}/facts?layer=PROBABLE` — returns all current
inferred facts at or above the given layer, with layer annotations. This is how the correction UI
filters facts to show.

---

## 4. Correction and PIN Control Surface

### 4.1 The correction flow (end to end)

```
Human sees derived fact X with wrong value
    → opens Correction panel (§6)
    → selects "Correct" (adjust to value Y) or "Tombstone" (suppress entirely)
    → optionally supplies justification text
    → submits

Backend (KbCorrectionService.correct):
    1. Read current InferredFact for atomKey (latest version)
    2. Emit FactAuditEvent[CORRECTED] with before state
    3. If tombstone: set atom to SUPPRESSED layer, write PinRecord(atomKey, PIN=true, pinnedValue=0.0)
       If value adjust: write PinRecord(atomKey, PIN=true, pinnedValue=Y)
    4. Write a new InferredFact version with confidence=Y (or 0.0) and the corrected value
    5. Publish FactCorrectedEvent (new Spring event, parallel to AgentFactAssertedEvent)
    6. GroundingCascadeHook fires → IncrementalReasoningOrchestrator.runFullReground
       → during re-ground: PinGuard checks each atom before writing new InferredFact version
         → if PinRecord(atomKey).pinned=true: SKIP (do not overwrite with re-derived value)
    7. Feed correction as training signal: PslWeightLearningService.updateOnBatch(
           program, Map.of(atomKey, pinnedValue), steps=1)
       → updated weights persisted to WeightStore with metadata HUMAN_CORRECTION
    8. Return CorrectionResult (new InferredFact version, audit event id, training signal applied)
```

### 4.2 The PIN mechanism

A PIN is an immutable override: a pinned fact cannot be overwritten by any subsequent cascade run.
It can only be reverted by a human via `DELETE /api/kb-grounding/{factSheetId}/corrections/{atomKey}`.

```java
// kompile-graph-reasoning/.../audit/PinRecord.java  (new, lib-level)
public record PinRecord(
    String atomKey,
    boolean pinned,           // false = un-pinned (PIN was reverted)
    double pinnedValue,       // the value the correction set; NaN if tombstone
    String pinnedBy,          // actor (human userId)
    Instant pinnedAt,
    String correctionAuditEventId,  // links to the FactAuditEvent that created this PIN
    String revertAuditEventId       // links to the FactAuditEvent that reverted this PIN (null if active)
) {}
```

**Pin guard in the orchestrator**: `IncrementalReasoningOrchestrator.doReground` (line 236 loop) gains
one check before writing a new `InferredFact` version:

```java
// Proposed addition at IncrementalReasoningOrchestrator.java:~238
if (pinGuard.isPinned(atomKey)) {
    log.debug("Pin guard: skipping re-derivation of pinned atom '{}'", atomKey);
    continue;   // does NOT write a new InferredFact version; existing pinned fact stands
}
```

`PinGuard` is a lightweight collaborator (injected into `IncrementalReasoningOrchestrator`) that
reads the `factsheet-<id>-pins.jsonl` file (via a `FileBackedPinStore`) for the current fact sheet.

**PIN scope**: a PIN applies to the exact atom key (the default). It does not suppress the rule that
produced it globally. This resolves **fork (ii)**: atom-scoped PIN is easier to implement, reversible,
and auditable. Rule-global suppression (suppress the rule everywhere) is more powerful but risks
unintended side effects on other atoms; it belongs in a future "rule override" surface. For Phase 2,
a `PinRecord` can optionally carry `suppressRuleId` to suppress a specific rule for a specific
atom-rule pair, which is a middle ground.

### 4.3 Training signal feedback

Every human correction is a labeled observation: `atomKey → pinnedValue` is ground truth that the
PSL weight learner did not have. The correction service calls:

```java
// After PinRecord is written:
PslProgram program = loadCurrentProgram(factSheetId);  // from WeightStore.latest
PslProgram updated = pslWeightLearningService.updateOnBatch(
    program,
    Map.of(atomKey, pinnedValue),   // single-atom "mini-batch"
    3                               // 3 weight-update steps (configurable)
);
List<PslRule> updatedRules = updated.rules();
weightStore.save(factSheetId + ":program", updatedRules);
// Emit WeightTuned audit event for each rule whose weight changed by > WEIGHT_EPSILON
```

This is exactly the `updateOnBatch` warm-start path documented in `PslWeightLearningService.java:76`.
Because the learner warm-starts from the current weights, corrections accumulate: each human correction
nudges the weights in the direction that makes the model less likely to re-derive the wrong value,
without resetting prior learning.

**Key design decision (decided here)**: the correction produces **both** a PIN (immediate hard block)
AND a weight-learning signal (soft bias). The PIN is the safety net that prevents immediate re-derivation
overwrite. The weight-learning signal is the long-term fix that may eventually make the PIN unnecessary
(once the model learns the corrected pattern, it would derive the correct value anyway). A human can
promote a fact from PIN → AUTO after enough corrections have trained the model to agree.

### 4.4 Weight tuning (rule-level, separate from fact correction)

A human can also tune PSL rule weights directly without correcting a specific derived fact:

```
PUT /api/kb-grounding/{factSheetId}/weights/{programId}
Body: { "ruleDisplay": "<3.20: State(a) & Link(a,b) -> State(b) ^2>", "newWeight": 1.8 }
```

This calls `PslWeightLearningService.applyWeights()` with the updated map, saves to `WeightStore`,
emits a `WEIGHT_TUNED` audit event for the rule, and triggers a cascade re-ground.

The tuned weight is the "human prior" weight. It participates in subsequent `learn()` or `updateOnBatch`
calls as the warm-start. This is the direct knob for a domain expert who understands which rule is
over- or under-weighted.

---

## 5. REST API Surface (kompile-app-main)

New controller: `KbCorrectionController` in `ai.kompile.app.web.controllers.grounding.correction`
(add to `GlobalExceptionHandler.basePackages`; see `reference_global_exception_handler_scope.md`).

```
# Audit trail
GET  /api/kb-grounding/{factSheetId}/audit
     ?atomKey=<key>&eventType=CORRECTED&since=<ISO-8601>&limit=100
     → List<FactAuditEvent>

# Corrections / PINs
POST /api/kb-grounding/{factSheetId}/corrections
     Body: { "atomKey": "...", "newValue": 0.0, "reason": "...", "tombstone": false }
     → CorrectionResult { auditEventId, newInferredFact, pinRecord, trainingSignalApplied }

DELETE /api/kb-grounding/{factSheetId}/corrections/{atomKey}
     → revert PIN (emit FactAuditEvent[CORRECTED] with pinnedAfter=false); trigger re-ground

GET  /api/kb-grounding/{factSheetId}/corrections
     → List<PinRecord> (all active PINs for this fact sheet)

# Rule weight tuning
PUT  /api/kb-grounding/{factSheetId}/weights/{programId}
     Body: { "ruleDisplay": "...", "newWeight": 2.5 }
     → WeightTuneResult { auditEventId, oldWeight, newWeight, cascadeTriggered }

GET  /api/kb-grounding/{factSheetId}/weights/{programId}
     → current weight map (from WeightStore.latest)

GET  /api/kb-grounding/{factSheetId}/weights/{programId}/history
     → List<WeightVersion> (from WeightStore.versions)

# Strength-layer queries
GET  /api/kb-grounding/{factSheetId}/facts
     ?layer=PROBABLE&minConfidence=0.5
     → List<InferredFactWithLayer>   (InferredFact + computed StrengthLayer + pinned flag)
```

---

## 6. Composition with the Decision Trail (Explainability View)

The `reasoning-trail-explainability-design.md` defines the WHY trail (logical derivation). This doc's
audit trail is the WHAT HAPPENED timeline (temporal mutation history). They compose in a single panel:

```
┌───────────────────────────────────────────────────────────────────────┐
│  isActive(alice)  PROBABLE  ●●●○○  0.67  [PINNED: 0.0 by adam]      │
│                                                                        │
│  ═══ WHY (Decision Trail) ══════════════════════════════════════════  │
│  [PSL] MAP solve runId=abc123  converged=true                         │
│  ▼ derived_isActive(alice) [0.67]  ← Rule R3: employedAt→isActive    │
│    ▼ employedAt(alice,acme) [0.95] ← observed F12                    │
│                                                                        │
│  ═══ WHAT HAPPENED (Audit Trail) ═══════════════════════════════════  │
│  2026-06-21T19:03:12Z  ASSERTED    actor=CRAWL         0.00 → 0.95   │
│  2026-06-21T19:03:45Z  DERIVED     actor=CASCADE       0.00 → 0.67   │
│    └─ runId=abc123 [▶ WHY?]                                           │
│  2026-06-21T19:15:00Z  CORRECTED   actor=HUMAN:adam    0.67 → 0.00   │
│    └─ reason: "Alice is no longer active — left org 2026-06-15"       │
│    └─ PIN=true  [Revert PIN] [Re-ground]                              │
│                                                                        │
│  Rule weights changed by this correction:                             │
│    R3: employedAt→isActive  3.20 → 2.41 (weight update step)         │
│                                                                        │
│  [Correct] [Tombstone] [Tune Weights] [Export Audit]                 │
└───────────────────────────────────────────────────────────────────────┘
```

**Implementation in Angular:**

The existing `ReasoningTrailComponent` (defined in `reasoning-trail-explainability-design.md §5`)
gains a second section — `AuditTimelineComponent` — that renders the audit log for the same atom.
The two sections share the `runId` join key: clicking "[▶ WHY?]" on a `DERIVED` audit event
calls `reasoningTrailService.explain(atomKey, { runId: event.runId })` to load the decision trail
for exactly that derivation run.

```typescript
// New audit section in reasoning-trail.component.html (additive)
<div *ngIf="auditTrail?.length > 0" class="audit-section">
  <h4>What Happened (Audit Trail)</h4>
  <app-audit-timeline [events]="auditTrail" [atomKey]="trail.targetId"
                      (correctRequested)="openCorrection($event)"
                      (explainRun)="loadTrailForRun($event)" />
</div>
```

`AuditTimelineComponent` is a new standalone component:
```
components/audit-timeline/
  audit-timeline.component.ts
  audit-timeline.component.html
  audit-timeline.component.css
```

The `ReasoningTrailService` gains one new method:
```typescript
auditTrail(factSheetId: number, atomKey: string, limit?: number): Observable<FactAuditEvent[]>
// GET /api/kb-grounding/{factSheetId}/audit?atomKey=<key>&limit=50
```

The `KbGroundingPanelComponent` (defined in `grounding-ui-integration-design.md §2.1`) gains a
"Corrections" sub-tab that shows `GET /api/kb-grounding/{factSheetId}/corrections` (active PINs)
and allows adding corrections and tuning rule weights.

---

## 7. Phased Build Plan

### Phase 1 — Audit event model + JSONL persistence + strength layering (lib + client, no UI, no correction API)

**What ships:**
- `FactAuditEvent` record with `toJson/fromJson` in `kompile-graph-reasoning/.../audit/`
- `StrengthLayer` enum + `StrengthLayerResolver` in `kompile-graph-reasoning/.../audit/`
- `FileBackedAuditLog` in `kompile-knowledge-graph/.../grounding/` — writes `factsheet-<id>-audit.jsonl`
- `IncrementalReasoningOrchestrator` emits `DERIVED` events after each `inferredStore.store()` call
  (line 253 area); `KbGroundingService.assertFact` emits `ASSERTED` events
- `GET /api/kb-grounding/{factSheetId}/audit` with atomKey + eventType filters
- `GET /api/kb-grounding/{factSheetId}/facts?layer=PROBABLE` — strength-layer-filtered fact list
- Unit tests: `FactAuditEventTest` (round-trip JSON), `StrengthLayerResolverTest` (band assignments),
  `FileBackedAuditLogTest` (append + load), integration: `GET /audit` returns DERIVED event after
  a cascade run

**Does not touch:** PIN, correction API, weight tuning, Angular.

### Phase 2 — PinRecord + PinGuard + FactCorrectionService + correction REST (no weight-tuning, no UI)

**What ships:**
- `PinRecord` record in `kompile-graph-reasoning/.../audit/`
- `FileBackedPinStore` in `kompile-knowledge-graph/.../grounding/` — manages `factsheet-<id>-pins.jsonl`
- `PinGuard` collaborator injected into `IncrementalReasoningOrchestrator`
  (new constructor parameter, backward-compat null default for tests)
- `FactCorrectedEvent` (Spring event, parallel to `AgentFactAssertedEvent.java:44`)
- `KbCorrectionService` Spring service in `kompile-knowledge-graph` (correction flow §4.1)
- `KbCorrectionController` in `kompile-app-main` — `POST /corrections`, `DELETE /corrections/{atomKey}`,
  `GET /corrections`
- Unit tests: `PinGuardTest` (pinned atom not overwritten after cascade),
  `KbCorrectionServiceTest` (correction → PIN written → cascade skips atom)

**Does not touch:** weight-learning feedback, Angular.

### Phase 3 — Weight-learning feedback (correction→training signal)

**What ships:**
- `KbCorrectionService` gains `updateOnBatch` call after PIN write (§4.3)
- `WeightTuned` audit event emission
- `PUT /api/kb-grounding/{factSheetId}/weights/{programId}` and history endpoints
- Unit tests: `CorrectionWeightFeedbackTest` — after 3 corrections, verify `WeightStore.latest`
  differs from pre-correction weights in the expected direction

### Phase 4 — Angular: AuditTimelineComponent + Correction panel

**What ships:**
- `AuditTimelineComponent` standalone component (§6)
- `ReasoningTrailComponent` gains audit section via `AuditTimelineComponent`
- `KbGroundingPanelComponent` gains Corrections sub-tab with PIN list, correction form, weight tuning
- `ReasoningTrailService.auditTrail()` method
- Angular budget: audit timeline is text-only (no D3); add ~30KB to bundle (within 30MB ceiling
  at `angular.json`)

---

## 8. Forks — Decisions and Recommendations

### Fork (i): Strength-layer cutoffs — fixed global vs. per-fact-sheet vs. learned

**Options:**
- (A) Fixed global constants (ESTABLISHED≥0.85, PROBABLE≥0.50, SPECULATIVE≥0.20) — simplest; one enum
- (B) Per-fact-sheet configurable cutoffs via `StrengthLayerConfig` in `factsheet-<id>-config.json`
- (C) Learned per-ontology cutoffs (Platt-scale calibration per `grounding-evaluation-design.md`)

**Decision: (A) as default, (B) as override, (C) deferred.** Global defaults work for Phase 1.
Per-fact-sheet override (B) is low-effort (one config file) and covers the case where a domain
expert knows their KB is high-precision (raise ESTABLISHED to 0.95). Platt calibration (C) belongs
in `grounding-evaluation-design.md`'s calibration harness and can feed back into the cutoffs later.

### Fork (ii): PIN scope — exact atom vs. atom-rule pair vs. global rule suppression

**Options:**
- (A) Atom-scoped PIN — blocks re-derivation of exactly `atomKey`, regardless of which rule derived it
- (B) Atom-rule-scoped PIN — blocks `(atomKey, ruleId)` pair; other rules can still derive the atom
- (C) Rule-global suppression — suppresses the rule everywhere (all atoms it would derive)

**Decision: (A) as default.** Atom-scope is the safest default: the human is correcting a specific
wrong fact, not a rule. (B) is valuable for the case "rule R3 got this atom wrong but is correct
for other atoms"; recommend adding optional `suppressRuleId` to `PinRecord` in Phase 2 as opt-in.
(C) is a separate "rule disable" feature belonging in the rule-authoring surface
(`IncrementalReasoningOrchestrator.loadProjectPslRules` / PSL rule files at `dataDir/rules/`).

### Fork (iii): Audit log retention / compaction

**Options:**
- (A) Unbounded growth — audit log never compacted; archived to `factsheet-<id>-audit-<year>.jsonl` annually
- (B) Rollup on N events — when `factsheet-<id>-audit.jsonl` exceeds N lines, roll up to a summary
  (e.g., "between T1 and T2, atom X changed 14 times; final: CORRECTED by adam")
- (C) Time-window retention — keep last 90 days; older events archived or dropped

**Decision: (A) for Phase 1 with a cap flag.** The JSONL file is append-only and small per atom per day.
For a fact sheet with 1000 atoms and 5 events/atom/day, the file grows at ~1MB/day. For Phase 1, log a
warning when the file exceeds 50MB; rollup is a Phase 4 task. Rollup strategy (B) is preferred over
time-window (C) because correction events and PIN events are never disposable — they represent human
decisions that must survive indefinitely. Time-windowing could lose them.

### Fork (iv): Correction propagation scope — does correcting atom X affect atoms derived FROM X?

**Decision: YES, via the standard cascade.** When a human corrects atom X to value Y, the correction
service:
1. Sets `InferredFact(X).value = Y` and pins it.
2. Publishes `FactCorrectedEvent` which triggers `IncrementalReasoningOrchestrator.runFullReground`.
3. During re-ground, atoms derived FROM X get new values (because X changed). The PinGuard only
   protects X itself from being overwritten; downstream atoms re-derive from the corrected X.

This means the correction propagates correctly through the graph without needing a separate cascade
logic. It uses the existing `IncrementalReasoningOrchestrator` infrastructure exactly as designed in
`incremental-cascade-reasoning-design.md §3.2`.

---

## 9. Open Questions

1. **Training signal accumulation bounds**: `updateOnBatch` with `steps=3` after each correction
   incrementally shifts weights. After 100 corrections, weights may drift far from initial values.
   When should `learn()` (full retraining from all labeled corrections) replace `updateOnBatch`?
   Recommend tracking a `correctionCount` in the `WeightStore` metadata and triggering full retrain
   when `correctionCount > 50` since last full train. Not designed yet.

2. **PIN on observed facts (Fact vs. InferredFact)**: The current design pins derived facts
   (`InferredFact`). Can a human pin an *observed* fact (a `Fact` in the `FactStore`) against being
   overwritten by future crawls? This is a parallel question for `KbGroundingService.assertFact`:
   should observed facts also have a pinned flag? The `FactStore` SPI
   (`kompile-graph-reasoning/.../fol/FactStore.java`) does not version facts; adding PINs to it would
   require a bigger contract change. Deferred to Phase 3.

3. **Multi-correction conflicts**: If two humans correct the same atom to different values (concurrently),
   whose PIN wins? `KbGroundingService` uses per-fact-sheet write locks but the correction API does not
   yet integrate the OCC versioning from `grounding-api-contract-design.md §2.3`. Recommend adding an
   `expectedVersion` parameter to `POST /corrections` (like `ask_graph_assert`) in Phase 2.

4. **UI training signal feedback loop**: After a correction triggers `updateOnBatch`, the rule weights
   change. The correction panel (Phase 4 Angular) should show which weights changed and by how much
   (the `WeightTuned` audit events). But the user needs to understand what "weight 3.20 → 2.41 on R3"
   means in plain language. The `deterministicSummary()` pattern from
   `KbGroundingController.java:325` should be extended to render weight changes as
   "reduced trust in rule R3 (employer→activity) based on your correction."

5. **Correction UI placement**: The correction panel (Phase 4) could live in three places:
   (a) inside `ReasoningTrailComponent` (where the user sees WHY + WHAT HAPPENED and corrects inline),
   (b) as a standalone Corrections tab in `KbGroundingPanelComponent`,
   (c) both. Recommendation: both — inline in the trail for ad-hoc corrections, standalone tab for
   reviewing all active PINs and bulk correction management.
