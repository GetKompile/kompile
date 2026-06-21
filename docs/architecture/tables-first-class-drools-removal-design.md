# Tables as a First-Class Concept + Full Drools Removal Design

**Status:** DESIGN — 2026-06-21
**Author:** Adam Gibson / kompile architecture
**Scope:** (1) Drools engine removal from the kompile reasoning stack while keeping DRL/XLS format
import-export; (2) native `Table` primitive in `kompile-graph-reasoning` that subsumes decision
tables, reasoning traces, and tabular KB data; (3) decision-table→rules compiler targeting
`RecursiveQueryEngine` / PSL; (4) traces-as-tables unification.

**Depends on:**
- `reasoning-trail-explainability-design.md` — `ReasoningTrail` record, `EntailmentRecord`, `DerivationTree`
- `fact-store-audit-tuning-correction-design.md` — `FactAuditEvent`, `PinRecord`, `StrengthLayer`
- `grounding-api-contract-design.md` — `FactSheetKbState`, OCC versioning
- `incremental-cascade-reasoning-design.md` — `IncrementalReasoningOrchestrator` cascade hook

---

## 1. Executive Summary

Drools (RETE/kie, 9.44.0.Final) powers three `NodeExecutionType` values in the compute-graph
subsystem: `DROOLS_RULE`, `DROOLS_INFERENCE`, and `DROOLS_DECISION_TABLE`. The engine is a large
JVM-classpath addition (six KIE/Drools jars), requires two `--initialize-at-run-time` GraalVM native
flags in every FP&A project POM, uses reflection-based dispatch in the Camel processor, and cannot be
AOT-compiled safely. We already have native replacements for every semantic the engine provides:

| Drools semantic | Native replacement |
|---|---|
| DRL forward-chaining (RETE) | `FolInferenceService` + `HlMrfMapInference` (PSL HL-MRF, soft or hard) |
| Recursive inference / transitive closure | `RecursiveQueryEngine` (semi-naive Datalog fixpoint) |
| Decision tables (XLS/CSV → rules) | new `TableDecisionCompiler` targeting PSL arithmetic rules |

The **one missing piece** is a native `Table` primitive. Adding it closes the last gap and enables:

1. Decision tables compiled natively to PSL/Datalog rules.
2. Reasoning traces (`ReasoningTrail`, `FactAuditEvent`, `EntailmentRecord`) rendered and exported
   uniformly as `Table` rows without separate serialization code.
3. Tabular KB data (entity-attribute CSV imports, process-mining event logs) expressed in the same
   primitive the rule compiler consumes — closing the KB→rules→audit triad in one coherent model.

The Drools format (DRL text, XLS/CSV decision tables) is valuable as an interchange format for
existing customer assets. We keep a `DroolsFormatImporter` and `DroolsFormatExporter` — pure text
translators with zero KIE runtime dependency.

---

## 2. Drools Removal Blast Radius

### 2.1 Source module

**Module:** `kompile-compute-graph-drools`
**Path:** `kompile-app/kompile-data/kompile-compute-graphs/kompile-compute-graph-drools/`
**Maven coordinates (all scope `compile`):**

| Artifact | Version |
|---|---|
| `org.drools:drools-core` | 9.44.0.Final |
| `org.drools:drools-compiler` | 9.44.0.Final |
| `org.drools:drools-mvel` | 9.44.0.Final |
| `org.drools:drools-decisiontables` | 9.44.0.Final |
| `org.kie:kie-api` | 9.44.0.Final |
| `org.kie:kie-internal` | 9.44.0.Final |

**Classes owned:**

| Class | Path:line | Role |
|---|---|---|
| `DroolsInferenceEngine` | `drools/DroolsInferenceEngine.java:37` | Implements `ComputeGraphEngine`; compiles all DROOLS_* nodes into one `KieBase`, fires RETE |
| `DroolsNodeExecutor` | `drools/DroolsNodeExecutor.java:25` | Implements `NodeExecutor`; dispatches DROOLS_RULE/INFERENCE/DECISION_TABLE per node |
| `DroolsDecisionTableCompiler` | `drools/DroolsDecisionTableCompiler.java:41` | Converts XLS/XLSX (Base64) or CSV to `KieBase` via `DecisionTableProviderImpl` |
| `DroolsRuleCompiler` | `drools/DroolsRuleCompiler.java` | Compiles DRL string from `ComputeNode.script` to cached `KieBase` |
| `DroolsComputeGraphAutoConfiguration` | `drools/config/DroolsComputeGraphAutoConfiguration.java` | `@AutoConfiguration @ConditionalOnClass(KieServices)` — wires all four beans |
| `NodeFacts` | `drools/NodeFacts.java` | Working-memory DTO: inputs/outputs/parameters/globalState maps |
| `NamedFact` | `drools/NamedFact.java` | Single `(name, value)` pair inserted into KieSession |

### 2.2 Enum values being deleted

`NodeExecutionType` (`kompile-compute-graph-core/src/…/model/NodeExecutionType.java:22`):
- `DROOLS_RULE` — line 22
- `DROOLS_INFERENCE` — line 28
- `DROOLS_DECISION_TABLE` — line 77

