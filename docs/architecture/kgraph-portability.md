# KGraph portability: storage is not execution

A `.kgraph` is a portable graph container, **not** a runtime bundle. Copying it
can move facts and learned artifacts; it does not install a chat model, encoder,
native backend, or MCP transport. `kompile-chat-local` consumes the same graph
library through its JVM bridge or an Android native graph backend.

## Container boundary

`UnifiedGraphFormat`, `UnifiedGraphWriter`, and `UnifiedGraphReader` define the ZIP:

| Entry | Meaning |
|---|---|
| `manifest.json` | Format/version, counts, metadata, inventories |
| `entities.jsonl`, `relations.jsonl` | Portable-v2 topology and properties |
| `schemas/index.json` | v2 schema inventory |
| `weights.json`, `opinions.jsonl` | Named weights and subjective-logic opinions |
| `vectors/*.kvec` | Primary/additional vector layers with dimensions and dtype |
| `models/<artifact-name>` | Opaque attached artifacts; names can contain nested paths |
| `topology/links.bin`, `topology/adjacency.bin`, `relation-properties.jsonl` | Compact-v3 topology alternative |

CLI `LocalProjectGraphBackend.exportGraph` selects `PORTABLE_V2`.
Its canonical folder-local save selects `COMPACT_V3`. Do not copy an internal v3
file to Android expecting import compatibility: `KgraphArtifactValidator`
currently accepts only v1/v2 and requires v2 schemas. The Java reader can accept v3.
No version bump is needed for the consumer changes described here.

## Current local execution contract

`graph_load` returns a `portability` inventory. Preloaded sessions expose the same
inventory under `data.portability` for `graph_reasoning_query` `ASSETS`/`OVERVIEW`.
It describes this consumer, not every backend that might read the archive.

- **ACTIVE**: decoded and used by a named local execution path.
- **INSPECTION_ONLY**: bytes are retained/inspectable, but not activated as a model.
- **INVALID**: a known activation contract is malformed, incomplete, or stale.
  The graph can still be queried using its other components.

| Component | Local consumer behavior |
|---|---|
| Entities/relations | Projected observed facts preserve `min(weight, confidence)`, provenance, and relation timestamps; not converted to certainty |
| `NOT_` relations | Projected as negative evidence against the unprefixed predicate |
| `datalog_rules` | Strict activation decode, bounded materialization; malformed bundles explicitly reported INVALID. Crisp Datalog binarizes observed values >= 0.5; completion/termination is reported |
| `reasoning/mebn-theory.v1.json` | Canonical learned JSON theory decoded, restricted to the requested neighborhood, then executed by Java SSBN inference. Missing/corrupt/stale model is an error, not a structural rebuild |
| `models/kge.json` + `kge`, `kge-relations` | Trained TransE/RotatE triple score and predictions, including relation vectors; missing/invalid/stale data errors instead of substituting cosine |
| Other vectors | Cosine similarity remains available without a trained KGE model. No text encoder is bundled by these vector rows |
| `reasoning/fol-psl-program.v1.json` | Strict versioned predicate/atom/rule program; bounded Java ADMM via `graph_psl`, with explicit evidence and hard-constraint/convergence reporting |
| `reasoning/graph-psl-weights.v1.json` | Versioned weights for the five default graph-PSL rules; fresh graph grounding and bounded Java scalar inference for structural RANK/SIMILAR |
| `reasoning/psl-weights.json` | Compatibility for recognized default graph-rule signatures only; arbitrary rule-display strings are rejected |
| `reasoning/consensus-targets.v1.json` | Strict versioned **training-target** snapshot with saved `nX` entity aliases; inspection-only, not a new posterior, fact, or executable consensus trainer |
| `reasoning/fol-psl-program.bin`, incremental PSL cache, legacy theory/consensus binaries | Preserved, inspection-only in this consumer; local ranking never deserializes them. Java serialization is not a portable execution contract |
| Other artifact names | Inspection-only unless explicitly registered; ZIP presence is not support |
| Weight maps/opinions | Preserved graph data; presence alone does not install an executable learner or program |
| Quantitative models/formulas | Existing MODELS/CALCULATE/SCENARIO/SOLVE_TARGET graph-resident formula execution; not arbitrary external code execution |
| `graph_bayes` | Structural network from topology, separate from learned `ask_graph_mebn` |
| Chat/model generation | Requires separately installed compatible chat model and runtime |

