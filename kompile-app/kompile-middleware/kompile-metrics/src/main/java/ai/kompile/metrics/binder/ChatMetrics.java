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

package ai.kompile.metrics.binder;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Metrics for chat history sessions and messages.
 *
 * Counters:
 * <ul>
 *   <li>{@code kompile.chat.sessions.created} – total chat sessions created</li>
 *   <li>{@code kompile.chat.messages.total} – total messages sent across all sessions</li>
 * </ul>
 *
 * Gauges:
 * <ul>
 *   <li>{@code kompile.chat.sessions.total} – current total session count (polled)</li>
 * </ul>
 */
public class ChatMetrics {

    private final MeterRegistry registry;
    private final AtomicLong totalSessions = new AtomicLong(0);

    private Counter sessionsCreated;
    private Counter messagesTotal;

    public ChatMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    @PostConstruct
    public void bindMetrics() {
        sessionsCreated = Counter.builder("kompile.chat.sessions.created")
                .description("Total chat sessions created").register(registry);
        messagesTotal = Counter.builder("kompile.chat.messages.total")
                .description("Total messages sent across all sessions").register(registry);

        Gauge.builder("kompile.chat.sessions.total", totalSessions, AtomicLong::get)
                .description("Current total session count").register(registry);
    }

    public void recordSessionCreated() {
        sessionsCreated.increment();
        totalSessions.incrementAndGet();
    }

    public void recordMessageSent(String source) {
        messagesTotal.increment();
        registry.counter("kompile.chat.messages.total.by_source", "source", source).increment();
    }

    public void setTotalSessions(long count) {
        totalSessions.set(count);
    }
}
