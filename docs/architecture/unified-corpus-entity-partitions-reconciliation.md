# Unified Corpus and Entity Partitions: Codebase Reconciliation

## Verdict

The specification is **substantially real at the extraction, graph, evidence, and reasoning layers, but not yet real as a persistent entity-partition orchestration model**. Kompile already has independently attributed chunks, structure-aware and semantic chunkers, bounded per-proposition LLM passes, cross-chunk entity resolution, claim/evidence matching, supporting/refuting dossiers, graph-neighborhood and community views, graph embeddings, process reasoning, truth maintenance, snapshots, sync adapters, and resource-bounded crawl batches. The important gap is narrower than the specification suggests: there is no durable `EntityPartition`/`EvidenceManifest` aggregate with discovery-policy-relative lifecycle, coverage state, access constraints, ordered mini-batch scheduling, and selective invalidation. Thus the pipeline and reasoning substrate are mostly done; the partition control plane is mostly absent.

## Coverage

| Spec section | Status | Where it lives (file:line) | Note |
|---|---|---|---|
| 1. Unified corpus | IMPLEMENTED | `kompile-app/kompile-data/kompile-graphs/kompile-knowledge-graph/src/main/java/ai/kompile/knowledgegraph/unified/ExtractionToUnifiedGraph.java:55`, `:58`; `kompile-app/kompile-data/kompile-crawlers/kompile-crawl-graph/src/main/java/ai/kompile/crawl/graph/GraphExtractionOrchestrator.java:1504`, `:1506` | Extraction results merge into a shared graph while chunk/document provenance is stamped on nodes and edges; aliases and attributes union across chunks. This is graph unification, not text concatenation. |
| 2. Hierarchical corpus units | PARTIAL | `kompile-app/kompile-app-parent/kompile-app-core/src/main/java/ai/kompile/app/core/chunking/HtmlChunker.java:38`, `:181`, `:188`, `:405`; `kompile-app/kompile-app-parent/kompile-app-core/src/main/java/ai/kompile/app/core/chunking/SemanticChunker.java:37`, `:105`; `kompile-app/kompile-app-parent/kompile-app-core/src/main/java/ai/kompile/core/graphrag/passes/ExtractionPassPrompts.java:59`; `.../passes/ExtractionProposals.java:162`, `:166` | HTML preserves headings, blocks, and table rows; semantic chunking groups sentences; pass 1 emits atomic propositions with polarity, modality, time, condition, and evidence. A universal persisted source-object → structural-unit → segment → proposition path was not found, and source-native email/Slack/Jira structural adapters were not found in the scoped modules. |
| 3. Entity-partition layer | PARTIAL | `kompile-app/kompile-app-parent/kompile-app-core/src/main/java/ai/kompile/core/graphrag/passes/PassContext.java:36`, `:47`, `:97`; `kompile-app/kompile-data/kompile-graphs/kompile-graph-reasoning/src/main/java/ai/kompile/graph/reasoning/subgraph/SubgraphMaterializer.java:31`; `.../subgraph/CommunityViewMaterializer.java:27`, `:30` | `PassContext.partitionId` records scheduling scope, and bounded subgraph/community views provide logical evidence scopes. No persistent `EntityPartition`, partition membership store, or union-of-discovery-channels builder was found (searched: `EntityPartition|PartitionManifest|WorkingSet|EvidenceManifest`). |
| 4. Partition versus mini-batch | PARTIAL | `kompile-app/kompile-data/kompile-crawlers/kompile-crawl-graph/src/main/java/ai/kompile/crawl/graph/CrawlBatchPlanner.java:30`, `:43`, `:60`, `:70`, `:95`; `.../passes/PassContext.java:36` | Cost-balanced, hard-capped execution batches exist and `partitionId` exists, but no persistent logical partition decomposed into recorded mini-batches or batch-delta commit protocol was found. |
| 5. Discovering connected chunks | PARTIAL | `kompile-app/kompile-data/kompile-graphs/kompile-knowledge-graph/src/main/java/ai/kompile/knowledgegraph/resolution/EntityResolutionService.java:31`; `.../subgraph/SubgraphMaterializer.java:31`; `.../embedding/GraphEmbeddingResolver.java:33`; `.../claims/DossierBuilder.java:28`, `:168`, `:201`, `:217` | Alias/fuzzy resolution, bounded neighborhoods, embeddings, direct/path/rule/KGE evidence, and explicit counter-evidence exist. One discovery policy that routes chunks through direct identifier, structured relationship, process, topic, semantic, and contradiction channels does not. |
| 6. Entity evidence manifests | ABSENT | not found (searched: `EvidenceManifest|PartitionManifest|WorkingSet|directly connected|semantically proposed|inaccessible|deferred|invalidated`) | `ClaimDossier` is a claim assessment, not an entity evidence manifest. No lifecycle/category ledger or policy-relative completeness record exists. |
| 7. Connected entity priors | PARTIAL | `kompile-app/kompile-data/kompile-graphs/kompile-graph-reasoning/src/main/java/ai/kompile/graph/reasoning/claims/DossierBuilder.java:28`, `:42`, `:217`, `:242`; `.../embedding/GraphEmbeddingResolver.java:33` | Dossiers fuse direct, verifier, path, KGE, and mined-rule signals, and graph embeddings expose bounded-neighborhood priors. A versioned per-entity profile containing type/role/category/process/temporal distributions is not present. |
| 8. Grouping schemes | PARTIAL | `kompile-app/kompile-data/kompile-graphs/kompile-graph-reasoning/src/main/java/ai/kompile/graph/reasoning/subgraph/SubgraphMaterializer.java:31`; `.../subgraph/CommunityViewMaterializer.java:27`, `:34`, `:64`; `.../community/LouvainDetector.java:145`, `:279` | Ego/neighborhood and community scopes are implemented, including induced views and halos. Pair, process-instance, category, temporal, contradiction-centered, and hybrid partitions are not first-class persisted grouping types. |
| 9. Default grouping strategy | ABSENT | not found (searched: `entity group|category subpartition|hybrid partition|partition strategy`) | Community/subgraph primitives could implement it, but no default two-level entity-group/category scheduler exists. |
| 10. Bootstrapping prerequisites | PARTIAL | `.../knowledgegraph/resolution/EntityResolutionService.java:31`; `.../knowledgegraph/unified/GraphSnapshotService.java:42`, `:69`; `.../passes/PassContext.java:36`, `:42`, `:97`; `.../GraphExtractionOrchestrator.java:1729`, `:1737` | Chunk IDs, entity resolution, graph snapshots, schema/model/partition pins, and provenance exist. Immutable structural-unit/segment registries, preliminary mention/topic registries, chunk-relationship registries, and ACL propagation were not found. |
| 11. Bootstrapping without a reliable graph | PARTIAL | `.../knowledgegraph/resolution/EntityResolutionService.java:31`; `.../passes/DecomposedExtractionPipeline.java:43`, `:45`, `:48`; `.../community/LouvainDetector.java:132` | Conservative candidate-bounded resolution and later community detection supply pieces of the phased bootstrap. No phase state machine, expansion-reason ledger, reconciliation loop, or uncertain-chunk revisit scheduler exists. |
| 12. Partition construction procedure | PARTIAL | `.../passes/DecomposedExtractionPipeline.java:43`, `:78`, `:233`, `:294`, `:528`; `.../CrawlBatchPlanner.java:60`; `.../claims/DossierBuilder.java:101` | Candidate retrieval, bounded passes, evidence validation, batching, claim aggregation, and contradiction flagging exist. They are not orchestrated as a discover/categorize/process/expand/reconcile/close partition lifecycle. |
| 13. Mini-batch ordering | PARTIAL | `.../passes/DecomposedExtractionPipeline.java:45`, `:233`, `:430`, `:474`, `:528`; `.../ExtractionPassPrompts.java:59`, `:149`, `:206`, `:261` | There is a strong within-proposition order: atomic proposition → identity → epistemic/attribution → relation → claim/evidence matching. There is no cross-chunk evidence-priority order (authoritative records first, contradictions later) or chronological process-batch scheduler. |
| 14. Read-your-writes | PARTIAL | `.../passes/DecomposedExtractionPipeline.java:43`, `:47`; `.../knowledgegraph/unified/ExtractionToUnifiedGraph.java:58`, `:198` | Each bounded pipeline pass sees results from earlier passes, and unified-graph projection merges prior attributes/aliases. No explicit `G_committed ∪ deltaG_current_partition` staged graph, provisional visibility boundary, or atomic partition-batch commit was found. |
| 15. Shared chunks/overlap | PARTIAL | `.../passes/PassContext.java:36`, `:47`; `.../knowledgegraph/unified/ExtractionToUnifiedGraph.java:58`; `.../GraphExtractionOrchestrator.java:1849`, `:1856`, `:1862` | Extraction is chunk-attributed and graph projection merges idempotently per chunk, making reuse possible. No extraction-cache key over source/model/schema snapshot or deduplicated contribution accounting across overlapping partitions was found. |
| 16. Bridge entities | PARTIAL | `.../knowledgegraph/resolution/EntityResolutionService.java:31`; `.../subgraph/CommunityViewMaterializer.java:34`, `:68`; `.../community/LouvainDetector.java:279` | Central identity, overlapping halo views, and community materialization provide the graph primitives. Category/time-bounded bridge-entity policies and global reconciliation focused on shared identities/evidence are absent. |
| 17. Claim-centered pooled attribution | IMPLEMENTED | `.../passes/ExtractionProposals.java:162`, `:166`, `:225`, `:280`; `.../passes/SpeechAct.java:20`; `.../passes/ExtractionProjection.java:184`, `:194`, `:200`, `:205`; `.../claims/DossierItem.java:16`, `:24` | Proposition identity preserves polarity/modality/time/condition; `SpeechAct` is an attribution firewall; claim proposals choose add-evidence/create/contradiction/abstain; dossiers pool supporting and refuting evidence with provenance. Scope/perspective are not a single canonical claim-key record, but the operational behavior is present. |
| 18. Cross-chunk inference | IMPLEMENTED | `.../claims/DossierBuilder.java:28`, `:168`, `:201`, `:231`; `.../tms/JustificationIndex.java:26`, `:157` | Global reasoning consumes graph facts, paths, rules, KGE signals, and provenance-bearing justifications rather than concatenated raw chunks. Dependency indexes retain premise support for inferred atoms. |
| 19. Incremental updates | PARTIAL | `kompile-app/kompile-app-parent/kompile-app-facts/src/main/java/ai/kompile/app/sync/adapter/SyncAdapter.java:38`, `:46`, `:51`, `:64`, `:69`; `.../tms/JustificationIndex.java:157`, `:167`, `:181` | Sync adapters detect externally changed/deleted objects, and the TMS identifies dependent/unsupported atoms after retraction. There is no source-chunk-to-partition/profile/promotion/embedding dependency registry or selective recrawl router. |
| 20. Completion and coverage | ABSENT | not found (searched: `discoveryPolicy|coverage|unresolved frontier|provisionally complete|inaccessible|deferred`) | No policy version, processed frontier, inaccessible/deferred ledger, or completion predicate is persisted. |
| 21. Combined strategy | PARTIAL | `.../GraphExtractionOrchestrator.java:1659`, `:1729`, `:1849`; `.../passes/DecomposedExtractionPipeline.java:43`; `.../claims/DossierBuilder.java:101` | Chunk-attributed extraction, bounded local passes, graph aggregation, and claim reconciliation exist. Entity-group/category/time partition construction and frontier refresh are missing. |
| 22. Core principles | PARTIAL | `.../knowledgegraph/unified/ExtractionToUnifiedGraph.java:55`, `:58`; `.../passes/PassContext.java:36`; `.../CrawlBatchPlanner.java:60`; `.../claims/DossierBuilder.java:28`; `.../tms/JustificationIndex.java:26` | Principles 1, 2, 4, 8/9, 10/12/13, and 17 have direct mechanisms. Persistent manifests, discovery policy, bridge policy, overlap accounting, and selective partition reprocessing do not. |
| 23. Integrated concept | PARTIAL | `.../GraphExtractionOrchestrator.java:1659`, `:1849`; `.../passes/DecomposedExtractionPipeline.java:45`; `.../knowledgegraph/unified/ExtractionToUnifiedGraph.java:58`; `.../claims/DossierBuilder.java:242` | The working path is independent chunks → bounded extraction → unified graph → pooled claim assessment/reasoning. The named entity-group partition and lifecycle layer between discovery and mini-batches is not implemented. |