**Replacement values to add in the same enum:**
- `FOL_RULE` — routes to `RecursiveQueryEngine` (crisp Datalog, deterministic)
- `PSL_RULE` — routes to `FolInferenceService` + `HlMrfMapInference` (soft rules, probabilistic)
- `TABULAR_RULE` — routes to new `TableDecisionCompiler` → PSL arithmetic rules

`StepType` (`kompile-process-engine/src/…/workflow/StepType.java:46`):
- `DROOLS_RULE` — line 46
- `DROOLS_INFERENCE` — line 48
- `DROOLS_DECISION_TABLE` — line 50

**Replacement values:** `FOL_RULE`, `PSL_RULE`, `TABULAR_RULE` (same three, parallel naming)

### 2.3 Dispatch chain — every link that must be re-wired

```
StepType.DROOLS_*/DROOLS_DECISION_TABLE
  → ProcessEngineServiceImpl.java:1605-1719
      → StepExecutionDispatcher.executeDroolsRules() / executeDroolsDecisionTable()
          → StepExecutionDispatcherImpl.java:592-643
              → DroolsNodeExecutor (resolved at StepExecutionDispatcherImpl.java:191-208)
                  → DroolsRuleCompiler / DroolsDecisionTableCompiler
                      → KieSession.fireAllRules()
```

**Full re-wiring map:**

| Existing call-site | File:line | Replace with |
|---|---|---|
| `case DROOLS_RULE: case DROOLS_INFERENCE:` | `ProcessEngineServiceImpl.java:1605` | `case FOL_RULE: case PSL_RULE:` → `dispatcher.executeFolRules(...)` |
| `case DROOLS_DECISION_TABLE:` | `ProcessEngineServiceImpl.java:1667` | `case TABULAR_RULE:` → `dispatcher.executeTabularRule(...)` |
| `executeDroolsRules()` | `StepExecutionDispatcher.java:117` | `executeFolRules(drl, facts, mode, maxRounds)` default method |
| `executeDroolsDecisionTable()` | `StepExecutionDispatcher.java:134` | `executeTabularRule(tableContent, format, facts)` default method |
| `executeDroolsRules(...)` | `StepExecutionDispatcherImpl.java:592` | delegate to `FolInferenceService` or `RecursiveQueryEngine` |
| `executeDroolsDecisionTable(...)` | `StepExecutionDispatcherImpl.java:620` | delegate to `TableDecisionCompiler.compile(table).evaluate(facts)` |
| `resolveDroolsExecutor()` | `StepExecutionDispatcherImpl.java:191` | `resolveFolExecutor()` — resolves `FolNodeExecutor` bean |
| `DroolsCamelProcessor` | `kompile-compute-graph-camel/…/DroolsCamelProcessor.java:32` | `FolCamelProcessor` — non-reflective, delegates to `FolNodeExecutor` |
| `BusinessRulesTool` | `kompile-tool-camel/…/BusinessRulesTool.java:23` | Replace `droolsNodeExecutor` + `droolsDecisionTableCompiler` fields with `FolNodeExecutor` + `TableDecisionCompiler` |
| `inferCategory("drools"/"rules"/"decision table")` | `StepExecutionDispatcherImpl.java:953` | keep label `"rules"` for TABULAR_RULE/FOL_RULE/PSL_RULE |
| `RulesCommand` (CLI) | `kompile-agent-cli/…/RulesCommand.java:34` | No change needed — it calls REST API only, no KIE imports |

### 2.4 FP&A project native-image footprint

Both `kompile-fpna-v3/project/pom.xml:825` and `kompile-fpna-v4/project/pom.xml:821` carry:

```xml
<buildArg>--initialize-at-run-time=org.drools</buildArg>
<buildArg>--initialize-at-run-time=org.kie</buildArg>
<buildArg>-H:IncludeResources=META-INF/services/org\.kie\..*</buildArg>
<buildArg>-H:IncludeResources=META-INF/services/org\.drools\..*</buildArg>
```

These four lines are deleted in the migration. No other FP&A-specific drools references exist; both
projects inherit the dependency transitively through `kompile-app-main`. Once the module is removed
from the parent BOM and from `kompile-compute-graphs/pom.xml:18`, the transitive dep disappears
automatically. The native image gains ~30MB (approximate drools+kie jar weight).

### 2.5 Parent BOM entries to remove

- `kompile-app/pom.xml:688` — BOM entry for `kompile-compute-graph-drools`
- `kompile-app/kompile-data/kompile-compute-graphs/pom.xml:18` — `<module>` entry
- `kompile-app/kompile-middleware/kompile-tools/kompile-tool-camel/pom.xml:35` — optional dep on drools module
- `kompile-app/kompile-data/kompile-compute-graphs/kompile-compute-graph-camel/pom.xml:103` — optional dep

### 2.6 Complete blast-radius table

