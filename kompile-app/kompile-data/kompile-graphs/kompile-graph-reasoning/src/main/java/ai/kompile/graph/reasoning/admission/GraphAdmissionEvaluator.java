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
 * Read-only graph admission policy.
 *
 * <p>Implementations must only inspect {@link AdmissionRequest#graph()} and return a value. The
 * interface deliberately exposes no persistence or mutation API, which keeps shadow evaluation from
 * changing the authoritative LLM path.</p>
 */
@FunctionalInterface
public interface GraphAdmissionEvaluator {

    GraphAdmissionResult evaluate(AdmissionRequest request);
}
