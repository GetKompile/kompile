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
 * limitations under the License.
 */

package ai.kompile.staging.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class StagingMcpToolRegistryTest {

    record EchoInput(String value) {
    }

    static final class EchoTool {
        @Tool(name = "staging_test_echo", description = "Echo a value")
        String echo(EchoInput input) {
            return input.value();
        }
    }

    @Test
    void springCallbackProvidesCompleteSchemaAndInvokesRecordTool() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean("echoTool", EchoTool.class);
            context.refresh();

            StagingMcpToolRegistry registry = new StagingMcpToolRegistry(new ObjectMapper(), context);
            List<McpServerFeatures.SyncToolSpecification> specifications = registry.discoverToolSpecifications();

            assertThat(specifications).hasSize(1);
            McpServerFeatures.SyncToolSpecification specification = specifications.get(0);
            assertThat(specification.tool().name()).isEqualTo("staging_test_echo");
            assertThat(specification.tool().inputSchema().properties()).containsKey("input");
            assertThat(specification.tool().inputSchema().required()).contains("input");

            CallToolResult result = specification.call().apply(
                    null, Map.of("input", Map.of("value", "native-safe")));
            assertThat(result.isError()).isFalse();
            assertThat(result.content()).hasSize(1);
            assertThat(result.content().get(0).toString()).contains("native-safe");
        }
    }
}
