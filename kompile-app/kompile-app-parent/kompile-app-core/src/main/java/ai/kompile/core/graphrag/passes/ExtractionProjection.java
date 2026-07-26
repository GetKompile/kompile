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

import ai.kompile.core.graphrag.format.GraphExtractionSchema;
import ai.kompile.core.graphrag.format.GraphExtractionSchema.ExtractedEntity;
import ai.kompile.core.graphrag.format.GraphExtractionSchema.ExtractedRelation;
import ai.kompile.core.graphrag.format.GraphExtractionSchema.ExtractionMetadata;
import ai.kompile.core.graphrag.format.GraphExtractionSchema.ExtractionResult;
import ai.kompile.core.graphrag.passes.ExtractionProposals.Alternative;
import ai.kompile.core.graphrag.passes.ExtractionProposals.ClaimProposal;
import ai.kompile.core.graphrag.passes.ExtractionProposals.EpistemicProposal;
import ai.kompile.core.graphrag.passes.ExtractionProposals.EvidenceSpan;
import ai.kompile.core.graphrag.passes.ExtractionProposals.MentionProposal;
import ai.kompile.core.graphrag.passes.ExtractionProposals.PassBundle;
import ai.kompile.core.graphrag.passes.ExtractionProposals.Polarity;
import ai.kompile.core.graphrag.passes.ExtractionProposals.PropositionProposal;
import ai.kompile.core.graphrag.passes.ExtractionProposals.RelationProposal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Projects validated pass proposals into the existing {@link GraphExtractionSchema} result that
 * the rest of the crawl pipeline already consumes.
 *
 * <p>This is where the epistemic firewall is enforced. A proposition classified as an opinion,
 * prediction, request or commitment still reaches the graph, but marked
 * ({@code epistemic}, {@code assertable=false}) and with damped confidence, so the existing fact
 * promotion policy — not the model — decides whether it ever becomes canonical. Negated
 * propositions are withheld entirely, because the downstream graph has no negative-edge channel
 * and projecting one as a positive edge would assert the opposite of the source.</p>
 */
public final class ExtractionProjection {

    /** Property keys stamped on projected entities and relations. */
    public static final class Props {
        public static final String PASS_SCHEMA = "passSchema";
        public static final String PROVISIONAL = "provisional";
        public static final String MENTION_TEXT = "mentionText";
        public static final String MENTION_ROLE = "mentionRole";
        public static final String RESOLUTION_REASON = "resolutionReason";
        public static final String REJECTED_CANDIDATES = "rejectedCandidates";
        public static final String SOURCE_CHUNK_ID = "sourceChunkId";
        public static final String PROPOSITION_ID = "propositionId";
        public static final String PROPOSITION_TEXT = "propositionText";
        public static final String EVIDENCE_QUOTE = "evidenceQuote";
        public static final String EVIDENCE_START = "evidenceStart";
        public static final String EVIDENCE_END = "evidenceEnd";
        public static final String EVIDENCE_ROLE = "evidenceRole";
        public static final String EPISTEMIC = "epistemic";
        public static final String EPISTEMIC_HOLDER = "epistemicHolder";
        public static final String SOURCE_CERTAINTY = "sourceCertainty";
        public static final String ASSERTABLE = "assertable";
        public static final String MODALITY = "modality";
        public static final String CLAIM_OPERATION = "claimOperation";
        public static final String CLAIM_ATOM_KEY = "claimAtomKey";
        public static final String SCHEMA_GAP = "schemaGap";
        public static final String QUALIFIER_PREFIX = "qual.";

        private Props() {
        }
    }

    /**
     * Ceiling applied to anything the source did not assert in its own voice. Matches
     * {@link GraphExtractionSchema#DEFAULT_RELATION_CONFIDENCE} so attributed material stays below
     * the PSL hard-pin threshold and can always be overridden by stronger evidence.
     */
    public static final double ATTRIBUTED_CONFIDENCE_CEILING =
            GraphExtractionSchema.DEFAULT_RELATION_CONFIDENCE;

