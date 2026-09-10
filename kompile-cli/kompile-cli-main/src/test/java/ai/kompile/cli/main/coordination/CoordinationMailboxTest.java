/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.cli.main.coordination;

import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.cli.main.chat.agent.AgentConfig;
import ai.kompile.cli.main.chat.permission.PermissionService;
import ai.kompile.cli.main.chat.tools.EditCoordinatorTool;
import ai.kompile.cli.main.chat.tools.SessionListTool;
import ai.kompile.cli.main.chat.tools.ToolContext;
import ai.kompile.cli.main.chat.tools.ToolRegistry;
import ai.kompile.cli.main.chat.tools.ToolResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

class CoordinationMailboxTest {

    @TempDir
    Path tempDir;

    private final ObjectMapper mapper = JsonUtils.standardMapper();
    private final List<CoordinationStateManager> managers = new ArrayList<>();

    @AfterEach
    void tearDown() {
        managers.forEach(CoordinationStateManager::shutdown);
    }

    @Test
    void managersFromDifferentSubdirectoriesConvergeOnProjectRoot() throws Exception {
        Files.writeString(tempDir.resolve("kompile.project.json"), "{}");
        Path firstDir = Files.createDirectories(tempDir.resolve("module-a/src"));
        Path secondDir = Files.createDirectories(tempDir.resolve("module-b/test"));
        CoordinationStateManager first = manager(firstDir, "session-first");
        CoordinationStateManager second = manager(secondDir, "session-second");

        first.registerAgent("first task", null, "codex", 0, 11L);
        second.registerAgent("second task", null, "claude", 0, 12L);

        assertEquals(tempDir.toAbsolutePath().normalize(), first.getProjectRoot());
        assertEquals(first.getProjectRoot(), second.getProjectRoot());
        assertEquals(Set.of("session-first", "session-second"),
                first.queryAgents().stream().map(AgentEntry::getSessionId).collect(java.util.stream.Collectors.toSet()));
    }

    @Test
    void messageRemainsPendingUntilRecipientAcknowledgesIt() throws Exception {
        CoordinationStateManager sender = manager(tempDir, "sender-session");
        CoordinationStateManager recipient = manager(tempDir, "recipient-session");

        CoordinationMessage delivered = sender.sendMessage(
                "recipient-session", "request", "Review the coordination plan", null);

        List<CoordinationMessage> firstRead = recipient.readMessages(20);
        assertEquals(1, firstRead.size());
        assertEquals(delivered.getMessageId(), firstRead.get(0).getMessageId());
        assertEquals("sender-session", firstRead.get(0).getSenderSessionId());
        assertEquals("Review the coordination plan", firstRead.get(0).getMessage());
        assertEquals(CoordinationMessage.CURRENT_SCHEMA_VERSION, firstRead.get(0).getSchemaVersion());
        assertEquals(1, recipient.readMessages(20).size(), "read must not imply acknowledgement");
        Path inbox = tempDir.resolve(".kompile/coordination/messages/recipient-session");
        if (Files.getFileStore(inbox).supportsFileAttributeView("posix")) {
            assertEquals(PosixFilePermissions.fromString("rwx------"),
                    Files.getPosixFilePermissions(inbox));
            assertEquals(PosixFilePermissions.fromString("rw-------"),
                    Files.getPosixFilePermissions(
                            inbox.resolve(delivered.getMessageId() + ".message.json")));
        }
        assertFalse(sender.acknowledgeMessage(delivered.getMessageId()),
                "a sender cannot acknowledge a recipient's inbox");

        assertTrue(recipient.acknowledgeMessage(delivered.getMessageId()));
        assertTrue(recipient.readMessages(20).isEmpty());
    }

