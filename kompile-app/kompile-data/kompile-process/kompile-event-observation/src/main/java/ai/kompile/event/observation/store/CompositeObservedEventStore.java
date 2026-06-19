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
package ai.kompile.event.observation.store;

import ai.kompile.event.observation.config.EventObservationConfigService;
import ai.kompile.event.observation.domain.EventObservationRecord;
import ai.kompile.event.observation.domain.EventType;
import ai.kompile.event.observation.domain.ObservedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * The {@code @Primary} {@link ObservedEventStore} consumed by the services. Realises the user's
 * "store in both" requirement: it dual-writes to every backend named in
 * {@code event-observation-config.json}'s {@code storageBackends} (default {@code ["jpa","vector"]})
 * and reads from the first read-capable enabled backend (JPA).
 */
@Service
@Primary
public class CompositeObservedEventStore implements ObservedEventStore {

    private static final Logger log = LoggerFactory.getLogger(CompositeObservedEventStore.class);

    private final List<ObservedEventStoreBackend> backends;
    private final EventObservationConfigService configService;

    public CompositeObservedEventStore(List<ObservedEventStoreBackend> backends,
                                       EventObservationConfigService configService) {
        this.backends = backends == null ? List.of() : backends;
        this.configService = configService;
    }

    private List<ObservedEventStoreBackend> enabledBackends() {
        List<String> names = configService.getConfig().storageBackends();
        List<ObservedEventStoreBackend> enabled = new ArrayList<>();
        for (ObservedEventStoreBackend b : backends) {
            if (names.contains(b.backendName())) {
                enabled.add(b);
            }
        }
        // Safety: never silently write to nothing — fall back to all available backends.
        if (enabled.isEmpty() && !backends.isEmpty()) {
            log.warn("No configured storageBackends matched available backends {}; writing to all",
                    backends.stream().map(ObservedEventStoreBackend::backendName).toList());
            return backends;
        }
        return enabled;
    }

    private Optional<ObservedEventStoreBackend> readBackend() {
        List<ObservedEventStoreBackend> enabled = enabledBackends();
        for (ObservedEventStoreBackend b : enabled) {
            if (b.supportsReads()) {
                return Optional.of(b);
            }
        }
        // No read-capable enabled backend: fall back to any read-capable backend at all.
        for (ObservedEventStoreBackend b : backends) {
            if (b.supportsReads()) {
                return Optional.of(b);
            }
        }
        return enabled.stream().findFirst();
    }

    @Override
    public ObservedEvent save(ObservedEvent event) {
        ObservedEvent authoritative = event;
        for (ObservedEventStoreBackend b : enabledBackends()) {
            try {
                ObservedEvent saved = b.save(event);
                if (b.supportsReads() && saved != null) {
                    authoritative = saved;
                }
            } catch (Exception e) {
                log.warn("Backend '{}' save failed for {}: {}", b.backendName(), event.getEventKey(), e.getMessage());
            }
        }
        return authoritative;
    }

    @Override
    public void appendRecord(EventObservationRecord record) {
        for (ObservedEventStoreBackend b : enabledBackends()) {
            try {
                b.appendRecord(record);
            } catch (Exception e) {
                log.warn("Backend '{}' appendRecord failed: {}", b.backendName(), e.getMessage());
            }
        }
    }

    @Override
    public Optional<ObservedEvent> findByKey(String eventKey) {
        return readBackend().flatMap(b -> b.findByKey(eventKey));
    }

    @Override
    public List<ObservedEvent> findBySubjectNodeId(String nodeId) {
        return readBackend().map(b -> b.findBySubjectNodeId(nodeId)).orElse(Collections.emptyList());
    }

    @Override
    public Optional<ObservedEvent> findByConnection(String sourceNodeId, String edgeType, String targetNodeId) {
        return readBackend().flatMap(b -> b.findByConnection(sourceNodeId, edgeType, targetNodeId));
    }

    @Override
    public List<ObservedEvent> topEvents(Long factSheetId, EventType type, int limit) {
        return readBackend().map(b -> b.topEvents(factSheetId, type, limit)).orElse(Collections.emptyList());
    }

    @Override
    public List<ObservedEvent> findAll() {
        return readBackend().map(ObservedEventStore::findAll).orElse(Collections.emptyList());
    }

    @Override
    public List<EventObservationRecord> history(String eventKey) {
        return readBackend().map(b -> b.history(eventKey)).orElse(Collections.emptyList());
    }
}