    /** Floor so a damped relation never collapses to zero and vanishes from scoring. */
    public static final double MIN_CONFIDENCE = 0.05;

    private ExtractionProjection() {
    }

    /**
     * @param result the schema result to hand to the existing graph construction path
     * @param notes  human-readable record of everything withheld and why; never silently dropped
     */
    public record Projection(ExtractionResult result, List<String> notes) {

        public Projection {
            notes = notes == null ? List.of() : List.copyOf(notes);
        }
    }

    /** Projects a bundle produced for one chunk. */
    public static Projection project(PassBundle bundle, PassContext context) {
        List<String> notes = new ArrayList<>();
        Map<String, PropositionProposal> propositions = bundle.propositions().stream()
                .filter(p -> p.id() != null)
                .collect(Collectors.toMap(PropositionProposal::id, Function.identity(),
                        (a, b) -> a, LinkedHashMap::new));
        Map<String, EpistemicProposal> epistemics = bundle.epistemics().stream()
                .filter(e -> e.propositionId() != null)
                .collect(Collectors.toMap(EpistemicProposal::propositionId, Function.identity(),
                        (a, b) -> a, LinkedHashMap::new));
        Map<String, ClaimProposal> claims = bundle.claims().stream()
                .filter(c -> c.relationKey() != null)
                .collect(Collectors.toMap(ClaimProposal::relationKey, Function.identity(),
                        (a, b) -> a, LinkedHashMap::new));

        Map<String, EntityAccumulator> entities = new LinkedHashMap<>();
        for (MentionProposal mention : bundle.mentions()) {
            if (!mention.resolved()) {
                notes.add("mention withheld (" + mention.operation() + "): "
                        + safe(mention.mentionText()) + " — " + safe(mention.reason()));
                continue;
            }
            String id = entityId(mention);
            if (id == null) {
                notes.add("mention withheld (no usable id): " + safe(mention.mentionText()));
                continue;
            }
            entities.computeIfAbsent(id, key -> new EntityAccumulator(key, context))
                    .add(mention);
        }

        List<ExtractedRelation> relations = new ArrayList<>();
        for (RelationProposal relation : bundle.relations()) {
            projectRelation(relation, propositions, epistemics, claims, entities, context, notes)
                    .ifPresent(relations::add);
        }

        List<ExtractedEntity> projectedEntities = entities.values().stream()
                .map(EntityAccumulator::toEntity)
                .toList();

        ExtractionMetadata metadata = new ExtractionMetadata(
                context.chunkId(), context.documentId(), context.modelId(),
                java.time.Instant.now().toString(), context.graphId(), context.parentGraphId());

        return new Projection(
                ExtractionResult.of(projectedEntities, List.copyOf(relations), metadata),
                notes);
    }

