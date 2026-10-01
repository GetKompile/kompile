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
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package ai.kompile.cli.main.chat.agent;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class SubprocessAgentRunnerBlockingNoticeTest {

    /**
     * The first stream-json line of Claude Code 2.1.282, trimmed. Its {@code analytics_disabled}
     * and {@code product_feedback_disabled} keys matched "disabled", so every kompile {@code task}
     * subagent was killed on its first line as "agent is disabled or unavailable".
     */
    private static final String CLAUDE_INIT = """
            {"type":"system","subtype":"init","cwd":"/work","session_id":"bdb4c664",\
            "tools":["Task","Bash","Read"],"mcp_servers":[{"name":"kompile","status":"pending",\
            "source":"project"},{"name":"kompile-app","status":"failed","source":"project"}],\
            "model":"fable[1m]","permissionMode":"bypassPermissions","apiKeySource":"none",\
            "claude_code_version":"2.1.282","analytics_disabled":false,\
            "product_feedback_disabled":false,"uuid":"3313b9cf"}""";

    @Test
    void claudeInitEventDoesNotStopTheAgent() {
        assertNull(SubprocessAgentRunner.blockingNoticeFor("claude", CLAUDE_INIT));
    }

    @Test
    void toolOutputAnswerTextAndRetriesDoNotStopTheAgent() {
        for (String line : List.of(
                """
                {"type":"user","message":{"role":"user","content":[{"type":"tool_result",\
                "tool_use_id":"t1","is_error":true,\
                "content":"cp: cannot open 'x': Permission denied; unsupported flag"}]}}""",
                """
                {"type":"assistant","message":{"content":[{"type":"text",\
                "text":"The flag is disabled until the rate limit resets."}]}}""",
                """
                {"type":"system","subtype":"api_retry","attempt":1,"error_status":429,\
                "error":"rate_limit"}""",
                """
                {"type":"result","subtype":"success","is_error":false,\
                "result":"Checked the usage limit handling; nothing is disabled."}""")) {
            assertNull(SubprocessAgentRunner.blockingNoticeFor("claude", line), line);
        }
    }

    @Test
    void claudeErrorResultStopsTheAgentWithItsOwnWording() {
        String line = """
                {"type":"result","subtype":"success","is_error":true,\
                "result":"You've hit your limit - resets 5am","session_id":"s1"}""";

        assertEquals("usage limit or quota reached (You've hit your limit - resets 5am)",
                SubprocessAgentRunner.blockingNoticeFor("claude", line));
    }

    @Test
    void errorEventsOfOtherStructuredAgentsStopTheAgent() {
        assertEquals("usage limit or quota reached"
                        + " (exceeded retry limit, last status: 429 Too Many Requests)",
                SubprocessAgentRunner.blockingNoticeFor("codex", """
                        {"type":"error",\
                        "message":"exceeded retry limit, last status: 429 Too Many Requests"}"""));
        assertEquals("usage limit or quota reached (You've hit your usage limit.)",
                SubprocessAgentRunner.blockingNoticeFor("codex", """
                        {"type":"turn.failed","error":{"message":"You've hit your usage limit."}}"""));
        assertEquals("agent authentication is required (Not authenticated with anthropic)",
                SubprocessAgentRunner.blockingNoticeFor("opencode", """
                        {"type":"error","error":{"name":"ProviderAuthError",\
                        "data":{"message":"Not authenticated with anthropic"}}}"""));
    }

    @Test
    void piStopsOnAFailedMessageOrAGivenUpRetryButNotWhileRetrying() {
        assertEquals("usage limit or quota reached"
                        + " (429 rate_limit_error: exceeded the rate limit for your organization)",
                SubprocessAgentRunner.blockingNoticeFor("pi", """
                        {"type":"message_end","message":{"role":"assistant","content":[],\
                        "stopReason":"error",\
                        "errorMessage":"429 rate_limit_error: exceeded the rate limit for your organization"}}"""));
        assertNull(SubprocessAgentRunner.blockingNoticeFor("pi", """
                {"type":"auto_retry_start","attempt":1,"maxAttempts":3,"delayMs":2000,\
                "errorMessage":"429 Too Many Requests"}"""));
        assertEquals("usage limit or quota reached (429 Too Many Requests)",
                SubprocessAgentRunner.blockingNoticeFor("pi", """
                        {"type":"auto_retry_end","success":false,"attempt":3,\
                        "finalError":"429 Too Many Requests"}"""));
    }

    @Test
    void geminiWarningsDoNotStopTheAgentButItsQuotaErrorDoes() {
        assertNull(SubprocessAgentRunner.blockingNoticeFor("gemini", """
                {"type":"error","timestamp":"2026-09-29T05:00:00.000Z","severity":"warning",\
                "message":"Agent execution blocked: the shell tool is disabled by policy"}"""));
        assertEquals("usage limit or quota reached"
                        + " (Quota exceeded for quota metric 'Gemini 2.5 Pro Requests')",
                SubprocessAgentRunner.blockingNoticeFor("gemini", """
                        {"type":"error","timestamp":"2026-09-29T05:00:00.000Z","severity":"error",\
                        "message":"Quota exceeded for quota metric 'Gemini 2.5 Pro Requests'"}"""));
    }

    @Test
    void plainTextDiagnosticsStillStopTheAgent() {
        assertEquals("usage limit or quota reached (Error: Claude AI usage limit reached)",
                SubprocessAgentRunner.blockingNoticeFor("claude",
                        "\033[31mError: Claude AI usage limit reached\033[0m"));
        assertEquals("agent is disabled or unavailable (sh: claude: command not found)",
                SubprocessAgentRunner.blockingNoticeFor("claude", "sh: claude: command not found"));
    }
}