    @Test
    void concurrentDeliveriesUseUniqueFilesWithoutLoss() throws Exception {
        CoordinationStateManager sender = manager(tempDir, "parallel-sender");
        CoordinationStateManager recipient = manager(tempDir, "parallel-recipient");
        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            List<Future<CoordinationMessage>> deliveries = new ArrayList<>();
            for (int i = 0; i < 40; i++) {
                int index = i;
                deliveries.add(executor.submit(() -> sender.sendMessage(
                        "parallel-recipient", "notice", "message-" + index, null)));
            }
            Set<String> deliveredIds = new HashSet<>();
            for (Future<CoordinationMessage> delivery : deliveries) {
                deliveredIds.add(delivery.get().getMessageId());
            }

            List<CoordinationMessage> pending = recipient.readMessages(100);
            assertEquals(40, deliveredIds.size());
            assertEquals(40, pending.size());
            assertEquals(deliveredIds,
                    pending.stream().map(CoordinationMessage::getMessageId)
                            .collect(java.util.stream.Collectors.toSet()));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void mailboxRejectsUnsafeIdentifiersAndOversizedMessages() {
        CoordinationStateManager sender = manager(tempDir, "safe-sender");

        assertThrows(IllegalArgumentException.class,
                () -> sender.sendMessage("../other", "message", "hello", null));
        assertThrows(IllegalArgumentException.class,
                () -> sender.sendMessage("target", "bad/kind", "hello", null));
        assertThrows(IllegalArgumentException.class,
                () -> sender.sendMessage("target", "message", "x".repeat(65_537), null));
        assertThrows(IllegalArgumentException.class,
                () -> new CoordinationStateManager(tempDir, "../unsafe", mapper));
    }

    @Test
    void broadcastDeliversToEveryOtherActiveAgentOnly() throws Exception {
        CoordinationStateManager sender = manager(tempDir, "broadcast-sender");
        CoordinationStateManager first = manager(tempDir, "broadcast-first");
        CoordinationStateManager second = manager(tempDir, "broadcast-second");
        sender.registerAgent("coordinate work", null, "sender", 0,
                ProcessHandle.current().pid());
        first.registerAgent("first peer", null, "first", 0,
                ProcessHandle.current().pid());
        second.registerAgent("second peer", null, "second", 0,
                ProcessHandle.current().pid());

        List<CoordinationMessage> delivered = sender.broadcastMessage(
                "notice", "System activity lane is reserved", null);

        assertEquals(2, delivered.size());
        assertTrue(sender.readMessages(20).isEmpty());
        assertEquals("System activity lane is reserved",
                first.readMessages(20).get(0).getMessage());
        assertEquals("System activity lane is reserved",
                second.readMessages(20).get(0).getMessage());
    }

    @Test
    void editCoordinatorExposesSendReadAndAcknowledgeActions() throws Exception {
        CoordinationStateManager sender = manager(tempDir, "tool-sender");
        CoordinationStateManager recipient = manager(tempDir, "tool-recipient");
        EditCoordinatorTool senderTool = new EditCoordinatorTool(sender);
        EditCoordinatorTool recipientTool = new EditCoordinatorTool(recipient);
        PermissionService permissions = new PermissionService();
        permissions.setAutoApproveAll(true);
        ToolContext senderContext = new ToolContext(
                "tool-sender", null, permissions, tempDir, new ToolRegistry(mapper));
        ToolContext recipientContext = new ToolContext(
                "tool-recipient", null, permissions, tempDir, new ToolRegistry(mapper));

        ObjectNode send = mapper.createObjectNode();
        send.put("action", "send_message");
        send.put("target_session_id", "tool-recipient");
        send.put("message_kind", "request");
        send.put("message", "Please inspect the pending diff");
        ToolResult sent = senderTool.execute(send, senderContext);
        assertFalse(sent.isError(), sent::getOutput);
        String messageId = String.valueOf(sent.getMetadata().get("messageId"));

        ObjectNode read = mapper.createObjectNode();
        read.put("action", "read_messages");
        ToolResult pending = recipientTool.execute(read, recipientContext);
        assertFalse(pending.isError(), pending::getOutput);
        assertTrue(pending.getOutput().contains(messageId));
        assertTrue(pending.getOutput().contains("Please inspect the pending diff"));

        ObjectNode acknowledge = mapper.createObjectNode();
        acknowledge.put("action", "ack_message");
        acknowledge.put("message_id", messageId);
        ToolResult acked = recipientTool.execute(acknowledge, recipientContext);
        assertFalse(acked.isError(), acked::getOutput);
        assertTrue(recipient.readMessages(20).isEmpty());
    }

    @Test
    void explicitRegistrationPreservesSuppliedParentSession() throws Exception {
        CoordinationStateManager child = manager(tempDir, "child-session");
        EditCoordinatorTool childTool = new EditCoordinatorTool(child);
        PermissionService permissions = new PermissionService();
        permissions.setAutoApproveAll(true);
        ToolContext context = new ToolContext(
                "tool-transcript-session",
                AgentConfig.builder("coder").roleName("architect").build(),
                permissions, tempDir, new ToolRegistry(mapper));
        ObjectNode register = mapper.createObjectNode();
        register.put("action", "register_agent");
        register.put("task", "child task");
        register.put("agent_name", "codex");
        register.put("parent_session_id", "parent-session");

        ToolResult result = childTool.execute(register, context);

        assertFalse(result.isError(), result::getOutput);
        AgentEntry entry = child.queryAgents().get(0);
        assertEquals("subagent", entry.getAgentType());
        assertEquals("parent-session", entry.getParentSessionId());
        assertEquals("tool-transcript-session", entry.getToolSessionId());
        assertEquals("architect", entry.getRoleName());
    }

    @Test
    void sessionsToolUsesProjectLocalCoordinationPresence() throws Exception {
        CoordinationStateManager manager = manager(tempDir, "stdio-session");
        manager.registerAgent("stdio task", null, "codex", 0,
                ProcessHandle.current().pid());
        PermissionService permissions = new PermissionService();
        permissions.setAutoApproveAll(true);
        ToolContext context = new ToolContext(
                "stdio-session", null, permissions, tempDir, new ToolRegistry(mapper));
        ObjectNode params = mapper.createObjectNode();
        params.put("section", "mcp_sessions");

        ToolResult result = new SessionListTool(manager).execute(params, context);

        assertFalse(result.isError(), result::getOutput);
        assertTrue(result.getOutput().contains("PROJECT AGENT SESSIONS"));
        assertTrue(result.getOutput().contains("stdio-session"));
        assertTrue(result.getOutput().contains("stdio task"));
    }

    private CoordinationStateManager manager(Path workDir, String sessionId) {
        CoordinationStateManager manager = new CoordinationStateManager(workDir, sessionId, mapper);
        managers.add(manager);
        return manager;
    }
}
