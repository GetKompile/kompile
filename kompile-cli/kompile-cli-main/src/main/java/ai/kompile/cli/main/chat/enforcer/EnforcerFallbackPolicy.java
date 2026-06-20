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

/**
 * What the enforcer does when the LLM judge is unavailable or fails mid-turn (timeout, exhausted
 * model swaps, etc). Previously this was an accidental, implicit fail-open in the passthrough
 * dispatch; it is now an explicit, configurable policy.
 */
public enum EnforcerFallbackPolicy {

    /** Allow the agent output through unjudged. Maximizes availability, minimizes safety. */
    FAIL_OPEN,

    /** Block the unverified output. Maximizes safety, may halt a turn when the judge is flaky. */
    FAIL_CLOSED,

    /** Re-check with the instant keyword evaluator built from the same rules; block only on a keyword hit. */
    DEGRADE_TO_KEYWORD;

    /** Parse a config string; defaults to {@link #DEGRADE_TO_KEYWORD} when null/blank/unknown. */
    public static EnforcerFallbackPolicy parse(String value) {
        if (value == null || value.isBlank()) {
            return DEGRADE_TO_KEYWORD;
        }
        switch (value.trim().toLowerCase().replace('-', '_')) {
            case "fail_open":
            case "open":
            case "allow":
                return FAIL_OPEN;
            case "fail_closed":
            case "closed":
            case "block":
                return FAIL_CLOSED;
            case "degrade_to_keyword":
            case "keyword":
            case "degrade":
                return DEGRADE_TO_KEYWORD;
            default:
                return DEGRADE_TO_KEYWORD;
        }
    }

    public String configValue() {
        return name().toLowerCase();
    }
}
