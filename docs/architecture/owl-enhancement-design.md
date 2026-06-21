# OWL Enhancement Design — Kompile Graph Reasoning Library

**Date:** 2026-06-21
**Status:** Design proposal — no code yet
**Scope:** Enhancing `kompile-graph-reasoning` with OWL Web Ontology Language semantics
as a standards-based layer on top of the existing `mebn/type/` system, subject to the
hard constraint that the lib stays infra-free (deps: nd4j-api, jackson-annotations,
slf4j, lombok only).

**Builds on:** `docs/architecture/mebn-type-system-ontology-bridge.md` (the Phase-1
type system is complete and green; this design adds OWL semantics as the next layer).
Do not contradict that document.

---

## 1. Problem Statement and Scope of the Enhancement

The Phase-1 type system (`mebn/type/`) gives the lib a sound, infra-free class hierarchy
(`TypeHierarchy`, `TypeNode`, `TypeRegistry`) with single-parent `subClassOf` semantics,
attribute schemas, and three sealed `TypeConstraint` variants. It is OWL-adjacent but
stops short of OWL proper: there is no standard vocabulary for property characteristics
(transitive, functional, symmetric, inverse), no class expressions (someValuesFrom,
allValuesFrom, hasValue, cardinality restrictions), no `equivalentClass`,
`disjointWith`, or any route to a standard OWL serialization.

The enhancement proposed here adds those constructs at two levels:

1. **Lib-level (infra-free):** An OWL 2 RL profile model and a forward-chaining
   RL reasoner, both hand-written with zero new dependencies. OWL 2 RL is the right
   profile because its entailment rules are finite, PTIME, and expressible as
   first-order Horn clauses — which means they compile directly into `FolRule`s and
   can be run by the existing `FolInferenceService`. The lib reads and writes a
   narrow OWL subset (the constructs it models) in Turtle/N-Triples format using the
   hand-rolled parser already present in the codebase.

2. **Client-level (infra-optional):** Arbitrary OWL ontology import (from Protege,
   external ontologies, etc.) and OWL DL reasoning (HermiT / Openllet) belong in a
   **client module** (`kompile-knowledge-graph` or a new `kompile-reasoning-owl-bridge`
   Maven module) that may depend on `org.semanticweb.owlapi`. The lib is only a consumer
   of what the client distills from the OWL API and hands in.

---

## 2. OWL Constructs to Model in the Lib (Infra-Free)

### 2.1 Package Decision

All new OWL-model classes live in:

```
ai.kompile.graph.reasoning.mebn.type.owl
```

Rationale: OWL classes are enriched `TypeNode`s; OWL properties are enriched
`TypeConstraint`s; the OWL model is a semantic overlay on the Phase-1 type layer, not
a parallel universe. Putting it under `mebn/type/owl/` makes that containment explicit.
A separate top-level `onto/owl/` package would imply parity with the rest of the lib;
this is an enhancement of one specific subsystem.

### 2.2 `OwlClass` — enhanced `TypeNode`

`OwlClass` wraps or extends a `TypeNode`, adding OWL-specific cross-class axioms.

```
OwlClass {
    String classIri                          // e.g. "https://kompile.ai/kg/class/Person"
    TypeNode typeNode                        // the Phase-1 node this OWL class is bound to
    Set<String> subClassOfIris               // declared superclasses (IRI strings)
    Set<String> equivalentClassIris          // owl:equivalentClass partners
    Set<String> disjointWithIris             // owl:disjointWith partners
    List<OwlRestriction> restrictions        // local restrictions on this class
}
```

Relationship to existing classes:

- `OwlClass.typeNode` is the Phase-1 `TypeNode` for the same concept. `subClassOfIris`
  corresponds to the single `TypeNode.parent` link but uses IRI strings, supporting
  external class references without requiring those classes to be in the local
  `TypeRegistry`.
- `equivalentClass` has no Phase-1 analogue — it is new.
- `disjointWith` has a partial analogue in `OntologicalConstraintBuilder.addMutualExclusion`
  (`psl/OntologicalConstraintBuilder.java:80`), but that is PSL-level and not typed.
  `OwlClass.disjointWithIris` makes it an explicit class-level axiom that the RL
  reasoner can use for consistency checking.
- `restrictions` generalizes `TypeConstraint` (see §2.4).

### 2.3 `OwlObjectProperty` and `OwlDataProperty`

Object properties correspond to typed relations between `GraphEntity` individuals.
Data properties correspond to typed attribute values.

