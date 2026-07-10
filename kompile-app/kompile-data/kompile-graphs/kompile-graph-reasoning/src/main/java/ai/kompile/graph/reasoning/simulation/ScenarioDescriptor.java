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

/**
 * Static self-description of a {@link GraphScenario}: identity, what patterns it plants, and the
 * parameters a UI should render. Serialized as-is to the simulator's scenario-list endpoint.
 *
 * @param id          stable machine id (kebab-case, e.g. {@code "rule-world"})
 * @param name        display name
 * @param description one-paragraph explanation of what is generated and what should be learned
 * @param plants      short labels of the pattern families this scenario plants
 *                    (e.g. {@code ["inferred-edges", "communities"]}) so the UI can pre-select
 *                    the relevant score panels
 * @param params      tunable parameters with defaults and bounds
 */
public record ScenarioDescriptor(
        String id,
        String name,
        String description,
        List<String> plants,
        List<ParamSpec> params) {

    public ScenarioDescriptor {
        plants = (plants == null) ? List.of() : List.copyOf(plants);
        params = (params == null) ? List.of() : List.copyOf(params);
    }

    /**
     * One tunable scenario parameter.
     *
     * @param key          map key in the {@code generate(seed, params)} call
     * @param label        display label
     * @param type         {@code "int"} or {@code "double"}
     * @param defaultValue default used when the caller omits the key
     * @param min          inclusive lower bound (clamped, not rejected)
     * @param max          inclusive upper bound (clamped, not rejected)
     */
    public record ParamSpec(String key, String label, String type,
                            double defaultValue, double min, double max) {

        public static ParamSpec ofInt(String key, String label, int defaultValue, int min, int max) {
            return new ParamSpec(key, label, "int", defaultValue, min, max);
        }

        public static ParamSpec ofDouble(String key, String label, double defaultValue, double min, double max) {
            return new ParamSpec(key, label, "double", defaultValue, min, max);
        }
    }
}
