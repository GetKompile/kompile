package ai.kompile.cli.main.chat.enforcer;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EnforcerToolCallDecisionTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void parsesBlockedToolCall() {
        EnforcerToolCallDecision decision = EnforcerToolCallDecision.parse(mapper, """
                {"action":"BLOCK","reason":"bash is forbidden","violations":["No shell commands"]}
                """);

        assertFalse(decision.isAllowed());
        assertEquals(EnforcerToolCallDecision.Action.BLOCK, decision.getAction());
        assertEquals("No shell commands", decision.blockMessage());
    }

    @Test
    void parsesRewriteArguments() {
        EnforcerToolCallDecision decision = EnforcerToolCallDecision.parse(mapper, """
                {"action":"REWRITE","reason":"limit path","violations":[],"rewrittenArgs":{"path":"docs"}}
                """);

        assertTrue(decision.isAllowed());
        assertTrue(decision.isRewrite());
        assertEquals("docs", decision.getRewrittenArgs().get("path"));
    }

    @Test
    void invalidJudgeJsonFailsClosed() {
        EnforcerToolCallDecision decision = EnforcerToolCallDecision.parse(mapper, "allow it");

        assertFalse(decision.isAllowed());
        assertEquals(EnforcerToolCallDecision.Action.BLOCK, decision.getAction());
    }
}
