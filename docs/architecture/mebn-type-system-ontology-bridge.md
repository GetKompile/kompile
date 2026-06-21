# MEBN Type System & Ontology Bridge — Architecture Design

**Date:** 2026-06-21
**Status:** Design proposal — no code yet
**Scope:** (1) a richer, infra-free type system built on top of MEBN's `EntityType` backbone, living entirely inside `kompile-graph-reasoning`; (2) a bridge adapter that connects that type system to the existing KG `OntologySchema`, closing the "derived dead-end" governance gap described in the ontology-enrichment-roadmap.

---

## 1. Current State — Three Disconnected Type Worlds

### World 1 — `GraphEntity.type()`: the live data carrier

`model/GraphEntity.java:38`:
```
String type();   // "Coarse type/label of this entity (e.g. "DOCUMENT", "Person"...)"
```
This is the canonical type string that every node in the live knowledge graph carries. It is a **flat, free-form string** — no hierarchy, no validation, no attribute schema attached. By the time a `GraphNode` is projected into a `ReasoningGraph` via `KnowledgeGraphReasoningAdapter`, the only type information available is this bare string. The adapter at `knowledgegraph/reasoning/KnowledgeGraphReasoningAdapter.java:54` reads `GraphNode.getMetadata()` to compose the `GraphEntity`, preserving `type()` but carrying no hierarchy forward.

### World 2 — MEBN `EntityType`: structured but thin

`mebn/EntityType.java:27-143`:
- `typeName` (String) — aligns with `GraphEntity.type()` by case-insensitive match
- `entityIds` (Set<String>) — the membership index, built by `fromGraph(ReasoningGraph, typeName)` at line 54, which iterates `graph.entities()` and filters on `typeName.equalsIgnoreCase(e.type())`
- `superType` (EntityType) — single-inheritance isA pointer, traversed by `isSubtypeOf()` at line 117

`MTheory.java:31` holds `Map<String, EntityType>` and `getMostSpecificMFrag()` at line 153 walks the `superType` chain to dispatch to the right MFrag. This polymorphic dispatch **already works correctly** (tested per `mebn-engine-gaps.md`, Gap 2 closed 2026-06-21).

`RandomVariable.java:43` carries `List<EntityType> argumentTypes` — typed RV arguments with explicit logical variable names via `argVars`. This is the MEBN-specific schema for what types a predicate ranges over.

**The gap:** `EntityType` is structurally thin — it knows only `typeName`, `superType`, and a membership set. It has no attribute schema (what properties instances should carry), no relation domain/range constraints (what other types they can relate to), and no cardinality constraints. It is also manually assembled: callers call `fromGraph(graph, typeName)` to populate membership, then separately call `setSuperType()` to wire hierarchy. There is no way to ask the hierarchy itself "what are all the subtypes of Person?" — only "is type X a subtype of Y?" via the upward chain walk.

`FolRule.entityTypeScope()` at `fol/FolRule.java:82` provides a flat string scope — a rule applies only to entities whose `type()` matches the scope string. This is the first-order equivalent of type-restricted quantification, but it only matches exact strings, not subtypes. So a rule scoped to "Person" does not fire for entities of type "Employee" even if Employee is-a Person.

`OntologicalConstraintBuilder` at `psl/OntologicalConstraintBuilder.java:56` already models PSL-level subsumption (`addSubsumption(sub, super)` → hard rule `Type(X, Sub) → Type(X, Super)`) and mutual exclusion. But these are **manually declared string pairs** — there is no mechanism to derive them automatically from the type hierarchy.

### World 3 — KG `OntologySchema`: rich but disconnected

`process/ontology/OntologySchema.java:39`:
- `List<EntityTypeDefinition> entityTypes` — each has `name`, `description`, `EntityClassification` (REFERENCE/TRANSACTIONAL/PATTERN/CONTROL/METRIC/ACTOR), and `List<FieldDefinition> fields` (typed, constrained: required/immutable/primaryKey/regex/fkReference/enumValues/min/max)
- `List<RelationshipTypeDefinition> relationshipTypes` — typed directed edges with `sourceEntityType`, `targetEntityType`, `Cardinality`
- `List<ValidationRule> globalRules` — SpEL-based cross-field rules

