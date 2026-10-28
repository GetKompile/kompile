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
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package ai.kompile.cli.main.chat.enforcer.deflection;

/**
 * Correction-prompt builders for the deflection lane (mirrors
 * {@code EnforcerService.buildCorrectionPrompt} in shape):
 * correction 1 is the ownership mandate, correction 2 the evidence-first mandate.
 */
public final class DeflectionMandate {

    private DeflectionMandate() {
    }

    /**
     * Correction 1 — ownership mandate. Requires, concretely: (a) one diagnostic run NOW
     * (named command), (b) an evidence-based ownership classification, (c) if genuinely
     * out of scope: exact artifact + owner + handoff action performed this turn.
     */
    public static String ownershipMandate(String originalPrompt, String signalSummary) {
        return "# Deflection Correction (ownership mandate)\n\n"
                + "Your previous response was blocked by the deflection enforcer: it claimed "
                + "non-ownership without receipts.\n\n"
                + "## Original User Prompt\n" + safe(originalPrompt) + "\n\n"
                + "## Deflection Signal(s)\n" + safe(signalSummary) + "\n\n"
                + "## Required Correction\n"
                + "You may classify the issue as pre-existing, out of scope, or owned elsewhere "
                + "ONLY IF you can show receipts:\n"
                + "1. Name and run one diagnostic command NOW (for example `git log --oneline -5 "
                + "-- <file>`, `mvn -pl <module> test -Dtest=...`, `grep -rn ...`) and classify "
                + "based on its actual output.\n"
                + "2. Give an evidence-based ownership classification: is this regression yours "
                + "(did you or another session modify the relevant code in this tree)? Answer "
                + "yes/no with the evidence line.\n"
                + "3. If it is genuinely out of scope: cite the exact artifact (file:line, "
                + "commit, test, incident id), name the owner, and state the handoff action you "
                + "performed THIS TURN (for example \"commented on INC-...\", \"assigned to X\").\n\n"
                + "Blanket claims without evidence are disallowed. Return the corrected response.";
    }

    /**
     * Correction 2 — evidence-first mandate. Re-answer only AFTER executing the check;
     * the next evaluation requires tool-trace evidence, not prose.
     */
    public static String evidenceFirstMandate(String originalPrompt, String signalSummary) {
        return "# Deflection Correction (evidence-first mandate)\n\n"
                + "Your corrected response was STILL missing receipts. This is the final "
                + "correction before the turn is blocked.\n\n"
                + "## Original User Prompt\n" + safe(originalPrompt) + "\n\n"
                + "## Deflection Signal(s)\n" + safe(signalSummary) + "\n\n"
                + "## Required Correction\n"
                + "Execute the diagnostic FIRST (bash/read/test — the tool trace is checked), "
                + "then write your answer using what the tools returned. The deflection "
                + "evaluation now REQUIRES evidence in this turn's tool trace or output: a "
                + "command you ran, a file:line you read, a commit SHA, a test result, or an "
                + "incident id. Prose-only claims of non-ownership will be blocked.\n\n"
                + "Return the corrected response with the evidence inline.";
    }

    /** Stop message when corrections are exhausted or deflection repeats identically. */
    public static String stopReason(String signalSummary, int correctionsUsed, int maxCorrections) {
        return "Deflection persisted after " + correctionsUsed + "/" + maxCorrections
                + " corrections without receipts. Signal(s): " + safe(signalSummary)
                + ". The turn is blocked: own the issue and show evidence, or hand it off "
                + "with a recorded action.";
    }

    private static String safe(String s) {
        return s == null || s.isBlank() ? "(not recorded)" : s.trim();
    }
}
