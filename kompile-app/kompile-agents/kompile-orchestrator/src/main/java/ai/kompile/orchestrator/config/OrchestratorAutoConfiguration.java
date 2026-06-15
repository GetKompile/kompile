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
package ai.kompile.orchestrator.config;

import ai.kompile.orchestrator.api.*;
import ai.kompile.orchestrator.model.state.DefaultState;
import ai.kompile.orchestrator.model.state.StateDefinition;
import ai.kompile.orchestrator.repository.*;
import ai.kompile.orchestrator.service.TriggerManager;
import ai.kompile.orchestrator.service.impl.*;
import ai.kompile.orchestrator.service.registry.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;

import jakarta.annotation.PostConstruct;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Auto-configuration for the orchestrator.
 *
 * Note: JPA repositories and entities are configured in PrimaryDataSourceConfig
 * to avoid duplicate bean registration conflicts.
 */
@AutoConfiguration
@EnableConfigurationProperties(OrchestratorProperties.class)
@ComponentScan(basePackages = "ai.kompile.orchestrator")
public class OrchestratorAutoConfiguration {

    // ==================== Registry Beans ====================

    @Bean
    @ConditionalOnMissingBean
    public StateRegistry stateRegistry() {
        StateRegistry registry = new StateRegistry();
        // Register default states
        List<StateDefinition> defaultStates = Arrays.stream(DefaultState.values())
                .map(DefaultState::toStateDefinition)
                .collect(Collectors.toList());
        registry.registerAll(defaultStates);
        return registry;
    }

    @Bean
    @ConditionalOnMissingBean
    public TaskDefinitionRegistry taskDefinitionRegistry() {
        return new TaskDefinitionRegistry();
    }

    @Bean
    @ConditionalOnMissingBean
    public TaskExecutorRegistry taskExecutorRegistry() {
        return new TaskExecutorRegistry();
    }

    @Bean
    @ConditionalOnMissingBean
    public LlmProviderRegistry llmProviderRegistry() {
        return new LlmProviderRegistry();
    }

    @Bean
    @ConditionalOnMissingBean
    public StateHandlerRegistry stateHandlerRegistry() {
        return new StateHandlerRegistry();
    }

    @Bean
    @ConditionalOnMissingBean
    public HookRegistry hookRegistry() {
        return new HookRegistry();
    }

    // ==================== Service Beans ====================

    @Bean
    @ConditionalOnMissingBean
    public StateMachineService stateMachineService(
            StateRegistry stateRegistry,
            StateHandlerRegistry handlerRegistry,
            OrchestratorInstanceRepository instanceRepository,
            ApplicationEventPublisher eventPublisher,
            OrchestratorProperties properties) {
        return new DefaultStateMachineService(stateRegistry, handlerRegistry,
                instanceRepository, eventPublisher);
    }