## Already done

### Chunk-addressed unified graph and provenance

`GraphExtractionOrchestrator` constructs a stable chunk-ID map (`:1729-1740`), requests per-item `chunkId` attribution, partitions returned entities/relations back to their source chunk (`:1849-1873`), and stamps crawl/source/document/model provenance (`:1504-1506`, `:1932-1934`). `ExtractionToUnifiedGraph` merges repeated extraction results while unioning attributes and aliases (`:55-58`, `:198-215`). This is the shared evidence-pool model in section 1.

### Structure-aware and semantic chunking

`HtmlChunker` preserves heading context (`:181-194`) and converts HTML tables by rows/cells (`:405-429`) before size normalization; `SemanticChunker` groups sentences around semantic breakpoints (`:37`, `:105`). This covers document structure and topical segmentation well for generic HTML/text. It does not establish one universal persisted hierarchy for every source-native object.

### Bounded atomic-proposition pipeline

`DecomposedExtractionPipeline` explicitly runs one chunk-level proposition call and then bounded per-proposition identity, epistemic, relation, and claim calls (`:43-50`), with configurable maxima (`:78-99`). `ExtractionPassPrompts` requires preservation of negation, modality, time, conditions, and attribution (`:59-91`). Evidence quotes are checked against exact source text, and fabricated spans cause abstention/drop (`DecomposedExtractionPipeline.java:233-244`, `:628-642`).

