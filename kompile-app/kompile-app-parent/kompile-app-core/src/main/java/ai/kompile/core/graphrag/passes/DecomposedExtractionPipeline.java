/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.core.graphrag.passes;

import ai.kompile.core.crawl.graph.PropositionAtomizationConfig.Mode;
import ai.kompile.core.graphrag.format.GraphExtractionSchema.ExtractionResult;
import ai.kompile.core.graphrag.passes.ExtractionCandidates.ClaimCandidate;
import ai.kompile.core.graphrag.passes.ExtractionCandidates.EntityCandidate;
import ai.kompile.core.graphrag.passes.ExtractionCandidates.IdentitySignals;
import ai.kompile.core.graphrag.passes.ExtractionCandidates.RelationCandidate;
import ai.kompile.core.graphrag.passes.ExtractionPassParsers.ClaimComparison;
import ai.kompile.core.graphrag.passes.ExtractionPassParsers.ClaimComparisonDecision;
import ai.kompile.core.graphrag.passes.ExtractionPassParsers.FocusedMentionChoice;
import ai.kompile.core.graphrag.passes.ExtractionPassParsers.FocusedMentionDecision;
import ai.kompile.core.graphrag.passes.ExtractionPassParsers.RelationExistence;
import ai.kompile.core.graphrag.passes.ExtractionPassParsers.RelationExistenceDecision;
import ai.kompile.core.graphrag.passes.ExtractionPassParsers.RelationTypeDisposition;
import ai.kompile.core.graphrag.passes.ExtractionPassParsers.RelationTypeDecision;
import ai.kompile.core.graphrag.passes.ExtractionProposals.Alternative;
import ai.kompile.core.graphrag.passes.ExtractionProposals.ClaimProposal;
import ai.kompile.core.graphrag.passes.ExtractionProposals.EpistemicProposal;
import ai.kompile.core.graphrag.passes.ExtractionProposals.EvidenceRole;
import ai.kompile.core.graphrag.passes.ExtractionProposals.EvidenceSpan;
import ai.kompile.core.graphrag.passes.ExtractionProposals.MentionProposal;
import ai.kompile.core.graphrag.passes.ExtractionProposals.PassBundle;
import ai.kompile.core.graphrag.passes.ExtractionProposals.PropositionProposal;
import ai.kompile.core.graphrag.passes.ExtractionProposals.RelationProposal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Runs the decomposed extraction passes for one chunk.
 *
 * <p>The model first semantically atomizes a production crawl chunk into every source-grounded
 * proposition it can surface. The engine then calls the focused identity, epistemic, relation, and
 * claim passes for each proposal. Between calls the engine retrieves graph candidates, verifies
 * verbatim evidence, enforces schema vocabularies, and applies graph facets before projection.
 * Proposition text may normalize a source assertion; its evidence must remain a source span.
 * Candidate ballots and later embedding, logic, schema, and graph-purity checks decide admission,
 * so initial surfacing is recall-oriented without allowing ungrounded graph writes.</p>
 *
 * <p>Pure orchestration: no Spring, no HTTP, no graph access. Dispatch arrives as an
 * {@link LlmCaller}, retrieval as the three candidate provider SPIs, so the whole pipeline is
 * exercisable with scripted responses.</p>
 */
public final class DecomposedExtractionPipeline {

    private static final Logger log = LoggerFactory.getLogger(DecomposedExtractionPipeline.class);
    /**
     * Dispatch seam. Implementations route a prompt to whatever lane the caller uses — the crawl
     * dispatcher, a staging model, or a scripted stub in tests.
     */
    @FunctionalInterface
    public interface LlmCaller {

        /**
         * @param passId one of {@link ExtractionPassPrompts#PASS_IDS}, so callers may route
         *               different passes to different models
         * @param prompt the fully rendered prompt
         * @return the raw model response, or {@code null} when the lane produced nothing
         */
        String call(String passId, String prompt);
    }

    /**
     * @param maxPropositions        optional explicit upper bound; non-positive means unbounded
     * @param entityCandidateLimit   candidates offered per proposition in pass 2
     * @param relationCandidateLimit relation types offered in pass 4
     * @param claimCandidateLimit    existing claims offered in pass 5
     * @param requireEvidenceSpans   drop proposals whose quote cannot be found in the source
     * @param claimMatchingEnabled   run pass 5 when claim candidates exist
     * @param splitMentionsByEndpoint dispatch one identity decision per subject/object endpoint
     * @param inferFocusedMentionOperation derive reuse/create/abstain from mutually exclusive
     *                                     focused-response fields instead of asking the model to
     *                                     repeat that redundant operation
     * @param splitRelationDecision first decide whether the proposition asserts any relation,
     *                              then select a type from an engine-owned ordinal ballot
     * @param splitClaimsByCandidate compare the new relation with one existing claim per model call
     * @param splitPropositionsBySourceEvent run every pass against a small ordered source event
     *                                       rather than exposing the full crawl chunk downstream
     * @param propositionAtomizationMode source of event boundaries inside a crawl chunk
     * @param propositionMaxEventChars maximum focus-event characters before deterministic subdivision
     * @param propositionReferenceContextChars preceding source characters retained for local reference
     */
    public record Options(
            int maxPropositions,
            int entityCandidateLimit,
            int relationCandidateLimit,
            int claimCandidateLimit,
            boolean requireEvidenceSpans,
            boolean claimMatchingEnabled,
            boolean splitMentionsByEndpoint,
            boolean inferFocusedMentionOperation,
            boolean splitRelationDecision,
            boolean splitClaimsByCandidate,
            boolean splitPropositionsBySourceEvent,
            Mode propositionAtomizationMode,
            int propositionMaxEventChars,
            int propositionReferenceContextChars) {

        public Options {
            propositionAtomizationMode = propositionAtomizationMode == null
                    ? (splitPropositionsBySourceEvent ? Mode.AUTO : Mode.WHOLE_CHUNK)
                    : propositionAtomizationMode;
            propositionMaxEventChars = Math.max(128, propositionMaxEventChars);
            propositionReferenceContextChars = Math.max(0, propositionReferenceContextChars);
        }

        public static Options defaults() {
            return new Options(0, 8, 12, 5, true, true, false, true, false, false,
                    false, Mode.WHOLE_CHUNK, 1_200, 1_200);
        }

        /** Source-compatible constructor for callers predating the relation/claim splits. */
        public Options(int maxPropositions, int entityCandidateLimit, int relationCandidateLimit,
                       int claimCandidateLimit, boolean requireEvidenceSpans,
                       boolean claimMatchingEnabled, boolean splitMentionsByEndpoint,
                       boolean inferFocusedMentionOperation) {
            this(maxPropositions, entityCandidateLimit, relationCandidateLimit,
                    claimCandidateLimit, requireEvidenceSpans, claimMatchingEnabled,
                    splitMentionsByEndpoint, inferFocusedMentionOperation, false, false, false,
                    Mode.WHOLE_CHUNK, 1_200, 1_200);
        }

        /** Source-compatible constructor for callers predating source-event proposition splitting. */
        public Options(int maxPropositions, int entityCandidateLimit, int relationCandidateLimit,
                       int claimCandidateLimit, boolean requireEvidenceSpans,
                       boolean claimMatchingEnabled, boolean splitMentionsByEndpoint,
                       boolean inferFocusedMentionOperation, boolean splitRelationDecision,
                       boolean splitClaimsByCandidate) {
            this(maxPropositions, entityCandidateLimit, relationCandidateLimit,
                    claimCandidateLimit, requireEvidenceSpans, claimMatchingEnabled,
                    splitMentionsByEndpoint, inferFocusedMentionOperation, splitRelationDecision,
                    splitClaimsByCandidate, false, Mode.WHOLE_CHUNK, 1_200, 1_200);
        }

        /** Source-compatible constructor for callers predating configurable proposition atomization. */
        public Options(int maxPropositions, int entityCandidateLimit, int relationCandidateLimit,
                       int claimCandidateLimit, boolean requireEvidenceSpans,
                       boolean claimMatchingEnabled, boolean splitMentionsByEndpoint,
                       boolean inferFocusedMentionOperation, boolean splitRelationDecision,
                       boolean splitClaimsByCandidate, boolean splitPropositionsBySourceEvent) {
            this(maxPropositions, entityCandidateLimit, relationCandidateLimit,
                    claimCandidateLimit, requireEvidenceSpans, claimMatchingEnabled,
                    splitMentionsByEndpoint, inferFocusedMentionOperation, splitRelationDecision,
                    splitClaimsByCandidate, splitPropositionsBySourceEvent,
                    splitPropositionsBySourceEvent ? Mode.AUTO : Mode.WHOLE_CHUNK, 1_200, 1_200);
        }

        public Options withMaxPropositions(int value) {
            return new Options(value, entityCandidateLimit, relationCandidateLimit,
                    claimCandidateLimit, requireEvidenceSpans, claimMatchingEnabled,
                    splitMentionsByEndpoint, inferFocusedMentionOperation,
                    splitRelationDecision, splitClaimsByCandidate,
                    splitPropositionsBySourceEvent, propositionAtomizationMode,
                    propositionMaxEventChars, propositionReferenceContextChars);
        }

        public Options withSplitMentionsByEndpoint(boolean value) {
            return new Options(maxPropositions, entityCandidateLimit, relationCandidateLimit,
                    claimCandidateLimit, requireEvidenceSpans, claimMatchingEnabled, value,
                    inferFocusedMentionOperation, splitRelationDecision, splitClaimsByCandidate,
                    splitPropositionsBySourceEvent, propositionAtomizationMode,
                    propositionMaxEventChars, propositionReferenceContextChars);
        }

        public Options withInferFocusedMentionOperation(boolean value) {
            return new Options(maxPropositions, entityCandidateLimit, relationCandidateLimit,
                    claimCandidateLimit, requireEvidenceSpans, claimMatchingEnabled,
                    splitMentionsByEndpoint, value, splitRelationDecision, splitClaimsByCandidate,
                    splitPropositionsBySourceEvent, propositionAtomizationMode,
                    propositionMaxEventChars, propositionReferenceContextChars);
        }

        public Options withSplitRelationDecision(boolean value) {
            return new Options(maxPropositions, entityCandidateLimit, relationCandidateLimit,
                    claimCandidateLimit, requireEvidenceSpans, claimMatchingEnabled,
                    splitMentionsByEndpoint, inferFocusedMentionOperation, value,
                    splitClaimsByCandidate, splitPropositionsBySourceEvent,
                    propositionAtomizationMode, propositionMaxEventChars,
                    propositionReferenceContextChars);
        }

        public Options withSplitClaimsByCandidate(boolean value) {
            return new Options(maxPropositions, entityCandidateLimit, relationCandidateLimit,
                    claimCandidateLimit, requireEvidenceSpans, claimMatchingEnabled,
                    splitMentionsByEndpoint, inferFocusedMentionOperation, splitRelationDecision,
                    value, splitPropositionsBySourceEvent, propositionAtomizationMode,
                    propositionMaxEventChars, propositionReferenceContextChars);
        }

        public Options withSplitPropositionsBySourceEvent(boolean value) {
            Mode mode = value && propositionAtomizationMode == Mode.WHOLE_CHUNK
                    ? Mode.AUTO : propositionAtomizationMode;
            return new Options(maxPropositions, entityCandidateLimit, relationCandidateLimit,
                    claimCandidateLimit, requireEvidenceSpans, claimMatchingEnabled,
                    splitMentionsByEndpoint, inferFocusedMentionOperation, splitRelationDecision,
                    splitClaimsByCandidate, value, mode,
                    propositionMaxEventChars, propositionReferenceContextChars);
        }

        public Options withPropositionAtomization(Mode mode, int maxEventChars,
                                                  int referenceContextChars) {
            return new Options(maxPropositions, entityCandidateLimit, relationCandidateLimit,
                    claimCandidateLimit, requireEvidenceSpans, claimMatchingEnabled,
                    splitMentionsByEndpoint, inferFocusedMentionOperation, splitRelationDecision,
                    splitClaimsByCandidate, mode != Mode.WHOLE_CHUNK, mode, maxEventChars,
                    referenceContextChars);
        }
    }

