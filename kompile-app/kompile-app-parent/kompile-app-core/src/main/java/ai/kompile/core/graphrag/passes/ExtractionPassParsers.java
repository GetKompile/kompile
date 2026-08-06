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

import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.core.graphrag.format.LlmJsonExtractor;
import ai.kompile.core.graphrag.passes.ExtractionCandidates.ClaimCandidate;
import ai.kompile.core.graphrag.passes.ExtractionProposals.Alternative;
import ai.kompile.core.graphrag.passes.ExtractionProposals.ClaimProposal;
import ai.kompile.core.graphrag.passes.ExtractionProposals.EpistemicProposal;
import ai.kompile.core.graphrag.passes.ExtractionProposals.EvidenceRole;
import ai.kompile.core.graphrag.passes.ExtractionProposals.EvidenceSpan;
import ai.kompile.core.graphrag.passes.ExtractionProposals.MentionProposal;
import ai.kompile.core.graphrag.passes.ExtractionProposals.Modality;
import ai.kompile.core.graphrag.passes.ExtractionProposals.Polarity;
import ai.kompile.core.graphrag.passes.ExtractionProposals.PropositionProposal;
import ai.kompile.core.graphrag.passes.ExtractionProposals.RelationProposal;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Lenient parsers for pass responses.
 *
 * <p>JSON isolation reuses {@link LlmJsonExtractor}, the existing helper that already survives
 * markdown fences and CLI-agent log prefixes. Field binding is done by hand against the parsed
 * tree rather than by Jackson data binding: small models emit lower-case enum labels, {@code
 * "null"} strings, single objects where arrays are specified and vice versa, and a strict binder
 * would throw away an otherwise usable answer. Every unrecognised value degrades to abstention,
 * never to a committing operation.</p>
 */
public final class ExtractionPassParsers {

    private static final ObjectMapper MAPPER = JsonUtils.newStandardMapper();
    private static final Pattern GRAPH_TYPE_NAME = Pattern.compile("[A-Z][A-Z0-9_]*");

    private ExtractionPassParsers() {
    }

    /** Engine-facing result of the relation-existence gate. */
    public enum RelationExistence {
        ASSERTED,
        NOT_ASSERTED,
        UNCERTAIN
    }

    public record RelationExistenceDecision(
            RelationExistence decision,
            double confidence,
            String reason) {

        public RelationExistenceDecision {
            decision = decision == null ? RelationExistence.UNCERTAIN : decision;
        }
    }

    /** Explicit semantic branch for a relation-type ballot. */
    public enum RelationTypeDisposition {
        SELECT,
        SCHEMA_GAP,
        ABSTAIN,
        DONE,
        UNKNOWN
    }

    /** Engine-facing result of relation-type selection over a fixed ordinal ballot. */
    public record RelationTypeDecision(
            RelationTypeDisposition disposition,
            Integer candidateOrdinal,
            boolean schemaGap,
            double confidence,
            Map<String, String> qualifiers,
            String reason) {

        public RelationTypeDecision {
            disposition = disposition == null ? RelationTypeDisposition.UNKNOWN : disposition;
            qualifiers = qualifiers == null ? Map.of() : Map.copyOf(qualifiers);
        }
    }

    /** One existing claim compared independently with the new relation. */
    public enum ClaimComparison {
        SAME,
        CONTRADICTS,
        DIFFERENT,
        UNCERTAIN
    }

    public record ClaimComparisonDecision(
            ClaimComparison comparison,
            double confidence,
            String reason) {

        public ClaimComparisonDecision {
            comparison = comparison == null ? ClaimComparison.UNCERTAIN : comparison;
        }
    }

    /** Discriminated decision used by the production one-endpoint identity contract. */
    public enum FocusedMentionChoice {
        REUSE,
        CREATE_PROVISIONAL,
        UNRESOLVED,
        UNKNOWN
    }

    /**
     * The model selects an ordinal, never an opaque graph id. {@code forbiddenLegacyFields}
     * records a shape violation so the engine can request one bounded correction instead of
     * silently accepting mutually exclusive fields.
     */
    public record FocusedMentionDecision(
            FocusedMentionChoice choice,
            Integer candidateOrdinal,
            Integer provisionalTypeOrdinal,
            String provisionalType,
            double confidence,
            String reason,
            boolean forbiddenLegacyFields) {

        public FocusedMentionDecision {
            choice = choice == null ? FocusedMentionChoice.UNKNOWN : choice;
        }
    }