    private static java.util.Optional<ExtractedRelation> projectRelation(
            RelationProposal relation,
            Map<String, PropositionProposal> propositions,
            Map<String, EpistemicProposal> epistemics,
            Map<String, ClaimProposal> claims,
            Map<String, EntityAccumulator> entities,
            PassContext context,
            List<String> notes) {

        String label = describe(relation);
        if (!relation.committing()) {
            notes.add("relation withheld (" + relation.operation() + "): " + label + " — "
                    + safe(relation.reason()));
            return java.util.Optional.empty();
        }
        if (!entities.containsKey(relation.sourceEntityId())
                || !entities.containsKey(relation.targetEntityId())) {
            notes.add("relation withheld (endpoint unresolved): " + label);
            return java.util.Optional.empty();
        }

        PropositionProposal proposition = propositions.get(relation.propositionId());
        if (proposition != null && proposition.polarity() == Polarity.NEGATED) {
            // The source denies this. There is no negative-edge channel downstream, so emitting
            // it as an ordinary edge would assert the opposite of what the document says.
            notes.add("relation withheld (source negates it): " + label);
            return java.util.Optional.empty();
        }

        EpistemicProposal epistemic = epistemics.get(relation.propositionId());
        SpeechAct act = epistemic == null ? SpeechAct.UNKNOWN : epistemic.speechAct();
        double certainty = epistemic == null ? 0.5d : epistemic.certainty();
        boolean modalityAllows =
                proposition == null || proposition.modality().allowsDirectAssertion()
                        || proposition.modality() == ExtractionProposals.Modality.UNKNOWN;
        boolean assertable = act.isDirectlyPromotable() && modalityAllows;

        ClaimProposal claim = claims.get(relationKey(relation));
        if (claim != null && claim.operation() == ProposalOperation.ABSTAIN) {
            notes.add("relation withheld (claim matching abstained): " + label + " — "
                    + safe(claim.reason()));
            return java.util.Optional.empty();
        }
        boolean contradiction = claim != null
                && claim.operation() == ProposalOperation.FLAG_CONTRADICTION;
        // A type outside the admissible set is a request to widen the ontology, not a fact. It is
        // carried so the gap is visible, but it can never promote on the model's say-so.
        boolean schemaGap = relation.operation() == ProposalOperation.PROPOSE_SCHEMA_GAP;

        double confidence = relation.confidence();
        if (!assertable) {
            confidence = Math.min(confidence * certainty, ATTRIBUTED_CONFIDENCE_CEILING);
        }
        if (contradiction || schemaGap) {
            // A flagged conflict is handed to the truth-maintenance system, not resolved here.
            confidence = Math.min(confidence, ATTRIBUTED_CONFIDENCE_CEILING);
        }
        confidence = Math.max(MIN_CONFIDENCE, Math.min(1.0d, confidence));

        Map<String, String> properties = new LinkedHashMap<>();
        properties.put(Props.PASS_SCHEMA, ExtractionProposals.SCHEMA_VERSION);
        putIfPresent(properties, Props.PROPOSITION_ID, relation.propositionId());
        if (proposition != null) {
            putIfPresent(properties, Props.PROPOSITION_TEXT, proposition.render());
            properties.put(Props.MODALITY, proposition.modality().name());
        }
        properties.put(Props.EPISTEMIC, act.name());
        properties.put(Props.ASSERTABLE,
                Boolean.toString(assertable && !contradiction && !schemaGap));
        if (schemaGap) {
            properties.put(Props.SCHEMA_GAP, "true");
        }
        properties.put(Props.SOURCE_CERTAINTY, format(certainty));
        if (epistemic != null) {
            putIfPresent(properties, Props.EPISTEMIC_HOLDER, epistemic.holder());
        }
        putIfPresent(properties, Props.SOURCE_CHUNK_ID, context.chunkId());
        putIfPresent(properties, Props.RESOLUTION_REASON, relation.reason());
        putIfPresent(properties, Props.REJECTED_CANDIDATES, renderAlternatives(relation.alternatives()));
        addEvidence(properties, relation.evidence());
        relation.qualifiers().forEach((k, v) -> properties.put(Props.QUALIFIER_PREFIX + k, v));
        if (claim != null) {
            properties.put(Props.CLAIM_OPERATION, claim.operation().name());
            putIfPresent(properties, Props.CLAIM_ATOM_KEY, claim.matchedAtomKey());
        }

        String description = proposition != null ? proposition.render() : relation.reason();
        return java.util.Optional.of(new ExtractedRelation(
                relation.sourceEntityId(), relation.targetEntityId(), relation.type(),
                description, confidence, Map.copyOf(properties), relation.occurredAt()));
    }

    /** Stable key linking a pass 4 relation to its pass 5 decision. */
    public static String relationKey(RelationProposal relation) {
        return relation.propositionId() + "|" + relation.sourceEntityId() + "|"
                + relation.type() + "|" + relation.targetEntityId();
    }

    /**
     * Graph id for a resolved mention: the selected candidate's id when reusing, otherwise a
     * deterministic slug of the proposed name so the same provisional entity converges across
     * chunks instead of multiplying.
     */
    public static String entityId(MentionProposal mention) {
        if (mention.operation() == ProposalOperation.REUSE_ENTITY) {
            return blankToNull(mention.selectedEntityId());
        }
        String name = blankToNull(mention.provisionalName());
        if (name == null) {
            name = blankToNull(mention.mentionText());
        }
        return name == null ? null : slug(name);
    }