```
OwlObjectProperty {
    String propertyIri
    String domainClassIri                    // rdfs:domain
    String rangeClassIri                     // rdfs:range
    // Characteristics (booleans):
    boolean functional                       // at most one value per individual
    boolean inverseFunctional                // at most one individual with a given value
    boolean transitive                       // P(a,b) & P(b,c) → P(a,c)
    boolean symmetric                        // P(a,b) → P(b,a)
    boolean reflexive                        // P(a,a) for all a in domain
    boolean asymmetric                       // P(a,b) → ¬P(b,a)
    boolean irreflexive                      // ¬P(a,a)
    String inverseOfIri                      // owl:inverseOf
    Set<String> subPropertyOfIris            // rdfs:subPropertyOf
    Set<String> equivalentPropertyIris       // owl:equivalentObjectProperties
}

OwlDataProperty {
    String propertyIri
    String domainClassIri
    String rangeDatatype                     // xsd:string, xsd:integer, xsd:double, etc.
    boolean functional
    Set<String> subPropertyOfIris
    Set<String> equivalentPropertyIris
}
```

Relationship to existing classes:

- `OwlObjectProperty` corresponds to a `TypeConstraint.RelationConstraint` (domain/range)
  plus one or more `TypeConstraint.CardinalityConstraint` records. But `RelationConstraint`
  and `CardinalityConstraint` are attached to the **domain type's** `TypeNode`, whereas
  an `OwlObjectProperty` is a first-class IRI-identified entity that multiple classes
  can reference. The OWL model promotes properties to first-class objects. The existing
  `TypeConstraint` sealed interface (`mebn/type/TypeConstraint.java:46`) is not broken —
  it remains the internal contract for the PSL engine. `OwlObjectProperty` is the
  standards-layer wrapper that compiles down to those constraints during RL rule generation
  (§3.2).
- `OwlDataProperty` corresponds to `AttributeDefinition`
  (`mebn/type/AttributeDefinition.java:38`) with an IRI identity.

### 2.4 `OwlRestriction` — class expressions

A restriction is an anonymous class expression attached to an `OwlClass` that restricts
the values of a property on instances of that class.

```
OwlRestriction {  // sealed
    String onPropertyIri

    // Variants:
    SomeValuesFrom {  // owl:someValuesFrom — ∃P.C
        String onPropertyIri
        String fillerClassIri
    }
    AllValuesFrom {   // owl:allValuesFrom — ∀P.C
        String onPropertyIri
        String fillerClassIri
    }
    HasValue {        // owl:hasValue — P value {individual}
        String onPropertyIri
        String individualId
    }
    MinCardinality {  // owl:minCardinality / owl:minQualifiedCardinality
        String onPropertyIri
        int n
        String qualifiedOnClassIri   // null = unqualified
    }
    MaxCardinality {
        String onPropertyIri
        int n
        String qualifiedOnClassIri
    }
    ExactCardinality {
        String onPropertyIri
        int n
        String qualifiedOnClassIri
    }
}
```

Relationship to existing `TypeConstraint`:

- `MaxCardinality(n=1)` on a property = `CardinalityConstraint(ONE_TO_ONE or MANY_TO_ONE)`
  on the corresponding `RelationConstraint`. `OwlRestriction` generalizes and supersedes
  `CardinalityConstraint` at the OWL layer while backward-compatibility is preserved by
  the RL compiler emitting equivalent PSL/FOL constraints.
- `SomeValuesFrom` and `AllValuesFrom` have no Phase-1 analogue; they are entirely new
  expressive power.
- `HasValue` maps to a `TypeConstraint.AttributeRequiredConstraint` only when the property
  is a data property with a specific literal; for object properties it is new.

### 2.5 `OwlOntology` — the registry of OWL axioms

```
OwlOntology {
    String ontologyIri
    Map<String, OwlClass>          classes          // IRI → OwlClass
    Map<String, OwlObjectProperty> objectProperties // IRI → OwlObjectProperty
    Map<String, OwlDataProperty>   dataProperties   // IRI → OwlDataProperty
    Map<String, String>            sameAs           // individual IRI → canonical IRI
    // Bridge into Phase-1:
    TypeRegistry toTypeRegistry()   // derive a TypeRegistry from declared subClassOf axioms
}
```

`OwlOntology.toTypeRegistry()` is the critical integration point: it translates each
`OwlClass.subClassOfIris` into a `TypeRegistry.subtype()` call, each `OwlObjectProperty`
domain/range into a `TypeRegistry.constraint()` `RelationConstraint`, and each
`MaxCardinality(1)` into a `CardinalityConstraint`. The resulting `TypeRegistry` can
be fed directly to `TypeHierarchy.fromGraph(graph, registry)` (the existing factory at
`mebn/type/TypeHierarchy.java:127`), giving the existing MEBN/PSL engines immediate
hierarchy-aware behavior with no changes to those engines.

### 2.6 Individuals → `GraphEntity`

OWL individuals (ABox assertions) map directly onto `GraphEntity` instances:

- Class assertion `owl:ClassAssertion(C, i)` → `GraphEntity.type()` = local name of C.
- Object property assertion `owl:ObjectPropertyAssertion(P, i, j)` → `GraphRelation` with
  `type()` = local name of P, `sourceId()` = i's IRI, `targetId()` = j's IRI.
- Data property assertion `owl:DataPropertyAssertion(P, i, v)` → `GraphEntity.attributes()` entry.