### Attribution firewall and conservative promotion

`SpeechAct` separates assertions, operational records, opinions, requests, commitments, warnings, predictions, and questions; `ExtractionProjection` withholds negated propositions from direct assertion (`:184-197`), gates promotion on epistemic class/modality (`:194-200`), and marks contradictions/schema gaps non-assertable (`:205-230`). This already solves the most dangerous claim-merging errors in section 17.

### Entity resolution and graph-scoped candidate context

`EntityResolutionService` performs cross-chunk fuzzy/alias resolution and relation remapping (`:31`). `SubgraphMaterializer` builds bounded graph views, while `CommunityViewMaterializer` turns detected communities into induced or halo subgraphs suitable for per-community priors/reasoning (`:27-35`, `:64-71`). `GraphEmbeddingResolver` resolves embeddings through relations, endpoints, and bounded neighborhoods (`:33`).

### Claim dossiers, contradictions, and evidence fusion

`DossierBuilder` aggregates direct edges, verifier proofs/counter-evidence, path evidence, calibrated KGE plausibility, and mined rules (`:28-42`, `:168-242`), then fuses supporting and refuting items (`:257-274`). `DossierItem` retains evidence kind, probability, and provenance (`:16-24`). This is a real pooled-evidence subsystem, not a proposed abstraction.

