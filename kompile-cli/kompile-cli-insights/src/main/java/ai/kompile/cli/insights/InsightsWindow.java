/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.cli.insights;

import java.time.Instant;

/**
 * The time range a report covers: {@code start} and {@code end} are both inclusive.
 *
 * @param label    how the range reads in a headline ("last 7 days", "today", "all time")
 * @param explicit true when the question named the range; false for the configured default
 */
public record InsightsWindow(Instant start, Instant end, String label, boolean explicit) {

    public boolean contains(Instant at) {
        return at != null && !at.isBefore(start) && !at.isAfter(end);
    }

    public boolean contains(long epochMs) {
        return contains(Instant.ofEpochMilli(epochMs));
    }

    public boolean allTime() {
        return Instant.EPOCH.equals(start);
    }
}
