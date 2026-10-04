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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.fail;

/** Fixtures and readers shared by the source tests. */
final class Reports {

    private Reports() {
    }

    /** The report's lines with runs of spaces collapsed, so a table row reads as its cells. */
    static List<String> lines(InsightReport report) {
        return List.of(report.getText().replaceAll(" +", " ").split("\n"));
    }

    /** Asserts that the report has each of {@code expected} as a whole line, in this order. */
    static void assertLines(InsightReport report, String... expected) {
        List<String> lines = lines(report);
        int from = 0;
        for (String line : expected) {
            int at = lines.subList(from, lines.size()).indexOf(line);
            if (at < 0) {
                fail("No line '" + line + "' after line " + from + " in:\n" + report.getText());
            }
            from += at + 1;
        }
    }

    /** One chart series' values as JSON, such as {@code [0,1,null]}; null when there is no such series. */
    static String series(ObjectNode chart, String name) {
        for (JsonNode entry : chart.path("series")) {
            if (name.equals(entry.path("name").asText())) {
                return entry.path("values").toString();
            }
        }
        return null;
    }

    static String labels(ObjectNode chart) {
        return chart.path("labels").toString();
    }

    /** JSON written with single quotes, which read better in a Java string. */
    static String json(String singleQuoted) {
        return singleQuoted.replace('\'', '"');
    }

    /** Writes {@code lines} as a JSON-lines file last modified at {@code modified}. */
    static Path writeLines(Path file, String modified, String... lines) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, jsonLines(lines), StandardCharsets.UTF_8);
        Files.setLastModifiedTime(file, FileTime.from(Instant.parse(modified)));
        return file;
    }

    /** Appends {@code lines} to a JSON-lines file, as a session's writer does while the panel follows it. */
    static void appendLines(Path file, String... lines) throws IOException {
        Files.writeString(file, jsonLines(lines), StandardCharsets.UTF_8, StandardOpenOption.APPEND);
    }

    private static String jsonLines(String... lines) {
        StringBuilder content = new StringBuilder();
        for (String line : lines) {
            content.append(json(line)).append('\n');
        }
        return content.toString();
    }
}
