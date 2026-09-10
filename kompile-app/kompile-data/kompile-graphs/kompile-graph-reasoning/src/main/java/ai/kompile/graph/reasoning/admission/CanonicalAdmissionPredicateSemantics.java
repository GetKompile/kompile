/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.graph.reasoning.admission;

import ai.kompile.graph.reasoning.model.GraphEntity;
import ai.kompile.graph.reasoning.model.GraphRelation;

import java.util.Collection;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Direction-aware semantics for canonical graph predicates used by admission.
 *
 * <p>Callers can override a relation with {@code admissionEvidenceKind}, {@code admissionRuleId},
 * and {@code admissionSummary} attributes. The built-ins cover general conflict and identity
 * predicates plus the status, version, period, exception, override, and action vocabulary present in
 * the reference golden graph.</p>
 */
public final class CanonicalAdmissionPredicateSemantics implements AdmissionPredicateSemantics {

    private static final Set<String> CONFLICT_TYPES = Set.of(
            "CONTRADICTS", "CONFLICTS", "REFUTES", "DISAGREES", "OPPOSES");
    private static final Set<String> IDENTITY_SEPARATION_TYPES = Set.of(
            "NOT_SAME_AS", "NOT_SAME", "DIFFERENT_FROM");
    private static final Set<String> CAUTION_TYPES = Set.of(
            "MISSING_PERIOD", "HAS_ISSUE", "HAS_RISK", "REFERENCE_ONLY_FOR");
    private static final Set<String> REQUIREMENT_TYPES = Set.of(
            "REQUIRES_ACTION", "REQUIRES_ESCALATION_TO", "HAS_THRESHOLD",
            "USABLE_FOR_PERIOD", "HAS_RETENTION",
            "HAS_TAX_BASIS", "HAS_VALUE_SCALE", "USES_CURRENCY", "USES_FX_RATE_SET",
            "USES_SKU_MAPPING", "COVERS_PERIOD");
    private static final Set<String> OVERRIDE_TYPES = Set.of(
            "EXCEPTION_TO", "OVERRIDES", "PERMITS_ACTION");
    private static final Set<String> AFFIRMING_TYPES = Set.of(
            "APPROVED_BY", "VALIDATES", "CORRECTS_ISSUE");

