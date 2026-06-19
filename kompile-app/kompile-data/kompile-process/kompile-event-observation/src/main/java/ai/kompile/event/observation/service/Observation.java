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
package ai.kompile.event.observation.service;

/**
 * A single Binomial observation: {@code occurrences} successes out of {@code opportunities} trials.
 * The invariant {@code 0 <= occurrences <= opportunities} is enforced at construction.
 */
public record Observation(long occurrences, long opportunities) {

    public Observation {
        if (occurrences < 0) {
            occurrences = 0;
        }
        if (opportunities < occurrences) {
            opportunities = occurrences;
        }
    }

    public boolean isEmpty() {
        return opportunities <= 0;
    }
}
