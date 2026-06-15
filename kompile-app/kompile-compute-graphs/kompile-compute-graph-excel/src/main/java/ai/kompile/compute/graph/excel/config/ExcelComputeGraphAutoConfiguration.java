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
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.compute.graph.excel.config;

import ai.kompile.compute.graph.engine.NodeExecutor;
import ai.kompile.compute.graph.excel.ExcelComputeTool;
import ai.kompile.compute.graph.excel.ExcelFormulaConverter;
import ai.kompile.compute.graph.excel.ExcelNodeExecutor;
import ai.kompile.compute.graph.model.NodeExecutionType;
import ai.kompile.core.llm.chat.LLMChat;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

import java.util.List;
import java.util.Map;

/**
 * Auto-configuration for the Excel compute graph engine.
 * Requires both an LLMChat bean (for formula conversion) and a NodeExecutor
 * that handles JAVASCRIPT (for executing the generated code).
 * <p>
 * Prefers the "codingAssistantLLMChat" bean when available (API-based LLM with memory),
 * but falls back to any available LLMChat (e.g. CliAgentLLMChat) for environments
 * without an API key configured.
 */
@AutoConfiguration
@ConditionalOnBean(LLMChat.class)
public class ExcelComputeGraphAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(ExcelFormulaConverter.class)
    public ExcelFormulaConverter excelFormulaConverter(Map<String, LLMChat> llmChats) {
        // Prefer codingAssistantLLMChat, then any other LLMChat
        LLMChat chat = llmChats.getOrDefault("codingAssistantLLMChat",
                llmChats.values().iterator().next());
        return new ExcelFormulaConverter(chat);
    }

    @Bean
    @ConditionalOnMissingBean(ExcelNodeExecutor.class)
    public ExcelNodeExecutor excelNodeExecutor(
            ExcelFormulaConverter converter,
            List<NodeExecutor> executors,
            ObjectMapper objectMapper) {
        // Find the scripting executor that handles JAVASCRIPT
        NodeExecutor scriptExecutor = executors.stream()
                .filter(e -> e.supportedTypes().contains(NodeExecutionType.JAVASCRIPT))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "ExcelNodeExecutor requires a NodeExecutor that supports JAVASCRIPT "
                        + "(e.g., ScriptingNodeExecutor from kompile-compute-graph-scripting)"));
        return new ExcelNodeExecutor(converter, scriptExecutor, objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean(ExcelComputeTool.class)
    public ExcelComputeTool excelComputeTool(
            ExcelFormulaConverter converter,
            ObjectMapper objectMapper) {
        return new ExcelComputeTool(converter, objectMapper);
    }
}
