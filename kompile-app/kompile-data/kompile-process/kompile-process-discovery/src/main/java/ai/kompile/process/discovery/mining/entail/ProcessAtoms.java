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

package ai.kompile.process.discovery.mining.entail;

/**
 * The canonical KB atom keys the process miner asserts and verifies — one definition so the
 * assert side (fact promotion into {@code KbGroundingService}) and the verify side
 * ({@code ProcessTreeToSuggestion} step grounding) can never drift apart.
 *
 * <p>Two deliberate conventions:
 * <ul>
 *   <li><b>Verify-facing atoms are quoted</b> ({@code activity("Approve Invoice")},
 *       {@code precedes("A", "B")}) — {@code DefaultKbVerifier} looks keys up by exact string,
 *       and the quoted form is the established KB style for label-argument atoms.</li>
 *   <li><b>Cascade-facing atoms are unquoted</b> ({@code Occurs(Approve Invoice)}) — the mined
 *       PSL rules ({@code w: Occurs("A") -> Occurs("B")}) pass through {@code Term.parse}, which
 *       STRIPS quotes from constants, so their ground-rule atom keys are unquoted. A quoted fact
 *       key would never unify with them (this exact mismatch kept mined rules inert in cascades).</li>
 * </ul>
 *
 * <p>Labels are sanitized (quotes and commas become spaces) because both the KB atom-key parser
 * and the PSL rule parser split arguments on commas. Node ids and paths must never appear here —
 * atom keys carry display labels only.
 */
public final class ProcessAtoms {

    private ProcessAtoms() {
    }

    /** Verify-facing existence atom for one activity, e.g. {@code activity("Approve Invoice")}. */
    public static String activityAtom(String label) {
        return "activity(\"" + sanitize(label) + "\")";
    }

    /**
     * Cascade-facing occurrence atom, e.g. {@code Occurs(Approve Invoice)} — unquoted so it
     * unifies with the mined rules' ground atoms after {@code Term.parse} strips their quotes.
     */
    public static String occursAtom(String label) {
        return "Occurs(" + sanitize(label) + ")";
    }

    /** Verify-facing precedence atom, e.g. {@code precedes("Approve", "Close")}. */
    public static String precedesAtom(String from, String to) {
        return "precedes(\"" + sanitize(from) + "\", \"" + sanitize(to) + "\")";
    }

    /**
     * Verify-facing performer atom, e.g. {@code performedBy("Approval", "bob")} — the observed
     * majority actor for an activity, tallied from the crawl's actor-incident relations. This is
     * the predicate {@code RoleBindingExtractor}'s KB tier queries, so promoting it is what makes
     * that tier live.
     */
    public static String performedByAtom(String activity, String actor) {
        return "performedBy(\"" + sanitize(activity) + "\", \"" + sanitize(actor) + "\")";
    }

    /** Verify-facing role atom, e.g. {@code hasRole("Approval", "Procurement Approver")}. */
    public static String hasRoleAtom(String activity, String role) {
        return "hasRole(\"" + sanitize(activity) + "\", \"" + sanitize(role) + "\")";
    }

    /**
     * Verify-facing activity-level taxonomy atom, e.g. {@code isa("Chianti", "Wine")} — the
     * process-vocabulary counterpart of the projector's entity-level {@code isa(entity, TYPE)}
     * atoms, promoted when a taxonomy roll-up abstracts sibling activities to their OWL concept.
     */
    public static String isAAtom(String child, String parent) {
        return "isa(\"" + sanitize(child) + "\", \"" + sanitize(parent) + "\")";
    }

    /** Verify-facing has-a atom, e.g. {@code partOf("Wine", "Order Placed")}. */
    public static String partOfAtom(String part, String whole) {
        return "partOf(\"" + sanitize(part) + "\", \"" + sanitize(whole) + "\")";
    }

