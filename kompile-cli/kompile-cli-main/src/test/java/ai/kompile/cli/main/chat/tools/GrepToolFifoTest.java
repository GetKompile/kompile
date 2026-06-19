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

package ai.kompile.cli.main.chat.tools;

import ai.kompile.cli.main.chat.agent.AgentConfig;
import ai.kompile.cli.main.chat.permission.PermissionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression test for the grep tool hanging on FIFOs.
 *
 * <p>A running app's {@code *.stderr.fifo} inside the search tree caused the
 * {@code grep -r} fallback (used whenever ripgrep is not installed) to OPEN and block on
 * the pipe indefinitely — the read never returns until a writer appears. The fix adds
 * {@code -D skip} so FIFOs/sockets/devices are skipped entirely, plus a watchdog that
 * hard-caps wall-clock time.</p>
 */
class GrepToolFifoTest {

    private GrepTool tool;
    private ObjectMapper om;

    @BeforeEach
    void setUp() {
        tool = new GrepTool();
        om = new ObjectMapper();
    }

    private ToolContext ctxFor(Path workingDir) {
        AgentConfig agent = AgentConfig.builder("coder").enabledTools(Set.of("*")).build();
        PermissionService perms = new PermissionService();
        ToolRegistry registry = new ToolRegistry(om);
        return new ToolContext("test-" + UUID.randomUUID(), agent, perms, workingDir, registry);
    }

    @Test
    void grepSkipsFifoAndStillFindsMatchesQuickly() throws Exception {
        Path tmp = Files.createTempDirectory("grep-fifo-test");
        try {
            Files.writeString(tmp.resolve("hit.txt"), "alpha NEEDLE_TOKEN beta\n");
            Files.writeString(tmp.resolve("miss.txt"), "nothing to see here\n");

            // A FIFO with NO writer: grep -r without -D skip opens and blocks on it (until the
            // watchdog kills grep ~20s later). This reproduces the real hang on app *.stderr.fifo.
            Path fifo = tmp.resolve("app.stderr.fifo");
            Process mk = new ProcessBuilder("mkfifo", fifo.toString()).start();
            boolean made = mk.waitFor(5, TimeUnit.SECONDS) && mk.exitValue() == 0;
            Assumptions.assumeTrue(made && Files.exists(fifo),
                    "mkfifo unavailable on this platform; skipping FIFO regression test");

            ToolContext ctx = ctxFor(tmp);
            ObjectNode params = om.createObjectNode();
            params.put("pattern", "NEEDLE_TOKEN");

            // A healthy run returns in well under a second. The 10s preemptive bound sits
            // below the tool's 20s watchdog, so a regression (FIFO not skipped) fails fast.
            ToolResult result = assertTimeoutPreemptively(Duration.ofSeconds(10),
                    () -> tool.execute(params, ctx),
                    "grep hung on the FIFO — the -D skip / device-skip guard regressed");

            assertFalse(result.isError(), "grep should succeed: " + result.getOutput());
            assertTrue(result.getOutput().contains("NEEDLE_TOKEN"),
                    "should find the match in the regular file while skipping the FIFO: "
                            + result.getOutput());
        } finally {
            deleteRecursively(tmp);
        }
    }

    @Test
    void grepFindsPlainMatch() throws Exception {
        Path tmp = Files.createTempDirectory("grep-plain-test");
        try {
            Files.writeString(tmp.resolve("a.txt"), "first line\nUNIQUE_MARKER here\nlast line\n");
            ObjectNode params = om.createObjectNode();
            params.put("pattern", "UNIQUE_MARKER");

            ToolResult result = tool.execute(params, ctxFor(tmp));
            assertFalse(result.isError());
            assertTrue(result.getOutput().contains("UNIQUE_MARKER"));
        } finally {
            deleteRecursively(tmp);
        }
    }