| Reference | Location | Action |
|---|---|---|
| `DROOLS_RULE`, `DROOLS_INFERENCE` in `NodeExecutionType` | `NodeExecutionType.java:22,28` | Replace with `FOL_RULE`, `PSL_RULE` |
| `DROOLS_DECISION_TABLE` in `NodeExecutionType` | `NodeExecutionType.java:77` | Replace with `TABULAR_RULE` |
| `DROOLS_RULE`, `DROOLS_INFERENCE`, `DROOLS_DECISION_TABLE` in `StepType` | `StepType.java:46,48,50` | Replace with `FOL_RULE`, `PSL_RULE`, `TABULAR_RULE` |
| `case DROOLS_RULE / DROOLS_INFERENCE` dispatch | `ProcessEngineServiceImpl.java:1605` | Rewire to FOL/PSL dispatcher |
| `case DROOLS_DECISION_TABLE` dispatch | `ProcessEngineServiceImpl.java:1667` | Rewire to TableDecisionCompiler dispatcher |
| `executeDroolsRules()` default method | `StepExecutionDispatcher.java:117` | Rename/replace `executeFolRules()` |
| `executeDroolsDecisionTable()` default method | `StepExecutionDispatcher.java:134` | Rename/replace `executeTabularRule()` |
| `executeDroolsRules()` impl | `StepExecutionDispatcherImpl.java:592` | Delegate to `FolInferenceService` / `RecursiveQueryEngine` |
| `executeDroolsDecisionTable()` impl | `StepExecutionDispatcherImpl.java:620` | Delegate to `TableDecisionCompiler` |
| `resolveDroolsExecutor()` | `StepExecutionDispatcherImpl.java:191` | Replace with `resolveFolExecutor()` |
| `DroolsCamelProcessor` | `DroolsCamelProcessor.java:32` | Replace with `FolCamelProcessor` (no reflection) |
| `BusinessRulesTool` | `BusinessRulesTool.java:23` | Replace drools fields with FOL/Table fields |
| `DroolsComputeGraphAutoConfiguration` | `drools/config/` | DELETE |
| `DroolsInferenceEngine`, `DroolsNodeExecutor`, `DroolsDecisionTableCompiler`, `DroolsRuleCompiler`, `NodeFacts`, `NamedFact` | `drools/` package | DELETE (6 classes) |
| `kompile-compute-graph-drools` module | `kompile-compute-graphs/pom.xml:18` | DELETE module |
| BOM entry | `kompile-app/pom.xml:688` | DELETE |
| camel optional dep | `kompile-compute-graph-camel/pom.xml:103` | DELETE |
| camel-tool optional dep | `kompile-tool-camel/pom.xml:35` | DELETE |
| FP&A v3 native flags | `kompile-fpna-v3/project/pom.xml:825` | DELETE 4 lines |
| FP&A v4 native flags | `kompile-fpna-v4/project/pom.xml:821` | DELETE 4 lines |

---

## 3. The `Table` Primitive

### 3.1 Rationale: three things, one primitive

A `Table` is a rectangular structure of typed, named columns and a sequence of typed rows. Three
currently-separate structures are all instances of this abstraction:

| Use case | Current form | Mapped to Table |
|---|---|---|
| **Decision tables** | XLS/CSV in `ComputeNode.script`; compiled via `DroolsDecisionTableCompiler` | Condition columns → rule body; conclusion columns → rule head |
| **Reasoning traces** | `ReasoningTrail`, `EntailmentRecord`, `FactAuditEvent` — separate records per engine | One trace = one Table; each step/event = one row |
| **Tabular KB data** | Entity-attribute CSV imports, process-mining event logs (XES/CSV), entity-browser table nodes | Rows = entities/events; columns = attributes/timestamps |

Making `Table` first-class means:
- One import/export path for all three (CSV, JSON, DRL format).
- One UI component (`TableViewComponent`) that renders decision tables, traces, and KB data uniformly.
- The `ReasoningTrail` and `FactAuditEvent` designed in the two existing design docs can be
  *projected to* `Table` for display without duplicating serialization logic.

### 3.2 `Table` record design (infra-free lib)

**Location:** `kompile-graph-reasoning/src/main/java/ai/kompile/graph/reasoning/table/`

```java
// ai.kompile.graph.reasoning.table.Table
public record Table(
    String id,                  // UUID; stable identity for caching/versioning
    String name,                // human-readable label
    TableKind kind,             // DECISION, TRACE, DATA (see 3.3)
    List<TableColumn> columns,  // ordered; never empty
    List<TableRow> rows,        // may be empty
    Map<String, String> metadata, // provenance: factSheetId, runId, sourceFile, etc.
    Instant createdAt
) {}

public record TableColumn(
    String name,
    ColumnRole role,       // CONDITION, CONCLUSION, ANNOTATION (for decision tables);
                           // FIELD (for traces/data)
    ColumnType type,       // STRING, DOUBLE, BOOLEAN, INSTANT, OBJECT
    String description
) {}

public enum ColumnRole { CONDITION, CONCLUSION, ANNOTATION, FIELD }
public enum ColumnType { STRING, DOUBLE, BOOLEAN, INSTANT, OBJECT }
public enum TableKind  { DECISION, TRACE, DATA }

public record TableRow(
    String rowId,           // UUID; stable for audit linkage
    Map<String, Object> cells  // columnName → value (typed per ColumnType)
) {}
```