    /** Per-pass accounting, surfaced to crawl telemetry and asserted on in tests. */
    public record PassStats(
            String passId,
            int calls,
            int proposed,
            int accepted,
            int abstained,
            int spanRejected,
            int outOfVocabulary,
            int failures) {
    }

    /**
     * @param result  projected result for the existing graph construction path
     * @param bundle  every proposal that survived validation, for audit and replay
     * @param stats   per-pass counters
     * @param notes   everything withheld, and why
     */
    public record Outcome(
            ExtractionResult result,
            PassBundle bundle,
            List<PassStats> stats,
            List<String> notes) {

        public Outcome {
            stats = stats == null ? List.of() : List.copyOf(stats);
            notes = notes == null ? List.of() : List.copyOf(notes);
        }

        /** Total entities and relations that reached the projected result. */
        public int projectedItems() {
            return result.entities().size() + result.relations().size();
        }
    }

    private final EntityCandidateProvider entityCandidates;
    private final RelationCandidateProvider relationCandidates;
    private final ClaimCandidateProvider claimCandidates;
    private final Options options;

    public DecomposedExtractionPipeline(EntityCandidateProvider entityCandidates,
                                        RelationCandidateProvider relationCandidates,
                                        ClaimCandidateProvider claimCandidates,
                                        Options options) {
        this.entityCandidates =
                entityCandidates == null ? EntityCandidateProvider.none() : entityCandidates;
        this.relationCandidates =
                relationCandidates == null ? RelationCandidateProvider.none() : relationCandidates;
        this.claimCandidates =
                claimCandidates == null ? ClaimCandidateProvider.none() : claimCandidates;
        this.options = options == null ? Options.defaults() : options;
    }

    /** Runs every pass for {@code context} and projects the result. */
    public Outcome run(PassContext context, LlmCaller caller) {
        List<String> notes = new ArrayList<>();
        List<PassStats> stats = new ArrayList<>();

        List<ScopedProposition> scopedPropositions =
                runPropositions(context, caller, stats, notes);
        List<PropositionProposal> propositions = scopedPropositions.stream()
                .map(scoped -> rebase(scoped.proposition(), scoped.sourceOffset()))
                .toList();
        if (propositions.isEmpty()) {
            PassBundle empty = PassBundle.empty();
            ExtractionProjection.Projection projection =
                    ExtractionProjection.project(empty, context);
            notes.addAll(projection.notes());
            return new Outcome(projection.result(), empty, stats, notes);
        }

        List<MentionProposal> mentions = new ArrayList<>();
        List<EpistemicProposal> epistemics = new ArrayList<>();
        List<RelationProposal> relations = new ArrayList<>();
        List<ClaimProposal> claims = new ArrayList<>();

        Counter mentionCounter = new Counter(ExtractionPassPrompts.PASS_MENTIONS);
        Counter epistemicCounter = new Counter(ExtractionPassPrompts.PASS_EPISTEMIC);
        Counter relationCounter = new Counter(ExtractionPassPrompts.PASS_RELATIONS);
        Counter claimCounter = new Counter(ExtractionPassPrompts.PASS_CLAIMS);

        for (ScopedProposition scoped : scopedPropositions) {
            PassContext propositionContext = scoped.context();
            PropositionProposal proposition = scoped.proposition();
            int sourceOffset = scoped.sourceOffset();
            List<MentionProposal> scopedMentions = new ArrayList<>();
            Map<String, ResolvedEndpoint> resolvedByRole = runMentions(
                    propositionContext, caller, proposition, scopedMentions, mentionCounter, notes);
            scopedMentions.stream().map(mention -> rebase(mention, sourceOffset))
                    .forEach(mentions::add);
            EpistemicProposal epistemic =
                    runEpistemic(propositionContext, caller, proposition, epistemicCounter, notes);
            epistemics.add(rebase(epistemic, sourceOffset));

            ResolvedEndpoint subject = resolvedByRole.get(ROLE_SUBJECT);
            ResolvedEndpoint object = resolvedByRole.get(ROLE_OBJECT);
            if (subject == null || object == null) {
                notes.add("relation pass skipped for " + proposition.id()
                        + ": endpoints not both resolved");
                continue;
            }
            List<RelationProposal> propositionRelations = runRelations(
                    propositionContext, caller, proposition, subject, object, relationCounter, notes);
            propositionRelations.stream().map(value -> rebase(value, sourceOffset))
                    .forEach(relations::add);
            propositionRelations.stream()
                    .filter(r -> r.operation() == ProposalOperation.CREATE_CLAIM)
                    .map(r -> runClaims(propositionContext, caller, r, claimCounter, notes))
                    .flatMap(Optional::stream)
                    .map(value -> rebase(value, sourceOffset))
                    .forEach(claims::add);
        }

        stats.add(mentionCounter.toStats());
        stats.add(epistemicCounter.toStats());
        stats.add(relationCounter.toStats());
        stats.add(claimCounter.toStats());

        PassBundle bundle = new PassBundle(propositions, mentions, epistemics, relations, claims);
        ExtractionProjection.Projection projection = ExtractionProjection.project(bundle, context);
        notes.addAll(projection.notes());
        return new Outcome(projection.result(), bundle, stats, notes);
    }

    // ---------------------------------------------------------------------------------------
    // pass 1 — propositions
    // ---------------------------------------------------------------------------------------

    private record ScopedProposition(PassContext context, int sourceOffset,
                                     PropositionProposal proposition) {
    }

    private record ScopedSourceEvent(SourceEventSegmenter.SourceEvent event,
                                     PassContext context, int sourceOffset) {
    }

    private List<ScopedProposition> runPropositions(PassContext context, LlmCaller caller,
                                                    List<PassStats> stats, List<String> notes) {
        Counter counter = new Counter(ExtractionPassPrompts.PASS_PROPOSITIONS);
        int configuredLimit = options.maxPropositions();
        boolean bounded = configuredLimit > 0;
        int limit = bounded ? configuredLimit : Integer.MAX_VALUE;
        List<ScopedProposition> accepted = new ArrayList<>();
        for (SourceEventSegmenter.SourceEvent event : sourceEvents(context)) {
            int remaining = bounded ? limit - accepted.size() : 0;
            if (bounded && remaining <= 0) {
                break;
            }
            ScopedSourceEvent scopedEvent = eventContext(context, event);
            PassContext eventContext = scopedEvent.context();
            String prompt = options.splitPropositionsBySourceEvent()
                    ? ExtractionPassPrompts.proposition(eventContext, event.text())
                    : ExtractionPassPrompts.propositions(eventContext, remaining);
            String response = dispatch(caller, ExtractionPassPrompts.PASS_PROPOSITIONS, prompt,
                    counter, notes);
            if (response == null) {
                continue;
            }
            List<PropositionProposal> parsed;
            if (options.splitPropositionsBySourceEvent()) {
                Optional<PropositionProposal> focused =
                        ExtractionPassParsers.proposition(response, eventContext, event.text());
                String focusedRejection = null;
                if (focused.isEmpty()
                        && !ExtractionPassParsers.explicitNullProposition(response)) {
                    focusedRejection = "focused proposition response was malformed";
                } else if (focused.isPresent()) {
                    focusedRejection = propositionValidationError(
                            focused.get(), eventContext, event.text());
                }
                if (focusedRejection != null) {
                    notes.add("focused proposition contract rejected (" + focusedRejection
                            + "); requesting one bounded correction");
                    String correction = dispatch(caller, ExtractionPassPrompts.PASS_PROPOSITIONS,
                            ExtractionPassPrompts.propositionCorrection(
                                    eventContext, event.text(), response, focusedRejection), counter, notes);
                    if (correction != null) {
                        focused = ExtractionPassParsers.proposition(
                                correction, eventContext, event.text());
                        String correctedRejection = focused.isPresent()
                                ? propositionValidationError(focused.get(), eventContext, event.text())
                                : !ExtractionPassParsers.explicitNullProposition(correction)
                                ? "corrected proposition response remained malformed"
                                : null;
                        if (correctedRejection != null) {
                            notes.add("corrected focused proposition remained invalid ("
                                    + correctedRejection + "); graph write withheld");
                            focused = Optional.empty();
                        }
                    }
                }
                parsed = focused.map(List::of).orElseGet(List::of);
            } else {
                parsed = ExtractionPassParsers.propositions(response, eventContext, remaining);
            }
            counter.proposed += parsed.size();

            for (PropositionProposal proposition : parsed) {
                PropositionProposal candidate = proposition;
                if (options.splitPropositionsBySourceEvent()) {
                    int focusStart = event.start() - scopedEvent.sourceOffset();
                    EvidenceSpan engineEvidence = new EvidenceSpan(eventContext.chunkId(),
                            focusStart, focusStart + event.text().length(), event.text(),
                            EvidenceRole.DIRECT_SUPPORT);
                    candidate = new PropositionProposal(proposition.id(), event.text(),
                            proposition.subject(), proposition.predicate(), proposition.object(),
                            proposition.polarity(), proposition.modality(), proposition.timeExpression(),
                            proposition.condition(), proposition.attributedTo(), engineEvidence);
                }
                EvidenceSpanValidator.SpanCheck check =
                        EvidenceSpanValidator.check(candidate.evidence(), eventContext);
                if (!check.usable()) {
                    // Evidence is the hard source-provenance boundary. Semantic proposition text may
                    // be normalized, but a model proposal cannot enter downstream graph reasoning
                    // without a verbatim source span.
                    counter.spanRejected++;
                    notes.add("proposition dropped (" + check.status() + "): " + check.detail());
                    continue;
                }
                if (!surfaceOccursIn(candidate.subject(), eventContext.sourceText())
                        || !surfaceOccursIn(candidate.object(), eventContext.sourceText())) {
                    counter.spanRejected++;
                    notes.add("proposition dropped (ENDPOINT_NOT_IN_SOURCE): explicit subject/object "
                            + "values must be source surfaces; unresolved or omitted endpoints stay null");
                    continue;
                }
                String semanticText = candidate.text() == null || candidate.text().isBlank()
                        ? check.span().quote() : candidate.text();
                String id = "p" + (accepted.size() + 1);
                PropositionProposal normalized = new PropositionProposal(id, semanticText,
                        candidate.subject(), candidate.predicate(), candidate.object(),
                        candidate.polarity(), candidate.modality(), candidate.timeExpression(),
                        candidate.condition(), candidate.attributedTo(), check.span());
                accepted.add(new ScopedProposition(eventContext, scopedEvent.sourceOffset(), normalized));
                if (accepted.size() >= limit) {
                    break;
                }
            }
        }
        counter.accepted += accepted.size();
        stats.add(counter.toStats());
        return List.copyOf(accepted);
    }

