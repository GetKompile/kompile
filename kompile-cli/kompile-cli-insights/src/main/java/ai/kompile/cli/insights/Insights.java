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

package ai.kompile.cli.insights;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Answers questions about chat-session data from the registered {@link InsightSource}s. The
 * topic is the caller's, or else inferred from the question's words; with neither, or with
 * {@value #OVERVIEW}, every source contributes its headline.
 */
public final class Insights {

    public static final String OVERVIEW = "overview";

    private final Map<String, InsightSource> sources = new LinkedHashMap<>();

    public Insights register(InsightSource source) {
        if (OVERVIEW.equals(source.topic())) {
            throw new IllegalArgumentException("'" + OVERVIEW + "' is reserved for the combined report");
        }
        sources.put(source.topic(), source);
        return this;
    }

    /** Registered topics, in registration order. */
    public Collection<String> topics() {
        return Collections.unmodifiableSet(sources.keySet());
    }

    public Collection<InsightSource> sources() {
        return Collections.unmodifiableCollection(sources.values());
    }

    public InsightSource source(String topic) {
        return sources.get(topic);
    }

    /**
     * @throws IllegalArgumentException when the query names a topic no source covers
     */
    public InsightReport answer(InsightsQuery query) throws IOException {
        String topic = query.getTopic();
        if (topic == null) {
            topic = inferTopic(query.getQuestion());
        }
        if (topic == null || OVERVIEW.equals(topic)) {
            return overview(query);
        }
        InsightSource source = sources.get(topic);
        if (source == null) {
            throw new IllegalArgumentException("Unknown insights topic '" + topic + "'. Topics: "
                    + String.join(", ", topics()) + ", " + OVERVIEW);
        }
        return source.report(query);
    }

    /**
     * The live session panel, at most {@code maxLines} rows: each source's
     * {@link InsightSource#panelLine} in registration order. Every topic's summary comes first;
     * details fill the rows left over, a live topic's (a running crawl) before the others, and
     * each sits beneath its summary. A source that fails says so on its row, and the rest still show.
     */
    public Panel panel(InsightsQuery query, int maxLines) {
        int rows = Math.max(1, maxLines);
        List<Panel.Line> lines = new ArrayList<>();
        Set<Panel.Watch> watches = new LinkedHashSet<>();
        for (InsightSource source : sources.values()) {
            Panel.Line line;
            try {
                line = source.panelLine(query);
            } catch (Exception e) {
                String why = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                line = Panel.Line.of(label(source.topic()) + ": unavailable (" + Format.clamp(why, 80) + ")");
            }
            if (line != null && lines.size() < rows) {
                lines.add(line);
            }
            try {
                watches.addAll(source.panelWatches(query));
            } catch (RuntimeException e) {
                // Without its watches the row still refreshes after each tool call.
            }
        }
        int room = rows - lines.size();
        boolean[] detailed = new boolean[lines.size()];
        for (boolean liveFirst : new boolean[]{true, false}) {
            for (int i = 0; i < lines.size() && room > 0; i++) {
                Panel.Line line = lines.get(i);
                if (!detailed[i] && line.live() == liveFirst && line.detail() != null && !line.detail().isBlank()) {
                    detailed[i] = true;
                    room--;
                }
            }
        }
        List<String> rendered = new ArrayList<>();
        boolean live = false;
        for (int i = 0; i < lines.size(); i++) {
            Panel.Line line = lines.get(i);
            rendered.add(line.text());
            if (detailed[i]) {
                rendered.add(line.detail());
            }
            live |= line.live();
        }
        return new Panel(rendered, live, List.copyOf(watches));
    }

    private static String label(String topic) {
        return topic.isEmpty() ? topic : Character.toUpperCase(topic.charAt(0)) + topic.substring(1);
    }

    /** The source whose keywords the question hits most, or null on a tie or no hit. */
    String inferTopic(String question) {
        if (question == null || question.isBlank()) {
            return null;
        }
        String[] words = question.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+");
        String best = null;
        int bestScore = 0;
        boolean tie = false;
        for (InsightSource source : sources.values()) {
            int score = score(source.keywords(), words);
            if (score > bestScore) {
                best = source.topic();
                bestScore = score;
                tie = false;
            } else if (score > 0 && score == bestScore) {
                tie = true;
            }
        }
        return tie ? null : best;
    }

    private static int score(List<String> keywords, String[] words) {
        int score = 0;
        for (int k = 0; k < keywords.size(); k++) {
            String stem = keywords.get(k);
            for (String word : words) {
                if (!word.isEmpty() && word.startsWith(stem)) {
                    score += k == 0 ? 2 : 1;
                    break;
                }
            }
        }
        return score;
    }

    private InsightReport overview(InsightsQuery query) {
        List<String> lines = new ArrayList<>();
        for (InsightSource source : sources.values()) {
            String headline;
            try {
                headline = source.headline(query);
            } catch (Exception e) {
                headline = source.topic() + ": unavailable (" + Format.clamp(String.valueOf(e.getMessage()), 120) + ")";
            }
            lines.add(headline);
        }
        StringBuilder text = new StringBuilder();
        lines.forEach(line -> text.append(line).append('\n'));
        text.append("\nAsk about one topic for detail: ").append(String.join(", ", topics())).append('\n');
        return InsightReport.builder()
                .topic(OVERVIEW)
                .headline("Overview, " + query.getWindow().label() + ": " + sources.size() + " topics")
                .text(text.toString())
                .build();
    }
}
