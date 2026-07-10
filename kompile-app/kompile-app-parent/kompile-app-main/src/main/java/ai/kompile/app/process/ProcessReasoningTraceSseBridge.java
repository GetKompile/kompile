/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.app.process;

import ai.kompile.app.services.agent.ReasoningTraceStore;
import ai.kompile.process.discovery.mining.trace.ProcessReasoningTraceEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Mirrors mined-process reasoning traces into the chat trace buffer.
 */
@Component
public class ProcessReasoningTraceSseBridge {

    private final ReasoningTraceStore traceStore;

    public ProcessReasoningTraceSseBridge(ReasoningTraceStore traceStore) {
        this.traceStore = traceStore;
    }

    @EventListener
    public void onProcessReasoningTrace(ProcessReasoningTraceEvent event) {
        if (event.getTrace() != null) {
            traceStore.storeTrace(event.getTrace());
        }
    }
}