The ABox is represented by the existing `ReasoningGraph` (`model/ReasoningGraph.java:36`).
The TBox (class and property declarations) is represented by `OwlOntology`. This
ABox/TBox split matches OWL semantics exactly: the lib reasons over the graph as ABox
and the ontology as TBox.

---

## 3. Profile Decision — OWL 2 RL

### 3.1 Why OWL 2 RL, not OWL DL or OWL EL

OWL 2 RL is the correct profile for an infra-free lib because its semantics are exactly
those of a forward-chaining datalog/Horn-clause system. The W3C OWL 2 RL specification
defines a fixed set of entailment rules (Table 8 of the spec) expressible as:
`IF <pattern over ABox + TBox facts> THEN <new ABox fact>`.
This maps one-to-one onto `FolRule` antecedent/consequent pairs, which the existing
`FolInferenceService` (`fol/FolInferenceService.java:73`) already evaluates.

OWL DL (full OWL 2) requires a tableau reasoner (HermiT, Openllet) — this is the
client-only boundary.

OWL EL (the tractable existential profile, used by Bio-ontologies) handles
`someValuesFrom` efficiently but does not cover property characteristics (transitive,
functional, inverse). Since the lib wants to handle transitive properties (e.g.
`subOrganizationOf*`) and functional properties (e.g. unique identifiers), RL is the
better fit.

OWL 2 RL is PTIME-complete in combined complexity, matches the lib's existing
forward-chaining capability, and covers the most useful constructs for knowledge-graph
reasoning: subclass inference, property characteristic inference, and disjointness
consistency. For the small set of `someValuesFrom`/`allValuesFrom` rules that RL also
covers (partial), the RL rules are just additional `FolRule` patterns.

### 3.2 Specific RL Entailment Rules to Implement

The following OWL 2 RL rules will be compiled into `FolRule`s by the RL rule compiler
(§3.3). This is a curated subset of Table 8, chosen for practicality on the
`ReasoningGraph` ABox:

**Subclass and property chain (TBox propagation):**

| Rule ID | If | Then |
|---|---|---|
| `scm-sco` | `C rdfs:subClassOf D`, `D rdfs:subClassOf E` | `C rdfs:subClassOf E` (transitivity) |
| `scm-spo` | `P rdfs:subPropertyOf Q`, `Q rdfs:subPropertyOf R` | `P rdfs:subPropertyOf R` |
| `cls-svf1` | `C owl:subClassOf [∃P.D]`, `P(a,b)`, `b: D` | `a: C` |
| `cls-avf` | `C owl:subClassOf [∀P.D]`, `a: C`, `P(a,b)` | `b: D` |

**ABox type propagation:**

| Rule ID | If | Then |
|---|---|---|
| `cax-sco` | `C rdfs:subClassOf D`, `a: C` | `a: D` |
| `prp-dom` | `P rdfs:domain C`, `P(a,b)` | `a: C` |
| `prp-rng` | `P rdfs:range C`, `P(a,b)` | `b: C` |

**Property characteristics → new relations:**

| Rule ID | If | Then |
|---|---|---|
| `prp-trp` | `P owl:TransitiveProperty`, `P(a,b)`, `P(b,c)` | `P(a,c)` |
| `prp-symp` | `P owl:SymmetricProperty`, `P(a,b)` | `P(b,a)` |
| `prp-inv1` | `P owl:inverseOf Q`, `P(a,b)` | `Q(b,a)` |
| `prp-inv2` | `P owl:inverseOf Q`, `Q(a,b)` | `P(b,a)` |
| `prp-fp` | `P owl:FunctionalProperty`, `P(a,b)`, `P(a,c)` | `b owl:sameAs c` |
| `prp-ifp` | `P owl:InverseFunctionalProperty`, `P(b,a)`, `P(c,a)` | `b owl:sameAs c` |

**Equivalence and disjointness:**

| Rule ID | If | Then |
|---|---|---|
| `cls-oo` | `C owl:equivalentClass D`, `a: C` | `a: D` |
| `cls-oo-sym` | `C owl:equivalentClass D`, `a: D` | `a: C` |
| `cax-dw` | `C owl:disjointWith D`, `a: C`, `a: D` | `inconsistency flag` |

**HasValue:**

| Rule ID | If | Then |
|---|---|---|
| `cls-hv1` | `C owl:subClassOf [P hasValue v]`, `a: C` | `P(a, v)` |
| `cls-hv2` | `C owl:subClassOf [P hasValue v]`, `P(a, v)` | `a: C` |

**NOT implemented in the lib (client-only, require tableau or complex grounding):**

- `owl:complementOf` (negation-as-failure, requires closed-world assumption machinery)
- `owl:unionOf` (disjunctive heads, not expressible in single-head Horn rules)
- Qualified cardinality restrictions with `someValuesFrom` combined with `min > 1`
  (generates O(N^k) groundings)
- Full OWL DL SROIQ axioms

---

## 4. The Infra-Free OWL-RL Reasoner

