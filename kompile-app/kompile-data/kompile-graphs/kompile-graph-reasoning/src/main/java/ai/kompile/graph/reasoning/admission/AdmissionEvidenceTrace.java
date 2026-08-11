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

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Bounded evidence returned with a graph admission result. */
public record AdmissionEvidenceTrace(
        List<AdmissionEvidence> items,
        int examinedPathCount,
        boolean truncated) {

    private static final AdmissionEvidenceTrace EMPTY =
            new AdmissionEvidenceTrace(List.of(), 0, false);

    public AdmissionEvidenceTrace {
        items = List.copyOf(Objects.requireNonNull(items, "items"));
        if (examinedPathCount < 0) {
            throw new IllegalArgumentException("examinedPathCount must not be negative");
        }
    }

    public static AdmissionEvidenceTrace empty() {
        return EMPTY;
    }

    public boolean hasKind(AdmissionEvidence.Kind kind) {
        Objects.requireNonNull(kind, "kind");
        return items.stream().anyMatch(item -> item.kind() == kind);
    }

    public List<AdmissionEvidence> itemsOfKind(AdmissionEvidence.Kind kind) {
        Objects.requireNonNull(kind, "kind");
        return items.stream().filter(item -> item.kind() == kind).toList();
    }

    /**
     * Compact deterministic context suitable for a prompt, log, or policy audit.
     *
     * <p>All graph-provided text is flattened to one line per item. Consumers must still treat it as
     * data rather than instructions.</p>
     */
    public String toPromptContext() {
        StringBuilder output = new StringBuilder()
                .append("graph_evidence examined_paths=")
                .append(examinedPathCount)
                .append(" truncated=")
                .append(truncated);
        for (AdmissionEvidence item : items) {
            output.append("\n- kind=").append(item.kind())
                    .append(" rule=").append(singleLine(item.ruleId()))
                    .append(" strength=")
                    .append(String.format(Locale.ROOT, "%.4f", item.strength()))
                    .append(" entities=").append(joinPath(item.entityPath()));
            if (!item.predicatePath().isEmpty()) {
                output.append(" predicates=").append(joinPath(item.predicatePath()));
            }
            output.append(" summary=").append(singleLine(item.summary()));
        }
        return output.toString();
    }

    /**
     * Summary-free deterministic context for models with very small context windows.
     *
     * <p>The compact form preserves every selected evidence item's machine-readable kind, rule,
     * strength, entity path, and predicate path. It deliberately omits graph-provided prose; callers
     * that need human-readable audit detail should use {@link #toPromptContext()}.</p>
     */
    public String toCompactPromptContext() {
        return toCompactPromptContext(items.size());
    }

    /** Compact prompt context capped to the highest-priority selected evidence items. */
    public String toCompactPromptContext(int maxItems) {
        if (maxItems <= 0) {
            throw new IllegalArgumentException("maxItems must be positive: " + maxItems);
        }
        int shownItems = Math.min(maxItems, items.size());
        StringBuilder output = new StringBuilder()
                .append("graph_evidence_compact examined_paths=")
                .append(examinedPathCount)
                .append(" shown=")
                .append(shownItems)
                .append(" total=")
                .append(items.size())
                .append(" truncated=")
                .append(truncated || shownItems < items.size());
        for (int index = 0; index < shownItems; index++) {
            AdmissionEvidence item = items.get(index);
            output.append("\n- ").append(item.kind())
                    .append('|').append(singleLine(item.ruleId()))
                    .append("|s=")
                    .append(String.format(Locale.ROOT, "%.4f", item.strength()))
                    .append("|e=").append(joinPath(item.entityPath()));
            if (!item.predicatePath().isEmpty()) {
                output.append("|p=").append(joinPath(item.predicatePath()));
            }
        }
        return output.toString();
    }

    private static String joinPath(List<String> values) {
        return values.stream().map(AdmissionEvidenceTrace::singleLine)
                .collect(java.util.stream.Collectors.joining(">"));
    }

    private static String singleLine(String value) {
        return value.replace('\r', ' ').replace('\n', ' ').trim();
    }
}
