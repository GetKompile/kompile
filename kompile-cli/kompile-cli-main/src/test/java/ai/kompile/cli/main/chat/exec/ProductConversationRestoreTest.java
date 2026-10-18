package ai.kompile.cli.main.chat.exec;

import ai.kompile.cli.main.chat.ChatHistory;
import ai.kompile.cli.main.chat.agent.AgenticChatLoop;
import ai.kompile.cli.main.chat.testing.TemporaryUserHome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

@TemporaryUserHome
class ProductConversationRestoreTest {
    @TempDir Path workspace;
    @Test void explicitRuntimeReopenRestoresOnlyTheExistingCanonicalConversation() throws Exception {
        String id = UUID.randomUUID().toString();
        ChatHistory history = new ChatHistory(id);
        history.open(null, "elthoria-gm", false, workspace);
        history.logUserMessage("previous-campaign-choice");
        history.logAgentResponse("elthoria-gm", "confirmed-world-observation", 1);
        history.close();
        AgenticChatLoop loop = mock(AgenticChatLoop.class);
        HeadlessAgentRunner.restoreProductConversation(loop, id);
        verify(loop).restoreHistory(argThat(turns -> turns.stream().anyMatch(
                turn -> turn.content().equals("previous-campaign-choice"))
                && turns.stream().anyMatch(turn -> turn.content().equals("confirmed-world-observation"))));
        verifyNoMoreInteractions(loop); // No model invocation or replay of a saved command.
    }
    @Test void anEmptyProductConversationDoesNotAuthorOrReplayABootstrapTurn() throws Exception {
        AgenticChatLoop loop = mock(AgenticChatLoop.class);
        HeadlessAgentRunner.restoreProductConversation(loop, UUID.randomUUID().toString());
        verifyNoInteractions(loop);
    }
}
