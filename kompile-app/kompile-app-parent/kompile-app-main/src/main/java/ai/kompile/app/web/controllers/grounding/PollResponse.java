/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.app.web.controllers.grounding;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.List;

/**
 * Response for {@code GET /api/kb-grounding/subscribe/{id}/poll}.
 *
 * <p>Pass {@code nextCursor} as the {@code cursor} query parameter on the subsequent call to
 * drain only new events since the last poll.</p>
 *
 * @param events     events with seq strictly greater than the requested cursor; empty on timeout
 * @param nextCursor the cursor to use in the next poll call
 * @param expiresAt  current TTL of the subscription (informational)
 * @param overflow   true when some events were dropped because the ring buffer was full before
 *                   this poll consumed them — re-query the KB if ordering matters
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PollResponse(
        List<EventDto> events,
        long nextCursor,
        Instant expiresAt,
        boolean overflow
) {

    /**
     * A single event as returned in the poll response.
     *
     * @param seq         monotonically increasing sequence number assigned by the ring buffer
     * @param ts          server-side timestamp
     * @param type        "asserted" | "retracted" | "graph_mutated" | "changed"
     * @param factSheetId the fact-sheet id
     * @param atomKey     the specific atom key; null for sheet-level events
     * @param value       truth value (present for "asserted" events only)
     * @param source      provenance/session id; may be null
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record EventDto(
            long seq,
            Instant ts,
            String type,
            long factSheetId,
            String atomKey,
            Double value,
            String source
    ) {}
}