    /**
     * Parses pass 1. Production normally asks for every semantic proposition in one crawl chunk;
     * a positive caller limit remains available as an explicit resource override. Every array item
     * is retained independently when unbounded. The singleton contract remains readable for stored
     * transcripts/providers during migration.
     */
    public static List<PropositionProposal> propositions(String raw, PassContext context,
                                                         int limit) {
        int effectiveLimit = limit <= 0 ? Integer.MAX_VALUE : limit;
        JsonNode parsedRoot = root(raw);
        JsonNode singleton = parsedRoot == null ? null : parsedRoot.get("proposition");
        if (singleton != null && singleton.isObject()) {
            PropositionProposal parsed = parseProposition(singleton, context, 1);
            return parsed == null ? List.of() : List.of(parsed);
        }

        JsonNode array = arrayNode(parsedRoot, "propositions", "results", "items");
        if (array == null) {
            return List.of();
        }
        List<PropositionProposal> out = new ArrayList<>();
        int index = 0;
        for (JsonNode node : array) {
            if (out.size() >= effectiveLimit) {
                break;
            }
            index++;
            PropositionProposal parsed = parseProposition(node, context, index);
            if (parsed != null) {
                out.add(parsed);
            }
        }
        return List.copyOf(out);
    }

    /**
     * Parses the production focused-event contract. The current contract is one flat object with a
     * boolean {@code asserted} discriminator, which avoids asking a small model to choose between an
     * object and null at the same JSON path. Text, evidence and id remain engine-owned. Former nested,
     * flat-without-discriminator and array shapes stay readable for stored transcript replay.
     */
    public static Optional<PropositionProposal> proposition(String raw, PassContext context,
                                                            String focusEvent) {
        if (focusEvent == null || focusEvent.isBlank()) {
            return Optional.empty();
        }
        JsonNode parsedRoot = root(raw);
        if (parsedRoot == null) {
            return Optional.empty();
        }

        JsonNode node;
        JsonNode asserted = parsedRoot.get("asserted");
        if (asserted != null) {
            if (!asserted.isBoolean() || !asserted.booleanValue()) {
                return Optional.empty();
            }
            if (!parsedRoot.has("subject") && !parsedRoot.has("predicate")) {
                return Optional.empty();
            }
            node = parsedRoot;
        } else {
            node = parsedRoot.get("proposition");
            if (node != null && node.isNull()) {
                return Optional.empty();
            }
            if (node == null || !node.isObject()) {
                JsonNode array = arrayNode(parsedRoot, "propositions", "results", "items");
                if (array != null && !array.isEmpty() && array.get(0).isObject()) {
                    node = array.get(0);
                } else if (parsedRoot.isObject()
                        && (parsedRoot.has("subject") || parsedRoot.has("predicate"))) {
                    node = parsedRoot;
                } else {
                    return Optional.empty();
                }
            }
        }
        PropositionProposal parsed = parseProposition(node, context, 1);
        if (parsed == null) {
            return Optional.empty();
        }
        return Optional.of(new PropositionProposal(defaultPropositionId(context, 1), focusEvent,
                parsed.subject(), parsed.predicate(), parsed.object(), parsed.polarity(),
                parsed.modality(), parsed.timeExpression(), parsed.condition(),
                parsed.attributedTo(), EvidenceSpan.ofQuote(context.chunkId(), focusEvent)));
    }

    /**
     * Distinguishes a valid focused-event abstention from malformed output eligible for repair.
     * The nested null check is retained solely for transcript compatibility.
     */
    public static boolean explicitNullProposition(String raw) {
        JsonNode parsedRoot = root(raw);
        if (parsedRoot == null) {
            return false;
        }
        JsonNode asserted = parsedRoot.get("asserted");
        if (asserted != null) {
            return asserted.isBoolean() && !asserted.booleanValue();
        }
        JsonNode proposition = parsedRoot.get("proposition");
        return proposition != null && proposition.isNull();
    }

    private static PropositionProposal parseProposition(JsonNode node, PassContext context, int index) {
        String propositionText = text(node, "text", "statement", "proposition");
        String subject = text(node, "subject", "source", "head");
        if (propositionText == null && subject == null) {
            return null;
        }
        String id = text(node, "id", "propositionId");
        return new PropositionProposal(
                id == null ? defaultPropositionId(context, index) : id,
                propositionText,
                subject,
                text(node, "predicate", "relation", "verb"),
                text(node, "object", "target", "tail"),
                Polarity.from(text(node, "polarity", "negation")),
                Modality.from(text(node, "modality", "mood")),
                text(node, "timeExpression", "time", "occurredAt", "when"),
                text(node, "condition", "scope"),
                text(node, "attributedTo", "holder", "speaker", "attribution"),
                span(node, context));
    }

    /** Parses a pass 2 response for one proposition. */
    public static List<MentionProposal> mentions(String raw, PassContext context,
                                                 String propositionId) {
        JsonNode array = arrayNode(root(raw), "mentions", "resolutions", "results", "items");
        if (array == null) {
            return List.of();
        }
        List<MentionProposal> out = new ArrayList<>();
        for (JsonNode node : array) {
            out.add(parseMention(node, context, propositionId));
        }
        return List.copyOf(out);
    }

