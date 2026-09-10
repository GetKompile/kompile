/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.app.chat.boundary;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Proves the app-main provisioning slice is absent from the chat persona classpath. */
class AgentProvisioningPersonaBoundaryTest {

    @Test
    void excludesAdminAgentProvisioningAndItsPrivateGraphStore() {
        assertClassAbsent("ai.kompile.app.config.AgentProvisioningConfiguration");
        assertClassAbsent("ai.kompile.app.web.controllers.AgentProvisioningController");
        assertClassAbsent("ai.kompile.app.services.AdminKClawExecutionScopeResolver");
        assertClassAbsent("ai.kompile.app.services.AdminProvisionedAgentRuntimeService");
        assertClassAbsent("ai.kompile.app.web.controllers.ProvisionedAgentRuntimeController");
        assertClassAbsent("ai.kompile.agent.graph.AgentInstanceStore");
        assertClassAbsent("ai.kompile.agent.graph.AgentConversationSession");
        assertClassAbsent("ai.kompile.agent.graph.AgentPrivateGraphContextAssembler");
        assertClassPresent("ai.kompile.app.services.agent.ProvisionedAgentRuntime");
        assertClassPresent("ai.kompile.app.services.agent.ProvisionedAgentRuntimeHttpClient");
    }

    private static void assertClassAbsent(String className) {
        assertThrows(ClassNotFoundException.class,
                () -> Class.forName(className, false,
                        AgentProvisioningPersonaBoundaryTest.class.getClassLoader()));
    }

    private static void assertClassPresent(String className) {
        assertDoesNotThrow(() -> Class.forName(
                className, false, AgentProvisioningPersonaBoundaryTest.class.getClassLoader()));
    }
}
