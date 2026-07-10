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

package ai.kompile.process.discovery.mining;

import ai.kompile.process.discovery.ProcessSuggestion;
import ai.kompile.process.discovery.ProcessSuggestion.SuggestedPhase;
import ai.kompile.process.discovery.ProcessSuggestion.SuggestedStep;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Diffs two generations of the SAME process (linked by {@code processKey} via
 * {@link ProcessIdentityResolver}) into human-readable drift lines — what changed since the
 * predecessor was mined: steps added/removed, control-flow rewires ({@code dependsOn}),
 * performer changes ({@code roleBinding}), choice-routing changes ({@code conditionLabel} — which
 * carries both the observed branch shares and any mined guard), and the confidence delta.
 *
 * <p>Pure and deliberately DESCRIPTIVE: it reports differences between what was mined then and
 * now; deciding whether a difference is drift or noise stays with the reader (the shares and
 * counts ride along so they can).
 */
public final class ProcessDriftAnalyzer {

    /** Confidence must move at least this much before the delta is worth a line. */
    static final double CONFIDENCE_DELTA_FLOOR = 0.1;

    private ProcessDriftAnalyzer() {
    }

    /**
     * Drift lines from {@code previous} to {@code fresh}; empty when the generations agree.
     * Order: structural (steps) → flow (dependsOn) → people (roles) → routing (conditions) →
     * confidence.
     */
    public static List<String> diff(ProcessSuggestion previous, ProcessSuggestion fresh) {
        List<String> lines = new ArrayList<>();
        if (previous == null || fresh == null) {
            return lines;
        }
        Map<String, SuggestedStep> before = stepsByName(previous);
        Map<String, SuggestedStep> after = stepsByName(fresh);

        Set<String> added = new LinkedHashSet<>(after.keySet());
        added.removeAll(before.keySet());
        for (String name : added) {
            lines.add("step added: '" + name + "'");
        }
        Set<String> removed = new LinkedHashSet<>(before.keySet());
        removed.removeAll(after.keySet());
        for (String name : removed) {
            lines.add("step removed: '" + name + "'");
        }

        Set<String> shared = new LinkedHashSet<>(before.keySet());
        shared.retainAll(after.keySet());
        for (String name : shared) {
            SuggestedStep b = before.get(name);
            SuggestedStep a = after.get(name);

            Set<String> depsBefore = new LinkedHashSet<>(b.getDependsOn() != null ? b.getDependsOn() : List.of());
            Set<String> depsAfter = new LinkedHashSet<>(a.getDependsOn() != null ? a.getDependsOn() : List.of());
            if (!depsBefore.equals(depsAfter)) {
                lines.add(String.format("'%s' now waits for %s (was %s)",
                        name, describeSet(depsAfter), describeSet(depsBefore)));
            }

            if (bound(b.getRoleBinding()) != null || bound(a.getRoleBinding()) != null) {
                if (!Objects.equals(bound(b.getRoleBinding()), bound(a.getRoleBinding()))) {
                    lines.add(String.format("performer of '%s': %s → %s",
                            name, describeRole(b), describeRole(a)));
                }
            }

            if (!Objects.equals(b.getConditionLabel(), a.getConditionLabel())) {
                if (a.getConditionLabel() == null) {
                    lines.add("'" + name + "' is no longer behind a choice");
                } else if (b.getConditionLabel() == null) {
                    lines.add("'" + name + "' is now behind a choice (" + a.getConditionLabel() + ")");
                } else {
                    lines.add("routing of '" + name + "' changed: " + a.getConditionLabel()
                            + " (was: " + b.getConditionLabel() + ")");
                }
            }
        }

        double delta = fresh.getConfidence() - previous.getConfidence();
        if (Math.abs(delta) >= CONFIDENCE_DELTA_FLOOR) {
            lines.add(String.format(Locale.ROOT, "confidence %s %.2f → %.2f",
                    delta > 0 ? "rose" : "fell", previous.getConfidence(), fresh.getConfidence()));
        }
        return lines;
    }

    private static Map<String, SuggestedStep> stepsByName(ProcessSuggestion suggestion) {
        Map<String, SuggestedStep> byName = new LinkedHashMap<>();
        if (suggestion.getPhases() == null) {
            return byName;
        }
        for (SuggestedPhase phase : suggestion.getPhases()) {
            if (phase.getSteps() == null) {
                continue;
            }
            for (SuggestedStep step : phase.getSteps()) {
                if (step.getName() != null) {
                    byName.putIfAbsent(step.getName(), step);
                }
            }
        }
        return byName;
    }

    /** UNASSIGNED and null are the same "no binding" state — never report a drift between them. */
    private static String bound(String roleBinding) {
        return roleBinding == null || "UNASSIGNED".equals(roleBinding) ? null : roleBinding;
    }

    private static String describeRole(SuggestedStep step) {
        String role = bound(step.getRoleBinding());
        if (role == null) {
            return "(unassigned)";
        }
        return "OBSERVED".equals(step.getRoleSource()) ? role : role + " (" + sourceOf(step) + ")";
    }

    private static String sourceOf(SuggestedStep step) {
        return step.getRoleSource() != null ? step.getRoleSource().toLowerCase(Locale.ROOT) : "unknown";
    }

    private static String describeSet(Set<String> names) {
        return names.isEmpty() ? "nothing" : "'" + String.join("', '", names) + "'";
    }
}
