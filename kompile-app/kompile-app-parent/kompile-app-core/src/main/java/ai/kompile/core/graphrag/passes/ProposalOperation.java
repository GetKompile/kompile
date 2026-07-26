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
 * The closed vocabulary a pass may choose from. Every pass output selects exactly one
 * operation, and every pass has a legitimate way out — {@link #ABSTAIN} / {@link #UNRESOLVED} —
 * so an ambiguous input is not forced into the closest available category.
 */
public enum ProposalOperation {

    /** The mention refers to a supplied candidate entity. */
    REUSE_ENTITY,

    /** No candidate fits; propose a new entity that stays provisional until validated. */
    CREATE_PROVISIONAL_ENTITY,

    /** The proposition supports an existing claim — attach evidence rather than create a claim. */
    ADD_EVIDENCE,

    /** No existing claim matches; propose a new one. */
    CREATE_CLAIM,

    /** Record as an attributed opinion rather than an unattributed fact. */
    RECORD_OPINION,

    /** The proposition appears incompatible with an existing claim; hand to the TMS. */
    FLAG_CONTRADICTION,

    /** Nothing in the supplied schema fits; describe the gap for schema governance. */
    PROPOSE_SCHEMA_GAP,

    /** The model declines to decide on this object. */
    ABSTAIN,

    /** Identity or selection could not be determined from the supplied bounded context. */
    UNRESOLVED;

    /** True when the operation declines to commit — the item must not reach the graph. */
    public boolean isAbstention() {
        return this == ABSTAIN || this == UNRESOLVED;
    }

    /**
     * Lenient parse of a model-emitted label; unknown or blank input yields {@link #ABSTAIN}.
     *
     * <p>Defaulting to abstention (rather than to a committing operation) is deliberate: a
     * garbled label must never be able to write to the graph.</p>
     */
    public static ProposalOperation from(String raw) {
        if (raw == null || raw.isBlank()) {
            return ABSTAIN;
        }
        String key = raw.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        return switch (key) {
            case "REUSE_ENTITY", "REUSE", "EXISTING", "LINK", "MATCH" -> REUSE_ENTITY;
            case "CREATE_PROVISIONAL_ENTITY", "CREATE_ENTITY", "NEW", "NEW_ENTITY", "CREATE" ->
                    CREATE_PROVISIONAL_ENTITY;
            case "ADD_EVIDENCE", "SUPPORT", "ATTACH_EVIDENCE", "EVIDENCE" -> ADD_EVIDENCE;
            case "CREATE_CLAIM", "NEW_CLAIM", "ASSERT_CLAIM",
                 // pass 4 phrases the same commitment as "propose this relation"
                 "PROPOSE_RELATION", "SELECT", "SELECT_TYPE", "RELATE" -> CREATE_CLAIM;
            case "RECORD_OPINION", "OPINION", "ATTRIBUTE" -> RECORD_OPINION;
            case "FLAG_CONTRADICTION", "CONTRADICT", "CONTRADICTION", "CONFLICT" ->
                    FLAG_CONTRADICTION;
            case "PROPOSE_SCHEMA_GAP", "SCHEMA_GAP", "GAP", "NO_SCHEMA_FIT" -> PROPOSE_SCHEMA_GAP;
            case "UNRESOLVED", "UNKNOWN", "AMBIGUOUS", "OTHER" -> UNRESOLVED;
            default -> ABSTAIN;
        };
    }
}