    /** Verify-facing control atom, e.g. {@code controls("C-04", "Validate forecast")}. */
    public static String controlsAtom(String control, String activity) {
        return "controls(\"" + sanitize(control) + "\", \"" + sanitize(activity) + "\")";
    }

    /** Verify-facing validation atom, e.g. {@code validates("C-04", "Validate forecast")}. */
    public static String validatesAtom(String control, String activity) {
        return "validates(\"" + sanitize(control) + "\", \"" + sanitize(activity) + "\")";
    }

    /** Verify-facing approval actor atom, e.g. {@code approvedBy("Gate", "J. Park")}. */
    public static String approvedByAtom(String activity, String approver) {
        return "approvedBy(\"" + sanitize(activity) + "\", \"" + sanitize(approver) + "\")";
    }

    /** Verify-facing approval policy atom, e.g. {@code requiresApproval("Gate", "CFO")}. */
    public static String requiresApprovalAtom(String activity, String policy) {
        return "requiresApproval(\"" + sanitize(activity) + "\", \"" + sanitize(policy) + "\")";
    }

    /** Verify-facing escalation atom, e.g. {@code escalatesTo("Variance triage", "Owner")}. */
    public static String escalatesToAtom(String activity, String target) {
        return "escalatesTo(\"" + sanitize(activity) + "\", \"" + sanitize(target) + "\")";
    }

    /** Verify-facing threshold atom, e.g. {@code threshold("Gate", "confidence", ">=", "0.85")}. */
    public static String thresholdAtom(String activity, String field, String operator, String value) {
        return "threshold(\"" + sanitize(activity) + "\", \"" + sanitize(field) + "\", \""
                + sanitize(operator) + "\", \"" + sanitize(value) + "\")";
    }

    /** Verify-facing routing policy atom, e.g. {@code routedBy("Gate", "below threshold")}. */
    public static String routedByAtom(String activity, String policy) {
        return "routedBy(\"" + sanitize(activity) + "\", \"" + sanitize(policy) + "\")";
    }

    /** Verify-facing action/effect atom, e.g. {@code action("Gate", "AUTO_CORRECT")}. */
    public static String actionAtom(String activity, String action) {
        return "action(\"" + sanitize(activity) + "\", \"" + sanitize(action) + "\")";
    }

    /** Verify-facing SLA atom, e.g. {@code sla("Approval", "slaSeconds", "3600")}. */
    public static String slaAtom(String activity, String key, String value) {
        return "sla(\"" + sanitize(activity) + "\", \"" + sanitize(key) + "\", \""
                + sanitize(value) + "\")";
    }

    /** Verify-facing SLA breach atom, e.g. {@code slaBreach("Approval", "overdue", "true")}. */
    public static String slaBreachAtom(String activity, String key, String value) {
        return "slaBreach(\"" + sanitize(activity) + "\", \"" + sanitize(key) + "\", \""
                + sanitize(value) + "\")";
    }

    /** Verify-facing status atom, e.g. {@code status("Approval", "BLOCKED")}. */
    public static String statusAtom(String activity, String status) {
        return "status(\"" + sanitize(activity) + "\", \"" + sanitize(status) + "\")";
    }

    /** Verify-facing remediation atom, e.g. {@code remediation("Gate", "REWORK")}. */
    public static String remediationAtom(String activity, String remediation) {
        return "remediation(\"" + sanitize(activity) + "\", \"" + sanitize(remediation) + "\")";
    }

    /** Verify-facing generic policy atom, e.g. {@code policy("Gate", "approvalPolicy", "CFO")}. */
    public static String policyAtom(String activity, String key, String value) {
        return "policy(\"" + sanitize(activity) + "\", \"" + sanitize(key) + "\", \""
                + sanitize(value) + "\")";
    }

    /** Quotes and commas break both the atom-key parser and the rule parser - flatten to spaces. */
    public static String sanitize(String label) {
        if (label == null) {
            return "";
        }
        return label.replace('"', ' ').replace(',', ' ').trim();
    }
}