    /** Parses the singleton contract used when the engine has already fixed one endpoint. */
    public static Optional<MentionProposal> mention(String raw, PassContext context,
                                                    String propositionId) {
        return mention(raw, context, propositionId, true);
    }

    /**
     * Parses a focused identity decision. When inference is enabled, selectedEntityId is the sole
     * reuse signal, provisionalName is the creation signal, and neither is an abstention. Any
     * selected id is still checked against the exact offered ballot by the pipeline before use.
     */
    public static Optional<MentionProposal> mention(String raw, PassContext context,
                                                    String propositionId,
                                                    boolean inferOperationFromFields) {
        JsonNode parsedRoot = root(raw);
        JsonNode node = objectNode(parsedRoot, "mention", "resolution", "decision", "result");
        if (node == null) {
            JsonNode array = arrayNode(parsedRoot, "mentions", "resolutions", "results", "items");
            if (array == null || array.isEmpty()) {
                return Optional.empty();
            }
            node = array.get(0);
        }
        MentionProposal parsed = parseMention(node, context, propositionId);
        if (!inferOperationFromFields) {
            return Optional.of(parsed);
        }
        ProposalOperation operation = parsed.selectedEntityId() != null
                ? ProposalOperation.REUSE_ENTITY
                : parsed.provisionalName() != null
                ? ProposalOperation.CREATE_PROVISIONAL_ENTITY
                : ProposalOperation.UNRESOLVED;
        return Optional.of(new MentionProposal(parsed.propositionId(), parsed.mentionText(),
                parsed.mentionRole(), operation, parsed.selectedEntityId(),
                parsed.provisionalName(), parsed.provisionalType(), parsed.confidence(),
                parsed.alternatives(), parsed.reason(), parsed.evidence()));
    }

    /** Parses the production branch-specific focused mention contract. */
    public static Optional<FocusedMentionDecision> focusedMentionDecision(String raw) {
        JsonNode response = root(raw);
        boolean canonicalEnvelope = response != null && response.isObject()
                && response.size() == 1 && response.has("mention")
                && response.get("mention").isObject();
        JsonNode node = objectNode(response, "mention", "resolution", "result");
        if (node == null && response != null && response.isObject() && response.has("decision")) {
            node = response;
        }
        if (node == null) {
            return Optional.empty();
        }
        String rawChoice = text(node, "decision", "choice", "action");
        if (rawChoice == null) {
            // Stored transcripts using selectedEntityId/provisionalName are handled by the legacy
            // replay parser and then subjected to the same engine purity validator.
            return Optional.empty();
        }
        FocusedMentionChoice choice = switch (rawChoice.strip().toUpperCase(Locale.ROOT)
                        .replace('-', '_').replace(' ', '_')) {
                    case "REUSE", "REUSE_ENTITY", "SAME" -> FocusedMentionChoice.REUSE;
                    case "CREATE", "CREATE_PROVISIONAL", "CREATE_PROVISIONAL_ENTITY", "NEW" ->
                            FocusedMentionChoice.CREATE_PROVISIONAL;
                    case "UNRESOLVED", "ABSTAIN", "UNCERTAIN", "AMBIGUOUS" ->
                            FocusedMentionChoice.UNRESOLVED;
                    default -> FocusedMentionChoice.UNKNOWN;
                };
        int ordinal = intAt(node, -1, "candidateOrdinal", "selectedCandidateOrdinal",
                "candidateIndex", "candidate");
        int provisionalTypeOrdinal = intAt(node, -1, "provisionalTypeOrdinal");
        boolean forbiddenLegacyFields = !canonicalEnvelope
                || hasUnexpectedFields(node, "decision", "candidateOrdinal",
                "provisionalTypeOrdinal", "provisionalType", "confidence", "reason")
                || hasAny(node, "selectedEntityId", "entityId", "provisionalName", "operation",
                "evidence", "alternatives", "mentionText", "mentionRole");
        return Optional.of(new FocusedMentionDecision(choice, ordinal > 0 ? ordinal : null,
                provisionalTypeOrdinal > 0 ? provisionalTypeOrdinal : null,
                text(node, "provisionalType", "entityType", "type"), confidence(node),
                text(node, "reason", "rationale", "explanation"), forbiddenLegacyFields));
    }

    /**
     * Validates the committing fields selected by the focused mention discriminator.
     *
     * <p>An ordinal attached to CREATE_PROVISIONAL or UNRESOLVED is non-committing noise: those
     * branches never reuse a graph id, and the engine discards the ordinal. Accepting that stale
     * ballot handle is therefore purity-preserving and avoids throwing away a source-grounded
     * endpoint. REUSE remains strict because it is the only branch capable of merging identities.</p>
     */
    public static boolean focusedMentionInternallyConsistent(FocusedMentionDecision decision,
                                                              boolean candidatesAvailable,
                                                              boolean expectedTypeAvailable) {
        return focusedMentionInternallyConsistent(decision, candidatesAvailable,
                expectedTypeAvailable, false);
    }

