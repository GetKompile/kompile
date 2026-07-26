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

import ai.kompile.core.graphrag.format.GraphExtractionSchema.ExtractionResult;
import ai.kompile.core.graphrag.passes.ExtractionCandidates.ClaimCandidate;
import ai.kompile.core.graphrag.passes.ExtractionCandidates.EntityCandidate;
import ai.kompile.core.graphrag.passes.ExtractionCandidates.RelationCandidate;
import ai.kompile.core.graphrag.passes.ExtractionProposals.ClaimProposal;
import ai.kompile.core.graphrag.passes.ExtractionProposals.EpistemicProposal;
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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Runs the decomposed extraction passes for one chunk.
 *
 * <p>The model is called once per bounded object — once for the chunk's propositions, then once
 * per proposition for identity, epistemic class and relation selection, and once per proposed
 * relation for claim matching. Between calls the engine does the work models are bad at:
 * retrieving candidates, verifying quoted evidence against the source, enforcing the closed
 * vocabularies, and finally projecting into the schema the crawl already consumes. A model answer
 * that names something outside its candidate list is downgraded to an abstention rather than
 * trusted, so recall stays the retriever's responsibility and invention cannot reach the graph.</p>
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
     * @param maxPropositions        upper bound on propositions taken from one chunk
     * @param entityCandidateLimit   candidates offered per proposition in pass 2
     * @param relationCandidateLimit relation types offered in pass 4
     * @param claimCandidateLimit    existing claims offered in pass 5
     * @param requireEvidenceSpans   drop proposals whose quote cannot be found in the source
     * @param claimMatchingEnabled   run pass 5 when claim candidates exist
     */
    public record Options(
            int maxPropositions,
            int entityCandidateLimit,
            int relationCandidateLimit,
            int claimCandidateLimit,
            boolean requireEvidenceSpans,
            boolean claimMatchingEnabled) {

        public static Options defaults() {
            return new Options(12, 8, 12, 5, true, true);
        }

        public Options withMaxPropositions(int value) {
            return new Options(value, entityCandidateLimit, relationCandidateLimit,
                    claimCandidateLimit, requireEvidenceSpans, claimMatchingEnabled);
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

        List<PropositionProposal> propositions = runPropositions(context, caller, stats, notes);
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

        for (PropositionProposal proposition : propositions) {
            Map<String, ResolvedEndpoint> resolvedByRole = runMentions(
                    context, caller, proposition, mentions, mentionCounter, notes);
            EpistemicProposal epistemic =
                    runEpistemic(context, caller, proposition, epistemicCounter, notes);
            epistemics.add(epistemic);

            ResolvedEndpoint subject = resolvedByRole.get(ROLE_SUBJECT);
            ResolvedEndpoint object = resolvedByRole.get(ROLE_OBJECT);
            if (subject == null || object == null) {
                notes.add("relation pass skipped for " + proposition.id()
                        + ": endpoints not both resolved");
                continue;
            }
            Optional<RelationProposal> relation = runRelations(
                    context, caller, proposition, subject, object, relationCounter, notes);
            relation.ifPresent(relations::add);
            relation.filter(RelationProposal::committing)
                    .flatMap(r -> runClaims(context, caller, r, claimCounter, notes))
                    .ifPresent(claims::add);
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

    private List<PropositionProposal> runPropositions(PassContext context, LlmCaller caller,
                                                      List<PassStats> stats, List<String> notes) {
        Counter counter = new Counter(ExtractionPassPrompts.PASS_PROPOSITIONS);
        String prompt = ExtractionPassPrompts.propositions(context, options.maxPropositions());
        String response = dispatch(caller, ExtractionPassPrompts.PASS_PROPOSITIONS, prompt,
                counter, notes);
        if (response == null) {
            stats.add(counter.toStats());
            return List.of();
        }
        List<PropositionProposal> parsed =
                ExtractionPassParsers.propositions(response, context, options.maxPropositions());
        counter.proposed += parsed.size();

        List<PropositionProposal> accepted = new ArrayList<>();
        for (PropositionProposal proposition : parsed) {
            EvidenceSpanValidator.SpanCheck check =
                    EvidenceSpanValidator.check(proposition.evidence(), context);
            if (!check.usable()) {
                // A proposition is the vehicle for everything downstream; an unverifiable one is
                // dropped outright rather than carried with a warning.
                counter.spanRejected++;
                notes.add("proposition dropped (" + check.status() + "): " + check.detail());
                continue;
            }
            accepted.add(new PropositionProposal(proposition.id(), proposition.text(),
                    proposition.subject(), proposition.predicate(), proposition.object(),
                    proposition.polarity(), proposition.modality(), proposition.timeExpression(),
                    proposition.condition(), proposition.attributedTo(), check.span()));
        }
        counter.accepted += accepted.size();
        stats.add(counter.toStats());
        return List.copyOf(accepted);
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
        List<EntityCandidate> candidates = retrieveEntityCandidates(context, proposition);
        String prompt = ExtractionPassPrompts.mentions(context, proposition, candidates);
        String response =
                dispatch(caller, ExtractionPassPrompts.PASS_MENTIONS, prompt, counter, notes);
        if (response == null) {
            return Map.of();
        }
        List<MentionProposal> parsed =
                ExtractionPassParsers.mentions(response, context, proposition.id());
        counter.proposed += parsed.size();

        Map<String, EntityCandidate> candidatesById = new LinkedHashMap<>();
        candidates.forEach(c -> candidatesById.put(c.id(), c));

        Map<String, ResolvedEndpoint> byRole = new LinkedHashMap<>();
        for (MentionProposal mention : parsed) {
            MentionProposal checked = enforceCandidateVocabulary(mention, candidatesById.keySet(),
                    counter, notes);
            checked = validateSpan(checked, context, counter, notes);
            if (checked == null) {
                continue;
            }
            if (checked.operation().isAbstention()) {
                counter.abstained++;
            } else {
                counter.accepted++;
            }
            sink.add(checked);
            String role = roleOf(checked, proposition);
            String entityId = ExtractionProjection.entityId(checked);
            if (role != null && checked.resolved() && entityId != null) {
                EntityCandidate matched = candidatesById.get(checked.selectedEntityId());
                String name = matched != null ? matched.name()
                        : firstNonBlank(checked.provisionalName(), checked.mentionText());
                String type = matched != null ? matched.type() : checked.provisionalType();
                byRole.putIfAbsent(role, new ResolvedEndpoint(checked, entityId, name, type));
            }
        }
        return byRole;
    }

    private List<EntityCandidate> retrieveEntityCandidates(PassContext context,
                                                           PropositionProposal proposition) {
        Map<String, EntityCandidate> merged = new LinkedHashMap<>();
        for (String probe : List.of(nullToEmpty(proposition.subject()),
                nullToEmpty(proposition.object()))) {
            if (probe.isBlank()) {
                continue;
            }
            List<EntityCandidate> found = safeCandidates(
                    () -> entityCandidates.candidatesFor(probe, null, context,
                            options.entityCandidateLimit()));
            for (EntityCandidate candidate : found) {
                if (candidate == null || candidate.id() == null) {
                    continue;
                }
                merged.merge(candidate.id(), candidate,
                        (a, b) -> a.score() >= b.score() ? a : b);
            }
        }
        return merged.values().stream()
                .sorted(Comparator.comparingDouble(EntityCandidate::score).reversed())
                .limit(Math.max(0, options.entityCandidateLimit()))
                .toList();
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
        if (candidateIds.contains(mention.selectedEntityId())) {
            return mention;
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
        if (parsed.isEmpty()) {
            notes.add("epistemic classification unparseable for " + proposition.id()
                    + "; treating as UNKNOWN (not assertable)");
            return EpistemicProposal.unknown(proposition.id());
        }
        counter.proposed++;
        EpistemicProposal epistemic = parsed.get();
        SpanOutcome outcome = checkSpan(epistemic.evidence(), context, counter, notes,
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

    private Optional<RelationProposal> runRelations(PassContext context, LlmCaller caller,
                                                    PropositionProposal proposition,
                                                    ResolvedEndpoint subject,
                                                    ResolvedEndpoint object,
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
        RelationProposal finalRelation = new RelationProposal(relation.propositionId(),
                relation.sourceEntityId(), relation.targetEntityId(), relation.type(),
                relation.operation(), relation.confidence(), relation.occurredAt(),
                relation.qualifiers(), relation.alternatives(), relation.reason(), outcome.span());
        if (finalRelation.operation().isAbstention()) {
            counter.abstained++;
        } else {
            counter.accepted++;
        }
        return Optional.of(finalRelation);
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
        String prompt = ExtractionPassPrompts.claims(context, relation,
                summarize(relation), candidates);
        String response =
                dispatch(caller, ExtractionPassPrompts.PASS_CLAIMS, prompt, counter, notes);
        if (response == null) {
            return Optional.of(ClaimProposal.newClaim(relation.propositionId(), relationKey));
        }
        Optional<ClaimProposal> parsed = ExtractionPassParsers.claim(
                response, context, relation.propositionId(), relationKey);
        if (parsed.isEmpty()) {
            notes.add("claim decision unparseable for " + relation.propositionId()
                    + "; treating as a new claim");
            return Optional.of(ClaimProposal.newClaim(relation.propositionId(), relationKey));
        }
        counter.proposed++;
        ClaimProposal claim = enforceClaimVocabulary(parsed.get(), candidates, counter, notes);
        SpanOutcome outcome = checkSpan(claim.evidence(), context, counter, notes,
                "claim " + relation.propositionId());
        ClaimProposal finalClaim = outcome.dropped()
                ? new ClaimProposal(claim.propositionId(), relationKey, ProposalOperation.ABSTAIN,
                        null, claim.confidence(), claim.alternatives(),
                        "evidence span could not be verified", null)
                : new ClaimProposal(claim.propositionId(), relationKey, claim.operation(),
                        claim.matchedAtomKey(), claim.confidence(), claim.alternatives(),
                        claim.reason(), outcome.span());
        if (finalClaim.operation().isAbstention()) {
            counter.abstained++;
        } else {
            counter.accepted++;
        }
        return Optional.of(finalClaim);
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
        try {
            List<T> found = supplier.get();
            return found == null ? List.of() : found;
        } catch (RuntimeException e) {
            log.warn("Candidate retrieval failed; continuing with an empty candidate set: {}",
                    e.toString());
            return List.of();
        }
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

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
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
