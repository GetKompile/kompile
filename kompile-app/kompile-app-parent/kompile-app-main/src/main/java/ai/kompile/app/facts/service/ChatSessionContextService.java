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

package ai.kompile.app.facts.service;

import ai.kompile.app.facts.domain.ChatSessionContext;
import ai.kompile.app.facts.repository.ChatSessionContextRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Per-session, per-source context control for chat sessions scoped to a FactSheet.
 *
 * Lets a user toggle each source within a chat session between FULL / SUMMARY_ONLY /
 * EXCLUDED without touching the FactSheet itself or other sessions. The RAG retrieval
 * layer consults {@link #getExcludedFactIds(String)} before ranking so excluded Facts
 * drop out of retrieval for that session.
 */
@Service
@Transactional
public class ChatSessionContextService {

    @Autowired
    private ChatSessionContextRepository repository;

    /** No-arg for Spring AOT / CGLIB proxy creation. */
    public ChatSessionContextService() {
    }

    public ChatSessionContextService(ChatSessionContextRepository repository) {
        this.repository = repository;
    }



    @Transactional(readOnly = true)
    public List<ChatSessionContext> getSessionContexts(String sessionId) {
        return repository.findBySessionId(sessionId);
    }

    /**
     * Set (or update) the mode for a single source within a session.
     * Upserts by (sessionId, factId) — the unique index guarantees one row per pair.
     */
    public ChatSessionContext setSourceMode(String sessionId, Long factSheetId, Long factId,
                                            String displayName, ChatSessionContext.SourceMode mode) {
        Optional<ChatSessionContext> existing = repository.findBySessionIdAndFactId(sessionId, factId);
        ChatSessionContext ctx = existing.orElseGet(() -> ChatSessionContext.builder()
                .sessionId(sessionId)
                .factSheetId(factSheetId)
                .factId(factId)
                .sourceDisplayName(displayName)
                .build());
        ctx.setMode(mode);
        if (displayName != null) ctx.setSourceDisplayName(displayName);
        if (factSheetId != null) ctx.setFactSheetId(factSheetId);
        return repository.save(ctx);
    }

    /**
     * Convenience: factIds explicitly excluded from this session, for use as a
     * retrieval filter.
     */
    @Transactional(readOnly = true)
    public List<Long> getExcludedFactIds(String sessionId) {
        return repository
                .findBySessionIdAndMode(sessionId, ChatSessionContext.SourceMode.EXCLUDED)
                .stream()
                .map(ChatSessionContext::getFactId)
                .collect(Collectors.toList());
    }

    public void clearSession(String sessionId) {
        repository.deleteBySessionId(sessionId);
    }
}