    /**
     * Validates a focused identity branch against both the identity ballot and the independent
     * provisional-type ballot. A type ordinal is committing graph data and is accepted only when
     * the engine actually supplied that ballot.
     */
    public static boolean focusedMentionInternallyConsistent(FocusedMentionDecision decision,
                                                              boolean candidatesAvailable,
                                                              boolean expectedTypeAvailable,
                                                              boolean typeCandidatesAvailable) {
        if (decision == null || decision.forbiddenLegacyFields()) {
            return false;
        }
        return switch (decision.choice()) {
            case REUSE -> candidatesAvailable && decision.candidateOrdinal() != null
                    && decision.provisionalTypeOrdinal() == null
                    && decision.provisionalType() == null;
            case CREATE_PROVISIONAL -> expectedTypeAvailable
                    ? decision.provisionalTypeOrdinal() == null
                            && decision.provisionalType() == null
                    : typeCandidatesAvailable
                            ? decision.provisionalTypeOrdinal() != null
                                    && decision.provisionalType() == null
                            : decision.provisionalTypeOrdinal() == null
                                    && concreteProvisionalType(decision.provisionalType());
            case UNRESOLVED -> decision.provisionalTypeOrdinal() == null
                    && decision.provisionalType() == null;
            case UNKNOWN -> false;
        };
    }

    private static boolean concreteProvisionalType(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String normalized = value.strip();
        return GRAPH_TYPE_NAME.matcher(normalized).matches()
                && !Set.of("TYPE", "TYPE_NAME", "ENTITY_TYPE", "ENTITY_CLASS",
                "TYPE_FROM_SOURCE", "SOURCE_TYPE", "SOURCE_OR_SCHEMA_TYPE", "UNKNOWN",
                "NULL", "NONE", "N_A").contains(normalized);
    }

    /** Legacy replay responses are accepted only when their committing fields are exclusive. */
    public static boolean mentionInternallyConsistent(MentionProposal mention,
                                                      boolean expectedTypeAvailable) {
        if (mention == null) {
            return false;
        }
        return switch (mention.operation()) {
            case REUSE_ENTITY -> mention.selectedEntityId() != null
                    && mention.provisionalName() == null && mention.provisionalType() == null;
            case CREATE_PROVISIONAL_ENTITY -> mention.selectedEntityId() == null
                    && mention.provisionalName() != null
                    && (expectedTypeAvailable || mention.provisionalType() != null);
            case UNRESOLVED, ABSTAIN -> mention.selectedEntityId() == null
                    && mention.provisionalName() == null && mention.provisionalType() == null;
            default -> false;
        };
    }

    private static MentionProposal parseMention(JsonNode node, PassContext context,
                                                String propositionId) {
        ProposalOperation operation =
                ProposalOperation.from(text(node, "operation", "decision", "action"));
        String selected = text(node, "selectedEntityId", "entityId", "candidateId", "id");
        String provisionalName = text(node, "provisionalName", "name", "newEntityName");
        // A committing answer with nothing to commit is an abstention, not a graph write.
        if (operation == ProposalOperation.REUSE_ENTITY && selected == null) {
            operation = ProposalOperation.UNRESOLVED;
        } else if (operation == ProposalOperation.CREATE_PROVISIONAL_ENTITY
                && provisionalName == null) {
            provisionalName = text(node, "mentionText", "mention", "surfaceForm");
            if (provisionalName == null) {
                operation = ProposalOperation.UNRESOLVED;
            }
        }
        return new MentionProposal(
                propositionId,
                text(node, "mentionText", "mention", "surfaceForm", "text"),
                text(node, "mentionRole", "role", "position"),
                operation,
                selected,
                provisionalName,
                text(node, "provisionalType", "type", "entityType"),
                confidence(node),
                alternatives(node),
                text(node, "reason", "rationale", "explanation"),
                span(node, context));
    }

    /** Parses a pass 3 response for one proposition. */
    public static Optional<EpistemicProposal> epistemic(String raw, PassContext context,
                                                        String propositionId) {
        JsonNode node = objectNode(root(raw), "classification", "epistemic", "result");
        if (node == null) {
            return Optional.empty();
        }
        return Optional.of(new EpistemicProposal(
                propositionId,
                SpeechAct.from(text(node, "speechAct", "speech_act", "type", "kind", "class")),
                text(node, "holder", "attributedTo", "speaker", "source"),
                doubleAt(node, 0.5d, "certainty", "sourceCertainty", "confidence"),
                text(node, "reason", "rationale", "explanation"),
                span(node, context)));
    }