    @Override
    public Assessment assess(GraphRelation relation,
                             GraphEntity source,
                             GraphEntity target,
                             String focusEntityId) {
        Objects.requireNonNull(relation, "relation");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(focusEntityId, "focusEntityId");

        Assessment override = attributeOverride(relation);
        if (override != null) {
            return override;
        }

        String type = normalize(relation.type());
        boolean focusIsSource = focusEntityId.equals(relation.sourceId());
        if (CONFLICT_TYPES.contains(type)) {
            return assessment(AdmissionEvidence.Kind.CONFLICT, "canonical.conflict",
                    "the relation supplies contradictory evidence");
        }
        if (IDENTITY_SEPARATION_TYPES.contains(type)) {
            return assessment(AdmissionEvidence.Kind.IDENTITY_CONSTRAINT,
                    "canonical.identity-separation",
                    "the endpoints are explicitly distinct identities");
        }
        if ("HAS_STATUS".equals(type)) {
            if (focusEntityId.equals(relation.targetId())) {
                return assessment(AdmissionEvidence.Kind.CONTEXT_RELATION,
                        "canonical.status.assignment",
                        "the focused status concept is assigned to another entity");
            }
            return statusAssessment(target);
        }
        if ("SUPERSEDES".equals(type)) {
            if (!focusIsSource) {
                return assessment(AdmissionEvidence.Kind.CAUTION,
                        "canonical.version.superseded",
                        "the focused version is superseded by a newer version");
            }
            return assessment(AdmissionEvidence.Kind.AFFIRMING_RELATION,
                    "canonical.version.supersedes",
                    "the focused version supersedes an older version");
        }
        if ("GOVERNED_BY".equals(type) || "GOVERNS".equals(type)) {
            boolean focusIsGoverned = "GOVERNED_BY".equals(type)
                    ? focusIsSource
                    : !focusIsSource;
            return assessment(
                    AdmissionEvidence.Kind.REQUIREMENT,
                    focusIsGoverned ? "canonical.governed-by" : "canonical.governs",
                    focusIsGoverned
                            ? "the focused entity is governed by an explicit policy"
                            : "the focused policy governs the related entity");
        }
        if (CAUTION_TYPES.contains(type)) {
            if (!focusIsSource) {
                return assessment(AdmissionEvidence.Kind.CONTEXT_RELATION,
                        "canonical." + ruleName(type) + ".subject",
                        "the focused concept is the object of a caution on another entity");
            }
            return assessment(AdmissionEvidence.Kind.CAUTION,
                    "canonical." + ruleName(type),
                    cautionSummary(type));
        }
        if (REQUIREMENT_TYPES.contains(type)) {
            if (!focusIsSource) {
                return assessment(AdmissionEvidence.Kind.CONTEXT_RELATION,
                        "canonical." + ruleName(type) + ".target",
                        "the focused concept is the target of another entity's requirement");
            }
            return assessment(AdmissionEvidence.Kind.REQUIREMENT,
                    "canonical." + ruleName(type),
                    requirementSummary(type));
        }
        if (OVERRIDE_TYPES.contains(type)) {
            return assessment(AdmissionEvidence.Kind.OVERRIDE,
                    focusIsSource
                            ? "canonical." + ruleName(type)
                            : "canonical." + ruleName(type) + ".target",
                    focusIsSource
                            ? "an exception or override changes the default policy path"
                            : "the focused entity is the target of an exception or override");
        }
        if (AFFIRMING_TYPES.contains(type)) {
            if (!focusIsSource && !"VALIDATES".equals(type)) {
                return assessment(AdmissionEvidence.Kind.CONTEXT_RELATION,
                        "canonical." + ruleName(type) + ".actor-or-object",
                        "the focused concept participates in another entity's validation");
            }
            return assessment(AdmissionEvidence.Kind.AFFIRMING_RELATION,
                    "canonical." + ruleName(type),
                    "the relation supplies affirmative validation evidence");
        }
        return assessment(AdmissionEvidence.Kind.CONTEXT_RELATION,
                "canonical.context",
                "the relation supplies structural context");
    }

    private static Assessment statusAssessment(GraphEntity status) {
        String text = searchableText(status);
        if (containsAny(text,
                "DO NOT USE", "DO NOT TRUST", "IGNORE", "UNRELIABLE",
                "INVALID", "REJECTED", "OBSOLETE")) {
            return assessment(AdmissionEvidence.Kind.CAUTION,
                    "canonical.status.not-usable",
                    "the status marks the focused entity as ignored or not usable");
        }
        if (containsAny(text, "REFERENCE ONLY", "REFERENCE-ONLY")) {
            return assessment(AdmissionEvidence.Kind.CAUTION,
                    "canonical.status.reference-only",
                    "the status limits the focused entity to reference use");
        }
        if (containsAny(text, "AUTHORITATIVE", "APPROVED", "CURRENT", "RIGHT ONE")) {
            return assessment(AdmissionEvidence.Kind.AFFIRMING_RELATION,
                    "canonical.status.authoritative",
                    "the status marks the focused entity as authoritative");
        }
        return assessment(AdmissionEvidence.Kind.REQUIREMENT,
                "canonical.status.unclassified",
                "the focused entity has an operational status that requires interpretation");
    }

    private static String cautionSummary(String type) {
        return switch (type) {
            case "MISSING_PERIOD" -> "required period coverage is explicitly missing";
            case "HAS_ISSUE" -> "the focused entity has a recorded quality issue";
            case "HAS_RISK" -> "the focused entity has a recorded risk";
            case "REFERENCE_ONLY_FOR" -> "the focused entity is restricted to reference use";
            default -> "the relation supplies cautionary domain evidence";
        };
    }

