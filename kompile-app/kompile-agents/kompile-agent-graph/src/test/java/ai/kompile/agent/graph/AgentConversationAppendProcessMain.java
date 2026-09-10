/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package ai.kompile.agent.graph;

import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

/** Forked-process writer used to prove the journal file lock serializes independent JVMs. */
public final class AgentConversationAppendProcessMain {

    private AgentConversationAppendProcessMain() {
    }

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]);
        AgentPrincipal principal = new AgentPrincipal(
                UUID.fromString(args[1]), UUID.fromString(args[2]));
        String conversationKey = args[3];
        String prefix = args[4];
        int count = Integer.parseInt(args[5]);
        AgentConversationSession session = new AgentInstanceStore(root)
                .openConversation(principal, conversationKey);
        for (int index = 0; index < count; index++) {
            session.append(new ConversationEventDraft(
                    ConversationEventType.MESSAGE,
                    ConversationRole.USER,
                    prefix + index,
                    Map.of("writer", prefix)));
        }
        System.out.println("APPENDED=" + count);
    }
}