`process/ontology/EntityTypeDefinition.java:36` is the richest type record: it has `List<FieldDefinition> fields` and `List<ValidationRule> rules` per type. But it has **no `superType` / isA field** — `OntologySchema` has no hierarchy concept. The classification enum (`EntityClassification`) is a coarse bucket, not a subtype relation.

**The disconnection (three-way):**

| Property | `GraphEntity.type()` | `EntityType` | `OntologySchema.EntityTypeDefinition` |
|---|---|---|---|
| Source | Live graph nodes (LLM-extracted string) | MEBN lib (manually assembled) | Process engine (LLM-derived or wizard) |
| Hierarchy | None | Single `superType` pointer | None |
| Attribute schema | In `attributes()` Map (untyped) | None | `List<FieldDefinition>` (typed + constrained) |
| Relation constraints | None | None | `RelationshipTypeDefinition` with domain/range/cardinality |
| Membership | All nodes with matching `type()` | `entityIds` set (a computed index) | Implicit: nodes whose `type()` string matches `name` |
| Governance | None | None | `OntologyConformanceValidator` validates runData maps only; graph nodes never validated |
| Consumers | Every KG query/traversal | MEBN/PSL reasoners | `ProcessDefinition`, `AgentSpec` (as bare string FK) |

Root cause of disconnection (per `ontology-enrichment-roadmap.md`): the ontology is **derived from the graph but never flows back to govern it**. `OntologyDerivationService.derive()` returns an unsaved draft. `NamedGraph.schemaJson` is inert (never read). `EntityType.superType` is set manually in tests and has no code path from `OntologySchema`. `FolRule.entityTypeScope()` matches flat strings, not subtypes. There is no shared type vocabulary.

---

## 2. Proposed Richer Lib Type System — Phase 1 (infra-free, lib-only)

### Design decision: single inheritance

The `superType` pointer on `EntityType` already implies single inheritance. Multiple inheritance adds complexity (diamond resolution for MFrag dispatch, attribute schema merge semantics, PSL subsumption rule combinatorics) with limited practical gain for KG entity types. **Decision: single-parent isA, like OWL's `rdfs:subClassOf` with a tree structure.** Mixins/role facets (e.g. "is also an ACTOR") are handled via tags on `GraphEntity` or PSL predicates, not the type hierarchy.

### 2.1 New package: `mebn/type/`

All new classes live in `ai.kompile.graph.reasoning.mebn.type`. No new runtime dependencies — only the four already allowed (nd4j-api, jackson-annotations, slf4j, lombok). The type system is computed over a `ReasoningGraph` and declared type information (explicit declarations for hierarchy + attribute schemas). `EntityType` in `mebn/EntityType.java` is kept as-is for backward compatibility.

#### `TypeHierarchy` — the backbone

A computed-and-declared type graph. It has two data sources:
- **Declared hierarchy**: explicit `registerSubtype(child, parent)` calls (or derived from an ontology bridge)
- **Computed membership**: `fromGraph(ReasoningGraph)` populates membership by grouping entities by `type()`

API:
```
TypeHierarchy.fromGraph(ReasoningGraph)                    // membership only, no declared hierarchy
TypeHierarchy.fromGraph(ReasoningGraph, TypeRegistry)      // membership + hierarchy from declared types
TypeHierarchy.forType(String typeName) → TypeNode          // O(1) lookup
TypeHierarchy.subtypesOf(String typeName) → Set<TypeNode>  // all transitive subtypes (downward)
TypeHierarchy.supertypesOf(String typeName) → List<TypeNode> // chain to root (upward)
TypeHierarchy.isA(String type, String candidate) → boolean  // is `type` a subtype of `candidate`?
TypeHierarchy.entitiesOfType(String typeName, boolean includeSubtypes) → Set<String>  // entity IDs
TypeHierarchy.allTypes() → Collection<TypeNode>
```

`TypeHierarchy.isA()` is the key new query that `FolRule` grounding, MEBN MFrag dispatch, and PSL constraint derivation all need.

The `fromGraph` factory reads `ReasoningGraph.entities()` and groups by `e.type()` (exact same logic as `EntityType.fromGraph` at `mebn/EntityType.java:67`), creating a `TypeNode` per distinct type string. This collapses the ~40% redundancy identified in `mebn-entities-overlap.md` — `EntityType` becomes a view over `TypeHierarchy`, not a separately maintained registry.

#### `TypeNode` — one node in the hierarchy