**Hit policy** (decision tables only, stored in `metadata.get("hitPolicy")`):
- `FIRST` — stop at first matching condition row (default for DECISION tables)
- `ALL` — collect all matching rows
- `PRIORITY` — sort by `metadata.get("priority")` column value, return highest

**No Spring, no JPA, no Jackson** — hand-rolled JSON serialization following the `InferredFact`
pattern (`InferredFact.java:159-205`). The `Table.toJson()` / `Table.fromJson(String)` pair lives in
the same file.

### 3.3 TableKind semantics

| Kind | Columns pattern | Rows meaning | Primary consumer |
|---|---|---|---|
| `DECISION` | CONDITION cols + CONCLUSION cols (+ optional ANNOTATION) | Each row = one rule: "if all CONDITIONs match then assert CONCLUSIONs" | `TableDecisionCompiler` |
| `TRACE` | FIELD cols (step, confidence, rule, actor, timestamp, etc.) | Each row = one reasoning step / audit event | `TraceTableProjector` |
| `DATA` | FIELD cols (entity attributes, event attributes) | Each row = one entity / event / KB fact | Tabular KB import, process-mining event log |

---

## 4. Decision-Table → Rules Compiler

### 4.1 `TableDecisionCompiler`

**Location:** `kompile-graph-reasoning/src/main/java/ai/kompile/graph/reasoning/table/TableDecisionCompiler.java`
**No Spring, no KIE** — pure logic.

**Algorithm:**

For each `TableRow` in a `Table` of kind `DECISION`:
1. Extract all `CONDITION` column cells → `List<GroundAtom>` (antecedents).
   - Numeric conditions: `amount > 1000` → PSL `ArithmeticRule` with threshold atom.
   - String/enum conditions: `category == "HIGH"` → equality predicate atom.
   - Wildcards (blank cell): skip predicate (universally satisfied).
2. Extract all `CONCLUSION` column cells → `List<PslAtom>` (consequents).
3. Assign a `weight` to each compiled rule:
   - Hard rules (weight = `DEFAULT_HARD_WEIGHT = 1e6`) when all conditions are crisp equality.
   - Soft rules (weight from `metadata.get("weight")` or `DEFAULT_SOFT_WEIGHT = 2.0`) when any
     condition is a range/inequality (the rule is satisfiable to a degree).
4. Wrap in a `PslRule` and add to a `PslProgram`.

Hit policy is enforced via a generated mutual-exclusion constraint:
- `FIRST`: only the lowest-index matching row's consequents are asserted (synthetic ordering atom).
- `ALL`: all matching rows assert their consequents simultaneously.
- `PRIORITY`: `metadata.get("priority")` column is mapped to a PSL rule weight; higher weight wins.

**Output:** `PslProgram` — directly consumable by `HlMrfMapInference.solve(program, factStore)`.

For crisp-only tables (all conditions are equality, all conclusions are boolean), the compiler can
emit `DatalogRule` objects instead, routing through `RecursiveQueryEngine.evaluate(rules, edb)`.
The compiler chooses the target based on whether any condition involves a numeric range or a soft
weight in the metadata.

### 4.2 `FolNodeExecutor` — the Drools-free node executor

**Location:** `kompile-compute-graph-core/src/main/java/ai/kompile/compute/graph/engine/FolNodeExecutor.java`
(new, in `kompile-compute-graph-core`, no new module needed)

```java
public class FolNodeExecutor implements NodeExecutor {
    // Injected: FolInferenceService, RecursiveQueryEngine reference, TableDecisionCompiler

    @Override
    public Set<NodeExecutionType> supportedTypes() {
        return Set.of(FOL_RULE, PSL_RULE, TABULAR_RULE);
    }

    @Override
    public ExecutionResult execute(ComputeNode node, Map<String, Object> inputs, ExecutionContext ctx) {
        return switch (node.getExecutionType()) {
            case FOL_RULE    -> executeFol(node, inputs, ctx);     // RecursiveQueryEngine
            case PSL_RULE    -> executePsl(node, inputs, ctx);     // FolInferenceService + HlMrfMapInference
            case TABULAR_RULE -> executeTable(node, inputs, ctx);  // TableDecisionCompiler
            default -> throw new UnsupportedOperationException(...);
        };
    }
}
```

`executeFol` converts `ComputeNode.script` (Datalog text) to `List<DatalogRule>` via a lightweight
parser (the same format used in `RecursiveQueryEngine.DatalogRule`), wraps inputs as an `EdbProvider`,
and calls `RecursiveQueryEngine.evaluate(rules, edb)`. The result `FixpointResult` is mapped to
`ExecutionResult.outputs`.