    /** Parses a pass 4 response for one proposition with resolved endpoints. */
    public static Optional<RelationProposal> relation(String raw, PassContext context,
                                                      String propositionId, String sourceEntityId,
                                                      String targetEntityId) {
        JsonNode node = objectNode(root(raw), "relation", "selection", "result");
        if (node == null) {
            return Optional.empty();
        }
        ProposalOperation operation =
                ProposalOperation.from(text(node, "operation", "decision", "action"));
        String type = text(node, "type", "relationType", "relation", "predicate");
        if (operation == ProposalOperation.CREATE_CLAIM && type == null) {
            operation = ProposalOperation.ABSTAIN;
        }
        return Optional.of(new RelationProposal(
                propositionId,
                sourceEntityId,
                targetEntityId,
                type,
                operation,
                confidence(node),
                text(node, "occurredAt", "timeExpression", "time", "when"),
                qualifiers(node),
                alternatives(node),
                text(node, "reason", "rationale", "explanation"),
                span(node, context)));
    }

    /** Parses the small-model relation-existence gate without asking for a relation type. */
    public static Optional<RelationExistenceDecision> relationExistence(String raw) {
        JsonNode node = objectNode(root(raw), "decision", "existence", "result");
        if (node == null) {
            return Optional.empty();
        }
        JsonNode value = first(node, "verdict", "assertsRelation", "relationExists", "exists", "asserted");
        RelationExistence decision = RelationExistence.UNCERTAIN;
        if (value != null && value.isBoolean()) {
            decision = value.asBoolean() ? RelationExistence.ASSERTED : RelationExistence.NOT_ASSERTED;
        } else if (value != null && value.isValueNode()) {
            String normalized = value.asText().strip().toUpperCase(Locale.ROOT)
                    .replace('-', '_').replace(' ', '_');
            decision = switch (normalized) {
                case "TRUE", "YES", "ASSERTED", "RELATION", "EXISTS" ->
                        RelationExistence.ASSERTED;
                case "FALSE", "NO", "NOT_ASSERTED", "NONE", "ABSTAIN" ->
                        RelationExistence.NOT_ASSERTED;
                default -> RelationExistence.UNCERTAIN;
            };
        }
        return Optional.of(new RelationExistenceDecision(decision, confidence(node),
                text(node, "reason", "rationale", "explanation")));
    }

    /** Parses relation type selection; the engine maps the ordinal back to its canonical type. */
    public static Optional<RelationTypeDecision> relationType(String raw) {
        JsonNode response = root(raw);
        JsonNode node = objectNode(response, "selection", "decision", "relationType", "result");
        JsonNode shorthand = response == null ? null : response.get("selection");
        if (node == null && shorthand != null
                && (shorthand.isIntegralNumber() || shorthand.isTextual())) {
            int ordinal = shorthand.asInt(-1);
            if (ordinal > 0) {
                return Optional.of(new RelationTypeDecision(
                        RelationTypeDisposition.SELECT,
                        ordinal,
                        booleanAt(response, false, "schemaGap", "missingType", "proposeSchemaGap"),
                        confidence(response),
                        qualifiers(response),
                        text(response, "reason", "rationale", "explanation")));
            }
        }
        if (node == null) {
            return Optional.empty();
        }
        int ordinal = intAt(node, -1, "candidateOrdinal", "selectedCandidateOrdinal",
                "selectedCandidate", "candidateIndex");
        boolean schemaGap = booleanAt(node, false,
                "schemaGap", "missingType", "proposeSchemaGap");
        RelationTypeDisposition disposition = relationTypeDisposition(node, ordinal, schemaGap);
        String reason = text(node, "reason", "rationale", "explanation");
        if ((reason == null || reason.isBlank()) && response != null && response != node) {
            reason = text(response, "reason", "rationale", "explanation");
        }
        return Optional.of(new RelationTypeDecision(
                disposition,
                ordinal > 0 ? ordinal : null,
                schemaGap,
                confidence(node),
                qualifiers(node),
                reason));
    }

    private static RelationTypeDisposition relationTypeDisposition(
            JsonNode node, int ordinal, boolean schemaGap) {
        String rawDisposition = text(node, "decision", "disposition", "outcome");
        if (rawDisposition != null && !rawDisposition.isBlank()) {
            return switch (rawDisposition.strip().toUpperCase(Locale.ROOT)
                    .replace('-', '_').replace(' ', '_')) {
                case "SELECT", "CHOOSE", "CANDIDATE" -> RelationTypeDisposition.SELECT;
                case "SCHEMA_GAP", "MISSING_TYPE", "PROPOSE_SCHEMA_GAP" ->
                        RelationTypeDisposition.SCHEMA_GAP;
                case "ABSTAIN", "UNCERTAIN", "UNRESOLVED" -> RelationTypeDisposition.ABSTAIN;
                case "DONE", "COMPLETE", "NO_ADDITIONAL_RELATION" -> RelationTypeDisposition.DONE;
                default -> RelationTypeDisposition.UNKNOWN;
            };
        }
        if (ordinal > 0) {
            return RelationTypeDisposition.SELECT;
        }
        return schemaGap ? RelationTypeDisposition.SCHEMA_GAP : RelationTypeDisposition.ABSTAIN;
    }

