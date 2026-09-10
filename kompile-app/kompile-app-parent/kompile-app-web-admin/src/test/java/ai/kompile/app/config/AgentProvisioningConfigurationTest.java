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
package ai.kompile.app.config;

import ai.kompile.agent.graph.AgentInstanceStore;
import ai.kompile.agent.graph.AgentPrivateGraphContextAssembler;
import ai.kompile.agent.graph.LocalOwnerIdentity;
import ai.kompile.agent.graph.LocalOwnerIdentityStore;
import ai.kompile.app.services.AgentPrivateGraphToolFactory;
import ai.kompile.app.services.AgentProvisioningService;
import ai.kompile.kclaw.agent.KClawExecutionScopeResolver;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AgentProvisioningConfigurationTest {

    @TempDir
    Path tempDir;

    @Test
    void wiresAdminOwnedStoresBeneathConfiguredDataDirectory() {
        new ApplicationContextRunner()
                .withUserConfiguration(AgentProvisioningConfiguration.class)
                .withPropertyValues("kompile.data.dir=" + tempDir)
                .run(context -> {
                    assertThat(context).hasSingleBean(LocalOwnerIdentityStore.class);
                    assertThat(context).hasSingleBean(LocalOwnerIdentity.class);
                    assertThat(context).hasSingleBean(AgentInstanceStore.class);
                    assertThat(context).hasSingleBean(AgentProvisioningService.class);
                    assertThat(context).hasSingleBean(AgentPrivateGraphContextAssembler.class);
                    assertThat(context).hasSingleBean(AgentPrivateGraphToolFactory.class);
                    assertThat(context).hasSingleBean(KClawExecutionScopeResolver.class);
                    LocalOwnerIdentity owner = context.getBean(LocalOwnerIdentity.class);
                    assertThat(tempDir.resolve("control/local-owner.id")).isRegularFile();
                    assertThat(Files.readString(tempDir.resolve("control/local-owner.id")))
                            .isEqualTo(owner.ownerId() + "\n");
                    assertThat(tempDir.resolve("agents")).isDirectory();
                });
    }

    @Test
    void honorsAnExplicitLocalOwnerIdentityTestSeam() {
        LocalOwnerIdentity expected = new LocalOwnerIdentity(UUID.randomUUID());
        new ApplicationContextRunner()
                .withBean(LocalOwnerIdentity.class, () -> expected)
                .withUserConfiguration(AgentProvisioningConfiguration.class)
                .withPropertyValues("kompile.data.dir=" + tempDir)
                .run(context -> assertThat(context.getBean(LocalOwnerIdentity.class))
                        .isSameAs(expected));
    }
}
