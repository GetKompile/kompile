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

package ai.kompile.cli.main.chat.enforcer;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * One durable judgement record — a single decision made by the enforcer/judge during a
 * session. Records are appended as JSON lines to
 * {@code ~/.kompile/sessions/<sessionId>/judgements.jsonl} by {@link JudgementLog} so that
 * every judgement (and, for LLM judges, the raw judge response) can be tracked and replayed
 * after the session ends.
 *
 * <p>The {@link #phase} field distinguishes layered records:</p>
 * <ul>
 *   <li>{@code JUDGE_TURN} / {@code JUDGE_PARTIAL} / {@code JUDGE_TOOL} — a raw LLM judge call
 *       (carries {@link #judgeRawResponse} + {@link #latencyMs}); the judge transcript. A
 *       {@code JUDGE_TOOL} record with {@code judgeMode=policy} was decided by deterministic
 *       policy before any model call; its raw response names the policy.</li>
 *   <li>{@code JUDGE_CHAT} — a /judge chat exchange with the judge (verdict fields are
 *       neutral; the raw response holds the judge's conversational reply).</li>
 *   <li>{@code JUDGE_DIRECTION} — a direction-judge check of whether the agent is on track.</li>
 *   <li>{@code ATTEMPT} — a non-LLM (keyword) per-attempt decision.</li>
 *   <li>{@code RESULT} — the final enforcement outcome for a turn ({@link #status}).</li>
 *   <li>{@code SWAP} — the resilient judge backend swapped judge models after a failure.</li>
 *   <li>{@code CONTROL} — a user change to the judge posture ({@code judgeMode=user});
 *       {@link #status} names the action ({@code OVERRIDE_ARMED}, {@code OVERRIDE_DISARMED},
 *       {@code APPROVAL_SET}, {@code APPROVAL_CLEARED}, {@code GUIDANCE_SET},
 *       {@code GUIDANCE_CLEARED}, {@code JUDGE_ENABLED}, {@code JUDGE_DISABLED}) and
 *       {@link #reasoning} the detail.</li>
 *   <li>{@code OVERRIDE} — a call or turn that a user decision let through ({@code judgeMode=user}):
 *       {@code status=APPROVED} when an explicit command approval skipped judge review, or
 *       {@code status=OVERRIDDEN} when the report-only override neutralized a verdict. An
 *       {@code OVERRIDDEN} record keeps that verdict ({@code compliant=false}, severity,
 *       violations) with {@code stop=false}, because nothing was stopped.</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class JudgementRecord {

    /** ISO-8601 instant; filled in by {@link JudgementLog} if absent. */
    private String timestamp;

    /** Owning session id (e.g. {@code enforcer-1a2b3c4d}). */
    private String sessionId;

    /** JUDGE_TURN | JUDGE_PARTIAL | JUDGE_TOOL | JUDGE_CHAT | JUDGE_DIRECTION | ATTEMPT | RESULT | SWAP | CONTROL | OVERRIDE. */
    private String phase;

    /** 1-based correction attempt, or 0 when not applicable. */
    private int attempt;

    /** {@code llm}, {@code keyword}, {@code policy} (deterministic, no model call), or {@code user} (a user decision). */
    private String judgeMode;

    /** Judge backend identity ({@code evaluator.describe()}). */
    private String backend;

    /** Optional judge model name. */
    private String model;

    /** Judge call latency in milliseconds (0 when not an LLM call). */
    private long latencyMs;

    private boolean compliant;
    private boolean stop;
    private String severity;
    private List<String> violations;
    private String correctionPrompt;
    private String reasoning;

    /**
     * RESULT: the final enforcement status (ACCEPTED | BLOCKED | UNAVAILABLE | ERROR).
     * CONTROL: the user action. OVERRIDE: APPROVED | OVERRIDDEN.
     */
    private String status;

    private String userPromptExcerpt;
    private String agentOutputExcerpt;

    /** Tool name (JUDGE_TOOL records, and OVERRIDE records for a tool call). */
    private String toolName;

    /** Raw judge response text (JUDGE_* phases); truncated. This is the LLM judge transcript. */
    private String judgeRawResponse;
}