### Truth maintenance and dependency-backed inference

`JustificationIndex` links inferred atoms to their supporting rules/facts (`:26-34`) and can find dependents and atoms that lose all support on retraction (`:157-183`). `ContradictionDetector` provides explicit contradiction discovery (`:42`). This is the correct substrate for cross-chunk inference and incremental belief revision.

### Communities, embeddings, and bounded execution

`LouvainDetector` produces deterministic community assignments and aggregates communities into super-nodes (`:132`, `:145-182`, `:279-295`). `CrawlBatchPlanner` creates item- and cost-capped batches and optionally balances estimated cost (`:60-74`, `:95-144`). These are substantive implementations of grouping and mini-batch mechanics, although they are not connected by a persistent partition control plane.

### Snapshots and external source change detection

`GraphSnapshotService` persists and restores fact-sheet graph snapshots (`:42`, `:69`, `:154-178`). `PassContext` pins schema version, partition ID, model ID, and arbitrary construction pins into a stable fingerprint (`:36-47`, `:65-97`). `SyncAdapter` supports changed-since scans, ID enumeration, fetch-by-ID deletion detection, update, and delete (`:38-69`).

## Partial: exact gaps and smallest closures

1. **Persisted hierarchy.** Generic structural and semantic chunking plus proposition extraction exist, but there is no common record connecting source object, structural unit, segment, proposition, and versions. Smallest closure: persist a `CorpusUnitRef(sourceId, structuralUnitId, segmentId, propositionId, versions)` beside existing chunk/proposition metadata; adapters need only supply structural-unit kind and parent ID.