```
TypeNode {
    String typeName                           // immutable
    TypeNode parent                           // null = root; set when hierarchy is declared
    Set<TypeNode> children                    // downward, filled by registerSubtype
    Set<String> entityIds                     // computed from graph membership
    TypeAttributeSchema attributeSchema       // typed attributes for this type (may be null = unschemaed)
    List<TypeConstraint> constraints          // type-level constraints (see below)
    boolean isSubtypeOf(TypeNode other)       // walk parent chain
}
```

#### `TypeAttributeSchema` — the bridge surface to ontology properties

Maps type names to typed attribute descriptors. This is the key concept that was absent in `EntityType` and is the main new surface:

```
TypeAttributeSchema {
    List<AttributeDefinition> attributes      // named, typed attribute descriptors
    // lookup
    Optional<AttributeDefinition> attribute(String name)
    List<AttributeDefinition> requiredAttributes()
}

AttributeDefinition {
    String name
    AttributeValueType valueType              // STRING, NUMBER, BOOLEAN, ENUM, REFERENCE
    boolean required
    List<String> enumValues                   // for ENUM type
    Double min, max                           // for NUMBER type
    String refersToType                       // for REFERENCE type: the target type name
    boolean inherited                         // true if contributed by a supertype's schema
}

enum AttributeValueType { STRING, NUMBER, BOOLEAN, ENUM, REFERENCE }
```

`TypeAttributeSchema` is intentionally simpler than `OntologySchema.FieldDefinition` — it carries only what the reasoning engines need (type, required, enum values, reference target). The full `FieldDefinition` detail (primaryKey, immutable, regex, fkReference) lives in the ontology bridge (Phase 2) and is not imported into the infra-free lib.

Schema inheritance: when `TypeHierarchy.forType(t)` resolves, `TypeNode.attributeSchema` merges the declared attributes for `t` with those inherited from its supertypes (child overrides parent on same attribute name). This gives the MEBN engine a complete attribute schema for each type without requiring every subtype to re-declare inherited fields.

#### `TypeConstraint` — relation and cardinality constraints

```
TypeConstraint {           // sealed, three implementations
    RelationConstraint {
        String relationLabel      // e.g. "EMPLOYS"
        String domainType         // source type name
        String rangeType          // target type name
    }
    CardinalityConstraint {
        String relationLabel
        Cardinality cardinality   // ONE_TO_ONE, ONE_TO_MANY, MANY_TO_ONE, MANY_TO_MANY
    }
    AttributeRequiredConstraint {
        String attributeName
    }
}
```

These are stored on `TypeNode.constraints` and are the lib's representation of what `OntologySchema.RelationshipTypeDefinition` expresses. They feed two consumers: (a) `OntologicalConstraintBuilder` can be built from them automatically (deriving subsumption rules + functional dependency rules), and (b) a `TypeConstraintValidator` can check graph nodes at read/write time (Phase 2).

#### `TypeRegistry` — explicit hierarchy + schema declarations

```
TypeRegistry {
    // declare a type node (idempotent)
    TypeRegistry declare(String typeName)
    TypeRegistry declare(String typeName, TypeAttributeSchema schema)
    // declare subtype relationship
    TypeRegistry subtype(String child, String parent)
    // declare a constraint
    TypeRegistry constraint(String typeName, TypeConstraint c)
    // build the hierarchy over a graph
    TypeHierarchy buildFor(ReasoningGraph graph)
}
```

`TypeRegistry` holds the explicit declarations (hierarchy + attribute schemas + constraints) and `buildFor(graph)` produces a `TypeHierarchy` by merging those declarations with the membership computed from the graph. This keeps membership computation lazy and graph-relative (per the `EntityType.fromGraph` pattern), while allowing hierarchy and schema to be declared once and reused across multiple graphs.

### 2.2 How existing consumers use the same type backbone

**MEBN — `EntityType` + `MTheory`:**
`EntityType.fromGraph` is kept for backward compatibility. A new factory method `EntityType.fromTypeNode(TypeNode)` or `TypeHierarchy.toEntityType(String)` extracts a compatible `EntityType` from a `TypeNode`, including `superType` wiring. `getMostSpecificMFrag()` at `MTheory.java:153` already traverses `superType` correctly — no change needed there. When a caller builds a `MTheory` from a `TypeHierarchy`, they call `TypeHierarchy.toEntityType(typeName)` per type, and the resulting `EntityType` chain matches the `TypeHierarchy` tree.

