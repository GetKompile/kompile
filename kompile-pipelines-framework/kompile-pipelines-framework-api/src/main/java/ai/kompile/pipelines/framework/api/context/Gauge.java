/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.pipelines.framework.api.context;

import java.util.function.Supplier;

/**
 * Represents a gauge metric, a value that can arbitrarily go up and down.
 * Gauges are typically used for measured values like temperatures, queue sizes, or memory usage.
 * The value is usually obtained via a {@link Supplier}.
 */
public interface Gauge {
    /**
     * Sets the {@link Supplier} that will provide the value for this gauge.
     * The supplier will be called whenever the gauge's value is queried by the metrics system.
     *
     * @param supplier The supplier of the gauge's value. Must not be null.
     * The {@link Number} returned by the supplier will be converted to a double.
     */
    void setSupplier(Supplier<Number> supplier);

    /**
     * Gets the current value of the gauge.
     * This typically invokes the registered supplier.
     *
     * @return The current value.
     */
    double value();

    /**
     * Gets the name of this gauge.
     * @return The gauge name.
     */
    String name();

    /**
     * Gets the help/description text for this gauge.
     * @return The help text, or an empty string if none.
     */
    String help();

    /**
     * Gets the tags associated with this gauge.
     * @return An array of strings representing key-value pairs (key1, value1, key2, value2,...).
     * Returns an empty array if no tags.
     */
    String[] tags();
}