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

package ai.kompile.core.graphrag.passes;

import java.util.Locale;

/**
 * Epistemic class of a proposition — what kind of speech act the source text performed.
 *
 * <p>This is the guard against <em>attribution collapse</em>: without it, "Alice thinks the
 * project is late" is extracted as the business fact "the project is late". Only
 * {@linkplain #isDirectlyPromotable() directly promotable} acts may become unattributed graph
 * facts; everything else is projected as an attributed record so the KB promotion policy
 * (not the model) decides what becomes canonical.</p>
 */
public enum SpeechAct {

    /** A plain factual claim about the world made by the source itself. */
    ASSERTION(true),

    /** A system/business record of something that happened (log line, transaction, ledger row). */
    OPERATIONAL_RECORD(true),

    /** A stance held by an identified holder ("Alice thinks X"). Never promoted unattributed. */
    OPINION(false),

    /** A request or instruction directed at someone ("please approve X"). */
    REQUEST(false),

    /** A promise or undertaking ("we will ship X by Friday"). */
    COMMITMENT(false),

    /** A warning about a possible negative outcome. */
    WARNING(false),

    /** A statement about the future ("revenue will grow"). */
    PREDICTION(false),

    /** An interrogative. */
    QUESTION(false),

    /** Could not be classified — treated as attributed, never promoted. */
    UNKNOWN(false);

    private final boolean directlyPromotable;

    SpeechAct(boolean directlyPromotable) {
        this.directlyPromotable = directlyPromotable;
    }

    /**
     * Whether a proposition of this class may be projected as an unattributed graph fact.
     * Everything else is projected with an {@code epistemic} marker and damped confidence.
     */
    public boolean isDirectlyPromotable() {
        return directlyPromotable;
    }

    /**
     * Lenient parse of a model-emitted label. Case-insensitive, accepts common synonyms and
     * separator variants; unknown or blank input yields {@link #UNKNOWN} rather than throwing —
     * a small model must never be able to crash a pass by inventing a label.
     */
    public static SpeechAct from(String raw) {
        if (raw == null || raw.isBlank()) {
            return UNKNOWN;
        }
        String key = raw.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        return switch (key) {
            case "ASSERTION", "ASSERT", "FACT", "STATEMENT", "CLAIM", "DIRECT_FACT" -> ASSERTION;
            case "OPERATIONAL_RECORD", "RECORD", "LOG", "EVENT", "TRANSACTION", "OPERATIONAL" ->
                    OPERATIONAL_RECORD;
            case "OPINION", "BELIEF", "STANCE", "JUDGEMENT", "JUDGMENT", "SENTIMENT" -> OPINION;
            case "REQUEST", "ASK", "INSTRUCTION", "DIRECTIVE", "COMMAND" -> REQUEST;
            case "COMMITMENT", "PROMISE", "UNDERTAKING", "PLEDGE" -> COMMITMENT;
            case "WARNING", "ALERT", "CAUTION" -> WARNING;
            case "PREDICTION", "FORECAST", "PROJECTION", "EXPECTATION" -> PREDICTION;
            case "QUESTION", "QUERY", "INTERROGATIVE" -> QUESTION;
            default -> UNKNOWN;
        };
    }
}
