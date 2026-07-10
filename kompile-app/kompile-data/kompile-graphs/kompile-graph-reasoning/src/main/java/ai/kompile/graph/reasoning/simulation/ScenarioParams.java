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

import java.util.Map;

/**
 * Typed, clamped parameter reads for {@link GraphScenario} implementations: missing keys fall
 * back to the {@link ScenarioDescriptor.ParamSpec} default, malformed values fall back too, and
 * everything is clamped into [min, max] rather than rejected (a simulator UI slider should never
 * be able to crash generation).
 */
public final class ScenarioParams {

    private ScenarioParams() {
    }

    /** Read an int parameter per its spec (default on missing/malformed, clamped to bounds). */
    public static int intParam(Map<String, Object> params, ScenarioDescriptor.ParamSpec spec) {
        return (int) Math.round(doubleParam(params, spec));
    }

    /** Read a double parameter per its spec (default on missing/malformed, clamped to bounds). */
    public static double doubleParam(Map<String, Object> params, ScenarioDescriptor.ParamSpec spec) {
        double v = spec.defaultValue();
        Object raw = (params == null) ? null : params.get(spec.key());
        if (raw instanceof Number n) {
            v = n.doubleValue();
        } else if (raw instanceof String s && !s.isBlank()) {
            try {
                v = Double.parseDouble(s.trim());
            } catch (NumberFormatException ignored) {
                // fall through to default
            }
        }
        return Math.max(spec.min(), Math.min(spec.max(), v));
    }
}
