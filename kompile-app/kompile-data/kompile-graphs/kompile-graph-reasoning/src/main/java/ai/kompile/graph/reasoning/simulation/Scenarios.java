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
package ai.kompile.graph.reasoning.simulation;

import java.util.List;
import java.util.Optional;

/**
 * Registry of the built-in {@link GraphScenario} generators. The simulator runner exposes these
 * through its scenario-list endpoint; additional scenarios only need to be appended here.
 */
public final class Scenarios {

    private static final List<GraphScenario> BUILT_IN = List.of(
            new RuleWorldScenario(),
            new OrgNetworkScenario(),
            new DuplicateIdentityScenario(),
            new CausalChainScenario());

    private Scenarios() {
    }

    /** All built-in scenario generators, in display order. */
    public static List<GraphScenario> builtIn() {
        return BUILT_IN;
    }

    /** Look up a generator by its {@link ScenarioDescriptor#id()}. */
    public static Optional<GraphScenario> byId(String id) {
        if (id == null) return Optional.empty();
        return BUILT_IN.stream().filter(s -> s.describe().id().equals(id)).findFirst();
    }
}
