/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.kclaw.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class KompileCliRunnerTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void parseOutput_prefersFinalResultText() {
        List<String> lines = List.of(
                "{\"type\":\"session\",\"session_id\":\"s1\",\"model\":\"m\"}",
                "{\"type\":\"text\",\"text\":\"Hello \"}",
                "{\"type\":\"text\",\"text\":\"world\"}",
                "{\"type\":\"tool\",\"name\":\"bash\",\"ok\":true,\"ms\":5}",
                "{\"type\":\"result\",\"text\":\"Hello world (final)\",\"session_id\":\"s1\",\"tools\":1,\"exit\":0}");
        KompileCliRunner.ParsedOutput out = KompileCliRunner.parseOutput(mapper, lines);
        assertEquals("Hello world (final)", out.text());
        assertNull(out.error());
    }

    @Test
    void parseOutput_fallsBackToTextDeltas() {
        List<String> lines = List.of(
                "{\"type\":\"text\",\"text\":\"part1 \"}",
                "{\"type\":\"text\",\"text\":\"part2\"}");
        KompileCliRunner.ParsedOutput out = KompileCliRunner.parseOutput(mapper, lines);
        assertEquals("part1 part2", out.text());
        assertNull(out.error());
    }

    @Test
    void parseOutput_capturesErrorEvent() {
        List<String> lines = List.of("{\"type\":\"error\",\"message\":\"boom\"}");
        KompileCliRunner.ParsedOutput out = KompileCliRunner.parseOutput(mapper, lines);
        assertEquals("boom", out.error());
    }

    @Test
    void parseOutput_ignoresNonJsonAndBlankLines() {
        List<String> lines = Arrays.asList(
                "  [INFO] some log line  ",
                "",
                null,
                "not json",
                "{\"type\":\"result\",\"text\":\"ok\",\"exit\":0}");
        KompileCliRunner.ParsedOutput out = KompileCliRunner.parseOutput(mapper, lines);
        assertEquals("ok", out.text());
    }
}