**PSL — `FolRule.entityTypeScope()` + `OntologicalConstraintBuilder`:**
`FolRule.entityTypeScope()` currently matches the exact `type()` string (`fol/FolRule.java:82`). With `TypeHierarchy` available, `FolInferenceService.buildProgram` (which does entity filtering at `fol/FolInferenceService.java:294`) can check `hierarchy.isA(entity.type(), rule.entityTypeScope())` instead of a string equality, making scoped rules fire for subtypes automatically. This is a single-line change in `FolInferenceService`, with no API break.

`OntologicalConstraintBuilder` can be automatically populated from a `TypeHierarchy`: iterate `hierarchy.allTypes()`, emit `addSubsumption(child, parent)` for each parent-child pair, and emit `addFunctionalDependency` for each `CardinalityConstraint` with ONE_OR_ZERO cardinality. This replaces manual string-pair declarations with a derived, consistent set.

**Embeddings:**
`Embeddings.java` at `embedding/Embeddings.java` operates on `double[]` from `GraphEntity.embedding()`. Type-constrained nearest-neighbor search (e.g. "nearest Person to this embedding") is currently a post-filter. With `TypeHierarchy`, the lib can offer `Embeddings.nearestOfType(query, graph, hierarchy, typeName, includeSubtypes)` — a type-scoped kNN. This is additive.

**Type-awareness for `InferredFact` and `FindingStore`:**
`InferredFact` (from Phases 1-4 of `mebn-entities-overlap.md`) already carries `atomKey` (predicate + entity ID). With `TypeHierarchy`, `InferredFactGraphMaterializer` could annotate materialized `INFERRED` edges with the entity's type at inference time, enabling type-filtered fact queries.

### 2.3 Package layout

```
kompile-graph-reasoning/src/main/java/ai/kompile/graph/reasoning/
  mebn/
    EntityType.java               (existing — kept unchanged)
    MTheory.java                  (existing — addEntityType still works)
    type/                         (NEW package)
      TypeHierarchy.java
      TypeNode.java
      TypeRegistry.java
      TypeAttributeSchema.java
      AttributeDefinition.java
      AttributeValueType.java     (enum)
      TypeConstraint.java         (sealed interface + three impl records)
      TypeConstraintValidator.java (validates GraphEntity attributes against TypeAttributeSchema)
```

No changes to `model/`, `fol/`, `psl/`, `bayesian/`, or `mebn/` root classes in Phase 1.

### 2.4 `TypeHierarchy.fromGraph` — staying a computed view

The overlap doc (`mebn-entities-overlap.md:21`) specifically directs: "`EntityType` should become a **computed view** over `ReasoningGraph`, not a separately-maintained registry." `TypeHierarchy` honors this: membership (`entityIds`) is always computed from the graph at construction time, not stored independently. The `TypeRegistry` stores only structural declarations (hierarchy links and attribute schemas), not entity IDs. The result is that `TypeHierarchy` ages gracefully — rebuilding it after a graph mutation produces a fresh view with no stale membership.

---

## 3. Ontology Bridge — Phase 2 (lives in the KG/app client layer)

The bridge is a client-side adapter, like `KnowledgeGraphReasoningAdapter`. It lives **outside** the `kompile-graph-reasoning` lib — in `kompile-knowledge-graph` or `kompile-app-main`, wherever `OntologySchema` is accessible. The lib remains infra-free.

### 3.1 Location decision

`GraphOntologyBindingService` (`app-main`) already resolves the active `OntologySchema` for a fact sheet and calls `OntologyConformanceValidator`. The bridge adapter belongs here, in `ai.kompile.app.ontology`. The new class is:

```
OntologyTypeSystemAdapter   (in kompile-app-main, ai.kompile.app.ontology)
    TypeRegistry buildTypeRegistry(OntologySchema schema)
    TypeHierarchy buildTypeHierarchy(OntologySchema schema, ReasoningGraph graph)
    void validateGraph(TypeHierarchy hierarchy, ReasoningGraph graph) → List<TypeViolation>
    FolRuleSet deriveConstraintRules(TypeHierarchy hierarchy) → FolRuleSet
```

