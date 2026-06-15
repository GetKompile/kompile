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

package ai.kompile.toolgateway.model;

/**
 * Quality score for a gateway evaluation decision, produced by
 * the judge LLM scoring the gateway's own output.
 *
 * @param correctness  1-5 score, or -1 on error
 * @param completeness 1-5 score, or -1 on error
 * @param reasoning    one-sentence explanation from the judge
 * @param error        whether this score represents an error
 * @param errorMessage error detail if {@code error} is true
 */
public record GatewayJudgeScore(
        float correctness,
        float completeness,
        String reasoning,
        boolean error,
        String errorMessage
) {
}