    /**
     * Recognizes the exact control-field intent in a malformed continuation response without
     * accepting its shape as a valid ballot. This lets the correction preserve completion instead
     * of turning a formatting error into a new graph edge.
     */
    static boolean relationTypeContinuationCompletionIntent(String raw) {
        JsonNode response = root(raw);
        if (response == null || !response.isObject()) {
            return false;
        }
        JsonNode nested = response.get("selection");
        if (nested != null && nested.isObject()) {
            Optional<RelationTypeDecision> parsed = relationType(raw);
            return parsed.map(decision -> decision.disposition() == RelationTypeDisposition.DONE)
                    .orElse(false);
        }
        return nested != null && nested.isNull()
                && response.has("schemaGap")
                && response.get("schemaGap").isBoolean()
                && !response.get("schemaGap").asBoolean()
                && !response.hasNonNull("candidateOrdinal");
    }

    /**
     * Validates only the structured relation ballot controls. Free-text reason is explanatory model
     * output, not a control channel: interpreting particular words here would make correctness
     * language- and domain-dependent.
     */
    public static boolean relationTypeInternallyConsistent(RelationTypeDecision decision) {
        if (decision == null) {
            return false;
        }
        return switch (decision.disposition()) {
            case SELECT -> decision.candidateOrdinal() != null && !decision.schemaGap();
            case SCHEMA_GAP -> decision.candidateOrdinal() == null && decision.schemaGap();
            case ABSTAIN, DONE -> decision.candidateOrdinal() == null && !decision.schemaGap();
            case UNKNOWN -> false;
        };
    }

    /** DONE is meaningful only after at least one relation has already been validated. */
    public static boolean relationTypeAllowedForRound(
            RelationTypeDecision decision, boolean additionalRound) {
        return relationTypeInternallyConsistent(decision)
                && (decision.disposition() != RelationTypeDisposition.DONE || additionalRound);
    }

    /** Parses a comparison against exactly one existing claim. */
    public static Optional<ClaimComparisonDecision> claimComparison(String raw) {
        JsonNode node = objectNode(root(raw), "comparison", "decision", "result");
        if (node == null) {
            return Optional.empty();
        }
        String rawComparison = text(node, "relationship", "comparison", "decision", "outcome");
        ClaimComparison comparison = rawComparison == null ? ClaimComparison.UNCERTAIN
                : switch (rawComparison.strip().toUpperCase(Locale.ROOT)
                        .replace('-', '_').replace(' ', '_')) {
                    case "SAME", "ENTAILS", "SUPPORTS", "EQUIVALENT", "ADD_EVIDENCE" ->
                            ClaimComparison.SAME;
                    case "CONTRADICTS", "CONTRADICTION", "CONFLICTS", "REFUTES",
                            "FLAG_CONTRADICTION" -> ClaimComparison.CONTRADICTS;
                    case "DIFFERENT", "UNRELATED", "DISTINCT", "NO_MATCH" ->
                            ClaimComparison.DIFFERENT;
                    default -> ClaimComparison.UNCERTAIN;
                };
        return Optional.of(new ClaimComparisonDecision(comparison, confidence(node),
                text(node, "reason", "rationale", "explanation")));
    }

    /** SAME is never valid when an engine-known atom field differs. */
    public static boolean claimComparisonInternallyConsistent(ClaimComparisonDecision decision,
                                                              RelationProposal relation,
                                                              ClaimCandidate candidate) {
        if (decision == null) {
            return false;
        }
        if (decision.comparison() != ClaimComparison.SAME || relation == null || candidate == null) {
            return true;
        }
        return !knownMismatch(relation.sourceEntityId(), candidate.subject())
                && !knownMismatch(relation.type(), candidate.predicate())
                && !knownMismatch(relation.targetEntityId(), candidate.object());
    }

    /**
     * Parses the legacy pass 5 response shape. New production callers should supply the exact
     * candidate ballot so a small model can select a 1-based ordinal instead of reproducing an
     * opaque atom key.
     */
    public static Optional<ClaimProposal> claim(String raw, PassContext context,
                                                String propositionId, String relationKey) {
        return claim(raw, context, propositionId, relationKey, List.of());
    }

