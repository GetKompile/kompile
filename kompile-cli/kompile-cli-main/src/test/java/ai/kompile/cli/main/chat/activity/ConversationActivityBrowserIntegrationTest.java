package ai.kompile.cli.main.chat.activity;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ConversationActivityBrowserIntegrationTest {

    @Test
    void browsesProjectSessionDetailAndPermittedTranscriptWithoutResuming(@TempDir Path temp)
            throws Exception {
        ActivityStorage storage = new ActivityStorage(temp.resolve("conversations"));
        ActivityIdentity identity = ActivityIdentity.conversation("browser-session", temp);
        Files.createDirectories(storage.conversationsRoot());
        Files.writeString(storage.transcriptPath(identity),
                "Started: 2026-09-01T00:00:00Z\nAgent: coder\nCWD: " + temp + "\n"
                        + "> Fix the activity browser\n\n< Done\n\n", StandardCharsets.UTF_8);

        ConversationActivityService service = new ConversationActivityService(storage,
                Clock.fixed(Instant.parse("2026-09-01T00:02:00Z"), ZoneOffset.UTC),
                List.of(id -> new TranscriptActivityReader(storage.transcriptPath(id), storage.conversationsRoot())));
        ProjectActivityController controller = new ProjectActivityController(
                () -> AgentActivitySnapshot.empty(Duration.ofSeconds(10)), new AgentActivityRenderer(),
                identity.conversationId(), () -> 100, 100L, 500L,
                Executors.newSingleThreadScheduledExecutor());
        controller.setConversationActivityBrowser(service, identity, temp);
        try {
            assertTrue(controller.openConversationActivity("project").contains("Fix the activity browser"));
            assertTrue(controller.openConversationActivity("session browser-session").contains("UNVERIFIED"));
            assertTrue(controller.openConversationActivity("detail").contains("Events:"));
            assertTrue(controller.openConversationActivity("transcript " + storage.transcriptPath(identity))
                    .contains("Read-only transcript"));
        } finally {
            controller.close();
        }
    }
}
