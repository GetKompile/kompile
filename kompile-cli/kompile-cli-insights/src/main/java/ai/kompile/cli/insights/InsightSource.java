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
import java.util.List;

/** One kind of session data that {@link Insights} can answer questions about. */
public interface InsightSource {

    /** The topic name a caller passes, lower-case ("judge", "tools", "tests"). */
    String topic();

    /** What the topic covers, for the tool description. */
    String description();

    /**
     * Lower-case word stems that suggest this topic when the caller names none. A stem matches
     * the start of a word in the question ("slow" matches "slowest"). The first stem is the
     * topic's own word and counts double, so "judge latency" goes to the judge, not to tools.
     */
    List<String> keywords();

    InsightReport report(InsightsQuery query) throws IOException;

    /**
     * The one line the overview shows for this topic. The default builds the whole report; a
     * source whose report is costly (a graph load, a server round trip per item) answers from
     * cheaper data instead.
     */
    default String headline(InsightsQuery query) throws IOException {
        return report(query).getHeadline();
    }

    /**
     * This topic's row in the live session panel, for the session the query names. The panel
     * refreshes after every tool call, so a source answers from cached state or from what was
     * appended since its previous call, never a full scan. Null leaves the topic off the panel.
     */
    default Panel.Line panelLine(InsightsQuery query) throws IOException {
        return null;
    }

    /** The files whose change should refresh this topic's panel row. */
    default List<Panel.Watch> panelWatches(InsightsQuery query) {
        return List.of();
    }
}
