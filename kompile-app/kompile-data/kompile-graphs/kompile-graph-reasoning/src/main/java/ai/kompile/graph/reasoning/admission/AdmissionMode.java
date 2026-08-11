/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.graph.reasoning.admission;

/** Controls whether graph admission is authoritative or observational. */
public enum AdmissionMode {
    /** Preserve the existing LLM-only behavior. */
    LLM_ONLY,
    /** Evaluate the graph branch concurrently while the LLM remains authoritative. */
    SHADOW_COMPARE,
    /**
     * Make the deterministic operational graph policy authoritative for configured entity types.
     *
     * <p>The model may still stage candidates, but only an explicit policy-matched
     * {@link OperationalDisposition#ALLOW} is admitted. Review, denial, missing rules, and degraded
     * graph evaluation all fail closed.</p>
     */
    GRAPH_POLICY
}