    /** Null is a valid absent endpoint; a supplied endpoint must be a literal surface mention. */
    private static boolean surfaceOccursIn(String surface, String propositionText) {
        if (surface == null || surface.isBlank()) {
            return true;
        }
        if (propositionText == null || propositionText.isBlank()) {
            return false;
        }
        String normalizedText = propositionText.toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ").strip();
        String normalizedSurface = surface.toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ").strip();
        return normalizedText.contains(normalizedSurface);
    }

    /** Engine-owned validation shared with isolated production-primitive accuracy tests. */
    static String propositionValidationError(PropositionProposal proposition, PassContext context) {
        return propositionValidationError(proposition, context, null);
    }

    /**
     * Language-neutral focused validation. The engine checks only what it can prove without
     * interpreting category or source words: explicit endpoint values occur in the bounded source.
     * Concept categories, predicate meaning, argument direction, polarity, and modality remain model
     * proposals for downstream schema, logic, embedding, and graph-purity evaluation.
     */
    static String propositionValidationError(PropositionProposal proposition, PassContext context,
                                             String focusEvent) {
        if (proposition == null) {
            return "focused proposition was empty, null, or malformed";
        }
        if (!surfaceOccursIn(proposition.subject(), context.sourceText())
                || !surfaceOccursIn(proposition.object(), context.sourceText())) {
            return "subject/object were not exact surface mentions from the focus or bounded reference source";
        }
        return null;
    }

    private List<SourceEventSegmenter.SourceEvent> sourceEvents(PassContext context) {
        String source = context == null ? "" : context.sourceText();
        if (source.isBlank()) {
            return List.of();
        }
        if (!options.splitPropositionsBySourceEvent()
                || options.propositionAtomizationMode() == Mode.WHOLE_CHUNK) {
            return List.of(new SourceEventSegmenter.SourceEvent(1, 0, source.length(), source));
        }
        List<SourceEventSegmenter.SourceEvent> supplied =
                SourceEventSegmenter.segmentSourceSpans(source,
                        options.propositionMaxEventChars(), context.sourceSpans());
        return switch (options.propositionAtomizationMode()) {
            case SOURCE_SPANS -> supplied.isEmpty()
                    ? List.of(new SourceEventSegmenter.SourceEvent(1, 0, source.length(), source))
                    : supplied;
            case AUTO -> supplied.isEmpty()
                    ? SourceEventSegmenter.segment(source, options.propositionMaxEventChars())
                    : supplied;
            case HEURISTIC -> SourceEventSegmenter.segment(source,
                    options.propositionMaxEventChars());
            case WHOLE_CHUNK -> List.of(
                    new SourceEventSegmenter.SourceEvent(1, 0, source.length(), source));
        };
    }

    private ScopedSourceEvent eventContext(PassContext base,
                                           SourceEventSegmenter.SourceEvent event) {
        if (!options.splitPropositionsBySourceEvent()) {
            return new ScopedSourceEvent(event, base, 0);
        }
        String source = base.sourceText();
        int contextStart = Math.max(0,
                event.start() - options.propositionReferenceContextChars());
        while (contextStart < event.start() && Character.isWhitespace(source.charAt(contextStart))) {
            contextStart++;
        }
        String boundedReference = source.substring(contextStart, event.end());
        PassContext scoped = new PassContext(base.chunkId(), base.documentId(), boundedReference, base.graphId(),
                base.parentGraphId(), base.schemaVersion(), base.partitionId(), base.modelId(),
                base.pins(), base.graphContext(), base.conceptHints(), List.of(),
                base.schemaEntityTypes())
                .withPin("sourceEvent", event.id());
        return new ScopedSourceEvent(event, scoped, contextStart);
    }

    // ---------------------------------------------------------------------------------------
    // pass 2 — mentions and identity
    // ---------------------------------------------------------------------------------------

    private static final String ROLE_SUBJECT = "SUBJECT";
    private static final String ROLE_OBJECT = "OBJECT";

    /**
     * A mention that resolved to a graph id, together with the name and type the engine holds for
     * it — taken from the retrieved candidate when reusing, so a reused entity's real type (not a
     * blank provisional one) drives relation-type admissibility in pass 4.
     */
    private record ResolvedEndpoint(MentionProposal mention, String entityId, String name,
                                    String type) {
    }

    private Map<String, ResolvedEndpoint> runMentions(PassContext context, LlmCaller caller,
                                                      PropositionProposal proposition,
                                                      List<MentionProposal> sink, Counter counter,
                                                      List<String> notes) {
        List<EntityCandidate> combinedCandidates = List.of();
        Map<String, List<EntityCandidate>> candidatesByRole = new LinkedHashMap<>();
        List<MentionProposal> parsed = new ArrayList<>();
        if (options.splitMentionsByEndpoint()) {
            List<EntityCandidate> subjectCandidates =
                    retrieveEntityCandidates(context, proposition.subject(), null);
            List<EntityCandidate> objectCandidates =
                    retrieveEntityCandidates(context, proposition.object(), null);
            candidatesByRole.put(ROLE_SUBJECT, subjectCandidates);
            candidatesByRole.put(ROLE_OBJECT, objectCandidates);
            collectFocusedMention(context, caller, proposition, ROLE_SUBJECT,
                    proposition.subject(), null, subjectCandidates, parsed, counter, notes);
            collectFocusedMention(context, caller, proposition, ROLE_OBJECT,
                    proposition.object(), null, objectCandidates, parsed, counter, notes);
        } else {
            combinedCandidates = retrieveEntityCandidates(context, proposition);
            String prompt = ExtractionPassPrompts.mentions(
                    context, proposition, combinedCandidates);
            String response =
                    dispatch(caller, ExtractionPassPrompts.PASS_MENTIONS, prompt, counter, notes);
            if (response == null) {
                return Map.of();
            }
            List<MentionProposal> decoded =
                    ExtractionPassParsers.mentions(response, context, proposition.id());
            counter.proposed += decoded.size();
            parsed.addAll(decoded);
        }

        Map<String, ResolvedEndpoint> byRole = new LinkedHashMap<>();
        for (MentionProposal mention : parsed) {
            String role = roleOf(mention, proposition);
            List<EntityCandidate> offered = options.splitMentionsByEndpoint()
                    ? candidatesByRole.getOrDefault(role, List.of())
                    : combinedCandidates;
            Map<String, EntityCandidate> candidatesById = new LinkedHashMap<>();
            offered.forEach(candidate -> candidatesById.put(candidate.id(), candidate));
            MentionProposal checked = enforceCandidateVocabulary(mention, candidatesById.keySet(),
                    counter, notes);
            checked = enforceCandidatePurity(checked, candidatesById, null, counter, notes);
            checked = validateSpan(checked, context, counter, notes);
            if (checked == null) {
                continue;
            }
            EntityCandidate matched = checked.operation() == ProposalOperation.REUSE_ENTITY
                    ? candidatesById.get(checked.selectedEntityId()) : null;
            checked = enrichFromCandidate(checked, matched);
            if (checked.operation().isAbstention()) {
                counter.abstained++;
            } else {
                counter.accepted++;
            }
            sink.add(checked);
            String entityId = ExtractionProjection.entityId(checked);
            if (role != null && checked.resolved() && entityId != null) {
                String name = matched != null ? matched.name()
                        : firstNonBlank(checked.provisionalName(), checked.mentionText());
                String type = matched != null ? matched.type() : checked.provisionalType();
                byRole.putIfAbsent(role, new ResolvedEndpoint(checked, entityId, name, type));
            }
        }
        return byRole;
    }