2. **Entity partition control plane.** `partitionId`, subgraphs, communities, candidate providers, and batches exist, but no durable partition aggregate owns membership or lifecycle. Smallest closure: add a partition record keyed by entity/group/category/time/policy/snapshot and a membership table of chunk ID + inclusion channel + state; reuse `SubgraphView`, `PassContext`, and `CrawlBatchPlanner`.

3. **Unified discovery policy.** Direct/fuzzy entity resolution, graph neighborhoods, embeddings, processes, and contradiction machinery are separate services. Smallest closure: one deterministic coordinator that invokes these providers and records an inclusion reason/confidence without changing the providers.

4. **Entity evidence manifest.** Claim dossiers cannot answer undiscovered vs deferred vs inaccessible vs invalidated. Smallest closure: make partition membership carry the section-6 state enum and discovery run/version; derive counts/completion from it.

5. **Priority ordering and read-your-writes.** Per-proposition pass order is strong, but cross-chunk batches are cost-balanced rather than evidence-priority ordered, and no partition staging view exists. Smallest closure: add priority/category/chronology keys before cost packing and expose a partition-local overlay graph that is promoted atomically after a batch.

6. **Overlap accounting.** Chunk attribution and merge semantics prevent many duplicates, but no snapshot-keyed extraction cache or evidence-contribution identity spans multiple partitions. Smallest closure: key local extraction by `(chunkVersion, schemaVersion, modelId)` and evidence contribution by `(canonicalClaimId, chunkId, evidenceSpan)`.

7. **Selective invalidation.** Sync and TMS handle source changes and inference dependencies separately. Smallest closure: persist reverse edges from chunk version to propositions, partition memberships, promoted facts, and embedding jobs; feed source changes into TMS retraction and partition rescheduling.

8. **Access control.** No meaningful ACL/permission propagation appeared in the scoped graph/crawl code (searched: `AccessControl|permission|tenant|ACL|visibility`; matches were license headers or unrelated tests). Smallest closure: add an immutable access-domain set to source/chunk metadata and require intersection/compatibility in partition membership queries.

9. **Version-complete construction state.** Graph snapshots and schema/model/partition pins exist; topic/entity-resolution/discovery-policy versions do not. Smallest closure: standardize those values in `PassContext.pins` and persist the resulting fingerprint on partition runs.

## Absent, ranked by dependency

1. **Persistent entity evidence manifest and partition lifecycle (sections 3, 6, 20).** This is the central missing control-plane abstraction; completion, incremental routing, overlap accounting, and reproducibility depend on it.
2. **Cross-channel partition discovery coordinator (sections 5, 11, 12).** The channels mostly exist, but nothing constructs one auditable candidate frontier from them.
3. **Partition-local staged graph/transaction (section 14).** Without it, later mini-batches cannot reliably consume provisional aliases/claims while retaining an explicit commit boundary.
4. **ACL propagation into chunk/partition membership (sections 1, 10).** This is independently important and blocks safe cross-source partitioning.
5. **Policy-relative coverage/completion model (section 20).** No discovered/deferred/inaccessible/invalidated frontier is recorded.
6. **Default hybrid grouping and bridge-entity policy (sections 9, 16).** Community/halo primitives exist, but category/time splitting and central reconciliation are not orchestrated.
7. **Extraction reuse and overlapping-partition evidence deduplication (section 15).** Merge behavior helps, but the required cache/contribution keys are absent.

## Naming reconciliation