    /** Parses a pass 5 response and maps an engine-owned candidate ordinal to its exact atom key. */
    public static Optional<ClaimProposal> claim(String raw, PassContext context,
                                                String propositionId, String relationKey,
                                                List<ClaimCandidate> candidates) {
        JsonNode node = objectNode(root(raw), "decision", "claim", "match", "result");
        if (node == null) {
            return Optional.empty();
        }
        ProposalOperation operation =
                ProposalOperation.from(text(node, "operation", "decision", "action"));
        String atomKey = text(node, "matchedAtomKey", "atomKey", "claimKey", "matchedClaim");
        int ordinal = intAt(node, -1, "matchedCandidateOrdinal", "candidateOrdinal",
                "matchedCandidate", "candidateIndex");
        if (atomKey == null && ordinal > 0 && candidates != null && ordinal <= candidates.size()) {
            ClaimCandidate candidate = candidates.get(ordinal - 1);
            atomKey = candidate == null ? null : candidate.atomKey();
        }
        // Attaching to, or contradicting, a claim that the engine cannot resolve is not actionable.
        if ((operation == ProposalOperation.ADD_EVIDENCE
                || operation == ProposalOperation.FLAG_CONTRADICTION) && atomKey == null) {
            operation = ProposalOperation.ABSTAIN;
        }
        return Optional.of(new ClaimProposal(
                propositionId,
                relationKey,
                operation,
                atomKey,
                confidence(node),
                alternatives(node),
                text(node, "reason", "rationale", "explanation"),
                span(node, context)));
    }

    // ---------------------------------------------------------------------------------------
    // tree helpers
    // ---------------------------------------------------------------------------------------

