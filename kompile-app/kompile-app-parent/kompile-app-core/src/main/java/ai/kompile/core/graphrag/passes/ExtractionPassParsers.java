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
import java.util.Map;
import java.util.Optional;

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

    private ExtractionPassParsers() {
    }

    /** Parses a pass 1 response, capping the number of propositions returned. */
    public static List<PropositionProposal> propositions(String raw, PassContext context,
                                                         int limit) {
        JsonNode array = arrayNode(root(raw), "propositions", "results", "items");
        if (array == null) {
            return List.of();
        }
        List<PropositionProposal> out = new ArrayList<>();
        int index = 0;
        for (JsonNode node : array) {
            if (out.size() >= Math.max(0, limit)) {
                break;
            }
            index++;
            String text = text(node, "text", "statement", "proposition");
            String subject = text(node, "subject", "source", "head");
            String object = text(node, "object", "target", "tail");
            if (text == null && subject == null) {
                continue;
            }
            String id = text(node, "id", "propositionId");
            out.add(new PropositionProposal(
                    id == null ? defaultPropositionId(context, index) : id,
                    text,
                    subject,
                    text(node, "predicate", "relation", "verb"),
                    object,
                    Polarity.from(text(node, "polarity", "negation")),
                    Modality.from(text(node, "modality", "mood")),
                    text(node, "timeExpression", "time", "occurredAt", "when"),
                    text(node, "condition", "scope"),
                    text(node, "attributedTo", "holder", "speaker", "attribution"),
                    span(node, context)));
        }
        return List.copyOf(out);
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
            out.add(new MentionProposal(
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
                    span(node, context)));
        }
        return List.copyOf(out);
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

    /** Parses a pass 5 response for one relation proposal. */
    public static Optional<ClaimProposal> claim(String raw, PassContext context,
                                                String propositionId, String relationKey) {
        JsonNode node = objectNode(root(raw), "decision", "claim", "match", "result");
        if (node == null) {
            return Optional.empty();
        }
        ProposalOperation operation =
                ProposalOperation.from(text(node, "operation", "decision", "action"));
        String atomKey = text(node, "matchedAtomKey", "atomKey", "claimKey", "matchedClaim");
        // Attaching to, or contradicting, a claim that was never named is not actionable.
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
        return root.isObject() && (root.has("operation") || root.has("speechAct")) ? root : null;
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