    private static String requirementSummary(String type) {
        return switch (type) {
            case "USABLE_FOR_PERIOD" -> "use is constrained to the stated reporting period";
            case "REQUIRES_ACTION" -> "the policy requires the stated action";
            case "REQUIRES_ESCALATION_TO" -> "the policy requires escalation to the stated target";
            case "HAS_THRESHOLD" -> "the policy is conditioned on the stated threshold";
            case "GOVERNED_BY", "GOVERNS" -> "the focused entity is governed by an explicit policy";
            case "HAS_RETENTION" -> "records are subject to the stated retention requirement";
            case "HAS_TAX_BASIS", "HAS_VALUE_SCALE", "USES_CURRENCY",
                    "USES_FX_RATE_SET", "USES_SKU_MAPPING", "COVERS_PERIOD" ->
                    "the relation constrains financial interpretation or normalization";
            default -> "the relation supplies an operational requirement";
        };
    }

    private static Assessment attributeOverride(GraphRelation relation) {
        String rawKind = firstText(
                relation.stringAttribute("admissionEvidenceKind"),
                relation.stringAttribute("admissionSemantics"));
        if (rawKind == null) {
            return null;
        }
        AdmissionEvidence.Kind kind = parseKind(rawKind);
        String ruleId = firstText(relation.stringAttribute("admissionRuleId"),
                "attribute." + normalize(relation.type()).toLowerCase(Locale.ROOT));
        String summary = firstText(relation.stringAttribute("admissionSummary"),
                "relation semantics were supplied explicitly by graph metadata");
        return assessment(kind, ruleId, summary);
    }

    private static AdmissionEvidence.Kind parseKind(String raw) {
        String normalized = normalize(raw);
        return switch (normalized) {
            case "SUPPORT", "SUPPORTING", "AFFIRMING" -> AdmissionEvidence.Kind.AFFIRMING_RELATION;
            case "WARNING", "BLOCKING" -> AdmissionEvidence.Kind.CAUTION;
            case "CONSTRAINT" -> AdmissionEvidence.Kind.REQUIREMENT;
            case "IDENTITY_SEPARATION" -> AdmissionEvidence.Kind.IDENTITY_CONSTRAINT;
            case "CONTEXT" -> AdmissionEvidence.Kind.CONTEXT_RELATION;
            default -> {
                try {
                    yield AdmissionEvidence.Kind.valueOf(normalized);
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException(
                            "unsupported admissionEvidenceKind '" + raw + "'", e);
                }
            }
        };
    }

    private static String searchableText(GraphEntity entity) {
        StringBuilder text = new StringBuilder()
                .append(entity.id()).append(' ')
                .append(entity.type()).append(' ')
                .append(entity.label());
        for (String key : Set.of("acceptedNames", "aliases", "status")) {
            Object value = entity.attributes().get(key);
            if (value instanceof Collection<?> values) {
                values.forEach(item -> text.append(' ').append(item));
            } else if (value != null) {
                text.append(' ').append(value);
            }
        }
        return normalize(text.toString()).replace('_', ' ').replace('-', ' ');
    }

    private static boolean containsAny(String text, String... values) {
        for (String value : values) {
            if (text.contains(value)) {
                return true;
            }
        }
        return false;
    }

    private static Assessment assessment(AdmissionEvidence.Kind kind,
                                         String ruleId,
                                         String summary) {
        return new Assessment(kind, ruleId, summary);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private static String ruleName(String normalizedType) {
        return normalizedType.toLowerCase(Locale.ROOT).replace('_', '-');
    }

    private static String firstText(String first, String fallback) {
        return first == null || first.isBlank() ? fallback : first.trim();
    }
}
