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

package ai.kompile.crawl.graph.passes;

import ai.kompile.core.crawl.graph.GraphExtractionConfig;
import ai.kompile.core.crawl.graph.GraphExtractionValidationPolicy;
import ai.kompile.core.graphrag.format.GraphExtractionValidator;
import ai.kompile.core.graphrag.format.GraphExtractionValidator.RelationSignature;
import ai.kompile.core.graphrag.model.schema.GraphSchema;
import ai.kompile.core.graphrag.model.schema.RelationshipType;
import ai.kompile.core.graphrag.passes.ExtractionCandidates.RelationCandidate;
import ai.kompile.core.graphrag.passes.PassContext;
import ai.kompile.core.graphrag.passes.RelationCandidateProvider;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Offers the relation types a resolved endpoint pair is permitted to take.
 *
 * <p>The admissible set comes from the same two places validation reads: the project's schema
 * relationship types and the {@code (SOURCE)-[:TYPE]->(TARGET)} signatures configured on the schema
 * and validation policy. Because both sides parse the signature the same way
 * ({@link GraphExtractionValidator#parseRelationPattern}), a type this provider offers for a pair
 * is never one the validator would later reject for that pair — the model is not set up to fail.</p>
 *
 * <p>Types with no signature are unconstrained and are always offered; types with signatures are
 * offered only when one of them admits the endpoint pair. When the endpoint types are unknown we
 * cannot discriminate, so everything is offered and the model decides.</p>
 */
public final class SchemaRelationCandidateProvider implements RelationCandidateProvider {

    private static final double SIGNATURE_MATCH_SCORE = 1.0;
    private static final double UNCONSTRAINED_SCORE = 0.7;

    /** type -> description, in declaration order. */
    private final Map<String, String> types = new LinkedHashMap<>();

    /** type -> audited lexical forms supplied by the schema. */
    private final Map<String, List<String>> aliases = new LinkedHashMap<>();

    /** type -> signatures declared for it. */
    private final Map<String, List<RelationSignature>> signatures = new LinkedHashMap<>();

    public SchemaRelationCandidateProvider(GraphSchema schema,
                                           List<String> configuredTypes,
                                           List<String> patternExpressions) {
        this(schema, configuredTypes, patternExpressions, true);
    }

    /**
     * @param enforceSignatures when false, signatures still declare their relation types but do not
     *                          constrain which endpoint pairs may take them. Used when the project
     *                          has turned the pattern validator off: retrieval must not withhold a
     *                          type that validation would happily accept.
     */
    public SchemaRelationCandidateProvider(GraphSchema schema,
                                           List<String> configuredTypes,
                                           List<String> patternExpressions,
                                           boolean enforceSignatures) {
        if (schema != null && schema.getRelationshipTypes() != null) {
            for (RelationshipType type : schema.getRelationshipTypes()) {
                if (type == null) {
                    continue;
                }
                put(type.getType(), type.getDescription(), type.getAliases());
            }
        }
        if (configuredTypes != null) {
            for (String type : configuredTypes) {
                put(type, null);
            }
        }

        Set<String> expressions = new LinkedHashSet<>();
        if (schema != null && schema.getPatterns() != null) {
            expressions.addAll(schema.getPatterns());
        }
        if (patternExpressions != null) {
            expressions.addAll(patternExpressions);
        }
        for (String expression : expressions) {
            GraphExtractionValidator.parseRelationPattern(expression).ifPresent(signature -> {
                if (enforceSignatures) {
                    signatures.computeIfAbsent(signature.relationType(), key -> new ArrayList<>())
                            .add(signature);
                }
                // A signature is itself a declaration that the type exists, even when the schema's
                // relationshipTypes list is empty (a pattern-only project).
                types.putIfAbsent(signature.relationType(), null);
            });
        }
    }

    /** Builds a provider from a crawl config plus its resolved schema and validation policy. */
    public static SchemaRelationCandidateProvider from(GraphExtractionConfig config,
                                                       GraphSchema schema,
                                                       GraphExtractionValidationPolicy policy) {
        return new SchemaRelationCandidateProvider(
                schema,
                config != null ? config.getRelationshipTypes() : null,
                policy != null ? policy.effectiveRelationPatterns() : null,
                policy == null || policy.isValidatorEnabled(
                        GraphExtractionValidationPolicy.RELATION_SCHEMA_PATTERN));
    }

    /** True when no type vocabulary is configured at all — the pass has nothing to offer. */
    public boolean isEmpty() {
        return types.isEmpty();
    }

    @Override
    public List<RelationCandidate> candidatesFor(String sourceType, String targetType,
                                                 PassContext context, int limit) {
        if (types.isEmpty() || limit <= 0) {
            return List.of();
        }
        String source = normalize(sourceType);
        String target = normalize(targetType);

        List<RelationCandidate> matched = new ArrayList<>();
        List<RelationCandidate> unconstrained = new ArrayList<>();
        for (Map.Entry<String, String> entry : types.entrySet()) {
            String type = entry.getKey();
            List<RelationSignature> declared = signatures.get(type);
            if (declared == null || declared.isEmpty()) {
                unconstrained.add(new RelationCandidate(
                        type, entry.getValue(), List.of(), List.of(), UNCONSTRAINED_SCORE,
                        aliases.getOrDefault(type, List.of())));
                continue;
            }
            List<String> domains = new ArrayList<>();
            List<String> ranges = new ArrayList<>();
            boolean admits = false;
            for (RelationSignature signature : declared) {
                if (!domains.contains(signature.sourceType())) {
                    domains.add(signature.sourceType());
                }
                if (!ranges.contains(signature.targetType())) {
                    ranges.add(signature.targetType());
                }
                boolean sourceOk = source == null || source.equals(signature.sourceType());
                boolean targetOk = target == null || target.equals(signature.targetType());
                admits |= sourceOk && targetOk;
            }
            if (admits) {
                matched.add(new RelationCandidate(
                        type, entry.getValue(), domains, ranges, SIGNATURE_MATCH_SCORE,
                        aliases.getOrDefault(type, List.of())));
            }
        }

        List<RelationCandidate> ordered = new ArrayList<>(matched);
        ordered.addAll(unconstrained);
        return ordered.size() > limit
                ? List.copyOf(ordered.subList(0, limit))
                : List.copyOf(ordered);
    }

    private void put(String type, String description) {
        put(type, description, List.of());
    }

    private void put(String type, String description, List<String> lexicalAliases) {
        String normalized = normalize(type);
        if (normalized == null) {
            return;
        }
        String existing = types.get(normalized);
        if (existing == null || existing.isBlank()) {
            types.put(normalized, description == null || description.isBlank() ? null : description);
        }
        if (lexicalAliases != null && !lexicalAliases.isEmpty()) {
            LinkedHashSet<String> merged = new LinkedHashSet<>(
                    aliases.getOrDefault(normalized, List.of()));
            lexicalAliases.stream().filter(value -> value != null && !value.isBlank())
                    .map(String::strip).forEach(merged::add);
            aliases.put(normalized, List.copyOf(merged));
        }
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim().toUpperCase(Locale.ROOT);
    }
}
