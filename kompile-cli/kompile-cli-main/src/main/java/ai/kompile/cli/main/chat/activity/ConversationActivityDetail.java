package ai.kompile.cli.main.chat.activity;

import java.util.List;

/** Bounded detail view for a selected session; it never contains raw streaming payloads. */
public record ConversationActivityDetail(
        ConversationActivitySummary summary,
        List<ActivityEvent> events,
        int malformedEvents,
        boolean truncated,
        List<String> details,
        List<String> warnings) {
    public ConversationActivityDetail(ConversationActivitySummary summary,
                                      List<ActivityEvent> events,
                                      int malformedEvents,
                                      boolean truncated,
                                      List<String> warnings) {
        this(summary, events, malformedEvents, truncated, List.of(), warnings);
    }

    public ConversationActivityDetail {
        summary = summary == null ? ConversationActivitySummary.empty(
                ActivityIdentity.conversation("unknown", null)) : summary;
        events = events == null ? List.of() : List.copyOf(events);
        malformedEvents = Math.max(0, malformedEvents);
        details = details == null ? List.of() : details.stream()
                .filter(java.util.Objects::nonNull).limit(64).toList();
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}
