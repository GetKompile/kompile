/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.react.config;

import ai.kompile.core.evaluation.EvaluationService;
import ai.kompile.core.graphrag.GraphRagService;
import ai.kompile.filterchain.service.FilterChainService;
import ai.kompile.orchestrator.api.WorkflowService;
import ai.kompile.react.api.Actor;
import ai.kompile.react.api.Finalizer;
import ai.kompile.react.api.Observer;
import ai.kompile.react.api.Reasoner;
import ai.kompile.react.component.impl.*;
import ai.kompile.react.eval.EvalTracker;
import ai.kompile.react.eval.impl.InMemoryEvalTracker;
import ai.kompile.react.hook.AgentHook;
import ai.kompile.react.hook.EvalHook;
import ai.kompile.react.hook.FilterChainHook;
import ai.kompile.react.service.ReActAgentService;
import ai.kompile.react.service.impl.DefaultReActAgentService;
import ai.kompile.react.service.impl.ReActWorkflowAdapter;
import ai.kompile.react.tool.FactSheetEvalTool;
import ai.kompile.react.tool.GraphRagTool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.util.ArrayList;
import java.util.List;

/**
 * Auto-configuration for the ReAct agent components.
 */
@Slf4j
@AutoConfiguration
@EnableConfigurationProperties(ReActAgentProperties.class)
@ConditionalOnClass(name = "ai.kompile.react.config.ReActAgentAutoConfiguration")
@ConditionalOnProperty(prefix = "kompile.react", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ReActAgentAutoConfiguration {

    @Autowired
    private ReActAgentProperties properties;

    // ==================== Reasoner Configuration ====================

    @Bean
    @ConditionalOnMissingBean(Reasoner.class)
    @ConditionalOnBean(ChatClient.Builder.class)
    @ConditionalOnProperty(prefix = "kompile.react", name = "graph-rag-enabled", havingValue = "false", matchIfMissing = true)
    public Reasoner defaultReasoner(ChatClient.Builder chatClientBuilder) {
        log.info("Configuring DefaultReasoner");
        return DefaultReasoner.builder()
                .id("default-reasoner")
                .name("Default Reasoner")
                .chatClient(chatClientBuilder.build())
                .systemPrompt(properties.getSystemPrompt())
                .build();
    }

    @Bean
    @ConditionalOnMissingBean(Reasoner.class)
    @ConditionalOnBean({ChatClient.Builder.class, GraphRagService.class})
    @ConditionalOnProperty(prefix = "kompile.react", name = "graph-rag-enabled", havingValue = "true")
    public Reasoner graphRagReasoner(
            ChatClient.Builder chatClientBuilder,
            GraphRagService graphRagService
    ) {
        log.info("Configuring GraphRag-enhanced Reasoner");
        return GraphRagReasoner.builder()
                .id("graphrag-reasoner")
                .name("GraphRag Reasoner")
                .chatClient(chatClientBuilder.build())
                .graphRagService(graphRagService)
                .systemPrompt(properties.getSystemPrompt())
                .maxGraphResults(properties.getGraphRagMaxResults())
                .build();
    }

    @Bean
    @ConditionalOnMissingBean(Reasoner.class)
    @ConditionalOnBean({ChatClient.Builder.class, EvaluationService.class})
    @ConditionalOnProperty(prefix = "kompile.react", name = "eval-based-enabled", havingValue = "true")
    public Reasoner evalBasedReasoner(
            ChatClient.Builder chatClientBuilder,
            EvaluationService evaluationService,
            @Autowired(required = false) EvalTracker evalTracker
    ) {
        log.info("Configuring Eval-Based Reasoner");
        return EvalBasedReasoner.builder()
                .id("eval-based-reasoner")
                .name("Eval-Based Reasoner")
                .chatClient(chatClientBuilder.build())
                .evaluationService(evaluationService)
                .evalTracker(evalTracker)
                .systemPrompt(properties.getSystemPrompt())
                .selfEvaluate(properties.isSelfEvaluate())
                .qualityThreshold(properties.getQualityThreshold())
                .build();
    }

    // ==================== Actor Configuration ====================

    @Bean
    @ConditionalOnMissingBean(Actor.class)
    public Actor actor() {
        if (properties.getExecutionMode() == ReActAgentProperties.ExecutionMode.PARALLEL) {
            log.info("Configuring ParallelActor");
            return ParallelActor.builder()
                    .id("parallel-actor")
                    .name("Parallel Actor")
                    .build();
        }

        log.info("Configuring SequenceActor");
        return SequenceActor.builder()
                .id("sequence-actor")
                .name("Sequence Actor")
                .build();
    }

    // ==================== Observer Configuration ====================

    @Bean
    @ConditionalOnMissingBean(Observer.class)
    public Observer observer() {
        log.info("Configuring DefaultObserver");
        return DefaultObserver.builder()
                .id("default-observer")
                .name("Default Observer")
                .summarizeResults(properties.isSummarizeResults())
                .maxResultLength(properties.getMaxResultLength())
                .build();
    }

    // ==================== Finalizer Configuration ====================

    @Bean
    @ConditionalOnMissingBean(Finalizer.class)
    @ConditionalOnBean(ChatClient.Builder.class)
    public Finalizer finalizer(ChatClient.Builder chatClientBuilder) {
        log.info("Configuring DefaultFinalizer");
        return DefaultFinalizer.builder()
                .id("default-finalizer")
                .name("Default Finalizer")
                .chatClient(chatClientBuilder.build())
                .summarizePrompt(properties.getSummarizePrompt())
                .build();
    }

    // ==================== Hooks Configuration ====================

    @Bean
    @ConditionalOnBean(FilterChainService.class)
    @ConditionalOnProperty(prefix = "kompile.react", name = "filter-chain-enabled", havingValue = "true", matchIfMissing = true)
    public FilterChainHook filterChainHook(FilterChainService filterChainService) {
        log.info("Configuring FilterChainHook");
        return new FilterChainHook(filterChainService);
    }

    // ==================== Eval Configuration ====================

    @Bean
    @ConditionalOnMissingBean(EvalTracker.class)
    @ConditionalOnProperty(prefix = "kompile.react", name = "eval-tracking-enabled", havingValue = "true", matchIfMissing = true)
    public EvalTracker evalTracker() {
        log.info("Configuring InMemoryEvalTracker");
        return new InMemoryEvalTracker();
    }

    @Bean
    @ConditionalOnBean(EvaluationService.class)
    @ConditionalOnProperty(prefix = "kompile.react", name = "eval-hook-enabled", havingValue = "true", matchIfMissing = true)
    public EvalHook evalHook(
            EvaluationService evaluationService,
            @Autowired(required = false) EvalTracker evalTracker
    ) {
        log.info("Configuring EvalHook");
        return new EvalHook(
                evaluationService,
                evalTracker,
                properties.isEvaluateReasoning(),
                properties.getQualityThreshold()
        );
    }

    // ==================== Tools Configuration ====================

    @Bean
    @ConditionalOnBean(GraphRagService.class)
    public GraphRagTool graphRagTool(GraphRagService graphRagService) {
        log.info("Configuring GraphRagTool");
        return new GraphRagTool(graphRagService);
    }

    @Bean
    @ConditionalOnBean(EvalTracker.class)
    public FactSheetEvalTool factSheetEvalTool(EvalTracker evalTracker) {
        log.info("Configuring FactSheetEvalTool");
        return new FactSheetEvalTool(evalTracker);
    }

    // ==================== Service Configuration ====================

    @Bean
    @ConditionalOnMissingBean(ReActAgentService.class)
    @ConditionalOnBean({Reasoner.class, Finalizer.class})
    public ReActAgentService reActAgentService(
            Reasoner reasoner,
            Actor actor,
            Observer observer,
            Finalizer finalizer,
            @Autowired(required = false) List<AgentHook> hooks
    ) {
        log.info("Configuring ReActAgentService with {} hooks",
                hooks != null ? hooks.size() : 0);

        return new DefaultReActAgentService(
                reasoner,
                actor,
                observer,
                finalizer,
                hooks != null ? hooks : new ArrayList<>()
        );
    }

    // ==================== Workflow Integration ====================

    @Bean
    @ConditionalOnBean({ReActAgentService.class, WorkflowService.class})
    public ReActWorkflowAdapter reActWorkflowAdapter(
            ReActAgentService agentService,
            WorkflowService workflowService
    ) {
        log.info("Configuring ReActWorkflowAdapter");
        return new ReActWorkflowAdapter(agentService, workflowService);
    }
}