    @Test
    void grepNoMatchReturnsCleanly() throws Exception {
        Path tmp = Files.createTempDirectory("grep-nomatch-test");
        try {
            Files.writeString(tmp.resolve("a.txt"), "just some text\n");
            ObjectNode params = om.createObjectNode();
            params.put("pattern", "PATTERN_THAT_DOES_NOT_EXIST_ANYWHERE");

            ToolContext ctx = ctxFor(tmp);
            ToolResult result = assertTimeoutPreemptively(Duration.ofSeconds(10),
                    () -> tool.execute(params, ctx));
            assertFalse(result.isError());
            assertTrue(result.getOutput().toLowerCase().contains("no matches"),
                    "expected a clean no-match message: " + result.getOutput());
        } finally {
            deleteRecursively(tmp);
        }
    }

    /**
     * Regression: pom.xml / XML files must be found WITHOUT an explicit glob.
     *
     * <p>Root cause: the grep fallback (always active on this machine because rg is only a
     * shell function invisible to Java's ProcessBuilder) hung on *.stderr.fifo named pipes
     * created by running fpna/staging processes. grep -r opens and reads FIFOs, blocking
     * until a writer appears. With no writer, the old 30 s waitFor fired only AFTER draining
     * stdout — which never finished — so the timeout never saved us. The tool returned
     * "No matches" even though the file existed. Adding -D skip + a watchdog thread fixes the
     * hang for all file types (including XML), not just XML specifically.
     *
     * <p>This test exercises the XML case explicitly so a future regression cannot silently
     * re-break it. It also places a FIFO alongside the pom.xml to confirm neither FIFO
     * blocks the search nor prevents the XML match from being returned.</p>
     */
    @Test
    void grepFindsXmlAndPomXmlWithoutExplicitGlob() throws Exception {
        Path tmp = Files.createTempDirectory("grep-xml-test");
        try {
            // Minimal pom.xml containing a distinctive string to search for
            String pomContent = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                    + "<project>\n"
                    + "  <artifactId>XML_GREP_REGRESSION_MARKER</artifactId>\n"
                    + "</project>\n";
            Files.writeString(tmp.resolve("pom.xml"), pomContent);

            // An unrelated Java file — must not interfere
            Files.writeString(tmp.resolve("Foo.java"), "// no marker here\npublic class Foo {}\n");

            // A FIFO with no writer: ensures the -D skip guard is active so the search
            // completes promptly rather than blocking on the pipe.
            boolean hasMkfifo = false;
            try {
                Path fifo = tmp.resolve("app.stderr.fifo");
                Process mk = new ProcessBuilder("mkfifo", fifo.toString()).start();
                hasMkfifo = mk.waitFor(5, TimeUnit.SECONDS) && mk.exitValue() == 0;
            } catch (Exception ignored) {
                // mkfifo unavailable — FIFO part of the test is skipped but XML part still runs
            }

            ToolContext ctx = ctxFor(tmp);
            ObjectNode params = om.createObjectNode();
            params.put("pattern", "XML_GREP_REGRESSION_MARKER");
            // NO "glob" parameter — the tool must find pom.xml by default

            ToolResult result = assertTimeoutPreemptively(Duration.ofSeconds(10),
                    () -> tool.execute(params, ctx),
                    "grep hung (likely on FIFO or excessive scan) — FIFO skip / watchdog regressed");

            assertFalse(result.isError(), "grep should succeed: " + result.getOutput());
            assertTrue(result.getOutput().contains("XML_GREP_REGRESSION_MARKER"),
                    "pom.xml content must be found without an explicit glob; got: " + result.getOutput());
            assertTrue(result.getOutput().contains("pom.xml"),
                    "result should identify pom.xml as the matching file; got: " + result.getOutput());
            if (hasMkfifo) {
                assertFalse(result.getOutput().toLowerCase().contains("timed out"),
                        "search must complete well within timeout even with a FIFO present: "
                                + result.getOutput());
            }
        } finally {
            deleteRecursively(tmp);
        }
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // best-effort cleanup
                }
            });
        }
    }
}
