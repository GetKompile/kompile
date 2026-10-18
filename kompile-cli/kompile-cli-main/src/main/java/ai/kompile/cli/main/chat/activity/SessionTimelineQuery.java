package ai.kompile.cli.main.chat.activity;

import java.time.Instant;
import java.util.Objects;

/**
 * Exact, case-sensitive selectors; null means unrestricted. Kind is an exact event
 * type (tool.end) or exact family (tool); no substring or wildcard matching.
 * Time bounds are inclusive.
 */
public record SessionTimelineQuery(ActivityIdentity identity, String runId, String kind,
                                   String outcome, Instant from, Instant to,
                                   int limit, int maxBytes) {
    public SessionTimelineQuery {
        Objects.requireNonNull(identity, "identity");
        if (limit < 1 || limit > 500) throw new IllegalArgumentException("limit must be 1..500");
        if (maxBytes < 1 || maxBytes > 1024 * 1024)
            throw new IllegalArgumentException("maxBytes must be 1..1048576");
        if (from != null && to != null && from.isAfter(to))
            throw new IllegalArgumentException("from must not follow to");
    }

    public static SessionTimelineQuery recent(ActivityIdentity identity) {
        return new SessionTimelineQuery(identity, null, null, null, null, null, 50, 256 * 1024);
    }
}