    private void collectFocusedMention(PassContext context, LlmCaller caller,
                                       PropositionProposal proposition, String role, String surface,
                                       String expectedType,
                                       List<EntityCandidate> candidates, List<MentionProposal> sink,
                                       Counter counter, List<String> notes) {
        if (surface == null || surface.isBlank()) {
            return;
        }
        ExtractionPassPrompts.MentionFocus focus = ROLE_SUBJECT.equals(role)
                ? ExtractionPassPrompts.MentionFocus.SUBJECT
                : ExtractionPassPrompts.MentionFocus.OBJECT;
        String prompt = ExtractionPassPrompts.mention(context, proposition, focus, expectedType,
                candidates, options.inferFocusedMentionOperation());
        String response =
                dispatch(caller, ExtractionPassPrompts.PASS_MENTIONS, prompt, counter, notes);
        if (response == null) {
            return;
        }
        FocusedMentionParse decoded = parseFocusedMention(response, context, proposition, role,
                surface, expectedType, candidates, options.inferFocusedMentionOperation());
        if (!decoded.valid() && options.inferFocusedMentionOperation()) {
            notes.add("focused mention " + role + " rejected: " + decoded.rejectionReason()
                    + "; requesting one bounded correction");
            String correction = dispatch(caller, ExtractionPassPrompts.PASS_MENTIONS,
                    ExtractionPassPrompts.mentionCorrection(context, proposition, focus,
                            expectedType, candidates, response, decoded.rejectionReason()),
                    counter, notes);
            if (correction != null) {
                response = correction;
                decoded = parseFocusedMention(response, context, proposition, role, surface,
                        expectedType, candidates, true);
            }
        }
        counter.proposed++;
        if (!decoded.valid()) {
            counter.outOfVocabulary++;
            notes.add("focused mention " + role
                    + " withheld after invalid identity decision: " + decoded.rejectionReason());
            sink.add(new MentionProposal(proposition.id(), surface, role,
                    ProposalOperation.UNRESOLVED, null, null, null, 0.0, List.of(),
                    decoded.rejectionReason(), EvidenceSpan.ofQuote(context.chunkId(), surface)));
            return;
        }
        sink.add(decoded.proposal());
    }

    private FocusedMentionParse parseFocusedMention(
            String response,
            PassContext context,
            PropositionProposal proposition,
            String role,
            String surface,
            String expectedType,
            List<EntityCandidate> candidates,
            boolean productionContract) {
        EvidenceSpan evidence = EvidenceSpan.ofQuote(context.chunkId(), surface);
        if (!productionContract) {
            Optional<MentionProposal> legacy = ExtractionPassParsers.mention(response, context,
                    proposition.id(), false);
            if (legacy.isEmpty()) {
                return FocusedMentionParse.rejected("response did not contain one mention object");
            }
            MentionProposal choice = legacy.get();
            return FocusedMentionParse.accepted(new MentionProposal(proposition.id(), surface, role,
                    choice.operation(), choice.selectedEntityId(), choice.provisionalName(),
                    choice.provisionalType(), choice.confidence(), choice.alternatives(),
                    choice.reason(), evidence));
        }

        Optional<FocusedMentionDecision> structured =
                ExtractionPassParsers.focusedMentionDecision(response);
        if (structured.isPresent()) {
            FocusedMentionDecision decision = structured.get();
            List<String> provisionalTypeBallot = expectedType == null
                    ? ExtractionPassPrompts.provisionalTypeBallot(context, surface, candidates)
                    : List.of();
            if (!ExtractionPassParsers.focusedMentionInternallyConsistent(decision,
                    candidates != null && !candidates.isEmpty(), expectedType != null,
                    !provisionalTypeBallot.isEmpty())) {
                return FocusedMentionParse.rejected(
                        "decision fields do not match the selected mention branch");
            }
            EntityCandidate selected = null;
            if (decision.choice() == FocusedMentionChoice.REUSE) {
                int ordinal = decision.candidateOrdinal();
                if (ordinal < 1 || ordinal > candidates.size()) {
                    return FocusedMentionParse.rejected(
                            "candidateOrdinal is outside the offered ballot");
                }
                selected = candidates.get(ordinal - 1);
                String selectionError = candidateSelectionError(selected, candidates, expectedType);
                if (selectionError != null) {
                    return FocusedMentionParse.rejected(selectionError);
                }
            }
            String provisionalType = null;
            if (decision.choice() == FocusedMentionChoice.CREATE_PROVISIONAL) {
                provisionalType = expectedType;
                if (provisionalType == null && decision.provisionalTypeOrdinal() != null) {
                    int ordinal = decision.provisionalTypeOrdinal();
                    if (ordinal < 1 || ordinal > provisionalTypeBallot.size()) {
                        return FocusedMentionParse.rejected(
                                "provisionalTypeOrdinal is outside the offered type ballot");
                    }
                    provisionalType = provisionalTypeBallot.get(ordinal - 1);
                }
                provisionalType = firstNonBlank(provisionalType, decision.provisionalType());
            }
            ProposalOperation operation = switch (decision.choice()) {
                case REUSE -> ProposalOperation.REUSE_ENTITY;
                case CREATE_PROVISIONAL -> ProposalOperation.CREATE_PROVISIONAL_ENTITY;
                case UNRESOLVED, UNKNOWN -> ProposalOperation.UNRESOLVED;
            };
            return FocusedMentionParse.accepted(new MentionProposal(
                    proposition.id(), surface, role, operation,
                    selected == null ? null : selected.id(),
                    operation == ProposalOperation.CREATE_PROVISIONAL_ENTITY ? surface : null,
                    operation == ProposalOperation.CREATE_PROVISIONAL_ENTITY ? provisionalType : null,
                    decision.confidence(), List.of(), decision.reason(), evidence));
        }

        // Stored transcripts from before the ordinal contract remain replayable, but conflicting
        // choice fields are no longer silently normalized into a graph write.
        Optional<MentionProposal> legacy = ExtractionPassParsers.mention(response, context,
                proposition.id(), true);
        if (legacy.isEmpty()) {
            return FocusedMentionParse.rejected("response did not contain one mention decision");
        }
        MentionProposal choice = legacy.get();
        if (!ExtractionPassParsers.mentionInternallyConsistent(choice, expectedType != null)) {
            return FocusedMentionParse.rejected(
                    "legacy selected/provisional fields are not mutually exclusive");
        }
        if (choice.operation() == ProposalOperation.REUSE_ENTITY) {
            Set<String> offeredIds = candidates.stream().map(EntityCandidate::id)
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            String canonicalId = canonicalOfferedCandidateId(choice.selectedEntityId(), offeredIds);
            if (canonicalId == null) {
                return FocusedMentionParse.rejected("selectedEntityId is not in the offered ballot");
            }
            EntityCandidate selected = candidates.stream()
                    .filter(candidate -> canonicalId.equals(candidate.id()))
                    .findFirst().orElse(null);
            String selectionError = candidateSelectionError(selected, candidates, expectedType);
            if (selectionError != null) {
                return FocusedMentionParse.rejected(selectionError);
            }
            choice = new MentionProposal(proposition.id(), surface, role,
                    ProposalOperation.REUSE_ENTITY, canonicalId, null, null, choice.confidence(),
                    choice.alternatives(), choice.reason(), evidence);
        } else if (choice.operation() == ProposalOperation.CREATE_PROVISIONAL_ENTITY) {
            choice = new MentionProposal(proposition.id(), surface, role,
                    ProposalOperation.CREATE_PROVISIONAL_ENTITY, null, surface,
                    firstNonBlank(expectedType, choice.provisionalType()), choice.confidence(),
                    choice.alternatives(), choice.reason(), evidence);
        } else {
            choice = new MentionProposal(proposition.id(), surface, role,
                    ProposalOperation.UNRESOLVED, null, null, null, choice.confidence(),
                    choice.alternatives(), choice.reason(), evidence);
        }
        return FocusedMentionParse.accepted(choice);
    }

    private static String candidateSelectionError(EntityCandidate selected,
                                                  List<EntityCandidate> candidates,
                                                  String expectedType) {
        if (selected == null) {
            return "selected candidate is unavailable";
        }
        if (selected.identitySignals().forbiddenMerge()) {
            return "selected candidate is NOT_SELECTABLE under an explicit graph identity constraint";
        }
        if (expectedType != null && selected.type() != null
                && !normalizeType(expectedType).equals(normalizeType(selected.type()))) {
            return "selected candidate contradicts expected entity type " + expectedType;
        }
        List<EntityCandidate> identifierAnchors = candidates.stream()
                .filter(candidate -> candidate.identitySignals().stableIdentifierExact()
                        && candidate.identitySignals().selectable())
                .toList();
        if (identifierAnchors.size() == 1 && identifierAnchors.get(0) != selected) {
            return "selection ignored the ballot's unique stable-identifier match";
        }
        List<EntityCandidate> exactAnchors = candidates.stream()
                .filter(candidate -> candidate.identitySignals().strongMatch()
                        && candidate.identitySignals().selectable())
                .toList();
        if (exactAnchors.size() == 1 && exactAnchors.get(0) != selected
                && selected.identitySignals().sharedTokensOnly()) {
            return "selection chose weak lexical overlap over the unique exact identity match";
        }
        if (selected.identitySignals().sharedTokensOnly()
                && !selected.identitySignals().strongMatch()) {
            return "selected candidate has only WEAK_LEXICAL_ONLY overlap and no exact identity signal";
        }
        return null;
    }

    private record FocusedMentionParse(MentionProposal proposal, String rejectionReason) {

        private static FocusedMentionParse accepted(MentionProposal proposal) {
            return new FocusedMentionParse(proposal, null);
        }

        private static FocusedMentionParse rejected(String reason) {
            return new FocusedMentionParse(null, reason);
        }

        private boolean valid() {
            return proposal != null;
        }
    }

    /**
     * Reused ids are model choices, but their canonical name and type are engine-owned candidate
     * metadata. Carry those fields into projection so the decomposed result satisfies the same
     * entity contract as one-shot extraction without asking the model to repeat graph state.
     */
    private static MentionProposal enrichFromCandidate(MentionProposal mention,
                                                       EntityCandidate candidate) {
        if (mention == null || candidate == null
                || mention.operation() != ProposalOperation.REUSE_ENTITY) {
            return mention;
        }
        String description = firstNonBlank(mention.reason(),
                "Source mention \"" + nullToEmpty(mention.mentionText())
                        + "\" resolved to an existing graph entity");
        return new MentionProposal(mention.propositionId(), mention.mentionText(),
                mention.mentionRole(), mention.operation(), mention.selectedEntityId(),
                candidate.name(), candidate.type(), mention.confidence(), mention.alternatives(),
                description, mention.evidence());
    }

