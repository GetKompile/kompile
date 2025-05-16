/*
 *  Copyright 2025 Kompile Inc.
 *  *
 *  * Licensed under the Apache License, Version 2.0 (the "License");
 *  * you may not use this file except in compliance with the License.
 *  * You may obtain a copy of the License at
 *  *
 *  * http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  * Unless required by applicable law or agreed to in writing, software
 *  * distributed under the License is distributed on an "AS IS" BASIS,
 *  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  * See the License for the specific language governing permissions and
 *  * limitations under the License.
 */

package ai.kompile.pipelines.framework.api.context;

/**
 * Represents a counter metric, a value that can only be incremented.
 * Useful for tracking occurrences of events, such as the number of items processed or errors.
 */
public interface Counter {
    /**
     * Increments the counter by 1.
     */
    void increment();

    /**
     * Increments the counter by the given non-negative amount.
     *
     * @param amount The amount to increment by. Must be non-negative.
     * @throws IllegalArgumentException if amount is negative.
     */
    void increment(double amount);

    /**
     * Gets the current count.
     *
     * @return The current value of the counter.
     */
    double count();

    /**
     * Gets the name of this counter.
     * @return The counter name.
     */
    String name();

    /**
     * Gets the help/description text for this counter.
     * @return The help text, or an empty string if none.
     */
    String help();

    /**
     * Gets the tags associated with this counter.
     * @return An array of strings representing key-value pairs (key1, value1, key2, value2,...).
     * Returns an empty array if no tags.
     */
    String[] tags();
}