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

package ai.kompile.process.discovery.mining.declare;

/**
 * The Declare constraint templates mined by {@link DeclareMiner}. Declarative process mining describes a
 * process by what must or must not hold rather than by a flowchart, which suits flexible processes and
 * maps cleanly onto soft logic (each template has a linear-temporal-logic-over-finite-traces meaning).
 */
public enum DeclareTemplate {

    /** Unary: the activity is the first event of the trace. */
    INIT,
    /** Unary: the activity is the last event of the trace. */
    END,
    /** {@code a} is eventually followed by {@code b} (□(a → ◇b)). */
    RESPONSE,
    /** {@code b} occurs only if {@code a} occurred before it. */
    PRECEDENCE,
    /** {@code a} is <em>immediately</em> followed by {@code b} (□(a → ◯b)). */
    CHAIN_RESPONSE,
    /** {@code a} and {@code b} never both occur in the same trace. */
    NOT_CO_EXISTENCE
}