    private List<EntityCandidate> retrieveEntityCandidates(PassContext context,
                                                           PropositionProposal proposition) {
        Map<String, EntityCandidate> merged = new LinkedHashMap<>();
        for (String probe : List.of(nullToEmpty(proposition.subject()),
                nullToEmpty(proposition.object()))) {
            for (EntityCandidate candidate : retrieveEntityCandidates(context, probe, null)) {
                merged.merge(candidate.id(), candidate,
                        DecomposedExtractionPipeline::mergeEntityCandidates);
            }
        }
        return merged.values().stream()
                .sorted(Comparator.comparingDouble(EntityCandidate::score).reversed())
                .limit(Math.max(0, options.entityCandidateLimit()))
                .toList();
    }

    /**
     * Retrieves the ballot for one exact source mention. Focused mention calls must not inherit a
     * higher-ranked candidate retrieved for the proposition's other endpoint: the provider API is
     * deliberately mention-scoped, and the model should only decide among candidates relevant to
     * the fixed surface in its prompt.
     */
    private List<EntityCandidate> retrieveEntityCandidates(PassContext context, String probe,
                                                           String typeHint) {
        if (probe == null || probe.isBlank()) {
            return List.of();
        }
        Map<String, EntityCandidate> unique = new LinkedHashMap<>();
        List<EntityCandidate> found = safeCandidates(
                () -> entityCandidates.candidatesFor(probe, typeHint, context,
                        options.entityCandidateLimit()));
        mergeEntityCandidates(unique, found);
        for (String sourceAlias : SourceGroundedIdentityVariants.forMention(context, probe)) {
            List<EntityCandidate> aliasFound = safeCandidates(
                    () -> entityCandidates.candidatesFor(sourceAlias, typeHint, context,
                            options.entityCandidateLimit()));
            mergeEntityCandidates(unique, aliasFound.stream()
                    .map(candidate -> sourceAliasCandidate(candidate, sourceAlias))
                    .toList());
        }
        return unique.values().stream()
                .sorted(Comparator.comparingDouble(EntityCandidate::score).reversed())
                .limit(Math.max(0, options.entityCandidateLimit()))
                .toList();
    }

    private static void mergeEntityCandidates(Map<String, EntityCandidate> unique,
                                              List<EntityCandidate> candidates) {
        for (EntityCandidate candidate : candidates) {
            if (candidate == null || candidate.id() == null) {
                continue;
            }
            unique.merge(candidate.id(), candidate,
                    DecomposedExtractionPipeline::mergeEntityCandidates);
        }
    }

    private static EntityCandidate mergeEntityCandidates(EntityCandidate first,
                                                          EntityCandidate second) {
        EntityCandidate preferred = first.score() >= second.score() ? first : second;
        IdentitySignals signals = first.identitySignals().merge(second.identitySignals());
        return new EntityCandidate(preferred.id(), preferred.name(), preferred.type(),
                preferred.aliases(), Math.max(first.score(), second.score()),
                preferred.provenance(), preferred.identityContext(), signals);
    }

    /**
     * An exact candidate match reached through a source-attached alias is strong recall evidence
     * for the original mention. Weak embedding/lexical results remain weak and cannot be promoted
     * merely because they happened to be returned for an alias probe.
     */
    private static EntityCandidate sourceAliasCandidate(EntityCandidate candidate,
                                                        String sourceAlias) {
        if (candidate == null) {
            return null;
        }
        IdentitySignals original = candidate.identitySignals();
        IdentitySignals signals = original.strongMatch()
                ? original.asSourceAlias(sourceAlias) : original;
        String provenance = candidate.provenance();
        if (signals.sourceAliasExact()) {
            provenance = provenance == null || provenance.isBlank()
                    ? "source-grounded-alias"
                    : provenance + "+source-grounded-alias";
        }
        return new EntityCandidate(candidate.id(), candidate.name(), candidate.type(),
                candidate.aliases(), candidate.score(), provenance, candidate.identityContext(),
                signals);
    }

    /**
     * Downgrades a reuse decision that names an id outside the offered candidate set. The model
     * may only choose from what retrieval gave it; anything else is invention.
     */
    private MentionProposal enforceCandidateVocabulary(MentionProposal mention,
                                                       Set<String> candidateIds, Counter counter,
                                                       List<String> notes) {
        if (mention.operation() != ProposalOperation.REUSE_ENTITY) {
            return mention;
        }
        String canonicalId = canonicalOfferedCandidateId(mention.selectedEntityId(), candidateIds);
        if (canonicalId != null) {
            if (canonicalId.equals(mention.selectedEntityId())) {
                return mention;
            }
            notes.add("normalized selected entity id '" + mention.selectedEntityId()
                    + "' to offered candidate '" + canonicalId + "'");
            return new MentionProposal(mention.propositionId(), mention.mentionText(),
                    mention.mentionRole(), mention.operation(), canonicalId,
                    mention.provisionalName(), mention.provisionalType(), mention.confidence(),
                    mention.alternatives(), mention.reason(), mention.evidence());
        }
        counter.outOfVocabulary++;
        notes.add("mention downgraded to UNRESOLVED: selected id '" + mention.selectedEntityId()
                + "' was not among the offered candidates");
        return new MentionProposal(mention.propositionId(), mention.mentionText(),
                mention.mentionRole(), ProposalOperation.UNRESOLVED, null,
                mention.provisionalName(), mention.provisionalType(), mention.confidence(),
                mention.alternatives(),
                "selected id was not an offered candidate: " + mention.selectedEntityId(),
                mention.evidence());
    }

    /** Enforces mutually exclusive fields, type controls and graph identity constraints. */
    private MentionProposal enforceCandidatePurity(MentionProposal mention,
                                                   Map<String, EntityCandidate> candidatesById,
                                                   String expectedType, Counter counter,
                                                   List<String> notes) {
        if (mention == null) {
            return null;
        }
        if (!ExtractionPassParsers.mentionInternallyConsistent(mention, expectedType != null)) {
            return rejectMentionForPurity(mention,
                    "mention choice fields are internally contradictory", counter, notes);
        }
        if (mention.operation() == ProposalOperation.REUSE_ENTITY) {
            EntityCandidate selected = candidatesById.get(mention.selectedEntityId());
            String error = candidateSelectionError(selected,
                    new ArrayList<>(candidatesById.values()), expectedType);
            if (error != null) {
                return rejectMentionForPurity(mention, error, counter, notes);
            }
            return mention;
        }
        if (mention.operation() == ProposalOperation.CREATE_PROVISIONAL_ENTITY
                && expectedType != null) {
            if (mention.provisionalType() != null
                    && !normalizeType(expectedType).equals(normalizeType(mention.provisionalType()))) {
                return rejectMentionForPurity(mention,
                        "provisional type contradicts expected entity type " + expectedType,
                        counter, notes);
            }
            if (mention.provisionalType() == null) {
                return new MentionProposal(mention.propositionId(), mention.mentionText(),
                        mention.mentionRole(), mention.operation(), null,
                        mention.provisionalName(), expectedType, mention.confidence(),
                        mention.alternatives(), mention.reason(), mention.evidence());
            }
        }
        if (mention.operation().isAbstention()) {
            return new MentionProposal(mention.propositionId(), mention.mentionText(),
                    mention.mentionRole(), ProposalOperation.UNRESOLVED, null, null, null,
                    mention.confidence(), mention.alternatives(), mention.reason(),
                    mention.evidence());
        }
        return mention;
    }

    private MentionProposal rejectMentionForPurity(MentionProposal mention, String reason,
                                                   Counter counter, List<String> notes) {
        counter.outOfVocabulary++;
        notes.add("mention downgraded to UNRESOLVED by graph-purity guard: " + reason);
        return new MentionProposal(mention.propositionId(), mention.mentionText(),
                mention.mentionRole(), ProposalOperation.UNRESOLVED, null, null, null,
                mention.confidence(), mention.alternatives(), reason, mention.evidence());
    }

    private static String canonicalOfferedCandidateId(String selected, Set<String> candidateIds) {
        if (selected == null || candidateIds == null || candidateIds.isEmpty()) {
            return null;
        }
        String value = selected.strip();
        if (candidateIds.contains(value)) {
            return value;
        }
        if (value.regionMatches(true, 0, "id=", 0, 3)) {
            String withoutPrefix = value.substring(3).strip();
            int delimiter = withoutPrefix.indexOf('|');
            if (delimiter >= 0) {
                withoutPrefix = withoutPrefix.substring(0, delimiter).strip();
            }
            if (candidateIds.contains(withoutPrefix)) {
                return withoutPrefix;
            }
        }
        return null;
    }

    private MentionProposal validateSpan(MentionProposal mention, PassContext context,
                                         Counter counter, List<String> notes) {
        SpanOutcome outcome = checkSpan(mention.evidence(), context, counter, notes,
                "mention " + safe(mention.mentionText()));
        if (outcome.dropped()) {
            return null;
        }
        if (outcome.span() == mention.evidence()) {
            return mention;
        }
        return new MentionProposal(mention.propositionId(), mention.mentionText(),
                mention.mentionRole(), mention.operation(), mention.selectedEntityId(),
                mention.provisionalName(), mention.provisionalType(), mention.confidence(),
                mention.alternatives(), mention.reason(), outcome.span());
    }

    /** Maps a mention onto the proposition slot it fills, by declared role then by surface form. */
    private static String roleOf(MentionProposal mention, PropositionProposal proposition) {
        String declared = mention.mentionRole() == null ? ""
                : mention.mentionRole().toLowerCase(Locale.ROOT);
        if (declared.contains("subj") || declared.contains("source") || declared.contains("head")) {
            return ROLE_SUBJECT;
        }
        if (declared.contains("obj") || declared.contains("target") || declared.contains("tail")) {
            return ROLE_OBJECT;
        }
        String text = normalize(mention.mentionText());
        if (!text.isEmpty()) {
            if (matches(text, proposition.subject())) {
                return ROLE_SUBJECT;
            }
            if (matches(text, proposition.object())) {
                return ROLE_OBJECT;
            }
        }
        return null;
    }

