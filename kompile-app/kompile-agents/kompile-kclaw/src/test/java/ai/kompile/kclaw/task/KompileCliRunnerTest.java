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
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class KompileCliRunnerTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @TempDir
    Path tempDir;

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

    @Test
    void runUsesStdinAndConcurrentlyDrainsStderr() throws Exception {
        Assumptions.assumeTrue(Files.getFileStore(tempDir).supportsFileAttributeView("posix"));
        Path script = tempDir.resolve("fake-kompile");
        Files.writeString(script, """
                #!/bin/sh
                test "$1" = "exec" || exit 2
                test "$2" = "--json" || exit 3
                test "$3" = "-" || exit 4
                test -z "$4" || exit 5
                input=$(cat)
                printf '%s' "$input" > captured-prompt.txt
                i=0
                while [ $i -lt 12000 ]; do echo "diagnostic-$i" >&2; i=$((i+1)); done
                printf '%s\n' '{"type":"result","text":"ok","exit":0}'
                """);
        Files.setPosixFilePermissions(script, Set.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE));
        KompileCliRunner runner = new KompileCliRunner(
                mapper, script.toString(), 10_000L, tempDir.toFile());

        KompileCliRunner.Result result = runner.run("private channel prompt", null);

        assertTrue(result.success(), result.error());
        assertEquals("ok", result.output());
        assertEquals("private channel prompt", Files.readString(tempDir.resolve("captured-prompt.txt")));
    }
}