    static JsonNode root(String raw) {
        String json = LlmJsonExtractor.extractJsonObject(raw);
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readTree(json);
        } catch (Exception primary) {
            // A response that opens with a bare array survives fence stripping but not the
            // object-anchored slice; retry on the raw text before giving up.
            try {
                return MAPPER.readTree(raw);
            } catch (Exception ignored) {
                return null;
            }
        }
    }

    /** Finds an array under any of {@code keys}, accepting a bare array or a single object. */
    static JsonNode arrayNode(JsonNode root, String... keys) {
        if (root == null) {
            return null;
        }
        if (root.isArray()) {
            return root;
        }
        for (String key : keys) {
            JsonNode node = root.get(key);
            if (node != null && node.isArray()) {
                return node;
            }
            if (node != null && node.isObject()) {
                return MAPPER.createArrayNode().add(node);
            }
        }
        return null;
    }

    /** Finds an object under any of {@code keys}, accepting a bare object or single-element array. */
    static JsonNode objectNode(JsonNode root, String... keys) {
        if (root == null) {
            return null;
        }
        for (String key : keys) {
            JsonNode node = root.get(key);
            if (node != null && node.isObject()) {
                return node;
            }
            if (node != null && node.isArray() && !node.isEmpty() && node.get(0).isObject()) {
                return node.get(0);
            }
        }
        if (root.isArray()) {
            return !root.isEmpty() && root.get(0).isObject() ? root.get(0) : null;
        }
        // Tolerate a flat response that omits the wrapper entirely.
        return root.isObject() && (root.has("operation") || root.has("speechAct")
                || root.has("selectedEntityId") || root.has("provisionalName")
                || root.has("matchedCandidateOrdinal") || root.has("assertsRelation")
                || root.has("candidateOrdinal") || root.has("comparison")) ? root : null;
    }

    private static JsonNode first(JsonNode node, String... keys) {
        if (node == null) {
            return null;
        }
        for (String key : keys) {
            JsonNode value = node.get(key);
            if (value != null && !value.isNull()) {
                return value;
            }
        }
        return null;
    }

    private static boolean hasAny(JsonNode node, String... keys) {
        if (node == null) {
            return false;
        }
        for (String key : keys) {
            if (node.has(key)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasUnexpectedFields(JsonNode node, String... allowedKeys) {
        if (node == null || !node.isObject()) {
            return true;
        }
        List<String> allowed = List.of(allowedKeys);
        Iterator<String> names = node.fieldNames();
        while (names.hasNext()) {
            if (!allowed.contains(names.next())) {
                return true;
            }
        }
        return false;
    }

    private static boolean knownMismatch(String left, String right) {
        if (left == null || left.isBlank() || right == null || right.isBlank()) {
            return false;
        }
        String normalizedLeft = left.strip().toUpperCase(Locale.ROOT)
                .replace('-', '_').replace(' ', '_');
        String normalizedRight = right.strip().toUpperCase(Locale.ROOT)
                .replace('-', '_').replace(' ', '_');
        return !normalizedLeft.equals(normalizedRight);
    }

    private static boolean booleanAt(JsonNode node, boolean fallback, String... keys) {
        JsonNode value = first(node, keys);
        if (value == null) {
            return fallback;
        }
        if (value.isBoolean()) {
            return value.asBoolean();
        }
        if (value.isValueNode()) {
            String normalized = value.asText().strip().toLowerCase(Locale.ROOT);
            if ("true".equals(normalized) || "yes".equals(normalized)) {
                return true;
            }
            if ("false".equals(normalized) || "no".equals(normalized)) {
                return false;
            }
        }
        return fallback;
    }

    static String text(JsonNode node, String... keys) {
        if (node == null) {
            return null;
        }
        for (String key : keys) {
            JsonNode value = node.get(key);
            if (value == null || value.isNull() || !value.isValueNode()) {
                continue;
            }
            String raw = value.asText().trim();
            if (raw.isEmpty() || "null".equalsIgnoreCase(raw) || "none".equalsIgnoreCase(raw)
                    || "n/a".equalsIgnoreCase(raw)) {
                continue;
            }
            return raw;
        }
        return null;
    }

    static double doubleAt(JsonNode node, double fallback, String... keys) {
        if (node == null) {
            return fallback;
        }
        for (String key : keys) {
            JsonNode value = node.get(key);
            if (value == null || value.isNull()) {
                continue;
            }
            if (value.isNumber()) {
                return clamp(value.asDouble());
            }
            if (value.isTextual()) {
                try {
                    return clamp(Double.parseDouble(value.asText().trim()));
                } catch (NumberFormatException ignored) {
                    // fall through to the next key
                }
            }
        }
        return fallback;
    }

    private static double confidence(JsonNode node) {
        return doubleAt(node, 0.5d, "confidence", "score", "probability");
    }

    private static double clamp(double value) {
        if (Double.isNaN(value)) {
            return 0.0d;
        }
        // Models often answer on a 0-100 scale despite the 0-1 instruction.
        double scaled = value > 1.0d && value <= 100.0d ? value / 100.0d : value;
        return Math.max(0.0d, Math.min(1.0d, scaled));
    }

    static List<Alternative> alternatives(JsonNode node) {
        JsonNode array = node == null ? null : node.get("alternatives");
        if (array == null && node != null) {
            array = node.get("rejected");
        }
        if (array == null || !array.isArray()) {
            return List.of();
        }
        List<Alternative> out = new ArrayList<>();
        for (JsonNode item : array) {
            if (item.isTextual()) {
                out.add(new Alternative(item.asText().trim(), 0.0d, null));
                continue;
            }
            String id = text(item, "candidateId", "id", "entityId", "type", "atomKey");
            if (id == null) {
                continue;
            }
            out.add(new Alternative(id, doubleAt(item, 0.0d, "score", "confidence"),
                    text(item, "reason", "rationale", "why")));
        }
        return List.copyOf(out);
    }

    static Map<String, String> qualifiers(JsonNode node) {
        JsonNode qualifiers = node == null ? null : node.get("qualifiers");
        if (qualifiers == null || !qualifiers.isObject()) {
            return Map.of();
        }
        Map<String, String> out = new LinkedHashMap<>();
        Iterator<Map.Entry<String, JsonNode>> fields = qualifiers.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            JsonNode value = field.getValue();
            if (value == null || value.isNull()) {
                continue;
            }
            String rendered = value.isValueNode() ? value.asText().trim() : value.toString();
            if (!rendered.isEmpty() && !"null".equalsIgnoreCase(rendered)) {
                out.put(field.getKey(), rendered);
            }
        }
        return Map.copyOf(out);
    }

    static EvidenceSpan span(JsonNode node, PassContext context) {
        if (node == null) {
            return null;
        }
        JsonNode evidence = node.get("evidence");
        if (evidence == null || !evidence.isObject()) {
            // Some models inline the quote instead of nesting an evidence object.
            String inline = text(node, "quote", "evidenceQuote", "sourceText");
            if (inline == null) {
                return null;
            }
            return new EvidenceSpan(chunkId(context), -1, -1, inline, EvidenceRole.DIRECT_SUPPORT);
        }
        String quote = text(evidence, "quote", "text", "span", "excerpt");
        if (quote == null) {
            return null;
        }
        return new EvidenceSpan(
                text(evidence, "chunkId", "chunk") == null
                        ? chunkId(context) : text(evidence, "chunkId", "chunk"),
                intAt(evidence, -1, "start", "startOffset", "begin"),
                intAt(evidence, -1, "end", "endOffset", "finish"),
                quote,
                EvidenceRole.from(text(evidence, "role", "kind")));
    }

    private static int intAt(JsonNode node, int fallback, String... keys) {
        for (String key : keys) {
            JsonNode value = node.get(key);
            if (value != null && value.isNumber()) {
                return value.asInt();
            }
            if (value != null && value.isTextual()) {
                try {
                    return Integer.parseInt(value.asText().trim());
                } catch (NumberFormatException ignored) {
                    // fall through to the next key
                }
            }
        }
        return fallback;
    }

    private static String chunkId(PassContext context) {
        return context == null ? null : context.chunkId();
    }

    private static String defaultPropositionId(PassContext context, int index) {
        String chunk = context == null || context.chunkId() == null ? "chunk" : context.chunkId();
        return chunk + ":p" + index;
    }
}