`executePsl` converts `ComputeNode.script` (PSL text: `weight: body -> head ^2`) to a `PslProgram`,
wraps inputs as atom truth values in a `FactStore`, calls `HlMrfMapInference.solve()`, and maps
solved atom values to `ExecutionResult.outputs`.

`executeTable` reads the JSON `Table` from `ComputeNode.script`, calls
`TableDecisionCompiler.compile(table)` to get a `PslProgram`, evaluates it as in `executePsl`.

### 4.3 `FolComputeGraphAutoConfiguration`

**Location:** `kompile-compute-graph-core/src/main/java/ai/kompile/compute/graph/engine/config/FolComputeGraphAutoConfiguration.java`
`@AutoConfiguration` — always active (no conditional). Registers `FolNodeExecutor`, `TableDecisionCompiler`.
Replaces `DroolsComputeGraphAutoConfiguration` which was conditional on `KieServices` being on classpath.

---

## 5. Traces as Tables

### 5.1 Design decision: PROJECT, not BECOME

The `ReasoningTrail`, `FactAuditEvent`, and `EntailmentRecord` records stay as they are — they are
the canonical, strongly-typed in-memory model. A lightweight `TraceTableProjector` converts them to
`Table` on demand for display, export, and I/O. The alternative — making `ReasoningTrail` internally
backed by `Table` rows — would break the existing test contracts (252+214+7 tests use these records
directly) and add per-step allocation overhead in the hot inference path.

**Decision: TRACE records PROJECT to Table, not BECOME Table.**

### 5.2 `TraceTableProjector`

**Location:** `kompile-graph-reasoning/src/main/java/ai/kompile/graph/reasoning/table/TraceTableProjector.java`
No Spring, all static methods.

```java
public final class TraceTableProjector {

    // ReasoningTrail → Table (kind=TRACE)
    // Columns: step, inferenceMode, confidence, ruleApplied, atomKey, sourceProvenance, notes
    // Each DerivationTree node = one row; EntailmentRecord entries = one row each
    public static Table fromReasoningTrail(ReasoningTrail trail) { ... }

    // FactAuditEvent list → Table (kind=TRACE)
    // Columns: eventId, eventType, atomKey, occurredAt, actor, valueBefore, valueAfter,
    //          confidenceBefore, confidenceAfter, strengthLayerBefore, strengthLayerAfter,
    //          pinnedAfter, ruleId, correctionReason
    public static Table fromAuditEvents(List<FactAuditEvent> events, String factSheetId) { ... }

    // List<EntailmentRecord> → Table (kind=TRACE)
    // Columns: atomKey, posterior, supportingFindingKeys (joined), activatedRules (joined),
    //          computedAt, inferenceRunId
    public static Table fromEntailments(List<EntailmentRecord> records) { ... }

    // InferenceStep list (from domain/InferenceStep.java) → Table (kind=TRACE)
    public static Table fromInferenceSteps(List<InferenceStep> steps, String trailId) { ... }
}
```

The projected `Table` carries `metadata`:
- `"sourceType"` → `"REASONING_TRAIL"` / `"AUDIT_EVENTS"` / `"ENTAILMENTS"`
- `"runId"` → `trail.runId()` (join key for `FactAuditEvent` linkage per audit design §2.3)
- `"factSheetId"` → fact-sheet scope

### 5.3 What "traces are tables" enables

- **Export:** a single `Table.toJson()` call covers reasoning trails, audit logs, and entailments —
  the same serialization the Drools format exporter uses for decision-table rows.
- **Display:** the planned `<app-reasoning-trail>` Angular component (`reasoning-trail-explainability-design.md §5`)
  can render `Table` rows directly in its flat "proof tree" fallback view — no new component needed.
- **MCP tool surface:** `ask_graph` can return a `Table` of trace rows, which is more LLM-friendly
  than the nested `DerivationTree` JSON.
- **CSV download:** `GET /api/explain/{trailId}/table.csv` — one endpoint, zero extra backend code
  once `TraceTableProjector` exists.

### 5.4 `InferenceStep` already exists

`kompile-graph-reasoning/src/main/java/ai/kompile/graph/reasoning/domain/InferenceStep.java` is an
existing record in the reasoning library. It already captures `stepType`, `description`, and
presumably a timestamp. `TraceTableProjector.fromInferenceSteps()` projects it to a `Table` row set,
so any engine that produces `InferenceStep` lists (causal chains, Bayesian VE steps, MEBN SSBN steps)
gets table export for free.

---

## 6. Drools-Format I/O (No Runtime Dependency)

The goal: round-trip DRL/XLS/CSV files without importing a single KIE class.

### 6.1 `DroolsFormatImporter`

**Location:** `kompile-graph-reasoning/src/main/java/ai/kompile/graph/reasoning/table/io/DroolsFormatImporter.java`

**DRL import → `Table` (kind=DECISION):**

DRL rule syntax is regular enough to parse with hand-rolled string scanning:
```
rule "R1"
  when
    $f : SomeFact(value > 100)
  then
    $f.setOutput("HIGH");
end
```
Each `rule` block → one `TableRow`. The `when` clause → CONDITION cells. The `then` clause →
CONCLUSION cells (action string stored verbatim). The `rule` name → `rowId`. Non-rule DRL (`package`,
`import`, `global` declarations) → `Table.metadata`.

