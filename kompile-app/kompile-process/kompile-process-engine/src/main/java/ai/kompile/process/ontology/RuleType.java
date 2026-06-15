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

package ai.kompile.process.ontology;

/**
 * Classifies the type of validation rule.
 */
public enum RuleType {
    /** General assertion that must hold true. */
    ASSERTION,
    /** Enforces a spending or value budget. */
    BUDGET_LIMIT,
    /** Triggers an escalation when a condition is met. */
    ESCALATION_TRIGGER,
    /** Invariant that must always be true (structural). */
    INVARIANT,
    /** Threshold-based alert (warning or error). */
    THRESHOLD,
    /** Verifies that a set of values sums to a target. */
    SUM_CHECK,
    /** Validates a value falls within [min, max]. */
    RANGE_CHECK,
    /** Domain-specific or composite rule. */
    CUSTOM
}