It has one dependency on the lib (`TypeRegistry`, `TypeHierarchy`, `TypeConstraint`) and one on the process-engine (`OntologySchema`, `EntityTypeDefinition`, `RelationshipTypeDefinition`).

### 3.2 Ontology → lib type system (the first direction: derive)

`buildTypeRegistry(schema)` maps `OntologySchema` onto `TypeRegistry` declarations:

| `OntologySchema` concept | `TypeRegistry` / lib type system mapping |
|---|---|
| `EntityTypeDefinition.name` | `declare(typeName)` |
| `EntityTypeDefinition.fields` | `declare(typeName, TypeAttributeSchema)` — map `FieldDefinition` fields onto `AttributeDefinition`, preserving `required`, `type → AttributeValueType`, `enumValues`, `min/max`, and `fkReference → refersToType` |
| `EntityTypeDefinition.classification` | A root-level pseudo-hierarchy: `ACTOR`, `METRIC`, etc. become declared supertypes; each `EntityTypeDefinition` gets `subtype(defName, classification.name())` if no finer hierarchy is declared |
| `RelationshipTypeDefinition` | `constraint(sourceType, RelationConstraint)` + `constraint(sourceType, CardinalityConstraint)` |
| `ValidationRule` (global) | Deferred — SpEL rules are process-engine-specific and cannot travel to the infra-free lib |

**What is not mapped:** `ProvenanceCitation`, `ChangeRecord`, `templateSource` — these are ontology provenance metadata, not type-system structure. `ValidationRule` with SpEL expressions stays in `OntologyConformanceValidator`.

**Hierarchy gap:** `OntologySchema.EntityTypeDefinition` has no `superType` field today (confirmed by reading the class). The `EntityClassification` enum provides only coarse buckets. Phase 2 needs a decision point (see Section 5, open question 1). For now, the adapter emits the classification as the immediate parent (`Employee → ACTOR`), giving a shallow two-level tree.

### 3.3 Lib type system → graph governance (the second direction: validate)

`validateGraph(hierarchy, graph)` iterates `graph.entities()`, checks each entity against its `TypeNode.attributeSchema` using `TypeConstraintValidator`, and accumulates `TypeViolation` records:

```
TypeViolation {
    String entityId
    String entityType
    String violationType   // UNKNOWN_TYPE | MISSING_REQUIRED_ATTR | INVALID_ATTR_VALUE | INVALID_RELATION_TARGET
    String detail
}
```

This is the **governance closure**: the ontology-derived hierarchy now reaches back to validate the live graph nodes. This closes the "derived dead-end" gap in `ontology-enrichment-roadmap.md`. The existing `GraphConformanceChecker` SPI (`app-core`) remains the hook; `GraphOntologyBindingService` implements it. The new path is:

```
GraphOntologyBindingService.check()
  → OntologyTypeSystemAdapter.buildTypeHierarchy(schema, graph)
  → OntologyTypeSystemAdapter.validateGraph(hierarchy, graph)
  → TypeViolation[] → GraphConformanceReport
```

This replaces the current flat `OntologyConformanceValidator.validateEntity(schema, type, props)` call with a hierarchy-aware check: an entity of type "Employee" is validated against the merged attribute schema of Employee + Person (its supertypes).

### 3.4 Constraint rule derivation (using the bridge output for reasoning)

```
deriveConstraintRules(hierarchy)
```

This produces a `FolRuleSet` containing PSL subsumption and cardinality rules auto-derived from the hierarchy, usable directly by `FolInferenceService`. This is the "type-conditioned CPT / rule templates keyed on ontology entity/edge types" mentioned in `ontology-enrichment-roadmap.md` Workstream D. It replaces manual `OntologicalConstraintBuilder` string declarations with auto-derived rules.

### 3.5 Reverse direction: graph types → ontology candidates

`TypeHierarchy` built from a `ReasoningGraph` may contain type strings not present in the `OntologySchema`. The adapter can emit these as `OntologySchema` candidates:

```
List<EntityTypeDefinition> discoverNewTypes(TypeHierarchy hierarchy, OntologySchema existing)
```

This is the bidirectionality for drift detection (Workstream B2 of the roadmap). Candidate types discovered in the graph that have no corresponding `EntityTypeDefinition` surface as proposed additions to the ontology, enabling the "types in graph not in ontology (add candidates)" drift detection.

---

