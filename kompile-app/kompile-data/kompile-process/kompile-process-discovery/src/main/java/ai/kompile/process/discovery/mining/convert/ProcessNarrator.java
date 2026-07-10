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

package ai.kompile.process.discovery.mining.convert;

import ai.kompile.process.discovery.ProcessSuggestion;
import ai.kompile.process.discovery.mining.extract.ActivityClassifier;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic business-process prose from a mined suggestion — the narrative every suggestion
 * carries even when no LLM is configured, and the grounded fallback when LLM narration fails
 * validation. Also derives the business-sounding display name ("Purchase Request → Invoice
 * process") from the flow's business endpoints.
 *
 * <p>Everything here reads ONLY the mined structure (steps, dependencies, roles, cases, evidence);
 * nothing is invented, which is exactly the property the LLM narration is validated against.</p>
 */
public final class ProcessNarrator {

    public static final String TEMPLATE_SOURCE = "TEMPLATE";

    private ProcessNarrator() {
    }

    /**
     * A business-sounding display name from the flow's first and last business activity
     * (communication carriers skipped so "Email Message → Email Message" can never be a name),
     * e.g. {@code "Purchase Request → Invoice process"}. Falls back to the first/last step of any
     * kind, then to "Mined process".
     */
    public static String businessName(ProcessSuggestion suggestion) {
        List<String> steps = orderedStepNames(suggestion);
        if (steps.isEmpty()) {
            return "Mined process";
        }
        List<String> business = steps.stream()
                .filter(s -> !ActivityClassifier.isCommunicationScaffoldLabel(s))
                .toList();
        List<String> basis = business.isEmpty() ? steps : business;
        String first = basis.get(0);
        String last = basis.get(basis.size() - 1);
        return first.equals(last) ? first + " process" : first + " → " + last + " process";
    }

    /**
     * A coherent multi-sentence description of the mined process: what it is, how it flows, who
     * acts, what was entailed beyond direct observation, and any caveats — in that order.
     */
    public static String narrate(ProcessSuggestion suggestion) {
        List<String> steps = orderedStepNames(suggestion);
        if (steps.isEmpty()) {
            return "No business steps were mined for this process.";
        }
        StringBuilder sb = new StringBuilder();

        // What it is.
        int cases = caseCount(suggestion);
        sb.append("This process was discovered from ").append(cases == 1 ? "one case" : cases + " cases")
                .append(" in the knowledge graph")
                .append(String.format(Locale.ROOT, " with %.0f%% confidence", suggestion.getConfidence() * 100))
                .append(". ");

        // How it flows.
        sb.append("It runs ").append(joinFlow(steps)).append(". ");
        List<String> dependencyClauses = dependencyClauses(suggestion);
        if (!dependencyClauses.isEmpty()) {
            sb.append(String.join(" ", dependencyClauses)).append(" ");
        }

        // Who acts.
        Set<String> roles = roles(suggestion);
        if (!roles.isEmpty()) {
            sb.append("Roles involved: ").append(String.join(", ", roles)).append(". ");
        }

        // What the reasoner added beyond direct observation.
        List<String> entailed = evidenceDescriptions(suggestion, "ENTAILED", 3);
        if (!entailed.isEmpty()) {
            sb.append("Beyond the directly observed order, the reasoner entailed: ")
                    .append(String.join("; ", entailed)).append(". ");
        }

        // Caveats.
        List<String> contradictions = evidenceDescriptions(suggestion, "CONTRADICTION", 2);
        if (!contradictions.isEmpty()) {
            sb.append("Caveats: ").append(String.join("; ", contradictions)).append(".");
        }
        return sb.toString().trim();
    }

    // ── structure readers ────────────────────────────────────────────────────

    /** Step names in phase/step order (the mined execution order). */
    public static List<String> orderedStepNames(ProcessSuggestion suggestion) {
        List<String> names = new ArrayList<>();
        if (suggestion == null || suggestion.getPhases() == null) {
            return names;
        }
        for (ProcessSuggestion.SuggestedPhase phase : suggestion.getPhases()) {
            if (phase.getSteps() == null) {
                continue;
            }
            for (ProcessSuggestion.SuggestedStep step : phase.getSteps()) {
                if (step.getName() != null && !names.contains(step.getName())) {
                    names.add(step.getName());
                }
            }
        }
        return names;
    }

    /**
     * The generation-drift clause rebuilt from the suggestion's DRIFT evidence ("Since 2026-06-12:
     * step added: 'X'; …"), or {@code ""} when none. This is what keeps drift visible in the
     * narrative even after the LLM narration pass REGENERATES it — the evidence is the durable
     * record, and any narrative writer appends this clause after its own prose. Within-log
     * change-point entries stay evidence-only (they describe the log, not the previous generation).
     */
    public static String driftClause(ProcessSuggestion suggestion) {
        if (suggestion.getStructuredEvidence() == null) {
            return "";
        }
        String since = null;
        List<String> lines = new ArrayList<>();
        for (ProcessSuggestion.StructuredEvidence evidence : suggestion.getStructuredEvidence()) {
            if (!"DRIFT".equals(evidence.getType()) || evidence.getDescription() == null
                    || !evidence.getDescription().startsWith("Since ")) {
                continue;
            }
            int colon = evidence.getDescription().indexOf(": ");
            if (colon < 0) {
                continue;
            }
            if (since == null) {
                since = evidence.getDescription().substring("Since ".length(), colon);
            }
            lines.add(evidence.getDescription().substring(colon + 2));
        }
        if (since == null || lines.isEmpty()) {
            return "";
        }
        return " Changes since " + since + ": "
                + String.join("; ", lines.subList(0, Math.min(3, lines.size())))
                + (lines.size() > 3 ? "; and " + (lines.size() - 3) + " more" : "") + ".";
    }

    /**
     * The conflicting-descriptions clause rebuilt from CONFLICT/RECONCILIATION evidence, or
     * {@code ""} when the sources agree. Like {@link #driftClause}, this keeps the disagreement —
     * and the explicitly-guessed reconciliation — visible in the narrative even after the LLM
     * narration pass regenerates it; the evidence entries are the durable record.
     */
    public static String conflictClause(ProcessSuggestion suggestion) {
        if (suggestion.getStructuredEvidence() == null) {
            return "";
        }
        String firstConflict = null;
        String firstReconciliation = null;
        int conflicts = 0;
        for (ProcessSuggestion.StructuredEvidence evidence : suggestion.getStructuredEvidence()) {
            if ("CONFLICT".equals(evidence.getType()) && evidence.getDescription() != null) {
                conflicts++;
                if (firstConflict == null) {
                    firstConflict = evidence.getDescription();
                }
            } else if ("RECONCILIATION".equals(evidence.getType()) && firstReconciliation == null
                    && evidence.getDescription() != null
                    && !evidence.getDescription().startsWith("Applied")) {
                firstReconciliation = evidence.getDescription();
            }
        }
        if (firstConflict == null) {
            return "";
        }
        StringBuilder clause = new StringBuilder(" The sources DISAGREE");
        if (conflicts > 1) {
            clause.append(" (").append(conflicts).append(" conflicts)");
        }
        clause.append(": ").append(firstConflict).append('.');
        if (firstReconciliation != null) {
            clause.append(' ').append(firstReconciliation).append('.');
        }
        return clause.toString();
    }

    /** Case count parsed from the converter's own deterministic description ("from N case(s)…"). */
    public static int caseCount(ProcessSuggestion suggestion) {
        String description = suggestion.getDescription();
        if (description != null) {
            Matcher m =
                    Pattern.compile("from (\\d+) case").matcher(description);
            if (m.find()) {
                try {
                    return Integer.parseInt(m.group(1));
                } catch (NumberFormatException ignored) {
                    // fall through
                }
            }
        }
        return 1;
    }

    private static String joinFlow(List<String> steps) {
        if (steps.size() == 1) {
            return "a single step: " + steps.get(0);
        }
        return String.join(" → ", steps);
    }

    /** "X waits for Y and Z." clauses from mined/entailed dependencies (skips trivial chains). */
    private static List<String> dependencyClauses(ProcessSuggestion suggestion) {
        List<String> ordered = orderedStepNames(suggestion);
        List<String> clauses = new ArrayList<>();
        if (suggestion.getPhases() == null) {
            return clauses;
        }
        for (ProcessSuggestion.SuggestedPhase phase : suggestion.getPhases()) {
            if (phase.getSteps() == null) {
                continue;
            }
            for (ProcessSuggestion.SuggestedStep step : phase.getSteps()) {
                List<String> deps = step.getDependsOn();
                if (deps == null || deps.isEmpty() || step.getName() == null) {
                    continue;
                }
                // The immediate predecessor is already narrated by the flow arrow — only spell
                // out NON-trivial dependencies (multiple prerequisites or a non-adjacent one).
                int idx = ordered.indexOf(step.getName());
                boolean trivial = deps.size() == 1 && idx > 0 && deps.get(0).equals(ordered.get(idx - 1));
                if (!trivial && clauses.size() < 3) {
                    clauses.add(step.getName() + " does not start until "
                            + String.join(" and ", deps) + (deps.size() > 1 ? " are" : " is")
                            + " complete.");
                }
            }
        }
        return clauses;
    }

    private static Set<String> roles(ProcessSuggestion suggestion) {
        Set<String> roles = new LinkedHashSet<>();
        if (suggestion.getPhases() == null) {
            return roles;
        }
        for (ProcessSuggestion.SuggestedPhase phase : suggestion.getPhases()) {
            if (phase.getSteps() == null) {
                continue;
            }
            for (ProcessSuggestion.SuggestedStep step : phase.getSteps()) {
                String role = step.getRoleBinding();
                if (role != null && !role.isBlank() && !"UNASSIGNED".equalsIgnoreCase(role.trim())) {
                    roles.add(role);
                }
            }
        }
        return roles;
    }

    private static List<String> evidenceDescriptions(ProcessSuggestion suggestion, String type, int cap) {
        List<String> out = new ArrayList<>();
        if (suggestion.getStructuredEvidence() == null) {
            return out;
        }
        for (ProcessSuggestion.StructuredEvidence ev : suggestion.getStructuredEvidence()) {
            if (type.equals(ev.getType()) && ev.getDescription() != null && out.size() < cap) {
                out.add(ev.getDescription());
            }
        }
        return out;
    }
}
