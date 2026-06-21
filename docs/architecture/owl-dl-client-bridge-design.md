# OWL O4 Client DL-Bridge — Design Document

**Date:** 2026-06-21
**Status:** Design only — no code
**Phase:** O4 (the "client-level" half of the OWL enhancement sketched in `owl-enhancement-design.md §7 Phase O4`)
**Depends on:** O1–O3 already shipped (all files confirmed present in `mebn/type/owl/`)

---

## 1. Motivation and Boundary

The infra-free lib delivers OWL 2 RL reasoning via `OwlRlReasoner` (`mebn/type/owl/OwlRlReasoner.java:71`),
which compiles TBox axioms into `FolRule`s and runs them through `FolInferenceService`.  RL is PTIME and
covers transitive closure, property characteristics, domain/range, disjointness, and equivalence — the full
forward-chaining profile.

What RL cannot deliver:

| Capability | RL (lib) | DL (client) |
|---|---|---|
| Subsumption classification (full hierarchy materialization) | Partial (ABox-driven cax-sco) | Full TBox-level (`SubClassOf` closure) |
| Satisfiability / consistency (full SROIQ) | cax-dw only | All OWL DL axioms |
| Complex class expressions (`ComplementOf`, `UnionOf`, `IntersectionOf`) | None | Full |
| Nominals (`owl:oneOf`) | None | Full |
| Role chains (`owl:propertyChainAxiom`) | None | Full |
| Arbitrary OWL file formats (RDF/XML, OWL/XML, Manchester, Turtle) | Narrow Turtle subset only | Full via OWL API |
| Reasoning over external ontologies (BFO, SNOMED, OBO) | None | Full |

The O4 bridge adds those capabilities in a new Maven module, keeping the lib's zero-dep promise intact.

---

## 2. New Module

### 2.1 Location and Coordinates

```
kompile-app/kompile-data/kompile-graphs/kompile-reasoning-owl-bridge/
```

This sits as a **new sibling** under `kompile-graphs/` alongside `kompile-knowledge-graph` and
`kompile-graph-reasoning`.  It is NOT a sub-module of `kompile-knowledge-graph` because OWL DL
reasoning is a peer of KG store operations, not subordinate to the store.

Maven coordinates:

```xml
<groupId>ai.kompile</groupId>
<artifactId>kompile-reasoning-owl-bridge</artifactId>
<version>0.1.0-SNAPSHOT</version>
```

Parent POM: `ai.kompile:kompile-graphs` (`kompile-app/kompile-data/kompile-graphs/pom.xml:17`).
The parent POM gains one new `<module>kompile-reasoning-owl-bridge</module>` entry.

### 2.2 Maven Dependencies

```xml
<dependencies>
  <!-- Lib: OwlReasoner interface + OwlOntology + OwlRlResult + all owl.* model types -->
  <dependency>
    <groupId>ai.kompile</groupId>
    <artifactId>kompile-graph-reasoning</artifactId>
  </dependency>

  <!-- KG store: GraphNode/GraphEdge for OwlGraphImporter (see §4.3) -->
  <!-- OPTIONAL: bridge may operate without the KG store if used standalone -->
  <!-- Include only if OwlGraphImporter wiring is needed in this module -->
  <!-- <dependency><groupId>ai.kompile</groupId><artifactId>kompile-knowledge-graph</artifactId></dependency> -->

  <!-- OWL API 5 — the de-facto Java OWL ontology framework.
       owlapi-distribution bundles parsers for RDF/XML, OWL/XML, Manchester, and Turtle. -->
  <dependency>
    <groupId>net.sourceforge.owlapi</groupId>
    <artifactId>owlapi-distribution</artifactId>
    <version>5.1.20</version>
  </dependency>

  <!-- DL reasoner: Openllet is the maintained successor to Pellet.
       It is pure-Java, supports OWL 2 DL (SROIQ), and embeds cleanly as a library.
       Alternative: net.sourceforge.owlapi:HermiT:1.4.5 (slower on property-chain queries
       but has better incremental reasoning support).
       DECISION: Openllet is preferred here; see §6 open question #2. -->
  <dependency>
    <groupId>com.github.galigator.openllet</groupId>
    <artifactId>openllet-owlapi</artifactId>
    <version>2.6.6</version>
  </dependency>

  <!-- SLF4J only — no Spring, no JPA, no ND4J in this module -->
  <dependency>
    <groupId>org.slf4j</groupId>
    <artifactId>slf4j-api</artifactId>
  </dependency>
  <dependency>
    <groupId>org.projectlombok</groupId>
    <artifactId>lombok</artifactId>
    <optional>true</optional>
  </dependency>

  <!-- Test -->
  <dependency>
    <groupId>org.junit.jupiter</groupId>
    <artifactId>junit-jupiter</artifactId>
    <scope>test</scope>
  </dependency>
</dependencies>
```