Learned PSL, MEBN, KGE and consensus snapshot validation also require matching projected-code generation
receipts when `codeIndexGeneration.<project>` is present. MEBN requests reject scopes
above 10,000 candidate grounding bindings before SSBN allocation; the entity cap alone
is not a Cartesian-product budget. This is not a wall-clock guarantee for exact inference.

Structural mutations through the local session clear old inferred facts/lineage,
rematerialize rules, and mark learned reasoning/KGE state stale. Save/reload preserves
that invalidation. Loading an unchanged graph does not mark it stale. Programmatic
callers mutating the exposed graph must call `reprimeKb()` afterward.

## PSL and consensus boundaries

The PSL JSON contract is `kompile-graph-psl-weights`, version 1,
`graph-psl-default-v3`: propagation, abduction, conflict, prior-forward and
prior-reverse weights. It restores parameters into a freshly built default graph
program, **not** an arbitrary archived program or solver checkpoint. Weights must
be finite and in `[0, 1,000,000]`; no silent rescaling is performed. A present invalid
canonical artifact blocks legacy fallback. Without learned weights, the same
bounded path can run the default program and reports that fact.

Local structural RANK/SIMILAR allow at most 1,000 entities, 2,000 relations and
10,000 candidate grounded rules. Stale models, malformed weights and excess work
return explicit errors. Inference reports its solver, learned/default parameters,
ground-rule count and convergence; nonconvergence is PARTIAL, not successful
convergence. This is a Java-only solver, not a native solver qualification.

The archived-program contract is `kompile-psl-program`, version 1, with
`semantics: "inference-program"`. It preserves structured terms (including
uppercase constants), predicate declarations, observed/target atoms, logical rules,
and supported arithmetic rules. `graph_psl` returns predicate truth assignments
rather than reducing arbitrary predicates to entity relevance. It decodes a fresh
program for each request; evidence fixes exact archived target keys and cannot
replace observations. It never deserializes the legacy program or incremental cache.

Grounding is fail-closed at 2,000 atoms, 10,000 combined ground rules, 100,000
atom incidences and 200,000 work units (including failed joins, closed-world
expansion, outer bindings and summation scans). Java ADMM runs at most 2,000
iterations, without native/tensor auto-selection. Nonconvergence or unsatisfied
hard constraints yields PARTIAL. JSON storage bounds are separate from these
execution limits; a stored program can be valid but too large to execute here.
External functions, arithmetic filters, multiple/non-final summation variables,
independent same-predicate sums with different argument prefixes, and constant-only
arithmetic are rejected: the current grounder does not implement
those semantics faithfully. This is a restricted portable language profile, not
support for every PSL extension or a solver checkpoint. New learning writes the
canonical program alongside the compatibility binary. Unsupported or oversized
programs replace that JSON with a `kompile-psl-program-unavailable` diagnostic:
legacy learning continues, but portable execution reports the reason and never
uses an older program or falls back to the binary.

The consensus JSON contract is `kompile-consensus-targets`, version 1, with
`semantics: "training-targets"`. It saves literal key/value entries and the aliases
from the actual training pass rather than reconstructing aliases after topology
changes. Values are finite `[0,1]`; duplicate keys, unsupported fields/versions,
invalid aliases and oversized snapshots fail strict decoding. The lifecycle writes
both canonical JSON and legacy binaries for compatibility. CLI verification prefers
canonical JSON and labels the value `consensusTrainingTarget`; the existing
`learnedPosterior` field is only a compatibility alias. Neither determines the
verification verdict. Invalid canonical JSON never falls back to binary targets.

Current hybrid/MEBN grounding still uses raw comma-delimited entity IDs. New
learning rejects empty IDs, commas, parentheses, control characters, surrounding
whitespace and colliding normalized relation types **before training or publishing
artifacts/opinions**. Historical literal snapshots remain inspectable; ambiguous
IDs are withheld from CLI learned-reference lookups. Supporting arbitrary opaque
IDs in new consensus learning requires an end-to-end grounding-key migration, not
JSON escaping alone.

Managed import reserves the canonical program, weights and consensus artifact names: without an importer that claims
them, preflight fails before graph mutation rather than silently dropping them.
This guard does not claim managed execution support.

## Phone and MCP boundary

Android imports SAF-selected raw v1/v2 files into private graph storage and invokes
`AndroidNativeGraphBackend` internally. That is graph consumption, **not** an
externally reachable MCP server. `GraphMcpServer(GraphToolBackend)` is backend-neutral,
but the current MCP launcher/stdio transport is a desktop CLI, not an APK service.

