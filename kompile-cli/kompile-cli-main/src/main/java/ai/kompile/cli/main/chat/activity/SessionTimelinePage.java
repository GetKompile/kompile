package ai.kompile.cli.main.chat.activity;

import java.util.List;

/** Journal evidence only. Sources are inspection references, never executable actions. */
public record SessionTimelinePage(List<ActivityEvent> events, SessionTimelineCursor nextCursor,
                                  ActivityCoverage coverage, boolean truncated, boolean reset,
                                  List<String> warnings) {
    public SessionTimelinePage {
        events = List.copyOf(events);
        warnings = List.copyOf(warnings);
    }
}
