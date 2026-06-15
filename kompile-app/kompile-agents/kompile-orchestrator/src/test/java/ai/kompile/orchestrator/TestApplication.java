/*
 * Copyright 2025 Kompile Inc.
 * Licensed under the Apache License, Version 2.0
 */
package ai.kompile.orchestrator;

import ai.kompile.orchestrator.api.*;
import ai.kompile.orchestrator.config.OrchestratorProperties;
import ai.kompile.orchestrator.model.llm.*;
import ai.kompile.orchestrator.model.state.DefaultState;
import ai.kompile.orchestrator.model.state.StateDefinition;
import ai.kompile.orchestrator.model.workflow.ActionProposal;
import ai.kompile.orchestrator.repository.*;
import ai.kompile.orchestrator.service.impl.*;
import ai.kompile.orchestrator.service.registry.*;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import reactor.core.publisher.Flux;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Spring Boot application for orchestrator tests.
 * Configures all required beans with H2 in-memory database.
 */
@SpringBootApplication(
        exclude = {ai.kompile.orchestrator.config.OrchestratorAutoConfiguration.class}
)
@EntityScan(basePackages = "ai.kompile.orchestrator.model")
@EnableJpaRepositories(basePackages = "ai.kompile.orchestrator.repository")
@ComponentScan(basePackages = "ai.kompile.orchestrator.executor")
@EnableConfigurationProperties(OrchestratorProperties.class)
public class TestApplication {

    @Bean
    public StateRegistry stateRegistry() {
        StateRegistry registry = new StateRegistry();
        List<StateDefinition> defaultStates = Arrays.stream(DefaultState.values())
                .map(DefaultState::toStateDefinition)
                .collect(Collectors.toList());
        registry.registerAll(defaultStates);
        return registry;
    }

    @Bean
    public TaskDefinitionRegistry taskDefinitionRegistry() {
        return new TaskDefinitionRegistry();
    }

    @Bean
    public TaskExecutorRegistry taskExecutorRegistry(List<TaskExecutor> executors) {
        TaskExecutorRegistry registry = new TaskExecutorRegistry();
        for (TaskExecutor executor : executors) {
            registry.register(executor);
        }
        return registry;
    }

    @Bean
    public StateHandlerRegistry stateHandlerRegistry() {
        return new StateHandlerRegistry();
    }

    @Bean
    public LlmProviderRegistry llmProviderRegistry() {
        return new LlmProviderRegistry();
    }

    @Bean
    public StateMachineService stateMachineService(
            StateRegistry stateRegistry,
            StateHandlerRegistry handlerRegistry,
            OrchestratorInstanceRepository instanceRepository,
            ApplicationEventPublisher eventPublisher) {
        return new DefaultStateMachineService(stateRegistry, handlerRegistry,
                instanceRepository, eventPublisher);
    }

    @Bean
    public TaskExecutionService taskExecutionService(
            TaskDefinitionRegistry definitionRegistry,
            TaskExecutorRegistry executorRegistry,
            TaskInstanceRepository instanceRepository,
            TaskDefinitionRepository definitionRepository,
            ApplicationEventPublisher eventPublisher,
            OrchestratorProperties properties) {
        return new DefaultTaskExecutionService(definitionRegistry, executorRegistry,
                instanceRepository, definitionRepository, eventPublisher, properties);
    }

    @Bean
    public LlmIntegrationService llmIntegrationService(
            LlmProviderRegistry providerRegistry,
            LlmSessionRepository sessionRepository,
            LlmTriggerRepository triggerRepository,
            ApplicationEventPublisher eventPublisher,
            OrchestratorProperties properties) {
        return new DefaultLlmIntegrationService(providerRegistry, sessionRepository,
                triggerRepository, eventPublisher, properties);
    }

    /**
     * Wire TaskExecutionService into StateMachineService for onEnter/onExit task hooks.
     */
    @Bean
    public Object stateMachineTaskWiring(StateMachineService stateMachineService,
                                         TaskExecutionService taskExecutionService) {
        if (stateMachineService instanceof DefaultStateMachineService dsm) {
            dsm.setTaskExecutionService(taskExecutionService);
        }
        return new Object();
    }

    @Bean
    public StubLlmProvider stubLlmProvider(LlmProviderRegistry registry) {
        StubLlmProvider provider = new StubLlmProvider();
        registry.register(provider);
        return provider;
    }

    /**
     * A simple in-memory LLM provider for testing.
     */
    public static class StubLlmProvider implements LlmProvider {
        private final Map<Long, LlmSession> sessions = new ConcurrentHashMap<>();
        private final AtomicLong idGen = new AtomicLong(1);
        private String nextResponse = "LLM response: task processed successfully";

        public void setNextResponse(String response) {
            this.nextResponse = response;
        }

        @Override
        public String getId() { return "stub"; }

        @Override
        public String getDisplayName() { return "Stub LLM Provider"; }

        @Override
        public boolean isAvailable() { return true; }

        @Override
        public LlmSession startSession(LlmSessionRequest request) {
            long id = idGen.getAndIncrement();
            LlmSession session = LlmSession.builder()
                    .id(id)
                    .providerId(getId())
                    .providerDisplayName(getDisplayName())
                    .initialPrompt(request.getPrompt())
                    .systemPrompt(request.getSystemPrompt())
                    .orchestratorInstanceId(request.getOrchestratorInstanceId())
                    .workflowId(request.getWorkflowId())
                    .workflowStepId(request.getWorkflowStepId())
                    .taskInstanceId(request.getTaskInstanceId())
                    .triggerId(request.getTriggerId())
                    .build();
            session.markRunning();
            session.markCompleted(nextResponse);
            sessions.put(id, session);
            return session;
        }

        @Override
        public LlmSession sendMessage(Long sessionId, String message) {
            LlmSession session = sessions.get(sessionId);
            if (session != null) session.appendOutput("\n" + nextResponse);
            return session;
        }

        @Override
        public void cancelSession(Long sessionId) {
            LlmSession session = sessions.get(sessionId);
            if (session != null) session.markCancelled();
        }

        @Override
        public boolean isSessionActive(Long sessionId) {
            LlmSession session = sessions.get(sessionId);
            return session != null && session.isActive();
        }

        @Override
        public Flux<String> streamOutput(Long sessionId) {
            return Flux.just(nextResponse);
        }

        @Override
        public ActionProposal parseActionProposal(String llmOutput) { return null; }

        public Map<Long, LlmSession> getSessions() { return sessions; }
    }
}
