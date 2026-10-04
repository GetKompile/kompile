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

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * The live session panel {@link Insights#panel} builds: one summary line per topic, each topic's
 * detail beneath its summary where rows allow, and what a caller needs to keep it current.
 *
 * @param lines   the panel's rows, in order
 * @param live    true while some topic is changing on its own (a running crawl), so the caller
 *                refreshes on a short timer as well as on file changes
 * @param watches the files whose change should refresh the panel
 */
public record Panel(List<String> lines, boolean live, List<Watch> watches) {

    public Panel {
        lines = List.copyOf(lines);
        watches = List.copyOf(watches);
    }

    /**
     * One topic's contribution.
     *
     * @param text   the summary row; the most important words come first, since a narrow
     *               terminal cuts the end off
     * @param detail a second row shown beneath the summary when there is room, or null
     * @param live   true while the topic is changing on its own
     */
    public record Line(String text, String detail, boolean live) {

        public Line {
            Objects.requireNonNull(text, "text");
        }

        public static Line of(String text) {
            return new Line(text, null, false);
        }
    }

    /**
     * A directory to watch, and the entry in it whose change refreshes the panel.
     *
     * @param directory the directory to register; it must exist when the watch is registered
     * @param fileName  the entry to react to, or null for any entry
     */
    public record Watch(Path directory, String fileName) {

        public Watch {
            Objects.requireNonNull(directory, "directory");
        }

        public boolean matches(String entry) {
            return fileName == null || fileName.equals(entry);
        }
    }
}
