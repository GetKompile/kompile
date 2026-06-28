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

package ai.kompile.app.subprocess;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Central fan-out for {@link SubprocessLogEvent}s emitted by every managed
 * subprocess (via {@link ManagedSubprocessLauncher}).
 *
 * <p>This is the single seam that decouples subprocess log/lifecycle producers
 * from consumers. Producers call {@link #publish(SubprocessLogEvent)}; consumers
 * (a central {@code ~/.kompile/logs} writer, the crawl UI's SSE publisher, a CLI
 * tail) register a {@link SubprocessLogSink}.</p>
 *
 * <p>Two registration paths:</p>
 * <ul>
 *   <li><b>Static</b> — any {@link SubprocessLogSink} Spring bean is auto-collected
 *       and always receives events (e.g. the durable log writer).</li>
 *   <li><b>Dynamic</b> — {@link #register(SubprocessLogSink)} /
 *       {@link #unregister(SubprocessLogSink)} let a crawl subscribe a per-job
 *       listener for the duration of a step and detach it afterward.</li>
 * </ul>
 *
 * <p>Fan-out happens on the calling (subprocess reader) thread and swallows sink
 * exceptions so one bad consumer can never stall log draining for the others.</p>
 */
@Component
public class SubprocessLogBus {

    private static final Logger log = LoggerFactory.getLogger(SubprocessLogBus.class);

    private final List<SubprocessLogSink> staticSinks;
    private final CopyOnWriteArrayList<SubprocessLogSink> dynamicSinks = new CopyOnWriteArrayList<>();

    @Autowired(required = false)
    public SubprocessLogBus(List<SubprocessLogSink> staticSinks) {
        this.staticSinks = staticSinks != null ? staticSinks : List.of();
    }

    public SubprocessLogBus() {
        this.staticSinks = List.of();
    }

    @PostConstruct
    void announce() {
        log.info("SubprocessLogBus ready with {} static sink(s)", staticSinks.size());
    }

    /**
     * Register a dynamic sink (e.g. a crawl's per-job listener). Idempotent.
     */
    public void register(SubprocessLogSink sink) {
        if (sink != null) {
            dynamicSinks.addIfAbsent(sink);
        }
    }

    /**
     * Remove a previously {@link #register(SubprocessLogSink)}ed dynamic sink.
     */
    public void unregister(SubprocessLogSink sink) {
        if (sink != null) {
            dynamicSinks.remove(sink);
        }
    }

    /**
     * Fan an event out to every static and dynamic sink. Never throws.
     */
    public void publish(SubprocessLogEvent event) {
        if (event == null) {
            return;
        }
        for (SubprocessLogSink sink : staticSinks) {
            deliver(sink, event);
        }
        for (SubprocessLogSink sink : dynamicSinks) {
            deliver(sink, event);
        }
    }

    private void deliver(SubprocessLogSink sink, SubprocessLogEvent event) {
        try {
            sink.onLog(event);
        } catch (Exception e) {
            // A consumer must never be able to stall log draining for the others.
            log.debug("SubprocessLogSink {} threw on event {}: {}",
                    sink.getClass().getSimpleName(), event.subprocessId(), e.toString());
        }
    }
}