**XLS/CSV import → `Table` (kind=DECISION):**

Standard Drools decision-table XLS format has a well-documented cell layout (condition columns
tagged `C`, action columns tagged `A`, priority column tagged `P`). Apache POI (already on classpath
for the existing XLSX barcode/entity extraction path) reads the worksheet. Each data row → `TableRow`.
Column headers and tags → `TableColumn`. This is the only non-trivial parsing: the RuleSet, RuleTable,
and column tag rows must be recognized. The implementation is ~200 lines, far lighter than the
`drools-decisiontables` JAR.

**CSV import:** plain `RFC 4180` CSV. First row = column headers. Second row optionally = column
roles (C/A/P). Data rows start after. No KIE dependency.

**Fork (iii):** Do we keep `drools-decisiontables` (the KIE XLS parser) as a format-only optional
dependency, or hand-roll the XLS parse with Apache POI?
Recommendation: **hand-roll with Apache POI**. Apache POI is already on the classpath (used in
tabular import and barcode features). This eliminates the last KIE runtime dependency from any
module, making the format-I/O path fully AOT-safe. The POI approach is ~200 lines vs. shipping a
2MB KIE JAR for format parsing alone.

### 6.2 `DroolsFormatExporter`

**Location:** `kompile-graph-reasoning/src/main/java/ai/kompile/graph/reasoning/table/io/DroolsFormatExporter.java`

**`Table` (kind=DECISION) → DRL:**

```java
public static String toDrl(Table table, String packageName) {
    // For each TableRow:
    //   emit "rule <rowId>\n  when\n    <condition atoms>\n  then\n    <conclusion actions>\nend\n"
    // Prepend package/import preamble from table.metadata()
}
```

**`Table` → CSV:** straightforward RFC 4180 with header row.

**`Table` → XLS:** Apache POI again. Write `RuleSet` / `RuleTable` header rows in standard Drools
decision-table format so the exported XLS can be imported by any Drools installation.

### 6.3 REST endpoint

`POST /api/rules/import` — body: DRL text or multipart file (XLS/CSV). Returns a `Table` (kind=DECISION).
`GET /api/rules/{tableId}/export?format=drl|csv|xls` — exports the stored `Table` in the requested format.

Controller: `RulesImportExportController` in `ai.kompile.app.web.controllers.rules` (add to
`GlobalExceptionHandler.basePackages`; see `reference_global_exception_handler_scope.md`).

---

## 7. Migration Sequencing

The migration must not break FP&A or process-engine at any point. The safe order is:

### Phase 1 — Build native replacements (no Drools removal yet)

1. Add `Table`, `TableColumn`, `TableRow`, `TableKind`, `ColumnRole`, `ColumnType` records to
   `kompile-graph-reasoning/…/table/`.
2. Add `TableDecisionCompiler` (PSL/Datalog target) to `kompile-graph-reasoning/…/table/`.
3. Add `TraceTableProjector` to `kompile-graph-reasoning/…/table/`.
4. Add `DroolsFormatImporter` and `DroolsFormatExporter` to `kompile-graph-reasoning/…/table/io/`.
5. Add `FolNodeExecutor` to `kompile-compute-graph-core/…/engine/` with
   `FOL_RULE`, `PSL_RULE`, `TABULAR_RULE` added to `NodeExecutionType`.
6. Add `FolComputeGraphAutoConfiguration`.
7. Tests: unit tests for `TableDecisionCompiler` (FIRST/ALL/PRIORITY hit policy), `TraceTableProjector`,
   `DroolsFormatImporter` round-trip (DRL → Table → DRL), `FolNodeExecutor` per type.
8. **Do not remove Drools yet.** Both executors coexist; `DroolsComputeGraphAutoConfiguration` stays
   conditional on `KieServices` presence, `FolComputeGraphAutoConfiguration` is always active.

### Phase 2 — Migrate process-engine dispatch

1. Add `FOL_RULE`, `PSL_RULE`, `TABULAR_RULE` to `StepType.java` alongside the existing Drools values.
2. Add `executeFolRules()` and `executeTabularRule()` default methods to `StepExecutionDispatcher.java`.
3. Implement them in `StepExecutionDispatcherImpl.java` (delegating to `FolNodeExecutor`).
4. Add `case FOL_RULE: case PSL_RULE:` and `case TABULAR_RULE:` dispatch branches in
   `ProcessEngineServiceImpl.java` (parallel to existing `DROOLS_*` branches — both run simultaneously
   during transition).
5. Migrate `FolCamelProcessor` (non-reflective replacement for `DroolsCamelProcessor`).
6. Migrate `BusinessRulesTool` fields.
7. Integration test: a process-engine workflow with `StepType.FOL_RULE` completes successfully.

### Phase 3 — Migrate FP&A consumers