### 4.1 Architecture Decision: RL-as-FolRules vs. Standalone Forward-Chainer

**Evaluation:**

A standalone forward-chainer would be a new backward/forward inference loop that
evaluates pattern-match conditions over the `ReasoningGraph` directly, accumulates
derived facts in a delta set, and iterates to fixpoint. It would be approximately
300-500 lines of Java, require its own grounding logic and fixpoint detection, and
duplicate significant logic already in `FolInferenceService` and the PSL grounder.

The existing `FolInferenceService` (`fol/FolInferenceService.java:73`) already:
- Accepts a `FolRuleSet` of weighted `FolRule`s
- Grounds rules against all entity pairs with a `KnowledgeBase` interface
- Uses `LogicalConstraint` antecedent/consequent evaluation
- Emits `InferredFact`s that can be materialized back into the graph

The key reuse question is whether the `LogicalConstraint` primitives in
`mebn/logic/Constraints.java` can express the RL entailment patterns. The answer is
**yes for most rules** — `Constraints.edgeOfType`, `Constraints.hasType`,
`Constraints.edgeExists`, `Constraints.and`, `Constraints.implies` are sufficient for
the domain/range, subclass, transitivity, symmetry, and inverse rules. The `sameAs`
output from functional/inverse-functional rules requires a new `Constraints.sameAs`
atom, which is a single new factory method with no other dependencies.

**Recommendation: compile OWL RL axioms into `FolRule`s and run them through
`FolInferenceService`.**

The compiler approach has one known limitation: `FolInferenceService.buildPairs`
(`fol/FolInferenceService.java:310`) caps grounding at 10,000 pairs. For transitivity
rules over large graphs this cap may truncate the closure. The RL compiler should emit
transitivity rules with an `entityTypeScope` filter so grounding is bounded to entities
relevant to the transitive property's domain/range, keeping the grounding set small.

### 4.2 `OwlRlRuleCompiler` — translating OWL axioms to `FolRule`s

```
OwlRlRuleCompiler {
    // input: an OwlOntology (TBox)
    // output: a FolRuleSet containing the RL entailment rules for this TBox

    FolRuleSet compile(OwlOntology ontology)
}
```

For each TBox axiom in `OwlOntology`, the compiler emits one or more `FolRule`s. Examples:

**`prp-dom` (domain propagation):**
```
OwlObjectProperty{domainClassIri="C", propertyIri="P"}
→
FolRule.of("prp-dom-P",
    weight=INFINITY,  // hard rule — RL rules are crisp
    antecedent=Constraints.edgeOfType("X", "Y", localName(P)),
    consequent=Constraints.hasType("X", localName(C)))
```

**`prp-trp` (transitivity):**
```
OwlObjectProperty{transitive=true, propertyIri="P", domainClassIri="C"}
→
FolRule.builder("prp-trp-P")
    .weight(INFINITY)
    .entityTypeScope(localName(C))   // bounds grounding to domain type
    .antecedent(Constraints.and(
        Constraints.edgeOfType("X", "Y", localName(P)),
        Constraints.edgeOfType("Y", "Z", localName(P))))
    .consequent(Constraints.edgeOfType("X", "Z", localName(P)))
    .build()
```

**`cax-dw` (disjointness inconsistency):**
```
OwlClass{disjointWithIris={"D"}, classIri="C"}
→
FolRule.builder("cax-dw-C-D")
    .weight(INFINITY)
    .antecedent(Constraints.and(
        Constraints.hasType("X", "C"),
        Constraints.hasType("X", "D")))
    .consequent(Constraints.falsehood())   // new factory — signals inconsistency
    .build()
```

`Constraints.falsehood()` is a new `LogicalConstraint` factory that always evaluates to
`false`. When the antecedent is true and the consequent is `falsehood()`, the rule is
violated and the RL result records an inconsistency. This is a one-line addition to
`mebn/logic/Constraints.java`.

The compiler is a pure function: `FolRuleSet compile(OwlOntology)`. It has no runtime
state and no dependencies beyond `fol/` and `mebn/logic/`. It lives at:

```
ai.kompile.graph.reasoning.mebn.type.owl.OwlRlRuleCompiler
```

### 4.3 `OwlRlReasoner` — the entry point

```
OwlRlReasoner {
    // Run OWL RL inference over a graph given a TBox.
    // Returns new inferred relations + type assignments + consistency violations.

    OwlRlResult reason(ReasoningGraph graph, OwlOntology ontology)
}
```

```
OwlRlResult {
    List<GraphRelation>   inferredRelations    // e.g. transitive closure edges
    Map<String, String>   inferredTypes        // entityId → inferred class IRI
    List<OwlInconsistency> inconsistencies     // disjointWith violations, etc.
    FolInferenceResult    rawFolResult         // the underlying soft-truth result
    List<InferredFact>    inferenceFacts       // for persistence via InferredFactStore
}
```

