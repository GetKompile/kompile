/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat;

import ai.kompile.cli.common.KompileHome;
import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.cli.main.chat.agent.AgentConfig;
import ai.kompile.cli.main.chat.agent.AgentRegistry;
import ai.kompile.cli.main.chat.agent.AgenticChatLoop;
import ai.kompile.cli.main.chat.config.ChatConfig;
import ai.kompile.cli.main.chat.config.DirectLlmClient;
import ai.kompile.cli.main.chat.permission.PermissionService;
import ai.kompile.cli.main.chat.render.AsciiRenderer;
import ai.kompile.cli.main.chat.render.TerminalRenderer;
import ai.kompile.cli.main.chat.roles.RoleManager;
import ai.kompile.cli.main.chat.testing.TemporaryUserHome;
import ai.kompile.cli.main.chat.tools.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@code /role} and {@code /local-agent} in a direct (local) chat change the agent
 * the next turn runs as: that agent's system prompt reaches the provider, and the
 * loop's agent config, which compaction and model routing read, is the same agent
 * while the turn runs. Drives the real router, role manager and AgenticChatLoop
 * against a provider that records each request.
 */
@TemporaryUserHome
@ResourceLock(Resources.SYSTEM_OUT)
class ChatRoleAgentTurnTest {
    private static final String SESSION = "role-agent-turn-test";
    private static final String ROLE_PROMPT = "ROLE_MARKER_5c1e: answer as the marker role.";

    @TempDir Path project;

    @Test
    void roleAndLocalAgentSwitchesChangeTheAgentEachTurnRunsAs() throws Exception {
        ObjectMapper mapper = JsonUtils.standardMapper();
        AgentRegistry agents = new AgentRegistry();
        RecordingProvider provider = new RecordingProvider(mapper);
        AgenticChatLoop loop = new AgenticChatLoop(null, mapper, new ToolRegistry(mapper),
                new PermissionService(), agents, project, provider, null);
        provider.chatAgent = loop::getCurrentAgentConfig;

        RoleManager roles = new RoleManager(project);
        roles.createRole("marker-role", "Marker", "Carries a marker prompt", "testing", ROLE_PROMPT);

        // The REPL's local agent name, which each turn passes to the loop.
        AtomicReference<String> localAgent = new AtomicReference<>("coder");
        ChatRepl repl = mock(ChatRepl.class);
        when(repl.getLocalAgentName()).thenAnswer(call -> localAgent.get());
        doAnswer(call -> {
            localAgent.set(call.getArgument(0));
            return null;
        }).when(repl).setLocalAgentName(anyString());

        ChatHistory history = new ChatHistory(SESSION);
        history.open(null, "coder", false, project);
        ChatSessionMetrics metrics = new ChatSessionMetrics(SESSION);
        TerminalRenderer renderer = new TerminalRenderer(false);
        ChatCommandRouter router = new ChatCommandRouter(
                repl, null, null, null,
                null, mapper, SESSION, true,
                history, metrics, renderer, new AsciiRenderer(renderer),
                agents, null, roles, null, null, loop, null, null, null,
                List.of(), null);

        // /role: the role becomes the chat's agent; the pane and the transcript
        // name the agent it replaced.
        String rolePane = printed(() -> assertTrue(router.handleSlashCommand("/role marker-role")));
        assertTrue(rolePane.contains("Agent changed from coder to marker-role"), rolePane);
        assertEquals("marker-role", localAgent.get());
        assertEquals("marker-role", loop.getCurrentAgentConfig().getName());
        assertEquals("marker-role", metrics.getActiveRole());
        String transcript = Files.readString(KompileHome.homeDirectory().toPath()
                .resolve("conversations").resolve(SESSION + ".txt"), StandardCharsets.UTF_8);
        assertTrue(transcript.contains("[system] Role assigned: marker-role (agent changed from coder)"),
                transcript);

        loop.chat("first question", SESSION, localAgent.get(), null, false);
        Request roleTurn = provider.firstRequestFor("first question");
        assertTrue(roleTurn.systemPrompt().startsWith(ROLE_PROMPT),
                "the role's prompt must lead the system prompt sent to the provider\n"
                        + roleTurn.systemPrompt());
        assertEquals("marker-role", roleTurn.chatAgent(),
                "compaction and routing must read the agent the turn runs as");
        assertEquals("marker-role", loop.getCurrentAgentConfig().getName());

        // /local-agent: the chat runs as that agent, and no longer as the role.
        printed(() -> assertTrue(router.handleSlashCommand("/local-agent planner")));
        assertEquals("planner", localAgent.get());
        assertEquals("planner", loop.getCurrentAgentConfig().getName());
        assertNull(roles.getActiveRoleName());
        assertNull(metrics.getActiveRole());

        loop.chat("second question", SESSION, localAgent.get(), null, false);
        Request plannerTurn = provider.firstRequestFor("second question");
        assertTrue(plannerTurn.systemPrompt().startsWith(agents.get("planner").getSystemPrompt().strip()),
                plannerTurn.systemPrompt());
        assertFalse(plannerTurn.systemPrompt().contains(ROLE_PROMPT), plannerTurn.systemPrompt());
        assertEquals("planner", plannerTurn.chatAgent());
        assertEquals("planner", loop.getCurrentAgentConfig().getName());
    }

    /** What the router printed while {@code action} ran. */
    private static String printed(Runnable action) {
        PrintStream original = System.out;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        System.setOut(new PrintStream(buffer, true, StandardCharsets.UTF_8));
        try {
            action.run();
        } finally {
            System.setOut(original);
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }

    /** One provider request and the loop's agent config while it was sent. */
    private record Request(String userMessage, String systemPrompt, String chatAgent) {
    }

    /** A direct provider that answers every request and records what it was sent. */
    private static final class RecordingProvider extends DirectLlmClient {
        private final List<Request> requests = new CopyOnWriteArrayList<>();
        private volatile Supplier<AgentConfig> chatAgent = () -> null;

        private RecordingProvider(ObjectMapper mapper) {
            super(new ChatConfig("custom", null, "role-agent-test", "http://unused.invalid"), mapper);
        }

        @Override
        public StreamResult streamChat(
                String userMessage,
                String systemPrompt,
                ArrayNode toolDefs,
                List<ToolCallResultInput> toolResults,
                String modelOverride,
                List<AttachmentInput> attachments) {
            AgentConfig agent = chatAgent.get();
            requests.add(new Request(userMessage == null ? "" : userMessage,
                    systemPrompt == null ? "" : systemPrompt,
                    agent == null ? null : agent.getName()));
            StreamResult result = new StreamResult();
            Consumer<String> output = getOutputConsumer();
            if (output != null) output.accept("ok");
            result.text = "ok";
            return result;
        }

        /** The first request whose user message carries {@code text}. */
        private Request firstRequestFor(String text) {
            return requests.stream()
                    .filter(request -> request.userMessage().contains(text))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("no request carried \"" + text + "\": " + requests));
        }
    }
}