An Android external MCP transport still needs an explicit lifecycle/access design
(e.g. same-device integration vs an authenticated network endpoint). Do not open a
listener merely because a graph was imported. The runtime inventory does not claim
that any external endpoint is running.

The local session fully materializes the archive. F16/I8 vectors are expanded to
Java arrays: archive bytes are not a peak-RAM estimate, and Android's ZIP preflight
limits are not guarantees that every accepted graph fits a phone.

## Qualification and remaining gaps

Regression tests exercise portable-v2 writer -> local load -> real MCP tools/call,
confidence preservation, derived-state invalidation, malformed artifact reporting,
learned JSON MEBN/evidence, and relation-sensitive KGE. Isolated-classloader tests
exercise learned MEBN, RotatE, portable PSL ranking and archived-program execution
without ND4J on the classpath. Archived-program tests cover structured rule parity,
Java ADMM convergence/non-convergence, evidence isolation, empty-sum constraints,
constant/variable identity, and fail-closed grounding budgets.
PSL/consensus tests additionally cover canonical/legacy parameter precedence,
strict snapshot save/reload, unchanged binary cache bytes, stale receipts, scope
limits, failed line-search reporting and ambiguous-key rejection.

These are JVM interchange tests, not an invocation of the full CLI crawl/export
pipeline or a native/device receipt.

### Graph-only Android AOT qualification

Use the existing `kompile-graph-reasoning-local` **stock GraalVM Android AOT**
recipe: GraalVM 21.0.10 (`jvmci-23.1-b84`) emits an AArch64 relocatable image;
NDK r28b (`28.1.13356709`) links it against Android API 28 bionic support
libraries. This is not Gluon, GraalPy, an SDX accelerator build, or a new MCP
transport. The stable `kgr_*` C ABI remains the Android backend boundary.

From the repository root, with installed toolchain paths and an already verified,
matching support-library directory:

```sh
env SDX_NATIVE_IMAGE_MAX_HEAP=12g SDX_NATIVE_IMAGE_THREADS=2 \
  /home/agibsonccc/dev-apps/mvn/bin/mvn -o \
  -pl :kompile-graph-reasoning-local -am -Pandroid-aot install -DskipTests \
  -Dnd4j.backend=nd4j-native -Dkompile.backend=cpu \
  -Dkompile.android.ndk="$ANDROID_NDK" \
  -Dkompile.android.graalvm="$GRAALVM_HOME" \
  -Dkompile.android.jobs=2 \
  -Dkompile.android.reuse.support.dir="$VERIFIED_BIONIC_SUPPORT"
```

The reuse directory contains the pinned JDK/SVM archives and JDK patch receipt;
the builder validates these rather than trusting a directory name. Complete
support reuse avoids source checkout operations; it does **not** reuse the graph
image. See the module's `build-android-ndk.sh` and `verify-android-ndk.sh` for
preflight and ABI checks. Output beneath the module is
`target/android-aot/jni/arm64-v8a/libkompile_reasoning_android.so`, with
`target/android-aot/metadata/build.properties`; Maven installs the
`android-arm64` SDK ZIP classifier.

Fresh host cross-build qualification (2026-10-02 UTC, process `proc-016`):

- Six-module offline reactor install passed in 5m29s, using the current consumer
  classes and verified cached support libraries; tests were skipped in this AOT
  build, independently of the JVM regression runs above.
- The verifier passed exact 12-symbol `kgr_*` exports, ELF64 AArch64 Android
  identity, 16 KiB load alignment, RELRO/BIND_NOW, and dependencies limited to
  `libc`, `libdl`, `liblog`, `libm`, and `libz`. No host GLIBC/GLIBCXX or BLAS
  dependency was accepted.
- Library size: 124,135,504 bytes. SHA-256:
  `d402b2cf859fede6290b916b124ff52a38d0ab18dcc33685f9b48d5a1fb72d3a`.

This establishes native compilation/link closure, **not execution on a phone**.
No Android device was attached. Existing APK/AAR binaries still need rebuilding
or repackaging to receive this library. Native PSL/MEBN/KGE dispatch, phone peak
memory, external Android MCP transport, unsupported PSL language extensions,
and consensus retraining on the phone remain separate qualification gaps; do
not label them done based on a JVM round trip or a successful AOT build.
