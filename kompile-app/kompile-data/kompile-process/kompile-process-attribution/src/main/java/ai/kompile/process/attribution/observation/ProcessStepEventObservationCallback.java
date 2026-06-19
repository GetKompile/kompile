/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.process.attribution.observation;

import ai.kompile.core.events.EventObserver;
import ai.kompile.process.execution.StepExecution;
import ai.kompile.process.execution.StepExecutionStatus;
import ai.kompile.process.execution.WorkflowRun;
import ai.kompile.process.service.ProcessGraphCallback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Observes process step executions as {@code PROCESS_STEP_OCCURRENCE} events so the empirical priors
 * learn each step's real success/occurrence rate from run history. Implements the process engine's
 * existing {@link ProcessGraphCallback} hook ({@code onStepCompleted}/{@code onRunCompleted}); the
 * engine wires a single such callback via its optional setter.
 *
 * <p>The {@link EventObserver} write SPI is optional — when the {@code kompile-event-observation}
 * module is absent it resolves to {@link EventObserver#NO_OP} and this callback is inert.</p>
 */
@Component
public class ProcessStepEventObservationCallback implements ProcessGraphCallback {

    private static final Logger log = LoggerFactory.getLogger(ProcessStepEventObservationCallback.class);

    private final EventObserver eventObserver;

    public ProcessStepEventObservationCallback(@Autowired(required = false) EventObserver eventObserver) {
        this.eventObserver = eventObserver != null ? eventObserver : EventObserver.NO_OP;
    }

    @Override
    public void onStepCompleted(WorkflowRun run, StepExecution stepExecution) {
        if (run == null || stepExecution == null || stepExecution.getStepId() == null || !eventObserver.isEnabled()) {
            return;
        }
        try {
            boolean success = stepExecution.getStatus() == StepExecutionStatus.COMPLETED;
            eventObserver.observeProcessStep(
                    run.getProcessDefinitionId(),
                    stepExecution.getStepId(),
                    stepExecution.getGraphNodeIds(),
                    success);
        } catch (Exception e) {
            log.debug("Process-step event observation failed: {}", e.getMessage());
        }
    }

    @Override
    public void onRunCompleted(WorkflowRun run) {
        // Step-level observations already cover the run; nothing additional needed.
    }
}
