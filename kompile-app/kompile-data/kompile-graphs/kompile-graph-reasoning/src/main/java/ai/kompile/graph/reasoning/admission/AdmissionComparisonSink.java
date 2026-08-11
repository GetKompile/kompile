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

/**
 * Observes admission comparisons without participating in extraction or graph mutation.
 *
 * <p>Implementations should be fast and non-blocking. Callers treat sink failures as diagnostic
 * failures and never allow them to change the authoritative LLM decision.</p>
 */
@FunctionalInterface
public interface AdmissionComparisonSink {

    void accept(AdmissionComparison comparison);

    static AdmissionComparisonSink noop() {
        return comparison -> {
        };
    }
}
