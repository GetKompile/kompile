package ai.kompile.cli.main.chat.activity;

import java.util.Objects;

/** Continuation over an immutable journal prefix; appends do not shift older pages. */
public record SessionTimelineCursor(SessionTimelineQuery query, String generation,
                                    long snapshotSize, long beforeOffset) {
    public SessionTimelineCursor {
        Objects.requireNonNull(query, "query");
        Objects.requireNonNull(generation, "generation");
        if (snapshotSize < 0 || beforeOffset < 0 || beforeOffset > snapshotSize)
            throw new IllegalArgumentException("Invalid timeline cursor offsets");
    }
}