`OwlRlReasoner.reason()` implementation:
1. Call `OwlRlRuleCompiler.compile(ontology)` to get the `FolRuleSet`.
2. Supplement with `OntologicalConstraintBuilder` subsumption rules derived from
   `ontology.toTypeRegistry()` so the PSL engine has type-level subsumption facts.
3. Call `FolInferenceService.inferFacts(graph, ruleSet)` to run the rules.
4. Scan the `InferredFact` list: facts with atom key `Type_*(n)` → `inferredTypes`;
   facts with atom key `Link_*(n,m)` → `inferredRelations`; violated `falsehood()`
   consequents → `inconsistencies`.
5. Wrap and return `OwlRlResult`.

### 4.4 Hard vs. Soft Semantics

OWL RL rules are crisp (weight = `Double.POSITIVE_INFINITY`). The existing `FolRule`
builder supports `weight(INFINITY)` and `FolInferenceService` already handles
`PslRule(POSITIVE_INFINITY, ...)` via `HlMrfMapInference` — this is how
`OntologicalConstraintBuilder` already emits hard rules
(`psl/OntologicalConstraintBuilder.java:130`). No change needed; the RL reasoner simply
sets `POSITIVE_INFINITY` weight on every compiled rule.

---

## 5. Import / Export — OWL Subset Reader/Writer (Infra-Free)

### 5.1 What the lib handles (hand-rolled, infra-free)

The lib writes and reads the specific OWL constructs it models (§2) in Turtle format.
The existing `RdfSupport` utility (`kompile-knowledge-graph:
knowledgegraph/io/format/RdfSupport.java:35`) already mints absolute IRIs under
`https://kompile.ai/kg/` and knows the `rdf:`, `rdfs:`, and `xsd:` namespaces.
However, that class is in `kompile-knowledge-graph`, not in `kompile-graph-reasoning`
— it depends on `PortableNode`/`PortableEdge` from that module.

The lib needs its own, independent IRI support (no cross-module dep). Proposal:

```
ai.kompile.graph.reasoning.mebn.type.owl.OwlIri {
    // namespace constants
    static final String OWL  = "http://www.w3.org/2002/07/owl#";
    static final String RDFS = "http://www.w3.org/2000/01/rdf-schema#";
    static final String XSD  = "http://www.w3.org/2001/XMLSchema#";
    static final String BASE = "https://kompile.ai/kg/";

    // helpers
    static String classIri(String name)     // BASE + "class/" + enc(name)
    static String propIri(String name)      // BASE + "prop/" + enc(name)
    static String indIri(String id)         // BASE + "node/" + enc(id)
}
```

**`OwlTurtleWriter`** — serializes an `OwlOntology` to Turtle:

```
ai.kompile.graph.reasoning.mebn.type.owl.OwlTurtleWriter {
    String write(OwlOntology ontology)
}
```

For each `OwlClass` it emits:
```turtle
<classIri> a owl:Class ;
    rdfs:subClassOf <superIri> ;      # for each subClassOfIris
    owl:equivalentClass <otherIri> ;  # for each equivalentClassIris
    owl:disjointWith <disIri> .       # for each disjointWithIris
```

For each restriction on a class:
```turtle
<classIri> rdfs:subClassOf [
    a owl:Restriction ;
    owl:onProperty <propIri> ;
    owl:someValuesFrom <fillerIri>
] .
```

For each `OwlObjectProperty`:
```turtle
<propIri> a owl:ObjectProperty ;
    rdfs:domain <domIri> ;
    rdfs:range <rangeIri> ;
    a owl:TransitiveProperty .     # if transitive=true, etc.
```

**`OwlTurtleReader`** — parses Turtle back into an `OwlOntology`. This is the harder
direction. The lib's hand-rolled parser need only handle:

- `@prefix` declarations
- Simple subject-predicate-object triples
- Blank node `[…]` notation (for restrictions only)
- `a` as shorthand for `rdf:type`
- Literal strings in `"…"` with optional `^^xsd:type` datatype

This parser does not attempt full Turtle grammar compliance. It is scoped to the subset
the `OwlTurtleWriter` emits (a compact, predictable subset). For arbitrary Turtle from
external sources, the client OWL bridge handles parsing via the OWL API (§5.2).

**N-Triples output** is even simpler — the `OwlTurtleWriter` can have a companion
`OwlNTriplesWriter` that emits the same triples without blank nodes
(blank node restrictions become reified triples with a mint IRI).
N-Triples output is useful for streaming and triplestore import.

### 5.2 What the Client Handles (OWL API + DL Reasoner)

The client module (proposed: `kompile-reasoning-owl-bridge`, living adjacent to
`kompile-knowledge-graph`) carries:

- `org.semanticweb.owlapi:owlapi-distribution` as a Maven dependency (this is the
  reason the client must be separate — owlapi pulls in OSGI headers, Guava, etc.)
- `net.sourceforge.owlapi:HermiT` or `io.github.owlcs:openllet-owlapi` for DL reasoning
- An adapter class `ExternalOwlImporter` that reads an arbitrary OWL file using the OWL
  API and extracts the supported constructs (subClassOf, equivalentClass, disjointWith,
  property characteristics, restrictions) into an `OwlOntology` that the lib can consume

