package ai.kompile.cli.main.chat.enforcer;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EnforcerConversationWindowTest {

    @TempDir
    Path tempDir;

    @Test
    void persistsRollingRecentMessagesForToolGuards() {
        ObjectMapper mapper = new ObjectMapper();
        Path contextFile = tempDir.resolve("context.json");
        EnforcerConversationWindow window = new EnforcerConversationWindow(contextFile, mapper, 3);

        window.addUserMessage("Do the task.");
        window.updateAssistantMessage("I will inspect files.");
        window.addToolCall("read", "{\"file\":\"A.java\"}");
        window.finishAssistantMessage("Done.");

        EnforcerConversationContext context = EnforcerConversationContext.read(contextFile, mapper);

        assertEquals(3, context.getMessages().size());
        assertEquals("assistant", context.getMessages().get(2).role());
        assertEquals("Done.", context.getMessages().get(2).content());
        assertTrue(context.formatForPrompt(10_000).contains("tool_call: read"));
    }
}