    private static boolean matches(String normalizedMention, String slot) {
        String normalizedSlot = normalize(slot);
        return !normalizedSlot.isEmpty()
                && (normalizedSlot.equals(normalizedMention)
                || normalizedSlot.contains(normalizedMention)
                || normalizedMention.contains(normalizedSlot));
    }

    // ---------------------------------------------------------------------------------------
    // pass 3 — epistemic classification
    // ---------------------------------------------------------------------------------------

    private EpistemicProposal runEpistemic(PassContext context, LlmCaller caller,
                                           PropositionProposal proposition, Counter counter,
                                           List<String> notes) {
        String prompt = ExtractionPassPrompts.epistemic(context, proposition);
        String response =
                dispatch(caller, ExtractionPassPrompts.PASS_EPISTEMIC, prompt, counter, notes);
        if (response == null) {
            return EpistemicProposal.unknown(proposition.id());
        }
        Optional<EpistemicProposal> parsed =
                ExtractionPassParsers.epistemic(response, context, proposition.id());
        String rejection = parsed.isEmpty()
                ? "response did not contain the production epistemic contract"
                : null;
        if (rejection != null) {
            notes.add("epistemic classification rejected for " + proposition.id() + " ("
                    + rejection + "); requesting one bounded correction");
            String correction = dispatch(caller, ExtractionPassPrompts.PASS_EPISTEMIC,
                    ExtractionPassPrompts.epistemicCorrection(
                            context, proposition, response, rejection), counter, notes);
            if (correction != null) {
                parsed = ExtractionPassParsers.epistemic(correction, context, proposition.id());
            }
        }
        if (parsed.isEmpty()) {
            notes.add("epistemic classification unparseable for " + proposition.id()
                    + "; treating as UNKNOWN (not assertable)");
            return EpistemicProposal.unknown(proposition.id());
        }
        counter.proposed++;
        EpistemicProposal epistemic = parsed.get();
        // Epistemic classification is a decision over an already verified proposition. The model
        // does not need to reconstruct provenance; retain a supplied quote only so fabricated
        // evidence still fails closed, otherwise carry the proposition's engine-verified span.
        EvidenceSpan classificationEvidence = epistemic.evidence() == null
                ? proposition.evidence() : epistemic.evidence();
        SpanOutcome outcome = checkSpan(classificationEvidence, context, counter, notes,
                "epistemic " + proposition.id());
        if (outcome.dropped()) {
            // Fabricated attribution evidence: keep the proposition but refuse the classification.
            return EpistemicProposal.unknown(proposition.id());
        }
        counter.accepted++;
        if (epistemic.speechAct() == SpeechAct.UNKNOWN) {
            counter.abstained++;
        }
        return new EpistemicProposal(proposition.id(), epistemic.speechAct(), epistemic.holder(),
                epistemic.certainty(), epistemic.reason(), outcome.span());
    }

    // ---------------------------------------------------------------------------------------
    // pass 4 — relation selection
    // ---------------------------------------------------------------------------------------

    private List<RelationProposal> runRelations(PassContext context, LlmCaller caller,
                                                PropositionProposal proposition,
                                                ResolvedEndpoint subject,
                                                ResolvedEndpoint object,
                                                Counter counter, List<String> notes) {
        if (options.splitRelationDecision()) {
            return runSplitRelationDecision(context, caller, proposition, subject, object,
                    counter, notes);
        }
        return runLegacyRelationDecision(context, caller, proposition, subject, object,
                counter, notes).map(List::of).orElseGet(List::of);
    }

    private Optional<RelationProposal> runLegacyRelationDecision(
            PassContext context, LlmCaller caller, PropositionProposal proposition,
            ResolvedEndpoint subject, ResolvedEndpoint object,
            Counter counter, List<String> notes) {
        String sourceId = subject.entityId();
        String targetId = object.entityId();
        List<RelationCandidate> candidates = safeCandidates(
                () -> relationCandidates.candidatesFor(subject.type(), object.type(), context,
                        options.relationCandidateLimit()));
        String prompt = ExtractionPassPrompts.relations(context, proposition,
                label(subject), label(object), candidates);
        String response =
                dispatch(caller, ExtractionPassPrompts.PASS_RELATIONS, prompt, counter, notes);
        if (response == null) {
            return Optional.empty();
        }
        Optional<RelationProposal> parsed = ExtractionPassParsers.relation(
                response, context, proposition.id(), sourceId, targetId);
        if (parsed.isEmpty()) {
            notes.add("relation selection unparseable for " + proposition.id());
            return Optional.empty();
        }
        counter.proposed++;
        RelationProposal relation = enforceRelationVocabulary(parsed.get(), candidates, counter,
                notes);
        SpanOutcome outcome = checkSpan(relation.evidence(), context, counter, notes,
                "relation " + proposition.id());
        if (outcome.dropped()) {
            return Optional.empty();
        }
        String occurredAt = firstNonBlank(relation.occurredAt(), proposition.timeExpression());
        RelationProposal finalRelation = new RelationProposal(relation.propositionId(),
                relation.sourceEntityId(), relation.targetEntityId(), relation.type(),
                relation.operation(), relation.confidence(), occurredAt,
                relation.qualifiers(), relation.alternatives(), relation.reason(), outcome.span());
        if (finalRelation.operation().isAbstention()) {
            counter.abstained++;
        } else {
            counter.accepted++;
        }
        return Optional.of(finalRelation);
    }

    private List<RelationProposal> runSplitRelationDecision(
            PassContext context,
            LlmCaller caller,
            PropositionProposal proposition,
            ResolvedEndpoint subject,
            ResolvedEndpoint object,
            Counter counter,
            List<String> notes) {
        String existencePrompt = ExtractionPassPrompts.relationExistence(context, proposition,
                label(subject), label(object));
        String existenceResponse = dispatch(caller, ExtractionPassPrompts.PASS_RELATIONS,
                existencePrompt, counter, notes);
        if (existenceResponse == null) {
            return List.of();
        }
        Optional<RelationExistenceDecision> parsedExistence =
                ExtractionPassParsers.relationExistence(existenceResponse);
        if (parsedExistence.isEmpty()) {
            notes.add("relation existence decision unparseable for " + proposition.id());
            return List.of();
        }
        counter.proposed++;
        RelationExistenceDecision existence = parsedExistence.get();
        if (existence.decision() != RelationExistence.ASSERTED) {
            counter.abstained++;
            return List.of(new RelationProposal(
                    proposition.id(), subject.entityId(), object.entityId(), null,
                    ProposalOperation.ABSTAIN, existence.confidence(), null, Map.of(), List.of(),
                    existence.reason(), proposition.evidence()));
        }

        // Candidate retrieval belongs after the existence gate: an abstention should not pay for
        // embeddings or expose type glosses that can bias the simpler boolean decision.
        List<RelationCandidate> candidates = safeCandidates(
                () -> relationCandidates.candidatesFor(subject.type(), object.type(), context,
                        options.relationCandidateLimit()));
        if (candidates.isEmpty()) {
            counter.outOfVocabulary++;
            counter.accepted++;
            return List.of(new RelationProposal(
                    proposition.id(), subject.entityId(), object.entityId(),
                    schemaGapType(proposition.predicate()),
                    ProposalOperation.PROPOSE_SCHEMA_GAP, existence.confidence(),
                    proposition.timeExpression(), Map.of(), List.of(),
                    "relation asserted but no schema-admissible type exists", proposition.evidence()));
        }

        Map<String, RelationCandidate> candidatesByType = new LinkedHashMap<>();
        for (RelationCandidate candidate : candidates) {
            if (candidate != null && candidate.type() != null && !candidate.type().isBlank()) {
                candidatesByType.putIfAbsent(normalize(candidate.type()), candidate);
            }
        }
        List<RelationCandidate> remaining = new ArrayList<>(candidatesByType.values());
        List<String> selectedTypes = new ArrayList<>();
        List<RelationProposal> selectedRelations = new ArrayList<>();
        while (!remaining.isEmpty()) {
            Optional<RelationProposal> selected = selectSplitRelationType(context, caller,
                    proposition, subject, object, existence, remaining, selectedTypes,
                    counter, notes);
            if (selected.isEmpty()) {
                if (!selectedRelations.isEmpty()) {
                    notes.add("later relation-type decision failed for " + proposition.id()
                            + "; preserving " + selectedRelations.size()
                            + " previously validated distinct relation(s)");
                }
                break;
            }
            RelationProposal relation = selected.get();
            if (relation.operation() == ProposalOperation.CREATE_CLAIM) {
                selectedRelations.add(relation);
                selectedTypes.add(relation.type());
                String selectedType = normalize(relation.type());
                remaining.removeIf(candidate -> normalize(candidate.type()).equals(selectedType));
                continue;
            }
            if (relation.operation() == ProposalOperation.PROPOSE_SCHEMA_GAP) {
                selectedRelations.add(relation);
            } else if (selectedRelations.isEmpty()) {
                selectedRelations.add(relation);
            }
            break;
        }
        return List.copyOf(selectedRelations);
    }

