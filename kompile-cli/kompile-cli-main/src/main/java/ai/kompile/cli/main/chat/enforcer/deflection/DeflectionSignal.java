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

import java.util.Locale;

/**
 * One deflection signal: a phrase or paraphrase in the agent output that asserts
 * non-ownership. A signal alone is never a verdict — the detector combines it with
 * the evidence check ({@code verdict = signal AND NOT evidence}).
 *
 * @param tier      0 = pattern library, 1 = synonym expansion, 2 = embedding
 * @param phrase    the canonical pattern that matched
 * @param matched   the text span that actually matched
 * @param score     signal strength in [0,1]
 */
public record DeflectionSignal(int tier, String phrase, String matched, double score) {

    public static final int TIER_PATTERN = 0;
    public static final int TIER_SYNONYM = 1;
    public static final int TIER_EMBEDDING = 2;

    public DeflectionSignal {
        phrase = phrase == null ? "" : phrase;
        matched = matched == null ? "" : matched;
        if (score < 0.0) score = 0.0;
        if (score > 1.0) score = 1.0;
        if (tier < TIER_PATTERN) tier = TIER_PATTERN;
        if (tier > TIER_EMBEDDING) tier = TIER_EMBEDDING;
    }

    public String describe() {
        return "tier-" + tier + " \"" + truncate(matched, 60) + "\" ~ \"" + phrase + "\" ("
                + String.format(Locale.ROOT, "%.0f%%", score * 100) + ")";
    }

    private static String truncate(String s, int max) {
        if (s.length() <= max) {
            return s;
        }
        return s.substring(0, max) + "…";
    }
}