```
// CLIENT MODULE ONLY:
ExternalOwlImporter {
    OwlOntology importFrom(java.io.InputStream owlFile)
    // uses OWLOntologyManager, OWLAxiomVisitor to walk the ontology
    // extracts only the RL-compatible axioms into OwlOntology
    // logs a warning for DL axioms that are dropped (complementOf, etc.)
}

OWLDLReasoningBridge {
    // uses HermiT/Openllet for full DL reasoning
    // outputs results as OwlRlResult (same type as the lib's RL reasoner)
    // allows client to swap between lib RL and full DL via a single interface
    OwlRlResult reason(ReasoningGraph graph, OwlOntology ontology)
}
```

The interface `OwlReasoner`:
```java
// lives in kompile-graph-reasoning (no deps), implementable by both sides
public interface OwlReasoner {
    OwlRlResult reason(ReasoningGraph graph, OwlOntology ontology);
}
```

`OwlRlReasoner` (lib) implements `OwlReasoner` using FOL rules.
`OWLDLReasoningBridge` (client) implements `OwlReasoner` using HermiT/Openllet.
Callers in `KnowledgeGraphReasoningAdapter` accept an `OwlReasoner` — the DI container
injects either implementation based on whether the OWL-bridge module is on the classpath.

---

## 6. Bridge to the Existing World

### 6.1 Connection to `OntologySchema`

`OntologySchema` (`process/ontology/OntologySchema.java:39`) is the existing KG
ontology: a list of `EntityTypeDefinition`s (with typed fields, no hierarchy) and
`RelationshipTypeDefinition`s (with domain/range/cardinality). The Phase-2 bridge
described in `mebn-type-system-ontology-bridge.md §3` maps `OntologySchema` onto a
`TypeRegistry`.

The OWL layer extends this bridge as follows:

**OntologySchema → OwlOntology (new direction):**

```
OntologyOwlAdapter  (lives in ai.kompile.app.ontology — app-main, NOT in the lib)
    OwlOntology toOwlOntology(OntologySchema schema)
```

- Each `EntityTypeDefinition.name` → `OwlClass` with IRI `BASE + "class/" + name`.
- Each `RelationshipTypeDefinition` → `OwlObjectProperty` with domain/range IRIs from
  `sourceEntityType`/`targetEntityType`, and `functional=true` when cardinality is
  `ONE_TO_ONE` or `ONE_TO_MANY`.
- The `EntityClassification` coarse bucket (ACTOR, METRIC, etc.) → a shared superclass
  `OwlClass` (e.g. `BASE + "class/Actor"`), with each classified type getting a
  `subClassOfIris` entry pointing to it. This reuses the existing `EntityClassification`
  as a two-level hierarchy until a finer `superType` field is added to
  `EntityTypeDefinition` (see §7, open question 1).
- `RelationshipTypeDefinition.cardinality == ONE_TO_ONE` → `OwlObjectProperty.functional=true`
  AND `inverseFunctional=true`.

**OwlOntology → enhanced bridge:**

After `OntologyOwlAdapter.toOwlOntology(schema)` produces an `OwlOntology`, the
Phase-2 bridge gains an additional step:
```
OntologyTypeSystemAdapter.enrichFromOwl(OwlOntology) → TypeRegistry
```
This replaces the flat `subtype(child, classification)` calls with the full
`subClassOf` chain from the `OwlOntology`. The `OwlOntology.toTypeRegistry()` method
(§2.5) does the translation inside the lib without touching Spring or the app layer.

### 6.2 Conformance Score Enhancement (Phase 6 + OWL)

The existing Phase-6 `conformanceScore` (`0..1`) measures ontology binding coverage.
The OWL layer adds a second dimension: **RL entailment soundness**. After running
`OwlRlReasoner.reason(graph, ontology)`, any `OwlInconsistency` in the result
decrements the conformance score. The score formula becomes:

```
conformanceScore = (covered types / total types) × (1 - inconsistency_rate)
```

This is computed in `GraphHealthSnapshot` (Phase-7 metric carrier) by calling
`OwlRlReasoner` lazily when an `OwlOntology` is available.

### 6.3 Phase-2 Ontology Bridge Enrichment

The existing Phase-2 design (`mebn-type-system-ontology-bridge.md §3`) describes
`OntologyTypeSystemAdapter.deriveConstraintRules(hierarchy)` which produces a
`FolRuleSet`. The OWL layer replaces this with:

```
OntologyTypeSystemAdapter.deriveOwlRlRules(schema) → FolRuleSet
```

which goes `schema → OwlOntology → OwlRlRuleCompiler.compile() → FolRuleSet`. This
is richer than the Phase-2 approach because it includes property characteristic rules
(transitivity, symmetry, inverse) that `deriveConstraintRules` could not produce.
The Phase-2 method can be deprecated in favor of this one once the OWL layer is built
(additive step, not a breaking change).