    @Bean
    @ConditionalOnMissingBean
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
    @ConditionalOnMissingBean
    public WorkflowService workflowService(
            WorkflowRepository workflowRepository,
            WorkflowStepRepository stepRepository,
            LlmIntegrationService llmService,
            TaskExecutionService taskService,
            ApplicationEventPublisher eventPublisher,
            OrchestratorProperties properties) {
        return new DefaultWorkflowService(workflowRepository, stepRepository,
                llmService, taskService, eventPublisher, properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public LlmIntegrationService llmIntegrationService(
            LlmProviderRegistry providerRegistry,
            LlmSessionRepository sessionRepository,
            LlmTriggerRepository triggerRepository,
            ApplicationEventPublisher eventPublisher,
            OrchestratorProperties properties) {
        return new DefaultLlmIntegrationService(providerRegistry, sessionRepository,
                triggerRepository, eventPublisher, properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public OrchestratorService orchestratorService(
            OrchestratorInstanceRepository instanceRepository,
            OrchestratorSnapshotRepository snapshotRepository,
            StateMachineService stateMachineService,
            TaskExecutionService taskExecutionService,
            WorkflowService workflowService,
            LlmIntegrationService llmIntegrationService,
            StateRegistry stateRegistry,
            ApplicationEventPublisher eventPublisher,
            OrchestratorProperties properties) {
        return new DefaultOrchestratorService(instanceRepository, snapshotRepository,
                stateMachineService, taskExecutionService, workflowService,
                llmIntegrationService, stateRegistry, eventPublisher, properties);
    }

    // ==================== Event Service ====================

    @Bean
    @ConditionalOnMissingBean
    public EventBroadcastService eventBroadcastService(OrchestratorProperties properties) {
        return new EventBroadcastService(properties);
    }

    // ==================== Audit Service ====================

    @Bean
    @ConditionalOnMissingBean
    public AuditService auditService(AuditLogRepository auditRepository) {
        return new AuditService(auditRepository);
    }

    // ==================== Trigger Manager ====================

    @Bean
    @ConditionalOnMissingBean
    public TriggerManager triggerManager(
            LlmTriggerRepository triggerRepository,
            LlmIntegrationService llmService) {
        return new TriggerManager(triggerRepository, llmService);
    }

    // ==================== Recovery Service ====================

    @Bean
    @ConditionalOnMissingBean
    public SnapshotService snapshotService(
            OrchestratorSnapshotRepository snapshotRepository,
            OrchestratorInstanceRepository instanceRepository,
            ApplicationEventPublisher eventPublisher,
            OrchestratorProperties properties) {
        return new SnapshotService(snapshotRepository, instanceRepository,
                eventPublisher, properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public RecoveryService recoveryService(
            OrchestratorSnapshotRepository snapshotRepository,
            OrchestratorInstanceRepository instanceRepository,
            StateMachineService stateMachineService,
            TaskInstanceRepository taskInstanceRepository,
            WorkflowRepository workflowRepository,
            ApplicationEventPublisher eventPublisher,
            OrchestratorProperties properties) {
        return new RecoveryService(snapshotRepository, instanceRepository,
                stateMachineService, taskInstanceRepository, workflowRepository,
                eventPublisher, properties);
    }

    // ==================== Component Wiring ====================

    /**
     * Wires optional components into the services that need them.
     * Uses a configuration bean to avoid circular dependencies.
     */
    @Bean
    public OrchestratorComponentWiring orchestratorComponentWiring(
            OrchestratorService orchestratorService,
            StateMachineService stateMachineService,
            TaskExecutionService taskExecutionService,
            TaskExecutorRegistry taskExecutorRegistry,
            @Autowired(required = false) List<TaskExecutor> taskExecutors,
            SnapshotService snapshotService,
            RecoveryService recoveryService,
            HookRegistry hookRegistry,
            TriggerManager triggerManager,
            AuditService auditService,
            TaskInstanceRepository taskInstanceRepository,
            WorkflowRepository workflowRepository) {
        return new OrchestratorComponentWiring(
                orchestratorService, stateMachineService, taskExecutionService,
                taskExecutorRegistry, taskExecutors,
                snapshotService, recoveryService,
                hookRegistry, triggerManager, auditService,
                taskInstanceRepository, workflowRepository);
    }

    /**
     * Helper class to wire optional components together.
     */
    public static class OrchestratorComponentWiring {

        public OrchestratorComponentWiring(
                OrchestratorService orchestratorService,
                StateMachineService stateMachineService,
                TaskExecutionService taskExecutionService,
                TaskExecutorRegistry taskExecutorRegistry,
                List<TaskExecutor> taskExecutors,
                SnapshotService snapshotService,
                RecoveryService recoveryService,
                HookRegistry hookRegistry,
                TriggerManager triggerManager,
                AuditService auditService,
                TaskInstanceRepository taskInstanceRepository,
                WorkflowRepository workflowRepository) {

            // Wire into orchestrator service
            if (orchestratorService instanceof DefaultOrchestratorService defaultService) {
                defaultService.setHookRegistry(hookRegistry);
                defaultService.setTriggerManager(triggerManager);
                defaultService.setSnapshotService(snapshotService);
                defaultService.setRecoveryService(recoveryService);
                defaultService.setAuditService(auditService);
            }

            // Wire task execution service into state machine for onEnter/onExit task hooks
            if (stateMachineService instanceof DefaultStateMachineService defaultStateMachine) {
                defaultStateMachine.setTaskExecutionService(taskExecutionService);
            }

            // Auto-register all TaskExecutor beans into the registry
            if (taskExecutors != null) {
                for (TaskExecutor executor : taskExecutors) {
                    taskExecutorRegistry.register(executor);
                }
            }

            // Wire repositories into snapshot service
            snapshotService.setTaskInstanceRepository(taskInstanceRepository);
            snapshotService.setWorkflowRepository(workflowRepository);

            // Wire services into recovery service
            recoveryService.setSnapshotService(snapshotService);
            recoveryService.setAuditService(auditService);
        }
    }
}
