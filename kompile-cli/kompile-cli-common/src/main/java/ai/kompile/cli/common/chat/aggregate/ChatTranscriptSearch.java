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

package ai.kompile.cli.common.chat.aggregate;

import ai.kompile.cli.common.chat.sources.ChatSessionSummary;
import ai.kompile.cli.common.chat.sources.ChatSourceAdapter;
import ai.kompile.cli.common.chat.sources.ChatSourceRegistry;
import ai.kompile.cli.common.chat.sources.ChatTurn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Grep-style search across one or more chat sources. Matches are emitted as
 * {@link Hit} records that include the source, session, turn index, matched
 * content, and optional neighbouring turns (context).
 *
 * <p>Runs by scanning each session from each selected source. This is O(turns)
 * per session; callers should pass a reasonable {@code limit} to avoid
 * unbounded work.
 */
public class ChatTranscriptSearch {

    private static final Logger log = LoggerFactory.getLogger(ChatTranscriptSearch.class);

    private final ChatSourceRegistry registry;

    public ChatTranscriptSearch(ChatSourceRegistry registry) {
        this.registry = registry;
    }

    public ChatTranscriptSearch() {
        this(ChatSourceRegistry.getInstance());
    }

    public List<Hit> search(Query query) {
        List<Hit> hits = new ArrayList<>();
        Matcher matcher = buildMatcher(query);
        if (matcher == null) return hits;
        int limit = query.limit <= 0 ? Integer.MAX_VALUE : query.limit;
        for (ChatSourceAdapter adapter : registry.all()) {
            if (hits.size() >= limit) break;
            if (query.sources != null && !query.sources.isEmpty()
                    && !query.sources.contains(adapter.id())) continue;
            try {
                for (ChatSessionSummary session : adapter.list()) {
                    if (hits.size() >= limit) break;
                    List<ChatTurn> turns;
                    try {
                        turns = adapter.readTurns(session.sessionId());
                    } catch (Exception e) {
                        log.debug("Adapter {} readTurns({}) failed: {}",
                                adapter.id(), session.sessionId(), e.getMessage());
                        continue;
                    }
                    for (int i = 0; i < turns.size() && hits.size() < limit; i++) {
                        ChatTurn turn = turns.get(i);
                        if (turn.content() == null) continue;
                        if (!matcher.matches(turn.content())) continue;
                        hits.add(new Hit(
                                adapter.id(), session.sessionId(),
                                session.title(), i, turn.role(),
                                turn.content(),
                                collectContext(turns, i, query.contextLines)));
                    }
                }
            } catch (Exception e) {
                log.warn("Adapter {} list() failed during search: {}", adapter.id(), e.getMessage());
            }
        }
        return hits;
    }

    private static Matcher buildMatcher(Query query) {
        if (query.pattern == null || query.pattern.isEmpty()) return null;
        if (query.regex) {
            try {
                Pattern p = Pattern.compile(query.pattern,
                        query.caseInsensitive ? Pattern.CASE_INSENSITIVE : 0);
                return text -> p.matcher(text).find();
            } catch (PatternSyntaxException e) {
                log.warn("Invalid regex '{}': {}", query.pattern, e.getMessage());
                return null;
            }
        }
        String needle = query.caseInsensitive
                ? query.pattern.toLowerCase(Locale.ROOT)
                : query.pattern;
        if (query.caseInsensitive) {
            return text -> text.toLowerCase(Locale.ROOT).contains(needle);
        }
        return text -> text.contains(needle);
    }

    private static List<ContextTurn> collectContext(List<ChatTurn> turns, int index, int contextLines) {
        if (contextLines <= 0) return List.of();
        List<ContextTurn> out = new ArrayList<>();
        int from = Math.max(0, index - contextLines);
        int to = Math.min(turns.size() - 1, index + contextLines);
        for (int i = from; i <= to; i++) {
            if (i == index) continue;
            ChatTurn t = turns.get(i);
            out.add(new ContextTurn(i, t.role(), t.content()));
        }
        return out;
    }

    @FunctionalInterface
    private interface Matcher {
        boolean matches(String text);
    }

    public static final class Query {
        private String pattern;
        private Collection<String> sources;
        private int contextLines;
        private int limit = 500;
        private boolean regex;
        private boolean caseInsensitive;

        public Query pattern(String pattern) { this.pattern = pattern; return this; }
        public Query sources(Collection<String> sources) { this.sources = sources; return this; }
        public Query contextLines(int n) { this.contextLines = n; return this; }
        public Query limit(int n) { this.limit = n; return this; }
        public Query regex(boolean regex) { this.regex = regex; return this; }
        public Query caseInsensitive(boolean v) { this.caseInsensitive = v; return this; }
    }

    public record Hit(
            String source,
            String sessionId,
            String sessionTitle,
            int turnIndex,
            String role,
            String content,
            List<ContextTurn> context
    ) {}

    public record ContextTurn(int turnIndex, String role, String content) {}
}
