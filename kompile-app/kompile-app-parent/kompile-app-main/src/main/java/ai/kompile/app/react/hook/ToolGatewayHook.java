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

package ai.kompile.app.react.hook;

import ai.kompile.react.context.AgentContext;
import ai.kompile.react.hook.AgentHook;
import ai.kompile.react.model.ToolCall;
import ai.kompile.toolgateway.model.GatewayAction;
import ai.kompile.toolgateway.model.GatewayDecision;
import ai.kompile.toolgateway.service.ToolGatewayConfigService;
import ai.kompile.toolgateway.service.ToolGatewayService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Iterator;
import java.util.List;

/**
 * ReAct agent hook that evaluates tool calls through the tool gateway
 * before they execute.
 * <p>
 * Runs at priority 10 (before FilterChainHook at 50) so gateway rules
 * are applied first.
 * <p>
 * For each tool call in the list:
 * <ul>
 *   <li>ALLOW — no change, tool executes normally</li>
 *   <li>BLOCK — removes the tool call from the list</li>
 *   <li>REWRITE — replaces the tool call's arguments</li>
 * </ul>
 */
public class ToolGatewayHook implements AgentHook {

    private static final Logger log = LoggerFactory.getLogger(ToolGatewayHook.class);

    private final ToolGatewayService gatewayService;
    private final ToolGatewayConfigService configService;

    public ToolGatewayHook(ToolGatewayService gatewayService,
                           ToolGatewayConfigService configService) {
        this.gatewayService = gatewayService;
        this.configService = configService;
    }

    @Override
    public int getPriority() {
        return 10;
    }

    @Override
    public void preAct(AgentContext context, List<ToolCall> toolCalls) {
        if (!configService.isEnabled()) {
            return;
        }

        Iterator<ToolCall> it = toolCalls.iterator();
        while (it.hasNext()) {
            ToolCall toolCall = it.next();
            try {
                GatewayDecision decision = gatewayService.evaluate(
                        toolCall.getName(), toolCall.getArguments());

                if (decision.action() == GatewayAction.BLOCK) {
                    log.info("Tool gateway BLOCKED tool '{}': {}", toolCall.getName(), decision.reason());
                    it.remove();
                    context.setMetadata("gateway_blocked_" + toolCall.getName(), decision.reason());
                } else if (decision.action() == GatewayAction.REWRITE && decision.rewrittenArgs() != null) {
                    log.info("Tool gateway REWROTE args for tool '{}': {}",
                            toolCall.getName(), decision.reason());
                    toolCall.setArguments(decision.rewrittenArgs());
                }
            } catch (Exception e) {
                log.warn("Tool gateway evaluation failed for '{}': {}", toolCall.getName(), e.getMessage());
                // Fail-open/closed is handled inside ToolGatewayService.evaluate()
            }
        }
    }
}
