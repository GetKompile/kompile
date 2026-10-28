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
 * limitations under the License.
 */

package ai.kompile.cli.main.chat.enforcer.deflection;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Investigation receipts found in the same turn as a deflection signal. Receipts are
 * the discriminator: a deflection verdict fires only for {@code signal AND NOT evidence}.
 *
 * <p>Three receipt kinds, mirroring the design doc:</p>
 * <ul>
 *   <li>{@code diagnostics} — executed investigation commands (git log/show/diff, greps,
 *       tests, thread dumps) in the turn's tool trace</li>
 *   <li>{@code artifacts} — concrete artifact references in the output: file:line,
 *       commit SHAs, test names, incident ids</li>
 *   <li>{@code handoffs} — an explicit ownership handoff ("assigned to X", "filed …")</li>
 * </ul>
 */
public record DeflectionEvidence(
        List<String> diagnostics,
        List<String> artifacts,
        List<String> handoffs) {

    public DeflectionEvidence {
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        artifacts = artifacts == null ? List.of() : List.copyOf(artifacts);
        handoffs = handoffs == null ? List.of() : List.copyOf(handoffs);
    }

    public static DeflectionEvidence empty() {
        return new DeflectionEvidence(List.of(), List.of(), List.of());
    }

    public boolean isEmpty() {
        return diagnostics.isEmpty() && artifacts.isEmpty() && handoffs.isEmpty();
    }

    public int size() {
        return diagnostics.size() + artifacts.size() + handoffs.size();
    }

    /** One-line summary for verdict reasoning lines. */
    public String summarize() {
        if (isEmpty()) {
            return "no receipts";
        }
        List<String> parts = new ArrayList<>();
        if (!diagnostics.isEmpty()) {
            parts.add(diagnostics.size() + " diagnostic(s)");
        }
        if (!artifacts.isEmpty()) {
            parts.add(artifacts.size() + " artifact ref(s)");
        }
        if (!handoffs.isEmpty()) {
            parts.add(handoffs.size() + " handoff(s)");
        }
        return String.join(", ", parts);
    }

    // ── Detection (static, deterministic) ────────────────────────────────────

    /**
     * Executed-diagnostic detection over a turn's tool-trace text (tool names, commands,
     * arguments). Each entry is a short description of what was found.
     */
    public static DeflectionEvidence fromToolTrace(String toolTraceText) {
        List<String> diagnostics = new ArrayList<>();
        if (toolTraceText == null || toolTraceText.isBlank()) {
            return new DeflectionEvidence(diagnostics, List.of(), List.of());
        }
        String lower = toolTraceText.toLowerCase(Locale.ROOT);
        for (Pattern p : DIAGNOSTIC_TRACE_PATTERNS) {
            Matcher m = p.matcher(lower);
            if (m.find()) {
                diagnostics.add(p.pattern());
            }
        }
        return new DeflectionEvidence(diagnostics, List.of(), List.of());
    }

    /**
     * Artifact-reference detection over the agent's output text.
     */
    public static DeflectionEvidence fromOutput(String output) {
        List<String> artifacts = new ArrayList<>();
        List<String> handoffs = new ArrayList<>();
        if (output == null || output.isBlank()) {
            return new DeflectionEvidence(List.of(), artifacts, handoffs);
        }
        for (Pattern p : ARTIFACT_PATTERNS) {
            Matcher m = p.matcher(output);
            if (m.find()) {
                artifacts.add(m.group().trim());
            }
        }
        for (Pattern p : HANDOFF_PATTERNS) {
            Matcher m = p.matcher(output);
            if (m.find()) {
                int start = Math.max(0, m.start() - 40);
                int end = Math.min(output.length(), m.end() + 40);
                handoffs.add(output.substring(start, end).replaceAll("\\s+", " ").trim());
            }
        }
        return new DeflectionEvidence(List.of(), artifacts, handoffs);
    }

    /**
     * Full receipt scan for a turn: tool trace supplies diagnostics, output supplies
     * artifact references and handoffs.
     */
    public static DeflectionEvidence scan(String output, String toolTraceText) {
        DeflectionEvidence fromTrace = fromToolTrace(toolTraceText);
        DeflectionEvidence fromOut = fromOutput(output);
        List<String> diagnostics = new ArrayList<>(fromTrace.diagnostics());
        List<String> artifacts = new ArrayList<>(fromOut.artifacts());
        List<String> handoffs = new ArrayList<>(fromOut.handoffs());
        return new DeflectionEvidence(diagnostics, artifacts, handoffs);
    }

    /**
     * Patterns matched against the LOWERCASED tool trace. Each represents evidence the
     * agent actually executed an investigation this turn.
     */
    private static final List<Pattern> DIAGNOSTIC_TRACE_PATTERNS = List.of(
            // git investigation
            Pattern.compile("\\bgit (log|show|diff|blame|status|bisect|describe)\\b"),
            // search
            Pattern.compile("\\b(grep|rg|ag|ack)\\b"),
            // tests
            Pattern.compile("\\b(mvn (test|verify)|gradle (test|check)|ctest|pytest|npm (test|run test))\\b"),
            // process / runtime inspection
            Pattern.compile("\\b(jstack|jcmd|threaddump|kill -3|top -h|ps aux)\\b"),
            // file reading counts as looking
            Pattern.compile("\\b(read|cat|head|tail|file_context|view)\\b")
    );

    /** Patterns matched against the raw output text. */
    private static final List<Pattern> ARTIFACT_PATTERNS = List.of(
            // file:line and path:line references
            Pattern.compile("[A-Za-z0-9_./\\-]+\\.(java|kt|py|c|cpp|h|cu|md|json|xml|ts|js|go|rs)(:\\d+)?"),
            // git SHAs (7-40 hex)
            Pattern.compile("\\b[0-9a-f]{7,40}\\b"),
            // test names like FooTest.bar or FooTest
            Pattern.compile("\\b[A-Z][A-Za-z0-9_]*Test\\.[A-Za-z0-9_]+\\b"),
            // incident ids
            Pattern.compile("\\bINC-[A-Za-z0-9]+\\b"),
            // issue/PR refs
            Pattern.compile("#\\d+\\b")
    );

    /** Handoff phrasing, matched with surrounding context captured. */
    private static final List<Pattern> HANDOFF_PATTERNS = List.of(
            Pattern.compile("\\bassigned to [A-Za-z0-9_@.-]+\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bfiled (an issue|a ticket|a bug)\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bcommented on (INC|issue|ticket|PR)\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bescalated to [A-Za-z0-9_@.-]+\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bpaged [A-Za-z0-9_@.-]+\\b", Pattern.CASE_INSENSITIVE)
    );
}