    /** Lower-cased, underscore-joined slug used as a deterministic provisional entity id. */
    public static String slug(String value) {
        String slug = value.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
        return slug.isEmpty() ? "entity" : slug;
    }

    private static void addEvidence(Map<String, String> properties, EvidenceSpan span) {
        if (span == null || !span.hasQuote()) {
            return;
        }
        properties.put(Props.EVIDENCE_QUOTE, span.quote());
        properties.put(Props.EVIDENCE_ROLE, span.role().name());
        if (span.hasOffsets()) {
            properties.put(Props.EVIDENCE_START, Integer.toString(span.start()));
            properties.put(Props.EVIDENCE_END, Integer.toString(span.end()));
        }
    }

    private static String renderAlternatives(List<Alternative> alternatives) {
        if (alternatives == null || alternatives.isEmpty()) {
            return null;
        }
        return alternatives.stream()
                .map(a -> a.candidateId() + "@" + format(a.score())
                        + (a.reason() == null ? "" : " (" + a.reason() + ")"))
                .collect(Collectors.joining("; "));
    }

    private static void putIfPresent(Map<String, String> properties, String key, String value) {
        if (value != null && !value.isBlank()) {
            properties.put(key, value);
        }
    }

    private static String describe(RelationProposal relation) {
        return safe(relation.sourceEntityId()) + " -[" + safe(relation.type()) + "]-> "
                + safe(relation.targetEntityId());
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }

    private static String safe(String value) {
        return value == null ? "?" : value;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /** Merges every mention that resolved to the same entity id within a chunk. */
    private static final class EntityAccumulator {

        private final String id;
        private final PassContext context;
        private final Set<String> aliases = new LinkedHashSet<>();
        private final Map<String, String> properties = new LinkedHashMap<>();
        private String name;
        private String type;
        private String description;
        private double confidence;
        private boolean provisional;

        EntityAccumulator(String id, PassContext context) {
            this.id = id;
            this.context = context;
        }

        void add(MentionProposal mention) {
            boolean isProvisional =
                    mention.operation() == ProposalOperation.CREATE_PROVISIONAL_ENTITY;
            provisional = provisional || isProvisional;
            String candidateName = firstNonBlank(mention.provisionalName(), mention.mentionText());
            if (name == null || (candidateName != null && candidateName.length() > name.length())) {
                name = candidateName;
            }
            if (type == null) {
                type = blankToNull(mention.provisionalType());
            }
            if (mention.mentionText() != null && !mention.mentionText().isBlank()) {
                aliases.add(mention.mentionText().trim());
            }
            confidence = Math.max(confidence, mention.confidence());
            if (description == null) {
                description = blankToNull(mention.reason());
            }
            putIfPresent(properties, Props.MENTION_TEXT, mention.mentionText());
            putIfPresent(properties, Props.MENTION_ROLE, mention.mentionRole());
            putIfPresent(properties, Props.RESOLUTION_REASON, mention.reason());
            putIfPresent(properties, Props.REJECTED_CANDIDATES,
                    renderAlternatives(mention.alternatives()));
            addEvidence(properties, mention.evidence());
        }

        ExtractedEntity toEntity() {
            Map<String, String> props = new LinkedHashMap<>(properties);
            props.put(Props.PASS_SCHEMA, ExtractionProposals.SCHEMA_VERSION);
            props.put(Props.PROVISIONAL, Boolean.toString(provisional));
            putIfPresent(props, Props.SOURCE_CHUNK_ID, context.chunkId());
            List<String> aliasList = aliases.stream()
                    .filter(a -> name == null || !a.equalsIgnoreCase(name))
                    .toList();
            return new ExtractedEntity(id, name == null ? id : name, type, aliasList, description,
                    confidence <= 0 ? null : confidence, Map.copyOf(props));
        }

        private static String firstNonBlank(String first, String second) {
            String value = blankToNull(first);
            return value != null ? value : blankToNull(second);
        }
    }
}