| Specification term | Existing codebase name | Reconciliation |
|---|---|---|
| unified corpus | unified graph / `ExtractionToUnifiedGraph` | Use “unified graph-backed corpus”; do not introduce a concatenated corpus type. |
| evidence chunk | Spring AI `Document`, chunk ID, `PassContext.chunkId` | “Chunk” is already the execution/provenance unit. |
| source lineage | `GraphProvenanceKeys.crawl(...)`, document ID/source path | Existing provenance metadata, though not a formal hierarchy registry. |
| structural unit | HTML `Block`/heading/table-row handling in `HtmlChunker` | No cross-source common type exists. |
| semantic segment | output chunk of `SemanticChunker` | Existing semantic segmentation name is adequate. |
| atomic proposition | `ExtractionProposals.PropositionProposal` | Exact functional match. |
| entity partition | `PassContext.partitionId` + `SubgraphView` / community view | These are execution ID and materialized view pieces, not a durable aggregate. Reserve “entity partition” for the missing lifecycle object. |
| mini-batch | `CrawlBatchPlanner.CostBatch` | Exact execution-slice analogue. |
| evidence manifest | not found | Do not rename `ClaimDossier`; it has different scope and semantics. |
| entity prior | graph neighborhood/KGE/path/rule signals; dossier fused score | Distributed mechanisms, no single `EntityProfile` prior object. |
| entity community | `CommunityAssignment`, `LouvainDetector`, `CommunityViewMaterializer` | Existing names should be reused. |
| evidence scope | `SubgraphSpec` / `SubgraphView` | Closest existing bounded reasoning scope. |
| claim pool | `ClaimDossier` + existing fact/atom candidates | Dossier pools assessment evidence for a claim. |
| claim identity | relation/atom key plus `ClaimProposal.relationKey` | Partly canonical; qualifiers remain proposition/projection properties. |
| attribution | `SpeechAct`, `EpistemicProposal.holder` | Existing attribution firewall. |
| evidence role | `ExtractionProposals.EvidenceRole` | Direct support, attribution, opposition, qualification already named. |
| contradiction candidate | `ProposalOperation.FLAG_CONTRADICTION` | Exact match at extraction time. |
| contradiction reconciliation | TMS `ContradictionDetector` / `BeliefReviser` / `JustificationIndex` | Prefer reasoner/TMS terms over model-side “resolution.” |
| graph snapshot | `GraphSnapshotService` snapshot ID | Exact persistence mechanism. |
| construction-state pins | `PassContext.pins` and `fingerprint()` | Extend rather than create another version record. |
| incremental dependency graph | `JustificationIndex` | Covers inferred atoms, not source-to-partition dependencies. |
| source change feed | `SyncAdapter.fetchChangedSince` / `fetchById` | Existing adapter contract. |

## Contradictions with the specification

1. **Negated propositions are preserved but withheld from direct graph assertion.** The specification asks propositions to preserve polarity and include contradictory evidence. `ExtractionProjection` deliberately sets negated propositions non-assertable (`:184-197`) rather than materializing the negated text as an ordinary positive relationship. The code is right: polarity remains metadata/evidence, while contradiction handling belongs in claim matching and the TMS.

2. **Conflict resolution is reasoner-side, not delegated to the extraction model.** Pass 5 may flag a contradiction, but dossiers and TMS verification decide support/refutation from pooled evidence and dependencies. The code is right: a bounded SLM should propose and abstain; it should not globally revise truth.

3. **Current crawl batches are cost-balanced, not semantically ordered partition batches.** `CrawlBatchPlanner` can reorder by descending cost and place items into the lightest batch (`:117-144`). That is right for resource scheduling but insufficient for the specification’s prior-building order. The two concerns should be composed: semantic priority determines strata, cost balancing packs within each stratum.

4. **Unknown chunk attribution falls back to the first chunk instead of rejecting the extracted item.** `GraphExtractionOrchestrator` explicitly preserves source-document provenance with a first-chunk fallback (`:1665-1667`, `:2107-2115`). This is operationally resilient but weaker than exact proposition lineage; for partition-quality evidence, an “unresolved attribution” state would be safer than silently assigning the first chunk.

5. **“Partition” already has two unrelated meanings.** `CrawlBatchPlanner` uses partitioning to mean cost-batch division, while `PassContext.partitionId` means an entity scheduling scope. The specification is right to distinguish persistent partition from mini-batch; new code should avoid calling a `CostBatch` an entity partition.

6. **Graph snapshots exist independently of schema/topic/entity-resolution versions.** The code’s snapshot service correctly snapshots graph state, while `PassContext` separately fingerprints schema/model/pins. The specification is right that a reproducible partition run needs both; replacing either mechanism would be wrong—composition is the missing step.