---

## 7. Phased Plan and Backward Compatibility

### Phase O1 — OWL Model + TypeRegistry Integration

**Scope:**
- Add package `ai.kompile.graph.reasoning.mebn.type.owl`
- Implement `OwlClass`, `OwlObjectProperty`, `OwlDataProperty`, `OwlRestriction` (sealed),
  `OwlOntology`, `OwlIri`
- Implement `OwlOntology.toTypeRegistry()` — the bridge into Phase-1
- Add `OwlReasoner` interface to `mebn/type/owl/`
- **No changes to any existing class.** Entirely additive.

Test coverage required:
- `OwlOntologyTest`: declare classes with `subClassOf`, call `toTypeRegistry()`, verify
  `TypeHierarchy.isA()` returns true across the derived hierarchy.
- `OwlPropertyTest`: functional/transitive flags survive serialization round-trip.

Existing tests that must stay green: all 252+ tests in the lib. Zero risk: nothing
existing is touched.

### Phase O2 — OWL-RL Reasoner via FolRules

**Scope:**
- Implement `Constraints.falsehood()` in `mebn/logic/Constraints.java` (one line)
- Implement `OwlRlRuleCompiler` for the 14 entailment rules in §3.2
- Implement `OwlRlReasoner`, `OwlRlResult`, `OwlInconsistency`

Test coverage required:
- `OwlRlReasonerTest`: fixture ontology with transitive `subOrganizationOf`, verify
  that A→B, B→C causes A→C to appear in `inferredRelations`.
- `OwlDomainRangeTest`: property with domain `Person`; assert that after reasoning,
  `GraphEntity` that is the source of such a relation gets `inferredTypes` entry
  `Person`.
- `OwlDisjointnessTest`: two entities both typed `Person` and `Robot` (with `disjointWith`)
  appear in `inconsistencies`.

`Constraints.falsehood()` change: one new static factory in `Constraints.java:42`,
fully backward-compatible (purely additive).

### Phase O3 — Turtle OWL Import/Export

**Scope:**
- Implement `OwlTurtleWriter`, `OwlNTriplesWriter`, `OwlTurtleReader` in `mebn/type/owl/`
- Round-trip test: write `OwlOntology` → Turtle → read → verify axiom equality.

Dependency note: `OwlTurtleReader` needs only `java.io.BufferedReader` — no parser
libraries. The grammar it handles is a minimal, predictable subset. If a production use
case requires full Turtle parsing from external sources, that falls to Phase O4.

### Phase O4 — Client OWL-DL Bridge (Deferred)

**Scope:**
- New Maven module `kompile-reasoning-owl-bridge` (or sub-module of `kompile-knowledge-graph`)
- `ExternalOwlImporter`, `OWLDLReasoningBridge`
- `OntologyOwlAdapter` in app-main
- `OntologyTypeSystemAdapter.deriveOwlRlRules(schema)`
- Update Phase-6 `conformanceScore` formula in `GraphHealthSnapshot`

Dependencies added only to the new module / app-main (not to `kompile-graph-reasoning`):
- `org.semanticweb.owlapi:owlapi-distribution:5.x`
- `net.sourceforge.owlapi:HermiT:1.4.x` (or Openllet)

**This phase touches app-main and requires the normal build/test cycle for that module.**

---

## 8. Open Questions / Decisions for the Implementer

**1. OWL EL in addition to RL for existential reasoning? (Profile scope decision)**

The `someValuesFrom` and `allValuesFrom` restrictions (§2.4) are partly covered by RL
rules `cls-svf1` and `cls-avf`. But these rules require the filler class and the
property to both appear in the ABox, and the grounding is O(N²) over entity pairs.
OWL EL covers `someValuesFrom` with a PTIME algorithm that avoids full pairwise
grounding. For ontologies heavy on existential restrictions (bio/medical ontologies),
EL is dramatically faster. The question is: does the kompile KG ontology have enough
existential restrictions to justify adding an EL-style completion algorithm alongside
the RL rule compiler? Recommendation: ship RL first; revisit if `someValuesFrom`
performance is a bottleneck.

**2. Reasoner interface: where does `OwlReasoner` live?**

Placed in `mebn/type/owl/OwlReasoner.java` in this design. But an alternative is to
place it in a new `reasoning/` sub-package to group it with `FolInferenceService`.
The decision matters for API discoverability. Recommendation: `mebn/type/owl/` is
acceptable for O1/O2; move to `reasoning/` if the reasoning layer grows to have more
pluggable strategies.

**3. How to handle the 10,000-pair grounding cap for transitive closure?**

`FolInferenceService.buildPairs` caps at 10,000 pairs to avoid explosion
(`fol/FolInferenceService.java:310`). Transitive closure over a graph with 1,000
domain-typed entities needs 1M pair evaluations. Options:
- (a) `entityTypeScope` filtering: the RL compiler scopes transitivity rules to the
  domain class, reducing the pair count to only domain-typed entities.
