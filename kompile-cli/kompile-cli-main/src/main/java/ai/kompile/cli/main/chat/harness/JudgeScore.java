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

package ai.kompile.cli.main.chat.harness;

/**
 * Result of a judge LLM evaluation of an agent's output.
 * Score range is 1.0-5.0; 0 indicates evaluation failure.
 */
public class JudgeScore {

    private final float score;
    private final String reasoning;
    private final boolean refused;
    private final boolean vague;
    private final boolean error;

    private JudgeScore(float score, String reasoning, boolean refused, boolean vague, boolean error) {
        this.score = score;
        this.reasoning = reasoning;
        this.refused = refused;
        this.vague = vague;
        this.error = error;
    }

    public static JudgeScore of(float score, String reasoning) {
        return new JudgeScore(Math.max(0, Math.min(5, score)), reasoning, false, false, false);
    }

    public static JudgeScore refused(String reasoning) {
        return new JudgeScore(1.0f, reasoning, true, false, false);
    }

    public static JudgeScore vague(String reasoning) {
        return new JudgeScore(2.0f, reasoning, false, true, false);
    }

    public static JudgeScore error(String reasoning) {
        return new JudgeScore(0f, reasoning != null ? reasoning : "Judge evaluation failed", false, false, true);
    }

    public static JudgeScore error() {
        return error(null);
    }

    public float getScore() { return score; }
    public String getReasoning() { return reasoning; }
    public boolean isRefused() { return refused; }
    public boolean isVague() { return vague; }
    public boolean isError() { return error; }

    /**
     * Whether this score should be used for routing decisions.
     * Error scores (0) are excluded from rolling averages.
     */
    public boolean isValid() {
        return !error && score > 0;
    }

    @Override
    public String toString() {
        if (error) return "JudgeScore{error, " + reasoning + "}";
        return "JudgeScore{" + score + "/5, " + reasoning + "}";
    }
}
