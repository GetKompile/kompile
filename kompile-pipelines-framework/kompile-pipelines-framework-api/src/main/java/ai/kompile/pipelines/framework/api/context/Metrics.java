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

/**
 * Interface for collecting metrics during pipeline execution.
 * Allows creating and updating counters, timers, and gauges.
 * The actual metrics backend (e.g., Micrometer, Prometheus, Dropwizard Metrics) is abstracted.
 * Implementations are provided by the core framework or specific metrics modules.
 */
public interface Metrics {

    /**
     * Creates or gets a counter with the given name and tags.
     * Counters can only be incremented.
     *
     * @param name The name of the counter. Should follow a consistent naming convention (e.g., dot.separated.case).
     * @param help Optional help text or description for the counter.
     * @param tags Optional tags (key-value pairs) to associate with the counter, e.g., "stepName", "myStep", "outcome", "success".
     * Must be an even number of strings.
     * @return A {@link Counter} instance. Never null (could be a No-Op implementation).
     */
    Counter counter(String name, String help, String... tags);

    /**
     * Creates or gets a timer with the given name and tags.
     * Timers measure durations of events.
     *
     * @param name The name of the timer. Should follow a consistent naming convention.
     * @param help Optional help text or description for the timer.
     * @param tags Optional tags (key-value pairs) to associate with the timer.
     * Must be an even number of strings.
     * @return A {@link Timer} instance. Never null (could be a No-Op implementation).
     */
    Timer timer(String name, String help, String... tags);

    /**
     * Creates or gets a gauge with the given name and tags.
     * Gauges represent a value that can arbitrarily go up and down.
     * The gauge's value is typically supplied by a {@link java.util.function.Supplier}.
     *
     * @param name The name of the gauge. Should follow a consistent naming convention.
     * @param help Optional help text or description for the gauge.
     * @param tags Optional tags (key-value pairs) to associate with the gauge.
     * Must be an even number of strings.
     * @return A {@link Gauge} instance. Never null (could be a No-Op implementation).
     */
    Gauge gauge(String name, String help, String... tags);
}