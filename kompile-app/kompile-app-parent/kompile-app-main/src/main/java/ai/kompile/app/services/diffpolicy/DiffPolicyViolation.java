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

package ai.kompile.app.services.diffpolicy;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A single policy violation found in a captured agent file-change. Produced by
 * scanning {@code DiffIndexEntry} records against the policy rules (reused
 * {@code DiffPatternEvaluator} content rules, path-of-concern globs, and an
 * optional LLM judge), so mistakes and policy breaches are queryable per
 * file/agent/session/time/severity.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class DiffPolicyViolation {
    /** Stable id (same finding across re-scans dedupes to the same id). */
    private String id;
    /** The DiffIndexEntry this violation was found in. */
    private String diffEntryId;
    /** Detector that produced it: {@code path}, {@code rule}, or {@code llm}. */
    private String detector;
    /** The rule that fired (path glob, content pattern, or LLM rationale key). */
    private String ruleId;
    /** info | warning | error | critical. */
    private String severity;
    /** 0..1 risk score (higher = worse), derived from severity. */
    private double riskScore;

    private String agent;
    private String source;
    private String sessionId;
    private String filePath;
    /** 1-based line of the offending added line; 0 for whole-file (path) rules. */
    private int lineNumber;
    private String matchedLine;
    private String message;
    private String toolName;
    /** Timestamp of the underlying file change. */
    private String timestamp;
    /** When the scan that produced this violation ran. */
    private String detectedAt;
}
