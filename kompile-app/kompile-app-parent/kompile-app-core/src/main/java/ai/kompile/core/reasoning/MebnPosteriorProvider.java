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
package ai.kompile.core.reasoning;

import java.util.Optional;

/**
 * SPI that exposes an MEBN posterior for a hypothesis atom without coupling the consumer to the
 * event-attribution / Bayesian-network machinery. Mirrors {@code OntologyProjectionProvider}: the
 * answer-synthesis pipeline (in {@code kompile-knowledge-graph}) sees this interface via
 * {@code kompile-app-core}, while only a module that has {@code BayesianNetworkService} on its
 * classpath (e.g. {@code kompile-app-main}) implements it.
 *
 * <p><b>Absent is neutral.</b> When no MEBN theory is registered for a fact sheet — or no
 * implementation is wired (tests / lean deployments) — {@link #hasTheory(long)} is {@code false} and
 * {@link #posterior} returns empty. Callers MUST treat "no posterior" as "MEBN contributes nothing"
 * (a vacuous ω_mebn signal), never as evidence against the hypothesis.</p>
 */
public interface MebnPosteriorProvider {

    /** True iff an MEBN theory is registered / resolvable for this fact sheet. */
    boolean hasTheory(long factSheetId);

    /**
     * Posterior probability of {@code atomKey} (e.g. {@code "leads(Alice, Acme)"}) under the fact
     * sheet's MEBN theory, or {@link Optional#empty()} when there is no theory or the hypothesis is
     * not inferable.
     */
    Optional<Double> posterior(long factSheetId, String atomKey);
}