1. Audit any existing `ComputeNode` records in the DB or test fixtures that use `DROOLS_*`
   `NodeExecutionType` values. Add a schema migration that remaps:
   - `DROOLS_RULE` → `FOL_RULE`
   - `DROOLS_INFERENCE` → `PSL_RULE`
   - `DROOLS_DECISION_TABLE` → `TABULAR_RULE`
   DRL scripts in `ComputeNode.script` stay as-is; `FolNodeExecutor.executeFol()` parses them
   natively using the same DRL-import logic from `DroolsFormatImporter`.
2. Update `kompile-fpna-v3` and `kompile-fpna-v4` test fixtures if any use `DROOLS_*` types.
3. Verify full FP&A build and tests pass.

### Phase 4 — Remove Drools module and KIE deps

1. Delete `kompile-compute-graph-drools/` directory (all 7 classes + pom.xml).
2. Remove `kompile-app/kompile-data/kompile-compute-graphs/pom.xml:18` module entry.
3. Remove `kompile-app/pom.xml:688` BOM entry.
4. Remove optional deps from `kompile-compute-graph-camel/pom.xml:103` and
   `kompile-tool-camel/pom.xml:35`.
5. Remove `DROOLS_RULE`, `DROOLS_INFERENCE`, `DROOLS_DECISION_TABLE` from `NodeExecutionType` and
   `StepType` (they are now unreferenced).
6. Remove `case DROOLS_*` branches from `ProcessEngineServiceImpl.java:1605-1719`.
7. Remove `executeDroolsRules()` / `executeDroolsDecisionTable()` from `StepExecutionDispatcher.java`
   and `StepExecutionDispatcherImpl.java`.
8. Remove `resolveDroolsExecutor()` from `StepExecutionDispatcherImpl.java:191`.
9. Remove `DroolsCamelProcessor` from `kompile-compute-graph-camel` (replaced by `FolCamelProcessor`).
10. Remove `--initialize-at-run-time=org.drools/org.kie` and `IncludeResources` lines from
    `kompile-fpna-v3/project/pom.xml:825` and `kompile-fpna-v4/project/pom.xml:821`.
11. Full build + test pass. Native image build should be ~30MB smaller.

### Phase 5 — Drools format I/O REST + Table UI (optional, addable later)

1. `RulesImportExportController` with `POST /api/rules/import` and `GET /api/rules/{id}/export`.
2. Angular `TableViewComponent` — standalone, renders `Table` rows in mat-table; embeds in
   existing process-engine decision-table UI and reasoning-trail proof tree.
3. MCP tool: `table_to_rules(tableJson)` → evaluates a `Table` as PSL rules and returns results.

---

## 8. Forks — Decisions and Recommendations

### Fork (i): Trace types Table-backed vs. Table-projected

**Options:**
- **(A) PROJECTED** — `ReasoningTrail`, `FactAuditEvent`, `EntailmentRecord` remain strongly-typed
  records; `TraceTableProjector` converts on demand.
- **(B) BACKED** — Internally store trace data as `Table` rows; typed accessors are projections over
  the row map.

**Decision: (A) PROJECTED.** The existing records already have 252+214+7 tests against their record
contracts. Backing them with `Table` rows changes the constructor, breaks the existing `toJson`/`fromJson`
contract, and adds per-step `Map` allocation in the inference hot path. The projection approach is
zero-cost at inference time and still yields full `Table` semantics for display and export.
Revisit (B) only if a future performance audit shows the projection overhead is material.

### Fork (ii): Decision-table hit-policy default

**Options:**
- **(A) FIRST** — stop at first matching row (most familiar to Drools users; also the Drools default)
- **(B) ALL** — collect all matching rows (semantically richer; needed for multi-output tables)

**Decision: (A) FIRST as default**, overridable via `Table.metadata.get("hitPolicy")`. Most existing
Drools decision tables use FIRST semantics. Storing it in metadata keeps the `Table` record agnostic
and allows per-table override without enum proliferation.

### Fork (iii): XLS import — hand-roll POI vs. keep `drools-decisiontables` as format-only dep

**Options:**
- **(A) HAND-ROLL with Apache POI** — ~200 lines; zero KIE runtime; fully AOT-safe.
- **(B) OPTIONAL KIE DEP** — keep `drools-decisiontables` as `<optional>true</optional>` dep in
  a new `kompile-graph-reasoning-drools-format` module; only loaded when XLS import is requested.

**Decision: (A) HAND-ROLL.** Apache POI is already on the classpath. The Drools XLS decision-table
format is documented and simple to parse (~6 row types: RuleSet, RuleTable, column-tag row, header
row, type row, data rows). An optional KIE dep contradicts the goal of fully eliminating the KIE
transitive closure. Option (B) would still require the `--initialize-at-run-time` native flags for
any build that might encounter the dep.

### Fork (iv): DRL input language for `FOL_RULE` nodes

When a `ComputeNode` of type `FOL_RULE` contains DRL text (migrated from `DROOLS_RULE`), the
`FolNodeExecutor` must parse it. Options:

- **(A) DRL-to-Datalog translator** — `DroolsFormatImporter.toDrl()` parses the `when`/`then` blocks
  into `DatalogRule` objects. Handles the common case (single-fact pattern matching + output
  assignment); unsupported Drools idioms (complex MVEL, agenda groups, salience) fall back to error.
- **(B) Switch all migrated nodes to native Datalog syntax** — requires a one-time script to
  translate existing `ComputeNode.script` fields. Cleaner long-term, but requires human review
  for complex rules.

**Decision: (A) for migration safety; (B) as follow-on.** The DRL-to-Datalog translator covers 80%+
of real decision-table DRL (condition = fact pattern match, action = output set). Complex DRL that
cannot be translated surfaces a clear `UnsupportedDrlFeatureException` with a line-level explanation.
A migration CLI command (`kompile rules migrate --dry-run`) reports which nodes need manual
rewrite before deletion of the Drools module.

---

## 9. Open Questions

1. **`ComputeNode.script` DRL parsing coverage**: How much of the existing `DROOLS_RULE` DRL in
   the DB uses Drools-specific idioms (salience, agenda-group, MVEL modify, `from`, `collect`,
   `accumulate`)? An audit query against the `compute_nodes` table (grouped by script complexity
   heuristic) is needed before Phase 3 to estimate the manual-rewrite surface. Recommend: run
   `DroolsFormatImporter.toTable(script)` on all existing scripts and count `UnsupportedDrlFeatureException`
   hits.

2. **PSL numeric arithmetic vs. Drools numeric conditions**: Drools decision tables support
   `amount > 1000` as a condition. PSL arithmetic rules (`ArithmeticRule.java`) support linear
   inequalities over soft-truth atoms, not raw numeric comparisons over plain Java doubles.
   The `TableDecisionCompiler` must bridge this: a raw numeric condition is represented as a
   crisp Datalog atom `greaterThan(amount, 1000)` with an `EdbProvider` that evaluates it to
   `{true, false}`. This is straightforward but requires `RecursiveQueryEngine.EdbProvider` to
   support built-in predicate resolution (currently only custom predicates via `Map<String, Set<?>>`).
   A `BuiltinEdbProvider` wrapper is needed in Phase 1.

3. **`Table` persistence**: Where are `Table` instances stored between the import endpoint and the
   export endpoint? Options: (a) in-process `ConcurrentHashMap` (lost on restart); (b) as a
   JSON file in `~/.kompile/tables/<id>.json` (durable, portable); (c) as a `ComputeNode` with
   `TABULAR_RULE` type (leverages existing DB storage). Recommendation: (b) for imported/authored
   tables, (c) for tables that are directly executable in a workflow. The persistence design should
   piggyback on the `graph-as-asset` portability infrastructure (Phase 1 roadmap at
   `plans/humming-zooming-llama.md`).

4. **`<app-table-view>` Angular component**: The UI for `Table` should be a standalone Angular
   component that works for all three `TableKind` values. The existing `TableRendererComponent`
   (`project_index_browser_table_rendering.md`) in the index browser renders `TABLE` graph nodes —
   it is a candidate to generalize rather than create a new component. Needs an audit of its current
   `@Input` interface before Phase 5.

5. **`NodeFacts` / `NamedFact` replacements**: These two Drools DTOs (`drools/NodeFacts.java` and
   `drools/NamedFact.java`) serve as working-memory containers. The `FolNodeExecutor` needs equivalent
   input/output DTOs. `NodeFacts` maps cleanly to a `Map<String, Object>` pair (inputs + outputs) —
   no dedicated record needed. `NamedFact` maps to `Map.Entry<String, Object>`. The Camel processor
   that reads `kompile_drools_rulesFired` from the exchange header needs a new header key
   `kompile_fol_rulesFired` (or `kompile_rules_fired` for neutrality).

---

## 10. Relationship to Existing Design Docs

| Existing doc | Interaction with this design |
|---|---|
| `reasoning-trail-explainability-design.md` | `ReasoningTrail` and `EntailmentRecord` PROJECT to `Table` via `TraceTableProjector`; no change to those records |
| `fact-store-audit-tuning-correction-design.md` | `FactAuditEvent` and `PinRecord` PROJECT to `Table`; the `FileBackedAuditLog` writes JSONL, `TraceTableProjector` reads for display |
| `grounding-api-contract-design.md` | `POST /api/rules/import` is a new endpoint, not overlapping with `/api/kb-grounding/*` |
| `incremental-cascade-reasoning-design.md` | `FactCorrectedEvent` cascade unchanged; the new `FOL_RULE` step type enters the cascade via the same hook |
| `process-mining-design.md` | Process-mining event logs are `Table` of kind `DATA`; `TraceTableProjector.fromInferenceSteps()` covers mining inference steps |
| `ontology-enrichment-roadmap.md` | Decision tables can express ontology rules (class membership, property ranges); `TableDecisionCompiler` → PSL is the native path |
| `recursive-query-design.md` | `RecursiveQueryEngine` is the crisp-Datalog backend for `FOL_RULE` nodes and for crisp decision tables |
