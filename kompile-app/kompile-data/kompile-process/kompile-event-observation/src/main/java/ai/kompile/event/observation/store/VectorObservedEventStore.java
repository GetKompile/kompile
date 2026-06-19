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

import ai.kompile.core.embeddings.VectorStore;
import ai.kompile.event.observation.domain.EventObservationRecord;
import ai.kompile.event.observation.domain.EventType;
import ai.kompile.event.observation.domain.ObservedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Mirrors each event's prior into the {@link VectorStore} as a {@code event-prior:{type}:{key}}
 * document, so priors are searchable and persist alongside the rest of the graph state (the same
 * pattern {@code VectorStoreMatrixGraphStore} uses for graph nodes/stats).
 *
 * <p>This is a write-side mirror: the JPA backend remains the read authority (and must stay enabled
 * for reads). Reads here are intentionally inert — the composite always prefers a read-capable
 * backend. Writes are best-effort and never propagate failures to the caller.</p>
 */
@Service
public class VectorObservedEventStore implements ObservedEventStoreBackend {

    private static final Logger log = LoggerFactory.getLogger(VectorObservedEventStore.class);
    private static final String DOC_PREFIX = "event-prior:";

    private final VectorStore vectorStore;

    public VectorObservedEventStore(@Autowired(required = false) VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    @Override
    public String backendName() {
        return "vector";
    }

    @Override
    public boolean supportsReads() {
        return false;
    }

    @Override
    public ObservedEvent save(ObservedEvent event) {
        if (vectorStore == null || event == null || event.getEventKey() == null) {
            return event;
        }
        try {
            String docId = DOC_PREFIX + event.getEventType() + ":" + event.getEventKey();
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("type", "event_prior");
            meta.put("eventKey", event.getEventKey());
            meta.put("eventType", event.getEventType() == null ? null : event.getEventType().name());
            meta.put("probability", event.probability());
            meta.put("alpha", event.getAlpha());
            meta.put("beta", event.getBeta());
            meta.put("occurrences", event.getOccurrenceCount());
            meta.put("opportunities", event.getOpportunityCount());
            if (event.getSubjectNodeId() != null) {
                meta.put("subjectNodeId", event.getSubjectNodeId());
            }
            if (event.getFactSheetId() != null) {
                meta.put("factSheetId", event.getFactSheetId());
            }
            String content = "Empirical event prior " + event.getEventKey()
                    + " p=" + String.format("%.4f", event.probability())
                    + " (" + event.getOccurrenceCount() + "/" + event.getOpportunityCount() + ")";
            vectorStore.add(List.of(new Document(docId, content, meta)));
            vectorStore.flushAndCommit();
        } catch (Exception e) {
            log.debug("Vector mirror of event prior {} failed: {}", event.getEventKey(), e.getMessage());
        }
        return event;
    }

    @Override
    public void appendRecord(EventObservationRecord record) {
        // The observation ledger (time-series) is a JPA concern; not mirrored to the vector store.
    }

    // ── Reads are inert; the JPA backend is the read authority ──────────────────

    @Override
    public Optional<ObservedEvent> findByKey(String eventKey) {
        return Optional.empty();
    }

    @Override
    public List<ObservedEvent> findBySubjectNodeId(String nodeId) {
        return Collections.emptyList();
    }

    @Override
    public Optional<ObservedEvent> findByConnection(String sourceNodeId, String edgeType, String targetNodeId) {
        return Optional.empty();
    }

    @Override
    public List<ObservedEvent> topEvents(Long factSheetId, EventType type, int limit) {
        return Collections.emptyList();
    }

    @Override
    public List<ObservedEvent> findAll() {
        return Collections.emptyList();
    }

    @Override
    public List<EventObservationRecord> history(String eventKey) {
        return Collections.emptyList();
    }
}
