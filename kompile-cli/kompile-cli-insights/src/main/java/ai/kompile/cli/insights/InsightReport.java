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

import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.Builder;
import lombok.Value;

/** One answer: a headline, the table and notes as plain text, and an optional chart description. */
@Value
@Builder(toBuilder = true)
public class InsightReport {

    String topic;
    /** One line that answers the question on its own. */
    String headline;
    /** Tables, sparklines and notes, ready for a terminal or an agent's context. */
    String text;
    /** What the web tool card draws (see {@link Charts}); null when there is nothing to chart. */
    ObjectNode chart;
}
