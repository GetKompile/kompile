/*
 *   Copyright 2025 Kompile Inc.
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package ai.kompile.cli.mcp.stdio;

import ai.kompile.cli.main.chat.agent.AgentConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The opencode subagent path: the TUI binary hangs on piped stdin, so the runner drives
 * {@code opencode serve} over HTTP instead. The parser test runs against a real captured
 * response from opencode 1.14.48; the live test is skipped when no opencode binary is on PATH.
 */
@DisabledOnOs(OS.WINDOWS)
class OpencodeServeSubagentTest {

    /**
     * Captured (abridged) from a real {@code POST /session/{id}/message} response, opencode
     * 1.14.48, provider opencode-go/deepseek-v4-pro: reasoning parts must be skipped, text
     * parts concatenated.
     */
    private static final String REAL_MESSAGE_RESPONSE = """
            {"info":{"parentID":"msg_x","role":"assistant","mode":"build","agent":"build",
             "cost":0.024,"tokens":{"total":13961,"input":13939,"output":4,"reasoning":18},
             "modelID":"deepseek-v4-pro","providerID":"opencode-go","finish":"stop",
             "id":"msg_y","sessionID":"ses_z"},
             "parts":[
               {"type":"step-start","id":"prt_1","sessionID":"ses_z","messageID":"msg_y"},
               {"type":"reasoning","text":"The user is asking me to reply with exactly OPENCODE_OK.",
                "id":"prt_2","sessionID":"ses_z","messageID":"msg_y"},
               {"type":"text","text":"OPENCODE_OK","id":"prt_3","sessionID":"ses_z","messageID":"msg_y"},
               {"type":"step-finish","reason":"stop","id":"prt_4"}
             ]}
            """;

    @Test
    void extractsTextParts_skipsReasoningAndStepParts() throws Exception {
        String text = DirectSubagentRunnerStdio.extractOpencodeText(new ObjectMapper(), REAL_MESSAGE_RESPONSE);
        assertEquals("OPENCODE_OK", text,
                "only the type==text part belongs in the result — no reasoning/CoT pollution");
    }

    @Test
    void extractsAndJoinsMultipleTextParts() throws Exception {
        String multi = """
                {"parts":[{"type":"text","text":"line one"},
                          {"type":"reasoning","text":"secret"},
                          {"type":"text","text":"line two"}]}
                """;
        String text = DirectSubagentRunnerStdio.extractOpencodeText(new ObjectMapper(), multi);
        assertEquals("line one\nline two", text);
    }

    /**
     * Live end-to-end: spawn a real {@code opencode serve}, run one turn through the runner, and
     * verify the reply and result formatting. Skipped when opencode is not installed.
     */
    @Test
    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    void liveOpencodeServeSubagentTurn(@TempDir Path tempDir) throws Exception {
        assumeTrue(binaryOnPath("opencode"), "opencode binary not on PATH — live test skipped");
        // Skip when CI env or when the opencode session is known to be inactive (e.g. zen account dry).
        // Set KOMPILE_OPENCODE_LIVE=true explicitly to opt in to this live test.
        assumeTrue("true".equalsIgnoreCase(System.getenv("KOMPILE_OPENCODE_LIVE")),
                "opencode live test skipped — set KOMPILE_OPENCODE_LIVE=true to enable");

        DirectSubagentRunnerStdio runner = new DirectSubagentRunnerStdio(tempDir);
        AgentConfig agent = AgentConfig.builder("opencode").build();

        String result = runner.runSubagent(agent,
                "Reply with exactly this single line and nothing else: OPENCODE_OK");

        assertNotNull(result);
        assertTrue(result.contains("Subagent 'opencode' completed in"),
                "result must carry the standard completion header, got:\n" + result);
        assertTrue(result.contains("OPENCODE_OK"),
                "the model's reply must be in the result, got:\n" + result);
    }

    private static boolean binaryOnPath(String name) {
        String path = System.getenv("PATH");
        if (path == null) {
            return false;
        }
        for (String dir : path.split(File.pathSeparator)) {
            if (new File(dir, name).canExecute()) {
                return true;
            }
        }
        return false;
    }
}
