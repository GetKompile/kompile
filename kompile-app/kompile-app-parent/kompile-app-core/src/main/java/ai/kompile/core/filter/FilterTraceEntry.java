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

package ai.kompile.core.filter;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Map;

/**
 * A trace entry recording filter execution for debugging and observability.
 * Filters can emit structured logs and traces that are collected for analysis.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FilterTraceEntry {

    /**
     * Log level for the trace entry.
     */
    public enum Level {
        DEBUG,
        INFO,
        WARNING,
        ERROR
    }

    /**
     * The ID of the filter that emitted this trace.
     */
    private String filterId;

    /**
     * The phase in which this trace was emitted.
     */
    private FilterPhase phase;

    /**
     * The log level of this trace.
     */
    @Builder.Default
    private Level level = Level.INFO;

    /**
     * The trace message.
     */
    private String message;

    /**
     * When the trace was emitted.
     */
    @Builder.Default
    private Instant timestamp = Instant.now();

    /**
     * Execution duration in milliseconds (if measuring performance).
     */
    private Long durationMs;

    /**
     * Additional structured data for the trace.
     */
    private Map<String, Object> metadata;

    /**
     * Create a debug trace entry.
     */
    public static FilterTraceEntry debug(String filterId, String message) {
        return FilterTraceEntry.builder()
                .filterId(filterId)
                .level(Level.DEBUG)
                .message(message)
                .timestamp(Instant.now())
                .build();
    }

    /**
     * Create an info trace entry.
     */
    public static FilterTraceEntry info(String filterId, String message) {
        return FilterTraceEntry.builder()
                .filterId(filterId)
                .level(Level.INFO)
                .message(message)
                .timestamp(Instant.now())
                .build();
    }

    /**
     * Create a warning trace entry.
     */
    public static FilterTraceEntry warning(String filterId, String message) {
        return FilterTraceEntry.builder()
                .filterId(filterId)
                .level(Level.WARNING)
                .message(message)
                .timestamp(Instant.now())
                .build();
    }

    /**
     * Create an error trace entry.
     */
    public static FilterTraceEntry error(String filterId, String message) {
        return FilterTraceEntry.builder()
                .filterId(filterId)
                .level(Level.ERROR)
                .message(message)
                .timestamp(Instant.now())
                .build();
    }

    /**
     * Create a performance trace entry with duration.
     */
    public static FilterTraceEntry performance(String filterId, FilterPhase phase, long durationMs) {
        return FilterTraceEntry.builder()
                .filterId(filterId)
                .phase(phase)
                .level(Level.DEBUG)
                .message("Filter executed")
                .durationMs(durationMs)
                .timestamp(Instant.now())
                .build();
    }
}