## 4. Phased Plan and Backward Compatibility

### Phase 1 — Richer lib type system (additive, lib-only, no Spring)

All changes are additive. The new `mebn/type/` package adds classes; no existing class is modified except an optional new factory method in `EntityType`.

Existing tests that must stay green (currently 324 passing per `mebn-entities-overlap.md:39`):
- All `MebnCanonicalGapsTest` tests that use `EntityType.fromGraph()`, `isSubtypeOf()`, `setSuperType()` — these APIs are unchanged
- `FolReasoningTest` — `FolRule.entityTypeScope()` field is unchanged; the optional `isA` check in `FolInferenceService` is gated on whether a `TypeHierarchy` is passed (new optional overload), not a change to the existing path
- `PslWeightLearningServiceTest` — no type-system dependency
- `OntologyConstraintTest` — `OntologicalConstraintBuilder` API unchanged; new `fromHierarchy(TypeHierarchy)` factory is additive

Build risk: zero — pure additions to the infra-free lib. No new dependencies.

### Phase 2 — Ontology bridge (client-side, app-main)

Changes confined to `kompile-app-main`, `ai.kompile.app.ontology`. The new `OntologyTypeSystemAdapter` is a new class alongside `GraphOntologyBindingService`.

`GraphOntologyBindingService` gains a new dependency: `OntologyTypeSystemAdapter` (injected). The existing `OntologyConformanceValidator` path stays; the new path runs alongside it (additive, feature-flagged initially).

Existing tests that must stay green:
- `GraphOntologyBindingServiceTest` (13 tests) — tests mock `OntologySchema`; the new adapter only adds behavior, does not change existing
- `OntologyConformanceValidatorTest` (9 tests) — no change to `OntologyConformanceValidator`
- `NamedGraphServiceImpl` tests (+5) — no change to `NamedGraph` or `NamedGraphService`

Build sequence: (1) lib `mebn/type/` package (`kompile-graph-reasoning`), (2) `OntologyTypeSystemAdapter` in `kompile-app-main`. Neither changes a sibling module's API, so no rebuild ripple across the other 30+ modules.

### First concrete thing to build