    private Optional<RelationProposal> selectSplitRelationType(
            PassContext context,
            LlmCaller caller,
            PropositionProposal proposition,
            ResolvedEndpoint subject,
            ResolvedEndpoint object,
            RelationExistenceDecision existence,
            List<RelationCandidate> candidates,
            List<String> alreadySelectedTypes,
            Counter counter,
            List<String> notes) {
        boolean additionalRound = alreadySelectedTypes != null && !alreadySelectedTypes.isEmpty();
        String typePrompt = ExtractionPassPrompts.relationType(context, proposition,
                label(subject), label(object), candidates, alreadySelectedTypes);
        String typeResponse = dispatch(caller, ExtractionPassPrompts.PASS_RELATIONS,
                typePrompt, counter, notes);
        if (typeResponse == null) {
            return Optional.empty();
        }
        Optional<RelationTypeDecision> parsedType = ExtractionPassParsers.relationType(typeResponse);
        boolean authoritativeCompletion = additionalRound
                && ExtractionPassParsers.relationTypeContinuationCompletionIntent(typeResponse);
        String authoritativeCompletionResponse = authoritativeCompletion ? typeResponse : null;
        boolean ordinalOutsideBallot = relationTypeOrdinalOutsideBallot(
                parsedType, candidates.size());
        boolean sawOrdinalOutsideBallot = ordinalOutsideBallot;
        if (sawOrdinalOutsideBallot) {
            counter.outOfVocabulary++;
        }
        int correctionAttempt = 0;
        while ((parsedType.isEmpty()
                || !ExtractionPassParsers.relationTypeAllowedForRound(
                        parsedType.get(), additionalRound)
                || ordinalOutsideBallot
                || (authoritativeCompletion && parsedType
                        .map(decision -> decision.disposition() != RelationTypeDisposition.DONE)
                        .orElse(true)))
                && correctionAttempt < 2) {
            correctionAttempt++;
            notes.add("relation type contract or internal consistency rejected for "
                    + proposition.id() + "; requesting bounded correction "
                    + correctionAttempt + " of 2");
            String correctionPrompt = ExtractionPassPrompts.relationTypeCorrection(
                    context, proposition, label(subject), label(object), candidates,
                    authoritativeCompletionResponse != null
                            ? authoritativeCompletionResponse : typeResponse,
                    alreadySelectedTypes);
            String correctionResponse = dispatch(caller, ExtractionPassPrompts.PASS_RELATIONS,
                    correctionPrompt, counter, notes);
            if (correctionResponse == null) {
                break;
            }
            typeResponse = correctionResponse;
            parsedType = ExtractionPassParsers.relationType(typeResponse);
            ordinalOutsideBallot = relationTypeOrdinalOutsideBallot(
                    parsedType, candidates.size());
            if (ordinalOutsideBallot && !sawOrdinalOutsideBallot) {
                counter.outOfVocabulary++;
                sawOrdinalOutsideBallot = true;
            }
        }
        if (parsedType.isEmpty()
                || !ExtractionPassParsers.relationTypeAllowedForRound(
                        parsedType.get(), additionalRound)
                || ordinalOutsideBallot
                || (authoritativeCompletion
                        && parsedType.get().disposition() != RelationTypeDisposition.DONE)) {
            notes.add("relation type decision was unparseable, internally contradictory, outside "
                    + "the graph-supplied ballot, or changed an explicit completion decision for "
                    + proposition.id());
            return Optional.empty();
        }
        counter.proposed++;
        RelationTypeDecision selection = parsedType.get();
        Integer ordinal = selection.candidateOrdinal();
        if (ordinal != null && (ordinal < 1 || ordinal > candidates.size())) {
            counter.abstained++;
            notes.add("relation type ordinal " + ordinal + " is outside the offered ballot for "
                    + proposition.id());
            return Optional.of(new RelationProposal(
                    proposition.id(), subject.entityId(), object.entityId(), null,
                    ProposalOperation.ABSTAIN, selection.confidence(), null, Map.of(), List.of(),
                    "selected relation ordinal was not offered", proposition.evidence()));
        }

        ProposalOperation operation;
        String type;
        if (selection.disposition() == RelationTypeDisposition.DONE) {
            operation = ProposalOperation.ABSTAIN;
            type = null;
        } else if (ordinal != null) {
            operation = ProposalOperation.CREATE_CLAIM;
            type = candidates.get(ordinal - 1).type();
        } else if (selection.schemaGap()) {
            operation = ProposalOperation.PROPOSE_SCHEMA_GAP;
            type = schemaGapType(proposition.predicate());
            counter.outOfVocabulary++;
        } else {
            operation = ProposalOperation.ABSTAIN;
            type = null;
        }
        double confidence = Math.min(existence.confidence(), selection.confidence());
        RelationProposal relation = new RelationProposal(
                proposition.id(), subject.entityId(), object.entityId(), type, operation, confidence,
                proposition.timeExpression(), selection.qualifiers(), List.of(), selection.reason(),
                proposition.evidence());
        if (operation.isAbstention()) {
            if (additionalRound) {
                notes.add("no additional distinct relation type selected for " + proposition.id()
                        + " after " + alreadySelectedTypes);
            } else {
                counter.abstained++;
            }
        } else {
            counter.accepted++;
        }
        return Optional.of(relation);
    }

    private static boolean relationTypeOrdinalOutsideBallot(
            Optional<RelationTypeDecision> parsed, int candidateCount) {
        if (parsed.isEmpty() || parsed.get().candidateOrdinal() == null) {
            return false;
        }
        int ordinal = parsed.get().candidateOrdinal();
        return ordinal < 1 || ordinal > candidateCount;
    }

    /**
     * Rejects a relation type that is not in the schema-admissible set. The model does not get to
     * widen the ontology; an unmatched type becomes a reported schema gap.
     */
    private RelationProposal enforceRelationVocabulary(RelationProposal relation,
                                                       List<RelationCandidate> candidates,
                                                       Counter counter, List<String> notes) {
        if (relation.operation() != ProposalOperation.CREATE_CLAIM || candidates.isEmpty()) {
            return relation;
        }
        String chosen = normalize(relation.type());
        boolean permitted = candidates.stream()
                .anyMatch(c -> normalize(c.type()).equals(chosen));
        if (permitted) {
            // Echo the schema's own spelling rather than the model's.
            String canonical = candidates.stream()
                    .filter(c -> normalize(c.type()).equals(chosen))
                    .map(RelationCandidate::type)
                    .findFirst()
                    .orElse(relation.type());
            return canonical.equals(relation.type()) ? relation
                    : new RelationProposal(relation.propositionId(), relation.sourceEntityId(),
                            relation.targetEntityId(), canonical, relation.operation(),
                            relation.confidence(), relation.occurredAt(), relation.qualifiers(),
                            relation.alternatives(), relation.reason(), relation.evidence());
        }
        counter.outOfVocabulary++;
        notes.add("relation type '" + relation.type() + "' is not admissible here; recorded as a "
                + "schema gap for " + relation.propositionId());
        return new RelationProposal(relation.propositionId(), relation.sourceEntityId(),
                relation.targetEntityId(), relation.type(), ProposalOperation.PROPOSE_SCHEMA_GAP,
                relation.confidence(), relation.occurredAt(), relation.qualifiers(),
                relation.alternatives(),
                "type not in permitted set: " + relation.type(), relation.evidence());
    }

    // ---------------------------------------------------------------------------------------
    // pass 5 — claim and evidence matching
    // ---------------------------------------------------------------------------------------

    private Optional<ClaimProposal> runClaims(PassContext context, LlmCaller caller,
                                              RelationProposal relation, Counter counter,
                                              List<String> notes) {
        String relationKey = ExtractionProjection.relationKey(relation);
        if (!options.claimMatchingEnabled()) {
            return Optional.of(ClaimProposal.newClaim(relation.propositionId(), relationKey));
        }
        List<ClaimCandidate> candidates = safeCandidates(
                () -> claimCandidates.candidatesFor(relation.sourceEntityId(), relation.type(),
                        relation.targetEntityId(), context, options.claimCandidateLimit()));
        if (candidates.isEmpty()) {
            // Nothing to match against: no call is worth making, the answer is already determined.
            return Optional.of(ClaimProposal.newClaim(relation.propositionId(), relationKey));
        }
        if (options.splitClaimsByCandidate()) {
            return runClaimsByCandidate(context, caller, relation, relationKey, candidates, counter,
                    notes);
        }
        String prompt = ExtractionPassPrompts.claims(context, relation,
                summarize(relation), candidates);
        String response =
                dispatch(caller, ExtractionPassPrompts.PASS_CLAIMS, prompt, counter, notes);
        if (response == null) {
            return Optional.of(ClaimProposal.newClaim(relation.propositionId(), relationKey));
        }
        Optional<ClaimProposal> parsed = ExtractionPassParsers.claim(
                response, context, relation.propositionId(), relationKey, candidates);
        if (parsed.isEmpty()) {
            notes.add("claim decision unparseable for " + relation.propositionId()
                    + "; treating as a new claim");
            return Optional.of(ClaimProposal.newClaim(relation.propositionId(), relationKey));
        }
        counter.proposed++;
        ClaimProposal claim = enforceClaimVocabulary(parsed.get(), candidates, counter, notes);
        // Claim matching does not introduce new source content. The relation evidence was already
        // verified, so the engine carries it forward instead of asking the model to reproduce it.
        ClaimProposal finalClaim = new ClaimProposal(claim.propositionId(), relationKey,
                claim.operation(), claim.matchedAtomKey(), claim.confidence(), claim.alternatives(),
                claim.reason(), relation.evidence());
        if (finalClaim.operation().isAbstention()) {
            counter.abstained++;
        } else {
            counter.accepted++;
        }
        return Optional.of(finalClaim);
    }