- (b) Increase the cap per rule based on expected arity (new `maxGroundings` field on
  `FolRule`).
- (c) A separate BFS/DFS transitive-closure pass for `owl:TransitiveProperty` rules
  that short-circuits the PSL grounding entirely and directly emits `GraphRelation`s.

Option (a) is the first step; (c) is the fallback if transitivity is a hot path. The
transitivity rule frequency in typical KG schemas is low (usually 1-3 properties),
so (a) should be sufficient for O2. **Decision needed before implementing `prp-trp`.**

**4. `OwlInconsistency` reporting vs. PSL soft-truth: should disjointness be hard?**

In the RL rule for `cax-dw`, should the `weight` be `POSITIVE_INFINITY` (hard rule,
makes the PSL solver infeasible if violated) or a large finite weight (soft, the PSL
solver penalizes but continues)? Hard rules make inconsistency detection crisp and
easy to surface. Soft rules allow the reasoner to handle noisy data (a node typed both
`Person` and `Robot` due to extraction error is penalized, not crashed). Recommendation:
emit `cax-dw` as a soft rule with a high weight (e.g. 1000.0) and a flag on
`OwlInconsistency` indicating the violation degree. This matches PSL's philosophy of
soft constraints on noisy KG data. **Decision: soft or hard?**

**5. Phase O4 module location: sub-module of `kompile-knowledge-graph` or new sibling?**

The OWL API bridge could live as a sub-module under `kompile-graphs/` alongside
`kompile-graph-reasoning` and `kompile-knowledge-graph`, or as a sub-module of
`kompile-knowledge-graph` itself. The latter is simpler (no new parent POM entry);
the former is cleaner (OWL reasoning is a peer of KG reasoning, not subordinate to
the KG store). Recommendation: new sibling module `kompile-reasoning-owl-bridge` under
`kompile-graphs/`. **Decision needed before starting Phase O4.**

**6. Restriction support scope: full or partial?**

The `OwlRestriction` sealed type (§2.4) includes `SomeValuesFrom`, `AllValuesFrom`,
`HasValue`, `MinCardinality`, `MaxCardinality`, and `ExactCardinality`. The RL rule
compiler needs to handle all six to be RL-complete. But in practice, most KG ontologies
derived from `OntologySchema` will only use `MaxCardinality(1)` (functional properties)
and possibly `HasValue`. Implementers may choose to start with only `MaxCardinality`
and `HasValue` for O2, deferred the rest to a later PR. **Decision: full or partial for
O2?**

---

## 9. File Cross-Reference

| Concept | File | Key lines |
|---|---|---|
| Phase-1 type system backbone | `mebn/type/TypeHierarchy.java` | 68, 95-131, 175-192 |
| `TypeNode` — single-parent parent pointer | `mebn/type/TypeNode.java` | 47-64, 78-95 |
| `TypeRegistry` fluent API | `mebn/type/TypeRegistry.java` | 66-95, 125-152 |
| `TypeAttributeSchema` — attribute inheritance merge | `mebn/type/TypeAttributeSchema.java` | 43-112 |
| `TypeConstraint` sealed — RelationConstraint/Cardinality | `mebn/type/TypeConstraint.java` | 46-130 |
| `AttributeDefinition` — typed attribute descriptors | `mebn/type/AttributeDefinition.java` | 38-116 |
| `FolRule` — weighted rule with antecedent/consequent | `fol/FolRule.java` | 55-97, 154-188 |
| `FolRuleSet` — ordered rule program | `fol/FolRuleSet.java` | 39-68 |
| `FolInferenceService.infer` — FOL→PSL pipeline | `fol/FolInferenceService.java` | 73-120, 222-288 |
| `FolInferenceService` grounding cap | `fol/FolInferenceService.java` | 310 |
| `Constraints` factory — atomic predicates | `mebn/logic/Constraints.java` | 42-77 |
| `OntologicalConstraintBuilder` — hard PSL rules from type pairs | `psl/OntologicalConstraintBuilder.java` | 56-145 |
| `ReasoningGraph` interface | `model/ReasoningGraph.java` | 36-78 |
| `GraphEntity` — individual carrier | `model/GraphEntity.java` | 38-130 |
| `GraphRelation` — typed relation carrier | `model/GraphRelation.java` | 34-131 |
| `OntologySchema` — KG ontology root | `process/ontology/OntologySchema.java` | 39-53 |
| `RdfSupport` — existing IRI mint/encode utility (KG module) | `knowledgegraph/io/format/RdfSupport.java` | 35-80 |
| Existing N-Triples importer (KG module) | `knowledgegraph/io/format/NTriplesGraphImporter.java` | 51-60 |
| Existing ontology bridge design doc | `docs/architecture/mebn-type-system-ontology-bridge.md` | §2-3 |
| Kompile graph reasoning pom (infra-free deps) | `kompile-graph-reasoning/pom.xml` | 42-89 |