The first thing to build in Phase 1 is `TypeHierarchy` + `TypeNode` + `TypeRegistry`, with `TypeHierarchy.fromGraph(ReasoningGraph)` that groups entities by `type()` into `TypeNode` objects — essentially extracting the existing `EntityType.fromGraph` logic into a unified structure that also supports `subtypesOf()` (downward traversal). This gives MEBN, PSL, and embeddings the `isA` query they currently lack, immediately enabling subtype-aware `FolRule` scoping. `TypeAttributeSchema` comes second (it's the bridge surface to ontology; Phase 1 can declare it without populating it). The bridge `OntologyTypeSystemAdapter` is Phase 2 only.

---

## 5. Open Questions / Design Forks

**1. How to get hierarchy into `OntologySchema`? (Blocking for Phase 2 bridge quality)**

`EntityTypeDefinition` has no `superType` field. The bridge adapter today can only emit the coarse `EntityClassification` as a parent. Options:
- (a) Add a nullable `String superTypeName` field to `EntityTypeDefinition` (a backward-compatible schema addition, versioned JSON). This is the clean fix.
- (b) Derive hierarchy from `RelationshipTypeDefinition` with a reserved label (e.g. `"IS_A"`) — a convention, no schema change but fragile.
- (c) Infer hierarchy from `EntityClassification` only — shallow, stable, but loses any domain-specific subtype information (Employee is-a Person is invisible).

Option (a) is recommended. It requires a one-field addition to `EntityTypeDefinition.java` and the ontology wizard/derive endpoint to surface it. **Decision needed before Phase 2 coding starts.**

**2. Multiple inheritance in the lib type system?**

Deferred above in favor of single-parent. But `EntityClassification` is more like a role facet (an entity can be both ACTOR and REFERENCE). If the bridge needs to attach classification as a facet without being a parent, should `TypeNode` carry an optional `Set<String> facets` alongside a single `parent`? This would model "Employee isA Person, also facet=ACTOR" without a diamond hierarchy. **Decision needed before bridge implementation.**

**3. Attribute schema inheritance — deep or shallow merge?**

When `Employee` is-a `Person` is-a `Entity`, should `Employee`'s `TypeAttributeSchema` include all of `Person`'s and `Entity`'s required attributes? If yes (deep merge), validation is complete but performance-sensitive for deep hierarchies. If no (shallow only), callers must call `validateChain(entity, hierarchy)` traversing upward explicitly. Recommendation: deep merge at `TypeHierarchy` build time (merge once, validate many times). **Decision: deep merge recommended, but check performance with deep hierarchies > 5 levels.**

**4. Validation strictness — block writes or tag-and-continue?**

The existing `ontology-enrichment-roadmap.md` A-2 item says: "LENIENT tags violations; STRICT drops/coerces. Default LENIENT." Should `TypeConstraintValidator` honor the same `SchemaEnforcementMode` that `GraphOntologyBindingService` already reads from? If yes, the bridge Phase 2 inherits the existing enforcement-mode configuration with no new API. Recommendation: yes. **Decision: reuse `SchemaEnforcementMode` for consistency.**

**5. How does `TypeRegistry` get seeded in the MEBN/PSL reasoning path (without Spring)?**

The infra-free lib cannot load `OntologySchema` (it lives in `kompile-process-engine`, which has Spring/Jackson-databind). The `TypeRegistry` must be populated by the caller (the `KnowledgeGraphReasoningAdapter` or `GraphOntologyBindingService`) before passing it to `TypeHierarchy.fromGraph`. This is the same pattern as the existing `KnowledgeGraphReasoningAdapter` — the lib consumes what the client builds; it does not reach out. **Not a problem, just needs to be explicit in the API contract.**

**6. Should `TypeHierarchy` be lazy (on-demand membership) or eager (build-time)?**

Current `EntityType.fromGraph` is eager — it iterates all entities at construction. For large graphs (hundreds of thousands of nodes), eager membership computation for all types at once may be expensive. Lazy (compute `entityIds` for a type only when queried) is more memory-efficient but requires a `ReasoningGraph` reference at query time. Recommendation: eager for Phase 1 (simpler, consistent with existing pattern, real graphs in kompile are typically < 50k nodes per fact sheet). **Revisit if profiling shows hot spots.**

---

## File Cross-Reference

| Concept | File | Key lines |
|---|---|---|
| `GraphEntity.type()` flat string | `model/GraphEntity.java` | 38, 46-48 |
| `EntityType.fromGraph` factory | `mebn/EntityType.java` | 54-75 |
| `EntityType.superType` isA chain | `mebn/EntityType.java` | 31, 86-89, 117-125 |
| `MTheory.getMostSpecificMFrag` | `mebn/MTheory.java` | 153-178 |
| `RandomVariable.argumentTypes` typed RV | `mebn/RandomVariable.java` | 43-44, 55-76 |
| `FolRule.entityTypeScope()` flat scope | `fol/FolRule.java` | 82, 107, 184 |
| `FolRuleSet.rulesForType` filter | `fol/FolRuleSet.java` | 55-64 |
| `OntologicalConstraintBuilder` subsumption | `psl/OntologicalConstraintBuilder.java` | 56-205 |
| `KnowledgeGraphReasoningAdapter` (adapter pattern) | `knowledgegraph/reasoning/KnowledgeGraphReasoningAdapter.java` | 54 |
| `OntologySchema` root | `process/ontology/OntologySchema.java` | 39-53 |
| `EntityTypeDefinition` (no superType field) | `process/ontology/EntityTypeDefinition.java` | 36-51 |
| `EntityClassification` coarse buckets | `process/ontology/EntityClassification.java` | 22-35 |
| `RelationshipTypeDefinition` domain/range | `process/ontology/RelationshipTypeDefinition.java` | 34-43 |
| `FieldDefinition` typed attributes | `process/ontology/FieldDefinition.java` | 36-55 |
| `GraphOntologyBindingService` (existing bridge seam) | `app/ontology/GraphOntologyBindingService.java` | 62 |
| "EntityType should become a computed view" | `docs/architecture/mebn-entities-overlap.md` | 21 |
| "derived dead-end" root cause | `docs/architecture/ontology-enrichment-roadmap.md` | 18 |
| Gap 6 "not wired to OntologySchema, deferred" | `docs/architecture/mebn-entities-overlap.md` | 31 |
| Workstream D "type-conditioned CPT/rule templates" | `docs/architecture/ontology-enrichment-roadmap.md` | 193-194 |