**What is explicitly NOT added to `kompile-graph-reasoning/pom.xml`:** `owlapi-distribution`,
`openllet-owlapi`, HermiT, or any OWL API artifact.  The lib's deps remain: `nd4j-api`,
`jackson-annotations`, `slf4j-api`, `lombok` (lib pom:42–89).

### 2.3 Java Package

```
ai.kompile.graph.reasoning.owl.bridge
```

Sub-packages:
- `ai.kompile.graph.reasoning.owl.bridge.importer` — `ExternalOwlImporter`, format-specific loaders
- `ai.kompile.graph.reasoning.owl.bridge.reasoner` — `OwlDlReasoningBridge`
- `ai.kompile.graph.reasoning.owl.bridge.mapper` — OWL-API ↔ lib type mappers
- `ai.kompile.graph.reasoning.owl.bridge.spring` — optional `@Bean` / autoconfiguration if Spring is
  available (conditional; no hard Spring dep in this module's core)

---

## 3. Implementing `OwlReasoner` — the DL Bridge

### 3.1 The Interface (Lib Side, Already Exists)

```java
// kompile-graph-reasoning, mebn/type/owl/OwlReasoner.java:44
public interface OwlReasoner {
    OwlRlResult reason(ReasoningGraph graph, OwlOntology ontology);
}
```

The bridge must implement this exactly.  `OwlRlResult` (`mebn/type/owl/OwlRlResult.java:46`) carries
`inferredRelations`, `inferredTypes`, and `inconsistencies` — all of which a DL reasoner can produce.

### 3.2 `OwlDlReasoningBridge` — Core Class

```
ai.kompile.graph.reasoning.owl.bridge.reasoner.OwlDlReasoningBridge
    implements OwlReasoner
```

**Constructor parameters:**

```java
public OwlDlReasoningBridge(OWLReasonerFactory reasonerFactory)
```

Default factory is `OpenlletReasonerFactory.getInstance()`.  Callers may substitute HermiT's
`ReasonerFactory` to swap reasoners without recompiling.

**`reason(ReasoningGraph graph, OwlOntology ontology)` — step-by-step:**

1. **Translate `OwlOntology` → `OWLOntology`** via `OwlOntologyMapper.toOwlApi(ontology)` (§3.3).
   This builds an in-memory `OWLOntology` using `OWLOntologyManager.createOntology()` and populates
   it with the axioms from the lib's TBox.

2. **Inject ABox assertions from `ReasoningGraph`** via `ReasoningGraphABoxLoader.load(graph, owlOntology)` (§3.4).
   Each `GraphEntity` becomes an `OWLNamedIndividual` with a `ClassAssertion`; each `GraphRelation`
   becomes an `ObjectPropertyAssertion`.

3. **Create and run the DL reasoner:**
   ```java
   OWLReasoner reasoner = reasonerFactory.createReasoner(owlOntology);
   reasoner.precomputeInferences(
       InferenceType.CLASS_HIERARCHY,
       InferenceType.CLASS_ASSERTIONS,
       InferenceType.OBJECT_PROPERTY_ASSERTIONS
   );
   ```

4. **Check consistency:** `reasoner.isConsistent()`. If false, enumerate unsatisfiable classes
   via `reasoner.getUnsatisfiableClasses()` and map each to an `OwlInconsistency.crisp("dl-unsat", ...)`.

5. **Collect inferred type assertions:** For each `OWLNamedIndividual` in the ABox, call
   `reasoner.getTypes(individual, false)` to get all inferred class memberships (not just asserted ones).
   Map to `inferredTypes: entityId → classIri`.

6. **Collect inferred object property assertions:** Call
   `reasoner.getObjectPropertyValues(individual, property)` for each declared object property.
   Relations present in the result but absent from the original `ReasoningGraph` become new
   `GraphRelation`s in `inferredRelations` (constructed via `SimpleGraphRelation.directed(...)`
   from the lib).

7. **Collect disjointness violations:** For entities typed as two disjoint classes (detectable
   after DL inference via `getTypes()`), add `OwlInconsistency.crisp("cax-dw", entityId, ...)`.

8. **Dispose the reasoner:** `reasoner.dispose()`.

9. **Return `OwlRlResult.of(inferredRelations, inferredTypes, inconsistencies)`.**

The method is pure: it does not mutate `graph` or `ontology`.  Thread safety: each call creates a
fresh `OWLOntologyManager` and `OWLReasoner` instance; no shared mutable state.

### 3.3 `OwlOntologyMapper` — Lib ↔ OWL API Type Translation

```
ai.kompile.graph.reasoning.owl.bridge.mapper.OwlOntologyMapper
```

**`OWLOntology toOwlApi(OwlOntology lib)`**

| Lib type | OWL API operation |
|---|---|
| `OwlClass` (classIri) | `OWLClass cls = df.getOWLClass(IRI.create(classIri))` + `OWLDeclarationAxiom` |
| `OwlClass.subClassOfIris` | `OWLSubClassOfAxiom` for each IRI |
| `OwlClass.equivalentClassIris` | `OWLEquivalentClassesAxiom` |
| `OwlClass.disjointWithIris` | `OWLDisjointClassesAxiom` |
| `OwlRestriction.SomeValuesFrom` | `df.getOWLObjectSomeValuesFrom(property, filler)` as superclass |
| `OwlRestriction.AllValuesFrom` | `df.getOWLObjectAllValuesFrom(property, filler)` as superclass |
| `OwlRestriction.HasValue` | `df.getOWLObjectHasValue(property, individual)` as superclass |
| `OwlRestriction.MinCardinality` | `df.getOWLObjectMinCardinality(n, property [, filler])` |
| `OwlRestriction.MaxCardinality` | `df.getOWLObjectMaxCardinality(n, property [, filler])` |
| `OwlRestriction.ExactCardinality` | `df.getOWLObjectExactCardinality(n, property [, filler])` |
| `OwlObjectProperty` (functional/transitive/symmetric/inverse/reflexive/asymmetric/irreflexive) | Corresponding `OWLFunctionalObjectPropertyAxiom`, `OWLTransitiveObjectPropertyAxiom`, etc. |
| `OwlObjectProperty.domainClassIri` | `OWLObjectPropertyDomainAxiom` |
| `OwlObjectProperty.rangeClassIri` | `OWLObjectPropertyRangeAxiom` |
| `OwlObjectProperty.inverseOfIri` | `OWLInverseObjectPropertiesAxiom` |
| `OwlObjectProperty.subPropertyOfIris` | `OWLSubObjectPropertyOfAxiom` for each |
| `OwlObjectProperty.equivalentPropertyIris` | `OWLEquivalentObjectPropertiesAxiom` |
| `OwlDataProperty` | Analogous using `OWLDataProperty` axioms; range → `OWLDatatype` via xsd IRI |
| `OwlOntology.sameAs` | `OWLSameIndividualAxiom` for each pair |

`OWLDataFactory df = OWLManager.getOWLDataFactory()` is the sole static entry point.

**`OwlOntology fromOwlApi(OWLOntology owlOnt)`**

The reverse direction — used by `ExternalOwlImporter` after loading an arbitrary OWL file:

Walk `owlOnt.axioms()` with a visitor (or by filtering on axiom type):
- `OWLSubClassOfAxiom` → `OwlClass.Builder.subClassOf(...)`.
- `OWLEquivalentClassesAxiom` → `equivalentClass(...)` on each pair.
- `OWLDisjointClassesAxiom` → `disjointWith(...)` on each pair.
- `OWLObjectPropertyDomainAxiom` / `OWLObjectPropertyRangeAxiom` → `OwlObjectProperty.Builder`.
- Property characteristic axioms → corresponding boolean flags on `OwlObjectProperty.Builder`.
- `OWLClassAssertionAxiom` — these are ABox, extracted separately by `ReasoningGraphABoxLoader`.
- Complex class expressions involving `ComplementOf` / `UnionOf` — **logged as skipped** (not
  representable in the lib model; they are handled in-memory by the DL reasoner but not persisted
  into `OwlOntology`).
- Annotations and `owl:imports` — ignored at this layer (import closure should be flattened by the
  OWL API's `OWLOntologyManager` before calling this method).

Return `OwlOntology.Builder.build()`.

### 3.4 `ReasoningGraphABoxLoader`

```
ai.kompile.graph.reasoning.owl.bridge.mapper.ReasoningGraphABoxLoader
```

Loads `ReasoningGraph` individuals into an existing `OWLOntology` as ABox assertions:

```java
void load(ReasoningGraph graph, OWLOntology target, OWLOntologyManager mgr) {
    OWLDataFactory df = mgr.getOWLDataFactory();
    for (GraphEntity e : graph.entities()) {
        OWLNamedIndividual ind = df.getOWLNamedIndividual(OwlIri.indIri(e.id()));
        if (e.type() != null && !e.type().isEmpty()) {
            OWLClass cls = df.getOWLClass(OwlIri.classIri(e.type()));
            mgr.addAxiom(target, df.getOWLClassAssertionAxiom(cls, ind));
        }
    }
    for (GraphRelation r : graph.relations()) {
        OWLNamedIndividual src = df.getOWLNamedIndividual(OwlIri.indIri(r.sourceId()));
        OWLNamedIndividual tgt = df.getOWLNamedIndividual(OwlIri.indIri(r.targetId()));
        OWLObjectProperty prop = df.getOWLObjectProperty(OwlIri.propIri(r.type()));
        mgr.addAxiom(target, df.getOWLObjectPropertyAssertionAxiom(prop, src, tgt));
    }
}
```

`OwlIri` (`mebn/type/owl/OwlIri.java`) is used directly (it is in the lib and provides
`indIri(id)` and `propIri(name)` — confirmed present in the codebase).

---

## 4. Arbitrary OWL File Import

### 4.1 `ExternalOwlImporter`

```
ai.kompile.graph.reasoning.owl.bridge.importer.ExternalOwlImporter
```

Reads an OWL file in any format supported by the OWL API (RDF/XML, OWL/XML, Turtle,
Manchester, N-Triples) and returns a lib `OwlOntology`:

```java
public final class ExternalOwlImporter {

    /**
     * Load an ontology from an InputStream.
     * @param in        the stream; closed by this method
     * @param baseIri   hint for relative IRI resolution (may be null)
     * @param format    OWLDocumentFormat hint, or null for auto-detect
     * @return the lib OwlOntology containing TBox axioms; ABox assertions
     *         are available via aboxGraph() on the same object if needed
     */
    public ImportResult importFrom(InputStream in, String baseIri, OWLDocumentFormat format) { … }

    /** Convenience: load from a file path (format inferred from extension). */
    public ImportResult importFromFile(Path path) { … }

    /** Convenience: load from a URL (HTTP/HTTPS fetch + format detection). */
    public ImportResult importFromUrl(java.net.URL url) { … }
}
```

**`ImportResult`:**

```java
public record ImportResult(
    OwlOntology tbox,          // TBox axioms as lib OwlOntology
    ReasoningGraph abox,       // ABox individuals and assertions as ReasoningGraph
    List<String> skippedAxioms // axiom types that were dropped (not expressible in lib model)
) {}
```

**Implementation:**

1. `OWLOntologyManager mgr = OWLManager.createOWLOntologyManager()`.
2. Load with `mgr.loadOntologyFromOntologyDocument(new StreamDocumentSource(in), config)`.
   If `baseIri` is non-null, configure `MissingImportHandlingStrategy.SILENT` and set the document IRI.
   If `format` is non-null, add a `OWLParserFactory` hint.
3. Flatten the import closure: `mgr.importsClosure(owlOnt).forEach(...)` — collect all axioms into a
   merged ontology so callers do not need to manage imports separately.
4. Call `OwlOntologyMapper.fromOwlApi(mergedOnt)` to extract TBox → `OwlOntology`.
5. Extract ABox: iterate `OWLClassAssertionAxiom`, `OWLObjectPropertyAssertionAxiom`,
   `OWLDataPropertyAssertionAxiom` → build a `MutableReasoningGraph` (`model/MutableReasoningGraph.java`)
   using `GraphEntity.builder(...)` and `GraphRelation.builder(...)` from the lib.
6. Collect skipped axiom type names (e.g. `"OWLComplementOf"`, `"OWLPropertyChain"`) and populate
   `ImportResult.skippedAxioms`.

### 4.2 Format Matrix

| File extension / MIME | OWL API parser | Notes |
|---|---|---|
| `.owl`, `.rdf`, `application/rdf+xml` | `RDFXMLDocumentFormat` | Default for legacy Protege files |
| `.owx`, `application/owl+xml` | `OWLXMLDocumentFormat` | Protege 4+ default |
| `.ttl`, `text/turtle` | `TurtleDocumentFormat` | Also reads the lib's own `OwlTurtleWriter` output |
| `.nt`, `application/n-triples` | `NTriplesDocumentFormat` | Matches lib's `OwlNTriplesWriter` output |
| `.omn`, Manchester | `ManchesterSyntaxDocumentFormat` | Human-authored ontologies |
| Auto-detect | `OWLOntologyManager` default | Tries parsers in order |

The OWL API's `OWLOntologyManager.loadOntologyFromOntologyDocument(StreamDocumentSource)` performs
format auto-detection by trying parsers in sequence, so explicit format hints are optional.

### 4.3 `OwlGraphImporter` — Direct Graph Store Import (Optional Component)

A higher-level convenience class that lives in the `importer` sub-package and depends on
`kompile-knowledge-graph`:

```
ai.kompile.graph.reasoning.owl.bridge.importer.OwlGraphImporter
```

```java
/**
 * Imports an external OWL file directly into the live KnowledgeGraphService store:
 * ABox individuals → GraphNode, relations → GraphEdge, TBox → OwlOntology stored
 * via the GraphOntologyBindingService.
 */
public final class OwlGraphImporter {
    private final KnowledgeGraphService graphService;
    private final ExternalOwlImporter   owlImporter;

    public ImportStats importIntoGraph(Path owlFile, String factSheetId) { … }
}
```

This class is the only place in the bridge that depends on `kompile-knowledge-graph`.  Its pom
dependency should be `optional` so that callers that only need `ExternalOwlImporter` and
`OwlDlReasoningBridge` do not pull in the full Spring/JPA stack.

---

## 5. DL Features Beyond the Lib's OWL 2 RL

The following features become available when the bridge is on the classpath and `OwlDlReasoningBridge`
is injected (instead of `OwlRlReasoner`):

### 5.1 Full Taxonomy Classification

`reasoner.getSubClasses(owlClass, false)` / `getSuperClasses(owlClass, false)` compute the complete
subsumption lattice over the TBox in one precompute step.  The lib's RL reasoner only propagates
ABox type assertions (cax-sco); it cannot infer new TBox subclass relationships that depend on
complex class expressions.

**Example:** if the TBox says `C ⊑ ∃P.D` and `∃P.D ⊑ E`, a DL reasoner deduces `C ⊑ E` as a TBox
fact.  The lib's RL would only propagate this at the ABox level (individual typed C also gets type E)
if an actual individual of type C exists in the graph.

### 5.2 Satisfiability and Consistency for Full SROIQ Axioms

`OwlDlReasoningBridge` checks `reasoner.isSatisfiable(cls)` for each declared class and
`reasoner.isConsistent()` for the whole ontology.  These checks use the tableau algorithm
(Openllet's SI+ALCRIQ+O reasoning) and catch:

- `owl:complementOf` inconsistencies (A entity cannot be both A and not-A)
- Role chain violations (`owl:propertyChainAxiom`: `P∘Q ⊑ R` + ABox data)
- Nominal inconsistencies (`owl:oneOf` singleton classes that are asserted disjoint)
- Qualified cardinality conflicts (`min 2 P.C` + `max 1 P.C` on the same class)

None of these are reachable by the lib's RL rule set.

### 5.3 Complex Class Expressions as `OwlInconsistency` Sources

When `OwlOntologyMapper.fromOwlApi()` encounters axioms it cannot represent in `OwlOntology`
(e.g. `OWLObjectComplementOf`, `OWLObjectUnionOf`), it logs them as skipped for the lib model
but **retains them in the in-memory `OWLOntology`** used by the DL reasoner.  This means the DL
reasoner reasons over the full axiom set, and any inconsistency it finds — even if triggered by a
skipped axiom type — surfaces as an `OwlInconsistency` in the returned `OwlRlResult`.

### 5.4 Property Chain Reasoning

OWL 2 `owl:propertyChainAxiom` (e.g., `partOf ∘ locatedIn ⊑ locatedIn`) is fully evaluated by
Openllet's role composition tableau.  The resulting entailed property assertions appear in
`inferredRelations` in the returned `OwlRlResult`, using the same `SimpleGraphRelation.directed()`
factory the lib uses for BFS transitive closure.

### 5.5 Incremental Reasoning (Openllet-specific)

Openllet exposes `PelletReasoner.refresh()` for incremental ABox updates.  When the bridge is used
in a long-running Spring context with evolving graph data, callers may cast the `OWLReasoner` to
`PelletReasoner` and call `refresh()` rather than `dispose()` + `createReasoner()` — reducing
re-classification cost.  This is an advanced optimization path and is not exposed in the
`OwlReasoner` interface (which remains stateless by contract).

---

## 6. Caller Selection: RL-in-lib vs. DL-in-client

### 6.1 The Selection Seam

`OwlReasoner` is the interface (`mebn/type/owl/OwlReasoner.java:44`).  Both `OwlRlReasoner` and
`OwlDlReasoningBridge` implement it.  Callers that hold an `OwlReasoner` reference do not change.

Current callers include `KnowledgeGraphReasoningAdapter`
(`kompile-knowledge-graph:knowledgegraph/reasoning/KnowledgeGraphReasoningAdapter.java:54`), which
already follows the adapter pattern: it converts `KnowledgeGraphService` → `ReasoningGraph` and
passes it to reasoning engines.  The bridge is injected at the same seam.

### 6.2 Injection Strategy (Spring Context)

In `kompile-app-main` (or any Spring Boot application):

```java
@Configuration
public class OwlReasonerConfiguration {

    /**
     * Prefer the DL bridge when kompile-reasoning-owl-bridge is on the classpath.
     * Falls back to the lib's RL reasoner when the bridge jar is absent.
     */
    @Bean
    @ConditionalOnClass(name = "ai.kompile.graph.reasoning.owl.bridge.reasoner.OwlDlReasoningBridge")
    public OwlReasoner owlDlReasoner() {
        return new OwlDlReasoningBridge(OpenlletReasonerFactory.getInstance());
    }

    @Bean
    @ConditionalOnMissingBean(OwlReasoner.class)
    public OwlReasoner owlRlReasoner() {
        return new OwlRlReasoner(); // lib built-in, no external deps
    }
}
```

This configuration lives in `kompile-app-main` (NOT in the bridge module, which has no Spring dep).
When the bridge jar is on the classpath the DL bean wins; otherwise RL is used transparently.

### 6.3 When to Use Which

| Situation | Use |
|---|---|
| Kompile-derived KG ontologies (OntologySchema-based, few/no complex expressions) | `OwlRlReasoner` (lib) — fast, no extra dep |
| External ontology file import from Protege / OBO / SNOMED | `ExternalOwlImporter` + `OwlDlReasoningBridge` |
| Consistency checking on a KG that uses `ComplementOf`/`UnionOf` | `OwlDlReasoningBridge` |
| Native image / CLI-only deployment (no Maven infra at runtime) | `OwlRlReasoner` (lib) — DL bridge is NOT suitable for GraalVM native due to OWL API + Openllet runtime reflection |
| Ontology with deep property chains or role hierarchies | `OwlDlReasoningBridge` |
| Kompile lite (low memory, embedded) | `OwlRlReasoner` (lib) — Openllet's tableau can use 300–800 MB on large ontologies |

### 6.4 CLI Exposure

A new CLI command `kompile owl import <file>` and `kompile owl check <file>` can call
`ExternalOwlImporter` and `OwlDlReasoningBridge` respectively.  These commands should only be
available when the bridge module is on the classpath (guarded by a `try { Class.forName(...) }`
check in the CLI command registration).

---

## 7. File and Class Cross-Reference

| Concept | File (absolute path) | Key lines |
|---|---|---|
| `OwlReasoner` interface | `kompile-app/kompile-data/kompile-graphs/kompile-graph-reasoning/src/main/java/ai/kompile/graph/reasoning/mebn/type/owl/OwlReasoner.java` | 44, 58 |
| `OwlRlReasoner` (lib RL impl) | `…/mebn/type/owl/OwlRlReasoner.java` | 71–143 |
| `OwlOntology` (TBox model) | `…/mebn/type/owl/OwlOntology.java` | 70–329 |
| `OwlRlResult` (result type) | `…/mebn/type/owl/OwlRlResult.java` | 46–121 |
| `OwlInconsistency` (violation record) | `…/mebn/type/owl/OwlInconsistency.java` | 40–133 |
| `OwlClass`, `OwlRestriction`, etc. | `…/mebn/type/owl/` | O1 model — all present |
| `OwlTurtleWriter` (O3, lib) | `…/mebn/type/owl/OwlTurtleWriter.java` | 50 (confirmed present) |
| `OwlTurtleReader` (O3, lib) | `…/mebn/type/owl/OwlTurtleReader.java` | 27–60 |
| `OwlIri` (namespace constants) | `…/mebn/type/owl/OwlIri.java` | (present) |
| `KnowledgeGraphReasoningAdapter` (client adapter pattern) | `kompile-app/kompile-data/kompile-graphs/kompile-knowledge-graph/src/main/java/ai/kompile/knowledgegraph/reasoning/KnowledgeGraphReasoningAdapter.java` | 54–176 |
| `MutableReasoningGraph` (writable graph) | `…/kompile-graph-reasoning/…/model/MutableReasoningGraph.java` | (present) |
| `SimpleGraphRelation` (inferred edge factory) | `…/kompile-graph-reasoning/…/model/SimpleGraphRelation.java` | (present) |
| `kompile-graphs` parent POM | `kompile-app/kompile-data/kompile-graphs/pom.xml` | 17 |
| `kompile-graph-reasoning` POM (infra-free deps) | `kompile-app/kompile-data/kompile-graphs/kompile-graph-reasoning/pom.xml` | 42–89 |
| `kompile-knowledge-graph` POM (Spring/JPA pattern) | `kompile-app/kompile-data/kompile-graphs/kompile-knowledge-graph/pom.xml` | 32–155 |

New files this design creates (none yet exist; O4 is deferred):

| New file | Purpose |
|---|---|
| `kompile-reasoning-owl-bridge/pom.xml` | Module POM with owlapi + openllet deps |
| `…/bridge/reasoner/OwlDlReasoningBridge.java` | Implements `OwlReasoner` via Openllet |
| `…/bridge/mapper/OwlOntologyMapper.java` | Bidirectional lib ↔ OWL API type translation |
| `…/bridge/mapper/ReasoningGraphABoxLoader.java` | Loads `ReasoningGraph` into `OWLOntology` |
| `…/bridge/importer/ExternalOwlImporter.java` | Reads arbitrary OWL files → `ImportResult` |
| `…/bridge/importer/OwlGraphImporter.java` | Loads OWL file → live KG store (optional) |
| (app-main) `OwlReasonerConfiguration.java` | Spring `@ConditionalOnClass` selector bean |
| (app-main) `OntologyOwlAdapter.java` | `OntologySchema` → `OwlOntology` (mentioned in O4 sketch) |

---

## 8. Open Questions

**Q1 — Reasoner choice: Openllet vs. HermiT (most urgent decision before O4 starts)**

Openllet (`com.github.galigator.openllet:openllet-owlapi:2.6.6`) is the actively maintained fork
of Pellet.  HermiT (`net.sourceforge.owlapi:HermiT:1.4.5`) has better incremental reasoning
support and is closer to the OWL API reference implementation, but is slower on property-chain
queries and has known issues with certain OWL 2 Full axioms.  The bridge design above uses Openllet
as the default and makes the factory injectable.  **Decision needed:** should both be listed as
test-scope alternatives, or should one be the single default?  A practical test is to load SNOMED
(300k+ axioms) with each and compare time-to-first-consistency-result and peak heap.

**Q2 — GraalVM native image exclusion: must this be documented explicitly?**

The OWL API uses reflection internally for its parser registry, and Openllet uses Guava + OSGI
metadata.  Neither will work in a GraalVM native image without custom `reflect-config.json`
entries.  The bridge module's `README` (or a `native-image.properties` file) should explicitly
exclude `kompile-reasoning-owl-bridge` from native compilation scope.  The CLI build currently
includes only `kompile-cli-main` — is the bridge ever expected in the native binary, or is it
purely a JVM-mode module?

**Q3 — ABox `additionalType` multi-typing gap**

`OwlRlReasoner.detectDisjointViolations` (`OwlRlReasoner.java:363`) detects multi-typing by
checking `entity.stringAttribute("additionalType")`.  `GraphEntity` has a single `type()` field
(`model/GraphEntity.java:38`).  The DL bridge gets true multi-typing for free from the OWL API's
`ClassAssertionAxiom` collection, but when it writes back to `inferredTypes`, only one class IRI
per entity is stored (the `OwlRlResult.inferredTypes` map is `Map<String, String>`).  Should
`OwlRlResult` be extended to `Map<String, Set<String>>` for multi-class membership, or should
multiple inferred types be emitted as multiple entries?  This is an API change to the lib's result
type and requires careful backward compatibility analysis.

**Q4 — Import closure handling: flatten or lazy-load?**

When `ExternalOwlImporter` calls `mgr.importsClosure(owlOnt)`, it fetches any declared
`owl:imports` URLs over HTTP.  In an air-gapped or offline deployment this will fail or hang.
Should the importer have a `offline=true` mode that sets `MissingImportHandlingStrategy.SILENT`
and skips all import fetching?  The MEMORY.md notes that kompile is designed for offline
enterprise use — this matters.

**Q5 — Inferred relation ID collision with existing graph IDs**

`OwlRlReasoner` mints inferred relation IDs as `"owl-trp-" + propName + "-" + src + "-" + tgt`
(`OwlRlReasoner.java:184`).  The DL bridge must follow the same convention, or callers that
deduplicate inferred relations by ID will merge results incorrectly.  The bridge should use a
coordinated prefix such as `"owl-dl-" + propName + "-" + src + "-" + tgt` so lib-RL results and
DL results are distinguishable when both are in play.

**Q6 — `OntologyOwlAdapter` location: bridge module or app-main?**

The O4 sketch (`owl-enhancement-design.md:700`) places `OntologyOwlAdapter` in `app-main` because
it needs `OntologySchema` (an app-core type).  But the bridge module is infra-optional (no Spring).
Could `OntologyOwlAdapter` live in `kompile-reasoning-owl-bridge` if the bridge takes a compile
dep on `kompile-app-core`?  That would pollute the bridge with app-core's Spring/JPA transitive
deps.  Recommendation: keep it in app-main as sketched, with the bridge providing only the
`OwlOntologyMapper` translation layer.

**Q7 — Conformance score integration with Phase 7 `GraphHealthSnapshot`**

The O4 sketch (`owl-enhancement-design.md:622`) says DL inconsistencies should update
`conformanceScore`.  `GraphHealthSnapshot` is in `kompile-knowledge-graph`.  When the bridge is on
the classpath, `GraphHealthSnapshot` should call `OwlDlReasoningBridge.reason()` instead of
`OwlRlReasoner.reason()`.  The `@ConditionalOnClass` `OwlReasonerConfiguration` bean (§6.2) handles
this transparently because `GraphHealthSnapshot` holds an `OwlReasoner` reference, not a concrete
type.  But `GraphHealthSnapshot` is currently constructed without dependency injection — it is built
by `GraphHealthService` with a `new`.  **Decision:** inject `OwlReasoner` into `GraphHealthService`,
or pass it as a parameter to `GraphHealthSnapshot.compute()`?

---

## 9. Summary of O4 Phases

| Sub-phase | Deliverable | Touches lib? |
|---|---|---|
| O4-a | `pom.xml` + `OwlOntologyMapper` (TBox direction only) + unit tests | No |
| O4-b | `ReasoningGraphABoxLoader` + `OwlDlReasoningBridge` + consistency/type tests | No |
| O4-c | `ExternalOwlImporter` (all 5 formats) + `ImportResult` + round-trip tests against a real OWL file | No |
| O4-d | `OwlGraphImporter` (KG store wiring) | No (adds `kompile-knowledge-graph` dep) |
| O4-e | `OwlReasonerConfiguration` (Spring `@ConditionalOnClass`) in app-main | app-main only |
| O4-f | `OntologyOwlAdapter` + `GraphHealthService` injection in app-main | app-main only |
| O4-g | CLI commands `kompile owl import/check` | CLI module only |
