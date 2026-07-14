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

package ai.kompile.cli.main.chat.tui;

/**
 * Fallback decoder for unknown agent CLIs — used whenever no named decoder matches.
 *
 * <h2>Design rationale</h2>
 * <p>This decoder extends {@link AbstractTuiDecoder} rather than implementing
 * {@link AgentTuiDecoder} directly so that the full base-class prompt machinery
 * ({@link #isAwaitingUserInput}, {@link #extractPromptText}, {@link #selectedOptionDigit},
 * box-border stripping, live-region scoping, and the blocking-notice scanner) works
 * without any agent-specific knowledge.
 *
 * <h2>altScreenIsDialog — default kept as {@code true}</h2>
 * <p>The base class default {@link #altScreenIsDialog()} returns {@code true}, meaning
 * that when an unknown agent enters the alternate screen Kompile treats it as a transient
 * full-screen dialog (picker, confirmation, etc.) and mirrors it rather than trying to
 * decode its content. This is the safest policy for an unknown app: most CLIs only enter
 * the alternate screen briefly for a picker or modal; decoding that content without knowing
 * the chrome layout would strip real options. No override is needed here.
 *
 * <h2>Live-region scoping</h2>
 * <p>Generic agents have no known done-markers, so {@link #liveRegionStartRow} uses the
 * base default (return 0 = full-screen scan). This means that if an answered question
 * happens to stay on screen above the current prompt, it could transiently re-assert
 * {@link #isAwaitingUserInput}. This is an inherent limitation of having no agent-specific
 * markers; it is documented here rather than worked around with heuristics that could
 * cause false negatives on real prompts.
 *
 * <h2>Chrome filtering</h2>
 * <p>Because we have no knowledge of any particular agent's layout, {@link #isChrome}
 * only drops rows that are clearly structural chrome in any CLI context: pure decorative
 * separator/border rows are already handled by the shared
 * {@link AbstractTuiDecoder#isSeparatorOrBorder} and
 * {@link AbstractTuiDecoder#containsMostlyDecorative} filters in
 * {@link AbstractTuiDecoder#isResponseRow}. The {@code isChrome} override here is a
 * minimal safety net, returning {@code false} for essentially everything — letting the
 * base structural filters make the call — so legitimate content is not silently dropped.
 *
 * <h2>Query policy</h2>
 * <p>Uses {@link QueryPolicy#CONSERVATIVE} (DSR/DA1/window/OSC only — no Kitty keyboard,
 * no XTVERSION) because the unknown agent's behaviour when receiving those responses is
 * unpredictable.
 */
public class GenericDecoder extends AbstractTuiDecoder {

    @Override
    public String agentName() {
        return "generic";
    }

    /**
     * For an unknown agent we make minimal assumptions about chrome layout.
     * The shared structural filters in {@link AbstractTuiDecoder#isResponseRow}
     * (separator detection, decorative-character ratio, block-art detection, progress
     * spinner detection) already suppress the most common chrome patterns. This override
     * only adds truly agent-agnostic exclusions that do not risk dropping real content.
     *
     * <p>Rows that are NOT filtered here (intentionally): numbered menus, y/n prompts,
     * "Press Enter to continue" lines, question lines ending with '?'. Those must flow
     * through to the prompt-detection machinery in {@link #isAwaitingUserInput} and
     * {@link #extractPromptText}.
     */
    @Override
    protected boolean isChrome(String row) {
        // No agent-specific chrome patterns are known for the generic decoder.
        // All structural filtering is handled by the shared filters in AbstractTuiDecoder
        // (isSeparatorOrBorder, containsMostlyDecorative, isProgressLine, etc.).
        return false;
    }

    /**
     * Conservative: DSR/DA/window/OSC only — no Kitty keyboard, no XTVERSION.
     * Safe for any unknown agent because it avoids injecting responses that could
     * be misinterpreted (e.g. Kitty keyboard protocol on an agent that doesn't expect it).
     */
    @Override
    public QueryPolicy queryPolicy() {
        return QueryPolicy.CONSERVATIVE;
    }
}