    private Optional<ClaimProposal> runClaimsByCandidate(
            PassContext context,
            LlmCaller caller,
            RelationProposal relation,
            String relationKey,
            List<ClaimCandidate> candidates,
            Counter counter,
            List<String> notes) {
        List<ClaimAssessment> assessments = new ArrayList<>();
        List<Alternative> alternatives = new ArrayList<>();
        boolean uncertain = false;
        for (int index = 0; index < candidates.size(); index++) {
            ClaimCandidate candidate = candidates.get(index);
            String prompt = ExtractionPassPrompts.claimCandidate(context, relation,
                    summarize(relation), candidate, index + 1, candidates.size());
            String response = dispatch(caller, ExtractionPassPrompts.PASS_CLAIMS, prompt, counter,
                    notes);
            if (response == null) {
                uncertain = true;
                continue;
            }
            Optional<ClaimComparisonDecision> parsed =
                    ExtractionPassParsers.claimComparison(response);
            String rejection = claimComparisonRejection(parsed, relation, candidate);
            if (rejection != null) {
                notes.add("claim comparison rejected for " + relation.propositionId()
                        + " candidate " + (index + 1) + ": " + rejection
                        + "; requesting one bounded correction");
                String correction = dispatch(caller, ExtractionPassPrompts.PASS_CLAIMS,
                        ExtractionPassPrompts.claimCandidateCorrection(context, relation,
                                summarize(relation), candidate, index + 1, candidates.size(),
                                response, rejection), counter, notes);
                if (correction != null) {
                    response = correction;
                    parsed = ExtractionPassParsers.claimComparison(response);
                    rejection = claimComparisonRejection(parsed, relation, candidate);
                }
            }
            if (rejection != null) {
                uncertain = true;
                notes.add("claim comparison withheld for " + relation.propositionId()
                        + " candidate " + (index + 1) + ": " + rejection);
                continue;
            }
            counter.proposed++;
            ClaimComparisonDecision decision = parsed.get();
            assessments.add(new ClaimAssessment(candidate, decision));
            alternatives.add(new Alternative(candidate == null ? null : candidate.atomKey(),
                    decision.confidence(), decision.reason()));
            uncertain |= decision.comparison() == ClaimComparison.UNCERTAIN;
        }

        Optional<ClaimAssessment> same = assessments.stream()
                .filter(assessment -> assessment.candidate() != null
                        && assessment.decision().comparison() == ClaimComparison.SAME)
                .max(Comparator.comparingDouble(
                        assessment -> assessment.decision().confidence()));
        Optional<ClaimAssessment> contradiction = assessments.stream()
                .filter(assessment -> assessment.candidate() != null
                        && assessment.decision().comparison() == ClaimComparison.CONTRADICTS)
                .max(Comparator.comparingDouble(
                        assessment -> assessment.decision().confidence()));

        ProposalOperation operation;
        ClaimAssessment selected = null;
        String reason;
        if (same.isPresent()) {
            operation = ProposalOperation.ADD_EVIDENCE;
            selected = same.get();
            reason = selected.decision().reason();
        } else if (contradiction.isPresent()) {
            operation = ProposalOperation.FLAG_CONTRADICTION;
            selected = contradiction.get();
            reason = selected.decision().reason();
        } else if (uncertain || assessments.size() < candidates.size()) {
            operation = ProposalOperation.ABSTAIN;
            reason = "at least one candidate comparison was uncertain or unavailable";
        } else {
            operation = ProposalOperation.CREATE_CLAIM;
            reason = "every existing candidate was classified as a different compatible fact";
        }

        double confidence = selected != null ? selected.decision().confidence()
                : assessments.stream().mapToDouble(a -> a.decision().confidence()).min().orElse(0.0d);
        String matchedAtomKey = selected == null || selected.candidate() == null
                ? null : selected.candidate().atomKey();
        ClaimProposal claim = new ClaimProposal(
                relation.propositionId(), relationKey, operation, matchedAtomKey, confidence,
                alternatives, reason, relation.evidence());
        if (operation.isAbstention()) {
            counter.abstained++;
        } else {
            counter.accepted++;
        }
        return Optional.of(claim);
    }

    private record ClaimAssessment(ClaimCandidate candidate, ClaimComparisonDecision decision) {
    }

    private static String claimComparisonRejection(Optional<ClaimComparisonDecision> parsed,
                                                   RelationProposal relation,
                                                   ClaimCandidate candidate) {
        if (parsed == null || parsed.isEmpty()) {
            return "response did not contain one parseable comparison";
        }
        return ExtractionPassParsers.claimComparisonInternallyConsistent(
                parsed.get(), relation, candidate) ? null
                : "SAME contradicts an engine-known subject, predicate, or object mismatch";
    }

    private ClaimProposal enforceClaimVocabulary(ClaimProposal claim,
                                                 List<ClaimCandidate> candidates, Counter counter,
                                                 List<String> notes) {
        if (claim.matchedAtomKey() == null) {
            return claim;
        }
        boolean known = candidates.stream()
                .anyMatch(c -> claim.matchedAtomKey().equals(c.atomKey()));
        if (known) {
            return claim;
        }
        counter.outOfVocabulary++;
        notes.add("claim decision referenced unknown atom '" + claim.matchedAtomKey()
                + "'; abstaining");
        return new ClaimProposal(claim.propositionId(), claim.relationKey(),
                ProposalOperation.ABSTAIN, null, claim.confidence(), claim.alternatives(),
                "referenced atom was not an offered candidate", claim.evidence());
    }

    // ---------------------------------------------------------------------------------------
    // shared helpers
    // ---------------------------------------------------------------------------------------

    private String dispatch(LlmCaller caller, String passId, String prompt, Counter counter,
                            List<String> notes) {
        counter.calls++;
        try {
            String response = caller.call(passId, prompt);
            if (response == null || response.isBlank()) {
                counter.failures++;
                notes.add("pass " + passId + " returned no content");
                return null;
            }
            return response;
        } catch (RuntimeException e) {
            counter.failures++;
            notes.add("pass " + passId + " failed: " + e.getMessage());
            log.warn("Decomposed extraction pass {} failed: {}", passId, e.toString());
            return null;
        }
    }

    /**
     * @param span    the span to carry forward, possibly repaired, possibly {@code null}
     * @param dropped whether the owning proposal must be discarded
     */
    private record SpanOutcome(EvidenceSpan span, boolean dropped) {
    }

    /**
     * Applies the evidence policy for passes 2-5: a quote that cannot be located in the source is
     * fabricated and kills the proposal; a proposal that simply omitted a quote is kept, because
     * the proposition it hangs off has already been verified against the source.
     */
    private SpanOutcome checkSpan(EvidenceSpan span, PassContext context, Counter counter,
                                  List<String> notes, String subject) {
        if (!options.requireEvidenceSpans()) {
            return new SpanOutcome(span, false);
        }
        EvidenceSpanValidator.SpanCheck check = EvidenceSpanValidator.check(span, context);
        if (check.usable()) {
            return new SpanOutcome(check.span(), false);
        }
        if (check.status() == EvidenceSpanValidator.Status.MISSING) {
            notes.add(subject + ": no evidence quote supplied");
            return new SpanOutcome(null, false);
        }
        counter.spanRejected++;
        notes.add(subject + " dropped (" + check.status() + "): " + check.detail());
        return new SpanOutcome(null, true);
    }

    private static <T> List<T> safeCandidates(java.util.function.Supplier<List<T>> supplier) {
        // An empty list is a valid retrieval result. An exception is infrastructure failure and
        // must propagate to the crawl's existing failed-chunk/retry path; converting it to an empty
        // ballot would incorrectly tell the model that no entity/relationship/claim exists.
        List<T> found = supplier.get();
        return found == null ? List.of() : found;
    }

    private static String label(ResolvedEndpoint endpoint) {
        StringBuilder sb = new StringBuilder("id=").append(endpoint.entityId());
        if (endpoint.name() != null) {
            sb.append(" | name=").append(endpoint.name());
        }
        if (endpoint.mention().mentionText() != null) {
            sb.append(" | mention=").append(endpoint.mention().mentionText());
        }
        if (endpoint.type() != null) {
            sb.append(" | type=").append(endpoint.type());
        }
        sb.append(endpoint.mention().operation() == ProposalOperation.REUSE_ENTITY
                ? " | existing graph entity" : " | provisional (new)");
        return sb.toString();
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second == null || second.isBlank() ? null : second;
    }

    private static String summarize(RelationProposal relation) {
        return relation.sourceEntityId() + " -[" + relation.type() + "]-> "
                + relation.targetEntityId();
    }

    private static PropositionProposal rebase(PropositionProposal value, int sourceOffset) {
        return new PropositionProposal(value.id(), value.text(), value.subject(), value.predicate(),
                value.object(), value.polarity(), value.modality(), value.timeExpression(),
                value.condition(), value.attributedTo(), rebase(value.evidence(), sourceOffset));
    }

    private static MentionProposal rebase(MentionProposal value, int sourceOffset) {
        return new MentionProposal(value.propositionId(), value.mentionText(), value.mentionRole(),
                value.operation(), value.selectedEntityId(), value.provisionalName(),
                value.provisionalType(), value.confidence(), value.alternatives(), value.reason(),
                rebase(value.evidence(), sourceOffset));
    }

    private static EpistemicProposal rebase(EpistemicProposal value, int sourceOffset) {
        return new EpistemicProposal(value.propositionId(), value.speechAct(), value.holder(),
                value.certainty(), value.reason(), rebase(value.evidence(), sourceOffset));
    }

    private static RelationProposal rebase(RelationProposal value, int sourceOffset) {
        return new RelationProposal(value.propositionId(), value.sourceEntityId(),
                value.targetEntityId(), value.type(), value.operation(), value.confidence(),
                value.occurredAt(), value.qualifiers(), value.alternatives(), value.reason(),
                rebase(value.evidence(), sourceOffset));
    }

    private static ClaimProposal rebase(ClaimProposal value, int sourceOffset) {
        return new ClaimProposal(value.propositionId(), value.relationKey(), value.operation(),
                value.matchedAtomKey(), value.confidence(), value.alternatives(), value.reason(),
                rebase(value.evidence(), sourceOffset));
    }

    private static EvidenceSpan rebase(EvidenceSpan span, int sourceOffset) {
        if (span == null || sourceOffset == 0 || !span.hasOffsets()) {
            return span;
        }
        return span.withOffsets(span.start() + sourceOffset, span.end() + sourceOffset);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static String normalizeType(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT)
                .replace('-', '_').replace(' ', '_');
    }

    private static String schemaGapType(String predicate) {
        String normalized = nullToEmpty(predicate).trim().toUpperCase(Locale.ROOT)
                .replaceAll("[^A-Z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
        return normalized.isBlank() ? "UNMAPPED_RELATION" : normalized;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String safe(String value) {
        return value == null ? "?" : value;
    }

    /** Mutable per-pass tally; converted to an immutable {@link PassStats} at the end of a run. */
    private static final class Counter {
        private final String passId;
        private int calls;
        private int proposed;
        private int accepted;
        private int abstained;
        private int spanRejected;
        private int outOfVocabulary;
        private int failures;

        Counter(String passId) {
            this.passId = passId;
        }

        PassStats toStats() {
            return new PassStats(passId, calls, proposed, accepted, abstained, spanRejected,
                    outOfVocabulary, failures);
        }
    }
}
